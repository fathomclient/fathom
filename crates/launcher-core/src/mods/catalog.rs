//! What we know about specific mods.
//!
//! Every entry here is a fact from `docs/research/mod-policies.md`, which is the
//! source of truth. Nothing in this file is a guess, and where the research said
//! "unclear" the entry says [`RedistributionPolicy::Unclear`] rather than
//! quietly picking the permissive reading.
//!
//! # What this catalogue is for
//!
//! Not a store. It holds no download URLs for anything we are not permitted to
//! fetch, and it is not a list of things to install. Its job is to let the
//! launcher answer three questions about a jar someone already has, or is about
//! to get:
//!
//! 1. **May we fetch this?** — [`RedistributionPolicy`]
//! 2. **What does it need?** — [`Dependency`]
//! 3. **What will it fight with?** — [`Conflict`]
//!
//! Question 3 is the reason this exists at all. Without it, a user who installs
//! `OptiFine` alongside our optimizations gets a broken renderer and no
//! explanation. `mod-policies.md` §8 documents that conflict specifically, and
//! this file is where that knowledge becomes something the launcher can act on.
//!
//! # `OptiFine`, specifically
//!
//! The research is unambiguous: proprietary, all rights reserved, no
//! redistribution, no auto-download. It is in the catalogue precisely so that
//! the launcher **refuses** to fetch it and can explain the conflict — not so it
//! can offer it.

use std::collections::BTreeSet;

use super::definition::{
    Conflict, ConflictKind, DownloadSource, License, MinecraftCompatibility,
    ModDefinition, ModId, ModVersion, PrivacyClassification, Provenance, RedistributionPolicy,
};
use crate::profiles::Loader;

/// When the entries here were last checked against the research.
const REVIEWED: &str = "2026-01";

/// The id our own optimization mod will use.
///
/// Named here so the conflict edges can refer to it before it exists. A mod that
/// is not built yet still has a knowable relationship to `OptiFine`.
pub const FATHOM_OPTIMIZATIONS: &str = "fathom-optimizations";

/// Legacy Fabric API's Modrinth project.
///
/// The slug rather than the opaque id, because it is legible in a diff and
/// Modrinth resolves either. If the project is ever renamed this stops
/// resolving, which is the failure mode to want: a loud 404 beats silently
/// installing whatever now occupies the slug.
pub const MODRINTH_FABRIC_API: &str = "legacy-fabric-api";

/// Legacy OptiFabric on Modrinth: the compatibility bridge that lets an
/// OptiFine jar run under Legacy Fabric.
///
/// MPL-2.0, so unlike OptiFine itself this one *may* be distributed and
/// fetched. Verified 2026-09-17 against `api.modrinth.com`: the
/// `2.3.6+1.3.1-1.13.2` build is tagged for the `legacy-fabric` loader, lists
/// 1.8.9 among its game versions, and declares `fabricloader >=0.15.4` — Fathom
/// ships 0.19.3. Its changelog for that build is "fix deadlock on loader 0.17+
/// (mixin 0.16.0+)", which is why an older build must not be pinned here: 2.2.0
/// predates the fix for the loader generation Fathom actually runs.
pub const MODRINTH_OPTIFABRIC: &str = "legacy-optifabric";

fn id(raw: &str) -> ModId {
    // Every literal in this file is checked by a test, so a panic here would be
    // a compile-time-shaped bug rather than a runtime one.
    ModId::new(raw).unwrap_or_else(|e| unreachable!("catalogue id {raw:?} is invalid: {e}"))
}

fn loaders(list: &[Loader]) -> BTreeSet<Loader> {
    list.iter().copied().collect()
}

/// Everything the launcher knows about, by id.
pub fn entries() -> Vec<ModDefinition> {
    vec![
        optifine(),
        fathom_optimizations(),
        polypatcher(),
        patcher(),
        memoryfix(),
        mouse_delay_fix(),
        fabric_api(),
        optifabric(),
    ]
}

/// Look one up.
pub fn find(wanted: &ModId) -> Option<ModDefinition> {
    entries().into_iter().find(|d| &d.id == wanted)
}

pub fn contains(wanted: &ModId) -> bool {
    entries().iter().any(|d| &d.id == wanted)
}

// ---------------------------------------------------------------------------
// Entries
// ---------------------------------------------------------------------------

/// `OptiFine`.
///
/// `mod-policies.md` §2 and §4: proprietary, all rights reserved, source not
/// public, **no reuse, no auto-download**. §7 names it explicitly under "never
/// auto-downloaded, under any circumstance".
fn optifine() -> ModDefinition {
    ModDefinition {
        id: id("optifine"),
        name: "OptiFine".into(),
        version: ModVersion::new("HD_U_M5"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        // OptiFine 1.8.9 is a Forge-or-standalone mod. It is not a Fabric mod,
        // and listing it as one would produce a suggestion that cannot work.
        loaders: loaders(&[Loader::Forge]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        download: Some(DownloadSource::UserMustSupply {
            reason: "OptiFine is proprietary and its licence does not permit redistribution, \
                     so this launcher will never download it for you."
                .into(),
            where_to_get: "https://optifine.net/downloads".into(),
        }),
        hash: None,
        license: License::Proprietary,
        redistribution: RedistributionPolicy::UserSuppliedOnly,
        privacy: PrivacyClassification::Local,
        dependencies: Vec::new(),
        conflicts: vec![Conflict {
            id: id(FATHOM_OPTIMIZATIONS),
            kind: ConflictKind::OverlappingOptimization,
            detail: "OptiFine rewrites large parts of the renderer. Font rendering, entity \
                     culling, chunk rendering and tile entities all overlap. Fathom's \
                     conflicting patches are turned off when OptiFine is present rather \
                     than fighting it, and entity culling is disabled outright under \
                     OptiFine shaders, which are documented as incompatible with it."
                .into(),
        }],
        summary: "The best-known 1.8.9 performance and visual mod. Proprietary.".into(),
        homepage: Some("https://optifine.net/".into()),
    }
}

/// Our own optimization mod.
///
/// The version tracks `minecraft/fathom-optimizations/gradle.properties`. The
/// module builds now -- Gradle, Loom, real Legacy Fabric mappings -- and the jar
/// it produces is a loadable Fabric mod with an entrypoint and a working Mixin.
/// What there still is not is a *release*: nothing is published anywhere for the
/// launcher to fetch, so `download` says so rather than pointing at a URL that
/// would 404. A portable build ships the jar it built; see
/// `tools/package-portable.ps1`.
///
/// The conflict with `OptiFine` is a fact about what both do to the renderer, and
/// it is knowable before either mod ships.
fn fathom_optimizations() -> ModDefinition {
    ModDefinition {
        id: id(FATHOM_OPTIMIZATIONS),
        name: "Fathom Optimizations".into(),
        // Kept in step with minecraft/fathom-optimizations/gradle.properties.
        version: ModVersion::new("0.3.0-hud"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::LegacyFabric]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        // Not fetchable, and for a reason that is not a gap: this ships inside
        // the launcher. `components::Supply::Bundled` installs it from the
        // `resources/` directory beside the executable, so there is nothing to
        // download, and a download source pointing anywhere would be a second
        // and worse way to get the same file.
        download: Some(DownloadSource::UserMustSupply {
            reason: "Fathom's own module ships with the launcher and is installed into a \
                     Fathom profile automatically, so there is nothing to download."
                .into(),
            where_to_get: "https://github.com/".into(),
        }),
        hash: None,
        license: License::Spdx {
            id: "Apache-2.0".into(),
        },
        redistribution: RedistributionPolicy::FetchOnRequest,
        privacy: PrivacyClassification::Local,
        // None. Its fabric.mod.json asks for the loader, Minecraft and Java,
        // and it hooks the game with its own Mixins: no Fabric API class is
        // referenced anywhere in it. This used to claim Fabric API was
        // required, and blocked launching a profile that ran perfectly.
        dependencies: Vec::new(),
        conflicts: vec![
            Conflict {
                id: id("optifine"),
                kind: ConflictKind::OverlappingOptimization,
                detail: "Both patch the renderer. Fathom disables its overlapping features \
                         when OptiFine is present."
                    .into(),
            },
            Conflict {
                id: id("polypatcher"),
                kind: ConflictKind::OverlappingOptimization,
                detail: "Heavy overlap in font rendering, entity culling and chunk rebuilds. \
                         If you have chosen PolyPatcher, Fathom defers to it."
                    .into(),
            },
            Conflict {
                id: id("patcher"),
                kind: ConflictKind::OverlappingOptimization,
                detail: "Patcher covers much of the same ground and is superseded by \
                         PolyPatcher. Running all three is not useful."
                    .into(),
            },
        ],
        summary: "Fathom's own 1.8.9 module: optimizations, the in-game hub and the HUD.".into(),
        homepage: None,
    }
}

/// `PolyPatcher` (Polyfrost).
///
/// `mod-policies.md` §2/§3: CC BY-NC-SA 4.0 — a licence whose
/// non-commercial and share-alike terms make code reuse impossible for us, and whose redistribution
/// terms are case-by-case. Actively maintained, which is why it, rather than
/// Patcher, is the one users are likely to have.
fn polypatcher() -> ModDefinition {
    ModDefinition {
        id: id("polypatcher"),
        name: "PolyPatcher".into(),
        version: ModVersion::new("1.10.0"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::Forge]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        download: Some(DownloadSource::UserMustSupply {
            reason: "PolyPatcher is CC BY-NC-SA 4.0. Redistribution is case-by-case and we \
                     have not sought permission, so this launcher will not fetch it."
                .into(),
            where_to_get: "https://polyfrost.org/".into(),
        }),
        hash: None,
        license: License::Spdx {
            id: "CC-BY-NC-SA-4.0".into(),
        },
        redistribution: RedistributionPolicy::Unclear,
        privacy: PrivacyClassification::Unknown,
        dependencies: Vec::new(),
        conflicts: vec![
            Conflict {
                id: id("patcher"),
                kind: ConflictKind::Supersedes,
                detail: "PolyPatcher is the maintained continuation of Patcher. Running both \
                         is redundant and they patch the same methods."
                    .into(),
            },
            Conflict {
                id: id(FATHOM_OPTIMIZATIONS),
                kind: ConflictKind::OverlappingOptimization,
                detail: "Heavy overlap with Fathom's own optimizations.".into(),
            },
        ],
        summary: "Maintained 1.8.9 optimization and quality-of-life mod.".into(),
        homepage: Some("https://polyfrost.org/".into()),
    }
}

/// Patcher (`Sk1erLLC`). Same licence as `PolyPatcher`; stale since 2024-03.
fn patcher() -> ModDefinition {
    ModDefinition {
        id: id("patcher"),
        name: "Patcher".into(),
        version: ModVersion::new("1.8.0"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::Forge]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        download: Some(DownloadSource::UserMustSupply {
            reason: "Patcher is CC BY-NC-SA 4.0 and redistribution is case-by-case.".into(),
            where_to_get: "https://sk1er.club/mods/patcher".into(),
        }),
        hash: None,
        license: License::Spdx {
            id: "CC-BY-NC-SA-4.0".into(),
        },
        redistribution: RedistributionPolicy::Unclear,
        privacy: PrivacyClassification::Unknown,
        dependencies: Vec::new(),
        // No `Supersedes` edge here: the direction is "the declaring mod
        // replaces the named one", and Patcher is the *superseded* side.
        // `PolyPatcher` declares it. Declaring it from both would render as
        // "Patcher replaces PolyPatcher", which is backwards.
        conflicts: Vec::new(),
        summary: "The original 1.8.9 optimization mod. No longer maintained.                   Superseded by PolyPatcher."
            .into(),
        homepage: Some("https://sk1er.club/mods/patcher".into()),
    }
}

/// `MemoryFix`.
///
/// `mod-policies.md` §2/§5: **no licence at all**, which means all rights
/// reserved by default rather than public domain. Archived 2019. Named in §7
/// under "never auto-downloaded, under any circumstance".
fn memoryfix() -> ModDefinition {
    ModDefinition {
        id: id("memoryfix"),
        name: "MemoryFix".into(),
        version: ModVersion::new("1.0"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::Forge]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        download: Some(DownloadSource::UserMustSupply {
            reason: "MemoryFix was published with no licence, which means all rights are \
                     reserved by default. This launcher will not fetch it."
                .into(),
            where_to_get: "https://github.com/GirafiStudios/MemoryFix".into(),
        }),
        hash: None,
        license: License::None,
        redistribution: RedistributionPolicy::UserSuppliedOnly,
        privacy: PrivacyClassification::Local,
        dependencies: Vec::new(),
        conflicts: vec![Conflict {
            id: id(FATHOM_OPTIMIZATIONS),
            kind: ConflictKind::Supersedes,
            detail: "Fathom passes -XX:+DisableExplicitGC, which neutralises the same \
                     System.gc() call MemoryFix patches. The JVM flag is equivalent and \
                     needs no mod."
                .into(),
        }],
        summary: "Neutralises vanilla's System.gc() on world load. Archived.".into(),
        homepage: Some("https://github.com/GirafiStudios/MemoryFix".into()),
    }
}

/// `MouseDelayFix`. Also unlicensed; archived 2018.
fn mouse_delay_fix() -> ModDefinition {
    ModDefinition {
        id: id("mousedelayfix"),
        name: "MouseDelayFix".into(),
        version: ModVersion::new("1.0"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::Forge]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        download: Some(DownloadSource::UserMustSupply {
            reason: "MouseDelayFix was published with no licence, which means all rights are \
                     reserved by default. This launcher will not fetch it."
                .into(),
            where_to_get: "https://github.com/OrangeMarshall/MouseDelayFix".into(),
        }),
        hash: None,
        license: License::None,
        redistribution: RedistributionPolicy::UserSuppliedOnly,
        privacy: PrivacyClassification::Local,
        dependencies: Vec::new(),
        conflicts: Vec::new(),
        summary: "Removes input latency added by vanilla's mouse handling. Archived.".into(),
        homepage: Some("https://github.com/OrangeMarshall/MouseDelayFix".into()),
    }
}

/// Fabric API for Legacy Fabric.
///
/// Apache-2.0, the same permissive stack as the loader itself. Recorded as a
/// dependency target; no download source is claimed, because the launcher does
/// not have a verified one and inventing a URL would be exactly the scraping
/// the brief forbids.
fn fabric_api() -> ModDefinition {
    ModDefinition {
        id: id("fabric-api"),
        name: "Fabric API (Legacy)".into(),
        version: ModVersion::new("unknown"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::LegacyFabric]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        // The gap this entry used to describe is closed. Legacy Fabric API is
        // published on Modrinth under Apache-2.0 with a per-file SHA-1, which is
        // a verified source rather than a guessed one -- and Modrinth's terms
        // grant third-party applications an API licence to fetch it.
        //
        // No hash here on purpose: which file is correct depends on the
        // profile's Minecraft version and loader, and it changes when the
        // project publishes. The hash that matters is the one the API returns
        // alongside the file, and `ModStore::adopt_bytes` refuses anything that
        // does not match it.
        download: Some(DownloadSource::Modrinth {
            project: MODRINTH_FABRIC_API.into(),
        }),
        hash: None,
        license: License::Spdx {
            id: "Apache-2.0".into(),
        },
        // Permitted by licence; the blocker is a verified source, not the terms.
        redistribution: RedistributionPolicy::FetchOnRequest,
        privacy: PrivacyClassification::Local,
        dependencies: Vec::new(),
        conflicts: Vec::new(),
        summary: "The shared API most Fabric mods build against.".into(),
        homepage: Some("https://github.com/Legacy-Fabric/fabric".into()),
    }
}

/// Legacy OptiFabric: what loads an `OptiFine` jar under Legacy Fabric.
fn optifabric() -> ModDefinition {
    ModDefinition {
        id: id("optifabric"),
        name: "OptiFabric".into(),
        version: ModVersion::new("2.3.6+1.3.1-1.13.2"),
        minecraft: MinecraftCompatibility::only("1.8.9"),
        loaders: loaders(&[Loader::LegacyFabric]),
        provenance: Provenance::BuiltInCatalogue {
            reviewed: REVIEWED.into(),
        },
        // Modrinth, which returns the file's hash with it; see MODRINTH_OPTIFABRIC
        // for why no older build may be pinned.
        download: Some(DownloadSource::Modrinth {
            project: MODRINTH_OPTIFABRIC.into(),
        }),
        hash: None,
        license: License::Spdx {
            id: "MPL-2.0".into(),
        },
        redistribution: RedistributionPolicy::FetchOnRequest,
        privacy: PrivacyClassification::Local,
        dependencies: Vec::new(),
        conflicts: Vec::new(),
        summary: "Lets OptiFine run on Legacy Fabric.".into(),
        homepage: Some("https://modrinth.com/mod/legacy-optifabric".into()),
    }
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;

    /// `id()` unwraps. This is what makes that safe.
    #[test]
    fn every_catalogue_id_is_valid() {
        for entry in entries() {
            assert!(ModId::new(entry.id.as_str()).is_ok(), "{}", entry.id);
        }
        assert!(ModId::new(FATHOM_OPTIMIZATIONS).is_ok());
    }

    #[test]
    fn ids_are_unique() {
        let mut ids: Vec<String> = entries().iter().map(|e| e.id.to_string()).collect();
        let before = ids.len();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), before, "duplicate id in the catalogue");
    }

    /// The headline rule from `mod-policies.md` §7. If this test ever fails, the
    /// launcher has started doing something the research says it must not.
    #[test]
    fn nothing_the_research_forbids_is_fetchable() {
        for name in [
            "optifine",
            "memoryfix",
            "mousedelayfix",
            "patcher",
            "polypatcher",
        ] {
            let entry = find(&id(name)).expect(name);
            assert!(
                !entry.is_launcher_fetchable(),
                "{name} must never be launcher-fetchable"
            );
            assert!(
                entry.why_not_fetchable().is_some(),
                "{name} must be able to explain why"
            );
        }
    }

    /// Every entry has to be able to tell a user where to get it themselves,
    /// otherwise "we will not fetch this" is a dead end.
    #[test]
    fn every_unfetchable_entry_says_where_to_get_it() {
        for entry in entries() {
            if entry.is_launcher_fetchable() {
                continue;
            }
            match &entry.download {
                Some(DownloadSource::UserMustSupply {
                    reason,
                    where_to_get,
                }) => {
                    assert!(!reason.is_empty(), "{}: empty reason", entry.id);
                    assert!(!where_to_get.is_empty(), "{}: nowhere to get it", entry.id);
                }
                other => panic!("{}: expected UserMustSupply, got {other:?}", entry.id),
            }
        }
    }

    /// The catalogue holds no download URL for anything we may not fetch. A
    /// URL sitting in the source is an invitation to use it.
    #[test]
    fn no_allowlisted_download_source_exists_for_a_forbidden_mod() {
        for entry in entries() {
            if entry.redistribution.may_fetch() {
                continue;
            }
            assert!(
                !entry
                    .download
                    .as_ref()
                    .is_some_and(DownloadSource::is_fetchable),
                "{} may not be fetched, so it must carry no fetchable source",
                entry.id
            );
        }
    }

    /// Nothing is ever written to the store without being checked against a
    /// hash the publisher stated. This test is about *where that hash comes
    /// from*, which differs by source and must never be "nowhere".
    ///
    /// - [`DownloadSource::Allowlisted`] names one exact file, so the hash is
    ///   knowable now and has to be in the catalogue.
    /// - [`DownloadSource::Modrinth`] names a project, and which file is
    ///   correct depends on the profile and changes when the project
    ///   publishes. The hash arrives with the file from the API, and
    ///   `ModStore::adopt_bytes` refuses bytes that do not match it. A hash in
    ///   the catalogue would be a hash for one particular build, pinning every
    ///   profile to it and going stale on the next release.
    #[test]
    fn every_fetchable_entry_has_a_hash_to_check_against() {
        for entry in entries() {
            match &entry.download {
                Some(DownloadSource::Allowlisted { .. }) => assert!(
                    entry.hash.is_some(),
                    "{} names one exact file and must carry its hash",
                    entry.id
                ),
                Some(DownloadSource::Modrinth { project }) => {
                    assert!(!project.is_empty(), "{}: empty project id", entry.id);
                    assert!(
                        entry.hash.is_none(),
                        "{} pins a hash for a file the API chooses",
                        entry.id
                    );
                }
                _ => {}
            }
        }
    }

    /// The catalogue version of Fathom's own module has to match the jar the
    /// build produces, or the interface reports a version nobody is running.
    #[test]
    fn our_own_module_version_matches_the_one_that_is_built() {
        let properties = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../minecraft/fathom-optimizations/gradle.properties"
        );
        let Ok(text) = std::fs::read_to_string(properties) else {
            // The Java module is optional in a source checkout.
            return;
        };
        let built = text
            .lines()
            .find_map(|line| line.trim().strip_prefix("mod_version="))
            .expect("gradle.properties declares a version");
        let catalogued = find(&id(FATHOM_OPTIMIZATIONS))
            .expect("our own entry")
            .version;
        assert_eq!(
            catalogued.as_str(),
            built.trim(),
            "the catalogue and the build disagree about which version of the module exists"
        );
    }

    /// `Supersedes` is directional: the declaring mod replaces the named one.
    /// A live run rendered "Patcher replaces " because both sides
    /// declared it; only the superseding side may.
    #[test]
    fn supersession_is_declared_only_by_the_superseding_side() {
        let poly = find(&id("polypatcher")).unwrap();
        let patcher = find(&id("patcher")).unwrap();

        assert!(
            poly.conflicts
                .iter()
                .any(|c| c.id.as_str() == "patcher" && c.kind == ConflictKind::Supersedes),
            "PolyPatcher supersedes Patcher"
        );
        assert!(
            !patcher
                .conflicts
                .iter()
                .any(|c| c.kind == ConflictKind::Supersedes),
            "the superseded side must not declare it back"
        );
    }

    /// `mod-policies.md` §8 is specifically about this pair.
    #[test]
    fn the_optifine_conflict_is_recorded_from_both_sides() {
        let ours = find(&id(FATHOM_OPTIMIZATIONS)).unwrap();
        let optifine = find(&id("optifine")).unwrap();

        assert!(ours.conflicts.iter().any(|c| c.id.as_str() == "optifine"));
        assert!(optifine
            .conflicts
            .iter()
            .any(|c| c.id.as_str() == FATHOM_OPTIMIZATIONS));

        // And it explains itself rather than just asserting a conflict.
        let detail = &optifine.conflicts[0].detail;
        assert!(detail.contains("renderer"), "{detail}");
        assert!(detail.contains("culling"), "{detail}");
    }

    /// Every conflict edge must point at something in the catalogue, or the
    /// resolver has nothing to resolve against.
    #[test]
    fn every_relationship_points_at_a_known_mod() {
        for entry in entries() {
            for conflict in &entry.conflicts {
                assert!(
                    contains(&conflict.id),
                    "{} conflicts with unknown mod {}",
                    entry.id,
                    conflict.id
                );
            }
            for dependency in &entry.dependencies {
                assert!(
                    contains(&dependency.id),
                    "{} depends on unknown mod {}",
                    entry.id,
                    dependency.id
                );
            }
        }
    }

    /// A conflict that does not say what goes wrong is not actionable.
    #[test]
    fn every_conflict_explains_itself() {
        for entry in entries() {
            for conflict in &entry.conflicts {
                assert!(
                    conflict.detail.len() > 20,
                    "{} -> {}: detail too thin",
                    entry.id,
                    conflict.id
                );
            }
        }
    }

    #[test]
    fn nothing_in_the_catalogue_claims_to_be_user_supplied() {
        for entry in entries() {
            assert!(
                matches!(entry.provenance, Provenance::BuiltInCatalogue { .. }),
                "{} has the wrong provenance",
                entry.id
            );
        }
    }

    /// Every entry is a 1.8.9 mod, because that is what this launcher targets.
    #[test]
    fn the_catalogue_is_about_the_version_we_support() {
        for entry in entries() {
            assert!(
                entry.minecraft.accepts("1.8.9"),
                "{} is not a 1.8.9 mod",
                entry.id
            );
        }
    }

    /// Our own mod is a Fabric mod; `OptiFine` 1.8.9 is not. Listing `OptiFine` as
    /// Fabric-compatible would produce advice that cannot work.
    #[test]
    fn loader_compatibility_matches_reality() {
        assert!(find(&id(FATHOM_OPTIMIZATIONS))
            .unwrap()
            .loaders
            .contains(&Loader::LegacyFabric));
        assert!(find(&id("optifine"))
            .unwrap()
            .loaders
            .contains(&Loader::Forge));
        assert!(!find(&id("optifine"))
            .unwrap()
            .loaders
            .contains(&Loader::LegacyFabric));
    }
}
