# Multiplayer frame, second pass — 2026-10-13

Fresh measurements, four new scenes modelled on real servers, a ranked list of
what a multiplayer frame spends, two output-identical fixes, and the plan for
the rest. Builds on `multiplayer-2026-10.md`; nothing validated there was
repeated or changed.

## 1. Was there a regression?

The 2026-10-12 run measured everything ~40% slower than 2026-10-06 (sp median
0.53 → 0.78 ms, lobby 1.73 → 2.41 ms, buffer swap 0.11 → 0.30 ms). Two candidate
causes: code changed since the last committed benchmark build (6a3302d), or the
machine. Settled by building both and measuring them in one sitting, new → old →
new (`tools/mp-bench/compare_builds.py`):

| Scene (mean ms) | new | old (6a3302d) | new, again |
|---|---:|---:|---:|
| sp | 0.715 | 0.669 | 0.735 |
| lobby | 2.123 | 2.286 | 1.504* |
| hud-heavy | 2.731 | 2.669 | 2.620 |
| per player drawn, lobby (µs) | 26.5 | 27.8 | 26.9 |

\* that run drew 18 of 40 players: the scene came up differently (both arms
alike, so not Fathom culling). The report now warns when players drawn fall
short of players loaded; compare those runs per player, not per frame.

**No code regression.** Both builds swap at ~0.24 ms (against 0.11 on 10-06)
and are within run-to-run noise of each other per player. The slowdown since
10-06 is the machine's state: GPU and CPU time from other programs (a browser,
Discord, Spotify were running). Every comparison below is interleaved within
one run for that reason.

## 2. Scenes added

Real servers send content the scripted-player scenes did not have:

| Scene | What |
|---|---|
| `holograms` | 16 NPCs, each under a 4-line hologram (64 named invisible marker armor stands) |
| `generators` | 160 dropped items on four generators (within the 16-block item render range, as a player on their island sees them), 8 armoured players fighting |
| `decor` | 40 signs, 24 mob skulls, 16 banners, 12 filled item frames |
| `tab-big` | 80 online (20 in view), 15-line sidebar, tab list held |

Every scene now clears the previous scene's props first, so none measures
another's. Skulls are mob skulls: a player head would make the client fetch a
skin from Mojang. The tail breakdown gained the player-draw parts, entity /
item / block-entity draws, tab list and chat.

## 3. Fresh baseline

Run `mp-full` (current build, 3 interleaved rounds, normal / Fathom off / drain).
1920×1080, RD 8, OptiFine M5, RTX 4060, Java 8, 4 GB heap. ms per frame.

| Scene | Median | Mean | p95 | p99 | fps (mean) | Fathom net | GPU queued at frame end |
|---|---:|---:|---:|---:|---:|---:|---:|
| sp | 0.594 | 0.678 | 1.262 | 1.598 | 1475 | +0.03 | 0.46 |
| mp-empty | 0.567 | 0.653 | 1.113 | 1.688 | 1531 | +0.03 | 0.45 |
| mp-few | 0.718 | 0.798 | 1.257 | 1.586 | 1253 | +0.04 | 0.53 |
| lobby | 1.838 | 1.896 | 2.818 | 3.320 | 527 | −0.03 | 0.84 |
| combat | 1.927 | 2.064 | 2.951 | 3.509 | 485 | +0.10 | 0.94 |
| combat-particles | 2.162 | 2.342 | 3.484 | 4.451 | 427 | +0.03 | 1.01 |
| nametags | 1.386 | 1.508 | 2.219 | 2.599 | 663 | −0.04 | 0.70 |
| hud-heavy | 2.700 | 2.714 | 3.647 | 4.250 | 369 | +0.06 | 1.00 |
| chunks | 0.831 | 0.915 | 1.437 | 1.714 | 1093 | +0.07 | 0.55 |
| holograms | 1.584 | 1.690 | 2.382 | 2.771 | 592 | **−0.46** | 0.59 |
| generators | 2.264 | 2.381 | 3.203 | 3.720 | 420 | +0.06 | **2.08** |
| decor | 1.277 | 1.359 | 1.922 | 2.224 | 736 | +0.05 | 0.80 |
| tab-big | 2.087 | 2.175 | 3.019 | 3.366 | 460 | +0.05 | 0.58 |

"Fathom net" = normal − Fathom off (positive costs, negative saves). "GPU
queued" = the drain arm's `glFinish` wait: GPU work still outstanding when the
CPU finished. It is below the CPU frame everywhere except `generators`, where
the GPU is close to being the limit.

0 frames rejected, no GC in any measured window (4 GB heap), window CoV 1–7%
except lobby (17%, players walking in and out of view).

## 4. Ranked bottlenecks

By measured cost in the scenes where each occurs. "Owner" is whose code it is.

| # | Bottleneck | Evidence | Owner | Estimated saving | Status |
|---|---|---|---|---|---|
| 1 | **Player drawing** | 26–28 µs per player (body 10, label decision 7 of which the label 5, held item 5); 54–62 µs armoured with enchants. 40 players ≈ 1.0–1.1 ms | vanilla / OptiFine | Large, but needs cross-player batching: a renderer project | Plan |
| 2 | **Tab list while held** | 0.74 ms at 80 players (glyph drawing 0.46 = 2.8 µs a name; its own work 0.29), 0.42 ms at 40 | vanilla | 0.3–0.5 ms while held (text replayed from display lists as nametags are; sort and name decoration once per change) | Plan, next |
| 3 | **Client tick in p99 frames** | Tick frames are 1.4–5% of frames and 50–95% of the p99 band; 0.6–1.0 ms a tick, 1.6–2.0 ms with particles (particle physics 1.1 ms) | vanilla | p99 only; particle physics changes simulation (rejected before) | Not attempted |
| 4 | **Dropped items** | 7 µs each: model 4.4, shadow 2.5. 160 items 1.13 ms CPU; the scene where the GPU is nearly the limit | vanilla / OptiFine | ~1 µs per item by making the shadow path allocation-free; more needs batching | Plan |
| 5 | **Labels (holograms, NPCs)** | 4.9–6.3 µs each on the fast path; 74 labels 0.47 ms. Fathom already saves 0.46 ms here | vanilla + Fathom | 1–2 µs a label by one display list per label (background and both passes) — to be measured before shipping | Plan |
| 6 | **Signs** | Wrapped and re-formatted every frame: 40–45% of render-thread allocation where signs are in view; 3.4 µs per sign | vanilla / OptiFine | **Fixed** (below) | Shipped |
| 7 | **Item frames** | 22 µs each: an `ItemEntity` constructed per frame entity per frame, the frame model emitted face by face, compass/clock sprite updates | vanilla / OptiFine | Construction **fixed** (3 µs); ~10 µs more by a display list for the frame model | Partly shipped |
| 8 | **Uniform slowdown in slow frames** | In p95+/p99+ every part of the frame (model, layers, labels, tab list, even the swap) is 1.6–1.8× its median: machine-level (clocks, scheduling, cache), not a code path | system | — | Recorded |
| 9 | **Fathom's own frame** | HUD 0.045 ms CPU (live widgets 0.02) + 0.01–0.02 ms more swap; net −0.46 to +0.10 ms across scenes | Fathom | ~0.02 ms by drawing once-a-second widgets from the cached layer | Plan |
| 10 | Packets | ≤ 0.03 ms a frame | vanilla | none | No action |
| 11 | Block entities (skulls, banners) | ~3.6 µs each | vanilla | small | No action |

Allocation: 20–36 MB/s in player scenes, 86 MB/s with signs (before the fix).
With a 4 GB heap no GC fell in a measured window; smaller heaps would see it.

## 5. What was fixed

### Sign text cache — `render.blockentity.sign-text-cache` (default on)

`SignBlockEntityRenderer.render` runs `Texts.wrapLines` and `asFormattedString`
on every line of every sign, every frame. A Flight Recorder allocation profile
of the new scenes put those two calls at 40–45% of everything the render thread
allocated. Both are pure functions of the line's `Text` and the font. Each sign
now keeps, per line, the `Text` it held, the wrapped list and the formatted
string, and returns those exact objects until the line's `Text` object changes
(a sign update or each keystroke while editing) or the font does (resource
reload, unicode toggle). Both calls survive in OptiFine M5's patched renderer
(checked in its patched bytecode); its custom sign colours and text distance
check are untouched.

### Item frame entity reuse — `render.entity.item-frame-entity-reuse` (default on)

To draw a frame's item, the renderer constructs a new `ItemEntity` around the
frame's stack every frame for every frame in view, then reads only that stack
back. One entity, given the current stack each time, gives it identical
inputs. It is dropped on every world change.

### Measured (run `mp-ab`, 4 interleaved rounds)

| Arm, `decor` scene | Paired median | Wins | p95 | p99 | Allocation |
|---|---:|---:|---:|---:|---:|
| normal (both off) | | | 2.539 | 3.336 | 78 MB/s |
| sign cache | **−0.117 ms** | 4/4 | 1.938 | 2.397 | 35 MB/s |
| frame reuse | +0.009 | 2/4 | 2.099 | 2.598 | 72 MB/s |
| both | **−0.169 ms** | 3/4 | 1.870 | 2.340 | **24 MB/s** |

Block-entity draws 0.381 → 0.246 ms (3.4 µs per sign); frame-entity draws
0.303 → 0.267 ms (3 µs per frame). `sp` and `lobby`, which have neither, moved
within their noise. Verification (`-Dfathom.verify.caches=true`): 178,000 sign
lines served-versus-original, **0 mismatches**.

## 5a. Second round: the rest of the plan (2026-10-14)

| Plan item | What was built | Correctness | Measured | Status |
|---|---|---|---|---|
| Tab list text replay (`render.hud.tab-list-text-replay`) | Each tab-list string recorded once at its position and colour and replayed; skins, icons, backgrounds, scores, sort stay live | 6,653 strings drawn both ways offscreen: **0 differing bytes** | Text drawing 0.461 → 0.138 ms (`tab-big`), 0.252 → 0.092 ms (`hud-heavy`); whole HUD −0.10 ms; allocation 35.5 → 22.5 MB/s. Whole frame **inconclusive**: `hud-heavy` −0.072 ms (3/4), `tab-big` +0.045 (2/4), with the buffer swap rising by a similar amount in `tab-big` | **On by default.** Re-run (6 rounds): faster in **6/6** in `tab-big`, paired −0.143 ms; 5/6 in `hud-heavy` |
| Item frame model list | The frame's wooden model recorded once per model | **Failed**: 1,371 of 1,371 checks differed. The check itself was validated (live against live: 0 of 282 differed), so the replay really draws different pixels -- tessellator draws captured in a display list under OptiFine | -- | **Removed** |
| Allocation-free shadows (`render.entity.shadow-allocation`) | `pos.down()` in `renderShadow` served from one mutable position | Identical input by construction | Allocation 21.9 → 16.4 MB/s in `generators`; shadow time unchanged (0.393 → 0.405 ms); whole frame unchanged | **On by default** for its allocation saving (25.0 → 20.8 MB/s in the re-run); no frame-time claim |
| Fathom HUD: slow layer re-recorded only on change | Content signatures for the slow layer instead of a 50 ms timer | -- | Layer 0.0087 → 0.0096 ms: computing the signatures cost what the skipped recordings saved | **Removed** |
| One display list per label | Not built. A diagnostic (`-Dfathom.measure.labelNoBackground=true`, draws the wrong thing) bounds what it could save; the frame-model result above is a warning that tessellator draws inside lists do not replay identically under OptiFine | -- | Quiet machine, per label: **4.9 µs** drawn normally, **3.9 µs** without the background quad (0.163 ms / 33.3 labels against 0.134 ms / 34.7). The background is ~1 µs, a fifth of a label; the rest is the two text passes. The scene came up only partly (6.7 of 16 NPCs, window CoV 47%), so only the per-label figures are used | **Not built.** A list that saved the background alone is worth ~0.03 ms at 33 labels; one replaying the text too could save more, but needs the offscreen pixel gate first because of the frame-model failure |
| Player batching, particle tick | Design documents: `design-player-batching.md`, `design-particle-tick.md` | -- | -- | Designed |

**Why "inconclusive"**: this round ran with RobloxPlayerBeta using about two cores
and the GPU (found afterwards). Windows varied by up to 27% in `sp`, and switches
that cannot affect `sp` moved it by +0.05 to +0.08 ms. The section timings and
allocation figures above are robust to that; whole-frame paired differences are
not. The re-run (6 rounds) also had the other game in the foreground: every frame was
unfocused and scenes drew only some of their players and items. The tab list is
drawn in full whatever is in view, so its 6/6 result stands; the label
measurement and any whole-frame claim for shadows need a quiet machine.

**Quiet re-test (2026-10-14, nothing else running, 5 rounds, 0 unfocused frames,
run `quiet`).** Tab list text replay off was faster in **0 of 5** rounds in both
scenes: paired +0.158 ms a frame with it off in `tab-big`, +0.097 ms in
`hud-heavy`; allocation 29.5 → 46.1 MB/s with it off. The win is confirmed.
Shadows off: `generators` paired −0.010 ms (4/5), i.e. frame-neutral, with
allocation 24.1 MB/s on against 30.2 off: kept for the allocation only.
`generators` came up in full (168 of 169 entities drawn, CoV 0.9%). `tab-big` and
`hud-heavy` drew **none** of their players in this run (see section 6), so their
figures are HUD-only evidence: valid for the tab list, not for whole-scene frame
times.

## 6. Harness changes

- `compare_builds.py`: several builds back to back, each against a fresh
  server, in an order that shows drift (A, B, A); `--jfr` for a flight
  recording per step.
- `run_client.py --jar`: a specific build. A watchdog stops the game it
  started if it has not exited a minute after writing its report (it sometimes
  hangs in sound shutdown).
- `on:` arms take several ids joined with `+`, as `off:` arms always did.
- The report warns when players drawn fall short of players loaded.
- That warning only appeared in runs with an `sp` or `mp-` scene. Several runs
  that started with a populated scene loaded its entities but drew none
  (`E: 0/81`): on the 1.8 client an entity that arrives before its chunk is
  filed in the empty placeholder chunk and never drawn. The sample-validity
  section now marks such scenes **INVALID** in every run, and `run_client.py`
  measures `mp-empty` first when a run does not start with `sp` or `mp-empty`.
  A populated scene measured straight after `mp-empty` can still come up only
  partly (bots arriving during measurement); a full run, where it comes later,
  draws every player.

## 7. Plan, in order

1. **Tab list text replay** (#2): the nametag display-list approach for tab
   entries, keyed by string, position and colour; the sort and decorated names
   kept until the player list or a team changes. Gate: pixel compare of replayed
   against live frames, as for chat; A/B in `tab-big` and `hud-heavy`.
2. **Item frame model list** (#7): the frame's block model recorded once per
   model and replayed. Gate: pixels; A/B in `decor` with more frames.
3. **Allocation-free entity shadows** (#4): mutable positions and one
   brightness lookup per column in `renderShadow`. Output-identical by
   construction; A/B in `generators`.
4. **One list per label** (#5): measure first; ship only on a paired win.
5. **Fathom HUD**: once-a-second widgets from the cached layer (#9).
6. **Player batching** (#1) and **particle physics** (#3): design documents
   before any code — the first is a renderer project, the second changes
   simulation.

## 8. Reproducing

```
python tools/mp-bench/mpbench.py prepare --accept-eula     # once
python tools/mp-bench/compare_builds.py --jar cur=minecraft/fathom-optimizations/build/libs/fathom-optimizations-0.3.0-hud.jar \
    --order cur --scenes sp,mp-empty,mp-few,lobby,combat,combat-particles,nametags,hud-heavy,chunks,holograms,generators,decor,tab-big \
    --arms normal,fathom-off,drain --rounds 3 --bots 80 --tag full
```

Reports land in `minecraft/fathom-optimizations/run-mp/logs/` as
`fathom-mp-<tag>-<label>-<n>.txt/.csv`. Close other GPU-heavy programs first, and
compare builds only within one sitting.
