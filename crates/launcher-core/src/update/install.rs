//! Replacing a file that something is relying on, without a moment where the
//! only good copy is gone.
//!
//! # The rule
//!
//! **The known-good copy is never destroyed until the replacement has been
//! shown to work.** Every step below exists to keep that true across a crash, a
//! power cut, a failed download, and a replacement that turns out not to start.
//!
//! # The sequence
//!
//! ```text
//!   staged           the download, already hash-verified
//!     |  1. re-verify from disk
//!     |  2. write the journal
//!     v
//!   backup <- target     3. the known-good copy moves aside. It is NOT deleted.
//!     |
//!     v
//!   target <- staged     4. the replacement takes its place
//!     |
//!     |  5. validate: does it actually run?
//!     |
//!     +-- yes --> journal removed. Backup kept until the next update.
//!     +-- no  --> target deleted, backup moved back, journal removed.
//! ```
//!
//! Each of steps 3, 4 and 5 records its completion in a journal file before
//! moving on, so a process that dies anywhere in the sequence leaves enough
//! information for [`Installer::recover`] to finish or undo it.
//!
//! # Why the launcher is the hard case
//!
//! A running executable cannot be overwritten on Windows — but it *can* be
//! renamed, which is what step 3 relies on. The genuinely hard part is step 5:
//! the thing that decides whether the replacement works cannot be the
//! replacement.
//!
//! So the **old process supervises**. `apply` runs in the launcher that is
//! already running, does the swap, starts the new binary with a self-check
//! argument, and waits for it. If the new binary fails to start, the old
//! process — which is still alive, and still knows where the backup is — puts
//! it back. That is why there is no background service and no separate updater
//! executable: the supervisor is the process the user already started.
//!
//! # What this cannot recover from
//!
//! Power loss *between* the swap and a successful validation, where the new
//! binary also fails to start. Then nothing is running to notice, and the next
//! start is the broken binary, which cannot recover itself. The backup is still
//! on disk and [`Installer::restore`] will put it back, but that needs a person
//! or a second executable to invoke it. Recorded honestly rather than papered
//! over; see `docs/research/update-security.md` §9.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use super::manifest::ComponentId;
use super::trust::UpdatePaths;
use super::verify::{self, ArtifactError};
use crate::fs_safety;

#[derive(Debug, thiserror::Error)]
pub enum InstallError {
    #[error("the staged file for {component} is missing at {path}")]
    StagedMissing {
        component: ComponentId,
        path: String,
    },
    #[error(transparent)]
    Artifact(#[from] ArtifactError),
    #[error("{operation} failed for {path}: {source}")]
    Io {
        operation: &'static str,
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("the install target {0} is not inside launcher-controlled storage")]
    TargetOutsideStorage(String),
    #[error(
        "the replacement for {component} did not pass its self-check ({detail}). The \
         previous version has been put back and is running."
    )]
    ReplacementRejected {
        component: ComponentId,
        detail: String,
    },
    #[error("could not record the update journal: {0}")]
    Journal(String),
}

fn io<'a>(
    operation: &'static str,
    path: &'a Path,
) -> impl FnOnce(std::io::Error) -> InstallError + 'a {
    move |source| InstallError::Io {
        operation,
        path: path.display().to_string(),
        source,
    }
}

/// How far a replacement has got. Written to the journal before each step, so
/// the value on disk is always "the last thing that definitely completed".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
pub enum Stage {
    /// The download is verified and sitting in staging. Nothing else has moved.
    Staged,
    /// The previous copy has been moved to the backup directory. The target may
    /// not exist right now.
    BackedUp,
    /// The replacement is in place but has not been validated.
    Swapped,
}

/// The record that survives a crash.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Journal {
    pub component: String,
    pub stage: Stage,
    pub target: PathBuf,
    pub staged: PathBuf,
    pub backup: PathBuf,
    /// The version being installed.
    pub new_version: String,
    /// The version being replaced, so a rollback can report what it restored.
    pub previous_version: String,
    /// SHA-256 of the replacement, so recovery can tell a completed swap from
    /// an interrupted one by looking at the file rather than guessing.
    pub sha256: String,
    /// Whether the target existed before. A fresh install has no backup to
    /// restore, and rolling back to a file that never existed would leave a
    /// stale copy behind.
    pub had_previous: bool,
    pub started_at: u64,
}

impl Journal {
    pub fn component_id(&self) -> Option<ComponentId> {
        ComponentId::parse(&self.component)
    }
}

/// What a recovery did, so the caller can tell the user rather than silently
/// changing what is on disk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Recovery {
    /// No journal. The normal case.
    NothingToDo,
    /// A download was interrupted before anything was moved.
    DiscardedStaging { component: String },
    /// The swap had completed and the replacement is intact; it was kept.
    CompletedSwap { component: String, version: String },
    /// The replacement was missing or corrupt; the previous copy was restored.
    RolledBack {
        component: String,
        restored_version: String,
    },
    /// A journal we could not act on. Left alone, and reported.
    Unrecognised { detail: String },
}

impl Recovery {
    /// A sentence for the user. `None` when nothing happened worth mentioning.
    pub fn message(&self) -> Option<String> {
        match self {
            Recovery::NothingToDo => None,
            Recovery::DiscardedStaging { component } => Some(format!(
                "An interrupted {component} download was discarded. Nothing was installed."
            )),
            Recovery::CompletedSwap { component, version } => Some(format!(
                "The {component} update to {version} was interrupted after it completed, and \
                 has been kept. It verified against the signed metadata."
            )),
            Recovery::RolledBack {
                component,
                restored_version,
            } => Some(format!(
                "A {component} update was interrupted and could not be completed. Version \
                 {restored_version} has been put back."
            )),
            Recovery::Unrecognised { detail } => Some(format!(
                "An update record could not be understood and was left alone: {detail}"
            )),
        }
    }
}

/// Decides whether a replacement actually works.
///
/// A trait rather than a hardcoded "spawn it and see" so that the state machine
/// can be tested exhaustively — including the failure paths, which are the ones
/// that matter and the ones a real binary makes awkward to trigger on demand.
pub trait Validator {
    /// Return `Ok(())` if the file at `path` is a working replacement.
    ///
    /// Called **after** the swap and **before** the backup is released, so a
    /// failure here still has somewhere to go back to.
    fn validate(&self, component: ComponentId, path: &Path) -> Result<(), String>;
}

/// Accepts anything. For components where "does it run" is not a question we
/// can ask — a jar, a metadata bundle — the hash check already did the work.
#[derive(Debug, Default)]
pub struct HashOnlyValidator;

impl Validator for HashOnlyValidator {
    fn validate(&self, _component: ComponentId, _path: &Path) -> Result<(), String> {
        Ok(())
    }
}

/// Runs the replacement and requires it to exit successfully.
///
/// The supervisor step for the launcher. Spawned with an argument vector and no
/// shell, like every other process this program starts.
#[derive(Debug)]
pub struct SelfCheckValidator {
    /// The argument that makes the launcher verify itself and exit 0.
    pub argument: String,
    pub timeout: std::time::Duration,
}

impl Default for SelfCheckValidator {
    fn default() -> Self {
        Self {
            argument: "--self-check".to_owned(),
            // Long enough for a cold start from disk on a slow machine, short
            // enough that a hung replacement does not hold the user forever. A
            // timeout counts as a failure: a launcher that cannot answer in
            // thirty seconds is not one we should have installed.
            timeout: std::time::Duration::from_secs(30),
        }
    }
}

impl Validator for SelfCheckValidator {
    fn validate(&self, _component: ComponentId, path: &Path) -> Result<(), String> {
        use std::process::{Command, Stdio};

        let mut child = Command::new(path)
            .arg(&self.argument)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("it would not start: {e}"))?;

        let deadline = std::time::Instant::now() + self.timeout;
        loop {
            match child.try_wait() {
                Ok(Some(status)) if status.success() => return Ok(()),
                Ok(Some(status)) => {
                    return Err(format!(
                        "its self-check exited with {}",
                        status
                            .code()
                            .map_or_else(|| "a signal".to_owned(), |c| c.to_string())
                    ))
                }
                Ok(None) => {
                    if std::time::Instant::now() >= deadline {
                        let _ = child.kill();
                        let _ = child.wait();
                        return Err(format!(
                            "its self-check did not finish within {:?}",
                            self.timeout
                        ));
                    }
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                Err(e) => return Err(format!("we could not tell whether it ran: {e}")),
            }
        }
    }
}

/// One replacement, described in full.
///
/// A struct rather than a long argument list, because six of the seven values
/// are strings and paths and a caller that transposes two of them would produce
/// something that compiles and installs the wrong file. Named fields make that
/// mistake visible at the call site.
#[derive(Debug, Clone)]
pub struct Replacement<'a> {
    pub component: ComponentId,
    /// The file being replaced.
    pub target: &'a Path,
    /// The verified download waiting in staging.
    pub staged: &'a Path,
    /// The hash from the signed metadata. Re-checked against the staged file.
    pub expected_sha256: &'a str,
    pub new_version: &'a str,
    /// What is being replaced, so a rollback can say what it restored. Empty
    /// for a component that was not installed before.
    pub previous_version: &'a str,
}

/// Performs replacements and recovers interrupted ones.
#[derive(Debug)]
pub struct Installer {
    paths: UpdatePaths,
    /// Everything an install may write to must be under here.
    storage_root: PathBuf,
}

impl Installer {
    pub fn new(paths: UpdatePaths, storage_root: impl Into<PathBuf>) -> Self {
        Self {
            paths,
            storage_root: storage_root.into(),
        }
    }

    pub fn paths(&self) -> &UpdatePaths {
        &self.paths
    }

    /// Where a component's download is staged before it is verified.
    pub fn staged_path(&self, component: ComponentId, version: &str) -> PathBuf {
        // The version is publisher-controlled text that becomes a filename, so
        // it is reduced to something that cannot be a path.
        let safe: String = version
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '.' || c == '-' {
                    c
                } else {
                    '_'
                }
            })
            .take(64)
            .collect();
        self.paths
            .staging()
            .join(format!("{}-{safe}.part", component.as_str()))
    }

    fn backup_path(&self, component: ComponentId) -> PathBuf {
        self.paths
            .backup()
            .join(format!("{}.previous", component.as_str()))
    }

    /// Install a staged, verified artifact over `target`.
    ///
    /// `expected_sha256` is re-checked **against the file on disk**, not
    /// against what the download reported. Between the download finishing and
    /// the install starting, the staged file is an ordinary file in an ordinary
    /// directory; re-hashing it costs milliseconds and closes the window.
    pub fn apply(
        &self,
        replacement: &Replacement<'_>,
        validator: &dyn Validator,
    ) -> Result<(), InstallError> {
        let Replacement {
            component,
            target,
            staged,
            expected_sha256,
            new_version,
            previous_version,
        } = *replacement;

        // Nothing outside launcher storage is ever a target. The path comes
        // from our own layout, not from metadata, and this is the assertion of
        // that rather than a check on hostile input.
        if !fs_safety::is_lexically_inside(&self.storage_root, target) {
            return Err(InstallError::TargetOutsideStorage(
                target.display().to_string(),
            ));
        }

        self.paths
            .ensure()
            .map_err(io("preparing the update directory", self.paths.root()))?;

        // 1. Re-verify from disk.
        if !staged.is_file() {
            return Err(InstallError::StagedMissing {
                component,
                path: staged.display().to_string(),
            });
        }
        let bytes = std::fs::read(staged).map_err(io("reading the staged file", staged))?;
        let actual = verify::sha256_hex(&bytes);
        if !actual.eq_ignore_ascii_case(expected_sha256) {
            let _ = std::fs::remove_file(staged);
            return Err(ArtifactError::HashMismatch {
                component,
                expected: expected_sha256.to_owned(),
                actual,
            }
            .into());
        }
        drop(bytes);

        let backup = self.backup_path(component);
        let had_previous = target.is_file();

        // 2. The journal, before anything moves.
        let mut journal = Journal {
            component: component.as_str().to_owned(),
            stage: Stage::Staged,
            target: target.to_path_buf(),
            staged: staged.to_path_buf(),
            backup: backup.clone(),
            new_version: new_version.to_owned(),
            previous_version: previous_version.to_owned(),
            sha256: expected_sha256.to_ascii_lowercase(),
            had_previous,
            started_at: now_seconds(),
        };
        self.write_journal(&journal)?;

        // 3. The known-good copy moves aside. It is not deleted, and nothing
        //    deletes it until a later update replaces it.
        if had_previous {
            if let Some(parent) = backup.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(io("creating the backup directory", parent))?;
            }
            let _ = std::fs::remove_file(&backup);
            std::fs::rename(target, &backup)
                .map_err(io("moving the previous copy aside", target))?;
        }
        journal.stage = Stage::BackedUp;
        self.write_journal(&journal)?;

        // 4. The replacement takes its place.
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(io("creating the target directory", parent))?;
        }
        if let Err(e) = std::fs::rename(staged, target) {
            // The swap itself failed. Put the previous copy straight back
            // rather than leaving the target missing.
            self.roll_back(&journal);
            return Err(InstallError::Io {
                operation: "moving the replacement into place",
                path: target.display().to_string(),
                source: e,
            });
        }
        journal.stage = Stage::Swapped;
        self.write_journal(&journal)?;

        // 5. Does it work? This is the step the whole shape exists to make
        //    possible: the previous copy is still on disk, so the answer is
        //    allowed to be no.
        match validator.validate(component, target) {
            Ok(()) => {
                self.clear_journal();
                tracing::info!(
                    component = component.as_str(),
                    from = previous_version,
                    to = new_version,
                    "component updated"
                );
                Ok(())
            }
            Err(detail) => {
                tracing::warn!(
                    component = component.as_str(),
                    %detail,
                    "the replacement failed its self-check; restoring the previous version"
                );
                self.roll_back(&journal);
                Err(InstallError::ReplacementRejected { component, detail })
            }
        }
    }

    /// Put the previous copy back. Best-effort by design: every step is
    /// attempted even if an earlier one failed, because a partial restore is
    /// better than none and there is nothing left to fall back to.
    fn roll_back(&self, journal: &Journal) {
        let _ = std::fs::remove_file(&journal.target);
        if journal.had_previous {
            if let Err(e) = std::fs::rename(&journal.backup, &journal.target) {
                tracing::error!(
                    error = %e,
                    backup = %journal.backup.display(),
                    target = %journal.target.display(),
                    "could not restore the previous version; it is still in the backup directory"
                );
            }
        }
        self.clear_journal();
    }

    /// Finish or undo an interrupted replacement.
    ///
    /// Called on startup, before anything reads the files an update might have
    /// been in the middle of replacing.
    ///
    /// `running_version` is what the currently-executing launcher reports.
    /// It resolves the one genuinely ambiguous case: a completed swap that was
    /// never validated. If the program now running is the new version, the
    /// replacement evidently starts, so it is kept.
    pub fn recover(&self, running_version: &str) -> Recovery {
        let path = self.paths.journal();
        let Ok(bytes) = std::fs::read(&path) else {
            return Recovery::NothingToDo;
        };
        let journal: Journal = match serde_json::from_slice(&bytes) {
            Ok(j) => j,
            Err(e) => {
                // Do not delete it. A record we cannot read might still be
                // useful to a person, and deleting it destroys the only trace
                // of what was happening.
                return Recovery::Unrecognised {
                    detail: e.to_string(),
                };
            }
        };

        match journal.stage {
            // Nothing moved. Throw the download away.
            Stage::Staged => {
                let _ = std::fs::remove_file(&journal.staged);
                self.clear_journal();
                Recovery::DiscardedStaging {
                    component: journal.component,
                }
            }
            // The previous copy is aside and the replacement never arrived.
            // The target may be missing entirely, which would mean a launcher
            // with no launcher.
            Stage::BackedUp => {
                self.roll_back(&journal);
                Recovery::RolledBack {
                    component: journal.component,
                    restored_version: journal.previous_version,
                }
            }
            // The interesting one. Decide by looking at what is actually on
            // disk rather than by assuming which side of the crash we are on.
            Stage::Swapped => {
                let intact = std::fs::read(&journal.target)
                    .is_ok_and(|b| verify::sha256_hex(&b).eq_ignore_ascii_case(&journal.sha256));

                let is_running = journal
                    .component_id()
                    .is_some_and(ComponentId::is_the_running_program);

                // For the launcher, "the new version is what is running" is
                // direct evidence that it starts — better evidence than the
                // self-check would have produced, because it is the real thing
                // rather than a probe.
                let proved_itself = !is_running || running_version == journal.new_version;

                if intact && proved_itself {
                    self.clear_journal();
                    Recovery::CompletedSwap {
                        component: journal.component,
                        version: journal.new_version,
                    }
                } else {
                    self.roll_back(&journal);
                    Recovery::RolledBack {
                        component: journal.component,
                        restored_version: journal.previous_version,
                    }
                }
            }
        }
    }

    /// Put the backup back on request, whatever the journal says.
    ///
    /// The manual escape hatch for the case `recover` cannot reach on its own:
    /// a replacement that completed, was never validated, and does not start.
    /// Nothing is running to notice that, so a person invokes this.
    pub fn restore(&self, component: ComponentId, target: &Path) -> Result<(), InstallError> {
        if !fs_safety::is_lexically_inside(&self.storage_root, target) {
            return Err(InstallError::TargetOutsideStorage(
                target.display().to_string(),
            ));
        }
        let backup = self.backup_path(component);
        if !backup.is_file() {
            return Err(InstallError::StagedMissing {
                component,
                path: backup.display().to_string(),
            });
        }
        let _ = std::fs::remove_file(target);
        std::fs::rename(&backup, target).map_err(io("restoring the previous copy", &backup))?;
        self.clear_journal();
        Ok(())
    }

    /// Whether a previous copy is available to restore.
    pub fn has_backup(&self, component: ComponentId) -> bool {
        self.backup_path(component).is_file()
    }

    /// Delete anything left in staging.
    ///
    /// Staging holds unverified bytes and nothing loads from it, so sweeping it
    /// is always safe. Returns how many files went.
    pub fn sweep_staging(&self) -> usize {
        let Ok(entries) = std::fs::read_dir(self.paths.staging()) else {
            return 0;
        };
        let mut removed = 0;
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file()
                && fs_safety::is_lexically_inside(&self.paths.staging(), &path)
                && std::fs::remove_file(&path).is_ok()
            {
                removed += 1;
            }
        }
        removed
    }

    fn write_journal(&self, journal: &Journal) -> Result<(), InstallError> {
        let bytes =
            serde_json::to_vec_pretty(journal).map_err(|e| InstallError::Journal(e.to_string()))?;
        fs_safety::atomic_write(&self.paths.journal(), &bytes)
            .map_err(|e| InstallError::Journal(e.to_string()))
    }

    fn clear_journal(&self) {
        let _ = std::fs::remove_file(self.paths.journal());
    }
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
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
        root: PathBuf,
        installer: Installer,
    }

    impl Fixture {
        fn new() -> Self {
            let tmp = tempfile::tempdir().expect("tempdir");
            let root = tmp.path().to_path_buf();
            let paths = UpdatePaths::new(&root);
            paths.ensure().expect("ensure");
            let installer = Installer::new(paths, &root);
            Self {
                _tmp: tmp,
                root,
                installer,
            }
        }

        fn target(&self) -> PathBuf {
            self.root.join("bin/fathom.exe")
        }

        fn write_target(&self, contents: &[u8]) {
            let target = self.target();
            std::fs::create_dir_all(target.parent().unwrap()).unwrap();
            std::fs::write(&target, contents).unwrap();
        }

        fn stage(&self, component: ComponentId, version: &str, contents: &[u8]) -> PathBuf {
            let path = self.installer.staged_path(component, version);
            std::fs::write(&path, contents).unwrap();
            path
        }
    }

    /// Accepts or refuses on command, and records that it was consulted.
    struct ScriptedValidator {
        verdict: Result<(), String>,
        seen: std::sync::Mutex<Vec<PathBuf>>,
    }

    impl ScriptedValidator {
        fn accepting() -> Self {
            Self {
                verdict: Ok(()),
                seen: std::sync::Mutex::new(Vec::new()),
            }
        }

        fn refusing(reason: &str) -> Self {
            Self {
                verdict: Err(reason.to_owned()),
                seen: std::sync::Mutex::new(Vec::new()),
            }
        }

        fn was_consulted(&self) -> bool {
            !self.seen.lock().unwrap().is_empty()
        }
    }

    impl Validator for ScriptedValidator {
        fn validate(&self, _component: ComponentId, path: &Path) -> Result<(), String> {
            self.seen.lock().unwrap().push(path.to_path_buf());
            self.verdict.clone()
        }
    }

    #[test]
    fn a_good_replacement_is_installed_and_the_previous_copy_is_kept() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        let staged = f.stage(ComponentId::Launcher, "0.3.0", b"new launcher");

        let validator = ScriptedValidator::accepting();
        f.installer
            .apply(
                &Replacement {
                    component: ComponentId::Launcher,
                    target: &f.target(),
                    staged: &staged,
                    expected_sha256: &verify::sha256_hex(b"new launcher"),
                    new_version: "0.3.0",
                    previous_version: "0.2.0",
                },
                &validator,
            )
            .expect("a good replacement installs");

        assert_eq!(std::fs::read(f.target()).unwrap(), b"new launcher");
        assert!(
            validator.was_consulted(),
            "the replacement must be validated"
        );
        assert!(
            f.installer.has_backup(ComponentId::Launcher),
            "the previous copy is kept, not deleted"
        );
        assert!(
            !f.installer.paths().journal().exists(),
            "a completed install leaves no journal"
        );
    }

    /// The requirement in one test: a replacement that does not work must not
    /// cost the user their working launcher.
    #[test]
    fn a_replacement_that_fails_its_self_check_is_rolled_back() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        let staged = f.stage(ComponentId::Launcher, "0.3.0", b"broken launcher");

        let validator = ScriptedValidator::refusing("it exited with 1");
        let err = f
            .installer
            .apply(
                &Replacement {
                    component: ComponentId::Launcher,
                    target: &f.target(),
                    staged: &staged,
                    expected_sha256: &verify::sha256_hex(b"broken launcher"),
                    new_version: "0.3.0",
                    previous_version: "0.2.0",
                },
                &validator,
            )
            .expect_err("a broken replacement must be refused");

        assert!(
            matches!(err, InstallError::ReplacementRejected { .. }),
            "{err:?}"
        );
        assert_eq!(
            std::fs::read(f.target()).unwrap(),
            b"old launcher",
            "the working version must be back in place"
        );
        assert!(!f.installer.paths().journal().exists());
    }

    /// The staged file is an ordinary file between download and install. If it
    /// changes in that window, the install must notice.
    #[test]
    fn a_staged_file_that_changed_after_download_is_refused() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        let staged = f.stage(ComponentId::Launcher, "0.3.0", b"swapped in later");

        let err = f
            .installer
            .apply(
                &Replacement {
                    component: ComponentId::Launcher,
                    target: &f.target(),
                    staged: &staged,
                    // The hash the signed metadata promised, for other bytes.
                    expected_sha256: &verify::sha256_hex(b"what we downloaded"),
                    new_version: "0.3.0",
                    previous_version: "0.2.0",
                },
                &ScriptedValidator::accepting(),
            )
            .expect_err("a changed staged file must be refused");

        assert!(matches!(err, InstallError::Artifact(_)), "{err:?}");
        assert_eq!(
            std::fs::read(f.target()).unwrap(),
            b"old launcher",
            "nothing may be touched when the staged file does not verify"
        );
        assert!(!staged.exists(), "and the bad file is discarded");
    }

    #[test]
    fn a_fresh_install_with_no_previous_copy_works() {
        let f = Fixture::new();
        let staged = f.stage(ComponentId::OptimizationModule, "0.1.0", b"jar bytes");
        let target = f.root.join("components/fathom-optimizations.jar");

        f.installer
            .apply(
                &Replacement {
                    component: ComponentId::OptimizationModule,
                    target: &target,
                    staged: &staged,
                    expected_sha256: &verify::sha256_hex(b"jar bytes"),
                    new_version: "0.1.0",
                    previous_version: "",
                },
                &HashOnlyValidator,
            )
            .expect("installing something that was not there before");

        assert_eq!(std::fs::read(&target).unwrap(), b"jar bytes");
        assert!(!f.installer.has_backup(ComponentId::OptimizationModule));
    }

    #[test]
    fn a_target_outside_launcher_storage_is_refused() {
        let f = Fixture::new();
        let staged = f.stage(ComponentId::Launcher, "0.3.0", b"payload");
        let outside = std::env::temp_dir().join("fathom-should-never-be-written.exe");

        let err = f
            .installer
            .apply(
                &Replacement {
                    component: ComponentId::Launcher,
                    target: &outside,
                    staged: &staged,
                    expected_sha256: &verify::sha256_hex(b"payload"),
                    new_version: "0.3.0",
                    previous_version: "0.2.0",
                },
                &ScriptedValidator::accepting(),
            )
            .expect_err("an install target outside storage must be refused");
        assert!(
            matches!(err, InstallError::TargetOutsideStorage(_)),
            "{err:?}"
        );
        assert!(!outside.exists());
    }

    // -- recovery ---------------------------------------------------------

    fn journal_for(f: &Fixture, stage: Stage, new_bytes: &[u8]) -> Journal {
        Journal {
            component: ComponentId::Launcher.as_str().to_owned(),
            stage,
            target: f.target(),
            staged: f.installer.staged_path(ComponentId::Launcher, "0.3.0"),
            backup: f.installer.backup_path(ComponentId::Launcher),
            new_version: "0.3.0".to_owned(),
            previous_version: "0.2.0".to_owned(),
            sha256: verify::sha256_hex(new_bytes),
            had_previous: true,
            started_at: 0,
        }
    }

    #[test]
    fn with_no_journal_recovery_does_nothing() {
        let f = Fixture::new();
        assert_eq!(f.installer.recover("0.2.0"), Recovery::NothingToDo);
    }

    #[test]
    fn an_interruption_before_anything_moved_just_discards_the_download() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        let staged = f.stage(ComponentId::Launcher, "0.3.0", b"new launcher");
        f.installer
            .write_journal(&journal_for(&f, Stage::Staged, b"new launcher"))
            .unwrap();

        let outcome = f.installer.recover("0.2.0");
        assert!(
            matches!(outcome, Recovery::DiscardedStaging { .. }),
            "{outcome:?}"
        );
        assert!(!staged.exists());
        assert_eq!(std::fs::read(f.target()).unwrap(), b"old launcher");
        assert!(outcome.message().is_some());
    }

    /// The dangerous interruption: the previous copy has been moved aside and
    /// the replacement never arrived, so the target does not exist at all.
    #[test]
    fn an_interruption_with_the_target_moved_aside_restores_it() {
        let f = Fixture::new();
        f.write_target(b"old launcher");

        // Simulate having got as far as the backup step.
        let backup = f.installer.backup_path(ComponentId::Launcher);
        std::fs::rename(f.target(), &backup).unwrap();
        assert!(!f.target().exists(), "the target is genuinely missing");

        f.installer
            .write_journal(&journal_for(&f, Stage::BackedUp, b"new launcher"))
            .unwrap();

        let outcome = f.installer.recover("0.2.0");
        assert!(
            matches!(outcome, Recovery::RolledBack { .. }),
            "{outcome:?}"
        );
        assert_eq!(
            std::fs::read(f.target()).unwrap(),
            b"old launcher",
            "a launcher with no launcher is the one outcome that must not happen"
        );
        assert!(!f.installer.paths().journal().exists());
    }

    /// A swap that completed, interrupted before validation, where the new
    /// version is what is now running. It evidently starts, so it is kept.
    #[test]
    fn a_completed_swap_is_kept_when_the_new_version_is_the_one_running() {
        let f = Fixture::new();
        f.write_target(b"new launcher");
        std::fs::write(
            f.installer.backup_path(ComponentId::Launcher),
            b"old launcher",
        )
        .unwrap();
        f.installer
            .write_journal(&journal_for(&f, Stage::Swapped, b"new launcher"))
            .unwrap();

        let outcome = f.installer.recover("0.3.0");
        assert!(
            matches!(outcome, Recovery::CompletedSwap { .. }),
            "the running program is the new version, so it demonstrably starts: {outcome:?}"
        );
        assert_eq!(std::fs::read(f.target()).unwrap(), b"new launcher");
    }

    /// The same journal, but the old version is what is running — so the new
    /// one did not start. Roll back.
    #[test]
    fn a_completed_swap_is_rolled_back_when_the_old_version_is_still_running() {
        let f = Fixture::new();
        f.write_target(b"new launcher");
        std::fs::write(
            f.installer.backup_path(ComponentId::Launcher),
            b"old launcher",
        )
        .unwrap();
        f.installer
            .write_journal(&journal_for(&f, Stage::Swapped, b"new launcher"))
            .unwrap();

        let outcome = f.installer.recover("0.2.0");
        assert!(
            matches!(outcome, Recovery::RolledBack { .. }),
            "{outcome:?}"
        );
        assert_eq!(std::fs::read(f.target()).unwrap(), b"old launcher");
    }

    /// A swap interrupted partway through the file write leaves a target that
    /// does not match the hash. That is not a version anyone should run.
    #[test]
    fn a_swap_that_left_a_corrupt_target_is_rolled_back() {
        let f = Fixture::new();
        f.write_target(b"half a laun");
        std::fs::write(
            f.installer.backup_path(ComponentId::Launcher),
            b"old launcher",
        )
        .unwrap();
        f.installer
            .write_journal(&journal_for(&f, Stage::Swapped, b"new launcher"))
            .unwrap();

        // Even claiming to be the new version does not rescue a corrupt file.
        let outcome = f.installer.recover("0.3.0");
        assert!(
            matches!(outcome, Recovery::RolledBack { .. }),
            "{outcome:?}"
        );
        assert_eq!(std::fs::read(f.target()).unwrap(), b"old launcher");
    }

    #[test]
    fn a_journal_we_cannot_read_is_reported_and_left_alone() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        std::fs::write(f.installer.paths().journal(), b"{ not json").unwrap();

        let outcome = f.installer.recover("0.2.0");
        assert!(
            matches!(outcome, Recovery::Unrecognised { .. }),
            "{outcome:?}"
        );
        assert!(
            f.installer.paths().journal().exists(),
            "a record we cannot read is the only trace of what happened; do not delete it"
        );
        assert_eq!(std::fs::read(f.target()).unwrap(), b"old launcher");
    }

    #[test]
    fn restore_puts_the_backup_back_on_request() {
        let f = Fixture::new();
        f.write_target(b"broken new launcher");
        std::fs::write(
            f.installer.backup_path(ComponentId::Launcher),
            b"old launcher",
        )
        .unwrap();

        f.installer
            .restore(ComponentId::Launcher, &f.target())
            .expect("a manual restore");
        assert_eq!(std::fs::read(f.target()).unwrap(), b"old launcher");
    }

    #[test]
    fn restore_refuses_a_target_outside_storage() {
        let f = Fixture::new();
        std::fs::write(
            f.installer.backup_path(ComponentId::Launcher),
            b"old launcher",
        )
        .unwrap();
        let outside = std::env::temp_dir().join("fathom-restore-escape.exe");
        assert!(matches!(
            f.installer.restore(ComponentId::Launcher, &outside),
            Err(InstallError::TargetOutsideStorage(_))
        ));
    }

    #[test]
    fn sweeping_staging_removes_only_staging() {
        let f = Fixture::new();
        f.write_target(b"old launcher");
        f.stage(ComponentId::Launcher, "0.3.0", b"a");
        f.stage(ComponentId::Metadata, "0.4.0", b"b");

        assert_eq!(f.installer.sweep_staging(), 2);
        assert_eq!(f.installer.sweep_staging(), 0);
        assert!(
            f.target().is_file(),
            "the sweep must not reach outside staging"
        );
    }

    /// A version string becomes a filename, and it comes from a publisher.
    #[test]
    fn a_hostile_version_string_cannot_escape_the_staging_directory() {
        let f = Fixture::new();
        for version in [
            "../../escape",
            "..\\..\\escape",
            "/etc/passwd",
            "C:\\Windows\\evil",
            "con",
            &"a".repeat(500),
            "with spaces",
            "\u{0}nul",
        ] {
            let path = f.installer.staged_path(ComponentId::Launcher, version);
            assert!(
                fs_safety::is_lexically_inside(&f.installer.paths().staging(), &path),
                "{version:?} produced {path:?}, outside staging"
            );
        }
    }
}
