# Fathom's design system: golden-hour glass

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
<Glass level="dense" radius="xl" inset={4}>…</Glass>
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

Quick, smooth, quiet. `press` 90, `hover` 160, `state` 180, `page` 200,
`surface` 220, `exit` 150 ms; `backdrop` 420 ms for background crossfades only.
One entrance curve, `cubic-bezier(0.2, 0.8, 0.2, 1)`, identical in CSS
(`--ease-standard`), Svelte (`EASE`, which now evaluates the bezier rather than
approximating it) and Java (`Ease`). Nothing bounces or springs. The global
reduced-motion rule still applies.

## 7. The background

[`Background.svelte`](../desktop/src/lib/Background.svelte) is the only place
a backdrop is drawn. It layers the warm fallback (always painted), the approved
photograph, and the warm scrim.

The policy is curated: [`backgrounds.ts`](../desktop/src/lib/backgrounds.ts)
lists the approved images, and today that is only `golden-hour`. The chain is
exactly **approved image → warm fallback**. The fallback is a failure-state
substrate, not a choice. Any other id, including every retired recording, is
shown as the default and is migrated to it in `appearance.json` on start
(`migrate_appearance` in `crates/launcher-desktop/src/lib.rs`, which leaves the
accessibility switches alone).

Video backgrounds are retired: the path renders `<img>` only. The picker shows
itself only when there is more than one approved background
(`PICKER_AVAILABLE`), so adding a second image is a one-line change in each of
the two lists. The photograph is shown as supplied (`--backdrop-image-filter`
is `none`).

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
| `Switch`, `Segmented` | `lib/ui` | Selection is champagne luminance or a denser pill |
| `Section`, `Row` | `lib/ui` | Standard-glass panel; rows wrap instead of truncating, and the control drops under the text when the row is narrow |
| `Page` | `lib/ui` | Document frame with an edgeless readability veil |
| `Sheet` | `lib/ui` | The one modal: inset dense glass, scrim, Escape closes |
| `Notice` | `lib/ui` | The notification surface (inline; no toasts by design). Standard glass on the photograph; flat inside a pane that is already glass, so blur never stacks |
| `Progress`, `SoundingLine` | `lib/ui`, `lib/brand` | Loading states; still under reduced motion |

**Deepening one pane.** Where a pane crosses the brightest part of the
photograph and its type falls short of 4.5:1, deepen that pane locally with
`--mat-veil: var(--control-deepen)` rather than moving it to a denser material
or darkening the page. Settings' category pane is the example.

**Legacy aliases.** In the launcher the previous dark system's CSS variables
(`--text*`, `--accent*`, `--fill*`, `--glass-*`, `--chrome*`, `--r-*`) and the
`.glass` classes are answered by these tokens (`theme.css`), so screens not yet
rebuilt sit in the same palette. The manifest's legacy values are untouched
because the game's `Skin` still reads them.

## 7b. The brand

The Surface wordmark, the offset ring and the Sounding Line are drawn from one
construction in [`desktop/src/lib/brand/surface.ts`](../desktop/src/lib/brand/surface.ts),
with their colours and construction numbers in the manifest (`system.brand`,
emitted to CSS as `--brand-*`, to `brand.generated.ts`, and to Java as
`FathomTheme.Tokens.BRAND_*`). The launcher renders them as components
(`Wordmark`, `BrandRing`, `SoundingLine` in `lib/brand/`); the icon files are
rendered from the same geometry by `assets/brand/build-brand.mjs`. See
[`assets/brand/README.md`](../assets/brand/README.md) for the rules.

- The header shows the wordmark at a 20px cap (the reduced tier). It reveals
  once after the window appears: the lower band of each letter steps into its
  whole-pixel displacement as an implied pane passes left to right, about a
  second in all. Reduced motion: a 200ms fade.
- The Sounding Line is the loading symbol. Nothing in the brand spins.
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
**no-blur** fill plus the three rim colours as separate ARGB values, which a
plate can draw as spans. Full-screen menus may later add one blurred world
snapshot, taken once when the screen opens. HUD modules never get a live blur.

**In game today** (the main menu and the Hub):

| Launcher | Game |
| --- | --- |
| `.material-*` with blur | `Skin.MATERIAL_*`: the no-blur fill, `VisualEffects.glass` draws fill + rim ring + a hairline highlight across the top's straight run |
| `--control-*` fills | `Tokens.CONTROL_*` (generated), read through `Skin.SURFACE`, `ELEVATED`, `RECESSED`, `FILL_*` |
| `--color-text*` | `Skin.TEXT`, `TEXT_MUTED` (champagne), `TEXT_FAINT`, `TEXT_DISABLED` |
| Primary button | `Skin.PROMINENT`: the dark dense fill, ivory type, never coloured |
| Selection / on | a denser fill and `Skin.EDGE_ELEVATED`; switches and sliders in champagne with an ivory knob |
| The old red | `Skin.ENERGY*`: ivory luminance only. There is no hue but the status tones |
| `Wordmark.svelte` | `BrandMark.wordmark`: the outlines are `generated/BrandGeometry.java`, emitted from `surface.ts` by the theme generator; `GlRenderer` rasterises them once per size into their own sheet with the same pixel snapping and tiers, plus the mark shadow |
| `SoundingLine.svelte` | `BrandMark.soundingLine`: the small mark and the loading glyph |
| `Background.svelte` | `MenuScenes` + `MenuPoster`: the approved set (Golden Hour), the launcher's warm scrim, the warm field as fallback |

The Hub is one pane of standard glass over a warm scrim; its header and rail
are regions of it, not panes on top of it. Only transient surfaces (popovers,
drawers, the menu's account, credits and picker panels) are dense glass. The
offset ring is not drawn in game: at in-game sizes it is too coarse, and the
Sounding Line is the small form of the brand.

## 10. What a component may not do

It may not write a hex colour, an rgba fill, a `backdrop-filter`, a border
colour, a raw font size, a raw radius or a raw duration. If it needs something
the system lacks, the manifest gains a token whose name says what it is for.
