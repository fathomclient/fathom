//! Is the Java runtime this profile wants actually usable?
//!
//! # Two kinds of Java, and only one of them is ours
//!
//! | Kind | Who owns it | What we may do |
//! |---|---|---|
//! | **Managed** — under `<data>/java` | The launcher | Validate, delete, re-acquire |
//! | **External** — a system JDK, or a path the user chose | The user | Look at it. Nothing else. |
//!
//! That line is the whole point of this module. A launcher that "helpfully"
//! repairs a broken external Java is a launcher that modified a system JDK
//! several other programs depend on. So:
//!
//! - **Nothing outside `<data>/java` is ever written to or deleted**, and
//!   [`RuntimeVerdict::may_reacquire`] is false for every external runtime.
//! - When the user chose a specific Java and it has become invalid, that is
//!   **reported**, not silently swapped for another one on the system. Quietly
//!   substituting a different runtime is how a user ends up debugging a crash
//!   caused by a JVM they did not know they were running.
//!
//! # No managed runtime exists yet
//!
//! `java_runtime.rs` finds Java the user already has; it does not download one.
//! So today every runtime is external and [`validate`] reports as much. The
//! managed paths are written and tested against a fabricated runtime directory,
//! because the interesting logic — incomplete extraction, wrong architecture,
//! wrong version — is the same whether or not we were the ones who put it there.

use std::path::{Path, PathBuf};

use crate::fs_safety;
use crate::java_runtime::{self, JavaInstallation};
use crate::paths::DataPaths;
use crate::problem::{Category, Problem, Repair, Retry};

/// One specific thing wrong with a runtime.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimeDefect {
    /// The directory a managed runtime should be in is not there.
    RuntimeMissing { path: PathBuf },
    /// The directory exists but the executable does not.
    ExecutableMissing { expected: PathBuf },
    /// The executable is there but will not run, or reports nothing we can read.
    ExecutableUnusable { path: PathBuf, why: String },
    /// It runs, but it is the wrong major version.
    WrongVersion { found: u32, required: u32 },
    /// It runs, but not on this machine's architecture.
    ///
    /// Detected by the executable failing in the specific way a wrong-arch
    /// binary fails, which is why this is separate from `ExecutableUnusable`.
    WrongArchitecture { detail: String },
    /// Files a complete runtime always has are missing: the archive was
    /// extracted partway and the process died.
    IncompleteExtraction { missing: Vec<String> },
    /// A file does not match the hash recorded when it was installed.
    IntegrityFailure { path: PathBuf },
    /// The user chose this runtime explicitly and it is no longer usable.
    ///
    /// Distinct from every defect above because the **response** is different:
    /// there is nothing to repair, and choosing a different runtime is the
    /// user's decision to make.
    ChosenRuntimeInvalid { path: PathBuf, why: String },
}

impl RuntimeDefect {
    pub fn describe(&self) -> String {
        match self {
            RuntimeDefect::RuntimeMissing { path } => {
                format!("No Java runtime at {}.", path.display())
            }
            RuntimeDefect::ExecutableMissing { expected } => format!(
                "The Java runtime directory exists but {} is not in it.",
                expected.display()
            ),
            RuntimeDefect::ExecutableUnusable { path, why } => {
                format!("{} would not run: {why}", path.display())
            }
            RuntimeDefect::WrongVersion { found, required } => format!(
                "This is Java {found}, and this version of Minecraft needs Java {required}."
            ),
            RuntimeDefect::WrongArchitecture { detail } => {
                format!("The Java runtime is not built for this machine. {detail}")
            }
            RuntimeDefect::IncompleteExtraction { missing } => format!(
                "The Java runtime is missing {} file(s) it should have, including {}. \
                 It was probably interrupted while being unpacked.",
                missing.len(),
                missing.first().map_or("some", String::as_str)
            ),
            RuntimeDefect::IntegrityFailure { path } => format!(
                "{} does not match what was recorded when it was installed.",
                path.display()
            ),
            RuntimeDefect::ChosenRuntimeInvalid { path, why } => format!(
                "The Java runtime you chose, {}, is no longer usable: {why}",
                path.display()
            ),
        }
    }
}

/// Whether the launcher is allowed to fix this itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ownership {
    /// Under `<data>/java`. Ours to replace.
    Managed,
    /// A system runtime, or one the user pointed at. Read-only to us.
    External,
}

/// The result of looking at a runtime.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeVerdict {
    pub path: Option<PathBuf>,
    pub ownership: Ownership,
    pub defects: Vec<RuntimeDefect>,
    /// What it actually is, when we got far enough to ask.
    pub installation: Option<JavaInstallation>,
}

impl RuntimeVerdict {
    pub fn is_usable(&self) -> bool {
        self.defects.is_empty() && self.installation.is_some()
    }

    /// Whether `repair` would do anything.
    ///
    /// **Managed runtimes only.** An external runtime is never re-acquired,
    /// however broken it is.
    pub fn may_reacquire(&self) -> bool {
        self.ownership == Ownership::Managed && !self.defects.is_empty()
    }

    /// A failure the window can show.
    pub fn problem(&self) -> Option<Problem> {
        let first = self.defects.first()?;
        let base = Problem::new(Category::JavaRuntime, first.describe()).detail(format!(
            "{} defect(s); runtime is {:?}",
            self.defects.len(),
            self.ownership
        ));
        Some(match (self.ownership, first) {
            // The user chose it. Offer the choice back, not a repair.
            (_, RuntimeDefect::ChosenRuntimeInvalid { .. }) => {
                base.retry(Retry::Pointless).repair(Repair::ChooseJava)
            }
            (Ownership::Managed, _) => base.retry(Retry::AfterRepair).repair(Repair::RepairJava),
            (Ownership::External, _) => base.retry(Retry::Pointless).repair(Repair::ChooseJava),
        })
    }
}

/// Files a complete Java 8 runtime always contains.
///
/// Deliberately short and deliberately boring: these exist in every JRE and JDK
/// layout we target, so a missing one means the tree is incomplete rather than
/// unusual. Checking for something exotic would report a perfectly good runtime
/// as broken.
const REQUIRED_ENTRIES: &[&str] = &["bin", "lib"];

/// Where a managed runtime for a given major version lives.
pub fn managed_runtime_dir(paths: &DataPaths, major: u32) -> PathBuf {
    paths.java_dir().join(format!("jre-{major}"))
}

/// The executable inside a runtime directory.
pub fn executable_in(runtime: &Path) -> PathBuf {
    let name = if cfg!(windows) { "java.exe" } else { "java" };
    runtime.join("bin").join(name)
}

/// Inspect a runtime.
///
/// `chosen` is the path the profile explicitly asked for, if any. Passing it
/// changes the *reporting* rather than the checks: the same broken runtime is
/// `ChosenRuntimeInvalid` when the user picked it and an ordinary defect when
/// we did.
pub fn validate(
    paths: &DataPaths,
    runtime_dir: &Path,
    required_major: u32,
    chosen: bool,
) -> RuntimeVerdict {
    let ownership = if fs_safety::is_lexically_inside(&paths.java_dir(), runtime_dir) {
        Ownership::Managed
    } else {
        Ownership::External
    };

    let mut verdict = RuntimeVerdict {
        path: Some(runtime_dir.to_path_buf()),
        ownership,
        defects: Vec::new(),
        installation: None,
    };

    // 1. Presence.
    if !runtime_dir.is_dir() {
        verdict.defects.push(RuntimeDefect::RuntimeMissing {
            path: runtime_dir.to_path_buf(),
        });
        return finish(verdict, chosen);
    }

    // 2. Completeness, before trying to run anything. A half-extracted tree
    //    often still has `java.exe`, and running it produces a confusing error
    //    rather than "this was never fully unpacked".
    let missing: Vec<String> = REQUIRED_ENTRIES
        .iter()
        .filter(|entry| !runtime_dir.join(entry).exists())
        .map(|entry| (*entry).to_owned())
        .collect();
    if !missing.is_empty() {
        verdict
            .defects
            .push(RuntimeDefect::IncompleteExtraction { missing });
    }

    // 3. The executable.
    let executable = executable_in(runtime_dir);
    if !executable.is_file() {
        verdict.defects.push(RuntimeDefect::ExecutableMissing {
            expected: executable,
        });
        return finish(verdict, chosen);
    }

    // 4. Does it run, and what is it? This is the only step that spawns a
    //    process, and it runs `java -version` with nothing user-controlled on
    //    the command line.
    match java_runtime::probe(&executable) {
        Ok(installation) => {
            if installation.major != required_major {
                verdict.defects.push(RuntimeDefect::WrongVersion {
                    found: installation.major,
                    required: required_major,
                });
            }
            verdict.installation = Some(installation);
        }
        Err(error) => {
            let text = error.to_string();
            // A wrong-architecture binary fails in a recognisable way, and
            // saying "not built for this machine" is far more useful than
            // "would not run".
            if looks_like_an_architecture_mismatch(&text) {
                verdict
                    .defects
                    .push(RuntimeDefect::WrongArchitecture { detail: text });
            } else {
                verdict.defects.push(RuntimeDefect::ExecutableUnusable {
                    path: executable,
                    why: text,
                });
            }
        }
    }

    finish(verdict, chosen)
}

/// Re-frame the defects when the user picked this runtime themselves.
///
/// One defect replaces the list, because the response is a single decision
/// rather than a repair, and listing four technical problems with a runtime the
/// launcher is not going to touch is noise.
fn finish(mut verdict: RuntimeVerdict, chosen: bool) -> RuntimeVerdict {
    if chosen && verdict.ownership == Ownership::External && !verdict.defects.is_empty() {
        let why = verdict
            .defects
            .first()
            .map_or_else(|| "it is not usable".to_owned(), RuntimeDefect::describe);
        let path = verdict
            .path
            .clone()
            .unwrap_or_else(|| PathBuf::from("(unknown)"));
        verdict.defects = vec![RuntimeDefect::ChosenRuntimeInvalid { path, why }];
    }
    verdict
}

/// Windows and the ELF loader both say something specific about this.
fn looks_like_an_architecture_mismatch(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    [
        "not a valid win32 application",
        "%1 is not a valid",
        "exec format error",
        "wrong architecture",
        "bad cpu type",
        "cannot execute binary file",
    ]
    .iter()
    .any(|marker| lower.contains(marker))
}

/// What a repair did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RepairOutcome {
    /// It was already fine.
    NothingToDo,
    /// A broken managed runtime was removed so it can be re-acquired.
    ///
    /// Removal and re-acquisition are separate because re-acquisition needs the
    /// network and this does not: a launcher that is offline can still get
    /// itself out of the "half-extracted runtime" state.
    RemovedForReacquisition { path: PathBuf },
    /// It is broken and it is not ours.
    RefusedExternal { path: PathBuf, why: String },
}

impl RepairOutcome {
    pub fn message(&self) -> String {
        match self {
            RepairOutcome::NothingToDo => "The Java runtime is fine.".to_owned(),
            RepairOutcome::RemovedForReacquisition { .. } => {
                "The damaged Java runtime was removed. It will be downloaded again the next \
                 time it is needed."
                    .to_owned()
            }
            RepairOutcome::RefusedExternal { path, why } => format!(
                "{} is not a runtime this launcher installed, so it has been left alone. \
                 {why} Choose a different Java runtime in the profile's settings.",
                path.display()
            ),
        }
    }
}

/// Repair a managed runtime by removing it.
///
/// # Idempotent
///
/// Running it twice is the same as running it once: the second call finds
/// nothing there and reports `NothingToDo`. There is no partial state — the
/// directory is either gone or it is not.
///
/// # It will not touch an external runtime
///
/// Checked twice: once through [`RuntimeVerdict::ownership`], and again here
/// against `paths.java_dir()` immediately before the removal. The second check
/// is the one that matters, because it is adjacent to the `remove_dir_all`.
pub fn repair(
    paths: &DataPaths,
    verdict: &RuntimeVerdict,
) -> Result<RepairOutcome, std::io::Error> {
    let Some(path) = verdict.path.as_ref() else {
        return Ok(RepairOutcome::NothingToDo);
    };
    if verdict.defects.is_empty() {
        return Ok(RepairOutcome::NothingToDo);
    }

    if verdict.ownership != Ownership::Managed {
        return Ok(RepairOutcome::RefusedExternal {
            path: path.clone(),
            why: verdict
                .defects
                .first()
                .map_or_else(String::new, RuntimeDefect::describe),
        });
    }

    // The check that sits next to the deletion. `is_lexically_inside` is not
    // enough on its own for a path that exists, so canonicalise first -- a
    // symlink at `<data>/java/jre-8` pointing at `C:\Program Files\Java` would
    // otherwise pass a lexical test and take a system JDK with it.
    let java_root = paths.java_dir();
    let inside = match (path.canonicalize(), java_root.canonicalize()) {
        (Ok(real), Ok(root)) => fs_safety::is_lexically_inside(&root, &real),
        // The path does not exist, so there is nothing to remove and nothing to
        // get wrong.
        _ => return Ok(RepairOutcome::NothingToDo),
    };
    if !inside {
        return Ok(RepairOutcome::RefusedExternal {
            path: path.clone(),
            why: "It resolves outside the launcher's own Java directory.".to_owned(),
        });
    }

    if !path.exists() {
        return Ok(RepairOutcome::NothingToDo);
    }
    std::fs::remove_dir_all(path)?;
    Ok(RepairOutcome::RemovedForReacquisition { path: path.clone() })
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

        /// A runtime tree that looks complete but whose executable is not real.
        fn fabricate_managed(&self, major: u32) -> PathBuf {
            let dir = managed_runtime_dir(&self.paths, major);
            std::fs::create_dir_all(dir.join("bin")).unwrap();
            std::fs::create_dir_all(dir.join("lib")).unwrap();
            std::fs::write(executable_in(&dir), b"not really java").unwrap();
            dir
        }
    }

    #[test]
    fn a_missing_runtime_is_reported_as_missing() {
        let f = Fixture::new();
        let dir = managed_runtime_dir(&f.paths, 8);
        let verdict = validate(&f.paths, &dir, 8, false);

        assert_eq!(verdict.ownership, Ownership::Managed);
        assert!(matches!(
            verdict.defects.first(),
            Some(RuntimeDefect::RuntimeMissing { .. })
        ));
        assert!(!verdict.is_usable());
        assert!(verdict.may_reacquire(), "a managed runtime may be replaced");
    }

    /// The specific failure the spec names: the archive was extracted partway
    /// and the process died.
    #[test]
    fn an_incomplete_extraction_is_detected_before_anything_is_run() {
        let f = Fixture::new();
        let dir = managed_runtime_dir(&f.paths, 8);
        // `bin/java` present, `lib/` never unpacked.
        std::fs::create_dir_all(dir.join("bin")).unwrap();
        std::fs::write(executable_in(&dir), b"partial").unwrap();

        let verdict = validate(&f.paths, &dir, 8, false);
        let incomplete = verdict
            .defects
            .iter()
            .find(|d| matches!(d, RuntimeDefect::IncompleteExtraction { .. }))
            .expect("an incomplete extraction");
        assert!(incomplete.describe().contains("interrupted"));
    }

    #[test]
    fn a_directory_with_no_executable_says_so() {
        let f = Fixture::new();
        let dir = managed_runtime_dir(&f.paths, 8);
        std::fs::create_dir_all(dir.join("bin")).unwrap();
        std::fs::create_dir_all(dir.join("lib")).unwrap();

        let verdict = validate(&f.paths, &dir, 8, false);
        assert!(matches!(
            verdict.defects.first(),
            Some(RuntimeDefect::ExecutableMissing { .. })
        ));
    }

    #[test]
    fn something_that_is_not_java_is_reported_as_unusable() {
        let f = Fixture::new();
        let dir = f.fabricate_managed(8);
        let verdict = validate(&f.paths, &dir, 8, false);

        assert!(!verdict.is_usable());
        assert!(
            verdict.defects.iter().any(|d| matches!(
                d,
                RuntimeDefect::ExecutableUnusable { .. } | RuntimeDefect::WrongArchitecture { .. }
            )),
            "{:?}",
            verdict.defects
        );
    }

    /// The rule the whole module exists for.
    #[test]
    fn an_external_runtime_is_never_repaired_however_broken_it_is() {
        let f = Fixture::new();
        // Outside `<data>/java` entirely: a "system" JDK.
        let external = f.paths.root().join("system-jdk");
        std::fs::create_dir_all(external.join("bin")).unwrap();
        std::fs::write(executable_in(&external), b"not java").unwrap();

        let verdict = validate(&f.paths, &external, 8, false);
        assert_eq!(verdict.ownership, Ownership::External);
        assert!(!verdict.is_usable());
        assert!(
            !verdict.may_reacquire(),
            "an external runtime is not ours to replace"
        );

        let outcome = repair(&f.paths, &verdict).unwrap();
        assert!(matches!(outcome, RepairOutcome::RefusedExternal { .. }));
        assert!(
            external.is_dir(),
            "a system JDK must still be there after a repair"
        );
        assert!(outcome.message().contains("left alone"));
    }

    /// A runtime the user chose is reported as their decision to revisit, not
    /// as something to silently replace.
    #[test]
    fn a_runtime_the_user_chose_is_reported_rather_than_swapped() {
        let f = Fixture::new();
        let chosen = f.paths.root().join("their-jdk");

        let verdict = validate(&f.paths, &chosen, 8, true);
        assert_eq!(verdict.defects.len(), 1, "one decision, not four defects");
        assert!(matches!(
            verdict.defects[0],
            RuntimeDefect::ChosenRuntimeInvalid { .. }
        ));

        let problem = verdict.problem().expect("a problem");
        assert_eq!(problem.available_repair(), Some(&Repair::ChooseJava));
        assert!(
            !problem.retry_advice().is_worth_it(),
            "retrying will not make their JDK come back"
        );
        assert!(problem.summary().contains("no longer usable"));
    }

    #[test]
    fn a_managed_repair_removes_the_tree_and_is_idempotent() {
        let f = Fixture::new();
        let dir = f.fabricate_managed(8);

        let verdict = validate(&f.paths, &dir, 8, false);
        let first = repair(&f.paths, &verdict).unwrap();
        assert!(matches!(
            first,
            RepairOutcome::RemovedForReacquisition { .. }
        ));
        assert!(!dir.exists());

        // Again. Nothing there, nothing to do, no error.
        let verdict = validate(&f.paths, &dir, 8, false);
        let second = repair(&f.paths, &verdict).unwrap();
        assert_eq!(second, RepairOutcome::NothingToDo);
    }

    #[test]
    fn a_healthy_runtime_is_never_removed() {
        let f = Fixture::new();
        let dir = f.fabricate_managed(8);
        let healthy = RuntimeVerdict {
            path: Some(dir.clone()),
            ownership: Ownership::Managed,
            defects: Vec::new(),
            installation: None,
        };
        assert_eq!(
            repair(&f.paths, &healthy).unwrap(),
            RepairOutcome::NothingToDo
        );
        assert!(dir.is_dir());
    }

    /// A symlink at `<data>/java/jre-8` pointing at a real JDK would pass a
    /// lexical containment check while `remove_dir_all` followed it out.
    #[cfg(unix)]
    #[test]
    fn a_link_inside_the_java_directory_cannot_take_a_system_jdk_with_it() {
        let f = Fixture::new();
        let system = f.paths.root().join("system-jdk");
        std::fs::create_dir_all(system.join("bin")).unwrap();
        std::fs::write(system.join("bin/java"), b"a real system jdk").unwrap();

        std::fs::create_dir_all(f.paths.java_dir()).unwrap();
        let link = managed_runtime_dir(&f.paths, 8);
        std::os::unix::fs::symlink(&system, &link).unwrap();

        let verdict = RuntimeVerdict {
            path: Some(link),
            // Claim it is managed: the lexical check passes, so the
            // canonicalising check next to the deletion is what has to catch it.
            ownership: Ownership::Managed,
            defects: vec![RuntimeDefect::IntegrityFailure {
                path: PathBuf::from("x"),
            }],
            installation: None,
        };

        let outcome = repair(&f.paths, &verdict).unwrap();
        assert!(
            matches!(outcome, RepairOutcome::RefusedExternal { .. }),
            "{outcome:?}"
        );
        assert!(
            system.join("bin/java").is_file(),
            "a repair followed a link and deleted a system JDK"
        );
    }

    #[test]
    fn architecture_mismatches_are_recognised_from_what_the_os_says() {
        for text in [
            "could not run C:/j/bin/java.exe: %1 is not a valid Win32 application. (os error 193)",
            "exec format error",
            "bad CPU type in executable",
        ] {
            assert!(
                looks_like_an_architecture_mismatch(text),
                "{text:?} should be recognised"
            );
        }
        assert!(!looks_like_an_architecture_mismatch("permission denied"));
        assert!(!looks_like_an_architecture_mismatch("no such file"));
    }
}
