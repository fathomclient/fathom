# Backgrounds

The launcher's backgrounds, which you pick under Settings → Appearance (the
game's menus follow the same choice). The list is
[`assets/backgrounds/manifest.json`](../../../assets/backgrounds/manifest.json),
and credits are in [`ATTRIBUTION.md`](../../../ATTRIBUTION.md).

Each background has:

| File | What it is |
| --- | --- |
| `<id>.webm` | The video: VP9, looping, no sound |
| `<id>.jpg` | A full-size still, shown until the video starts (or instead of it) |
| `thumbs/<id>.jpg` | A 384×216 thumbnail for the picker |

| id | Name |
| --- | --- |
| `bg_1` | Cherry Grove |
| `bg_2` | Snowfall |
| `bg_3` | Midnight Coast |
| `bg_4` | Savanna |
| `bg_5rain` | Rainforest Storm (default) |
| `bg_5noraintickfrozen` | Rainforest Twilight |
| `bg_5noraintickunfrozen` | Rainforest Drift |
| `bg_6clear` | Metropolis |
| `bg_6midnight` | Midnight Metropolis |
| `bg_6tokyo` | Tokyo Tower |
| `bg_6aurora` | Aurora |

All are 1920×1080 at CRF 23.

## Adding one

1. Encode the recording with `tools/prepare-background-video.ps1`.
2. Add an entry to the manifest and credit it in `ATTRIBUTION.md`.
3. Add its id to `BACKGROUNDS` in `crates/launcher-desktop/src/lib.rs` (a test
   checks they match).
4. Run `node tools/prepare-background-library.mjs` for the stills and
   thumbnails, then `node tools/derive-background-accents.mjs` and
   `node tools/generate-backgrounds.mjs`.
