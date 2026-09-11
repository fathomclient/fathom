<#
.SYNOPSIS
    Turns the source gameplay recordings into Fathom's launcher-background assets.

.DESCRIPTION
    Fathom draws full-window gameplay footage behind the whole shell. The source
    recordings are 1920x1080 30fps H.264 files of 50-63 seconds, 7-20 Mbit/s.
    Playing those directly would mean shipping a video decoder, so this script
    converts each one into a short, seamless, already-defocused JPEG frame
    sequence that the launcher can display with no runtime image processing at
    all.

    Every visual decision is baked in here rather than performed per frame at
    runtime:

      resolution   1280x720. The renderer scales this to the window on the GPU,
                   which costs nothing. It is deliberately NOT smaller: the
                   previous build shipped 384x216, and upscaling that to a
                   1080p window produced the visible 40-pixel JPEG blocks that
                   made the background look broken.

      defocus      A Gaussian blur of sigma 13 (at 720p) is encoded into the
                   frames. This is the single most important decision in the
                   file. Blurring 2 megapixels per frame at runtime costs more
                   than the whole rest of the interface; blurring it once, here,
                   costs nothing forever. Sigma 13 is a deliberate midpoint --
                   strong enough to sit under text, weak enough that a cherry
                   grove still reads as a cherry grove.

      levels       Each clip is graded to a common mean luminance so that one
                   fixed overlay in the launcher works for all four. Without
                   this the clips land between luma 7 (bg_3, a night scene) and
                   133 (bg_4, bright daylight) -- no single overlay can serve
                   both. The gamma figures below were solved by bisection
                   against the measured mean of each graded clip; see
                   docs/backgrounds.md.

      loop         The last CrossfadeFrames frames are dissolved into the frames
                   immediately preceding the segment start, so the sequence
                   wraps with no cut and no black frame. None of the four
                   sources contains a scene change, which is what makes this
                   work.

    The source .mp4 files are read only. They are never modified, and they are
    not copied into the repository -- they are roughly 477 MB in total, and the
    derivatives below are the only thing the launcher needs.

.PARAMETER Source
    Directory holding bg_1.mp4 .. bg_4.mp4. Defaults to the user's Downloads.

.PARAMETER Output
    Where the derivatives are written. Defaults to the launcher-ui asset tree.

.PARAMETER FFmpeg
    Path to ffmpeg.exe. Defaults to whatever is on PATH.

.PARAMETER Only
    Regenerate just these backgrounds, e.g. -Only 2,3. Default: all four.

.EXAMPLE
    pwsh tools/prepare-backgrounds.ps1

.EXAMPLE
    pwsh tools/prepare-backgrounds.ps1 -Source D:\footage -Only 3
#>
[CmdletBinding()]
param(
    [string] $Source = (Join-Path $env:USERPROFILE 'Downloads'),
    [string] $Output,
    [string] $FFmpeg = 'ffmpeg',
    [int[]]  $Only
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# $PSScriptRoot is not bound while parameter defaults are evaluated, so the
# repository-relative default is resolved here instead.
if (-not $PSBoundParameters.ContainsKey('Output') -or [string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot '..\crates\launcher-ui\assets\backgrounds'
}
$hasOnly = $PSBoundParameters.ContainsKey('Only') -and $Only

# -- output shape ---------------------------------------------------------
#
# 300 frames at 30 fps is a 10 second loop. Longer means a larger install for
# footage that is defocused past the point of being memorable; shorter starts
# to read as a repeat.
$Width            = 1280
$Height           = 720
$Fps              = 30
$LoopFrames       = 300
$CrossfadeFrames  = 24      # 0.8s dissolve at the wrap point
$BlurSigma        = 13
$Quality          = 3       # ffmpeg -q:v, 2 = best. Blurred frames compress
                            # extremely well: ~25 KB each at this setting.

# -- per-clip grading -----------------------------------------------------
#
# Start   the segment offset, chosen for stable exposure. bg_2 fades from day
#         to night across its first 24 seconds; starting at 30s avoids a loop
#         that visibly pulses in brightness.
# Gamma   solved by bisection so each graded clip lands on TargetLuma.
# Sat     mild desaturation. The background is scenery, not the subject.
$Clips = @(
    [pscustomobject]@{ Index = 1; Start = 15; Gamma = 0.69; Saturation = 0.80; Scene = 'cherry grove'      }
    [pscustomobject]@{ Index = 2; Start = 30; Gamma = 1.10; Saturation = 0.85; Scene = 'snowfall at night' }
    [pscustomobject]@{ Index = 3; Start = 15; Gamma = 1.22; Saturation = 0.92; Scene = 'midnight coast'    }
    [pscustomobject]@{ Index = 4; Start = 10; Gamma = 0.51; Saturation = 0.78; Scene = 'savanna village'   }
)

function Resolve-FFmpeg {
    param([string] $Candidate)

    $found = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }

    # winget's Gyan.FFmpeg package, which is how this machine got ffmpeg. The
    # PATH entry it adds only applies to shells started afterwards, so a script
    # run in the same session as the install would otherwise fail here.
    $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $winget) {
        $probe = Get-ChildItem $winget -Recurse -Filter 'ffmpeg.exe' -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($probe) { return $probe.FullName }
    }

    throw "ffmpeg was not found. Install it (winget install Gyan.FFmpeg) or pass -FFmpeg <path>."
}

$ffmpegExe = Resolve-FFmpeg $FFmpeg
Write-Host "ffmpeg: $ffmpegExe"

$outRoot = (New-Item -ItemType Directory -Force -Path $Output).FullName
Write-Host "output: $outRoot"

$selected = if ($hasOnly) { $Clips | Where-Object { $Only -contains $_.Index } } else { $Clips }
if (-not $selected) { throw "No matching backgrounds for -Only $($Only -join ',')." }

foreach ($clip in $selected) {
    $name  = "bg_$($clip.Index)"
    $clipPath = Join-Path $Source "$name.mp4"
    if (-not (Test-Path $clipPath)) { throw "Source recording not found: $clipPath" }

    $target = Join-Path $outRoot $name
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $target | Out-Null

    Write-Host ""
    Write-Host "== $name ($($clip.Scene)) =="

    # Frames needed from the source: the loop itself plus the overlap that gets
    # dissolved back onto its own head.
    $needed   = $LoopFrames + $CrossfadeFrames
    $duration = [math]::Round(($needed + 6) / $Fps, 3)

    # Shared front end: normalise rate, scale, defocus, grade. Everything after
    # this point is only about making the sequence wrap cleanly.
    $graded = "fps=$Fps,scale=$($Width):$($Height):flags=lanczos," +
              "gblur=sigma=$($BlurSigma):steps=3," +
              "eq=gamma=$($clip.Gamma):saturation=$($clip.Saturation)," +
              "setpts=PTS-STARTPTS"

    # out[0 .. L-X)  = g[X .. L)                      played straight
    # out[L-X .. L)  = g[L .. L+X) dissolved into g[0 .. X)
    #
    # so the final frame lands one source frame before out[0], and the wrap is
    # continuous. 'N' is the frame index within the blend, which is why the
    # divisor is written as a literal -- 'X' is already a pixel coordinate in
    # blend's expression namespace.
    $tailStart = $LoopFrames
    $tailEnd   = $LoopFrames + $CrossfadeFrames
    $filter = @(
        "[0:v]$graded,split=3[s1][s2][s3]"
        "[s1]trim=start_frame=$($CrossfadeFrames):end_frame=$($LoopFrames),setpts=PTS-STARTPTS[body]"
        "[s2]trim=start_frame=$($tailStart):end_frame=$($tailEnd),setpts=PTS-STARTPTS[tail]"
        "[s3]trim=start_frame=0:end_frame=$($CrossfadeFrames),setpts=PTS-STARTPTS[head]"
        "[tail][head]blend=all_expr='A*(1-(N/$CrossfadeFrames))+B*(N/$CrossfadeFrames)'[seam]"
        "[body][seam]concat=n=2:v=1:a=0[out]"
    ) -join ';'

    & $ffmpegExe -v error -y -ss $clip.Start -t $duration -i $clipPath `
        -filter_complex $filter -map '[out]' -an `
        -q:v $Quality -f image2 (Join-Path $target 'frame_%03d.jpg')
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $name (exit $LASTEXITCODE)." }

    $written = @(Get-ChildItem $target -Filter 'frame_*.jpg')
    if ($written.Count -ne $LoopFrames) {
        throw "$name produced $($written.Count) frames, expected $LoopFrames."
    }

    # A small still, embedded in the executable. It is what the background
    # switcher shows as a preview, and what the launcher paints for the few
    # milliseconds before the first real frame is decoded -- which is how
    # switching background avoids ever showing a black window.
    & $ffmpegExe -v error -y -ss $clip.Start -i $clipPath -frames:v 1 `
        -vf "scale=640:360:flags=lanczos,gblur=sigma=6.5:steps=3,eq=gamma=$($clip.Gamma):saturation=$($clip.Saturation)" `
        -q:v 4 (Join-Path $target 'poster.jpg')
    if ($LASTEXITCODE -ne 0) { throw "poster generation failed for $name (exit $LASTEXITCODE)." }

    $bytes = ($written | Measure-Object -Property Length -Sum).Sum
    @{
        source           = "$name.mp4"
        scene            = $clip.Scene
        width            = $Width
        height           = $Height
        fps              = $Fps
        frames           = $LoopFrames
        crossfade_frames = $CrossfadeFrames
        blur_sigma       = $BlurSigma
        gamma            = $clip.Gamma
        saturation       = $clip.Saturation
        start_seconds    = $clip.Start
        generated        = (Get-Date).ToString('o')
    } | ConvertTo-Json | Set-Content (Join-Path $target 'manifest.json') -Encoding utf8

    '{0}: {1} frames, {2}x{3} @ {4} fps, {5:N1} MB ({6:N0} KB/frame)' -f `
        $name, $written.Count, $Width, $Height, $Fps,
        ($bytes / 1MB), ($bytes / $written.Count / 1KB) | Write-Host
}

Write-Host ""
Write-Host "Done. See docs/backgrounds.md for what these settings mean."
