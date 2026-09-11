//! Getting back to a working state.
//!
//! # Why these are core services and not buttons
//!
//! Every flow here is reachable from the desktop application, from
//! `fathom-cli`, and from a test. Putting the logic behind a button would mean
//! the only way to exercise "repair a half-extracted Java runtime" is to break
//! one by hand and click — which is why launchers ship recovery paths that have
//! never been run.
//!
//! So the interface calls the same functions the tests do, and the tests break
//! things on purpose.
//!
//! # Every flow takes a lease first
//!
//! Recovery is exactly the code that must not race: it deletes and replaces
//! things other operations are reading. `ops::Operations` is therefore acquired
//! *before* any of it touches the filesystem, and the lease is held for the
//! whole operation rather than around each step.
//!
//! Recovery code respecting the locks is not optional and not decorative — a
//! repair that runs while the game is starting will delete a jar the JVM has
//! just been told about.
//!
//! # Idempotence
//!
//! Every repair here can be run twice. That is a property of how they are
//! written, not a claim: each one asks what is wrong *now* and fixes only that,
//! so a second run finds nothing wrong and does nothing. None of them appends,
//! renames in a cycle, or assumes a starting state.

pub mod cache;
pub mod diagnostics;
pub mod java;
pub mod startup;

use std::path::{Path, PathBuf};

use crate::ops::{Activity, Operations, Scope};
use crate::paths::DataPaths;
use crate::problem::{Category, Problem, Repair, Retry};
use crate::profiles::{Profile, ProfileId};

/// The recovery facade.
///
/// Holds the paths and the coordinator. Cheap to construct; the coordinator is
/// shared, which is what makes the locking real rather than per-instance.
#[derive(Debug, Clone)]
pub struct RecoveryService {
    paths: DataPaths,
    operations: Operations,
}

impl RecoveryService {
    pub fn new(paths: DataPaths, operations: Operations) -> Self {
        Self { paths, operations }
    }

    pub fn paths(&self) -> &DataPaths {
        &self.paths
    }

    pub fn operations(&self) -> &Operations {
        &self.operations
    }

    // -- startup ----------------------------------------------------------

    /// The check that runs before the window opens.
    ///
    /// Takes `GlobalUpdate` and `SharedInstallation`, because it removes
    /// `.partial` files and may finish an interrupted executable replacement.
    /// If either is busy — which at startup would mean a second launcher
    /// instance — the scan is skipped rather than fighting it.
    pub fn run_startup_scan(
        &self,
        running_version: &str,
    ) -> Result<startup::StartupReport, Problem> {
        let _lease = self.operations.begin(
            Activity::RecoveringAtStartup,
            &[Scope::GlobalUpdate, Scope::SharedInstallation],
        )?;
        Ok(startup::scan(&self.paths, running_version))
    }

    // -- cache ------------------------------------------------------------

    /// What "clear cache" would remove, without removing it.
    pub fn preview_cache(&self) -> Result<cache::CacheReport, Problem> {
        let _lease = self.operations.begin(
            Activity::ClearingCache,
            &[Scope::SharedInstallation, Scope::GlobalUpdate],
        )?;
        Ok(cache::preview(&self.paths))
    }

    /// Discard disposable data.
    ///
    /// Takes `SharedInstallation` because `.partial` files live beside the
    /// artifacts they are becoming, and `GlobalUpdate` because update staging
    /// is one of the things cleared. A download in progress therefore blocks
    /// this, which is the conflict the brief names.
    ///
    /// See [`cache`] for exactly what counts as cache. Worlds, screenshots,
    /// resource packs, profile configuration, credentials and user-supplied
    /// mods are not in that set, and a test asserts it.
    pub fn clear_cache(&self) -> Result<cache::CacheReport, Problem> {
        let _lease = self.operations.begin(
            Activity::ClearingCache,
            &[Scope::SharedInstallation, Scope::GlobalUpdate],
        )?;
        let report = cache::clear(&self.paths, false);
        tracing::info!(
            files = report.count(),
            bytes = report.bytes(),
            "cache cleared"
        );
        Ok(report)
    }

    // -- java -------------------------------------------------------------

    /// Inspect the runtime a profile would use.
    ///
    /// `chosen` distinguishes "the launcher picked this" from "the user picked
    /// this", which changes what a failure means rather than what is checked.
    pub fn validate_java(
        &self,
        runtime_dir: &Path,
        required_major: u32,
        chosen: bool,
    ) -> Result<java::RuntimeVerdict, Problem> {
        let _lease = self
            .operations
            .begin(Activity::RepairingJava, &[Scope::JavaRuntime])?;
        Ok(java::validate(
            &self.paths,
            runtime_dir,
            required_major,
            chosen,
        ))
    }

    /// Repair a managed runtime.
    ///
    /// **Never touches an external one.** See [`java`] for why that is checked
    /// twice, the second time immediately next to the deletion.
    pub fn repair_java(
        &self,
        runtime_dir: &Path,
        required_major: u32,
        chosen: bool,
    ) -> Result<java::RepairOutcome, Problem> {
        let _lease = self
            .operations
            .begin(Activity::RepairingJava, &[Scope::JavaRuntime])?;

        let verdict = java::validate(&self.paths, runtime_dir, required_major, chosen);
        java::repair(&self.paths, &verdict).map_err(|error| {
            Problem::new(
                Category::JavaRuntime,
                "The damaged Java runtime could not be removed.",
            )
            .detail(format!("path={}", runtime_dir.display()))
            .caused_by(&error)
            .retry(Retry::Worthwhile)
        })
    }

    // -- profiles ---------------------------------------------------------

    /// Check one profile.
    ///
    /// Cheap: no network, no hashing. `installation` is the install engine's
    /// answer when the caller already has it, so validation does not duplicate
    /// the expensive question.
    pub fn validate_profile(
        &self,
        profile: &Profile,
        installation: Option<&crate::InstallSnapshot>,
    ) -> Result<crate::profiles::validate::ProfileReport, Problem> {
        let _lease = self.operations.begin(
            Activity::ValidatingProfile,
            &[Scope::Profile(profile.id.clone())],
        )?;
        Ok(crate::profiles::validate::validate(
            &self.paths,
            profile,
            installation,
        ))
    }

    /// Fix what can be fixed without a decision.
    ///
    /// Idempotent: it re-validates first and acts only on what is still wrong.
    pub fn repair_profile(
        &self,
        profile: &mut Profile,
        installation: Option<&crate::InstallSnapshot>,
    ) -> Result<crate::profiles::validate::RepairSummary, Problem> {
        let _lease = self.operations.begin(
            Activity::RepairingProfile,
            &[Scope::Profile(profile.id.clone())],
        )?;
        crate::profiles::validate::repair(&self.paths, profile, installation).map_err(|error| {
            Problem::new(Category::Profile, "The profile could not be repaired.")
                .detail(format!("profile={}", profile.id))
                .caused_by(&error)
                .retry(Retry::Worthwhile)
        })
    }

    // -- trash ------------------------------------------------------------

    /// What is in the recovery area.
    pub fn trashed_profiles(&self) -> Vec<PathBuf> {
        self.ops_for_profiles().trashed()
    }

    /// Put a deleted profile back.
    ///
    /// **Refuses to overwrite an existing profile.** A restore that silently
    /// replaced a live profile with a deleted one would destroy whatever the
    /// user has done since — so a collision is reported as a decision for them
    /// to make, with the id they would have to free up named.
    pub fn restore_profile(&self, trashed: &Path, id: &ProfileId) -> Result<(), Problem> {
        let _lease = self
            .operations
            .begin(Activity::RestoringProfile, &[Scope::Profile(id.clone())])?;

        let store = crate::profiles::ProfileStore::new(self.paths.clone());
        if store.exists(id) {
            return Err(Problem::new(
                Category::Profile,
                format!(
                    "A profile called {id} already exists, so the deleted one was not \
                     restored. Rename or delete the existing profile first."
                ),
            )
            .detail(format!("trashed={}", trashed.display()))
            .retry(Retry::Pointless)
            .repair(Repair::UserDecision {
                what: format!("Choose a different id for the restored profile, or remove {id}"),
            }));
        }

        self.ops_for_profiles()
            .restore(trashed, id)
            .map_err(|error| {
                Problem::new(Category::Profile, "The profile could not be restored.")
                    .detail(format!("trashed={} id={id}", trashed.display()))
                    .caused_by(&error)
                    .retry(Retry::Worthwhile)
            })
    }

    /// Destroy one recovery-area entry.
    ///
    /// The path comes from a directory listing rather than from us, so it goes
    /// through `ProfileOps::purge`, which canonicalises and confirms the target
    /// is inside the trash directory before any recursive removal. A path from
    /// corrupted profile metadata cannot reach a `remove_dir_all`.
    pub fn purge_trashed_profile(&self, trashed: &Path) -> Result<(), Problem> {
        let _lease = self
            .operations
            .begin(Activity::DeletingProfile, &[Scope::GlobalUpdate])?;

        self.ops_for_profiles()
            .purge(trashed)
            .map(|_| ())
            .map_err(|error| {
                Problem::new(
                    Category::Profile,
                    "That recovery-area entry could not be permanently removed.",
                )
                .detail(format!("path={}", trashed.display()))
                .caused_by(&error)
                .retry(Retry::Pointless)
            })
    }

    /// Check a restored profile the way a freshly loaded one is checked.
    pub fn validate_restored(&self, id: &ProfileId) -> Result<Vec<Problem>, Problem> {
        let store = crate::profiles::ProfileStore::new(self.paths.clone());
        let profile = store.load(id).map_err(|error| {
            Problem::new(
                Category::Profile,
                "The restored profile could not be read back.",
            )
            .caused_by(&error)
            .retry(Retry::Pointless)
            .repair(Repair::RepairProfile {
                profile: id.to_string(),
            })
        })?;
        let report = self.validate_profile(&profile, None)?;
        Ok(report
            .defects
            .iter()
            .map(|defect| {
                Problem::new(Category::Profile, defect.message())
                    .detail(format!("profile={id}"))
                    .retry(Retry::Pointless)
                    .repair(Repair::RepairProfile {
                        profile: id.to_string(),
                    })
            })
            .collect())
    }

    fn ops_for_profiles(&self) -> crate::profiles::ops::ProfileOps {
        crate::profiles::ops::ProfileOps::new(self.paths.clone())
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

    struct Fixture {
        _tmp: tempfile::TempDir,
        service: RecoveryService,
        operations: Operations,
        paths: DataPaths,
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
                service,
                operations,
                paths,
            }
        }

        fn profile_ops(&self) -> crate::profiles::ops::ProfileOps {
            crate::profiles::ops::ProfileOps::new(self.paths.clone())
        }
    }

    /// The rule from the module comment: recovery respects the locks.
    #[test]
    fn a_repair_is_refused_while_something_else_holds_its_scope() {
        let f = Fixture::new();
        let _held = f
            .operations
            .begin(Activity::InstallingGame, &[Scope::SharedInstallation])
            .expect("an install starts");

        let problem = f
            .service
            .clear_cache()
            .expect_err("clearing the cache under a running install must be refused");

        assert_eq!(problem.category(), Category::Busy);
        assert!(
            problem.retry_advice().is_worth_it(),
            "the install will finish, so retrying is genuinely worthwhile"
        );
        assert!(
            problem.summary().contains("installing Minecraft"),
            "{problem}"
        );
    }

    #[test]
    fn a_java_repair_is_refused_while_another_java_repair_runs() {
        let f = Fixture::new();
        let _held = f
            .operations
            .begin(Activity::RepairingJava, &[Scope::JavaRuntime])
            .expect("the first repair starts");

        let problem = f
            .service
            .repair_java(&f.paths.java_dir().join("jre-8"), 8, false)
            .expect_err("two java repairs must not run at once");
        assert_eq!(problem.category(), Category::Busy);
    }

    /// A lease is released when the operation finishes, so the next one works.
    #[test]
    fn a_scope_is_free_again_once_the_operation_returns() {
        let f = Fixture::new();
        f.service.clear_cache().expect("first clear");
        f.service.clear_cache().expect("second clear");
        assert!(!f.operations.is_busy(&Scope::SharedInstallation));
    }

    #[test]
    fn the_startup_scan_runs_under_a_lease_and_releases_it() {
        let f = Fixture::new();
        let report = f.service.run_startup_scan("0.1.0").expect("a clean scan");
        assert!(report.is_clean());
        assert!(!f.operations.is_busy(&Scope::GlobalUpdate));
    }

    /// The conflict-handling requirement, which is the one that protects data:
    /// restoring must never overwrite a live profile.
    #[test]
    fn restoring_over_an_existing_profile_is_refused_rather_than_overwriting_it() {
        let f = Fixture::new();
        let ops = f.profile_ops();

        // Create, delete, then re-create something with the same id.
        let original = ops.create("Main").expect("create");
        let id = original.id.clone();
        let receipt = ops.delete(&id).expect("delete");
        let trashed = receipt.moved_to.expect("moved to the recovery area");

        let recreated = ops.create("Main").expect("create again");
        assert_eq!(recreated.id, id, "the same name yields the same id");
        std::fs::write(
            ops.store().dirs(&id).minecraft_dir().join("marker.txt"),
            b"work done since",
        )
        .ok();

        let problem = f
            .service
            .restore_profile(&trashed, &id)
            .expect_err("restoring over a live profile must be refused");

        assert_eq!(problem.category(), Category::Profile);
        assert!(
            problem.summary().contains("already exists"),
            "the message must say why: {problem}"
        );
        assert!(
            matches!(
                problem.available_repair(),
                Some(Repair::UserDecision { .. })
            ),
            "this is the user's decision, not something to fix automatically"
        );
        assert!(
            trashed.exists(),
            "and the deleted profile must still be recoverable"
        );
    }

    #[test]
    fn restoring_into_a_free_id_works_and_the_result_validates() {
        let f = Fixture::new();
        let ops = f.profile_ops();

        let original = ops.create("Solo").expect("create");
        let id = original.id.clone();
        let receipt = ops.delete(&id).expect("delete");
        let trashed = receipt.moved_to.expect("moved");

        f.service
            .restore_profile(&trashed, &id)
            .expect("nothing is in the way");

        let problems = f.service.validate_restored(&id).expect("validate");
        assert!(
            problems.is_empty(),
            "a restored profile should be healthy: {problems:?}"
        );
    }

    /// A path from outside the trash must never reach a recursive delete.
    #[test]
    fn purging_a_path_outside_the_recovery_area_is_refused() {
        let f = Fixture::new();
        let elsewhere = f.paths.profiles_dir().join("not-trash");
        std::fs::create_dir_all(elsewhere.join("saves")).unwrap();
        std::fs::write(elsewhere.join("saves/level.dat"), b"a world").unwrap();

        let problem = f
            .service
            .purge_trashed_profile(&elsewhere)
            .expect_err("a path outside the recovery area must be refused");
        assert_eq!(problem.category(), Category::Profile);
        assert!(
            elsewhere.join("saves/level.dat").is_file(),
            "and nothing may be deleted"
        );
    }

    #[test]
    fn a_profile_validates_and_repairs_through_the_service() {
        let f = Fixture::new();
        let mut profile = f.profile_ops().create("Checked").expect("create");

        let report = f
            .service
            .validate_profile(&profile, None)
            .expect("validate");
        assert!(report.is_healthy(), "{:?}", report.defects);

        // Break something a repair can fix.
        std::fs::remove_dir_all(f.profile_ops().store().dirs(&profile.id).mods_dir()).ok();

        let summary = f
            .service
            .repair_profile(&mut profile, None)
            .expect("repair");
        let _ = summary;

        // Idempotent: run it again, nothing left to do, no error.
        f.service
            .repair_profile(&mut profile, None)
            .expect("a second repair must not fail");
    }

    #[test]
    fn cache_preview_and_clear_agree() {
        let f = Fixture::new();
        let partial = f.paths.shared_cache().join("libraries/a.jar.partial");
        std::fs::create_dir_all(partial.parent().unwrap()).unwrap();
        std::fs::write(&partial, b"half").unwrap();

        let preview = f.service.preview_cache().expect("preview");
        assert_eq!(preview.count(), 1);
        assert!(partial.is_file(), "a preview must not delete");

        let cleared = f.service.clear_cache().expect("clear");
        assert_eq!(cleared.count(), preview.count());
        assert!(!partial.exists());
    }
}
