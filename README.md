<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/lockup.svg">
  <img alt="fathom" src="assets/brand/lockup-reversed.svg" height="40">
</picture>

# Fathom

Fathom is a free, open-source launcher and client for Minecraft: Java Edition
1.8.9. It signs you in with your own Microsoft account, downloads the game from
Mojang, and runs it with Fathom's in-game client on Legacy Fabric or Forge.

What you get:

- **A launcher** for profiles, mods (with a built-in Modrinth browser),
  OptiFine, Java, and recovery when a launch goes wrong.
- **An in-game client.** Right Shift opens the hub, with 21 modules: FPS,
  coordinates, keystrokes, CPS, armour and potion HUDs, zoom, a custom
  crosshair, toggle sprint and sneak, Fullbright, Time Changer, 1.7
  animations, and more. Each one can be placed in the HUD editor.
- **Performance work**, each piece measured in the game before it is turned on
  by default. See [optimization-registry.md](docs/performance/optimization-registry.md).
- **Both loaders.** Legacy Fabric and Forge 1.8.9 run the same client, and your
  settings and keybinds follow you between them.

It collects nothing: no telemetry, analytics, ads, launcher account or
background service. [PRIVACY.md](PRIVACY.md) and [NETWORK.md](NETWORK.md) list
what is stored and every host it talks to. It never offers cracked accounts,
skips the ownership check, or redistributes game files.

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

## Licence

Apache-2.0. Third-party assets and their credits are in
[ATTRIBUTION.md](ATTRIBUTION.md).
