//! Authentication state, as a state machine rather than a pile of booleans.
//!
//! The six states in the brief map one-to-one onto [`AuthStatus`]. The richer
//! [`AuthState`] carries the data each state actually needs, so there is no way
//! to be "authenticated" without an identity or "entitlement verified" without
//! a proof.
//!
//! ```text
//!                    begin()
//!   SignedOut ──────────────────► Authenticating
//!       ▲                              │
//!       │                              │ authenticated(identity)
//!       │ sign_out()                   ▼
//!       │                          Authenticated  ── tokens are good,
//!       │                              │             ownership is NOT yet known
//!       │                              │ entitlement_verified(proof)
//!       │                              ▼
//!       │                       EntitlementVerified  ◄── the only launchable state
//!       │                              │
//!       │                              │ token expires
//!       │                              ▼
//!       ├──────────────────── TokenRefreshRequired
//!       │                              │
//!       └───────────────────────── AuthError
//! ```
//!
//! # Two properties worth stating
//!
//! **Authentication and entitlement are separate.** `Authenticated` means
//! Microsoft and Mojang agreed who you are. It says nothing about whether you
//! own the game. Only `EntitlementVerified` does, and it carries the proof that
//! says so. Conflating the two is how a launcher ends up accidentally shipping
//! an ownership bypass.
//!
//! **The gate fails closed.** [`AuthSession::launch_credentials`] is the only
//! way to obtain something you can launch with, it exists only in
//! `EntitlementVerified`, and in a release build it additionally requires that
//! the proof came from an authoritative verifier and the identity is a real
//! Microsoft one. A timeout, a malformed response, an unknown state or an
//! unavailable service all leave the session somewhere that is not
//! `EntitlementVerified`, and therefore cannot launch.

use std::time::{Duration, SystemTime};

use super::entitlement::Ownership;
use super::provider::{IdentityKind, MinecraftIdentity};

/// The flat status, for matching and display. Exactly the six states the
/// product brief names.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthStatus {
    SignedOut,
    Authenticating,
    Authenticated,
    EntitlementVerified,
    TokenRefreshRequired,
    AuthError,
}

impl AuthStatus {
    pub fn label(self) -> &'static str {
        match self {
            AuthStatus::SignedOut => "Signed out",
            AuthStatus::Authenticating => "Signing in",
            AuthStatus::Authenticated => "Signed in, checking entitlement",
            AuthStatus::EntitlementVerified => "Ready",
            AuthStatus::TokenRefreshRequired => "Session expired",
            AuthStatus::AuthError => "Sign-in failed",
        }
    }
}

/// How far through sign-in we are. Only meaningful in
/// [`AuthState::Authenticating`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthStage {
    /// Asking Microsoft for a device code.
    RequestingCode,
    /// The user has a code and is entering it on Microsoft's page.
    AwaitingUser {
        user_code: String,
        verification_uri: String,
        expires_at: SystemTime,
    },
    /// Trading the Microsoft token through Xbox Live, XSTS and Minecraft
    /// Services.
    ExchangingTokens,
    /// Asking Minecraft Services what this account owns.
    VerifyingEntitlement,
}

impl AuthStage {
    pub fn label(&self) -> &'static str {
        match self {
            AuthStage::RequestingCode => "Contacting Microsoft",
            AuthStage::AwaitingUser { .. } => "Waiting for you to enter the code",
            AuthStage::ExchangingTokens => "Exchanging tokens",
            AuthStage::VerifyingEntitlement => "Checking your entitlement",
        }
    }
}

/// Evidence that an entitlement check actually happened, and what decided it.
///
/// Carries `authoritative` so a release build can insist on a real
/// signature-verified answer rather than trusting that only real verifiers were
/// compiled in. Belt and braces: the development verifier does not exist in a
/// release binary *and* would be rejected here if it somehow did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntitlementProof {
    pub ownership: Ownership,
    /// Which verifier produced this.
    pub verifier: &'static str,
    /// Whether that verifier checked a Mojang signature.
    pub authoritative: bool,
    pub verified_at: SystemTime,
}

impl EntitlementProof {
    pub fn may_launch(&self) -> bool {
        self.ownership.may_launch()
    }
}

/// Why a refresh is needed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefreshReason {
    /// The Minecraft access token passed its expiry.
    TokenExpired,
    /// The token is close enough to expiry that we should renew before using it.
    ExpiringSoon,
    /// A request came back 401.
    Rejected,
}

impl RefreshReason {
    pub fn label(self) -> &'static str {
        match self {
            RefreshReason::TokenExpired => "your session expired",
            RefreshReason::ExpiringSoon => "your session is about to expire",
            RefreshReason::Rejected => "the server rejected your session",
        }
    }
}

/// The full state, with the data each state needs.
#[derive(Debug, Clone)]
pub enum AuthState {
    SignedOut,
    Authenticating {
        stage: AuthStage,
    },
    /// Tokens obtained. Ownership is **not** established yet.
    Authenticated {
        identity: MinecraftIdentity,
    },
    /// The only state from which the game may be launched.
    EntitlementVerified {
        identity: MinecraftIdentity,
        entitlement: EntitlementProof,
    },
    TokenRefreshRequired {
        account_id: String,
        reason: RefreshReason,
    },
    Failed {
        message: String,
        /// Whether signing in again is likely to help.
        recoverable: bool,
    },
}

impl AuthState {
    pub fn status(&self) -> AuthStatus {
        match self {
            AuthState::SignedOut => AuthStatus::SignedOut,
            AuthState::Authenticating { .. } => AuthStatus::Authenticating,
            AuthState::Authenticated { .. } => AuthStatus::Authenticated,
            AuthState::EntitlementVerified { .. } => AuthStatus::EntitlementVerified,
            AuthState::TokenRefreshRequired { .. } => AuthStatus::TokenRefreshRequired,
            AuthState::Failed { .. } => AuthStatus::AuthError,
        }
    }
}

/// A transition that was not allowed.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("cannot {attempted} from {from:?}")]
pub struct TransitionError {
    pub attempted: &'static str,
    pub from: AuthStatus,
}

/// Why launching was refused.
///
/// Every variant is a refusal. There is deliberately no "unknown, allow anyway"
/// case: an unrecognised situation is not ownership.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum LaunchDenied {
    #[error("not signed in")]
    SignedOut,
    #[error("sign-in is still in progress")]
    InProgress,
    #[error("signed in, but the entitlement check has not completed")]
    EntitlementNotVerified,
    #[error("your session expired; sign in again")]
    TokenExpired,
    #[error("sign-in failed: {0}")]
    Failed(String),
    #[error("{0}")]
    NotEntitled(String),
    #[error(
        "this build refuses to launch with a non-production identity. This is a bug: the \
         development provider should not exist in a release binary."
    )]
    NonProductionIdentity,
    #[error(
        "the entitlement was not verified against Mojang's signature. Refusing to launch rather \
         than assume ownership."
    )]
    UnverifiedEntitlement,
}

/// Everything needed to launch, obtainable only from a verified session.
///
/// There is no public constructor. Holding one of these *is* the proof that the
/// gate passed.
#[derive(Debug, Clone, Copy)]
pub struct LaunchCredentials<'a> {
    pub identity: &'a MinecraftIdentity,
    pub entitlement: &'a EntitlementProof,
}

/// How close to expiry a token may get before we want a refresh.
pub const REFRESH_MARGIN: Duration = Duration::from_secs(5 * 60);

/// The session.
#[derive(Debug, Clone)]
pub struct AuthSession {
    state: AuthState,
}

impl Default for AuthSession {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthSession {
    pub fn new() -> Self {
        Self {
            state: AuthState::SignedOut,
        }
    }

    pub fn state(&self) -> &AuthState {
        &self.state
    }

    pub fn status(&self) -> AuthStatus {
        self.state.status()
    }

    /// The identity, in any state that has one.
    pub fn identity(&self) -> Option<&MinecraftIdentity> {
        match &self.state {
            AuthState::Authenticated { identity }
            | AuthState::EntitlementVerified { identity, .. } => Some(identity),
            _ => None,
        }
    }

    pub fn entitlement(&self) -> Option<&EntitlementProof> {
        match &self.state {
            AuthState::EntitlementVerified { entitlement, .. } => Some(entitlement),
            _ => None,
        }
    }

    /// The account whose refresh token we would look for.
    pub fn account_id(&self) -> Option<&str> {
        match &self.state {
            AuthState::Authenticated { identity }
            | AuthState::EntitlementVerified { identity, .. } => Some(&identity.profile.id),
            AuthState::TokenRefreshRequired { account_id, .. } => Some(account_id),
            _ => None,
        }
    }

    // -- transitions -------------------------------------------------------

    /// Start signing in. Allowed from any state that is not already in
    /// progress: retrying after a failure, or replacing an expired session, are
    /// both normal.
    pub fn begin(&mut self) -> Result<(), TransitionError> {
        if matches!(self.state, AuthState::Authenticating { .. }) {
            return Err(TransitionError {
                attempted: "begin sign-in",
                from: self.status(),
            });
        }
        self.state = AuthState::Authenticating {
            stage: AuthStage::RequestingCode,
        };
        Ok(())
    }

    /// Advance within `Authenticating`.
    pub fn set_stage(&mut self, stage: AuthStage) -> Result<(), TransitionError> {
        match &mut self.state {
            AuthState::Authenticating { stage: current } => {
                *current = stage;
                Ok(())
            }
            _ => Err(TransitionError {
                attempted: "advance sign-in",
                from: self.status(),
            }),
        }
    }

    /// Tokens obtained. Ownership still unknown.
    pub fn authenticated(&mut self, identity: MinecraftIdentity) -> Result<(), TransitionError> {
        if !matches!(self.state, AuthState::Authenticating { .. }) {
            return Err(TransitionError {
                attempted: "complete authentication",
                from: self.status(),
            });
        }
        self.state = AuthState::Authenticated { identity };
        Ok(())
    }

    /// Ownership established. The only transition into a launchable state.
    ///
    /// Refuses a proof that does not permit launching: a session must not sit
    /// in `EntitlementVerified` while holding a proof that says "you do not own
    /// this". That would be a state whose name lies.
    pub fn entitlement_verified(
        &mut self,
        entitlement: EntitlementProof,
    ) -> Result<(), TransitionError> {
        let AuthState::Authenticated { identity } = &self.state else {
            return Err(TransitionError {
                attempted: "record entitlement",
                from: self.status(),
            });
        };

        if !entitlement.may_launch() {
            let message = entitlement.ownership.explanation();
            self.state = AuthState::Failed {
                message,
                recoverable: false,
            };
            return Ok(());
        }

        self.state = AuthState::EntitlementVerified {
            identity: identity.clone(),
            entitlement,
        };
        Ok(())
    }

    /// The token needs renewing.
    pub fn needs_refresh(&mut self, reason: RefreshReason) {
        let account_id = self.account_id().map(str::to_owned);
        self.state = match account_id {
            Some(account_id) => AuthState::TokenRefreshRequired { account_id, reason },
            // Nothing to refresh from; a full sign-in is required.
            None => AuthState::SignedOut,
        };
    }

    /// Something went wrong.
    pub fn fail(&mut self, message: impl Into<String>, recoverable: bool) {
        self.state = AuthState::Failed {
            message: message.into(),
            recoverable,
        };
    }

    /// Forget everything. Always allowed.
    pub fn sign_out(&mut self) {
        self.state = AuthState::SignedOut;
    }

    /// Move to `TokenRefreshRequired` if the token has expired or is about to.
    ///
    /// Returns true if the state changed. Call before using credentials, so an
    /// expired token becomes a refresh rather than a confusing 401 later.
    pub fn check_expiry(&mut self, now: SystemTime) -> bool {
        let reason = match self.identity() {
            Some(identity) if identity.is_expired_at(now) => RefreshReason::TokenExpired,
            Some(identity) if identity.expires_within(now, REFRESH_MARGIN) => {
                RefreshReason::ExpiringSoon
            }
            _ => return false,
        };
        self.needs_refresh(reason);
        true
    }

    // -- the gate ----------------------------------------------------------

    /// The only way to obtain credentials the launcher may start the game with.
    ///
    /// Fails closed: every path that is not "verified, entitled, unexpired, and
    /// acceptable for this build" is a refusal.
    pub fn launch_credentials(
        &self,
        now: SystemTime,
    ) -> Result<LaunchCredentials<'_>, LaunchDenied> {
        let (identity, entitlement) = match &self.state {
            AuthState::EntitlementVerified {
                identity,
                entitlement,
            } => (identity, entitlement),
            AuthState::SignedOut => return Err(LaunchDenied::SignedOut),
            AuthState::Authenticating { .. } => return Err(LaunchDenied::InProgress),
            AuthState::Authenticated { .. } => return Err(LaunchDenied::EntitlementNotVerified),
            AuthState::TokenRefreshRequired { .. } => return Err(LaunchDenied::TokenExpired),
            AuthState::Failed { message, .. } => return Err(LaunchDenied::Failed(message.clone())),
        };

        if identity.is_expired_at(now) {
            return Err(LaunchDenied::TokenExpired);
        }
        if !entitlement.may_launch() {
            return Err(LaunchDenied::NotEntitled(
                entitlement.ownership.explanation(),
            ));
        }

        // Release builds accept nothing but a real, signature-verified answer.
        // The development provider is not compiled into a release binary at
        // all; this is the second lock on the same door.
        #[cfg(not(debug_assertions))]
        {
            if !identity.kind.is_production() {
                return Err(LaunchDenied::NonProductionIdentity);
            }
            if !entitlement.authoritative {
                return Err(LaunchDenied::UnverifiedEntitlement);
            }
        }

        Ok(LaunchCredentials {
            identity,
            entitlement,
        })
    }

    /// Whether the game could be launched right now.
    pub fn can_launch(&self, now: SystemTime) -> bool {
        self.launch_credentials(now).is_ok()
    }

    /// Convenience for callers that only care whether this is a real account.
    pub fn identity_kind(&self) -> Option<IdentityKind> {
        self.identity().map(|i| i.kind)
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
    use crate::auth::AccountProfile;
    use crate::secret::Secret;

    fn now() -> SystemTime {
        SystemTime::UNIX_EPOCH + Duration::from_secs(1_800_000_000)
    }

    fn identity(kind: IdentityKind, valid_for: Duration) -> MinecraftIdentity {
        MinecraftIdentity {
            profile: AccountProfile {
                id: "uuid-1".into(),
                name: "Steve".into(),
                ..AccountProfile::default()
            },
            access_token: Secret::new("token"),
            kind,
            expires_at: now() + valid_for,
        }
    }

    fn proof(ownership: Ownership, authoritative: bool) -> EntitlementProof {
        EntitlementProof {
            ownership,
            verifier: "test",
            authoritative,
            verified_at: now(),
        }
    }

    /// Drive a session all the way to launchable.
    fn verified() -> AuthSession {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
            .unwrap();
        s.entitlement_verified(proof(Ownership::JavaEdition, true))
            .unwrap();
        s
    }

    // -- happy path --------------------------------------------------------

    #[test]
    fn the_full_transition_sequence_reaches_a_launchable_state() {
        let mut s = AuthSession::new();
        assert_eq!(s.status(), AuthStatus::SignedOut);

        s.begin().unwrap();
        assert_eq!(s.status(), AuthStatus::Authenticating);

        s.set_stage(AuthStage::AwaitingUser {
            user_code: "ABCD-EFGH".into(),
            verification_uri: "https://microsoft.com/link".into(),
            expires_at: now() + Duration::from_secs(900),
        })
        .unwrap();
        assert_eq!(s.status(), AuthStatus::Authenticating);

        s.set_stage(AuthStage::ExchangingTokens).unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
            .unwrap();

        // Authenticated is NOT launchable: ownership is still unknown.
        assert_eq!(s.status(), AuthStatus::Authenticated);
        assert_eq!(
            s.launch_credentials(now()).unwrap_err(),
            LaunchDenied::EntitlementNotVerified
        );

        s.entitlement_verified(proof(Ownership::JavaEdition, true))
            .unwrap();
        assert_eq!(s.status(), AuthStatus::EntitlementVerified);
        assert!(s.launch_credentials(now()).is_ok());
    }

    // -- invalid transitions ----------------------------------------------

    #[test]
    fn authentication_cannot_complete_without_having_begun() {
        let mut s = AuthSession::new();
        let err = s
            .authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(60)))
            .expect_err("must not jump straight to authenticated");
        assert_eq!(err.from, AuthStatus::SignedOut);
        assert_eq!(s.status(), AuthStatus::SignedOut);
    }

    #[test]
    fn entitlement_cannot_be_recorded_before_authentication() {
        let mut s = AuthSession::new();
        assert!(s
            .entitlement_verified(proof(Ownership::JavaEdition, true))
            .is_err());
        assert_eq!(s.status(), AuthStatus::SignedOut);

        // Not even mid-sign-in.
        s.begin().unwrap();
        assert!(s
            .entitlement_verified(proof(Ownership::JavaEdition, true))
            .is_err());
    }

    #[test]
    fn a_second_begin_while_already_authenticating_is_refused() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        assert!(s.begin().is_err(), "double sign-in must be refused");
    }

    #[test]
    fn retrying_after_a_failure_is_allowed() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.fail("network died", true);
        assert_eq!(s.status(), AuthStatus::AuthError);
        s.begin().expect("retry after failure should be allowed");
    }

    #[test]
    fn advancing_a_stage_outside_authenticating_is_refused() {
        let mut s = verified();
        assert!(s.set_stage(AuthStage::ExchangingTokens).is_err());
    }

    // -- entitlement failure ----------------------------------------------

    #[test]
    fn an_unentitled_proof_lands_in_failure_not_in_verified() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
            .unwrap();
        s.entitlement_verified(proof(Ownership::Nothing, true))
            .unwrap();

        assert_eq!(
            s.status(),
            AuthStatus::AuthError,
            "a session must never sit in EntitlementVerified holding a proof that says otherwise"
        );
        assert!(s.launch_credentials(now()).is_err());
    }

    #[test]
    fn owning_other_products_does_not_permit_launch() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
            .unwrap();
        s.entitlement_verified(proof(
            Ownership::OtherProductsOnly {
                products: vec!["Minecraft: Bedrock Edition".into()],
            },
            true,
        ))
        .unwrap();

        assert_eq!(s.status(), AuthStatus::AuthError);
        match s.launch_credentials(now()) {
            Err(LaunchDenied::Failed(msg)) => assert!(msg.contains("Bedrock")),
            other => panic!("expected a failure explaining why, got {other:?}"),
        }
    }

    // -- expiry ------------------------------------------------------------

    #[test]
    fn an_expired_token_cannot_launch() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(10)))
            .unwrap();
        s.entitlement_verified(proof(Ownership::JavaEdition, true))
            .unwrap();

        assert!(s.launch_credentials(now()).is_ok());

        let later = now() + Duration::from_secs(30);
        assert_eq!(
            s.launch_credentials(later).unwrap_err(),
            LaunchDenied::TokenExpired,
            "an expired token must not launch even though the state says verified"
        );
    }

    #[test]
    fn check_expiry_moves_to_refresh_required() {
        let mut s = verified();
        assert!(!s.check_expiry(now()), "a fresh token needs nothing");

        let later = now() + Duration::from_secs(3601);
        assert!(s.check_expiry(later));
        assert_eq!(s.status(), AuthStatus::TokenRefreshRequired);
        assert_eq!(s.account_id(), Some("uuid-1"));
        assert_eq!(
            s.launch_credentials(later).unwrap_err(),
            LaunchDenied::TokenExpired
        );
    }

    #[test]
    fn a_token_close_to_expiry_is_refreshed_early() {
        let mut s = verified();
        // Inside the refresh margin but not yet expired.
        let nearly = now() + Duration::from_secs(3600) - Duration::from_secs(60);
        assert!(s.check_expiry(nearly));
        assert_eq!(s.status(), AuthStatus::TokenRefreshRequired);
        match s.state() {
            AuthState::TokenRefreshRequired { reason, .. } => {
                assert_eq!(*reason, RefreshReason::ExpiringSoon);
            }
            other => panic!("expected refresh required, got {other:?}"),
        }
    }

    #[test]
    fn needing_a_refresh_with_no_account_falls_back_to_signed_out() {
        let mut s = AuthSession::new();
        s.needs_refresh(RefreshReason::Rejected);
        assert_eq!(
            s.status(),
            AuthStatus::SignedOut,
            "there is nothing to refresh from, so a full sign-in is required"
        );
    }

    // -- the gate ----------------------------------------------------------

    #[test]
    fn every_non_verified_state_refuses_to_launch() {
        let cases: Vec<(AuthSession, LaunchDenied)> = vec![
            (AuthSession::new(), LaunchDenied::SignedOut),
            (
                {
                    let mut s = AuthSession::new();
                    s.begin().unwrap();
                    s
                },
                LaunchDenied::InProgress,
            ),
            (
                {
                    let mut s = AuthSession::new();
                    s.begin().unwrap();
                    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
                        .unwrap();
                    s
                },
                LaunchDenied::EntitlementNotVerified,
            ),
            (
                {
                    let mut s = verified();
                    s.needs_refresh(RefreshReason::Rejected);
                    s
                },
                LaunchDenied::TokenExpired,
            ),
        ];

        for (session, expected) in cases {
            assert_eq!(
                session.launch_credentials(now()).unwrap_err(),
                expected,
                "state {:?} must refuse to launch",
                session.status()
            );
        }
    }

    #[test]
    fn a_failed_session_refuses_to_launch() {
        let mut s = AuthSession::new();
        s.fail("service unavailable", true);
        assert!(matches!(
            s.launch_credentials(now()),
            Err(LaunchDenied::Failed(_))
        ));
    }

    /// The release gate. In a debug build the development identity is allowed
    /// through, which is the entire point of it; in release the `cfg` above
    /// rejects it.
    #[test]
    fn the_release_gate_matches_the_build_profile() {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(
            IdentityKind::Development,
            Duration::from_secs(3600),
        ))
        .unwrap();
        s.entitlement_verified(proof(Ownership::JavaEdition, false))
            .unwrap();

        let result = s.launch_credentials(now());

        if cfg!(debug_assertions) {
            assert!(
                result.is_ok(),
                "a development build must be able to launch with the development identity"
            );
        } else {
            assert!(
                matches!(
                    result,
                    Err(LaunchDenied::NonProductionIdentity | LaunchDenied::UnverifiedEntitlement)
                ),
                "a release build must refuse a non-production identity"
            );
        }
    }

    #[test]
    fn launch_credentials_carry_both_halves() {
        let s = verified();
        let creds = s.launch_credentials(now()).unwrap();
        assert_eq!(creds.identity.profile.name, "Steve");
        assert!(creds.entitlement.authoritative);
        assert_eq!(creds.entitlement.ownership, Ownership::JavaEdition);
    }

    #[test]
    fn sign_out_from_any_state_returns_to_signed_out() {
        for mut s in [verified(), AuthSession::new(), {
            let mut s = AuthSession::new();
            s.fail("x", true);
            s
        }] {
            s.sign_out();
            assert_eq!(s.status(), AuthStatus::SignedOut);
            assert!(s.identity().is_none());
            assert!(s.entitlement().is_none());
            assert!(s.launch_credentials(now()).is_err());
        }
    }

    #[test]
    fn status_labels_are_all_distinct_and_human() {
        let all = [
            AuthStatus::SignedOut,
            AuthStatus::Authenticating,
            AuthStatus::Authenticated,
            AuthStatus::EntitlementVerified,
            AuthStatus::TokenRefreshRequired,
            AuthStatus::AuthError,
        ];
        let mut labels: Vec<&str> = all.iter().map(|s| s.label()).collect();
        let before = labels.len();
        labels.sort_unstable();
        labels.dedup();
        assert_eq!(labels.len(), before, "two states share a label");
    }
}
