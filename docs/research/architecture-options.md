# Architecture Options

Status: **research / pre-implementation**. Nothing here is built yet.
Scope: launcher + runtime manager for **Minecraft: Java Edition 1.8.9 only** (initially).
Date of research: 2026-08-23.

---

## 0. What we are actually building

Before choosing a stack, it is worth writing down how small the launcher really is. Over-scoping
the UI is the main way this kind of project ends up with a 300 MB Electron bundle.

The launcher must:

1. Run a Microsoft OAuth login (device-code or loopback-redirect authorization-code + PKCE).
2. Exchange tokens through Xbox Live -> XSTS -> Minecraft Services.
3. **Verify Java Edition entitlement** and fetch the profile.
4. Store *only* a Microsoft refresh token in the OS credential store.
5. Read `version_manifest_v2.json`, resolve the 1.8.9 version JSON, and download
   client jar + 37 libraries + natives + the `1.8` asset index and objects **from Mojang's own
   CDN**, verified by SHA-1.
6. Provision a JRE (or locate one) matching `javaVersion: { component: "jre-legacy", majorVersion: 8 }`.
7. Build a classpath and command line, substitute `${auth_player_name}`, `${auth_uuid}`,
   `${auth_access_token}`, etc., and spawn `java`.
8. Show progress, show logs, and exit or sit idle at ~0% CPU.
9. Update itself, safely and verifiably.

Ground truth pulled from the official 1.8.9 version JSON on `piston-meta.mojang.com`:

| Field | Value |
|---|---|
| `id` | `1.8.9` |
| `mainClass` | `net.minecraft.client.main.Main` |
| `javaVersion` | `{ component: "jre-legacy", majorVersion: 8 }` |
| `assets` | `1.8` |
| client jar size | 8,461,484 bytes |
| library count | 37 |
| LWJGL | `org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209` (2.9.2 under macOS rules) |
| arg style | legacy `minecraftArguments` string, **not** the modern `arguments` object |
| `minimumLauncherVersion` | 14 |

Two consequences fall out of that table immediately:

- 1.8.9 is a **legacy-format** version. Any launcher core must implement the old
  `minecraftArguments` string-substitution path, not just the 1.13+ `arguments` array. If we ever
  add newer versions we need both.
- 1.8.9 ships **LWJGL 2.9.4**, which is the root cause of several input and window problems
  (see `performance.md`). Whether we stay on LWJGL 2 or swap in LWJGL 3 is the largest
  Minecraft-side technical decision we have.

That is not a large program. It is a *high-consequence* program: it handles OAuth tokens, writes
to the user's filesystem, spawns processes, and updates itself. **Correctness and blast radius
matter far more than UI framework expressiveness.**

---

## 1. Evaluation criteria and weights

| Criterion | Weight | Why |
|---|---|---|
| Security / memory safety | **Very high** | Handles OAuth tokens, parses untrusted JSON/ZIP, self-updates |
| Idle CPU + RSS | **Very high** | Launcher sits alongside the game; it must not compete for cycles |
| Startup time | High | Perceived speed is the product's core claim |
| Dependency surface | High | Every transitive dep is supply-chain risk and audit burden |
| Process management | High | Must spawn/monitor/kill a JVM cleanly on 3 OSes |
| Credential storage | High | DPAPI / Keychain / Secret Service integration required |
| Filesystem safety | High | Zip-slip, path traversal, atomic writes, concurrent instances |
| Cross-platform | Medium-high | Windows first, but macOS/Linux must not be architecturally excluded |
| Auto-update | Medium-high | Signed, verifiable, non-tracking |
| UI quality | Medium | Real, but the UI is ~6 screens |
| Maintainability / dev velocity | Medium | Small team assumed |
| Binary size | Medium | Nice-to-have; a 15 MB launcher is fine, a 250 MB one is not |
| Windows integration | Medium | Shortcuts, DPAPI, WebView2-free, code signing |

---

## 2. Launcher core language

### 2.1 Rust

**For**

- No GC, no runtime, no interpreter. Idle RSS for a headless core is single-digit MB.
- Memory safety across exactly the attack surface we have: JSON parsing, ZIP/jar extraction,
  HTTP responses, and a self-updater. These are the classic memory-corruption sinks.
- `rustls` gives a memory-safe TLS stack with no OpenSSL C dependency and no system-OpenSSL
  version-skew problems.
- First-class cross-compilation; static-ish binaries; `windows-rs` gives direct, typed access to
  DPAPI and the Win32 credential APIs without a C shim.
- `cargo-audit` / `cargo-deny` give machine-checkable supply-chain **and licence** policy, which
  matters enormously given the licence minefield documented in `mod-policies.md`.
- Strong ecosystem for exactly our needs: `reqwest`/`hyper`, `serde`, `sha1`/`sha2`, `zip`,
  `keyring`, `jsonwebtoken` (for validating Mojang's entitlement JWT).

**Against**

- Slower to write UI in than C# or TypeScript. Real cost, not imaginary.
- The async ecosystem (`tokio`) is a meaningful dependency-count jump. Mitigable: for a launcher, a
  bounded thread pool with blocking I/O is entirely adequate and much smaller.
- Compile times.

**Verdict: strongest candidate.** The risk profile of this program is a near-perfect match for what
Rust is good at.

### 2.2 C++

**For**

- Maximum control, smallest possible footprint, no runtime.
- Every OS API directly available.

**Against**

- We would be hand-writing memory-unsafe code that parses attacker-influenceable JSON, ZIP
  archives, and TLS responses, and that performs privileged self-update file replacement. For a
  security-sensitive network client with a small team, this is the wrong trade.
- Dependency management (vcpkg/Conan) is materially worse than Cargo for auditability.
- No meaningful performance advantage over Rust for I/O-bound work.

**Verdict: rejected for the core.** The only thing it buys over Rust is familiarity.

### 2.3 C# / .NET

**For**

- Best-in-class Windows integration and developer velocity.
- NativeAOT (.NET 8+) removes the runtime-install requirement and drops startup into the
  tens-of-milliseconds range.
- `System.Text.Json`, `HttpClient`, and MSAL are mature.
- Memory-safe by default.

**Against**

- Even with NativeAOT, a self-contained binary lands roughly in the 15-40 MB range, and idle RSS is
  higher than Rust's because of the GC heap and its background threads.
- MSAL is the obvious auth library but pulls a wide dependency graph and is oriented toward
  Entra ID / enterprise flows; the Minecraft chain needs the raw Xbox Live/XSTS calls anyway.
- NativeAOT plus reflection-heavy libraries is still a friction point; trimming warnings are common.
- Cross-platform GUI means Avalonia (good) or MAUI (Windows/macOS-centric, heavier).

**Verdict: a genuinely viable second choice**, especially if the team's existing strength is C#. It
loses to Rust on idle footprint, dependency-surface auditability, and single-binary cleanliness --
not on capability.

### 2.4 Java / Kotlin (for the launcher itself)

**For**

- One language for launcher and mod. Real conceptual simplification.
- Easy to reason about the JVM we are about to launch.

**Against**

- The launcher would need its *own* JVM, and it cannot be the 1.8.9 `jre-legacy` Java 8 one -- so we
  ship two JVMs, or we ship a JVM the game cannot use. Either is bad.
- Idle RSS for a JavaFX/Swing launcher is 120-250 MB and cold start is 0.8-2 s. That directly
  contradicts the product's central claim.
- jlink/jpackage/GraalVM native-image can improve this, but native-image plus a reflective UI
  toolkit is a known pain path.

**Verdict: rejected for the launcher core.** Correct for the *mod*, wrong for the launcher.

### 2.5 Go (evaluated for completeness, not requested)

Memory-safe, fast startup, single binary, excellent stdlib HTTP/crypto. Loses on GUI (no good native
option) and on idle RSS versus Rust (GC plus runtime threads). Not recommended, but it would not be
an irresponsible choice for a *headless* CLI core.

---

## 3. UI framework

This is the decision most likely to be made badly by defaulting to what is popular.

### 3.1 The WebView question (Tauri / Dioxus-desktop / Wry)

Measured baseline from an independent comparison on Linux/X11 (Kalbertodt, 2023): Tauri reached
final render in **366-417 ms** versus egui's 200-300 ms and iced's 217-333 ms; the Tauri window
itself took 100-150 ms to appear versus iced's 33-50 ms. Tauri's *binary* was smallest at 5 MB --
but that number excludes the platform WebView, which is the actual cost.

Against WebView for **this** product specifically:

- On Windows it means **WebView2**, a Microsoft-managed Edge/Chromium runtime. It runs as additional
  processes, typically adding 80-200 MB RSS beyond the Rust process, and it has its own update
  channel and its own network behaviour that we neither control nor can audit. For a product whose
  headline promise is *"no unrelated network traffic, no tracking,"* embedding a browser engine
  maintained by a third party is a direct contradiction we would have to explain away in the README.
- On Linux it means WebKitGTK, whose version skew across distributions is a recurring source of
  blank windows and GPU bugs.
- Multi-process architecture conflicts with "no persistent background services" -- not literally, but
  it makes the process tree look exactly like the thing we are differentiating from.
- The IPC boundary between Rust and JS becomes an internal attack surface we must design carefully
  (command allowlisting, CSP) for zero product benefit at our UI complexity.

Tauri is an excellent framework. It is the wrong framework for a launcher that markets itself on not
embedding other people's runtimes.

**Verdict: rejected**, on privacy-positioning and footprint grounds rather than technical ones.

### 3.2 egui (immediate mode, wgpu/glow)

Fastest to build; single process; ~15-20 MB binary; low RSS; predictable frame cost; AccessKit
integration gives it a real accessibility story (Windows Narrator works). Downside is the aesthetic
ceiling -- the 2026 survey literature describes it bluntly as feeling "like a debug UI that grew up."
Achievable to theme, hard to make *beautiful*.

**Verdict: excellent prototyping target; acceptable shipping target; not the best one.**

### 3.3 iced (retained mode, Elm architecture, wgpu + cosmic-text)

Measured startup 217-333 ms, window in 33-50 ms, ~17 MB binary, input lag comparable to egui.
Retained-mode with a real layout engine and real text shaping, so the UI can look designed rather
than tooled. The strongest maturity signal is that System76 built the entire COSMIC desktop
environment on it -- a far more demanding consumer of the toolkit than our launcher will ever be,
which means the framework is now exercised at production scale.

Downsides are honest: API churn between releases, and documentation that lags the code.

**Verdict: recommended.**

### 3.4 Slint

Declarative `.slint` DSL compiled to native Rust, genuinely small runtime, good embedded pedigree,
good accessibility. Technically a strong fit.

The blocker is licensing: Slint is tri-licensed (GPLv3 / royalty-free-with-conditions / paid
commercial). For an open-source project the GPLv3 arm works, but it forces the *entire launcher* to
GPLv3 and forecloses a permissive licence. That is a large strategic commitment to make in exchange
for a UI toolkit.

**Verdict: viable, but only if we consciously choose GPLv3 for the whole project.**

### 3.5 Xilem

The Linebender successor to Druid. Architecturally the most promising long-term Rust UI, and the team
has the deepest track record in the space. Explicitly **not production-ready as of 2026**.

**Verdict: watch, do not build on.**

### 3.6 Native toolkits

- **WinUI 3 / windows-rs** -- best Windows look; Windows-only; WinUI 3 from Rust is painful.
- **Avalonia (C#)** -- genuinely good cross-platform XAML, mature, real theming. The best option
  *if* the core is C#. Pairs poorly with a Rust core.
- **Qt (C++)** -- mature and beautiful, but LGPL dynamic-linking obligations plus a large deployment
  footprint, and it drags the core toward C++.
- **GTK** -- Linux-first; poor Windows story.

---

## 4. Minecraft-side (in-game) framework

This is where the licence research changes the answer, so read `mod-policies.md` alongside it.

### 4.1 The two ecosystems for 1.8.9

| | **Minecraft Forge 1.8.9** | **Legacy Fabric** |
|---|---|---|
| Maintenance | Unmaintained for 1.8.9 | Actively maintained; 1.8.9 confirmed present in `meta.legacyfabric.net/v2/versions/game` |
| Loader licence | GitHub API reports `NOASSERTION`; Forge is LGPL-2.1 **with additional terms** -- needs legal review | `Legacy-Fabric/fabric-loader-1.8.9` -> **Apache-2.0** |
| Mappings | MCP -- historically redistribution-restricted | `Legacy-Fabric/yarn-1.8.9`, `Legacy-Intermediaries` -> **CC0-1.0** |
| Bytecode manipulation | Coremods / LaunchWrapper ASM, or Mixin bolted on (SpongeMixins, UniMixins, GasStation) | Mixin is native to the loader |
| Mixin version | via third-party shims, commonly pinned to 0.7.11 | modern Mixin, **MIT** |
| Existing 1.8.9 PvP mod ecosystem | **Everything** -- OptiFine, Patcher/PolyPatcher, FoamFix 0.6.3a, BetterFps, MemoryFix, MouseDelayFix | Sparse for 1.8.9 |
| Toolchain | ForgeGradle 2.x, ancient Gradle, brittle | Loom, modern Gradle, pleasant |

The tension is stark and worth stating plainly:

> **Legacy Fabric is dramatically better on licensing, tooling, and maintenance.
> Forge 1.8.9 is where every mod our users already run actually lives.**

### 4.2 Recommendation: dual-loader, Legacy-Fabric-default

1. **Our own optimizations ship as a Legacy Fabric + Mixin mod.** The whole chain (Fabric Loader
   Apache-2.0, Mixin MIT, yarn/intermediary CC0-1.0) is permissively licensed and compatible with
   any licence we pick. We can write, ship, and relicense our own patches without entanglement.
2. **Ship a Forge 1.8.9 profile as an explicit, opt-in compatibility mode**, for users who need
   OptiFine or PolyPatcher. We do not bundle those mods (see `mod-policies.md`); we make the profile
   work and let the user supply the jars.
3. Do **not** attempt to run both loaders simultaneously. That path is a support nightmare.

### 4.3 Java vs Kotlin for the mod

**Java 8 source level, plain Java.** Reasons:

- The target JVM is `jre-legacy` / Java 8. Language features beyond Java 8 are not available without
  extra toolchain work.
- Kotlin's stdlib adds roughly 1.5-5 MB to the classpath and measurable class-loading time at
  startup. On a mod whose entire purpose is reducing startup time and memory, that is
  self-defeating.
- Mixin's annotation processor and bytecode targets are best-trodden with Java.
- Legacy Fabric does ship `fabric-language-kotlin` (Apache-2.0) if we change our minds later.

### 4.4 The LWJGL 2 -> LWJGL 3 question

1.8.9 ships LWJGL **2.9.4-nightly-20150209**. LWJGL 2 is the source of several input and window
problems (see `performance.md`, "Input / responsiveness"). Two prior-art data points:

- `Zarzelcow/legacy-lwjgl3` (**LGPL-2.1**) is a Legacy Fabric mod that runs pre-1.13 Minecraft on
  LWJGL 3 and is **explicitly tested on 1.8.9**. Last pushed 2023.
- `GTNewHorizons/lwjgl3ify` (**LGPL-3.0**, actively maintained) does the same for 1.7.10 on Forge,
  including a relauncher that swaps to Java 17+.

So the modernization path is proven for 1.8.9 on Legacy Fabric, but the actively maintained
implementation targets a different version and a different loader. Both are LGPL, meaning we can
*depend on* them (separate jar, dynamic linkage) but should not vendor their code into a permissively
licensed mod.

**Recommendation: treat "LWJGL 3 + modern JVM for 1.8.9" as a spike, not a v1 commitment.** It is the
single highest-leverage technical bet available -- it unlocks modern GCs, raw mouse input, proper
fullscreen handling, and better frame pacing all at once -- and also the single highest-risk one.
Prototype it before promising it.

---

## 5. Recommended architecture

```
+--------------------------------------------------------------+
|  launcher-ui         Rust, iced                              |
|    - 6 screens, single process, wgpu-rendered                |
|    - no WebView, no JS runtime, no IPC boundary              |
+--------------------------------------------------------------+
                   | direct calls, one process
+--------------------------------------------------------------+
|  launcher-core       Rust (library crate, UI-agnostic)       |
|    +- auth      MS OAuth PKCE / device-code -> XBL -> XSTS   |
|    |            -> Minecraft Services -> entitlement+profile |
|    +- vault     OS credential store; refresh token only      |
|    +- meta      piston-meta parsing, legacy arg substitution |
|    +- install   SHA-1-verified downloads, atomic writes,     |
|    |            zip-slip-safe natives extraction             |
|    +- jre       jre-legacy provisioning / detection          |
|    +- launch    JVM arg construction, spawn, supervise, logs |
|    +- update    ed25519-signed manifest, staged replace      |
+--------------------------------------------------------------+
                   | std::process::Command
+--------------------------------------------------------------+
|  JVM (Java 8, jre-legacy)                                    |
|    +- Legacy Fabric Loader (Apache-2.0)                      |
|         +- our-optimizations  Java 8 + Mixin (MIT)           |
|              - all optimizations live INSIDE this process    |
+--------------------------------------------------------------+
```

Key structural properties:

- **`launcher-core` is a library with no UI dependency.** This lets us ship a headless CLI, run the
  whole auth + install + launch path in CI, and swap the UI later without rewriting anything that
  matters. It is also what makes the iced-vs-egui decision cheap to reverse.
- **One launcher process. No daemon. No tray agent. No service.** The launcher may exit entirely once
  the JVM is spawned (a user preference), or stay to show logs.
- **Every optimization lives in category A, B, C, or D** of the product brief: inside the game
  process (the Mixin mod), in the JVM command line (launch module), in launcher-owned files
  (install module), or as an explicit reversible preference. Nothing in this diagram can touch the
  registry, a driver, or another process. That is an architectural guarantee, not a policy promise.

### 5.1 Per-criterion scoring

| Criterion | Rust + iced | Rust + Tauri | C# NativeAOT + Avalonia | C++ + Qt | Java + JavaFX |
|---|---|---|---|---|---|
| Idle RSS | ~30-60 MB | ~130-260 MB | ~60-110 MB | ~60-100 MB | ~150-250 MB |
| Startup (cold) | ~0.2-0.4 s | ~0.4-0.8 s | ~0.1-0.3 s | ~0.2-0.4 s | ~0.8-2 s |
| Binary size | ~17-25 MB | ~5 MB + WebView2 | ~20-40 MB | ~30 MB + Qt libs | ~40 MB + JRE |
| Memory safety | high | high | high | low | high |
| Dependency surface | medium | high (browser) | medium-high | high | high |
| Credential storage | `keyring` / `windows-rs` DPAPI | same | DPAPI native | native | JNI needed |
| Process management | excellent | excellent | excellent | excellent | fair |
| Auto-update | roll our own, signed | Tauri updater | roll our own | roll our own | awkward |
| UI quality ceiling | good | excellent | good | excellent | fair |
| Dev velocity | fair | excellent | good | poor | good |
| **Privacy positioning** | excellent | poor | good | good | good |

Tauri wins on velocity and UI ceiling and loses on the two things this product is *about*.

Numbers in the RSS/startup rows are order-of-magnitude expectations synthesised from the cited
comparison plus general runtime characteristics, not measurements of our own code. They should be
re-measured on our own prototypes before the decision is treated as final.

---

## 6. Auto-update design (sketch)

- Static file host. **No unique identifier, no cookie, no query string carrying version or OS.**
  (Version and OS in the URL *path* is unavoidable to fetch the right artifact; that is not tracking,
  but it must be documented honestly.)
- `update.json` manifest signed with **ed25519 (minisign format)**; public key compiled into the
  binary. Verify the signature *before* parsing anything else in the manifest.
- Download to a temp file, verify SHA-256 **and** signature, then atomic rename. Never self-modify
  in place.
- **Default: check on launcher start only, user-visible, one request.** Offer "never check
  automatically" in settings. Never background-poll. Never auto-install without consent --
  auto-updating is a code-execution channel and deserves an explicit user decision.
- On Windows, avoid an elevated installer entirely: per-user install under `%LOCALAPPDATA%` so
  updates need no admin rights and cannot be used as a privilege-escalation vector.

---

## 7. Open architectural questions

1. **Can 1.8.9 + Legacy Fabric run on Java 17/21 via LWJGL 3 reliably enough to ship?** This gates
   modern GC availability, which is one of the biggest performance levers we have.
2. **Do we accept the Forge-1.8.9 compatibility profile in v1, or defer it?** Deferring is cleaner
   engineering; including it is what users will actually ask for on day one.
3. **iced vs egui** -- build the same two screens in both and measure cold start, RSS, and how the
   result actually *looks* before committing. The `launcher-core` split makes this cheap.
4. **Project licence** (Apache-2.0 / MIT vs GPLv3). Must be decided *before* any dependency choice
   that forces it (Slint, or vendoring anything LGPL).
5. **Azure application approval.** `api.minecraftservices.com` returns **403** for Azure apps not
   approved for the Minecraft API; approval is via a Microsoft form and is widely reported to take
   weeks to months. This is a **schedule-critical, submit-immediately** item, not an implementation
   detail. See `security-model.md`.
6. **Do we provision a JRE ourselves, or require the user to have one?** Provisioning is better UX
   and lets us guarantee the Java 8 target; it also means we redistribute an OpenJDK build and
   inherit its licence and CVE-patching obligations.

---

## 8. Sources

- Minecraft 1.8.9 version JSON -- https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json
- Version manifest v2 -- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- Microsoft authentication (Minecraft Wiki) -- https://minecraft.wiki/w/Microsoft_authentication
- Tauri vs Iced vs egui performance comparison -- http://lukaskalbertodt.github.io/2023/02/03/tauri-iced-egui-performance-comparison.html
- The Rust GUI Landscape in 2026 -- https://wrenlearnsrust.com/posts/2026-03-11-rust-gui-landscape-2026.html
- Legacy Fabric meta (1.8.9 support) -- https://meta.legacyfabric.net/v2/versions/game
- Legacy Fabric organisation repos and licences -- https://github.com/Legacy-Fabric
- SpongePowered/Mixin (MIT) -- https://github.com/SpongePowered/Mixin
- Zarzelcow/legacy-lwjgl3 (LGPL-2.1, tested on 1.8.9) -- https://github.com/Zarzelcow/legacy-lwjgl3
- GTNewHorizons/lwjgl3ify (LGPL-3.0) -- https://github.com/GTNewHorizons/lwjgl3ify
- HeliosLauncher Microsoft auth notes -- https://github.com/dscalzi/HeliosLauncher/blob/master/docs/MicrosoftAuth.md
