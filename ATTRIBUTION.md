# Attribution

Fathom's own code is licensed under Apache-2.0 (see [LICENSE](LICENSE)). This
file records the creators whose work appears in this repository, and the terms
it is used under. Anything added to the repository that was not made for Fathom
must be recorded here before it is committed.

Fathom is not an official Minecraft product. It is not approved by or associated
with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB. The
launcher downloads the game from Mojang's own servers and does not redistribute
it.

---

## Launcher background

### golden-hour

| | |
|---|---|
| Image | Palm Tree Sunset Water |
| Credited to | showtime, at Wallpapers.com |
| Source | <https://wallpapers.com/wallpapers/palm-tree-sunset-water-63gj3uifdzlwg9vh.html> |
| Licence | **Not yet confirmed.** Wallpapers.com hosts user uploads; the uploader is not necessarily the photographer, and the site does not grant redistribution rights by default. Confirm the terms, or replace the image, before a public release. |

- `desktop/public/backgrounds/golden-hour.jpg` — the launcher's default and
  currently only background. Credited in the launcher under Settings →
  Appearance.

## Background footage

The launcher no longer offers these recordings as backgrounds; the files are
retired in place and not loaded. The in-game main menu still uses the bg_5 and
bg_6 material listed below.

All background footage was recorded in Minecraft by the Fathom project owner.
Some recordings were made in vanilla worlds. Others were made in community-made
worlds whose creators permit their use with credit; those creators are credited
below.

### bg_1 – bg_4

Recorded in vanilla Minecraft.

- `desktop/public/backgrounds/bg_1` – `bg_4` (`.webm`, `.jpg`)
- `crates/launcher-ui/assets/backgrounds/bg_1/` – `bg_4/`

### bg_5 — Tideworn

| | |
|---|---|
| World | [Tideworn - 8 sqkm Forgotten Flooded City](https://www.curseforge.com/minecraft/worlds/tideworn-8-sqkm-forgotten-flooded-city) |
| Creator | [milkshake_mp3](https://www.curseforge.com/members/milkshake_mp3/projects) |

All bg_5 recordings were taken in this world, with the help of native game
commands such as `/tick freeze` and `/tick rate`.

Assets derived from these recordings:

- `desktop/public/backgrounds/bg_5rain` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_5noraintickfrozen` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_5noraintickunfrozen` (`.webm`, `.jpg`)
- `minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu/bg_5rain/`
  — the in-game main-menu video
- `minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu/main-menu-bg.png`
  — the in-game main-menu fallback still

### bg_6 — Tokyo Inspired City

| | |
|---|---|
| World | [Tokyo Inspired City](https://www.curseforge.com/minecraft/worlds/tokyo-inspired-city) |
| Creator | [CoinCoffer](https://www.curseforge.com/members/coincoffer/projects) |

All bg_6 recordings and screenshots were taken in this world, with the help of
native game commands such as `/tick freeze` and `/tick rate`.

Assets derived from these recordings:

- `desktop/public/backgrounds/bg_6clear` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_6midnight` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_6pull_city-overlook.jpg`
- `desktop/public/backgrounds/bg_6pull_moon-tower.jpg`
- `desktop/public/backgrounds/bg_6pull_neon-grid.jpg`
- `desktop/public/backgrounds/bg_6pull_red-tower.jpg`
- `desktop/public/backgrounds/bg_6pull_storm-panorama.jpg`

---

## Fonts

### Geist

Copyright (c) 2023 Vercel, in collaboration with basement.studio. Licensed under
the SIL Open Font License, Version 1.1. The full licence is kept beside each
copy of the font:

- `desktop/src/lib/fonts/Geist-Variable.woff2` — `desktop/src/lib/fonts/Geist-LICENSE.txt`
- `minecraft/fathom-optimizations/src/main/resources/assets/fathom/font/Geist-*.ttf`
  — `minecraft/fathom-optimizations/src/main/resources/assets/fathom/font/Geist-LICENSE.txt`

---

## Icons

### Lucide

The SVG icons in `assets/icons/`, and the icon data generated from them for the
launcher and the in-game client, come from [Lucide](https://lucide.dev). See
`assets/icons/README.md`.

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2023 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2025.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The MIT License (MIT) (for portions derived from Feather)

Copyright (c) 2013-2023 Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
