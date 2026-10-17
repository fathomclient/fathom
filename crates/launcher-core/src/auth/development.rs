//! A fake local identity, for building the launcher while Microsoft application
//! approval is pending.
//!
//! # This module must never reach a user
//!
//! Four independent barriers, strongest first. The first one is the real
//! guarantee; the rest are defence in depth.
//!
//! ### 1. It is not in a release binary at all
//!
//! The module is behind the non-default `dev-auth` Cargo feature, and the
//! `compile_error!` below turns "release build with `dev-auth`" into a **build
//! failure**. This is not a runtime check that could be flipped, and not a flag
//! that could be set — in a release artifact the code is simply not present, so
//! there is nothing to enable.
//!
//! ### 2. It is off by default even in a development build
//!
//! Enabling the feature is not enough. [`DevelopmentAuthProvider::from_env`]
//! additionally requires `FATHOM_DEV_AUTH=i-understand-this-is-not-real`, so it
//! cannot be switched on by accident or by a stray `--features` in a script.
//!
//! ### 3. It cannot play online — by physics, not by policy
//!
//! The identity holds [`PLACEHOLDER_TOKEN`], which is not a credential. When the
//! game tries to join an online-mode server it asks Mojang's session server to
//! validate the session, and that fails. We are not *blocking* multiplayer; there
//! is simply nothing to authenticate with. Singleplayer works, which is all the
//! launcher work needs.
//!
//! ### 4. It never claims to be real
//!
//! Its [`IdentityKind`] is `Development`, its username is visibly fake, its
//! UUID is a nil-prefixed sentinel, it reports `--userType legacy` rather than
//! `msa`, and the interface carries a persistent banner.
//!
//! # What it is for
//!
//! Building profiles, settings, mod management, the install pipeline and the
//! 1.8.9 performance work without waiting on a manual approval queue. See
//! `docs/research/auth-feasibility.md` section 12.

// Barrier 1. If this ever fires, someone has tried to ship the mock.
#[cfg(not(debug_assertions))]
compile_error!(
    "The `dev-auth` feature enables a fake authentication provider and must never be built into \
     a release binary. It is enabled here together with a non-debug profile. Remove `dev-auth` \
     from the feature list for release builds. See crates/launcher-core/src/auth/development.rs."
);

use std::time::{Duration, SystemTime};

use crate::secret::Secret;

use super::entitlement::{EntitlementError, EntitlementsResponse, Ownership};
use super::provider::{AuthProvider, EntitlementVerifier, IdentityKind, MinecraftIdentity};
use super::session::EntitlementProof;
use super::AccountProfile;

/// The environment variable that must be set, and its required value.
///
/// Spelled out as a sentence so it cannot be set casually, and so that anyone
/// reading a shell history or a CI config sees what it means.
pub const ENABLE_VAR: &str = "FATHOM_DEV_AUTH";
pub const ENABLE_VALUE: &str = "i-understand-this-is-not-real";

/// Deliberately not a JWT, not base64, and self-describing.
///
/// If this ever appears in a bug report or a packet capture, it should be
/// immediately obvious what it is and that nothing was compromised.
pub const PLACEHOLDER_TOKEN: &str = "DEVELOPMENT-ONLY-NOT-A-REAL-ACCESS-TOKEN";

/// A UUID that is obviously a sentinel rather than a real account.
///
/// All-zero except for a trailing marker, so it sorts and reads as fake.
pub const DEVELOPMENT_UUID: &str = "00000000000000000000000000000dev";

/// The visibly-fake username.
pub const DEVELOPMENT_USERNAME: &str = "DevBuild_NotReal";

/// Produces a fake local identity. Development builds only.
#[derive(Debug, Clone)]
pub struct DevelopmentAuthProvider {
    enabled: bool,
}

impl DevelopmentAuthProvider {
    /// Construct one that is switched on. Only reachable from a build that has
    /// already passed barriers 1 and 2.
    pub fn enabled() -> Self {
        Self { enabled: true }
    }

    /// Barrier 2: require an explicit, unambiguous opt-in.
    pub fn from_env() -> Self {
        let enabled = std::env::var(ENABLE_VAR).is_ok_and(|v| v == ENABLE_VALUE);

        if enabled {
            // Loud on purpose. Anyone running this should be reminded every time.
            tracing::warn!(
                "DEVELOPMENT AUTHENTICATION IS ACTIVE. The signed-in identity is fake, owns \
                 nothing, and cannot join online-mode servers. Never use this build to \
                 represent a real account."
            );
        }
        Self { enabled }
    }

    /// The fake identity.
    ///
    /// Given a long nominal life so a development session does not keep
    /// expiring mid-work. The token behind it is not a credential, so its
    /// stated lifetime means nothing to anyone but us.
    pub fn identity(&self) -> Option<MinecraftIdentity> {
        if !self.enabled {
            return None;
        }
        Some(MinecraftIdentity {
            profile: AccountProfile {
                id: DEVELOPMENT_UUID.to_owned(),
                name: DEVELOPMENT_USERNAME.to_owned(),
                ..AccountProfile::default()
            },
            access_token: Secret::new(PLACEHOLDER_TOKEN),
            kind: IdentityKind::Development,
            expires_at: SystemTime::now() + Duration::from_secs(24 * 60 * 60),
        })
    }
}

impl AuthProvider for DevelopmentAuthProvider {
    fn id(&self) -> &'static str {
        "development"
    }

    fn describe(&self) -> String {
        "Development identity - NOT a real Minecraft account".to_owned()
    }

    fn is_production(&self) -> bool {
        false
    }

    fn is_available(&self) -> bool {
        self.enabled
    }

    fn unavailable_reason(&self) -> Option<String> {
        if self.enabled {
            return None;
        }
        Some(format!(
            "Development authentication is compiled in but switched off. Set {ENABLE_VAR}={ENABLE_VALUE} to enable it."
        ))
    }

    fn immediate_identity(&self) -> Option<MinecraftIdentity> {
        self.identity()
    }
}

/// A verifier that waves everything through. Development builds only.
///
/// Exists so the seam is complete, and so that
/// [`EntitlementVerifier::is_authoritative`] can report `false` and be asserted
/// against.
#[derive(Debug, Default, Clone, Copy)]
pub struct DevelopmentEntitlementVerifier;

impl EntitlementVerifier for DevelopmentEntitlementVerifier {
    fn id(&self) -> &'static str {
        "development-noop"
    }

    fn is_authoritative(&self) -> bool {
        false
    }

    /// Reports ownership without checking anything.
    ///
    /// Safe only because this type cannot exist in a release binary, and
    /// because the proof it produces is stamped `authoritative: false` — which
    /// the release launch gate rejects outright even if it somehow appeared.
    fn verify(
        &self,
        _response: &EntitlementsResponse,
    ) -> Result<EntitlementProof, EntitlementError> {
        Ok(EntitlementProof {
            ownership: Ownership::JavaEdition,
            verifier: self.id(),
            authoritative: false,
            verified_at: SystemTime::now(),
        })
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

    #[test]
    fn it_is_off_unless_explicitly_enabled() {
        let p = DevelopmentAuthProvider { enabled: false };
        assert!(!p.is_available());
        assert!(p.identity().is_none());
        assert!(p
            .unavailable_reason()
            .expect("must explain")
            .contains(ENABLE_VAR));
    }

    #[test]
    fn the_opt_in_value_is_deliberately_awkward() {
        // A value someone could set casually would defeat barrier 2.
        assert!(ENABLE_VALUE.len() > 20);
        assert!(ENABLE_VALUE.contains("not-real"));
        assert_ne!(ENABLE_VALUE, "1");
        assert_ne!(ENABLE_VALUE, "true");
    }

    #[test]
    fn the_identity_never_claims_to_be_production() {
        let identity = DevelopmentAuthProvider::enabled()
            .identity()
            .expect("enabled provider yields an identity");

        assert_eq!(identity.kind, IdentityKind::Development);
        assert!(!identity.kind.is_production());
        assert!(identity.kind.warning().is_some());
        assert_eq!(identity.user_type(), "legacy");
    }

    #[test]
    fn the_token_could_never_be_mistaken_for_a_real_one() {
        let identity = DevelopmentAuthProvider::enabled().identity().unwrap();
        let token = identity.access_token.expose();

        // A real Minecraft access token is a JWT: three base64url segments.
        assert!(!token.contains('.'), "must not look like a JWT");
        assert_eq!(token, PLACEHOLDER_TOKEN);
        assert!(token.contains("NOT-A-REAL"));
        assert!(
            token.chars().any(|c| c == '-'),
            "must not be plausible base64"
        );
    }

    #[test]
    fn the_identity_is_visibly_fake() {
        let identity = DevelopmentAuthProvider::enabled().identity().unwrap();
        assert!(identity.profile.name.contains("NotReal"));
        assert!(identity.profile.id.ends_with("dev"));
        // A real Minecraft UUID is 32 hex characters; ours deliberately is not.
        assert!(
            !identity.profile.id.chars().all(|c| c.is_ascii_hexdigit()),
            "the sentinel UUID must not be valid hex, so it cannot be mistaken \
             for a real account id"
        );
    }

    #[test]
    fn the_development_verifier_admits_it_is_not_authoritative() {
        assert!(!DevelopmentEntitlementVerifier.is_authoritative());
    }

    #[test]
    fn the_development_proof_is_stamped_unauthoritative() {
        // The release launch gate rejects any proof with this flag clear, so it
        // is the second lock behind compile-time exclusion.
        let response = EntitlementsResponse {
            items: Vec::new(),
            signature: None,
            key_id: None,
        };
        let proof = DevelopmentEntitlementVerifier
            .verify(&response)
            .expect("the development verifier waves everything through");
        assert!(!proof.authoritative);
        assert_eq!(proof.verifier, "development-noop");
    }

    /// Barrier 1 is enforced by the `compile_error!` at the top of this file,
    /// not by a runtime check — if `debug_assertions` were off, this file would
    /// not compile at all. There is deliberately no assertion for it here,
    /// because a tautology would only look like a test.
    ///
    /// What *is* worth asserting is that nothing in this module ever reports
    /// itself as production-grade. If someone adds a provider here and forgets,
    /// this fails.
    #[test]
    fn nothing_in_this_module_claims_to_be_production() {
        let providers: Vec<Box<dyn AuthProvider>> = vec![
            Box::new(DevelopmentAuthProvider::enabled()),
            Box::new(DevelopmentAuthProvider { enabled: false }),
        ];
        for p in &providers {
            assert!(
                !p.is_production(),
                "{} is in the development module but claims to be production",
                p.id()
            );
            assert!(
                p.describe().to_lowercase().contains("not a real")
                    || p.describe().to_lowercase().contains("development"),
                "{} must describe itself as non-real",
                p.id()
            );
        }

        let verifiers: Vec<Box<dyn EntitlementVerifier>> =
            vec![Box::new(DevelopmentEntitlementVerifier)];
        for v in &verifiers {
            assert!(
                !v.is_authoritative(),
                "{} is in the development module but claims to be authoritative",
                v.id()
            );
        }
    }
}
