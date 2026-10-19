# Lobby NPCs and turning the camera — 2026-10

Status: **measured 2026-10-17** (see Results). The root causes were first read
from the 1.8.9 classes (yarn-mapped, and OptiFine M5's patched `RenderGlobal`
from a Forge class dump) and from the earlier studies cited; the Results
section is what the new scenes then showed.

## What was reported

On a real server lobby (Minemen Club): the Optimized profile (Legacy Fabric,
Fathom, OptiFine M5 with Render Regions) reaches about 1,000–1,200 fps facing
open areas and falls to about 500 facing a lobby NPC; Forge falls further;
turning quickly from an empty view to the NPCs drops frames.

That is two different things, and they are kept apart throughout:

- **Sustained cost**: what the busy view costs every frame while you hold it.
- **Transition cost**: what changes on the frames where the camera moves, and on
  the first look at something.

## Root causes

### 1. Sustained: it is the crowd and the labels, not the NPC

A lobby NPC is an ordinary player entity: lobby plugins spawn it with the
named-entity-spawn packet, hide its nametag with a team, and stack named,
invisible armor stands above it for the hologram text. The client draws it
exactly as it draws a player.

Measured on a local server (`multiplayer-2026-10-13.md`, OptiFine M5): a player
costs 26–28 µs to draw (body 10, held item 5, label 5–7), 54–62 µs armoured with
enchantments, and each hologram line about 5 µs. One NPC with a three-line
hologram is therefore about 0.04 ms: it cannot by itself turn a 1.0 ms frame
into a 2.0 ms one. What surrounds an NPC in a lobby can: the 40-player `lobby`
scene ran at 527 fps and the 16-NPC `holograms` scene at 592 fps, matching the
report.

The remaining sustained cost is the one `design-player-batching.md` describes:
players are drawn part by part, a few hundred GL calls each, and the driver,
not the GPU, is the limit. That is a renderer project, not something a cache
fixes.

### 2. Transition: the visibility search reruns, and allocates, on every moving frame (without OptiFine)

`WorldRenderer.setupTerrain` sets `needsTerrainUpdate` whenever the camera's
position, pitch or yaw differs at all from the last frame, and then rebuilds the
visible-section list from scratch. In vanilla that rebuild allocates, for every
visible section, a `ChunkInfo` with a fresh `EnumSet` inside it and a
`LinkedList` node for the queue, plus a new list and queue each time. A held
camera skips all of it, which is why the steady-view study found
`setupTerrain` at 1% of a frame (`view-direction-2026-10-02.md`): it was never
measured while turning.

At render distance 12 with a few thousand visible sections that is an estimated
0.2–0.3 MB of garbage per frame while the camera moves, every frame of a turn
(estimate from object sizes; to be measured).

**OptiFine M5 already avoids this**: its `setupTerrain` keeps one render-info
object per chunk (`RenderChunk.getRenderInfo()`) and clears and reuses its lists
and queue. So the cost is specific to profiles **without** OptiFine — the
reported Forge profiles, and Legacy Fabric without OptiFine.

Not built yet: reusing the per-section objects needs writing `final` fields of a
package-private inner class from a Mixin, which this build has no access
widener for. Built only if the `turn` scene shows the cost (design below).

### 3. Transition: the first look at a label decodes glyph pages on the render thread

The font draws anything outside its ASCII sheet from a page of the unicode
font and binds the page through `TextureManager.bindTexture`
(`TextRenderer.bindPageTexture` → `getFontPage`, "textures/font/unicode_page_%02x.png").
The first bind of a page has no texture yet, so the manager creates and loads
one there and then: a PNG decoded on the render thread, inside the frame. Lobby
labels are made of exactly those characters — ★ ❤ ✦ ➜ ● ✔ — so the first time
a hologram comes into view the frame waits for one decode per new page. Later
looks pay nothing. **Fixed** (below), pending measurement.

### 4. Forge only: the armour lookup cache never ran

Forge draws armour through its own `LayerArmorBase.getArmorResource(Entity,
ItemStack, int, String)` (two `String.format`s and a call to the item per piece
per frame); Fathom's cache was on the vanilla method Forge no longer calls.
**Ported** (`ForgeArmorTextureMixin`); see `docs/forge.md`.

### Considered and not a cause here

| Suspect | Finding |
|---|---|
| NPC skins | Downloaded on a worker thread; the upload when it arrives is one 64×64 texture. Not a stall. |
| Chunk rebuilds when turning | Only sections marked dirty are rebuilt, and only the camera's own section synchronously. A settled lobby has none; the first turn after joining builds sections never seen, once. |
| Player skull heads in singleplayer | `TileEntitySkull.updateGameprofile` can block on a profile lookup, but only with an integrated server; on a multiplayer server it returns at once. |
| Labels | Already replayed from recordings by `render.nametag.replay`, with names cached by `render.entity.nametag-name-cache`. |

## What was built

| Change | Where | Effect on pixels |
|---|---|---|
| **NPC scenes** in the view benchmark: `npc` (one NPC under a 3-line hologram), `npcs` (six), `crowd` (six NPCs and 32 players), `turn` (the crowd, the camera turning 180° to the sky behind and back, twice a second) | `ViewBenchmark` | — |
| **First-look tracking**: every scene's frames from its props being placed to the end of its first window, worst frame and frames over 16/33 ms; warm-up is the session's first look, each round a repeat | `ViewBenchmark` ("Look at …" lines in the report) | — |
| **Spike table**: every arm's median, p99, p99.9, max and frames over 2× median, 8, 16 and 33 ms per thousand, with GC | `ViewBucket`, `ViewReport` (SPIKES) | — |
| **Engagement counters**: each optimization's fast path counted per frame, every arm, so an optimization that applied but never ran reads zero | `ViewCounter`, `ViewProbe.engaged`, each hook; `ViewReport` (ENGAGEMENT) | — |
| `render.font.glyph-page-preload`: the seven symbol pages lobbies use (punctuation, arrows, maths, shapes, symbols, dingbats, extra shapes) loaded with the font | `GlyphPages`, hooked from `TextRendererWidthMixin` | None: the same textures, earlier. ~1.75 MB of video memory |
| Armour lookup cache on Forge | `ForgeArmorTextureMixin`, `ForgeArmorTextures` | None: the object Forge's own map holds |

The glyph preload has no evidence yet, so it is off in Balanced and on only in
Maximum FPS or when switched on; it joins Balanced only after a measured
first-look improvement.

## Results (2026-10-17)

Singleplayer `mpbench`, 1600x900, render distance 12, Fancy, 3 interleaved
rounds per arm, 0 unfocused frames; reports in `benchmarks/reports/fathom-view-
2026-10-17-*`. Median ms per frame, switches on.

| Scene | Forge | Forge + OptiFine | Fabric | Fabric + OptiFine |
|---|---|---|---|---|
| Horizon | 2.20 | 0.62 | 1.82 | 0.58 |
| One NPC under a hologram | 2.34 | 0.72 | 1.93 | 0.66 |
| Six NPCs | 2.62 | 0.87 | 2.20 | 0.80 |
| Crowd (six NPCs, 32 players) | 3.32 | 1.47 | 2.75 | 1.26 |
| Turning, crowd to sky and back | 2.45 | 0.43 | 1.90 | 0.42 |

**Sustained.** One NPC with its hologram adds 0.08-0.14 ms over the empty
horizon; the crowd adds 0.7-1.1 ms. On Forge without OptiFine the crowd's 38
players cost 0.70 ms a frame and its 50 labels 0.44, which is the cost `design-player-batching.md` describes. Facing an NPC is
expensive because of what is around it, as predicted.

**Turning (cause 2), Forge without OptiFine:** the visibility search costs
**0.38-0.50 ms a frame while turning against 0.04 ms held**, client
allocation rises from 36 to 104-141 MB/s, and two collections (18 ms) fell
inside the turning windows; frames over 8 ms went from 0 to 1.2 per thousand.
With OptiFine the turn is cheap (median 0.42-0.43 ms) and its tail is the
sky-facing half of the turn being faster, not stutter: max 5.7-9.3 ms, nothing
over 16 ms. **Confirmed; the fix is not built yet** (below).

**First look (cause 3), one launch pair, Optimized setup:** the first look at
the NPC scene, glyph preload off: worst frame **51.8 ms**, two frames over 16
ms. Preload on: worst **9.5 ms**, none over 16 ms. One pair, and the off run
also had a collection in that window, so this is strong but not settled; the
preload stays off in Balanced until a repeat agrees. The preload itself took
47.9 ms at font load, on the loading screen.

**Repeat looks** (each round after warm-up): no frame over 16 ms in any scene
on any setup. What remains after a first look is sustained cost, not stutter.

## How to measure it

Singleplayer, the `mpbench` world, windowed, nothing else running:

```
-Dfathom.benchmark=view -Dfathom.view.world=mpbench -Dfathom.view.pos=0.5,101,-4.5 -Dfathom.view.yaw=0
-Dfathom.view.scenes=sky,horizon,npc,npcs,crowd,turn -Dfathom.view.rounds=4
-Dfathom.view.arms=normal,fathom-off
```

On each of: Forge without OptiFine, Forge with OptiFine, Legacy Fabric with
OptiFine (the Optimized profile). Read:

- **SCENES** and **SPIKES**: `npc`/`npcs`/`crowd` medians are the sustained
  cost; `turn` against `crowd` is what turning adds, and its spike columns are
  the stutter. `terrain: visibility (setupTerrain)` in `turn` is cause 2 directly.
- **GC n/ms** and allocation MB/s in `turn`: cause 2's garbage.
- **Look at … (warm-up, first)** against **(round n, repeat)**: cause 3. Run once
  with the glyph preload on and once off (it acts at load, so it is a launch
  pair, not an arm).
- **ENGAGEMENT**: every optimization that should run in a scene is non-zero in
  `normal` and zero in `fathom-off`.

## Next: reusing the visibility search's objects

Measured above: 0.38-0.50 ms a turning frame and most of the turning
allocation, without OptiFine. The plan: keep one `ChunkInfo` per `BuiltChunk` and reset it instead of
constructing one (each section enters the search at most once per rebuild, so
one per section is enough), reuse the visible list (it is only read on the
client thread within the frame), and clear rather than allocate the facing set.
This is what OptiFine does. It needs an access widener for `ChunkInfo` and
for the synthetic `WorldRenderer$1` its constructor is called through (which
Forge's patched `RenderGlobal` may number differently, so the Forge build needs
its own check), a Mixin skipped with OptiFine like the other `setupTerrain`
hooks, and a check that the visible list is identical, section for section and
in order, with and without it.
