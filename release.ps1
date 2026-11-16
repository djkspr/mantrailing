# Prépare un envoi Play Store : tests unitaires + tests d'interface, puis AAB signé.
# Usage : .\release.ps1            (un émulateur ou un téléphone doit être branché pour les tests d'interface)
#         .\release.ps1 -SkipDeviceTests
param([switch]$SkipDeviceTests)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not (Test-Path keystore.properties)) {
    throw "keystore.properties absent : copie $env:USERPROFILE\.android-keys\mantrailing-keystore.properties ici (voir README)."
}
if (git status --porcelain) {
    throw 'Des modifications ne sont pas commitées : le versionCode (nombre de commits) ne serait pas unique.'
}

$tasks = @('testDebugUnitTest')
if (-not $SkipDeviceTests) { $tasks += 'connectedDebugAndroidTest' }
$tasks += 'bundleRelease'

& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Échec (code $LASTEXITCODE) : rien n'est à envoyer." }

$aab = 'app\build\outputs\bundle\release\app-release.aab'
$version = git rev-list --count HEAD
$out = "release\mantrailing-v$version.aab"
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item $aab $out -Force
git tag -f "play-v$version" | Out-Null
Write-Host "`nPrêt : $out (versionCode $version, tag play-v$version)"
Write-Host 'À envoyer dans Play Console > Tester et publier > Tests internes > Créer une release.'
