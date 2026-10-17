//! Starting the game, and knowing what happened to it.
//!
//! Deliberately not a daemon. A [`GameProcess`] is a handle to a child that the
//! operating system owns; if the launcher exits, the game keeps running,
//! because `security-model.md` §7 requires that closing the launcher does not
//! kill the session. Nothing here polls in the background except the two output
//! pumps, which exist because a child whose pipes fill up blocks forever.
//!
//! **No shell, ever.** `Command::new(java).args(...)` passes an argument vector
//! to `CreateProcessW` / `execvp` directly. No `cmd.exe`, no `powershell`, no
//! `sh -c`, and therefore no quoting rules to get wrong and no injection
//! surface. There is a test that asserts the executable is the Java binary
//! itself rather than an interpreter.

use std::collections::VecDeque;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use crate::logging;
use crate::minecraft_runtime::launch::LaunchPlan;
use crate::secret::{self, Secret};

/// How many lines of the child's output are kept in memory for diagnosis.
///
/// The whole stream goes to the log file; this is only the tail that an error
/// message can quote. Bounded because a crash loop can produce megabytes.
const TAIL_LINES: usize = 200;

#[derive(Debug, thiserror::Error)]
pub enum ProcessError {
    #[error("java was not found at {0}")]
    JavaMissing(String),
    #[error("the working directory {0} does not exist")]
    WorkingDirMissing(String),
    #[error("the natives directory {0} does not exist, so LWJGL would fail to load")]
    NativesMissing(String),
    #[error("could not create the log file {path}: {source}")]
    LogFile {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("could not start java: {source}")]
    Spawn {
        #[source]
        source: std::io::Error,
    },
    #[error("could not determine whether the game is still running: {source}")]
    Wait {
        #[source]
        source: std::io::Error,
    },
}

/// Where a launch got to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunState {
    Running,
    Exited,
}

/// What the game's exit means.
///
/// The distinctions are the ones a user can act on. "Crashed" without saying
/// how is not worth telling anyone.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Still going.
    Running,
    /// Exit code 0. The player quit.
    Normal,
    /// The JVM could not start at all: a bad flag, or a corrupt runtime.
    JavaFailedToStart { code: Option<i32>, detail: String },
    /// A class was on the classpath at plan time and is not there now.
    MissingLibrary { detail: String },
    /// The natives directory is wrong, empty, or for another platform.
    InvalidNatives { detail: String },
    /// A JVM argument was rejected.
    BadJvmArguments { detail: String },
    /// Non-zero exit with no more specific explanation.
    Crashed { code: Option<i32> },
}

impl Outcome {
    pub fn is_running(&self) -> bool {
        matches!(self, Outcome::Running)
    }

    pub fn is_clean_exit(&self) -> bool {
        matches!(self, Outcome::Normal)
    }

    /// A short sentence for the interface.
    pub fn message(&self) -> String {
        match self {
            Outcome::Running => "Minecraft is running.".to_owned(),
            Outcome::Normal => "Minecraft exited normally.".to_owned(),
            Outcome::JavaFailedToStart { code, detail } => format!(
                "Java could not start{}. {detail}",
                code.map_or(String::new(), |c| format!(" (exit {c})"))
            ),
            Outcome::MissingLibrary { detail } => format!(
                "A library the game needs is missing or unreadable. Verify the \
                 installation and try again. {detail}"
            ),
            Outcome::InvalidNatives { detail } => format!(
                "The native libraries could not be loaded. Repairing the installation \
                 re-extracts them. {detail}"
            ),
            Outcome::BadJvmArguments { detail } => {
                format!("The JVM rejected an argument. {detail}")
            }
            Outcome::Crashed { code } => format!(
                "Minecraft crashed{}. The log is kept next to the profile.",
                code.map_or(String::new(), |c| format!(" (exit {c})"))
            ),
        }
    }

    /// Whether repairing the installation is a plausible fix. Used to decide
    /// what to offer the user, not to do anything automatically.
    pub fn suggests_repair(&self) -> bool {
        matches!(
            self,
            Outcome::MissingLibrary { .. } | Outcome::InvalidNatives { .. }
        )
    }
}

/// A running (or finished) game.
///
/// Not `Clone`: there is exactly one handle to a child, and duplicating it
/// would mean two owners racing to reap the same process.
#[derive(Debug)]
pub struct GameProcess {
    child: Child,
    pid: u32,
    started_at: SystemTime,
    stopped_at: Option<SystemTime>,
    exit_code: Option<i32>,
    log_file: PathBuf,
    tail: Arc<Mutex<VecDeque<String>>>,
}

impl GameProcess {
    pub fn pid(&self) -> u32 {
        self.pid
    }

    pub fn started_at(&self) -> SystemTime {
        self.started_at
    }

    /// `None` while the game is still running.
    pub fn stopped_at(&self) -> Option<SystemTime> {
        self.stopped_at
    }

    /// `None` while running, or if the process was killed by a signal.
    pub fn exit_code(&self) -> Option<i32> {
        self.exit_code
    }

    pub fn log_file(&self) -> &Path {
        &self.log_file
    }

    /// How long the game ran, or has been running.
    pub fn elapsed(&self) -> std::time::Duration {
        self.stopped_at
            .unwrap_or_else(SystemTime::now)
            .duration_since(self.started_at)
            .unwrap_or_default()
    }

    /// The last lines the game printed. Already scrubbed.
    pub fn recent_output(&self, lines: usize) -> Vec<String> {
        let guard = match self.tail.lock() {
            Ok(g) => g,
            // A panicked pump thread must not take the diagnosis path down with
            // it; an empty tail is a worse message, not a crash.
            Err(poisoned) => poisoned.into_inner(),
        };
        let skip = guard.len().saturating_sub(lines);
        guard.iter().skip(skip).cloned().collect()
    }

    /// Check without blocking. Records the stop time the first time it sees the
    /// process has ended.
    pub fn poll(&mut self) -> Result<RunState, ProcessError> {
        if self.stopped_at.is_some() {
            return Ok(RunState::Exited);
        }
        match self.child.try_wait() {
            Ok(None) => Ok(RunState::Running),
            Ok(Some(status)) => {
                self.record_exit(status);
                Ok(RunState::Exited)
            }
            Err(source) => Err(ProcessError::Wait { source }),
        }
    }

    /// Block until the game exits. Only for the CLI and tests; the interface
    /// polls instead.
    pub fn wait(&mut self) -> Result<i32, ProcessError> {
        if let Some(code) = self.exit_code {
            return Ok(code);
        }
        let status = self
            .child
            .wait()
            .map_err(|source| ProcessError::Wait { source })?;
        self.record_exit(status);
        Ok(self.exit_code.unwrap_or(-1))
    }

    fn record_exit(&mut self, status: std::process::ExitStatus) {
        self.stopped_at = Some(SystemTime::now());
        self.exit_code = status.code();
    }

    pub fn state(&self) -> RunState {
        if self.stopped_at.is_some() {
            RunState::Exited
        } else {
            RunState::Running
        }
    }

    /// What happened, as far as the exit code and the captured output show.
    ///
    /// Classification reads the tail rather than guessing from the exit code
    /// alone, because the JVM reports almost everything as exit 1.
    pub fn outcome(&self) -> Outcome {
        if self.stopped_at.is_none() {
            return Outcome::Running;
        }
        if self.exit_code == Some(0) {
            return Outcome::Normal;
        }
        classify(&self.recent_output(TAIL_LINES), self.exit_code)
    }

    /// Ask the game to stop. Used only when the user asks; nothing kills the
    /// game on its own.
    pub fn terminate(&mut self) -> Result<(), ProcessError> {
        if self.stopped_at.is_some() {
            return Ok(());
        }
        self.child
            .kill()
            .map_err(|source| ProcessError::Wait { source })?;
        let _ = self.wait();
        Ok(())
    }
}

/// Decide what a non-zero exit meant, from what the child printed.
///
/// Ordered most specific first. The markers are JVM and LWJGL messages, not
/// Minecraft's own, so they stay true across game versions.
fn classify(output: &[String], code: Option<i32>) -> Outcome {
    let haystack = output.join("\n");

    let found = |needles: &[&str]| -> Option<String> {
        output
            .iter()
            .find(|line| needles.iter().any(|n| line.contains(n)))
            .map(|line| line.trim().to_owned())
    };

    // A rejected flag stops the JVM before any class loads, so check it first:
    // its message also contains "Could not create the Java Virtual Machine",
    // which would otherwise be read as a generic startup failure.
    if let Some(detail) = found(&[
        "Unrecognized option",
        "Unrecognized VM option",
        "Improperly specified VM option",
        "Invalid maximum heap size",
        "Invalid initial heap size",
        "Too small initial heap",
    ]) {
        return Outcome::BadJvmArguments { detail };
    }

    if let Some(detail) = found(&[
        "UnsatisfiedLinkError",
        "no lwjgl in java.library.path",
        "Failed to locate library",
        "java.library.path",
    ]) {
        return Outcome::InvalidNatives { detail };
    }

    if let Some(detail) = found(&[
        "NoClassDefFoundError",
        "ClassNotFoundException",
        "Could not find or load main class",
        "Error: Unable to initialize main class",
    ]) {
        return Outcome::MissingLibrary { detail };
    }

    if haystack.contains("Could not create the Java Virtual Machine")
        || haystack.contains("Error occurred during initialization of VM")
    {
        return Outcome::JavaFailedToStart {
            code,
            detail: found(&[
                "Could not create the Java Virtual Machine",
                "Error occurred during initialization of VM",
            ])
            .unwrap_or_default(),
        };
    }

    Outcome::Crashed { code }
}

/// Start the game.
///
/// Normal process priority, no shell, no elevation. The child is deliberately
/// not put in a job object: the game must survive the launcher exiting.
pub fn spawn(plan: &LaunchPlan) -> Result<GameProcess, ProcessError> {
    // Fail before spawning rather than letting the JVM produce a worse message.
    if !plan.java().is_file() {
        return Err(ProcessError::JavaMissing(plan.java().display().to_string()));
    }
    if !plan.working_dir().is_dir() {
        return Err(ProcessError::WorkingDirMissing(
            plan.working_dir().display().to_string(),
        ));
    }
    if !plan.natives_dir().is_dir() {
        return Err(ProcessError::NativesMissing(
            plan.natives_dir().display().to_string(),
        ));
    }

    let log = open_log(plan.log_file())?;

    let mut command = Command::new(plan.java());
    suppress_console_window(&mut command);
    command
        .args(plan.argv_exposing_secrets())
        .current_dir(plan.working_dir())
        // Nothing to type at the game, and an inherited stdin keeps a console
        // window alive on Windows.
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    for name in plan.removed_environment() {
        command.env_remove(name);
    }
    for (name, value) in plan.environment() {
        command.env(name, value);
    }

    let started_at = SystemTime::now();
    let mut child = command
        .spawn()
        .map_err(|source| ProcessError::Spawn { source })?;
    let pid = child.id();

    tracing::info!(
        pid,
        java = %plan.java().display(),
        working_dir = %plan.working_dir().display(),
        log = %plan.log_file().display(),
        "game started"
    );

    // One shared sink so interleaving matches what the game actually printed.
    let sink = Arc::new(Mutex::new(log));
    let tail = Arc::new(Mutex::new(VecDeque::with_capacity(TAIL_LINES)));

    // This launch's own secret values, so the pump can remove them from the
    // game's output by exact match rather than hoping a marker rule catches
    // however the game chose to print them.
    let secrets = Arc::new(plan.secret_values());

    if let Some(stdout) = child.stdout.take() {
        pump(
            "out",
            stdout,
            Arc::clone(&sink),
            Arc::clone(&tail),
            Arc::clone(&secrets),
            pid,
        );
    }
    if let Some(stderr) = child.stderr.take() {
        pump(
            "err",
            stderr,
            Arc::clone(&sink),
            Arc::clone(&tail),
            Arc::clone(&secrets),
            pid,
        );
    }

    Ok(GameProcess {
        child,
        pid,
        started_at,
        stopped_at: None,
        exit_code: None,
        log_file: plan.log_file().to_path_buf(),
        tail,
    })
}

/// Minecraft's output is already captured into Fathom's log/console window.
/// Prevent Windows from creating a second, native terminal for the Java child.
#[cfg(windows)]
fn suppress_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt as _;

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn suppress_console_window(_command: &mut Command) {}

fn open_log(path: &Path) -> Result<File, ProcessError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|source| ProcessError::LogFile {
            path: parent.display().to_string(),
            source,
        })?;
    }
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|source| ProcessError::LogFile {
            path: path.display().to_string(),
            source,
        })
}

/// Drain one of the child's pipes into the log file and the in-memory tail.
///
/// A thread rather than a poll loop because a child whose pipe buffer fills up
/// blocks on write and never gets to the point of exiting. Reading has to keep
/// happening whether or not anyone is asking.
///
/// Every line is scrubbed twice before it is written:
///
/// 1. [`logging::scrub`], which removes anything shaped like a credential;
/// 2. this launch's own secret values, by exact match.
///
/// The second pass is not belt-and-braces. Minecraft 1.8.9 prints
/// `Setting user: ...` followed by `(Session ID is token:<access token>:<uuid>)`
/// on every start, and a launcher that captures stdout writes that straight to
/// disk. It was found by launching the game and reading the log, which is the
/// only way it could have been found.
fn pump(
    stream: &'static str,
    source: impl std::io::Read + Send + 'static,
    sink: Arc<Mutex<File>>,
    tail: Arc<Mutex<VecDeque<String>>>,
    secrets: Arc<Vec<Secret>>,
    pid: u32,
) {
    let spawned = std::thread::Builder::new()
        .name(format!("fathom-game-{stream}-{pid}"))
        .spawn(move || {
            for line in BufReader::new(source).lines() {
                let Ok(line) = line else { break };
                let refs: Vec<&Secret> = secrets.iter().collect();
                let clean = secret::redact_all(&logging::scrub(&line), &refs);

                if let Ok(mut file) = sink.lock() {
                    let _ = writeln!(file, "[{stream}] {clean}");
                }
                if let Ok(mut buffer) = tail.lock() {
                    if buffer.len() == TAIL_LINES {
                        buffer.pop_front();
                    }
                    buffer.push_back(clean);
                }
            }
        });

    if let Err(e) = spawned {
        // Losing a pump means losing the log, and eventually a blocked child.
        // Worth a warning; not worth refusing a launch that has already begun.
        tracing::warn!(error = %e, stream, "could not start an output pump");
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

    fn lines(text: &str) -> Vec<String> {
        text.lines().map(str::to_owned).collect()
    }

    #[test]
    fn a_clean_exit_is_not_a_crash() {
        assert_eq!(classify(&[], Some(1)), Outcome::Crashed { code: Some(1) });
        // Exit 0 never reaches `classify`; `outcome` short-circuits it.
        assert!(Outcome::Normal.is_clean_exit());
        assert!(!Outcome::Crashed { code: Some(1) }.is_clean_exit());
    }

    #[test]
    fn a_missing_class_is_reported_as_a_missing_library() {
        let out = classify(
            &lines(
                "Exception in thread \"main\" java.lang.NoClassDefFoundError: com/google/common/base/Predicate\n\tat net.minecraft.client.main.Main.main(Main.java:41)",
            ),
            Some(1),
        );
        assert!(matches!(out, Outcome::MissingLibrary { .. }), "{out:?}");
        assert!(out.suggests_repair());
    }

    #[test]
    fn a_main_class_that_will_not_load_is_a_missing_library_too() {
        let out = classify(
            &lines("Error: Could not find or load main class net.minecraft.client.main.Main"),
            Some(1),
        );
        assert!(matches!(out, Outcome::MissingLibrary { .. }), "{out:?}");
    }

    #[test]
    fn an_unsatisfied_link_error_is_reported_as_bad_natives() {
        let out = classify(
            &lines("java.lang.UnsatisfiedLinkError: no lwjgl in java.library.path"),
            Some(1),
        );
        assert!(matches!(out, Outcome::InvalidNatives { .. }), "{out:?}");
        assert!(out.suggests_repair());
    }

    #[test]
    fn a_rejected_flag_is_reported_as_bad_jvm_arguments() {
        let out = classify(
            &lines("Unrecognized VM option 'UseNonsenseGC'\nError: Could not create the Java Virtual Machine.\nError: A fatal exception has occurred."),
            Some(1),
        );
        assert!(matches!(out, Outcome::BadJvmArguments { .. }), "{out:?}");
        assert!(
            !out.suggests_repair(),
            "repairing files will not fix a flag"
        );
    }

    /// The ordering that matters: a bad flag also prints "Could not create the
    /// Java Virtual Machine", and reporting that instead would send the user
    /// looking at their Java installation rather than their arguments.
    #[test]
    fn a_bad_flag_outranks_the_generic_jvm_failure_it_also_prints() {
        let out = classify(
            &lines("Invalid maximum heap size: -Xmx999999999M\nError: Could not create the Java Virtual Machine."),
            Some(1),
        );
        assert!(matches!(out, Outcome::BadJvmArguments { .. }), "{out:?}");
    }

    #[test]
    fn a_vm_that_cannot_initialise_is_reported_as_java_failing_to_start() {
        let out = classify(
            &lines("Error occurred during initialization of VM\nCould not reserve enough space for object heap"),
            Some(1),
        );
        assert!(matches!(out, Outcome::JavaFailedToStart { .. }), "{out:?}");
    }

    #[test]
    fn an_unexplained_non_zero_exit_stays_unexplained() {
        let out = classify(
            &lines("[Client thread] INFO: Setting user: Steve"),
            Some(255),
        );
        assert_eq!(out, Outcome::Crashed { code: Some(255) });
        assert!(!out.suggests_repair());
    }

    #[test]
    fn every_outcome_says_something_specific() {
        for outcome in [
            Outcome::Running,
            Outcome::Normal,
            Outcome::JavaFailedToStart {
                code: Some(1),
                detail: "x".into(),
            },
            Outcome::MissingLibrary { detail: "x".into() },
            Outcome::InvalidNatives { detail: "x".into() },
            Outcome::BadJvmArguments { detail: "x".into() },
            Outcome::Crashed { code: Some(1) },
        ] {
            assert!(!outcome.message().is_empty(), "{outcome:?}");
        }
    }
}
