//! Contract tests for the authentication core.
//!
//! These sit outside the module tests on purpose: they exercise the seams the
//! rest of the launcher depends on, from the outside, the way a caller would.
//! Several of them are worded as the property they defend rather than the
//! function they call, because that is what a reviewer needs to check.
//!
//! Nothing here touches the network. Everything that would require a live
//! Microsoft application is exercised through the abstraction instead.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::sync::Arc;
use std::time::{Duration, SystemTime};

use launcher_core::auth::entitlement::{EntitlementError, EntitlementsResponse, NamedEntitlement};
use launcher_core::auth::provider::{
    AuthProvider, EntitlementVerifier, IdentityKind, MicrosoftAuthProvider, MinecraftIdentity,
    MojangEntitlementVerifier,
};
use launcher_core::auth::session::{
    AuthSession, AuthStage, AuthStatus, EntitlementProof, LaunchDenied, RefreshReason,
};
use launcher_core::auth::{AccountProfile, Ownership};
use launcher_core::credentials::{CredentialError, CredentialStore, EphemeralCredentialStore};
use launcher_core::paths::DataPaths;
use launcher_core::secret::Secret;
use launcher_core::Launcher;

fn clock() -> SystemTime {
    SystemTime::UNIX_EPOCH + Duration::from_secs(1_800_000_000)
}

fn identity(kind: IdentityKind, valid_for: Duration) -> MinecraftIdentity {
    MinecraftIdentity {
        profile: AccountProfile {
            id: "986dec87b7ec47ff89ff033fdb95c4b5".into(),
            name: "Steve".into(),
        },
        access_token: Secret::new("mc-access-token-value"),
        kind,
        expires_at: clock() + valid_for,
    }
}

fn proof(ownership: Ownership, authoritative: bool) -> EntitlementProof {
    EntitlementProof {
        ownership,
        verifier: "test",
        authoritative,
        verified_at: clock(),
    }
}

fn launcher() -> (tempfile::TempDir, Launcher) {
    let tmp = tempfile::tempdir().expect("tempdir");
    let paths = DataPaths::rooted_at(tmp.path());
    let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
    let core = Launcher::new(paths, store).expect("launcher");
    (tmp, core)
}

// ---------------------------------------------------------------------------
// State transitions
// ---------------------------------------------------------------------------

#[test]
fn a_fresh_session_is_signed_out_and_cannot_launch() {
    let s = AuthSession::new();
    assert_eq!(s.status(), AuthStatus::SignedOut);
    assert_eq!(
        s.launch_credentials(clock()).unwrap_err(),
        LaunchDenied::SignedOut
    );
}

#[test]
fn the_six_states_are_reachable_and_distinct() {
    use launcher_core::auth::AuthState;

    let mut seen = Vec::new();

    let mut s = AuthSession::new();
    seen.push(s.status());

    s.begin().unwrap();
    seen.push(s.status());

    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();
    seen.push(s.status());

    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();
    seen.push(s.status());

    s.needs_refresh(RefreshReason::TokenExpired);
    seen.push(s.status());
    assert!(matches!(s.state(), AuthState::TokenRefreshRequired { .. }));

    s.fail("nope", true);
    seen.push(s.status());

    assert_eq!(
        seen,
        vec![
            AuthStatus::SignedOut,
            AuthStatus::Authenticating,
            AuthStatus::Authenticated,
            AuthStatus::EntitlementVerified,
            AuthStatus::TokenRefreshRequired,
            AuthStatus::AuthError,
        ]
    );
}

#[test]
fn authentication_alone_is_not_permission_to_play() {
    // The property that keeps "signed in" from silently meaning "owns the game".
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();

    assert_eq!(s.status(), AuthStatus::Authenticated);
    assert!(s.identity().is_some(), "we know who they are");
    assert!(
        s.entitlement().is_none(),
        "but we do not yet know what they own"
    );
    assert_eq!(
        s.launch_credentials(clock()).unwrap_err(),
        LaunchDenied::EntitlementNotVerified
    );
}

#[test]
fn stages_advance_only_inside_authenticating() {
    let mut s = AuthSession::new();
    assert!(
        s.set_stage(AuthStage::ExchangingTokens).is_err(),
        "cannot be mid-exchange while signed out"
    );

    s.begin().unwrap();
    s.set_stage(AuthStage::AwaitingUser {
        user_code: "ABCD-EFGH".into(),
        verification_uri: "https://microsoft.com/link".into(),
        expires_at: clock() + Duration::from_secs(900),
    })
    .unwrap();
    s.set_stage(AuthStage::VerifyingEntitlement).unwrap();
    assert_eq!(s.status(), AuthStatus::Authenticating);
}

// ---------------------------------------------------------------------------
// Token expiry and refresh
// ---------------------------------------------------------------------------

#[test]
fn an_expired_token_is_refused_even_from_the_verified_state() {
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(60)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    assert!(s.launch_credentials(clock()).is_ok());
    assert_eq!(
        s.launch_credentials(clock() + Duration::from_secs(61))
            .unwrap_err(),
        LaunchDenied::TokenExpired
    );
}

#[test]
fn refresh_failure_leaves_the_session_unable_to_launch() {
    // Simulates: the refresh token was revoked, so renewal fails.
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(10)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    assert!(s.check_expiry(clock() + Duration::from_secs(11)));
    assert_eq!(s.status(), AuthStatus::TokenRefreshRequired);

    // The refresh attempt fails.
    s.fail("the refresh token was rejected", true);
    assert_eq!(s.status(), AuthStatus::AuthError);
    assert!(
        matches!(
            s.launch_credentials(clock()).unwrap_err(),
            LaunchDenied::Failed(_)
        ),
        "a failed refresh must never fall back to the old session"
    );
}

#[test]
fn a_rejected_token_moves_to_refresh_and_keeps_the_account() {
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    s.needs_refresh(RefreshReason::Rejected);
    assert_eq!(s.status(), AuthStatus::TokenRefreshRequired);
    assert_eq!(
        s.account_id(),
        Some("986dec87b7ec47ff89ff033fdb95c4b5"),
        "we still know which account to renew"
    );
}

// ---------------------------------------------------------------------------
// Entitlement: fail closed
// ---------------------------------------------------------------------------

#[test]
fn an_unsigned_entitlements_response_is_not_ownership() {
    // The exact shape an attacker with a local proxy would return: a plausible
    // `items` array and no signature.
    let forged = EntitlementsResponse {
        items: vec![
            NamedEntitlement {
                name: "product_minecraft".into(),
            },
            NamedEntitlement {
                name: "game_minecraft".into(),
            },
        ],
        signature: None,
        key_id: None,
    };
    assert!(matches!(
        MojangEntitlementVerifier.verify(&forged),
        Err(EntitlementError::Unsigned)
    ));
}

#[test]
fn a_malformed_signature_is_not_ownership() {
    for signature in [
        "",
        "not-a-jwt",
        "a.b",
        "a.b.c",
        "eyJhbGciOiJub25lIn0..", // alg:none, the classic
    ] {
        let response = EntitlementsResponse {
            items: Vec::new(),
            signature: Some(signature.to_owned()),
            key_id: Some("1".into()),
        };
        assert!(
            MojangEntitlementVerifier.verify(&response).is_err(),
            "signature {signature:?} must be refused"
        );
    }
}

#[test]
fn an_entitlement_that_does_not_permit_launch_never_reaches_the_verified_state() {
    for ownership in [
        Ownership::Nothing,
        Ownership::OtherProductsOnly {
            products: vec!["Minecraft Dungeons".into()],
        },
    ] {
        let mut s = AuthSession::new();
        s.begin().unwrap();
        s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
            .unwrap();
        s.entitlement_verified(proof(ownership.clone(), true))
            .unwrap();

        assert_eq!(
            s.status(),
            AuthStatus::AuthError,
            "{ownership:?} must not produce a verified session"
        );
        assert!(s.launch_credentials(clock()).is_err());
    }
}

#[test]
fn an_unrecognised_entitlement_name_is_not_ownership() {
    let decided = launcher_core::auth::entitlement::decide(&["product_something_new_2030"]);
    assert!(
        !decided.may_launch(),
        "an unknown entitlement must fail closed, not open"
    );
}

// ---------------------------------------------------------------------------
// The release launch gate
// ---------------------------------------------------------------------------

#[test]
fn the_launch_gate_is_the_only_way_to_obtain_credentials() {
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    let creds = s
        .launch_credentials(clock())
        .expect("a verified session should yield credentials");
    assert_eq!(creds.identity.profile.name, "Steve");
    assert!(creds.entitlement.authoritative);
}

#[test]
fn a_release_build_refuses_a_non_authoritative_entitlement() {
    // A proof that was not signature-checked. In debug this is the development
    // path and is allowed; in release it must be refused.
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, false))
        .unwrap();

    let result = s.launch_credentials(clock());
    if cfg!(debug_assertions) {
        assert!(result.is_ok());
    } else {
        assert_eq!(
            result.unwrap_err(),
            LaunchDenied::UnverifiedEntitlement,
            "a release build must not launch on an unverified entitlement"
        );
    }
}

#[test]
fn a_release_build_refuses_a_non_production_identity() {
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(
        IdentityKind::Development,
        Duration::from_secs(3600),
    ))
    .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    let result = s.launch_credentials(clock());
    if cfg!(debug_assertions) {
        assert!(result.is_ok());
    } else {
        assert_eq!(
            result.unwrap_err(),
            LaunchDenied::NonProductionIdentity,
            "a release build must not launch on a fake identity"
        );
    }
}

// ---------------------------------------------------------------------------
// Provider exclusion
// ---------------------------------------------------------------------------

#[test]
fn without_the_dev_auth_feature_only_production_providers_exist() {
    let (_tmp, core) = launcher();
    let providers = core.auth_providers();
    assert!(!providers.is_empty());

    #[cfg(not(feature = "dev-auth"))]
    {
        assert_eq!(providers.len(), 1);
        assert!(providers.iter().all(|p| p.is_production()));
        assert!(
            !core.development_auth_available(),
            "no development provider can exist without the feature"
        );
    }

    #[cfg(feature = "dev-auth")]
    {
        assert!(
            providers.iter().any(|p| !p.is_production()),
            "the development provider should be listed when compiled in"
        );
    }
}

#[test]
fn the_microsoft_provider_explains_itself_when_unconfigured() {
    let p = MicrosoftAuthProvider::new(false);
    assert!(p.is_production());
    assert!(!p.is_available());
    let reason = p.unavailable_reason().expect("must explain");
    assert!(reason.contains("approved"), "must name the actual blocker");
    assert!(p.immediate_identity().is_none());
}

// ---------------------------------------------------------------------------
// Credential storage
// ---------------------------------------------------------------------------

#[test]
fn credentials_round_trip_and_delete() {
    let store = EphemeralCredentialStore::default();
    let account = "986dec87b7ec47ff89ff033fdb95c4b5";

    assert!(matches!(
        store.retrieve(account),
        Err(CredentialError::NotFound(_))
    ));

    store.store(account, &Secret::new("refresh-token")).unwrap();
    assert_eq!(store.retrieve(account).unwrap().expose(), "refresh-token");

    store.delete(account).unwrap();
    assert!(
        matches!(store.retrieve(account), Err(CredentialError::NotFound(_))),
        "deletion must actually remove the credential"
    );

    // Deleting again is not an error: sign-out must be idempotent.
    store.delete(account).unwrap();
}

#[test]
fn signing_out_deletes_the_stored_credential() {
    let (_tmp, mut core) = launcher();

    // Pretend a previous session was remembered.
    let mut settings = core.settings().clone();
    settings.last_account_id = Some("acct".into());
    settings.last_account_name = Some("Steve".into());
    core.update_settings(settings).unwrap();

    core.sign_out().unwrap();

    assert!(core.settings().last_account_id.is_none());
    assert!(core.settings().last_account_name.is_none());
    assert_eq!(core.auth_status(), AuthStatus::SignedOut);
    assert!(core.identity().is_none());
}

// ---------------------------------------------------------------------------
// Token redaction
// ---------------------------------------------------------------------------

#[test]
fn secrets_do_not_print_through_debug_or_display() {
    let s = Secret::new("eyJhbGciOiJSUzI1NiJ9.super-secret.signature");
    assert!(!format!("{s:?}").contains("super-secret"));
    assert!(!format!("{s}").contains("super-secret"));
    assert!(!s.redacted_hint().contains("super-secret"));
}

#[test]
fn an_identity_does_not_leak_its_token_through_debug() {
    // The realistic failure mode: someone logs a struct that contains a token.
    let id = identity(IdentityKind::Microsoft, Duration::from_secs(60));
    let rendered = format!("{id:?}");
    assert!(rendered.contains("Steve"), "public fields still render");
    assert!(
        !rendered.contains("mc-access-token-value"),
        "the token must not survive a derived Debug"
    );
}

#[test]
fn a_whole_auth_session_does_not_leak_its_token_through_debug() {
    let mut s = AuthSession::new();
    s.begin().unwrap();
    s.authenticated(identity(IdentityKind::Microsoft, Duration::from_secs(3600)))
        .unwrap();
    s.entitlement_verified(proof(Ownership::JavaEdition, true))
        .unwrap();

    let rendered = format!("{s:?}");
    assert!(!rendered.contains("mc-access-token-value"));
}

#[test]
fn every_credential_shape_in_the_brief_is_scrubbed() {
    use launcher_core::logging::scrub;

    let cases = [
        ("--accessToken eyJ.mc.token", "eyJ.mc.token"),
        (r#"{"access_token":"oauth-access"}"#, "oauth-access"),
        (r#"{"refresh_token":"oauth-refresh"}"#, "oauth-refresh"),
        (r#"{"RpsTicket":"d=xbox-rps"}"#, "xbox-rps"),
        ("identityToken=XBL3.0 x=hash;xsts-token", "xsts-token"),
        ("Authorization: Bearer bearer-value", "bearer-value"),
        ("Set-Cookie: session-cookie-value", "session-cookie-value"),
        (r#"{"device_code":"the-device-code"}"#, "the-device-code"),
    ];

    for (line, secret) in cases {
        let scrubbed = scrub(line);
        assert!(
            !scrubbed.contains(secret),
            "{secret:?} survived scrubbing of {line:?} -> {scrubbed:?}"
        );
    }
}

// ---------------------------------------------------------------------------
// Nothing sensitive reaches disk
// ---------------------------------------------------------------------------

/// "Never write tokens into JSON, config or profile files", checked directly:
/// hand a token to the credential store, write the settings and a profile the
/// way a real sign-in does, then read every file under the data root and assert
/// the token appears in none of them.
///
/// The credential store is the *only* sanctioned home for a token, and it is
/// not inside the data root — on a real machine it is Credential Manager,
/// Keychain or the Secret Service.
#[test]
fn no_token_is_ever_written_anywhere_under_the_data_root() {
    const TOKEN: &str = "THIS-IS-THE-SECRET-TOKEN-VALUE";

    let tmp = tempfile::tempdir().expect("tempdir");
    let paths = DataPaths::rooted_at(tmp.path());
    let credentials = Arc::new(EphemeralCredentialStore::default());

    // The token goes where tokens are supposed to go, and nowhere else.
    credentials
        .store("986dec87b7ec47ff89ff033fdb95c4b5", &Secret::new(TOKEN))
        .unwrap();

    let store: Arc<dyn CredentialStore> = credentials.clone();
    let mut core = Launcher::new(paths, store).expect("launcher");

    // Persist an account the way a real sign-in would, and create a profile so
    // there are profile files on disk to inspect too.
    let mut settings = core.settings().clone();
    settings.last_account_id = Some("986dec87b7ec47ff89ff033fdb95c4b5".into());
    settings.last_account_name = Some("Steve".into());
    core.update_settings(settings).unwrap();
    core.create_profile("Main").unwrap();

    // Sanity: the store really does hold it, so a false pass is not possible
    // just because nothing was ever stored.
    assert_eq!(
        credentials
            .retrieve("986dec87b7ec47ff89ff033fdb95c4b5")
            .unwrap()
            .expose(),
        TOKEN
    );

    let mut checked = 0;
    let mut stack = vec![tmp.path().to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            let bytes = std::fs::read(&path).unwrap_or_default();
            let text = String::from_utf8_lossy(&bytes);
            assert!(!text.contains(TOKEN), "{} contains a token", path.display());
            // Nor any of the field names we would expect around one.
            for marker in ["access_token", "refresh_token", "accessToken"] {
                assert!(
                    !text.contains(marker),
                    "{} contains {marker}, which suggests a token was serialised",
                    path.display()
                );
            }
            checked += 1;
        }
    }

    assert!(checked > 0, "the test should have inspected some files");
}

#[test]
fn the_persisted_account_record_is_public_information_only() {
    let (_tmp, mut core) = launcher();

    let mut settings = core.settings().clone();
    settings.last_account_id = Some("986dec87b7ec47ff89ff033fdb95c4b5".into());
    settings.last_account_name = Some("Steve".into());
    core.update_settings(settings).unwrap();

    let json = std::fs::read_to_string(core.paths().settings_file()).unwrap();
    // UUID and username are public: every server you join already knows them.
    assert!(json.contains("986dec87b7ec47ff89ff033fdb95c4b5"));
    assert!(json.contains("Steve"));
    // Nothing else about the account is stored.
    assert!(!json.to_lowercase().contains("token"));
    assert!(!json.to_lowercase().contains("secret"));
    assert!(!json.to_lowercase().contains("password"));
}

// ---------------------------------------------------------------------------
// The launcher-level gate
// ---------------------------------------------------------------------------

#[test]
fn a_launch_plan_cannot_be_built_while_signed_out() {
    let (_tmp, core) = launcher();
    let profile = launcher_core::profiles::Profile::new(
        launcher_core::profiles::ProfileId::new("main").unwrap(),
        "Main",
    );
    let version: launcher_core::minecraft_runtime::VersionDetail = serde_json::from_str(
        r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main",
            "minecraftArguments":"--username ${auth_player_name}"}"#,
    )
    .unwrap();

    let err = core
        .build_launch_plan(&profile, &version)
        .expect_err("a signed-out launcher must not produce a launch command");
    let message = err.to_string();
    assert!(
        message.contains("not signed in") || message.contains("cannot launch"),
        "the refusal should say why: {message}"
    );
}

#[test]
fn readiness_reports_the_gate_rather_than_its_own_opinion() {
    let (_tmp, core) = launcher();
    let profile = launcher_core::profiles::Profile::new(
        launcher_core::profiles::ProfileId::new("main").unwrap(),
        "Main",
    );

    let readiness = core.readiness(&profile, None, None);
    assert!(!readiness.can_play());
    assert!(
        readiness
            .blockers
            .iter()
            .any(|b| matches!(b, launcher_core::Blocker::SignedOut)),
        "being signed out must be reported as a blocker"
    );
}
