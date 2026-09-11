<#
.SYNOPSIS
    Turns the source gameplay recordings into the launcher's background videos.

.DESCRIPTION
    The presentation layer is a WebView, so backgrounds are now real video played
    by a `<video>` element and composited on the GPU. That is a different problem
    from the previous frame-sequence approach and a much easier one: the browser
    decodes and scales in hardware, so there is no reason to shrink the footage
    and no reason for it to run below source rate.

    VP9 in WebM, not H.264 in MP4 -- see the encoder call below. The WebView2
    runtime cannot demux H.264, and this was found the hard way.

    WHAT IS BAKED IN, AND WHAT IS NOT

      blur        Baked. A moderate Gaussian (sigma 10 at 720p). CSS could blur
                  at runtime and it would look fine on a discrete GPU, but a
                  full-screen blur every frame is real work on integrated
                  graphics, and this costs nothing once. The CSS layer adds only
                  a light extra treatment on top, which stays tunable.

      grade       Baked, per clip. The sources have very different exposure;
                  without normalising them no single overlay can serve them all.

      darkening   NOT baked. That is a CSS overlay, because it is the thing most
                  likely to need adjusting against real interface colours, and
                  re-encoding every video to try a different scrim is absurd.

      loop        Baked. The tail is dissolved into the head so `<video loop>`
                  wraps with no visible cut.

    The source .mp4 recordings are read only, and are not copied into the repository.

.PARAMETER Source
    Directory holding the original numbered and named bg_*.mp4 recordings.
    Defaults to the user's Downloads.

.PARAMETER Output
    Where the derivatives go. Defaults to the frontend's static asset folder.

.PARAMETER Only
    Optional clip names or legacy numbers to build, for example
    -Only bg_5rain,bg_6clear or -Only 1,2.
#>
[CmdletBinding()]
param(
    [string] $Source = (Join-Path $env:USERPROFILE 'Downloads'),
    [string] $Output,
    [string] $FFmpeg = 'ffmpeg',
    [string[]] $Only
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $PSBoundParameters.ContainsKey('Output') -or [string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot '..\desktop\public\backgrounds'
}
$hasOnly = $PSBoundParameters.ContainsKey('Only') -and $Only

# -- output shape ---------------------------------------------------------
$Width           = 1600
$Height          = 900
$Fps             = 30
$CrossfadeFrames = 24    # 0.8s dissolve at the wrap point
# A cinematic defocus, not a frosting.
#
# This was 10, which destroyed the scene: at 1280x720 a sigma of 10 removes
# every block edge and leaves coloured shapes. The point of the background is
# that it is recognisably Minecraft, and foreground glass -- not blur -- is what
# makes text readable over it. Blur is destructive, so raising this is the one
# change here that CANNOT be undone by re-grading: it needs the source files.
$BlurSigma       = 3.5
# Lower than before, because there is now detail worth preserving. At sigma 10
# the footage compressed to nothing whatever the CRF; at 3.5 it does not.
$Crf             = 30

# Start offsets chosen for stable exposure; bg_2 fades from day to night across
# its first 24 seconds, so it starts at 30s. Gammas solved by bisection against
# each clip's measured mean luma. See docs/backgrounds.md.
#
# The saturations are all higher than they were. They had been pulled down to
# stop a heavily-blurred frame reading as a wash of flat colour -- but with the
# blur now at sigma 3.5 there is real detail to see, and desaturating it was
# removing the last thing that made the scene identifiable as Minecraft. Still
# below 1.0: this sits behind an interface and must not compete with it.
$Clips = @(
    [pscustomobject]@{ Index = 1; Name = 'bg_1'; Start = 15; Gamma = 0.69; Saturation = 0.92; LoopFrames = 300; Scene = 'cherry grove' }
    [pscustomobject]@{ Index = 2; Name = 'bg_2'; Start = 30; Gamma = 1.10; Saturation = 0.95; LoopFrames = 300; Scene = 'snowfall at night' }
    [pscustomobject]@{ Index = 3; Name = 'bg_3'; Start = 15; Gamma = 1.22; Saturation = 0.98; LoopFrames = 300; Scene = 'midnight coast' }
    [pscustomobject]@{ Index = 4; Name = 'bg_4'; Start = 10; Gamma = 0.51; Saturation = 0.90; LoopFrames = 300; Scene = 'savanna village' }
    [pscustomobject]@{ Index = 5; Name = 'bg_5rain'; Start = 12; Gamma = 1.18; Saturation = 0.96; LoopFrames = 300; Scene = 'rainy jungle bridge' }
    [pscustomobject]@{ Index = 5; Name = 'bg_5noraintickfrozen'; Start = 6; Gamma = 0.86; Saturation = 0.92; LoopFrames = 300; Scene = 'still jungle twilight' }
    [pscustomobject]@{ Index = 5; Name = 'bg_5noraintickunfrozen'; Start = 6; Gamma = 0.88; Saturation = 0.94; LoopFrames = 300; Scene = 'moving jungle twilight' }
    [pscustomobject]@{ Index = 6; Name = 'bg_6midnight'; Start = 0; Gamma = 1.12; Saturation = 0.96; LoopFrames = 180; Scene = 'midnight metropolis' }
    [pscustomobject]@{ Index = 6; Name = 'bg_6clear'; Start = 0; Gamma = 0.84; Saturation = 0.92; LoopFrames = 300; Scene = 'daylight metropolis' }
)

function Resolve-FFmpeg {
    param([string] $Candidate)
    $found = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $winget) {
        $probe = Get-ChildItem $winget -Recurse -Filter 'ffmpeg.exe' -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($probe) { return $probe.FullName }
    }
    throw "ffmpeg was not found. Install it (winget install Gyan.FFmpeg) or pass -FFmpeg <path>."
}

$ffmpegExe = Resolve-FFmpeg $FFmpeg
$outRoot = (New-Item -ItemType Directory -Force -Path $Output).FullName
Write-Host "ffmpeg: $ffmpegExe"
Write-Host "output: $outRoot"

$selected = if ($hasOnly) {
    $Clips | Where-Object { $Only -contains [string]$_.Index -or $Only -contains $_.Name }
} else {
    $Clips
}

if (-not $selected) {
    throw "No matching clips. Available names: $($Clips.Name -join ', ')"
}

foreach ($clip in $selected) {
    $name = $clip.Name
    $LoopFrames = $clip.LoopFrames
    $clipPath = Join-Path $Source "$name.mp4"
    if (-not (Test-Path $clipPath)) { throw "Source recording not found: $clipPath" }

    Write-Host ""
    Write-Host "== $name ($($clip.Scene)) =="

    $needed   = $LoopFrames + $CrossfadeFrames
    $duration = [math]::Round(($needed + 6) / $Fps, 3)

    $graded = "fps=$Fps,scale=$($Width):$($Height):flags=lanczos," +
              "gblur=sigma=$($BlurSigma):steps=3," +
              "eq=gamma=$($clip.Gamma):saturation=$($clip.Saturation)," +
              "setpts=PTS-STARTPTS"

    # out[0 .. L-X) = g[X .. L) played straight; the last X frames dissolve into
    # g[0 .. X), so the final frame lands one source frame before out[0] and the
    # wrap is continuous. 'N' is the blend's frame index; the divisor is a
    # literal because 'X' is already a pixel coordinate in blend's namespace.
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

    $target = Join-Path $outRoot "$name.webm"

    # VP9 in WebM, not H.264 in MP4.
    #
    # This is not a preference. The WebView2 runtime cannot demux H.264: an
    # otherwise valid MP4 that plays in full Chromium fails in the launcher's
    # webview with DEMUXER_ERROR_COULD_NOT_OPEN, because the Evergreen runtime
    # does not carry the proprietary decoders. VP9 is royalty-free and present
    # in every Chromium engine, WebView2 included.
    #
    # -an because a launcher background with audio would be indefensible.
    & $ffmpegExe -v error -y -ss $clip.Start -t $duration -i $clipPath `
        -filter_complex $filter -map '[out]' -an `
        -c:v libvpx-vp9 -pix_fmt yuv420p `
        -crf $Crf -b:v 0 -row-mt 1 -deadline good -cpu-used 2 -g $Fps `
        $target
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $name (exit $LASTEXITCODE)." }

    # A poster frame, shown for the instant before the video decodes so a
    # background change never flashes empty.
    & $ffmpegExe -v error -y -ss $clip.Start -i $clipPath -frames:v 1 `
        -vf "scale=640:360:flags=lanczos,gblur=sigma=2:steps=3,eq=gamma=$($clip.Gamma):saturation=$($clip.Saturation)" `
        -q:v 4 (Join-Path $outRoot "$name.jpg")
    if ($LASTEXITCODE -ne 0) { throw "poster generation failed for $name." }

    $size = (Get-Item $target).Length
    '{0}: {1}x{2} @ {3} fps, {4:N1} MB' -f $name, $Width, $Height, $Fps, ($size / 1MB) | Write-Host
}

Write-Host ""
Write-Host "Done. See docs/backgrounds.md."
