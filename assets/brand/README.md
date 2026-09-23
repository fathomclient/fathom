# Brand assets

The symbol is **concept 06, "Aperture"**: a square frame opened at its
bottom-right corner with a smaller plane suspended below and behind it. The gap
between the two planes is the mark. It was chosen from the six studies in
[`concepts/`](concepts/), which stay in the tree as the record of that decision.

Application icons are the two-tone treatment on a transparent ground, so the
operating system does not draw a black square around the mark.

| File | What it is |
| --- | --- |
| `logo.svg` | The mark for dark backgrounds — light frame, accent plane, transparent ground |
| `logo-on-light.svg` | The same mark for light backgrounds |
| `logo-mono.svg` | Single colour throughout, `currentColor`, for inline use and anywhere one ink is available |
| `app-icon.svg` | Icon artwork: the mark on transparent ground. Master for every raster below |
| `small/mark-16.svg`, `-20`, `-24` | The mark redrawn on its own pixel grid for small UI sizes |
| `png/fathom-icon-*.png` | 16, 20, 24, 32, 40, 48, 64, 128, 256, 512 — for installers, store listings, web |
| `wordmark.svg` | FATHOM, treatment A, outlined — for dark backgrounds |
| `wordmark-on-light.svg` | The same wordmark for light backgrounds |
| `wordmark-mono.svg` | The same wordmark in `currentColor` |
| `build-icons.js` | Generates every symbol and icon file above |
| `build-wordmark.py` | Generates the three wordmark files |

The shipped application icons are `crates/launcher-desktop/icons/icon.ico` and
`icon.png`, and they are build output — regenerate, don't hand-edit:

```
node assets/brand/build-icons.js
```

The wordmark has its own build, because outlining glyphs needs fontTools and
the icon build deliberately needs nothing:

```
python assets/brand/build-wordmark.py
```

## How it is drawn

The mark lives on a 64-unit grid: bars 10 units thick, 8 units of safe area,
the opening a third of the mark's width. That grid divides exactly at 32, 64,
128, 256 and 512, so those sizes are the master scaled and land on whole pixels
by themselves. 16, 20, 24, 40 and 48 do not divide cleanly, so each is drawn by
hand on its own pixel grid in `build-icons.js` — same construction, whole-pixel
bars, no antialiasing. Proportions hold to the master within a pixel, except at
16 where the mark is deliberately given a tighter margin so the bars can stay
three pixels wide.

Every shape is an axis-aligned rectangle on integer coordinates, which is why
the build needs no SVG renderer and no image library: it fills rectangles into
a buffer and writes the PNG and ICO containers itself.

Colours are the theme's, not new ones: frame `#f4f6f9` (`--text`), plane
`#c4303c` (`--accent`). The ground is transparent. No gradient anywhere. The
mark reads flattened to a single colour, which is what `logo-mono.svg` is for.

`icon.ico` carries 16, 20, 24, 32, 40, 48, 64, 128 and 256. Everything up to
128 is a BMP entry and only 256 is PNG-compressed: PNG-in-ICO has been legal
since Vista, but GDI+ and some installer toolchains still will not decode it,
and the small sizes are the ones shells and installers actually reach for.

The top bar draws the mark itself, inline in `desktop/src/App.svelte`, rather
than loading `small/mark-24.svg`: it is five rectangles, and an `<img>` would
cost a request to say the same thing. The geometry there is that file's, copied
— if the mark ever changes, both move together.

## The wordmark

Treatment A: FATHOM in Geist, weight 600, +0.30em tracking. Nothing is redrawn
and nothing is scaled non-uniformly — the only decisions are weight, case and
tracking. The glyphs are converted to outlines, because a wordmark set as
`<text>` renders in whatever the viewer happens to have installed, which for an
SVG dropped into a store listing or an installer is usually not Geist. The
source is the font the launcher already ships, under the SIL Open Font License,
which permits this; the result is artwork, not a redistributed font.

Kerning is applied on top of the tracking, because it is not negligible at this
width: FA and AT come to -83 and -96 units against 300 of tracking, a third of
the gap. The layout reproduces the browser's to within 0.4 of 4069 units.

The viewBox is the ink box, so it includes the O's overshoot — 16 units above
the caps and 16 below the baseline, out of 742. **Align lockups on the cap
height, not the box edge**, or the wordmark will sit 2% low against anything
aligned to its bounding box.

The top bar in `desktop/src/App.svelte` still sets FATHOM as live text rather
than loading this file, which is correct there: the app bundles Geist, so the
font is guaranteed, and text stays selectable and scales with the type ramp.

## Still outstanding

- Clear space, minimum sizes and recolouring rules are not written down yet.
