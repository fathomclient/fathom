# Fathom interleaved core A/B benchmark — 2026-09-21

## Verdict

Core Bypass does **not** consistently or materially improve frame time. Across 20 valid adjacent pairs (replicated in a second 20-pair run with a working entity-render counter, below), the median paired Normal-minus-Bypass delta was **-0.0153 ms** and the mean was **-0.0338 ms**: Normal was marginally faster, not slower. Bypass won 8/20 pairs, only 5/20 by at least 0.05 ms, with an exact two-sided sign-test p-value of 0.50344.

The earlier ordered-pass result (+2.289 ms in favor of bypass) was therefore sequential runtime/scene drift, not causal Fathom core overhead. No downstream mechanism appeared: the paired states had identical render-pass and chunk-rebuild rates, stable OptiFine/VBO paths, and zero sampled GL-state leaks.

## Method

- One continuous authenticated Minecraft process and one multiplayer lobby scene.
- 20 adjacent A/B pairs (40 epochs), balanced 10 AB / 10 BA in a fixed pseudo-random order.
- A = Normal Fathom core; B = Core Bypass. Optional HUD/modules remained disabled.
- Each epoch discarded 0.75 seconds for stabilization, then measured 2.50 seconds.
- 20/20 pairs were valid; no focus, world, movement, camera, GC-pause, or major scene-change invalidations.
- Run duration: 140 seconds. Windowed 854x480, render distance 8, VSync off, 260 FPS limit, OptiFine HD U M5 with OptiFabric, Java 8.0.502, 4096 MiB heap.

## Results

| Measure | Result |
|---|---:|
| Pooled median, A Normal | 2.1180 ms |
| Pooled median, B Core Bypass | 2.1517 ms |
| Median paired A-B delta | -0.0153 ms |
| Mean paired A-B delta | -0.0338 ms |
| Paired standard deviation | 0.1300 ms |
| Paired IQR | 0.0907 ms |
| B faster / A faster | 8 / 12 |
| B faster by at least 0.05 ms | 5 / 20 |
| Exact two-sided sign-test | p = 0.50344 |

Every epoch's mean, median, p95, p99, and sample count is preserved in the runtime report. Individual paired deltas ranged from -0.3497 to +0.1729 ms and alternated in sign.

Order stratification points directly at drift: mean A-B was **+0.0241 ms in AB pairs** (B measured second) but **-0.0917 ms in BA pairs** (A measured second). Counterbalancing cancels that order-dependent movement; an ordered six-pass sequence does not.

## Environmental and indirect-state checks

| Metric (valid-epoch average) | A Normal | B Bypass | B-A |
|---|---:|---:|---:|
| Visible entities | 91.025 | 91.175 | +0.150 |
| Loaded entities | 101.525 | 101.725 | +0.200 |
| Server player-list count | 15.200 | 15.350 | +0.150 |
| Rendered chunks | 258.000 | 258.000 | 0.000 |
| Particles | 7.125 | 6.350 | -0.775 |
| Chunk rebuilds/epoch | 0.050 | 0.050 | 0.000 |
| Client ticks/frame | 0.045 | 0.046 | +0.001 |
| Chunk-layer calls/frame | 4.000 | 4.000 | 0.000 |
| GC count/epoch | 0.150 | 0.150 | 0.000 |
| GC milliseconds/epoch | 0.750 | 0.300 | -0.450 |
| Heap change MiB/epoch | 273.412 | 268.118 | -5.294 |
| Player/camera movement | 0 / 0 | 0 / 0 | 0 / 0 |

The harness sampled 40 HUD-hook GL boundaries during discarded stabilization. It found zero changes to framebuffer, viewport, scissor, blend, depth, alpha, culling, active texture/binding, shader program, VBO, or index-buffer state. OptiFine/VBO settings were invariant: VBO on, Fast Render off, Fast Math off, Smooth FPS off, Smooth World on, Lazy Chunk Loading on, Render Regions off.

Static and runtime checks also found no Core Bypass write to OptiFine fast paths, VBO, entity culling, framebuffer, timer, or tick settings. With OptiFine present, conflicting Fathom WorldRenderer, occlusion, and chunk-provider production Mixins remain skipped; the active overlap is limited to two field-read redirects in OptiFine-patched `GameRenderer.render`.

## Replication with a working entity counter (21:06)

In the first run the entity-render counter never fired. `EntityRenderDispatcherTimingMixin` hooked `render(Entity,DDDFF)Z` (`method_6912`, MCP `renderEntityWithPosYaw`), but the in-world path, `renderEntityStatic`, calls `doRenderEntity` (`method_6913`, `(Entity,DDDFFZ)Z`) directly. The hook now targets `method_6913` and applies cleanly under OptiFine/OptiFabric. A second paired run then used the same protocol: one process, the same lobby, 20 pairs (10 AB, 10 BA), 0.75 s settle plus 2.50 s measured per state, 140 s in total.

| Measure | Run 1 (19:39) | Run 2 (21:06) |
|---|---:|---:|
| Valid pairs | 20/20 | 20/20 |
| Pooled median, A Normal | 2.1180 ms | 2.0609 ms |
| Pooled median, B Core Bypass | 2.1517 ms | 2.0380 ms |
| Median paired A-B delta | -0.0153 ms | -0.0193 ms |
| Mean paired A-B delta | -0.0338 ms | +0.0155 ms |
| Paired SD / IQR | 0.130 / 0.091 ms | 0.108 / 0.161 ms |
| B faster / A faster | 8 / 12 | 10 / 10 |
| B faster by at least 0.05 ms | 5 / 20 | 8 / 20 |
| Sign-test p | 0.503 | 1.000 |
| Mean A-B in AB / BA pairs | +0.024 / -0.092 ms | +0.036 / -0.005 ms |

Run 2 environment (B-A):

- **Entity renders/frame:** 89.81 vs 89.57 (-0.24). Visible entities were 89.80 vs 89.58 (-0.225).
- **Chunks:** 258 rendered in both states; 0 rebuilds in both; 4.0 chunk-layer calls/frame in both.
- **Other:** particles +1.9; GC count/epoch 0.2 vs 0.1; no player or camera movement.
- **Validity:** 0 invalid pairs; 0 GL-state leaks in 40 samples; OptiFine/VBO fingerprint stable.

Entity renders per frame track the visible entity count in both states, so Core Bypass does not change how many entities Minecraft/OptiFine draws. Both runs put the paired effect at roughly zero: about 0.02 ms, with the sign flipping from pair to pair.

Run 2 ran fullscreen at 1920x1080, because the profile's `options.txt` is set to fullscreen. Run 1 was windowed at 854x480. Each run compares A and B at a single resolution, so the paired result holds either way. Frame times are nearly identical at both resolutions, which suggests this scene is CPU-bound.

## Remaining limitation

- GL-state audit coverage is narrow: 40 samples, all from one hook boundary (HUD), taken during stabilization frames. It rules out a leak from that hook, not from every Fathom render hook. No frame-time difference remains for a leak to explain.

## Validation and artifacts

- Full Gradle `build`: successful; benchmark/core tests 44 passed and all other suites passed (re-run after the counter fix).
- Runtime reports: `C:\Users\jb\AppData\Local\Fathom\data\profiles\optimized\minecraft\logs\fathom-core-paired-2026-09-21-19-39-26.txt` (run 1) and `fathom-core-paired-2026-09-21-21-06-27.txt` in the same folder (run 2).
- The failed fullscreen startup produced no samples and is excluded. The successful run was uninterrupted.
- Temporary benchmark, auto-connect, and windowed flags were removed/restored after the run.
- Diagnosis only: no optimization behavior was changed. The benchmark and GL audit remain property-gated and inert in ordinary launches.
