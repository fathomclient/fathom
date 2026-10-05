param(
    [string]$Screenshots = "minecraft/fathom-optimizations/run/screenshots"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Screenshots))
$directories = Get-ChildItem -LiteralPath $root -Directory -Filter "motion-*" | Sort-Object Name
foreach ($directory in $directories) {
    $frames = @(Get-ChildItem -LiteralPath $directory.FullName -File |
        Where-Object { $_.Name -match '^\d{4}-\d{4}ms\.png$' } |
        Sort-Object Name)
    if ($frames.Count -eq 0) {
        continue
    }

    $wanted = [Math]::Min(16, $frames.Count)
    $selected = @()
    for ($index = 0; $index -lt $wanted; $index++) {
        $at = if ($wanted -eq 1) { 0 } else {
            [Math]::Round($index * ($frames.Count - 1) / ($wanted - 1))
        }
        $selected += $frames[$at]
    }

    $columns = 4
    $cellWidth = 320
    $imageHeight = 180
    $labelHeight = 22
    $rows = [Math]::Ceiling($selected.Count / $columns)
    $sheet = New-Object System.Drawing.Bitmap ($columns * $cellWidth), ($rows * ($imageHeight + $labelHeight))
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $graphics.Clear([System.Drawing.Color]::FromArgb(255, 15, 17, 21))
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $font = New-Object System.Drawing.Font "Consolas", 9
    $brush = [System.Drawing.Brushes]::White
    try {
        for ($index = 0; $index -lt $selected.Count; $index++) {
            $column = $index % $columns
            $row = [Math]::Floor($index / $columns)
            $x = $column * $cellWidth
            $y = $row * ($imageHeight + $labelHeight)
            $image = [System.Drawing.Image]::FromFile($selected[$index].FullName)
            try {
                $graphics.DrawImage($image, $x, $y, $cellWidth, $imageHeight)
            } finally {
                $image.Dispose()
            }
            $graphics.DrawString($selected[$index].BaseName, $font, $brush, $x + 5, $y + $imageHeight + 3)
        }
        $output = Join-Path $directory.FullName "contact-sheet.png"
        $sheet.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Host "$($directory.Name): $($frames.Count) frames -> $output"
    } finally {
        $font.Dispose()
        $graphics.Dispose()
        $sheet.Dispose()
    }
}
