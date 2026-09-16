# Mod browser (backend)

One catalogue over Modrinth and CurseForge, in `crates/launcher-core/src/mods/browse/`.
This document covers the domain layer; the interface is not wired to it yet (see the last
section).

## Layers

```
interface ─► Launcher (services.rs) ─► ModCatalog (browse/mod.rs)
                                          ├─ CatalogProvider: ModrinthProvider, CurseForgeProvider
                                          ├─ CatalogCache (search / projects / files in memory, images on disk)
                                          ├─ dependencies::plan ─► InstallPlan
                                          └─ install::Installer ─► ModStore + ProfileModConfiguration
```

Nothing outside `browse/modrinth.rs` and `browse/curseforge.rs` sees a provider's wire types.
The rest of Fathom sees `ModProject`, `ModFile`, `CatalogEntry`, `InstallPlan` and, once
installed, an ordinary `ProfileMod`. There is no second mod system: the browser writes the same
profile records, into the same content-addressed store, that materialize and launch already use.

| File | Responsibility |
|---|---|
| `model.rs` | Unified model, limits on remote data, `safe_file_name`, `Target`, `InstallState` |
| `provider.rs` | `CatalogProvider` trait, `SearchQuery`, `BrowseError` (structured, serialisable) |
| `modrinth.rs` | Adapter over the existing `mods::modrinth` client |
| `curseforge.rs` | CurseForge Core API client, key configuration, distribution flag |
| `cache.rs` | TTL + size-bounded caches; disk image cache with a byte ceiling |
| `search.rs` | Debounce, cancel-previous, stale-result dropping |
| `identity.rs` | Cross-provider duplicate detection and merging |
| `dependencies.rs` | Recursive dependency planning |
| `install.rs` | Stage → verify → record; update rollback; dependency-safe uninstall |

## CurseForge configuration

CurseForge requires an API key. Fathom does not ship one. The user supplies their own, either:

- in `curseforge-api-key.txt` in Fathom's data directory, or
- in the `FATHOM_CURSEFORGE_API_KEY` environment variable (takes precedence).

A key containing whitespace or control characters, or longer than 256 bytes, is rejected (it
would otherwise be able to forge an HTTP header). The key is held as a `Secret` and never
logged. Without a key, CurseForge reports `NotConfigured` in every search status, makes no
requests, and Modrinth works as normal.

Projects whose authors have turned off third-party distribution come back with
`FileDownload::Withheld { where_to_get }`. They can be browsed; they cannot be installed, and
nothing tries to work around that (`research/mod-policies.md` §7).

## Guarantees and where they are enforced

| Guarantee | Enforced in |
|---|---|
| Results default to the profile's Minecraft version and loader | `SearchQuery::new(Target::of(profile))` |
| Incompatible files are refused at install whatever search said | `ModFile::supports` in `dependencies::plan` **and** `Installer::validate`; `ModCatalog::files` also filters provider output |
| No half-written jar anywhere | `DownloadManager` writes `.partial`, hashes, renames; materialize now copies to `.partial` then renames |
| Only verified bytes are recorded | Every file is fetched and SHA-1-verified, and must start with `PK`, before the profile changes |
| Profile isolation | Installs change `profile.mods`; files reach disk only via materialize into that profile's `ProfileDirs` |
| No silent optional dependencies | Optional deps are listed in `InstallPlan::optional`; installed only if named in `chosen_optional` |
| No duplicates | Same hash / same project → blocker; probable cross-index duplicate → `possible_duplicate`, install refused until `accept_possible_duplicates` |
| Never two versions active | An update reuses the entry's `ModId`; the report carries the old entry for `install::rollback` |
| Shared dependencies survive uninstall | `Origin::requires` records resolved dependencies; `plan_uninstall` lists `needed_by` and refuses |
| One provider failing never breaks browsing | `ModCatalog::search` returns per-provider `ProviderStatus` |
| Consent | Every call needs a `PerActionGrant`; the HTTP client enforces the allowlist and the Privacy switch per request |

Remote data is clipped (`model::limits`), slugs are reduced to slug characters, links must be
HTTPS, image and download URLs must be on the compile-time allowlist, file names must be plain
`.jar` names (no separators, `..`, leading dots or Windows device names), and ids are validated
before they become URL path segments. Downloaded files are never opened beyond the two-byte zip
check, never unpacked and never executed.

## Threading

Every `ModCatalog` / `Launcher` browser method that takes a `Cancel` blocks on network or disk
and must run on a worker thread. `ModCatalog` is `Clone + Send + Sync`; clones share caches and
in-flight state. `SearchCoordinator` gives a search box debounce and stale-result dropping.

## Launcher entry points

`mod_catalog`, `search_mod_catalog`, `mod_catalog_entry`, `plan_mod_install`, `plan_mod_update`,
`mod_catalog_updates`, `install_mod_plan`, `plan_mod_uninstall`, `uninstall_mod`.
`install_mod_from_repository` now goes through the same planner and installer.

## Tests

- Unit tests in each `browse/*.rs` (planner, installer, catalogue, providers' parsing).
- `crates/launcher-core/tests/mod_browser.rs`: profile isolation, update leaves one version,
  shared-dependency uninstall, Privacy switch.
- Live, ignored by default: `cargo test -p launcher-core -- --ignored catalog_live`
  (CurseForge part runs only with `FATHOM_CURSEFORGE_API_KEY` set).
