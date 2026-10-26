# Fathom v1 UI refinement: design system and plan

Status: **proposal, awaiting approval** (2026-10-19). Nothing in the UI has been
changed for this document. Every value below is read from the repository as it
stands; where a value is a proposal it is marked **→ proposed**.

## 1. Infrastructure, as it is

### One token source, generated into both runtimes

`assets/theme/fathom-theme.json` is the only source. `tools/generate-fathom-theme.mjs`
writes:

| Output | Read by |
|---|---|
| `desktop/src/lib/theme.generated.css` (712 lines of custom properties) | the launcher (Svelte 5 + Vite, in Tauri) |
| `desktop/src/lib/metrics.generated.ts`, `motion.generated.ts`, `icons.generated.ts` | launcher TypeScript |
| `generated/FathomTheme.java` (`DIM_*`, `TYPE_*`, `Metrics`, `Tokens`) and `generated/GraphiteTokens.java` | the in-game compositor |
| `generated/SharedIconPaths.java`, `Mark.java`, `Wordmark.java` | in-game icons and brand |

`npm run check` fails if the generated files drift from the JSON. The two
runtimes share **specifications only**: no runtime code is shared, and none
needs to be.

The JSON has a **`game` density layer**: `game.metrics` overrides metrics by
name, and `game.graphite` is merged over `graphite`, for the Java outputs only.
Its own note: "the game's screens float over a world the player came to see, so
its controls are a size smaller". That is how the launcher and the game can
share one vocabulary at two densities.

### The JSON holds three token generations

| Layer | What it holds | Still read by |
|---|---|---|
| Top level (`colors`, `typography`, `dimensions`, `motion`, `metrics`) | the original graphite/black palette: red accent and focus `#c4303c`, type 11/12/13/14/15/17/26, page padding 28/24, hover 110 ms | **in-game `core.ui.render.Type`** (every in-game text size), `Skin.PAGE_INSET_*`, the hub min/max frame (`DIM_HUB_*`); `metrics` is still the shared control geometry |
| `system.*` | the current shared system: colour roles, `type.roles`, materials, `motion` 90/120/160/180/200, ease `[0.2, 0.8, 0.2, 1]`, launcher layout | launcher (`--color-*` ×351, `--type-*` ×355, `--space-*` ×350), in-game `Skin` via `FathomTheme.Tokens` |
| `graphite.*` (+ `game.graphite`) | Graphite primitives: ink, fills, rims, signal `#8FC8FF`, its own type scale, ease `[0.2, 0, 0, 1]`, hub/menu frame layout | launcher Home/shell (`--g-*` ×174); in-game `Graphite` (hub frame, Mods grid, main menu) |

### In-game compositor

- **Layout:** a retained box tree (`core.ui.layout.Box`, `Rect`, `TextLeaf`). The hub frame, Mods grid,
  menu, Quick, Performance, Profiles, Diagnostics and Launcher use it. **AppearanceSection and HudEditorScreen
  are hand-laid-out with a y cursor**.
- **Drawing:** a `core.ui.render.Renderer` interface. `fabric.gfx.SdfRenderer`/`GlRenderer` draw rounded SDF
  shapes, rims, gradients, clips (`ui.clip`) and alpha stacks. `SdfText` draws Geist from a pre-rasterised SDF
  atlas (`core.ui.text.FontAtlas`, five faces: regular, medium, semibold and two mono). `Icons` strokes the shared
  icon paths.
- **Two component vocabularies:**

  | Vocabulary | Used by (references) |
  |---|---|
  | `fabric.ui.Graphite` | FathomHubScreen (76), ModsSection grid and tabs (123), FathomMenuScreen (111) |
  | `fabric.ui.Skin` + `Controls` + `FathomComponents` + `FathomSettingRows` | every other Hub section, module settings, HUD editor (99), all controls (Controls: 320) |

- **Glass:** `SdfBackdrop` copies the frame once, makes two 2:1 blits to quarter size and runs a separable
  Gaussian, with up to 3 strengths a frame and only when glass is drawn. `WorldBackdrop` captures the world once
  when a menu opens and box-averages it to a quarter. Gameplay Glass blur choices are 12/18/26, and the dim
  choices (`ClientPrefs.RSHIFT_DIM_VALUES`) are **0.24 / 0.34 (default) / 0.44**.
- **Caching:** `HudLayerCache`, `HudSteadyCache` and `VanillaHudReplay` for the HUD; the retained layout for
  screens. `GlStateAudit`/`GlLeakCheck` verify GL state in QA.
- **Motion:** `core.ui.anim` (`Motion`, `Ease.Curve.STANDARD|GRAPHITE|EXIT`, `Timing.*` from `Tokens.MOTION_*`,
  `Transition`). The clock is real time, not frames.
- **Scale:** `core.ui.UiScale`: `scale = clamp(framebufferHeight / 720 × preference, 0.75, 3.0)`, with the
  preference 0.65–1.5 (Quick → Fathom UI scale). The hub frame is a 900×540 logical canvas
  (`game.graphite.layout`), fitted with a 16 margin. Minecraft's GUI scale governs only HUD widgets
  (`MinecraftGuiSize`), never Fathom screens.

### Launcher

Svelte 5. The shell is `App.svelte`, with shared components in `lib/ui/*`: Row, Section, Page, Button, Switch,
Select, Segmented, SearchField, Sheet, EmptyState, Notice and Progress. Shared CSS is in `lib/controls.css`
(`.ui-popover` and friends) and `lib/theme.css` (base rules, the lowercase rule, the icon stroke). Graphite pieces
are `lib/graphite/*`. Motion is `lib/motion.ts` plus Svelte transitions: `EASE` is the standard curve, used ×74
against `--g-ease` ×10. Icons are Lucide (32 imports) plus `FathomIcon` from `icons.generated.ts`, all at a
1.5 px non-scaling stroke (`theme.css`).

## 2. Current production values (source of truth)

In game the numbers are logical units (× UiScale = device px); in the launcher they are CSS px.

### Spacing

| Token | Value | Notes |
|---|---|---|
| space-1/2/3/4/6/8 | 4 / 8 / 12 / 16 / 24 / 32 | both sides; the requested 8/12/16/24 already exist |
| `system.space.5` | 24 | the same as `space.6`: a duplicate, not a step |
| section gap | 24 | launcher `--layout-section-gap`; in-game `Skin.SECTION_GAP` (aligned 2026-10-18) |
| page padding | launcher 40 / 32 (`system.layout`); game 28 / 24 (legacy `dimensions`) | **mismatch** |
| panel padding | launcher 16, game 12 (`graphite.layout.panel-pad`, game override) | density |
| scroll gutter | game 16 (`Skin.SCROLL_GUTTER`) | launcher has none (native scrollbars) |

### Radii

| Role | Launcher | Game |
|---|---|---|
| panel | 18 | 14 |
| control | 12 | 9 |
| small / segment | 10 | 7 |
| pill | 999 | 999 |

The rule in both: an inner radius is the outer radius less the inset (concentric).

### Control dimensions

| Control | Launcher | Game |
|---|---|---|
| control | 36 | 28 |
| control-compact | 32 | 24 |
| control-primary | 44 | 32 |
| input / search / segmented | 36 | 28 |
| row / row-dense | 48 / 40 | 40 / 32 |
| switch (w × h, knob) | 32×18, 14 | 26×15, 11 |
| icon (nav / inline / tile) | 20 / 16 / 20 | 16 / 14 / 16 |
| module tile | 32 | 26 |

Literals in game that bypass these tokens: `Controls.SWATCH 24`, `SLIDER_HEIGHT 20`, `STEPPER_WIDTH 104`;
ModsSection `HEX_WIDTH 86`, `SLIDER_VALUE 44`, `CARD_BUTTON 22`; `FathomSettingRows.SECTION_HEADING 43`.

### Typography: Geist (Geist Mono for data)

| Role | Launcher (`system.type.roles`) | Launcher Graphite | Game (`core.ui.render.Type`, legacy `typography`) | Game Graphite |
|---|---|---|---|---|
| display | 48 / 600 | — | `DISPLAY` 26 / 600 | — |
| title | 28 / 600 | 20 / 600 | `TITLE` 15 / 600 | 18 / 600 |
| heading | 18 / 600 | 15 / 600 | `SECTION` 17 / 600 | 14 / 600 |
| row title | Row `.title` 14 / 600 | — | `ROW_TITLE` 14 / 500 | — |
| body | 14 / 400 | 13 / 400 | `BODY` 13 / 400, `BODY_STRONG` 13 / 500 | 13 / 400 |
| secondary | 13 / 400 | — | (none; rows use `BODY`) | — |
| ui / button | 14 / 500 | 13 / 500 | — | 13 / 500 |
| small | — | 12 / 400 | `SMALL` 12 / 400 | 12 / 400 |
| caption / label | 12 / 400, label 12 / 500 | 11 / 500, upper +0.06 em | `CAPTION` 11 / 400, `LABEL` 11 / 500 | 11 / 500 upper |
| data | — | Mono 20 / 13 / 12 | — | Mono 20 / 13 / 12 |

Text floor: 9 device px (`size.text-floor-px`).

### Colour (shared roles)

- **Text:** primary `#EDF2F7`, secondary `#B7C2CE`, tertiary `#95A1AE` (`rgba(183,196,210,.72)` in `system`),
  disabled `#5A6470`.
- **Signal / focus:** `#8FC8FF` (focus ring at 0.95 alpha). The legacy layer still defines red `#c4303c` as
  accent and focus; nothing in the launcher reads it.
- **Status:** ok `#7DB38C`, warn `#D9B45A`, error `#E07A6E` (Graphite); `system` uses sage / amber / terracotta
  roles of close value.
- **Controls:** fill `rgba(255,255,255,.07)`, hover `.11`, selected `rgba(190,222,255,.16)`; sunken
  `rgba(6,12,18,.20)`; hairline `rgba(214,234,255,.09)`, strong `.15`; prominent (primary button)
  `rgba(237,243,250,.95)` with dark text.

### Materials (glass)

| Level | Fill | Blur (launcher) | Rim (top / edge) |
|---|---|---|---|
| clear | `rgba(211,233,255,.035)` | 6 | .14 / .08 |
| light | `rgba(17,25,34,.12)` | 14 | .16 / .10 |
| standard | `rgba(17,25,34,.20)` | 18 | .18 / .12 |
| dense | `rgba(12,19,27,.52)` | 22 | .18 + shade |

- The game uses each level's `tint-game` fill.
- No-blur and solid variants exist for the launcher's appearance modes.
- Scrims: launcher modal `rgba(6,12,18,.28)`; game world scrim `.18`, poster `.12`, modal `.28`; Fathom-background
  dim-veil .16 and dim-pool .32.

### Motion

| Token | Value |
|---|---|
| press | 90 ms, press scale 0.985 |
| hover | 120 |
| state (toggle, selection) | 160 |
| surface / panel | 180 |
| page / hub enter | 200 |
| exit / close | 120 |
| backdrop | 420 |
| stagger | 24 |
| tooltip delay | 400 |
| scrollbar fade | 600 |
| travel | 8 |
| curves | standard `[0.2, 0.8, 0.2, 1]`, exit `[0.4, 0, 1, 1]`, Graphite `[0.2, 0, 0, 1]` |

The legacy layer still says hover 110, toggle 160, sidebar 200, drawer 215, stagger 22.

### Icons

Shared artwork (`assets/icons/manifest.json` → `icons.generated.ts` and `SharedIconPaths.java`), 24-unit grid,
round caps, a **1.5 px** stroke on both sides (`theme.css`; `IconGeometry.STROKE_DP`, floored at 1.3 device px).
Sizes are 20/16 in the launcher and 16/14 in game.

### Layout dimensions today, against the targets

The Hub at **1920×1080, automatic UI scale** (1080/720 = 1.5×):

| Element | Logical | Device px today | Target px | Target ÷ today |
|---|---|---|---|---|
| RShift panel | 900×540 | **1350×810** | ~780×490 | 0.58 / 0.60 |
| Sidebar | 156 | 234 | ~150 | 0.64 |
| Header (row + gap) | 28 + 14 | 42 + 21 | ~48 | — |
| Module card | min 156×92 (≈170×92 at 4 columns) | ≈255×138 | 145–170×70–80 | ≈0.6 |
| Settings controls | 24–28 | 36–42 | 32–36 | ≈0.85 |

**The targets are almost exactly today's logical sizes at a 1.0 scale**: sidebar 156, header ≈42, controls
24–32, a panel of 780×490 against today's 900×540 canvas. What makes the UI feel large at 1080p is the scale
curve (1.5× there), not the layout.

## 3. Proposed shared specification

Values are logical units for the game (×scale) and px for the launcher. The game keeps its one-size-smaller
density.

| Spec | Launcher | Game | Change |
|---|---|---|---|
| Spacing | 8 / 12 / 16 / 24 | same | **→ proposed:** drop `system.space.5` (= 24), use `space-6` |
| Radii | 18 / 12 / 10 | 14 / 9 / 7 | none |
| Type (one scale) | title 20/600, heading 15/600, row title 14/600, body 14/400, secondary 13/400, small 12/400, label 11/500 caps | title 18/600, heading 14/600, row title 14/500, body 13/400, secondary 13/400, small 12/400, label 11/500 caps | **→ proposed:** game `core.ui.render.Type` reads `graphite.type` (with the game override) instead of legacy `typography`, so the game has one type scale |
| Sidebar | icon rail 48, tray 200 on hover | **→ proposed:** labelled rail 150 (from 156); rows 28; icon 16 | |
| Header | shell 56 | **→ proposed:** 32 row + 12 gap ≈ 44 (48 px at 1080p with the scale below) | |
| Module card | — | **→ proposed:** min 148 × 72, pad 10, gap 8, tile 26, switch 26×15 | |
| Settings row | Row 48 | 40 (32 dense); controls 24 (compact) / 28 | none |
| Panel | standard glass, radius 18 | standard `tint-game`, radius 14, rim .18/.12 | none |
| Motion | standard curve; hover 120, state 160, surface 180, page 200, exit 120, stagger 24 | same | **→ proposed:** retire the Graphite curve and the legacy 110/215/22 values; hub open/close moves to standard / exit |
| Icons | 1.5 stroke; 20 / 16 | 1.5 stroke; 16 / 14 | **→ proposed:** remove the 2.2 override in `VerdictBadge.svelte` |
| Background behind the in-game UI | — | **→ proposed:** dim choices 0.28 / **0.40** / 0.50 (from .24 / .34 / .44); tint from `game.scrim` `rgba(6,12,18)` instead of the literal `0x071019` | |

### Scale (needs approval)

| | Today | **→ proposed** |
|---|---|---|
| Rule | `scale = clamp(height / 720 × preference, 0.75, 3)` | `scale = clamp(max(1, height / 1080) × preference, 0.75, 3)` |
| 720p | 1.0 | 1.0 |
| 1080p | 1.5 | 1.0 |
| 1440p | 2.0 | 1.33 |
| 4K | 3.0 | 2.0 |
| Hub canvas | 900×540 | **780×490** |
| 1080p panel | 1350×810 px | 780×490 px |
| 720p panel | 900×540 (70% × 75% of the screen) | 780×490 (61% × 68%) |
| Text at 1080p | — | body 13 px |

The 65–150% preference stays.

## 4. Implementation plan, file by file

Every step keeps the compositor, Gameplay Glass (no new framebuffer or blur pass), the caches, GL-state
discipline, the motion clock and the retained layout.

### 0. Tokens (first, alone)

- `assets/theme/fathom-theme.json`:
  - `game.graphite.layout`: hub-width/height → 780/490, hub-rail 150, hub-header 32, hub-header-gap 12; add
    `mod-card-min-width` 148, `mod-card-height` 72, `mod-card-pad` 10.
  - `game.metrics`: add `swatch`, `slider`, `stepper-width`, `section-heading`.
  - Map the legacy `typography` to `graphite.type`.
  - Mark the legacy colour, motion and dimension keys deprecated (aliases).
- `tools/generate-fathom-theme.mjs`: emit the new keys; keep `--check`.
- `core/ui/UiScale.java` (+ `UiTests`): the scale curve above.
- `core/ui/render/Type.java`: read `GraphiteTokens.TYPE_*`.

### 1. RShift Modules screen

- `fabric/hub/FathomHubScreen.java`: frame, rail and header from the new tokens; dim tint from the scrim token
  (line 659); hub transition on the standard and exit curves.
- `fabric/hub/ModsSection.java`: `CARD_*` and `CARD_BUTTON` from tokens; card internals (tile, name, meta,
  switch, star, gear) re-spaced to 72 high; toolbar on `SEARCH`/`segmented` metrics.
- `fabric/ui/Graphite.java`: card, segment and switch helpers on the shared metrics.

### 2. Module configuration

- `fabric/hub/ModsSection.java` (`modPage`, `settingsGroup`, `settingRow`, `layoutGroup`): the header row on the
  page-header token; one group pattern.
- `fabric/ui/FathomSettingRows.java`: `SECTION_HEADING` → token; heading style shared with the other sections.
- `fabric/ui/Controls.java`: `SWATCH`, `SLIDER_HEIGHT`, `STEPPER_WIDTH` → metrics; switches already on Graphite.
- `fabric/hub/ModulePreview.java`.

### 3. Appearance and HUD editor

- `fabric/hub/AppearanceSection.java`: move onto the Box tree, as Quick is (it is the last hand-laid section
  apart from the editor); the same group pattern; presentation, glass and background groups.
- `fabric/hud/HudEditorScreen.java` (1,909 lines, Skin, hand-laid): toolbar and inspector panels on Graphite
  surfaces and shared controls; no behaviour change (EditorSelfTest must stay 43/0).
- `core/module/ClientPrefs.java`: `RSHIFT_DIM_VALUES`.
- `fabric/Runtime.java` `rshiftDim()`: unchanged API.

### 4. Minecraft main menu

- `fabric/menu/FathomMenuScreen.java` (Graphite, Box tree): menu-header, column and gap tokens re-checked against
  the new scale; the button family on control-primary / control.
- `fabric/menu/MenuScenes.java`, `MenuVideo.java`, `MenuPoster.java`: unchanged (no new footage).
- `generated/Wordmark.java` via the generator only.

### 5. Launcher consistency

- `App.svelte`: the rail stays an icon rail; align rail, tray and header with the shared spec (shell header 56
  is the launcher's own chrome).
- `lib/ui/Row.svelte`, `Section.svelte`, `Page.svelte`: page padding from one token (decide 40/32 or 28/24 for
  both); group label style matching the game.
- `lib/controls.css`, `lib/theme.css`: legacy aliases removed once unused.
- `lib/ui/LoadingScreen.svelte`: orbit literals → `--brand-orbit-*`.
- `routes/mods/VerdictBadge.svelte`: stroke.
- `routes/Home.svelte` and `lib/graphite/*`: on `--g-*` / `--metric-*` only.

### 6. Motion and rendering validation

- `fabric/diag/UiTour.java`: step names that do not overwrite one another (`wheel:-2` and `wheel:-2` collide
  today); a module-page step.
- Tours at 1280×720, 1920×1080 and 2560×1440, UI scale 65/100/150%, Fathom background and Gameplay Glass.
- Self-tests: `ModuleSelfTest` (74/0), `EditorSelfTest` (43/0).
- `UiProfile` `measure:` steps for frame time.
- `SdfBackdrop.passes` unchanged (≤ 3 strengths × 2 passes).
- `GlStateAudit` clean.
- Core suites (`gradle build`), `npm run check`, `npm test`.

## 5. Design inconsistencies found

1. **Three token generations** in one JSON. The game's text sizes still come from the oldest layer; the red
   accent and focus survive there unused.
2. **Two in-game component vocabularies:** Graphite (frame, grid, menu) and Skin/Controls (every section,
   settings, HUD editor), with different surface helpers (`Graphite.surface` / `FathomComponents.panel`) and
   segmented controls.
3. **Two easing curves:** standard `[0.2, 0.8, 0.2, 1]` and Graphite `[0.2, 0, 0, 1]`. The launcher uses
   standard 74 : 10; the game opens the Hub on Graphite (5 uses) and everything else on standard (9).
4. **Navigation models differ:** the launcher has a 48 icon rail with a 200 tray on hover; the game has a 156
   labelled rail.
5. **Page padding:** launcher 40/32 against game 28/24.
6. **Group pattern:** a module page puts its heading above the panel; Quick, Appearance and Performance put a
   label inside it. Performance mixes both on one page.
7. **Type:** launcher body 14 against game 13; game title 18 against launcher 20 (density, but by separate
   scales).
8. **Hard-coded sizes:** see "Control dimensions" above; AppearanceSection widths 744 / 370 / 190; Quick
   `SLIDER_LABEL`; ModsSection `introHeight` 22/48 and `OFF_NOTICE` 44.
9. **Dim tint literal** `0x071019` (FathomHubScreen:659) against the theme's `rgba(6,12,18)`.
10. **Hand-laid screens:** AppearanceSection and HudEditorScreen are not on the Box tree, so their spacing is
    computed separately and has drifted before.
11. **Scale:** 1.5× at 1080p makes every screen about 1.7× the approved targets.
12. **Module cards** are 92 high against a 70–80 target.
13. **Icon stroke override** (2.2) in the launcher's verdict badges.
14. **Duplicate spacing step:** `system.space.5` = `space.6` = 24.
15. **Loading screen** literals duplicate the brand-orbit tokens.

Already fixed on 2026-10-18 (for reference): text-field rims, the settings-row type scale (launcher Row), one
scroll gutter, greyed inactive style settings, the profile-switcher material.

## 6. Decisions needed before implementation

1. **The scale curve** (section 3): 1.0 at 1080p, or keep 1.5 and shrink the logical layout instead (text
   becomes too small).
2. **Page padding** for both sides: 40/32 or 28/24.
3. **Group pattern:** heading above the panel (module pages) or label inside it (Quick and others).
4. **Dim default:** 0.40 ("slightly darker").
5. **Retiring the legacy token layer:** alias now, delete after the pass.
