# Delete what building and benchmarking leave behind.
#
# WHAT THIS IS FOR
#
# A full session here produces several gigabytes of regenerable output: Gradle's
# build tree, Cargo's target directory, a benchmark instance with its own copy of
# the mods and the world, per-run game logs, and QA screenshots. None of it is
# source and all of it comes back from a build. It is still worth deleting
# deliberately rather than by hand, because the directories it lives in also
# contain things that do not come back.
#
# WHAT IT WILL NOT TOUCH, EVER
#
#   minecraft/fathom-optimizations/run/saves
#       The development world. `bench-ab.ps1` copies "New World" out of here to
#       build its own instance, and refuses to run without it. It looks like
#       scratch data inside a scratch directory and it is neither.
#
#   Anything under %LOCALAPPDATA%\Fathom except the two categories named below.
#       Profiles, worlds, accounts, options and screenshots are the user's.
#       A cleanup script that reaches into them once is a cleanup script nobody
#       can run without reading it first.
#
#   .tmp-gradle-*, desktop/node_modules
#       Toolchains. Regenerable in the sense that they can be downloaded again,
#       which is not the sense that matters at the point somebody runs this.
#
# DRY RUN BY DEFAULT
#
# With no arguments it prints what it would remove and how much space that is,
# and removes nothing. `-Delete` is required to act. The asymmetry is deliberate:
# the cost of an unnecessary dry run is two seconds.

[CmdletBinding()]
param(
    # Actually delete. Without this the script only reports.
    [switch] $Delete,

    # Also remove benchmark result JSON (`run-ab/fathom-bench`). Off by default:
    # those files are measurements, and a measurement is not regenerable in the
    # way a build output is -- reproducing it means another twenty-minute run on
    # a machine in a different thermal state.
    [switch] $Results,

    # Also remove Cargo's target directory. Off by default because it is the one
    # entry here whose rebuild is measured in minutes rather than seconds.
    [Alias("Target")]
    [switch] $CargoTarget,

    # Also remove the packaging output in dist/.
    [switch] $Dist,

    # Also prune per-launch game logs in the Fathom data directory, keeping the
    # newest few per profile. These are diagnostic output, not user content, but
    # they are the only thing in that tree this script will remove besides the
    # backups it made itself.
    [switch] $GameLogs,

    # How many launch logs to keep per profile when -GameLogs is given.
    [int] $KeepLogs = 3,

    # Everything above.
    [switch] $All
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Refuse to run anywhere that is not this checkout. The script deletes
# directories by relative path, so being in the wrong place is the one failure
# that would matter.
if (-not (Test-Path -LiteralPath (Join-Path $root "Cargo.toml")) -or
    -not (Test-Path -LiteralPath (Join-Path $root "minecraft\fathom-optimizations"))) {
    throw "tools/clean.ps1 must live in the Fathom checkout; $root does not look like one"
}

if ($All) {
    $Results = $true
    $CargoTarget = $true
    $Dist = $true
    $GameLogs = $true
}

$data = Join-Path $env:LOCALAPPDATA "Fathom\data"

# Paths this script is allowed to delete inside. Anything outside both is a bug
# in the list below, and is refused rather than removed.
$allowedRoots = @($root)
if (Test-Path -LiteralPath $data) { $allowedRoots += (Resolve-Path -LiteralPath $data).Path }

$plan = New-Object System.Collections.ArrayList
$totalBytes = 0L

function Measure-Size([string] $path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if (-not $item) { return 0L }
    if ($item.PSIsContainer) {
        $sum = Get-ChildItem -LiteralPath $path -Recurse -Force -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum
        if ($sum.Sum) { return [long] $sum.Sum }
        return 0L
    }
    return [long] $item.Length
}

function Format-Size([long] $bytes) {
    if ($bytes -ge 1GB) { return "{0,7:N1} GB" -f ($bytes / 1GB) }
    if ($bytes -ge 1MB) { return "{0,7:N1} MB" -f ($bytes / 1MB) }
    if ($bytes -ge 1KB) { return "{0,7:N1} KB" -f ($bytes / 1KB) }
    return "{0,7} B " -f $bytes
}

# Queue one path for removal, after checking it is somewhere this script may act.
function Add-Target([string] $path, [string] $why) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $inside = $false
    foreach ($allowed in $allowedRoots) {
        # Compare resolved paths, so a junction or a `..` cannot walk out of the
        # tree and still pass a textual check.
        if ($resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { $inside = $true }
    }
    if (-not $inside) {
        Write-Warning "skipping $resolved -- outside the checkout and the Fathom data directory"
        return
    }
    $bytes = Measure-Size $resolved
    [void] $plan.Add([pscustomobject] @{ Path = $resolved; Why = $why; Bytes = $bytes })
    $script:totalBytes += $bytes
}

# -- build output -----------------------------------------------------------
Add-Target (Join-Path $root "minecraft\fathom-optimizations\build") "Gradle build output"
Add-Target (Join-Path $root "minecraft\fathom-optimizations\.gradle") "Gradle project cache"

# -- the benchmark instance -------------------------------------------------
$benchRoot = Join-Path $root "minecraft\fathom-optimizations\run-ab"
Add-Target (Join-Path $benchRoot "mods") "benchmark mods (recopied per run)"
Add-Target (Join-Path $benchRoot "saves") "benchmark world (recopied from run/saves)"
Add-Target (Join-Path $benchRoot "logs") "benchmark game logs"
if (Test-Path -LiteralPath $benchRoot) {
    Get-ChildItem -LiteralPath $benchRoot -Filter "*.log" -File -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Target $_.FullName "benchmark per-run log" }
}
if ($Results) {
    Add-Target (Join-Path $benchRoot "fathom-bench") "benchmark RESULTS (measurements)"
}
Add-Target (Join-Path $root "tools\.bench-classpath.txt") "cached launch classpath"
# Deliberately NOT removed by default: the launcher reads this before it reads
# application data, so deleting it silently sends the next launch back to
# whatever stale jar %LOCALAPPDATA% happens to hold.
if ($All) {
    Add-Target (Join-Path $root "resources") "staged client module (beside the executable)"
}

# -- the development client -------------------------------------------------
# `run/saves` is deliberately absent: see the header.
$runRoot = Join-Path $root "minecraft\fathom-optimizations\run"
Add-Target (Join-Path $runRoot "logs") "development client logs"
Add-Target (Join-Path $runRoot "crash-reports") "development client crash reports"
if (Test-Path -LiteralPath (Join-Path $runRoot "screenshots")) {
    # Only the ones the QA harness writes. A timestamped screenshot was taken by
    # a person and is not this script's to judge.
    Get-ChildItem -LiteralPath (Join-Path $runRoot "screenshots") -Filter "fathom-*.png" `
        -File -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Target $_.FullName "QA screenshot" }
}

# -- JVM crash dumps, wherever they landed ----------------------------------
foreach ($pattern in @("hs_err_pid*.log", "replay_pid*.log", "*.hprof")) {
    Get-ChildItem -LiteralPath $root -Filter $pattern -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike "*\target\*" -and $_.FullName -notlike "*\node_modules\*" } |
        ForEach-Object { Add-Target $_.FullName "JVM crash dump" }
}

# -- optional, larger ------------------------------------------------------
if ($CargoTarget) { Add-Target (Join-Path $root "target") "Cargo build output" }
if ($Dist) { Add-Target (Join-Path $root "dist") "packaging output" }

# -- the Fathom data directory: only our own leavings -----------------------
if (Test-Path -LiteralPath $data) {
    # Backups written by a diagnostic run, not by the launcher.
    Get-ChildItem -LiteralPath (Join-Path $data "profiles") -Filter "*.diagbak" `
        -File -Recurse -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Target $_.FullName "diagnostic profile backup" }

    if ($GameLogs) {
        Get-ChildItem -LiteralPath (Join-Path $data "profiles") -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $logs = Join-Path $_.FullName "logs"
                if (Test-Path -LiteralPath $logs) {
                    Get-ChildItem -LiteralPath $logs -Filter "launch-*.log" -File -ErrorAction SilentlyContinue |
                        Sort-Object LastWriteTime -Descending |
                        Select-Object -Skip $KeepLogs |
                        ForEach-Object { Add-Target $_.FullName "old launch log" }
                }
            }
    }
}

# -- report ------------------------------------------------------------------
if ($plan.Count -eq 0) {
    Write-Host "Nothing to clean." -ForegroundColor Green
    return
}

$grouped = $plan | Group-Object Why | Sort-Object { ($_.Group | Measure-Object Bytes -Sum).Sum } -Descending
foreach ($group in $grouped) {
    $bytes = ($group.Group | Measure-Object Bytes -Sum).Sum
    if (-not $bytes) { $bytes = 0L }
    $count = $group.Group.Count
    $suffix = ""
    if ($count -gt 1) { $suffix = "  ($count items)" }
    Write-Host ("  {0}  {1}{2}" -f (Format-Size ([long] $bytes)), $group.Name, $suffix)
}
Write-Host ""
Write-Host ("  {0}  total" -f (Format-Size $totalBytes)) -ForegroundColor Cyan

if (-not $Delete) {
    Write-Host ""
    Write-Host "Dry run. Nothing was deleted. Add -Delete to remove these." -ForegroundColor Yellow
    if (-not $All) {
        Write-Host "Not included: -Results (measurements), -CargoTarget, -Dist, -GameLogs. -All adds every one."
    }
    return
}

$removed = 0L
$failed = 0
foreach ($entry in $plan) {
    try {
        Remove-Item -LiteralPath $entry.Path -Recurse -Force -ErrorAction Stop
        $removed += $entry.Bytes
    } catch {
        # A file the running game still holds open is the ordinary case here,
        # and it is not a reason to abandon the rest.
        Write-Warning ("could not remove {0}: {1}" -f $entry.Path, $_.Exception.Message)
        $failed++
    }
}

Write-Host ""
Write-Host ("Removed {0}." -f (Format-Size $removed).Trim()) -ForegroundColor Green
# The launcher's build script embeds the module jar, so the next build has to be
# a full one. Said here rather than discovered later as an os error 3 inside a
# build script.
Write-Host "The next build must be a full one: tools/build-all.ps1 without -SkipModule."

if ($failed -gt 0) {
    Write-Host "$failed item(s) could not be removed; close the game or the launcher and run again."
}
