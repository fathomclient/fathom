# Brand assets

Fathom's identity is **Orbit**: an arc that sweeps up and narrows to a bright
leading point, with a small four-pointed star at the centre of its sweep, on a
deep rounded tile. The word "fathom" is set lowercase in Geist, ivory, taking
the arc's blue over its last letters.

Every file here is generated. The geometry lives in
[`desktop/src/lib/brand/orbit.ts`](../../desktop/src/lib/brand/orbit.ts), drawn
on the icon's own 512 grid; its inks and the lockup's proportions are
`system.brand` in the theme manifest
([`assets/theme/fathom-theme.json`](../theme/fathom-theme.json)); the
wordmark's outlines are
[`wordmark.generated.ts`](../../desktop/src/lib/brand/wordmark.generated.ts),
made from the launcher's Geist by `tools/generate-wordmark.py`. Regenerate
with:

```
node tools/generate-fathom-theme.mjs
python tools/generate-wordmark.py        (only when the wordmark's letters change)
node assets/brand/build-brand.mjs
```

## The mark

| Part | Ink |
| --- | --- |
| Tile | `#141c25` → `#101820` → `#080e13`, top left to bottom right, with a `#35495e` rim |
| Halo | `#4c9afb` at 22%, fading to nothing at the edge of an ellipse behind the mark |
| Arc | `#5b9ceb` → `#a5d6ff` → `#8fc8ff` → `#d3e9ff`, from its tail up to its tip |
| Taper | `#77baff` at 60%, falling away under the tip |
| Star | `#c9eaff`, over a `#75bdff` glow (70%, blurred 3 grid px), with two `#f2faff` hairlines |

On light grounds (`mark-reversed.svg`, the README in light mode) the same blues
go a density deeper (`#1f5fb0` to `#4c9afb`) and the glow is left out, because a
glow on white only greys it.

## The app icon

The icon is the whole thing: tile, halo and mark. `icon.svg` is the master.
Every raster is drawn at its own size rather than shrunk from the master:

- The tile's outer edge (10.5 grid px in, the rim's outside) is rounded to a
  whole pixel, so its sides never fall between two pixels and show as a soft
  grey fringe on a taskbar. Its rim is drawn inside that edge, at least one
  whole pixel wide, so the tile keeps a defined edge on a dark taskbar and
  nothing spills past it on a light one.
- The mark scales with the tile, so nothing reaches past it at any size.
- Below 48 px the arc is widened just enough that its thinnest stretch stays
  about one pixel (1.35 px below 24): a line, not a broken smudge.

| Size | Where |
| --- | --- |
| 16, 20, 24, 30, 32, 36, 40, 48, 64, 128, 256 | `icon.ico`: Explorer, the taskbar (30 and 36 are its frames at 125% and 150%), Start |
| 512 | `icon.png`: Tauri's window icon and the bundle |
| 16, 32 | the game window (`WindowIconMixin`) |
| 128 | the mod's own icon (`fabric.mod.json`) |

## The wordmark

"fathom", always lowercase, in Geist, outlined so it is the same drawing in
the launcher, the game and every file here. It is shaped by HarfBuzz, so
Geist's kerning is kept. Its ink is ivory `#edf2f7` to 34% of its width, then
warms to the arc's light, `#b2d7ff`, by the last letter: the "om" carries the
icon's blue, the rest stays plain. Reversed, it runs from the tile's `#141c25`
to `#2b5a92`.

What changes with size is weight and spacing, chosen by the ascender (Geist's
cap height) in **device** pixels:

| Tier | Ascender | Weight | Tracking | Where |
| --- | --- | --- | --- | --- |
| small | ≤ 16 px | SemiBold | +2% | the launcher header (13 px), the game (12 dp), the installer |
| text | 17–32 px | Medium | 0% | the header at 125% and up, the splash, documents |
| display | ≥ 33 px | Medium | −1.5% | anything larger |

Up to 32 px the x-height is rounded to whole pixels. The minimum is a 10 px
ascender. The Standard capitalization setting never touches it; its
accessible name is "Fathom".

## The lockup

Side by side, the mark is 1.9 ascenders tall, centred on the word's ascender,
and 0.55 ascenders before it: the launcher header, the game's title screen and
Hub, the installer. Stacked, the mark is 3.4 ascenders tall and 0.9 above the
word: the splash and the loading screen. The mark's own box is the arc and the
star; the glow and the halo spill past it, so nothing round it should clip.

## Loading

`OrbitMark.svelte` with `working` is the small wait symbol (signing in,
restoring a session): the star breathes slowly while the arc holds still.
Nothing spins or travels; under reduced motion it holds still. Long waits keep
the sweeping line.

## Files

| File | What it is |
| --- | --- |
| `icon.svg` | The app icon master, 512 |
| `png/fathom-icon-*.png` | Every raster size |
| `mark.svg`, `mark-reversed.svg`, `mark-one-colour.svg` | The mark without its tile, for dark grounds, light grounds, and one ink |
| `wordmark.svg`, `wordmark-text.svg`, `wordmark-small.svg` | The wordmark's three tiers |
| `wordmark-reversed.svg` | The display tier for light grounds |
| `lockup.svg`, `lockup-reversed.svg` | Mark and word side by side |
| `lockup-stacked.svg`, `lockup-stacked-reversed.svg` | Stacked |

The shipped application icons are `crates/launcher-desktop/icons/icon.ico` and
`icon.png`; the installer's header and sidebar are `nsis-header.bmp`
(150 × 57) and `nsis-sidebar.bmp` (164 × 314) beside them. The game's copies
are `generated/Mark.java` and `assets/fathom-optimizations/icon/`. All are
build output.

Never: stretching, recolouring the arc outside these inks, the mark on a tile
of another colour, a drop shadow, or the star moved off the arc's centre.

## History

The Aperture mark (a frame open at one corner, with its missing corner set
inside) that came before this is in the git history, with its concept studies.
