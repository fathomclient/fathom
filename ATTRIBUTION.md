# Attribution

Fathom's own code is licensed under Apache-2.0 (see [LICENSE](LICENSE)). This
file records the creators whose work appears in this repository, and the terms
it is used under. Anything added to the repository that was not made for Fathom
must be recorded here before it is committed.

Fathom is an independent Minecraft mod. Neither Fathom nor The Fathom Project is affiliated with, endorsed by, or otherwise connected to Mojang AB or the Microsoft Corporation. The terms 'Minecraft,' 'Mojang,' 'Microsoft,' and any other product marks, trademarks, logos, word marks, or service marks, along with Minecraft and underlying materials therein are the property of their respective owners. Use of the foregoing does not imply any affiliation with or endorsement by Mojang or Microsoft.

The launcher downloads the game from Mojang's own servers and does not
redistribute it.

---

## Libraries bundled in Fathom for Forge

The Forge build of the client (`gradle forgeJar`, `fathom-forge-*.jar`) carries
three libraries Forge 1.8.9 does not provide. The Fabric build carries none of
them; Fabric's loader supplies its own. The jar includes
`META-INF/THIRD-PARTY-NOTICES.txt` with these notices and the licence texts.

| Library | Version | Licence | Notes |
|---|---|---|---|
| [Mixin](https://github.com/FabricMC/Mixin) (SpongePowered; Fabric's fork) | 0.17.3+mixin.0.8.7 | MIT | Unmodified; its LaunchWrapper service starts the client under Forge |
| [MixinExtras](https://github.com/LlamaLad7/MixinExtras) (LlamaLad7) | 0.5.4 | MIT | Unmodified |
| [ASM](https://asm.ow2.io) (OW2; INRIA, France Telecom) | 9.6, plus five classes from 6.2.1 | BSD-3-Clause | **One method modified**: `Type.getTypeInternal` returns an internal-name type for a non-descriptor instead of throwing, the ASM 5 behaviour Forge 1.8.9 relies on. The `Remapping*Adapter` classes are 6.2.1's, unmodified |

Neither Forge nor its deobfuscation table is redistributed: the build reads the
table to rename Fathom's references and copies none of it into the jar.

## Background footage

Every recording below is a user-selectable background again (see
`assets/backgrounds/manifest.json`). Each has, beside the files listed, a
full-resolution still (`<id>.jpg`, frame 0 of the clip), a picker thumbnail
(`thumbs/<id>.jpg`) and a copy of the still for the game's menus
(`textures/menu/scenes/<id>.jpg`).

all of the background footage was recorded in minecraft by me.
Some recordings were made in vanilla worlds. Others were made in community-made
worlds whose creators permit their use with credit; those creators are credited
below.

### bg_1 – bg_4

Recorded in vanilla Minecraft.

- `desktop/public/backgrounds/bg_1` – `bg_4` (`.webm`, `.jpg`)

### bg_5 — Tideworn

| | |
|---|---|
| World | [Tideworn - 8 sqkm Forgotten Flooded City](https://www.curseforge.com/minecraft/worlds/tideworn-8-sqkm-forgotten-flooded-city) |
| Creator | [milkshake_mp3](https://www.curseforge.com/members/milkshake_mp3/projects) |

All bg_5 recordings were taken in this world, with the help of native game
commands such as `/tick freeze` and `/tick rate`.

Assets derived from these recordings:

- `desktop/public/backgrounds/bg_5rain` (`.webm`, `.png`) — the `.webm` is
  re-encoded from the game's frame sequence below
- `minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu/bg_5rain/`
  (`high/`, `low/` frame sequences) and `textures/menu/main-menu-bg.png`
- `desktop/public/backgrounds/bg_5noraintickfrozen` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_5noraintickunfrozen` (`.webm`, `.jpg`)

### bg_6 — Tokyo Inspired City

| | |
|---|---|
| World | [Tokyo Inspired City](https://www.curseforge.com/minecraft/worlds/tokyo-inspired-city) |
| Creator | [CoinCoffer](https://www.curseforge.com/members/coincoffer/projects) |

All bg_6 recordings were taken in this world, with the help of
native game commands such as `/tick freeze` and `/tick rate`.

Assets derived from these recordings:

- `desktop/public/backgrounds/bg_6clear` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_6midnight` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_6tokyo` (`.webm`, `.jpg`)
- `desktop/public/backgrounds/bg_6aurora` (`.webm`, `.jpg`)

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
