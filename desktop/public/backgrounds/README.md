# Background asset catalog

The launcher plays the `.webm` files and uses the matching `.jpg` as a poster
while each video decoder starts. Run `tools/prepare-background-video.ps1` to
rebuild them from the project owner's original recordings in `Downloads`.

| Asset | Scene | Source |
| --- | --- | --- |
| `bg_1` | Cherry grove | `bg_1.mp4` |
| `bg_2` | Snowfall | `bg_2.mp4` |
| `bg_3` | Midnight coast | `bg_3.mp4` |
| `bg_4` | Savanna village | `bg_4.mp4` |
| `bg_5rain` | Rainy jungle bridge | `bg_5rain.mp4` |
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
