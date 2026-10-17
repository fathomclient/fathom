//! Signing in, as the interface sees it.
//!
//! # Why this looks like `launch`
//!
//! Same shape, same reasons. Signing in is a device-code flow: Fathom asks
//! Microsoft for a short code, shows it, and then polls — for as long as it
//! takes somebody to pick up a phone, open a page and type eight characters.
//! That is not a request/response command, so it is a supervisor with a
//! progress cell, started by one command and polled by another.
//!
//! # What is and is not here
//!
//! No token, ever. The code on screen is a *device* code: it is worthless
//! without the account holder typing it into Microsoft's own page, it is shown
//! deliberately, and it is not a credential of the user's. The refresh token
//! that comes out the far end goes straight into the OS credential store from
//! Rust, and the interface is told only a display name.
//!
//! # The rule this file must not break
//!
//! **A development identity is never allowed to look like a real one.** It is
//! compiled out entirely unless the `dev-auth` feature is on, it is refused
//! unless the environment variable is also set, and every view that mentions an
//! identity carries a `development` flag that the interface shows permanently.
//! Production Play stays unavailable until a real sign-in succeeds.

use std::sync::Mutex;

use serde::Serialize;

use launcher_core::tasks::Cancel;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum AuthPhase {
    /// Nothing happening.
    Idle,
    /// Trying the stored refresh token at start-up.
    Restoring,
    /// Asking Microsoft for a code.
    Starting,
    /// The code is on screen and we are waiting for the user to use it.
    Waiting,
    /// They used it; we are exchanging it and checking the entitlement.
    Verifying,
    /// Done.
    SignedIn,
    Failed,
    Cancelled,
}

impl AuthPhase {
    pub fn is_active(self) -> bool {
        matches!(
            self,
            AuthPhase::Restoring | AuthPhase::Starting | AuthPhase::Waiting | AuthPhase::Verifying
        )
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthProgress {
    pub phase: AuthPhase,
    pub headline: String,
    pub detail: String,
    /// The short code to type. Present only while `Waiting`.
    pub user_code: Option<String>,
    /// The page to type it on. Opened in the default browser when the code
    /// arrives — only after `pages::microsoft_sign_in_url` has confirmed it is
    /// Microsoft's own page — and shown as text as well, so the flow still
    /// works on a machine where no browser opens.
    pub verification_uri: Option<String>,
    /// Whether the browser was opened for the user.
    pub browser_opened: bool,
    /// Whether the user code was placed on the system clipboard before the
    /// browser opened.
    pub code_copied: bool,
    pub active: bool,
    pub failure: Option<String>,
}

impl AuthProgress {
    fn new(phase: AuthPhase, headline: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            phase,
            headline: headline.into(),
            detail: detail.into(),
            user_code: None,
            verification_uri: None,
            browser_opened: false,
            code_copied: false,
            active: phase.is_active(),
            failure: None,
        }
    }

    pub(crate) fn idle() -> Self {
        Self::new(AuthPhase::Idle, String::new(), String::new())
    }

    pub(crate) fn restoring() -> Self {
        Self::new(
            AuthPhase::Restoring,
            "Restoring your session",
            "Checking the saved sign-in",
        )
    }

    pub(crate) fn starting() -> Self {
        Self::new(
            AuthPhase::Starting,
            "Contacting Microsoft",
            "Requesting a code",
        )
    }

    /// The code, and where to type it.
    pub(crate) fn waiting(code: &str, uri: &str, browser_opened: bool, code_copied: bool) -> Self {
        let mut progress = if browser_opened {
            Self::new(
                AuthPhase::Waiting,
                "Continue in your browser",
                "Sign in on Microsoft's page. Fathom continues by itself when you are done.",
            )
        } else {
            Self::new(
                AuthPhase::Waiting,
                "Enter this code",
                "Sign in on Microsoft's page, then come back",
            )
        };
        progress.user_code = Some(code.to_owned());
        progress.verification_uri = Some(uri.to_owned());
        progress.browser_opened = browser_opened;
        progress.code_copied = code_copied;
        progress
    }

    pub(crate) fn verifying() -> Self {
        Self::new(
            AuthPhase::Verifying,
            "Checking your account",
            "Confirming it owns Minecraft",
        )
    }

    pub(crate) fn signed_in(name: &str) -> Self {
        Self::new(
            AuthPhase::SignedIn,
            "Signed in",
            format!("Playing as {name}"),
        )
    }

    pub(crate) fn failed(message: impl Into<String>) -> Self {
        let message = message.into();
        let mut progress = Self::new(AuthPhase::Failed, "Sign-in failed", message.clone());
        progress.failure = Some(message);
        progress
    }

    pub(crate) fn cancelled() -> Self {
        Self::new(AuthPhase::Cancelled, "Cancelled", "Nothing was signed in")
    }

    /// A saved session that did not come back within `signin::RESTORE_LIMIT`.
    ///
    /// Settled rather than active, so the Sign In button is live again: the
    /// person is told to sign in instead of watching "Restoring" indefinitely.
    pub(crate) fn restore_timed_out() -> Self {
        let message = "Restoring your saved session is taking too long. Sign in again to continue.";
        let mut progress = Self::new(AuthPhase::Failed, "Sign in again", message);
        progress.failure = Some(message.to_owned());
        progress
    }
}

impl Default for AuthProgress {
    fn default() -> Self {
        Self::idle()
    }
}

/// The one sign-in in flight, if there is one.
#[derive(Debug, Default)]
pub struct Supervisor {
    progress: Mutex<AuthProgress>,
    cancel: Mutex<Cancel>,
    /// Which attempt is current. Bumped by every `begin`, so the result of an
    /// attempt that was overtaken -- a restore that answered after its time
    /// ran out and the person started a new sign-in -- cannot overwrite the
    /// attempt that replaced it.
    generation: std::sync::atomic::AtomicU64,
}

impl Supervisor {
    pub fn progress(&self) -> AuthProgress {
        self.progress
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_else(|_| AuthProgress::failed("the sign-in supervisor is poisoned"))
    }

    pub(crate) fn set(&self, progress: AuthProgress) {
        if let Ok(mut guard) = self.progress.lock() {
            *guard = progress;
        }
    }

    /// The current attempt's number (see `generation`).
    pub(crate) fn generation(&self) -> u64 {
        self.generation.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// Settle attempt `generation`, unless a newer one has begun since.
    pub(crate) fn set_if_current(&self, generation: u64, progress: AuthProgress) -> bool {
        let Ok(mut guard) = self.progress.lock() else {
            return false;
        };
        if self.generation() != generation {
            return false;
        }
        *guard = progress;
        true
    }

    /// Give up on attempt `generation` if it is still restoring: settle it as
    /// timed out and ask it to stop. Returns whether it did.
    pub(crate) fn expire_restore(&self, generation: u64) -> bool {
        let Ok(mut guard) = self.progress.lock() else {
            return false;
        };
        if self.generation() != generation || guard.phase != AuthPhase::Restoring {
            return false;
        }
        *guard = AuthProgress::restore_timed_out();
        drop(guard);
        if let Ok(cancel) = self.cancel.lock() {
            cancel.cancel();
        }
        true
    }

    /// Claim the supervisor, refusing a second concurrent attempt.
    pub(crate) fn begin(&self, initial: AuthProgress) -> Result<Cancel, String> {
        let mut progress = self
            .progress
            .lock()
            .map_err(|_| "the sign-in supervisor is poisoned")?;
        if progress.phase.is_active() {
            return Err("a sign-in is already under way".to_owned());
        }
        *progress = initial;
        self.generation
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);

        let fresh = Cancel::new();
        let mut cancel = self
            .cancel
            .lock()
            .map_err(|_| "the sign-in supervisor is poisoned")?;
        *cancel = fresh.clone();
        Ok(fresh)
    }

    /// Abandon the attempt in flight.
    ///
    /// Unlike a launch, a sign-in is cancellable right up to the end: nothing
    /// has been written and nothing is running. The device code simply expires.
    pub(crate) fn request_cancel(&self) -> Result<bool, String> {
        let progress = self
            .progress
            .lock()
            .map_err(|_| "the sign-in supervisor is poisoned")?;
        if !progress.phase.is_active() {
            return Ok(false);
        }
        drop(progress);

        if let Ok(cancel) = self.cancel.lock() {
            cancel.cancel();
        }
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A restore still running at the limit is settled as "sign in again",
    /// with the Sign In button live, and asked to stop.
    #[test]
    fn a_restore_past_its_limit_asks_for_a_new_sign_in() {
        let supervisor = Supervisor::default();
        let cancel = supervisor.begin(AuthProgress::restoring()).unwrap();
        let attempt = supervisor.generation();

        assert!(supervisor.expire_restore(attempt));
        let progress = supervisor.progress();
        assert_eq!(progress.phase, AuthPhase::Failed);
        assert!(!progress.active, "the Sign In button must be usable again");
        assert!(progress.failure.unwrap().contains("Sign in again"));
        assert!(cancel.is_cancelled(), "the slow restore is told to stop");
    }

    /// The watchdog leaves alone a restore that already finished, and any
    /// attempt that is not the one it was watching.
    #[test]
    fn the_watchdog_only_expires_the_restore_it_watched() {
        let supervisor = Supervisor::default();
        supervisor.begin(AuthProgress::restoring()).unwrap();
        let first = supervisor.generation();
        supervisor.set_if_current(first, AuthProgress::signed_in("Steve"));
        assert!(
            !supervisor.expire_restore(first),
            "a finished restore stays finished"
        );
        assert_eq!(supervisor.progress().phase, AuthPhase::SignedIn);

        supervisor.begin(AuthProgress::starting()).unwrap();
        assert!(
            !supervisor.expire_restore(first),
            "a newer attempt is not touched"
        );
        assert_eq!(supervisor.progress().phase, AuthPhase::Starting);
    }

    /// A late answer from an overtaken attempt cannot overwrite its replacement.
    #[test]
    fn an_overtaken_attempt_cannot_overwrite_the_current_one() {
        let supervisor = Supervisor::default();
        supervisor.begin(AuthProgress::restoring()).unwrap();
        let old = supervisor.generation();
        supervisor.expire_restore(old);
        supervisor.begin(AuthProgress::starting()).unwrap();

        assert!(!supervisor.set_if_current(old, AuthProgress::signed_in("Late")));
        assert_eq!(supervisor.progress().phase, AuthPhase::Starting);
    }

    #[test]
    fn only_an_unsettled_sign_in_keeps_the_poll_alive() {
        for phase in [
            AuthPhase::Idle,
            AuthPhase::SignedIn,
            AuthPhase::Failed,
            AuthPhase::Cancelled,
        ] {
            assert!(!phase.is_active(), "{phase:?}");
        }
        for phase in [
            AuthPhase::Restoring,
            AuthPhase::Starting,
            AuthPhase::Waiting,
            AuthPhase::Verifying,
        ] {
            assert!(phase.is_active(), "{phase:?}");
        }
    }

    /// The code and the page are shown together or not at all: a code with
    /// nowhere to type it is not an instruction.
    #[test]
    fn the_code_and_the_page_arrive_together() {
        let progress = AuthProgress::waiting("ABCD-EFGH", "https://microsoft.com/link", true, true);
        assert_eq!(progress.user_code.as_deref(), Some("ABCD-EFGH"));
        assert_eq!(
            progress.verification_uri.as_deref(),
            Some("https://microsoft.com/link")
        );

        // And every other phase carries neither, so a stale code cannot linger
        // on screen after the moment it was useful.
        for progress in [
            AuthProgress::idle(),
            AuthProgress::starting(),
            AuthProgress::verifying(),
            AuthProgress::signed_in("Steve"),
            AuthProgress::failed("no"),
            AuthProgress::cancelled(),
        ] {
            assert!(progress.user_code.is_none(), "{:?}", progress.phase);
            assert!(progress.verification_uri.is_none(), "{:?}", progress.phase);
        }
    }

    #[test]
    fn a_failure_is_reported_as_one() {
        let progress = AuthProgress::failed("that account does not own Minecraft");
        assert_eq!(progress.phase, AuthPhase::Failed);
        assert!(!progress.active);
        assert!(progress.failure.is_some());
    }

    #[test]
    fn a_second_attempt_is_refused_while_one_is_under_way() {
        let supervisor = Supervisor::default();
        supervisor
            .begin(AuthProgress::starting())
            .expect("the first attempt");
        assert!(supervisor.begin(AuthProgress::starting()).is_err());
    }

    #[test]
    fn a_settled_attempt_frees_the_supervisor() {
        let supervisor = Supervisor::default();
        supervisor.begin(AuthProgress::starting()).expect("first");
        supervisor.set(AuthProgress::cancelled());
        supervisor.begin(AuthProgress::starting()).expect("second");
    }

    #[test]
    fn cancelling_raises_the_flag_the_poll_loop_watches() {
        let supervisor = Supervisor::default();
        let cancel = supervisor
            .begin(AuthProgress::starting())
            .expect("an attempt");
        assert_eq!(supervisor.request_cancel(), Ok(true));
        assert!(cancel.is_cancelled());
    }

    #[test]
    fn cancelling_nothing_says_so_rather_than_erroring() {
        let supervisor = Supervisor::default();
        assert_eq!(supervisor.request_cancel(), Ok(false));
    }
}
