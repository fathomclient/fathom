# Symbol concepts — design study

**Concept 06, "Aperture", was chosen**, replaced for a week by the Surface
identity, and then chosen again and developed: re-cut on an exact six-unit
grid, where the plane is the frame's own missing corner, and paired with a
lowercase wordmark. The product mark is that developed version; see
[`../README.md`](../README.md). This folder stays as the record of the original
study. Its files are not artwork and nothing references them; `logo-06.svg` is
the 64-unit original, not the shipped geometry.

## Contents

| File | What it is |
| --- | --- |
| `logo-01.svg` … `logo-06.svg` | Six symbol concepts, 64×64 viewBox |
| `brand-concepts.html` | Contact sheet: every concept at 16/24/32/64/128 on black and white, single-colour, on application tiles, in situ, plus wordmark studies |
| `build.js` | Regenerates the contact sheet from the SVGs — `node build.js` |

## The concepts

| # | Name | Idea |
| --- | --- | --- |
| 01 | Descending F | Stem plus two arms that shorten as they descend; the stem continues past the last arm into an accent foot — the part below the surface. |
| 02 | Stepped planes | Three identical planes stacked and stepped sideways, each further from the surface than the one above, deepest in accent. |
| 03 | Displaced F | An F broken along a horizontal fault, lower half stepped down and across, so it reads as a section rather than a letter. |
| 04 | Terraces | One continuous form stepping down and across in equal terraces, the last detached and in accent. |
| 05 | Notched slab | An F cut as two notches into a solid slab, the lower notch reaching further in so the stem narrows as it descends. |
| 06 | Aperture | A square frame opened at one corner with a smaller plane suspended below and behind it; the gap is the mark. |

## Construction rules

- 64×64 grid, 8 units of safe area on every side.
- Flat fills only. No gradients, no strokes, no type inside the mark.
- Minimum bar weight 10 units (2.5 px at 16×16); minimum negative gap 5 units.
- Base geometry uses `currentColor`; the single accent element uses
  `var(--fathom-accent, #c4303c)`. Set `--fathom-accent: currentColor` for a
  single-colour mark, or `#fff` for a knockout on an accent tile. Standalone in
  an `<img>` the mark falls back to black plus `#c4303c`.
- Every concept still reads when flattened to one colour — no concept depends
  on the accent to stay legible.

## What happened after 06 was picked

1. Hand-tuned 16, 20, 24, 40 and 48 px grids — done, in the since-removed
   `assets/brand/build-icons.js`. The developed mark needs no hand-tuning:
   one rule, u = ⌊S ÷ 8⌋, solves every size in `desktop/src/lib/brand/aperture.ts`.
2. Multi-size `.ico` (16 → 256) and the PNG set — done.
3. `assets/brand/logo.svg` replaced with the real mark, and the CSS-drawn
   square in `desktop/src/App.svelte` swapped for the mark itself — done.
4. Wordmark treatment A — uppercase, 600, +0.30em — chosen and outlined.
   Superseded: the developed identity sets "fathom" lowercase in Geist, with
   clear space and minimum sizes written in `../README.md`.
