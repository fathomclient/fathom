//! The game-side stack, end to end.
//!
//! This milestone's claim is that "a Fathom 1.8.9 profile" now means Legacy
//! Fabric plus Fathom's own module plus a working route to third-party mods —
//! rather than vanilla with a launcher on top. These tests are that claim,
//! written down.
//!
//! Nothing here reaches the network. The one thing that genuinely needs a
//! server is covered by the ignored live test in `mods::modrinth`; what is
//! checked here is everything around it, including that the consent rules make
//! the network unreachable when they should.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::Arc;

use launcher_core::components::{self, Supply, Tier};
use launcher_core::mods::{ModId, ModSource, Origin, ProfileMod, Problem, Severity};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{Loader, Profile, ProfileId, ProfileKind};
use launcher_core::credentials::{CredentialStore, EphemeralCredentialStore};
use launcher_core::services::Launcher;

/// A credential store that keeps nothing. These tests never sign in; the
/// argument exists because `Launcher` owns one, and an ephemeral one makes it
/// impossible for a test to touch the real keyring by accident.
fn store() -> Arc<dyn CredentialStore> {
    Arc::new(EphemeralCredentialStore::default())
}

/// A launcher rooted in a temporary directory, with the module jar available
/// the way a packaged build has it.
fn launcher_with_module() -> (tempfile::TempDir, Launcher) {
    let tmp = tempfile::tempdir().expect("a temporary directory");
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().expect("the data directories");

    let resources = paths.root().join("resources");
    std::fs::create_dir_all(&resources).expect("a resources directory");
    std::fs::write(
        resources.join("fathom-optimizations-0.3.0-hud.jar"),
        b"PK\x03\x04 stands in for the real jar",
    )
    .expect("the module jar");

    let launcher = Launcher::new(paths, store()).expect("a launcher");
    (tmp, launcher)
}

fn launcher_without_module() -> (tempfile::TempDir, Launcher) {
    let tmp = tempfile::tempdir().expect("a temporary directory");
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().expect("the data directories");
    let launcher = Launcher::new(paths, store()).expect("a launcher");
    (tmp, launcher)
}

fn fathom_profile(id: &str) -> Profile {
    let mut profile = Profile::new(ProfileId::new(id).expect("a valid id"), "Fathom 1.8.9");
    profile.kind = ProfileKind::Fathom;
    profile.loader.kind = Loader::LegacyFabric;
    "1.8.9".clone_into(&mut profile.version_id);
    profile
}

fn a_jar(directory: &std::path::Path, name: &str, contents: &[u8]) -> PathBuf {
    let path = directory.join(name);
    let mut bytes = b"PK\x03\x04".to_vec();
    bytes.extend_from_slice(contents);
    std::fs::write(&path, bytes).expect("a jar");
    path
}

// ---------------------------------------------------------------------------
// What a Fathom profile means
// ---------------------------------------------------------------------------

/// The headline. Create a Fathom profile on a build that ships the module and
/// it has the module, without anybody adding a jar.
#[test]
fn a_fathom_profile_arrives_with_fathoms_own_module_installed() {
    let (_tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("fathom");

    let report = launcher
        .ensure_components(&mut profile)
        .expect("components can be ensured");

    assert!(
        report.added.contains(&"fathom-performance"),
        "the module should install itself: {report:?}"
    );
    let entry = profile
        .mods
        .iter()
        .find(|entry| entry.id.as_str() == "fathom-optimizations")
        .expect("the module is in the profile");
    assert_eq!(entry.source, ModSource::FirstParty);
    assert!(entry.enabled);

    // And it is not reported as a gap afterwards.
    assert!(
        launcher.missing_components(&profile).is_empty(),
        "{:?}",
        launcher.missing_components(&profile)
    );
}

/// The three named components are all satisfied by the one jar. This is the
/// test that would fail if the packaging changed and only one of them was
/// actually delivered.
#[test]
fn core_components_are_all_accounted_for_by_the_shipped_jar() {
    let (_tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("fathom");
    let report = launcher.ensure_components(&mut profile).expect("ensured");

    let mut named: Vec<&str> = report
        .added
        .iter()
        .chain(report.updated.iter())
        .chain(report.current.iter())
        .copied()
        .collect();
    named.sort_unstable();

    for required in components::required_for(ProfileKind::Fathom, Loader::LegacyFabric) {
        assert!(
            named.contains(&required.id),
            "{} is required and was not accounted for: {report:?}",
            required.id
        );
    }
    assert!(report.unavailable.is_empty(), "{report:?}");
}

/// A profile that calls itself Fathom and has lost its module says so, and says
/// it in a way that stops a launch — because the launcher can put it back.
#[test]
fn a_fathom_profile_missing_its_module_blocks_and_explains_itself() {
    let (_tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("fathom");
    launcher.ensure_components(&mut profile).expect("ensured");

    // Somebody removed it.
    let id = ModId::new("fathom-optimizations").expect("a valid id");
    profile.mods.remove(&id);

    let problems = launcher.missing_components(&profile);
    assert!(!problems.is_empty(), "the gap should be noticed");
    let problem = problems.first().expect("one problem");
    assert_eq!(problem.severity(), Severity::Blocking);
    assert!(matches!(
        problem,
        Problem::FirstPartyModuleMissing {
            repairable: true,
            ..
        }
    ));

    // And it reaches the resolution the interface reads.
    let resolution = launcher.resolve_mods(&profile);
    assert!(!resolution.is_launchable());
}

/// The same gap on a build that does not ship the jar is a warning, not a
/// block. Refusing to launch over something nobody present can fix would turn a
/// developer build into one that will not start.
#[test]
fn the_same_gap_is_only_a_warning_when_the_build_cannot_supply_it() {
    let (_tmp, launcher) = launcher_without_module();
    let profile = fathom_profile("fathom");

    let problems = launcher.missing_components(&profile);
    assert!(!problems.is_empty(), "it is still reported");
    for problem in &problems {
        assert_eq!(problem.severity(), Severity::Warning, "{problem:?}");
    }
    assert!(launcher.resolve_mods(&profile).is_launchable());
}

/// A Custom profile with the same components chose them. A component it does
/// not have is not a promise it broke, and putting a warning on it would be the
/// launcher inventing a requirement the user never accepted.
#[test]
fn a_custom_profile_is_never_told_it_is_missing_a_fathom_component() {
    let (_tmp, launcher) = launcher_without_module();
    let mut profile = fathom_profile("custom");
    profile.kind = ProfileKind::Custom;

    assert!(launcher.missing_components(&profile).is_empty());
    assert!(launcher.resolve_mods(&profile).is_launchable());
}

// ---------------------------------------------------------------------------
// Consent
// ---------------------------------------------------------------------------

/// Mod repositories are off until somebody turns them on, and every entry point
/// says so rather than failing at the network layer with something obscure.
#[test]
fn browsing_is_refused_with_a_reason_until_it_is_switched_on() {
    let (_tmp, mut launcher) = launcher_with_module();
    let profile = fathom_profile("fathom");

    let blocked = launcher
        .browsing_blocked()
        .expect("repositories are off by default");
    assert!(blocked.contains("Privacy"), "{blocked}");
    assert!(launcher.browse_mods(&profile, "", 0).is_err());
    assert!(launcher.mod_updates(&profile).is_err());

    // Offline mode is a different reason and gets a different sentence, because
    // "turn on mod repositories" is useless advice to somebody who is offline
    // on purpose.
    let mut settings = launcher.settings().clone();
    settings.privacy.mod_repositories = true;
    settings.privacy.offline_mode = true;
    launcher
        .update_settings(settings)
        .expect("settings can be changed");
    let offline = launcher.browsing_blocked().expect("offline blocks it too");
    assert!(offline.contains("offline"), "{offline}");
}

/// Fabric API is fetchable and is deliberately not automatic: the automatic
/// pass runs at launch, and a launch is not somebody asking for a download.
#[test]
fn the_recommended_download_is_offered_and_never_taken_without_asking() {
    let fabric_api = components::find("fabric-api").expect("it is in the registry");
    assert!(matches!(fabric_api.supply, Supply::Managed { .. }));
    assert!(fabric_api.supply.is_requestable());
    assert!(!fabric_api.supply.is_installable());
    assert_ne!(fabric_api.tier, Tier::Core);

    let (_tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("fathom");
    launcher.ensure_components(&mut profile).expect("ensured");

    assert!(
        profile
            .mods
            .iter()
            .all(|entry| entry.id.as_str() != "fabric-api"),
        "nothing was downloaded during an automatic pass"
    );
}

/// `OptiFine` is in the registry so the launcher can refuse it out loud. It must
/// never become something the launcher can fetch.
#[test]
fn optifine_is_never_fetchable_by_any_route() {
    let optifine = components::find("optifine").expect("it is in the registry");
    assert!(!optifine.supply.is_installable());
    assert!(!optifine.supply.is_requestable());
    assert!(matches!(optifine.supply, Supply::UserSupplied { .. }));

    let definition = launcher_core::mods::catalog::find(
        &ModId::new("optifine").expect("a valid id"),
    )
    .expect("it is in the catalogue");
    assert!(!definition.is_launcher_fetchable());
    assert!(definition.why_not_fetchable().is_some());
}

// ---------------------------------------------------------------------------
// User jars, and cleaning up after them
// ---------------------------------------------------------------------------

/// Removing the last reference to a jar deletes its bytes; removing one of two
/// references does not, because the other profile still needs the file — and
/// for a user-supplied jar there is no way to get it back.
#[test]
fn a_stored_jar_is_deleted_only_when_nothing_refers_to_it() {
    let (tmp, launcher) = launcher_with_module();

    let mut first = fathom_profile("first");
    let mut second = fathom_profile("second");
    launcher.save_profile(&first).expect("the first profile");
    launcher.save_profile(&second).expect("the second profile");

    let jar = a_jar(tmp.path(), "shared-mod.jar", b"the same bytes");
    let added = launcher
        .add_mod_from_file(&mut first, &jar)
        .expect("added to the first");
    launcher
        .add_mod_from_file(&mut second, &jar)
        .expect("added to the second");

    let store = launcher.mod_engine();
    assert!(store.store().contains(&added.sha1));

    // One reference goes; the file stays, because the other profile has it.
    let (removed, deleted) = launcher
        .remove_mod_and_collect(&mut first, &added.id)
        .expect("removal works");
    assert!(removed.is_some());
    assert!(!deleted, "the other profile still refers to these bytes");
    assert!(store.store().contains(&added.sha1));

    // The last reference goes; now the file goes with it.
    let (_, deleted) = launcher
        .remove_mod_and_collect(&mut second, &added.id)
        .expect("removal works");
    assert!(deleted, "nothing refers to it any more");
    assert!(!store.store().contains(&added.sha1));
}

/// Garbage collection never touches a file a profile still names, even when the
/// entry is switched off. Disabled is not removed.
#[test]
fn collecting_garbage_spares_a_disabled_mod() {
    let (tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("only");
    launcher.save_profile(&profile).expect("the profile");

    let jar = a_jar(tmp.path(), "switched-off.jar", b"still wanted");
    let added = launcher
        .add_mod_from_file(&mut profile, &jar)
        .expect("added");
    launcher
        .set_mod_enabled(&mut profile, &added.id, false)
        .expect("switched off");

    // The set has to be built from every profile, not from the one in hand.
    // Building it from one profile is exactly the mistake that would delete
    // another profile's mods -- including the Fathom module the seeded profile
    // installed -- so the test builds it the way the service does.
    let mut referenced = BTreeSet::new();
    for other in launcher.profiles() {
        for entry in other.mods.iter() {
            referenced.insert(entry.sha1.clone());
        }
    }
    for entry in profile.mods.iter() {
        referenced.insert(entry.sha1.clone());
    }

    let removed = launcher.mod_engine().store().collect_garbage(&referenced);
    assert!(removed.is_empty(), "nothing referenced was deleted: {removed:?}");
    assert!(
        launcher.mod_engine().store().contains(&added.sha1),
        "a switched-off mod is still wanted"
    );

    // And the same call with an empty reference set does delete things, so the
    // assertion above is about the reference set rather than about a garbage
    // collector that never does anything.
    let swept = launcher
        .mod_engine()
        .store()
        .collect_garbage(&BTreeSet::new());
    assert!(swept.contains(&added.sha1), "{swept:?}");
}

/// A jar the launcher downloaded is recorded well enough to be checked for
/// updates offline; a jar somebody dropped in is not, and is not pretended to
/// be.
#[test]
fn only_a_managed_mod_carries_what_an_update_check_needs() {
    let (tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("mixed");
    launcher.save_profile(&profile).expect("the profile");

    let jar = a_jar(tmp.path(), "mine.jar", b"a file I found");
    let mine = launcher
        .add_mod_from_file(&mut profile, &jar)
        .expect("added");
    let mine = profile.mods.get(&mine.id).expect("it is in the profile");
    assert_eq!(mine.source, ModSource::UserSupplied);
    assert!(mine.origin.is_empty(), "nothing is known about it");
    assert!(!mine.origin.is_updatable());

    // What a managed install records, constructed here rather than downloaded.
    let managed = ProfileMod::new(
        ModId::new("some-mod").expect("a valid id"),
        "d".repeat(40),
        "some-mod-1.2.3.jar",
    )
    .from_source(ModSource::Managed)
    .with_origin(Origin {
        repository: Some("modrinth".to_owned()),
        project: Some("some-mod".to_owned()),
        version_id: Some("abc123".to_owned()),
        ..Origin::default()
    });
    assert!(managed.origin.is_updatable());
}

/// Provenance survives a save and a reload. The whole point of storing it is
/// that "what did I install, and from where" stays answerable later.
#[test]
fn what_was_installed_and_from_where_survives_a_restart() {
    let tmp = tempfile::tempdir().expect("a temporary directory");
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().expect("the data directories");

    let mut profile = fathom_profile("recorded");
    profile.mods.upsert(
        ProfileMod::new(
            ModId::new("legacy-fabric-api").expect("a valid id"),
            "e".repeat(40),
            "legacy-fabric-api-1.13.5.jar",
        )
        .from_source(ModSource::Managed)
        .with_origin(Origin {
            name: Some("Legacy Fabric API".to_owned()),
            license: Some("Apache-2.0".to_owned()),
            minecraft: ["1.8".to_owned(), "1.8.9".to_owned()].into_iter().collect(),
            loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
            repository: Some("modrinth".to_owned()),
            project: Some("9CJED7xi".to_owned()),
            version_id: Some("Znk9CSGQ".to_owned()),
            page: Some("https://modrinth.com/mod/legacy-fabric-api".to_owned()),
            requires: Default::default(),
            summary: None,
        }),
    );

    {
        let launcher = Launcher::new(paths.clone(), store()).expect("a launcher");
        launcher.save_profile(&profile).expect("saved");
    }

    let launcher = Launcher::new(paths, store()).expect("a second launcher");
    let reloaded = launcher
        .profiles()
        .into_iter()
        .find(|profile| profile.id.as_str() == "recorded")
        .expect("it is still there");
    let entry = reloaded
        .mods
        .get(&ModId::new("legacy-fabric-api").expect("a valid id"))
        .expect("the entry survived");

    assert_eq!(entry.source, ModSource::Managed);
    assert_eq!(entry.origin.license.as_deref(), Some("Apache-2.0"));
    assert_eq!(entry.origin.project.as_deref(), Some("9CJED7xi"));
    assert_eq!(entry.origin.version_id.as_deref(), Some("Znk9CSGQ"));
    assert!(entry.origin.minecraft.contains("1.8.9"));
    assert!(entry.origin.loaders.contains("legacy-fabric"));
    assert!(entry.origin.is_updatable());
}

/// A profile written before provenance existed still loads, with an empty
/// record rather than a failure. Every profile on every existing installation
/// is one of these.
#[test]
fn a_profile_written_before_provenance_existed_still_loads() {
    let (_tmp, launcher) = launcher_with_module();
    let mut profile = fathom_profile("old");
    profile.mods.upsert(ProfileMod::new(
        ModId::new("something").expect("a valid id"),
        "f".repeat(40),
        "something.jar",
    ));
    launcher.save_profile(&profile).expect("saved");

    let reloaded = launcher
        .profiles()
        .into_iter()
        .find(|profile| profile.id.as_str() == "old")
        .expect("loaded");
    let entry = reloaded
        .mods
        .get(&ModId::new("something").expect("a valid id"))
        .expect("the entry");
    assert!(entry.origin.is_empty());
    assert_eq!(entry.source, ModSource::UserSupplied);
}
