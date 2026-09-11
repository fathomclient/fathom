# Fathom

A free, open-source launcher for Minecraft: Java Edition 1.8.9, built for
performance and privacy.

Fathom signs you in with your own Microsoft account, checks that the account
owns the game, and downloads Minecraft from Mojang's own servers. It manages
profiles and mods, and includes Fathom Optimizations, an in-game performance and
HUD mod.

- **Private.** No telemetry, analytics, ads, launcher account or background
  service. See [PRIVACY.md](PRIVACY.md) and [NETWORK.md](NETWORK.md).
- **Legitimate.** No cracked accounts, no ownership bypass, and no redistributed
  game files.

Not affiliated with, endorsed by, or associated with Mojang or Microsoft.

## Build

Needs Rust and Node.js. Run the interface dev server, then the app:

```bash
npm --prefix desktop install
```

```bash
npm --prefix desktop run dev
```

```bash
cargo run -p launcher-desktop
```

`tools/package-portable.ps1` builds a portable release.

### Signing in

Microsoft sign-in needs an Azure application id approved for the Minecraft API.
Set it with `FATHOM_AZURE_CLIENT_ID`. **Forks must register their own**; see
[auth-feasibility.md](docs/research/auth-feasibility.md).

For development without one, build with `--features dev-auth` and set
`FATHOM_DEV_AUTH=i-understand-this-is-not-real`. That identity is fake, cannot
play online, and cannot be compiled into a release build.

## Checks

```bash
cargo test --workspace
```

```bash
cargo clippy --workspace --all-targets -- -D warnings
```

## Documents

| File | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layering, decisions, limitations |
| [PRIVACY.md](PRIVACY.md) | What is stored and what leaves the machine |
| [NETWORK.md](NETWORK.md) | Every reachable host, and how the allowlist is enforced |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Threats, mitigations, and what is still open |
| [ATTRIBUTION.md](ATTRIBUTION.md) | Creator credits and third-party assets |

## Licence

Apache-2.0. Background footage recorded in community worlds is credited in
[ATTRIBUTION.md](ATTRIBUTION.md) and in the launcher under Settings → Appearance.
