# Fathom

An open-source, privacy-first launcher for Minecraft: Java Edition 1.8.9.

**This launcher does not distribute Minecraft.** It signs you in with your own
Microsoft account, verifies that the account is entitled to Java Edition, and
then downloads the game from Mojang's own servers.

There is no launcher account, no telemetry, no analytics, no ads, no device
identifier, no kernel driver, and no background service. Those are checkable
claims, not slogans — see [PRIVACY.md](PRIVACY.md) and [NETWORK.md](NETWORK.md).

## Status

Foundation. It signs in, verifies entitlement, downloads game files in the
background, and builds a launch command. It does **not** yet implement a mod
loader or self-update. [ARCHITECTURE.md](ARCHITECTURE.md) §8
lists every known limitation; the interface says so on screen rather than
offering controls that do not work.

## What it will not do

No cracked accounts, no offline-mode ownership bypass, no authentication bypass,
no redistributed game binaries. No registry tweaks, no service disabling, no
Defender exclusions, no realtime priority, no overclocking. The reasoning is in
[docs/research/security-model.md](docs/research/security-model.md) §9.

## Build and run

```bash
cargo run -p launcher-ui
```

```bash
cargo run -p launcher-cli -- doctor
```

### Building without a Microsoft application id

Microsoft requires every launcher to register its own application and have it
approved for the Minecraft API; approval is a manual review. See
[auth-feasibility.md](docs/research/auth-feasibility.md).

Until that lands, a fake local identity lets the rest of the launcher be built
and tested:

```bash
FATHOM_DEV_AUTH=i-understand-this-is-not-real cargo run -p launcher-ui --features dev-auth
```

It owns nothing, cannot join online-mode servers, and **cannot be built into a
release binary** — that combination is a compile error. Installing and verifying
game files needs no account at all, so most of the launcher is testable without
it.

Signing in needs an Azure application id that Microsoft has approved for the
Minecraft API. Registrations that have not been approved receive HTTP 403.

```bash
FATHOM_AZURE_CLIENT_ID=<your-application-id> cargo run -p launcher-ui
```

## Checks

```bash
cargo test --workspace
```

```bash
cargo clippy --workspace --all-targets -- -D warnings
```

```bash
cargo deny check
```

## Documents

| File | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layering, decisions, limitations |
| [PRIVACY.md](PRIVACY.md) | What is stored, what leaves the machine, and what we cannot promise |
| [NETWORK.md](NETWORK.md) | Every reachable host, and how the allowlist is enforced |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Fourteen threats, what stops each, and what is still open |
| [ATTRIBUTION.md](ATTRIBUTION.md) | Creator credits for background footage, and third-party fonts and icons |
| [docs/research/](docs/research/) | The research this is built on |

## Licence

Apache-2.0. Not affiliated with, endorsed by, or associated with Mojang or
Microsoft. Third-party assets and creator credits are listed in
[ATTRIBUTION.md](ATTRIBUTION.md).
