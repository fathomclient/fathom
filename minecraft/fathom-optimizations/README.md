# Fathom Optimizations

The Minecraft-side half of Fathom: instrumentation, a benchmark framework, and the
first batch of implemented optimizations for Minecraft 1.8.9 on Legacy Fabric.

A separate module from the Rust launcher on purpose — different language, different
toolchain, different release cadence, and a licensing question the launcher does not
have. The launcher treats it as a versioned component and pins the version in
`crates/launcher-core/src/mods/catalog.rs`.

---

## What is verified and what is not

This is the first thing to know about this module, because the split runs through
every file.

| Part | Status |
|---|---|
| `src/main/java/net/fathom/optimizations/core/**` | **Compiled and tested.** 196 assertions across `CoreTests`, `OptTests`, `ConfigTests`, `FeatureTests`, `HudTests`, `UiTests` and `ModuleTests`, run with a bare JDK. No Minecraft or Fabric dependencies at all. |
| `src/main/java/net/fathom/optimizations/fabric/**` | **Compiled and remapped** by `gradle build`. The renderer beneath it was run in the game, driven and photographed; the interface **rebuilt on top of it has not been** — see "What has not been run" below. |
| `src/main/resources/*.json` | Loaded. `injectors.defaultRequire` is 1, so a Mixin that fails to apply crashes at class load rather than going quiet. |
| `build.gradle` | **Executed.** Loom resolves 1.8.9, the Legacy Fabric intermediary and yarn 1.8.9+build.604. |

The split is deliberate rather than a consequence of running out of time.
**Everything carrying logic lives in `core` and has no game dependency**, which is
what makes it verifiable without Minecraft. What assertions cannot reach is what
needs the game to exist: layout in a real GL context, Mixin application, and how
1.8.9 delivers input — which is checked by opening the client and using it.

### What has been run in the game

The hub and the module system were both built without opening the game, then
opened and used: 1280x720, GUI scale 3, singleplayer, a creative world.

Seen working: the hub and its Mods and HUD pages; the full-page module settings built from
the setting schema, including the stepper and both dropdowns; a Format change
that altered the FPS readout on the next frame; module toggles for Clock, CPS,
Keystrokes, Sprint and Crosshair, each of which drew its widget immediately;
Ping on and correctly silent in singleplayer with "Only on a multiplayer server"
on its row; keybind rebinding; **Zoom**, which narrowed the field of view; the
**crosshair Mixin**, which hid vanilla's and drew ours; the HUD editor with its
safe-area bounds, selection outline and inspector; and the settings file written
on close.

Also confirmed: a HUD layout written by an older build, carrying
`widget.*.enabled=false` for nine widgets, was read and those lines discarded —
no widget was wrongly hidden, and the file rewritten on close no longer contains
them.

Two defects were found this way and fixed:

- the former settings drawer's close button cleared `open` in the middle of drawing from
  it, which crashed the render call. Detail navigation now keeps the module alive through
  its dedicated exit transition and clears it only after the frame.
  The crash was in code the hub milestone shipped; it took a click on that
  particular control to reach it;
- the inspector's two new reset buttons drew outside the panel, which is
  arithmetic no test can see.

What has still **not** been seen: any resolution other than 1280x720, multiplayer
(so Ping's number and colour-by-quality are unobserved), the Armour and Potion
widgets with real armour and effects, and mouse-button keybinds — the rebinding
control took a mouse press correctly in unit tests but was only exercised with
keys in game.

What stands in for that, and what does not:

- The arithmetic under the layout — rectangles, the scale, text fitting, the
  animation clock, the frame-cap policy, the session naming, the interface-scale
  preference — is covered by 160 assertions that run with a bare JDK.
- The drawing is not. A control drawn in the wrong place, a colour that reads
  badly over the game, or a popover that opens off-screen would not be caught by
  any of it.

**A registry entry is off by default until it has an in-game measurement.**
`docs/performance/optimization-registry.md` lists which ones have one.
Client *features* — the hub, the HUD widgets, the frame caps — are a different thing
from registry optimizations and have their own defaults, in `core/feature`.

---

## The launcher config bridge

`core/config/LauncherConfig` reads the file Fathom writes before every launch:

```
<game directory>/config/fathom-performance.properties
```

The launcher owns the settings UI — it knows the profile, it has the screen
space, and it can say what an optimization costs before you enable it. This side
needs the answers, not a second settings screen.

`java.util.Properties` rather than JSON, so that `core` keeps its defining
property: no dependencies outside the JDK. A JSON library would be one more
thing every user must have on the classpath for the mod to load.

It never fails a launch. A missing file is the normal case for someone running
the mod without Fathom; a malformed one produces registry defaults and a list of
complaints for the caller to log. See `docs/performance-module.md` §3 for the
full contract, which is asserted from both sides.

**Not yet wired into `FathomOptimizationsClient`** — that happens when the Fabric
layer can first be compiled.

---

## Building and testing without Gradle

Everything under `core` compiles and runs with nothing but a JDK 8.

```bash
javac -encoding UTF-8 -d build/classes $(find src/main/java/net/fathom/optimizations/core -name '*.java')
javac -encoding UTF-8 -cp build/classes -d build/classes $(find src/test/java -name '*.java')
java -cp build/classes net.fathom.optimizations.core.CoreTests
java -cp build/classes net.fathom.optimizations.core.OptTests
java -cp build/classes net.fathom.optimizations.core.ConfigTests
java -cp build/classes net.fathom.optimizations.core.FeatureTests
java -cp build/classes net.fathom.optimizations.core.HudTests
java -cp build/classes net.fathom.optimizations.core.UiTests
```

Or, scripted, which also packages the jar:

```bash
powershell -ExecutionPolicy Bypass -File ../../tools/build-optimization-module.ps1
```

`-encoding UTF-8` is not optional. The sources contain the section sign that
introduces Minecraft's formatting codes, and `javac` otherwise reads them in the
platform codepage and fails. Gradle sets it too, in `build.gradle`.

Expected: `40`, `34`, `9`, `28`, `26` and `23` passed, none failed.

### Running the benchmarks

```bash
java -Xms2048M -Xmx2048M -cp build/classes net.fathom.optimizations.bench.TextMetricsBench
java -Dfathom.bench.liveMb=512 -Xms2048M -Xmx2048M -XX:+UseG1GC -cp build/classes net.fathom.optimizations.bench.ExplicitGcBench "a label"
```

Numbers will differ on other hardware. The comparisons should not.

### Under Gradle

`gradle build` compiles the `fabric` half against Minecraft, remaps the Mixins and
produces the jar the launcher ships. It does **not** run these assertions: they are
plain `main` methods rather than JUnit tests, deliberately, so that `core` stays
checkable with nothing but a JDK — Gradle's `test` task finds no JUnit tests and
reports zero, which is expected and not a passing run. Run the five above.

`gradle runClient` starts a real, mod-loaded 1.8.9 for using the hub by hand.

---

## Layout

```
core/
  registry/     What an optimization is, what class it is in, whether it is on,
                and the evidence gate that decides
  instrument/   Frame timing, counters, memory sampling, GC readings, reports
  bench/        Scenario definitions; micro/ holds the comparison harness
  experiment/   JVM questions we refuse to answer from folk wisdom
  guard/        Fall back to vanilla when an optimization misbehaves
  opt/          The optimizations themselves
    text/       Width measurement, metrics caching, quad batching
    gc/         Explicit-collection policy
    resource/   Pack icon sizing
    entity/     The refusal rules for entity culling
  feature/      What a client feature is: settings, keybinds, widgets, the
                registry that holds them and the file they persist to
  hud/          Widget placement, layouts, snapping
  input/        The rule for which events belong to a screen that just opened
  ui/           The interface layer that needs no game: the scale, rectangles,
                the animation clock, easing, text fitting, and the Renderer
                interface everything above the backend draws through
fabric/         Entry point and Mixins, plus everything that needs the game:
  gfx/          The renderer: batching, glyph and icon atlases, coverage
                shapes, the frame-polled mouse, the spike screen
  ui/           Fathom's tokens and its eleven controls (Skin, Controls)
  hub/          The Fathom Hub — the screen Right Shift opens
  hud/          The HUD renderer and its editor
  bridge/       The loopback link to the launcher
```

### Input in 1.8.9 has two rules, and both were learned the hard way

**One key, one owner.** `MinecraftClientKeyMixin` opens *and* closes the hub. It
used to open there and close in the screen's own `keyPressed`, and a single press
could reach both — opening the hub and closing it inside one tick.

**The mouse does not come from Minecraft at all.** `Screen.mouseClicked` and friends
are delivered from the game's 20 Hz tick, which is up to 50 ms of latency on every
hover and every click, and they hand a screen the events that were queued before it
existed. Fathom's screens override them to do nothing and poll the device once per
rendered frame instead (`fabric/gfx/Pointer`), turning presses into edges. Hover and
clicks now run at frame rate, and a button that was already held when a screen opened
cannot be seen as a press, because there is no edge.

The keyboard still arrives the vanilla way: character input needs the event queue's
translation, and 50 ms on a keystroke is not what anyone means by input lag.

### `core` has no dependencies, including on the JDK's optional parts

`MemorySampler` reaches for `com.sun.management.ThreadMXBean` reflectively and falls
back to inference when it is absent, marking the result as estimated rather than
measured. Nothing else touches anything outside Java SE 8.

---

## The rules this module enforces in code

Three, each of which would otherwise be a note somebody has to remember.

**A visible toggle changes the game.** A module declares what it drives — a HUD
widget, an optimization, or a hook by name — and `ModuleTests` fails the build for
any module that is switchable and drives none of them. Anything not implemented is
`Implementation.UNAVAILABLE`, which the interface draws **with no toggle at all**.
There is no state for "declared but not wired": see `docs/modules.md` §1.

**A widget has one switch, and it is its module's.** `Placement` carries no
`enabled` flag. It used to, the shipped layout said disabled for nine of eleven
widgets, and a module toggle therefore only *permitted* its widget while the
layout kept it hidden — which looked exactly like a broken switch and was one. A
reflection test asserts the flag has not come back.

**Nothing is on until it has been measured in the game.**

```java
public boolean defaultEnabled() {
    return measuredInGame() && defaultOnWhenMeasured;
}
```

`Evidence.Level.ISOLATED` — a microbenchmark, a draw-call count, a synthetic JVM
workload — does not satisfy `measuredInGame()`. A test asserts that no entry claims
in-game evidence, so a change that promotes something on the strength of a
microbenchmark fails rather than shipping.

**Only Safe optimizations may ever default on.** Optional and Experimental entries
are the user's choice whatever the numbers say, and the constructor throws if an
entry claims otherwise.

**No preset includes anything Experimental.** Experimental means hardware-, driver- or
server-dependent, and a preset is a choice made without knowing the user's machine.

**Every optimization mixin must set `require = 1` on its own injectors.**
`fathom.mixins.json` sets `defaultRequire: 0`, which is right for instrumentation
— losing a frame-time counter should not stop someone playing — and wrong for an
optimization: a half-applied optimization leaves the game in a state neither we
nor Mojang designed, and the guard in `core/guard` cannot catch a mixin that
never applied. See `THREAT_MODEL.md` §13.

---

## Source discipline

No source has been read from Patcher, PolyPatcher, OptiFine, MemoryFix or any other
restricted-licence project. `mod-policies.md` §3 establishes that Patcher and
PolyPatcher are CC BY-NC-SA and their code is not reusable by us.

Where one of them told us a problem exists, the registry entry records
`Basis.THIRD_PARTY_PROBLEM_STATEMENT` and the implementation is our own. Knowing
*that a problem exists* is a fact about Minecraft; knowing *how someone else solved
it* is their work.

---

## Documentation

- `docs/ingame-ui-renderer-decision.md` — how the in-game renderer was chosen, and
  what the seven rejected options would have cost
- `docs/performance/ingame-ui.md` — what that renderer costs, measured in the client
- `docs/performance/benchmarks.md` — every measurement, with its limitations
- `docs/performance/optimization-registry.md` — the register, generated from this code
- `docs/modules.md` — every module, what it drives, its settings, and the toggle
  validation table
- `docs/integration.md` — what crosses between the launcher and the game: the
  session descriptor, the main menu, the OptiFine flow, and what Fathom stands
  down for it
- `docs/performance/benchmark-methodology.md` — how anything gets measured
- `docs/research/performance.md` — the analysis behind the registry
