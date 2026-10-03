# Where a frame goes when you look at the ground — 2026-10-02

Fathom's view-direction profile: why a 1.8.9 frame that runs at 3,500+ FPS
looking at the sky drops to a few hundred looking at terrain, measured at
~45 exact boundaries inside the frame, with OptiFine installed.

## Result

**Terrain draw submission explains 95-98% of the sky-to-ground gap.** It is
OptiFine-patched vanilla code drawing one chunk section per layer per call,
about 0.6 µs of CPU each. Looking 45° down at render distance 8 that is ~580
draws a frame. The fix already exists in OptiFine — **Render Regions**, off by
default — and halves ground-facing frames. Fathom does not duplicate it and does
not turn it on; the launcher now shows the measurement and offers the switch.

**Fathom is not interfering with terrain.** No Fathom hook runs inside the
terrain loop under OptiFine, no chunk was rebuilt or uploaded in any settled
view, and Fathom's net cost was 0.044-0.062 ms a frame in every scene — almost
all of it its own HUD, now 0.015 ms cheaper.

## Method

- **Harness:** `-Dfathom.benchmark=view` (`ViewBenchmark`, `ViewProbe`). Its
  Mixins apply only when asked for; a normal launch carries none of them.
- **Scenes** (fixed camera, the player's own world and position): sky (pitch
  −90), horizon, 45° down, straight down, 48 armoured players with nametags,
  a steady particle field (~3,700 live), glass and water walls.
- **Interleaving:** each scene × arm gets a 3 s window per round; arm order
  reverses on alternate rounds; 2-3 rounds.
- **Settling:** each window waits ≥1 s *and* 0.5 s with no chunk rebuild or
  upload.
- **Warm-up:** every scene once, not recorded.
- **Frame time:** loop head to loop head, minus the harness's own time.
  Sections are self time, so they sum to the frame (the untimed remainder was
  0.0003 ms).
- **Overhead:**
  - one timed boundary costs 48-55 ns (calibrated at start);
  - ~27 boundaries a frame ≈ 0.0015 ms;
  - the probes-off arm agreed within window noise.
- **Rejected frames:** none. Frames were rejected only with a screen open or
  the world unloaded. Unfocused frames counted (no pause, no frame cap in this
  mode), and their share is reported.
- **Setup:**
  - RTX 4060, driver 617.14
  - 1920×1080 windowed, render distance 8, VBO on
  - OptiFine HD U M5 via OptiFabric 2.3.6, Fathom with the `optimized` profile's
    switches
  - Java 8u502, G1, 4 GB heap

## Scenes (normal arm, baseline run)

| Scene | Median | Mean | p95 | p99 | Terrain | Sections drawn |
|---|---:|---:|---:|---:|---:|---:|
| sky | 0.232 | 0.264 | 0.405 | 0.595 | 0.008 | 3 |
| horizon | 0.472 | 0.505 | 0.667 | 0.877 | 0.245 | 412 |
| 45° down | 0.577 | 0.624 | 0.930 | 1.086 | 0.348 | 580 |
| straight down | 0.759 | 0.834 | 1.257 | 1.475 | 0.518 | 837 |

All values in ms per frame. Entities and particles in their own scenes, from
the run after the harness placed them at the player:

| Scene | Mean | p99 | Main cost |
|---|---:|---:|---|
| 48 players (10 in view) | 0.842 | 1.393 | entity draws 0.226 ms (23 µs per armoured player), nametags 0.049 ms |
| particles (3,760 live) | 1.656 | 5.224 | particle drawing 0.946 ms (0.25 µs each); particle tick 3.2 ms every 50 ms |

## Where the extra time goes, 45° down vs sky

| Δ self time | Section | Share of Δ | Owner |
|---:|---|---:|---|
| +0.173 | terrain: solid layer | 48% | vanilla, OptiFine-patched |
| +0.101 | terrain: cutout-mipped layer (leaves, grass) | 28% | vanilla, OptiFine-patched |
| +0.061 | terrain: translucent layer | 17% | vanilla, OptiFine-patched |
| +0.006 | buffer swap | 2% | driver |
| +0.004 | terrain visibility (`setupTerrain`) | 1% | vanilla, OptiFine-patched |
| | **listed: 98.4% of the +0.359 ms** | | |

## Top 10 frame-time consumers, 45° down

| # | Section | ms | % | Owner |
|---:|---|---:|---:|---|
| 1 | terrain: solid layer | 0.180 | 28.8 | vanilla, OptiFine-patched |
| 2 | terrain: cutout-mipped layer | 0.101 | 16.2 | vanilla, OptiFine-patched |
| 3 | buffer swap | 0.075 | 12.0 | driver / GPU |
| 4 | terrain: translucent layer | 0.061 | 9.8 | vanilla, OptiFine-patched |
| 5 | vanilla HUD (hotbar, crosshair, text) | 0.052 | 8.3 | vanilla |
| 6 | Fathom HUD | 0.047 | 7.5 | Fathom |
| 7 | game loop self (timer, sound, profiler) | 0.029 | 4.6 | vanilla |
| 8 | world pass self (camera, fog, clear, GL state) | 0.028 | 4.5 | vanilla, OptiFine-patched |
| 9 | sky | 0.011 | 1.8 | vanilla, OptiFine-patched |
| 10 | hand / held item | 0.010 | 1.6 | vanilla, OptiFine-patched |

Simulation is not part of the story: the client tick was 0.002-0.005 ms a frame
amortised (one 20 Hz tick spread over ~80 frames), network packets ~0, entity
ticking ~0.001.

## Is this CPU or GPU?

CPU-side submission. The swap — where an NVIDIA driver makes the CPU wait for
the GPU — moved only 0.069 → 0.083 ms between sky and straight down, while the
layer draws grew by 0.5 ms. A `glFinish` arm showed 0.18-0.49 ms of GPU work
still queued at frame end. That is the GPU keeping pace a little behind the CPU,
not the GPU limiting it.

## What did and did not interfere

| Question | Finding |
|---|---|
| Is OptiFine's fast path active? | VBOs yes. **Render Regions off** and **Fast Render off** (OptiFine's defaults, not set by Fathom: nothing in Fathom writes `optionsof.txt`). |
| Fathom forcing rebuilds? | No. 0 rebuilds and 0-0.06 invalidations a frame in settled views. |
| Fathom hooks in hot terrain loops? | None under OptiFine. The two direction-array Mixins are skipped; the per-layer timing hook is 2 callbacks a layer. |
| Fathom disabling an OptiFine optimization? | No. |
| Fathom disabling *its own*? | Yes. The chunk lookup cache was skipped under OptiFine on the false belief that OptiFine patches the chunk provider. Checked against the classes OptiFabric actually patches, it does not. Re-enabled and A/B'd, it moved nothing outside the noise (entities −3.7%, terrain views +1.5-2.9%), so it stays off — now for the measured reason, and the launcher says so. |
| `glGet` stalls? | Camera readback 0.0015 ms, frustum readback 0.0004 ms: not stalling here. Fathom's HUD GL-state snapshot never runs (no widget uses Fathom's renderer by default). |

## Changes, each measured on its own

### OptiFine Render Regions (offered, not enabled)

| Scene | Mean before → after | Median | p95 | p99 |
|---|---|---|---|---|
| horizon | 0.533 → 0.321 (−0.212, −40%) | 0.497 → 0.290 | 0.705 → 0.415 | 0.892 → 0.648 |
| 45° down | 0.672 → 0.354 (−0.319, −47%) | 0.627 → 0.321 | 0.917 → 0.494 | 1.139 → 0.672 |
| straight down | 0.755 → 0.418 (−0.338, −45%) | 0.714 → 0.383 | 0.939 → 0.549 | 1.250 → 0.760 |
| entities | 0.815 → 0.604 (−0.211, −26%) | 0.774 → 0.567 | 0.990 → 0.780 | 1.363 → 1.116 |
| sky | 0.252 → 0.250 (−0.002) | 0.223 → 0.220 | 0.351 → 0.357 | 0.523 → 0.526 |

- **Correctness:** screenshots of every static scene with and without it
  differ only in moving clouds, animated water and the FPS counter.
- **Where it lives:** the launcher's Performance page, when a profile runs
  OptiFine; it edits only `ofRenderRegions` in `optionsof.txt`, and only when
  pressed.
- **Caveat:** OptiFine documents rendering problems on some older Intel and AMD
  drivers, which is a reason it stays the player's choice.

### HUD steady cache (`HudSteadyCache`, part of "HUD caching")

Replays Fathom's realtime widgets (keystrokes, CPS, text readouts) on frames
where their content is unchanged.

**Run 5:** 3 rounds, each scene against the arm with the cache off:

| Scene | Mean | Median | p95 | p99 |
|---|---|---|---|---|
| sky | 0.283 → 0.256 (−0.026) | 0.248 → 0.228 | 0.389 → 0.362 | 0.594 → 0.529 |
| 45° down | 0.698 → 0.671 (−0.027) | 0.656 → 0.630 | 0.962 → 0.886 | 1.184 → 1.201 |
| entities | 0.871 → 0.842 (−0.029) | 0.821 → 0.793 | 1.193 → 1.157 | 1.546 → 1.505 |

- **Run 6:** 2 rounds, 7 scenes; the cache was faster in all 7, by 0.003-0.041 ms.
- **Fathom's HUD:** 0.042 → 0.027 ms per frame.
- **Text draws:** 15 → 7 per frame.
- **Pixel check:** keystrokes and CPS identical in every scene's screenshot.
- **GL state:** no leak visible; the world behind the HUD and the next frame
  are unchanged.

### Dead GL readback removed

`HudLayerCache` captured the GL state after every re-record (about 25 `glGet`
calls, 20 times a second) and never read it. Removed. It is not measured on its
own, and nothing depended on it.

### Frame limiter (`FramePacer`, "Accurate frame limiter") — 2026-10-02

Replaces `Display.sync` in the game loop with fixed deadlines on
`System.nanoTime`: sleep while more than 1.5 ms remains, then yield; a frame a
whole period late releases at once and restarts the schedule. Run
`view-2026-10-02-pace144`: OptiFine profile, frame cap 144, arms `normal` vs
`off:input.frame-limiter-accuracy`, 3 rounds × 3 s per view.

| View | sd on → off | p1 on → off | p99 on → off | mean on → off |
|---|---|---|---|---|
| sky | 0.242 → 0.511 ms | 6.37 → 5.51 | 7.58 → 7.87 | 6.948 → 6.941 |
| horizon | 0.377 → 0.549 ms | 6.41 → 5.63 | 7.52 → 7.87 | 6.959 → 6.941 |
| 45° down | 0.406 → 0.516 ms | 6.20 → 5.51 | 7.82 → 7.80 | 6.961 → 6.941 |

Same average; frames sit closer to 6.94 ms on both sides. Default on: it is
output-identical and does nothing without a cap.

### Chunk worker pool (`ChunkWorkerPolicy`, "Chunk rebuild scheduling") — 2026-10-03

Vanilla 1.8.9 builds chunks on 2 threads with 5 buffers; this uses half the
hardware threads (2-5) and workers + 3 buffers. Not under OptiFine, which sizes
its own pool. Run `view-2026-10-03-chunk-workers`: the user's profile *without*
OptiFine, scene `reload` (renderer reload at window start; the window ends after
0.5 s with no rebuild), four launches on/off/off/on because the pool is fixed
when the renderer is built, 3 reloads each. Window length (rebuild + 0.5 s):

| Launch | Reload 1 | Reload 2 | Reload 3 |
|---|---|---|---|
| on (5 workers) | 0.672 s | 0.649 s | 0.635 s |
| off (2 workers) | 1.267 s | 0.738 s | 0.724 s |
| off (2 workers) | 0.774 s | 1.003 s | 0.724 s |
| on (5 workers) | 0.653 s | 0.642 s | 1.123 s |

Median 0.65 s on vs 0.76 s off; mean 0.73 s vs 0.87 s. So the rebuild itself is
roughly 0.15 s against 0.26 s. Every arm had one slow outlier and this is one
machine, so it ships switchable and **off** by default. Of the three ideas this
entry merged, rebuild prioritisation was dropped (vanilla already sorts by
distance) and redundant-rebuild skipping was dropped (none were observed).

### Rejected

- **Chunk lookup cache under OptiFine:** no benefit (above).
- **Camera/frustum readback replacement, `Thread.yield` removal:** together
  under 3 µs a frame.
- **Particle brightness caching:** the light lookup is not the particle cost.

## Remaining candidates, by measured size

1. **Particle tick spike** (B): 3.2 ms every 50 ms with 3,700 particles, a p99 of
   5 ms. It is vanilla particle physics; making it cheaper means changing how
   particles collide, which is simulation, so it needs its own design.
2. **Entity drawing** (B): 23 µs per armoured player. This is where occlusion
   culling (planned) would pay. Nametags are already on Fathom's replay path at
   5.5 µs each.
3. **Vanilla HUD** (B/C): 0.04-0.05 ms, mostly hotbar items.
4. **Fast Render** (C): OptiFine setting, −4-8% in terrain views. Not offered
   yet, because it changes GL state handling and has not been checked against
   Fathom's HUD.
