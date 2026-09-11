//! What the launcher checks before it shows a window.
//!
//! # The constraint that shapes this
//!
//! It runs on **every** start, including the ordinary one where nothing is
//! wrong, and the launcher is now something a user double-clicks. So it must be
//! fast: `stat` calls and directory listings, never hashing.
//!
//! Verifying the installation properly means hashing 139 MB across 800 files.
//! That is a repair, it takes seconds, and doing it on every start would make
//! the application feel broken in order to check whether it is broken.
//! [`scan`] therefore answers "is there evidence something went wrong" from
//! cheap signals, and full verification happens only when that evidence exists
//! or when the user asks.
//!
//! # Automatic versus reported
//!
//! Every finding declares which it is, and the distinction is not a judgement
//! call made at the call site:
//!
//! - [`Disposition::CleanedUp`] — done already. Reserved for things that are
//!   *definitionally* worthless: a `.partial` is an incomplete download and can
//!   never be anything else; a file in `tmp/` belongs to a run that has ended.
//!   Removing them cannot lose anything, because there was nothing there to
//!   lose.
//! - [`Disposition::NeedsAttention`] — found, not touched. Anything that might
//!   be, or might contain, something the user cares about.
//!
//! When in doubt it is the second. A launcher that quietly deletes on startup
//! is a launcher nobody can trust with their worlds.

use std::path::PathBuf;

use crate::install::download;
use crate::paths::DataPaths;
use crate::problem::{Category, Problem, Repair, Retry};
use crate::update::install::Recovery as UpdateRecovery;

/// Whether the scan dealt with something or is reporting it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Disposition {
    /// Removed. Only for things that could not have been wanted.
    CleanedUp,
    /// Left exactly as found, and reported.
    NeedsAttention,
}

/// One thing the scan noticed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Finding {
    /// Incomplete downloads were removed.
    ///
    /// A `.partial` is never valid input to anything — `download::attempt`
    /// deletes any it finds before starting, and nothing loads one — so
    /// removing them is free.
    StalePartialsRemoved { count: usize },
    /// The temporary directory was emptied.
    TemporaryFilesRemoved { count: usize, bytes: u64 },
    /// An interrupted launcher update was finished or undone.
    UpdateRecovered { detail: String },
    /// Update staging held bytes for a version nothing is going to install.
    AbandonedUpdateStaging { count: usize },
    /// A profile's manifest would not parse and a backup was used instead.
    ///
    /// Reported rather than cleaned: the user should know their profile was
    /// restored from a backup, because the backup may be a little behind.
    ProfileRecoveredFromBackup { profile: String },
    /// A profile's manifest would not parse and no backup helped.
    ProfileUnreadable { profile: String, detail: String },
    /// A profile directory exists with no manifest in it. Possibly an
    /// interrupted create or delete.
    ProfileDirectoryOrphaned { path: PathBuf },
    /// A natives directory was left extracted with no stamp.
    ///
    /// Not removed: `NativeLibraryManager` re-extracts on any disagreement, so
    /// this is informational rather than a problem to fix here.
    UnstampedNatives { path: PathBuf },
}

impl Finding {
    pub fn disposition(&self) -> Disposition {
        match self {
            Finding::StalePartialsRemoved { .. }
            | Finding::TemporaryFilesRemoved { .. }
            | Finding::AbandonedUpdateStaging { .. }
            | Finding::UpdateRecovered { .. } => Disposition::CleanedUp,

            Finding::ProfileRecoveredFromBackup { .. }
            | Finding::ProfileUnreadable { .. }
            | Finding::ProfileDirectoryOrphaned { .. }
            | Finding::UnstampedNatives { .. } => Disposition::NeedsAttention,
        }
    }

    /// A sentence for the interface.
    pub fn message(&self) -> String {
        match self {
            Finding::StalePartialsRemoved { count } => format!(
                "Discarded {count} incomplete download(s) left by a previous session. \
                 Nothing verified was affected."
            ),
            Finding::TemporaryFilesRemoved { count, bytes } => format!(
                "Removed {count} temporary file(s) ({}).",
                crate::services::human_bytes(*bytes)
            ),
            Finding::UpdateRecovered { detail } => detail.clone(),
            Finding::AbandonedUpdateStaging { count } => {
                format!("Discarded {count} unverified update file(s) from an interrupted download.")
            }
            Finding::ProfileRecoveredFromBackup { profile } => format!(
                "Profile {profile} could not be read and was restored from its backup. \
                 A change made just before the last shutdown may be missing."
            ),
            Finding::ProfileUnreadable { profile, detail } => format!(
                "Profile {profile} could not be read, and its backup did not help: {detail}"
            ),
            Finding::ProfileDirectoryOrphaned { path } => format!(
                "{} looks like a profile but has no configuration in it. It has been left \
                 alone.",
                path.display()
            ),
            Finding::UnstampedNatives { path } => format!(
                "Native libraries in {} were extracted by an interrupted run. They will be \
                 re-extracted on the next launch.",
                path.display()
            ),
        }
    }

    /// The failure to show, for findings that need one.
    pub fn problem(&self) -> Option<Problem> {
        match self {
            Finding::ProfileUnreadable { profile, detail } => Some(
                Problem::new(Category::Profile, self.message())
                    .detail(detail.clone())
                    .retry(Retry::Pointless)
                    .repair(Repair::RepairProfile {
                        profile: profile.clone(),
                    }),
            ),
            Finding::ProfileRecoveredFromBackup { profile } => Some(
                Problem::new(Category::Profile, self.message())
                    .retry(Retry::Pointless)
                    .repair(Repair::RepairProfile {
                        profile: profile.clone(),
                    }),
            ),
            _ => None,
        }
    }
}

/// Everything the scan found.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StartupReport {
    pub findings: Vec<Finding>,
    /// How long the scan took. Watched, because this runs on every start.
    pub elapsed_ms: u128,
}

impl StartupReport {
    pub fn is_clean(&self) -> bool {
        self.findings.is_empty()
    }

    /// Findings the user has to decide about.
    pub fn needs_attention(&self) -> Vec<&Finding> {
        self.findings
            .iter()
            .filter(|f| f.disposition() == Disposition::NeedsAttention)
            .collect()
    }

    /// Findings already dealt with. Worth mentioning once, not worth a dialog.
    pub fn cleaned_up(&self) -> Vec<&Finding> {
        self.findings
            .iter()
            .filter(|f| f.disposition() == Disposition::CleanedUp)
            .collect()
    }

    pub fn problems(&self) -> Vec<Problem> {
        self.findings.iter().filter_map(Finding::problem).collect()
    }
}

/// Run the startup check.
///
/// `running_version` is what this build reports, and it settles the one
/// genuinely ambiguous update case — see `update::install::Installer::recover`.
///
/// **The caller must hold a lease.** `RecoveryService::run_startup_scan` takes
/// `GlobalUpdate` and `SharedInstallation` first, because this removes
/// `.partial` files that a concurrent download would be writing.
pub fn scan(paths: &DataPaths, running_version: &str) -> StartupReport {
    let started = std::time::Instant::now();
    let mut findings = Vec::new();

    // 1. Interrupted updates, first. It can move the executable itself, so
    //    nothing else should be looking at files until it has settled.
    let update = crate::update::UpdateService::new(paths.root());
    match update.recover(running_version) {
        UpdateRecovery::NothingToDo => {}
        other => {
            if let Some(detail) = other.message() {
                findings.push(Finding::UpdateRecovered { detail });
            }
        }
    }

    // 2. Unverified update staging. Nothing loads from staging without
    //    re-hashing, so discarding it costs at most one re-download.
    let swept = update.installer().sweep_staging();
    if swept > 0 {
        findings.push(Finding::AbandonedUpdateStaging { count: swept });
    }

    // 3. Incomplete downloads. Definitionally worthless.
    let partials = download::sweep_partials(&paths.shared_cache());
    if partials > 0 {
        findings.push(Finding::StalePartialsRemoved { count: partials });
    }

    // 4. Temporary files from a run that has ended.
    let (count, bytes) = empty_directory(&paths.temp_dir());
    if count > 0 {
        findings.push(Finding::TemporaryFilesRemoved { count, bytes });
    }

    // 5. Profiles. `list_detailed` already does the backup-recovery dance, so
    //    this reads its outcome rather than repeating the logic.
    findings.extend(scan_profiles(paths));

    StartupReport {
        findings,
        elapsed_ms: started.elapsed().as_millis(),
    }
}

/// Read every profile's load outcome without touching any of them.
///
/// Two passes, because `ProfileStore::list_detailed` deliberately skips a
/// directory with no manifest — for it, that is not a profile and not an error,
/// since the trash and scratch directories sit alongside. Here it *is* worth
/// noticing: a profile directory with no configuration in it is usually an
/// interrupted create or an interrupted delete, and it may contain a world.
fn scan_profiles(paths: &DataPaths) -> Vec<Finding> {
    use crate::profiles::{LoadOutcome, ProfileId, ProfileStore};

    let store = ProfileStore::new(paths.clone());
    let mut findings = Vec::new();

    // Pass 1: profiles the store recognises.
    for (id, outcome) in store.list_detailed() {
        match outcome {
            Ok(loaded) => {
                if matches!(loaded.outcome, LoadOutcome::RecoveredFromBackup { .. }) {
                    findings.push(Finding::ProfileRecoveredFromBackup {
                        profile: id.to_string(),
                    });
                }
            }
            Err(error) => findings.push(Finding::ProfileUnreadable {
                profile: id.to_string(),
                detail: error.to_string(),
            }),
        }
    }

    // Pass 2: directories that look like profiles but have no manifest.
    let Ok(entries) = std::fs::read_dir(paths.profiles_dir()) else {
        return findings;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = std::fs::symlink_metadata(&path) else {
            continue;
        };
        if !meta.is_dir() || meta.file_type().is_symlink() {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(id) = ProfileId::new(&name) else {
            // Not a valid id, so not something we created. Left alone and not
            // reported: the user may keep their own directories here.
            continue;
        };
        if !store.exists(&id) {
            findings.push(Finding::ProfileDirectoryOrphaned { path });
        }
    }

    findings
}

/// Remove everything directly inside `dir`, returning how much went.
///
/// Not recursive into links, for the same reason as everywhere else: we never
/// create one, so one found here is not ours.
fn empty_directory(dir: &std::path::Path) -> (usize, u64) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return (0, 0);
    };
    let mut count = 0;
    let mut bytes = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = std::fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            if std::fs::remove_dir_all(&path).is_ok() {
                count += 1;
            }
            continue;
        }
        let size = meta.len();
        if std::fs::remove_file(&path).is_ok() {
            count += 1;
            bytes += size;
        }
    }
    (count, bytes)
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
        paths: DataPaths,
    }

    impl Fixture {
        fn new() -> Self {
            let tmp = tempfile::tempdir().expect("tempdir");
            let paths = DataPaths::rooted_at(tmp.path());
            paths.ensure().expect("ensure");
            Self { _tmp: tmp, paths }
        }

        fn write(&self, relative: &str, contents: &[u8]) -> PathBuf {
            let path = self.paths.root().join(relative);
            std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
            std::fs::write(&path, contents).expect("write");
            path
        }
    }

    #[test]
    fn a_clean_installation_produces_no_findings() {
        let f = Fixture::new();
        let report = scan(&f.paths, "0.1.0");
        assert!(report.is_clean(), "{:?}", report.findings);
    }

    /// It runs on every start, so it has to be cheap even when there is a lot
    /// of installation there. The point of the assertion is that nothing
    /// hashes: 800 files of a megabyte each would take seconds if it did.
    #[test]
    fn the_scan_does_not_hash_the_installation() {
        let f = Fixture::new();
        let payload = vec![0u8; 512 * 1024];
        for i in 0..60 {
            f.write(&format!("shared/libraries/org/lib{i}.jar"), &payload);
        }

        let report = scan(&f.paths, "0.1.0");
        assert!(report.is_clean());
        assert!(
            report.elapsed_ms < 2_000,
            "the startup scan took {} ms over 30 MB; it must be using stat, not hashing",
            report.elapsed_ms
        );
    }

    #[test]
    fn incomplete_downloads_are_removed_without_asking() {
        let f = Fixture::new();
        let partial = f.write("shared/libraries/a/lib.jar.partial", b"half");
        let real = f.write("shared/libraries/a/lib.jar", b"verified");

        let report = scan(&f.paths, "0.1.0");

        assert!(
            !partial.exists(),
            "a partial is never valid input to anything"
        );
        assert!(
            real.is_file(),
            "and the verified artifact beside it survives"
        );
        let finding = report
            .findings
            .iter()
            .find(|f| matches!(f, Finding::StalePartialsRemoved { .. }))
            .expect("the partial should have been reported");
        assert_eq!(finding.disposition(), Disposition::CleanedUp);
    }

    #[test]
    fn temporary_files_are_removed() {
        let f = Fixture::new();
        f.write("tmp/scratch-1", b"1234");
        f.write("tmp/nested/deep", b"5678");

        let report = scan(&f.paths, "0.1.0");
        assert!(report
            .findings
            .iter()
            .any(|f| matches!(f, Finding::TemporaryFilesRemoved { .. })));
        assert_eq!(
            std::fs::read_dir(f.paths.temp_dir()).unwrap().count(),
            0,
            "tmp should be empty"
        );
    }

    /// The rule the module exists for: nothing that might matter is touched.
    #[test]
    fn nothing_the_user_owns_is_touched_by_a_startup_scan() {
        let f = Fixture::new();
        let precious = [
            "profiles/main/minecraft/saves/World/level.dat",
            "profiles/main/minecraft/options.txt",
            "profiles/main/minecraft/mods/user-supplied.jar",
            "settings.json",
            "shared/versions/1.8.9/1.8.9.jar",
            "mods/ab/abcd.jar",
            "trash/old-profile/profile.json",
        ];
        for path in precious {
            f.write(path, b"mine");
        }

        scan(&f.paths, "0.1.0");

        for path in precious {
            assert!(
                f.paths.root().join(path).is_file(),
                "the startup scan removed {path}"
            );
        }
    }

    /// A profile directory with no manifest is reported, never deleted: it may
    /// be an interrupted create, or an interrupted delete, and both contain
    /// files the user might want.
    #[test]
    fn an_orphaned_profile_directory_is_reported_and_left_alone() {
        let f = Fixture::new();
        let world = f.write("profiles/ghost/minecraft/saves/World/level.dat", b"a world");

        let report = scan(&f.paths, "0.1.0");
        let finding = report
            .findings
            .iter()
            .find(|f| matches!(f, Finding::ProfileDirectoryOrphaned { .. }))
            .expect("an orphaned directory should be noticed");
        assert_eq!(finding.disposition(), Disposition::NeedsAttention);
        assert!(world.is_file(), "and its contents must survive");
    }

    #[test]
    fn a_profile_whose_manifest_will_not_parse_is_reported_with_a_repair() {
        let f = Fixture::new();
        f.write("profiles/broken/profile.json", b"{ not json");

        let report = scan(&f.paths, "0.1.0");
        let problems = report.problems();
        let problem = problems.first().expect("a problem to show");
        assert_eq!(problem.category(), Category::Profile);
        assert!(
            matches!(
                problem.available_repair(),
                Some(Repair::RepairProfile { .. })
            ),
            "the user needs a button, not a sentence"
        );
    }

    #[test]
    fn findings_are_split_into_handled_and_needing_attention() {
        let f = Fixture::new();
        f.write("shared/a.jar.partial", b"x");
        f.write("profiles/ghost/minecraft/keep", b"y");

        let report = scan(&f.paths, "0.1.0");
        assert_eq!(report.cleaned_up().len(), 1);
        assert_eq!(report.needs_attention().len(), 1);
        assert!(!report.is_clean());
    }

    #[test]
    fn a_scan_is_idempotent() {
        let f = Fixture::new();
        f.write("shared/a.jar.partial", b"x");
        f.write("tmp/scratch", b"y");

        let first = scan(&f.paths, "0.1.0");
        assert_eq!(first.findings.len(), 2);

        let second = scan(&f.paths, "0.1.0");
        assert!(
            second.is_clean(),
            "the second scan should find nothing: {:?}",
            second.findings
        );
    }

    /// The sweep must not follow a link out of the temporary directory.
    #[cfg(unix)]
    #[test]
    fn emptying_the_temporary_directory_does_not_follow_a_link() {
        let f = Fixture::new();
        let outside = f.paths.root().join("not-temporary");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("precious"), b"mine").unwrap();
        std::os::unix::fs::symlink(&outside, f.paths.temp_dir().join("link")).unwrap();

        scan(&f.paths, "0.1.0");
        assert!(outside.join("precious").is_file());
    }
}
