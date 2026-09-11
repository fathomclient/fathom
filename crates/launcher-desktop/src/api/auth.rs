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
    /// The page to type it on. Shown as text, never opened for the user: a
    /// launcher that opens a browser by itself, at a URL the user did not read,
    /// is teaching exactly the habit that makes phishing work.
    pub verification_uri: Option<String>,
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
        Self::new(AuthPhase::Starting, "Contacting Microsoft", "Requesting a code")
    }

    /// The code, and where to type it.
    pub(crate) fn waiting(code: &str, uri: &str) -> Self {
        let mut progress = Self::new(
            AuthPhase::Waiting,
            "Enter this code",
            "Sign in on Microsoft's page, then come back",
        );
        progress.user_code = Some(code.to_owned());
        progress.verification_uri = Some(uri.to_owned());
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
        Self::new(AuthPhase::SignedIn, "Signed in", format!("Playing as {name}"))
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
        let progress = AuthProgress::waiting("ABCD-EFGH", "https://microsoft.com/link");
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
        let cancel = supervisor.begin(AuthProgress::starting()).expect("an attempt");
        assert_eq!(supervisor.request_cancel(), Ok(true));
        assert!(cancel.is_cancelled());
    }

    #[test]
    fn cancelling_nothing_says_so_rather_than_erroring() {
        let supervisor = Supervisor::default();
        assert_eq!(supervisor.request_cancel(), Ok(false));
    }
}
