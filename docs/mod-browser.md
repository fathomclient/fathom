# Mod browser

(What happens to a mod once it is installed — updates, earlier builds, safe launches and
recovery — is in `docs/mod-management.md`.)

Find third-party mods for a profile, see whether they will load, and install them, without
downloading or moving jars by hand. The backend is `crates/launcher-core/src/mods/browse/`; the
desktop commands are `crates/launcher-desktop/src/api/browse.rs`; the interface is the **Browse**
tab of the Mods page (`desktop/src/routes/mods/`).

## Layers

```
Browse tab (Svelte) ─► catalog_* commands (async, on workers, outside the launcher lock)
                         └─► ModCatalog (browse/mod.rs)
                               ├─ CatalogProvider: ModrinthProvider, CurseForgeProvider  (+ Throttle each)
                               ├─ compat: the compatibility resolver (verdict + reasons per build)
                               ├─ CatalogCache (search / projects / files in memory, images on disk)
                               ├─ dependencies::plan ─► InstallPlan (blockers, unverified, optional)
                               └─ install::Installer ─► ModStore + ProfileModConfiguration
```

Nothing outside `browse/modrinth.rs` and `browse/curseforge.rs` sees a provider's wire types.
Installed mods are ordinary `ProfileMod` records in the same content-addressed store that
materialize and launch already use; there is no second mod system.

| Concern | Where |
|---|---|
| Provider API clients | `mods/modrinth.rs` (wire), `browse/modrinth.rs`, `browse/curseforge.rs` |
| Request pacing | `browse/throttle.rs` |
| Discovery data model | `browse/model.rs`, `browse/provider.rs` |
| Compatibility resolution | `browse/compat.rs` |
| Dependency planning | `browse/dependencies.rs` |
| Download / install | `browse/install.rs` over `install::download::DownloadManager` and `ModStore` |
| Persistent mod state | `ProfileModConfiguration` in the profile (unchanged) |
| Desktop commands | `launcher-desktop/src/api/browse.rs`, registered in `commands.rs` |
| Interface | `routes/mods/Browse.svelte`, `CatalogDetail.svelte`, `InstallSheet.svelte`, `lib/richtext.ts` |

## Compatibility

Fathom runs Minecraft 1.8.9 on Legacy Fabric (loader 0.19.3). The resolver answers, for each
*build*, one of three verdicts with the reasons behind it:

| Verdict | When | Install |
|---|---|---|
| **Compatible** | Tagged `legacy-fabric` for 1.8.9, nothing it requires contradicts that | Yes |
| **Unverified** | Nothing rules it out, nothing proves it: chiefly a 1.8.9 build tagged plain `fabric` (Modrinth had no `legacy-fabric` tag before 2023; CurseForge has none at all), or a build that states no loader or no version | Only after the user ticks "Install anyway" |
| **Incompatible** | Another Minecraft version; Forge, NeoForge, Quilt-only, Ornithe, Babric; requires Ornithe Standard Libraries or modern Fabric API; or a project Fathom manages itself (Legacy OptiFabric) | Never |

Evidence is the index's metadata for the file (game versions, loader tags, declared
dependencies) plus a short table of projects checked by hand (`compat::KNOWN`). The jar is never
opened to ask it (`mods/mod.rs` rule 6), so a mod's `fabric.mod.json` requirements, notably the
Fabric Loader version it demands, are not known before launch. The launch-time resolver remains
the last word.

A project's verdict is the best of its builds. Search hits are only candidates: index facets are
project-wide, so a project with Legacy Fabric builds for 1.12 and Forge builds for 1.8.9 matches a
"Legacy Fabric + 1.8.9" search. Every card therefore starts as **Checking** and is resolved by
reading the project's real version list (`catalog_verdicts`, a few cards at a time).

The **compatibility filter** decides what is asked of the index and what is shown:

| Filter | Asked for | Shown |
|---|---|---|
| Verified Legacy Fabric | `legacy-fabric` + 1.8.9 | compatible (and still checking) |
| Include unverified (default) | (`legacy-fabric` or `fabric`) + 1.8.9 | everything except incompatible |
| All 1.8.9 mods | 1.8.9, any loader | everything, incompatible ones marked with the reason |

Verified against live Modrinth on 2026-10-12 (`tests/live_catalog.rs`): ksyxis and AxolotlClient
(its 3.0.6 Legacy Fabric build, ahead of newer Ornithe builds) are compatible, yosbr is
unverified, Chatting (Forge/Ornithe) and EntityCulling (Forge) are incompatible.

## Installing

Install always opens a sheet with a **plan** first: the build chosen, every required dependency
(recursively), what the profile already has, optional extras (never ticked for the user),
blockers, unverified builds and possible duplicates. The plan is rebuilt in Rust when Install is
pressed, and the installer re-checks every verdict, so nothing the interface holds is trusted.

The install runs in two passes so the launcher lock is never held across the network:

1. **Fetch**, on a worker, against a copy of the profile: every file downloaded into the store
   and SHA-1-verified, progress sent as `fathom://mod-install` events. Cancellable.
2. **Record**, under the lock, against the profile as it is now: the plan is re-validated and
   applied (every file is already in the store), and the profile is saved, or rolled back if the
   save fails.

Updates are the same plan with `replacing` set; the entry keeps its id, its enabled state, and
the old bytes stay in the store until garbage collection. Removal refuses while another installed
mod requires it.

## Network behaviour

- Off until **Allow mod repositories** is switched on (Privacy, or the button on the Browse tab).
  Every request also needs a `PerActionGrant` from the user's action.
- Search is debounced 300 ms; a new search cancels the previous one down to the HTTP request, and
  stale answers are dropped.
- Each provider has one `Throttle`: Modrinth a burst of 12 then 2.5 requests/s (half its published
  300/min); CurseForge 8 then 2/s. A 429 pauses every request to that index for its `Retry-After`
  or `X-Ratelimit-Reset`; a search will not wait more than 3 s and says so instead.
- Metadata requests time out after 20 s (downloads keep the 300 s budget).
- Pages of 24, loaded as the list scrolls; caches: search 3 min, projects 15 min, version lists
  10 min, images 14 days on disk (64 MB ceiling).
- User-Agent `fathomclient/fathom/<version>`, as Modrinth's API documentation asks.

## Safety of remote content

Remote data is clipped (`model::limits`); slugs and ids are validated before they become URL path
segments; download and image URLs must be HTTPS on the compile-time allowlist; file names must be
plain `.jar` names. Descriptions (Markdown on Modrinth, HTML on CurseForge) are parsed by
`lib/richtext.ts` into a small tree rendered with ordinary elements — never `{@html}` — so a
description cannot create an element, attribute or script. Images are fetched by Rust and shown as
`data:` URLs (PNG, JPEG, GIF, WebP only). A link in a description is opened only if that exact URL
is in the description Rust fetched, and every external link passes `pages::external_url`.

## CurseForge

The provider is implemented (search, project, files, dependencies, the third-party distribution
flag honoured as `FileDownload::Withheld`), but it is **off** unless an API key is configured, via
`FATHOM_CURSEFORGE_API_KEY` or `curseforge-api-key.txt` in the data directory. Fathom ships no key.

Blocker, as of 2026-10-12: CurseForge issues API keys to third-party applications through an
application form, and Fathom has not been issued one. The CurseForge for Studios terms restrict
providing the platform to third parties, and whether a launcher may embed its key, or let users
supply their own, is governed by CurseForge's third-party API terms, which need reading before any
build enables it by default. Even with a key, CurseForge has no Legacy Fabric loader tag, so every
1.8.9 Fabric build there is **Unverified**, and category filtering is Modrinth-only.

## Forge installations

An installation on Forge (Custom or Fathom) browses for Forge: the search asks for the
`forge` loader, **Verified Forge** means a 1.8.9 build tagged for Forge, and
"Include unverified" adds builds that state no loader -- not plain Fabric builds,
which are plausible only for Legacy Fabric. Fabric, Legacy Fabric and Ornithe
builds are incompatible there, as Forge builds are in a Fabric installation.
See `docs/forge.md`.

## Not supported

- Judging a mod by its jar's `fabric.mod.json` (Fabric Loader version, entrypoints): by design.
- Libraries that do not list 1.8.9 (for example Fabric Language Kotlin): the planner blocks mods
  that require them rather than guess.
- Resource packs, shaders and modpacks: mods only.


## Tests

- Unit: `browse/compat.rs` (verdict rules), `browse/throttle.rs`, `browse/install.rs` (consent:
  unverified needs it, incompatible ignores it), planner, providers' parsing.
- `crates/launcher-core/tests/mod_browser.rs`: profile isolation, updates, shared dependencies,
  Privacy switch.
- Live, ignored by default:
  `cargo test -p launcher-core --test live_catalog -- --ignored --nocapture`
  (search and paging, real verdicts, real dependency planning, categories).
- Description parser: `npm test` in `desktop/` (hostile HTML, links, Markdown structure).
- Desktop: `cargo test -p launcher-desktop` (image sniffing, link validation).
