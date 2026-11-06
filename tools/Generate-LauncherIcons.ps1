# Generate density assets from the selected C illustration, without repainting it.
# Windows: powershell -File tools/Generate-LauncherIcons.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path $PSScriptRoot -Parent
$resRoot = Join-Path $projectRoot 'app/src/main/res'
$source = [System.Drawing.Image]::FromFile((Join-Path $projectRoot 'docs/assets/raidian-icon-source.png'))
function Write-Icon([string]$path, [int]$size, [int]$inset, [bool]$round) {
    $bitmap = [System.Drawing.Bitmap]::new($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $clip = $null
    try {
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        if ($round) {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            $clip = [System.Drawing.Drawing2D.GraphicsPath]::new()
            $clip.AddEllipse(0, 0, $size, $size)
            $graphics.SetClip($clip)
        } else {
            $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#C6F8F0'))
        }
        $graphics.DrawImage($source, $inset, $inset, ($size - 2 * $inset), ($size - 2 * $inset))
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        if ($clip) { $clip.Dispose() }
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}
try {
    # Adaptive layers are 108 dp; artwork occupies the central 72 dp.
    # Launcher applies its own mask; keep this layer square.
    $artDir = Join-Path $resRoot 'drawable-nodpi'
    New-Item -ItemType Directory -Force $artDir | Out-Null
    Write-Icon (Join-Path $artDir 'raidian_launcher_art.png') 432 72 $false
    foreach ($entry in @{mdpi=48; hdpi=72; xhdpi=96; xxhdpi=144; xxxhdpi=192}.GetEnumerator()) {
        $dir = Join-Path $resRoot ('mipmap-' + $entry.Key)
        New-Item -ItemType Directory -Force $dir | Out-Null
        Write-Icon (Join-Path $dir 'ic_launcher.png') $entry.Value 0 $false
        Write-Icon (Join-Path $dir 'ic_launcher_round.png') $entry.Value 0 $true
    }
    Write-Icon (Join-Path $projectRoot 'docs/assets/raidian-icon.png') 256 0 $false
} finally {
    $source.Dispose()
}
