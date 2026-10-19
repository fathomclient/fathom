# Forge 1.8.9

Forge is an opt-in loader for **Custom** installations, alongside Legacy
Fabric. Choosing it changes nothing about any Fabric installation.

## What a Forge installation is

Minecraft 1.8.9, Forge (the recommended build, 11.15.1.2318, unless you pick
another), your own Forge mods, and **Fathom for Forge**: Fathom's own client,
the same classes as on Legacy Fabric, built for Forge. The hub on Right Shift,
the HUD and its editor, the custom main menu, every module (Zoom, crosshair,
Fullbright, Time Changer, 1.7 Animations, ...), the shared Fathom Profiles and
their keybinds, the launcher bridge, and the optimizations. It is installed and
kept current automatically before a Forge launch, in Fathom and Custom
installations alike.

A Fathom installation can be switched between Legacy Fabric and Forge in the
installation editor; the launcher swaps in the matching build of the client
(`components::ensure` removes the other loader's build, so switching back
installs it afresh rather than finding it off).

Minecraft's `options.txt` is also launcher-wide: before any profile starts, the
newest valid options file from a known installation is copied into the target.
That carries controls, global keybinds, video, audio and accessibility settings
between Forge and Legacy Fabric without sharing worlds or per-mod config.
Fathom's own settings and keybinds live in the shared Fathom Profiles, which both
builds read from the same place.

OptiFine loads directly on Forge: **Add OptiFine** puts the jar in the
installation with no OptiFabric.

## How Fathom runs on Forge

Nothing is rewritten for Forge. `gradle build` in
`minecraft/fathom-optimizations` makes the Fabric jar, then `forgeJar` renames
it for Forge (`build.gradle`, end):

1. **Names.** Under Fabric the game's classes use Legacy Fabric's intermediary
   names; under Forge, MCP/SRG names (FML renames the game as it loads). The two
   tables are composed through Mojang's obfuscated names -- Legacy Fabric's
   intermediary, and `deobfuscation_data-1.8.9.lzma` from Forge's universal jar
   -- and tiny-remapper (with its Mixin extension, as Loom uses) renames every
   reference. The Mixin reference map is rewritten to match. A final pass renames
   anything left (a `@Shadow` in a Mixin whose string target tiny-remapper could
   not resolve), and the build fails if an intermediary name survives.
2. **Mixin.** Forge 1.8.9 has none, so the jar carries Fabric's Mixin build and
   MixinExtras, and the ASM 9 they need. Forge ships ASM 5.0.3; the launcher puts
   this jar first on the class path so ASM 9 wins. Two incompatibilities in Forge
   are covered: its deobfuscator extends two ASM classes ASM 9 removed (taken from
   ASM 6.2.1, unchanged), and its `EventSubscriptionTransformer` passes class names
   to `Type.getType`, which ASM 9 rejects; one method of ASM's `Type` is patched
   to give ASM 5's answer (ATTRIBUTION.md). Mixin's Java 16 ModLauncher classes are
   left out.
3. **Start.** The launcher adds `--tweakClass
   org.spongepowered.asm.launch.MixinTweaker` after FML's. The jar's manifest
   names the same tweaker (so FML treats it as a tweaker and does not scan it as
   a mod) and the two Mixin configs. MixinExtras is started by Fathom's Mixin
   plugin. `fathom.forge.mixins.json` adds what Forge needs on top of the shared
   config: the client entrypoint (Fabric's loader calls it; under Forge a Mixin in
   `Minecraft.startGame` does) and Fathom's HUD (Forge draws the HUD with
   `GuiIngameForge`, which never calls vanilla's `renderGameOverlay`).
4. **Loader questions.** The client asks `fabric.Platform` for the game directory,
   loaded mods and versions; it answers from Fabric's API or from FML by
   reflection, so the same classes link on both.

Other Mixin mods are fine: a Forge mod that bundles its own Mixin tweaker
(EntityCulling, for one) finds Fathom's already started, and LaunchWrapper skips
the duplicate; Mixin still reads that mod's config from its manifest and applies
it. Fathom's Mixin is 0.8.7, so a mod written for Mixin 0.7 gets 0.8.7 -- which
reads 0.7 configs.

## How Forge is installed

Forge distributes an installer jar meant to be run. Fathom never runs it
(`THREAT_MODEL.md`). `crates/launcher-core/src/loaders/forge.rs`:

1. Lists Forge's builds for 1.8.9 from `maven-metadata.xml` on Forge's Maven,
   and marks the one `promotions_slim.json` recommends.
2. For the chosen build, downloads the installer, checks it against its
   published SHA-1, and reads **only** `install_profile.json` out of it: the main
   class (`net.minecraft.launchwrapper.Launch`), the tweaker
   (`--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker`) and the
   library list.
3. Resolves every client library (all but `clientreq: false`, which is jline)
   to a URL and a SHA-1: the repository's `.sha1` sidecar, cross-checked against
   the profile's own checksums when it lists them, or the profile's first
   checksum when there is no sidecar (two Scala libraries). No hash, no build.
   Forge itself is its `-universal.jar`, stored where the installer would have
   put it.
4. Keeps the resolved build in the metadata cache, so later launches contact
   nobody; its URLs and paths are re-checked on every read.

Every URL goes through the allowlist (`maven.minecraftforge.net`,
`files.minecraftforge.net`, `libraries.minecraft.net`; `NETWORK.md`).

## What else knows about Forge

| Part | Behaviour |
|---|---|
| Installation editor, new installation | Forge is offered for Custom and Fathom installations; changing loader clears the old loader's build and swaps Fathom's client build |
| Mod browser | Searches and verifies Forge builds; see `mod-browser.md` |
| Mods page | Lists Fathom for Forge as the launcher's own and explains what it is |
| Recovery | Quotes FML's failure lines (`Caught exception from`, `MissingModsException`, duplicate mods, wrong Minecraft version) without flagging its ordinary coremod chatter |
| Launch | Java 8 (Forge 1.8.9 fails on later Java) |

## Verified

- `cargo test -p launcher-core --test live_forge -- --ignored`: the real version
  list (recommended 11.15.1.2318 marked, newest first) and a real resolution: 18
  libraries, every one with a hash, then again with the network off from the
  kept build.
- `cargo test -p launcher-core --test live_catalog -- --ignored a_forge_profile`:
  Chatting and EntityCulling are compatible from a Forge installation, yosbr is
  not; Forge searches return only Forge-tagged projects.
- A launch from Fathom (2026-10-13): `Forge Mod Loader version 11.15.1.2318 for
  Minecraft 1.8.9 loading` ... `Forge Mod Loader has successfully loaded 3 mods`,
  to the main menu. FML also logs "FML appears to be missing any signature data",
  which it does for the universal jar outside its installer's layout; it is
  harmless.

- A Forge mod end to end (2026-10-14): EntityCulling 1.5.0 for Forge 1.8.9,
  installed through the launcher's catalogue into a Forge installation, launched
  from Fathom: `Forge Mod Loader has successfully loaded 4 mods` (mcp, FML, Forge,
  entityculling), its Mixin tweaker applied, main menu reached, no crash report.
  The Mods page listed it "From Modrinth", compatible; Check for update found it
  current.

- Fathom on Forge (2026-10-14/15), from a launcher built from this tree, in a
  private copy of the data:
  - Fathom's client started under Forge: marker written with the placed jar's
    SHA-1, 21 modules, 20 optimizations, 13 HUD widgets, shared profile
    `Default` from the launcher's collection, launcher settings applied.
  - Every Mixin applied with none failing (OptiFine installed): 46 of the
    shared config's 77, both Forge ones, and the same four OptiFine-overlapping
    ones skipped as on Fabric; the other 27 are the view benchmark's, applied
    only when it runs. From Mixin's verbose log (`Mixing ... into ...`).
  - Fathom's menu ("1.8.9 - forge"), the hub (Mods, Performance, Diagnostics:
    "Forge 11.15.1.2318") and the HUD in a world, by the UI tour.
  - The in-game module self-test: 73 of 74 checks pass on Forge (74 of 74 on
    Fabric); the next launch's verify pass, 8 of 8 on both: settings written in
    one session came back in the next. The one failure is OptiFine's (Limitations);
    without OptiFine, 74 of 74 on Forge.
  - OptiFine M5 and EntityCulling 1.5.0 together with Fathom: 4 mods loaded,
    EntityCulling's own Mixin config (`entityculling.mixins.json`, 5 Mixins)
    applied through Fathom's Mixin.
  - A paired view benchmark (OptiFine, `mpbench` world, 3 rounds; Fathom on
    against off) behaves as on Fabric. Forge itself is slower than Fabric in the
    same scenes (horizon median 1.19 against 0.84 ms; 48 players 3.13 against
    2.12 ms) and allocates 90-200 MB/s against 4-60 on Fabric, with Fathom on or
    off. The runs were not clean (the Forge window was mostly unfocused, the
    Fabric run's windows varied 35-55%), so they show the optimizations working,
    not their size.

## Optimizations on Forge

Every optimization in the shared registry runs on Forge: the same Mixins apply
(see Verified), and with OptiFine the same four OptiFine-overlapping ones stand
down as on Fabric. Forge has costs Fabric does not, and the first one measured
is fixed:

**`render.forge.sky-blend-cache`** (Forge only, default on). With Fancy
graphics Forge tints the sky and fog by averaging the sky colour of every biome
column around the camera -- 2,809 columns at render distance 12, each a new
`BlockPos` and a biome lookup -- and asks twice a frame. Its own cache compares
the camera's Z with a stored Y (`ForgeHooksClient.getSkyBlendColour`), so it
recomputes on almost every call. Fathom keeps the answer until the world, the
camera's block (Y included), the blend radius or the loaded chunks change
(`fabric.perf.ForgeSkyBlend`; chunk loads, unloads and fills are counted by two
Mixins in `fathom.forge.mixins.json`, and the cache stays off until both have
been seen to run), and computes a miss with one reused position.

- Correct: with `-Dfathom.verify.caches=true` Forge computes every answer as
  well; 28,672 compared in the view benchmark and 45,056 across the module
  self-test (joining, leaving and rejoining worlds), none different.
- Faster (OptiFine M5, Fancy, render distance 12, 4 paired rounds, no
  unfocused frames): horizon 1.163 -> 0.896 ms median (4/4), looking down
  1.319 -> 1.010 (4/4), 48 players 3.146 -> 2.867 (3/4); client allocation
  118 -> 18 MB/s. That is most of the gap to Fabric in the same scenes (0.84 ms).

With Fast graphics Forge blends nothing (radius 0) and the fix changes little.

## Performance parity (2026-10-17)

Reported: Forge at about 300 fps on a server lobby where the Optimized profile
(Legacy Fabric, OptiFine) reached 1,000-1,200. Three causes, in order of size:

1. **No OptiFine.** The Forge profiles had none; Optimized has M5 with Render
   Regions. OptiFine works on Forge natively (no OptiFabric), and Fathom leaves
   the renderer paths it rewrites to it on both loaders.
2. **Every Fathom optimization was switched off.** Forge profiles are created as
   Custom, which starts with an empty switch map, and an entry Custom never
   mentioned reads as off: the game logged "21 optimizations registered, 1
   enabled". With Forge's sky-colour cache bug back on, that state was slower
   than Fathom bypassed. Fixed in the launcher: a profile that runs Fathom's
   client and never chose any optimizations gets Balanced
   (`components::tune_if_never_chosen`, at open and before launch). A choice,
   including "all off", is kept.
3. **One optimization applied and never ran.** Forge draws armour through its
   own `LayerArmorBase.getArmorResource(Entity, ItemStack, int, String)`, so
   the vanilla armour-texture hook was idle. Ported (`ForgeArmorTextureMixin`).

Ruled out: a stale client (the jars in the real launches were the shipped
build, by SHA-1), Java (8u502 for both), settings (identical), and the
huge-method JIT flag (one method over the limit in all of Forge and OptiFine:
the crafting-recipe constructor, run once).

### Measured

View benchmark, singleplayer `mpbench`, 1600x900, render distance 12, Fancy,
unlimited fps, 3 interleaved rounds per arm, 0 unfocused frames. Median ms per
frame (fps). "All off" is the state the Forge profiles were in; "now" is with
the switches on. Reports: `benchmarks/reports/fathom-view-2026-10-17-*`.

| Scene | Forge, all off | Forge now | Forge + OptiFine | Fabric | Fabric + OptiFine |
|---|---|---|---|---|---|
| Horizon | 2.82 (355) | 2.20 (455) | 0.62 (1,613) | 1.82 (549) | 0.58 (1,727) |
| One NPC with a hologram | 3.00 (333) | 2.34 (427) | 0.72 (1,397) | 1.93 (519) | 0.66 (1,517) |
| Six NPCs | 3.36 (298) | 2.62 (382) | 0.87 (1,155) | 2.20 (454) | 0.80 (1,252) |
| Crowd: six NPCs, 32 players | 4.12 (243) | 3.32 (301) | 1.47 (682) | 2.75 (364) | 1.26 (794) |
| 48 armoured players | 4.77 (210) | 3.68 (272) | 1.92 (520) | 3.06 (327) | 1.61 (621) |
| Turning between crowd and sky | 3.63 (275) | 2.45 (409) | 0.43 (2,347) | 1.90 (527) | 0.42 (2,381) |

Switches on against all off, paired: Forge -0.62 to -1.19 ms in every scene (0
of 3 rounds where off was faster, in every scene); Forge with OptiFine -0.31 to
-0.72. With OptiFine, Forge is now 7-19% behind Fabric in the same scenes; the
rest is Forge's own per-entity work (render events, its armour path) and shows
in the entity scenes.

### Parity matrix

Engagement is each optimization's fast path counted per frame in the same runs
(the report's ENGAGEMENT section): non-zero with it on, zero in the all-off arm.

| Optimization | Forge | Evidence on Forge |
|---|---|---|
| Forge sky colour cache | Active, Forge only | 2 hits a frame in every scene; the largest part of the all-off penalty |
| Armour texture cache | **Ported**; was applied but idle | 192 hits a frame with 48 armoured players (Forge's own method) |
| Nametag name cache | Active | 3 / 18 / 50 per frame in npc / npcs / crowd |
| Font metrics cache | Active without OptiFine; stands down with it | 1-151 hits a frame |
| Chunk lookup cache | Active without OptiFine; stands down with it | 3,000-7,400 hits a frame |
| Shared direction arrays | Active (visibility search without OptiFine) | 4,026 a frame while turning |
| Sign text, item frames, tab list, chat and sidebar replay, shadows | Active (same hooks; not in these scenes) | Targets confirmed on Forge's classes; shadows are off in these options |
| Nametag replay, draw distances, HUD caching | Active (in-game settings) | Section timings in the reports |
| Symbol glyph preload | Active | "Preloaded 7 symbol glyph pages" at font load |
| Chunk rebuild scheduling | Active without OptiFine; stands down with it | Takes effect on renderer reload |
| World-change GC, unfocused cap, frame limiter, pack icons | Active | Log lines; the limiter only matters with a cap |

Still open: the visibility search while turning costs 0.38-0.50 ms a frame and
70-100 MB/s of garbage on Forge without OptiFine (0.04 ms with the camera held);
OptiFine already avoids it. See `docs/performance/scene-transitions-2026-10.md`.

## Limitations

- With OptiFine on Forge, the armour HUD widget allocates 704 bytes a frame
  (the self-test's budget is 256; it passes on Forge without OptiFine and on
  Fabric). OptiFine's own `RenderItem` calls Forge's hooks through its
  reflection helper, which builds an argument array per item. It is OptiFine's
  code, so it is left alone.
- Only 1.8.9, which is the only Minecraft version Fathom offers.
- Coremods that need to be named on the command line
  (`-Dfml.coreMods.load`) must be added by hand in the installation's JVM
  arguments.
- The earlier separate Forge mod (`minecraft/fathom-forge`, with its own
  simplified menu and HUD) has been retired; this replaces it.
