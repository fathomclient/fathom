# Fathom's design system: Graphite glass

One manifest, three outputs. Everything visual in the launcher, and eventually
in the game, comes from [`assets/theme/fathom-theme.json`](../assets/theme/fathom-theme.json):

```
node tools/generate-fathom-theme.mjs
```

| Output | Consumer |
| --- | --- |
| `desktop/src/lib/theme.generated.css` | Launcher: custom properties, the four `.material-*` classes, the `.type-*` roles, the accessibility-mode swaps |
| `desktop/src/lib/motion.generated.ts` | Launcher: durations and bezier control points for Svelte transitions |
| `minecraft/.../generated/FathomTheme.java` | Game: legacy constants (consumed by `Skin`) and `FathomTheme.Tokens` (not yet consumed) |

The generator runs as part of the build, so the outputs cannot drift from the
manifest:

- `npm run build` and `npm run dev` in `desktop/` run it first (`npm run tokens`).
- Gradle's `compileJava` depends on `generateThemeTokens`, so the in-game module
  never compiles against stale constants. This also covers `tools/build-all.ps1`,
  which builds the module before the launcher.
- `npm run check` runs it with `--check`, which writes nothing and fails if any
  output is out of date.

Outputs are rewritten only when their content changes. Commit them with the
manifest.

## 1. The idea

**The background is the palette.** Surfaces are glass that borrows its colour
from the photograph behind it. There is no decorative accent colour, no glow,
no neon, and almost nothing fully opaque.

**Hierarchy is density.** A more important surface is *denser* glass -- less of
the photograph gets through -- not a different hue. The only hues that carry
meaning are the three status tones.

## 2. Two generations, for now

The manifest's top-level `colors`, `dimensions`, `typography`, `motion` and
`css` sections are the previous dark system (`--text`, `--accent`, `--r-*`,
`--m-*`, `.glass`). Screens that have not been rebuilt still use them and
render exactly as before. The game's `Skin` reads them too.

Everything new lives under `system` and uses these names only:

| Prefix | What |
| --- | --- |
| `--palette-*` | Primitives: ivory, champagne, sand, bronze, olive, umber, and the three status hues. Never used by a component directly |
| `--color-*` | Roles: `text`, `text-secondary`, `text-tertiary`, `text-disabled`, `text-inverse`, `danger`/`success`/`warning` (+ `-text`, `-tint`, `-rim`), `focus`, `prominent` |
| `--mat-{level}-*` | Material levels (below) |
| `--shadow-*` | `none`, `sm`, `md`, `lg`: warm, soft, low opacity |
| `--radius-*` | `xs` 6, `sm` 10, `md` 14, `lg` 20, `xl` 26, `2xl` 32, `pill`; `inner-min` 6 |
| `--space-*` | 4px steps: 1–6, 8, 10, 12, 16, 20 |
| `--layout-*` | Page padding, gaps, column widths |
| `--type-{role}-*` | Size, weight, leading, tracking per role |
| `--motion-*`, `--ease-*` | Durations by role; `standard` and `exit` curves |
| `--backdrop-*` | Base colour, warm fallback, scrims, image filter, text shadows |

When a rebuilt screen no longer needs a legacy token, delete the token.

## 3. Materials

```html
<div class="material-standard">…</div>
<div class="material-dense" style="--surface-radius: var(--radius-xl)">…</div>
```

| Level | For | Tint | Blur | Rim | Shadow |
| --- | --- | --- | --- | --- | --- |
| `clear` | Chips and icon buttons on the photograph | ivory, 5% | 10px | top only | none |
| `light` | Quiet grouping | champagne, 14% | 20px | soft | sm |
| `standard` | The default pane: groups, popovers, platters | umber, 32% | 28px | medium | md |
| `dense` | Drawers, dialogs, the primary control, important status | umber, 56% | 34px | deep lip | md |

Each level has three fills: the normal `tint`, a `tint-noblur` for reduced blur
(denser, because a sharp photograph behind text needs more cover than a blurred
one), and a `tint-solid` for reduced transparency. The generated rule reads the
resolved `--mat-{level}-fill` and `--mat-{level}-filter`, and the modes swap only
those. **No component ever branches on a mode.**

**Rim lighting.** Three inset shadows: a brighter top edge, a faint even edge
all round, and a darker lip underneath on the denser levels. Inset shadows follow
the corner radius, so the highlight curves into the corners and fades. There are
no borders.

**Interaction.** `.material-interactive` adds a hover veil (an ivory layer
already reserved in the material's box-shadow, so it interpolates), a 0.985
press compression, and an ivory focus ring. No glow, no lift.

**Status.** `.tone-danger`, `.tone-success`, `.tone-warning` are a wash and a
rim in the status hue: the one place a hue means something.

## 4. Geometry

### Control metrics -- one system, both renderers

`metrics` in [`fathom-theme.json`](../assets/theme/fathom-theme.json) is the only
place a control's geometry is a number. The launcher reads it as
`--metric-*` (and `ICON` in `metrics.generated.ts`), the game as
`FathomTheme.Metrics`. Every older radius, height and spacing token is an alias
of it, and the generator refuses a literal in those tables.

| Metric | Value |
| --- | --- |
| Radius: panel / control / small (item inside a panel, segment) | 18 / 12 / 10 (pill only for switches, avatars, badges) |
| Button / compact / primary action | 36 / 32 / 44 |
| Input / search / segmented | 36 / 36 / 36 (segment inset 2, radius 10) |
| List row / dense row | 48 / 40 |
| Switch | 32 × 18, knob 14 |
| Icons: navigation / inline / tile / in caption text | 20 / 16 / 20 / 12, one 1.5 pen at every size |
| Rim / divider / focus ring | 1 / 1 / 2 **device** pixels |
| Spacing | 4 / 8 / 12 / 16 / 24 / 32 (40+ only for page gutters) |

**A rim is one device pixel.** In CSS every rim is a multiple of
`var(--hairline)` (`1 / devicePixelRatio`, set in `main.ts`); in the game it is
`Renderer.rim()` / `Renderer.pixel()`, which NanoVG snaps onto the pixel grid.
Selection, hover and focus change a rim's brightness, never its width, and a
child inside a bordered parent is filled, not given a second rim.

The geometry debug overlay shows all of this live: Ctrl+Shift+G in a dev build
of the launcher, `-Dfathom.debugGeometry=true` in the game. Off-metric values
are drawn in the warning colour.

### Concentric shapes

Generous and concentric. A container that owns its shape sets
`--surface-radius` and `--surface-inset`; a `.concentric` child gets
`max(inner-min, outer − inset)`. This works one level deep. A deeper nest sets
its own variables.

## 5. Typography

Geist, already vendored under SIL OFL 1.1 in both the launcher and the game. It
was kept: it holds heavy display weights well, has tabular figures, and needs no
new licence. Roles, used as `class="type-heading"` or through the variables:

| Role | Size | Weight | Colour |
| --- | --- | --- | --- |
| `display` | fluid 36–64 | 600 | text |
| `title` | 28 | 600 | text |
| `heading` | 18 | 600 | text |
| `body` | 14 | 400 | text |
| `secondary` | 13 | 400 | text-secondary |
| `label` | 12 | 500 | text-secondary |
| `button` | 14 | 500 | text |
| `caption` | 12 | 400 | text-tertiary |
| `stat` | 32 | 500, tabular | text |

Sentence case throughout. The old uppercase tracked eyebrow labels belong to the
previous system.

## 6. Motion

Quick, smooth, quiet. `press` 90, `hover` 120, `state` 160, `page` 200,
`surface` 180, `exit` 120 ms; `backdrop` 420 ms for background crossfades only.
One entrance curve, `cubic-bezier(0.2, 0.8, 0.2, 1)`, identical in CSS
(`--ease-standard`), Svelte (`EASE`, which now evaluates the bezier rather than
approximating it) and Java (`Ease`). Nothing bounces or springs. The global
reduced-motion rule still applies.

## 7. The background

The background is the user's choice: the atmosphere. The control system is the
quality, and it does not change per background. See
[`backgrounds.md`](backgrounds.md).

[`Background.svelte`](../desktop/src/lib/Background.svelte) is the only place a
backdrop is drawn. It layers the fallback field (always painted), the chosen
background's full-resolution still, its video when it has one (fading in once
it plays), and a light scrim. Nothing is blurred or re-graded per background.
The library is [`assets/backgrounds/manifest.json`](../assets/backgrounds/manifest.json);
an unknown stored id is shown as the default and migrated in `appearance.json`
on start.

## 7d. Light

The interface is lit by the scene behind it. Each background carries an
`accent` (in the manifest, measured from its own still by
`tools/derive-background-accents.mjs`), and that one colour, at the strengths
in the theme's `graphite.glow`, is every glow on both surfaces:

| Glow | Launcher | Game | Strength |
| --- | --- | --- | --- |
| Bloom: a round pool behind what you came to do, and a fainter echo high on the far side | `GraphiteBackdrop` | `Glow.behind` (title menu, Hub) | `bloom-alpha` 20%, echo 8% |
| Sheen: light falling on a pane from above | `.material-standard`, `.material-dense` | `Graphite.surface` on Standard and Dense panes | `sheen-alpha` 9% |
| Halo: around the one primary action | `--glow-halo` (Play, `Button` primary) | `Glow.halo` (`Controls.primary`) | `halo-alpha` 28% |
| Current place: the selected nav item, the hovered title-menu row | `--glow-selected` | `Glow.current` | `selected-alpha` 14% |
| Spotlight: a circle following the pointer over a pane | `use:spotlight` | -- | `spotlight-alpha` 4.5% |

Two rules. **Round**: every glow is a radial falloff or a halo that follows the
rounded shape it belongs to; nothing is a band, and nothing is clipped so that
light reaches a rounded shape's square corners. **Subtle**: a glow says "here"
and is otherwise barely there; only the primary action and the current place
get one. Strokes never change: light goes behind and around a shape, never into
its one-pixel rim. The bloom drifts over most of a minute and stops under
reduced motion; the accent crossfades when the background changes.

## 7a. The shell and the shared controls

**The shell** ([`App.svelte`](../desktop/src/App.svelte)) paints no bars. The
photograph fills the window; the header row (brand, page, account, window
controls; the whole row is the drag region), the two navigation capsules and
the status line are glass and legible type laid onto it. The current page is
the denser pill in the rail, not a coloured marker. Shell dimensions are
`--layout-shell-*` and `--layout-rail-*`.

**Two surface families.** A control *on the photograph* combines a shape with a
material and brings its own blur: `class="ui-capsule material-clear
material-interactive"`. A control *inside a pane* uses the shape alone and gets
a flat `--control-*` fill, because a second blur under the first is wasted
work.

| Primitive | Where | What |
| --- | --- | --- |
| `.ui-capsule` (`.small`) | `controls.css` | The secondary control: translucent pill, density on hover |
| `.ui-icon-button` (`.small`, `.danger`) | `controls.css` | Round icon-only control |
| `.ui-popover`, `-item`, `-note` | `controls.css` | Menus and dropdown lists: dense glass |
| `.ui-field` | `controls.css` | Text inputs, selects, text areas: sunken, rim brightens on focus |
| `input[type=range]` | `controls.css` | Every slider: sunken channel, ivory knob |
| `.ui-item`, `.ui-mark`, `.ui-item-title`, `-meta`, `-controls` | `controls.css` | One object in a manager list (profiles, installations): hairline-separated inside a `Section material`; `.current` is a faint fill, never a coloured edge |
| `use:tooltip` | `tooltip.ts` | Replaces `title` on icon-only controls |
| `Button` | `lib/ui` | `normal`, `primary` (denser, never coloured), `caution` |
| `Switch`, `Segmented` | `lib/ui` | On is the icon's arc blue; selection a denser pill |
| `Section`, `Row` | `lib/ui` | Standard-glass panel; rows wrap instead of truncating, and the control drops under the text when the row is narrow |
| `Page` | `lib/ui` | Document frame with an edgeless readability veil |
| `Sheet` | `lib/ui` | The one modal: inset dense glass, scrim, Escape closes |
| `Notice` | `lib/ui` | The notification surface (inline; no toasts by design). Standard glass on the photograph; flat inside a pane that is already glass, so blur never stacks |
| `Progress`, `OrbitMark working` | `lib/ui`, `lib/brand` | Loading states; still under reduced motion |

**Deepening one pane.** Where a pane crosses the brightest part of the
photograph and its type falls short of 4.5:1, deepen that pane locally with
`--mat-veil: var(--control-deepen)` rather than moving it to a denser material
or darkening the page. Settings' category pane is the example.

**Legacy aliases.** In the launcher the previous dark system's CSS variables
(`--text*`, `--accent*`, `--fill*`, `--glass-*`, `--chrome*`, `--r-*`) and the
`.glass` classes are answered by these tokens (`theme.css`), so screens not yet
rebuilt sit in the same palette. The manifest's legacy values are untouched
because the game's `Skin` still reads them.

## 7c. Finishing rules

The small things, shared by the launcher and the game so the two read as one
product. Each has one implementation per side.

| Element | Launcher | Game | Rule |
| --- | --- | --- | --- |
| Icons | `svg.lucide` rule in `theme.css`; `FathomIcon` for Fathom ids | `Icons.strokeUnits` | Lucide geometry only. Sizes 20 (navigation), 18 (standalone action), 16 (inline), 14/12 (status). Stroke fixed in screen units by size: 1.5 / 1.6 / 1.7 / 1.75. Call sites never set a stroke. |
| Focus | `--focus-ring-width` + `--color-focus` | `Skin.FOCUS`, `Controls.focusRing` | 1.5px signal blue, 2px outside the shape, keyboard focus only. |
| Signal blue | `--color-signal`, `--brand-orbit-light` | `Tokens.COLOR_SIGNAL`, `Graphite.BRAND_LIGHT` | The icon's arc blue (`#8fc8ff`, `#a5d6ff`): focus, live telemetry (the running-now dot, FPS dot, graphs), HUD-editor guides/selection, a switch that is on, a slider's fill and the current page's glyph in the rail. Small marks only: never a panel, never a large fill. |
| Buttons | `Button` (`primary`, `normal`, `ghost`, `caution`) | `Controls.primary/secondary/ghost/danger` | Primary is the bright neutral fill with dark type, one per surface. No lift, no glow, no scale on hover. |
| Switch | `Switch` | `Controls.toggle` | 38x22, 16 knob; off a faint track and grey knob, on a brighter track and near-white knob. 120ms. |
| Slider | `input[type=range]` + `use:rangeFill` | `Controls.slider` | 4px translucent channel, brighter neutral fill, 14px thumb. Values beside it are Geist Mono, fixed width. |
| Segmented | `Segmented` | `Controls.segmented*` | Light track; selection a denser pill with the strong rim, lit by the glow. No shadow. |
| Search | `SearchField` | `ModsSection.graphiteSearch` | Glyph in a 16px slot; clear button only while there is a value; Escape clears. |
| Tooltip | `use:tooltip` | `Controls.tooltip` | Dense glass, 6px radius, rim only, 12px type. 400ms hover delay, immediate on keyboard focus, 120ms fade, no travel. Never the native `title`. |
| Popover | `.ui-popover` | `Controls.drawList` | Dense glass, one `md` shadow. In: fade + 6px over 180ms. Out: fade over 120ms. |
| Scrollbar | `::-webkit-scrollbar` | `Scroller.rail` | No track. 4px thumb inset 3px, 20% white, 32% under the pointer. Never changes width. |
| Hairline | `--hairline` (one device pixel, set in `main.ts`) | `Graphite.hairline` | Only where structure needs it; never a gradient. |
| Status | `Notice`, `Row` badges, `.status` dots | `Skin.GOOD/WARN/BAD` on marks only | A 6px dot or a 14-16px icon in the status colour beside neutral text. Panels are never washed green, amber or red. |
| Empty | `EmptyState` | -- | Small icon in a faint circle, a short heading, one line, optionally one action. |
| Loading | `Progress` (3px line; drifts when there is no fraction) | -- | No spinners, no fake percentages. The Play control reserves the line's space so nothing moves. Under it, the launch's four steps (Check, Download, Start, Confirm) in caption type: the furthest reached is held, a step not needed is passed over. |
| Timeline | `FrameTimeline` | `hub/Instrument` | One column per bucket of play: the mean frame time as a faint signal-blue column with a lit top; a whisker up to a cap at the slowest frame only when it stands 2.5x clear of the mean, in the warning colour at a hitch (50 ms). A faint hairline at a 60 fps frame. Ceiling: the 90th-percentile slowest frame x 1.25, never under 20 ms; a rare spike clips. A tertiary hairline marks a change (world, settings). Newest on the right. Hover reads a column out in words. |
| Figures | `SessionFigures` | `Instrument.figure` | Caption label over a Geist Mono value and a caption unit; every figure explains itself on hover or focus (tooltip). |
| Session | Home's session block, the header's session capsule | -- | The capsule appears on every page but Home while a launch works, a game runs, or one went wrong; one press goes back. Its dot is signal blue (breathing only while working), danger once it went wrong. |
| Current page | `.indicator` in each rail group | Hub rail | One pill that slides between items (`--motion-state`), not a fill that blinks from one to the next. |
| Press | `--press-scale` (0.985) on every control, Play and the rail included | `Controls` press | A denser fill and a slight compression; nothing lifts. |
| Shell | `App.svelte` (Home included) | Hub header and rail | One header, account capsule and rail on every page, in the same place. Home drops only the crumb and the status line. |
| Avatar | header capsule, account sheet | -- | Round everywhere, pixel-scaled at a whole multiple of the 8px face, rim drawn over the image. |
| Presets | `Segmented` with the current preset | `Controls.segmentedNatural` + `SwitchBoard.matchingPreset` | The preset in effect is selected; "Custom" once a switch is changed by hand. |
| Mono | `.mono` | `Graphite.DATA_*` | Geist Mono for measured values, build ids, hashes, host names, log lines and coordinates. Short versions in running chrome (the header, the status line) stay tabular Geist: in a monospace face "1.8.9" letter-spaces. |

## 7b. The brand

The interface is the icon's system. Its neutrals lean to the tile's navy
(glass tints, hairlines, rims and scrims are cool rather than warm grey), and
its one accent is the arc's light blue, used only in small marks: focus,
telemetry, what is on, a slider's fill, the current page's glyph. The glows
that a background lends the interface are pulled 60% of the way to that blue
(`graphite.glow.brand-mix`), so every scene lights the glass in the same family.

Orbit: the mark is drawn from one set of paths in
[`desktop/src/lib/brand/orbit.ts`](../desktop/src/lib/brand/orbit.ts), on the
icon's 512 grid, with its inks and the lockup's proportions in the manifest
(`system.brand`, emitted to CSS as `--brand-*`, to `brand.generated.ts`, and to
Java as `FathomTheme.Tokens.BRAND_*`; the paths themselves reach the game as
`generated/Mark.java`). The wordmark is lowercase Geist, outlined by
`tools/generate-wordmark.py` into `wordmark.generated.ts` and
`generated/Wordmark.java`, with its optical tiers in `graphite.wordmark`; its
ink is ivory warming to the arc's blue over the last letters. In the launcher
the mark is `lib/brand/OrbitMark.svelte` and the wordmark
`lib/brand/Wordmark.svelte`; the icon files and installer bitmaps are rendered
from the same paths by `assets/brand/build-brand.mjs`. See
[`assets/brand/README.md`](../assets/brand/README.md) for the rules.

- The header shows the lockup, the mark with its halo and the wordmark at a
  13px ascender, the same drawing the game's Hub and title screen show. It
  does not animate, and the Standard capitalization setting does not change it.
- The splash and the loading screen stand on the icon's own ground (its tile's
  three stops and its halo), with the stacked lockup and a sweeping line in the
  arc's blues.
- `OrbitMark working` is the small wait symbol: the star breathes, the arc
  holds still. Nothing in the brand spins.
- Editing a brand colour means running `node assets/brand/build-brand.mjs` as
  well as the theme generator, so the icon files follow.

## 8. Accessibility

| Mode | How it is set | Effect |
| --- | --- | --- |
| Reduce transparency | Settings → Appearance, or Windows' "Transparency effects" off (`prefers-reduced-transparency`) | `tint-solid` fills, no backdrop filter anywhere, flat scrim |
| Reduce blur | Settings → Appearance | `tint-noblur` fills, no backdrop filter anywhere |

Both are stored in `appearance.json` beside the background (merge-written, so
neither writer resets the other) and applied as `data-transparency` /
`data-blur` on `<html>` in both windows. The kill switch also covers the
remaining legacy components that still declare their own `backdrop-filter`.

## 9. The game is not the launcher

The launcher uses real backdrop blur. The game must not copy that. Nothing in
the in-game renderer reads the framebuffer, and HUD widgets are replayed from
display lists. `FathomTheme.Tokens` therefore gives the game each level as its
**`tint-game`** fill plus the three rim colours as separate ARGB values, which a
plate can draw as spans. `tint-game` is not the launcher's `tint-noblur`: that
is the launcher's blur-off mode, over a scrimmed photograph, and dense. In game,
clear is the launcher's own pale ivory veil (for controls on the photograph),
light (24%) and standard (36%) are light warm deepenings, and only dense (74%)
is dense. Unblurred, a pale veil behind ivory type lowers its contrast, so a
group that carries text deepens a little instead of lightening. The scrims are
`system.game`: over the world (38%), over the photograph (24%), under a modal
(34%). Full-screen menus may later add one blurred world
snapshot, taken once when the screen opens. HUD modules never get a live blur.

**In game today** (the main menu and the Hub):

| Launcher | Game |
| --- | --- |
| `.material-*` with blur | `Skin.MATERIAL_*`: the `tint-game` fill, `VisualEffects.glass` draws fill + rim ring + a hairline highlight across the top's straight run |
| `--control-*` fills | `Tokens.CONTROL_*` (generated), read through `Skin.SURFACE`, `ELEVATED`, `RECESSED`, `FILL_*` |
| `--color-text*` | `Skin.TEXT`, `TEXT_MUTED` (champagne), `TEXT_FAINT`, `TEXT_DISABLED` |
| Primary button | `Skin.PROMINENT`: the cool ivory fill with the tile's navy type |
| Selection / on | a denser, faintly blue fill and `Skin.EDGE_ELEVATED`; switches and sliders in the arc's blue with a near-white knob |
| The old red | `Skin.ENERGY*`: ivory luminance only. The only hues are the icon's blue and the status tones |
| `Wordmark.svelte`, `OrbitMark.svelte` | `Graphite.lockup`: the outlines are `generated/Wordmark.java` and `generated/Mark.java`, from the same generators as the launcher's; `BrandArt` rasterises them once per size, in their own inks, into their own sheet with the same optical tiers and x-height snapping |
| `Background.svelte` | `MenuScenes` + `MenuPoster`: the generated approved set, the launcher's neutral scrim, and the fallback field only when the picture cannot load. The Hub opened from the title screen draws the same selected scene (`FathomMenuScreen.drawBackdrop`) |

The Hub has no window. The background -- the world under the light scrim, or
the photograph -- is the page. The header is loose capsules; navigation is two
floating groups of light glass (the current section is the ivory capsule);
the content area is open, and a page's lists and settings are light-glass
groups whose rows are not cards. Material use:

| Material | In game |
| --- | --- |
| Clear | Controls on the photograph (the menu's account capsule) |
| Light | Navigation groups, list and settings groups, cards, the menu dock |
| Standard | The selected card, pressed states |
| Dense | Popovers, dropdowns, tooltips, the menu's account, credits and picker panels |

Radii follow the launcher: controls are pills, cards and groups
`RADIUS_XL`, major groups `RADIUS_2XL`, popovers `RADIUS_LG`, and rows inside a
group are concentric with it (its radius less the inset). Control heights are
the launcher's 28/34/40. The wordmark is sized by device pixels, as in the
launcher: its optical tier follows the ascender in device pixels, so the 12dp
wordmark is SemiBold at 1x and Medium from 1.5x, and its x-height is always a
whole number of pixels.

## 10. What a component may not do

It may not write a hex colour, an rgba fill, a `backdrop-filter`, a border
colour, a raw font size, a raw radius or a raw duration. If it needs something
the system lacks, the manifest gains a token whose name says what it is for.
