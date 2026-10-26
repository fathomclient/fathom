# Comparison follow-up (Fathom vs Lunar)

Running checklist for the work that came out of the manual comparison PDF.
Symptoms in the PDF are treated as evidence to investigate, not as proof of a
cause. Every item below says what was **read in code / bytecode**, what was
**changed**, and what is still **unverified at runtime**.

Nothing here has been run in the game. Compilation and the core test suites are
the only checks; the manual checklist at the end is what remains.

> The PDF itself could not be opened from the agent's environment (no PDF
> renderer available), so none of the screenshots were examined. The tasks were
> worked from the written descriptions and the code. Items 2 (weather) and 4
> (TNT / sky) are the ones most likely to need the screenshots.

Names below are the yarn names the Legacy Fabric build uses. Shared Mixins
apply to both loaders (Forge gets them through `forgeJar`'s remap), so every
hook below is shared.

## Task 1 - Rendering correctness

### 1. Freelook chunk visibility - fixed (compiled, not run)

- **Cause (bytecode of `WorldRenderer.setupTerrain`)**: the visible-chunk list is
  rebuilt, and newly reached chunks queued, only when `needsTerrainUpdate` is
  set or the camera entity's position or its `yaw`/`pitch` differ from
  `lastCameraYaw`/`lastCameraPitch`. Freelook gives `transformCamera` the free
  angles and puts the player's own back straight after, so by the time
  `setupTerrain` runs the entity has not turned and the test never fires.
  The frustum (built from the GL matrices) had moved; the list had not.
- **Change**: `fabric/mixin/WorldRendererFreelookMixin` lends the free angles to
  `setupTerrain` for the length of the call (same swap as the camera setup,
  `Freelook.beginCamera/endCamera`), restored on every return. Nothing is
  rebuilt per frame; the existing angle test now fires exactly when the camera
  really turned. Freelook start/stop calls `scheduleTerrainUpdate()` once
  (`Freelook.consumeTerrainDirty`), because the third-person camera moves while
  the angles do not.
- **Safety**: `Freelook.beginCamera` restores any stale swap first and
  `Freelook.frame` restores one at the start of every frame, so a render pass
  that throws cannot leave camera angles on the player (and so in packets).
- Player rotation, movement, aim and packets are untouched: the swap only
  exists inside render calls.
- **Code paths**: `fabric/input/Freelook.java`, `GameRendererFreelookMixin`
  (camera), `WorldRendererFreelookMixin` (terrain), `fathom.mixins.json`.
- **Uncertain**: OptiFine rewrites `setupTerrain` but keeps the method and its
  descriptor (the existing `WorldRendererDirectionsMixin` skip note says so); the
  new hook is HEAD/RETURN only and uses the public `scheduleTerrainUpdate()`, no
  `@Shadow`. Not launched with OptiFine/OptiFabric.

### 2. Time / weather / custom sky - time fixed, weather not changed

- **Time - cause**: Time Changer only answered `World.getSkyAngle`. OptiFine's
  custom sky (`net.optifine.CustomSky.renderSky`, `CustomSkyLayer.isActive` and
  `.render`, read from the bundled OptiFine HD U M5 jar) takes the time of day
  from `World.getTimeOfDay()` for layer fade windows, `days` ranges and layer
  rotation. Those followed the real clock while the sun followed the override.
- **Change**: `WorldSkyAngleMixin` also answers the client world's
  `getTimeOfDay()` while Time Changer is on, as
  `Lighting.visualTimeOfDay(real, chosenTick)` = real day number x 24000 + the
  chosen tick, so day-based things (moon phase, pack day ranges, the F3 day)
  keep following the real world and the override does not drift as the real
  clock advances. Level properties are untouched, so the integrated server's
  world, its time, and everything sent to a server are unchanged. Off, or in
  another world, the mixin does nothing (stateless; nothing to restore).
- **Test**: `BaselineTests.fullbrightNeverDimsAndTimeChangerShowsTheChosenHour`
  (day kept, no drift, negative times). `ModuleSelfTest` case 48 now checks
  that the level properties still hold the real time and that
  `getTimeOfDay()` equals the visual time (it used to assert `getTimeOfDay()`
  was the real one, which is exactly what is changing).
- **Weather - what was read**: `WorldWeatherMixin` overrides the client world's
  `getRainGradient`/`getThunderGradient`; every consumer found in the game and
  in OptiFine's `CustomSky` goes through those two methods (no direct field
  reads outside `World`). OptiFine's `CustomSky.renderSky` divides thunder by
  rain and `CustomSkyLayer.getWeatherBrightness(rain, thunder)` weighs
  `1-rain` / `rain-thunder/rain` / `thunder/rain` by each layer's `weather`
  list (clear by default). The override is a consistent (rain, thunder) pair
  with thunder <= rain in every mode. `ForgeSkyBlend` (the cached biome sky
  blend) depends on biome data only, not time or weather, so it is not a
  factor.
- **Weather - not changed**: nothing in the code explains a jump "above roughly
  60%". The arithmetic is continuous in intensity, so a threshold would have to
  come from the pack's own layers (`weather`, `blend`) or from the screenshots.
  No speculative change was made. To resolve it: note the pack, the layer
  `.properties` for the layer that changes, the weather mode and the intensity
  at which it flips.

### 3. Nametags at steep angles - fixed (compiled, not run)

- **Cause 1 (freelook)**: every billboard (vanilla and Fathom nametags, Teamview
  markers, Waypoint labels) rotates by `EntityRenderDispatcher.yaw/pitch`,
  which `updateCamera` reads from the camera entity, which freelook has already
  turned back to the player's angles. Labels faced where the player looks while
  the screen showed elsewhere, edge-on when the two differ a lot (worst with a
  steep free-camera pitch).
- **Cause 2 (front-facing third person)**: `updateCamera` adds 180 to the yaw
  for perspective 2 but does not negate the pitch, while the camera matrix
  (`R_y(180) R_x(p)` = `R_x(-p) R_y(180)`) looks back at the player with the
  opposite pitch. Verified in the bytecode of both methods. Later versions
  negate it.
- **Change**: `fabric/mixin/EntityRenderDispatcherFreelookMixin` lends the free
  angles to `updateCamera` and negates `pitch` for perspective 2 afterwards.
  The billboard transform, visibility checks (vanilla's team/invisibility/
  distance rules) and depth handling (see-through pass without depth test,
  solid pass with it) are unchanged, so occlusion rules are as before.
- **Uncertain**: normal first/back third person is the vanilla transform and was
  not found to be wrong; if tags still vanish there, it needs a repro.

### 4. TNT lighting / sky artifacts - one real leak fixed, rest undiagnosed

- **Read**: the TNT countdown label (`TntEntityRendererCountdownMixin` ->
  `renderLabelIfPresent`) goes through `Nametags.render` or
  `NametagRenderer.render`; both follow vanilla's sequence (matrix, normal,
  lighting, depth mask, blend) and end with lighting enabled, blend disabled,
  white colour and the caller's texture. Display-list replays re-sync texture,
  colour and alpha test. No leak found there. `Hitboxes`, `BlockOutline` and
  `ItemPhysics` restore what they change, and their early returns happen
  before any state is touched.
- **Found and fixed**: `Waypoints.render` and `Teamview.render` (run just
  before the hand, via `GameRendererWorldOverlayMixin`) finished with
  `GlStateManager.enableFog()`. Vanilla's world pass ends with `disableFog()`
  right before that point (bytecode of `GameRenderer.renderWorld`), and nothing
  between there and the HUD turns it off, so with either module drawing, fog
  stayed enabled for the hand and the HUD. Both now end with fog disabled (as
  found), and the drawing is in `try/finally` so an exception cannot leave
  the matrix pushed or the state altered. No GL queries were added.
- **Not diagnosed**: the specific TNT lighting and sky screenshots could not
  be examined, and no further cause is supported by the code. Needs a runtime
  repro (which modules on, OptiFine or not, loader). Suggested first check:
  the existing `GlStateAudit` around the nametag and overlay hooks, with
  Waypoints/Teamview/TNT Countdown on one at a time.

## Checks run

See the task report for the exact commands. Compilation and the core suites
only; no game, launcher or server was started.

## Manual checklist (needs the game)

1. Freelook: hold the key and turn through 360 degrees and up/down; terrain
   behind and beside you fills in without moving the player; release; look at
   a fresh area; repeat in Toggle mode. Confirm the player did not turn.
2. Time Changer on (several hours) with an OptiFine custom sky pack: layers
   stay on their hour; wait through a few minutes; off restores real time.
3. Weather Changer 10..100% with the same pack: note any layer that changes
   and at what intensity (see task 2).
4. Nametags: look straight up/down at a player in first person, back and
   front third person, and in freelook; tag stays above the head, readable,
   and hidden behind walls as before.
5. Waypoints/Teamview on: HUD and hand not fogged; TNT with countdown on/off:
   compare lighting; report anything that remains with the modules involved.

## Task 2 - Two reported stalls (source inspection only)

Nothing was run: no game, benchmark, server or profiler. Proven below means
"shown by reading the code", not "measured".

### Proven, and changed

- **Interface font atlas decoded on the render thread.** `SdfText.create()`
  (run from `SdfRenderer.beginFrame` the first time the compositor is used)
  parsed the 507 KB `ui.atlas`, ImageIO-decoded the 1.28 MB `ui.png`
  (1024x896), copied 917,504 pixels through a per-pixel `put` loop, then
  uploaded. It runs again after every resource reload, because the reload hook
  (`FathomOptimizationsClient.installRendererReloadHook`) calls `Gfx.dispose()`
  and the next HUD or screen frame recreates the compositor. A reload also
  happens when a server applies its resource pack. All of that was
  synchronous, on the thread that owns the frame.
  **Change:** the read and decode now run on a new low-priority daemon thread
  (`gfx/AssetPrep`), started at client init (`Gfx.prepareAssets`) and again
  from the reload hook when the compositor was in use. `SdfText.create()`
  takes the finished result and only does the GL upload (`glGenTextures`,
  `glTexImage2D`) on the render thread; if nothing was prepared, or the
  prepare failed, it reads inline exactly as before, with the same
  exceptions. The pixel buffer is consumed once and not retained. A scratch
  check confirmed `read()` returns 1024x896, 3,670,016 bytes, direct, and that
  the async path returns a result; the GL upload was not run.
- **Not changed, by design:** shader compile and every GL upload stay on the
  render thread; the atlas is not kept around between reloads.

### Established not to be the cause (as far as the code shows)

- **World-change `System.gc()`.** The launcher passes `-XX:+DisableExplicitGC`
  by default (`jvm_args.rs`, behind the profile's `disable_explicit_gc`
  toggle). Where that flag is absent, the in-game policy
  (`WorldChangeGc`) still lets the first world change of a run collect. That
  is a candidate for "first match after launch" only on profiles that turned
  the flag off; it already logs its own duration (`World-change GC ... took`).
- **Fathom's combat code.** The attack path is `ClientInteractionParticlesMixin`
  -> `HitParticles`, `KeyBindingPressMixin` -> click tracker and the crosshair
  flash, the hurt-tint and hurt-camera readers, and `ClientPlayerEntityParticlesMixin`.
  Each is a few field reads or a module lookup; none touches disk, textures,
  fonts or sounds. Fathom owns no sound code. No cache was found that expires
  on idle time (nametag names, model lists, text lists and HUD caches are
  invalidated by content, not by a clock).

### Hypotheses (not proven)

- First match after launch: the first-use cost of anything not yet warmed in the
  world (a HUD widget's first draw, an icon size's first raster in
  `Icons.rasterise`, a first nametag list), or a resource reload from a server
  pack. The atlas change above removes the largest synchronous piece that is
  demonstrable; the rest needs timing.
- 50 ms on the first hit after a quiet spell: nothing in Fathom's code explains
  it. Cold vanilla paths (class loading and JIT for the damage and hurt
  animation path, loading the hit sound the first time it plays, first
  particle use) are the usual cause of this pattern, but that is not shown here.

### Diagnostics (off by default, not run)

`-Dfathom.diag.hitches=true` enables `diag/Hitches`. Off, `ENABLED` is a
`static final` false, so each call site folds away and `begin()` is a
constant zero. On, it times the compositor creation (and its shader and atlas
steps), the HUD pass, the per-frame listeners, a world or player change, the
resource-reload hook, an attack, glyph-page and icon rasterising, and nametag
list recording. It logs a section only above `-Dfathom.diag.hitches.ms`
(default 8), and one line for a frame above `-Dfathom.diag.hitches.frame.ms`
(default 40) naming the longest timed section in it, at most 60 lines per run.
A long frame with "longest Fathom section: none" points away from Fathom's
code. Lines carry section names and durations only.

To use it: start with the flag, join the first match, hit someone after a quiet
spell, and read the `[Fathom hitch]` lines. `World-change GC` lines are logged
regardless.

## Task 3 - Configuration interaction and HUD editor usability

Compiled and unit-tested only (`InteractionTests`, 522 checks, plus the
existing hud, profile, feature, editor, module, config, core, ui, baseline,
registry and view suites). The UI was not launched; every item below needs the
manual pass at the end.

1. **Scrollbars.** `hub/Scroller.rail` already dragged its thumb. The Modules
   grid drew its own display-only bar (`ModsSection.graphiteScrollbar`); it now
   uses the shared rail, so every visible scrollbar drags. The rail also pages
   on a press on its strip above or below the thumb (one viewport less 10%
   towards the pointer, and the press is consumed). The arithmetic is in
   `core/ui/layout/ScrollMath` (thumb size and place, drag inverse, paging,
   wheel share). Offsets were already clamped on read each frame; `wheel` now
   clamps at once too, so content, panel or filter changes cannot leave a stale
   offset.
2. **Nested scrolling.** The Performance page had one scroll area (the engine
   list) inside a fixed page, so the part above it could not be reached in a
   short window. It now has an outer page scroller (`pageScroller`) and an
   inner list scroller. The list gets the page's remaining height but never
   less than `LIST_MIN` (seven rows plus the heading); below that the page
   scrolls. `Section.scroll(notches, x, y)` is new (default calls the old
   method); `FathomHubScreen` passes the pointer, `Scroller.wheel` returns the
   notches it could not use, and Performance gives the wheel to the list under
   the pointer, then the leftover (and everything over the rest of the page) to
   the page. The outer clip is applied to the whole page, so the existing
   clip-aware hit testing (`Controls.pointerOverVisible`) applies at every GUI
   scale. Other pages have one scroll area and are unchanged.
3. **Live preview.** The four modules with drawings of their own (crosshair,
   keystrokes, armour, potions) were already drawn live from the module's
   current values. Others had none, and the HUD is not drawn under the Hub. Now,
   with Gameplay glass on, in a world, and a module page open that has widgets
   and no drawing of its own, the Hub calls `FathomHud.previewUnderHub`, and the
   ordinary HUD pass (`HudHooks.passAllowed`, `FathomHud.render`) draws only
   that module's widgets where they sit, behind the window. Nothing is drawn
   twice, nothing takes input, nothing is saved per frame; it is cleared on
   leaving the page or closing the Hub. Fathom background mode is opaque, so
   nothing is requested there. Not verified: how it looks under the glass.
4. **Reopen the last page.** `core/hud/HubState`
   (`config/fathom-hub-state.properties`, outside profiles, like the editor's
   preferences) holds the preference (on by default), the section title, the
   page inside it (Settings tab or module id) and the scroll. Written when the
   Hub closes or the preference changes. `Section.pageId/scrollPosition/restore`
   are implemented by Modules, Settings (and so its tabs), Appearance,
   Performance, Quick, Profiles and Diagnostics. A missing section, tab or
   module falls back to the default page. A caller that names a section
   (`startingAt`) or a requested module still wins. The toggle is
   Appearance > Presentation > Reopen the last page.
5. **Editor toolbar.** `EditorPrefs` gains `toolbar` (default on) and
   `toolbarSize` (Standard/Compact), stored in the editor's own file. In the
   editor: a Hide button, the T shortcut (G and B already toggle the grid and
   bounds), and, when hidden, a 30x18 tab at the bottom right that restores it.
   Only the tab's own rectangle takes input while hidden, the inspector's lower
   corners drop to just above it, and widgets under it can be grabbed. Compact
   is a shorter bar with smaller buttons. The same two controls are in
   Appearance > HUD editor ("Show editor toolbar", "Toolbar size").
6. **Text anchoring.** `core/hud/TextAnchor` (Auto, Left, Centre, Right) is a
   new field of `Placement`, saved in the layout file and in profiles only when
   not Auto, so existing layouts and profiles read and write as before; Auto
   follows the screen anchor exactly as it did. `core/hud/HudGeometry` is the one
   place that turns anchor, text anchor, measured width and offset into the box's
   left edge and back (`left`, `offset`, `clampLeft`); `FathomHud.locate`, the
   editor's drag/re-anchor/text-anchor change and the Modules page's re-anchor
   all use it, so drawing, hit boxes, snapping, dragging, backgrounds and
   screen clamping read the same measured box. With an explicit anchor the
   chosen edge of the box sits on the reference point, so a right-anchored label
   keeps its right edge as it changes length. The inspector has a Text row; changing
   it keeps the box where it is on screen.

### Remaining uncertainty and manual checklist

- Scrollbars: drag every one (Modules grid and a module page, Quick, Profiles,
  Appearance, Diagnostics, Performance list and page); click above and below a
  thumb; shrink the window and filter the grid while scrolled.
- Performance: in a short window the page scrolls; over the list the wheel
  scrolls it and, at either end, the page; at 65% and 125% interface scale, hover
  and click rows near the list's clipped edges.
- Preview: Gameplay glass, a world, open Time/FPS/CPS-type module pages, change
  colour, scale, background; widgets update behind the window. Fathom background
  shows nothing extra.
- Reopen: close on a module page scrolled down, and on a Settings tab; reopen;
  turn the preference off; remove a module from the profile and reopen.
- Toolbar: hide with the button, with T, and from Appearance; restore from the
  tab and from Appearance; Compact at several GUI scales; drag a widget that sits
  under where the bar was.
- Anchoring: a widget on the left of the screen set to Right text, with a
  readout that changes length (coordinates, CPS); its right edge stays put in the
  editor and in the game, selection box and plate match, dragging works.
