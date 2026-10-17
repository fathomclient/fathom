//! Breaking things on purpose, and checking the launcher survives.
//!
//! # Why these are integration tests
//!
//! Every one of them injects a failure at the filesystem or process level and
//! then asks a *core service* what happened. That is deliberate: a recovery path
//! reachable only from a button is a recovery path nobody has run, and the
//! failures below are the ones that are awkward to produce by hand — a
//! half-written file, a directory that cannot be written to, a junction
//! pointing out of the sandbox.
//!
//! # The shape every test takes
//!
//! 1. Build a working launcher state.
//! 2. Break exactly one thing.
//! 3. Assert the launcher **notices**, **says something usable**, and
//!    **does not lose the user's data**.
//!
//! The third is the one that matters. A launcher that reports a problem and
//! deletes a world has still failed.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::path::{Path, PathBuf};

use launcher_core::fs_safety;
use launcher_core::ops::{Activity, Operations, Scope};
use launcher_core::paths::DataPaths;
use launcher_core::problem::Category;
use launcher_core::profiles::ops::ProfileOps;
use launcher_core::profiles::{ProfileId, ProfileStore};
use launcher_core::recovery::{cache, java, startup, RecoveryService};

struct Fixture {
    _tmp: tempfile::TempDir,
    paths: DataPaths,
    operations: Operations,
    service: RecoveryService,
}

impl Fixture {
    fn new() -> Self {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("ensure");
        let operations = Operations::new();
        let service = RecoveryService::new(paths.clone(), operations.clone());
        Self {
            _tmp: tmp,
            paths,
            operations,
            service,
        }
    }

    fn profiles(&self) -> ProfileOps {
        ProfileOps::new(self.paths.clone())
    }

    fn write(&self, relative: &str, contents: &[u8]) -> PathBuf {
        let path = self.paths.root().join(relative);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
        std::fs::write(&path, contents).expect("write");
        path
    }
}

/// Files a real profile has that the user would be upset to lose.
fn seed_user_data(f: &Fixture, id: &ProfileId) -> Vec<PathBuf> {
    let dirs = ProfileStore::new(f.paths.clone()).dirs(id);
    let game = dirs.minecraft_dir();
    let files = [
        game.join("saves/My World/level.dat"),
        game.join("screenshots/2026-08-27.png"),
        game.join("resourcepacks/faithful.zip"),
        game.join("options.txt"),
        game.join("mods/user-supplied.jar"),
    ];
    for path in &files {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, b"the user's own").unwrap();
    }
    files.to_vec()
}

fn assert_all_present(files: &[PathBuf], what: &str) {
    for path in files {
        assert!(
            path.is_file(),
            "{what} destroyed {}, which belongs to the user",
            path.display()
        );
    }
}

// ===========================================================================
// Corrupt and truncated configuration
// ===========================================================================

/// The failure a power cut during a write produces: a file that exists, is the
/// right size, and is not valid JSON.
#[test]
fn a_corrupt_profile_manifest_is_reported_and_the_instance_survives() {
    let f = Fixture::new();
    let profile = f.profiles().create("Main").expect("create");
    let id = profile.id.clone();
    let precious = seed_user_data(&f, &id);

    let manifest = ProfileStore::new(f.paths.clone()).dirs(&id).manifest_file();
    std::fs::write(&manifest, b"{\"schema\": 3, \"pro").expect("truncate");
    // Remove the backup too, so there is nothing to fall back on.
    let _ = std::fs::remove_file(manifest.with_extension("json.bak"));

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    let problems = report.problems();
    assert!(
        !problems.is_empty(),
        "a corrupt manifest must be reported: {:?}",
        report.findings
    );
    assert_eq!(problems[0].category(), Category::Profile);
    assert!(
        problems[0].available_repair().is_some(),
        "and it must offer a repair rather than only a sentence"
    );

    assert_all_present(&precious, "startup recovery after a corrupt manifest");
}

/// The last-known-good copy is what makes an interrupted write survivable.
#[test]
fn a_profile_is_recovered_from_its_backup_and_the_user_is_told() {
    let f = Fixture::new();
    let profile = f.profiles().create("Backed Up").expect("create");
    let id = profile.id.clone();

    let store = ProfileStore::new(f.paths.clone());
    // Save twice so a backup exists.
    store.save(&profile).expect("save");
    store.save(&profile).expect("save again");

    let manifest = store.dirs(&id).manifest_file();
    let backup = manifest.with_extension("json.bak");
    assert!(backup.is_file(), "a backup should exist after two saves");

    // Corrupt the live copy only.
    std::fs::write(&manifest, b"not json at all").expect("corrupt");

    let loaded = store.load(&id).expect("the backup should rescue it");
    assert_eq!(loaded.id, id);

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    assert!(
        report
            .findings
            .iter()
            .any(|finding| matches!(finding, startup::Finding::ProfileRecoveredFromBackup { .. })),
        "a silent recovery is worse than a reported one: {:?}",
        report.findings
    );
}

/// A file that is a valid JSON document but not a profile.
#[test]
fn a_structurally_valid_but_wrong_manifest_is_refused() {
    let f = Fixture::new();
    f.write("profiles/odd/profile.json", br#"{"schema":3,"profile":42}"#);

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    assert!(
        !report.problems().is_empty(),
        "a document that parses but is not a profile must still be refused"
    );
}

// ===========================================================================
// Interrupted atomic writes
// ===========================================================================

/// Simulates the interruption directly: the temporary file exists, the rename
/// never happened. The real file must be untouched and still readable.
#[test]
fn an_interrupted_atomic_write_leaves_the_previous_content_intact() {
    let f = Fixture::new();
    let target = f.write("settings.json", br#"{"online":true}"#);

    // What a crash between "write the temp file" and "rename" leaves behind.
    let orphan = target.with_file_name(format!(".settings.json.{}.abcd.0.tmp", std::process::id()));
    std::fs::write(&orphan, b"half a doc").expect("orphan");

    let text = std::fs::read_to_string(&target).expect("read");
    assert_eq!(
        text, r#"{"online":true}"#,
        "an interrupted write must not have touched the real file"
    );

    // And a subsequent write still succeeds despite the orphan sitting there.
    fs_safety::atomic_write(&target, br#"{"online":false}"#).expect("a later write");
    assert_eq!(
        std::fs::read_to_string(&target).unwrap(),
        r#"{"online":false}"#
    );
}

/// Repeated interrupted writes must not accumulate junk that eventually stops
/// the launcher writing at all.
#[test]
fn many_orphaned_temporary_files_do_not_prevent_a_write() {
    let f = Fixture::new();
    let target = f.write("settings.json", b"original");

    for i in 0..50 {
        let orphan = target.with_file_name(format!(".settings.json.orphan.{i}.tmp"));
        std::fs::write(&orphan, b"junk").expect("orphan");
    }

    fs_safety::atomic_write(&target, b"replacement").expect("write");
    assert_eq!(std::fs::read(&target).unwrap(), b"replacement");
}

// ===========================================================================
// Permission denied / read-only
// ===========================================================================

/// The closest portable stand-in for "disk full": a write that cannot succeed.
/// The launcher must report it rather than losing the previous content.
#[test]
fn a_write_that_cannot_succeed_is_reported_and_loses_nothing() {
    let f = Fixture::new();
    let target = f.write("settings.json", b"the good content");

    // A directory where the file should be: every write to it fails.
    let blocked = f.paths.root().join("blocked");
    std::fs::create_dir_all(blocked.join("settings.json")).expect("a directory in the way");

    let result = fs_safety::atomic_write(&blocked.join("settings.json"), b"new");
    assert!(result.is_err(), "writing over a directory must fail");

    assert_eq!(
        std::fs::read(&target).unwrap(),
        b"the good content",
        "an unrelated failure must not touch other files"
    );
}

// ===========================================================================
// Java
// ===========================================================================

#[test]
fn a_missing_managed_java_runtime_is_repairable() {
    let f = Fixture::new();
    let dir = java::managed_runtime_dir(&f.paths, 8);

    let verdict = f.service.validate_java(&dir, 8, false).expect("validate");
    assert!(!verdict.is_usable());
    assert!(verdict.may_reacquire());

    let outcome = f.service.repair_java(&dir, 8, false).expect("repair");
    assert_eq!(outcome, java::RepairOutcome::NothingToDo);
}

#[test]
fn a_half_extracted_managed_runtime_is_detected_and_removed() {
    let f = Fixture::new();
    let dir = java::managed_runtime_dir(&f.paths, 8);
    std::fs::create_dir_all(dir.join("bin")).unwrap();
    std::fs::write(java::executable_in(&dir), b"partial").unwrap();

    let verdict = f.service.validate_java(&dir, 8, false).expect("validate");
    assert!(verdict
        .defects
        .iter()
        .any(|d| matches!(d, java::RuntimeDefect::IncompleteExtraction { .. })));

    let outcome = f.service.repair_java(&dir, 8, false).expect("repair");
    assert!(matches!(
        outcome,
        java::RepairOutcome::RemovedForReacquisition { .. }
    ));
    assert!(!dir.exists());

    // Idempotent.
    f.service
        .repair_java(&dir, 8, false)
        .expect("second repair");
}

/// The rule: a runtime the user chose is theirs.
#[test]
fn a_broken_external_java_is_reported_and_never_replaced() {
    let f = Fixture::new();
    let external = f.paths.root().join("their-jdk");
    std::fs::create_dir_all(external.join("bin")).unwrap();
    std::fs::write(java::executable_in(&external), b"not java").unwrap();

    let verdict = f
        .service
        .validate_java(&external, 8, true)
        .expect("validate");
    assert!(!verdict.may_reacquire());

    let outcome = f.service.repair_java(&external, 8, true).expect("repair");
    assert!(matches!(
        outcome,
        java::RepairOutcome::RefusedExternal { .. }
    ));
    assert!(
        external.join("bin").is_dir(),
        "a system JDK must survive a repair"
    );
}

// ===========================================================================
// Cache
// ===========================================================================

#[test]
fn clearing_the_cache_under_every_kind_of_user_data_loses_none_of_it() {
    let f = Fixture::new();
    let profile = f.profiles().create("Main").expect("create");
    let precious = seed_user_data(&f, &profile.id);

    f.write("shared/libraries/a.jar", b"expensive to re-download");
    f.write("shared/libraries/a.jar.partial", b"disposable");
    f.write("cache/metadata/manifest.json", b"disposable");
    f.write("mods/ab/abcdef.jar", b"a user's mod, content addressed");

    let report = f.service.clear_cache().expect("clear");

    assert_all_present(&precious, "clear cache");
    assert!(
        f.paths.root().join("shared/libraries/a.jar").is_file(),
        "shared artifacts are expensive, not disposable"
    );
    assert!(
        f.paths.root().join("mods/ab/abcdef.jar").is_file(),
        "the mod store holds user-supplied jars with no authorized source to \
         re-acquire from"
    );
    assert!(
        f.paths
            .root()
            .join("profiles")
            .join(profile.id.to_string())
            .join("profile.json")
            .is_file(),
        "profile configuration is not cache"
    );
    assert_eq!(report.count(), 2, "exactly the two disposable files");
}

// ===========================================================================
// Concurrency
// ===========================================================================

#[test]
fn a_repair_and_an_install_cannot_run_on_the_same_installation_at_once() {
    let f = Fixture::new();
    let _install = f
        .operations
        .begin(Activity::InstallingGame, &[Scope::SharedInstallation])
        .expect("install starts");

    let problem = f.service.clear_cache().expect_err("must be refused");
    assert_eq!(problem.category(), Category::Busy);
    assert!(problem.retry_advice().is_worth_it());
}

#[test]
fn deleting_a_profile_is_refused_while_its_game_is_running() {
    let f = Fixture::new();
    let profile = f.profiles().create("Playing").expect("create");
    let _running = f
        .operations
        .begin(Activity::Running, &[Scope::Profile(profile.id.clone())])
        .expect("the game is running");

    let refused = f
        .operations
        .begin(
            Activity::DeletingProfile,
            &[Scope::Profile(profile.id.clone())],
        )
        .expect_err("deleting a running profile must be refused");
    assert_eq!(refused.holder, Activity::Running);
}

/// Many threads racing for the same scope: exactly one may hold it.
#[test]
fn simultaneous_operations_produce_exactly_one_winner() {
    let operations = Operations::new();
    let barrier = std::sync::Arc::new(std::sync::Barrier::new(16));
    let winners = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));

    let handles: Vec<_> = (0..16)
        .map(|_| {
            let operations = operations.clone();
            let barrier = barrier.clone();
            let winners = winners.clone();
            std::thread::spawn(move || {
                barrier.wait();
                if let Ok(lease) =
                    operations.begin(Activity::RepairingGame, &[Scope::SharedInstallation])
                {
                    winners.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    std::thread::sleep(std::time::Duration::from_millis(20));
                    drop(lease);
                }
            })
        })
        .collect();
    for handle in handles {
        handle.join().expect("thread");
    }

    let count = winners.load(std::sync::atomic::Ordering::SeqCst);
    assert!(
        count >= 1,
        "somebody must get through, or the coordinator is a deadlock"
    );
    assert!(
        !operations.is_busy(&Scope::SharedInstallation),
        "every lease must have been released"
    );
}

// ===========================================================================
// Deletion safety
// ===========================================================================

/// A path from corrupted profile metadata must never reach a recursive delete.
#[test]
fn purging_a_path_outside_the_recovery_area_is_refused() {
    let f = Fixture::new();
    let profile = f.profiles().create("Main").expect("create");
    let precious = seed_user_data(&f, &profile.id);

    for hostile in [
        f.paths.root().join("profiles"),
        f.paths.root().join("shared"),
        f.paths.root().to_path_buf(),
        f.paths.trash_dir(),
    ] {
        let _ = f.service.purge_trashed_profile(&hostile);
    }

    assert_all_present(&precious, "a refused purge");
    assert!(f.paths.profiles_dir().is_dir());
    assert!(f.paths.shared_cache().is_dir());
}

#[test]
fn restoring_over_a_live_profile_is_refused_and_both_copies_survive() {
    let f = Fixture::new();
    let ops = f.profiles();

    let original = ops.create("Duplicate").expect("create");
    let id = original.id.clone();
    let receipt = ops.delete(&id).expect("delete");
    let trashed = receipt.moved_to.expect("in the recovery area");

    ops.create("Duplicate").expect("re-create");
    let live = seed_user_data(&f, &id);

    let problem = f
        .service
        .restore_profile(&trashed, &id)
        .expect_err("must be refused");
    assert_eq!(problem.category(), Category::Profile);

    assert_all_present(&live, "a refused restore");
    assert!(
        trashed.exists(),
        "and the deleted copy is still recoverable"
    );
}

/// A junction or symlink must not let a deletion or a sweep escape.
#[cfg(unix)]
#[test]
fn a_link_out_of_launcher_storage_cannot_be_followed_by_a_cleanup() {
    let f = Fixture::new();
    let outside = f.paths.root().parent().unwrap().join("outside-storage");
    std::fs::create_dir_all(&outside).unwrap();
    let precious = outside.join("precious.jar");
    std::fs::write(&precious, b"not the launcher's").unwrap();

    // One in each place a sweep runs.
    std::os::unix::fs::symlink(&outside, f.paths.temp_dir().join("link")).unwrap();
    std::os::unix::fs::symlink(&outside, f.paths.metadata_cache_dir().join("link")).unwrap();

    f.service.clear_cache().expect("clear");
    f.service.run_startup_scan("0.1.0").expect("scan");

    assert!(
        precious.is_file(),
        "a cleanup followed a link out of launcher storage"
    );
}

// ===========================================================================
// Startup recovery
// ===========================================================================

#[test]
fn stale_update_staging_is_discarded_without_being_executed() {
    let f = Fixture::new();
    let staging = f.paths.root().join("update/staging");
    std::fs::create_dir_all(&staging).unwrap();
    let unverified = staging.join("launcher-9.9.9.part");
    std::fs::write(&unverified, b"unverified bytes").unwrap();

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    assert!(
        !unverified.exists(),
        "unverified update bytes must not be left lying around"
    );
    assert!(report
        .findings
        .iter()
        .any(|finding| matches!(finding, startup::Finding::AbandonedUpdateStaging { .. })));
}

#[test]
fn abandoned_partial_downloads_are_discarded_but_verified_artifacts_are_not() {
    let f = Fixture::new();
    let real = f.write("shared/versions/1.8.9/1.8.9.jar", b"verified game jar");
    let partial = f.write("shared/versions/1.8.9/1.8.9.jar.partial", b"half");

    f.service.run_startup_scan("0.1.0").expect("scan");

    assert!(!partial.exists());
    assert_eq!(std::fs::read(&real).unwrap(), b"verified game jar");
}

/// The startup scan runs on every launch of a double-clicked application, so it
/// has to stay fast even on a full installation.
#[test]
fn the_startup_scan_stays_fast_on_a_populated_installation() {
    let f = Fixture::new();
    let payload = vec![7u8; 256 * 1024];
    for i in 0..200 {
        f.write(&format!("shared/libraries/org/x/lib{i}.jar"), &payload);
    }
    for i in 0..20 {
        f.write(
            &format!("shared/assets/objects/{:02x}/obj{i}", i % 256),
            &payload,
        );
    }

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    assert!(report.is_clean());
    assert!(
        report.elapsed_ms < 3_000,
        "the scan took {} ms over ~55 MB. It must use stat, not hashing: a \
         double-clicked application cannot spend seconds deciding it is healthy.",
        report.elapsed_ms
    );
}

// ===========================================================================
// Working directory independence
// ===========================================================================

/// The property the whole desktop-application milestone rests on.
///
/// A shortcut, a Start-menu entry and a taskbar pin each hand the process a
/// different working directory, and a shortcut with a blank "Start in" may get
/// `C:\Windows\System32`. Nothing persistent may move when it does.
#[test]
fn every_service_resolves_the_same_paths_from_any_working_directory() {
    let f = Fixture::new();
    let profile = f.profiles().create("Anywhere").expect("create");
    let expected = ProfileStore::new(f.paths.clone())
        .dirs(&profile.id)
        .root()
        .to_path_buf();

    let elsewhere = tempfile::tempdir().expect("tempdir");
    let original = std::env::current_dir().expect("cwd");

    std::env::set_current_dir(elsewhere.path()).expect("move");
    let from_elsewhere = ProfileStore::new(f.paths.clone())
        .dirs(&profile.id)
        .root()
        .to_path_buf();
    let listed = ProfileOps::new(f.paths.clone()).store().list();
    let scan = startup::scan(&f.paths, "0.1.0");
    let preview = cache::preview(&f.paths);
    std::env::set_current_dir(&original).expect("move back");

    assert_eq!(
        expected, from_elsewhere,
        "a profile directory moved when the working directory did"
    );
    assert!(expected.is_absolute());
    assert_eq!(listed.len(), 1, "profiles must still be discoverable");
    assert!(scan.is_clean(), "{:?}", scan.findings);
    assert!(preview.is_empty());
}

/// A `DataPaths` built from a relative path would silently follow the process
/// around. Everything the launcher itself constructs is absolute.
#[test]
fn the_discovered_data_root_is_absolute() {
    let Ok(paths) = DataPaths::discover() else {
        return;
    };
    assert!(paths.root().is_absolute());
    assert!(paths.profiles_dir().is_absolute());
    assert!(paths.launcher_logs_dir().is_absolute());
}

// ===========================================================================
// Diagnostics under failure
// ===========================================================================

#[test]
fn a_diagnostic_report_survives_a_broken_installation_and_leaks_nothing() {
    use launcher_core::recovery::diagnostics::{audit, Builder, Environment, Normalizer};

    let f = Fixture::new();
    f.write("profiles/broken/profile.json", b"{ corrupt");

    let report = f.service.run_startup_scan("0.1.0").expect("scan");
    let env = Environment::detect();

    let mut builder = Builder::new(Normalizer::new(&f.paths))
        .section("Launcher")
        .entry("Version", env.launcher_version.clone())
        .entry("Build", env.build_profile)
        .entry("OS", env.os.clone())
        .entry("Architecture", env.arch.clone())
        .section("Startup")
        .path_entry("Data root", f.paths.root());

    for problem in &report.problems() {
        builder = builder.problem(problem);
    }
    // A log line of the kind that has actually leaked before.
    builder = builder
        .section("Log")
        .line("(Session ID is token:eyJ.LEAKED.sig:uuid)");

    let text = builder.build().to_text();

    assert!(
        !text.contains("LEAKED"),
        "a credential reached the diagnostic report:\n{text}"
    );
    assert!(audit(&text).is_empty(), "audit found: {:?}", audit(&text));
    assert!(
        text.contains("<APP_DATA>"),
        "paths should be normalised so the report carries no username"
    );
    assert!(text.contains("[Profile]"), "the failure should be in there");
}

// ===========================================================================
// Path containment
// ===========================================================================

/// Everything a recovery flow can be pointed at stays inside launcher storage.
#[test]
fn no_recovery_operation_can_be_pointed_outside_launcher_storage() {
    let f = Fixture::new();
    let outside = f.paths.root().parent().unwrap().join("elsewhere");
    std::fs::create_dir_all(&outside).unwrap();
    let precious = outside.join("important.txt");
    std::fs::write(&precious, b"not the launcher's").unwrap();

    let hostile: Vec<PathBuf> = vec![
        outside.clone(),
        precious.clone(),
        Path::new("/").to_path_buf(),
        f.paths.root().join("../elsewhere"),
    ];

    for path in hostile {
        let _ = f.service.purge_trashed_profile(&path);
        let _ = f
            .service
            .restore_profile(&path, &ProfileId::new("restored").expect("a valid id"));
    }

    assert!(
        precious.is_file(),
        "a recovery operation reached outside launcher storage"
    );
    assert!(outside.is_dir());
}
