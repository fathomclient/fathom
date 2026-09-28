# Background library

The launcher's backgrounds are the user's to choose (Settings → Appearance →
Background, or the picker in the bottom bar), and the game's title screen, Hub
and Fathom menu backdrops follow the same choice. The catalogue is
[`assets/backgrounds/manifest.json`](../../../assets/backgrounds/manifest.json);
everything in this folder is listed there. Credit and licence status:
[`ATTRIBUTION.md`](../../../ATTRIBUTION.md).

Per background:

| File | What it is |
| --- | --- |
| `<id>.webm` | A video background: VP9, looping, silent. |
| `<id>.jpg` / `bg_5rain.png` | The full-resolution still. For a video it is frame 0 of the clip, shown until the first frame decodes and instead of the video if it cannot play. For an image it is the background. |
| `thumbs/<id>.jpg` | 384×216 picker thumbnail. Never shown full size. |

| id | Name | Type | Native size | Notes |
| --- | --- | --- | --- | --- |
| `bg_1` | Cherry Grove | video | 1600×900 | blur baked in (legacy encode) |
| `bg_2` | Snowfall | video | 1600×900 | blur baked in; very dark |
| `bg_3` | Midnight Coast | video | 1600×900 | blur baked in; very dark |
| `bg_4` | Savanna | video | 1600×900 | blur baked in |
| `bg_5rain` | Rainforest Storm | video | 1920×1080 | **default**; unblurred, CRF 23, from the game's frame sequence |
| `bg_5noraintickfrozen` | Rainforest Twilight | video | 1600×900 | blur baked in |
| `bg_5noraintickunfrozen` | Rainforest Drift | video | 1600×900 | blur baked in |
| `bg_6clear` | Metropolis | video | 1600×900 | blur baked in |
| `bg_6midnight` | Midnight Metropolis | video | 1600×900 | blur baked in; 6 s loop |
| `bg_6pull_*` (5) | City Overlook, Moon Tower, Neon Grid, Red Tower, Storm Panorama | image | 1728×972 | |
| `golden-hour` | Golden Hour | image | 1920×1080 | licence not yet confirmed |
| `graphite` | Graphite | image | 2560×1440 | source not yet recorded |

The legacy videos were encoded with a Gaussian blur (sigma 3.5) at 1600×900,
CRF 30. That cannot be undone from these files: rebuild them from the original
recordings with `tools/prepare-background-video.ps1`, which now keeps them
unblurred at 1920×1080, CRF 23. Then run `node tools/prepare-background-library.mjs`
for the stills, thumbnails and the game's copies, and
`node tools/generate-backgrounds.mjs` if the manifest changed.

To add a background: add its files here, add an entry to the manifest, credit it
in `ATTRIBUTION.md`, add its id to `BACKGROUNDS` in
`crates/launcher-desktop/src/lib.rs` (a test checks the two agree), and run the
two scripts above, then `node tools/derive-background-accents.mjs` and
`node tools/generate-backgrounds.mjs` again for its accent.
