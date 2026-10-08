# The multiplayer frame — 2026-10

What a populated server adds to a frame, the harness that measures it, and the
three optimizations it produced.

## Result

**Connecting to a server costs nothing measurable by itself.** In an empty
server scene the frame is within 0.02–0.05 ms of the same camera in a
singleplayer copy of the world, and packets cost the client thread
0.001–0.03 ms a frame in every scene. **The multiplayer delta is the content:**
other players (16–49 µs each), the vanilla HUD a server fills (chat, sidebar,
tab list), particles, and the client tick that all of it makes heavier, which is
what sets p99.

Three output-identical optimizations, now default on, each verified against the
original code and measured interleaved on a real 1.8.9 server connection:

| | Paired median saving | Where |
|---|---:|---|
| Nametag name cache | 0.05–0.10 ms | every scene with labelled players |
| Armor texture cache | 0.12–0.13 ms | armoured players (combat) |
| Chat + sidebar replay | 0.11–0.16 ms | chat lines or a server sidebar on screen |
| **All three together** | **0.15–0.21 ms** | lobby, combat, HUD-heavy; 4/4 rounds in every player scene |

## Setup

- The local server and scripted players are described below. The server is a
  vanilla 1.8.9 dedicated server on 127.0.0.1 in offline mode; the client is the
  production stack (Knot, Fathom, OptiFabric 2.3.6, OptiFine HD U M5) in its own
  game directory, with the `optimized` profile's settings copied in.
- 1920×1080 windowed, render distance 8, fast graphics, smooth lighting max,
  VBO on, particles All, frame cap off, vsync off, GUI scale 2.
- RTX 4060 (driver 617.14), 12 hardware threads, Java 8u502, G1, 4 GB heap.
- Arms are interleaved per scene, order reversed on alternate rounds; 4 rounds
  of 3 s windows; every new scene settles 5 s before its first window (a first
  version without that settle biased whichever arm went first by up to
  0.1 ms, and was discarded). 0 frames rejected, 0 unfocused, in every run
  quoted here.

## Multiplayer vs singleplayer

Run `mp-2026-10-06-18-41-41`, all three optimizations on (the shipped state).
ms per frame; deltas against `sp`.

| Scene | Median | Mean | p95 | p99 | Δ median | Δ p95 | Δ p99 | Players drawn | Labels |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| sp | 0.526 | 0.543 | 0.730 | 0.927 | | | | 0 | 0 |
| mp-empty | 0.548 | 0.594 | 0.783 | 0.987 | +0.022 | +0.054 | +0.059 | 0 | 0 |
| mp-few | 0.649 | 0.692 | 0.935 | 1.144 | +0.123 | +0.206 | +0.216 | 5 | 5 |
| lobby | 1.729 | 1.839 | 2.554 | 3.222 | +1.202 | +1.824 | +2.295 | 40 | 26 |
| combat | 1.533 | 1.682 | 2.488 | 3.074 | +1.006 | +1.758 | +2.147 | 20 | 12 |
| combat-particles | 1.750 | 1.988 | 3.077 | 4.270 | +1.224 | +2.348 | +3.342 | 20 | 12 |
| nametags | 1.262 | 1.370 | 1.987 | 2.495 | +0.735 | +1.257 | +1.568 | 40 | 32 |
| hud-heavy | 2.134 | 2.248 | 3.113 | 3.806 | +1.607 | +2.383 | +2.879 | 40 | 21 |
| chunks | 0.735 | 0.793 | 1.128 | 1.533 | +0.208 | +0.398 | +0.606 | 10 | 6 |

## Top causes of the delta

Section deltas against `sp`, run `mp-2026-10-06-17-27-39` (before the HUD
replay; the shape is unchanged after it, only smaller):

| Cause | lobby | combat | combat + particles | hud-heavy | Owner |
|---|---:|---:|---:|---:|---|
| Player entity draws | +0.78 | +0.93 | +0.92 | +0.87 | vanilla / OptiFine |
| — body model + skin | +0.34 | +0.18 | +0.18 | +0.36 | vanilla |
| — armor layer (+ glint) | 0 | +0.34 | +0.34 | 0 | vanilla / OptiFine |
| — held item | +0.14 | +0.17 | +0.17 | +0.16 | vanilla |
| — label decision + label | +0.20 | +0.11 | +0.11 | +0.22 | vanilla + Fathom fast path |
| Vanilla HUD | +0.14 | +0.09 | +0.01 | +0.59 | vanilla |
| — tab list (held) | | | | +0.35 | vanilla |
| — chat | +0.07 | +0.08 | | +0.13 | vanilla |
| — sidebar | +0.05 | | | +0.09 | vanilla |
| Particles (draw) | +0.01 | +0.01 | +0.20 | +0.02 | vanilla / OptiFine |
| Client tick (mean; it is the p99) | +0.01 | +0.01 | +0.05 | +0.02 | vanilla |
| Packets applied | +0.001 | +0.002 | +0.010 | +0.004 | vanilla |

**One player**, microseconds per player drawn (shipped state): plain 16–17 µs
(body 8.7, label decision 5.0 of which the label 3.7); with a held item 21–22 µs;
iron-armoured with a sword, half enchanted, **48.6 µs** (armor 17.5, held item
9.1). Cost scales linearly: 5 players +0.12 ms, 20 armoured +1.0 ms, 40 +0.74 to
+1.2 ms depending on what they carry.

### The tail

p99 frames are tick frames. At these frame rates a client tick lands in one
frame in 25–90; it costs 0.33 ms in `sp`, 0.4–0.8 ms with players, and in
`combat-particles` 1.3 ms, of which vanilla particle physics is 1.1 ms. 27–58% of
p99+ frames carry a tick (the rest are player-drawing variance). GC was in
under 2% of p99+ frames. Packet bursts reach 35–53 packets in a frame in combat
and still cost under 0.03 ms.

### Packets

| Kind | µs per packet | Rate in combat |
|---|---:|---:|
| entity movement | 0.4–0.9 | 380–720/s |
| equipment | 0.4 | 155–175/s |
| animation / status | 1.2–3.5 | 90/s |
| metadata | 9–10 | 10/s |
| block changes | 4–13 | 50/s (chunks scene) |
| chunk data | 34 | 2/s |
| sounds | 35–39 | 23/s |
| server particles | 35–40 | 100/s |
| chat | 108–115 | 11/s (hud-heavy) |

No packet path is doing redundant work worth removing: movement, the bulk of
traffic, is under a microsecond. Chat (line wrapping and the `[CHAT]` log
line) and sounds are the expensive ones per packet and are rare.

## Fathom's own share

`fathom-off` (Fathom's hooks bypassed, performance layer off) against
`normal`: Fathom costs **0.05 ms** in `sp` and `mp-empty` — its HUD (FPS,
coordinates, keystrokes, CPS) at 0.027 ms and frame hooks — and **saves**
0.05–0.10 ms in scenes with labels, through its nametag fast path. The
distance-limit hook costs 0.002 ms with 40 players. No Fathom code runs per
packet, per tick or per player layer other than those two hooks and the new
caches. Fathom's event system does not dispatch anything per entity: there is
no per-entity listener traversal to remove.

## Optimizations

### Nametag name cache — `render.entity.nametag-name-cache`

Vanilla builds the label string with `entity.getName().asFormattedString()`
every frame for every label: a `LiteralText` with a click event, an insertion
and a hover event whose text is an NBT compound serialised to a string —
`UUID.toString()`, a `HashMap`, and a Java 8 `String.replace` (a regex compile)
per value — to produce `prefix + name + suffix + "§r"`. Fathom calls the
original once, keeps the Text and its string on the entity, and serves them
while the name, the team and its prefix and suffix are unchanged; any change
runs the original that same frame. Only vanilla's own entity classes; villagers,
minecarts and modded entities always take the original.

### Armor texture cache — `render.entity.armor-texture-cache`

`ArmorFeatureRenderer.getArmorTexture` formats the texture path with
`String.format` (twice with an overlay) per armour piece per frame, then looks
it up in vanilla's own cache. OptiFine keeps the call. Fathom remembers the
`Identifier` the original returned for each material, layer and overlay and
returns that same object.

### Chat and sidebar replay — `render.hud.vanilla-replay`

Chat and the scoreboard sidebar redraw every glyph every frame, though what
they show changes at most once a tick (chat line ages) or when the scoreboard
changes. Each is recorded into a display list while it holds still and
replayed until any input changes; that frame is drawn live.

- Chat's signature covers the tick, the visible lines, scroll, unread marker,
  focus, the five chat settings and the font. When unfocused chat has nothing
  young enough to show, vanilla's empty pass runs instead (the first version
  replayed nothing at a 0.013 ms cost; fixed and re-measured).
- The sidebar's covers the objective, the scoreboard, a revision counter that
  every scoreboard change bumps (all `update*` notifications, plus team
  membership and lazily created scores), the scaled window and the font.
- GL state is read back before and after each recording and forced around
  each replay, so the following code sees exactly what a live draw leaves, in
  GL and in `GlStateManager`'s cache. Obfuscated text (`§k`) is always live. Off
  under shader packs.
- The tab list is not cached: it also depends on entity metadata, players
  entering range and asynchronously loaded skins, which a signature cannot
  cover without risking a stale frame.

### Measured, one at a time (run `mp-2026-10-06-18-18-23` and re-check `18-26-09`)

Paired median (arm window − normal window over 4 rounds; positive = the switch
off is slower) and rounds won:

| Scene | name cache off | armor cache off | replay on |
|---|---:|---:|---:|
| sp | −0.005 | +0.003 | +0.005 |
| mp-few | +0.015 (1/4) | −0.011 | −0.003 |
| lobby | +0.049 (1/4) | −0.033 | **−0.111 / −0.115 (3/4)** |
| combat | **+0.058 (0/4)** | **+0.125 (0/4)** | −0.015 |
| combat-particles | **+0.052 (0/4)** | **+0.130 (0/4)** | +0.013 |
| nametags | **+0.097 (0/4)** | −0.011 | +0.008 (after fix) |
| hud-heavy | **+0.072 (0/4)** | −0.033 | **−0.155 (4/4)**; p95 −0.249, p99 −0.409 |
| chunks | +0.021 (0/4) | +0.010 | −0.003 |

Each moves only the scenes where its work exists.

### All three together (run `mp-2026-10-06-18-41-41`)

`normal` (all on) against all three off, 4 interleaved rounds:

| Scene | Median before → after | p95 | p99 | Paired | Wins | Heap alloc MB/s |
|---|---|---|---|---:|---:|---|
| mp-few | 0.672 → 0.649 | 0.958 → 0.935 | 1.207 → 1.144 | −0.027 | 4/4 | 37.3 → 9.1 |
| lobby | 1.921 → 1.729 | 2.849 → 2.554 | 3.566 → 3.222 | **−0.206** | 4/4 | 119.7 → 23.5 |
| combat | 1.703 → 1.533 | 2.848 → 2.488 | 3.515 → 3.074 | **−0.204** | 4/4 | 128.6 → 13.7 |
| combat-particles | 1.895 → 1.750 | 3.226 → 3.077 | 4.085 → 4.270 | **−0.184** | 4/4 | — |
| nametags | 1.319 → 1.262 | 2.066 → 1.987 | 2.583 → 2.495 | −0.055 | 4/4 | 138.8 → 22.5 |
| hud-heavy | 2.310 → 2.134 | 3.296 → 3.113 | 3.837 → 3.806 | **−0.151** | 4/4 | 113 → 35.6 |
| chunks | 0.759 → 0.735 | 1.170 → 1.128 | 1.580 → 1.533 | −0.026 | 4/4 | 61.5 → 12.4 |
| sp / mp-empty | unchanged within 0.01 | | | | 2/4, 3/4 | unchanged |

The combat-particles p99 is set by the particle tick (1.1 ms), which none of
these touch; it moved within its own noise.

## Correctness

| Check | Result |
|---|---|
| Nametag strings, served vs original (`-Dfathom.verify.caches=true`) | 130,122 in SP with teams and prefixes churning every 30 frames, 1,152,263 on the server with team colours and health below names: **0 mismatches** |
| Armor identifiers, served vs original | 543,702 lookups: **0 mismatches** |
| Chat replay, pixels | 630 replays drawn both ways into an offscreen framebuffer: **0 differing bytes** |
| Sidebar replay, pixels | 691 replays, scores updating every tick: **0 differing bytes** |
| Stale information | None by construction: every input of each cached output is in its key; a changed input is drawn live the same frame |
| Multiplayer behaviour | Nothing sent, delayed or dropped; no packet handling changed |
| OptiFine | All Mixins apply against OptiFine M5's patched classes (verified in its patched bytecode, not assumed) |
| Shader packs | The HUD replay steps aside, as the nametag fast path does |

## The harness

`-Dfathom.benchmark=view` gained a multiplayer mode (its Mixins still apply only
when asked for):

- **Sections:** player / item / other entity draws; body model, feature layers,
  armor, held item, cape; shadow + fire; nametag decision; scoreboard sidebar,
  tab list, chat, hotbar, status bars; other players' ticks; Fathom's
  distance-limit hook.
- **Packets** by kind: queued and applied per second, client-thread time per
  packet, chunk sections invalidated by the packet that caused them.
- **Per window:** client-thread allocation (`ThreadMXBean`).
- **Report:** multiplayer vs singleplayer, where the time goes, one player,
  packets, the tail (p95+/p99+ bands: tick share, GC share, sections), and every
  arm's distribution with paired differences and wins.
- **Arms:** `on:<id>` and `off:<id>` (several joined with `+`), `fathom-off`,
  `probes-off`.

### Scenes and the local server

`tools/mp-bench/mpbench.py` runs a vanilla 1.8.9 server (the jar Loom already
downloaded) bound to 127.0.0.1 in offline mode, and 40 protocol-47 players over
real sockets. The client asks for a scene through `run-mp/control`; the driver
sets it up and answers when ready.

| Scene | |
|---|---|
| `sp` | the server's world copied into a singleplayer save, same camera; runs before and after the server scenes |
| `mp-empty` | connected, nobody in range |
| `mp-few` | 5 players walking |
| `lobby` | 40 walking, sneaking, sprinting, chatting; team colours, health below names, a sidebar |
| `combat` | 20 in pairs, iron armour and diamond swords, half enchanted, hitting every 0.5 s |
| `combat-particles` | combat plus crit, magic-crit and reddust bursts from the server |
| `nametags` | 40 standing, team colours, health below names |
| `hud-heavy` | 40 players, 15-line sidebar updated every tick, tab list held, chat every 100 ms |
| `chunks` | blocks changing near the camera every tick, 10 players walking |
| `live` | a real server; camera not held |

Players not in a scene are logged out, not teleported away: vanilla 1.8
re-checks who can see a teleported player against the position it last
broadcast, and never again while it stays within 4 blocks, so a player
teleported back from far away stays invisible to everyone near its new spot.
Found with `tools/mp-bench/observe.py`, which counts what the server sends.

```
python tools/mp-bench/mpbench.py prepare --accept-eula   # once
python tools/mp-bench/mpbench.py serve                   # server + players; stops when the run ends
python tools/mp-bench/run_client.py                      # the client, in run-mp/
```

On a real server: `-Dfathom.benchmark=view -Dfathom.view.server=<host>` and no
control directory; the scene is `live`.

## Rejected or not attempted

- **Optimizing packet handling.** Measured at 0.001–0.03 ms a frame in total;
  nothing to recover.
- **Tab list replay.** Its output depends on entity metadata, players entering
  range and asynchronously loaded skins; any key that covers all of them is
  close to "every frame". Not worth a stale frame.
- **Armour glint or held-item drawing.** The remaining armour cost (17 µs per
  enchanted armoured player) is the glint passes; reducing it changes pixels.
- **Particle physics.** 1.1 ms per tick under combat particles, the largest p99
  contributor; changing it changes simulation (already listed as B in
  `view-direction-2026-10-02.md`).
- **Pixel diffs of whole screenshots** as the gate: moving clouds, animation and
  the FPS counter make them noisy; the comparisons above are exact instead.

## Remaining opportunities, by measured size

1. **Particle tick** in combat (1.1 ms per tick frame; sets p99 there). Needs a
   simulation-preserving design.
2. **Player body + layers**: 9 µs body, 17.5 µs enchanted armour, 9 µs held item
   per player. GL-submission bound; batching across players would be a renderer
   project.
3. **Tab list** while held: 0.35 ms with 40 players.
4. **Nametag label draw** (Fathom's replay): 3.2–3.9 µs per label.
5. **Chat packet** handling: 110 µs per message (line wrapping, logging).
