# Gameplay optimization, September 2026

Measured work on Fathom's own per-frame cost and on the hottest vanilla paths
found by profiling the real client. Every number below comes from the scripted
benchmark in `fabric/bench/PerfBench.java` or from Java Flight Recorder
sampling of the same runs.

## Setup

- Development client (`gradle runClient -Pnanovg`), Minecraft 1.8.9, Legacy
  Fabric, game JVM on JDK 17 (G1).
- 1280×720 windowed, render distance 12, fancy graphics, smooth lighting max,
  all particles, VBOs off, VSync off, frame limit Unlimited (260), GUI scale auto.
- The development save `New World`, singleplayer. No multiplayer server was
  available, so scenario E was not run.
- Machine: i5-12400F, RTX 4060, Windows 11. Frame rates are 400–1600 fps, so a
  frame is 0.6–2.5 ms and background activity on the machine moves separate runs
  by tens of percent. **Only comparisons inside one session are trusted**; see
  "Method".

### Running it

```text
gradle runClient -Pnanovg -PperfBench=world -PperfLabel=name [-PjfrFile=out.jfr]
gradle runClient -Pnanovg -PperfBench=ab -PperfAb=id1,id2 [-PperfOn=id] [-PperfAbScenarios=a-still,b-pan] [-PperfAbReverse=true]
gradle runClient -Pnanovg -PperfBench=soak [-PperfSoakSeconds=90]
```

`world` runs each scenario once (settle, then 20 s measured) and prints FPS,
frame-time percentiles, 1% and 0.1% lows, Fathom's own section costs, heap,
allocation rate and GC. Results are also written to `run/fathom-bench/`.
`ab` alternates the listed optimization switches in ABBA windows inside one
session. `soak` holds the main menu, Hub, settings drawer and HUD editor open and
reports FPS per 10 s window plus heap after a full collection at each end.

While a benchmark runs, the unfocused frame cap is bypassed and
pause-on-focus-loss is disabled in memory, because a launched window often lacks
focus. Neither is saved; neither changes rendering work.

Scenarios: **a** still at the horizon, **b** panning 45°/s, **c** looking down,
**d** flying 12 blocks/s through terrain, **g** every HUD module on,
**f** Hub open over the world, **h** vanilla flame/smoke particles spawned in
view (particle spawning proved unreliable in this save; see "Limitations").

## Method

The first attempt compared separate runs and could not be trusted: the same
still view measured 844 fps in one scenario and 1468 fps in another in the same
run. Two controls replaced it:

1. **Interleaved A/B.** Switches alternate in ABBA order, three cycles, 4–5 s
   windows, inside one session and one world state, so drift cancels and each
   condition pools ~30 s of frames.
2. **Profiler shares.** JFR execution and allocation samples of the render
   thread, compared as percentages, which do not move with machine noise.

A result counts only when both agree, or when repeated interleaved blocks agree
in sign.

## Bottlenecks found

Render thread, baseline JFR run (2180 CPU samples, 16.7 GB allocated):

| Where | CPU share | Allocation share | Owner |
| --- | --- | --- | --- |
| `Particle.draw` → light lookup → chunk hash lookup (scene with torches) | 37% incl. | 23% | vanilla |
| `LongObjectStorage.get` (chunk lookup) | 28% self | — | vanilla |
| `WorldRenderer.setupTerrain` (visibility search) | 31% incl. | 51% incl. | vanilla |
| `Direction.values()` array clones | — | 22% | vanilla |
| `BlockPos.offset` | — | 22% | vanilla |
| `LinkedList` nodes in `setupTerrain` | 8% self | 14% | vanilla |
| `OptimizationId.of` via `TextRendererWidthMixin` (regex + allocation per `getStringWidth`) | — | 7.3% | **Fathom** |
| Fathom HUD (`TextWidget` rebuilding lines 6–8× per widget per frame) | 0.071–0.090 ms/frame | ~5% | **Fathom** |
| Fancy clouds | 3–14% incl. | — | vanilla |

Also found:

- **Main-menu video decoder**: `ImageIO.read` allocated a new 6 MB frame image
  30 times a second — 449 young collections in a 90 s soak.
- **Settings save on the render thread**: 2–4 ms per save, 64 ms for the first
  of a session, whenever the Hub closes dirty or the HUD editor commits an edit.
- `Runtime.unfocusedFrameCap()` copied the settings list on every
  `getMaxFramerate`; several `System.getProperty` reads per HUD frame;
  `HudRegistry.all()` and `ModuleRegistry.owningWidget` allocating or scanning
  per widget per frame.
- **Vanilla world-change GC**: joining the save ran three forced full
  collections (21.9, 23.6, 23.9 ms on this small heap).
- **Hub**: 0.89 ms/frame, almost all native (NanoVG tessellation and GL). Only 5
  Java samples fell inside the Hub in a 20 s run.

## Optimizations

### Fathom's own hot paths (always on)

- `OptimizationId.of` interns validated ids; hot Mixins read static switch flags
  refreshed once per frame instead of looking ids up per call.
- HUD: per-frame memo of each text widget's lines and measured size; Clock
  formats once per second; reused font wrappers; widget-owner map instead of a
  scan; cached widget list; launch-time property reads made constants.
- Keystrokes and Crosshair draw every rectangle in one batched tessellator draw
  (`QuadBatch`) instead of one draw per rectangle (~35 for Keystrokes, up to ~90
  for the circle crosshair). Same vertices, blend function and ordering.
- `unfocusedFrameCap()` reads the one setting directly.
- Menu video decodes into one reused raster with one reused JPEG reader.
- Profile saves: snapshot and JSON on the render thread, disk write on a single
  background writer. Saves of one profile coalesce; every other store operation
  waits for pending writes; a shutdown hook flushes. Tested in
  `ProfileTests.aBackgroundSaveIsNeverReadStale`.

### Vanilla hooks (switchable, Performance page)

| Id | Mixin | What |
| --- | --- | --- |
| `cpu.allocation.hot-path-reduction` | `WorldDirectionsMixin`, `ChunkVisibilityDirectionsMixin`, `WorldRendererDirectionsMixin` | Share one `Direction[]` at loops that only read it (bytecode checked) |
| `world.chunk.lookup-cache` | `ClientChunkProviderMixin` | 1024-slot direct-mapped cache of chunk lookups on the client thread, cleared on every chunk load/unload |
| `memory.world-change-gc` | `MinecraftClientWorldGcMixin` | Routes the two world-change `System.gc()` calls through the tested `ExplicitGcPolicy`; every collection run is timed and logged |

The first two carry in-game evidence and default on in the module itself.
`memory.world-change-gc` keeps isolated evidence (see below), so the module
leaves it off without a launcher. The launcher catalogue
(`crates/launcher-core/src/optimizations/mod.rs`) now reports all three as active,
and its Balanced preset switches on all three.

## Measurements

### Fathom HUD cost (Probe `HUD` section, ms per frame)

| Scenario | Before | After |
| --- | --- | --- |
| a / b / c / d (default HUD) | 0.071–0.078 | 0.023–0.026 |
| g (every HUD module) | 0.090 | 0.037–0.041 |

About **65% less**. The `MODULES` section fell from 0.001 to below 0.0005 ms.

### Vanilla hooks, interleaved A/B (both switches)

| Scenario | Mean frame time | p99 | Allocation | GCs |
| --- | --- | --- | --- | --- |
| b pan | **−1.8% / −8.3%** (two sessions) | 1.60→1.55 / 4.44→3.68 ms | **51.8→23.4 MB/s** | 6→3 |
| d move | **−4.6% / −4.8%** | 4.37→3.99 / 3.92→3.79 ms | **203→132 MB/s** | 26→18 |
| h particles | −0.5% / −7.1% | 1.41→1.37 / 1.31→1.16 ms | ≈ | ≈ |
| c ground | −0.4% / +0.2% | ≈ | ≈ | ≈ |
| a still | −5.0% / +23% (see below) | | ≈ | |

### Chunk lookup cache alone, order-controlled

Scenario **a** repeated four times back to back, alternating ABBA and BAAB:
**−6.5%, −6.5%, −7.2%, −9.9%** mean frame time; p99 lower in every block. The
+23–25% readings in two earlier sessions were each the first block after
joining the world, and did not reproduce once position was controlled.

Profiler, chunk cache off → on: chunk lookups **14.3% → 5.1%** of render-thread
CPU samples; `World.getChunk` 15.5% → 6.3% inclusive; `renderEntities` 33% → 21.5%.
(A first 16-slot version did not help entity rendering, whose lookups span
hundreds of columns; that was measured and replaced.)

### Allocation profile (JFR, render thread, same scenario set)

`Direction.values()` clones **1890 MB → 91 MB**; total **9078 MB → 7515 MB (−17%)**.
With the Fathom-side fixes, `OptimizationId.of` and HUD strings no longer appear
among the top allocation sites.

### Main menu and screens (soak)

| | Before | After |
| --- | --- | --- |
| Menu young GCs | 449 / 90 s (5.0/s, 321 ms) | 56 / 45 s (1.2/s, 29 ms) |
| Menu p99 / 1% low / avg | 1.03 ms / 445 / 3865 fps | 0.60 ms / 493 / 4117 fps |
| Render-thread profile save | 2–4 ms, 64 ms first | 0.13–0.20 ms (1.7–2.7 ms first, starts the writer) |

No degradation while screens stay open: per-10 s FPS stays flat (menu
4050–4150, Hub 983–995, settings 1068–1072, editor 4693–4740) and heap after a
full collection moves by at most 4 MB over each phase.

### World-change GC

Joining with the switch off: three full collections, 21.9 + 23.6 + 23.9 ms.
With it on: the first ran, the next two were skipped. On a server-sized heap each
collection is far longer; this save's heap is too small to quote a number for
that, so the entry keeps isolated evidence and is not promoted.

## Unfocused FPS limit (audited, unchanged in behaviour)

`MinecraftClientFrameCapMixin` evaluates the policy on every `getMaxFramerate`,
so it lowers the limit the frame focus is lost and restores it the frame focus
returns. It only ever lowers while unfocused, treats Unlimited as no cap, and
never interferes with the in-world or menu limits. Vanilla applies the result via
LWJGL `Display.sync` (sleep plus a short yield), so there is no busy wait. The
only change was removing its per-frame list copy and id parse.

## Rejected as unsafe or unproven

- **Particle brightness cached per tick.** Vanilla re-lights from the current
  position every frame; a block-light change delivered between ticks would lag
  up to a tick. Not output-identical, so not done (the existing OPTIONAL registry
  entry stays unimplemented).
- **Entity occlusion culling.** Vanilla already frustum-culls entities; occlusion
  can hide entities that should be visible and conflicts with shader packs.
- **Replacing `setupTerrain`'s `LinkedList` or pooling `ChunkInfo` / `BlockPos`.**
  The largest remaining allocation, but requires rewriting vanilla's visibility
  search. Not worth the correctness risk in this pass.
- **Reducing the Hub's NanoVG work.** Would change the interface's appearance.
- **Removing every `System.gc()`.** Shutdown and out-of-memory recovery calls are
  left alone.

## Limitations

- Singleplayer only; no multiplayer measurement.
- The particle scenario's spawns were inconsistent in this save (0 to ~300 live),
  so particle results are weaker than the others. The baseline profile that
  showed particles at 37% of CPU came from torches in view.
- One machine, one resolution.

## Remaining bottlenecks

1. `WorldRenderer.setupTerrain`: 24–26% of render-thread CPU and the largest
   allocation source (`LinkedList` nodes, `ChunkInfo`, `EnumSet`, `BlockPos`).
2. `WorldRenderer.renderEntities` self time (~20%): the per-section loop.
3. Fancy clouds rebuilt every frame (3–14%).
4. `BufferBuilder.nextElement` per-vertex overhead for particles and entities.
5. Hub draw cost (~0.9 ms/frame) in native NanoVG and GL.
