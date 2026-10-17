# Fathom performance layer, phase 1 — 2026-09-22

Entity and tile-entity distance limits, a nametag fast path, and a cached HUD
layer, measured against themselves in one process on a populated server.

## Result

**Phase 1 reduces frame time in the populated-server scene and now passes its
GL-state correctness gate.** The release-verification run measured a median
paired saving of **0.2392 ms/frame**. All 20 pairs favoured the enabled arm.
Mean frame time moved from **2.0645 ms to 1.8089 ms**: **0.2556 ms/frame, or
12.4%**, in this scene.

That is a real scene-specific improvement, not a claim of a universal 12% FPS
gain. The lobby contained about 99 visible entities with the layer off,
including 52 distant armor stands that Fathom could reject, and the fast path
drew about 36.6 nametags per enabled frame. A different
server population will produce a different result.

The attributed validator sampled 33 exact Phase 1 boundaries and found **zero
Fathom-attributable mismatches**. It separately observed 20 later state changes
made by the surrounding vanilla/OptiFine render methods. The earlier reported
blend mismatch was one of those: the old validator sampled the whole HUD method,
not the cache boundary. The narrower validator also found one real nametag
texture-binding leak; the final build restores the caller's cached binding and
the verification run is clean.

### Whole-layer A/B result

The final verification used 20 counterbalanced pairs in one client process. Each arm
settled for 0.75 seconds and was then measured for 2.5 seconds. A was the
performance layer disabled and B was the complete layer enabled.

| Run | A mean | B mean | A median | B median | A p95 | B p95 | A p99 | B p99 | Median paired saving |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Release verification | 2.0645 | 1.8089 | 1.9003 | 1.6494 | 2.9277 | 2.5958 | 3.5345 | 3.1877 | **0.2392 ms** |

All values are milliseconds per frame. B won 20 of 20 pairs; the exact
two-sided sign test was below 0.00001. Order did not explain the result: the
A−B mean was +0.2345 ms for AB and +0.2768 ms for BA.

The distribution changed as follows:

| Statistic | Layer off | Layer on | Saved | Change |
|---|---:|---:|---:|---:|
| Mean | 2.0645 ms | 1.8089 ms | 0.2556 ms | 12.4% |
| Median | 1.9003 ms | 1.6494 ms | 0.2509 ms | 13.2% |
| p95 | 2.9277 ms | 2.5958 ms | 0.3319 ms | 11.3% |
| p99 | 3.5345 ms | 3.1877 ms | 0.3468 ms | 9.8% |

As supplemental context only, the reciprocal of the mean frame time is about
484 versus 553 FPS. That arithmetic describes this uncapped benchmark scene; it
is not a prediction for a capped client or a different server.

### Feature attribution

The isolated runs are more informative than adding their numbers together.
They had different live server populations, and the features interact: culling
an armor stand also removes work that its nametag would otherwise perform.

| Feature | Median paired saving | What changed | Finding |
|---|---:|---|---|
| Nametag fast path | **0.1550 ms/frame** | 33.19 labels/frame used the fast path; `getStringWidth` fell from 156.78 to 57.02 calls/frame (−63.6%); only 0.002 lists/frame were recorded after warm-up | The strongest isolated result: B won 18/20 pairs, sign-test p = 0.00040. |
| Entity limits | 0.0597 ms/frame | 52 armor stands/frame were rejected; renders fell from 85.24 to 33.31/frame; rejection cost was 0.010 ms/frame | Useful in this lobby, but not statistically consistent on its own: B won 14/20, p = 0.11532. Keep the claim scene-specific and the limits configurable. |
| HUD cache | 0.0362 ms/frame | 94.9% of frames replayed the cached layer and 5.1% recorded it; measured HUD work fell from 0.090 to 0.082 ms/frame | Below a strong attribution threshold: B won 13/20, p = 0.26318. Do not advertise a standalone gain. |
| Tile-entity limits | Not isolated | The final whole-layer run considered 68 and culled 3 tile entities/frame | Engagement is proven, but no standalone frame-time claim is supported by this session. |

The culling test itself remained cheap: **0.009–0.012 ms/frame** in the
whole-layer and entity-only runs, versus the 52 entity renderer calls it avoided
in this scene.

### Correctness and compatibility findings

| Check | Finding |
|---|---|
| Nearby players | Pass. Zero players were culled inside the 48-block safety floor. |
| Duplicate entity rendering | Pass. Render counts never exceeded considered-entity counts. |
| Nametag output | Pass. Eight strings covering colours, styles, reset codes, a team-style prefix and non-ASCII text were pixel-identical. |
| Nametag invalidation | Pass. A font change cleared the recorded-label cache. |
| OptiFine presence and settings | Pass for the tested configuration. OptiFine M5 remained active and the VBO/Fast Render/Fast Math/Smooth FPS/Smooth World/Lazy Chunks/Render Regions fingerprint did not change between arms. |
| GL state | **Pass.** The final run sampled 33 exact hook boundaries and found 0 Fathom-attributable mismatches. The validator reported 20 later-only vanilla/OptiFine changes separately. Both nametag record/replay and HUD record paths restored their caller state. |
| Resource packs and shaders | Not exercised in the multiplayer A/B runs: no resource pack or shader pack was active. Font invalidation passed, and the shader fast-path bypass exists, but those are not substitutes for an in-scene compatibility run. |

The OptiFine configuration actually exercised VBOs, Lazy Chunk Loading and
custom entity models. Fast Render, Fast Math, Render Regions and shader packs
were off, so “fingerprint stable” for those options means Fathom did not change
them, not that every combination has been empirically validated.

### Benchmark disposition

The state audit investigation used a diagnostic whole-layer run to localize the
blocker before the release-verification run above. The original audit captured
state before and after the complete `InGameHud.render` callback, so ordinary
HUD work performed after Fathom's cache was incorrectly attributed to the
cache. The replacement captures before Fathom, immediately after Fathom and at
the surrounding method's return, and records the active feature and path. That
proved the reported HUD blend change was later-only, while exposing a real
nametag texture change at the immediate boundary. The production fix restores
the caller's texture binding from `GlStateManager`'s own cache and explicitly
resynchronizes its alpha-test cache after list record/replay. It adds no
unconditional `glGet*` call to the frame or label hot paths.

Two preliminary reports are excluded from the result above:

- The first kept only 1 of 24 pairs because the client lost focus and fell onto
  the 30 FPS unfocused cap.
- The next kept 20 pairs, but every performance counter in arm B was zero. It
  measured two disengaged arms and correctly produced a noise-sized 0.0080
  ms/frame difference.

Those failures led to explicit focus recovery and an engagement assertion in
the harness. A subsequent engaged build produced 20/20 valid pairs and a
0.2878 ms/frame median saving. The release-verification run above is the
reported evidence for the final state-correct build.

### Decision

The performance objective and the Phase 1 correctness gate are met for the
measured CPU-heavy lobby. The complete layer saved **0.2392 ms/frame** by the
paired median, every pair favoured B, and all 33 attributed GL boundaries were
clean. The Phase 1 layer is **safe to keep enabled** for release under the tested
OptiFine M5 configuration. Nametags remain the only isolated feature with
evidence strong enough for a standalone performance claim. Entity limits are a
configurable, workload-dependent optimization, while HUD and tile-entity gains
should still be described as unproven individually.

## Why these four, and not something else

The frame this is trying to shorten was profiled first, because the previous
milestone spent two runs proving that the thing everyone suspected — Fathom's
own integration — costs nothing measurable.

**Where the render thread's time goes.** A 80-second Java Flight Recorder
recording of the lobby caught 295 native samples on the render thread. 89.5% of
them were inside `SwapBuffers`, waiting for the driver; the rest were spread
over `glDrawArrays`, `glCallList`, `glBegin` and texture binds. Java execution
samples on that thread were too sparse to rank (36 in the whole recording).
That is the signature of a thread that is not computing much and is instead
feeding a driver more commands than it can retire — an NVIDIA driver with
threaded optimization drains its queue at the swap.

So the target is the number of GL commands issued per frame, not the arithmetic
around them:

| Cost in the lobby | Command shape |
|---|---|
| ~97 entities drawn per frame | model setup, matrix, texture bind and geometry per entity |
| ~36 nametags per frame | `glBegin`/`glEnd` and eight coordinate calls **per glyph**, twice per label |
| ~140 `getStringWidth` calls per frame | three per nametag, plus scoreboard and tab list |
| ~68 tile entities considered per frame | a renderer call each |

Vanilla's own frame timings agreed: `RenderGlobal.renderEntities` was the
largest measured boundary at about 1.25 ms/frame, against 0.47 ms for the chunk
layers and 0.28 ms for the vanilla HUD.

## What each feature does

### Entity distance limits

1.8.9 already culls entities by `averageSideLength * 64 * weight` blocks and by
the view frustum, and OptiFine keeps both. Fathom's limit is applied *after*
that answer, in `EntityRenderDispatcher.shouldRender`, and only when vanilla
said yes — so it can only ever draw less, never more, and it adds nothing to an
entity vanilla already rejected.

The entities vanilla lets through are the ones worth limiting: other players
(weight 10, ~640 blocks), arrows (320), armor stands (~250), item frames
(1024), and large mobs by size. Dropped items are 16 blocks in vanilla, so the
item row cannot make them vanish any sooner than they already do — its floor is
vanilla's own range, and the report says so rather than claiming a saving.

Limits are per kind, never one universal distance:

| Kind | Conservative | Balanced (default) | Aggressive | Floor |
|---|---:|---:|---:|---:|
| Players | 128 | 96 | 64 | 48 |
| Projectiles, TNT, falling blocks | 128 | 96 | 64 | 64 |
| Hostile mobs | 96 | 64 | 48 | 32 |
| Passive mobs | 64 | 48 | 32 | 24 |
| Armor stands | 96 | 64 | 48 | 32 |
| Other entities | 64 | 48 | 32 | 24 |
| Dropped items | 32 | 24 | 16 | 16 |

Never limited at any setting: the camera entity and what it rides, bosses,
lightning, end crystals, and anything showing a nametag inside vanilla's
64-block nametag range. The floors are above a bow's carry, so nothing you can
fight disappears at a distance you could fight at; a test asserts that, and the
benchmark counts players culled inside the floor and reports the count.

One rejection is not about distance: an armor stand that is invisible, holds
and wears nothing, has no name and is not burning. Its renderer runs its whole
setup and draws no pixel, so skipping it is output-identical by construction.
Spectators see invisible entities, so the rule does not apply to them.

### Nametag fast path

`renderLabelIfPresent` measures the string three times and draws it twice, and
each draw walks the string in Java and sends every glyph to the driver in
immediate mode. The fast path keeps vanilla's exact matrix, depth, blend and
background-quad sequence and replaces only the two glyph passes: the first time
a string is seen, each pass is drawn by the game's own `TextRenderer` and
recorded into a display list as it goes; after that the pass is one
`glCallList`. The width is measured once and kept with the lists.

Because the recording *is* the game's own drawing, team colours, formatting
codes, resource-pack fonts and OptiFine's custom text colours come out exactly
as the game drew them. That claim is checked by pixels, not by argument: the
benchmark draws eight sample strings — colour codes, bold, underline,
strikethrough, a team-style prefix, non-ASCII, a mid-string reset — into an
offscreen buffer both ways and compares the bytes.

`GlStateManager` caches what it believes is bound and skips calls it thinks
redundant, which a replayed list changes behind its back. So the texture and
colour the list leaves behind are read back once when it is recorded, and told
to `GlStateManager` after every replay; alpha test is also explicitly
resynchronized. The caller's texture binding is read from `GlStateManager`'s
cache before the fast path and restored afterward, without a driver query. The
path steps aside for obfuscated text (`§k` is random every frame), while an
OptiFine shader pack is loaded, when the cache shape is unavailable, and after
the first dozen new strings in a frame.

### Tile entity distance limits

Applied to the value the dispatcher already compares the distance against —
`getSquaredRenderDistance()` — so vanilla still does the comparison, once, and
the limit only makes the answer "not this frame" sooner. Nothing about the tile
entity changes: it exists, ticks and keeps its inventory; only its renderer is
skipped, and only while it is far away. Beacons, end portals and moving pistons
are never limited. Conservative is the default, and leaves signs at vanilla's
own range.

### HUD caching

Fathom's widgets are split into realtime (keystrokes, CPS, sprint/sneak status,
crosshair, and anything registered through the public API) and slow-changing
(FPS, coordinates, clock, ping, armour, potions, pack display). The
slow-changing ones are recorded into a display list 20 times a second and
replayed on the frames between; static geometry — plates, borders, icons — rides
in the recording rather than being issued from Java every frame. Input feedback
is never cached, so nothing a key press drives is delayed. A change of layout,
widget set, visibility, window size or any setting records again immediately.

A framebuffer would have been the obvious approach and is the wrong one here:
the widgets' blend functions do not write coverage into alpha, so a translucent
plate composited from a texture does not match the plate drawn directly. A
display list replays the same commands into the same framebuffer, so a replayed
frame is the frame the widgets would have drawn, at any GUI scale.

## OptiFine

Fathom's layer complements OptiFine and duplicates none of it. The only
OptiFine call in the layer is `Config.isShaders()`, read once per frame; nothing
writes an OptiFine option.

| OptiFine feature | Interaction |
|---|---|
| VBOs, Render Regions, Fast Render, Fast Math, Smooth FPS, Lazy Chunk Loading | None. The benchmark fingerprints all seven across both arms and reports them unchanged. |
| Entity rendering | Fathom hooks the `shouldRender` answer OptiFine's own `renderEntities` loop asks for. OptiFine's loop, batching and culling are untouched. |
| Sign text distance, item-frame item distance | OptiFine already has both. Not duplicated: the tile limit is coarser and signs default to vanilla's range. |
| Custom entity models, custom colours, HD fonts | Untouched. The nametag path records the game's own drawing, whatever font and colours are loaded. |
| Shader packs | The nametag fast path steps aside entirely while one is loaded. |
| Resource packs | A font change drops every recorded list; the benchmark checks that hook. |

## Settings

A Performance page section, drawn from the module's own settings, so what the
page shows is what the file stores and what a Fathom Profile carries:

- **Entity rendering** — optimization preset (Off / Conservative / Balanced /
  Aggressive / Custom), then player, mob, item and misc distances.
- **Nametags** — fast nametags.
- **Tile entities** — optimization preset and distance.
- **HUD** — HUD caching.
- **Advanced** — show performance statistics.

Choosing a preset writes its distances into the sliders; moving a slider
switches the preset to Custom. Switching the module off is the master switch:
every hook reads "do nothing" from one snapshot. The sliders cannot be dragged
under a kind's floor.

## Validation

`-Dfathom.benchmark=perf-paired` runs the same interleaved paired harness the
core investigation used: one process, one lobby, 20 counterbalanced pairs of
0.75 s settle plus 2.5 s measured epochs, A = layer off, B = layer on, with the
HUD widgets on in both. `-Dfathom.perf.only=<feature>` restricts arm B to one
feature so a saving can be attributed.

Three checks exist because the first version of this benchmark got them wrong
and reported a confident nothing:

- **Engagement.** The report states whether arm B ran the layer at all. An
  early run disabled every module at each epoch, so both arms were identical;
  the counters were all zero and the verdict was "no difference".
- **Rendered entities are the result, not a confound.** The paired harness
  invalidates a pair when the scene changes between arms, and it counted drawn
  entities as a scene signal. Drawing fewer entities is the point here, so that
  one check is skipped in this mode and the scene is judged by what the server
  sent: loaded entities, the player list, chunks and particles.
- **Focus.** An unattended run starts behind the terminal that launched it, and
  the unfocused frame cap pins every frame at 33 ms. The benchmark now takes the
  game's own focus back the way clicking on the window does.
