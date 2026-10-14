# The game-side module, and how the launcher talks to it

Two programs, in two languages, with two files between them.

- `minecraft/fathom-optimizations` — Java 8, targeting Minecraft 1.8.9 on Legacy
  Fabric. One jar, three components: **Fathom Core** (the config bridge and the
  registries), **Fathom Performance** (the optimizations), **Fathom HUD** (the
  hub, the widgets and the HUD editor).
- `crates/launcher-core/src/optimizations` — Rust. Owns the settings interface,
  the profile they are stored in, writing them out before launch, and reading
  back the measurements the module records.

### Why one jar and three components

Because they are separately *describable* and not yet separately *shippable*.
The component registry lists all three, and `Supply::PartOf` says which jar
delivers which — so the interface can name what a Fathom profile contains
without pretending there are three downloads. Splitting the jar later is a
change to two lines in `components::ALL`, not to every caller.

The launcher's package name is still `fathom-optimizations`, and so is the mod
id. Renaming a mod id is a migration for everybody's profiles in exchange for a
tidier string.

## 1. Honest status

**The module builds, loads, and does several things.** Some of them are
measured, none of them is claimed to have made the game faster.

What exists:

| | Count | State |
| --- | --- | --- |
| JVM flags | 2 | **Active.** Real command-line flags the launcher passes, covered by tests in `minecraft_runtime::jvm_args`. |
| Game-side, verified in game | 1 | `render.font.metrics-cache`. A Mixin on `TextRenderer#getStringWidth`, observed applying inside a running 1.8.9 client. |
| Game-side, in the module | 1 | `input.unfocused-fps-cap`. Implemented, remapped and shipped as an independent, opt-in background-window policy. |
| Game-side, core written | 4 | Logic implemented and unit-tested in `core/`, with no Mixin hooking it to the game. |
| Game-side, planned | 12 | Registry entries with a described problem and no implementation. |
| HUD widgets | 8 | Drawn by the module. Two on by default; two classified server-sensitive and never on by default. |

### Frame-rate policy

The clearest thing in the module, and worth stating exactly because it is the
kind of change that invites overstatement.

1.8.9's `getMaxFramerate` returns 30 when there is no world *and* a screen is
open, and the user's configured cap otherwise. So the main menu is capped and
nothing else is: inventory, containers, chat and pause use the same configured
limit as gameplay. An alt-tabbed window otherwise renders at full speed into a
surface nobody is looking at.

`FrameCapPolicy::effective` preserves vanilla's answer for every in-world
screen. A Fathom screen with no world uses the player's configured cap instead
of vanilla's title-screen 30; its backdrop animation has a separate 30 Hz clock.
Only the explicit unfocused-window option can lower a valid cap. The policy is
pure arithmetic and is tested as such; the Mixin reads client state and calls it.

What that is worth in practice has not been measured, and the registry entry
says so.

### The HUD

Right Shift opens **Fathom Hub**: three tabs (HUD, Performance, Diagnostics)
over a searchable list, plus the HUD editor and the benchmark control.

The interesting half is in `core/hud`, with no Minecraft types and real tests:
anchors and offsets, placement clamping, layout parsing and writing, and the
snapping geometry the editor uses. What is left in `fabric/hud` is "read a value
off the client and format it", which is the code that cannot be tested without a
running game and is therefore kept as thin as possible.

Two design points worth naming:

**Anchors, not pixel positions.** A widget stores an anchor and an inward
offset, so one pinned to the bottom-right is still bottom-right at another
resolution or GUI scale. Dropping a widget re-anchors it to the third of the
screen it landed in. Storing raw positions is why so many HUDs scatter when the
window is resized.

**Server-sensitive widgets are never on by default.** `Sensitivity::mayDefaultOn`
is consulted by `Widget::onByDefault`, so a default cannot be flipped by editing
one line. Ping and the armour readout are classified that way; they can still be
switched on, because the rule is about defaults and not about forbidding a
feature.

### The keybind, and what it is not

Right Shift is read from the raw LWJGL key event in a Mixin on
`handleKeyInput`, not registered as a vanilla `KeyBinding`. A registered binding
would appear in the Controls screen and be rebindable, which is better — and it
needs a category in `KeyBinding`'s category-order map, where a missing entry
makes the Controls screen throw. Crashing a screen somebody opened for an
unrelated reason is a worse failure than a fixed key, so the key is fixed for
now. Nothing is consumed: vanilla's own handling runs immediately afterwards.

### What "verified in game" does and does not claim

The module has now been run inside Minecraft 1.8.9, twice: once through Loom's
`runClient`, and once launched by Fathom itself through Legacy Fabric. Both
printed:

```
[Fathom] 18 optimizations registered, 4 enabled (launcher settings applied).
```

"launcher settings applied" is the config bridge working end to end — Rust wrote
the file, Java read it.

**How the Mixins were proved to apply.** Not by the absence of an error message.
`injectors.defaultRequire` was `0`, under which a Mixin that fails to inject is
skipped silently — which is exactly how `MinecraftClientFrameMixin` sat broken
against a method that does not exist in 1.8.9 for a whole milestone. It is now
`1`, so a failed injection crashes at class load. The client reaching the title
screen is therefore positive evidence that every injector applied.

It does **not** claim the game got faster. `Readiness::RunsInGame` sits below
`Working` for that reason, and
`no_game_entry_claims_more_than_it_has_earned` fails the build if any game-side
entry claims `Working`. The only numbers we have are the isolated
microbenchmark in §7.

The entries with recorded evidence carry *isolated microbenchmarks* — they show
a mechanism works and is not slower than what it replaces. They do not show the
game got faster. Only an in-game run under
`docs/performance/benchmark-methodology.md` can do that, and none has been run.

### Why the font cache is the one that shipped first

There are two caches in `core/opt/text/`, and the safer one was chosen:

- `TextMetricsCache` **computes** the width itself, from a `GlyphSource`, via
  `TextMeasurer`. That reimplements vanilla's layout rules — format codes, the
  bold overdraw, the unicode page split — and a reimplementation can disagree
  with vanilla. Every string it gets wrong is a misaligned scoreboard.
- `WidthMemo` **remembers** the width vanilla returned. On a miss the original
  method runs untouched; on a hit the same `int` comes back. "Output-identical"
  is arithmetic rather than an argument.

`WidthMemo` is the one the Mixin uses. Its single obligation is invalidation,
and both points where 1.8.9 can change the font — `init()` on resource reload,
`setUnicode(boolean)` on the unicode toggle — drop the table wholesale.

### A bug this build found

`MinecraftClientFrameMixin` targeted `MinecraftClient#render`. That method does
not exist in 1.8.9; the per-frame method is `runGameLoop()`. Because
`fathom.mixins.json` sets `injectors.defaultRequire` to `0`, the injection
failed to apply *silently* and no frame was ever timed. It is fixed, and it is
the concrete cost of that setting: right for instrumentation, wrong for anything
that changes behaviour.

## 1a. Measuring

The module records; the launcher displays. Neither invents a number.

Press Right Shift, open Diagnostics, and start a benchmark; play; stop it. The
module writes a JSON report into `fathom/benchmarks/` in the profile's own game
directory, keeping the twenty most recent. `optimizations::benchmarks` reads
them back and the Performance page shows the newest usable one.

What is recorded, all of it from `core/instrument`, which had the statistics
long before there was anywhere to put them:

- average FPS — frames over elapsed time, not the mean of per-frame rates
- 1% and 0.1% lows — the mean of the worst slice, not a percentile
- frame-time median, 95th and 99th percentiles, best and worst
- peak and mean heap, and allocation rate where the JVM can report it
- per-collector GC counts and pause totals

What is **not** recorded is a comparison. One run has no baseline, and a
percentage derived from one run is exactly the "+42% FPS" claim
`FATHOM_MODS_AND_LICENSING.md` forbids. The page shows the measurements and the
module's own caveats about them, and no adjective.

A run under a hundred frames is kept and shown as too short rather than
averaged: a 1% low over forty samples is one frame wearing a statistic's
clothes.

The direction of this file is the opposite of the config bridge in §4, and the
asymmetry is deliberate. Settings are small, authoritative, and overwritten
every launch. Results are an artefact of one run, worth keeping several of, and
produced at a moment nobody schedules. One file each way, each owned by whoever
produces it.

### The instrument: play as it was played

A benchmark is a measurement somebody decides to make. The instrument is the
other kind: always there, never started, and cheap enough that nobody has to
decide to pay for it. `core/instrument/Pulse` (tested in `PulseTests`) is fed
by the frame Mixin with the timestamp it has already read:

| Per frame | Once per second of play | On demand |
|---|---|---|
| a subtraction, a ring store, a histogram increment | close the second (rate, slowest frame), read the heap, copy a snapshot for the launcher's writer | recent p95/p99 by nearest rank over the last five seconds (at most 2,048 frames), sorted at most once per closed second |

Only frames **in a world with the window focused** count, and an interval over
a second is a pause (a world load), not a frame. Seconds are seconds of play,
not wall clock, so a minute in the pause menu is not a minute of graph. The
session-wide p95/p99 come from a 0.1 ms histogram, so they cost nothing to keep;
hitches are frames of 50 ms or more. A benchmark arm that bypasses Fathom's
hooks bypasses the pulse too.

Where it shows:

- **The Hub's Performance page** (`hub/Instrument`): frame rate, p95, p99,
  memory, modules and the profile, over a two-minute timeline of play, one
  column per second, with a tick where a world was joined or a performance
  setting changed. Hovering a column reads it out; hovering a figure explains
  it. Its caption still says what the page itself costs.
- **The launcher**, when it started the game (`fathom/session.json` is present):
  a daemon thread writes `fathom/session-pulse.json` every five seconds and once
  as the game closes (`fabric/diag/SessionPulse`, `PulseJson`).
  `launcher_core::session_pulse` reads it as untrusted input (size cap, schema,
  clamped numbers); the launch supervisor attaches it to the running session,
  and to the finished one as its summary. Home shows it live and after; the
  Performance page shows the last session. A launch clears the previous file
  first. Nothing in it names a world, server or player, and it never leaves the
  machine (`PRIVACY.md`).

What it does not claim: it is play, not a controlled run. Two sessions in
different places are not comparable, so the launcher keeps these figures in
their own section, apart from Measured, and never computes a difference.

## 2. Presets

Four, named for intent: **Balanced**, **Competitive**, **Maximum FPS**,
**Custom**.

`Maximum FPS` is a statement about how much it switches on, not a promise about
a frame counter. No preset description quotes a frame rate, because none has
been measured. Profiles written by earlier builds are read through serde
aliases: `safe` becomes `Balanced` (same membership rule), and `none` becomes
`Custom` (its stored switch map is all-off, and `Custom` means "the map on disk
is the truth", so the profile keeps behaving exactly as it did).

Choosing a preset expands it into the profile's switch map immediately, rather
than storing the name and resolving it later. The bridge file the game reads is
a list of switches, so expanding at the moment of choice means the file and the
page cannot disagree about what "Balanced" meant on the day it was picked.

## 3. Why the catalogue exists twice

`OptimizationRegistry.java` is the source of truth. `optimizations/mod.rs`
mirrors it: same ids, same order.

That duplication is a deliberate trade. Generating the Rust side from the Java
would mean the module has to be built before the launcher can be — which would
make a Rust developer install a JDK to compile a window. The cost is drift, so
a test reads the Java source directly and fails if either side gains or loses an
entry:

```
optimizations::tests::the_catalogue_matches_the_java_registry
```

Add an optimization to the registry without adding it here, and the build stops.

## 4. The config bridge

One file, written into the game directory immediately before launch and read
once at startup.

```
<game directory>/config/fathom-performance.properties
```

```properties
# Written by Fathom before launch. Do not edit: it is replaced every time
# the game starts. Change these settings in the launcher's Performance page.
schema = 1
preset = safe
opt.render.font.batching = true
opt.render.hud.quad-batching = false
```

**Rust:** `optimizations::bridge::Settings` — `from_profile`, `to_properties`,
`write_to`.
**Java:** `net.fathom.optimizations.core.config.LauncherConfig` —
`fromGameDirectory`, `apply(registry)` returning a `SwitchBoard`.

### Why properties and not JSON

Because Java can parse it with nothing. `java.util.Properties` is in the JDK,
which lets `core` keep its defining property: no dependencies, builds and tests
with a bare JDK 8. JSON would mean either a library every user then needs on the
classpath for the mod to load, or a hand-rolled parser — a bug surface in
exchange for punctuation nobody reads.

### The rules it follows

- **Written whole, every launch.** Never appended, so a previous session's
  switches cannot linger.
- **Deterministic.** Switches live in a `BTreeMap`, so identical settings produce
  identical bytes and the file is diffable.
- **Non-fatal in both directions.** Failing to write it logs a warning and the
  launch continues; failing to read it produces registry defaults, which is
  everything off. A performance mod that stops Minecraft from starting because
  it could not parse its own preferences has cost more than it saves.
- **Unknown keys are ignored**, on both sides. That is what lets a newer
  launcher run against an older module.
- **Strict booleans.** `Boolean.parseBoolean` maps every non-`"true"` string to
  `false`, which would read a typo as "off" and leave the user wondering why
  their setting did nothing. Anything unrecognised is rejected and recorded.
- **Switches apply as `Reason.USER`.** The switchboard distinguishes a person's
  decision from a conflict safeguard's, and these came from someone clicking
  things in the launcher.
- **JVM flags do not cross.** They are command-line arguments the launcher
  already applies; sending them would suggest the module could act on them.

### Keeping the two ends in step

Both sides hard-code the path, the schema number and the `opt.` prefix. A Rust
test reads the Java source and asserts they agree
(`bridge::tests::the_wire_contract_matches_the_java_side`), and a Java test
round-trips the exact bytes the Rust writer produces
(`ConfigTests.aRealLauncherFileRoundTrips`). A rename on one side alone fails
one of them rather than silently stopping the module from reading its settings.

## 5. Building the module

### The real build

```bash
# JAVA_HOME must point at a JDK 17: Gradle and Loom need it to run.
# The mod itself is still compiled to Java 8 bytecode, via a toolchain.
gradle build
```

Produces `build/libs/fathom-optimizations-<version>.jar` — a loadable Fabric mod
with `fabric.mod.json`, an entrypoint, and remapped Mixins. Also produces a
`-sources.jar`, which is not a mod; `tools/package-portable.ps1` excludes it and
the core jar by name and suffix.

Four things had to be true at once for this to work, and each was a separate
failure:

| Problem | Fix |
| --- | --- |
| `fabric-loom` was `1.7-SNAPSHOT`, a mutable coordinate | pinned to `1.7.4` — closes the open finding in `THREAT_MODEL.md` |
| `yarn_mappings` was the placeholder `1.8.9+build.custom` | `1.8.9+build.604` |
| Loom could not find Legacy Fabric's intermediary | explicit `loom { intermediaryUrl }` |
| "You are using an outdated version of Java (8)" | build on JDK 17, target 8 through a `toolchain` block |

The last one is the counter-intuitive one: Loom refuses to run on Java 8, but
Minecraft 1.8.9 declares Java 8, so the build needs both. A toolchain expresses
exactly that. `options.release = 8` does not work here — `--release` is a JDK 9+
flag.

### The core-only build

```bash
powershell -ExecutionPolicy Bypass -File tools/build-optimization-module.ps1
```

Needs a JDK 8 and nothing else. Compiles `core`, runs all three test suites
(`CoreTests`, `OptTests`, `ConfigTests` — 83 assertions), and packages
`build/libs/fathom-optimizations-core.jar`.

**That jar is not a loadable Fabric mod.** No entrypoint, no `fabric.mod.json`,
deliberately, so it cannot be mistaken for one. Dropping it in `mods/` does
nothing, and both packaging and the docs exclude it by name.

It still earns its place: it is how `core` gets compiled and tested by somebody
who has not installed Gradle, and it keeps the half of the module that carries
the reasoning verifiable on every change.

### Why the split

- `core` — the logic, the registry, the switch board, the bridge. No Minecraft
  imports, no Fabric imports, no third-party dependencies at all. Every
  optimization's *decision* lives here, which is why the decisions are testable
  without a game.
- `fabric` — the entrypoint and the Mixins. Needs fabric-loader, Legacy Fabric's
  1.8.9 intermediary mappings, and Minecraft on the compile classpath. As thin
  as it can be: a Mixin should be a hook, not a place where logic hides.

## 6. Getting the module into a profile

Fathom ships a profile called **Optimized**: Legacy Fabric, the Balanced preset,
and the module already installed. It is created once per installation, on first
open, from the jar in `resources/` — no network, no download, nothing for the
user to import. See `crates/launcher-core/src/profiles/seed.rs` for the four
rules that seeding follows, of which the important one is that deleting the
profile is a decision the launcher does not overrule.

Legacy Fabric is a real loader target now. `services::launch` resolves the
loader, fetches its libraries through the same verified download path the game
uses, prepends them to the classpath **in front of the game jar**, and takes its
main class. That ordering is the mechanism: with the game jar first, vanilla's
classes win and no mod loads — silently.

If the profile has no loader build chosen, the newest stable one is picked at
first launch and written back, so the choice is made once and then pinned.

**None of this has been run against a real Legacy Fabric server.** It compiles,
its unit tests pass, and no part of it has fetched an actual loader profile.

## 7. Benchmark results

`render.font.metrics-cache`, measured against the class that actually ships
(`WidthMemo`, not `TextMetricsCache`). Run it with:

```bash
gradle build
java -cp build/classes/java/main;build/classes/java/test   net.fathom.optimizations.bench.WidthMemoBench
```

Corpus: 235 strings per frame, 11,528 characters — a scoreboard, a tab list, a
chat backlog and a screenful of nametags. One iteration is one frame's worth of
measurement. JDK 8.0.502, this machine.

| Churn per frame | Vanilla | Memoised | Difference | Noise floor | Significant |
| --- | --- | --- | --- | --- | --- |
| 0% (static) | 14,279 ns | 1,832 ns | **-87.2%** | 41.6% | yes |
| 5% | 16,685 ns | 5,479 ns | **-67.2%** | 31.7% | yes |
| 50% | 16,946 ns | 14,818 ns | -12.6% | 19.4% | **no** |
| 100% | 15,879 ns | 24,240 ns | **+52.7% slower** | 17.3% | yes |

Hit rate: 99.8% static, 94.8% at 5% churn, 60.3% at 50%, 0% at 100%.
**Width mismatches at every churn level: zero.**

### Reading these honestly

- The 100% row is the real worst case and it is a **loss**. Every lookup misses,
  so the cache is pure overhead. Real chat is nearer the 5% row.
- The 50% row is inside the noise floor. That is "no measurable difference", not
  "a small win", and the harness says so itself.
- **None of this is an FPS claim.** Every row is
  `MicroBench.Scope.ISOLATED_FUNCTION`: one method in a loop, with the cache to
  itself and no renderer around it. What fraction of a frame `getStringWidth`
  occupies in a real client has not been measured, so what fraction of 87% of it
  reaches the frame time is unknown.

## 8. What is still missing

1. **In-game verification.** Nothing here has been observed running inside
   Minecraft, and the Legacy Fabric launch path has never fetched a real loader
   profile. Every `InModule` entry stays `InModule` until it has.
2. **Benchmarks under `docs/performance/benchmark-methodology.md`.** Until then
   every claim is a microbenchmark or nothing, and the Performance page keeps
   saying so.
3. **Mixins for the four `CoreWritten` entries.** The logic and its tests exist;
   what is missing is the hook. `WidthMemo` is the template: prefer memoising
   what the game computed over recomputing it ourselves.
4. **A published release.** `catalog.rs` has no `download` for the module
   because nothing is published anywhere. A portable build ships the jar it
   built locally.
