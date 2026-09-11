# Optimization registry

Every optimization this project intends to make, and every JVM question it
intends to answer.

**The tables below are generated from `OptimizationRegistry`, `Experiments` and
`Scenarios` in `minecraft/fathom-optimizations`.** They are not maintained by
hand: a hand-maintained copy of a code catalogue is a copy that drifts. §8 says
how to regenerate them.

---

## 1. Nothing here is on

All **18** entries are off by default. **5** of them now have a
tested implementation in `core` and **4** carry real measurements —
and none of that promotes anything, because all of the evidence is *isolated*:
microbenchmarks and draw-call counts, taken without the game running.

That gate is enforced in code, not by discipline:

```java
public boolean defaultEnabled() {
    return measuredInGame() && defaultOnWhenMeasured;
}
```

`Evidence.Level.ISOLATED` does not satisfy `measuredInGame()`. Only a run of the
game in a named scenario under `benchmark-methodology.md` does, and none has been
possible: the Fabric layer of this module has never been compiled.

The distinction is the whole point. A boolean `measured` flag could not tell
"we timed this function in a loop" from "we ran the game and the frame times
moved", and collapsing the two is how a 66% microbenchmark win becomes a
"66% more FPS" line in a changelog. A test asserts that no entry claims in-game
evidence, so a change that promotes something on the strength of a microbenchmark
fails the build rather than shipping.

Several entries are marked `defaultOnWhenMeasured()` — they are the ones we
*expect* to promote once the numbers exist. Expecting is not knowing, and the two
are stored in different fields.

`docs/performance/optimizations.md` §7 says what would change this, per entry.

| Class | Count | May ever default on? |
|---|---|---|
| Safe | 14 | Yes, once measured **in the game** |
| Optional | 3 | No — the user chooses |
| Experimental | 1 | No — hardware-dependent by construction |

| Status | Count |
|---|---|
| Logic written in `core` and tested | 5 of 18 |
| Carrying an isolated measurement | 4 of 18 |
| Carrying an in-game measurement | **0 of 18** |
| Wired into the running game | **0 of 18** |

---

## 2. The register

| ID | Name | Class | Default | Code | Evidence | Basis |
|---|---|---|---|---|---|---|
| `render.font.batching` | Batched font rendering | Safe | Off | core | isolated (`opt-2026-08-26-quad-batching`) | our reading of vanilla |
| `render.font.metrics-cache` | Font metrics caching | Safe | Off | core | isolated (`bench-2026-08-26-text-metrics`) | our reading of vanilla |
| `render.hud.quad-batching` | Batched HUD and GUI quads | Safe | Off | — | **none** | our reading of vanilla |
| `render.entity.model-batching` | Batched entity and item model rendering | Safe | Off | — | **none** | our reading of vanilla |
| `render.tile-entity.batching` | Tile entity batching by texture | Safe | Off | — | **none** | our reading of vanilla |
| `render.tile-entity.double-render-fix` | Fix double-rendered tile entities | Safe | Off | — | **none** | third-party problem statement (no code read) |
| `render.entity.occlusion-culling` | Entity occlusion culling | Optional | Off | core | **none** | third-party problem statement (no code read) |
| `render.chunk.rebuild-priority` | Prioritised chunk rebuild queue | Safe | Off | — | **none** | our reading of vanilla |
| `render.chunk.skip-redundant-rebuilds` | Skip rebuilds that cannot change the mesh | Safe | Off | — | **none** | our reading of vanilla |
| `render.chunk.frustum-false-negative-fix` | Fix invisible chunks (MC-63020 / MC-70850) | Optional | Off | — | **none** | Mojang bug report |
| `cpu.allocation.hot-path-reduction` | Reduce allocation on hot paths | Safe | Off | — | **none** | our reading of vanilla |
| `memory.world-change-gc` | Remove System.gc() on world change | Safe | Off | core | isolated (`bench-2026-08-26-explicit-gc`) | our reading of vanilla |
| `resources.pack-icon-downscale` | Downscale resource pack icons | Safe | Off | core | isolated (`opt-2026-08-26-pack-icons`) | third-party problem statement (no code read) |
| `input.mc-67665-look-vector` | Fix MC-67665 look-vector staleness | Safe | Off | — | **none** | Mojang bug report |
| `input.frame-limiter-accuracy` | Accurate frame limiter | Safe | Off | — | **none** | our reading of vanilla |
| `input.unfocused-fps-cap` | Cap frame rate when unfocused | Safe | Off | — | **none** | our reading of vanilla |
| `render.chunk.worker-pool-sizing` | Size the chunk worker pool to the machine | Experimental | Off | — | **none** | our reading of vanilla |
| `render.particle.brightness-cache` | Cache particle brightness | Optional | Off | — | **none** | third-party problem statement (no code read) |

Every entry has an **id**, a **name**, a **description**, a **default state**, a
**risk class**, **compatibility requirements**, and **known conflicts** — the
fields the milestone brief requires. Ids are permanent: a user's saved
configuration refers to them, so renaming one would silently reset their choice.

Each is **individually switchable**, always. Presets (§4) are a bulk assignment
over the same per-id switches, never a separate mechanism — a design where
presets were primary could not express "the Safe preset, but without this one
thing", which is exactly what someone debugging a graphics problem needs.

---

## 3. Every entry in full

#### `render.font.batching` — Batched font rendering

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | logic in `core`, tested; not wired into the game |
| Evidence | isolated (`opt-2026-08-26-quad-batching`) |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Vanilla issues one draw call per glyph. Batching a text run into a single buffer turns a line of chat from dozens of calls into one.

**Expected benefit.** Expected to be the largest 2D win on text-heavy screens. The draw-call reduction is measured; the frame-time effect is not.

**Why this default.** Off by default: measured only in isolation (opt-2026-08-26-quad-batching). That establishes the implementation works and is not slower than what it replaces; it does not establish that the game got faster, and only an in-game run does that.

**What we measured.** A 240-glyph line spanning two font pages issues 3 draw calls instead of 240, with a vertex stream compared element by element against the unbatched path and found identical. That is the mechanism working, not a frame-time result. (`opt-2026-08-26-quad-batching`)

**Known conflicts.**

- **OptiFine** (ours off) — OptiFine rewrites large parts of the renderer, including its font paths. Fathom turns this off when OptiFine is present rather than fighting it: the user chose to install OptiFine.
- **PolyPatcher** (ours off) — PolyPatcher covers font rendering as well. Where a user has chosen it, Fathom defers rather than double-patching the same methods.

**References.** docs/research/performance.md §2.1. docs/performance/benchmarks.md — opt-2026-08-26-quad-batching.

#### `render.font.metrics-cache` — Font metrics caching

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | logic in `core`, tested; not wired into the game |
| Evidence | isolated (`bench-2026-08-26-text-metrics`) |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Character width lookups are recomputed per frame. Caching them is output-identical as long as the cache is invalidated on resource reload and language change.

**Expected benefit.** Measured 66-86% faster than recomputing, for the measurement work alone, at realistic churn. Unknown as a fraction of a frame.

**Why this default.** Off by default: measured only in isolation (bench-2026-08-26-text-metrics). That establishes the implementation works and is not slower than what it replaces; it does not establish that the game got faster, and only an in-game run does that.

**What we measured.** One frame of a busy server's text (235 strings, 11,528 characters): 12.9 us to recompute, 1.8 us cached with a static corpus and 5.2 us with 5% of strings new each frame. At 50% churn the difference falls inside the noise floor, and at 100% the cache is 47% slower. (`bench-2026-08-26-text-metrics`)

**Known conflicts.**

- **OptiFine** (ours off) — OptiFine rewrites large parts of the renderer, including its font paths. Fathom turns this off when OptiFine is present rather than fighting it: the user chose to install OptiFine.

**References.** docs/research/performance.md §2.1. docs/performance/benchmarks.md — bench-2026-08-26-text-metrics.

#### `render.hud.quad-batching` — Batched HUD and GUI quads

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Quads sharing a texture are collected into one buffer instead of being submitted individually.

**Expected benefit.** Expected to help HUD-heavy scenes. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**Known conflicts.**

- **OptiFine** (ours off) — OptiFine rewrites large parts of the renderer, including HUD drawing. Fathom turns this off when OptiFine is present rather than fighting it: the user chose to install OptiFine.

**References.** docs/research/performance.md §2.2.

#### `render.entity.model-batching` — Batched entity and item model rendering

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Entity and item models sharing state are drawn together.

**Expected benefit.** Expected to help entity-dense scenes. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**Known conflicts.**

- **OptiFine** (ours off) — OptiFine rewrites large parts of the renderer, including entity rendering. Fathom turns this off when OptiFine is present rather than fighting it: the user chose to install OptiFine.

**References.** docs/research/performance.md §2.4.

#### `render.tile-entity.batching` — Tile entity batching by texture

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Tile entities sharing a texture are grouped into one draw.

**Expected benefit.** Expected to help builds with many tile entities. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.5.

#### `render.tile-entity.double-render-fix` — Fix double-rendered tile entities

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | third-party problem statement (no code read) |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Some special tile entities are drawn twice per frame. Drawing them once is a straightforward defect fix.

**Expected benefit.** Halves the cost of the affected tile entities. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.5. Problem identified by PolyPatcher's changelog. No code read or reused; the fix is our own reading of vanilla..

#### `render.entity.occlusion-culling` — Entity occlusion culling

| | |
|---|---|
| Class | Optional |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | logic in `core`, tested; not wired into the game |
| Evidence | **none** |
| Basis | third-party problem statement (no code read) |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Skip entities fully hidden behind geometry. Must exempt nametags, ender dragons and withers, and must never reveal anything vanilla hides. Only the refusal half is written: the rules that say an entity must be drawn whatever an occlusion test concluded. There is no occlusion test yet, so this cannot currently make anything faster and is not claimed to.

**Expected benefit.** Expected to be very large in lobbies with hundreds of armour stands. Unmeasured, and breaks visibly if the culling test is wrong.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**Known conflicts.**

- **OptiFine shaders** (ours off) — PolyPatcher documents that entity culling and OptiFine shaders cannot be made compatible. Shaders present is a hard gate: culling is off, not merely adjusted.

**References.** docs/research/performance.md §2.4. docs/research/security-model.md §9 — must not reveal hidden things.

#### `render.chunk.rebuild-priority` — Prioritised chunk rebuild queue

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Rebuild nearby and in-view chunks first. Reorders work rather than throttling it, so nothing is deferred indefinitely.

**Expected benefit.** Expected to remove most join-stutter. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.3.

#### `render.chunk.skip-redundant-rebuilds` — Skip rebuilds that cannot change the mesh

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

A block update that cannot alter the rendered geometry should not trigger a rebuild.

**Expected benefit.** Expected to cut rebuild counts substantially. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.3.

#### `render.chunk.frustum-false-negative-fix` — Fix invisible chunks (MC-63020 / MC-70850)

| | |
|---|---|
| Class | Optional |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | Mojang bug report |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Vanilla's frustum culling has false negatives that make chunks disappear. Fixing it is a correctness win that costs performance.

**Expected benefit.** Negative for frame rate, positive for correctness. A trade that belongs to the user, not to us.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** Mojang bug reports MC-63020, MC-70850. docs/research/performance.md §2.3.

#### `cpu.allocation.hot-path-reduction` — Reduce allocation on hot paths

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Vanilla allocates short-lived objects in per-frame and per-tick paths. Removing them reduces GC pressure without changing behaviour.

**Expected benefit.** Expected to reduce allocation rate and therefore GC frequency. Unmeasured; the instrumentation to measure it is what this milestone builds.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §3.2.

#### `memory.world-change-gc` — Remove System.gc() on world change

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | logic in `core`, tested; not wired into the game |
| Evidence | isolated (`bench-2026-08-26-explicit-gc`) |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Vanilla calls System.gc() when changing worlds, which stops the client for as long as a full collection takes.

**Expected benefit.** Removes a stall of roughly a quarter of a second per world change on the reference machine, and more on a larger live set. The JVM flag -XX:+DisableExplicitGC achieves the same thing without a mod, and the launcher already passes it; this covers the case where it was not passed.

**Why this default.** Off by default: measured only in isolation (bench-2026-08-26-explicit-gc). That establishes the implementation works and is not slower than what it replaces; it does not establish that the game got faster, and only an in-game run does that.

**What we measured.** A forced full collection under a synthetic 512 MB live set costs a median 226 ms with G1 and 926 ms with ParallelGC, the collector every unmodded 1.8.9 player is running. -XX:+DisableExplicitGC removes it entirely: zero collections performed, 0.0 ms. Synthetic workload, not Minecraft. (`bench-2026-08-26-explicit-gc`)

**References.** docs/research/performance.md §4. docs/performance/benchmarks.md — bench-2026-08-26-explicit-gc. docs/performance/optimization-registry.md — experiment jvm.explicit-gc.

#### `resources.pack-icon-downscale` — Downscale resource pack icons

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | logic in `core`, tested; not wired into the game |
| Evidence | isolated (`opt-2026-08-26-pack-icons`) |
| Basis | third-party problem statement (no code read) |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Every installed pack's pack.png is uploaded at whatever resolution its author saved it and kept for the session, though the resource pack screen never draws one larger than 32 pixels. Downscaling to 64 on load frees the difference.

**Expected benefit.** Frees video memory in proportion to how many packs are installed and how carelessly their icons were exported. Costs nothing at frame time either way — this is a memory optimization and should not be expected to move FPS.

**Why this default.** Off by default: measured only in isolation (opt-2026-08-26-pack-icons). That establishes the implementation works and is not slower than what it replaces; it does not establish that the game got faster, and only an in-game run does that.

**What we measured.** Arithmetic over a plausible 40-pack folder: 12 MB of video memory above what the screen samples, and 4.2 MB for a single 1024x1024 icon. Exact rather than estimated, because RGBA8 texture size is a calculation and not a measurement. (`opt-2026-08-26-pack-icons`)

**References.** docs/research/performance.md §4.1. Problem identified by MemoryFix's README and by PolyPatcher's feature list. No code read or reused.. docs/performance/benchmarks.md — opt-2026-08-26-pack-icons.

#### `input.mc-67665-look-vector` — Fix MC-67665 look-vector staleness

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | Mojang bug report |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

The reported 1.8 'input lag' is a one-frame-stale look vector, not input latency. Mojang fixed it in a later version.

**Expected benefit.** Removes a frame of apparent aim latency. Perceptual; hard to measure with frame timing alone, which is a limitation the methodology document records.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** Mojang bug report MC-67665. docs/research/performance.md §6.1.

#### `input.frame-limiter-accuracy` — Accurate frame limiter

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Vanilla's FPS cap busy-waits imprecisely, producing uneven frame pacing at capped rates.

**Expected benefit.** Expected to reduce frame time variance at a cap without changing average FPS. Exactly the thing percentile metrics exist to show. Unmeasured.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §6.3.

#### `input.unfocused-fps-cap` — Cap frame rate when unfocused

| | |
|---|---|
| Class | Safe |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Render fewer frames when the window is not focused.

**Expected benefit.** Frees the GPU and CPU when the game is in the background. No effect on focused performance.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §6.5.

#### `render.chunk.worker-pool-sizing` — Size the chunk worker pool to the machine

| | |
|---|---|
| Class | Experimental |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | our reading of vanilla |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

1.8's worker count is conservative for modern core counts. Sizing it to the actual machine is a one-line change with a hardware-dependent effect.

**Expected benefit.** Expected to help chunk loading on many-core machines and to do nothing, or harm, on few-core ones. Hardware-dependent by construction, which is why it is EXPERIMENTAL.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.3.

#### `render.particle.brightness-cache` — Cache particle brightness

| | |
|---|---|
| Class | Optional |
| Surface | In-game (Mixin) |
| Default | Off |
| Implemented | **not written** |
| Evidence | **none** |
| Basis | third-party problem statement (no code read) |
| Compatibility | Minecraft 1.8.9 · legacy-fabric · Java 8+ |

Particles re-query lighting every frame. Caching it means they do not re-light as they drift, which is visible if you look for it.

**Expected benefit.** Expected to help particle-heavy scenes. Not output-identical, hence OPTIONAL rather than SAFE.

**Why this default.** Off by default: not yet measured. Nothing is promoted before it has been benchmarked, whatever its risk class.

**References.** docs/research/performance.md §2.6.

---

## 4. Presets

| Preset | Includes now | What it is |
|---|---|---|
| Vanilla | 0 of 18 | Nothing switched on. The game as Mojang shipped it, which is what every benchmark is measured against. |
| Safe defaults | 0 of 18 | Everything classified Safe that we have benchmarked ourselves. Output is identical to vanilla; nothing here changes what you see. |
| Safe, including unmeasured | 14 of 18 | Everything classified Safe, including work we have not benchmarked yet. Output-identical by design, but unproven by us. |
| Aggressive | 17 of 18 | Safe and Optional together. Some of these change what you see — hidden entities, particle lighting — so read what they do before relying on them in a competitive setting. |

**No preset includes anything Experimental**, and a test enforces it. Experimental
means hardware-, driver- or server-dependent, and a preset is by definition a
choice made without knowing the user's machine.

`Safe defaults` currently includes **zero of 18**, because it reads the same
`defaultEnabled()` rule the registry does. That is deliberate: if the preset
asserted its own membership independently, a user would get different behaviour
depending on whether they had ever opened the settings screen.

---

## 5. JVM experiments

The milestone brief is explicit that these must not be hardcoded as facts:

- "`-Xms` must equal `-Xmx`"
- "`DisableExplicitGC` is universally better"
- "specific GC flags are always optimal"

Each is plausible, each has a mechanism behind it, and **none has been measured
by us on 1.8.9 on Java 8**. `performance.md` §5.1 is explicit that most modern
JVM tuning advice does not transfer to Java 8 — precisely the situation in which
inherited folk wisdom is least reliable.

So each became an experiment: a hypothesis, arms to compare, the scenarios that
would show a difference, and — required, not optional — **what result would prove
it wrong**. Writing the falsification condition down before running anything is
what stops a result being reinterpreted afterwards to agree with what we already
believed.

**The launcher's current defaults are unchanged by this milestone.** Each
experiment records what the launcher does today and on what basis. Changing a
default on the strength of an experiment we have not run would be the same
mistake in the other direction.

#### `jvm.heap-size`

**Question.** What heap size gives the best frame-time behaviour for 1.8.9 on Java 8, and does raising it past the working set make pauses worse?

**Hypothesis.** A modest heap of roughly 2-3 GB is best, and larger heaps make each collection cover more memory and therefore take longer. performance.md §5.2 argues this from the tuning literature; we have not measured it on 1.8.9 ourselves.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| 1g | `-Xms1024M -Xmx1024M` | Below the stated 0.6-1.5 GB working set; expected to collect often. |
| 2g | `-Xms2048M -Xmx2048M` | The launcher's current default. |
| 4g | `-Xms4096M -Xmx4096M` | Comfortably above the working set. |
| 8g | `-Xms8192M -Xmx8192M` | The size the community routinely recommends. |

**Scenarios.** chunk-movement, entity-lobby, pvp-map

**Primary metric.** 0.1% low FPS and p99 frame time. Average FPS is the wrong metric here: the claim is about pause length, which averages hide.

**Falsified by.** 8g showing equal or better 0.1% low FPS than 2g across the chunk-movement and entity-lobby scenarios. That would mean the pause-length argument does not apply at these heap sizes on Java 8, and the settings screen's wording would need to change.

**What the launcher does today.** The launcher defaults to 2048 MB and its settings screen says raising it will not increase FPS. That wording is currently based on research, not on our own measurement.

*Note.* Peak heap used must be recorded per arm. If the 1g arm is at its ceiling the comparison is measuring heap exhaustion rather than collection cost.

#### `jvm.equal-min-max-heap`

**Question.** Does setting -Xms equal to -Xmx improve frame-time consistency, and does it cost anything?

**Hypothesis.** Equal min and max avoids heap-resize pauses at no cost. The 'at no cost' half is the part we have not checked: committing the full heap up front increases resident memory from the first frame, which matters on a machine that is short of RAM.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| equal | `-Xms2048M -Xmx2048M` | The launcher's current behaviour. |
| default-min | `-Xmx2048M` | Let the JVM choose the initial heap and grow it. |
| small-min | `-Xms256M -Xmx2048M` | A deliberately small initial heap, to make any resize pause visible if one exists. |

**Scenarios.** chunk-movement, entity-lobby

**Primary metric.** p99 frame time during the first two minutes after warm-up, where any growth pauses would occur, plus committed heap over time.

**Falsified by.** No measurable difference in p99 frame time between the arms. That would mean equal min/max buys nothing on this workload, and the launcher should stop committing memory it does not need.

**What the launcher does today.** build_jvm_args always emits -Xms and -Xmx at the same value, and there is no way to configure otherwise. This experiment is the reason that will become a setting rather than a constant.

*Note.* Resize pauses are rare and short. Detecting them may need GC logging rather than frame timing; that is a limitation of the in-process instrumentation, not evidence of absence.

#### `jvm.explicit-gc`

**Question.** Does -XX:+DisableExplicitGC improve the experience, and is there any workload where suppressing System.gc() makes things worse?

**Hypothesis.** Vanilla calls System.gc() on world change, which stops the client for as long as a full collection takes; suppressing it removes a visible freeze. The 'universally' part is unverified: a full collection at a natural pause point may leave the heap in a better state for the minutes that follow.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| suppressed | `-XX:+DisableExplicitGC` | The launcher's current default. |
| allowed | *(none)* | Let System.gc() run, as stock Java does. |

**Scenarios.** chunk-movement, pvp-map

**Primary metric.** Longest single frame time across a world change, and mean frame time for the two minutes after it. The first captures the freeze; the second captures whether the collection was worth it.

**Falsified by.** The 'allowed' arm showing better frame times in the minutes after a world change, by more than the freeze cost. That would make it a trade rather than a free win, and the default would need to become a choice.

**What the launcher does today.** JvmOptimizations::default() sets disable_explicit_gc = true, and build_jvm_args emits the flag. That default is unchanged by this milestone and remains unmeasured.

*Note.* The mod-side optimization memory.world-change-gc removes the call instead of suppressing it. If both are available they should be compared: the flag needs no mod, the mod needs no flag.

#### `jvm.collector`

**Question.** Which garbage collector, with how much tuning, gives the best frame-time consistency for a 1.8.9 client on Java 8?

**Hypothesis.** G1 with modest tuning beats the Java 8 default ParallelGC for pause-sensitivity, and aggressive server-oriented tuning is worse than modest tuning. performance.md §5.3 argues both halves; neither is measured.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| parallel | `-XX:+UseParallelGC` | Java 8's default. The baseline everyone actually runs. |
| g1-modest | `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` | The launcher's current default. |
| g1-untuned | `-XX:+UseG1GC` | G1 with no pause target, to isolate what the target actually does. |
| cms | `-XX:+UseConcMarkSweepGC` | Concurrent mark-sweep: available on Java 8, pause-oriented, and rarely tried for clients. |

**Scenarios.** chunk-movement, entity-lobby, particles

**Primary metric.** 0.1% low FPS, plus total GC pause time as a fraction of wall clock from GarbageCollectorMXBean.

**Falsified by.** ParallelGC matching or beating G1 on 0.1% low FPS. Given it is the default that every unmodded player already runs, that result would mean the launcher should stop overriding it.

**What the launcher does today.** build_jvm_args emits -XX:+UseG1GC with -XX:MaxGCPauseMillis=50 on every launch, unconditionally.

*Note.* Aikar's flags are deliberately not an arm. performance.md §5.3 records that they are server-oriented and produce client stutter; including them would be testing something we already have a documented reason to refuse. If someone wants them measured, that is a separate experiment with its own justification.

#### `jvm.compile-huge-methods`

**Question.** Does -XX:-DontCompileHugeMethods improve frame times enough to justify the extra code cache and compilation work?

**Hypothesis.** Several Minecraft methods exceed HotSpot's 8000-byte HugeMethodLimit and therefore run interpreted forever. Letting them compile should help. The cost is more compilation pressure, which is why the matrix classifies it OPTIONAL rather than SAFE.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| off | *(none)* | The launcher's current default. |
| on | `-XX:-DontCompileHugeMethods -XX:ReservedCodeCacheSize=400M` | Compile them, with the enlarged code cache the benchmarked flag set pairs it with. |
| on-default-cache | `-XX:-DontCompileHugeMethods` | Without the enlarged cache, to find out whether the cache size is doing the work. |

**Scenarios.** pvp-map, entity-lobby, chunk-movement

**Primary metric.** Average FPS and p99 frame time after a long warm-up. Warm-up matters more here than anywhere else: the whole effect is about what eventually gets compiled.

**Falsified by.** No measurable difference after a long warm-up, or a worse p99 from compilation pressure. Either would mean this stays off.

**What the launcher does today.** JvmOptimizations::default() sets compile_huge_methods = false. The flag is available as a per-profile toggle and is off by default.

*Note.* The default warm-up of 500 frames is probably too short for this one. A longer warm-up should be used and recorded.

#### `jvm.shenandoah-availability`

**Question.** Is Shenandoah present in the OpenJDK 8 builds a user is likely to have (Temurin 8, Corretto 8), or only in Red Hat's?

**Hypothesis.** It is a Red Hat backport and is probably absent from Temurin and Corretto, which would mean collector choice on the Java 8 path is limited to what shipped with Java 8.

**Arms.**

| Arm | Flags | Why |
|---|---|---|
| probe | `-XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC` | Start the JVM with the flag and observe whether it starts. |
| control | *(none)* | Start without it, to confirm the probe is measuring the flag and not something else. |

**Scenarios.** entity-lobby

**Primary metric.** Whether the JVM starts at all, and what GarbageCollectorMXBean reports for collector names afterwards. This is a capability check before it is a performance one.

**Falsified by.** The JVM starting with Shenandoah active on a stock Temurin 8, which would open a collector option performance.md §5.3 assumes is closed.

**What the launcher does today.** Not attempted. The launcher passes no collector flag other than G1.

*Note.* performance.md open question 1. Listed as an experiment because it is cheap to answer and it gates whether jvm.collector has more arms worth testing.

*Note.* A failed start must be distinguished from a crash. The launcher's Outcome::BadJvmArguments classification already reports 'Unrecognized VM option' distinctly, which is exactly what this probe needs.

### 5.1 Four of these have now been run

| Experiment | Answer | Did a launcher default change? |
|---|---|---|
| `jvm.explicit-gc` | `-XX:+DisableExplicitGC` removes the collection entirely: 446.7 ms → 0.0 ms, zero collections performed | No — the launcher already passes it, and it is now measured rather than assumed |
| `jvm.equal-min-max-heap` | ~23% faster through the heap-growth phase; no effect on collection cost | No — already set equal, now measured |
| `jvm.collector` | G1 pauses 4.1× shorter than ParallelGC, and is 3.4× slower through the growth phase | No — already G1, now measured **with its cost stated** |
| `jvm.heap-size` | **The common claim is wrong as usually stated** — see below | No, and this run does not justify the current 2 GB either |
| `jvm.shenandoah-availability` | Shenandoah is **not present** on Temurin 1.8.0_502 | No — it was never an option |

**The heap-size result is the one worth reading.** Sweeping `-Xmx` with the live set
scaled to half the heap gives 221 / 450 / 909 / 2,271 ms for 1 / 2 / 4 / 8 GB, which
reads as "8 GB makes world changes take 2.3 seconds" — the claim everyone repeats.
Two things varied at once, so it establishes nothing. Pinning the live set at 512 MB
and varying only `-Xmx` gives **220 / 229 / 238 / 241 ms**: flat.

Full-collection cost tracks the **live set**, not `-Xmx`. A larger heap is slower to
collect only because it lets a larger live set accumulate. The fix is not a smaller
`-Xmx`; it is not forcing full collections at all.

Full data, including the limitations of a synthetic workload, in
`docs/performance/benchmarks.md` §4. `baseline.md` §5 covers the Shenandoah probe.

---

## 6. Scenarios

| ID | Reproducibility | Comparable | Seconds |
|---|---|---|---|
| `pvp-map` | SCRIPTED_LOCAL | yes | 180 |
| `entity-lobby` | AUTOMATABLE | yes | 120 |
| `text-heavy` | AUTOMATABLE | yes | 120 |
| `particles` | SCRIPTED_LOCAL | yes | 180 |
| `chunk-movement` | SCRIPTED_LOCAL | yes | 180 |
| `inventory-gui` | AUTOMATABLE | yes | 90 |
| `resource-pack` | NOT_YET_REPRODUCIBLE | **no** | 120 |

Three of the seven are not comparable between sessions, and one is not
reproducible at all. `benchmark-methodology.md` §4.1 records why for each.

---

## 7. Source discipline

Every entry records a **basis** — where the knowledge came from:

| Basis | Meaning |
|---|---|
| Mojang bug report | A documented defect, and where one exists, Mojang's own later fix. The preferred basis. |
| Our reading of vanilla | Our own analysis of the decompiled game. |
| Third-party problem statement | A restricted-licence project's public feature list or changelog told us a problem exists. **No code read or reused.** |
| Platform literature | General JVM or OpenGL sources. |

`mod-policies.md` §3 establishes that Patcher and PolyPatcher are CC BY-NC-SA and
their **code** is not reusable by us. Knowing *that a problem exists* is a fact
about Minecraft; knowing *how someone else solved it* is their work. Three
entries carry the third-party basis, and each says in its references that the
problem statement was the only thing taken.

Where an optimization corresponds to a known Mojang bug — `MC-67665`,
`MC-63020`, `MC-70850` — that is the basis cited and the reference given.

---

## 8. Regenerating this document

The tables come from the code:

```bash
cd minecraft/fathom-optimizations
javac -d build/classes $(find src/main/java/net/fathom/optimizations/core -name '*.java')
javac -cp build/classes -d build/classes ../../tools/GenRegistryDoc.java
java -cp build/classes GenRegistryDoc
```

If a table here disagrees with the code, the code is right and this file is
stale.

---

## 9. References

- `docs/performance/optimizations.md` — what was implemented, what was rejected, and why
- `docs/performance/benchmarks.md` — every run behind the evidence column
- `docs/performance/benchmark-methodology.md` — how anything here gets measured
- `docs/performance/baseline.md` — the environment, and where results go
- `docs/research/performance.md` — the analysis these entries encode
- `docs/research/PERFORMANCE_MATRIX.md` — the classification scheme
- `docs/research/mod-policies.md` §3, §7, §8 — licensing and conflicts
