#Requires -Version 7
<#
.SYNOPSIS
    Enregistre une fois pour toutes, dans le profil Windows de l'utilisateur, les identifiants FTP du
    déploiement de l'API Mantrailing (variables MANTRAILING_FTP_HOST, MANTRAILING_FTP_USER, MANTRAILING_FTP_PASS).

.DESCRIPTION
    Les valeurs sont demandées à l'écran (mot de passe masqué) : rien n'est écrit dans le dépôt.
    Elles servent aux prochaines fenêtres PowerShell ; celle-ci les reçoit aussi tout de suite.
    Relancer le script pour changer une valeur (Entrée garde la valeur actuelle).

.EXAMPLE
    .\server\set-deploy-env.ps1
#>

function Ask([string]$name, [string]$label, [string]$default, [switch]$Secret) {
    $current = [Environment]::GetEnvironmentVariable($name, 'User')
    $shown = if ($Secret -and $current) { '(déjà défini)' } elseif ($current) { $current } else { $default }
    if ($Secret) {
        $secure = Read-Host "$label [$shown]" -AsSecureString
        $value = [Runtime.InteropServices.Marshal]::PtrToStringUni([Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure))
    }
    else {
        $value = Read-Host "$label [$shown]"
    }
    if (-not $value) { $value = if ($current) { $current } else { $default } }
    if (-not $value) { throw "$name est obligatoire." }
    [Environment]::SetEnvironmentVariable($name, $value, 'User')
    Set-Item "env:$name" $value
}

Write-Host "Identifiants FTP du déploiement de l'API Mantrailing (compte FTP dédié, limité au dossier de l'API)." -ForegroundColor Cyan
Ask 'MANTRAILING_FTP_HOST' 'Serveur FTP' 'ftp.lesbrasseursdupastel.fr'
Ask 'MANTRAILING_FTP_USER' 'Utilisateur FTP (ex. mantrailing@lesbrasseursdupastel.fr)' ''
Ask 'MANTRAILING_FTP_PASS' 'Mot de passe FTP' '' -Secret

# Secret des routes de déploiement (installation npm, migrations) : même valeur ici et dans le .env du serveur.
Write-Host ""
Write-Host "Secret de déploiement (installation des dépendances et migrations par l'API)." -ForegroundColor Cyan
$current = [Environment]::GetEnvironmentVariable('MANTRAILING_DEPLOY_SECRET', 'User')
$answer = Read-Host $(if ($current) { "Déjà défini : Entrée pour le garder, « nouveau » pour en générer un autre" } else { "Entrée pour en générer un" })
if (-not $current -or $answer -eq 'nouveau') {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    [Environment]::SetEnvironmentVariable('MANTRAILING_DEPLOY_SECRET', $secret, 'User')
    $env:MANTRAILING_DEPLOY_SECRET = $secret
    Write-Host ""
    Write-Host "Ajoute (ou remplace) cette ligne dans /home/gepi9588/.config/mantrailing/.env sur o2switch, puis « Restart » :" -ForegroundColor Yellow
    Write-Host "DEPLOY_SECRET=$secret"
    try { "DEPLOY_SECRET=$secret" | Set-Clipboard; Write-Host "(ligne copiée dans le presse-papiers)" -ForegroundColor DarkGray } catch { }
}
Write-Host ""
Write-Host "Enregistré dans le profil Windows. Les nouvelles fenêtres PowerShell (et VS Code relancé) les verront." -ForegroundColor Green
