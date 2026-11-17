#Requires -Version 7
<#
.SYNOPSIS
    Envoie l'API Mantrailing sur o2switch (FTP), la redémarre et vérifie la version en ligne.

.DESCRIPTION
    1. Tests de l'API contre la base locale (npm run db:local), sauf -SkipTests.
    2. src/version.json : commit déployé, renvoyé par GET /version.
    3. Envoi par FTP (WinSCP .NET, le même paquet que pour Les Brasseurs du Pastel) : src/, package.json,
       package-lock.json. Jamais .env, node_modules, test/ ni scripts/.
    4. Redémarrage : tmp/restart.txt (signal de Passenger).
    5. GET <adresse>/version doit renvoyer le commit envoyé.

    Identifiants FTP : variables FTP_USER et FTP_PASS (les mêmes que pour l'autre projet si c'est le même
    compte o2switch). Hôte : MANTRAILING_FTP_HOST, sinon ftp.lesbrasseursdupastel.fr.

.EXAMPLE
    .\server\deploy.ps1
    .\server\deploy.ps1 -SkipTests
#>
param(
    # Dossier de l'application Node sur o2switch (« Application root » du panneau Setup Node.js App).
    [string]$RemotePath = "/mantrailing-api",
    # Adresse publique de l'API ; par défaut celle de l'appli (gradle.properties, mantrailing.apiUrl).
    [string]$ApiUrl = "",
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$serverDir = $PSScriptRoot
$repoRoot = Split-Path $serverDir -Parent

if (-not $ApiUrl) {
    $line = Get-Content (Join-Path $repoRoot 'gradle.properties') | Where-Object { $_ -match '^\s*mantrailing\.apiUrl\s*=' } | Select-Object -First 1
    $ApiUrl = ($line -replace '^\s*mantrailing\.apiUrl\s*=\s*', '').Trim().TrimEnd('/')
}
if (-not $ApiUrl) { throw "Adresse de l'API inconnue : renseigne mantrailing.apiUrl dans gradle.properties (ou -ApiUrl)." }
if (-not $env:FTP_USER -or -not $env:FTP_PASS) { throw "Variables FTP_USER et FTP_PASS absentes (compte FTP o2switch)." }
$ftpHost = if ($env:MANTRAILING_FTP_HOST) { $env:MANTRAILING_FTP_HOST } else { 'ftp.lesbrasseursdupastel.fr' }

# Paquet « WinSCP .NET assembly » (build netstandard2.0), déjà extrait pour Les Brasseurs du Pastel.
$automationRoot = if ($env:WINSCP_AUTOMATION) { $env:WINSCP_AUTOMATION } else {
    Join-Path $env:USERPROFILE 'source\lesbrasseursdupastel\winscp-automation'
}
$assembly = Join-Path $automationRoot 'netstandard2.0\WinSCPnet.dll'
if (-not (Test-Path $assembly)) { throw "WinSCP .NET assembly introuvable ($assembly) : définis WINSCP_AUTOMATION." }

Push-Location $serverDir
try {
    if (-not $SkipTests) {
        Write-Host "1/5 Tests de l'API (base locale)" -ForegroundColor Cyan
        node scripts/local-db.js
        npm test
        if ($LASTEXITCODE -ne 0) { throw "Tests en échec : rien n'est envoyé." }
    }

    Write-Host "2/5 Version déployée" -ForegroundColor Cyan
    $commit = (git -C $repoRoot rev-parse --short HEAD).Trim()
    if (git -C $repoRoot status --porcelain -- server) { $commit += '-modifié' }
    $version = [ordered]@{ commit = $commit; builtAt = (Get-Date).ToUniversalTime().ToString('o') }
    $version | ConvertTo-Json | Set-Content -Encoding utf8NoBOM (Join-Path $serverDir 'src\version.json')

    Write-Host "3/5 Envoi vers o2switch ($ftpHost$RemotePath)" -ForegroundColor Cyan
    Add-Type -Path $assembly
    $options = New-Object WinSCP.SessionOptions -Property @{
        Protocol = [WinSCP.Protocol]::Ftp
        HostName = $ftpHost
        UserName = $env:FTP_USER
        Password = $env:FTP_PASS
    }
    $session = New-Object WinSCP.Session
    $session.ExecutablePath = Join-Path $automationRoot 'WinSCP.exe'
    try {
        $session.Open($options)
        $transfer = New-Object WinSCP.TransferOptions
        $transfer.FileMask = '|node_modules/;test/;scripts/;.env;.env.*;deploy.ps1;README.md;.gitignore;tmp/'
        if (-not $session.FileExists($RemotePath)) { $session.CreateDirectory($RemotePath) }
        $result = $session.SynchronizeDirectories(
            [WinSCP.SynchronizationMode]::Remote, $serverDir, $RemotePath, $false, $false,
            [WinSCP.SynchronizationCriteria]::Either, $transfer)
        $result.Check()
        Write-Host ("   {0} fichier(s) envoyé(s)" -f $result.Uploads.Count) -ForegroundColor DarkGray
        $lockChanged = $result.Uploads | Where-Object { $_.FileName -like '*package-lock.json' -or $_.FileName -like '*package.json' }

        Write-Host "4/5 Redémarrage (Passenger)" -ForegroundColor Cyan
        $restart = Join-Path $env:TEMP 'mantrailing-restart.txt'
        Set-Content $restart (Get-Date -Format o)
        if (-not $session.FileExists("$RemotePath/tmp")) { $session.CreateDirectory("$RemotePath/tmp") }
        $session.PutFiles($restart, "$RemotePath/tmp/restart.txt").Check()
    }
    finally {
        $session.Dispose()
    }
    if ($lockChanged) {
        Write-Host "   Dépendances modifiées : dans cPanel › Setup Node.js App › mantrailing-api, clique « Run NPM Install » puis « Restart »." -ForegroundColor Yellow
    }

    Write-Host "5/5 Vérification de $ApiUrl/version" -ForegroundColor Cyan
    $ok = $false
    for ($i = 0; $i -lt 10 -and -not $ok; $i++) {
        try {
            $running = Invoke-RestMethod -Uri "$ApiUrl/version" -TimeoutSec 20
            if ($running.commit -eq $version.commit -and $running.builtAt -eq $version.builtAt) { $ok = $true; break }
        }
        catch { $running = $null }
        Start-Sleep 3
    }
    if ($ok) {
        Write-Host "API en ligne avec la version envoyée ($($version.commit))." -ForegroundColor Green
    }
    else {
        Write-Host "⚠ L'API ne répond pas encore avec la version envoyée ($($version.commit)). Vérifie le panneau Setup Node.js App (Restart, Run NPM Install, variables d'environnement) puis $ApiUrl/version." -ForegroundColor Yellow
        exit 1
    }
}
finally {
    Remove-Item (Join-Path $serverDir 'src\version.json') -ErrorAction SilentlyContinue
    Pop-Location
}
