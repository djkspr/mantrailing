# Publie une version sur Google Play : tests unitaires + tests d'interface, AAB signé, puis envoi sur la piste de test.
# Usage : .\release.ps1                     (un émulateur ou un téléphone doit être branché pour les tests d'interface)
#         .\release.ps1 -SkipDeviceTests
#         .\release.ps1 -Track alpha        (piste Play : internal [défaut], alpha = test fermé, beta, production)
#         .\release.ps1 -NoUpload           (prépare l'AAB sans l'envoyer)
param(
    [switch]$SkipDeviceTests,
    [switch]$NoUpload,
    [string]$Track = 'internal'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not (Test-Path keystore.properties)) {
    throw "keystore.properties absent : copie $env:USERPROFILE\.android-keys\mantrailing-keystore.properties ici (voir README)."
}
if (git status --porcelain) {
    throw 'Des modifications ne sont pas commitées : le versionCode (nombre de commits) ne serait pas unique.'
}

# Clé du compte de service Google Play (voir README) : chemin « playApiKey » de keystore.properties, sinon
# emplacement par défaut. Vérifiée avant les tests pour ne pas les lancer pour rien.
$playKey = (Get-Content keystore.properties | Where-Object { $_ -match '^\s*playApiKey\s*=' } | Select-Object -First 1) -replace '^\s*playApiKey\s*=\s*', ''
if (-not $playKey) { $playKey = "$env:USERPROFILE\.android-keys\mantrailing-play-api.json" }
if (-not $NoUpload -and -not (Test-Path $playKey)) {
    throw "Clé d'API Google Play absente ($playKey) : voir README, ou relancer avec -NoUpload."
}

node --test 'tools/*.test.mjs'
if ($LASTEXITCODE -ne 0) { throw "Tests des outils en échec : rien n'est à envoyer." }

& .\gradlew.bat clean testDebugUnitTest bundleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Échec (code $LASTEXITCODE) : rien n'est à envoyer." }

if (-not $SkipDeviceTests) {
    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    # Tests d'interface sur l'ÉMULATEUR seulement : la désinstallation ci-dessous viderait l'appli d'un vrai téléphone
    # branché en USB (pistes, recherches, chiens). ANDROID_SERIAL limite aussi Gradle à cet appareil.
    $emulator = & $adb devices | Select-String '^(emulator-\d+)\s+device' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
    if (-not $emulator) { throw "Aucun émulateur démarré : lance-le (tests d'interface jamais sur un téléphone branché), ou -SkipDeviceTests." }
    $env:ANDROID_SERIAL = $emulator
    Write-Host "Tests d'interface sur $emulator"
    # Une version signée différemment (release) déjà installée empêcherait l'installation des tests.
    & $adb -s $emulator uninstall app.mantrailing 2>&1 | Out-Null
    # Écran allumé pendant les tests : en veille, les tests d'interface échouent tous.
    & $adb -s $emulator shell svc power stayon true
    & $adb -s $emulator shell input keyevent 224
    & $adb -s $emulator shell wm dismiss-keyguard
    $results = 'app\build\outputs\androidTest-results\connected'
    foreach ($attempt in 1, 2) {
        & .\gradlew.bat connectedDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Tests d'interface en échec (code $LASTEXITCODE) : rien n'est à envoyer." }
        # L'émulateur rate parfois le lancement juste après une installation (« failed to attach ») : on réessaie une fois.
        $notStarted = Get-ChildItem $results -Recurse -Filter 'TEST-*.xml' | Select-String -Quiet 'Instrumentation run failed'
        if (-not $notStarted) { break }
        Write-Host "L'instrumentation n'a pas démarré, nouvel essai..."
    }
}

# Gradle peut finir en succès sans avoir lancé de test : on vérifie les rapports.
function Assert-Tests($dir, $label) {
    $suites = Get-ChildItem $dir -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        $x = [xml](Get-Content $_.FullName)
        if ($x.testsuites) { $x.testsuites.testsuite } else { $x.testsuite }
    }
    $total = ($suites | Measure-Object -Property tests -Sum).Sum
    $bad = ($suites | ForEach-Object { [int]$_.failures + [int]$_.errors } | Measure-Object -Sum).Sum
    if (-not $total -or $bad) { throw "$label : $total tests, $bad en échec. Rien n'est à envoyer." }
    Write-Host "$label : $total tests OK"
}
Assert-Tests 'app\build\test-results\testDebugUnitTest' 'Tests unitaires'
if (-not $SkipDeviceTests) { Assert-Tests 'app\build\outputs\androidTest-results\connected' "Tests d'interface" }

$version = git rev-list --count HEAD
$out = "release\mantrailing-v$version.aab"
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item 'app\build\outputs\bundle\release\app-release.aab' $out -Force

if ($NoUpload) {
    Write-Host "`nPrêt : $out (versionCode $version), non envoyé."
    return
}

# Notes de version : titres des commits depuis le dernier envoi.
$previous = git describe --tags --match 'play-v*' --abbrev=0 2>$null
$range = if ($previous) { "$previous..HEAD" } else { 'HEAD' }
$notes = (git log $range --no-merges --format='- %s' -n 15) -join "`n"

node tools/play-publish.mjs --aab $out --key $playKey --track $Track --notes $notes
if ($LASTEXITCODE -ne 0) { throw "Envoi sur Google Play en échec : l'AAB reste disponible dans $out." }

git tag -f "play-v$version" | Out-Null
Write-Host "`nPublié : versionCode $version sur la piste « $Track » (tag play-v$version)."
