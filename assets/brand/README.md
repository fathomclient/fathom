# Brand assets

The Fathom mark is an arc that sweeps up to a bright point, with a small
four-pointed star at the centre of the sweep, on a dark rounded tile. The
wordmark is "fathom" in lowercase Geist, ivory, warming to the arc's blue over
the last letters.

Everything in this folder is generated, so edit the sources rather than the
files:

- the mark's geometry: `desktop/src/lib/brand/orbit.ts` (on a 512 grid)
- colours and lockup proportions: `system.brand` in `assets/theme/fathom-theme.json`
- the wordmark outlines: `desktop/src/lib/brand/wordmark.generated.ts`, made from
  Geist by `tools/generate-wordmark.py`

To regenerate:

```powershell
node tools/generate-fathom-theme.mjs
python tools/generate-wordmark.py      # only if the wordmark's letters change
node assets/brand/build-brand.mjs
```

## Colours

| Part | Colour |
| --- | --- |
| Tile | `#141c25` → `#101820` → `#080e13`, with a `#35495e` rim |
| Halo | `#4c9afb` at 22%, fading out |
| Arc | `#5b9ceb` → `#a5d6ff` → `#8fc8ff` → `#d3e9ff`, tail to tip |
| Star | `#c9eaff` over a `#75bdff` glow |

On light backgrounds the reversed files use deeper blues (`#1f5fb0` to
`#4c9afb`) and drop the glow.

## Icon sizes

Each raster is drawn at its own size rather than scaled down from the master,
so edges land on whole pixels and the arc never thins below about a pixel.

| Size | Used for |
| --- | --- |
| 16–256 | `icon.ico`: Explorer, taskbar, Start |
| 512 | `icon.png`: the window icon and installer |
| 16, 32 | the game window |
| 128 | the mod's icon |

## Wordmark sizes

| Cap height | Weight | Tracking | Used for |
| --- | --- | --- | --- |
| up to 16 px | SemiBold | +2% | launcher header, in game, installer |
| 17–32 px | Medium | 0% | larger headers, splash |
| 33 px and up | Medium | −1.5% | anything bigger |

## Files

| File | What it is |
| --- | --- |
| `icon.svg` | App icon master |
| `png/fathom-icon-*.png` | Raster sizes |
| `mark*.svg` | The mark without its tile (dark, light, one colour) |
| `wordmark*.svg` | The wordmark at each size, plus a light-background version |
| `lockup*.svg` | Mark and wordmark together, side by side or stacked |

Please don't stretch the mark, recolour the arc, put it on a different tile
colour, or add a drop shadow.
