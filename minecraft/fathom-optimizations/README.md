# Fathom client (Minecraft module)

The in-game half of Fathom for Minecraft 1.8.9: the hub on Right Shift, the HUD
and its editor, the custom main menu, the modules, and the performance work. It
is a Legacy Fabric mod, and the same build is renamed for Forge (see below).

The launcher installs it into every Fathom profile. It also works on its own as
an ordinary mod, without the launcher; it then uses its defaults.

## Layout

```
src/main/java/net/fathom/optimizations/
  core/      Everything with no game dependency: the optimization registry and
             its evidence rules, modules and settings, HUD layout, profiles,
             the UI toolkit's maths, instrumentation. Tested with a plain JDK.
  fabric/    Everything that needs the game: the entry point and Mixins, the
             renderer (gfx/), the hub (hub/), the HUD (hud/), the main menu,
             and the link to the launcher (bridge/).
  forge/     The few Mixins only Forge needs (start-up, its HUD, its sky blend).
```

`core` holding all the logic is deliberate: it can be checked without starting
Minecraft. What needs the game (Mixins applying, drawing, input) is checked in
the game, by the self-tests below.

## Building

```powershell
gradle build
```

That compiles and remaps the Fabric jar, builds the Forge jar from it, and
runs the core test suites (`gradle coreTest` runs them alone). Output lands in
`build/libs/`:

- `fathom-optimizations-<version>.jar` for Legacy Fabric
- `fathom-forge-<version>.jar` for Forge

Gradle needs JDK 17; the mod itself is compiled for Java 8 with a toolchain.

`gradle runClient` starts a development copy of the game with the mod loaded.
It has no account, so it can only reach singleplayer.

### How the Forge jar is made

Nothing is rewritten for Forge. The `forgeJar` task renames every reference in
the Fabric jar from Legacy Fabric's intermediary names to Forge's SRG names
(using tiny-remapper, the tool Loom itself uses), rewrites the Mixin reference
map to match, and bundles Mixin, MixinExtras and ASM 9, which Forge 1.8.9 lacks.
The launcher puts that jar first on the class path and starts Mixin's
LaunchWrapper tweaker. The details and caveats are in [docs/forge.md](../../docs/forge.md).

## Rules the code enforces

- **A switch has to do something.** Every module names what it drives (a HUD
  widget, an optimization, or a behaviour hook), and `ModuleTests` fails the
  build for one that drives nothing.
- **Nothing is on by default until it has been measured in the game.** A
  registry entry needs in-game evidence before `defaultOnWhenMeasured()` takes
  effect; a microbenchmark is not enough. Only "Safe" entries can default on,
  and presets never include experimental ones.
- **Optimizations must apply or fail loudly.** `fathom.mixins.json` requires
  every injection, so a Mixin that misses its target stops the game at start-up
  instead of half-applying.
- **Caches prove themselves.** Output-identical caches have a verify mode
  (`-Dfathom.verify.caches=true`) that recomputes and compares every answer.

## Testing in the game

Two self-tests drive the real client:

- `gradle runClient -PqaModules=run -PprofilesDir=<folder>`, then
  `-PqaModules=verify` with the same folder: joins a world, switches every
  module and checks what it drew, then checks that settings survive a restart.
- `-Dfathom.uiTour=menu,hub:Mods,world,hud` saves screenshots of each screen
  to `screenshots/`.

Performance measurement is covered in
[benchmark-methodology.md](../../docs/performance/benchmark-methodology.md).

## Launcher settings

Before each launch the launcher writes
`<game directory>/config/fathom-performance.properties` with the profile's
optimization switches. A missing or unreadable file just means defaults; the
mod never refuses to load over it. The full contract is in
[performance-module.md](../../docs/performance-module.md).

## Third-party code

No code has been taken from Patcher, PolyPatcher, OptiFine or other
restrictive-licence projects. Where one of them pointed at a problem, the
registry records that as the basis and the fix is our own. The Forge jar's
bundled libraries are listed in
[THIRD-PARTY-NOTICES.txt](forge/THIRD-PARTY-NOTICES.txt).
