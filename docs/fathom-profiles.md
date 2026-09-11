# Fathom Profiles and Game Instances

Two things were called `Profile`. They are different things with different
lifetimes, different owners and different restart semantics, and conflating them
is why the launcher's Profiles screen and the game's Profiles page never showed
the same list.

## The split

**Game Instance** — how Minecraft is set up. Minecraft version, loader and
loader version, game directory, Java runtime policy, memory and JVM options,
external mod jars, Fathom component versions, launch arguments, servers.
Changing one of these may require a restart because it changes what process
starts.

**Fathom Profile** — a loadout. Which Fathom modules are on, their settings,
the HUD layout, colours, text templates, module keybinds, the client's own
interface preferences, and the live-safe performance switches. Nothing here
changes what process starts, so all of it switches while the game runs.

A Fathom Profile never contains a Minecraft version, a loader, a Java path, a
game directory, an external jar set, or anything about an account. A Game
Instance never contains a module toggle or a HUD position.

## One collection, two readers

There is one Fathom Profile collection and both the launcher and the game read
and write it. Not a launcher copy synchronised into a game copy: the same files.

```
<data root>/
  profiles/
    <id>.json                 one Fathom Profile each
    client-state.json         { lastProfileId, migratedFrom }
    <instance-id>/            a Game Instance's directory, for now (below)

<game directory>/
  fathom/backups/<stamp>/     the old properties files, copied before migration
  fathom/profiles-migrated    which collections this game has migrated into
```

**Why instances are still in `profiles/`.** The Game Instance store lists only
directories and the profile store lists only `.json` files, so the two share the
directory without seeing each other. That let the collection exist without
moving any instance directory out from under a game that might be running from
it. Moving instances to `instances/` is its own restart-safe step and has not
been done; see Remaining work.

`<data root>` is the launcher's per-user data directory, as the `directories`
crate computes it for application `Fathom` with no organisation:

| Platform | Data root |
| --- | --- |
| Windows | `%LOCALAPPDATA%\Fathom\data` |
| macOS | `~/Library/Application Support/Fathom` |
| Linux | `$XDG_DATA_HOME/fathom` (default `~/.local/share/fathom`) — lowercased by the crate |

The game computes the same thing independently, so these three rows are the
contract; they were read out of `directories` 6.0.0's source rather than
remembered.

### How the game finds it

In order:

1. The `root` field of `fathom/session.json`, when the launcher started the
   game. This is how a portable install works, where the data root is not the
   platform default.
2. The platform default, computed the same way the launcher computes it.
3. `<game directory>/fathom/profiles/`, when neither of the above exists — a
   game started by hand, or the Gradle development client.

Case 3 is a real fallback and not an error. A copy of the mod dropped into
somebody's own instance has profiles; they are just local to it, because there
is no launcher whose collection they could join.

Adding `root` to `session.json` is a deliberate change to that file's stated
invariant, which previously said it carried no path outside the game directory.
The invariant that matters is the one the test enforces — no account, no token,
no identity — and a path to the user's own launcher data is none of those. It is
documented in `session.rs` rather than left as a surprise.

## The profile document

`profiles/<id>.json`, schema 1:

```json
{
  "schema": 1,
  "id": "bedwars",
  "name": "Bedwars",
  "createdAt": 1789000000,
  "updatedAt": 1789000000,
  "modules": {
    "fps": {
      "enabled": true,
      "favourite": false,
      "settings": { "decimals": "0" },
      "keys": { "toggle": "33" }
    }
  },
  "hud": {
    "fps": {
      "anchor": "top-left",
      "x": 4, "y": 4,
      "scale": 1.0, "opacity": 1.0,
      "hidden": false
    }
  },
  "client": {
    "hubKey": 54,
    "uiScale": "Auto",
    "highQualityUi": true,
    "hudFont": "Fathom"
  },
  "performance": {
    "preset": "balanced",
    "switches": { "render.font.metrics-cache": true }
  }
}
```

The example spells out `favourite` and `hidden` for illustration; a real
document leaves out anything false or empty. Switch ids are the game registry's
dotted ids, without the `opt.` prefix the launcher's properties file uses.

Only what differs from a module's declared default is written, which is what
keeps a profile diffable and keeps a new module's default from being frozen into
every existing profile the first time it is saved.

Both sides write atomically — write a sibling temporary file, fsync, rename —
so a profile is never half a document, and both validate `schema` before
trusting the rest.

## Live switching

Switching is a transaction. It either happens or the current profile is left
exactly as it was; there is no state in which half a loadout is applied.

0. Save the departing profile, so edits made in it are not lost to the switch.
1. Load and validate the target. A document that will not parse, has a newer
   schema, or holds an impossible value fails here and nothing has changed.
2. Capture the running client, as a rollback point.
3. Disable the departing modules, so a module that is leaving is switched off
   as itself rather than half-reconfigured first.
4. Apply settings and colours. Anything the target does not list is reset —
   a switch is a replacement, not a merge, so Bedwars looks the same whichever
   profile you arrived from.
5. Enable the arriving modules, now that their settings are right.
6. Replace the HUD layout wholesale.
7. Replace module keybinds, the hub key and the client's interface preferences.
8. Apply the live-safe performance switches. One the target does not mention
   returns to its launcher baseline.
9. Persist `lastProfileId`.
10. Confirm, quietly.

If steps 3 to 8 throw — a setting value its module rejects, a key code that is
not a number — the rollback point from step 2 is applied back, and neither the
live profile nor the pointer moves.

A module, widget or optimization this build does not have is **skipped and
logged**, not failed. A profile made by a newer Fathom must still switch on an
older one.

Implemented as `ProfileRuntime.switchTo` (the transaction) and
`ProfileSwitch.apply` / `capture` (the mechanism).

### What still needs a restart

Nothing in a Fathom Profile. The performance switches a profile carries are the
live-safe ones. The JVM-level optimizations — the garbage collector flags and
anything else fixed for the life of the process — belong to the Game Instance,
are marked restart-required individually, and are not part of a profile. A
profile switch never asks for a restart.

## Startup

Read `client-state.json`, load `lastProfileId`. If it is missing, unreadable, or
names a profile that is not there, fall back to the one profile called `Default`
— creating it only if the collection is genuinely empty. The client does not
generate a second default because the first one could not be found.

## Migration

Fathom's game-side configuration used to live in three properties files:

| Was | Now |
| --- | --- |
| `config/fathom-features.properties` | `modules` and `client` in the profile |
| `config/fathom-hud[-name].properties` | `hud` in the profile |
| `config/fathom-performance.properties` | `performance` in the profile |

Migration copies everything into a profile called `Default`, and each named HUD
layout that is not the default becomes a profile of its own, since a named
layout was the closest thing the client previously had to a loadout.

**Back up first.** The original files are copied into
`<game directory>/fathom/backups/<stamp>/` before any profile is written, and
then left where they are. Nothing is deleted: a value that could not be mapped
is a value somebody chose, and it is reported and kept.

**Never over an edited Default.** A blank placeholder `Default` is filled in
with the migrated data. A `Default` somebody has already changed is kept, and
the migrated data goes into `Default (imported)` beside it.

**Once per game directory, per collection.** Recorded in
`<game directory>/fathom/profiles-migrated`, one normalised collection path per
line. A second run into the same collection does nothing, so it cannot produce a
second Default. A different collection still gets the data — a game that only
ever ran standalone and is later started by the launcher migrates into the
launcher's collection too.

Both the launcher (`loadout::migrate`) and the game (`ProfileMigration`)
implement this, by the same rules, each with its own tests. The game is where
these files are usually found, including the development client, which has no
launcher.

### The performance file is different

`fathom-performance.properties` is not something the player edited in game. The
launcher writes it before every launch from the Game Instance's optimization
settings (`opt.<id> = true|false`, plus `preset` in the launcher's vocabulary:
`balanced`, `competitive`, `maximum-fps`, `custom`), and overwrites it the next
time. Migration still carries its switches into the profile, because they are
choices somebody made.

At startup the game applies the launcher's file first and then the live
profile's switches over it, so a profile's choices win. A switch the profile
does not mention stays at what the launcher's file said. The launcher's
Performance page still edits the Game Instance's settings; moving it onto the
live profile is not done (see Remaining work).

## Terminology

The launcher's version/loader/runtime collection is **Installations** in the
navigation, the page title, the Home switcher and the bottom bar. **Profiles**
in the launcher is the same collection of loadouts the game shows, and Home has
a `Profile:` picker beside the installation switcher.

Choosing a profile in the launcher sets the one the next game opens. It cannot
reach into a game that is already running — the bridge is deliberately a closed
four-verb protocol — so a running game switches in game, where it is immediate.
Both pages say so.

Gone from normal UI: `Unknown profile`, `Launch next time`, `Profiles are the
launcher's`, and `Development Profile`. The development client shows the real
profile it is running, the same as any other client; `Diagnostics` still says
that the launcher is absent, because that is a fact about this session rather
than a name for a loadout.

## Remaining work

Not done in this change, and why each is safe to leave for now:

- **The Rust type is still `profiles::Profile`.** Every user-visible word is
  Installation; the internal rename to `GameInstance` touches about a hundred
  files and changes no behaviour, so it is a separate mechanical change. Until
  then the Fathom Profile module is `loadout`, so no file has two types called
  `Profile`.
- **Instance directories are still `profiles/<id>/`.** See the layout note above.
- **The launcher's Performance page edits the instance, not the live profile.**
  In game, the Performance page's switches are captured into the live profile.
- **The bridge's `next-profile` and `restart-into` verbs name installations.**
  The in-game Profiles page no longer uses them.
- **The launcher's first-run setup flow** still uses the word profile in places
  for what is an installation.

## Verification

- `cargo test -p launcher-core --lib loadout` and `session::` — the store,
  migration, and the `root` field.
- `gradle coreTest` — `ProfileTests` (store, document, migration, the bytes the
  launcher writes) and `ProfileLocationTests` (the data-root rules per platform).
- `tools/profile-contract.sh` — launcher writes Default, Bedwars and Survival and
  chooses Survival; the game's store opens the same files, starts on Survival,
  saves an edit and switches to Bedwars, restarts on Bedwars with no second
  Default; the launcher reads the edit and the pointer back. No window opens.
- `gradle runClient -PcaptureUi=Profiles -PprofilesDir=<folder>` — the in-game
  page against an isolated collection, so a QA run never writes into a real one.

