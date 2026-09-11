# Assemble a portable release directory.
#
# THIS IS A DEVELOPER SCRIPT. It is not how anyone launches the launcher:
# it produces the directory that contains the thing they launch. The user-facing
# artifact is `Fathom\fathom.exe`, a normal windowed executable that they
# double-click. There is deliberately no `start-launcher.bat`, and adding one
# would be a regression.
#
#   pwsh tools/package-portable.ps1 [-OutDir <path>]

param(
    [string]$OutDir = "dist/Fathom"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# The frontend first. `fathom.exe` is the Tauri shell, and Tauri embeds
# `desktop/dist` into the binary at compile time -- so building the Rust side
# against a stale bundle produces an executable that looks fine and shows the
# previous version of every page.
Write-Host "Building the frontend..."
Push-Location (Join-Path $root "desktop")
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "frontend build failed" }
Pop-Location

Write-Host "Building release binaries..."
Push-Location $root

# Keep the build machine's paths out of the shipped binary.
#
# `file!()` paths end up in panic messages, and build scripts bake their
# absolute `OUT_DIR` into generated sources -- `glutin_wgl_sys`, reached through
# the wgpu renderer, embeds one. Without this the executable contains a string
# like `C:\Users\<name>\...\target\release\build\...`, which hands anyone who
# opens it in a text editor the username of whoever built it. For a launcher
# whose PRIVACY.md leads with "no device identifier", shipping the developer's
# account name is the wrong detail to leave lying around.
#
# Remapping to a fixed stand-in also makes the build that bit more reproducible:
# two machines with different checkout paths now produce the same strings.
# Two prefixes, because there are two sources of absolute paths. Our own
# checkout is the smaller one: the bulk is dependency source under Cargo's
# registry, which contributed 805 copies of the username before this was added.
$cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME }
             else { Join-Path $env:USERPROFILE '.cargo' }

$previousFlags = $env:RUSTFLAGS
$env:RUSTFLAGS = (@(
    $previousFlags,
    "--remap-path-prefix=$root=/fathom",
    "--remap-path-prefix=$cargoHome=/cargo"
) | Where-Object { $_ }) -join ' '
try {
    cargo build --workspace --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }

    # The shell, again, with `custom-protocol`.
    #
    # This flag is what makes Tauri serve the interface from inside the
    # executable instead of fetching it from the vite dev server. It is decided
    # by a cargo feature and NOT by debug versus release -- `tauri-macros` reads
    # it as `dev: cfg!(not(feature = "custom-protocol"))` -- so a plain
    # `cargo build --release` silently produces a binary that opens on
    # "localhost refused to connect". It did, and this line is why it no longer
    # does.
    cargo build -p launcher-desktop --release --features custom-protocol
    if ($LASTEXITCODE -ne 0) { throw "cargo build (launcher-desktop) failed" }
} finally {
    $env:RUSTFLAGS = $previousFlags
}

# Verify the feature actually reached `tauri`, rather than trusting that the
# command above meant what it said. The failure this guards against is
# invisible in the build output and only shows up as an error page at runtime,
# which is exactly the kind of thing worth asserting at package time.
$features = (cargo tree -p launcher-desktop --features custom-protocol -f "{p} {f}" --depth 1 |
             Select-String -Pattern '^\S*\s+tauri v') -join "`n"
if ($features -notmatch 'custom-protocol') {
    throw "tauri was built without custom-protocol; the packaged app would load devUrl instead of its own interface."
}
Write-Host "Verified: tauri built with custom-protocol (interface embedded in the binary)."
Pop-Location

$dest = Join-Path $root $OutDir
Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dest | Out-Null

# The launcher, and the headless companion for `recover` and `diagnostics`.
# `fathom-release` is deliberately NOT copied: it is the offline signing tool
# and has no business on a user's machine.
#
# `fathom.exe` is `fathom-desktop.exe` under its user-facing name. The product
# is the Tauri shell; `crates/launcher-ui` still builds a binary also called
# `fathom.exe`, and copying that one would ship the interface this migration
# replaced. Renaming on copy rather than renaming the crate's `[[bin]]` keeps
# the two distinguishable in `target/release`, where both exist.
Copy-Item (Join-Path $root "target/release/fathom-desktop.exe") (Join-Path $dest "fathom.exe")
Copy-Item (Join-Path $root "target/release/fathom-cli.exe") $dest

# First-party resources the launcher looks for beside the executable.
# `DataPaths::bundled_resource` checks here first, then application data.
$resources = Join-Path $dest "resources"
New-Item -ItemType Directory -Force $resources | Out-Null
# The optimization module.
#
# Two jars come out of the Gradle build and only one of them belongs here:
#
#   fathom-optimizations-<version>.jar          the mod, remapped -- ship this
#   fathom-optimizations-<version>-sources.jar  its source, for reading
#   fathom-optimizations-core.jar               dependency-free core, not a mod
#
# The core jar is what `tools/build-optimization-module.ps1` produces with a
# bare JDK: the registry, the switch board and the config bridge, compiled and
# tested without Gradle. It has no entrypoint and no `fabric.mod.json`, so
# Fabric will not load it. The sources jar is not a mod either. Shipping either
# one under `resources/` would put a jar that cannot load where the launcher
# looks for one that can, so both are excluded by name and by suffix rather
# than by hoping the directory only ever holds one file.
$module = Join-Path $root "minecraft/fathom-optimizations/build/libs"
if (Test-Path $module) {
    $loadable = @(Get-ChildItem $module -Filter *.jar |
                  Where-Object { $_.Name -ne 'fathom-optimizations-core.jar' -and
                                 $_.Name -notlike '*-sources.jar' })
    if ($loadable) {
        $loadable | Copy-Item -Destination $resources
        Write-Host "Optimization module: $($loadable.Name -join ', ')"
    } else {
        Write-Host "note: no loadable module jar was found; not shipped."
    }
} else {
    Write-Host "note: the optimization module has not been built; skipping."
}

# The backgrounds are NOT copied beside the executable any more.
#
# They were, for the iced build, because `background::asset_root` looked for
# JPEG frame sequences next to `fathom.exe`. The Tauri shell plays WebM from
# `desktop/public/backgrounds`, which Vite copies into `desktop/dist` and Tauri
# embeds into the binary -- so they are already inside `fathom.exe`, and copying
# the old frame sequences here would add tens of megabytes of files nothing
# reads.
#
# The iced assets are still in the repository and still used by that crate.

Copy-Item (Join-Path $root "PRIVACY.md") $dest -ErrorAction SilentlyContinue
Copy-Item (Join-Path $root "README.md") $dest -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Portable build in $dest"
Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize

# The check that matters: a user must never need a script to start this.
$scripts = Get-ChildItem $dest -Recurse -Include *.bat, *.cmd, *.ps1, *.sh
if ($scripts) {
    throw "a launch script ended up in the portable build: $($scripts.Name -join ', ')"
}
Write-Host "No launch scripts present. Users run fathom.exe."
