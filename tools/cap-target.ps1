# Keep Cargo's target directory under a size cap.
#
# WHY THIS EXISTS
#
# Cargo never deletes anything from target/. Every `cargo build`, `test`,
# `clippy` and `check` with a different profile or feature set adds its own
# artifacts and incremental sessions beside the old ones, and in October 2026
# that reached 48 GB in this checkout. Nobody notices until the disk does.
#
# WHAT IT DOES
#
# Measures target/. Under the cap: nothing. Over it: removes the incremental
# caches first (the fastest-growing part, and the cheapest to lose), then, if
# that is not enough, the whole directory. The cost of crossing the cap is one
# slower build, never a wrong one.
#
# It runs automatically from tools/build-all.ps1 before every launcher build and
# from the SessionStart hook in .claude/settings.json. Run it by hand any time.

[CmdletBinding()]
param(
    # The most target/ may hold, in GB, before it is trimmed.
    [double] $CapGB = 15,
    # Report only.
    [switch] $WhatIf
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "target"
if (-not (Test-Path -LiteralPath $target)) { return }

function Get-TreeBytes([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return 0L }
    # .NET enumeration rather than Get-ChildItem: several times faster on the
    # hundreds of thousands of small files Cargo writes.
    $sum = 0L
    $files = [System.IO.Directory]::EnumerateFiles($path, "*", [System.IO.SearchOption]::AllDirectories)
    foreach ($file in $files) {
        try { $sum += ([System.IO.FileInfo]::new($file)).Length } catch { }
    }
    return $sum
}

$cap = [long] ($CapGB * 1GB)
$size = Get-TreeBytes $target
if ($size -le $cap) { return }

$message = "target/ is {0:N1} GB, over the {1:N0} GB cap" -f ($size / 1GB), $CapGB
if ($WhatIf) { Write-Host "$message; would trim it."; return }
Write-Host "$message; trimming." -ForegroundColor Yellow

# Files a running build or launcher holds open are skipped, not fatal.
foreach ($profileDir in Get-ChildItem -LiteralPath $target -Directory -ErrorAction SilentlyContinue) {
    $incremental = Join-Path $profileDir.FullName "incremental"
    if (Test-Path -LiteralPath $incremental) {
        Remove-Item -LiteralPath $incremental -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$size = Get-TreeBytes $target
if ($size -gt $cap) {
    Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue
    $size = Get-TreeBytes $target
}
Write-Host ("target/ is now {0:N1} GB." -f ($size / 1GB)) -ForegroundColor Green
