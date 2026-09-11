<#
.SYNOPSIS
    Builds the in-game Fathom menu background at native source cadence.

.DESCRIPTION
    Produces a high-quality 1080p sequence and an explicit 720p fallback.
    It never up-converts frame rate: a 30 fps source stays 30 fps and a 60 fps
    source stays 60 fps. Blur and tint remain runtime presentation effects, so
    the source is not irreversibly softened before it reaches the game.
#>
[CmdletBinding()]
param(
    [string] $Source = (Join-Path $env:USERPROFILE 'Downloads\bg_5rain.mp4'),
    [string] $Output,
    [string] $FFmpeg = 'ffmpeg',
    [string] $FFprobe = 'ffprobe',
    [double] $StartSeconds = 20,
    [int] $LoopSeconds = 10
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $PSBoundParameters.ContainsKey('Output') -or [string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot `
        '..\minecraft\fathom-optimizations\src\main\resources\assets\fathom-optimizations\textures\menu\bg_5rain'
}

function Resolve-Binary {
    param([string] $Candidate, [string] $Name)
    $found = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    if (Test-Path -LiteralPath $Candidate) { return (Resolve-Path -LiteralPath $Candidate).Path }
    throw "$Name was not found. Pass -$Name <path>."
}

if (-not (Test-Path -LiteralPath $Source)) {
    throw "Source recording not found: $Source"
}

$ffmpegExe = Resolve-Binary $FFmpeg 'FFmpeg'
$ffprobeExe = Resolve-Binary $FFprobe 'FFprobe'
$target = (New-Item -ItemType Directory -Force -Path $Output).FullName
$high = (New-Item -ItemType Directory -Force -Path (Join-Path $target 'high')).FullName
$low = (New-Item -ItemType Directory -Force -Path (Join-Path $target 'low')).FullName

# Remove only generated video frames, including the legacy flat layout.
Get-ChildItem -LiteralPath $target -File -Filter 'frame_*.jpg' -ErrorAction SilentlyContinue |
    Remove-Item -Force
Get-ChildItem -LiteralPath $high -File -Filter 'frame_*.jpg' -ErrorAction SilentlyContinue |
    Remove-Item -Force
Get-ChildItem -LiteralPath $low -File -Filter 'frame_*.jpg' -ErrorAction SilentlyContinue |
    Remove-Item -Force

$rateText = (& $ffprobeExe -v error -select_streams v:0 `
    -show_entries stream=avg_frame_rate -of default=nokey=1:noprint_wrappers=1 $Source).Trim()
$parts = $rateText.Split('/')
$sourceFps = if ($parts.Count -eq 2 -and [double]$parts[1] -ne 0) {
    [double]$parts[0] / [double]$parts[1]
} else { [double]$rateText }
$fps = [math]::Max(1, [math]::Min(60, [math]::Round($sourceFps)))
$loopFrames = $fps * $LoopSeconds
$crossfadeFrames = [math]::Max(8, [math]::Round($fps * 0.8))
$needed = $loopFrames + $crossfadeFrames
$duration = [math]::Round(($needed + 6) / $fps, 3)

function Write-Sequence {
    param([string] $Destination, [int] $Width, [int] $Height, [int] $Quality)
    $grade = "fps=$fps,scale=$($Width):$($Height):force_original_aspect_ratio=increase:flags=lanczos," +
             "crop=$($Width):$($Height)," +
             'eq=gamma=1.06:saturation=0.98:contrast=1.02:brightness=0.005,' +
             'setpts=PTS-STARTPTS'
    $tailStart = $loopFrames
    $tailEnd = $loopFrames + $crossfadeFrames
    $filter = @(
        "[0:v]$grade,split=3[s1][s2][s3]"
        "[s1]trim=start_frame=$($crossfadeFrames):end_frame=$($loopFrames),setpts=PTS-STARTPTS[body]"
        "[s2]trim=start_frame=$($tailStart):end_frame=$($tailEnd),setpts=PTS-STARTPTS[tail]"
        "[s3]trim=start_frame=0:end_frame=$($crossfadeFrames),setpts=PTS-STARTPTS[head]"
        "[tail][head]blend=all_expr='A*(1-(N/$crossfadeFrames))+B*(N/$crossfadeFrames)'[seam]"
        '[body][seam]concat=n=2:v=1:a=0[out]'
    ) -join ';'
    $pattern = Join-Path $Destination 'frame_%03d.jpg'
    & $ffmpegExe -v error -y -ss $StartSeconds -t $duration -i $Source `
        -filter_complex $filter -map '[out]' -an -frames:v $loopFrames `
        -start_number 0 -q:v $Quality -pix_fmt yuvj420p $pattern
    if ($LASTEXITCODE -ne 0) {
        throw "Main-menu sequence generation failed (exit $LASTEXITCODE)."
    }
    $frames = @(Get-ChildItem -LiteralPath $Destination -File -Filter 'frame_*.jpg')
    if ($frames.Count -ne $loopFrames) {
        throw "Expected $loopFrames frames, generated $($frames.Count) in $Destination."
    }
    return ($frames | Measure-Object -Property Length -Sum).Sum
}

$highBytes = Write-Sequence $high 1920 1080 2
$lowBytes = Write-Sequence $low 1280 720 4
[IO.File]::WriteAllText((Join-Path $target 'video.properties'),
    "fps=$fps`nframes=$loopFrames`n", [Text.Encoding]::ASCII)

Write-Host ('High: {0} frames at 1920x1080, {1} fps ({2:N1} MB).' -f `
    $loopFrames, $fps, ($highBytes / 1MB))
Write-Host ('Low:  {0} frames at 1280x720, {1} fps ({2:N1} MB).' -f `
    $loopFrames, $fps, ($lowBytes / 1MB))
