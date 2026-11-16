# Génère les images Play Store (PNG) à partir des SVG, avec Edge en mode headless.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP 'mantrailing-store-render'

foreach ($img in @(
    @{ svg = 'icon.svg'; png = 'icon-512.png'; w = 512; h = 512 },
    @{ svg = 'feature-graphic.svg'; png = 'feature-graphic-1024x500.png'; w = 1024; h = 500 }
)) {
    $page = Join-Path $env:TEMP "render-$($img.svg).html"
    $svg = Get-Content $img.svg -Raw
    Set-Content $page "<!doctype html><html><body style='margin:0;overflow:hidden'>$svg</body></html>" -Encoding utf8
    $out = Join-Path $PSScriptRoot $img.png
    Start-Process $edge -Wait -ArgumentList '--headless=new', '--disable-gpu', '--hide-scrollbars',
        "--window-size=$($img.w),$($img.h)", "--screenshot=$out", "--user-data-dir=$profile", "file:///$($page -replace '\\','/')"
    Remove-Item $page
    Add-Type -AssemblyName System.Drawing
    $bmp = [System.Drawing.Image]::FromFile($out)
    Write-Host ("{0} : {1}x{2}, {3:N0} Ko" -f $img.png, $bmp.Width, $bmp.Height, ((Get-Item $out).Length / 1KB))
    $bmp.Dispose()
}
