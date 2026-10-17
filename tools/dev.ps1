# Run the canonical launcher with Vite hot reload.
#
# Ordinary `cargo run` builds use Fathom's embedded frontend and never touch a
# TCP port. This script is the one explicit exception for frontend development.
# Tauri owns the Vite child, waits for its HTTP readiness response, and does not
# create WebView2 until that response succeeds.

param(
    [switch] $DevAuth,
    [string] $NodePath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root "desktop"
$shell = Join-Path $root "crates/launcher-desktop"

$node = if ($NodePath) { $NodePath } else {
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { $command.Source } else { "" }
}
if (-not $node -or -not (Test-Path -LiteralPath $node -PathType Leaf)) {
    throw "Node.js is required for frontend development."
}

$tauri = Join-Path $desktop "node_modules/@tauri-apps/cli/tauri.js"
$vite = Join-Path $desktop "node_modules/vite/bin/vite.js"
if (-not (Test-Path -LiteralPath $tauri) -or -not (Test-Path -LiteralPath $vite)) {
    throw "Frontend dependencies are missing. Run npm ci in desktop first."
}

# Ask Windows for an unused IPv4 loopback port. Each invocation gets its own,
# so concurrent developer launchers do not contend for 5173. Vite's strict-port
# flag turns the tiny release/rebind race into a clean startup failure instead
# of silently selecting a different URL than WebView2.
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = ([Net.IPEndPoint] $probe.LocalEndpoint).Port
$probe.Stop()

$devUrl = "http://127.0.0.1:$port"
$viteCommand = "`"$node`" `"$vite`" --host 127.0.0.1 --port $port --strictPort"
$config = @{
    build = @{
        devUrl = $devUrl
        beforeDevCommand = $viteCommand
    }
} | ConvertTo-Json -Compress

$arguments = @("dev", "--config", $config)
$previousDevAuth = $env:FATHOM_DEV_AUTH
if ($DevAuth) {
    $env:FATHOM_DEV_AUTH = "i-understand-this-is-not-real"
    $arguments += @("--features", "dev-auth")
    Write-Host "Development identity ENABLED. It is fake and owns nothing."
}

Write-Host "Starting Fathom hot reload on an ephemeral loopback port ($port)..."
Write-Host "The packaged launcher never uses this server."

try {
    Push-Location $shell
    & $node $tauri @arguments
    if ($LASTEXITCODE -ne 0) { throw "Tauri development session failed" }
} finally {
    Pop-Location
    $env:FATHOM_DEV_AUTH = $previousDevAuth
}
