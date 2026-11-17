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
Write-Host "Enregistré dans le profil Windows. Les nouvelles fenêtres PowerShell (et VS Code relancé) les verront." -ForegroundColor Green
