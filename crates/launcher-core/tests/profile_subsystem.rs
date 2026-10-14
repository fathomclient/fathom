//! End-to-end tests for the profile subsystem.
//!
//! The priority here is data safety, so the tests are weighted towards the
//! things that destroy data rather than the things that merely annoy: deletion
//! boundaries, path escape, corruption, and what a clone does and does not
//! carry.
//!
//! Everything runs against a real temporary data root through the real
//! `ProfileOps`, because the interesting failures are filesystem failures and a
//! mocked filesystem would not have any of them.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::path::{Path, PathBuf};

use launcher_core::mods::{ModId, ProfileMod};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::model::{JavaPolicy, JavaPreference};
use launcher_core::profiles::ops::ProfileOps;
use launcher_core::profiles::validate;
use launcher_core::profiles::{
    Defect, GameDirectory, Loader, Profile, ProfileError, ProfileHealth, ProfileId, ProfileStore,
    SCHEMA_VERSION,
};

struct Fixture {
    /// Kept alive so the temporary tree outlives the fixture, and used directly
    /// by the tests that need somewhere *outside* launcher storage to prove
    /// nothing reaches it.
    tmp: tempfile::TempDir,
    paths: DataPaths,
    ops: ProfileOps,
}

impl Fixture {
    fn new() -> Self {
        Self::rooted_at(None)
    }

    fn rooted_at(suffix: Option<&str>) -> Self {
        let tmp = tempfile::tempdir().expect("tempdir");
        let root = match suffix {
            Some(s) => tmp.path().join(s),
            None => tmp.path().to_path_buf(),
        };
        let paths = DataPaths::rooted_at(&root);
        paths.ensure().expect("data dirs");
        let ops = ProfileOps::new(paths.clone());
        Self { tmp, paths, ops }
    }

    fn store(&self) -> &ProfileStore {
        self.ops.store()
    }

    /// A profile with a world, a screenshot and a mod jar in it, so operations
    /// can be checked against data that would be painful to lose.
    fn populated(&self, name: &str) -> Profile {
        let profile = self.ops.create(name).expect("create");
        let dirs = self.store().dirs(&profile.id);

        std::fs::create_dir_all(dirs.saves_dir().join("MyWorld")).unwrap();
        std::fs::write(dirs.saves_dir().join("MyWorld/level.dat"), b"WORLD DATA").unwrap();
        std::fs::write(dirs.options_file(), b"fov:1.0\n").unwrap();
        std::fs::write(dirs.screenshots_dir().join("shot.png"), b"PNG").unwrap();
        std::fs::write(dirs.mods_dir().join("cool.jar"), b"JAR").unwrap();
        profile
    }

    /// Put something in shared storage, so tests can assert it survives.
    fn seed_shared(&self) -> PathBuf {
        let library = self
            .paths
            .libraries_dir()
            .join("com/example/thing/1.0/thing-1.0.jar");
        std::fs::create_dir_all(library.parent().unwrap()).unwrap();
        std::fs::write(&library, b"SHARED LIBRARY").unwrap();
        library
    }
}

fn id(name: &str) -> ProfileId {
    ProfileId::new(name).expect("valid id")
}

// ===========================================================================
// Create
// ===========================================================================

#[test]
fn creating_a_profile_makes_every_directory_it_needs() {
    let f = Fixture::new();
    let profile = f.ops.create("My Profile").unwrap();
    assert_eq!(profile.id.as_str(), "my-profile");

    let dirs = f.store().dirs(&profile.id);
    for dir in dirs.all_dirs() {
        assert!(dir.is_dir(), "{} was not created", dir.display());
    }
    assert!(dirs.manifest_file().is_file());
}

#[test]
fn two_profiles_with_the_same_name_get_distinct_ids_and_directories() {
    let f = Fixture::new();
    let a = f.ops.create("Same Name").unwrap();
    let b = f.ops.create("Same Name").unwrap();

    assert_ne!(a.id, b.id);
    assert_eq!(a.id.as_str(), "same-name");
    assert_eq!(b.id.as_str(), "same-name-2");
    assert_ne!(f.store().dirs(&a.id).root(), f.store().dirs(&b.id).root());
}

/// A display name is untrusted: it becomes a directory name.
#[test]
fn a_hostile_display_name_cannot_produce_a_directory_outside_the_profiles_root() {
    let f = Fixture::new();
    for name in [
        "../../escape",
        "..\\..\\escape",
        "/etc/passwd",
        "C:\\Windows\\System32",
        "CON",
        "nul",
        "a:b",
        "....//....//x",
    ] {
        let Ok(profile) = f.ops.create(name) else {
            continue; // refused outright, which is also correct
        };
        let root = f.store().dirs(&profile.id).root().to_path_buf();
        assert!(
            root.starts_with(f.paths.profiles_dir()),
            "{name:?} produced {}, which escapes the profiles root",
            root.display()
        );
        // And it is exactly one level deep: no nesting from embedded separators.
        assert_eq!(
            root.parent(),
            Some(f.paths.profiles_dir().as_path()),
            "{name:?} produced a nested directory"
        );
    }
}

// ===========================================================================
// Clone
// ===========================================================================

#[test]
fn cloning_copies_worlds_options_and_mods() {
    let f = Fixture::new();
    let original = f.populated("Original");
    let copy = f.ops.clone_profile(&original.id, "Copy").unwrap();

    let dirs = f.store().dirs(&copy.id);
    assert_eq!(
        std::fs::read(dirs.saves_dir().join("MyWorld/level.dat")).unwrap(),
        b"WORLD DATA"
    );
    assert_eq!(std::fs::read(dirs.options_file()).unwrap(), b"fov:1.0\n");
    assert_eq!(
        std::fs::read(dirs.mods_dir().join("cool.jar")).unwrap(),
        b"JAR"
    );
    assert_eq!(
        std::fs::read(dirs.screenshots_dir().join("shot.png")).unwrap(),
        b"PNG"
    );
}

#[test]
fn cloning_copies_configuration_but_gives_the_copy_its_own_identity() {
    let f = Fixture::new();
    let mut original = f.ops.create("Original").unwrap();
    original.memory.heap_mb = 6144;
    original.display.fullscreen = true;
    original.jvm.extra_args = vec!["-Dfathom.test=1".into()];
    f.store().save(&original).unwrap();

    let copy = f.ops.clone_profile(&original.id, "Copy").unwrap();

    assert_eq!(copy.memory.heap_mb, 6144);
    assert!(copy.display.fullscreen);
    assert_eq!(copy.jvm.extra_args, original.jvm.extra_args);

    assert_ne!(copy.id, original.id);
    assert_eq!(copy.display_name, "Copy");
    assert!(copy.created_at >= original.created_at);
}

/// The two instances must be genuinely separate afterwards, not two views of
/// one directory. Writing to one is the only way to be sure.
#[test]
fn a_clone_is_isolated_from_its_original() {
    let f = Fixture::new();
    let original = f.populated("Original");
    let copy = f.ops.clone_profile(&original.id, "Copy").unwrap();

    let copy_world = f
        .store()
        .dirs(&copy.id)
        .saves_dir()
        .join("MyWorld/level.dat");
    std::fs::write(&copy_world, b"CHANGED IN THE COPY").unwrap();

    let original_world = f
        .store()
        .dirs(&original.id)
        .saves_dir()
        .join("MyWorld/level.dat");
    assert_eq!(
        std::fs::read(original_world).unwrap(),
        b"WORLD DATA",
        "writing to the clone must not reach the original"
    );
}

/// Natives carry a stamp naming the version they were extracted for. Copying
/// them would hand the clone a stamp describing an extraction that never
/// happened in its directory.
#[test]
fn cloning_does_not_carry_natives_or_logs() {
    let f = Fixture::new();
    let original = f.populated("Original");
    let dirs = f.store().dirs(&original.id);
    std::fs::write(dirs.natives_dir().join("lwjgl64.dll"), b"NATIVE").unwrap();
    std::fs::write(dirs.logs_dir().join("launch-1.log"), b"old run").unwrap();

    let copy = f.ops.clone_profile(&original.id, "Copy").unwrap();
    let copy_dirs = f.store().dirs(&copy.id);

    assert!(!copy_dirs.natives_dir().join("lwjgl64.dll").exists());
    assert!(!copy_dirs.logs_dir().join("launch-1.log").exists());
    // But the directories themselves exist, ready for the next launch.
    assert!(copy_dirs.natives_dir().is_dir());
    assert!(copy_dirs.logs_dir().is_dir());
}

/// Two profiles pointing at one external directory would fight over the same
/// `level.dat`, so the clone gets its own.
#[test]
fn cloning_a_profile_with_an_external_game_directory_does_not_share_it() {
    let f = Fixture::new();
    let external = f.tmp.path().join("elsewhere");
    std::fs::create_dir_all(&external).unwrap();

    let mut original = f.ops.create("External").unwrap();
    original.game_directory = GameDirectory::External {
        path: external.clone(),
    };
    f.store().save(&original).unwrap();

    let copy = f.ops.clone_profile(&original.id, "Copy").unwrap();
    assert_eq!(copy.game_directory, GameDirectory::Managed);
    assert!(copy.game_directory.is_launcher_owned());
}

// ===========================================================================
// Rename
// ===========================================================================

/// The id is the directory name and appears in logs. Renaming is a display
/// change, and moving every file for it would break anything holding the id.
#[test]
fn renaming_changes_the_name_but_not_the_id_or_the_directory() {
    let f = Fixture::new();
    let original = f.populated("Before");
    let before_root = f.store().dirs(&original.id).root().to_path_buf();

    let renamed = f.ops.rename(&original.id, "After").unwrap();

    assert_eq!(renamed.display_name, "After");
    assert_eq!(renamed.id, original.id);
    assert_eq!(f.store().dirs(&renamed.id).root(), before_root);
    assert!(
        before_root
            .join("minecraft/saves/MyWorld/level.dat")
            .is_file(),
        "the world must still be there"
    );
}

#[test]
fn renaming_to_nothing_is_refused() {
    let f = Fixture::new();
    let profile = f.ops.create("Name").unwrap();
    assert!(f.ops.rename(&profile.id, "   ").is_err());
    assert!(f.ops.rename(&profile.id, "").is_err());
    // The original name survives the refusal.
    assert_eq!(f.store().load(&profile.id).unwrap().display_name, "Name");
}

// ===========================================================================
// Deletion boundaries -- the most important tests in this file
// ===========================================================================

/// Deleting must not destroy. A world someone has played for years is behind
/// this button.
#[test]
fn deleting_moves_the_instance_to_the_recovery_area_intact() {
    let f = Fixture::new();
    let profile = f.populated("Doomed");

    let receipt = f.ops.delete(&profile.id).unwrap();
    assert!(!receipt.permanent);
    let moved = receipt.moved_to.expect("moved somewhere");

    assert!(
        !f.store().dirs(&profile.id).root().exists(),
        "gone from profiles"
    );
    assert!(moved.is_dir(), "and present in the recovery area");
    assert_eq!(
        std::fs::read(moved.join("minecraft/saves/MyWorld/level.dat")).unwrap(),
        b"WORLD DATA",
        "the world must survive deletion"
    );
}

#[test]
fn a_deleted_profile_can_be_restored() {
    let f = Fixture::new();
    let profile = f.populated("Restorable");
    let receipt = f.ops.delete(&profile.id).unwrap();

    f.ops
        .restore(&receipt.moved_to.unwrap(), &profile.id)
        .unwrap();

    let back = f.store().load(&profile.id).unwrap();
    assert_eq!(back.display_name, "Restorable");
    assert_eq!(
        std::fs::read(
            f.store()
                .dirs(&profile.id)
                .saves_dir()
                .join("MyWorld/level.dat")
        )
        .unwrap(),
        b"WORLD DATA"
    );
}

/// The rule the spec is most emphatic about. `profile.json` is an ordinary
/// file that a user, a bad merge or a hostile import can edit, and a recursive
/// delete driven by its contents is the worst thing this program could do.
#[test]
fn no_path_outside_launcher_storage_can_ever_be_removed() {
    let f = Fixture::new();

    let precious = f.tmp.path().join("precious");
    std::fs::create_dir_all(precious.join("irreplaceable")).unwrap();
    std::fs::write(precious.join("irreplaceable/photos.zip"), b"MEMORIES").unwrap();

    let mut candidates = vec![
        precious.clone(),
        f.tmp.path().to_path_buf(),
        f.paths.root().to_path_buf(),
        f.paths.shared_cache(),
        f.paths.profiles_dir(),
        f.paths.profiles_dir().join("..").join("shared"),
        PathBuf::from("/"),
    ];
    if cfg!(windows) {
        candidates.push(PathBuf::from(r"C:\"));
        candidates.push(PathBuf::from(r"C:\Windows"));
    }

    for candidate in candidates {
        let result = f.ops.removable_root(&candidate);
        assert!(
            matches!(result, Err(ProfileError::RefusingToDeleteOutsideStorage(_))),
            "{} must be refused, got {result:?}",
            candidate.display()
        );
    }

    assert!(
        precious.join("irreplaceable/photos.zip").is_file(),
        "nothing outside storage may be touched"
    );
}

/// The one path shape that must be accepted, so the check is not simply
/// refusing everything.
#[test]
fn a_real_profile_directory_is_accepted_as_removable() {
    let f = Fixture::new();
    let profile = f.ops.create("Real").unwrap();
    let root = f.store().dirs(&profile.id).root().to_path_buf();
    let approved = f.ops.removable_root(&root).expect("a profile is removable");
    assert!(approved.ends_with(profile.id.as_str()));
}

/// A tampered manifest is exactly the attack this defends against.
#[test]
fn a_profile_pointing_its_game_directory_at_someone_elses_data_cannot_delete_it() {
    let f = Fixture::new();
    let precious = f.tmp.path().join("documents");
    std::fs::create_dir_all(&precious).unwrap();
    std::fs::write(precious.join("thesis.txt"), b"YEARS OF WORK").unwrap();

    let mut profile = f.ops.create("Tampered").unwrap();
    profile.game_directory = GameDirectory::External {
        path: precious.clone(),
    };
    f.store().save(&profile).unwrap();

    let receipt = f.ops.delete(&profile.id).unwrap();

    assert!(
        precious.join("thesis.txt").is_file(),
        "an external game directory must never be deleted"
    );
    assert_eq!(receipt.left_alone, Some(precious));
    assert!(receipt.message().contains("left untouched"));
}

/// Shared storage is content-addressed and shared by every profile. Deleting
/// one profile must not reclaim a byte of it.
#[test]
fn deleting_a_profile_never_touches_shared_storage() {
    let f = Fixture::new();
    let library = f.seed_shared();
    let profile = f.populated("Doomed");

    f.ops.delete(&profile.id).unwrap();

    assert!(library.is_file(), "shared libraries must survive");
    assert_eq!(std::fs::read(&library).unwrap(), b"SHARED LIBRARY");
    assert!(f.paths.assets_dir().is_dir());
    assert!(f.paths.versions_dir().is_dir());
}

#[test]
fn deleting_one_profile_leaves_its_neighbours_untouched() {
    let f = Fixture::new();
    let keep = f.populated("Keep");
    let drop = f.populated("Drop");

    f.ops.delete(&drop.id).unwrap();

    assert!(f.store().exists(&keep.id));
    assert_eq!(
        std::fs::read(
            f.store()
                .dirs(&keep.id)
                .saves_dir()
                .join("MyWorld/level.dat")
        )
        .unwrap(),
        b"WORLD DATA"
    );
}

/// Destroying data is always a second, explicit act.
#[test]
fn purging_is_a_separate_step_and_only_reaches_the_recovery_area() {
    let f = Fixture::new();
    let profile = f.populated("Doomed");
    let moved = f.ops.delete(&profile.id).unwrap().moved_to.unwrap();

    // Anything outside the trash is refused even here.
    for outside in [
        f.paths.profiles_dir(),
        f.paths.shared_cache(),
        f.ops.trash_dir(),
        f.tmp.path().to_path_buf(),
    ] {
        assert!(
            f.ops.purge(&outside).is_err(),
            "{} must not be purgeable",
            outside.display()
        );
    }

    let receipt = f.ops.purge(&moved).unwrap();
    assert!(receipt.permanent);
    assert!(!moved.exists());
}

#[test]
fn deleting_twice_in_the_same_second_does_not_collide() {
    let f = Fixture::new();
    let a = f.ops.create("Twice").unwrap();
    let first = f.ops.delete(&a.id).unwrap().moved_to.unwrap();

    let b = f.ops.create("Twice").unwrap();
    assert_eq!(b.id, a.id, "the id is free again after deletion");
    let second = f.ops.delete(&b.id).unwrap().moved_to.unwrap();

    assert_ne!(first, second);
    assert!(first.is_dir() && second.is_dir());
    assert_eq!(f.ops.trashed().len(), 2);
}

// ===========================================================================
// Shared versus isolated data
// ===========================================================================

#[test]
fn profiles_share_nothing_mutable() {
    let f = Fixture::new();
    let a = f.populated("A");
    let b = f.populated("B");

    let a_dirs = f.store().dirs(&a.id);
    let b_dirs = f.store().dirs(&b.id);

    for (x, y) in [
        (a_dirs.saves_dir(), b_dirs.saves_dir()),
        (a_dirs.mods_dir(), b_dirs.mods_dir()),
        (a_dirs.config_dir(), b_dirs.config_dir()),
        (a_dirs.screenshots_dir(), b_dirs.screenshots_dir()),
        (a_dirs.natives_dir(), b_dirs.natives_dir()),
        (a_dirs.logs_dir(), b_dirs.logs_dir()),
    ] {
        assert_ne!(x, y);
        assert!(!x.starts_with(&y) && !y.starts_with(&x));
    }

    // Writing to one is invisible to the other.
    std::fs::write(a_dirs.options_file(), b"A OPTIONS").unwrap();
    assert_eq!(std::fs::read(b_dirs.options_file()).unwrap(), b"fov:1.0\n");
}

#[test]
fn no_profile_directory_lies_inside_shared_storage() {
    let f = Fixture::new();
    let profile = f.ops.create("Main").unwrap();
    let shared = f.paths.shared_cache();

    for dir in f.store().dirs(&profile.id).all_dirs() {
        assert!(
            !dir.starts_with(&shared),
            "{} is inside shared storage",
            dir.display()
        );
    }
}

// ===========================================================================
// Export and import
// ===========================================================================

#[test]
fn an_export_carries_configuration_and_nothing_else() {
    let f = Fixture::new();
    let profile = f.populated("Exportable");
    let json = f.ops.export_json(&profile.id).unwrap();

    // Configuration is there.
    assert!(json.contains("Exportable"));
    assert!(json.contains("1.8.9"));
    assert!(json.contains(&SCHEMA_VERSION.to_string()));

    // The user's data is not.
    for forbidden in ["WORLD DATA", "level.dat", "MyWorld", "shot.png", "cool.jar"] {
        assert!(!json.contains(forbidden), "export leaked {forbidden}");
    }
    // Nor is anything resembling a credential.
    let lower = json.to_ascii_lowercase();
    for forbidden in ["token", "secret", "password", "refresh", "session"] {
        assert!(!lower.contains(forbidden), "export leaked {forbidden}");
    }
}

/// Machine-specific facts must not travel: a Java path from one machine points
/// at nothing on another.
#[test]
fn an_export_drops_paths_that_only_make_sense_on_this_machine() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Local").unwrap();
    profile.java = JavaPolicy::Custom {
        path: PathBuf::from("/opt/jdk8/bin/java"),
    };
    profile.game_directory = GameDirectory::External {
        path: PathBuf::from("/home/me/.minecraft"),
    };
    f.store().save(&profile).unwrap();

    let exported = f.ops.export(&profile.id).unwrap();
    assert_eq!(exported.profile.java, JavaPreference::Automatic);
    assert_eq!(exported.profile.game_directory, GameDirectory::Managed);

    let json = f.ops.export_json(&profile.id).unwrap();
    assert!(!json.contains("/opt/jdk8"));
    assert!(!json.contains(".minecraft"));
}

#[test]
fn export_and_import_round_trips_the_settings_that_should_travel() {
    let f = Fixture::new();
    let mut original = f.ops.create("Travelling").unwrap();
    original.memory.heap_mb = 3072;
    original.display.resolution = launcher_core::profiles::Resolution {
        width: 1600,
        height: 900,
    };
    original.display.fullscreen = true;
    original.jvm.extra_args = vec!["-Dfathom.example=1".into()];
    original.optimizations.jvm.compile_huge_methods = true;
    f.store().save(&original).unwrap();

    let json = f.ops.export_json(&original.id).unwrap();

    // Import into a *different* launcher root, as if on another machine.
    let other = Fixture::new();
    let (imported, outcome) = other.ops.import(json.as_bytes()).unwrap();

    assert_eq!(imported.memory.heap_mb, 3072);
    assert_eq!(imported.display.resolution.width, 1600);
    assert!(imported.display.fullscreen);
    assert!(imported.optimizations.jvm.compile_huge_methods);
    assert!(other.store().exists(&imported.id));

    // JVM arguments deliberately do NOT travel, and this is the one place the
    // security pass made an import less convenient on purpose.
    //
    // `-Dfathom.example=1` is harmless, and it is dropped anyway, because the
    // alternative is deciding harmlessness with a denylist over an option set
    // that grows every JVM release. `-agentpath:` in the same list would load a
    // native library into the game before Minecraft starts. Losing a `-D` is a
    // smaller cost than getting that judgement wrong once.
    assert!(
        imported.jvm.extra_args.is_empty(),
        "arguments must not travel: {:?}",
        imported.jvm.extra_args
    );
    assert_eq!(
        outcome.adjustments.len(),
        1,
        "the dropped arguments are the only adjustment, and they are reported: {:?}",
        outcome.adjustments
    );
    assert!(
        outcome.adjustments[0].contains("-Dfathom.example=1"),
        "the user is shown exactly what to add back: {:?}",
        outcome.adjustments
    );
}

#[test]
fn importing_a_name_that_is_taken_produces_a_new_id_and_says_so() {
    let f = Fixture::new();
    let original = f.ops.create("Duplicate").unwrap();
    let json = f.ops.export_json(&original.id).unwrap();

    let (imported, outcome) = f.ops.import(json.as_bytes()).unwrap();
    assert_ne!(imported.id, original.id);
    assert!(outcome.renamed);
    assert!(f.store().exists(&original.id), "the original is untouched");
}

// ===========================================================================
// Malformed imports
// ===========================================================================

#[test]
fn malformed_import_documents_are_refused_without_creating_anything() {
    let f = Fixture::new();

    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("empty", b"".to_vec()),
        ("not json", b"this is not json".to_vec()),
        ("truncated", br#"{"schema":2,"profile":{"#.to_vec()),
        ("an array", b"[]".to_vec()),
        ("a bare string", br#""hello""#.to_vec()),
        ("no profile", br#"{"schema":2}"#.to_vec()),
        (
            "an invalid id inside",
            br#"{"schema":2,"exported_by":"x","exported_at":0,
                "profile":{"id":"../escape","display_name":"x","version_id":"1.8.9"}}"#
                .to_vec(),
        ),
        (
            "a reserved id inside",
            br#"{"schema":2,"exported_by":"x","exported_at":0,
                "profile":{"id":"con","display_name":"x","version_id":"1.8.9"}}"#
                .to_vec(),
        ),
        ("a huge document", vec![b'{'; 2 * 1024 * 1024]),
    ];

    for (label, bytes) in cases {
        assert!(
            f.ops.import(&bytes).is_err(),
            "{label} should have been refused"
        );
    }
    assert!(
        f.store().list().is_empty(),
        "a refused import must not leave a profile behind"
    );
}

#[test]
fn an_import_from_a_newer_schema_is_refused_rather_than_reinterpreted() {
    let f = Fixture::new();
    let json = format!(
        r#"{{"schema":{},"exported_by":"future","exported_at":0,
            "profile":{{"id":"future","display_name":"Future","version_id":"1.8.9"}}}}"#,
        SCHEMA_VERSION + 1
    );
    let err = f.ops.import(json.as_bytes()).expect_err("must refuse");
    assert!(matches!(err, ProfileError::SchemaTooNew { .. }), "{err:?}");
}

/// Values from another machine are not trusted to be in range, and every
/// adjustment is reported rather than made silently.
#[test]
fn an_import_with_out_of_range_values_is_corrected_and_the_user_is_told() {
    let f = Fixture::new();
    let json = r#"{
        "schema": 3,
        "exported_by": "hostile",
        "exported_at": 0,
        "profile": {
            "id": "hostile",
            "display_name": "Hostile",
            "version_id": "1.8.9",
            "memory": { "heap_mb": 4000000 },
            "display": { "resolution": { "width": 0, "height": 0 }, "fullscreen": false },
            "java": { "kind": "explicit", "path": "/not/on/this/machine/java" },
            "game_directory": { "kind": "external", "path": "/somewhere/else" },
            "mods": { "mods": [
                { "id": "escape", "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "file_name": "../../escape.jar", "enabled": true },
                { "id": "fine", "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "file_name": "fine.jar", "enabled": true }
            ]}
        }
    }"#;

    let (profile, outcome) = f.ops.import(json.as_bytes()).unwrap();

    assert_eq!(profile.memory.heap_mb, 2048);
    assert!(profile.display.resolution.is_sane());
    assert_eq!(profile.java, JavaPreference::Automatic);
    assert_eq!(profile.game_directory, GameDirectory::Managed);

    let names: Vec<&str> = profile
        .mods
        .mods
        .iter()
        .map(|m| m.file_name.as_str())
        .collect();
    assert_eq!(names, vec!["fine.jar"], "the traversing entry is dropped");

    assert!(
        outcome.adjustments.len() >= 4,
        "every correction must be reported: {:?}",
        outcome.adjustments
    );
}

/// The security pass found this: a profile is a document people share, and its
/// JVM argument list was carried across an import untouched.
///
/// A JVM flag list is a code-execution surface. `-agentpath:` loads a native
/// library into the game's address space at startup, before any Minecraft class
/// runs, and `-XX:OnError` hands a command to a shell the JVM starts itself —
/// which is why "the launcher never invokes a shell" was not the protection it
/// appeared to be.
///
/// Two independent defences, and this test pins both. `jvm_args::validate_arg`
/// now refuses the families; more importantly, an import drops the list
/// wholesale, so the safety of opening someone else's file does not depend on
/// our having predicted every dangerous flag the JVM has.
#[test]
fn an_imported_profile_cannot_smuggle_in_jvm_arguments() {
    let f = Fixture::new();
    let json = r#"{
        "schema": 3,
        "exported_by": "hostile",
        "exported_at": 0,
        "profile": {
            "id": "payload",
            "display_name": "Looks Fine",
            "version_id": "1.8.9",
            "jvm": { "extra_args": [
                "-agentpath:C:/Users/Public/evil.dll",
                "-XX:OnOutOfMemoryError=calc.exe",
                "-XX:+AlwaysPreTouch"
            ]}
        }
    }"#;

    let (profile, outcome) = f.ops.import(json.as_bytes()).unwrap();

    assert!(
        profile.jvm.extra_args.is_empty(),
        "no argument survives an import, not even a harmless one: {:?}",
        profile.jvm.extra_args
    );

    // Dropped, not silently: the user is shown what was in the file so they can
    // put back anything they actually wanted.
    let told = outcome
        .adjustments
        .iter()
        .any(|a| a.contains("JVM arguments") && a.contains("agentpath"));
    assert!(
        told,
        "the dropped arguments must be reported in full: {:?}",
        outcome.adjustments
    );

    // And the second layer, independently: even typed in by hand on this
    // machine, the dangerous ones never reach a command line.
    let mut local = profile.clone();
    local.jvm.extra_args = vec![
        "-agentpath:C:/Users/Public/evil.dll".to_owned(),
        "-XX:OnOutOfMemoryError=calc.exe".to_owned(),
        "-XX:+AlwaysPreTouch".to_owned(),
    ];
    let args = launcher_core::minecraft_runtime::jvm_args::build_jvm_args(&local, 8, "/n");
    assert!(
        !args.iter().any(|a| a.contains("agentpath")),
        "a native agent must never reach the JVM: {args:?}"
    );
    assert!(
        !args.iter().any(|a| a.contains("OnOutOfMemoryError")),
        "a command hook must never reach the JVM: {args:?}"
    );
    assert!(
        args.contains(&"-XX:+AlwaysPreTouch".to_owned()),
        "and the harmless one still works: {args:?}"
    );
}

// ===========================================================================
// Validation and repair
// ===========================================================================

#[test]
fn a_fresh_profile_is_healthy() {
    let f = Fixture::new();
    let profile = f.ops.create("Healthy").unwrap();
    let report = validate::validate(&f.paths, &profile, None);
    assert_eq!(
        report.health,
        ProfileHealth::Healthy,
        "{:?}",
        report.defects
    );
    assert!(report.is_healthy());
}

#[test]
fn every_kind_of_defect_is_detected_separately() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Broken").unwrap();

    profile.display_name = "  ".into();
    profile.loader.kind = Loader::Forge; // not implemented, and needs a version
    profile.memory.heap_mb = 1;
    profile.display.resolution = launcher_core::profiles::Resolution {
        width: 1,
        height: 1,
    };
    profile.java = JavaPolicy::Custom {
        path: PathBuf::from("/definitely/not/here/java"),
    };
    profile.jvm.extra_args = vec!["-XX:+UseLargePages".into()];
    profile.mods.mods = vec![
        ProfileMod::new(
            ModId::new("missing").unwrap(),
            "a".repeat(40),
            "missing.jar",
        ),
        ProfileMod::new(
            ModId::new("escape").unwrap(),
            "a".repeat(40),
            "../escape.jar",
        ),
    ];
    std::fs::remove_dir_all(f.store().dirs(&profile.id).screenshots_dir()).unwrap();

    let report = validate::validate(&f.paths, &profile, None);
    let kinds: Vec<&str> = report.defects.iter().map(Defect::kind).collect();

    for expected in [
        "empty-display-name",
        "loader-not-implemented",
        "loader-version-missing",
        "heap-out-of-range",
        "unusable-resolution",
        "java-missing",
        "rejected-jvm-argument",
        "mod-file-missing",
        "unsafe-mod-file-name",
        "missing-directory",
    ] {
        assert!(
            kinds.contains(&expected),
            "{expected} not detected: {kinds:?}"
        );
    }

    assert_eq!(report.health, ProfileHealth::Unusable);
    assert!(!report.health.is_launchable());
    for defect in &report.defects {
        assert!(!defect.message().is_empty());
    }
}

/// Mods configured against a loader that cannot load them is its own defect,
/// and only fires for vanilla -- Forge and Legacy Fabric both read `mods/`.
#[test]
fn mods_configured_against_vanilla_are_reported_as_unloadable() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Vanilla With Mods").unwrap();
    profile.mods.mods = vec![ProfileMod::new(
        ModId::new("cool").unwrap(),
        "a".repeat(40),
        "cool.jar",
    )];
    std::fs::write(
        f.store().dirs(&profile.id).mods_dir().join("cool.jar"),
        b"JAR",
    )
    .unwrap();

    let kinds: Vec<&str> = validate::validate(&f.paths, &profile, None)
        .defects
        .iter()
        .map(Defect::kind)
        .collect();
    assert!(kinds.contains(&"mods-without-a-loader"), "{kinds:?}");

    // And it does not fire once a loader that reads them is chosen.
    profile.loader.kind = Loader::LegacyFabric;
    profile.loader.version = Some("0.15.3".into());
    let kinds: Vec<&str> = validate::validate(&f.paths, &profile, None)
        .defects
        .iter()
        .map(Defect::kind)
        .collect();
    assert!(!kinds.contains(&"mods-without-a-loader"), "{kinds:?}");
}

#[test]
fn repair_fixes_what_it_can_and_leaves_decisions_alone() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Repairable").unwrap();

    profile.memory.heap_mb = 1;
    profile.display.resolution = launcher_core::profiles::Resolution {
        width: 1,
        height: 1,
    };
    profile.java = JavaPolicy::Custom {
        path: PathBuf::from("/gone/java"),
    };
    profile.loader.version = Some("nonsense".into()); // vanilla takes none
    profile.mods.mods = vec![ProfileMod::new(
        ModId::new("missing").unwrap(),
        "a".repeat(40),
        "missing.jar",
    )];
    std::fs::remove_dir_all(f.store().dirs(&profile.id).saves_dir()).unwrap();

    let summary = validate::repair(&f.paths, &mut profile, None).unwrap();
    assert!(summary.changed_anything());

    assert_eq!(profile.memory.heap_mb, 2048);
    assert!(profile.display.resolution.is_sane());
    assert_eq!(profile.java, JavaPreference::Automatic);
    assert_eq!(profile.loader.version, None);
    assert!(profile.mods.is_empty());
    assert!(f.store().dirs(&profile.id).saves_dir().is_dir());

    assert_eq!(
        validate::validate(&f.paths, &profile, None).health,
        ProfileHealth::Healthy
    );
}

/// Repair may reset a setting. It may never delete the user's data or decide
/// which loader they wanted.
#[test]
fn repair_never_changes_the_loader_or_removes_a_file() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Modded").unwrap();
    profile.loader.kind = Loader::Forge;
    profile.mods.mods = vec![ProfileMod::new(
        ModId::new("real").unwrap(),
        "a".repeat(40),
        "real.jar",
    )];
    let jar = f.store().dirs(&profile.id).mods_dir().join("real.jar");
    std::fs::write(&jar, b"JAR").unwrap();
    f.store().save(&profile).unwrap();

    let summary = validate::repair(&f.paths, &mut profile, None).unwrap();

    assert_eq!(
        profile.loader.kind,
        Loader::Forge,
        "the choice is the user's"
    );
    assert!(jar.is_file(), "repair must not delete a mod jar");
    assert!(
        summary
            .left_alone
            .iter()
            .any(|d| d.kind() == "loader-not-implemented"),
        "the blocking defect is reported, not silently fixed"
    );
}

/// An unsafe mod *entry* is removed; a file the user placed is not.
#[test]
fn repairing_an_unsafe_mod_entry_removes_the_entry_not_a_file() {
    let f = Fixture::new();
    let mut profile = f.ops.create("Unsafe").unwrap();
    profile.loader.kind = Loader::LegacyFabric;
    profile.mods.mods = vec![ProfileMod::new(
        ModId::new("outside").unwrap(),
        "a".repeat(40),
        "../../outside.jar",
    )];

    let outside = f.tmp.path().join("outside.jar");
    std::fs::write(&outside, b"NOT OURS").unwrap();

    validate::repair(&f.paths, &mut profile, None).unwrap();

    assert!(profile.mods.is_empty());
    assert!(outside.is_file(), "nothing outside the profile is touched");
}

// ===========================================================================
// Awkward roots
// ===========================================================================

#[test]
fn the_whole_subsystem_works_under_a_path_with_spaces_and_unicode() {
    let f = Fixture::rooted_at(Some("Fathom Data \u{2014} \u{6e2c}\u{8a66} (v1)"));
    assert!(f.paths.root().display().to_string().contains(' '));

    let profile = f.populated("Awkward Root");
    let copy = f.ops.clone_profile(&profile.id, "Copy Of It").unwrap();
    assert_eq!(
        std::fs::read(
            f.store()
                .dirs(&copy.id)
                .saves_dir()
                .join("MyWorld/level.dat")
        )
        .unwrap(),
        b"WORLD DATA"
    );

    let json = f.ops.export_json(&profile.id).unwrap();
    assert!(!json.is_empty());

    let receipt = f.ops.delete(&copy.id).unwrap();
    assert!(receipt.moved_to.unwrap().is_dir());

    assert_eq!(
        validate::validate(&f.paths, &profile, None).health,
        ProfileHealth::Healthy
    );
}

// ===========================================================================
// Corruption, end to end
// ===========================================================================

#[test]
fn a_corrupt_profile_does_not_prevent_the_others_being_used() {
    let f = Fixture::new();
    let good = f.populated("Good");
    let bad = f.populated("Bad");
    std::fs::write(f.store().dirs(&bad.id).manifest_file(), b"<<<corrupt>>>").unwrap();

    let listed = f.store().list();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].id, good.id);

    // And the broken one is reported rather than quietly vanishing.
    let detailed = f.store().list_detailed();
    assert_eq!(detailed.len(), 2);
    assert!(detailed.iter().any(|(pid, r)| pid == &bad.id && r.is_err()));
}

/// The recovery path, end to end: a save creates a backup, corruption falls
/// back to it, and the world is untouched throughout.
#[test]
fn corruption_recovery_preserves_the_instance_data() {
    let f = Fixture::new();
    let mut profile = f.populated("Recoverable");
    profile.memory.heap_mb = 4096;
    f.store().save(&profile).unwrap();

    std::fs::write(f.store().dirs(&profile.id).manifest_file(), b"###").unwrap();

    let loaded = f.store().load_detailed(&profile.id).unwrap();
    assert!(loaded.outcome.message().is_some());
    assert_eq!(
        std::fs::read(
            f.store()
                .dirs(&profile.id)
                .saves_dir()
                .join("MyWorld/level.dat")
        )
        .unwrap(),
        b"WORLD DATA",
        "recovery must not disturb the world"
    );
}

#[test]
fn a_profile_directory_that_is_not_a_profile_is_ignored_not_deleted() {
    let f = Fixture::new();
    let stray = f.paths.profiles_dir().join("not-a-profile");
    std::fs::create_dir_all(&stray).unwrap();
    std::fs::write(stray.join("something.txt"), b"user data").unwrap();

    assert!(f.store().list().is_empty());
    assert!(stray.join("something.txt").is_file());
}

// ===========================================================================
// Migration, end to end
// ===========================================================================

const V1_ON_DISK: &str = r#"{
    "id": "old",
    "display_name": "Old Profile",
    "version_id": "1.8.9",
    "loader": "vanilla",
    "jvm": {
        "heap_mb": 3072,
        "extra_args": ["-Dlegacy=1"],
        "java_path": null,
        "compile_huge_methods": true
    },
    "keep_launcher_open": true
}"#;

#[test]
fn a_v1_profile_on_disk_is_migrated_and_keeps_its_instance_data() {
    let f = Fixture::new();
    let old = id("old");
    let dirs = f.store().dirs(&old);
    dirs.ensure().unwrap();
    std::fs::create_dir_all(dirs.saves_dir().join("OldWorld")).unwrap();
    std::fs::write(dirs.saves_dir().join("OldWorld/level.dat"), b"OLD WORLD").unwrap();
    std::fs::write(dirs.manifest_file(), V1_ON_DISK).unwrap();

    let loaded = f.store().load_detailed(&old).unwrap();
    assert!(loaded.outcome.needs_rewrite());
    assert_eq!(loaded.profile.memory.heap_mb, 3072);
    assert_eq!(loaded.profile.jvm.extra_args, vec!["-Dlegacy=1".to_owned()]);
    assert!(loaded.profile.optimizations.jvm.compile_huge_methods);

    f.store().consolidate();

    // Migrated on disk now, and the world is exactly where it was.
    assert_eq!(
        f.store().load_detailed(&old).unwrap().outcome,
        launcher_core::profiles::LoadOutcome::Clean
    );
    assert_eq!(
        std::fs::read(dirs.saves_dir().join("OldWorld/level.dat")).unwrap(),
        b"OLD WORLD"
    );
}

#[test]
fn a_migrated_profile_can_be_cloned_and_deleted_like_any_other() {
    let f = Fixture::new();
    let old = id("old");
    let dirs = f.store().dirs(&old);
    dirs.ensure().unwrap();
    std::fs::write(dirs.manifest_file(), V1_ON_DISK).unwrap();
    f.store().consolidate();

    let copy = f.ops.clone_profile(&old, "Copy Of Old").unwrap();
    assert_eq!(copy.memory.heap_mb, 3072);

    let receipt = f.ops.delete(&copy.id).unwrap();
    assert!(receipt.moved_to.unwrap().is_dir());
}

// ===========================================================================
// Path escape, once more, through every entry point
// ===========================================================================

#[test]
fn no_operation_accepts_a_path_from_outside_launcher_storage() {
    let f = Fixture::new();
    let outside = f.tmp.path().join("outside");
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("keep.txt"), b"KEEP").unwrap();

    // Three different refusals, each naming the right reason.
    assert!(matches!(
        f.ops.purge(&outside),
        Err(ProfileError::NotInRecoveryArea(_))
    ));
    assert!(matches!(
        f.ops.restore(&outside, &id("anything")),
        Err(ProfileError::NotInRecoveryArea(_))
    ));
    assert!(matches!(
        f.ops.removable_root(&outside),
        Err(ProfileError::RefusingToDeleteOutsideStorage(_))
    ));

    // And shared storage, which *is* inside launcher storage, is refused by the
    // recovery-area gate rather than being mislabelled as outside it.
    let shared = f.paths.shared_cache();
    assert!(matches!(
        f.ops.purge(&shared),
        Err(ProfileError::NotInRecoveryArea(_))
    ));
    assert!(shared.is_dir(), "shared storage must be untouched");

    assert!(outside.join("keep.txt").is_file());
}

#[test]
fn a_symlinked_profile_directory_pointing_outward_is_not_followed_into_deletion() {
    let f = Fixture::new();
    let outside = f.tmp.path().join("outside");
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("keep.txt"), b"KEEP").unwrap();

    let link = f.paths.profiles_dir().join("linked");
    let made = symlink_dir(&outside, &link);
    if !made {
        // Windows without developer mode refuses to create symlinks. Nothing to
        // assert, and pretending otherwise would be a test that never runs.
        return;
    }

    // `removable_root` canonicalises, so the link resolves to the outside
    // directory and is refused there.
    assert!(
        f.ops.removable_root(&link).is_err(),
        "a symlink out of the profiles root must not be removable"
    );
    assert!(outside.join("keep.txt").is_file());
}

#[cfg(unix)]
fn symlink_dir(target: &Path, link: &Path) -> bool {
    std::os::unix::fs::symlink(target, link).is_ok()
}

#[cfg(windows)]
fn symlink_dir(target: &Path, link: &Path) -> bool {
    std::os::windows::fs::symlink_dir(target, link).is_ok()
}

// ===========================================================================
// The v2 -> v3 mod configuration migration
// ===========================================================================

/// v2 stored mods as bare file names. v3 needs an id and a store hash, because
/// resolution has to know *which mod* a jar is.
const V2_WITH_MODS: &str = r#"{
    "schema": 2,
    "id": "modded",
    "display_name": "Modded",
    "version_id": "1.8.9",
    "loader": { "kind": "legacy-fabric", "version": "0.19.3" },
    "mods": { "entries": [
        { "file_name": "CoolMod-1.2.jar", "enabled": true,
          "sha1": "cccccccccccccccccccccccccccccccccccccccc" },
        { "file_name": "OffMod.jar", "enabled": false,
          "sha1": "dddddddddddddddddddddddddddddddddddddddd" },
        { "file_name": "NoHash.jar", "enabled": true }
    ]}
}"#;

#[test]
fn a_v2_mod_list_is_migrated_into_the_real_configuration() {
    let f = Fixture::new();
    let profile_id = id("modded");
    let dirs = f.store().dirs(&profile_id);
    dirs.ensure().unwrap();
    std::fs::write(dirs.manifest_file(), V2_WITH_MODS).unwrap();

    let loaded = f.store().load_detailed(&profile_id).unwrap();
    assert_eq!(
        loaded.outcome,
        launcher_core::profiles::LoadOutcome::Migrated { from: 2 }
    );

    let mods = &loaded.profile.mods;
    // Entries with a hash carry across, keeping their enabled state and gaining
    // an id derived from the file name.
    assert_eq!(mods.len(), 2, "{mods:?}");
    let cool = mods
        .get(&ModId::new("coolmod-1-2").unwrap())
        .expect("carried");
    assert!(cool.enabled);
    assert_eq!(cool.file_name, "CoolMod-1.2.jar");

    let off = mods.get(&ModId::new("offmod").unwrap()).expect("carried");
    assert!(!off.enabled, "the disabled state must survive");
}

/// An entry with no hash cannot reference the content-addressed store, so it is
/// dropped — and the jar itself is untouched, so the next materialization adopts
/// it with a real hash. Losing a flag is recoverable; inventing a hash is not.
#[test]
fn a_v2_entry_with_no_hash_is_dropped_but_its_file_is_not() {
    let f = Fixture::new();
    let profile_id = id("modded");
    let dirs = f.store().dirs(&profile_id);
    dirs.ensure().unwrap();
    std::fs::write(dirs.manifest_file(), V2_WITH_MODS).unwrap();

    // The jar the hashless entry named.
    let jar = dirs.mods_dir().join("NoHash.jar");
    std::fs::write(&jar, b"PK\x05\x06").unwrap();

    let loaded = f.store().load_detailed(&profile_id).unwrap();
    assert!(
        loaded
            .profile
            .mods
            .get(&ModId::new("nohash").unwrap())
            .is_none(),
        "an entry with no hash cannot be carried across"
    );
    assert!(jar.is_file(), "but the user's jar must not be touched");
}

/// A profile exported by an older build has to be importable, or "export" is a
/// promise the launcher cannot keep across an upgrade.
#[test]
fn a_profile_exported_at_an_older_schema_still_imports() {
    let f = Fixture::new();
    let older = r#"{
        "schema": 2,
        "exported_by": "Fathom 0.0.1",
        "exported_at": 0,
        "profile": {
            "id": "old-export",
            "display_name": "Old Export",
            "version_id": "1.8.9",
            "loader": { "kind": "vanilla" },
            "memory": { "heap_mb": 3072 },
            "mods": { "entries": [] }
        }
    }"#;

    let (profile, outcome) = f.ops.import(older.as_bytes()).expect("should import");
    assert_eq!(profile.memory.heap_mb, 3072);
    assert!(profile.mods.is_empty());
    assert!(
        outcome.adjustments.iter().any(|a| a.contains("upgraded")),
        "the upgrade must be reported: {:?}",
        outcome.adjustments
    );
}
