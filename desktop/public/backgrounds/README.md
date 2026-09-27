# Background asset catalog

**The launcher has one approved background: `bg_5rain.png` (Rainforest Storm).** It is listed
in `desktop/src/lib/backgrounds.ts` and in the Rust allowlist
(`crates/launcher-desktop/src/lib.rs`), which a test keeps in step. If it cannot
load, the launcher shows the neutral gradient `--backdrop-fallback`; that gradient
is a failure-state substrate, not a background anyone can choose. Credit and
licence status: `ATTRIBUTION.md`.

The 1920×1080 PNG is the exact approved poster shared with the Minecraft menu.
It is cover-scaled without additional global blur so the rain, bridge and forest
remain recognizable.

To add a curated background later: put `<id>.png` here, add it to both lists,
and credit it. The picker appears on its own once there is more than one.

## Retired

Everything below is retired in place. None of it is listed, selectable or
loaded, and a stored preference naming any of it is migrated to `bg_5rain`
on the next start. The files are kept until they are deleted deliberately;
note that Vite still copies this whole folder into the bundle, so they add
about 3.9 MB to the executable until then.

The launcher used to play the `.webm` files, with the matching `.jpg` as a poster
while each video decoder starts. Run `tools/prepare-background-video.ps1` to
rebuild them from the project owner's original recordings in `Downloads`.

| Asset | Scene | Source |
| --- | --- | --- |
| `bg_1` | Cherry grove | `bg_1.mp4` |
| `bg_2` | Snowfall | `bg_2.mp4` |
| `bg_3` | Midnight coast | `bg_3.mp4` |
| `bg_4` | Savanna village | `bg_4.mp4` |
| `bg_5rain.webm` / `bg_5rain.jpg` | Retired launcher motion/low-res poster; Rainforest Storm now uses the full-resolution PNG | `bg_5rain.mp4` |
| `bg_5noraintickfrozen` | Still jungle twilight | `bg_5noraintickfrozen.mp4` |
| `bg_5noraintickunfrozen` | Moving jungle twilight | `bg_5noraintickunfrozen.mp4` |
| `bg_6midnight` | Midnight metropolis | `bg_6midnight.mp4` |
| `bg_6clear` | Daylight metropolis | `bg_6clear.mp4` |

The `bg_6pull_*.jpg` files are 1728×972 stills extracted from
`bg_6screenshotPulls.mp4`. They crop 96 pixels from each side and the bottom
108 pixels, removing the hotbar while preserving a 16:9 image:

| Still | Source frame |
| --- | ---: |
| `bg_6pull_city-overlook.jpg` | 240 |
| `bg_6pull_moon-tower.jpg` | 780 |
| `bg_6pull_red-tower.jpg` | 840 |
| `bg_6pull_storm-panorama.jpg` | 1200 |
| `bg_6pull_neon-grid.jpg` | 1560 |

The bg_5 and bg_6 recordings were made in community-made worlds; their creator
credits are in [`ATTRIBUTION.md`](../../../ATTRIBUTION.md).
