# Brand assets

Fathom's identity is **Aperture**: a mark built on a six-unit grid, and the
word "fathom" set lowercase in Geist.

Every file here is generated. The construction lives in
[`desktop/src/lib/brand/aperture.ts`](../../desktop/src/lib/brand/aperture.ts);
its numbers and inks are `system.brand` in the theme manifest
([`assets/theme/fathom-theme.json`](../theme/fathom-theme.json)); the
wordmark's outlines are
[`wordmark.generated.ts`](../../desktop/src/lib/brand/wordmark.generated.ts),
made from the launcher's Geist by `tools/generate-wordmark.py`. Regenerate
with:

```
node tools/generate-fathom-theme.mjs
python tools/generate-wordmark.py        (only when the wordmark changes)
node assets/brand/build-brand.mjs
```

## The mark

A frame six units square with one-unit walls, open at its bottom-right corner.
The missing corner, two units square, is set inside as the **plane**, moved in
by its own width, where it lands dead centre: one unit from every wall it
faces, its outer corner pointing straight at the opening. Wall, gap and plane
are 1, 1 and 2 units; the opening is 2. Nothing is placed by eye.

**Solved in pixels.** In an app icon of S px the unit is u = ⌊S ÷ 7⌋ and the
frame, 6u, is centred with a margin of (S − 6u) ÷ 2. At every size the icon
ships in (16, 20, 24, 30, 32, 36, 40, 48, 64, 128, 256, 512) both are whole
pixels, so no edge ever falls between two pixels. The tiers are set by 8u, the
size of the tile the mark was first drawn on.

| Icon | Tier | Treatment |
| --- | --- | --- |
| 16–20 | pixel | u = 2. Square corners everywhere: at 2 px walls a curve is only grey pixels. |
| 24–40 | curve | Outer corners 1.5u, inner corners 0.5u, concentric. Open ends cut square. |
| 48 and up | full | The plane's corners soften to 0.25u. |

Inks: frame ivory `#ededed`, plane sand `#a8afb3` (one density down).
Reversed, for light grounds: frame `#171d20`, plane `#646b6f`. Signal blue
belongs in marketing, never in the app icon.

The app icon is the mark alone -- frame and plane on transparency, no tile.
The deep tile it used to sit on read as a grey outline round the frame and a
grey square round the plane on a dark taskbar. The cost is contrast on light
grounds (a light taskbar, File Explorer in light mode), where the ivory frame is
faint; Windows has no light and dark variants of an executable's icon.

## The wordmark

"fathom", always lowercase, in Geist, outlined so it is the same drawing in
the launcher, the game and every file here. It is shaped by HarfBuzz, so
Geist's kerning is kept. What changes with size is weight and spacing, chosen
by the ascender (Geist's cap height) in **device** pixels:

| Tier | Ascender | Weight | Tracking | Where |
| --- | --- | --- | --- | --- |
| small | ≤ 16 px | SemiBold | +2% | the launcher header (13 px), the game (12 dp), the installer |
| text | 17–32 px | Medium | 0% | the header at 125% and up, the README, documents |
| display | ≥ 33 px | Medium | −1.5% | anything larger |

Up to 32 px the x-height is rounded to whole pixels. The minimum is a 10 px
ascender. The Standard capitalization setting never touches it; its
accessible name is "Fathom".

## The lockup

The mark stands on the wordmark's baseline and its plane's top is the
x-height, so the mark is one and a half x-heights tall, a little taller than
the ascenders. The gap is half the mark (3u). Stacked, the mark doubles its
unit and sits centred above, 3u clear. Clear space is the missing corner, 2u,
on every side.

## Loading

The mark is also the loading and progress symbol (`ApertureMark.svelte`).
With a known value the open corner closes as the work completes, over the
closed frame drawn at 16% so the measure is visible; without one the plane
breathes slowly between two densities. Done closes the frame with the plane in
the success ink; failed stops where it was with the plane in the danger ink.
Nothing spins, nothing travels; under reduced motion it holds still. At rest
the corner is always open: a closed frame means done.

## Files

| File | What it is |
| --- | --- |
| `icon.svg`, `icon-{16,20,24,30,32,36,40,48,64}.svg` | The app icon, untiled: the 512 master and the pixel masters |
| `png/fathom-icon-*.png` | Every raster size |
| `mark.svg`, `mark-reversed.svg`, `mark-one-colour.svg` | The mark without its tile, full tier |
| `wordmark.svg`, `wordmark-text.svg`, `wordmark-small.svg` | The wordmark's three tiers, ivory |
| `wordmark-reversed.svg` | The display tier for light grounds |
| `lockup.svg`, `lockup-reversed.svg` | Mark and word side by side |
| `lockup-stacked.svg`, `lockup-stacked-reversed.svg` | Stacked |

The shipped application icons are `crates/launcher-desktop/icons/icon.ico`
(16, 20, 24, 30, 32, 36, 40, 48, 64, 128, 256; 30 and 36 are the taskbar's
frames at 125% and 150%) and `icon.png` (512). The installer's header and
sidebar are `nsis-header.bmp` (150 × 57) and `nsis-sidebar.bmp` (164 × 314)
beside them. All are build output.

Never: glow, chromatic aberration, RGB splits, stretching, a closed frame at
rest, a moved or resized plane, waves, water, or anything nautical.

## History

`concepts/` holds the six symbol studies that first produced Aperture. The
Surface identity (refraction wordmark, offset ring, Sounding Line) that
replaced it for a week is retired; its construction is in the history at
commit 25fcc91 (`desktop/src/lib/brand/surface.ts`).
