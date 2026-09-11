# Performance Matrix

Status: **research / pre-implementation**. Nothing here is built or measured on our own code.
Target: Minecraft Java Edition **1.8.9**.
Date: 2026-08-23.

Read with `performance.md` (why each item matters), `mod-policies.md` (what we are allowed to do),
and `security-model.md` (what we refuse outright).

---

## Legend

**Safety class**

| Class | Meaning |
|---|---|
| **SAFE** | Output-identical or effectively so. No meaningful downside. Candidate for the default preset. |
| **OPTIONAL** | Alters visuals, memory, latency, compatibility, or behaviour in a way a user might not want. Off by default unless noted. |
| **EXPERIMENTAL** | Hardware-, driver-, or server-dependent, or insufficiently tested. Opt-in, clearly labelled. |
| **REJECTED** | Requires unsafe system modification, questionable behaviour, invasive access, or offers negligible benefit for its risk. Will not be implemented. |

**Expected benefit** is a rough estimate from the problem's shape, not a measurement. Anything marked
"est." is unvalidated until we have our own profile of a real Hypixel-lobby frame (see
`performance.md` open question 4).

**Privacy impact** is "None" for essentially everything, because every item lives inside the game
process, the JVM command line, or a launcher-owned file. Where it is not "None", that is the point of
the row.

---

## A. Rendering -- draw call reduction

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Font renderer batching** (one draw per text run instead of per glyph) | **Very high** on text-heavy servers; est. large frame-time reduction on Hypixel lobbies | CPU (driver) + GPU | None | None | Medium -- OptiFine also rewrites font paths | None | High | Our own, from vanilla `FontRenderer`. Problem identified by PolyPatcher ("Optimized Font Renderer"), **code not reusable (CC BY-NC-SA)** | **SAFE -- ON** | The single biggest 2D win in 1.8.9. Disable automatically if OptiFine is present until compatibility is proven. |
| **Font metrics / layout caching** | High | CPU | None | None | Low | None | Medium | Our own. Problem identified by PolyPatcher ("Cache Font Data") | **SAFE -- ON** | Must invalidate on resource reload and language change. |
| **GUI/HUD quad batching** (same texture -> one buffer) | High | CPU (driver) + GPU | None | None | Low-medium | None | Medium-high | Our own | **SAFE -- ON** | The safe half of "HUD caching". |
| **Batch model rendering** (entity/item models in one draw call) | High | CPU (driver) + GPU | None | None | Medium | None | High | Our own. Problem identified by PolyPatcher ("Batch Model Rendering", on by default there) | **SAFE -- ON** | |
| **Tile entity batching by texture** | Medium-high in builds with many TEs | CPU + GPU | None | None | Medium | None | High | Our own | **SAFE -- ON** | |
| **Fix double-rendered special tile entities** (e.g. beacons rendered twice per frame) | Medium | CPU + GPU | None | None | Low | None | Medium | Our own; defect described in PolyPatcher 1.10.0 changelog | **SAFE -- ON** | Straightforward vanilla bug. Free win. |
| **Faster tile entity unloading** | Low-medium | CPU + RAM | None | None | Low | None | Medium | Our own; defect described in PolyPatcher 1.10.0 changelog | **SAFE -- ON** | |
| **Avoid redundant normal computation in vertex formats** | Low-medium | CPU | None | None | Low | None | Medium | Our own; described in PolyPatcher 1.10.0 changelog | **SAFE -- ON** | |
| **Whole-HUD frame caching** (render HUD to FBO, reuse N frames) | Medium-high | CPU + GPU | Animated HUD elements become choppy | Possible perceived lag on health/hunger updates | Medium-high | None | High | Our own. PolyPatcher classifies its version as Experimental | **EXPERIMENTAL -- OFF** | Their own classification is the right one. Trades correctness for throughput. |
| **Route all our GL calls through `GlStateManager`** | Low, but prevents regressions | CPU | None | None | None | None | Low | Our own / vanilla | **SAFE -- ON** | Hygiene, not a feature. |

## B. Rendering -- culling and visibility

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Entity occlusion culling** (skip entities fully hidden by geometry) | **Very high** in server lobbies with hundreds of armor stands | CPU + GPU | None *if correct* | None if correct; **breaks badly if wrong** | **High** | None | High | Our own. Feature and its exception list documented by Patcher/PolyPatcher; **code not reusable** | **SAFE -- ON**, with exceptions | Must exempt: ender dragons, withers, player nametags, entity nametags, armor stand nametags. Must auto-disable under OptiFine shaders -- PolyPatcher states culling and their shaders are incompatible. Must never *reveal* anything vanilla hides (see `security-model.md` §9). |
| **Configurable occlusion check interval** | Tunable | CPU | None | Stale culling at long intervals | Low | None | Low | Our own | **OPTIONAL** | Shorter = more accurate, more costly. Competitive players want short. |
| **Entity back-face culling** | Medium | GPU | Body parts invisible when the camera is inside an entity; some transparent-faced models break | Minor | Medium | None | Medium | Our own; behaviour documented by PolyPatcher | **OPTIONAL -- OFF** | Known artifacts on wither skeletons and similar. |
| **Per-category entity render distance** (hostile / passive / player / tile entity / global) | High when used | CPU + GPU | Entities pop out beyond the limit | **Yes** -- you stop seeing distant players | Low | None | Low | Our own | **OPTIONAL -- OFF** | Genuinely useful, genuinely a gameplay change. Never default. |
| **Chunk frustum-culling false-negative fix** (MC-63020 / MC-70850 -- invisible chunks) | Correctness, **negative** perf | CPU | Fixes missing chunks | Fixes seeing through the world | Low | None | Medium | Our own; Mojang bug reports are the basis | **OPTIONAL -- OFF (offer prominently)** | PolyPatcher notes their fix "can negatively impact performance". A correctness-vs-speed choice that belongs to the user. |
| **"Do not render X" toggles** (armor stands, item frames, skulls, grounded arrows, falling blocks, end portals, enchantment books, lightning) | Medium-high per toggle | CPU + GPU | **Large** | **Yes** -- armor stands are NPC nametags/holograms on most servers | Low | None | Low | Our own | **OPTIONAL -- OFF** | Ship them, default none on. Consider whether hiding server-placed information is something we want to normalise. |
| **Disable enchantment glint** | Medium (glint = ~3x geometry per item) | GPU | Obvious | You can no longer tell an item is enchanted at a glance | Low | None | Low | Our own | **OPTIONAL -- OFF** | |
| **Unstacked ground items** (draw 1 model instead of up to 5) | Low-medium | CPU + GPU | Noticeable | None | Low | None | Low | Our own | **OPTIONAL -- OFF** | |

## C. Rendering -- chunks and particles

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Distance- and view-prioritised chunk rebuild queue** | High (kills join-stutter) | CPU | Chunks converge in a better order | None | Medium | None | High | Our own | **SAFE -- ON** | The good version of "limit chunk updates": reorder rather than throttle. |
| **Skip rebuilds for block updates that cannot change the mesh** | Medium-high | CPU | None | None | Medium | None | High | Our own | **SAFE -- ON** | |
| **Chunk worker pool sized to the actual machine** | Medium | CPU | None | None | Low | None | Low | Our own | **SAFE -- ON** | 1.8's default is conservative for modern core counts. |
| **Hard chunk-update-per-second cap** | Smooths stutter, delays terrain | CPU | Chunks appear later | Can hide terrain you are moving into | Low | None | Low | Our own; PolyPatcher exposes the same control | **OPTIONAL -- OFF** | Blunt instrument. Prefer prioritisation. |
| **Particle brightness caching** (stop per-frame lighting lookups) | Medium in particle-heavy scenes | CPU | Particles do not re-light as they drift | None | Low | None | Medium | Our own; Patcher shipped "Static Particle Color" **on by default** | **OPTIONAL -- ON** | Visually near-invisible in motion; classified OPTIONAL only because it is not strictly output-identical. |
| **Particle count cap** | Medium-high | CPU + GPU | Large | Can hide combat feedback | Low | None | Low | Our own | **OPTIONAL -- OFF** | |
| **Reduce `doVoidFogParticles` sample rate** (~1000 block samples/tick) | Medium | CPU | Fewer ambient particles | None | Low | None | Low | Our own, from vanilla `WorldClient` | **OPTIONAL -- ON at a mild reduction** | Pure cosmetic cost in vanilla. |
| **Fullbright** (skip lighting updates) | Low-medium perf, large visibility gain | CPU | **Large** | **Yes** -- see in the dark | Medium (minimaps, shaders) | None | Medium | Our own; PolyPatcher ships it **on by default** with a "Smart Fullbright" shader guard | **OPTIONAL -- OFF** | Widely accepted in the 1.8.9 PvP scene, but it *is* a gameplay advantage. Defaulting it on, as PolyPatcher does, is a choice we should make deliberately rather than inherit. |

## D. CPU and allocation

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Reduce allocation in top hot paths** (`BlockPos`, `Vec3`, `AxisAlignedBB` in collision) | Medium-high, mostly as *reduced stutter* rather than higher average FPS | CPU + RAM (GC) | None | None | Medium | None | High | Our own, from vanilla | **SAFE -- ON** | Profile first. Guessing which allocation sites matter is how this kind of work goes wrong. |
| **`MathHelper.sin/cos` replacement** (Riven / Full / Half tables) | **Low and unsigned** | CPU | None | None | Medium (ASM/coremod conflicts) | None | Medium | Our own. BetterFps is LGPL-2.1 -- usable as a separate jar but not vendorable | **EXPERIMENTAL -- OFF** | BetterFps ships **seven** algorithms and a benchmark precisely because the winner is hardware-dependent, and notes some are *worse* than vanilla. Vanilla's 256 KB table already thrashes cache. Do not put this in a default preset. |
| **Forge entrypoint caching** (avoid `@Mod` annotation class-walk at startup) | High on **startup time** only | CPU (startup) | None | None | Medium | None | Medium | Our own; PolyPatcher classifies theirs as Experimental, needs two restarts | **N/A on Legacy Fabric** | Legacy Fabric declares entrypoints in `fabric.mod.json` and does no scan. Choosing Fabric removes the problem instead of caching around it. Only relevant to the Forge compatibility profile. |
| **Avoid Forge event-bus overhead** | Medium | CPU | None | None | -- | None | -- | Architectural | **Handled by loader choice** | See `architecture-options.md` §4. |

## E. Memory

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Fix the OptiFine cape leak** | **Very high** for lobby-hoppers -- hundreds of MB over a session | RAM | None | None | Medium (touches OptiFine's texture handling) | None | Medium-high | Our own, from the public defect (sp614x/optifine#526). MemoryFix is **unlicensed -- not reusable** | **SAFE -- ON when OptiFine present** | Only applies on the Forge+OptiFine profile. Described by MemoryFix's author as "the main memory leak". |
| **Downscale resource pack icons to 64x64** | High for users with many packs -- hundreds of MB | RAM + VRAM | Pack icons slightly softer in the menu | None | Low | None | Low | Our own, from the public defect. PolyPatcher ships the same **on by default** | **SAFE -- ON** | Cheap, invisible, large. Excellent value. |
| **Remove `System.gc()` on world change (in-game)** | High -- multi-second freeze -> near-instant | CPU + RAM | None | None | Low | None | Low | Our own; PolyPatcher "Optimized World Swapping" | **SAFE -- ON** | See also the launcher-side equivalent in §F. |
| **Audit and bound unbounded caches** (chat history, nametag textures) | Unknown -- **needs profiling** | RAM | None | Possible chat scrollback limit | Low | None | Medium | Our own | **Investigate first** | Do not ship claims here without a profile. |
| **Baked-quad / model memory compaction** (FoamFix's technique) | **Low on vanilla 1.8.9**, high on heavily modded | RAM | None | None | Medium-high | None | Very high | Our own. FoamFix is LGPL-3.0 -- separate jar only | **OPTIONAL -- OFF (or skip entirely)** | Honest assessment: vanilla 1.8.9 has few models. This is FoamFix's home turf and not our audience's problem. Recommend deferring. |

## F. JVM (launcher-side, category B)

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Right-sized heap: `-Xms` = `-Xmx`, ~2-3 GB for vanilla 1.8.9** | **Very high** -- this is the highest-value single change available | RAM + CPU (GC pauses) | None | None | None | None | Trivial | Our own; tuning literature | **SAFE -- ON** | Directly contradicts the community's "allocate 8-16 GB" folklore. The UI must say so. Equal Xms/Xmx removes resize pauses for free. |
| **`-XX:+DisableExplicitGC`** | High -- removes the world-change freeze | CPU | None | None | Very low | None | Trivial | Our own; MemoryFix's README names this exact flag as equivalent to its in-game fix | **SAFE -- ON** | Launcher-side, one flag, no bytecode. Ideal shape. |
| **Pause-oriented collector** (G1 on Java 8; Shenandoah `-XX:ShenandoahGCMode=iu` if available on a modern JVM) | High -- converts long freezes into short ones | CPU | None | None | Low | None | Low | Our own; benchmarked flag guidance | **SAFE -- ON (conservatively tuned)** | Java 8's G1 is much weaker than modern G1. Shenandoah availability in Temurin/Corretto 8 is **unverified**. |
| **`-XX:-DontCompileHugeMethods` + `-XX:ReservedCodeCacheSize=400M`** | Medium-high -- lets HotSpot JIT Minecraft methods above the 8000-byte limit that otherwise run interpreted forever | CPU | None | None | Medium (code cache pressure; unknown interaction with mod-generated methods) | None | Trivial | Our own; benchmarked flag guidance | **OPTIONAL -- OFF initially, promote after measurement** | Strong candidate for the default preset once we have data. |
| **`-XX:+AlwaysPreTouch`** | Low-medium; smoother after startup, slower startup | RAM | None | None | Low | None | Trivial | Benchmarked flag guidance | **OPTIONAL -- OFF** | Costs startup time to commit the whole heap up front. |
| **`-XX:AllocatePrefetchStyle=3`** | Low, hardware-dependent | CPU | None | None | Medium (incompatible with ZGC) | None | Trivial | Benchmarked flag guidance | **EXPERIMENTAL -- OFF** | |
| **Aikar's server flags applied to the client** | **Negative** | -- | -- | -- | -- | -- | -- | -- | **REJECTED** | The tuning literature is explicit: they clamp `MaxGCPauseMillis` via a very high `G1NewSizePercent`, "producing long stutters on some clients", and collect old-gen far too aggressively for a client. Several launchers ship these. We will not. |
| **ZGC** | **Negative for clients** | -- | -- | -- | -- | -- | -- | -- | **REJECTED** | Reported "significant client FPS hit". Also unavailable on Java 8. |
| **`-XX:+UseLargePages`** | Low-medium | -- | -- | -- | -- | -- | -- | -- | **REJECTED** | Requires `SeLockMemoryPrivilege` on Windows -- an elevation and a lasting privilege grant. The benchmarking source itself flags the security implication. |
| **`-Xverify:none` / `-noverify`** | Marginal startup | -- | -- | -- | -- | -- | -- | -- | **REJECTED** | Disables bytecode verification, a real security control, to save a fraction of a second. |
| **`-Xmx` at or above physical RAM** | **Negative** | -- | -- | -- | -- | -- | -- | -- | **REJECTED** | Guarantees swapping. Blocked with an explanation, not silently dropped. |

## G. Input and responsiveness

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **MC-67665 fix** (`EntityPlayerSP.getLook` uses `rotationYaw`, not `rotationYawHead`) | **Very high perceived** -- removes what players experience as input lag / bad hit registration | CPU (negligible) | None | **Yes, positively** -- restores vanilla-correct aim. This is a bug fix, not an advantage | Low | None | **Low** | **Our own, implementing Mojang's own 1.11 fix.** MouseDelayFix is unlicensed and not reusable, but the defect and Mojang's fix are both public | **SAFE -- ON** | Best benefit-to-effort ratio in this entire document. Two lines. Documented by Mojang, fixed by Mojang in 1.11. |
| **Unfocused FPS cap** | High for battery/thermals/other apps | CPU + GPU | None while focused | None | Low | None | Low | Our own; PolyPatcher ships the same | **SAFE -- ON at ~30 FPS**, user-overridable | Must be overridable -- streamers and recorders need it off. |
| **Unfocused sound volume control** | Quality of life | -- | None | None | Low | None | Low | Our own | **OPTIONAL -- OFF** | |
| **Accurate frame limiter** (high-resolution timing, target frame *times*) | High -- turns "capped at 60 with stutter" into smooth 60 | CPU | None | Smoother, more consistent input sampling | Medium | None | Medium | Our own; replaces LWJGL 2's `Display.sync` sleep+spin | **SAFE -- ON** | Must not raise the global OS timer resolution -- see the rejected row below. |
| **Do not double-limit: disable the FPS cap when VSync is on** | Medium | CPU + GPU | None | Removes a stutter source | Low | None | Low | Our own | **SAFE -- ON** | The two mechanisms fight each other in vanilla. |
| **Borderless windowed fullscreen** | Fast alt-tab, no mode switching, no black screens | GPU (small latency cost on some drivers vs exclusive) | None | None | Medium (multi-monitor, DPI) | None | Medium | Our own; PolyPatcher ships "Windowed Fullscreen" and "Instant Fullscreen" | **OPTIONAL -- ON**, with true exclusive fullscreen available | Category A/D work: our window, our preference, reversible. |
| **Raw mouse input** (`GLFW_RAW_MOUSE_MOTION`, bypasses OS pointer acceleration) | **High** for a PvP client | CPU | None | Consistent aim | **High** -- requires the LWJGL 3 path | None | Very high | Our own, on top of LWJGL 3. `legacy-lwjgl3` is LGPL-2.1 and 1.8.9-tested; usable as a dependency, not vendorable | **EXPERIMENTAL -- OFF** | Only reachable via the LWJGL 3 spike. If that lands, this is one of the biggest wins available. |
| **Decouple mouse sampling from frame rate** | Medium-high | CPU | None | More responsive aim at low FPS | High | None | High | Our own | **EXPERIMENTAL -- OFF** | 1.8 samples the mouse once per frame. Also part of the LWJGL 3 story. |
| **Changing Windows pointer settings / disabling Enhanced Pointer Precision** | -- | -- | -- | -- | -- | **System-wide change** | -- | -- | **REJECTED** | System-wide modification. The correct fix is raw input inside our own process. |
| **Raising global timer resolution (`timeBeginPeriod`)** | -- | -- | -- | -- | -- | **System-wide change**, battery cost | -- | -- | **REJECTED** | The correct fix is a better limiter, not a worse operating system. |

## H. Startup and launcher-owned files (category C)

| Optimization | Expected benefit | CPU/GPU/RAM target | Visual impact | Gameplay impact | Compatibility risk | Privacy impact | Impl. difficulty | Licence / source basis | Recommended default | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| **Parallel, hash-verified asset download with resume** | High on first install | Network + disk | None | None | Low | None | Medium | Our own; Mojang publishes SHA-1 for every file | **SAFE -- ON** | Correctness (hash verification) is non-negotiable; speed is a bonus. |
| **Content-addressed shared library/asset store across instances** | High disk saving | Disk | None | None | Low | None | Medium | Our own | **SAFE -- ON** | Mojang's asset store is already content-addressed; mirror that model. |
| **Trim the classpath to what 1.8.9 actually needs** | Low-medium startup | CPU (class loading) | None | None | Medium | None | Medium | Our own | **OPTIONAL -- OFF** | 37 libraries; some are platform-gated by rules. Correct rule evaluation is the real requirement. |
| **Sensible `options.txt` defaults for a fresh install** | Medium | CPU + GPU | Yes -- it changes the user's settings | Yes | Low | None | Low | Our own | **OPTIONAL, first-run prompt only** | Never silently rewrite an existing `options.txt`. Back it up if we touch it. |
| **Log directory cleanup** | Disk only | Disk | None | None | Low | None | Low | Our own; PolyPatcher's "Log Optimizer" warns files are unrecoverable | **OPTIONAL -- OFF** | If shipped: scoped to our instance, opt-in, prefer the recycle bin over unlink. |
| **Access token delivered out-of-band instead of on the command line** | Not a perf item -- a **privacy** win | -- | None | None | Medium | **Positive** -- removes token exposure to every process on the machine | High | Our own; see `security-model.md` §3 | **EXPERIMENTAL -- prototype** | Fabric profile only. Would be better than any shipping launcher currently does. |

## I. Explicitly rejected (system, hardware, privilege, integrity)

Every row here is **REJECTED**. Listed so the answer to "why doesn't your launcher do X" is
permanent and written down. Full rationale in `security-model.md` §9.

| Category | Items |
|---|---|
| Windows services | Disabling or stopping any service |
| Registry | Tweak packs; Game Bar / Game DVR registry edits; any `HKLM` write |
| Network stack | TCP parameter tuning, Nagle/`TcpAckFrequency`, QoS, adapter settings |
| CPU security | Disabling Spectre/Meltdown or other mitigations |
| Timers | Global timer resolution changes |
| Memory | Standby-list clearing, "RAM cleaners", working-set trimming |
| Power | Global power plan changes, disabling C-states or CPU power management |
| Security software | Disabling Defender, adding exclusions, interfering with any AV |
| Hardware | Overclocking, undervolting, fan curves, GPU driver setting overrides, BIOS/UEFI |
| Privilege | Realtime/high process priority; running elevated; kernel or filter drivers |
| Other processes | Injection, memory reading, process enumeration |
| Identity | HWID, device fingerprinting, MachineGuid/MAC reading |
| Data | Telemetry, analytics, crash reporting, "anonymous usage stats" |
| Ecosystem | Automating past OptiFine's ad-gated download; bundling or mirroring Minecraft or unlicensed mods; bypassing CurseForge's author distribution opt-out |
| Game integrity | Reach modification, autoclick, aim assistance, X-ray, rendering entities vanilla would not render |
| JVM | Aikar's server flags on a client; ZGC; `UseLargePages`; `-Xverify:none`; `-Xmx` >= physical RAM; remote debug/JMX on non-loopback |

---

## Default preset summary

Everything marked **SAFE -- ON** above, and nothing else:

**In-game (Mixin mod):** font batching + metrics caching, GUI/HUD quad batching, batched model
rendering, tile entity batching, double-render and slow-unload fixes, vertex-normal fix, entity
occlusion culling *with the exception list and the OptiFine-shader guard*, prioritised chunk rebuild
queue, redundant-rebuild elimination, right-sized chunk worker pool, hot-path allocation reduction,
pack-icon downscaling, `System.gc()` removal on world change, MC-67665 look-vector fix, unfocused FPS
cap, accurate frame limiter, VSync/limiter conflict fix, and the OptiFine cape-leak fix when OptiFine
is present.

**Launcher (JVM args):** `-Xms` = `-Xmx` at a modest size, `-XX:+DisableExplicitGC`, a conservatively
tuned pause-oriented collector.

**Launcher (files):** parallel hash-verified downloads, shared content-addressed store.

Nothing in the default preset changes what the player can see, changes game logic, touches anything
outside our own directories and process, or sends a single byte anywhere not on the allowlist in
`security-model.md` §5.

---

## Sources

Same source set as `performance.md` and `mod-policies.md`; the load-bearing ones for this matrix:

- MouseDelayFix README -- MC-67665 analysis and Mojang's 1.11 fix -- https://github.com/prplz/MouseDelayFix
- MemoryFix README -- cape leak, pack icons, `System.gc()`, `-XX:+DisableExplicitGC` -- https://github.com/prplz/MemoryFix
- OptiFine cape leak issue -- https://github.com/sp614x/optifine/issues/526
- PolyPatcher README + 1.10.0 changelog -- feature list, defaults, and their own SAFE/EXPERIMENTAL judgements -- https://github.com/Polyfrost/PolyPatcher
- BetterFps -- seven sin/cos algorithms and per-machine benchmarking -- https://ftb.fandom.com/wiki/BetterFps
- Minecraft Performance Flags Benchmarks -- client GC/JIT flags, Aikar-on-client critique, Large Pages warning -- https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks
- MC-63020 / MC-70850 -- frustum culling false negatives -- https://bugs.mojang.com/browse/MC-63020
- Minecraft 1.8.9 version JSON -- https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json
