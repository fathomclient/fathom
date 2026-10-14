# Token exposure on the command line

**Status: open question. Nothing here is implemented.**

This document exists so that a known weakness is written down rather than
half-fixed. The spec for the launch milestone was explicit: *do not invent a
fragile workaround now*. What follows is the problem, what we already do about
it, and what would have to be true before we did anything more.

---

## 1. The problem

Minecraft 1.8.9 takes the account's access token as a command-line argument:

```
java ... net.minecraft.client.main.Main --username Steve --accessToken <token> ...
```

On every operating system we target, a process's argument vector is readable by
other processes:

| OS | How | Who can read it |
|---|---|---|
| Windows | `NtQueryInformationProcess` → PEB → `ProcessParameters.CommandLine`; WMI `Win32_Process.CommandLine` | Any process running as the same user. WMI needs no elevation for same-user processes. |
| Linux | `/proc/<pid>/cmdline` | Same user, and root. `hidepid=2` restricts it, but is not the default anywhere. |
| macOS | `sysctl KERN_PROCARGS2` | Same user, and root. |

So any program the user runs — a mod manager, a Discord overlay, a piece of
malware that never needed elevation — can read the Minecraft session token for
as long as the game is running.

**This is inherent to vanilla 1.8.9.** The game reads `--accessToken` in
`net.minecraft.client.main.Main`. There is no environment variable, no stdin
handshake, and no file-based alternative in that code path. Every launcher for
this version has the same exposure, including the official one.

## 2. What the token is worth

A Minecraft services access token authenticates to session servers: it can join
multiplayer servers as the account and read the profile. It is **not** a
Microsoft account token — it cannot change a password, read email, or make a
purchase — and it is short-lived (24 hours nominal).

That bounds the damage but does not make it acceptable. Session hijacking is
the usual consequence, and it is the exact attack the 1.8.9 modding scene has
historically seen.

## 3. What we already do

These are implemented and tested. They address *our* handling of the token, not
the OS-visible argv.

| Mitigation | Where |
|---|---|
| The token is a `Secret`; `Debug` and `Display` both redact | `secret.rs` |
| An argument built from a secret is `Argument::Sensitive`, carrying its redacted rendering alongside the real one — so redaction is structural, not a string search | `minecraft_runtime/launch.rs` |
| `Debug` on `LaunchPlan` renders the redacted form, so the plan is safe to put in an error chain or a log line | `minecraft_runtime/launch.rs` |
| The only way to get the real argv is `argv_exposing_secrets()`, named to be conspicuous in review | `minecraft_runtime/launch.rs` |
| The game's captured stdout/stderr goes through `logging::scrub` before it reaches the log file — the game prints its own argument list in crash reports | `minecraft_runtime/process.rs` |
| No shell. The argv never becomes a string a shell parses, so it is never in a shell history | `minecraft_runtime/process.rs` |

What none of this fixes: the token is in the child's argv, because the game
requires it there.

## 4. The idea worth investigating

We intend to support Legacy Fabric (`docs/research/mod-policies.md` §4). A
loader means **our** code runs inside the JVM before Minecraft's `main`. That
opens a possibility that is closed to a pure vanilla launcher:

1. Launch with a placeholder token on the command line.
2. A small Fathom bootstrap mod, loaded by Legacy Fabric, receives the real
   token out of band.
3. The bootstrap sets the session before `Minecraft.startGame()` runs.

Argv would then contain nothing worth stealing.

### Candidate out-of-band channels

| Channel | Argv-clean | Notes |
|---|---|---|
| Inherited pipe on a spare fd / handle | Yes | The bootstrap reads it once and closes it. Needs the fd number passed — but a *number* is not a secret. Handle inheritance differs sharply between Windows and Unix. |
| Loopback socket with a one-shot nonce | Partly | The nonce would itself be on the command line; it is single-use and worthless after the read, but any local process could race for it. Would need the launcher to verify the connecting PID. |
| File with restrictive ACLs, deleted after read | Partly | A file is readable by the same user for as long as it exists, which is the same threat we started with, only narrower in time. Worse on Windows, where deletion while open is awkward. |
| Environment variable | **No** | `/proc/<pid>/environ` and `NtQueryInformationProcess` expose it to the same readers. Not an improvement; listing it so nobody proposes it again. |
| stdin | Yes | Simplest of all, and we already set `Stdio::null()` so nothing else uses it. Requires the bootstrap to read stdin before the game does — 1.8.9 does not read stdin, but a mod might. |

Current instinct: **inherited pipe, or stdin.** Both are argv-clean and neither
needs a secret on the command line. Neither is written.

### What would have to be answered first

1. Does Legacy Fabric give us a hook that runs reliably before the session is
   read? Which entrypoint, and is it stable across Legacy Fabric versions?
2. Can the session be set at that point without reflection into obfuscated
   1.8.9 internals? Reflection against obfuscated names is exactly the fragility
   the spec warned against.
3. What does vanilla do with the placeholder if the bootstrap fails to run — a
   confusing "invalid session" at the multiplayer screen, or a clean failure?
   A silent degradation to an unusable session is worse than the exposure.
4. Does this force Legacy Fabric on users who want vanilla? If so, the mitigation
   is not available to the people running plain 1.8.9, and we would have two
   launch paths with different security properties, which must then be stated
   plainly in the UI rather than glossed over.
5. Does injecting a session this way violate Mojang's terms, or look to a server
   like session spoofing? This has to be answered before any code is written.

### What we will not do

- **Ship a bootstrap that reflects into obfuscated internals** and breaks on
  every mod-loader update. The spec is explicit, and it is right: a fragile
  mitigation that fails open is worse than a documented exposure.
- **Patch or repackage the client jar.** That is redistribution, which
  `mod-policies.md` §1 rules out.
- **Claim the exposure is fixed** while any launch path still puts the token in
  argv. If this is ever implemented for Legacy Fabric only, the vanilla path
  keeps the exposure and the UI has to say so.

## 5. Until then

`THREAT_MODEL.md` records this as an accepted, documented limitation of vanilla
1.8.9. `LaunchPlan::exposes_a_secret_argument()` reports it programmatically, so
the day a launch path exists that does not expose a token, the difference is
observable rather than assumed — and `fathom-cli plan` prints it.

## 6. References

- `docs/research/security-model.md` §3 — the original note that raised this.
- `THREAT_MODEL.md` §9 — sensitive log leakage, and what is mitigated.
- `docs/research/mod-policies.md` §4 — Legacy Fabric's licence and status.
