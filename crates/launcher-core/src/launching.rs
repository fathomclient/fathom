//! Turning "press Play" into a running game, and refusing clearly when it
//! cannot.
//!
//! Two things live here:
//!
//! - [`LaunchRefusal`], the reasons a launch does not happen. Each one names
//!   something the user can act on. "Cannot launch" without saying why is not
//!   worth showing anyone, and a launcher that lumps *your session expired* in
//!   with *a library is missing* sends people to the wrong screen.
//! - [`RunningGames`], which knows what is already running. Its job is to stop
//!   the same profile being started twice, because two JVMs writing the same
//!   `level.dat` and the same `options.txt` is how worlds get corrupted.
//!
//! Failures **before** the process starts are refusals and live here. Failures
//! **after** it starts are [`crate::minecraft_runtime::process::Outcome`]s and
//! live with the process. The split matters: a refusal means nothing happened,
//! so nothing needs cleaning up.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::SystemTime;

use crate::auth::session::LaunchDenied;
use crate::minecraft_runtime::jvm_args::ArgRejection;
use crate::minecraft_runtime::launch::LaunchError;
use crate::minecraft_runtime::process::{GameProcess, Outcome, ProcessError, RunState};
use crate::profiles::{Loader, ProfileId};
use crate::services::InstallSnapshot;

// ---------------------------------------------------------------------------
// Refusals
// ---------------------------------------------------------------------------

/// Why a launch did not start.
///
/// Every variant is a distinct thing to do about it, which is the point.
#[derive(Debug, thiserror::Error)]
pub enum LaunchRefusal {
    /// The authentication gate said no. Carries the gate's own reason, so an
    /// expired token and a missing entitlement stay distinguishable.
    #[error("{0}")]
    NotAuthorised(#[from] LaunchDenied),

    /// The profile asks for a loader that is not built yet.
    #[error("{} support is not implemented yet", .0.label())]
    LoaderNotImplemented(Loader),

    /// The loader is implemented, but this particular install of it could not
    /// be obtained -- no build for this Minecraft version, the metadata service
    /// unreachable, or an artifact that failed verification.
    ///
    /// Deliberately distinct from `LoaderNotImplemented`. "We never built this"
    /// and "we could not reach the server just now" call for completely
    /// different reactions from the person reading it, and collapsing them into
    /// one message would send somebody to check their internet connection over
    /// a feature that does not exist, or vice versa.
    #[error("the loader could not be installed: {0}")]
    LoaderUnavailable(String),

    /// The profile's own directories could not be prepared.
    #[error("this profile is not usable: {0}")]
    InvalidProfile(String),

    /// A component is installed that cannot work on its own under this loader,
    /// and the thing it needs is not there.
    ///
    /// This exists for OptiFine. Dropping its jar into a Legacy Fabric profile
    /// produces one of two outcomes without this check: the loader ignores it
    /// entirely and the player is left wondering why nothing changed, or -- if
    /// something does pick it up -- Minecraft dies during class transformation
    /// with a stack trace nobody outside this project can read. Refusing before
    /// the process starts, and naming the missing piece, is the only version of
    /// this that a player can act on.
    #[error(
        "{component} needs {bridge} to run under {loader}, and {bridge} is not installed.          Add it from the Mods page, or remove {component}."
    )]
    MissingBridge {
        component: &'static str,
        bridge: &'static str,
        loader: &'static str,
    },

    /// No Java at all.
    #[error("no Java {required_major} runtime was found")]
    NoJava { required_major: u32 },

    /// Java, but the wrong one.
    #[error("{explanation}")]
    IncompatibleJava {
        required_major: u32,
        found_major: u32,
        explanation: String,
    },

    /// A Java path that was recorded earlier and has since gone.
    #[error("the Java executable at {0} is missing")]
    JavaMissing(String),

    /// Files are absent or wrong. Carries the snapshot so the caller can say
    /// how much work a repair is.
    #[error("{}", .0.message())]
    InstallationIncomplete(InstallSnapshot),

    /// Natives could not be made current.
    #[error("the native libraries could not be prepared: {0}")]
    NativesUnavailable(String),

    /// The profile's own JVM arguments do not pass policy. Refused rather than
    /// silently dropped, because a user who typed a flag should be told it was
    /// not used.
    #[error("{} JVM argument(s) were rejected", .0.len())]
    BadJvmArguments(Vec<(String, ArgRejection)>),

    /// This profile is already running.
    #[error("Minecraft is already running for this profile (pid {pid})")]
    AlreadyRunning { pid: u32, since: SystemTime },

    /// The plan itself could not be assembled.
    #[error(transparent)]
    Build(#[from] LaunchError),

    /// The process would not start.
    #[error(transparent)]
    Process(#[from] ProcessError),

    /// A Fathom profile whose client module cannot be supplied, placed or
    /// verified. Refused rather than launched, because launched it is vanilla.
    #[error("{0}")]
    FathomClientUnavailable(String),

    /// The profile's game directory is the user's own Minecraft folder.
    #[error(
        "this profile's game directory is your normal Minecraft folder ({}). Fathom launches \
         its own isolated instance and does not modify that folder; choose another directory \
         or use the managed one",
        .0.display()
    )]
    SharedMinecraftDirectory(std::path::PathBuf),
}

impl LaunchRefusal {
    /// Whether repairing the installation is a plausible fix.
    pub fn suggests_repair(&self) -> bool {
        matches!(
            self,
            LaunchRefusal::InstallationIncomplete(_) | LaunchRefusal::NativesUnavailable(_)
        )
    }

    /// Whether signing in again is a plausible fix.
    pub fn suggests_sign_in(&self) -> bool {
        matches!(
            self,
            LaunchRefusal::NotAuthorised(
                LaunchDenied::SignedOut | LaunchDenied::TokenExpired | LaunchDenied::InProgress
            )
        )
    }

    /// A stable short name, for logs and for tests that assert on the class of
    /// failure rather than its wording.
    pub fn kind(&self) -> &'static str {
        match self {
            LaunchRefusal::NotAuthorised(_) => "not-authorised",
            LaunchRefusal::LoaderNotImplemented(_) => "loader-not-implemented",
            LaunchRefusal::LoaderUnavailable(_) => "loader-unavailable",
            LaunchRefusal::InvalidProfile(_) => "invalid-profile",
            LaunchRefusal::MissingBridge { .. } => "missing-bridge",
            LaunchRefusal::NoJava { .. } => "no-java",
            LaunchRefusal::IncompatibleJava { .. } => "incompatible-java",
            LaunchRefusal::JavaMissing(_) => "java-missing",
            LaunchRefusal::InstallationIncomplete(_) => "installation-incomplete",
            LaunchRefusal::NativesUnavailable(_) => "natives-unavailable",
            LaunchRefusal::BadJvmArguments(_) => "bad-jvm-arguments",
            LaunchRefusal::AlreadyRunning { .. } => "already-running",
            LaunchRefusal::Build(_) => "plan-build-failed",
            LaunchRefusal::Process(_) => "spawn-failed",
            LaunchRefusal::FathomClientUnavailable(_) => "fathom-client-unavailable",
            LaunchRefusal::SharedMinecraftDirectory(_) => "shared-minecraft-directory",
        }
    }
}

// ---------------------------------------------------------------------------
// What is running
// ---------------------------------------------------------------------------

/// A copyable view of a game process, safe to hand to a UI.
///
/// The `GameProcess` itself stays inside the registry, because there must be
/// exactly one owner reaping a child.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GameStatus {
    pub profile: ProfileId,
    pub pid: u32,
    pub state: RunState,
    pub started_at: SystemTime,
    pub stopped_at: Option<SystemTime>,
    pub exit_code: Option<i32>,
    pub outcome: Outcome,
    pub log_file: std::path::PathBuf,
}

impl GameStatus {
    pub fn is_running(&self) -> bool {
        self.state == RunState::Running
    }

    pub fn elapsed(&self) -> std::time::Duration {
        self.stopped_at
            .unwrap_or_else(SystemTime::now)
            .duration_since(self.started_at)
            .unwrap_or_default()
    }
}

/// A profile's entry in the registry.
enum Slot {
    /// A launch is being prepared. Holds the place so a second attempt is
    /// refused even before the process exists.
    Starting,
    Running(Box<GameProcess>),
}

/// Which profiles are running.
///
/// Cheap to clone; every clone shares one map. Held by [`crate::Launcher`] and
/// by whatever thread performs a launch.
#[derive(Debug, Clone, Default)]
pub struct RunningGames {
    inner: Arc<Mutex<BTreeMap<ProfileId, Slot>>>,
}

impl std::fmt::Debug for Slot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Slot::Starting => f.write_str("Starting"),
            Slot::Running(p) => write!(f, "Running(pid {})", p.pid()),
        }
    }
}

impl RunningGames {
    pub fn new() -> Self {
        Self::default()
    }

    /// A poisoned lock means a thread panicked while holding it. The map is
    /// still structurally valid -- it is a `BTreeMap` of handles -- so
    /// recovering is better than propagating a panic into every later launch.
    fn lock(&self) -> MutexGuard<'_, BTreeMap<ProfileId, Slot>> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Reserve a profile for launching.
    ///
    /// This is the whole duplicate-launch defence, and it is a *claim* rather
    /// than a check because a check would race: two threads could both see
    /// "not running" before either had spawned anything. The place in the map
    /// is taken under the same lock that observed it was free.
    ///
    /// The returned guard releases the claim if it is dropped without
    /// [`LaunchClaim::fulfil`] being called, so a refused or failed launch does
    /// not leave a profile permanently unlaunchable.
    pub fn claim(&self, profile: &ProfileId) -> Result<LaunchClaim, LaunchRefusal> {
        let mut map = self.lock();

        // Reap first: a profile whose game has exited is free again, and the
        // user should not have to restart the launcher to discover that.
        if let Some(Slot::Running(process)) = map.get_mut(profile) {
            if matches!(process.poll(), Ok(RunState::Exited)) {
                map.remove(profile);
            }
        }

        match map.get(profile) {
            Some(Slot::Running(process)) => {
                return Err(LaunchRefusal::AlreadyRunning {
                    pid: process.pid(),
                    since: process.started_at(),
                })
            }
            Some(Slot::Starting) => {
                return Err(LaunchRefusal::AlreadyRunning {
                    pid: 0,
                    since: SystemTime::now(),
                })
            }
            None => {}
        }

        map.insert(profile.clone(), Slot::Starting);
        Ok(LaunchClaim {
            games: self.clone(),
            profile: profile.clone(),
            fulfilled: false,
        })
    }

    /// Current status of one profile, reaping it if it has exited.
    pub fn status(&self, profile: &ProfileId) -> Option<GameStatus> {
        let mut map = self.lock();
        let Some(Slot::Running(process)) = map.get_mut(profile) else {
            return None;
        };
        let _ = process.poll();
        Some(status_of(profile, process))
    }

    /// Every profile with a live or just-finished game.
    pub fn all(&self) -> Vec<GameStatus> {
        let mut map = self.lock();
        let mut out = Vec::new();
        for (profile, slot) in map.iter_mut() {
            if let Slot::Running(process) = slot {
                let _ = process.poll();
                out.push(status_of(profile, process));
            }
        }
        out
    }

    pub fn is_running(&self, profile: &ProfileId) -> bool {
        self.status(profile).is_some_and(|s| s.is_running())
    }

    /// Drop the entries for games that have finished, returning their last
    /// status so the caller can report the exit.
    pub fn sweep(&self) -> Vec<GameStatus> {
        let mut map = self.lock();
        let mut finished = Vec::new();
        let mut done: Vec<ProfileId> = Vec::new();

        for (profile, slot) in map.iter_mut() {
            if let Slot::Running(process) = slot {
                if matches!(process.poll(), Ok(RunState::Exited)) {
                    finished.push(status_of(profile, process));
                    done.push(profile.clone());
                }
            }
        }
        for profile in done {
            map.remove(&profile);
        }
        finished
    }

    /// Ask a profile's game to stop. Only ever called because a user asked.
    pub fn terminate(&self, profile: &ProfileId) -> Result<Option<GameStatus>, ProcessError> {
        let mut map = self.lock();
        let Some(Slot::Running(process)) = map.get_mut(profile) else {
            return Ok(None);
        };
        process.terminate()?;
        let status = status_of(profile, process);
        map.remove(profile);
        Ok(Some(status))
    }

    fn release(&self, profile: &ProfileId) {
        let mut map = self.lock();
        if matches!(map.get(profile), Some(Slot::Starting)) {
            map.remove(profile);
        }
    }

    fn install(&self, profile: &ProfileId, process: GameProcess) {
        self.lock()
            .insert(profile.clone(), Slot::Running(Box::new(process)));
    }
}

fn status_of(profile: &ProfileId, process: &GameProcess) -> GameStatus {
    GameStatus {
        profile: profile.clone(),
        pid: process.pid(),
        state: process.state(),
        started_at: process.started_at(),
        stopped_at: process.stopped_at(),
        exit_code: process.exit_code(),
        outcome: process.outcome(),
        log_file: process.log_file().to_path_buf(),
    }
}

/// A reserved place in [`RunningGames`].
///
/// Exists so that the window between "decided to launch" and "process exists"
/// is covered. Drop it without fulfilling and the reservation goes away.
#[must_use = "dropping a claim without fulfilling it releases the profile"]
pub struct LaunchClaim {
    games: RunningGames,
    profile: ProfileId,
    fulfilled: bool,
}

impl LaunchClaim {
    pub fn profile(&self) -> &ProfileId {
        &self.profile
    }

    /// Hand the started process to the registry, which owns it from here.
    pub fn fulfil(mut self, process: GameProcess) -> GameStatus {
        let status = status_of(&self.profile, &process);
        self.games.install(&self.profile, process);
        self.fulfilled = true;
        status
    }
}

impl Drop for LaunchClaim {
    fn drop(&mut self) {
        if !self.fulfilled {
            self.games.release(&self.profile);
        }
    }
}

impl std::fmt::Debug for LaunchClaim {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LaunchClaim")
            .field("profile", &self.profile)
            .field("fulfilled", &self.fulfilled)
            .finish_non_exhaustive()
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

    fn id(name: &str) -> ProfileId {
        ProfileId::new(name).expect("valid id")
    }

    #[test]
    fn a_free_profile_can_be_claimed() {
        let games = RunningGames::new();
        let claim = games
            .claim(&id("main"))
            .expect("first claim should succeed");
        assert_eq!(claim.profile(), &id("main"));
    }

    /// The property the whole type exists for.
    #[test]
    fn a_profile_cannot_be_claimed_twice() {
        let games = RunningGames::new();
        let _first = games.claim(&id("main")).expect("first");
        let second = games.claim(&id("main"));
        assert!(
            matches!(second, Err(LaunchRefusal::AlreadyRunning { .. })),
            "{second:?}"
        );
    }

    #[test]
    fn a_different_profile_is_unaffected() {
        let games = RunningGames::new();
        let _first = games.claim(&id("main")).expect("first");
        let _other = games
            .claim(&id("other"))
            .expect("a different profile is free");
    }

    /// A refused launch must not leave the profile stuck. This is the reason
    /// the claim is a guard rather than a plain insert.
    #[test]
    fn dropping_an_unfulfilled_claim_frees_the_profile() {
        let games = RunningGames::new();
        {
            let _claim = games.claim(&id("main")).expect("first");
            assert!(games.claim(&id("main")).is_err(), "held while alive");
        }
        let _reclaimed = games
            .claim(&id("main"))
            .expect("the profile should be free again after the claim is dropped");
    }

    /// Simulates the race the claim is designed to lose safely: many threads
    /// attempting the same profile at once. Exactly one may win.
    #[test]
    fn only_one_of_many_simultaneous_attempts_wins() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        let games = RunningGames::new();
        let winners = Arc::new(AtomicUsize::new(0));
        let barrier = Arc::new(std::sync::Barrier::new(16));

        let handles: Vec<_> = (0..16)
            .map(|_| {
                let games = games.clone();
                let winners = Arc::clone(&winners);
                let barrier = Arc::clone(&barrier);
                std::thread::spawn(move || {
                    barrier.wait();
                    if let Ok(claim) = games.claim(&id("contested")) {
                        winners.fetch_add(1, Ordering::SeqCst);
                        // Hold it, so no one else can take it either.
                        std::thread::sleep(std::time::Duration::from_millis(80));
                        drop(claim);
                    }
                })
            })
            .collect();

        for h in handles {
            h.join().expect("no thread should panic");
        }
        assert_eq!(
            winners.load(Ordering::SeqCst),
            1,
            "exactly one attempt may claim the profile"
        );
    }

    #[test]
    fn nothing_is_running_in_a_fresh_registry() {
        let games = RunningGames::new();
        assert!(!games.is_running(&id("main")));
        assert!(games.status(&id("main")).is_none());
        assert!(games.all().is_empty());
        assert!(games.sweep().is_empty());
    }

    /// A claim in progress is not a running game: there is no process yet, so
    /// there is no status to report.
    #[test]
    fn a_pending_claim_is_not_reported_as_running() {
        let games = RunningGames::new();
        let _claim = games.claim(&id("main")).expect("claim");
        assert!(!games.is_running(&id("main")));
        assert!(games.status(&id("main")).is_none());
    }

    #[test]
    fn terminating_a_profile_that_is_not_running_is_not_an_error() {
        let games = RunningGames::new();
        assert!(games.terminate(&id("main")).expect("no error").is_none());
    }

    // -- refusals ----------------------------------------------------------

    #[test]
    fn every_refusal_has_a_distinct_kind_and_a_message() {
        let refusals = vec![
            LaunchRefusal::NotAuthorised(LaunchDenied::TokenExpired),
            LaunchRefusal::LoaderNotImplemented(Loader::LegacyFabric),
            LaunchRefusal::InvalidProfile("no directory".into()),
            LaunchRefusal::NoJava { required_major: 8 },
            LaunchRefusal::IncompatibleJava {
                required_major: 8,
                found_major: 21,
                explanation: "1.8.9 needs Java 8".into(),
            },
            LaunchRefusal::JavaMissing("/gone/java".into()),
            LaunchRefusal::NativesUnavailable("empty".into()),
            LaunchRefusal::BadJvmArguments(vec![]),
            LaunchRefusal::AlreadyRunning {
                pid: 42,
                since: SystemTime::now(),
            },
            LaunchRefusal::Build(LaunchError::NoArguments),
        ];

        let mut kinds: Vec<&str> = refusals.iter().map(LaunchRefusal::kind).collect();
        let before = kinds.len();
        kinds.sort_unstable();
        kinds.dedup();
        assert_eq!(kinds.len(), before, "each refusal needs its own kind");

        for refusal in &refusals {
            assert!(!refusal.to_string().is_empty(), "{refusal:?}");
        }
    }

    /// The two questions the interface actually asks of a refusal.
    #[test]
    fn refusals_point_at_the_right_remedy() {
        assert!(LaunchRefusal::NotAuthorised(LaunchDenied::TokenExpired).suggests_sign_in());
        assert!(LaunchRefusal::NotAuthorised(LaunchDenied::SignedOut).suggests_sign_in());
        assert!(LaunchRefusal::NativesUnavailable("x".into()).suggests_repair());

        // A rejected flag is the user's own configuration, not a broken file.
        let bad = LaunchRefusal::BadJvmArguments(vec![]);
        assert!(!bad.suggests_repair());
        assert!(!bad.suggests_sign_in());

        // Neither is a missing Java runtime.
        let no_java = LaunchRefusal::NoJava { required_major: 8 };
        assert!(!no_java.suggests_repair());
        assert!(!no_java.suggests_sign_in());
    }

    /// An expired token must not be reported as something a repair fixes, and
    /// a missing library must not be reported as something signing in fixes.
    #[test]
    fn authentication_and_file_problems_never_overlap() {
        let auth = LaunchRefusal::NotAuthorised(LaunchDenied::TokenExpired);
        assert!(!auth.suggests_repair());
        let files = LaunchRefusal::NativesUnavailable("x".into());
        assert!(!files.suggests_sign_in());
    }
}
