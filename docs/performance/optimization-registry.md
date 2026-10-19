# Optimization registry

Every optimization Fathom makes or plans to make, and every JVM question it
intends to answer.

**There is one registry: `OptimizationRegistry` in `minecraft/fathom-optimizations`.**
`gradle exportRegistry` writes it to `assets/optimizations/registry.json`; the
launcher compiles that file in, and a core test (`RegistryTests`) fails when the
checked-in copy is stale. §1-§3 below are generated from the same file by
`python tools/gen-registry-doc.py`. Do not edit between the markers.

<!-- generated:start -->

## 1. Where things stand

**23** entries: **22** implemented and hooked into the game, **1** planned. **19** carry a measurement, **15** of them taken in a running game.

| Status | Count |
|---|---|
| Runs in game | 22 |
| In the module, not yet seen in game | 0 |
| Core written, not hooked up | 1 |
| Planned | 0 |

## 2. The register

| ID | Name | Group | Class | Status | Control | Evidence | Stood down by |
|---|---|---|---|---|---|---|---|
| `render.font.metrics-cache` | Font metrics caching | Rendering | Safe | Runs in game | Launcher switch | isolated (`bench-2026-08-26-text-metrics`) | OptiFine |
| `render.font.format-code-case` | Colour codes without copying | Rendering | Safe | Runs in game | Launcher switch | **none** | — |
| `render.font.glyph-page-preload` | Preload symbol glyphs | Rendering | Safe | Runs in game | Launcher switch | **none** | — |
| `render.nametag.replay` | Fast nametags | Entities | Safe | Runs in game | Set in game (Hub) | in-game (`performance-layer-2026-09-22`) | — |
| `render.entity.distance-limit` | Entity draw distance | Entities | Optional | Runs in game | Set in game (Hub) | in-game (`performance-layer-2026-09-22`) | — |
| `render.tile-entity.distance-limit` | Tile entity draw distance | Entities | Optional | Runs in game | Set in game (Hub) | **none** | — |
| `render.hud.caching` | HUD caching | HUD and interface | Safe | Runs in game | Set in game (Hub) | in-game (`view-2026-10-02-hud-steady`) | — |
| `cpu.allocation.hot-path-reduction` | Reduce allocation on hot paths | Memory | Safe | Runs in game | Launcher switch | in-game (`perf-2026-09-14-ab`) | — |
| `world.chunk.lookup-cache` | Chunk lookup cache | Chunks | Safe | Runs in game | Launcher switch | in-game (`perf-2026-09-14-ab`) | OptiFine |
| `memory.world-change-gc` | Remove System.gc() on world change | Memory | Safe | Runs in game | Launcher switch | isolated (`bench-2026-08-26-explicit-gc`) | — |
| `resources.pack-icon-downscale` | Downscale resource pack icons | Resources | Safe | Runs in game | Launcher switch | isolated (`opt-2026-08-26-pack-icons`) | — |
| `render.entity.nametag-name-cache` | Nametag name cache | Entities | Safe | Runs in game | Launcher switch | in-game (`view-2026-10-05-entities`) | — |
| `render.entity.armor-texture-cache` | Armor texture lookup cache | Entities | Safe | Runs in game | Launcher switch | in-game (`view-2026-10-05-entities`) | — |
| `render.blockentity.sign-text-cache` | Sign text cache | Entities | Safe | Runs in game | Launcher switch | in-game (`mp-2026-10-13-ab`) | — |
| `render.entity.item-frame-entity-reuse` | Item frame item entity reuse | Entities | Safe | Runs in game | Launcher switch | in-game (`mp-2026-10-13-ab`) | — |
| `render.hud.tab-list-text-replay` | Tab list text replay | HUD and interface | Safe | Runs in game | Launcher switch | in-game (`mp-2026-10-14-ab2-ab3`) | — |
| `render.entity.shadow-allocation` | Shadow lookup without allocation | Entities | Safe | Runs in game | Launcher switch | in-game (`mp-2026-10-14-ab2-ab3`) | — |
| `render.forge.sky-blend-cache` | Forge sky colour cache | Rendering | Safe | Runs in game | Launcher switch | in-game (`forge-2026-10-15-sky-blend`) | — |
| `render.hud.vanilla-replay` | Replay unchanged chat and scoreboard | HUD and interface | Safe | Runs in game | Launcher switch | in-game (`mp-2026-10-06-hud`) | — |
| `input.unfocused-fps-cap` | Cap frame rate when unfocused | Frames and input | Safe | Runs in game | Launcher switch | isolated (`opt-2026-09-04-frame-caps`) | — |
| `render.entity.occlusion-culling` | Entity occlusion culling | Entities | Optional | Core written, not hooked up | Launcher switch | **none** | OptiFine shaders |
| `render.chunk.rebuild-scheduling` | Chunk rebuild scheduling | Chunks | Safe | Runs in game | Launcher switch | in-game (`view-2026-10-03-chunk-workers`) | OptiFine |
| `input.frame-limiter-accuracy` | Accurate frame limiter | Frames and input | Safe | Runs in game | Launcher switch | in-game (`view-2026-10-02-pace144`) | — |

## 3. Every entry in full

#### `render.font.metrics-cache` — Font metrics caching

Character width lookups are recomputed per frame. Caching them is output-identical as long as the cache is invalidated on resource reload and language change.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Rendering. **Default:** off.
- **Expected benefit:** Measured 66-86% faster than recomputing, for the measurement work alone, at realistic churn. Unknown as a fraction of a frame.
- **Measured** (isolated, `bench-2026-08-26-text-metrics`): One frame of a busy server's text (235 strings, 11,528 characters): 12.9 us to recompute, 1.8 us cached with a static corpus and 5.2 us with 5% of strings new each frame. At 50% churn the difference falls inside the noise floor, and at 100% the cache is 47% slower.
- **Stood down by OptiFine:** OptiFine rewrites large parts of the renderer, including its font paths. Fathom turns this off when OptiFine is present rather than fighting it: the user chose to install OptiFine.

#### `render.font.format-code-case` — Colour codes without copying

Vanilla's text drawing reads the letter after every colour code from a lowercased copy of the whole line, made once per code and again for the shadow. This lowercases the one letter instead. Output-identical, except after the rare character whose lowercase is two characters long (U+0130), where vanilla misreads the code that follows.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Rendering. **Default:** off.
- **Expected benefit:** Less allocation wherever coloured text is drawn live: the tab list, nametags, signs, holograms, scoreboards and chat when not replayed, and HUD widgets in Minecraft's font. A JFR profile traced a scoreboard's whole per-frame cost to these copies. Not yet measured in game.
- **Measured:** nothing yet.

#### `render.font.glyph-page-preload` — Preload symbol glyphs

The font draws symbols such as stars, hearts and arrows from pages of its unicode sheet, and loads a page the first time anything on it is drawn: a PNG decoded on the render thread, mid-frame, the first time a lobby label comes into view. This loads the pages lobbies use while the font itself loads instead. The same textures, drawn the same way; about 1.5 MB of video memory.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Rendering. **Default:** off.
- **Expected benefit:** A first-look stutter, once per session per page, where labels or chat use symbols. Nothing in steady frames. Not yet measured.
- **Measured:** nothing yet.

#### `render.nametag.replay` — Fast nametags

Vanilla draws a nametag a glyph at a time, twice. Each distinct label is recorded once and replayed, with the same glyphs in the same places.

- **Status:** Runs in game. **Control:** Set in game (Hub). **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Largest where many named players or armour stands are in view.
- **Measured** (in-game, `performance-layer-2026-09-22`): Isolated on a populated server lobby: 0.155 ms/frame median paired saving with 33 labels a frame, 18 of 20 pairs, sign test p = 0.0004. Eight test strings were pixel-identical to vanilla.

#### `render.entity.distance-limit` — Entity draw distance

Entities past a set distance are not drawn. Players are never limited inside 48 blocks, and the game still simulates everything it would have.

- **Status:** Runs in game. **Control:** Set in game (Hub). **Class:** Optional. **Group:** Entities. **Default:** off.
- **Expected benefit:** Workload-dependent: large in lobbies full of distant armour stands, nothing where everything is close.
- **Measured** (in-game, `performance-layer-2026-09-22`): Isolated in a lobby with 52 distant armour stands: 0.060 ms/frame median paired saving, 14 of 20 pairs (p = 0.115) -- not consistent enough for a standalone claim. The distance test itself cost 0.010 ms/frame.

#### `render.tile-entity.distance-limit` — Tile entity draw distance

Chests, signs, skulls and banners past a set distance are not drawn. Beacons, end portals and pistons are never limited.

- **Status:** Runs in game. **Control:** Set in game (Hub). **Class:** Optional. **Group:** Entities. **Default:** off.
- **Expected benefit:** Engaged in testing (3 of 68 tile entities culled a frame) but never isolated, so no frame-time claim is made.
- **Measured:** nothing yet.

#### `render.hud.caching` — HUD caching

Fathom's HUD widgets that have not changed since the last frame are replayed from a recording instead of drawn again, and slow readouts such as the clock and ping are redrawn 20 times a second. Replaces the planned quad batching: Fathom's widgets already batch their quads.

- **Status:** Runs in game. **Control:** Set in game (Hub). **Class:** Safe. **Group:** HUD and interface. **Default:** on.
- **Expected benefit:** Most of what Fathom's own HUD costs a frame, in every scene.
- **Measured** (in-game, `view-2026-10-02-hud-steady`): Replaying unchanged realtime widgets, A/B'd in the view benchmark (3 interleaved rounds, OptiFine on): -0.026 ms/frame looking at the sky, -0.027 at the ground, -0.029 with 48 players in view; Fathom's HUD fell from 0.042 to 0.027 ms/frame. Keystrokes and CPS pixel-identical in every scene.

#### `cpu.allocation.hot-path-reduction` — Reduce allocation on hot paths

Vanilla clones the six-direction array at the top of loops in per-block light queries, light updates and the per-frame chunk visibility search. Those loops only read it, so they share one copy instead. Output-identical. With OptiFine, only the light-query part applies: OptiFine rewrites the visibility search.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Memory. **Default:** on.
- **Expected benefit:** The clones were 22% of the render thread's allocation in a sampled run. Together with the chunk lookup cache, an interleaved in-game A/B cut allocation 55% while panning and 35% while moving, with 5-8% lower mean frame time in those scenes.
- **Measured** (in-game, `perf-2026-09-14-ab`): Interleaved ABBA run in the development world, switches toggled within one session: pan 51.8 -> 23.4 MB/s allocated and 6 -> 3 collections; moving 203 -> 132 MB/s. Profiler: the six-direction clones fell from 1890 MB to 91 MB over the run.

#### `world.chunk.lookup-cache` — Chunk lookup cache

Every light query resolves its chunk through a hash lookup, and particles ask for light every frame. The client thread remembers the last few chunks it looked up; the cache is cleared whenever a chunk loads or unloads, so it always returns what the lookup would.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Chunks. **Default:** on.
- **Expected benefit:** The hash lookup was the hottest method on the render thread (28% of CPU samples) in a scene with torches. Largest where particles and entities are drawn.
- **Measured** (in-game, `perf-2026-09-14-ab`): Toggled alone, four consecutive interleaved blocks standing still in alternating ABBA/BAAB order: mean frame time 6.5-9.9% lower in every block, p99 lower in every block. Profiler: chunk lookups fell from 14.3% to 5.1% of render-thread samples.
- **Stood down by OptiFine:** With OptiFine installed it measured no benefit: A/B'd in the view benchmark (2026-10-02) it moved nothing outside the noise, so it is not applied there.

#### `memory.world-change-gc` — Remove System.gc() on world change

Vanilla calls System.gc() when changing worlds, which stops the client for as long as a full collection takes.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Memory. **Default:** off.
- **Expected benefit:** Removes a stall of roughly a quarter of a second per world change on the reference machine, and more on a larger live set. The JVM flag -XX:+DisableExplicitGC achieves the same thing without a mod, and the launcher already passes it; this covers the case where it was not passed.
- **Measured** (isolated, `bench-2026-08-26-explicit-gc`): A forced full collection under a synthetic 512 MB live set costs a median 226 ms with G1 and 926 ms with ParallelGC, the collector every unmodded 1.8.9 player is running. -XX:+DisableExplicitGC removes it entirely: zero collections performed, 0.0 ms. Synthetic workload, not Minecraft.

#### `resources.pack-icon-downscale` — Downscale resource pack icons

Every installed pack's pack.png is uploaded at whatever resolution its author saved it and kept for the session, though the resource pack screen never draws one larger than 32 pixels. Downscaling to 64 on load frees the difference.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Resources. **Default:** off.
- **Expected benefit:** Frees video memory in proportion to how many packs are installed and how carelessly their icons were exported. Costs nothing at frame time either way -- this is a memory optimization and should not be expected to move FPS.
- **Measured** (isolated, `opt-2026-08-26-pack-icons`): Arithmetic over a plausible 40-pack folder: 12 MB of video memory above what the screen samples, and 4.2 MB for a single 1024x1024 icon. Exact rather than estimated, because RGBA8 texture size is a calculation and not a measurement.

#### `render.entity.nametag-name-cache` — Nametag name cache

Every visible nametag, every frame, vanilla builds a chat component with click and hover events -- an NBT compound turned into a string, a UUID turned into a string -- only to read back prefix, name and suffix. This keeps the string the original code produced and rebuilds it the frame the name, team, prefix or suffix changes. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Proportional to labels on screen: player lobbies, holograms, PvP. Removes per-label allocation and Java 8 regex compiles.
- **Measured** (in-game, `view-2026-10-05-entities`): OptiFine profile, 48 armoured players with nametags, 4 interleaved rounds against the switch off: faster in 4/4 rounds, paired median -0.200 ms/frame, median 1.948 -> 1.815 ms, p99 4.243 -> 3.813 ms; the label decision fell from 0.069 to 0.009 ms/frame. Verified against the original on 130,122 labels with teams and prefixes changing: 0 mismatches.

#### `render.entity.armor-texture-cache` — Armor texture lookup cache

Vanilla finds each armour piece's texture by formatting its path with String.format every frame, then looking the string up in a cache of its own. This remembers the identifier the original lookup returned for each material and layer, so the same object comes back without the formatting. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Proportional to armoured players and mobs in view; up to five String.format calls per armoured player per frame.
- **Measured** (in-game, `view-2026-10-05-entities`): OptiFine profile, 48 iron-armoured players, 4 interleaved rounds against the switch off: faster in 3/4 rounds, paired median -0.174 ms/frame, median 1.948 -> 1.784 ms; the armour layer fell from 0.456 to 0.253 ms/frame and client allocation from 206 to 99 MB/s. 543,702 lookups verified identical.

#### `render.blockentity.sign-text-cache` — Sign text cache

Vanilla re-wraps and re-formats every line of every sign in view every frame: a copy of the text component, an iterator walk, a style copy and a width measurement per line, then the formatting codes rebuilt. This keeps each sign's wrapped lines and their formatted strings until the sign's text or the font changes, and hands the renderer the very same objects. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Proportional to signs in view: lobbies, sign walls, server spawns. Most of the render thread's allocation where signs are on screen.
- **Measured** (in-game, `mp-2026-10-13-ab`): Local server, 40 signs with 24 skulls, 16 banners and 12 item frames in view, 4 interleaved rounds against the switch off: faster in 4/4 rounds, paired median -0.117 ms/frame, p99 3.34 -> 2.40 ms; block entity draws 0.381 -> 0.246 ms/frame (about 3.4 us per sign) and client allocation 78 -> 35 MB/s. 178,000 sign lines verified identical.

#### `render.entity.item-frame-entity-reuse` — Item frame item entity reuse

To draw the item in an item frame, vanilla constructs a new item entity around it every frame, for every frame in view, and reads only the item back. This reuses one entity, given the frame's item each time, so the renderer gets the same inputs without an entity's construction. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Proportional to filled item frames in view: map walls, displays.
- **Measured** (in-game, `mp-2026-10-13-ab`): Local server, 12 filled item frames in view, 4 interleaved rounds against the switch off: frame-entity draws 0.303 -> 0.267 ms/frame (about 3 us per frame) and client allocation 78 -> 72 MB/s; the whole frame moved within noise at this count (paired +0.009 ms, 2/4). Inputs to the renderer are identical by construction.

#### `render.hud.tab-list-text-replay` — Tab list text replay

While the tab list is held, vanilla draws every name glyph by glyph, every frame. This records each string once, at its position and colour, and replays it; the skins, icons, backgrounds and scores stay live, so nothing that changes can go stale. Obfuscated text is always drawn live. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** HUD and interface. **Default:** on.
- **Expected benefit:** While the tab list is held, proportional to the players in it: most of the list's cost on a full server.
- **Measured** (in-game, `mp-2026-10-14-ab2-ab3`): Local server, 80 players online with the tab list held: text drawing 0.461 -> 0.138 ms/frame and client allocation 35.5 -> 22.5 MB/s; faster in 6/6 interleaved rounds, paired median -0.143 ms/frame (measured with another game running, so whole-frame figures are noisy; the section and allocation figures are not). 6,653 strings drawn both ways offscreen: 0 differing bytes.

#### `render.entity.shadow-allocation` — Shadow lookup without allocation

Each entity shadow asks for the block below every block it covers with a new position object, used once and dropped. This reuses one position for that lookup, with the same coordinates. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Entities. **Default:** on.
- **Expected benefit:** Less allocation under piles of dropped items and crowds.
- **Measured** (in-game, `mp-2026-10-14-ab2-ab3`): Local server, 160 dropped items on generators: client allocation 21.9 -> 16.4 and 25.0 -> 20.8 MB/s in two runs; shadow drawing time unchanged (0.393 -> 0.405 ms/frame) and no whole-frame change. Identical input to the lookup by construction.

#### `render.forge.sky-blend-cache` — Forge sky colour cache

With Fancy graphics, Forge averages the sky colour of every biome column around the camera -- 2,809 of them at render distance 12 -- twice a frame, because its own cache compares the wrong coordinate and never hits. This remembers the answer until the camera's block, the blend radius or the loaded chunks change, and computes it without allocating. Forge only. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Rendering. **Default:** on.
- **Expected benefit:** Forge with Fancy graphics: less frame time and allocation in every scene.
- **Measured** (in-game, `forge-2026-10-15-sky-blend`): Forge 11.15.1.2318 with OptiFine M5, Fancy, render distance 12, 4 paired rounds: horizon 1.163 -> 0.896 ms median (4/4), down45 1.319 -> 1.010 (4/4), 48 players 3.146 -> 2.867 (3/4); client allocation 118 -> 18 MB/s. Verify mode: 28,672 answers compared with Forge's own, 0 differed.

#### `render.hud.vanilla-replay` — Replay unchanged chat and scoreboard

Chat and the scoreboard sidebar redraw every glyph every frame, though what they show changes at most once a client tick or when the server changes the scoreboard. This records them while they hold still and replays the recording until anything they are drawn from changes; that frame is drawn live. Obfuscated text is always drawn live. Output-identical.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** HUD and interface. **Default:** on.
- **Expected benefit:** Whenever chat lines or a server sidebar are on screen: in the multiplayer profile they cost 0.06-0.13 and 0.05-0.09 ms a frame.
- **Measured** (in-game, `mp-2026-10-06-hud`): Local 1.8.9 server, 40 scripted players, OptiFine profile, 4 interleaved rounds against the switch off. Scoreboard updating every tick and chat every 100 ms: faster in 4/4 rounds, paired median -0.155 ms/frame, p95 3.314 -> 3.065 ms, p99 4.061 -> 3.652 ms. Lobby with chat and sidebar: -0.111 and -0.115 ms paired, 3/4 rounds in each of two runs. No change where neither is on screen. Pixel check: 630 chat and 691 sidebar replays drawn both ways offscreen, 0 differing bytes.

#### `input.unfocused-fps-cap` — Cap frame rate when unfocused

Vanilla renders at full speed into a window you have alt-tabbed away from. This caps it at 30 frames per second while the window does not have focus, and changes nothing while it does.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Frames and input. **Default:** off.
- **Expected benefit:** Frees the GPU and CPU when the game is in the background. No effect on focused performance, by construction: the cap is only consulted while the window is unfocused.
- **Measured** (isolated, `opt-2026-09-04-frame-caps`): Arithmetic rather than a measurement, and exact because of it: the policy returns the minimum of vanilla's own answer and the cap, so the frames drawn while unfocused fall from whatever the machine managed to 10 per second. What that frees on any given machine has not been measured.

#### `render.entity.occlusion-culling` — Entity occlusion culling

Skip entities fully hidden behind geometry. Must exempt nametags, ender dragons and withers, and must never reveal anything vanilla hides. Built and measured in game on 2026-10-03 (branch experiment/entity-occlusion) and not shipped: proving an entity hidden on the CPU cost more than drawing it. A sampled test hid a walled-off crowd of 48 armoured zombies but spent 1.8-2.3 ms a frame to save 1.2; an exact test never errs but proves too few hidden behind real terrain, and the scene was still 0.35 ms slower with it on.

- **Status:** Core written, not hooked up. **Control:** Launcher switch. **Class:** Optional. **Group:** Entities. **Default:** off.
- **Expected benefit:** About 25 us per armoured mob drawn is the most it can save, and players and anything named are exempt. Needs a test far cheaper than that per entity -- likely GPU occlusion queries -- before it is worth offering.
- **Measured:** nothing yet.
- **Stood down by OptiFine shaders:** PolyPatcher documents that entity culling and OptiFine shaders cannot be made compatible. Shaders present is a hard gate: culling is off, not merely adjusted.

#### `render.chunk.rebuild-scheduling` — Chunk rebuild scheduling

Without OptiFine, 1.8.9 rebuilds chunks on exactly two worker threads whatever the machine. This sizes the pool to the CPU instead -- half the hardware threads, between two and five -- with a build buffer per worker. Of the three ideas merged here, only the pool size survived reading the code: vanilla already builds nearest chunks first, and the view benchmark found no redundant rebuilds to skip. Takes effect at the next renderer reload (joining a world, F3+A).

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Chunks. **Default:** off.
- **Expected benefit:** Chunks fill in sooner when joining, teleporting or reloading. Steady frame time is untouched: once a view settles nothing is being rebuilt. Not on by default -- one machine, and each arm had an outlier.
- **Measured** (in-game, `view-2026-10-03-chunk-workers`): Full renderer reloads on my own profile without OptiFine, four launches in on-off-off-on order, three reloads each, 5 workers against vanilla's 2: reload-to-quiet median 0.65 s against 0.76 s, mean 0.73 s against 0.87 s (each including a fixed 0.5 s quiet period).
- **Stood down by OptiFine:** OptiFine already sizes its own chunk worker pool to the CPU (up to five threads).

#### `input.frame-limiter-accuracy` — Accurate frame limiter

Vanilla's FPS cap waits using LWJGL's estimates of how long a sleep and a yield take, so it reaches the right average with uneven frames around it. This waits for fixed deadlines on the system's precise clock instead: sleep while more than 1.5 ms remains, then yield. Only does anything with a frame cap set.

- **Status:** Runs in game. **Control:** Launcher switch. **Class:** Safe. **Group:** Frames and input. **Default:** on.
- **Expected benefit:** More even frame pacing at a cap, same average FPS. Nothing at all uncapped.
- **Measured** (in-game, `view-2026-10-02-pace144`): Capped at 144 FPS with OptiFine, three views, three rounds each: frame-time standard deviation 0.51 -> 0.24 ms (sky), 0.55 -> 0.38 ms (horizon), 0.52 -> 0.41 ms (45 degrees down); mean frame time unchanged within 0.02 ms.

<!-- generated:end -->

---

## 3a. The 2026-10-02 audit

The launcher used to say **"14 more optimizations planned"**. The number came
from the launcher's own hand-written copy of the catalogue (`launcher-core`
`optimizations/mod.rs`): the count of game-side entries whose status was not
`Active`. The Java registry held the same 19 game-side entries. Neither had a
way to say whether a planned idea was still a good one, and the copy had
drifted from the game (it reported the chunk lookup cache as running under
OptiFine, which the game skips). The browser preview's fixture also hard-coded
`planned: 14`.

Each of the 14 was reviewed against measurement — the view-direction profile
(`view-direction-2026-10-02.md`) and the performance-layer A/B
(`performance-layer-2026-09-22.md`) — and against what OptiFine already does,
read from the classes OptiFabric actually patches.

| Planned entry | Decision | Why |
|---|---|---|
| `render.font.batching` | **Remove** | OptiFine patches `TextRenderer`, so it was stood down in every OptiFine profile; text measured 0.012-0.021 ms a frame in total; the fast nametag path already replays the text that mattered (nametags). The core `QuadBatcher` stays for its tests. |
| `render.hud.quad-batching` | **Replace** with `render.hud.caching` | Fathom's widgets already batch their quads (`QuadBatch`). What the profile found was redrawing unchanged widgets every frame; replaying them saved 0.026-0.029 ms in every scene. |
| `render.entity.model-batching` | **Remove** | Invasive (every entity renderer), and OptiFine patches `LivingEntityRenderer`, `ModelPart` and the armour layers. Entity cost (23 us per armoured player) is real and is carried by occlusion culling instead. |
| `render.tile-entity.batching` | **Remove** | Block-entity drawing measured 0.0001-0.0003 ms a frame in every scene. OptiFine patches the dispatcher. |
| `render.tile-entity.double-render-fix` | **Remove** | Same measurement: nothing to save. |
| `render.entity.occlusion-culling` | **Keep (planned)** | Valid target: a crowd behind a wall is 23 us a player. Needs an occlusion test that is cheaper than what it saves and never hides a visible entity — its own research. |
| `render.chunk.rebuild-priority` | **Merge** into `render.chunk.rebuild-scheduling` | Chunk-loading latency, not steady frames (the profile saw zero rebuilds in settled views). OptiFine already schedules rebuilds. Needs a chunk-loading benchmark. |
| `render.chunk.skip-redundant-rebuilds` | **Merge** into `render.chunk.rebuild-scheduling` | As above: invalidations measured 0-0.06 a frame in settled views. |
| `render.chunk.worker-pool-sizing` | **Merge** into `render.chunk.rebuild-scheduling` | Vanilla 1.8.9 does use exactly two workers (`ChunkBuilder.<init>`), but OptiFine replaces that with a CPU-scaled pool of up to five. Only non-OptiFine profiles could gain. |
| `render.chunk.frustum-false-negative-fix` | **Remove** | Not an optimization: it costs frame time by design, and OptiFine has its own frustum test. |
| `resources.pack-icon-downscale` | **Implement** | Small, isolated, real (memory, not frame time). Hooked in this pass (`ResourcePackIconMixin`). |
| `input.mc-67665-look-vector` | **Remove** | Not a performance optimization, and changing which block a click targets belongs in a deliberate gameplay feature, not here. |
| `input.frame-limiter-accuracy` | **Keep (planned)** | Valid for capped frame rates. Fathom profiles default to uncapped, so it waits behind measured work. |
| `render.particle.brightness-cache` | **Remove** | The light lookup it would cache measured as no bottleneck under OptiFine (the chunk lookup cache A/B moved particles 0.6%). Particle drawing is 0.25 us a particle, and the real particle problem is the 3 ms tick spike, which is simulation. |

**Added** — real, running features that were missing from the registry, all
from the in-game performance layer: `render.nametag.replay`,
`render.entity.distance-limit`, `render.tile-entity.distance-limit`, and
`render.hud.caching`.

**Result:** 14 planned → **3 valid planned** (occlusion culling, rebuild
scheduling, frame limiter). 1 implemented this pass, 1 replaced by an
implemented module, 3 merged into one, 7 removed.

**Update 2026-10-03:** two of the three now run in game, each measured in its
own run (`docs/performance/view-direction-2026-10-02.md`):
`input.frame-limiter-accuracy` (frame-time sd at a 144 cap 0.51 → 0.24 ms,
same mean; on by default) and `render.chunk.rebuild-scheduling`, which on
reading the code reduced to the worker pool size (rebuild after a reload
≈0.15 s vs ≈0.26 s without OptiFine; off by default, one machine). Only
occlusion culling is still planned; it was then built and measured (2026-10-03) and
proved slower than drawing what it hides, so it stays planned with those numbers.

The launcher no longer shows a count. It lists the planned entries by name, with
how far each has got, and shows every implemented one with a truthful status:
**On**, **Off**, **Off with OptiFine**, **Set in game**, **Automatic**, or
**Needs the game module**.

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
`docs/performance/benchmarks.md` §4.

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

```bash
cd minecraft/fathom-optimizations && gradle exportRegistry   # registry -> assets/optimizations/registry.json
python tools/gen-registry-doc.py                             # registry.json -> §1-§3 of this file
```

If a table here disagrees with the code, the code is right and this file is
stale.

---

## 9. References

- `docs/performance/benchmarks.md` — every run behind the evidence column
- `docs/performance/benchmark-methodology.md` — how anything here gets measured
- `docs/research/performance.md` — the analysis these entries encode
- `docs/research/PERFORMANCE_MATRIX.md` — the classification scheme
- `docs/research/mod-policies.md` §3, §7, §8 — licensing and conflicts
