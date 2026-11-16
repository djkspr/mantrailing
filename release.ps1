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

$tasks = @('clean', 'testDebugUnitTest')
if (-not $SkipDeviceTests) {
    # Une version signée différemment (release) déjà installée empêcherait l'installation des tests.
    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    & $adb uninstall app.mantrailing 2>&1 | Out-Null
    $tasks += 'connectedDebugAndroidTest'
}
$tasks += 'bundleRelease'

& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Échec (code $LASTEXITCODE) : rien n'est à envoyer." }

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

$aab = 'app\build\outputs\bundle\release\app-release.aab'
$version = git rev-list --count HEAD
$out = "release\mantrailing-v$version.aab"
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item $aab $out -Force
git tag -f "play-v$version" | Out-Null
Write-Host "`nPrêt : $out (versionCode $version, tag play-v$version)"
Write-Host 'À envoyer dans Play Console > Tester et publier > Tests internes > Créer une release.'
