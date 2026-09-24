# Brand assets

Fathom's identity is built on one construction: **the Surface**. Letters sit on
a 100-unit cap height, and an implied surface runs 42 units above the baseline.
Nothing is drawn along it. Everything below it is displaced slightly to the
right -- refraction, not a glitch.

Every file here is generated. The construction lives in
[`desktop/src/lib/brand/surface.ts`](../../desktop/src/lib/brand/surface.ts);
colours and construction numbers live in the theme manifest
([`assets/theme/fathom-theme.json`](../theme/fathom-theme.json), `system.brand`
and the `brand-*` palette entries). Regenerate with:

```
node assets/brand/build-brand.mjs
```

## The three marks

| Mark | Role | Files |
| --- | --- | --- |
| **Surface wordmark** | The primary wordmark | `wordmark.svg` (full tier), `wordmark-reduced.svg`, `wordmark-plain.svg`, `wordmark-dark.svg` |
| **Offset ring** | The primary app icon; the wordmark's O | `icon.svg`, `icon-{16,24,32,48,64}.svg`, `ring.svg` (untiled), `png/fathom-icon-*.png` |
| **Sounding Line** | Loading and progress; the smallest mark | `sounding-line.svg`, `sounding-line-tile.svg` |

The shipped application icons are `crates/launcher-desktop/icons/icon.ico`
(16, 20, 24, 32, 40, 48, 64, 128, 256) and `icon.png` (512). They are build
output: the executable's icon resource, the window and taskbar icon, and the
installer's icon all come from them.

## The wordmark

- Stems 15u, bars 12u, continuous outer corners 5u, inner corners 2u. The step
  where a stroke crosses the surface stays sharp.
- The displacement at a rendered size is about a sixth of the stem width,
  **snapped to whole pixels**. The surface itself falls on a pixel row. No
  fractional displacement, no blur.
- **48px cap and up:** full displacement, two densities, the 1px bronze detail
  along the lower-right edges above the surface.
- **20–47px:** a 1px displacement and the two densities; no edge detail.
- **Under 20px:** the plain letters. The icon carries the identity.
- Above the surface: ivory at 88%. Below: ivory-beneath at 90%, no edge.
- `wordmark-dark.svg` is the deep-brown variant for crops where bright gold
  sits behind the letters.

Never: chromatic aberration, RGB splits, several slices, jitter, noise, waves,
water, or anything nautical.

## The offset ring

The O's counter is dropped by 4u: walls of about 19u above, 15u at the sides,
11u below. The ring never takes the horizontal step. Ivory above the surface,
ivory-beneath below; the counter shows the tile, or the photograph when the
ring is used untiled at hero size.

Every size is solved in pixels for that size rather than scaled from one master
(`ringGeometry`): an even diameter so it centres, whole-pixel side walls, and a
counter that drops at least one full pixel -- so at 16px the top wall is still
visibly heavier than the bottom and the icon cannot become a generic circle.

| Size | Treatment |
| --- | --- |
| 16, 20 | One tone |
| 24–40 | Two tones: ivory above the surface, ivory-beneath below |
| 48 and up | Two tones and the bronze edge detail |

Keep the deep tile wherever contrast is not controlled (the OS, the taskbar,
the installer). The tile is a continuous-curve square.

## The Sounding Line

One stem crossing the surface and stepping right beneath it. The launcher uses
it as the loading and progress symbol (`SoundingLine.svelte`): with a known
value, its depth below the surface is the measure; without one it breathes
very slowly between two densities. It is not an app icon.

## Placement on the golden-hour background

For a hero or splash wordmark on `golden-hour.jpg`: the smooth rose sky near
the top, about 7–8% down; away from the horizon and palm band and from the
water. Do not line the wordmark's surface up with the photograph's horizon. A
very soft local darkening behind it is allowed (`veil` on `Wordmark.svelte`: at
most 18% at the centre, feathered to nothing). If a crop puts bright gold
behind the letters, use the dark variant.

## History

`concepts/` holds the six studies from the previous identity ("Aperture"),
kept as a record. Nothing ships from it.
