# Interleaved A/B benchmark for arms that cannot be switched at runtime.
#
# WHY THIS EXISTS SEPARATELY FROM -PperfBench=ab
#
# `PerfBench`'s own `ab` mode is the better instrument and should be preferred
# whenever it applies: it alternates optimization switches in ABBA order inside
# a single session, against one world state, so machine drift cancels within the
# comparison and each condition pools tens of seconds of frames.
#
# It cannot do OptiFine. OptiFine is patched into the game by OptiFabric at
# class-load time, before anything is drawn, so "OptiFine off" and "OptiFine on"
# are two different processes by construction. No in-session toggle exists or
# can exist.
#
# The next best control is to interleave at the level of *runs*: A, B, B, A,
# alternating within one sitting rather than running all of A and then all of B.
# Thermal state and background load drift over minutes, and batching turns that
# drift into an apparent result -- which is exactly the mistake
# `docs/performance/benchmark-methodology.md` records under "interleave arms".
#
# WHAT IT DOES NOT DO
#
# Download OptiFine. Its licence does not permit redistribution and this script
# will not fetch it; pass the path to a jar you downloaded yourself. OptiFabric
# is MPL-2.0 and may be fetched, but this script does not do that either -- pass
# it, or install it through the launcher.

[CmdletBinding()]
param(
    # A jar you downloaded from optifine.net yourself.
    [Parameter(Mandatory = $true)][string] $OptiFine,
    # Legacy OptiFabric, e.g. optifabric-2.3.6+1.3.1-1.13.2.jar.
    [Parameter(Mandatory = $true)][string] $OptiFabric,
    # How many ABBA cycles. Four runs each, so 2 gives eight runs.
    [int] $Cycles = 1,
    # Seconds measured per scenario.
    [int] $Seconds = 20,
    [int] $Width = 1920,
    [int] $Height = 1080,
    [string] $Label = "ab"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $env:LOCALAPPDATA "Fathom\data"

foreach ($jar in @($OptiFine, $OptiFabric)) {
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Not found: $jar"
    }
}

# ---------------------------------------------------------------------------
# The classpath
# ---------------------------------------------------------------------------
#
# Taken from a real launch rather than rebuilt here. The launcher resolves the
# library set from the version manifest, and a second implementation of that in
# PowerShell would be a second thing to keep correct -- and would drift silently
# the first time the manifest changed.
$cacheFile = Join-Path $PSScriptRoot ".bench-classpath.txt"
if (Test-Path -LiteralPath $cacheFile) {
    $classpath = (Get-Content -LiteralPath $cacheFile -Raw).Trim()
} else {
    $log = Join-Path $data "logs\launcher.log"
    if (-not (Test-Path -LiteralPath $log)) {
        throw "No launcher log at $log. Launch a Legacy Fabric profile from Fathom once, so this harness can read the classpath it used, then run this again."
    }
    $classpath = $null
    foreach ($line in Get-Content -LiteralPath $log) {
        if ($line -match '-cp (\S+) net\.fabricmc') { $classpath = $Matches[1] }
    }
    if (-not $classpath) {
        throw "No Fabric launch found in $log. Launch a Legacy Fabric profile from Fathom once, then run this again."
    }
    Set-Content -LiteralPath $cacheFile -Value $classpath -Encoding utf8
    Write-Host "Cached the launch classpath in $cacheFile"
}

# The Java, from the same real launch the classpath came from.
#
# WHY NOT `Get-Command java`
#
# Because the JDK on PATH here is 17, Minecraft 1.8.9 needs 8, and the failure is
# not loud: the game dies during LWJGL initialisation, this harness pipes its
# output to Out-Null, and every run produces no samples. The summary then reports
# two arms of nothing, which reads like a benchmark that ran rather than one that
# never started. The launcher already resolved the correct runtime and wrote it
# down; read that, exactly as the classpath above is read.
$javaExe = $null
$log = Join-Path $data "logs\launcher.log"
if (Test-Path -LiteralPath $log) {
    foreach ($line in Get-Content -LiteralPath $log) {
        if ($line -match 'launch report: java: (.+?\.exe)') { $javaExe = $Matches[1] }
    }
}
if (-not $javaExe) {
    $managed = Join-Path $data "java"
    $javaExe = Get-ChildItem -Path $managed -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $javaExe -or -not (Test-Path -LiteralPath $javaExe)) {
    throw "No Java 8 runtime found. Launch a profile from Fathom once so this harness can read the runtime it used."
}

# Stated, not assumed: a harness that benchmarks nothing must say so before it
# spends twenty minutes doing it.
# `java -version` writes to stderr, and Windows PowerShell wraps each stderr line
# from a native command in an ErrorRecord -- which under `ErrorActionPreference =
# Stop` throws even though the command succeeded. Relax it just around this call.
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaBanner = (& $javaExe -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
$ErrorActionPreference = $previousPreference
if ($javaBanner -notmatch '1\.8\.') {
    throw "Minecraft 1.8.9 needs Java 8, but $javaExe reports: $javaBanner"
}
Write-Host "Java:      $javaExe"
Write-Host "Classpath: $($classpath.Length) chars from the launcher's own last launch"

# ---------------------------------------------------------------------------
# The bench instance
# ---------------------------------------------------------------------------
$bench = Join-Path $root "minecraft\fathom-optimizations\run-ab"
$mods = Join-Path $bench "mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null

# PerfBench loads the save named "New World". Copy the development one in if the
# bench instance has not got it yet.
$saves = Join-Path $bench "saves"
if (-not (Test-Path (Join-Path $saves "New World"))) {
    $devSave = Join-Path $root "minecraft\fathom-optimizations\run\saves\New World"
    if (-not (Test-Path -LiteralPath $devSave)) {
        throw "No 'New World' save to benchmark. Run the development client once and create one."
    }
    New-Item -ItemType Directory -Force -Path $saves | Out-Null
    Copy-Item -LiteralPath $devSave -Destination (Join-Path $saves "New World") -Recurse -Force
}

# VSync off and no frame cap, or the comparison measures the limiter. These are
# the same two values `profiles::game_options` seeds a new profile with.
#
# WHY THIS IS A FUNCTION, CALLED BEFORE EVERY RUN
#
# It used to be written once, before the loop. On a fresh bench instance the
# first run then came back at a flat 120 fps in every scenario -- 1.8.9's default
# cap -- with a frame-time coefficient of variation of 0.096 and a 3.3 ms minimum
# frame time: the machine idling against a limiter, not working. The game rewrites
# options.txt in full when it exits, so runs two onward were uncapped and only the
# first was wrong, which is the worst shape for this bug. It does not look like a
# failure, it looks like one noisy run, and it inflated arm A's spread to 98% and
# buried a real 31-80% difference under "inside the noise".
#
# `WriteAllText` with an explicit no-BOM encoding rather than `Set-Content
# -Encoding utf8`, which in Windows PowerShell 5.1 writes a BOM. A BOM makes the
# first key unparseable, and the first key is the frame cap.
$noBom = New-Object System.Text.UTF8Encoding $false
function Set-BenchOptions {
    [System.IO.File]::WriteAllText(
        (Join-Path $bench "options.txt"),
        "maxFps:260`nenableVsync:false`n",
        $noBom)
}
Set-BenchOptions

$fathomJar = Get-ChildItem -Path (Join-Path $root "minecraft\fathom-optimizations\build\libs") `
    -Filter "fathom-optimizations-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*-sources.jar" } |
    Select-Object -First 1
if (-not $fathomJar) { throw "Build the module first: gradle build" }

$natives = Join-Path $data "profiles\optimized\natives"
$assets = Join-Path $data "shared\assets"

function Set-Arm([string] $arm) {
    # Every run, because the game rewrites this file when it exits and a run that
    # inherits the previous run's rewrite is not the run this harness configured.
    Set-BenchOptions
    Get-ChildItem -Path $mods -Filter *.jar | Remove-Item -Force
    Copy-Item -LiteralPath $fathomJar.FullName -Destination $mods -Force
    if ($arm -eq "B") {
        Copy-Item -LiteralPath $OptiFabric -Destination $mods -Force
        Copy-Item -LiteralPath $OptiFine -Destination $mods -Force
    }
}

function Invoke-Run([string] $arm, [int] $index) {
    $runLabel = "$Label-$arm$index"
    Write-Host "== arm $arm, run $index ($runLabel) =="
    Set-Arm $arm
    # Built as an array and quoted item by item. Windows PowerShell splits an
    # unquoted `-Dfile.encoding=UTF-8` into `-Dfile` and `.encoding=UTF-8`, and
    # the second half then lands in the main-class position: the run dies with
    # "Could not find or load main class .encoding=UTF-8" before the game starts.
    # Because the output is piped to Out-Null, that failure is invisible and the
    # harness cheerfully reports eight runs that never rendered a frame.
    $arguments = @(
        '-Xms2048M', '-Xmx2048M',
        '-XX:+DisableExplicitGC', '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=50',
        "-Djava.library.path=$natives",
        '-Dfile.encoding=UTF-8',
        '-Dfathom.perfBench=world',
        "-Dfathom.perfBench.label=$runLabel",
        "-Dfathom.perfBench.seconds=$Seconds",
        '-cp', $classpath,
        'net.fabricmc.loader.impl.launch.knot.KnotClient',
        '--username', 'BenchLocal', '--version', '1.8.9', '--gameDir', $bench,
        '--assetsDir', $assets, '--assetIndex', '1.8',
        '--uuid', '00000000000000000000000000000dev',
        '--accessToken', '0', '--userProperties', '{}', '--userType', 'legacy',
        '--width', $Width, '--height', $Height
    )
    $runLog = Join-Path $bench "$runLabel.log"
    # Kept rather than discarded. A run that produced no samples has a reason,
    # and it is in this file.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $javaExe @arguments 2>&1 | Out-File -LiteralPath $runLog -Encoding utf8
    $ErrorActionPreference = $previousPreference
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "run $runLabel exited with $LASTEXITCODE; see $runLog"
    }
}

# ABBA, not AABB. The point of the ordering is that whatever drifts over the
# sitting affects both arms in both halves.
$order = @("A", "B", "B", "A")
for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    for ($step = 0; $step -lt $order.Length; $step++) {
        Invoke-Run $order[$step] (($cycle - 1) * $order.Length + $step + 1)
    }
}

Write-Host ""
Write-Host "Runs are in $(Join-Path $bench 'fathom-bench')."
Write-Host "Summarise with:"
Write-Host "  python tools/bench-ab-summary.py `"$(Join-Path $bench 'fathom-bench')`" $Label"
