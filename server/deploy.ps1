#Requires -Version 7
<#
.SYNOPSIS
    Déploie l'API Mantrailing sur o2switch, sur le modèle du déploiement des Brasseurs du Pastel (sans rien partager
    avec lui).

.DESCRIPTION
    Par défaut, déploie la DERNIÈRE VERSION TESTÉE, pas le dossier de travail : `npm run test:all` (dans server/) pose
    l'étiquette git « api-tested » quand tous les tests passent ; ce script envoie ce commit depuis une copie séparée
    du dépôt (git worktree ../mantrailing-deploy), sans jamais toucher au dossier de travail.

    Étapes numérotées (heure, durée, récapitulatif final) :
    1. Préparation de la version testée (copie séparée du dépôt) — sauf -Version actuel.
    2. Envoi de l'API par FTP (WinSCP .NET, tools/winscp-automation) : src/, migrations/, seeds/, knexfile.js,
       migrate.js, package.json, package-lock.json. Jamais de secrets (~/.config/mantrailing/.env sur le serveur, posé
       à la main), ni node_modules, test/, scripts/.
    3. Dépendances npm : installées par l'API elle-même si package-lock.json a changé (POST /deploy/npm-install).
    4. Redémarrage (tmp/restart.txt, signal de Passenger), puis attente que /version annonce le commit envoyé.
    5. Migrations (structure) puis seeds (données) de la base avec knex, exécutées par l'API redémarrée
       (POST /deploy/run-migrations).
    Les routes /deploy sont protégées par un secret : MANTRAILING_DEPLOY_SECRET sur ce poste, même valeur que
    DEPLOY_SECRET dans le .env du serveur.

    Variables de ce poste (server/set-deploy-env.ps1) : MANTRAILING_FTP_HOST, MANTRAILING_FTP_USER,
    MANTRAILING_FTP_PASS (compte FTP limité au dossier de l'API) et MANTRAILING_DEPLOY_SECRET.

.EXAMPLE
    .\server\deploy.ps1                    # version testée (étiquette api-tested)
    .\server\deploy.ps1 -Version actuel    # dossier de travail tel quel
    .\server\deploy.ps1 -NoServerUpdate    # envoi seul : ni dépendances, ni redémarrage, ni migrations
#>
param(
    # « tested » (défaut) : le commit marqué « api-tested » par npm run test:all ; « actuel » : le dossier de travail.
    [ValidateSet("tested", "actuel")]
    [string]$Version = "tested",
    # Ne PAS mettre à jour le serveur après l'envoi : vérifie seulement la version en ligne.
    [switch]$NoServerUpdate,
    # Dossier de l'application Node (« Application root », mantrailing/api), vu du compte FTP : le compte FTP dédié
    # s'ouvre sur mantrailing/, d'où « /api ».
    [string]$RemotePath = "/api",
    # Adresse publique de l'API ; par défaut celle de l'appli (gradle.properties, mantrailing.apiUrl).
    [string]$ApiUrl = ""
)

$ErrorActionPreference = 'Stop'
# Sortie des commandes natives (git) lue en UTF-8 : sinon « é » devient « ├® ».
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path $PSScriptRoot -Parent
$worktree = Join-Path (Split-Path $repoRoot -Parent) 'mantrailing-deploy'
$sourceRoot = if ($Version -eq 'tested') { $worktree } else { $repoRoot }
$RemotePath = '/' + $RemotePath.Trim('/')
function Remote([string]$name) { if ($RemotePath -eq '/') { "/$name" } else { "$RemotePath/$name" } }
$appPanel = "cPanel › Setup Node.js App › mantrailing/api"

# ----- Progression : étapes numérotées, heure de début, durée, récapitulatif final -----
$script:DeployStart = Get-Date
$script:StepNumber = 0
$script:StepTotal = 0
$script:CurrentStep = $null
$script:StepLog = New-Object System.Collections.Generic.List[object]
$script:DeployedVersionLabel = 'dossier de travail (-Version actuel)'

function Format-Duration([TimeSpan]$ts) {
    if ($ts.TotalMinutes -ge 1) { return "{0} min {1:00} s" -f [int][Math]::Floor($ts.TotalMinutes), $ts.Seconds }
    return "{0:0.0} s" -f $ts.TotalSeconds
}

function Complete-Step {
    if (-not $script:CurrentStep) { return }
    $duration = (Get-Date) - $script:CurrentStep.Start
    Write-Host ("    ✓ {0} — {1}" -f $script:CurrentStep.Title, (Format-Duration $duration)) -ForegroundColor Green
    $script:StepLog.Add([pscustomobject]@{ Number = $script:CurrentStep.Number; Title = $script:CurrentStep.Title; Duration = $duration })
    $script:CurrentStep = $null
}

# Étape en échec : marquée ✗ (et non ✓) dans le déroulé et le récapitulatif.
function Fail-Step {
    if (-not $script:CurrentStep) { return }
    $duration = (Get-Date) - $script:CurrentStep.Start
    Write-Host ("    ✗ {0} — {1}" -f $script:CurrentStep.Title, (Format-Duration $duration)) -ForegroundColor Red
    $script:StepLog.Add([pscustomobject]@{ Number = $script:CurrentStep.Number; Title = "✗ " + $script:CurrentStep.Title; Duration = $duration })
    $script:CurrentStep = $null
}

function Start-Step([string]$Title) {
    Complete-Step
    $script:StepNumber++
    $total = if ($script:StepTotal -ge $script:StepNumber) { "/$($script:StepTotal)" } else { "" }
    $elapsed = Format-Duration ((Get-Date) - $script:DeployStart)
    Write-Host ""
    Write-Host ("[{0}{1}] {2}  ({3}, +{4})" -f $script:StepNumber, $total, $Title, (Get-Date -Format "HH:mm:ss"), $elapsed) -ForegroundColor Cyan
    $script:CurrentStep = @{ Number = $script:StepNumber; Title = $Title; Start = Get-Date }
}

function Write-DeploySummary([bool]$Success) {
    if ($Success) { Complete-Step } else { Fail-Step }
    Write-Host ""
    Write-Host "──────────── Récapitulatif ────────────" -ForegroundColor Cyan
    Write-Host ("Version : {0}" -f $script:DeployedVersionLabel)
    foreach ($s in $script:StepLog) {
        Write-Host ("  [{0}] {1,-62} {2}" -f $s.Number, $s.Title, (Format-Duration $s.Duration))
    }
    $color = if ($Success) { "Green" } else { "Red" }
    $status = if ($Success) { "terminé" } else { "INTERROMPU" }
    Write-Host ("Déploiement {0} en {1}" -f $status, (Format-Duration ((Get-Date) - $script:DeployStart))) -ForegroundColor $color
}

# ----- Contrôles avant de commencer -----
if (-not $ApiUrl) {
    $line = Get-Content (Join-Path $repoRoot 'gradle.properties') | Where-Object { $_ -match '^\s*mantrailing\.apiUrl\s*=' } | Select-Object -First 1
    $ApiUrl = ($line -replace '^\s*mantrailing\.apiUrl\s*=\s*', '').Trim().TrimEnd('/')
}
if (-not $ApiUrl) { throw "Adresse de l'API inconnue : renseigne mantrailing.apiUrl dans gradle.properties (ou -ApiUrl)." }
# Variables du poste : celles de la fenêtre, sinon celles du profil Windows (enregistrées par set-deploy-env.ps1 après
# l'ouverture de ce terminal, que Windows ne lui transmet pas).
foreach ($name in 'MANTRAILING_FTP_HOST', 'MANTRAILING_FTP_USER', 'MANTRAILING_FTP_PASS', 'MANTRAILING_DEPLOY_SECRET') {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        $saved = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($saved) { Set-Item "env:$name" $saved }
    }
}
$missingFtp = @("MANTRAILING_FTP_HOST", "MANTRAILING_FTP_USER", "MANTRAILING_FTP_PASS") | Where-Object { -not [Environment]::GetEnvironmentVariable($_) }
if ($missingFtp) { throw "Variables absentes : $($missingFtp -join ', '). Lance .\server\set-deploy-env.ps1." }
# Paquet « WinSCP .NET assembly » (build netstandard2.0) extrait dans tools/winscp-automation (non versionné).
$automationRoot = if ($env:WINSCP_AUTOMATION) { $env:WINSCP_AUTOMATION } else { Join-Path $repoRoot 'tools\winscp-automation' }
$assembly = Join-Path $automationRoot 'netstandard2.0\WinSCPnet.dll'
if (-not (Test-Path $assembly)) { throw "WinSCP .NET assembly introuvable ($assembly) : télécharge « WinSCP .NET assembly » sur winscp.net et extrais-le là (ou définis WINSCP_AUTOMATION)." }

# ----- Étapes -----

# Copie séparée du dépôt (git worktree) placée sur le commit « api-tested » : seuls les fichiers versionnés de ce
# commit sont envoyés, quel que soit l'état du dossier de travail.
function Prepare-TestedSource {
    Start-Step "Préparation de la version testée (copie séparée du dépôt)"
    $tested = git -C $repoRoot rev-parse --verify --quiet "refs/tags/api-tested^{commit}"
    if (-not $tested) {
        throw "Aucune version testée : l'étiquette git « api-tested » n'existe pas. Lance « npm run test:all » dans server/ (elle la pose si tout passe), ou déploie le dossier de travail avec -Version actuel."
    }
    $tested = $tested.Trim()
    if (-not (Test-Path (Join-Path $worktree '.git'))) {
        Write-Host "Création de la copie $worktree..." -ForegroundColor DarkGray
        git -C $repoRoot worktree prune
        git -C $repoRoot worktree add --detach $worktree $tested
        if ($LASTEXITCODE -ne 0) { throw "Création de la copie impossible." }
    }
    else {
        Write-Host "Mise à jour de la copie $worktree..." -ForegroundColor DarkGray
        git -C $worktree checkout --detach --force $tested
        if ($LASTEXITCODE -ne 0) { throw "Passage de la copie sur la version testée impossible." }
        # Fichiers non versionnés laissés par une version précédente : retirés pour ne jamais être envoyés.
        git -C $worktree clean -fd
    }
    $subject = (git -C $worktree log -1 --format="%h — %s (%cd)" --date=format:"%d/%m/%Y %H:%M").Trim()
    $script:DeployedVersionLabel = "version testée $subject"
    Write-Host "Version testée : $subject" -ForegroundColor Green
    $ahead = [int](git -C $repoRoot rev-list --count "$tested..HEAD" -- server)
    $dirty = git -C $repoRoot status --porcelain --untracked-files=no -- server
    if ($ahead -gt 0 -or $dirty) {
        Write-Host ("Non déployé (pas encore testé) : {0} commit(s) plus récent(s) dans server/{1}." -f $ahead, $(if ($dirty) { " et des modifications non commitées" } else { "" })) -ForegroundColor DarkGray
    }
}

function Open-Ftp {
    if (-not ([AppDomain]::CurrentDomain.GetAssemblies() | Where-Object { $_.GetName().Name -eq 'WinSCPnet' })) { Add-Type -Path $assembly }
    $options = New-Object WinSCP.SessionOptions -Property @{
        Protocol = [WinSCP.Protocol]::Ftp
        HostName = $env:MANTRAILING_FTP_HOST
        UserName = $env:MANTRAILING_FTP_USER
        Password = $env:MANTRAILING_FTP_PASS
    }
    $session = New-Object WinSCP.Session
    $session.ExecutablePath = Join-Path $automationRoot 'WinSCP.exe'
    $session.Open($options)
    return $session
}

# Envoi de l'API ; src/version.json (commit et date d'envoi) permet de vérifier ce qui tourne réellement.
function Send-Api {
    Start-Step "Envoi de l'API vers o2switch ($($env:MANTRAILING_FTP_HOST)$RemotePath)"
    $serverDir = Join-Path $sourceRoot 'server'
    $commit = (git -C $sourceRoot rev-parse --short HEAD).Trim()
    if ($Version -eq 'actuel' -and (git -C $sourceRoot status --porcelain -- server)) { $commit += '-modifié' }
    $script:DeployedVersion = [ordered]@{ commit = $commit; builtAt = (Get-Date).ToUniversalTime().ToString('o') }
    $versionFile = Join-Path $serverDir 'src\version.json'
    $script:DeployedVersion | ConvertTo-Json | Set-Content -Encoding utf8NoBOM $versionFile
    $session = Open-Ftp
    try {
        $transfer = New-Object WinSCP.TransferOptions
        $transfer.FileMask = '|node_modules/;test/;scripts/;.env;.env.*;*.ps1;README.md;.gitignore;tmp/'
        if ($RemotePath -ne '/' -and -not $session.FileExists($RemotePath)) { $session.CreateDirectory($RemotePath) }
        # Date OU taille : un décalage d'heure entre le serveur FTP et ce poste ne fait pas passer un fichier modifié
        # pour « déjà à jour ».
        $result = $session.SynchronizeDirectories(
            [WinSCP.SynchronizationMode]::Remote, $serverDir, $RemotePath, $false, $false,
            [WinSCP.SynchronizationCriteria]::Either, $transfer)
        $result.Check()
        Write-Host ("{0} fichier(s) envoyé(s)." -f $result.Uploads.Count) -ForegroundColor DarkGray
        $result.Uploads | ForEach-Object { Write-Host "  - $($_.FileName.Substring($serverDir.Length + 1))" -ForegroundColor DarkGray }
    }
    finally {
        $session.Dispose()
        Remove-Item $versionFile -ErrorAction SilentlyContinue
    }
}

function Get-DeployHeaders {
    if (-not $env:MANTRAILING_DEPLOY_SECRET) { return $null }
    return @{ 'x-deploy-secret' = $env:MANTRAILING_DEPLOY_SECRET }
}

# Dépendances npm : installées par l'API (encore l'ancienne version, déjà en mémoire) si package-lock.json a changé,
# AVANT le redémarrage (sinon un module manquant empêcherait la nouvelle version de démarrer). $false : échec.
function Update-ServerDependencies {
    Start-Step "Dépendances npm sur le serveur (si package-lock.json a changé)"
    $headers = Get-DeployHeaders
    try {
        $start = Invoke-RestMethod -Uri "$ApiUrl/deploy/npm-install" -Method Post -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec 30
    }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 404 -or -not $code) {
            # Premier déploiement (l'API ne tourne pas encore) ou API sans DEPLOY_SECRET dans son .env.
            Write-Host "⚠ L'API en ligne ne peut pas installer ses dépendances (pas encore démarrée, ou DEPLOY_SECRET absent de son .env) : pour cette fois, clique « Run NPM Install » dans $appPanel, puis réponds ici." -ForegroundColor Yellow
            Read-Host "Entrée une fois « Run NPM Install » terminé" | Out-Null
            return $true
        }
        if ($code -eq 403) {
            Write-Host "✗ Installation refusée (HTTP 403) : MANTRAILING_DEPLOY_SECRET (ce poste) et DEPLOY_SECRET (serveur) diffèrent." -ForegroundColor Red
            return $false
        }
        if ($code -ge 500) {
            Write-Host "✗ L'API ne répond pas (erreur $code) : dans $appPanel, clique « Run NPM Install » puis « Restart », et relance le déploiement." -ForegroundColor Red
            return $false
        }
        Write-Host "⚠ Installation des dépendances impossible à lancer ($_)." -ForegroundColor Yellow
        return $true
    }
    if ($start.status -eq 'up-to-date') {
        Write-Host "Dépendances déjà à jour (package-lock.json inchangé)." -ForegroundColor DarkGray
        return $true
    }
    Write-Host "npm install lancé sur le serveur..." -ForegroundColor Yellow
    $deadline = (Get-Date).AddMinutes(10)
    do {
        Start-Sleep -Seconds 5
        try { $state = Invoke-RestMethod -Uri "$ApiUrl/deploy/npm-install" -Headers $headers -TimeoutSec 15 } catch { $state = $null }
        if ($state) { Write-Host "  ... $($state.state)" -ForegroundColor DarkGray }
    } while ((-not $state -or $state.state -eq 'running') -and (Get-Date) -lt $deadline)
    if ($state.state -eq 'done') {
        Write-Host "Dépendances installées." -ForegroundColor Green
        return $true
    }
    Write-Host "✗ npm install a échoué sur le serveur (code $($state.exitCode)) :" -ForegroundColor Red
    if ($state.output) { Write-Host $state.output -ForegroundColor DarkGray }
    return $false
}

# Attend que /version annonce le commit envoyé. $true : la bonne version tourne.
function Wait-ApiVersion([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { $running = Invoke-RestMethod -Uri "$ApiUrl/version" -TimeoutSec 15 } catch { $running = $null }
        $ok = $running -and $running.commit -eq $script:DeployedVersion.commit -and $running.builtAt -eq $script:DeployedVersion.builtAt
        if (-not $ok) { Start-Sleep -Seconds 3 }
    } while (-not $ok -and (Get-Date) -lt $deadline)
    if ($ok) { Write-Host "L'API tourne avec la version envoyée ($($script:DeployedVersion.commit)) — démarrée le $($running.startedAt)." -ForegroundColor Green }
    return $ok
}

# Redémarrage : dépose tmp/restart.txt (Passenger redémarre l'appli à la requête suivante).
function Restart-Api {
    Start-Step "Redémarrage de l'API et vérification de la version en ligne"
    $local = Join-Path $env:TEMP 'mantrailing-restart.txt'
    "redémarrage demandé par deploy.ps1 le $(Get-Date -Format o)" | Out-File -FilePath $local -Encoding ascii
    $session = Open-Ftp
    try {
        if (-not $session.FileExists((Remote 'tmp'))) { $session.CreateDirectory((Remote 'tmp')) }
        $session.PutFiles($local, (Remote 'tmp/restart.txt')).Check()
    }
    finally {
        $session.Dispose()
        Remove-Item $local -ErrorAction SilentlyContinue
    }
    return (Wait-ApiVersion 120)
}

# Migrations (structure) et seeds (données) : exécutés par l'API redémarrée, donc avec le nouveau code.
function Invoke-ServerMigrations {
    Start-Step "Migrations de la base (structure et données)"
    try {
        $result = Invoke-RestMethod -Uri "$ApiUrl/deploy/run-migrations" -Method Post -Headers (Get-DeployHeaders) -TimeoutSec 300
        $files = @($result.migrations.files)
        if ($files.Count -gt 0) {
            Write-Host "$($files.Count) migration(s) exécutée(s) :" -ForegroundColor Green
            $files | ForEach-Object { Write-Host "  - $_" -ForegroundColor DarkGray }
        }
        else {
            Write-Host "Aucune migration en attente." -ForegroundColor DarkGray
        }
        Write-Host ("Seeds : {0}" -f (@($result.seeds) -join ', ')) -ForegroundColor DarkGray
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -in 403, 404) {
            throw "Migrations refusées par le serveur (HTTP $status) : vérifie MANTRAILING_DEPLOY_SECRET (ce poste) et DEPLOY_SECRET (~/.config/mantrailing/.env), puis relance."
        }
        # Erreur d'une migration : le message de la base, lisible (et non le JSON brut).
        $message = "$_"
        try { $message = ($_.ErrorDetails.Message | ConvertFrom-Json).error } catch { }
        throw "Échec d'une migration : $message. Les migrations suivantes n'ont pas été appliquées ; corrige-la, refais passer les tests (npm run test:all), puis relance."
    }
}

# ----- Déroulé -----
$script:StepTotal = 1 + $(if ($Version -eq 'tested') { 1 } else { 0 }) + $(if ($NoServerUpdate) { 1 } else { 3 })
Write-Host ("Déploiement de l'API Mantrailing ($ApiUrl) — version : {0}" -f $(if ($Version -eq 'tested') { 'testée (étiquette git api-tested)' } else { 'dossier de travail' })) -ForegroundColor Cyan
$deployOk = $false
try {
    if ($Version -eq 'tested') { Prepare-TestedSource }
    elseif (git -C $repoRoot status --porcelain -- server) {
        Write-Host "⚠ Modifications non commitées dans server/ : elles partent telles quelles (-Version actuel)." -ForegroundColor Yellow
    }
    Send-Api
    if ($NoServerUpdate) {
        Start-Step "Vérification de la version en ligne (sans redémarrage)"
        if (-not (Wait-ApiVersion 10)) {
            Write-Host "⚠ L'API tourne encore avec une autre version : redémarre-la ($appPanel › Restart), puis lance les migrations (déploiement sans -NoServerUpdate)." -ForegroundColor Yellow
        }
    }
    else {
        if (-not (Get-DeployHeaders)) {
            throw "MANTRAILING_DEPLOY_SECRET non défini sur ce poste : lance .\server\set-deploy-env.ps1 (même valeur que DEPLOY_SECRET dans le .env du serveur)."
        }
        if (-not (Update-ServerDependencies)) {
            throw "Redémarrage annulé : l'API garde sa version actuelle, qui tourne encore. Corrige l'installation ($appPanel, « Run NPM Install ») puis relance."
        }
        if (-not (Restart-Api)) {
            throw "L'API ne tourne pas avec la version envoyée ($($script:DeployedVersion.commit)) : vérifie $appPanel (Run NPM Install, Restart, journal stderr.log) et ~/.config/mantrailing/.env, puis $ApiUrl/version. Migrations non lancées."
        }
        Invoke-ServerMigrations
    }
    $deployOk = $true
}
catch {
    Write-Host ""
    Write-Host "✗ $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    Write-DeploySummary $deployOk
}
if ($deployOk) { Write-Host "🎉 Déploiement terminé !" -ForegroundColor Green } else { exit 1 }
