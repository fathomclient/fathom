# Forge 1.8.9

Forge is an opt-in loader for **Custom** installations, alongside Legacy
Fabric. Choosing it changes nothing about any Fabric installation.

## What a Forge installation is

Minecraft 1.8.9, Forge (the recommended build, 11.15.1.2318, unless you pick
another), and your own Forge mods. **Fathom's in-game client -- the Hub, the HUD
modules, the performance layer -- is a Legacy Fabric mod and is not part of a
Forge installation.** The Mods page says so on a Forge installation. Porting the
client to Forge is a separate, later project.

OptiFine loads directly on Forge: **Add OptiFine** puts the jar in the
installation with no OptiFabric.

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
| Installation editor, new installation | Forge is offered for Custom; changing loader clears the old loader's build |
| Mod browser | Searches and verifies Forge builds; see `mod-browser.md` |
| Mods page | Explains that Fathom's client is not part of the installation |
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

## Limitations

- Fathom's in-game client does not run on Forge.
- Only 1.8.9, which is the only Minecraft version Fathom offers.
- Coremods that need to be named on the command line
  (`-Dfml.coreMods.load`) must be added by hand in the installation's JVM
  arguments.
