# Run Fathom against the Vite dev server, with hot reload.
#
#   pwsh tools/dev.ps1            normal
#   pwsh tools/dev.ps1 -DevAuth   plus the fake development identity
#
# WHAT THIS GIVES YOU
#
# Edit anything under `desktop/src` and the window updates immediately. No
# cargo build, no packaging, no `dist/Fathom`. Only a change to Rust needs the
# binary rebuilt (Ctrl-C and re-run).
#
# HOW IT WORKS, AND WHY IT IS THE OPPOSITE OF THE PACKAGING BUG
#
# Tauri decides where the interface comes from with a cargo feature, not with
# debug-vs-release:
#
#     dev: cfg!(not(feature = "custom-protocol"))
#
# `tools/package-portable.ps1` passes `--features custom-protocol`, which embeds
# the built bundle into the executable. This script deliberately does NOT, so
# the same binary loads `devUrl` from tauri.conf.json instead. That is the
# behaviour that shipped a broken release once; here it is the point.
#
# The consequence worth knowing: a dev build with no Vite server running shows
# "localhost refused to connect". That is not a crash -- start the server, or
# just re-run this script, which starts it for you.
#
# The server is pinned to `--host 127.0.0.1`. Left alone, Vite here binds to
# `::1` ONLY, and `devUrl` is `http://localhost:5173`: whether that reaches the
# server then depends on which address the WebView's resolver happens to try
# first. Binding IPv4 explicitly removes the coin toss.

param(
    # Compile in the fake development identity. It owns nothing and cannot join
    # an online-mode server; see crates/launcher-core/src/auth/development.rs.
    [switch] $DevAuth,
    [int]    $Port = 5173
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Is anything listening, on EITHER address family?
#
# The first version of this connected a TcpClient to 127.0.0.1 and gave up after
# sixty seconds reporting that Vite had not started. Vite had started -- it had
# bound to `::1` only, so an IPv4 probe never saw it. Asking the OS what is
# listening avoids having to be right about which family a server chose.
function Test-Port([int] $number) {
    $listening = Get-NetTCPConnection -LocalPort $number -State Listen -ErrorAction SilentlyContinue
    return [bool] $listening
}

# Reuse a server that is already up rather than failing on a taken port: leaving
# `npm run dev` running in another terminal is the normal way to work.
$vite = $null
if (Test-Port $Port) {
    Write-Host "Vite is already serving on $Port; reusing it."
} else {
    Write-Host "Starting Vite on $Port..."
    $vite = Start-Process -PassThru -WindowStyle Minimized `
        -FilePath 'cmd.exe' `
        -ArgumentList '/c', "npm --prefix `"$root\desktop`" run dev -- --host 127.0.0.1 --port $Port"

    $deadline = (Get-Date).AddSeconds(60)
    while (-not (Test-Port $Port)) {
        if ((Get-Date) -gt $deadline) { throw "Vite did not start on port $Port" }
        if ($vite.HasExited) { throw "Vite exited; run 'npm --prefix desktop run dev' to see why" }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "Vite is up."
}

$featureArgs = @()
if ($DevAuth) {
    # Two switches, not one: the feature must be compiled in AND the environment
    # variable set. Neither alone does anything, which is what stops a stray
    # `--features` in a script producing a build that looks signed in.
    $env:FATHOM_DEV_AUTH = 'i-understand-this-is-not-real'
    $featureArgs = @('--features', 'dev-auth')
    Write-Host "Development identity ENABLED. It is fake and owns nothing."
}

Write-Host ""
Write-Host "Running Fathom (debug, hot reload). Ctrl-C to stop."
Write-Host "Edit desktop/src/** and the window updates without a rebuild."
Write-Host ""

try {
    Push-Location $root
    & cargo run -p launcher-desktop @featureArgs
} finally {
    Pop-Location
    # Only stop the server this script started. A server that was already
    # running belongs to whoever started it.
    if ($vite -and -not $vite.HasExited) {
        Write-Host "Stopping the Vite server this script started..."
        Stop-Process -Id $vite.Id -Force -ErrorAction SilentlyContinue
    }
}
