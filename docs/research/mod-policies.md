# Third-Party Mod Policies and Licensing

Status: **research / pre-implementation**.
Date of research: 2026-08-23.

> **This is engineering research, not legal advice.** Every licence conclusion below should be
> reviewed by counsel before we ship anything that relies on it. Where I could not verify something
> from a primary source, it is marked **[unverified]**.

---

## 1. The three questions we have to answer for every mod

For each project we care about, three *separate* questions:

1. **Can we read it?** (Almost always yes, if the source is public.)
2. **Can we reuse its code?** (Depends entirely on the licence.)
3. **Can our launcher download or ship it for the user?** (A different question again -- distribution
   rights, host terms of service, and author opt-outs all apply independently of the code licence.)

Conflating these is how projects end up with licence violations. A mod can be readable, unreusable,
and undistributable all at once -- OptiFine is exactly that.

---

## 2. Findings table

| Project | Licence | Source | 1.8.9? | Maintained | Reuse code? | Auto-download? |
|---|---|---|---|---|---|---|
| **OptiFine** | Proprietary, all rights reserved | **No** (partial, on request) | Yes | Yes | **No** | **No** |
| **Patcher** (Sk1erLLC) | CC BY-NC-SA 4.0 | Yes | Yes | Stale (last push 2024-03) | **No** (see §3) | Case-by-case |
| **PolyPatcher** (Polyfrost) | CC BY-NC-SA 4.0 | Yes | Yes | **Yes** (2026-01) | **No** (see §3) | Case-by-case |
| **FoamFix** | LGPL-3.0-or-later | Yes | Yes (`0.6.3a`) | No (1.8.9 branch long dead) | Yes, as separate jar | Likely yes |
| **BetterFps** | LGPL-2.1 | Yes | Yes | No | Yes, as separate jar | Likely yes |
| **MemoryFix** | **None** -- all rights reserved | Yes (readable) | Yes (1.8.9-only) | No, archived 2019 | **No** | **No** |
| **MouseDelayFix** | **None** -- all rights reserved | Yes (readable) | Yes | No, archived 2018 | **No** | **No** |
| **FastCraft** | Closed source | No | No (1.7.10) | Discontinued | **No** | **No** |
| **FPS Plus / "FPS+"** | **[unverified]** -- no canonical repo found | Unclear | Claimed | Unclear | **Assume no** | **Assume no** |
| **Fabric Loader** (Legacy Fabric) | Apache-2.0 | Yes | Yes | Yes | **Yes** | Yes |
| **Mixin** (SpongePowered) | MIT | Yes | Yes | Yes (2024) | **Yes** | Yes |
| **yarn-1.8.9 / Legacy-Intermediaries** | CC0-1.0 | Yes | Yes | Yes | **Yes** | Yes |
| **OneConfig** (Polyfrost) | LGPL-3.0 + Polyfrost Additional Terms | Yes | Yes | Yes (active) | Only as separate jar, terms need review | Case-by-case |
| **legacy-lwjgl3** | LGPL-2.1 | Yes | **Yes, tested** | No (2023) | Separate jar only | Likely yes |
| **lwjgl3ify** | LGPL-3.0 | Yes | No (1.7.10) | Yes | Separate jar only | N/A |
| **Minecraft Forge** | LGPL-2.1 **plus additional terms**; GitHub reports `NOASSERTION` | Yes | Yes | Not for 1.8.9 | Needs legal review | Yes (standard practice) |
| **Sodium / Lithium** (CaffeineMC) | LGPL-3.0 (Lithium confirmed) | Yes | **No** (1.16+) | Yes | N/A for 1.8.9 | N/A |

---

## 3. The CC BY-NC-SA problem (Patcher / PolyPatcher)

This is the most consequential licensing finding in this research, so it gets its own section.

Both Patcher and PolyPatcher are licensed **CC BY-NC-SA 4.0**. PolyPatcher's README states it
directly: *"This work, 'PolyPatcher', is adapted from 'Patcher' by Sk1erLLC, used under CC BY-NC-SA
4.0. 'PolyPatcher' is licensed under CC BY-NC-SA 4.0 by Polyfrost."*

Three independent problems for us:

**a) NonCommercial.** The NC clause forbids use "primarily intended for or directed toward commercial
advantage or private monetary compensation." Its boundaries are notoriously unclear for software.
Even if we never charge a cent, taking donations, having a Patreon, or being acquired later could all
put us on the wrong side of it -- and we would have baked that ambiguity into our codebase
permanently.

**b) ShareAlike.** Any adaptation must be released under CC BY-NC-SA 4.0 or a CC-approved compatible
licence. Since CC BY-NC-SA is **not an open-source licence** (it fails the Open Source Definition's
prohibition on field-of-use restrictions, OSD #6), deriving from Patcher/PolyPatcher would make our
project **not open source**. That contradicts the first line of the product spec.

**c) Creative Commons themselves recommend against using CC licences for software**, because they
lack the patent, source-availability, and linking provisions that software licences need. This is a
licence chosen for a mod, not for a library, and it should not be treated as a reusable component.

There is a further wrinkle: PolyPatcher's README also states it uses code from CaffeineMC's Lithium
and Hydrogen, both **LGPL-3.0**. Whether an LGPL-3.0 contribution can be redistributed inside a
CC BY-NC-SA 4.0 work is, at minimum, a question worth not inheriting.

### Policy

> **We will not copy, port, translate, decompile, or adapt any code from Patcher or PolyPatcher.**
> We may read their public feature lists and changelogs to learn *which problems are worth solving* --
> that is factual information about Minecraft's behaviour, not their expression -- and then implement
> our own solutions from the game's own code.

Anyone who has read PolyPatcher's source should not be the person who writes our equivalent feature.
If that becomes impractical for a specific optimization, the fallback is to leave it to the
third-party mod and not implement it ourselves.

---

## 4. OptiFine

OptiFine's copyright notice is unambiguous: the mod *"may not be reproduced under any circumstances
except for personal, private use as long as it remains in its unaltered, unedited form."* Public
distribution requires advance written permission; modification is forbidden; placing it on websites
or bundling it with other projects or services is forbidden.

Source is not published. sp614x's stated reason is that OptiFine's core is an extensive
reorganisation of Minecraft's rendering code, and publishing it in full would run into Minecraft's own
EULA.

OptiFine's FAQ additionally states that it does not support PvP clients, on the grounds that most of
them illegally redistribute OptiFine without permission. We should read that as a direct description
of the category we are entering, and behave differently.

### Policy

- **Never bundle OptiFine.**
- **Never auto-download OptiFine**, including from optifine.net. The official downloads sit behind an
  ad-supported interstitial; scripting past it would both breach the distribution terms and strip
  the author's only revenue.
- **Never modify or repackage the OptiFine jar.**
- **Do** support it: if the user places `OptiFine_1.8.9_HD_U_*.jar` into the Forge profile's `mods`
  directory themselves, the launcher should detect it, adapt its own optimization defaults to avoid
  conflicts (see §7), and link to https://optifine.net for downloads.

---

## 5. The unlicensed mods (MemoryFix, MouseDelayFix)

Both `prplz/MemoryFix` and `prplz/MouseDelayFix` are public, archived (2019 and 2018), and contain
**no licence file**. Under default copyright that means **all rights reserved**: readable on GitHub,
but not reusable, not redistributable, not forkable in any legally meaningful way.

This is awkward, because they are also two of the highest-value and smallest fixes in the whole
1.8.9 space.

The path through is that **their READMEs describe the defects in prose, and in MouseDelayFix's case
the canonical fix is Mojang's own.** MouseDelayFix's README lays out the bug (`EntityLivingBase.getLook`
using `rotationYawHead`), quotes **Mojang's 1.11 fix** (`EntityPlayerSP.getLook` overriding to use
`rotationYaw`), and then shows its own variant. We can implement Mojang's 1.11 behaviour directly.
Facts about how a program misbehaves, and an API-level behavioural fix published by the original
rightsholder, are not prplz's expression.

Likewise, MemoryFix's README identifies its three targets in plain English -- the OptiFine cape leak
(sp614x/optifine#526), oversized resource-pack icons, and `System.gc()` on world change -- and even
notes that the third is achievable from the launcher with `-XX:+DisableExplicitGC`. All three can be
addressed from the descriptions.

### Policy

- **Do not vendor, fork, redistribute, or auto-download these jars.**
- **Do** implement equivalent fixes independently, documenting the *public defect* (Mojang bug ID,
  OptiFine issue number) as the basis rather than the mod.
- Credit them in our documentation as the projects that identified the problems. Attribution is
  courtesy here, not a licence obligation, and courtesy is cheap.
- If in doubt on a specific fix, contact the author. The repos are archived but the author is
  reachable, and an explicit permissive relicence would be worth asking for.

---

## 6. LGPL mods (FoamFix, BetterFps, legacy-lwjgl3, lwjgl3ify, OneConfig)

The LGPL family is workable but requires care in a Java-mod context.

- **FoamFix** is LGPL-3.0-or-later. The licence header at `docs/licenses/COPYING` states it
  explicitly. `0.6.3a` supports 1.8.9 (confirmed via Modrinth's version API). Note the repository has
  no top-level `LICENSE` file, so GitHub's API reports no licence -- the real terms are in
  `docs/licenses/`. Modrinth lists it as `LicenseRef-Custom` pointing at that file.
- **BetterFps** is LGPL-2.1 (`LICENSE` on the `1.12` branch is the LGPL 2.1 text).
- **legacy-lwjgl3** is LGPL-2.1; **lwjgl3ify** is LGPL-3.0.
- **OneConfig** is **LGPL-3.0 plus "Additional Terms Applicable to OneConfig" published by Polyfrost**
  (v1.1 or later, at our option). The additional terms have not been reviewed here and **must be**
  before we depend on it. PolyPatcher requires OneConfig as of its 1.9.0, so this matters if we ever
  interoperate.

### Policy

- **We may depend on LGPL mods as separate jars** loaded alongside ours. That is ordinary use, not
  derivation.
- **We must not shade or vendor LGPL code into our own mod jar.** LGPL-3 §4 permits combined works
  under conditions (relinking, source provision, licence notice), but satisfying those inside a
  shaded Minecraft mod jar is fiddly and creates ongoing obligations. Not worth it for the gains
  available.
- If we ever want a specific FoamFix or BetterFps technique, we implement it ourselves from the
  described behaviour, exactly as with the CC-licensed mods.

---

## 7. Automatic downloading policy

Even where a licence permits redistribution, **downloading on a user's behalf is a distribution act**
and is separately governed by the host's terms and the author's wishes.

Two concrete constraints:

- **CurseForge** lets each project owner toggle third-party distribution. When it is off, *"any
  3rd-party service, website or client will not have access to your projects or files via the
  3rd-party API"* -- which is why third-party tools have to fall back to "open this in your browser"
  for some mods. Any CurseForge integration we build **must honour that flag and must never work
  around it** by scraping the website. It also requires an API key, which comes with its own terms.
- **Modrinth** exposes licence metadata per project and is generally friendlier to third-party
  clients, but that does not override an individual project's licence.

### Policy

Our launcher will implement automatic mod downloading only under **all** of these conditions:

1. The user explicitly asked for that specific mod. **No mod is ever installed silently.**
2. The download comes from the **author's official distribution channel** (Modrinth, CurseForge via
   the official API, or the project's own release page) -- never a mirror, never a scrape.
3. The host's API permits it, including any author opt-out flag.
4. The mod's licence permits redistribution, *or* we are merely fetching from the official host on
   the user's behalf and any host terms allow that.
5. The file is verified against a publisher-provided hash before it is written to disk.
6. The user is shown the source URL, the licence, and the file hash before the download starts.

**Never auto-downloaded, under any circumstance:** OptiFine, MemoryFix, MouseDelayFix, FastCraft, or
any project with unclear licensing.

**Never bundled in our installer:** anything we do not have explicit written distribution rights to.
This includes every Minecraft asset -- the client jar, libraries, and assets are always fetched from
Mojang's own CDN at install time, verified by the SHA-1 hashes Mojang publishes.

---

## 8. Compatibility notes

- **OptiFine vs. our optimizations.** OptiFine rewrites large parts of the renderer. Anything we do
  to font rendering, entity culling, chunk rendering, or tile entities is a candidate conflict.
  PolyPatcher's own feature list is full of OptiFine-specific accommodations -- "Smart Entity Culling
  (disable entity culling when using OptiFine shaders ... due to the way OptiFine shaders work, we
  are unable to make Entity Culling compatible)", "OptiFine Custom Sky Fix", "Smart Fullbright",
  "Simplify FPS Counter", "Use Vanilla Metrics Renderer". **We must detect OptiFine at load and
  disable our conflicting patches by default rather than fighting it.**
- **OptiFine shaders specifically disable entity-culling correctness.** Treat "shaders present" as a
  hard gate on our culling features.
- **Patcher/PolyPatcher vs. our mod.** Heavy overlap. If a user runs both, we should detect it and
  default our overlapping features off, deferring to the mod the user explicitly chose to install.
- **BetterFps vs. anything.** It rewrites `MathHelper` via ASM. Coremod-level conflicts with other
  ASM transformers are plausible; it is also unmaintained.
- **Mixin version skew on Forge 1.8.9** is a classic failure mode: mods pinning
  `"minVersion": "0.7.11"` versus shims providing 0.8.5 (GasStation, UniMixins, MixinBooterLegacy).
  This is one more reason the Legacy Fabric profile is the primary target.

---

## 9. Our own licence -- decision needed

This must be settled **before** any dependency choice forces it.

| Option | Consequence |
|---|---|
| **Apache-2.0** | Permissive, patent grant, widely trusted. Rules out Slint (unless commercial licence), rules out vendoring any LGPL code. **Recommended.** |
| **MIT** | Simplest, no patent grant. |
| **GPLv3** | Required if we use Slint's free arm; maximises copyleft; narrows contributor and downstream reuse. |
| **LGPL-3.0** | Middle ground, but adds relinking obligations for a desktop app that has no plugin ABI. Little benefit. |
| **CC BY-NC-SA** | **Never.** Not an open-source licence; see §3. |

**Recommendation: Apache-2.0**, with a `cargo-deny` licence allowlist in CI that fails the build on
any GPL/LGPL/CC-NC dependency entering the tree. The whole reason we chose the Legacy Fabric stack
(Apache-2.0 loader, MIT Mixin, CC0 mappings) is that it keeps this option open. Do not spend it
casually.

---

## 10. Sources

- OptiFine copyright notice -- https://optifine.net/copyright
- OptiFine FAQ (source availability, PvP clients) -- https://optifine.readthedocs.io/faq.html
- Patcher (CC BY-NC-SA 4.0) -- https://github.com/Sk1erLLC/Patcher/blob/master/LICENSE.md
- PolyPatcher README and licence -- https://github.com/Polyfrost/PolyPatcher
- FoamFix licence header (LGPL-3.0-or-later) -- https://github.com/asiekierka/FoamFix/blob/0.9.x-1.12.2/docs/licenses/COPYING
- FoamFix 1.8.9 version (`0.6.3a`) -- https://modrinth.com/mod/foamfix/versions
- BetterFps (LGPL-2.1) -- https://github.com/Guichaguri/BetterFps
- MemoryFix (archived, no licence) -- https://github.com/prplz/MemoryFix
- MouseDelayFix (archived, no licence) -- https://github.com/prplz/MouseDelayFix
- OneConfig (LGPL-3.0 + Polyfrost Additional Terms) -- https://github.com/Polyfrost/OneConfig/blob/v1/LICENSE
- SpongePowered/Mixin (MIT) -- https://github.com/SpongePowered/Mixin
- Legacy Fabric repositories and licences -- https://github.com/Legacy-Fabric
- CurseForge project distribution toggle -- https://support.curseforge.com/support/solutions/articles/9000207877-project-distribution-toggle
- CurseForge mod authors terms -- https://legal.overwolf.com/docs/curseforge/mod-authors-terms/
- asie, "The Overwolf API and You" -- https://blog.asie.pl/2021/11/overwolf-api-and-you/
- Minecraft Usage Guidelines -- https://www.minecraft.net/en-us/usage-guidelines
- Minecraft EULA -- https://www.minecraft.net/en-us/eula
