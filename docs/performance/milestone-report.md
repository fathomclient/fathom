# Performance milestone: audit and harness

The nine answers the brief asked for.

**Date:** 2026-09-07 · **Stack:** 1.8.9 · Legacy Fabric 0.19.3 · yarn
1.8.9+build.604 · Java 8 bytecode · `fathom-optimizations` 0.3.0-hud

---

## 1. The actual working game-side stack

Verified by launching the dev client, driving it and photographing it — not by
checking that files exist.

| Layer | State |
| --- | --- |
| Legacy Fabric loader | **Working.** Diagnostics reports `Fabric 0.19.3` |
| Fathom Core — modules, HUD, renderer, Hub, menu, bridge | **Working.** 15 modules, 11 widgets, all eight Mixins applied |
| Fathom Performance — registry, switch board, guard | **Loaded, but almost nothing is hooked.** See §2 |
| Launcher → game config | **Working.** `config/fathom-performance.properties` read at startup |
| Session descriptor | **Working.** Profile, version, loader, preset reach the main menu |

**One artifact, not two.** "Fathom Core" and "Fathom Performance" are both inside
`fathom-optimizations-0.3.0-hud.jar`. There is no separate performance jar and no
separate loading step.

---

## 2. Broken and incomplete systems found

### 2.1 Four optimization toggles did nothing — **fixed**

`render.font.batching`, `render.entity.occlusion-culling`,
`memory.world-change-gc` and `resources.pack-icon-downscale` each drew a working
switch in the in-game Performance page and changed a boolean nothing reads.

The page gated on `implementedInCore()`, which means "the decision logic exists
and has tests" — true, and not the same claim as "a Mixin calls it".

**Fixed** with a new fact, `Optimization.runsInGame()`, set by hand on the three
that have a Mixin, checked by `OptTests.onlyHookedOptimizationsMaySwitch` against
the Mixin list. The other four now read *"Written, not hooked up"*.

The launcher-side page was already honest; only the in-game one was wrong.

### 2.2 Closing any screen at the main menu dropped you on vanilla's — **fixed**

`setScreen(null)` substitutes a `TitleScreen` **inside** the method body, at
bytecode offset 25, after the `HEAD` injection point the menu Mixin used. The
Mixin was handed a null, never saw a title screen, and the game landed on the
grey vanilla menu.

Found by pressing the Fathom button and screenshotting the result. **Fixed** by
replacing the null in the Mixin, which pre-empts vanilla's substitution.

### 2.3 The main menu's profile pill overlapped the Singleplayer button — **fixed**

Branding was laid out downward from a guessed offset. Now laid out upward from
the button column with its own measured height.

### 2.4 In-game Performance changes do not persist — **recorded, not fixed**

`SwitchBoard.setByUser` changes the live board; nothing writes it back, and the
launcher overwrites the file every launch anyway. Half deliberate — the config
bridge is one-way by design — but the page does not say so, and a switch that
reverts on restart is a smaller version of the same dishonesty.

Not fixed because it is an interface change and this milestone is under
instruction not to do interface work.

### 2.5 A measurement hazard that invalidates everything

Driving the client with synthetic input loses window focus. With the unfocused
cap on, **every configuration reports exactly 30 fps**. Two readings from one
session: the world alone at 200 fps, the Hub over the same world at 30.

This is now enforced, not warned about: `Run.rejectionReason` discards any run
that lost focus, and focus is sampled every frame from the frame hook.

---

## 3. Baseline benchmark data

**None. No benchmark has been run.**

The harness, the profiler, the configurations, the scenarios and the thresholds
all exist and are tested. What has not happened is a measurement session, because
a session is six configurations × three runs × a scenario, each needing the game
set up by hand and left untouched for a minute.

`docs/performance/current-state-audit.md` §8 has single Diagnostics readings from
the development session. **They are not benchmarks** and are recorded there
precisely so they are not quoted as though they were.

---

## 4. Fathom Core overhead

**Not measured.** It is the number this milestone built the means to produce:
configuration D against configuration B in `cpu-ceiling`.

What is known about its *composition*, from reading the code rather than timing
it:

| Per frame, with everything off | Cost |
| --- | --- |
| Frame hook | Two `nanoTime` calls and a null check |
| Frame-cap hook | One policy evaluation — a few comparisons |
| HUD loop | One `Window` allocation, then 11 × (map lookup + a linear scan of 15 modules) ≈ **165 list probes** |
| FPS meter | One `nanoTime`, three doubles |

The HUD loop is the only part that is not trivially cheap, and it is now
instrumented separately (`Probe.Section.MODULES` versus `HUD`) so the split can
be measured rather than argued about.

---

## 5. Fathom Performance overhead with everything off

**Not measured**, and — this is the more important finding — **currently almost
nothing to measure**.

Two frame-related hooks remain after the UI recovery removed the inappropriate
in-world menu throttle:

| Optimization | Mixin | Effect |
| --- | --- | --- |
| `render.font.metrics-cache` | `TextRendererWidthMixin` | Makes a frame cheaper |
| `input.unfocused-fps-cap` | `MinecraftClientFrameCapMixin` | Draws fewer frames |

Only the first changes what a focused rendered frame costs. The unfocused policy
is intentionally outside a focused benchmark.

---

## 6. Largest measured hot paths

**None measured.** The counters that would find them — rendered entities, tile
entities, particles, chunk rebuilds — exist in `Counters` and **nothing
increments them**, because the Mixins that would are the same ones the
optimizations need, and those are not written.

The probe does measure Fathom's own five sections, so the *first* hot-path
question — which part of Fathom costs what — is answerable by running the
harness. Minecraft's own hot paths are not, yet.

---

## 7. Allocation hotspots

**Not measured.** `MemorySampler` reports an allocation rate where the JVM
exposes one, and the report says which method was used or that it could not be.

One allocation is known by inspection: `FathomHud.render` constructs a `Window`
every frame, including when no widget is enabled. Per-frame allocation does not
show in mean frame time — it shows in GC pause count and then in the 1% low,
which is why the threshold document lists GC pause count as its own check.

---

## 8. Is the gap Fathom-caused or vanilla-renderer-caused?

**Unanswerable today, and the honest answer is that the question is premature.**

There is no "gap" measured. What can be said from the audit:

- Fathom has **three** hooks into Minecraft's rendering, one of which affects
  frame cost. It has not optimized the vanilla renderer in any meaningful sense,
  so a gap against another client would be almost entirely vanilla's renderer
  plus whatever Fathom's own framework costs.
- Fathom's framework cost is bounded by what §4 lists, which is small in absolute
  terms and unmeasured in relative ones.

The comparison that answers this is D − B in `cpu-ceiling`. Until it is run,
anything else is a guess.

---

## 9. Next targets, ranked

Ranked by what the audit found, **not** by measured frame-time cost — because
nothing has been measured, and ranking by guess is the mistake this milestone
exists to stop.

| # | Target | Why it is first | Blocked on |
| --- | --- | --- | --- |
| 1 | **Run the harness.** Six configurations, `cpu-ceiling`, three runs each | Every item below is a guess until this exists | A measurement session |
| 2 | **The D − B number against the threshold** | Decides whether any optimization may be written at all | 1 |
| 3 | The HUD loop's per-frame module lookup | The only part of Fathom's idle cost that is not trivial; a sealed-registry cache is a small fix | 1, to know if it matters |
| 4 | The `Window` allocation per frame | Shows in GC pauses and the 1% low, not the mean | 1 |
| 5 | Feed the deep counters | Entities, tile entities, particles, chunk rebuilds have fields and no Mixins; without them Minecraft's own hot paths cannot be found | Mixins that do not exist |
| 6 | Hook the four written-but-unhooked optimizations | Their logic exists and is tested; they do nothing | 2 — not before the overhead is known |

**Item 6 is deliberately last.** Four optimizations are written, tested and
inert, and connecting them is the most tempting work available. It is also
exactly what the threshold rule forbids until D − B is known: an optimization
written against an unmeasured baseline is an optimization that cannot be
evaluated.

---

## 10. What was built this milestone

| Piece | Where |
| --- | --- |
| Section profiler, ~0.005% of a frame when on, free when off | `core/instrument/Probe.java` |
| Run metadata, settings capture, focus watchdog | `core/bench/Environment.java`, `fabric/bench/Capture.java` |
| Six comparison configurations | `core/bench/Configuration.java` |
| Two new scenarios, including the CPU ceiling | `core/bench/Scenarios.java` |
| Fixed-duration runs, warm-up discard, rejection rules | `core/bench/Run.java` |
| Comparison, settings check, summary generation | `launcher-core/src/bench` |
| `fathom-cli bench plan / scenarios / summary` | `launcher-cli` |
| The audit | `docs/performance/current-state-audit.md` |
| The harness manual | `docs/performance/benchmark-harness.md` |
| The threshold and the stop rule | `docs/performance/regression-thresholds.md` |

Tests: 46 `CoreTests` (six new, covering the probe, the environment, the
configurations and the rejection rules), 35 `OptTests` (one new, locking the
hooked-optimization list to the Mixin list), 7 new Rust tests in
`launcher-core::bench`. Everything compiles; clippy is clean at pedantic across
the workspace.
