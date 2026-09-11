//! Background work, so the window never stops responding.
//!
//! The shape is a single long-lived subscription:
//!
//! ```text
//!   App  --Job-->  [ dispatch loop ]  --spawns-->  std::thread (blocking work)
//!    ^                                                   |
//!    +---------------- Event ----------------------------+
//! ```
//!
//! Every job runs on its own OS thread, so a 2000-file asset download and a
//! sign-in poll can be in flight together without either blocking the
//! interface. The dispatch loop itself only moves messages, so it never blocks.
//!
//! Deliberately **not** an async runtime. `launcher-core` is blocking by design
//! (`architecture-options.md` §2.1 argues a launcher does not need `tokio`), so
//! a thread per job plus a channel is both simpler and a much smaller
//! dependency. The only async here is iced's own subscription plumbing.

use iced::futures::channel::mpsc;
use iced::futures::{SinkExt, StreamExt};
use iced::stream;

use std::path::{Path, PathBuf};

use launcher_core::auth::DeviceCodePrompt;
use launcher_core::install::{InstallStage, RepairOutcome, ResolvedInstall, VerificationReport};
use launcher_core::java_runtime::JavaInstallation;
use launcher_core::launching::{GameStatus, RunningGames};
use launcher_core::profiles::ProfileId;
use launcher_core::tasks::{Cancel, SignedIn, Tasks};
use launcher_core::InstallSnapshot;

/// Work the interface asks for.
#[derive(Debug)]
pub enum Job {
    /// Probe for Java runtimes. Spawns `java -version` per candidate.
    ScanJava { tasks: Tasks },
    /// Restore a session from the stored refresh token.
    RestoreSession { tasks: Tasks, account_id: String },
    /// Run a device-code sign-in to completion, polling on Microsoft's own
    /// interval rather than waiting for the user to press a button.
    SignIn { tasks: Tasks, cancel: Cancel },
    /// Fetch and verify the version document and asset index, resolve the
    /// complete artifact set, then check what is already on disk.
    ///
    /// One job rather than three, because a half-prepared state is not
    /// something the interface has anything useful to say about.
    Prepare {
        tasks: Tasks,
        version_id: String,
        natives_dir: PathBuf,
        cancel: Cancel,
    },
    /// Download whatever is outstanding, extract natives, then verify.
    Install {
        tasks: Tasks,
        resolved: Box<ResolvedInstall>,
        natives_dir: PathBuf,
        cancel: Cancel,
    },
    /// Hash every artifact and report what is really there.
    Verify {
        tasks: Tasks,
        resolved: Box<ResolvedInstall>,
        natives_dir: PathBuf,
    },
    /// Wait for a running game to exit, then report how it ended.
    ///
    /// A thread that blocks rather than a timer in the interface: the answer
    /// arrives when it arrives, and a launcher that wakes every second for a
    /// two-hour session to learn nothing is wasting the user's battery.
    WatchGame {
        games: RunningGames,
        profile: ProfileId,
    },
    /// Discard corrupt bytes, then re-fetch only the broken subset.
    Repair {
        tasks: Tasks,
        resolved: Box<ResolvedInstall>,
        natives_dir: PathBuf,
        cancel: Cancel,
    },
}

impl Job {
    /// Short label used in error messages, so a failure says which job failed.
    fn label(&self) -> &'static str {
        match self {
            Job::ScanJava { .. } => "Java scan",
            Job::RestoreSession { .. } => "session restore",
            Job::SignIn { .. } => "sign-in",
            Job::Prepare { .. } => "version metadata",
            Job::Install { .. } => "install",
            Job::Verify { .. } => "verification",
            Job::WatchGame { .. } => "game",
            Job::Repair { .. } => "repair",
        }
    }
}

/// What comes back.
///
/// `Clone` because iced requires it of any message a widget can emit.
#[derive(Debug, Clone)]
pub enum Event {
    /// First message on the channel: how to submit jobs.
    Ready(mpsc::UnboundedSender<Job>),

    JavaScanned(Vec<JavaInstallation>),

    /// Microsoft returned a code; show it while polling continues.
    SignInCode(Box<DeviceCodePrompt>),
    SignInFinished(Box<SignedIn>),

    /// `None` means there was nothing stored, which is not an error.
    SessionRestored(Option<Box<SignedIn>>),

    /// The version is resolved and we know what is on disk.
    Prepared {
        resolved: Box<ResolvedInstall>,
        report: Box<VerificationReport>,
        snapshot: InstallSnapshot,
    },

    /// Where an install has got to. Carries the engine's own stage rather than
    /// a percentage, so the interface can say "extracting natives" instead of
    /// leaving a bar sitting at 100% while work continues.
    InstallProgress(InstallStage),
    InstallFinished {
        bytes: u64,
        report: Box<VerificationReport>,
        snapshot: InstallSnapshot,
    },

    Verified {
        report: Box<VerificationReport>,
        snapshot: InstallSnapshot,
    },

    /// The game this launcher started has ended.
    GameEnded(Box<GameStatus>),

    Repaired {
        outcome: Box<RepairOutcome>,
        report: Box<VerificationReport>,
        snapshot: InstallSnapshot,
    },

    /// Any job that failed, with a message already fit to show a user.
    Failed {
        job: &'static str,
        message: String,
    },
    /// A job the user cancelled. Distinct from a failure so the interface can
    /// stay quiet about it.
    Cancelled {
        job: &'static str,
    },
}

/// Capacity of the event channel. Progress events are the only high-rate
/// traffic and they coalesce naturally, so a modest buffer is plenty.
const EVENT_BUFFER: usize = 256;

/// The subscription. Passed to `Subscription::run`, which uses the function's
/// type for identity, so it is created once and lives for the whole session.
pub fn run() -> impl iced::futures::Stream<Item = Event> {
    stream::channel(EVENT_BUFFER, |mut output| async move {
        let (job_tx, mut job_rx) = mpsc::unbounded::<Job>();

        if output.send(Event::Ready(job_tx)).await.is_err() {
            return;
        }
        tracing::info!("worker connected");

        // Dispatch only. Each job gets its own thread so that slow work never
        // holds up the next job or the interface.
        while let Some(job) = job_rx.next().await {
            let sink = output.clone();
            if let Err(e) = std::thread::Builder::new()
                .name(format!("fathom-{}", job.label()))
                .spawn(move || execute(job, sink))
            {
                tracing::error!(error = %e, "could not spawn a worker thread");
            }
        }
    })
}

/// A sink that can be used from a plain thread.
///
/// `try_send` is non-blocking, which is what we want: a worker thread must
/// never stall because the interface is briefly behind. Dropped progress
/// events are harmless because each one carries absolute counts rather than a
/// delta — the next one corrects the display.
struct Sink(mpsc::Sender<Event>);

impl Sink {
    fn send(&mut self, event: Event) {
        let critical = !matches!(event, Event::InstallProgress(_));
        if let Err(e) = self.0.try_send(event) {
            if critical {
                tracing::warn!("dropped a worker event: the channel was full or closed: {e}");
            }
        }
    }
}

fn execute(job: Job, sink: mpsc::Sender<Event>) {
    let mut sink = Sink(sink);
    let label = job.label();
    let started = std::time::Instant::now();
    tracing::info!(job = label, "worker job started");
    let _finished = FinishLog { label, started };

    match job {
        Job::ScanJava { tasks } => {
            let found = tasks.discover_java();
            sink.send(Event::JavaScanned(found));
        }

        Job::RestoreSession { tasks, account_id } => match tasks.restore_session(&account_id) {
            Ok(restored) => sink.send(Event::SessionRestored(restored.map(Box::new))),
            // A failed restore is not worth alarming the user about; they simply
            // sign in again.
            Err(e) => {
                tracing::info!(error = %e, "could not restore the stored session");
                sink.send(Event::SessionRestored(None));
            }
        },

        Job::SignIn { tasks, cancel } => {
            // The closure fires as soon as Microsoft returns the code, so the
            // user sees it immediately while polling continues underneath.
            let mut code_sink = Sink(sink.0.clone());
            let result = tasks.sign_in(&cancel, |prompt| {
                code_sink.send(Event::SignInCode(Box::new(prompt.clone())));
            });
            match result {
                Ok(signed_in) => sink.send(Event::SignInFinished(Box::new(signed_in))),
                Err(e) => sink.send(failure(label, &e)),
            }
        }

        Job::Prepare {
            tasks,
            version_id,
            natives_dir,
            cancel,
        } => match prepare(&tasks, &version_id, &natives_dir, &cancel) {
            Ok(event) => sink.send(event),
            Err(e) => sink.send(failure(label, &e)),
        },

        Job::Install {
            tasks,
            resolved,
            natives_dir,
            cancel,
        } => match install(&tasks, &resolved, &natives_dir, &cancel, &mut sink) {
            Ok(event) => sink.send(event),
            Err(e) => sink.send(failure(label, &e)),
        },

        Job::Verify {
            tasks,
            resolved,
            natives_dir,
        } => {
            let report = tasks.verify(&resolved, &natives_dir);
            let snapshot = InstallSnapshot::from_report(&report, &resolved);
            sink.send(Event::Verified {
                report: Box::new(report),
                snapshot,
            });
        }

        Job::WatchGame { games, profile } => {
            if let Some(status) = watch_game(&games, &profile) {
                sink.send(Event::GameEnded(Box::new(status)));
            }
        }

        Job::Repair {
            tasks,
            resolved,
            natives_dir,
            cancel,
        } => match repair(&tasks, &resolved, &natives_dir, &cancel, &mut sink) {
            Ok(event) => sink.send(event),
            Err(e) => sink.send(failure(label, &e)),
        },
    }
}

/// Logs how long a job took, however it ends.
struct FinishLog {
    label: &'static str,
    started: std::time::Instant,
}

impl Drop for FinishLog {
    fn drop(&mut self) {
        tracing::info!(
            job = self.label,
            elapsed_ms = self.started.elapsed().as_millis() as u64,
            "worker job finished"
        );
    }
}

fn failure(job: &'static str, error: &launcher_core::ServiceError) -> Event {
    if matches!(error, launcher_core::ServiceError::Cancelled) {
        Event::Cancelled { job }
    } else {
        Event::Failed {
            job,
            message: error.to_string(),
        }
    }
}

/// Resolve a version completely, then look at what is already on disk.
///
/// The check is existence-only rather than a full hash pass: this runs when the
/// screen is opened, and making the user wait while 139 MB is re-hashed to
/// answer "is there anything to download?" would be the wrong trade. The Verify
/// button exists for when they want the stronger answer.
fn prepare(
    tasks: &Tasks,
    version_id: &str,
    natives_dir: &Path,
    cancel: &Cancel,
) -> Result<Event, launcher_core::ServiceError> {
    let resolved = tasks.prepare(version_id, cancel)?;
    let report = tasks.quick_check(&resolved, natives_dir);
    let snapshot = InstallSnapshot::from_report(&report, &resolved);

    Ok(Event::Prepared {
        resolved: Box::new(resolved),
        report: Box::new(report),
        snapshot,
    })
}

fn install(
    tasks: &Tasks,
    resolved: &ResolvedInstall,
    natives_dir: &Path,
    cancel: &Cancel,
    sink: &mut Sink,
) -> Result<Event, launcher_core::ServiceError> {
    let bytes = tasks.install(resolved, natives_dir, cancel, &mut |stage| {
        sink.send(Event::InstallProgress(stage.clone()));
    })?;

    // Hash everything afterwards rather than trusting that the transfer said it
    // worked. This is the one place a full pass earns its cost: the user has
    // just waited for a download, and the question they want answered is "is it
    // actually correct", not "did the transfer return Ok".
    sink.send(Event::InstallProgress(InstallStage::Verifying));
    let report = tasks.verify(resolved, natives_dir);
    let snapshot = InstallSnapshot::from_report(&report, resolved);

    Ok(Event::InstallFinished {
        bytes,
        report: Box::new(report),
        snapshot,
    })
}

fn repair(
    tasks: &Tasks,
    resolved: &ResolvedInstall,
    natives_dir: &Path,
    cancel: &Cancel,
    sink: &mut Sink,
) -> Result<Event, launcher_core::ServiceError> {
    let outcome = tasks.repair(resolved, natives_dir, cancel, &mut |stage| {
        sink.send(Event::InstallProgress(stage.clone()));
    })?;

    let report = tasks.verify(resolved, natives_dir);
    let snapshot = InstallSnapshot::from_report(&report, resolved);

    Ok(Event::Repaired {
        outcome: Box::new(outcome),
        report: Box::new(report),
        snapshot,
    })
}

/// Block until a game exits, then hand back its final status.
///
/// Polls the registry rather than owning the child, because the registry is the
/// single owner that reaps it -- two waiters on one process is a race. A second
/// between checks is imperceptible against a play session and costs nothing.
///
/// `None` means the entry vanished without us seeing the exit, which is not
/// something worth inventing an outcome for.
fn watch_game(games: &RunningGames, profile: &ProfileId) -> Option<GameStatus> {
    loop {
        let status = games.status(profile)?;
        if !status.is_running() {
            // Free the profile so it can be launched again.
            let _ = games.sweep();
            return Some(status);
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
