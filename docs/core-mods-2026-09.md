# Core mods, September 2026

The milestone after the performance pass: take the modules the client already
had and finish them. No new modules, no new architecture — the module registry,
the settings schema, the HUD layout and the profile format are the ones that
were already there, and everything below is declared through them.

## What changed, by module

| Module | What it was | What it is now |
| --- | --- | --- |
| **Crosshair** | Four shapes, a size, a gap, a colour | Presets, length, thickness, gap, centre dot, outline width and colour, opacity, dynamic spread, target colour, and the four "when not to draw" rules. Live preview in the settings drawer. |
| **Zoom** | Hold a key; amount, smooth, sensitivity | Scroll to adjust between a minimum and a maximum, remember or reset on release, a zoom duration, cinematic camera, hide hand, and sensitivity compensation that is correct rather than approximate. Nothing is written to the game's options at all. |
| **Keystrokes** | Fixed 16px keys, fixed spacing | Key size, spacing, border width, press fade, CPS on the buttons, and the shared background, border, radius and font settings. |
| **Sprint and sneak** | A label when the game said you were sprinting | Toggle Sprint and Toggle Sneak, done by reporting a latched key to vanilla and nothing else, with five configurable labels and two colours. |
| **FPS** | Off / Light / Smooth, three formats | Raw / Light / Smooth / Average, a custom prefix and suffix, and a refresh interval down to 100 ms. |
| **Ping** | A number | A number, vanilla's signal bars, or both; refresh interval; two colour thresholds; `--` when the server has not said. |
| **CPS** | Left, right or both | Adds Combined and button letters, and counts from the game's own input events rather than sampling per frame. |
| **Coordinates** | X/Y/Z, direction, biome | Three formats, three layouts (single line, one per line, compact), four direction styles, dimension and chunk. |
| **Clock** | 12/24 hour, seconds, zone | Adds five date formats and a custom pattern. |
| **Armour HUD** | Text lines | Real item rendering with durability values, percent or remaining, slot or item names, vertical or horizontal, reverse order, spacing, the vanilla damage bar, and colour by condition with configurable thresholds. |
| **Potion HUD** | Text lines | Minecraft's own effect icons, names, levels, time left, compact mode, ordering, layout, effect colour and an ending warning. |
| **Pack Display** | The top pack's name | The pack's own icon, its description, the server's pack, and a count or the whole stack. |

## The three behaviours

Modules that are not a widget have to name a behaviour the client implements;
`ModuleTests` fails the build if a name has no implementation or an
implementation has no name. This pass adds one and rewrites one.

### Toggle Sprint and Toggle Sneak (`movement-toggle`)

Two `KeyBinding.isPressed` reads are redirected: the sprint key where
`ClientPlayerEntity.tickMovement` decides whether to sprint, and the sneak key
where `KeyboardInput.tick` decides whether to sneak. While a toggle is latched,
those two reads say "held", and nothing else changes — vanilla still applies
hunger, blindness, using an item, walking forwards and the sneak slowdown. The
client cannot be made to do something the physical key could not.

The latch is dropped on death, respawn, world change and disconnect, and a key
held across one of those does not re-latch: a press has to start after the
release. Sneak's latch is ignored (but kept) while flying or riding, where the
key means descend or dismount.

Presses are only seen through the game's own key state, which vanilla clears
when a screen opens and does not update while one is open — so typing a capital
letter in chat cannot toggle sneak.

### Zoom (`zoom`)

The field of view, the mouse sensitivity and the camera smoothing are changed
where vanilla reads them, for that read only:

- `getFov(F, true)` — the world's projection, and not the hand's, which is why
  the arm no longer fills the screen while zoomed;
- the `GameOptions.sensitivity` read in `GameRenderer.render`;
- the `GameOptions.smoothCameraEnabled` reads in `render` and `tick`.

The previous version wrote those options and put them back on release. That is
state in a file Minecraft saves, so anything that saved the options mid-zoom —
or a crash — kept the zoomed sensitivity, and a change made while zoomed was
overwritten. Now there is nothing to restore.

**Sensitivity compensation is solved, not divided.** Minecraft turns by
`(s × 0.6 + 0.2)³ × 8` per pixel of mouse travel, so halving the option does not
halve the turning. `ZoomMath.compensatedSensitivity` inverts that curve, and
`HudTests` asserts that the camera turns exactly *n* times slower at *n*× zoom,
at three sensitivities and three zooms. Strength is configurable from 0 to 100%.

### Crosshair (`crosshair`)

`CrosshairShape` turns the settings into whole-pixel rectangles; the HUD draws
them through the batched tessellator and the settings preview draws the same
rectangles through Fathom's renderer, so the preview cannot show a crosshair the
game will not draw.

The shape is rasterised into a small mask and read back as row runs, which is
what makes the outline exact: "every rectangle one pixel larger" overlaps itself
wherever two arms meet, and those overlaps are visible as dark knots at anything
under full opacity.

## HUD editor

Behaviour only; nothing about how it looks changed.

- **Snap strength** — Off, Light, Normal, Strong, on the toolbar, persisted in
  `config/fathom-hud-editor.properties`. Editor preferences are deliberately not
  part of a profile: they belong to the person, not to the loadout.
- **Alt places freely** — no snapping, no grid rounding, no overlap resolution
  while it is held, which is the only way to put a widget one pixel from another
  on purpose.
- **Overlap resolution** — a widget dropped on top of another is pushed clear by
  the shortest way out, and left where it was dropped when there is nowhere
  clear.
- **Shift-arrows** nudge by ten pixels; arrows still nudge by one.
- **A position readout** follows a dragged widget: its anchor, its offset and
  its size, so an exact position can be reached and read.
- **Widgets are kept on screen.** An anchor and an inward offset already survive
  a resize, but an offset chosen on a wide window can exceed a narrow one; a
  widget that fits at all is now nudged back inside when it is drawn. The stored
  placement is untouched, so making the window wide again puts it back.

The placement model is unchanged — anchor, offset, scale, opacity — so every
saved layout and every exported profile still loads. There is nothing to
migrate.

## The shared HUD appearance

`HudStyle` declares the settings every HUD module has, in one call, with the same
ids and defaults: font, text colour, text shadow, background, background colour,
background opacity, **border, border colour, border width**, padding, corner
radius and drop shadow. `TextWidget` reads them, and the three widgets that are
not text — Keystrokes, the armour list, the potion list — draw their plate
through the same code, so a module's Background row means the same thing
wherever it is drawn.

Scale and position stay in the HUD editor rather than becoming settings. They
are spatial, they are set against the scene they sit over, and two places to
change one number is how they end up disagreeing.

## Settings, keys and profiles

- **A setting id is persisted.** Where an option was renamed, the old spelling is
  kept as an alias (`Setting.Choice.alias`), so a profile written by an older
  build still reads. Where a setting changed meaning it has a new id, so an old
  value cannot be silently read as something it never was.
- **A profile no longer fails over one bad value.** `ProfileSwitch.apply` used to
  throw when a stored value could not be parsed, which rolled the whole switch
  back — and at startup it meant a single renamed dropdown entry left the client
  on the wrong profile. A value this build cannot use now costs that one setting,
  is reset, and is reported.
- **Settings that depend on each other** are kept consistent by the module's own
  declared listener: a crosshair preset writes the shape it names, editing that
  shape turns the picker to Custom, and Zoom's minimum, amount and maximum stay
  in order. The hub has no per-module code for any of it.
- **Backspace and Delete clear a keybind** while it is capturing (Escape still
  cancels), which is the only way to unbind a key; the Hub key is put back to its
  default rather than being lost.
- **Key conflicts include Minecraft's own bindings.** Fathom consumes nothing, so
  a shared key fires both actions — worth knowing before wondering why zooming
  also drops an item.

## Performance

The rule for this pass was to keep what the performance milestone bought.

- **Nothing is rebuilt per frame that does not change per frame.** Armour text is
  rebuilt when an item, its damage, its count or a setting changes; potion names
  when the effects change and their times when the displayed second changes; pack
  names when the pack list changes. A frame where nothing happened allocates
  nothing in any of them.
- **`Setting.revision()`** is a counter bumped by every value change, which is how
  those caches know a setting was edited without re-reading a dozen values.
- **Batched drawing.** Potion icons are one texture bind and one draw call for
  however many effects are on; armour items share one set-up of the GUI item
  lighting; the crosshair, the keystroke keys and the ping bars each go out as
  one tessellator batch.
- **A square plate border is four rectangles**, not one per row — the row-by-row
  path is only for rounded corners.
- **`-PhudProfile`** prints what each widget costs per frame, so the next feature
  that makes the HUD expensive is attributable rather than a mystery.

Measured with the scripted benchmark, development client, 1280×720:

| Scenario | HUD section, after the performance pass | after this pass |
| --- | --- | --- |
| a-still / b-pan / c-ground / d-move (default widgets) | 0.023–0.026 ms | 0.040–0.054 ms |
| g-hud (every module on) | 0.037–0.041 ms | 0.077 ms |

The rise is the price of what the modules now do — a crosshair rasterised from
its settings rather than four fixed rectangles, keystroke fades, per-widget
alignment, the stable-width rule, and the plate borders. It is under a twentieth
of a millisecond on a frame that takes between one and four, and the frame rates
in those scenarios (365, 268, 743, 670 fps) are where they were.

With every widget on and a player wearing armour with two effects — the heaviest
the HUD gets — `-PhudProfile` reports the cost as:

```text
fps 0.077   coordinates 0.055   clock 0.072   cps 0.024   keystrokes 0.073
pack-display 0.033   crosshair 0.045   armour 0.218   potions 0.128  ms/frame
```

Those are from the capture harness rather than the benchmark: the frame there is
GPU-bound, so each number includes some waiting for the driver and they are worth
reading against each other rather than as absolute costs.

Armour and potions are the two that draw something other than text: five item
models and a texture bind. That is what they cost, and a player who does not
want to pay it switches them off — which is the same answer vanilla gives for
its own hotbar.

## Testing

- `HudTests` and `ModuleTests` cover the geometry, the toggles, the zoom
  arithmetic, snapping strength, overlap resolution, editor preferences,
  keybind clearing, presets, option aliases and the settings revision.
- In-game captures from the development client: the HUD with every widget on and
  a dressed player (`-PcaptureUi=hud -PcaptureHudItems`), the settings drawer with
  the crosshair preview (`-PcaptureUi=hub -PqaOpenSettings=crosshair`) and the
  HUD editor (`-PcaptureUi=hud-editor`).
- Not covered: a real multiplayer server (so the ping widget was exercised only
  against its unknown path), and the toggle behaviours were verified from the
  code and the unit tests rather than from a recorded play session.
