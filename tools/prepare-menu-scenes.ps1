<#
.SYNOPSIS
    Bakes the in-game menu's selectable scenes from the source recordings.

.DESCRIPTION
    The launcher offers a background picker. This produces the same list for the
    menu inside the game, as one still per scene.

    WHY STILLS, AND NOT THE CLIPS

    The animated menu background is a preprocessed frame sequence, because
    Minecraft 1.8.9 has no video decoder: `bg_5rain` alone is 31 MB of JPEGs in
    two quality tiers. Fourteen of those would be most of a gigabyte in the jar
    for something that plays behind a menu nobody sits on for long. So one scene
    animates -- the one the menu has always used -- and the rest are stills,
    about 250 KB each, graded to sit under the same interface.

    THE GRADE

    Each clip's own gamma and saturation, from `tools/prepare-background-video.ps1`,
    then the poster treatment from `tools/prepare-main-menu-poster.ps1`: a sigma-7
    blur, a lift, and a vignette. The blur is heavier than the launcher's because
    this sits still behind type rather than moving behind it, and a sharp
    photograph under a menu competes with the menu.

.PARAMETER Source
    Directory holding the source recordings. Defaults to the user's Downloads.

.PARAMETER Only
    Regenerate just these scenes, e.g. -Only bg_1,bg_6clear. Default: all.
#>
[CmdletBinding()]
param(
    [string]   $Source = (Join-Path $env:USERPROFILE 'Downloads'),
    [string[]] $Only,
    [string]   $FFmpeg = 'ffmpeg'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'minecraft\fathom-optimizations\src\main\resources\assets\fathom-optimizations\textures\menu\scenes'

# Gamma and saturation are each clip's own, so a scene looks here the way it
# looks in the launcher. Start is a second with something in frame.
$Scenes = @(
    [pscustomobject]@{ Id = 'bg_1';                   From = 'bg_1.mp4';                   Start = 15; Gamma = 0.69; Saturation = 0.92 }
    [pscustomobject]@{ Id = 'bg_2';                   From = 'bg_2.mp4';                   Start = 30; Gamma = 1.10; Saturation = 0.95 }
    [pscustomobject]@{ Id = 'bg_3';                   From = 'bg_3.mp4';                   Start = 15; Gamma = 1.22; Saturation = 0.98 }
    [pscustomobject]@{ Id = 'bg_4';                   From = 'bg_4.mp4';                   Start = 10; Gamma = 0.51; Saturation = 0.90 }
    [pscustomobject]@{ Id = 'bg_5rain';               From = 'bg_5rain.mp4';               Start = 20; Gamma = 1.18; Saturation = 0.96 }
    [pscustomobject]@{ Id = 'bg_5noraintickfrozen';   From = 'bg_5noraintickfrozen.mp4';   Start = 6;  Gamma = 0.86; Saturation = 0.92 }
    [pscustomobject]@{ Id = 'bg_5noraintickunfrozen'; From = 'bg_5noraintickunfrozen.mp4'; Start = 6;  Gamma = 0.88; Saturation = 0.94 }
    [pscustomobject]@{ Id = 'bg_6midnight';           From = 'bg_6midnight.mp4';           Start = 2;  Gamma = 1.12; Saturation = 0.96 }
    [pscustomobject]@{ Id = 'bg_6clear';              From = 'bg_6clear.mp4';              Start = 2;  Gamma = 0.84; Saturation = 0.92 }
    # The screenshot pulls are already cropped stills in the launcher's assets;
    # the frame numbers behind them are recorded in docs/backgrounds.md.
    [pscustomobject]@{ Id = 'bg_6pull_city-overlook';  Still = 'bg_6pull_city-overlook.jpg';  Gamma = 1.04; Saturation = 0.94 }
    [pscustomobject]@{ Id = 'bg_6pull_moon-tower';     Still = 'bg_6pull_moon-tower.jpg';     Gamma = 1.16; Saturation = 0.94 }
    [pscustomobject]@{ Id = 'bg_6pull_red-tower';      Still = 'bg_6pull_red-tower.jpg';      Gamma = 1.08; Saturation = 0.94 }
    [pscustomobject]@{ Id = 'bg_6pull_storm-panorama'; Still = 'bg_6pull_storm-panorama.jpg'; Gamma = 1.10; Saturation = 0.94 }
    [pscustomobject]@{ Id = 'bg_6pull_neon-grid';      Still = 'bg_6pull_neon-grid.jpg';      Gamma = 1.02; Saturation = 0.96 }
)

function Resolve-FFmpeg {
    param([string] $Candidate)
    $found = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $winget) {
        $probe = Get-ChildItem -Path $winget -Recurse -Filter 'ffmpeg.exe' -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($probe) { return $probe.FullName }
    }
    throw "ffmpeg not found. Install it, or pass -FFmpeg with a path."
}

$ffmpegExe = Resolve-FFmpeg $FFmpeg
New-Item -ItemType Directory -Force -Path $output | Out-Null

$selected = if ($Only) { $Scenes | Where-Object { $Only -contains $_.Id } } else { $Scenes }
if (-not $selected) { throw "No matching scenes for -Only $($Only -join ',')." }

# Where the launcher keeps the already-cropped stills.
$launcherBackgrounds = Join-Path $root 'desktop\public\backgrounds'

foreach ($scene in $selected) {
    $target = Join-Path $output "$($scene.Id).jpg"
    $filter = "scale=1920:1080:flags=lanczos," +
              "gblur=sigma=7:steps=3," +
              ("eq=gamma={0}:saturation={1}:contrast=1.04:brightness=0.035," -f ($scene.Gamma * 1.23), $scene.Saturation) +
              "vignette=PI/5"

    if ($scene.Still) {
        $input = Join-Path $launcherBackgrounds $scene.Still
        if (-not (Test-Path -LiteralPath $input)) { throw "Missing still: $input" }
        & $ffmpegExe -v error -y -i $input -vf $filter -q:v 3 $target
    } else {
        $input = Join-Path $Source $scene.From
        if (-not (Test-Path -LiteralPath $input)) { throw "Missing recording: $input" }
        & $ffmpegExe -v error -y -ss $scene.Start -i $input -frames:v 1 -vf $filter -q:v 3 $target
    }
    if ($LASTEXITCODE -ne 0) { throw "Scene generation failed for $($scene.Id) (exit $LASTEXITCODE)." }

    $size = [math]::Round((Get-Item -LiteralPath $target).Length / 1KB)
    Write-Host ("{0,-26} {1,5} KB" -f $scene.Id, $size)
}

Write-Host ""
Write-Host "Wrote $($selected.Count) scene(s) to $output"
Write-Host "The picker's list lives in MenuScenes.java and must name these ids."
