# Managing installed mods

How Fathom installs, updates, switches off, restores and recovers third-party mods, per
profile. Discovery and compatibility are in `docs/mod-browser.md`; this document covers what
happens to a mod once it is in a profile.

## The model

A profile records the mods it wants (`profile.json`, `mods`); jars live once, by SHA-1, in the
shared mod store (`data/mods/<aa>/<sha1>.jar`); a profile's `minecraft/mods/` folder is
**derived** at every launch from those two (`mods::store::materialize`). Switching a mod off
changes the record, never a file name. Two profiles using the same jar share one stored copy.

| Kind | What it is | Where it is managed |
|---|---|---|
| **Built into Fathom** | Fathom's client (`fathom-optimizations`). Its modules — HUD, Keystrokes, Zoom, Crosshair… — are switched on in game, from the Hub (Right Shift). | Kept current by Fathom; cannot be switched off in a Fathom profile |
| **Fathom component** | Jars Fathom installs and pins for a profile: Legacy Fabric API, Legacy OptiFabric | Kept current by Fathom; no Update or Remove |
| **External mod** | Installed from Modrinth (or CurseForge with a key), or a JAR the user added or dropped into `mods/` | The user's: switch, update, restore, remove |

External Java mods are loaded when Minecraft starts. Nothing is hot-loaded: every change applies
at the next start, and the Mods page says so while the game is running.

## Installation lifecycle

| Requirement | Implementation |
|---|---|
| One-click install | A card verified **Compatible** whose plan is just that mod installs on one click; anything more (libraries, optional extras, an unverified build, a possible duplicate) shows the plan first |
| Version selection | Every build for Minecraft 1.8.9 on the mod's page, each with its verdict and an Install button |
| Progress, cancel, retry | `fathom://mod-install` events; Stop cancels down to the HTTP request; a failure leaves the sheet open with **Try again** |
| Checksum verification | Every file is fetched to `.partial`, SHA-1-checked against the index's published hash, then renamed (`DownloadManager`); store files are re-hashed before every placement in `mods/` |
| Atomic staging | All files are verified in the store before the profile changes; the profile is then changed in one swap and saved, or rolled back if saving fails (`install::execute`, `install::rollback`) |
| Unsupported files, network failures | Structured `BrowseError`s with one-sentence messages; non-jar files are refused; offline and repository-off are explained, not shown as errors |
| Duplicates | Same bytes or same project are blockers; a probable copy from the other index needs explicit confirmation |
| Rollback after failure | All-or-nothing: an interrupted, cancelled or failed install leaves the profile exactly as it was (tested) |
| Installed-mod detection | Jars dropped into `mods/` are adopted at launch; with repositories on, jars the user added are identified by exact SHA-1 on the Mods page, which gives them a name, a verdict and an update path |
| Never execute a download | Nothing downloaded is opened beyond a two-byte zip check, unpacked or run |

## Dependencies

The planner (`browse/dependencies.rs`) walks required dependencies recursively (depth 6, 48
projects), lists what will be added, what is already installed (and whether it is switched off),
optional extras, and blockers. Cycles end at the second visit. A dependency with no 1.8.9 build,
a pinned incompatible build, a declared incompatibility with something installed, or a file the
author withholds is a blocker with a sentence that says which and why. An incompatible build —
Forge, Ornithe, another Minecraft version — is refused by the planner *and* by the installer,
whatever the caller asks.

After installation, what a mod required is recorded (`Origin::requires`). The launch-time resolver
checks it: a library that is missing **or switched off** makes the mod's launch fail at Fabric's
dependency check, so Fathom refuses that launch first, names both mods, and offers the launch
without external mods (`Launcher::mod_launch_check`). Removing a library is refused while a mod
still needs it.

## The Installed page

Each row says what the jar is and where it came from ("Built into Fathom", "From Modrinth",
"Your file · identified on Modrinth"), its version, its verdict when it is not compatible, and
the problems that are about it. A mod's page shows: on/off, compatibility and why, what it needs
and what needs it, problems, earlier builds, the file (version, name, SHA-1, licence, what it was
built for) and whether that file is intact. Actions: switch, check for update, put an earlier
build back, fetch a missing or damaged file again (the same build, checked against the recorded
hash — never a newer one), show the file in Explorer, remove.

**Earlier builds.** An update keeps the build it replaced (`ProfileMod::previous`, up to three).
Their bytes stay in the store because the profile file names them, so **Put back** works offline.

## Profile isolation and shared files

Installs change one profile's record; files reach disk only by materialization into that
profile's own game directory. Materialization removes only files it placed (recorded in
`.fathom-mods.json`) and never touches anything that is not a jar; worlds, screenshots, resource
packs and configuration are outside its reach.

Garbage collection of the shared store deletes a jar only when **no file anywhere** names its
hash: every other profile's `profile.json` (read as text, so an unreadable profile still
counts — and so does its backup, which is what would be loaded in its place), profiles in the
trash, launch histories, earlier builds and set-aside entries. The profile being edited is taken
from memory.

## Crash prevention and recovery

**Launch history** (`mods/history.rs`, `launch-history.json` beside each profile): every launch
records its mod set; the desktop's session judges it — worked (reached the game and exited
cleanly or ran three minutes) or failed (crashed, or the Fathom client never confirmed). The last
launch that worked is kept separately.

**After a failure**, Home offers **What changed?** and **Launch without external mods**. The
Issues tab shows (`recovery/mods.rs`):

- what changed since the last launch that worked — added, updated, switched on or off, removed;
- jars missing from the store or damaged on disk, with **Repair** when Fathom knows the build;
- the failure lines from the launch log, the game log and the newest crash report (mod loading,
  mixin, missing-class, duplicate, corrupt-zip and out-of-memory errors), scrubbed of paths, tokens
  and the player's name, with the mods each line actually names.

A mod is only pointed at when a log line names its file or id; "changed since" is never presented
as a cause. Short ids are not matched on their own, to avoid false accusations.

**Launch without external mods** lays out only Fathom's own components for that one launch:
external jars — including ones dropped into `mods/` by hand, which are first adopted into the
store — are taken out of the folder, nothing is deleted, the profile is not changed, and the next
ordinary launch puts them all back. It is recorded as a safe launch and never becomes the "last
working" set.

**Restore last working mods** puts the record back to that set: mods added or switched on since
are switched off (with a reason, one switch away), updated ones go back to the build that worked,
removed ones are re-added when their bytes remain, and a build whose bytes are gone is switched
off rather than faked. Fathom's own components are left to Fathom.

**A damaged profile still launches.** If `profile.json` and its backup both fail to parse because
of mod entries, the entries that parse are kept, the others are written to `mods-set-aside.json`
beside the profile (never deleted), and the profile opens and launches (`profiles::store::salvage`).
Missing or damaged jars are skipped at launch rather than placed. Fathom's own client is restored
by the component system on every launch.

## Tests

- `crates/launcher-core/tests/mod_management.rs`: restore a previous build offline and after a
  restart; shared bytes survive removal while an unreadable or trashed profile, an earlier build or
  the last working set names them; a switched-off library refuses the launch; the safe layout
  removes every external jar (including a dropped one) and the next layout restores them; the safe
  set; recovery lists only what changed and restoring switches off without deleting; a damaged jar
  is reported, marked repairable and kept out of `mods/`; an interrupted or cancelled install
  changes nothing; a Forge build is never installed into a Legacy Fabric profile, even with every
  consent given; a dependency cycle terminates and installs each project once.
- Unit: `mods/history.rs`, `recovery/mods.rs` (restore rules, evidence only from named lines),
  `mods/resolve.rs` (recorded requirements and loaders), `mods/store.rs` (damaged store file),
  `mods/config.rs` via `browse/install.rs` (earlier builds), `profiles/store.rs` (salvage),
  `launcher-desktop` `api/mods.rs` (labels and recorded compatibility).

## Limitations

- **What a jar requires internally is not read.** Compatibility and dependencies come from index
  metadata recorded at install; a hand-added jar no index recognises has none, so Fathom cannot
  check its dependencies before launch and says so ("unknown").
- **"Worked" is a heuristic**: reached the game, then a clean exit or three minutes of running. A
  crash after three minutes is recorded as a failed launch, but that set stays the last working one.
- **Log evidence is pattern-based.** Fathom quotes the lines the loader and JVM use for mod
  failures and matches mod file names and ids in them; a failure reported in other words is not
  quoted, and the full logs remain on the Logs page.
- **Repair** needs the mod to have come from an index with a recorded build, and the index to
  still publish identical bytes for it; otherwise the user re-adds the file.
- **Garbage collection is conservative**: a removed jar's bytes stay while a trashed profile, a
  launch history, an earlier build or an unreadable profile's backup still names them.
- **Safe launch keeps only Fathom's own components**, so OptiFine (the user's file) and OptiFabric
  are left out of it too.
