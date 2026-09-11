# Current state audit

What is actually working in the game-side stack, verified end to end, before any
new optimization work begins.

**Date of audit:** 2026-09-07
**Stack audited:** Minecraft 1.8.9 · Legacy Fabric loader 0.19.3 · yarn
1.8.9+build.604 · Java 8 bytecode built on JDK 17 · `fathom-optimizations`
0.3.0-hud
**How:** the dev client was launched, driven and photographed. Static claims
were checked against bytecode with `javap` and against the source with grep.
Nothing below is inferred from a file existing.

---

## 0. A naming correction, first

The brief asks about "Fathom Core" and "Fathom Performance" as two things. **They
are one artifact.** `fathom-optimizations-0.3.0-hud.jar` contains both:

| Name in the brief | What it actually is | Where |
| --- | --- | --- |
| Fathom Core | The module framework, HUD, renderer, Hub, main menu, launcher bridge | `core/module`, `core/ui`, `core/hud`, `fabric/gfx`, `fabric/hub`, `fabric/menu` |
| Fathom Performance | The optimization registry, switch board, guard, and the Mixins that act on them | `core/registry`, `core/opt`, `core/guard`, three of the eight Mixins |

There is no separate performance jar and no separate loading step. Anywhere
below that distinguishes them, it means "which half of the one jar".

---

## 1. The fifteen checks

| # | System | Verdict | Evidence |
| --- | --- | --- | --- |
| 1 | Legacy Fabric profile launches in dev | **WORKING** | `gradle runClient` reaches the main menu; Diagnostics reports `Loader — Fabric 0.19.3` |
| 2 | Fathom Core loaded | **WORKING** | `[Fathom] 19 optimizations registered, 6 enabled, 15 features, 11 HUD widgets`; Hub opens and draws |
| 3 | Fathom Performance loaded | **PARTIAL** | The registry and switch board load. **Only 3 of 19 optimizations have a Mixin.** See §2 |
| 4 | Launcher config reaches the module | **WORKING** | `config/fathom-performance.properties` is read at startup; `LauncherConfig.apply` builds the switch board from it |
| 5 | In-game Performance settings modify the same config | **PARTIAL** | They change the live switch board and are **not written back**. See §3 |
| 6 | Every visible performance toggle maps to real code | **FIXED THIS PASS** | Was BROKEN: four toggles were inert. See §2 |
| 7 | Unimplemented options are not presented as working | **FIXED THIS PASS** | Same defect, same fix |
| 8 | HUD framework does no work when disabled | **PARTIAL** | ~165 list scans and one allocation per frame with everything off. See §4 |
| 9 | Hub does no work while closed | **WORKING** | It is a `Screen`; when closed it is not instantiated. Nothing per-frame references it |
| 10 | In-game screens retain the gameplay limit | **FIXED IN UI RECOVERY** | The inappropriate menu cap was removed; only the separate unfocused-window policy can lower it |
| 11 | UI/input timing not bound to 20 TPS | **WORKING** | `Gfx.begin` takes a `System.nanoTime` delta per frame; `Pointer.poll` reads the device per frame. No tick dependency |
| 12 | User-supplied mod materialization | **WORKING** | `mods::store::materialize` + 10 `mods::supplied` tests |
| 13 | Profile isolation | **WORKING** | `ModuleTests.oneProfileDoesNotSeeAnother` and `session::tests`; every path is under the profile's own game directory |
| 14 | Managed/custom Java selection | **WORKING** | `Launcher::resolve_java` honours Automatic/Managed/System/Custom in that order; 17 `java_runtime` tests |
| 15 | No duplicate hooks from abandoned work | **WORKING** | 8 Mixins, all live; 3 touch optimizations and no id is hooked twice. See §5 |

---

## 2. The defect this audit was for

**Four optimizations presented a working toggle and did nothing in the game.**

`render.font.batching`, `render.entity.occlusion-culling`,
`memory.world-change-gc` and `resources.pack-icon-downscale` were all marked
`implementedInCore()` — which is true, and is not the same claim. It means the
decision logic exists in `core` and has tests. The in-game Performance page gated
its switch on that fact, so all four drew a switch, and moving it changed a
boolean that nothing in the render loop reads.

This is the same class of defect the module milestone removed for modules, still
present for optimizations, because the two used different gates.

### What is actually hooked

| Optimization | Mixin | Status |
| --- | --- | --- |
| `render.font.metrics-cache` | `TextRendererWidthMixin` | Runs in game |
| `input.unfocused-fps-cap` | `MinecraftClientFrameCapMixin` | Runs in game |
| the other entries | — | Nothing calls them |

### The fix

A new fact on `Optimization`: `runsInGame()`, meaning **a Mixin in this build
calls it**. It is set by hand on exactly those three, and
`OptTests.onlyHookedOptimizationsMaySwitch` checks the count against the Mixin
list — written out rather than derived, because a derived list would agree with
the registry and what it has to agree with is `fathom.mixins.json`.

The Performance page now gates on `runsInGame()`. The other four read
**"Written, not hooked up"** where the switch was.

The launcher-side page was already honest: it gates on `Status::Active`, which
those four never claimed. Only the in-game page was wrong.

**Consequence for this milestone: Fathom Performance currently optimizes almost
nothing.** Three hooks, two of which are frame caps that reduce work by drawing
fewer frames rather than by making a frame cheaper. Only
`render.font.metrics-cache` makes a rendered frame faster. Any benchmark
comparing "Fathom Performance on" against "off" is today comparing a font-width
cache and two caps.

---

## 3. In-game Performance changes do not persist

Toggling an optimization in the Hub calls `SwitchBoard.setByUser`, which changes
the live board. Nothing writes it back: `FathomHud.saveAll` persists the HUD
layout and the module store, and the optimization switches are not in either.

This is half deliberate. The launcher owns those settings — the config bridge is
documented as one-way, written before launch and never read back — so a game-side
write would be overwritten on the next launch anyway.

It is still **PARTIAL**, because the page does not say so. A switch that works
until you restart and then silently reverts is a smaller version of the same
dishonesty as a switch that never worked. Either the page states it, or the
setting round-trips.

**Not fixed in this pass.** It is an interface change, and this milestone is
under instruction not to do interface work. Recorded as a known defect.

---

## 4. What Fathom costs with everything off

Per rendered frame, with no HUD widget enabled and the Hub closed:

| Work | Cost |
| --- | --- |
| `MinecraftClientFrameMixin` | Two `System.nanoTime()` calls and a null check |
| `MinecraftClientFrameCapMixin` | One `getMaxFramerate` call through `FrameCapPolicy.effective` — a few comparisons |
| `FathomHud.render` | `new Window(client)` (one allocation), then for each of 11 widgets: a map lookup plus `widgetAllowed`, which is a linear scan of 15 modules each doing a `List.contains` |
| `Widgets.frame()` | One `nanoTime`, three doubles |
| `TextRendererWidthMixin` | Only when text is drawn; a map lookup replacing a switch |

The HUD loop is the only part that is not trivially cheap: roughly 165 list
probes and one allocation per frame even when nothing is enabled. In absolute
terms that is microseconds, but it is per frame, it allocates, and it is exactly
the kind of thing this milestone exists to measure rather than estimate.

**Deliberately not optimized before it is measured.** Guessing at it would be the
same mistake as promoting an optimization on a microbenchmark.

---

## 5. Mixins in this build

| Mixin | Target | Purpose | Optimization |
| --- | --- | --- | --- |
| `MinecraftClientFrameMixin` | `runGameLoop` | Frame timing plus frame-rate Hub hotkey edge | — |
| `MinecraftClientFrameCapMixin` | `getMaxFramerate` | Preserve the user cap; optional unfocused cap | `input.unfocused-fps-cap` |
| `GameMenuScreenMixin` | pause-menu buttons | Adds a Fathom entry | — |
| `MinecraftClientMenuMixin` | `setScreen` | Fathom's main menu | — |
| `InGameHudMixin` | `render` | Draws the Fathom HUD | — |
| `InGameHudCrosshairMixin` | `showCrosshair` | Hides vanilla's crosshair | — |
| `GameRendererFovMixin` | `getFov` | Zoom | — |
| `TextRendererWidthMixin` | `getStringWidth` | Font metrics cache | `render.font.metrics-cache` |

No id is hooked twice. No Mixin targets a method another Mixin also rewrites.
There are no leftovers from abandoned implementations — the eight above are all
reachable and all referenced from `fathom.mixins.json`, which has
`defaultRequire: 1`, so a Mixin that failed to apply would crash at class load
rather than going quiet.

---

## 6. Defects found and fixed in this pass

### 6.1 Closing any screen at the main menu dropped you on vanilla's title screen

`setScreen(null)` does not mean "no screen" when there is no world. Vanilla
substitutes a `TitleScreen` **inside the method body**, at bytecode offset 25 —
after the `HEAD` injection point `MinecraftClientMenuMixin` used. So the Mixin
was handed a null, never saw a `TitleScreen`, and the game landed on the grey
vanilla menu.

Observed, not reasoned about: pressing the Fathom button opened the Hub,
something closed it a frame later, and the next screenshot was vanilla's title
screen with a different splash.

**Fixed:** the Mixin now replaces the null itself when there is no world, which
pre-empts vanilla's substitution — its `if (screen == null && world == null)` is
simply no longer true. One line, and it covers every caller rather than the one
that was caught.

### 6.2 The main menu's profile pill overlapped the Singleplayer button

The branding block was laid out downward from a guessed offset above the button
column. At 1280×720 the pill landed on top of the first button.

**Fixed:** the block is now laid out upward from the column with its own measured
height, so the gap is fixed at every window size.

### 6.3 Four inert optimization toggles

§2.

---

## 7. A measurement hazard found while auditing

Driving the client with synthetic input loses window focus between actions. With
`input.unfocused-fps-cap` on, that engages the 30 fps unfocused cap, and every
frame-rate number captured that way is 30 regardless of what the machine can do.

Two Diagnostics readings from the same session make the point: the world alone
showed **200 fps**, and the Hub over the same world showed **30 fps** — which is
the unfocused cap, not the Hub's cost.

**Consequence for the harness: a benchmark run must assert the window is focused
for every sample, and discard the run if it is not.** Without that, every number
is the unfocused cap and every comparison is meaningless. This is written into
the harness rather than left as advice.

---

## 8. Numbers observed while auditing

Not benchmarks. Single readings, on one machine, at 1280×720 with a GUI scale of
3, captured through Diagnostics. They are here because they set expectations for
the harness, not because they are results.

| Reading | Value |
| --- | --- |
| Fathom Hub, main menu, draw cost | 1.01 ms mean, p99 1.13 ms |
| Fathom Hub, in world, draw cost | 0.44 ms mean, p99 0.70 ms |
| Hub geometry | 1092 quads in 35 draw calls |
| World, no screen, focused | 200 fps |
| Heap, main menu | 142 MB of 4042 MB |
| Heap, in world | 356 MB of 4042 MB |

Machine: i5-12400F, RTX 4060, Windows 11, JDK 17 running the game.

---

## 9. What this means for the milestone

1. **Do not benchmark "Fathom Performance on versus off" as though it were a
   renderer.** Three hooks, one of which affects frame cost. The honest
   comparison for this milestone is *Fathom's overhead*, not Fathom's benefit.
2. **The overhead question is the whole question.** Configuration D — Legacy
   Fabric + Fathom with everything optional off — against configuration B —
   Legacy Fabric alone — is the number that decides whether more optimizations
   may be written. §4 lists what that overhead is made of.
3. **The profiler has to attribute cost, not just report it.** "Fathom draw
   0.44 ms" is already measured; what is not is the HUD loop, the module
   dispatch, and the per-frame allocation, each separately.
