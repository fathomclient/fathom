# Build the normal per-user Windows installer for Fathom.
#
# This is the sole release packaging command. It builds the client component,
# installs the locked frontend toolchain, builds the current frontend, stages
# only the loadable first-party jar, and asks Tauri to create an NSIS installer
# whose WebView assets are embedded in the executable.

param(
    [string] $GradlePath = "",
    [string] $NodePath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root "desktop"
$shell = Join-Path $root "crates/launcher-desktop"
$moduleRoot = Join-Path $root "minecraft/fathom-optimizations"
$started = Get-Date

$node = if ($NodePath) { $NodePath } else {
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { $command.Source } else { "" }
}
if (-not $node -or -not (Test-Path -LiteralPath $node -PathType Leaf)) {
    throw "Node.js is required on the release builder. It is not required on an installed client."
}
$npm = Join-Path (Split-Path -Parent $node) "npm.cmd"
if (-not (Test-Path -LiteralPath $npm -PathType Leaf)) {
    throw "npm.cmd was not found beside Node.js: $npm"
}

$wrapper = Join-Path $moduleRoot "gradlew.bat"
# The Gradle tools/build-all.ps1 uses, downloaded into the checkout.
$checkoutGradle = Join-Path $root ".tmp-gradle-8.10.2\gradle-8.10.2\bin\gradle.bat"
$gradle = if ($GradlePath) { $GradlePath }
          elseif (Test-Path -LiteralPath $wrapper) { $wrapper }
          elseif (Test-Path -LiteralPath $checkoutGradle) { $checkoutGradle }
          else {
              $command = Get-Command gradle -ErrorAction SilentlyContinue
              if ($command) { $command.Source } else { "" }
          }
if (-not $gradle -or -not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle is required on the release builder; refusing to create an installer without Fathom's client."
}

Write-Host "Installing the locked frontend build dependencies..."
Push-Location $desktop
& $npm ci
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm ci failed" }

Write-Host "Building the canonical frontend..."
& $npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "frontend build failed" }
Pop-Location

Write-Host "Building and testing the Fathom Minecraft client..."
Push-Location $moduleRoot
& $gradle build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Fathom client build failed" }
Pop-Location

$versionLine = Select-String -Path (Join-Path $moduleRoot "gradle.properties") -Pattern '^mod_version=' |
               Select-Object -First 1
if (-not $versionLine) { throw "mod_version is missing from gradle.properties" }
$moduleVersion = ($versionLine.Line -split '=', 2)[1].Trim()
$clientJar = Join-Path $moduleRoot "build/libs/fathom-optimizations-$moduleVersion.jar"
if (-not (Test-Path -LiteralPath $clientJar -PathType Leaf)) {
    throw "Required Fathom client output is missing: $clientJar"
}

$stage = Join-Path $shell "bundle/resources"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$stagedClient = Join-Path $stage "fathom-optimizations.jar"
Copy-Item -LiteralPath $clientJar -Destination $stagedClient -Force

$tauri = Join-Path $desktop "node_modules/@tauri-apps/cli/tauri.js"
if (-not (Test-Path -LiteralPath $tauri -PathType Leaf)) {
    throw "The locked Tauri CLI was not installed by npm ci."
}

# Do not leak the release builder's checkout or account name into panic paths.
$cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE ".cargo" }
$previousFlags = $env:RUSTFLAGS
$env:RUSTFLAGS = (@(
    $previousFlags,
    "--remap-path-prefix=$root=/fathom",
    "--remap-path-prefix=$cargoHome=/cargo"
) | Where-Object { $_ }) -join " "

try {
    Write-Host "Building the per-user NSIS installer..."
    Push-Location $shell
    & $node $tauri build --config tauri.installer.conf.json --bundles nsis
    if ($LASTEXITCODE -ne 0) { throw "Tauri installer build failed" }
} finally {
    Pop-Location
    $env:RUSTFLAGS = $previousFlags
    Remove-Item -LiteralPath $stagedClient -Force -ErrorAction SilentlyContinue
}

$bundleDir = Join-Path $root "target/release/bundle/nsis"
$installer = Get-ChildItem -LiteralPath $bundleDir -Filter "*.exe" -File -ErrorAction SilentlyContinue |
             Where-Object { $_.LastWriteTime -ge $started } |
             Sort-Object LastWriteTime -Descending |
             Select-Object -First 1
if (-not $installer) { throw "Tauri reported success but no fresh NSIS installer was produced." }

$outputDir = Join-Path $root "dist/installer"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$published = Join-Path $outputDir $installer.Name
Copy-Item -LiteralPath $installer.FullName -Destination $published -Force
$hash = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash

Write-Host ""
Write-Host "Windows installer: $published"
Write-Host "SHA-256: $hash"
Write-Host "UI transport: embedded Tauri protocol (no localhost server)"
Write-Host "Install mode: current user (no elevation required)"
# Sign-in needs the approved Azure registration compiled in (see AzureApp in
# launcher-core). Without it every tester sees "this build cannot sign in".
# A personal FATHOM_AZURE_CLIENT_ID is deliberately not used: only the release
# variable is compiled in.
if ($env:FATHOM_RELEASE_AZURE_CLIENT_ID -and $env:FATHOM_RELEASE_AZURE_CLIENT_ID.Trim()) {
    Write-Host "Microsoft sign-in: release application id embedded"
} else {
    Write-Warning "Microsoft sign-in: NO application id embedded; testers will not be able to sign in. Set FATHOM_RELEASE_AZURE_CLIENT_ID to Fathom's approved registration before building anything for other people."
}
