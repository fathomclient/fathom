# Crowded lobbies on Forge — 2026-10-20

Why a Minemen lobby runs at ~500 fps against 700–800 elsewhere, measured on the
setup it is played on (Forge + OptiFine M5 + Fathom), and the two output-identical
changes that came out of it. Builds on `multiplayer-2026-10-13.md` and
`design-player-batching.md`.

## 1. What changed in the harness

| Change | Why |
|---|---|
| Scene `minemen` (`mpbench.py`, `ViewBenchmark`) | A 1.8 PvP practice lobby: 64 players walking 4–40 blocks ahead with rank-coloured team names and lobby items (compass, book, sword, skull, paper…), 6 NPCs with hidden nametags under 3-line holograms, 80 online, a 12-line sidebar updated once a second, chat |
| `run_client.py --forge`, `compare_builds.py --forge` | The user plays on Forge, not on the Fabric `optimized` profile. The client is launched as the Forge profile `fathom-1-8-9-2` launches it (LaunchWrapper, FML + Mixin tweakers, OptiFine as a Forge coremod, the remapped `fathom-forge` jar first on the classpath) with that profile's video, OptiFine and Fathom settings and 2.3 GB heap. Classpath: `run-mp/forge/forge-classpath.txt`, from the launcher's last Forge launch |
| **"Players loaded but not drawn" fixed** | OptiFine draws entities only from chunks that held entities at the last visibility rebuild (`renderInfosEntities`, rebuilt when the camera moves or the renderer is marked dirty). A held camera never rebuilds, so bots arriving in, or walking into, a chunk that had none were not drawn: whole runs drew 0 of 89 players. `ViewBenchmark` now calls `scheduleTerrainUpdate()` once per client tick in multiplayer scenes. Reports from before this (including the `tab-big`/`hud-heavy` runs of 2026-10-14 that "drew none of their players") were affected |
| Per-window console line with entities drawn and unfocused frames; `tools/mp-bench/paired_windows.py` | Pairs an arm with the normal arm of the same round only when both windows drew ≥ 90% of what was loaded and neither lost focus |

## 2. Where a crowded lobby frame goes

`minemen`, normal arm, players fully drawn. ms per frame.

| | Forge (user settings: RD 10, Render Regions off, shadows off) | Fabric (`optimized`: RD 8) |
|---|---:|---:|
| Frame mean / median | 3.62 / 3.56 (276 fps) | 2.31 / 2.55 |
| Player entity draws | 2.10 (31 µs per player) | 1.43 (21 µs) |
| — body model + skin | 0.68 (8.3 µs) | 0.54 (8.0 µs) |
| — held item | 0.73 (**8.9 µs**) | 0.28 (4.1 µs) |
| — nametag decision + label | 0.53 | 0.44 |
| Terrain | 0.56 | 0.29 |
| HUD | 0.22 | 0.17 |

Flight recordings of player drawing (sampled native frames):

- **Forge**: held item 37% (mostly `glDrawArrays` sending the item model's few hundred
  quads, plus building them quad by quad), `glCallList` for the body's model parts 35%,
  nametags 22%.
- **Fabric**: `glCallList` 50%, held-item `glDrawArrays` 18%.

The held item is the largest single Forge-specific cost; Forge's item path rebuilds the
model through its own quad pipeline every frame.

## 3. What was built

### Held item vertex reuse — `render.entity.item-model-lists` (default on)

Inside an entity's held-item layer, the first draw of an item model under a key copies the
vertex data the tessellator holds at its draw. Later draws begin the tessellator with the
same mode and format, put the data back in one copy and draw through the game's own
upload path (vanilla's, OptiFine's or Forge's), so the driver receives the same bytes and
the same calls. Transforms, textures, the glint and OptiFine's custom item choice stay
live.

The key is the model, the colour asked for, and — for tinted quads, which every generated
item model has (layer 0, usually white) — the stack's tint computed as the renderer
computes it (item colour, then OptiFine custom colours). Steps aside for models with two
tint indices or OptiFine emissive quads, with shaders, multitexture, quads-to-triangles,
smart animations, and in anaglyph. A model reload releases everything.

**Two GPU-side versions were built first and failed the gate**: a display list (lit pixels
one level apart in a third of comparisons) and a vertex buffer holding byte-identical data
(one or two levels apart in a quarter). The driver converts client-memory vertex data
differently from buffer-resident data. That is also the most likely cause of the
item-frame model list failure on 2026-10-14. A live-vs-live control through the same check
gave 0 of 579 differing, so the check itself is deterministic.

### Model box batching — `render.entity.model-box-batching` (default on)

Each model part is compiled into a display list in which every face of every box is its
own draw (six per box, 72 for a player body; OptiFine M5 compiles the same way). Each box
now compiles as one `GL_QUADS` draw of the same 24 vertices in the same order. Only list
contents change; parts compiled under the other setting recompile once when the switch
changes. This is the cheap half of `design-player-batching.md`'s step 2: it cuts the work
inside each `glCallList`, not the number of calls, which is where most of the body's cost
is.

### Correctness

`-Dfathom.verify.caches=true`: one replay in 32 (items) / one part call in 64 (boxes) is
drawn both ways into an offscreen framebuffer under the live matrices and state and the
images compared byte for byte; items also compare the live draw's vertex data with the
kept copy.

| | Forge | Fabric |
|---|---|---|
| Held items | 1,277 compared (652 with pixels drawn), **0 differing**; vertex data 0 differing | 243 (123 drawn), **0 differing**; data 0 differing |
| Model boxes | 1,940 compared (835 drawn), **0 differing** | 1,990 (858 drawn), **0 differing** |

## 4. Measured

Paired, interleaved, 6 rounds × 4 s windows per arm, quiet machine (CPU idle 1–5%, no other
game running), all windows focused, only rounds where both windows drew the scene fully.
Run `mmf-ab3` (Forge) and `mm-ab3` (Fabric).

| Scene | Forge: item reuse | Forge: both | Fabric: item reuse | Fabric: both |
|---|---:|---:|---:|---:|
| `minemen` (64 players) | −0.321 ms (−8.4%), 5/5 | **−0.414 ms (−10.8%), 5/5** | −0.067, 5/5 | **−0.180 (−6.0%), 5/5** |
| `lobby` (40 players) | −0.150 (−6.7%), 6/6 | **−0.168 (−7.5%), 6/6** | −0.018, 6/6 | **−0.053 (−3.1%), 6/6** |
| `combat` (20 armoured) | −0.183 (−9.0%), 6/6 | **−0.225 (−11.0%), 6/6** | −0.048, 5/6 | **−0.102 (−6.3%), 6/6** |
| `mp-empty` | ±0.003 | ±0.001 | ±0.002 | ±0.000 |

Box batching on top of item reuse, paired (Forge): −0.100 ms (5/6) in `minemen`, −0.037
(6/6) in `lobby`, −0.054 (5/6) in `combat`. A first Forge run (`mmf-ab2`, 6 rounds, some
windows unfocused) agreed: both −0.453 ms in `minemen`, −0.159 in `lobby`.

Per player on Forge: held item 8.9 → 5.2 µs (lobby items), 16.6 → 8.5 µs (enchanted
swords); body model 8.3 → 7.4 µs. `minemen` p95 5.00 → 4.44 ms, p99 5.80 → 5.15 ms.
Allocation per second rose a few percent with the frame rate; nothing new is allocated per
frame once a model is kept.

In fps on the Forge `minemen` scene: ~276 → ~308 mean. The real Minemen lobby has fewer
players in view than this scene, so the absolute gain there is smaller; per player it is
the same ~4.6 µs.

## 5. Also found, not changed

- **Forge vs Fabric**: in the same crowd Forge spends ~10 µs more per player, most of it in
  the held item (now halved) and the nametag decision (3.7 µs more: OptiFine posts Forge's
  `RenderLivingEvent.Specials` events through reflection per entity).
- **The user's Forge settings**: OptiFine Render Regions is off on the Forge profile (on in
  `optimized`); terrain costs 0.56 ms there against 0.29. Worth turning on.
- The multi-millisecond Fathom HUD spikes seen on Forge in some runs were the HUD-replay
  verification (a full-frame readback every 16th replay), not play: clean runs show the
  live widgets at 0.04 ms.
- OptiFine's held-camera entity list behaviour (section 1) may also apply in play to a
  player standing perfectly still while others walk into empty chunks. Not tested.
- The launcher's diagnostic "OptiFine and OptiFabric disagree" warning fires on every
  Forge launch, where OptiFabric is not needed.

## 6. Next

1. Nametag decision on Forge (3.7 µs per entity extra): the reflective event posts.
2. Fewer `glCallList`s per player (one per model part today): the body remains 7–9 µs.
3. Dropped items and item frames through the same vertex reuse, gated the same way.

## 7. Reproducing

```
python tools/mp-bench/compare_builds.py --forge --jar cur=minecraft/fathom-optimizations/build/libs/fathom-forge-0.3.0-hud.jar \
    --order cur --scenes mp-empty,minemen,lobby,combat \
    --arms normal,off:render.entity.item-model-lists+render.entity.model-box-batching --rounds 6 --seconds 4 --bots 80 --tag forge-ab
python tools/mp-bench/paired_windows.py minecraft/fathom-optimizations/run-mp/logs/fathom-mp-forge-ab-cur-1-console.log
```

Add `--extra fathom.verify.caches=true` for the correctness gate (timings meaningless in
that mode). Reports and console logs land in `minecraft/fathom-optimizations/run-mp/logs/`.
