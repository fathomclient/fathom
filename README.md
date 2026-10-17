<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/lockup.svg">
  <img alt="fathom" src="assets/brand/lockup-reversed.svg" height="40">
</picture>

# Fathom

Fathom is a free, open-source launcher and client for Minecraft: Java Edition
1.8.9.

What you get:

- **A launcher** for profiles, mods (with a built-in mod browser),
  OptiFine, Java, and recovery when a launch goes wrong.
- **An in-game client.** Right Shift opens the hub, with 21 modules.
- **Performance work**, each piece measured in the game before it is turned on
  by default. See [optimization-registry.md](docs/performance/optimization-registry.md).
- **Both loaders.** Legacy Fabric and Forge 1.8.9 run the same client, and your
  settings and keybinds persist between them.

It collects nothing: no telemetry, analytics, ads, launcher account or
background service. [PRIVACY.md](PRIVACY.md) and [NETWORK.md](NETWORK.md) list 
everything that is ever contacted. It does not support cracked accounts and you
must have an owned copy of the game to use Fathom.

Not affiliated with Mojang or Microsoft.

## Building

You need, on Windows:

- Rust (stable; the toolchain file picks it up)
- Node.js 18 or newer
- JDK 17 to run Gradle, and JDK 8 for the Minecraft module
- Gradle 8.10, on your `PATH` or unpacked to `.tmp-gradle-8.10.2/`

Then:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
```

That builds the Minecraft module (both the Fabric and Forge jars), the
frontend and the launcher, and leaves `Fathom.exe` in the repository root. A
normal per-user installer comes from `tools\build-installer.ps1`, written to
`dist\installer`.

For frontend work with hot reload, run `tools\dev.ps1`.

### Microsoft sign-in

Signing in needs an Azure application that Microsoft has approved for the
Minecraft API. Forks have to register their own; see
[auth-feasibility.md](docs/research/auth-feasibility.md).

- `FATHOM_AZURE_CLIENT_ID` is read when the launcher starts.
- `FATHOM_RELEASE_AZURE_CLIENT_ID` is compiled into the build, for releases
  other people run. Set it in the shell before running either build script.

Without one, `--features dev-auth` with
`FATHOM_DEV_AUTH=i-understand-this-is-not-real` gives an offline identity for
development. It cannot play online and cannot end up in a release build.

## Tests

```powershell
cargo test --workspace
```

```powershell
cargo clippy --workspace --all-targets -- -D warnings
```

The Minecraft module's own checks run with `gradle build` (or `gradle coreTest`)
in `minecraft/fathom-optimizations`.

## Where things are

| Path | What it is |
|---|---|
| `crates/launcher-core` | Everything the launcher does, with no UI |
| `crates/launcher-desktop` | The Tauri shell |
| `desktop` | The launcher's interface (Svelte) |
| `crates/launcher-cli` | A command-line front end, handy for testing |
| `minecraft/fathom-optimizations` | The in-game client |

More reading: [ARCHITECTURE.md](ARCHITECTURE.md),
[THREAT_MODEL.md](THREAT_MODEL.md), [docs/forge.md](docs/forge.md),
[docs/modules.md](docs/modules.md) and [docs/mod-browser.md](docs/mod-browser.md).

Thank you for using Fathom <3

## Licence

Apache-2.0. Third-party assets and their credits are in
[ATTRIBUTION.md](ATTRIBUTION.md).
