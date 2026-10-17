<#
.SYNOPSIS
    Builds the static Fathom Minecraft main-menu poster from supplied footage.

.DESCRIPTION
    The source is a 1920x1080 recording. Blur, grading and vignette are baked
    once so Minecraft only uploads one texture and draws one full-frame quad.
    The UI remains a separate framebuffer-native render path.

    The frame is bg_5rain at 20 seconds: a rainy jungle cliff and suspension
    bridge with a calm central horizon. Its layered silhouettes stay legible
    after defocus while leaving clear space for the centered menu controls.
#>
[CmdletBinding()]
param(
    [string] $Source = (Join-Path $env:USERPROFILE 'Downloads\bg_5rain.mp4'),
    [string] $Output,
    [string] $FFmpeg = 'ffmpeg',
    [double] $FrameSeconds = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $PSBoundParameters.ContainsKey('Output') -or [string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot `
        '..\minecraft\fathom-optimizations\src\main\resources\assets\fathom-optimizations\textures\menu\main-menu-bg.png'
}

function Resolve-FFmpeg {
    param([string] $Candidate)
    $found = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }

    $packages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path -LiteralPath $packages) {
        $binary = Get-ChildItem -LiteralPath $packages -Recurse -File -Filter 'ffmpeg.exe' `
            -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($binary) { return $binary.FullName }
    }
    throw 'FFmpeg was not found. Install Gyan.FFmpeg or pass -FFmpeg <path>.'
}

if (-not (Test-Path -LiteralPath $Source)) {
    throw "Source recording not found: $Source"
}

$ffmpegExe = Resolve-FFmpeg $FFmpeg
$target = [System.IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($target)) | Out-Null

# Sigma 7 at 1080p softens the rainy vista while retaining the bridge, cliff
# and leaf silhouettes. The source is intentionally lifted before the runtime
# adds its navy/black contrast wash.
$filter = 'scale=1920:1080:flags=lanczos,' +
          'gblur=sigma=7:steps=3,' +
          'eq=gamma=1.45:saturation=0.96:contrast=1.04:brightness=0.035,' +
          'vignette=PI/5'

& $ffmpegExe -v error -y -ss $FrameSeconds -i $Source -frames:v 1 `
    -vf $filter -compression_level 8 $target
if ($LASTEXITCODE -ne 0) {
    throw "Poster generation failed (exit $LASTEXITCODE)."
}

Write-Host "Wrote $target"
