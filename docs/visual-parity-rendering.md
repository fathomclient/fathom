# Fathom visual parity rendering

The launcher and Minecraft UI use one authored visual system. The canonical
source is [`assets/theme/fathom-theme.json`](../assets/theme/fathom-theme.json).
Run `node tools/generate-fathom-theme.mjs` after changing it. The generator
produces:

- `desktop/src/lib/theme.generated.css` for launcher CSS tokens.
- `desktop/src/lib/motion.generated.ts` for Svelte transition durations.
- `minecraft/fathom-optimizations/src/main/java/net/fathom/optimizations/generated/FathomTheme.java`
  for framebuffer UI constants.

`desktop/src/lib/theme.css` imports the generated CSS. Minecraft's `Skin`,
`Type`, and `Ease` classes reference the generated Java constants. Do not edit
the generated files directly.

## Type and icons

Both interfaces use Geist. The launcher loads
`desktop/src/lib/fonts/Geist-Variable.woff2`; Minecraft bundles the matching
Regular, Medium, and SemiBold TTF faces under
`minecraft/fathom-optimizations/src/main/resources/assets/fathom/font/` and
rasterises glyphs at framebuffer resolution. Measurement and drawing use the
same cached glyph pages.

Shared icon identities live in `assets/icons/manifest.json`. Run
`node tools/generate-fathom-icons.mjs` after changing it. It extracts the exact
Lucide 0.544.0 geometry already installed by the launcher, checks SVG copies
into `assets/icons/` and `desktop/public/icons/`, and emits the launcher URL map
plus Minecraft path data. Minecraft converts the SVG paths to Java2D at atlas
creation time; it does not maintain a second hand-drawn icon family.

## HUD typography

The HUD widgets draw in Geist too, and the way that was done is the interesting
part. Everything around a widget — the saved layout, the editor's hit boxes,
the snapping, the plate behind each one — is measured in Minecraft's scaled-GUI
pixels against `TextRenderer.fontHeight`, which is nine. Changing the typeface
naively would have moved every saved position.

So the widgets no longer take a `TextRenderer`. They take `HudFont`, whose unit
is Minecraft's scaled pixel in **both** implementations: the vanilla one
delegates to `TextRenderer`, and the Geist one measures Fathom's renderer and
divides back. The Geist face is derived rather than chosen — Minecraft's
eight-pixel glyph cell converted into logical units and rounded to a whole
number, so the glyph sheet is cached across window sizes rather than
re-rasterised on every resize frame. Switching the font moves nothing.

Drawing it means opening a Fathom frame inside the game's own HUD pass, which
`Gfx.beginOverlay` does. It deliberately does not advance the animation clock
(every screen's motion is sampled from it, and running it at sixty frames a
second while no menu is open would mean every animation on the next screen was
already finished) and does not record a frame time (`Fathom UI` on the
Performance page is the cost of a Fathom *screen*; the HUD is measured
separately as `Probe.Section.HUD`).

Fathom's renderer batches into the game's one shared Tessellator, and so does
everything else the HUD draws, so a queue must not survive across vanilla
drawing or across a change of modelview. Both have an owner: `Plate` flushes
before it draws — Keystrokes alternates plate, label, plate, label — and the
HUD flushes at the end of each widget while that widget's matrix is still
current.

### What it costs

Seven widgets, in a world, at 1280x720, median of three runs each:

| HUD font | Mean per frame |
| --- | ---: |
| Geist | 0.35 ms |
| Minecraft | 0.23 ms |

About 0.1 ms a frame, most of it the GL state snapshot the overlay frame takes
and hands back. That snapshot is eighteen `glGet` calls and it is not optional:
handing the game back a state it did not have is how every translucent quad in
the client vanished from the second frame onwards once already.

**Quick > Fathom > HUD font** switches between them, and Minecraft's font is a
real choice rather than a deprecated path — a HUD is read in a fight, at a
glance. It is also the fallback when the typeface cannot be loaded.

## Rounded geometry and quality setting

High-quality rendering is on by default. Rounded fills and outlines use cached
coverage masks generated at the final device-pixel radius with 8x8 subpixel
coverage. The cached masks live in the renderer's existing atlas, so steady
frames do not allocate or rasterise geometry.

The switch is at **Performance > Interface > High quality UI rendering** and is
persisted as `client.ui.high-quality`. Turning it off uses the older stretched
fill mask and four square-corner outline quads. That path is visibly simpler
and does less work; shared icon and text atlases remain enabled in both modes.

One 1280x720 development-client pass on the Performance page measured:

| Mode | Mean Fathom UI time |
| --- | ---: |
| High quality | 1.545 ms |
| Fast fallback | 1.457 ms |

The observed reduction was 0.088 ms, about 5.7% of the high-quality pass. These
are single-machine QA readings from one settled capture each, not a
cross-machine benchmark claim, and the gap is well inside the run-to-run spread
of this measurement — the honest summary is that the quality setting costs very
little on this machine.

## Motion

One engine, in `core/ui/anim`. `Clock` advances once per rendered frame from
`System.nanoTime`. `Motion` stores a start value, a target and the timestamp it
was retargeted at, and answers `value(now)` — so an animation is a function of
elapsed time rather than of how many frames happened to render. Retargeting
samples the current interpolated value first, which is what makes a pointer
entering and immediately leaving reverse from where it is instead of queueing
or snapping. `Ease.out` is the launcher's `cubic-bezier(0.2, 0.8, 0.2, 1)`,
evaluated in the same x-to-y form as CSS.

Durations come from the manifest and are asserted against these bands by
`UiTests.theSharedDurationsAreWithinTheSpecifiedBands`:

| Token | Value | Band |
| --- | ---: | ---: |
| `hover` | 110 ms | 90–130 |
| `press` | 75 ms | 60–90 |
| `toggle` | 160 ms | 140–180 |
| `selection` | 160 ms | 140–180 |
| `sidebar` | 200 ms | 180–220 |
| `drawer` | 215 ms | 190–240 |
| `hub` | 225 ms | 200–250 |

`Clock.restart` closes the gap a shut menu leaves behind by moving the last
frame forward. It deliberately does not rewind `seconds()`: live motions hold
timestamps on that timeline, and moving it backwards under them would put their
start in the future, where progress reads as zero and the interface freezes.

## Hover, and how it is proved

Pointer state is read from the device once per rendered frame, not delivered
through Minecraft's 20 TPS screen callbacks. Within a frame the order is fixed:
advance the clock, poll the pointer, hit-test, retarget, draw. The frame that
discovers a hover is therefore the frame that draws it.

`HoverLatency` records three timestamps — the pointer event, the logical state
change, and the flush of the frame carrying it — plus the number of rendered
frames between the last two. **Frames waited is the number that matters**, and
it should read zero; microseconds vary by machine, but "the hover waited for
something" is structural. The value appears on the Diagnostics page, in the
visual-QA log line, and per-sample on the console with `-PhoverProbe`.

## Navigation and interaction

The hub reserves only a 56-unit icon rail. Hovering it opens a 186-unit label
tray over the page, so content never reflows; the pin action holds it open and
a press outside it lets go. The tray is decided before the page draws and drawn
after it: deciding first lets the page be shielded from the pointer, so rows
under an open tray no longer light up through it. HUD configuration is reached
through the persistent **HUD Editor** action and is no longer duplicated as a
sidebar section.

## The module filter, and never cropping a label

`Controls.segmentedNatural` answers a narrow box in three steps, in order:
cells take their labels' natural widths; then horizontal padding drops from 12
to 8; then the row scrolls, with the wheel, with the selected cell always
brought into view, and with a gradient at each end. It never shortens a label —
a category reading "Performan…" is one the reader has to guess at.

## Visual QA

The Gradle run supports deterministic framebuffer captures:

```text
gradle runClient -PcaptureUi=menu
gradle runClient -PcaptureUi=Mods
gradle runClient -PcaptureUi=Profiles
gradle runClient -PcaptureUi=Performance
gradle runClient -PcaptureUi=hud-editor
gradle runClient -PcaptureUi=Performance -PqualityUi=false
gradle runClient -PcaptureUi=Mods -PrailOpen
gradle runClient -PcaptureUi=Mods -PclientWidth=2560 -PclientHeight=1440
gradle runClient -PcaptureUi=Mods -PhoverProbe
gradle runClient -PcaptureUi=hud
gradle runClient -PcaptureUi=hud -PhudFont=Minecraft
```

`-PcaptureUi=hud` loads the development world from the main menu and
photographs the HUD as the game's own pass draws it, which is the path players
use and the one the editor's capture cannot stand in for. `-PhudFont` picks the
typeface for the run without touching a saved preference file.

`-PrailOpen` pins the label tray, which otherwise only exists while a pointer
is on it and so cannot appear in a still. `-PclientWidth` / `-PclientHeight`
drive the resolution sweep. `-PhoverProbe` prints a line per hover change.

## Tests

The core suites are `main` methods rather than JUnit, because the point of the
core/fabric split is that the core has no dependencies. `gradle test` therefore
finds nothing to run — it did not run any of them, silently, for as long as the
suites have existed. `gradle coreTest` executes each one in its own JVM and
fails the build on a non-zero exit; `check` depends on it, so `gradle build`
runs them.

Captured PNGs are written to
`minecraft/fathom-optimizations/run/screenshots/`. The capture log also prints
the mean Fathom UI time for that surface.
