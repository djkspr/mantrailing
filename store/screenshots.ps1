# Captures d'écran Play Store (9:16, 1080x1920) sur l'émulateur, avec une séance fictive.
# Prérequis : un émulateur lancé, avec accès Internet (tuiles de carte).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot '..\tools\java-home.ps1')
Set-AndroidJavaHome
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Écran 9:16 et barre d'état propre (heure fixe, batterie pleine, pas de notifications).
& $adb shell wm size 1080x1920
& $adb shell settings put global sysui_demo_allowed 1
foreach ($cmd in @('enter', 'clock -e hhmm 1030', 'battery -e level 100 -e plugged false',
                   'network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none', 'notifications -e visible false')) {
    $parts = $cmd -split ' ', 2
    & $adb shell "am broadcast -a com.android.systemui.demo -e command $($parts[0]) $(if ($parts.Count -gt 1) { $parts[1] })" | Out-Null
}
& $adb uninstall app.mantrailing 2>&1 | Out-Null

try {
    & .\gradlew.bat connectedDebugAndroidTest --console=plain `
        "-Pandroid.testInstrumentationRunnerArguments.class=app.mantrailing.ui.StoreScreenshots" `
        "-Pandroid.testInstrumentationRunnerArguments.storeScreenshots=true"
    if ($LASTEXITCODE -ne 0) { throw "Échec des captures (code $LASTEXITCODE)" }
    foreach ($name in @('1-trace-plan-ign', '2-trace-photos-ign')) {
        & $adb pull "/data/local/tmp/store-$name.png" (Join-Path $PSScriptRoot "screenshot-$name.png") | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Capture $name absente : le test n'a pas tourné (voir le rapport androidTests)." }
        & $adb shell rm "/data/local/tmp/store-$name.png"
    }
    Add-Type -AssemblyName System.Drawing
    Get-ChildItem $PSScriptRoot -Filter 'screenshot-*.png' | ForEach-Object {
        $img = [System.Drawing.Image]::FromFile($_.FullName)
        Write-Host ("{0} : {1}x{2}, {3:N0} Ko" -f $_.Name, $img.Width, $img.Height, ($_.Length / 1KB))
        $img.Dispose()
    }
} finally {
    & $adb shell am broadcast -a com.android.systemui.demo -e command exit | Out-Null
    & $adb shell wm size reset
}
