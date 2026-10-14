//! Pressing Play.
//!
//! # Why this is not one command
//!
//! Everything else on the bridge is a question with an immediate answer:
//! `settings_view` reads some state and returns it, `set_mod_enabled` writes
//! one field and hands back the new view. A launch is not that. It can take
//! four minutes on a fresh install, it has stages a person wants to watch, and
//! it has one point — before the process exists — where abandoning it is both
//! possible and reasonable.
//!
//! So it is three commands over a supervisor: `play` starts one and returns
//! immediately, `launch_progress` reports where it is, and `cancel_launch`
//! asks it to stop.
//!
//! # Polled, not pushed
//!
//! There is no event channel. The bridge is request/response everywhere else,
//! and a poll at four frames a second while a progress bar is on screen is
//! cheaper — in code, not just in cycles — than an emitter, a listener, a
//! subscription lifetime and a reconnect story that exist for one screen. The
//! same decision is documented on the Logs page, for the same reason.
//!
//! # The lock
//!
//! `Launcher` lives behind a `Mutex` shared with every other command. A launch
//! must not hold it: the interface keeps drawing while the download runs, and a
//! four-minute lock would freeze the window solid.
//!
//! So the supervisor takes the lock three times, briefly — once to read what it
//! needs, once to start the game, and once per poll while it runs — and does
//! the long work (metadata, 139 MB of downloads, hashing, unpacking) holding
//! nothing. Everything it needs for that part is either owned or cheaply
//! cloneable: `InstallService` is a handle, `Cancel` is an `Arc<AtomicBool>`,
//! and the profile is a value.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Serialize;

use launcher_core::install::InstallStage;
use launcher_core::session_pulse::SessionPulse;
use launcher_core::network::policy::Feature;
use launcher_core::tasks::Cancel;

/// Where a launch has got to.
///
/// Ordered the way a launch moves through them, which is also the order the
/// interface reveals them in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Phase {
    /// Nothing is happening. The launch control is a launch control.
    Idle,
    /// Working out what this version needs, and whether we have it.
    Preparing,
    /// Fetching game files.
    Downloading,
    /// Unpacking the native libraries.
    Unpacking,
    /// Authenticating, assembling the command, spawning the process.
    Starting,
    /// Minecraft is up.
    Running,
    /// The game exited. `failure` says how, if it went badly.
    Finished,
    /// We never got as far as a process.
    Failed,
    /// The user pressed Stop.
    Cancelled,
}

impl Phase {
    /// Whether a launch is under way. Drives whether the interface polls.
    pub fn is_active(self) -> bool {
        matches!(
            self,
            Phase::Preparing
                | Phase::Downloading
                | Phase::Unpacking
                | Phase::Starting
                | Phase::Running
        )
    }

    /// Whether Stop does anything.
    ///
    /// Only before the process exists. Once Minecraft is running, "cancel"
    /// would mean killing it, and killing a game the user is playing is not
    /// what somebody who pressed Play two minutes ago is asking for — they
    /// close the game window. A Stop button that turns into a Kill button
    /// without saying so is the kind of control that loses a world.
    pub fn is_cancellable(self) -> bool {
        matches!(
            self,
            Phase::Preparing | Phase::Downloading | Phase::Unpacking
        )
    }
}

/// One snapshot of a launch, as the interface draws it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchProgress {
    pub phase: Phase,
    /// Two or three words. Replaces the button's label while active.
    pub headline: String,
    /// One line under it. May be empty.
    pub detail: String,
    /// 0.0 to 1.0, or `None` when the work has no measurable length.
    /// Deliberately absent rather than faked: a bar that sits at 30% because
    /// somebody had to put a number there teaches people to ignore bars.
    pub fraction: Option<f64>,
    pub active: bool,
    pub cancellable: bool,
    /// Set when the launch ended badly, whether before or after the process
    /// started. One sentence, already suitable for display.
    pub failure: Option<String>,
    /// Set when the failure may be about mods, so the interface offers the
    /// recovery page and a launch without external mods. Not a diagnosis:
    /// recovery says what it can actually show.
    pub recovery: bool,
    /// True while (or after) a launch without external mods.
    pub safe: bool,
    /// Seconds since the game started, while it runs and after it ended.
    /// Whole seconds; the interface rounds it to minutes, so nothing ticks.
    pub elapsed_seconds: Option<u64>,
    /// The game's own reading of the session (`launcher_core::session_pulse`),
    /// when its module has written one. While running, `pulse_live` says
    /// whether it is recent; after the session, it is the summary.
    pub pulse: Option<SessionPulse>,
    /// Whether `pulse` was written in the last few seconds. False in a menu or
    /// with the window in the background, when the game has nothing new to say.
    pub pulse_live: bool,
    /// Which of the launch's steps this is, for the step line under Play:
    /// 0 check, 1 download, 2 start, 3 confirm the Fathom client, 4 running.
    /// A step that is not needed is passed over, never shown as work.
    pub step: u8,
}

impl LaunchProgress {
    fn new(phase: Phase, headline: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            phase,
            headline: headline.into(),
            detail: detail.into(),
            fraction: None,
            active: phase.is_active(),
            cancellable: phase.is_cancellable(),
            failure: None,
            recovery: false,
            safe: false,
            elapsed_seconds: None,
            pulse: None,
            pulse_live: false,
            step: match phase {
                Phase::Downloading | Phase::Unpacking => 1,
                Phase::Starting => 2,
                Phase::Running => 4,
                _ => 0,
            },
        }
    }

    fn idle() -> Self {
        Self::new(Phase::Idle, String::new(), String::new())
    }

    fn failed(message: impl Into<String>) -> Self {
        let message = message.into();
        let mut progress = Self::new(Phase::Failed, "Launch failed", message.clone());
        progress.failure = Some(message);
        progress
    }
}

impl Default for LaunchProgress {
    fn default() -> Self {
        Self::idle()
    }
}

/// The one launch in flight, if there is one.
///
/// Owns no launcher state. It is a place for a thread to write what it is doing
/// and a place for `cancel_launch` to raise a flag.
#[derive(Debug, Default)]
pub struct Supervisor {
    progress: Mutex<LaunchProgress>,
    cancel: Mutex<Cancel>,
}

impl Supervisor {
    pub fn progress(&self) -> LaunchProgress {
        self.progress
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_else(|_| LaunchProgress::failed("the launch supervisor is poisoned"))
    }

    pub(crate) fn set(&self, progress: LaunchProgress) {
        if let Ok(mut guard) = self.progress.lock() {
            *guard = progress;
        }
    }

    /// Claim the supervisor for a new launch.
    ///
    /// Refuses if one is already under way. This is the second of two guards:
    /// the core's own `RunningGames::claim` refuses a second process for the
    /// same profile, and this refuses a second *preparation*, which is the
    /// window a double-click actually lands in.
    pub(crate) fn begin(&self) -> Result<Cancel, String> {
        let mut progress = self
            .progress
            .lock()
            .map_err(|_| "the launch supervisor is poisoned")?;
        if progress.phase.is_active() {
            return Err("a launch is already under way".to_owned());
        }
        *progress = LaunchProgress::new(Phase::Preparing, "Preparing", "Checking the installation");

        let fresh = Cancel::new();
        let mut cancel = self
            .cancel
            .lock()
            .map_err(|_| "the launch supervisor is poisoned")?;
        *cancel = fresh.clone();
        Ok(fresh)
    }

    /// Ask the launch in flight to stop.
    ///
    /// Returns whether there was anything to stop. Raising the flag is all this
    /// does: the downloader notices between files, and the supervisor thread
    /// sets the final phase. Nothing is torn down from here, because the thread
    /// that owns the work is the only thing that knows what is half-written.
    pub(crate) fn request_cancel(&self) -> Result<bool, String> {
        let progress = self
            .progress
            .lock()
            .map_err(|_| "the launch supervisor is poisoned")?;
        if !progress.phase.is_cancellable() {
            return Ok(false);
        }
        drop(progress);

        if let Ok(cancel) = self.cancel.lock() {
            cancel.cancel();
        }
        Ok(true)
    }
}

// ---------------------------------------------------------------------------
// The stages, as sentences
// ---------------------------------------------------------------------------

impl LaunchProgress {
    /// Turn a download stage into something worth reading.
    ///
    /// Files and megabytes, not percentages alone: "412 of 771 - 84 MB"
    /// tells somebody whether to wait or make tea, and a bare 53% does not.
    pub(crate) fn from_stage(stage: &InstallStage) -> Self {
        match stage {
            InstallStage::ResolvingMetadata => Self::new(
                Phase::Preparing,
                "Preparing",
                "Reading the version manifest",
            ),
            InstallStage::FetchingAssetIndex => {
                Self::new(Phase::Preparing, "Preparing", "Reading the asset index")
            }
            InstallStage::Downloading(p) => {
                let mut progress = Self::new(
                    Phase::Downloading,
                    "Downloading",
                    format!(
                        "{} of {} \u{00B7} {}",
                        p.completed,
                        p.total,
                        launcher_core::services::human_bytes(p.bytes_transferred)
                    ),
                );
                // Guarded: `total` is zero for the moment between starting and
                // knowing how many files there are, and a division there would
                // be NaN, which serialises to `null` and renders as an empty
                // bar that flickers once at the start of every launch.
                if p.total > 0 {
                    #[allow(clippy::cast_precision_loss)]
                    let fraction = p.completed as f64 / p.total as f64;
                    progress.fraction = Some(fraction.clamp(0.0, 1.0));
                }
                progress
            }
            InstallStage::ExtractingNatives => {
                Self::new(Phase::Unpacking, "Unpacking", "Native libraries")
            }
            InstallStage::Verifying => {
                Self::new(Phase::Preparing, "Checking", "Verifying what is on disk")
            }
            InstallStage::Done => Self::starting(),
        }
    }

    /// Handing off to the core: authenticate, assemble, spawn.
    ///
    /// The last cancellable moment has passed by the time this is shown, which
    /// is why it is its own constructor rather than a stage -- the interface
    /// drops the Stop button on this transition.
    pub(crate) fn starting() -> Self {
        Self::new(Phase::Starting, "Starting", "Handing off to Minecraft")
    }
}

/// The process exists, but it is not called a Fathom session until the client
/// module proves that the expected jar completed initialization.
pub(crate) fn confirming_fathom() -> LaunchProgress {
    let mut progress = LaunchProgress::new(Phase::Starting, "Starting", "Confirming the Fathom client");
    progress.step = 3;
    progress
}

/// What the interface says while the game is up.
pub(crate) fn running(elapsed: Duration) -> LaunchProgress {
    let mut progress = LaunchProgress::new(
        Phase::Running,
        "Minecraft is running",
        elapsed_phrase(elapsed),
    );
    progress.elapsed_seconds = Some(elapsed.as_secs());
    progress
}

/// Attach the game's reading of the session, and how long it lasted.
pub(crate) fn with_session(
    mut progress: LaunchProgress,
    elapsed: Duration,
    pulse: Option<SessionPulse>,
    live: bool,
) -> LaunchProgress {
    progress.elapsed_seconds = Some(elapsed.as_secs());
    progress.pulse_live = live && pulse.is_some();
    progress.pulse = pulse;
    progress
}

/// A settled, finished launch.
pub(crate) fn finished(clean: bool, message: String) -> LaunchProgress {
    let mut progress = LaunchProgress::new(
        Phase::Finished,
        if clean {
            "Session ended"
        } else {
            "Minecraft stopped"
        },
        message.clone(),
    );
    if !clean {
        progress.failure = Some(message);
    }
    progress
}

pub(crate) fn cancelled() -> LaunchProgress {
    LaunchProgress::new(Phase::Cancelled, "Stopped", "Nothing was started")
}

pub(crate) fn failure(message: impl Into<String>) -> LaunchProgress {
    LaunchProgress::failed(message)
}

/// A failure the mod recovery page may be able to help with.
pub(crate) fn with_recovery(mut progress: LaunchProgress) -> LaunchProgress {
    progress.recovery = progress.failure.is_some();
    progress
}

/// Mark a progress snapshot as belonging to a launch without external mods.
pub(crate) fn safe(mut progress: LaunchProgress, safe: bool) -> LaunchProgress {
    progress.safe = safe;
    progress
}

/// How long the session has been up, in words.
///
/// Rounded on purpose. A seconds counter ticking on the launch screen is a
/// clock, and nobody pressed Play to watch a clock.
fn elapsed_phrase(elapsed: Duration) -> String {
    let seconds = elapsed.as_secs();
    if seconds < 90 {
        return "Started just now".to_owned();
    }
    let minutes = seconds / 60;
    if minutes < 60 {
        return format!(
            "Running for {minutes} {}",
            if minutes == 1 { "minute" } else { "minutes" }
        );
    }
    let hours = minutes / 60;
    let rest = minutes % 60;
    if rest == 0 {
        format!(
            "Running for {hours} {}",
            if hours == 1 { "hour" } else { "hours" }
        )
    } else {
        format!(
            "Running for {hours} {} {rest} min",
            if hours == 1 { "hour" } else { "hours" }
        )
    }
}

/// The moment a launch started, for the running phase's elapsed line.
///
/// A plain `Instant` rather than the process's own `started_at`, because that
/// one is a `SystemTime` and would go backwards if the clock changed under a
/// long session.
pub(crate) fn now() -> Instant {
    Instant::now()
}

/// The feature every network request in a launch is justified by.
///
/// Named here rather than written at four call sites so that the network
/// journal shows one purpose for the whole operation: a user reading the
/// Privacy screen after pressing Play should see "Play", not a mix of "Play"
/// and "Profile install" for the same button.
pub(crate) const PURPOSE: Feature = Feature::Play;

#[cfg(test)]
mod tests {
    use super::*;
    use launcher_core::install::download::Progress;

    #[test]
    fn only_the_stages_before_a_process_can_be_cancelled() {
        assert!(Phase::Preparing.is_cancellable());
        assert!(Phase::Downloading.is_cancellable());
        assert!(Phase::Unpacking.is_cancellable());
        // The line this file exists to hold: once the game is up, Stop is not
        // on offer, because it would mean killing it.
        assert!(!Phase::Starting.is_cancellable());
        assert!(!Phase::Running.is_cancellable());
        assert!(!Phase::Idle.is_cancellable());
    }

    #[test]
    fn a_settled_launch_is_not_active() {
        for phase in [
            Phase::Idle,
            Phase::Finished,
            Phase::Failed,
            Phase::Cancelled,
        ] {
            assert!(
                !phase.is_active(),
                "{phase:?} should not keep the poll alive"
            );
        }
        for phase in [
            Phase::Preparing,
            Phase::Downloading,
            Phase::Unpacking,
            Phase::Starting,
            Phase::Running,
        ] {
            assert!(phase.is_active(), "{phase:?} should keep the poll alive");
        }
    }

    /// The bug this guards: `completed / total` with a total of zero is NaN,
    /// which serde writes as `null` and the bar renders as a flicker.
    #[test]
    fn a_download_with_no_files_yet_has_no_fraction() {
        let progress = LaunchProgress::from_stage(&InstallStage::Downloading(Progress {
            completed: 0,
            total: 0,
            current: String::new(),
            bytes_transferred: 0,
        }));
        assert_eq!(progress.fraction, None);
    }

    #[test]
    fn a_download_reports_files_and_bytes_rather_than_a_bare_percentage() {
        let progress = LaunchProgress::from_stage(&InstallStage::Downloading(Progress {
            completed: 412,
            total: 771,
            current: "assets/objects/ab/abcd".to_owned(),
            bytes_transferred: 88_080_384,
        }));
        assert_eq!(progress.phase, Phase::Downloading);
        assert!(
            progress.detail.contains("412 of 771"),
            "{}",
            progress.detail
        );
        assert!(progress.detail.contains("MB"), "{}", progress.detail);
        assert!(progress.fraction.is_some_and(|f| (0.53..0.54).contains(&f)));
    }

    /// A failed launch must carry its reason in `failure`, not only in the
    /// detail line: the interface treats the two differently, and a launch that
    /// failed with an empty `failure` renders as if it had succeeded.
    #[test]
    fn a_failure_is_reported_as_one() {
        let progress = failure("no Java 8 runtime was found");
        assert_eq!(progress.phase, Phase::Failed);
        assert!(!progress.active);
        assert_eq!(
            progress.failure.as_deref(),
            Some("no Java 8 runtime was found")
        );
    }

    #[test]
    fn a_clean_exit_is_not_a_failure() {
        let clean = finished(true, "Minecraft exited normally.".to_owned());
        assert!(clean.failure.is_none());
        let crashed = finished(false, "Minecraft crashed (exit 1).".to_owned());
        assert!(crashed.failure.is_some());
    }

    #[test]
    fn a_supervisor_refuses_a_second_launch_while_one_is_under_way() {
        let supervisor = Supervisor::default();
        supervisor.begin().expect("the first launch");
        assert!(
            supervisor.begin().is_err(),
            "a double-click must not start two launches"
        );
    }

    /// And it lets go once the launch settles, or Play never works again.
    #[test]
    fn a_finished_launch_frees_the_supervisor() {
        let supervisor = Supervisor::default();
        supervisor.begin().expect("the first launch");
        supervisor.set(failure("something went wrong"));
        supervisor
            .begin()
            .expect("a second attempt after a failure");
    }

    #[test]
    fn cancelling_nothing_says_so_rather_than_erroring() {
        let supervisor = Supervisor::default();
        assert_eq!(supervisor.request_cancel(), Ok(false));
    }

    #[test]
    fn cancelling_raises_the_flag_the_downloader_watches() {
        let supervisor = Supervisor::default();
        let cancel = supervisor.begin().expect("a launch");
        assert!(!cancel.is_cancelled());
        assert_eq!(supervisor.request_cancel(), Ok(true));
        assert!(
            cancel.is_cancelled(),
            "the running work never saw the request"
        );
    }

    /// Cancelling after the process exists must not raise the flag: the flag is
    /// read by the *next* launch's downloader, and a stale cancellation would
    /// abort it before it began.
    #[test]
    fn cancelling_a_running_game_does_nothing() {
        let supervisor = Supervisor::default();
        let cancel = supervisor.begin().expect("a launch");
        supervisor.set(running(Duration::from_secs(5)));
        assert_eq!(supervisor.request_cancel(), Ok(false));
        assert!(!cancel.is_cancelled());
    }

    fn reading() -> SessionPulse {
        launcher_core::session_pulse::parse(
            "{\"schema\":1,\"updatedAt\":1,\"secondsOfPlay\":20,\"frames\":2000,             \"currentFps\":100.0,\"averageFps\":100.0,\"p95Ms\":10.0,\"p99Ms\":10.0,             \"worstMs\":10.0,\"hitches\":0,\"heapUsedMb\":512,\"heapMaxMb\":4096,             \"modules\":7,\"bucketSeconds\":10,\"timelineFps\":[100.0],             \"timelineWorstMs\":[10.0],\"marks\":[]}",
        )
        .expect("a reading")
    }

    /// The steps under Play follow the phases, and confirming the Fathom
    /// client is its own step rather than more of "Starting".
    #[test]
    fn each_phase_names_its_step() {
        assert_eq!(LaunchProgress::from_stage(&InstallStage::ResolvingMetadata).step, 0);
        assert_eq!(LaunchProgress::from_stage(&InstallStage::ExtractingNatives).step, 1);
        assert_eq!(LaunchProgress::starting().step, 2);
        assert_eq!(confirming_fathom().step, 3);
        assert_eq!(running(Duration::from_secs(1)).step, 4);
    }

    #[test]
    fn a_running_session_carries_its_time_and_reading() {
        let live = with_session(
            running(Duration::from_secs(600)),
            Duration::from_secs(600),
            Some(reading()),
            true,
        );
        assert_eq!(live.elapsed_seconds, Some(600));
        assert!(live.pulse_live);
        assert_eq!(live.pulse.as_ref().map(|p| p.frames), Some(2000));
        // No reading yet: never called live, whatever the caller says.
        let early = with_session(running(Duration::from_secs(5)), Duration::from_secs(5), None, true);
        assert!(!early.pulse_live);
    }

    /// The summary outlives the session: a crash keeps both its failure and
    /// the game's last reading, so Home can show what it was doing.
    #[test]
    fn a_finished_session_keeps_its_summary() {
        let ended = with_session(
            with_recovery(finished(false, "Minecraft crashed (exit 1).".to_owned())),
            Duration::from_secs(300),
            Some(reading()),
            false,
        );
        assert!(ended.failure.is_some());
        assert!(ended.recovery);
        assert_eq!(ended.elapsed_seconds, Some(300));
        assert!(ended.pulse.is_some());
        assert!(!ended.active);
    }

    #[test]
    fn elapsed_time_is_rounded_rather_than_ticking() {
        assert_eq!(elapsed_phrase(Duration::from_secs(3)), "Started just now");
        assert_eq!(elapsed_phrase(Duration::from_secs(89)), "Started just now");
        assert_eq!(
            elapsed_phrase(Duration::from_secs(100)),
            "Running for 1 minute"
        );
        assert_eq!(
            elapsed_phrase(Duration::from_secs(600)),
            "Running for 10 minutes"
        );
        assert_eq!(
            elapsed_phrase(Duration::from_secs(3600)),
            "Running for 1 hour"
        );
        assert_eq!(
            elapsed_phrase(Duration::from_secs(9000)),
            "Running for 2 hours 30 min"
        );
    }
}
