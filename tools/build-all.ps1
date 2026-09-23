# Build everything and put Fathom.exe in the repository root.
#
# Three artifacts have to agree for a change to reach the game, and the order
# matters:
#
#   1. the Minecraft module  (minecraft/fathom-optimizations -> a jar)
#   2. that jar, staged where the launcher looks for it
#   3. the launcher itself   (desktop frontend + Tauri shell -> Fathom.exe)
#
# Step 2 is the one that is easy to forget and impossible to notice. The
# launcher installs its client module from `%LOCALAPPDATA%\Fathom\data\resources`,
# not from the build directory, so a rebuilt jar that is not copied there leaves
# the launcher shipping the previous one -- the game starts, everything works,
# and none of the changes are in it.

[CmdletBinding()]
param(
    # Skip the Java module when only the launcher changed.
    [switch] $SkipModule,
    # Skip the launcher when only the module changed.
    [switch] $SkipLauncher,
    # Build the launcher unoptimised. Much faster; only affects the launcher
    # window, never the game's frame rate.
    [Alias("Unoptimized")]
    [switch] $DebugBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$resources = Join-Path $env:LOCALAPPDATA "Fathom\data\resources"

function Step([string] $text) {
    Write-Host ""
    Write-Host "== $text ==" -ForegroundColor Cyan
}

if (-not $SkipModule) {
    Step "Building the Minecraft module"
    $gradle = Join-Path $root ".tmp-gradle-8.10.2\gradle-8.10.2\bin\gradle.bat"
    if (-not (Test-Path -LiteralPath $gradle)) {
        throw "Gradle not found at $gradle"
    }
    Push-Location (Join-Path $root "minecraft\fathom-optimizations")
    try {
        # The module is copied into the launcher data root immediately after
        # this command, so an incremental/cache false positive ships stale game
        # bytecode. Build from a clean output tree and make source authoritative.
        & $gradle clean build --rerun-tasks --no-build-cache --console=plain
        if ($LASTEXITCODE -ne 0) { throw "the module build failed" }
    } finally {
        Pop-Location
    }

    Step "Staging the module where the launcher reads it"
    $jar = Get-ChildItem -Path (Join-Path $root "minecraft\fathom-optimizations\build\libs") `
        -Filter "fathom-optimizations-*.jar" |
        Where-Object { $_.Name -notlike "*-sources.jar" } |
        Select-Object -First 1
    if (-not $jar) { throw "no module jar was produced" }
    New-Item -ItemType Directory -Force -Path $resources | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination $resources -Force

    # Also beside the executable, which is where `components::bundled_jar` looks
    # FIRST and only falls back to application data.
    #
    # WHY BOTH
    #
    # %LOCALAPPDATA% is not a reliable rendezvous between every process that
    # needs this file. A packaged (MSIX) process has its own virtualised view of
    # it, so a jar staged by one tool can be invisible to another while both
    # report the same path -- which cost an afternoon of launches that loaded a
    # build nobody had staged, each one verified by reading back the writer's own
    # copy. The repository tree has no such redirection, and it is the first
    # candidate anyway, so staging there makes the launcher read what was just
    # built regardless of who built it.
    $beside = Join-Path $root "resources"
    New-Item -ItemType Directory -Force -Path $beside | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination $beside -Force

    # Verified, not assumed. The staging step is the one that has actually failed:
    # a jar was built, the copy did not land (or landed somewhere else), and the
    # launcher went on serving the previous build under an identical file name and
    # version string. Everything downstream then describes code that is not running.
    $builtHash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA1).Hash.ToLower()
    foreach ($staged in @((Join-Path $resources $jar.Name), (Join-Path $beside $jar.Name))) {
        $stagedHash = (Get-FileHash -LiteralPath $staged -Algorithm SHA1).Hash.ToLower()
        if ($builtHash -ne $stagedHash) {
            throw "staging did not take: $staged is $stagedHash but the build produced $builtHash"
        }
    }

    # The same identity the game prints at startup, so the two can be compared
    # without opening either file.
    $buildProps = Join-Path $root "minecraft/fathom-optimizations/build/resources/main/fathom-build.properties"
    $commit = "unknown"
    if (Test-Path -LiteralPath $buildProps) {
        $line = Select-String -LiteralPath $buildProps -Pattern '^commit=' | Select-Object -First 1
        if ($line) { $commit = $line.Line.Substring(7) }
    }
    Write-Host "  $($jar.Name) -> $resources"
    Write-Host "  $($jar.Name) -> $beside  (read first by the launcher)"
    Write-Host "  build $commit  sha1 $stagedHash" -ForegroundColor Green
    Write-Host "  the game prints this at startup as [FATHOM PERF] Fathom build:"
}

# -SkipModule is only meaningful when the module has been built before: the
# launcher's build script embeds the client jar and panics if `build/libs` is
# not there. `tools/clean.ps1` removes that directory, so "clean, then rebuild
# only the launcher" fails in a build script with an os error 3 rather than
# anywhere a reader would look.
if ($SkipModule -and -not $SkipLauncher) {
    $libs = Join-Path $root "minecraft/fathom-optimizations/build/libs"
    if (-not (Test-Path -LiteralPath $libs)) {
        Write-Host ""
        Write-Host "  -SkipModule was given, but the module has never been built here" -ForegroundColor Yellow
        Write-Host "  (or tools/clean.ps1 removed its output): $libs" -ForegroundColor Yellow
        Write-Host "  The launcher embeds that jar. Re-run without -SkipModule."
        throw "the module output is missing, so -SkipModule cannot work"
    }
}

if (-not $SkipLauncher) {
    Step "Building the launcher frontend"
    Push-Location (Join-Path $root "desktop")
    try {
        & node "node_modules/svelte-check/bin/svelte-check" --tsconfig "./tsconfig.json"
        if ($LASTEXITCODE -ne 0) { throw "the frontend type check failed" }
        & node "node_modules/vite/bin/vite.js" build
        if ($LASTEXITCODE -ne 0) { throw "the frontend build failed" }
    } finally {
        Pop-Location
    }

    Step "Building the launcher"
    Push-Location $root
    try {
        if ($DebugBuild) {
            & cargo build -p launcher-desktop
            $built = Join-Path $root "target\debug\fathom-desktop.exe"
        } else {
            & cargo build --release -p launcher-desktop
            $built = Join-Path $root "target\release\fathom-desktop.exe"
        }
        if ($LASTEXITCODE -ne 0) { throw "the launcher build failed" }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $built)) { throw "expected $built" }
    $destination = Join-Path $root "Fathom.exe"

    # A running launcher holds this file open, and Copy-Item's own message for
    # that is an IOException naming a path -- which reads like a build failure
    # after a build that in fact succeeded completely. Say what it is instead,
    # and say that the work is not lost.
    $holding = Get-Process -Name "Fathom" -ErrorAction SilentlyContinue
    if ($holding) {
        Write-Host ""
        Write-Host "  The launcher is running (pid $($holding.Id -join ', ')), so it is holding" -ForegroundColor Yellow
        Write-Host "  $destination open and it cannot be replaced." -ForegroundColor Yellow
        Write-Host "  Everything else built and staged. Close Fathom and run:"
        Write-Host "    Copy-Item -LiteralPath '$built' -Destination '$destination' -Force"
        Write-Host "  or re-run this script with -SkipModule."
        throw "Fathom.exe is in use by the running launcher"
    }

    Copy-Item -LiteralPath $built -Destination $destination -Force
    Write-Host "  -> $destination"
}

Write-Host ""
Write-Host "Done. Double-click Fathom.exe in $root" -ForegroundColor Green
