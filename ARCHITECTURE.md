# Architecture

Status: **canonical Tauri/Svelte launcher**. The launcher signs in, verifies
entitlement, installs Legacy Fabric and Fathom's client module, and launches an
isolated managed instance. The historical iced details below are retained as a
decision record only; they do not describe a second runnable application.

This document describes what exists. Where something is missing it says so.
`docs/research/` holds the research this is built on; where the two disagree, the
research is the reasoning and this file is the record of what was actually done.

---

## 1. Shape

```
crates/launcher-ui        compatibility command; calls launcher_desktop::run()
crates/launcher-desktop   canonical Tauri shell and typed command boundary
desktop/                  canonical Svelte frontend; embedded for production
                 |
                 |  typed calls into a library, same process
                 v
crates/launcher-core      Rust library, no UI dependency
    services.rs           the facade the UI is allowed to touch
    optimizations/        the optimization catalogue, mirroring the Java registry
      bridge.rs           writes the settings file the Minecraft module reads
    auth/                 Microsoft -> Xbox -> XSTS -> Minecraft -> entitlement
      session.rs          AuthSession: the state machine and the launch gate
      provider.rs         AuthProvider, EntitlementVerifier, MinecraftIdentity
      entitlement.rs      Mojang signature verification, fail-closed
      development.rs      fake local identity; debug builds only, off by default
    credentials.rs        OS credential store; refresh token only
    network/              the only module that opens a socket
    install/              acquire and verify a complete 1.8.9 installation
      metadata.rs         version manifest -> hash-checked version document
      resolver.rs         version document -> the exact artifact set
      download.rs         hash-during-write, atomic finalise, resumable set
      cache.rs            where each kind of artifact is allowed to land
      assets.rs           asset index -> objects/<2 hex>/<sha1>
      natives.rs          native archives -> a scratch directory
      verify.rs           what is on disk, not what we remember
    update/               signed updates; the highest-risk subsystem here
      signing.rs          Ed25519, key ids, and the domain-separated preimage
      manifest.rs         the two signed documents, and what they may say
      keys.rs             the compiled-in trust anchor (empty, on purpose)
      verify.rs           size -> envelope -> threshold -> parse -> expiry -> version
      trust.rs            rollback memory: the floors an attacker must beat
      install.rs          stage, back up, swap, validate, roll back, recover
    ops.rs                which dangerous operations may run at once
    problem.rs            a failure a window can show: category, retry, repair
    recovery/             getting back to a working state
      startup.rs          the fast check before the window opens
      cache.rs            what "clear cache" removes, stated exactly
      java.rs             managed Java only; a system JDK is never touched
      diagnostics.rs      a privacy-safe report, normalised and scrubbed
    tasks.rs              the Send handle worker threads run blocking work on
    launching.rs          launch refusals, and which profiles are running
    minecraft_runtime/    version metadata, JVM flags, launch command
      launch.rs           the typed, immutable LaunchPlan
      process.rs          spawn, capture output, classify the exit
    java_runtime.rs       finds and identifies Java
    loaders/              what a loader is, and how to install it
      legacy_fabric.rs    the real one, against Legacy Fabric's own metadata
      forge.rs            Forge 1.8.9 from Forge's Maven; its installer is read, never run
    mods/                 the mod engine
      definition.rs       ModDefinition: identity, licence, policy, relationships
      catalog.rs          what mod-policies.md established, as data
      config.rs           ProfileModConfiguration: what a profile wants
      resolve.rs          dependencies, conflicts, cycles -> the effective set
      store.rs            content-addressed jars, and materializing mods/
    profiles/             the instance abstraction
      model.rs            the versioned Profile and its sub-configurations
      store.rs            schema versioning, migrations, atomic writes
      ops.rs              create, clone, rename, delete, export, import
      validate.rs         what is wrong with a profile, and what repair may fix
    mods.rs               bring-your-own-jar
    configuration.rs      launcher settings
    paths.rs              the two roots we are allowed to write to
    fs_safety.rs          untrusted names -> safe paths
    secret.rs             tokens that cannot be printed
    logging.rs            tracing setup + credential scrubbing
                 |
                 |  std::process::Command, argument vector, no shell
                 v
JVM (Java 8)              net.minecraft.client.main.Main
```

`crates/launcher-desktop` produces the `WINDOWS_GUI` launcher packaged by the
NSIS release pipeline. `crates/launcher-ui` is only a source-compatible command
alias. No console appears behind the installed launcher, nothing is resolved
relative to the working directory, and a failure before the window opens produces a window
saying why rather than an `eprintln!` nobody can see. `docs/reliability.md`
covers the build, the data locations, startup recovery and the operation
locking.

`crates/fathom-release` is a fourth crate and is **never linked into a shipped
binary**. It is the only place in the workspace that knows what a private key
is, and nothing depends on it — which is what makes "the signing key never
reaches the client" a property of the dependency graph rather than a promise.
It depends on `launcher-core` in the other direction so that it signs with the
same preimage function the launcher verifies with.

`crates/launcher-cli` is a third binary over the same core. It exists to prove
the core really is UI-agnostic, and because `fathom-cli doctor` is the fastest
way to check that a build behaves the way `PRIVACY.md` claims.

### 1.1 The Minecraft-side module

```
minecraft/fathom-optimizations    Java 8, Legacy Fabric, Mixin
    core/                         no Minecraft or Fabric dependency at all
      registry/                   what an optimization is; the evidence gate
      instrument/                 frame timing, counters, memory, GC, reports
      bench/                      scenarios; micro/ is the comparison harness
      experiment/                 JVM questions, with falsification conditions
      guard/                      fall back to vanilla when a patch misbehaves
      opt/                        the optimizations themselves
    fabric/                       entry point and Mixins
```

**Not part of the Rust workspace, and not built by it.** Different language,
different toolchain, different release cadence, and a licensing question the
launcher does not have. The launcher treats it as a versioned component: the
version in `gradle.properties` is pinned in `mods/catalog.rs` and nowhere else.

The internal split matters more than the module boundary. **Everything under
`core/` has no game dependency**, which is what lets it be compiled and tested
with a bare JDK — the launcher's development environment has no Gradle and no
access to the Fabric maven. `fabric/` is the glue that needs the game to exist,
and it has never been compiled. `minecraft/fathom-optimizations/README.md` is the
authority on which is which.

An optimization only turns on by default once it has an in-game measurement
behind it; the gate is enforced in `Optimization.defaultEnabled()` rather than
trusted to discipline. See `docs/performance/optimization-registry.md`.

---

## 2. Decisions, and what changed from the research

### 2.1 Kept

| Decision | Source |
|---|---|
| Rust for core and UI | `architecture-options.md` §2.1 |
| iced, not Tauri/WebView | §3.1, §3.3 — a privacy launcher should not embed someone else's browser |
| `launcher-core` as a UI-free library | §5 |
| Device-code OAuth over a loopback redirect | avoids binding a local listener |
| Entitlement gate on the **signed** JWT, not the JSON body | `security-model.md` §2.2 |
| Only the Microsoft refresh token is persisted | `security-model.md` §3 |
| Modest default heap, `-Xms` = `-Xmx`, `-XX:+DisableExplicitGC` | `PERFORMANCE_MATRIX.md` §F |
| Aikar's flags, ZGC, `UseLargePages`, `-Xverify:none` refused | §F, `security-model.md` §8 |
| One process, no daemon, no tray agent, no service | `security-model.md` §1 |

### 2.2 Changed

**iced with wgpu, falling back to tiny-skia.** This entry previously read "iced
without wgpu", on the reasoning that a window of text and buttons does not need
a GPU and that dropping wgpu removes several hundred transitive crates from a
project whose stated value is a small auditable dependency surface. It closed by
reserving the reversal: "If the UI ever grows something that genuinely needs a
GPU, this is one line to revert."

The full-window animated background is that thing, and the reversal has been
taken. Both renderers are compiled in and iced chooses at runtime: wgpu first,
tiny-skia if no adapter can be created.

The measurement that forced it: on the software renderer a single full-window
bilinear blit of the background costs 21.7 ms at 1920×1010 and 39.2 ms at
2560×1380, and that cost depends *only* on the destination size — a 384×216
source is exactly as expensive to draw as a 1280×720 one. There is no version of
a smooth full-window background that the CPU path can reach, and the previous
build's 6 fps at 384×216 was the result of trying. `docs/backgrounds.md` §1 has
the full table.

What the fallback preserves: the launcher still starts in a virtual machine,
over remote desktop, and on a driver that refuses to initialise. The background
runs slower there; nothing else changes. What it costs: the dependency surface
this entry was originally protecting. That is a real loss, taken deliberately
and recorded here rather than quietly.

**Performance is a destination, not a settings tab.** The first-party
optimization module is one of the things Fathom is: eighteen catalogued
optimizations with presets, evidence and per-entry status, plus a config bridge
into the game. Filing that under Settings would describe the product's main
feature as a preference. Settings links to it rather than containing it, so
someone looking where every other launcher puts it is sent to the right place.

**The Performance page offers no toggle it cannot honour.** Eighteen of the
twenty entries cannot act until the Minecraft-side module ships, so they render
as rows with a status rather than as switches. A page of toggles that all did
nothing would look considerably more finished and would be a lie the first time
a user compared their frame rate. `docs/performance-module.md` has the full
state; a test fails the build if any game-side entry claims to be active.

**No mod browsing.** Fetching a mod on a user's behalf is a distribution act,
and doing it properly needs a signed index and a licence to redistribute from.
Until that exists the launcher adds jars the user already has. A browse grid
would be the most convincing-looking part of the launcher and the least real.

**Vanilla is the default loader, not Legacy Fabric.** The research argues Legacy
Fabric should be the default, and that reasoning still holds. But no loader is
implemented yet, so shipping a default that cannot launch would be dishonest.
`Loader::is_implemented()` returns true only for `Vanilla`, the Profiles screen
labels the others "soon", and `readiness()` reports a specific blocker if one is
selected. When the loader lands, the default flips.

**iced pinned to 0.13, not latest.** 0.14 is out. The research flagged API churn
as iced's main risk; 0.13 is the widely-exercised release. Moving is a reviewed
change, not a bump.

**A real executor had to be enabled explicitly.** iced 0.13 does not re-export
an executor feature, and with none of `tokio` / `async-std` / `smol` /
`thread-pool` enabled, `iced_futures::backend::default` resolves to the **null**
executor: subscriptions and tasks are constructed and then silently never
polled. Nothing warns you. `launcher-ui` therefore depends on `iced_futures`
directly with `features = ["thread-pool"]`, which is
`futures::executor::ThreadPool` and nothing else — no async runtime, which was
the point of the original choice.

### 2.3 Deferred, and honestly labelled in the UI

- Legacy Fabric and Forge loaders.
- Self-update. The design is in `architecture-options.md` §6; no code.
- Managed Java download.
- Mod repository browsing (`mod-policies.md` §7 sets six preconditions).
- Out-of-band access-token delivery (`security-model.md` §3).

---

## 3. Layering rules

These are the rules a reviewer should check a diff against.

1. **Only `network/http.rs` opens a socket.** Nothing else may construct a URL.
2. **Callers cannot express a host.** They name a `Destination` enum variant.
   There is no `Destination::Other(String)` and there must never be one.
3. **Untrusted names go through `fs_safety::contain`.** Zip entries, mod
   filenames, library paths from Mojang metadata, profile ids.
4. **Tokens are `Secret`.** `Debug` and `Display` redact. The only way to see a
   value is `expose()`, which greps well.
5. **The UI calls `services::Launcher` and nothing else.** It cannot reach
   `auth`, `credentials`, `downloads`, or `launch` directly in any meaningful
   way, because the facade exposes intentions rather than mechanisms.
6. **Every write lands under `DataPaths::root()`.** `assert_inside` is the
   single assertion point.

Rule 5 is a convention rather than a compiler-enforced boundary: `launcher-core`
re-exports its modules publicly so the CLI and tests can use them. Making the
internals `pub(crate)` and forcing everything through the facade is a
worthwhile follow-up.

---

## 4. The authentication chain

Implemented in `auth/mod.rs`. Endpoints and error codes are in
`security-model.md` §2.

```
device code  ->  Xbox Live  ->  XSTS  ->  Minecraft Services
                                             |
                                             +-- /entitlements/mcstore   <- the gate
                                             +-- /minecraft/profile
```

The gate is the whole point of the auth code, so it is worth being precise about
what "verified" means here:

1. `GET /entitlements/mcstore` returns `{ items: [...], signature: <JWT> }`.
2. The **unsigned `items` array is ignored.**
3. The `signature` JWT is verified RS256 against Mojang's public key, which is
   compiled into the binary (`auth/entitlement.rs`), with `exp` and `nbf`
   enforced.
4. The entitlement list is read from the **verified payload**.
5. `decide()` maps that list to `Ownership`. Only `JavaEdition` and
   `JavaEditionViaGamePass` permit launching. Anything unrecognised fails closed.

`build_launch_plan` requires a live `Session` whose `ownership.may_launch()` is
true. There is no code path from "not signed in" to a runnable command.

**Two honest caveats.**

The Mojang public key came from public documentation, not from a live response
we captured. If it is wrong, verification fails and nobody can launch — the safe
direction, but a bad launch-day bug. It needs checking against a real signed
response.

And this is not DRM. We are open source and we do not obfuscate: anyone can fork
this and delete the check. Our obligation is that the software *we* publish
verifies entitlement and ships no affordance for bypassing it.

---

## 5. Profiles

A profile is not saved UI settings. It is the unit of isolation: one way of
playing, owning everything mutable about itself.

```text
<data>/profiles/<id>/          owned by the profile, deletable
    profile.json               the manifest, schema-versioned
    profile.json.bak           last known-good copy
    minecraft/                 the game's working directory
        saves/ options.txt resourcepacks/ screenshots/ mods/ config/
    natives/                   extracted per profile, never shared
    logs/                      the launcher's capture of the game's output

<data>/shared/                 immutable, content-addressed
    libraries/ assets/ versions/ logging/       never touched by a profile op

<data>/trash/<id>-<stamp>/     where deleted profiles go first
```

**Mutable is owned; immutable is shared.** Worlds, options, screenshots, mods
and config belong to one profile and nothing else can see them. Libraries,
assets and version jars are content-addressed, so sharing them is safe and
saves 139 MB per profile.

### 5.1 The model

| Concept | Type | Note |
|---|---|---|
| Identity | `ProfileId` | Validated; the only way to name a directory |
| Name | `String` | Display only; renaming does not move anything |
| Minecraft version | `String` | |
| Loader + version | `LoaderConfig` | Vanilla, Legacy Fabric, **Forge** |
| Game directory | `GameDirectory` | `Managed` or `External` |
| Java | `JavaPreference` | `Automatic` or `Explicit` |
| Memory | `MemoryConfig` | `-Xms` == `-Xmx` |
| JVM options | `JvmOptions` | Validated at launch, not on entry |
| Resolution, fullscreen | `DisplayConfig` | |
| Mods | `ModConfiguration` | File names, never paths |
| Optimizations | `OptimizationConfig` | JVM half live; rendering half inert |
| Created / updated | `Timestamp` | Seconds since epoch; stamped by the store |

**All three loaders exist now.** Only vanilla is *implemented*, and only
implemented loaders are offered — but a file naming Forge parses, stores and
round-trips today. Adding Forge is implementing it, not migrating everyone's
profiles. A test round-trips every variant.

**No secrets, ever.** A profile is exported, imported, copied between machines
and pasted into bug reports. A test asserts a serialised profile contains no
substring resembling a credential.

**`optimizations.rendering` is a map, not named booleans.** The matrix has ~40
entries and will grow; a map lets a toggle land without a schema migration, and
lets a profile written by a newer build round-trip through an older one without
losing settings it does not understand. It is inert today — the rendering work
needs a loader mod — and says so rather than pretending.

### 5.2 Storage

**JSON, one file per profile.** `architecture-options.md` does not mandate a
database, and one would be the wrong shape: the whole dataset is a handful of
small documents written when a setting changes. What matters is that a corrupt
file cannot take the launcher down, and one file per profile gives that for
free — a broken profile is one broken profile. A single SQLite file would put
every profile's fate in one place and add a dependency with its own corruption
modes.

| Requirement | How |
|---|---|
| Atomic persistence | `fs_safety::atomic_write` — temp, fsync, rename |
| Schema versioning | `schema` in every file; **a file from the future is refused, not guessed at** |
| Migrations | `migrate` walks forward one version at a time |
| Corruption detection | Parse failure falls back to `.bak` and **says so** |
| Safe defaults | Every field `#[serde(default)]`; absence is not an error |

Three failures are deliberately *not* treated as corruption, because a backup
would answer them identically: a schema from the future, a missing migration,
and a file whose recorded id disagrees with its directory. The last one matters
— trusting the file would let a profile impersonate another.

**Migration happens on open**, in `Launcher::new`, and is written back. Doing it
only on read would leave a profile at the old schema until something happened to
load it.

### 5.3 Operations

`create`, `clone`, `rename`, `delete`, `restore`, `purge`, `export`, `import`,
`validate`, `repair`.

**Clone carries the worlds.** A clone is for "snapshot before I break
something" as much as "same setup, different worlds". What it does *not* carry:
natives, whose stamp names an extraction that never happened in the copy's
directory (§6.4); and logs, which describe the original's runs.

**Rename does not move anything.** The id is the directory name and appears in
the logs. Display name and identity are different things, and this is where that
distinction earns its keep.

**Export is configuration only.** No worlds, no jars, no credentials. Machine-
specific facts — an absolute Java path, an external game directory — are dropped,
because they point at nothing on another machine.

**Import treats everything as untrusted.** The id is re-derived rather than
taken from the file; out-of-range values are replaced with defaults; a mod entry
whose file name is not a safe path component is dropped. **Every correction is
reported**, never silent.

### 5.4 Deletion

Worlds someone has spent years in are behind this. Three rules:

1. **Nothing is destroyed on the first ask.** Deletion *moves* the instance to
   `<data>/trash/<id>-<stamp>/`. A rename is a move; a delete should be too.
   Purging is a separate, explicit act.
2. **No path from profile metadata is recursively removed without being proved
   inside launcher storage.** `profile.json` is an ordinary file that a user, a
   bad merge or a hostile import can edit. `ProfileOps::removable_root`
   canonicalises both sides and fails closed — a path that does not resolve, a
   symlink pointing outward, the profiles root itself, all refused.
3. **Shared storage is never touched.** Deleting the only profile using a
   library still does not delete the library. Reclaiming that space is a
   separate operation that does not exist yet.

An `External` game directory is **never** deleted, and the receipt says so. The
predicate is `GameDirectory::is_launcher_owned`, which answers without needing
to know anything else about the path.

Two refusals, not one: `RefusingToDeleteOutsideStorage` and `NotInRecoveryArea`.
`shared/` *is* inside launcher storage and still must not be purged; telling
someone it is "outside launcher storage" would send them looking for a problem
that is not there.

**Deleting a running profile is refused outright** — moving a directory out from
under a live JVM corrupts whatever it was writing.

### 5.5 Validation

`ProfileHealth` is `Healthy` / `Warnings` / `Unusable`, and a `Defect` names one
specific thing. "Invalid profile" is not something a user can act on; "your Java
path points at a file that no longer exists" is.

Each defect answers two questions: `is_blocking` (does this stop a launch?) and
`is_auto_repairable` (can the launcher fix it unasked?). **Repair may reset a
setting or drop an entry that names nothing. It may never delete the user's
data, change which loader they chose, or download anything.** A rejected JVM
argument is left alone and reported — it is the user's own configuration, and
silently discarding it would be worse than refusing.

Validation does no network, no hashing, no JVM. Whether the *installation* is
complete is the install engine's question (§5b); this takes its answer as an
`InstallSnapshot` rather than duplicating it.

---

## 4b. Authentication state

`auth/session.rs`. Six states, matching the product spec one for one, with the
data each actually needs — not a pile of booleans:

```text
                   begin()
  SignedOut ──────────────────► Authenticating ── stage: RequestingCode
      ▲                              │              AwaitingUser
      │                              │              ExchangingTokens
      │ sign_out()                   │              VerifyingEntitlement
      │                              ▼
      │                          Authenticated  ── who you are.
      │                              │             NOT whether you own it.
      │                              ▼
      │                       EntitlementVerified ◄── the only launchable state
      │                              │
      ├──────────────────── TokenRefreshRequired
      │                              │
      └───────────────────────── AuthError
```

Three properties worth checking a diff against:

**Authentication and entitlement are separate types.** `MinecraftIdentity`
carries no ownership field. Ownership lives on `EntitlementProof`, which exists
only in the verified state. Conflating them in one struct is how a launcher
ends up treating "signed in" as "allowed to play"; here that mistake does not
typecheck.

**`entitlement_verified` refuses a proof that says no.** Presenting an
unentitled proof moves the session to `AuthError` carrying the explanation,
rather than parking it in a state whose name would be a lie.

**One gate.** `AuthSession::launch_credentials(now)` is the only way to obtain
something the launcher can start the game with, and `LaunchCredentials` has no
public constructor — holding one *is* the proof that the gate passed. It fails
closed:

| Situation | Result |
|---|---|
| Signed out / mid-sign-in / authenticated-but-unverified | refused |
| Token expired | refused |
| Entitlement says no | refused |
| Timeout, malformed response, service unavailable | never reaches `EntitlementVerified`, so refused |
| **Release build**, non-production identity | refused |
| **Release build**, entitlement not signature-verified | refused |

The last two are a second lock on the door the `dev-auth` compile-time
exclusion already bolts: a proof carries `authoritative`, and a release build
insists on it even though the only verifier that could clear it is the Mojang
one.

`services::Launcher` holds an `AuthSession` and asks it; `readiness()` reports
whatever the gate says rather than forming a second opinion.

---

## 4a. The identity seam

`docs/research/auth-feasibility.md` concluded that production Microsoft
authentication is **approval-dependent**: the code is understood, but the client
ID comes from a manual review we do not control. So identity sits behind a seam,
and "we have no client ID yet" is a normal typed state rather than a wall.

```
MinecraftIdentity     who is playing, how that was established, when it expires
AuthProvider          produces an identity
EntitlementVerifier   verify(response) -> EntitlementProof, fail-closed
AuthSession           the state machine, and the single launch gate
CredentialStore       persists the one long-lived secret
```

`EntitlementVerifier::verify` takes a raw entitlements response and returns a
proof or an error. The Mojang implementation ignores the unsigned `items` array
entirely and reads the list out of the RS256-verified JWT payload. Anything
unsigned, forged, expired, or unrecognised is an error — never a permissive
default.

`Launcher` holds an `Option<MinecraftIdentity>` rather than a Microsoft-specific
session. `IdentityKind` is carried **on the identity itself**, so no code path
can lose track of whether it is holding a real credential.

### The development identity

Enabled with `--features dev-auth`. It fabricates an obviously fake local
identity so the interface, profiles, mods and the install pipeline can be
exercised end to end while approval is pending.

Four barriers keep it away from users, strongest first:

1. **It is not in a release binary.** Non-default Cargo feature, plus a
   `compile_error!` in `auth/development.rs` that makes release + `dev-auth` a
   **build failure**. Verified in CI terms by
   `cargo check --release --features dev-auth` returning non-zero.
2. **Off by default even in debug.** Requires
   `FATHOM_DEV_AUTH=i-understand-this-is-not-real`. Without the feature the
   provider does not exist at all, whatever the variable says — `fathom-cli
   doctor` reports `providers 1` in that case and `providers 2` with the feature.
3. **It cannot play online, by physics rather than policy.** It holds
   `DEVELOPMENT-ONLY-NOT-A-REAL-ACCESS-TOKEN`. Mojang's session server rejects
   it. We are not blocking multiplayer; there is nothing to authenticate with.
4. **It never claims to be real.** `IdentityKind::Development`, username
   `DevBuild_NotReal`, a UUID that is deliberately not valid hex, and it reports
   `--userType legacy` rather than `msa` — telling the *game* it holds a
   Microsoft session would be a lie too. The interface carries a
   non-dismissible banner above the header on every screen, and it is never
   written to settings or the credential store.

The entitlement gate is untouched in release builds: the signature-verified
Mojang path is the only verifier compiled in, and a test asserts that every
provider present without the feature reports `is_production()`.

---

## 5a. Background work

Nothing slow runs on the interface thread. (This section describes the iced
build's worker, which is in the git history; the Tauri launcher keeps the same
rule with a thread per job behind its commands.) It held a single long-lived
subscription:

```text
  App  --Job-->  [ dispatch loop ]  --spawns-->  std::thread (blocking work)
   ^                                                    |
   +---------------- Event -----------------------------+
```

- **`launcher_core::tasks::Tasks`** is the bridge. `Launcher` owns interface
  state and is not `Send`; `Tasks` is four cheap handles (HTTP client, paths,
  auth service, credential store), is `Clone + Send + Sync`, and carries every
  blocking operation. A test asserts the `Send + Sync` bound, because that is
  the property the whole design rests on.
- **A thread per job**, not a pool: jobs are few, long, and independent. A
  2000-file asset download and a sign-in poll can be in flight together.
- **The dispatch loop only moves messages**, so it never blocks.
- **No async runtime.** `launcher-core` is blocking by design; a thread plus a
  channel is simpler than colouring the codebase async, and much smaller than
  adding `tokio`.

Two consequences worth calling out:

**Sign-in polls itself.** The worker runs the whole device-code flow: it emits
the code as soon as Microsoft returns it, then polls on Microsoft's own
interval, honouring `slow_down` and the expiry. There is no longer a "check
now" button, because there is nothing for the user to do but type the code.

**Cancellation is cooperative and prompt.** `tasks::Cancel` is an
`Arc<AtomicBool>` checked between files and *during* sleeps, in 100 ms slices.
Stopping a download or a sign-in takes effect in a fraction of a second rather
than at the end of a 30-second HTTP timeout.

Progress events are sent with `try_send` and may be dropped if the interface is
briefly behind. That is safe because each carries absolute counts rather than a
delta, so the next one corrects the display. Every other event is logged if it
is ever dropped.

**The four installation jobs.** `Prepare`, `Install`, `Verify` and `Repair` are
the whole of the install engine's exposure to the interface. Each returns a
`VerificationReport` and an `InstallSnapshot`, so the screen's idea of what is
on disk is always something the engine just measured rather than something the
interface inferred.

`Prepare` deliberately does three things in one job — fetch metadata, resolve
the artifact set, check what is present — because there is nothing useful to say
about the two intermediate states. `Verify` is a separate job precisely because
it is the expensive one.

**Stages, not percentages.** `InstallStage` travels to the interface unchanged.
The screen shows a bar only for `Downloading`, where a fraction can honestly be
computed; `ExtractingNatives` and `Verifying` get a sentence instead. A bar
sitting at 100% while work continues is a small lie the interface does not need
to tell, and there is a test asserting no other stage offers a fraction.

---

## 5b. Installation

`install/`. The engine that turns "1.8.9" into a complete, verified game
directory. It is deliberately separate from launching: nothing here knows about
credentials, and nothing here can start a process.

**The pipeline.** Each step narrows uncertainty, and each one is hash-checked
before the next is allowed to read it.

```
version_manifest_v2.json      (piston-meta)
  -> version document         hash checked against the manifest before parsing
  -> ResolvedInstall          client jar, libraries, natives, asset index,
                              logging config -- every entry carrying its
                              expected SHA-1 and size, and a target path
                              already proven to be inside the cache
  -> asset index              hash checked, then expanded into 734 objects
  -> DownloadManager          fetch what is missing, verify, finalise
  -> NativeLibraryManager     extract .dll/.so/.dylib into a scratch directory
  -> InstallationVerifier     re-hash everything and report the truth
```

**Resolution is total and offline-checkable.** `ArtifactResolver::resolve`
produces the whole artifact set up front — 771 items, 139.3 MB for 1.8.9 on
Windows — before a single byte of game content is fetched. That is what
`fathom-cli prepare` prints. It means the size and scope of an install are
knowable before it starts, and it means the resolver can be tested against a
version document without a network.

Only 1.8.9 resolves. `SUPPORTED_VERSION` is a constant and anything else is
refused by name, rather than half-supported.

**Downloads.**

- **Hashed while written**, not after. `HashingWriter` feeds SHA-1 as the bytes
  land, so a mismatch is known the moment the transfer ends and the file is
  never in a state where it looks complete but is wrong.
- **Written to `<name>.part`, then renamed.** A finished artifact appearing at
  its real path is proof it was verified. There is no window in which a partial
  file is indistinguishable from a real one, and `sweep_partials` clears
  leftovers from a killed process before a repair runs.
- **A bad hash is fatal, never retried.** `is_transient()` is false for
  `HashMismatch` and `SizeMismatch`. Retrying corrupt bytes is how a launcher
  eventually accepts them; the engine refuses the artifact and fails the
  install. This is the direct implementation of the rule in `THREAT_MODEL.md`
  §5.
- **Capped.** 512 MB per artifact, so a hostile or broken response cannot fill
  the disk before the size check would have caught it.
- **Cancellable mid-transfer.** Copying happens in 64 KiB chunks and checks the
  cancel flag between them, so closing the window does not leave a thread
  streaming 139 MB.

**Every target path is proven, not trusted.** `ArtifactCache` is the only thing
that decides where an artifact may land, and each accessor runs the name through
`fs_safety::contain`. A library path of `../../../../evil.jar` and an asset hash
that is really a path are both refused at resolution time, before any request is
made. Asset hashes must be exactly 40 hex characters. Native extraction goes
through `fs_safety::extract_zip`, which refuses traversal, absolute entries,
symlinks and decompression bombs, and only `.dll`/`.so`/`.dylib`/`.jnilib`
entries are written out at all.

**Installation state is derived, never remembered.** There is no "installed"
flag. `InstallationVerifier::verify` hashes what is on disk and reports one of
`NotInstalled`, `Installing`, `Installed`, `Corrupted`, `RepairRequired`.
Corruption outranks absence in that ranking: if any artifact hashes wrong, the
state is `Corrupted` even if others are also missing, because "you have the
wrong bytes" is the more serious thing to tell someone.

**Repair is the broken subset only.** `repair_set` is the defect list, not the
artifact list. Corrupt files are discarded first so they cannot be mistaken for
present, then only the missing ones are fetched. Repairing a deliberately
damaged 1.8.9 install transferred 194.3 KB rather than 139.3 MB, and left
`level.dat` and `options.txt` untouched — repair reads and writes the shared
cache and never touches profile data.

**Nothing here is authenticated.** Mojang's distribution hosts are unauthenticated
CDNs, so installation works before sign-in and needs no token. That is why the
install engine has no dependency on `auth/` in either direction. Entitlement is
enforced at launch (§4b), which is the only place it can be enforced honestly.

**We do not redistribute anything.** Every artifact comes from Mojang's own
hosts at install time. No Minecraft binary is embedded in this repository or in
a Fathom build.

### How the interface uses it

The Home screen's "Game files" card is a rendering of a `ResolvedInstall` and a
`VerificationReport`. It offers Resolve, Download / Repair, Verify all files and
Re-resolve, and it distinguishes claims the engine can support:

- **"present" is not "verified".** `Prepare` runs an existence check, and the
  card says `771 of 771 present (not hashed)`. Only after Verify does it say
  *hashed and correct*. Conflating the two is how a launcher tells someone their
  installation is fine when it has never looked at a byte of it.
- **Defects are named**, up to six with a count for the rest, each labelled
  `missing` or `bad hash`. "Something is wrong" is not an actionable thing to
  read.
- **The state drives the button.** `Corrupted` offers Repair, because corrupt
  bytes must be discarded before a download will replace them rather than skip
  them as present. Everything else offers Download.
- **The blocker names the actual problem.** An installation with every artifact
  present but no natives unpacked used to read "0 game files still to download";
  `InstallSnapshot::message` now special-cases it, and a test asserts the message
  contains no misleading count.

**`readiness` is a pure function.** It takes an `InstallSnapshot` rather than
computing one, because deciding whether an installation is complete means
hashing 139 MB and `readiness` is called while a frame is being drawn. `None`
means *not checked yet* and produces `Blocker::InstallNotChecked` — deliberately
different from *nothing installed*, which would send a user to re-download files
they already have. Two tests pin both halves of that.

**Switching profile discards the snapshot.** Profiles have their own natives
directories and may pin different versions, so what was learned about one says
nothing about the next.

---

## 5c. Loaders and mods

### 5c.1 The loader seam

A loader changes four things about a launch: the main class, the classpath, the
arguments, and whether `mods/` means anything. `LoaderProvider` is the seam that
lets each describe those four without the launch path knowing which loader it is
dealing with.

| Loader | Status |
|---|---|
| Vanilla | Implemented. Adds nothing, reads no `mods/`, contacts nobody. |
| Legacy Fabric | Implemented, against the project's own metadata service. |
| Forge | Implemented for 1.8.9 Custom installations (`docs/forge.md`). Fathom's own client does not run on it. |

`LoaderInstallation` is **additive**: the vanilla launch is computed first and
this is layered on top. A loader that could *replace* parts of the vanilla plan
would be much harder to reason about, and none of the three needs to.

`ModDirectorySemantics` has three variants rather than a boolean, because Forge
loads coremods earlier and by different rules. Vanilla's `None` is not "mods
unsupported for now" — nothing in an unmodified client reads that directory, so
a jar placed there is inert, and the type lets the launcher say so plainly.

**Forge reads its installer, never runs it.** `forge.rs` supplies what Forge needs
that Legacy Fabric does not: launchwrapper as the main class, FML's tweaker in the
game arguments, coremod semantics, and its libraries -- resolved from the install
profile inside Forge's installer jar, which is downloaded, checked against its
SHA-1 and opened as a zip, never executed. Every library has a hash before it is
fetched. The licence decision is recorded in `mod-policies.md` §2 (2026-10-13):
Forge's own files, from Forge's own repository, at the user's request, never
bundled. See `docs/forge.md`.

### 5c.2 Legacy Fabric

Three metadata calls, all to `meta.legacyfabric.net`:

```text
/v2/versions/game                       which Minecraft versions are supported
/v2/versions/loader/<mc>                every loader build, newest first
/v2/versions/loader/<mc>/<loader>       main class, libraries, mappings
```

**We do not scrape.** Those endpoints and two Maven repositories are the only
places this module looks, and all four are on the allowlist by name.

**Nothing unverified reaches the classpath.** A loader library is code that runs
with the game's privileges, so every artifact needs a hash before it is fetched.
The metadata declares one for each shared library; the loader jar and the
mappings, which the profile names by coordinate only, get theirs from the
repository's own `.sha1` sidecar. No hash means no download —
`Expectation` was deliberately *not* given an "unverified" variant to
accommodate this, because weakening the download manager's guarantee to fit one
caller would have undone the install engine's central property.

**Repository URLs come from a downloaded document**, so each goes through
`descriptor_from_url`. A library pointed at an unlisted host is refused rather
than fetched, and there are tests for `evil.example`,
`maven.fabricmc.net.evil.example` and plain `http://`.

### 5c.3 Three types, not one

| Type | Answers |
|---|---|
| `ModDefinition` | What is this mod, and what may we do with it? |
| `InstalledMod` | Which bytes are on disk, and where? |
| `ProfileModConfiguration` | Which mods does this profile want, and are they on? |

Collapsing them into "a list of jar filenames" is the mistake this milestone
exists to avoid. That shape cannot express *`OptiFine` may never be
auto-downloaded*, cannot say *this needs Fabric API*, and cannot tell a user why
their game broke when two renderer patches fought.

`ModDefinition` carries provenance as a first-class field. A mod the user
dropped in and a mod from a catalogue are different things, and the difference
survives into the interface — nobody should have to guess whether the launcher
chose something on their behalf.

### 5c.4 Mods are untrusted code

A mod runs inside the JVM with the game's privileges. Sandboxing is not possible
for 1.8.9, so the job is care about *which* bytes get there and honesty about
what is known. Six rules, each a thing the engine does not do:

1. **Verify what we fetch.** A download needs a published hash **and** an
   allowlisted source **and** a permitting policy. Any one missing means no
   download.
2. **Validate sources against metadata.** A download source is a `Destination`
   and a path, not a URL a caller supplies. There is no way to express "fetch
   this from anywhere".
3. **Never execute an installer.** Nothing in the engine starts a process.
4. **Never launch helper executables.** Same.
5. **Prevent traversal.** Every file name goes through `contain_in_game`; every
   store hash is validated as forty hex characters before it becomes a path.
6. **Do not inspect jars by running their code.** Four bytes are read to check a
   file is an archive at all. No class loading, no walking archive entries for
   metadata.

Rule 6 costs something: a user-supplied jar is `Unknown` in every field. That is
the honest trade. Parsing `fabric.mod.json` out of an archive would be more
convenient and would mean trusting an attacker-controlled document to describe
itself.

### 5c.5 Policy

`catalog.rs` is `docs/research/mod-policies.md` as data. Where the research said
"unclear", the entry says `RedistributionPolicy::Unclear`, which **behaves
exactly like forbidden** — that is why it is a distinct variant rather than an
`Option`.

`OptiFine`, `MemoryFix` and `MouseDelayFix` are in the catalogue *precisely so
the launcher refuses them* and can explain the conflict, not so it can offer
them. The catalogue holds no download URL for anything we may not fetch: a URL
sitting in the source is an invitation to use it. There are tests for both.

`may_bundle()` returns false unconditionally, so §7's "never bundled in our
installer" is stated in code rather than only in prose.

### 5c.6 Resolution

The point is that Minecraft never crashes because two mods fought. Resolution
reports *"Fathom Optimizations requires Fabric API"* or *"`OptiFine` overlaps
with Fathom Optimizations"* before anything starts.

Severity, not a boolean: a missing hard dependency blocks; two mods patching the
same renderer is a warning. Reporting both as "problem" would leave the user
unable to tell which one they must act on.

`ConflictKind` has four variants — `Incompatible`, `Supersedes`,
`OverlappingOptimization`, `KnownBad` — because "conflict" alone cannot tell
someone whether their game will fail to start or merely render text twice.

Cycle detection is an **iterative** depth-first search with an explicit stack.
The input is a list from a file a user can edit, and a deeply nested graph should
produce a report rather than a stack overflow; there is a 5,000-node test.

It is a **check over a fixed set**, not a version solver. This ecosystem has no
reliable version metadata to solve over, and a solver that is sometimes right
would produce failures harder to explain than the ones it prevented.

### 5c.7 Toggling without renaming

The obvious way to disable a mod is to rename `x.jar` to `x.jar.disabled`. It is
also the wrong way: it is destructive, it is not idempotent, it loses *why* a mod
is off, and it cannot express "enabled but its dependency is missing".

Instead: the enabled set is a fact about the profile, and the mods directory is a
**consequence**, rebuilt from it.

```text
ProfileModConfiguration -> resolve -> effective set -> materialize -> mods/
```

Toggling is a field assignment. Materializing is idempotent. A test flips a mod
four times and asserts no `.disabled` file ever appears.

**Only files the launcher placed are ever removed.** A `.fathom-mods.json`
manifest records what was materialized — the same pattern as the natives stamp.
A jar someone dropped in by hand is not in it, so it is left alone and *adopted*
into the configuration instead. Deleting a user's file because it was not in a
list we generated would be the worst bug this module could have, and there is a
test named for it.

The store is content-addressed at `<data>/mods/<2 hex>/<sha1>.jar`, so two
profiles using the same mod hold one copy. It is kept out of `shared/`, which is
Mojang content fetched from Mojang's hosts; a user-supplied jar is a different
kind of thing.

---

## 6. Launching

The chain, in order. Each step can only be reached by passing the one before it.

```
authentication ──► entitlement ──► profile ──► installation ──► Java ──► plan ──► process
   AuthSession      EntitlementProof   Profile    quick_check     probe   LaunchPlan  GameProcess
```

### 6.1 Preflight

`Launcher::preflight` establishes, in this order:

1. **The authentication gate.** `auth.launch_credentials(now)` — the single
   place a launch is authorised (§4b). Its refusal is carried verbatim into
   `LaunchRefusal::NotAuthorised`, so *expired token* and *not entitled* stay
   distinguishable all the way to the screen.
2. **The profile is runnable.** An unimplemented loader is refused by name;
   the profile's directories are created or the launch is refused.
3. **Java exists, is the right major, and is still on disk.** The last check
   matters: a runtime found at start-up can be uninstalled before Play is
   pressed.
4. **The profile's own JVM arguments pass policy.** Refused, not silently
   dropped — a user who typed a flag deserves to be told it was not used.

Then `launch` adds two more, in the order that fails cheapest first:

5. **The installation.** `quick_check` — existence, not hashes. Hashing 139 MB
   on every Play press would be the wrong trade; Verify exists for the stronger
   answer, and a missing file is caught here either way.
6. **The natives are current.** See §6.4.

Ordering is not cosmetic. The first thing the user is told is the first thing
they have to fix.

### 6.2 `LaunchPlan`

Immutable by construction: no public fields, no setters, produced whole by
`build_plan`. Everything is decided before it exists, so nothing can be adjusted
between validation and spawning.

| Part | Accessor |
|---|---|
| Java executable | `java()`, `java_major()` |
| Working directory | `working_dir()` — the profile's isolated `minecraft/` |
| Profile directory | `profile_dir()` |
| Main class | `main_class()` |
| Classpath entries | `classpath()`, `classpath_argument()` |
| Native library directory | `natives_dir()` |
| JVM arguments | `jvm_args()` |
| Minecraft arguments | `game_args()` — typed, see below |
| Environment variables | `environment()`, `removed_environment()` |
| Log destination | `log_file()` |

**Redaction is structural, not a search.** A game argument is an `Argument`:

```rust
enum Argument {
    Plain(String),
    Sensitive { actual: Secret, redacted: String },
}
```

Substitution produces both renderings in one pass, so an argument built from a
secret carries its own redacted form. `Debug` on `Argument` *and* on
`LaunchPlan` prints the redacted form, which means the plan is safe to put in a
log line or an error chain — the trap the previous design had, where deriving
`Debug` on a struct of raw strings would have printed the token.

The only way to the real values is `argv_exposing_secrets()`, named to be
conspicuous in review.

This is not theoretical. 1.7-era templates use `${auth_session}`, which embeds
the token inside `token:<token>`; a search-and-replace renderer has to find it
in the middle of a longer string, and a typed one cannot miss it. There is a
test for exactly that.

**Environment.** Nothing is added. `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and
`JDK_JAVA_OPTIONS` are **removed**, because each silently injects JVM flags that
would never have been through `jvm_args::validate` — bypassing the policy
entirely. A user who wants extra flags has a profile field for them, where they
are checked.

### 6.3 The process

`minecraft_runtime/process.rs`.

- **No shell.** `Command::new(java).args(...)` reaches `CreateProcessW` /
  `execvp` directly. No `cmd.exe`, no PowerShell, no `sh -c`, so no quoting
  rules to get wrong and no injection surface.
- **Not a daemon.** The child is deliberately not put in a job object: the game
  survives the launcher exiting, which `security-model.md` §7 requires.
- **Output is captured** by one thread per pipe — a thread rather than a poll
  loop because a child whose pipe buffer fills blocks on write and never
  reaches the point of exiting.
- **Lifecycle**: `pid()`, `state()`, `exit_code()`, `started_at()`,
  `stopped_at()`, `elapsed()`, `outcome()`.

**Every captured line is scrubbed twice** — by `logging::scrub`, and by this
launch's own secret values by exact match. The second pass is not
belt-and-braces; see §6.6.

### 6.4 Natives

Extracted per profile, never shared, because the directory is on the JVM's
library search path and one profile must not be able to put a `.dll` on
another's.

A `.fathom-natives.json` stamp records the version id, the platform, and the
SHA-1 of every archive extracted. Before each launch, `is_current` compares it
and re-extracts on any disagreement. **Fails closed**: a missing stamp, an
unreadable one, or one that disagrees all mean *extract again*. Re-extracting
costs a fraction of a second; loading a previous version's LWJGL costs an
`UnsatisfiedLinkError` no user could be expected to diagnose.

Extraction itself goes through `fs_safety::extract_zip` (§THREAT_MODEL 4), and
only `.dll`/`.so`/`.dylib`/`.jnilib` are written.

### 6.5 Failure, before and after

The split matters: a **refusal** means nothing started, so nothing needs
cleaning up. An **outcome** means a process existed.

| `LaunchRefusal` (before) | `Outcome` (after) |
|---|---|
| `NotAuthorised` — carries `LaunchDenied` | `Normal` — exit 0 |
| `LoaderNotImplemented` | `JavaFailedToStart` |
| `InvalidProfile` | `MissingLibrary` |
| `NoJava` / `IncompatibleJava` / `JavaMissing` | `InvalidNatives` |
| `InstallationIncomplete` | `BadJvmArguments` |
| `NativesUnavailable` | `Crashed` |
| `BadJvmArguments` | |
| `AlreadyRunning` | |

Outcomes are classified from the **captured output**, not the exit code alone,
because the JVM reports nearly everything as exit 1. Order is deliberate: a
rejected flag also prints *Could not create the Java Virtual Machine*, and
reporting that instead would send the user to inspect their Java installation
rather than their arguments. There is a test pinning that precedence.

`suggests_repair()` is true only for file problems. Telling someone to
re-download 139 MB because they typed a bad flag would waste their time.

### 6.6 The token on the command line

1.8.9 takes `--accessToken` as an argument, so it is visible in the OS process
list to anything running as the same user. That is inherent to vanilla and is
not something a launcher can fix; `docs/research/token-exposure.md` records the
problem, what a Legacy Fabric bootstrap might do about it, and the five
questions that have to be answered before anything is built.

`LaunchPlan::exposes_a_secret_argument()` reports it programmatically, and
`fathom-cli plan` prints it — so the day a launch path exists that does not
expose a token, the difference is observable rather than assumed.

**A real leak this milestone found and fixed.** Minecraft 1.8.9 prints
`(Session ID is token:<access token>:<uuid>)` to stdout on every start. The
launcher captures stdout to a file, so before the fix every launch wrote a live
session token to disk in plaintext. `logging::scrub` did not catch it: the
phrase is space-separated words, which no marker rule matched. It was found by
launching the game and reading the log — not by review. Two changes:
`PHRASE_MARKERS` now includes the wording, and the pump additionally removes
this launch's own secret values by exact match, which does not depend on
guessing how the game chose to print them.

### 6.7 Duplicate launches

Two JVMs writing the same `level.dat` and `options.txt` is how worlds get
corrupted. `RunningGames::claim` reserves a profile before any of the work
above, and returns a guard that releases the reservation if it is dropped
without being fulfilled — so a *refused* launch does not leave a profile stuck.

It is a claim rather than a check because a check would race: two threads could
both observe "not running" before either had spawned anything. The place in the
map is taken under the same lock that observed it was free. A test runs 24
threads at a barrier and asserts exactly one wins.

**In-process only.** Two separate launcher processes have separate registries,
so this does not prevent a second copy of Fathom launching the same profile.
That is the same gap as §8.5 and is not fixed here.

## 7. Build, test, run

```bash
cargo test --workspace
```

```bash
cargo clippy --workspace --all-targets -- -D warnings
```

```bash
cargo deny check
```

```bash
cargo run -p launcher-cli -- doctor
```

The install engine can be driven end to end from the CLI, which is the fastest
way to confirm a build really can produce a valid 1.8.9 installation:

```bash
cargo run -p launcher-cli -- prepare
```

```bash
cargo run -p launcher-cli -- install
```

```bash
cargo run -p launcher-cli -- verify
```

```bash
cargo run -p launcher-cli -- repair
```

The launch chain, including the development identity:

```bash
cargo run -p launcher-cli --features dev-auth -- plan
```

```bash
cargo run -p launcher-cli --features dev-auth -- launch
```

The profile subsystem is drivable end to end:

```bash
cargo run -p launcher-cli -- profile list
```

```bash
cargo run -p launcher-cli -- profile validate <id>
```

```bash
cargo run -p launcher-cli -- profile delete <id>
```

`profile` alone covers create, show, clone, rename, delete, trash, restore,
purge, validate, repair, export and import.

`plan` prints every part of the `LaunchPlan` and the redacted command line
without starting anything. `launch` runs the game and reports the exit outcome.
Both need `FATHOM_DEV_AUTH=i-understand-this-is-not-real` in the environment
before the development identity will activate.

```bash
cargo run -p launcher-ui
```

Sign-in needs an Azure application id, which Microsoft must approve for the
Minecraft API before `api.minecraftservices.com` will answer:

```bash
FATHOM_AZURE_CLIENT_ID=<your-application-id> cargo run -p launcher-ui
```

---

## 8. Known limitations

1. **Launching the game is still synchronous.** `LaunchPlan::spawn` returns as
   soon as the JVM starts, so it is fast, but building the plan resolves the
   artifact set on the interface thread. That is path construction rather than
   I/O, so it is fast, but it is the one path that has not moved to the worker.
2. **The game's stdout and stderr are not captured.** `spawn` pipes them and
   then drops the handles. Wiring them into the log viewer, through
   `logging::scrub`, is the obvious next step.
3. **`launcher-core`'s internals are public.** See §3, rule 5.
4. **No self-update.** No update endpoint exists; `Destination::LauncherUpdates`
   points at a placeholder host.
5. **Advisory instance locking is not implemented.** Two launcher processes
   writing to the same profile would race. All writes are atomic, so corruption
   is unlikely, but "unlikely" is not "prevented".
6. **The Mojang entitlement key is unverified against a live response** (§4).
7. **No integration test that actually launches the game.** Command construction
   is well covered; the end-to-end path is not, because it needs an approved
   Azure app and a real account.
8. **The install card's own state is not persisted.** Closing the launcher loses
   the resolved set, so the next start shows "not resolved yet" until Resolve is
   pressed. Everything it would say is derivable from disk, so this costs a
   second, not correctness — but it is a rough edge.
9. **No automated test drives the real window.** The presentation logic is unit
   tested and the engine is covered end to end, but the click-through path is
   verified by hand. Synthetic mouse input against a `winit` window proved too
   unreliable to build a suite on.
10. **Duplicate-launch prevention is in-process only** (§6.7). A second copy of
    the launcher has its own registry and would not be stopped.
11. **The game's own network activity is not governed by the allowlist.** The
    allowlist constrains the launcher's sockets. Once Minecraft is running it
    makes its own connections — to session servers, and to whatever server the
    player joins. That is the game's behaviour, not the launcher's, and the
    Privacy screen says so rather than implying otherwise.
12. **Nothing reports a crash back to the interface yet.** `GameStatus` and
    `Outcome` exist and the CLI prints them; the desktop app currently reports
    only that the game started. Wiring the exit outcome into a notice is the
    obvious next step.
13. **The profile screen's operations are not click-verified.** They compile,
    lint clean, and every operation behind them is covered by integration tests
    and demonstrated through `fathom-cli profile`. Synthetic mouse input
    against the `winit` window is too unreliable to drive a screenshot suite
    (§9), so the buttons themselves were not pressed by hand this milestone.
14. **`optimizations.rendering` does nothing yet.** The toggles are stored and
    round-trip, but the rendering work needs a loader mod that does not exist.
15. **Reclaiming shared storage is not implemented.** Deleting every profile
    leaves the 139 MB artifact cache in place, by design; a "clean up unused
    artifacts" operation is a separate, deliberate feature.
16. **No mod is downloadable yet.** The policy engine, the hash requirement
    and the allowlist check are all in place, but nothing in the catalogue is
    both permitted and has a verified source, so `ModEngine::fetch` currently
    only ever refuses. That is a true statement about the research rather than
    a missing feature.
17. **Legacy Fabric resolves but does not launch.** `LoaderInstallation` is
    produced and verified against the real metadata; wiring it into
    `build_launch_plan` — prepending the loader classpath and swapping the main
    class — is the next step. `Loader::is_implemented` still reports only
    vanilla, so nothing offers it prematurely.
18. **Mod definitions are not read from jars.** A user-supplied mod is `Unknown`
    in every field by design (§5c.4 rule 6). A future index integration would
    fill them in from metadata someone published, not from the archive.
19. **Production authentication is still blocked** on Microsoft application
    approval. Everything downstream of the gate is exercised with the
    development identity; the production path differs only in which
    `AuthProvider` fills `MinecraftIdentity`.

---

## 9. Dependencies

`launcher-core` (10 direct):
`serde`, `serde_json`, `thiserror`, `tracing`, `tracing-subscriber`,
`directories`, `ureq` (rustls, no cookies), `sha1`, `sha2`, `base64`,
`jsonwebtoken`, `keyring`, `zip`.

`launcher-desktop`: `tauri` (no plugins), `rfd` (file picker), `serde`,
`serde_json`, `base64`, `tracing`.

`launcher-ui` (2 direct): `launcher-desktop` and `tauri`, only to keep
`cargo run -p launcher-ui` starting the same launcher.

`launcher-cli` (0 beyond the core).

**Features.** `dev-auth` on `launcher-core`, forwarded by `launcher-desktop` and `launcher-ui`. Not
default anywhere, and unbuildable in release. See §4a.

`deny.toml` enforces the licence allowlist recommended in `mod-policies.md` §9
and bans a second HTTP or TLS stack outright, so an accidental `reqwest` or
`native-tls` is a build failure rather than something a reviewer has to spot.
