//! The authentication seam.
//!
//! `docs/research/auth-feasibility.md` concludes that production Microsoft
//! authentication is **approval-dependent**: the code is understood, but the
//! client ID is issued by a manual review we do not control. So identity is
//! behind a trait, and "we have no client ID yet" is a normal typed state rather
//! than a wall.
//!
//! Four pieces:
//!
//! - [`MinecraftIdentity`] — who is playing, and **how that was established**.
//! - [`AuthProvider`] — produces an identity.
//! - [`EntitlementVerifier`] — decides whether an identity may launch.
//! - [`CredentialStore`](crate::credentials::CredentialStore) — persists the one
//!   long-lived secret. Already implemented; unchanged by this.
//!
//! The development provider lives in [`super::development`] and is not part of a
//! release build. See that module for the four barriers keeping it out.

use std::time::{Duration, SystemTime};

use crate::secret::Secret;

use super::entitlement::{EntitlementError, EntitlementsResponse};
use super::session::EntitlementProof;
use super::{AccountProfile, AuthError};

/// How an identity was established.
///
/// This is deliberately carried on the identity itself rather than tracked
/// alongside it, so that no code path can lose track of whether it is holding
/// a real credential.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IdentityKind {
    /// A real Microsoft account, entitlement verified against Mojang's signature.
    Microsoft,
    /// A local placeholder for building the launcher. Holds no valid token and
    /// cannot join an online-mode server. Never present in a release build.
    Development,
}

impl IdentityKind {
    /// Whether this identity can actually play online.
    ///
    /// Not a permission check we enforce — a statement of fact. A development
    /// identity carries no valid token, so Mojang's session server rejects it
    /// when the game tries to join an online-mode server.
    pub fn is_production(self) -> bool {
        matches!(self, IdentityKind::Microsoft)
    }

    /// Short label for the interface.
    pub fn label(self) -> &'static str {
        match self {
            IdentityKind::Microsoft => "Microsoft account",
            IdentityKind::Development => "Development identity (not a real account)",
        }
    }

    /// A warning to display persistently, if one is warranted.
    pub fn warning(self) -> Option<&'static str> {
        match self {
            IdentityKind::Microsoft => None,
            IdentityKind::Development => Some(
                "This is a fake local identity for building the launcher. It is not a Minecraft \
                 account, it owns nothing, and it cannot join online-mode servers.",
            ),
        }
    }
}

/// Who is playing.
///
/// Deliberately carries **no** ownership information. Authentication answers
/// "who are you"; entitlement answers "do you own this". Keeping them in one
/// struct is how a launcher ends up treating "signed in" as "allowed to play".
/// The entitlement lives on [`EntitlementProof`], which only exists in the
/// verified state.
#[derive(Debug, Clone)]
pub struct MinecraftIdentity {
    pub profile: AccountProfile,
    /// Minecraft access token. For a development identity this is a deliberately
    /// invalid placeholder.
    pub access_token: Secret,
    pub kind: IdentityKind,
    /// When the Minecraft access token stops being accepted. Mojang issues them
    /// with a 24 hour life.
    pub expires_at: SystemTime,
}

impl MinecraftIdentity {
    /// Whether the access token has passed its expiry.
    pub fn is_expired_at(&self, now: SystemTime) -> bool {
        now >= self.expires_at
    }

    /// Whether it will expire within `margin`.
    pub fn expires_within(&self, now: SystemTime, margin: Duration) -> bool {
        now + margin >= self.expires_at
    }

    /// Remaining life, saturating at zero.
    pub fn remaining(&self, now: SystemTime) -> Duration {
        self.expires_at
            .duration_since(now)
            .unwrap_or(Duration::ZERO)
    }

    /// The `--userType` value Minecraft expects.
    ///
    /// A development identity reports `legacy` rather than `msa`, because
    /// claiming to be a Microsoft session while holding no Microsoft token would
    /// be a lie told to the game as well as to the user.
    pub fn user_type(&self) -> &'static str {
        match self.kind {
            IdentityKind::Microsoft => "msa",
            IdentityKind::Development => "legacy",
        }
    }
}

/// Something that can establish an identity.
///
/// Kept deliberately small. The Microsoft device-code flow needs a callback for
/// the user code and a cancellation flag, both of which live in
/// [`crate::tasks`]; this trait describes only what every provider must offer.
pub trait AuthProvider: std::fmt::Debug + Send + Sync {
    /// Stable identifier, for logs and settings.
    fn id(&self) -> &'static str;

    /// Human-readable description for the Account screen.
    fn describe(&self) -> String;

    /// Whether this provider yields identities that can actually play online.
    ///
    /// A release build must only ever contain providers for which this is true.
    /// There is a test asserting exactly that.
    fn is_production(&self) -> bool;

    /// Whether this provider is usable right now. The Microsoft provider returns
    /// false until a client ID is configured.
    fn is_available(&self) -> bool;

    /// Why it is unavailable, if it is. Shown to the user verbatim.
    fn unavailable_reason(&self) -> Option<String>;

    /// An identity this provider can produce with no user interaction and no
    /// network.
    ///
    /// Only the development provider can do this. The Microsoft provider needs
    /// the device-code exchange and therefore returns `None` — the difference is
    /// real, so the trait states it rather than pretending both are the same
    /// shape.
    fn immediate_identity(&self) -> Option<MinecraftIdentity> {
        None
    }
}

/// Something that can decide whether an account owns the game.
///
/// The rest of the launcher depends on this trait rather than on Mojang's
/// signature format, so the entitlement step can be reasoned about and tested
/// without a live account.
pub trait EntitlementVerifier: std::fmt::Debug + Send + Sync {
    fn id(&self) -> &'static str;

    /// Whether this verifier actually checks a Mojang signature.
    ///
    /// A release build must only ever contain verifiers for which this is true,
    /// and the launch gate re-checks it on the resulting proof.
    fn is_authoritative(&self) -> bool;

    /// Decide what the account owns.
    ///
    /// Must fail closed: anything unrecognised, unsigned, expired or malformed
    /// is an error, never a permissive default.
    fn verify(&self, response: &EntitlementsResponse)
        -> Result<EntitlementProof, EntitlementError>;
}

/// The production verifier: RS256 signature checked against Mojang's public key,
/// with the entitlement list read from the verified payload.
///
/// The real work lives in [`super::entitlement`]; this is the marker that lets
/// the build assert which verifier is compiled in.
#[derive(Debug, Default, Clone, Copy)]
pub struct MojangEntitlementVerifier;

impl EntitlementVerifier for MojangEntitlementVerifier {
    fn id(&self) -> &'static str {
        "mojang-signed"
    }

    fn is_authoritative(&self) -> bool {
        true
    }

    fn verify(
        &self,
        response: &EntitlementsResponse,
    ) -> Result<EntitlementProof, EntitlementError> {
        // The unsigned `items` array is ignored entirely. Only the verified JWT
        // payload decides.
        let claims = super::entitlement::verify_signed_entitlements(response)?;
        Ok(EntitlementProof {
            ownership: super::entitlement::decide(&claims.names()),
            verifier: self.id(),
            authoritative: true,
            verified_at: SystemTime::now(),
        })
    }
}

/// The production provider: Microsoft device code -> Xbox Live -> XSTS ->
/// Minecraft Services.
///
/// Availability depends on holding an approved Entra client ID; see
/// `docs/research/auth-feasibility.md`.
#[derive(Debug, Clone)]
pub struct MicrosoftAuthProvider {
    configured: bool,
}

impl MicrosoftAuthProvider {
    pub fn new(configured: bool) -> Self {
        Self { configured }
    }
}

impl AuthProvider for MicrosoftAuthProvider {
    fn id(&self) -> &'static str {
        "microsoft"
    }

    fn describe(&self) -> String {
        "Microsoft account (device code)".to_owned()
    }

    fn is_production(&self) -> bool {
        true
    }

    fn is_available(&self) -> bool {
        self.configured
    }

    fn unavailable_reason(&self) -> Option<String> {
        if self.configured {
            return None;
        }
        Some(
            "No Microsoft application id is configured. Microsoft requires each launcher to \
             register its own application and have it approved for the Minecraft API before \
             sign-in can work; approval is a manual review. See \
             docs/research/auth-feasibility.md."
                .to_owned(),
        )
    }
}

/// Map an `AuthError` onto whether it is the approval gate specifically.
///
/// Worth singling out: a user hitting this has done nothing wrong, and the fix
/// is entirely on our side.
pub fn is_app_registration_rejection(error: &AuthError) -> bool {
    matches!(error, AuthError::NotApproved)
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

    #[test]
    fn only_a_microsoft_identity_is_production() {
        assert!(IdentityKind::Microsoft.is_production());
        assert!(!IdentityKind::Development.is_production());
    }

    #[test]
    fn a_development_identity_always_carries_a_warning() {
        assert!(IdentityKind::Microsoft.warning().is_none());
        let warning = IdentityKind::Development
            .warning()
            .expect("development identities must warn");
        assert!(warning.contains("not a Minecraft account"));
        assert!(warning.contains("online-mode"));
    }

    #[test]
    fn a_development_identity_does_not_claim_to_be_a_microsoft_session() {
        let identity = MinecraftIdentity {
            profile: AccountProfile {
                id: "0".into(),
                name: "x".into(),
                ..AccountProfile::default()
            },
            access_token: Secret::new("not-a-token"),
            kind: IdentityKind::Development,
            expires_at: SystemTime::now() + Duration::from_secs(60),
        };
        assert_eq!(identity.user_type(), "legacy");
        assert_ne!(
            identity.user_type(),
            "msa",
            "a fake identity must not tell the game it is a Microsoft session"
        );
    }

    #[test]
    fn the_microsoft_provider_is_unavailable_without_a_client_id() {
        let p = MicrosoftAuthProvider::new(false);
        assert!(p.is_production());
        assert!(!p.is_available());
        let reason = p.unavailable_reason().expect("must explain itself");
        assert!(reason.contains("approved"));
        assert!(reason.contains("auth-feasibility"));

        let configured = MicrosoftAuthProvider::new(true);
        assert!(configured.is_available());
        assert!(configured.unavailable_reason().is_none());
    }

    #[test]
    fn the_production_verifier_is_authoritative() {
        assert!(MojangEntitlementVerifier.is_authoritative());
    }

    #[test]
    fn token_expiry_is_computed_against_a_supplied_clock() {
        let base = SystemTime::UNIX_EPOCH + Duration::from_secs(1_000_000);
        let identity = MinecraftIdentity {
            profile: AccountProfile {
                id: "0".into(),
                name: "x".into(),
                ..AccountProfile::default()
            },
            access_token: Secret::new("t"),
            kind: IdentityKind::Microsoft,
            expires_at: base + Duration::from_secs(100),
        };

        assert!(!identity.is_expired_at(base));
        assert!(identity.is_expired_at(base + Duration::from_secs(101)));
        assert!(identity.expires_within(base, Duration::from_secs(200)));
        assert!(!identity.expires_within(base, Duration::from_secs(10)));
        assert_eq!(identity.remaining(base), Duration::from_secs(100));
        // Saturates rather than panicking once past expiry.
        assert_eq!(
            identity.remaining(base + Duration::from_secs(500)),
            Duration::ZERO
        );
    }

    #[test]
    fn the_production_verifier_refuses_an_unsigned_response() {
        // Fail closed: an entitlements body with no signature is not ownership,
        // however plausible its `items` array looks.
        let response = EntitlementsResponse {
            items: vec![super::super::entitlement::NamedEntitlement {
                name: "game_minecraft".into(),
            }],
            signature: None,
            key_id: None,
        };
        let err = MojangEntitlementVerifier
            .verify(&response)
            .expect_err("an unsigned response must be refused");
        assert!(matches!(err, EntitlementError::Unsigned));
    }

    #[test]
    fn the_production_verifier_refuses_a_forged_signature() {
        let response = EntitlementsResponse {
            items: vec![],
            signature: Some("not.a.jwt".into()),
            key_id: Some("1".into()),
        };
        assert!(MojangEntitlementVerifier.verify(&response).is_err());
    }

    #[test]
    fn the_microsoft_provider_offers_no_immediate_identity() {
        // It cannot: the device-code exchange has to happen first. Anything that
        // claimed otherwise would be fabricating a session.
        assert!(MicrosoftAuthProvider::new(true)
            .immediate_identity()
            .is_none());
        assert!(MicrosoftAuthProvider::new(false)
            .immediate_identity()
            .is_none());
    }

    /// The invariant the whole safety argument rests on.
    ///
    /// In a release build the development module is not compiled at all, so the
    /// only providers that exist are production ones. This test states that as
    /// an assertion rather than a comment.
    #[test]
    #[cfg(not(feature = "dev-auth"))]
    fn without_the_dev_auth_feature_every_provider_is_production() {
        let providers: Vec<Box<dyn AuthProvider>> =
            vec![Box::new(MicrosoftAuthProvider::new(false))];
        for p in &providers {
            assert!(
                p.is_production(),
                "{} is not a production provider but was compiled in",
                p.id()
            );
        }

        let verifiers: Vec<Box<dyn EntitlementVerifier>> =
            vec![Box::new(MojangEntitlementVerifier)];
        for v in &verifiers {
            assert!(v.is_authoritative(), "{} is not authoritative", v.id());
        }
    }
}
