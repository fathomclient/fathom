//! The three ways to set a profile up, end to end.
//!
//! Creates a Vanilla, a Fathom and a Custom 1.8.9 profile through the real
//! `Launcher` against a real temporary data root, and checks what the brief
//! asked to be checked: Java resolution, component materialization, profile
//! switching, `OptiFine` import, inheritance and persistence.
//!
//! # What is deliberately not here
//!
//! Loader *materialization* — fetching Legacy Fabric's libraries — needs the
//! network, and a unit test that reaches Mojang and `repo.legacyfabric.net` is a
//! test that fails when somebody's wifi does. That path is covered by
//! `launch_pipeline.rs` and by running the app; here the loader is checked as
//! far as it can be offline: the profile asks for Legacy Fabric, the version is
//! left for the launcher to pin, and the loader is one the build implements.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::sync::Arc;

use launcher_core::components;
use launcher_core::credentials::{CredentialStore, EphemeralCredentialStore};
use launcher_core::mods::{ModId, ModSource};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{
    Blueprint, JavaPolicy, Loader, MemoryChoice, OptimizationPreset, ProfileKind,
};
use launcher_core::Launcher;

struct Fixture {
    _tmp: tempfile::TempDir,
    paths: DataPaths,
    launcher: Launcher,
}

impl Fixture {
    /// A launcher over an empty data root, with a shipped module jar in
    /// `resources/` so component installation has something real to install.
    fn new() -> Self {
        let tmp = tempfile::tempdir().expect("a temporary directory");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("the data directories");

        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).expect("resources");
        std::fs::write(
            resources.join("fathom-optimizations-9.9.9.jar"),
            // The store checks the zip magic, so the stand-in has to start
            // like a jar even though nothing ever opens it here.
            b"PK\x03\x04 stand-in for the shipped module",
        )
        .expect("the module jar");

        let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
        let launcher = Launcher::new(paths.clone(), store).expect("a launcher");
        Self {
            _tmp: tmp,
            paths,
            launcher,
        }
    }
}

/// A valid setup of the given kind.
///
/// Free-standing rather than a method: it reads nothing from the fixture,
/// and taking `&self` would suggest the answer depends on the launcher's
/// state when it depends only on the kind.
fn setup_for(name: &str, kind: ProfileKind) -> Blueprint {
    let mut blueprint = Blueprint::recommended(name);
    blueprint.kind = kind;
    if kind != ProfileKind::Fathom {
        blueprint.components.clear();
    }
    if kind == ProfileKind::Custom {
        blueprint.loader = Some(Loader::LegacyFabric);
    }
    blueprint
}

/// Write a plausible `OptiFine` jar somewhere outside launcher storage, the way
/// a user's Downloads folder is outside it.
fn optifine_jar(dir: &std::path::Path) -> std::path::PathBuf {
    let path = dir.join("OptiFine_1.8.9_HD_U_M5.jar");
    std::fs::write(&path, b"PK\x03\x04 not really OptiFine").expect("the jar");
    path
}

// ---------------------------------------------------------------------------
// Creating the three
// ---------------------------------------------------------------------------

#[test]
fn all_three_profile_types_can_be_created() {
    let fixture = Fixture::new();

    for (name, kind) in [
        ("Vanilla 1.8.9", ProfileKind::Vanilla),
        ("Fathom 1.8.9 test", ProfileKind::Fathom),
        ("Custom 1.8.9", ProfileKind::Custom),
    ] {
        let blueprint = setup_for(name, kind);
        assert!(
            blueprint.is_valid(),
            "{name} is not a valid setup: {:?}",
            blueprint.problems()
        );
        let (profile, _) = fixture
            .launcher
            .create_from_blueprint(&blueprint)
            .unwrap_or_else(|error| panic!("{name} could not be created: {error}"));
        assert_eq!(profile.kind, kind);
        assert_eq!(profile.display_name, name);
        assert_eq!(profile.version_id, "1.8.9");
    }

    // Three created, plus the one Fathom seeds on a fresh installation.
    assert_eq!(fixture.launcher.profiles().len(), 4);
}

/// A Vanilla profile is exactly what it says: no loader, no components, and
/// nothing the launcher will add to it later.
#[test]
fn a_vanilla_profile_stays_vanilla() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Vanilla 1.8.9", ProfileKind::Vanilla);
    let (mut profile, report) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    assert_eq!(profile.loader_kind(), Loader::Vanilla);
    assert!(profile.mods.is_empty());
    assert!(!profile.owns_components());
    assert!(!report.changed_anything());

    // And a second pass adds nothing, however many times it runs.
    let again = fixture.launcher.ensure_components(&mut profile).unwrap();
    assert!(!again.changed_anything());
    assert!(profile.mods.is_empty());
}

/// The milestone in one test: a Fathom profile arrives with the loader, the
/// preset and Fathom's own module already in it, with nobody having added a
/// jar to a mods folder.
#[test]
fn a_fathom_profile_installs_its_own_components() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Fathom 1.8.9 test", ProfileKind::Fathom);
    let (profile, report) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    assert_eq!(profile.loader_kind(), Loader::LegacyFabric);
    assert!(profile.loader_kind().is_implemented());
    // Left for `ensure_loader` to pin at first launch rather than baked in.
    assert_eq!(profile.loader.version, None);
    assert_eq!(profile.optimizations.preset, OptimizationPreset::Balanced);
    assert!(profile.optimizations.rendering.values().any(|on| *on));

    let module = ModId::new("fathom-optimizations").unwrap();
    let entry = profile
        .mods
        .iter()
        .find(|entry| entry.id == module)
        .expect("the module was installed without anybody adding it");
    assert!(entry.enabled);
    assert_eq!(
        entry.source,
        ModSource::FirstParty,
        "a component the launcher installed must be marked as its own"
    );
    assert!(report.added.contains(&"fathom-performance"), "{report:?}");
}

/// A component that was deleted comes back, and one the user switched off does
/// not come back on.
#[test]
fn a_missing_component_is_restored_and_a_disabled_one_is_left_off() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Fathom 1.8.9 test", ProfileKind::Fathom);
    let (mut profile, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();
    let module = ModId::new("fathom-optimizations").unwrap();

    profile.mods.remove(&module);
    let report = fixture.launcher.ensure_components(&mut profile).unwrap();
    assert!(report.added.contains(&"fathom-performance"), "{report:?}");
    assert!(profile.mods.contains(&module));

    let mut entry = profile.mods.iter().find(|e| e.id == module).unwrap().clone();
    entry.enabled = false;
    profile.mods.upsert(entry);
    fixture.launcher.ensure_components(&mut profile).unwrap();
    assert!(
        !profile.mods.iter().find(|e| e.id == module).unwrap().enabled,
        "a component the user switched off was switched back on"
    );
}

/// Custom means the user decides. The launcher installs nothing into it, even
/// under a loader that could take everything.
#[test]
fn a_custom_profile_is_left_entirely_to_the_user() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Custom 1.8.9", ProfileKind::Custom);
    let (mut profile, report) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    assert_eq!(profile.loader_kind(), Loader::LegacyFabric);
    assert!(!report.changed_anything());
    assert!(profile.mods.is_empty());

    let again = fixture.launcher.ensure_components(&mut profile).unwrap();
    assert!(!again.changed_anything());
}

// ---------------------------------------------------------------------------
// Java
// ---------------------------------------------------------------------------

/// Each policy resolves to a different answer on the same machine, which is the
/// whole reason it replaced a switch.
#[test]
fn each_java_policy_resolves_differently() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Java test", ProfileKind::Fathom);
    let (mut profile, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    // No managed runtime is installed in a temporary data root, and the scan
    // has not run, so `System` finds nothing and says so rather than offering
    // to download one.
    profile.java = JavaPolicy::System;
    let outcome = fixture.launcher.resolve_java(&profile, 8);
    assert!(
        matches!(outcome, launcher_core::services::JavaOutcome::Missing { .. }),
        "System must never offer to download: {outcome:?}"
    );

    // Automatic and Managed both may fetch one, so both ask for an install
    // rather than reporting failure.
    for policy in [JavaPolicy::Automatic, JavaPolicy::Managed] {
        profile.java = policy.clone();
        let outcome = fixture.launcher.resolve_java(&profile, 8);
        assert!(
            matches!(
                outcome,
                launcher_core::services::JavaOutcome::NeedsInstall { required_major: 8 }
            ),
            "{policy:?} should offer to install: {outcome:?}"
        );
    }

    // A custom path that is not there is named as such, not reported as "no
    // Java found" — the user chose that path and needs to know it moved.
    profile.java = JavaPolicy::Custom {
        path: fixture.paths.root().join("nowhere/java.exe"),
    };
    let outcome = fixture.launcher.resolve_java(&profile, 8);
    match outcome {
        launcher_core::services::JavaOutcome::Missing { reason, .. } => {
            assert!(reason.contains("chose"), "{reason}");
        }
        other => panic!("expected a named failure, got {other:?}"),
    }
}

/// Choosing a policy that downloads has to open the network allowlist too, or
/// the choice silently does nothing.
#[test]
fn the_java_policy_drives_the_network_allowlist() {
    let mut settings = launcher_core::configuration::Settings::default();

    settings.set_java_policy(JavaPolicy::Managed);
    assert!(settings.to_network_policy().allow_managed_java);

    settings.set_java_policy(JavaPolicy::System);
    assert!(!settings.to_network_policy().allow_managed_java);

    // Offline still overrules everything.
    settings.set_java_policy(JavaPolicy::Automatic);
    settings.privacy.offline_mode = true;
    assert!(!settings.to_network_policy().allow_managed_java);
}

// ---------------------------------------------------------------------------
// OptiFine
// ---------------------------------------------------------------------------

/// The file is copied in, hashed, identified and recorded as the user's. It is
/// never fetched, and the user's own copy is left where it was.
#[test]
fn optifine_is_imported_as_a_user_supplied_file() {
    let fixture = Fixture::new();
    let downloads = tempfile::tempdir().expect("somewhere outside launcher storage");
    let jar = optifine_jar(downloads.path());

    let blueprint = setup_for("Fathom 1.8.9 test", ProfileKind::Fathom);
    let (mut profile, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    let (entry, identified) = fixture
        .launcher
        .import_mod_from_file(&mut profile, &jar)
        .expect("the import");

    assert_eq!(identified.name.as_deref(), Some("OptiFine"));
    assert_eq!(identified.version.as_deref(), Some("HD_U_M5"));
    assert_eq!(identified.minecraft.as_deref(), Some("1.8.9"));
    // Under the catalogue's id, which is what connects it to the conflict rules
    // recorded about it.
    assert_eq!(entry.id.as_str(), "optifine");
    assert_eq!(entry.source, ModSource::UserSupplied);
    assert_eq!(entry.sha1.len(), 40);

    assert!(jar.is_file(), "the user's own copy must not be moved");
}

/// The launcher must never be able to describe `OptiFine` as something it can
/// install for you.
#[test]
fn optifine_is_never_installable_by_the_launcher() {
    let optifine = components::find("optifine").expect("in the registry");
    assert!(!optifine.supply.is_installable());
    assert_ne!(optifine.tier, components::Tier::Core);
    assert_eq!(
        optifine.supply.where_to_get(),
        Some("https://optifine.net/downloads")
    );
}

// ---------------------------------------------------------------------------
// Switching, inheriting, persisting
// ---------------------------------------------------------------------------

#[test]
fn switching_the_selected_profile_survives_a_restart() {
    let fixture = Fixture::new();
    let (vanilla, _) = fixture
        .launcher
        .create_from_blueprint(&setup_for("Vanilla 1.8.9", ProfileKind::Vanilla))
        .unwrap();
    let (fathom, _) = fixture
        .launcher
        .create_from_blueprint(&setup_for("Fathom 1.8.9 test", ProfileKind::Fathom))
        .unwrap();

    let mut settings = fixture.launcher.settings().clone();
    settings.selected_profile = Some(vanilla.id.clone());
    let mut launcher = fixture.launcher;
    launcher.update_settings(settings).unwrap();

    let mut settings = launcher.settings().clone();
    settings.selected_profile = Some(fathom.id.clone());
    launcher.update_settings(settings).unwrap();
    drop(launcher);

    let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
    let reopened = Launcher::new(fixture.paths.clone(), store).expect("reopened");
    assert_eq!(reopened.settings().selected_profile.as_ref(), Some(&fathom.id));
}

/// Everything a profile carries has to survive being written and read back,
/// including the fields this milestone added.
#[test]
fn a_profile_round_trips_through_disk_with_its_new_fields() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Fathom 1.8.9 test", ProfileKind::Fathom);
    let (mut profile, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();

    profile.presentation.theme = Some("bg_3".to_owned());
    profile.presentation.resource_pack = Some("faithful.zip".to_owned());
    profile.presentation.hud_layout = Some("bedwars".to_owned());
    profile.servers.push(launcher_core::profiles::ServerShortcut {
        name: "Practice".to_owned(),
        address: "play.example.net".to_owned(),
    });
    profile.java = JavaPolicy::Managed;
    profile.memory = MemoryChoice::Explicit(3072).resolve();
    fixture.launcher.save_profile(&profile).unwrap();

    let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
    let reopened = Launcher::new(fixture.paths.clone(), store).expect("reopened");
    let read_back = reopened
        .profiles()
        .into_iter()
        .find(|candidate| candidate.id == profile.id)
        .expect("still there");

    assert_eq!(read_back, profile);
    assert_eq!(read_back.kind, ProfileKind::Fathom);
    assert_eq!(read_back.presentation.theme.as_deref(), Some("bg_3"));
    assert_eq!(read_back.servers.len(), 1);
    assert_eq!(read_back.java, JavaPolicy::Managed);
    assert_eq!(read_back.memory.heap_mb, 3072);
}

/// The brief's example: a Bedwars profile that is the same client with a
/// different look, and stays the same client when the parent changes.
#[test]
fn a_derived_profile_follows_its_parent() {
    let fixture = Fixture::new();
    let (mut parent, _) = fixture
        .launcher
        .create_from_blueprint(&setup_for("Fathom 1.8.9 Competitive", ProfileKind::Fathom))
        .unwrap();

    let mut child = fixture
        .launcher
        .derive_profile(&parent.id, "Bedwars")
        .expect("the variant");
    child.presentation.resource_pack = Some("bedwars-16x.zip".to_owned());
    fixture.launcher.save_profile(&child).unwrap();

    // The parent's preset moves.
    parent.optimizations.preset = OptimizationPreset::Competitive;
    parent.memory.heap_mb = 4096;
    fixture.launcher.save_profile(&parent).unwrap();

    let resolved = fixture.launcher.resolve_profile(&child).expect("resolved");
    assert_eq!(resolved.optimizations.preset, OptimizationPreset::Competitive);
    assert_eq!(resolved.memory.heap_mb, 4096);
    assert_eq!(resolved.loader_kind(), Loader::LegacyFabric);
    // And it keeps its own name and its own look.
    assert_eq!(resolved.display_name, "Bedwars");
    assert_eq!(
        resolved.presentation.resource_pack.as_deref(),
        Some("bedwars-16x.zip")
    );
    // Nothing large is duplicated: the variant is a JSON file.
    assert_ne!(resolved.id, parent.id);
}

/// A blueprint that could never work must not reach the disk.
#[test]
fn an_impossible_setup_creates_nothing() {
    let fixture = Fixture::new();
    let before = fixture.launcher.profiles().len();

    let mut blueprint = setup_for("Forge please", ProfileKind::Custom);
    blueprint.loader = Some(Loader::Forge);
    assert!(fixture.launcher.create_from_blueprint(&blueprint).is_err());

    let mut blueprint = setup_for("", ProfileKind::Fathom);
    blueprint.name = "   ".to_owned();
    assert!(fixture.launcher.create_from_blueprint(&blueprint).is_err());

    assert_eq!(fixture.launcher.profiles().len(), before);
}

/// Two profiles with the same name get different directories rather than one
/// overwriting the other.
#[test]
fn two_setups_with_the_same_name_do_not_collide() {
    let fixture = Fixture::new();
    let blueprint = setup_for("Same name", ProfileKind::Vanilla);
    let (first, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();
    let (second, _) = fixture.launcher.create_from_blueprint(&blueprint).unwrap();
    assert_ne!(first.id, second.id);
    assert_eq!(first.display_name, second.display_name);
}
