# Fathom core-isolation benchmark — 2026-09-21

## Verdict

The six-pass run reduced the former ~2.76 ms/frame `unaccounted` bucket to 0.4725 ms/frame by adding nested timing boundaries. The observed Normal Core versus Core Bypass gap was 2.289 ms/frame and exceeded the run's 1.394 ms/frame within-pass threshold.

That difference is not sufficient to conclude that Fathom core integration costs 2.289 ms/frame. The controls disagree with the direct timers: bypassing a dispatch path measured at 0.0022 ms/frame appeared to save 1.886 ms/frame, while the instrumentation control produced a non-physical -1.031 ms/frame overhead estimate. Normal Core also had a 4.282 ms mean but 2.424 ms median, showing large transient outliers. These are signs of pass-order/JIT/scene/thermal drift in the live multiplayer lobby.

Result: the observed difference is statistically distinguishable at the frame-sample level, but causal Fathom core overhead is **inconclusive** without counterbalanced run-level replication. No optimization change is justified by this run alone.

> **Superseded:** the follow-up interleaved A/B run ([core-paired-2026-09-21.md](core-paired-2026-09-21.md)) found no Core Bypass benefit (median paired delta −0.015 ms, 8/20 pairs favoring bypass, p = 0.50). The 2.289 ms gap above was sequential drift.

## Run

- Duration: 102 seconds, six passes.
- Scene: BedwarsPractice multiplayer lobby, fixed player viewpoint, focused fullscreen window.
- Stack: Minecraft 1.8.9, Legacy Fabric 0.19.3, OptiFabric, OptiFine HD U M5, Java 8.0.502, 4096 MiB heap.
- Resolution/settings: 1920x1080 fullscreen, fast graphics, render distance 8, VSync off, VBO on, 260 FPS setting.
- All measurable optional HUD/modules were disabled for every pass.

| Pass | Mean frame | Median | p95 | Samples |
|---|---:|---:|---:|---:|
| Normal Core | 4.282 ms | 2.424 ms | 4.064 ms | 2,799 |
| Core Bypass | 1.994 ms | 1.877 ms | 2.728 ms | 6,013 |
| Event Dispatch Bypass | 2.397 ms | 2.235 ms | 3.486 ms | 5,000 |
| Render/Entity Hook Bypass | 3.035 ms | 2.918 ms | 3.900 ms | 3,950 |
| OptiFine Overlap Bypass | 3.029 ms | 2.908 ms | 3.890 ms | 3,958 |
| Instrumentation Control | 3.025 ms | 2.875 ms | 3.846 ms | 3,962 |

Raw deltas against Normal Core were +2.289 ms for Core Bypass, +1.886 ms for Event Dispatch Bypass, +1.247 ms for Render/Entity Hook Bypass, and +1.254 ms for the OptiFine-overlap bypass. Core Bypass versus Instrumentation Control yielded -1.031 ms, so this run does not provide a credible numeric profiler-overhead estimate.

## Deep frame boundaries

Normal Core was decomposed without adding nested timings twice:

| Boundary | Mean per frame |
|---|---:|
| `Minecraft.runGameLoop` | 4.2824 ms |
| `Minecraft.runTick` | 1.5832 ms |
| `EntityRenderer.updateCameraAndRender` | 2.2267 ms |
| `EntityRenderer.renderWorld` | 1.9266 ms |
| `RenderGlobal.renderEntities` | 1.2459 ms |
| `RenderGlobal.sortAndRender` / chunk layers | 0.4670 ms |
| `RenderManager.doRenderEntity` aggregate | 0.0000 ms |
| `GuiIngame.renderGameOverlay` | 0.2766 ms |
| Outside tick + camera/render | 0.4725 ms |
| Camera/render remainder | 0.0235 ms |
| Render-world remainder | 0.2138 ms |

The targeted `RenderManager.doRenderEntity` path did not fire in the measured viewpoint, so this run does not characterize a populated per-entity rendering path.

## Directly measured Fathom paths

| Path | Mean per frame | Calls |
|---|---:|---:|
| All Mixin callback bodies | 0.0275 ms | 142.03 callbacks/frame |
| Direct injected dispatch | 0.0022 ms | 1 dispatch/frame, 3 listeners/frame |
| Other optimization hooks | 0.0034 ms | aggregate |
| GL/state wrappers | 0.0000 ms | below timer resolution |
| Active OptiFine-overlap handlers | included above | 2.07 hooks/frame |

The directly timed Fathom work totals about 0.033 ms/frame. It does not explain the 2.289 ms ordered-pass delta, which is why the larger difference is treated as confounded rather than attributed to Fathom.

## Runtime rendering/ticking Mixin inventory

| Target / method | Injection | Cancellable | Steady-state frequency | OptiFine same method |
|---|---|---:|---:|---:|
| `MinecraftClient.runGameLoop` | HEAD + RETURN `@Inject` | No | 2 callbacks/frame | No |
| `MinecraftClient.tick` | HEAD + RETURN timing `@Inject` | No | tick-dependent | No |
| `MinecraftClient.getMaxFramerate` | RETURN `@Inject` | Yes | ~1/frame | No |
| `MinecraftClient.setScreen` | HEAD `@ModifyVariable` | No | scene-dependent | Yes |
| `MinecraftClient.tick` hotbar scroll | INVOKE `@Redirect` | No | input-dependent | No |
| `MinecraftClient.startIntegratedServer/connect` GC calls | INVOKE `@Redirect` x2 | No | 0/frame | No |
| `GameRenderer.getFov` | RETURN `@Inject` | Yes | render-dependent | No |
| `GameRenderer.render/tick` option-field reads | FIELD `@Redirect` x2 | No | render/tick-dependent | `render`: Yes; `tick`: No |
| `GameRenderer.renderHand` | HEAD `@Inject` | Yes | <=1/frame | No |
| `GameRenderer.render/renderWorld` | HEAD + RETURN timing `@Inject` | No | 1/frame | `render`: Yes; `renderWorld`: No |
| `InGameHud.render` | RETURN hook plus HEAD/RETURN timing `@Inject` | No | 1/frame | No |
| `InGameHud.showCrosshair` | RETURN `@Inject` | Yes | <=1/frame | No |
| `TextRenderer.getStringWidth` | cancellable HEAD + RETURN `@Inject` | HEAD only | 127.41 queries/frame | No |
| `TextRenderer.init/setUnicode` | RETURN `@Inject` x2 | No | 0/frame steady-state | `init`: Yes; `setUnicode`: No |
| `ClientPlayerEntity.tickMovement` | HEAD `@Inject` + INVOKE `@Redirect` | No | tick-dependent | No proven overlap |
| `KeyboardInput.tick` | INVOKE `@Redirect` | No | tick-dependent | No proven overlap |
| `KeyBinding.onKeyPressed` | HEAD `@Inject` | No | input-dependent | No proven overlap |
| `GameMenuScreen.init/buttonClicked` | RETURN/HEAD `@Inject` | Button only | 0/frame in world | No |
| `World` light methods | INVOKE `@Redirect` | No | light-query-dependent | No |
| `WorldRenderer.setupTerrain` | INVOKE `@Redirect` | No | skipped with OptiFine | Yes |
| `WorldRenderer.renderEntities/renderLayer` | HEAD + RETURN timing `@Inject` | No | 1 / 4 calls per frame | `renderEntities`: Yes; `renderLayer`: No |
| `ChunkOcclusionDataBuilder.getOpenFaces` | INVOKE `@Redirect` | No | skipped with OptiFine | Yes |
| `ClientChunkProvider` ctor/get/load/unload | RETURN/HEAD `@Inject` + INVOKE `@Redirect` | No | whole Mixin skipped with OptiFine | `getOrGenerateChunk`: Yes |
| `EntityRenderDispatcher.render` | HEAD + RETURN timing `@Inject` | No | 0 in this viewpoint | No |

There are no `@Overwrite` Mixins. The runtime set contains eight rendering/ticking redirect declarations and five cancellable injection points.

## OptiFine interaction evidence

- `GameRenderer.render` is patched by OptiFine. Fathom redirects sensitivity and smooth-camera field reads inside that patched method; this is the active steady-state overlap and its handler can be bypassed without disabling OptiFine.
- `MinecraftClient.setScreen` is patched by OptiFine. Fathom modifies the argument at HEAD before the patched body; it did not fire during the in-world sample.
- OptiFine modifies `WorldRenderer.setupTerrain`, `WorldRenderer.renderEntities`, and `ChunkOcclusionDataBuilder.getOpenFaces`. Fathom skips its production `setupTerrain` and `getOpenFaces` redirects at class load when OptiFine is present. The benchmark-only `renderEntities` timer wraps the patched body.
- OptiFine modifies `ClientChunkProvider.getOrGenerateChunk`; Fathom skips that entire production Mixin when OptiFine is present.
- OptiFine modifies `TextRenderer.init`; Fathom's cache invalidation runs at RETURN during resource initialization/reload only. `getStringWidth` itself is bytecode-identical.
- No Fathom overwrite, forced fallback, disabled OptiFine optimization, or duplicate vanilla render invocation was found.

The overlap pass measured 3.029 ms/frame, only 0.006 ms from Render/Entity Bypass and 0.004 ms from Instrumentation Control. Its 1.254 ms delta from the much earlier Normal pass cannot be assigned to the two active Fathom overlap handlers.

## Duplicate execution check

No once-per-frame boundary exceeded 1.05 calls/frame. Aggregate counters recorded 1.00 game loop, 1.00 camera render, 1.00 world render, 4.00 chunk-layer calls, and 0.00 Fathom HUD passes per frame. No double registration, nested event dispatch, duplicate world render, or duplicate entity render was observed.

## Validation and artifacts

- Full Gradle `build`: successful.
- Benchmark/core tests: 44 passed; all other project test groups passed.
- Runtime report: `C:\Users\jb\AppData\Local\Fathom\data\profiles\optimized\minecraft\logs\fathom-core-benchmark-2026-09-21-17-27-13.txt`.
- The profile's temporary benchmark and auto-connect flags were removed after the run. The benchmark code is property-gated and inert in an ordinary launch.

