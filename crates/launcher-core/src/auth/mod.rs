//! Microsoft sign-in and Minecraft: Java Edition ownership verification.
//!
//! The chain, from `security-model.md` §2.1:
//!
//! ```text
//! Microsoft OAuth (device code)  ->  Xbox Live  ->  XSTS
//!   ->  Minecraft Services login  ->  entitlements  ->  profile
//! ```
//!
//! Design decisions worth stating:
//!
//! - **Device code, not a loopback redirect.** The research offered both. Device
//!   code avoids binding a local HTTP listener, which is one less attack
//!   surface and one less firewall prompt, and it works on machines with no
//!   default browser. The user reads a code off our window and types it into
//!   Microsoft's page; we never see a password.
//! - **Only the refresh token is persisted**, and only in the OS credential
//!   store. Everything else lives in memory for the length of the session.
//! - **No offline mode, no fallback, no bypass.** If the entitlement check
//!   cannot complete, we do not launch.

pub mod account;
pub mod entitlement;
pub mod provider;
pub mod session;

/// A fake local identity for building the launcher while Microsoft application
/// approval is pending.
///
/// Behind a non-default feature, and `development.rs` contains a
/// `compile_error!` that fails the build if that feature is ever combined with
/// a release profile. See `docs/research/auth-feasibility.md` section 12.2.
#[cfg(feature = "dev-auth")]
pub mod development;

use serde::Deserialize;

use crate::credentials::{CredentialError, CredentialStore};
use crate::network::{
    Body, Destination, Feature, HttpClient, Method, NetworkError, Purpose, RequestDescriptor,
};
use crate::secret::Secret;

pub use entitlement::{EntitlementsResponse, Ownership};
pub use provider::{
    AuthProvider, EntitlementVerifier, IdentityKind, MicrosoftAuthProvider, MinecraftIdentity,
    MojangEntitlementVerifier,
};
pub use session::{
    AuthSession, AuthStage, AuthState, AuthStatus, EntitlementProof, LaunchCredentials,
    LaunchDenied, RefreshReason,
};

/// Scopes required by the Minecraft chain. `XboxLive.signin` is mandatory or
/// step 2 fails; `offline_access` is what yields the refresh token.
const SCOPE: &str = "XboxLive.signin offline_access";

/// The `consumers` tenant, not `common` and not an AAD tenant id.
const DEVICE_CODE_PATH: &str = "/consumers/oauth2/v2.0/devicecode";
const TOKEN_PATH: &str = "/consumers/oauth2/v2.0/token";

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error(
        "no Azure client id is configured. Microsoft requires each launcher to \
         register its own application and be approved for the Minecraft API \
         before sign-in can work."
    )]
    NoClientId,
    #[error(transparent)]
    Network(#[from] NetworkError),
    #[error("Microsoft rejected the sign-in: {code}{}", .description.as_deref().map(|d| format!(" ({d})")).unwrap_or_default())]
    OAuth {
        code: String,
        description: Option<String>,
    },
    #[error("sign-in was not completed in time")]
    DeviceCodeExpired,
    #[error("sign-in was declined")]
    Declined,
    #[error("{0}")]
    Xbox(#[from] XboxError),
    #[error(
        "this Microsoft account is not approved to use the Minecraft API, or \
         this launcher's Azure application has not been approved yet (HTTP 403)"
    )]
    NotApproved,
    #[error("Minecraft Services returned HTTP {status}")]
    MinecraftService { status: u16 },
    #[error(transparent)]
    Entitlement(#[from] entitlement::EntitlementError),
    #[error("this account is not entitled to Minecraft: Java Edition. {0}")]
    NotEntitled(String),
    #[error(
        "this account owns Minecraft but has no profile yet. Sign in to the \
         official Minecraft Launcher once to choose a username, then come back."
    )]
    NoProfile,
    #[error(transparent)]
    Credentials(#[from] CredentialError),
}

/// XSTS refusals that describe a legitimate account state rather than a bug.
/// `security-model.md` §2.3.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum XboxError {
    #[error("This account is banned from Xbox services.")]
    Banned,
    #[error(
        "This Microsoft account has no Xbox profile. Sign in at minecraft.net \
         once to create one, then try again."
    )]
    NoXboxAccount,
    #[error("Xbox Live is not available in this account's region.")]
    RegionUnavailable,
    #[error("This account needs adult verification on Xbox before it can be used.")]
    AdultVerificationRequired,
    #[error(
        "This is a child account. An adult must add it to a Microsoft family \
         group before it can sign in."
    )]
    ChildAccount,
    #[error("Xbox Live refused the sign-in (code {0}).")]
    Unknown(u64),
}

impl XboxError {
    /// Map an `XErr` value from a 401 response.
    pub fn from_xerr(xerr: u64) -> Self {
        match xerr {
            2_148_916_227 => XboxError::Banned,
            2_148_916_233 => XboxError::NoXboxAccount,
            2_148_916_235 => XboxError::RegionUnavailable,
            2_148_916_236 | 2_148_916_237 => XboxError::AdultVerificationRequired,
            2_148_916_238 => XboxError::ChildAccount,
            other => XboxError::Unknown(other),
        }
    }
}

/// What the user must do to complete a device-code sign-in.
#[derive(Debug, Clone)]
pub struct DeviceCodePrompt {
    /// Short code the user types on Microsoft's page.
    pub user_code: String,
    /// The page to open. Shown as a link; never opened automatically.
    pub verification_uri: String,
    /// Opaque handle we poll with. Not shown to the user.
    pub device_code: Secret,
    pub expires_in_secs: u64,
    /// Minimum seconds between polls, per the OAuth device flow spec.
    pub poll_interval_secs: u64,
}

/// The raw result of walking the Microsoft chain: an identity plus what the
/// entitlement check concluded.
///
/// Not itself a permission to launch. `services` feeds this into an
/// [`AuthSession`], and the launch gate lives there.
#[derive(Debug, Clone)]
pub struct Session {
    pub identity: provider::MinecraftIdentity,
    pub entitlement: EntitlementProof,
}

/// Public account information. Safe to persist: it is what other players see.
///
/// Exactly the shape of `GET /minecraft/profile`, minus nothing sensitive
/// because the endpoint returns nothing sensitive: a UUID, a username, and the
/// public addresses of the skins and capes other players already download.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
pub struct AccountProfile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub skins: Vec<ProfileTexture>,
    #[serde(default)]
    pub capes: Vec<ProfileTexture>,
}

/// One skin or cape on a Minecraft profile.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
pub struct ProfileTexture {
    #[serde(default)]
    pub id: String,
    /// `ACTIVE` for the one being worn.
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub url: String,
    /// `CLASSIC` or `SLIM`, for skins.
    #[serde(default)]
    pub variant: Option<String>,
    /// A cape's name, e.g. `Migrator`.
    #[serde(default)]
    pub alias: Option<String>,
}

impl ProfileTexture {
    pub fn is_active(&self) -> bool {
        self.state.eq_ignore_ascii_case("active")
    }
}

impl AccountProfile {
    /// The skin currently worn, if the profile has one. A profile with none
    /// wears the default Steve or Alex, which is drawn from the UUID instead.
    pub fn active_skin(&self) -> Option<&ProfileTexture> {
        self.skins.iter().find(|skin| skin.is_active())
    }

    pub fn active_cape(&self) -> Option<&ProfileTexture> {
        self.capes.iter().find(|cape| cape.is_active())
    }
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct DeviceCodeResponse {
    device_code: String,
    user_code: String,
    verification_uri: String,
    expires_in: u64,
    #[serde(default = "default_interval")]
    interval: u64,
}

fn default_interval() -> u64 {
    5
}

/// Microsoft's token endpoint response.
///
/// The two token fields are `Secret`, not `String`. They were `String` until
/// the security pass: this struct is `Debug`-derived, and a single
/// `tracing::debug!(?parsed)` anywhere on the sign-in path would have written a
/// live refresh token to the log. `Secret` deserializes, so the token is
/// wrapped from the moment it is parsed rather than from the moment somebody
/// remembers to wrap it.
#[derive(Debug, Deserialize)]
struct TokenResponse {
    #[serde(default)]
    access_token: Option<Secret>,
    #[serde(default)]
    refresh_token: Option<Secret>,
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    error_description: Option<String>,
}

/// An Xbox Live or XSTS authorisation response. See [`TokenResponse`] for why
/// the token is a `Secret` rather than a `String`.
#[derive(Debug, Deserialize)]
struct XboxAuthResponse {
    #[serde(rename = "Token")]
    token: Secret,
    #[serde(rename = "DisplayClaims", default)]
    display_claims: Option<DisplayClaims>,
}

#[derive(Debug, Deserialize)]
struct DisplayClaims {
    #[serde(default)]
    xui: Vec<XuiClaim>,
}

#[derive(Debug, Deserialize)]
struct XuiClaim {
    #[serde(default)]
    uhs: Option<String>,
}

#[derive(Debug, Deserialize)]
struct XstsErrorResponse {
    #[serde(rename = "XErr", default)]
    xerr: Option<u64>,
}

/// The Minecraft Services login response. See [`TokenResponse`] for why the
/// token is a `Secret` rather than a `String`.
#[derive(Debug, Deserialize)]
struct MinecraftLoginResponse {
    access_token: Secret,
    /// Seconds. Mojang issues 86400. Defaulted conservatively rather than
    /// assumed, so a missing field shortens the token's assumed life instead of
    /// extending it.
    #[serde(default = "conservative_token_lifetime")]
    expires_in: u64,
}

/// If Minecraft Services omits `expires_in`, assume a short life. Guessing high
/// would mean using a dead token and failing confusingly later; guessing low
/// only costs an extra refresh.
fn conservative_token_lifetime() -> u64 {
    300
}

/// The outcome of one poll of the device-code endpoint.
#[derive(Debug)]
pub enum PollOutcome {
    /// Still waiting for the user. Poll again after the interval.
    Pending,
    /// Microsoft asked us to slow down; increase the interval.
    SlowDown,
    /// Done. Contains the Microsoft refresh token.
    Complete {
        access_token: Secret,
        refresh_token: Secret,
    },
}

/// Azure application identity.
///
/// `security-model.md` §2.6: an unapproved application receives 403 from
/// `api.minecraftservices.com`, and approval takes weeks. There is deliberately
/// no default value and no bundled id — using someone else's would be both
/// dishonest and fragile.
#[derive(Debug, Clone, Default)]
pub struct AzureApp {
    pub client_id: Option<String>,
}

impl AzureApp {
    /// The client id: `FATHOM_AZURE_CLIENT_ID` at run time if set, otherwise
    /// the one a release build compiled in, otherwise none.
    ///
    /// A release for other people has to carry its approved registration --
    /// asking every tester to set an environment variable is not a sign-in
    /// flow. The release builder supplies it as
    /// `FATHOM_RELEASE_AZURE_CLIENT_ID` in the build's environment: a name of
    /// its own, so a developer's personal `FATHOM_AZURE_CLIENT_ID` is never
    /// baked into a binary by accident, and nothing about the id is written
    /// into this repository. A build made without it has none and says so.
    pub fn from_env() -> Self {
        let runtime = std::env::var("FATHOM_AZURE_CLIENT_ID").ok();
        let compiled = option_env!("FATHOM_RELEASE_AZURE_CLIENT_ID").map(str::to_owned);
        Self {
            client_id: runtime
                .filter(|s| !s.trim().is_empty())
                .or_else(|| compiled.filter(|s| !s.trim().is_empty())),
        }
    }

    pub fn is_configured(&self) -> bool {
        self.client_id.is_some()
    }

    fn require(&self) -> Result<&str, AuthError> {
        self.client_id.as_deref().ok_or(AuthError::NoClientId)
    }
}

/// Runs the authentication chain.
#[derive(Debug)]
pub struct AuthService {
    http: HttpClient,
    app: AzureApp,
}

impl AuthService {
    pub fn new(http: HttpClient, app: AzureApp) -> Self {
        Self { http, app }
    }

    pub fn app(&self) -> &AzureApp {
        &self.app
    }

    /// Step 1a: ask Microsoft for a device code.
    pub fn begin_device_code(&self, feature: Feature) -> Result<DeviceCodePrompt, AuthError> {
        let client_id = self.app.require()?;
        let request = RequestDescriptor::new(
            Destination::MicrosoftOAuth,
            Purpose::BeginSignIn,
            feature,
            DEVICE_CODE_PATH,
        );
        let response = self.http.send(
            &request,
            None,
            Method::Post,
            Body::Form(&[("client_id", client_id), ("scope", SCOPE)]),
            &[],
        )?;

        if !response.is_success() {
            let parsed: TokenResponse = response
                .json(Destination::MicrosoftOAuth.host())
                .unwrap_or(TokenResponse {
                    access_token: None,
                    refresh_token: None,
                    error: Some(format!("http_{}", response.status)),
                    error_description: None,
                });
            return Err(AuthError::OAuth {
                code: parsed.error.unwrap_or_else(|| "unknown".into()),
                description: parsed.error_description,
            });
        }

        let parsed: DeviceCodeResponse = response.json(Destination::MicrosoftOAuth.host())?;
        Ok(DeviceCodePrompt {
            user_code: parsed.user_code,
            verification_uri: parsed.verification_uri,
            device_code: Secret::new(parsed.device_code),
            expires_in_secs: parsed.expires_in,
            poll_interval_secs: parsed.interval.max(1),
        })
    }

    /// Step 1b: poll once. The caller owns the timing so the UI stays
    /// responsive and the user can cancel.
    pub fn poll_device_code(
        &self,
        prompt: &DeviceCodePrompt,
        feature: Feature,
    ) -> Result<PollOutcome, AuthError> {
        let client_id = self.app.require()?;
        let request = RequestDescriptor::new(
            Destination::MicrosoftOAuth,
            Purpose::ExchangeToken,
            feature,
            TOKEN_PATH,
        );
        let response = self.http.send(
            &request,
            None,
            Method::Post,
            Body::Form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", prompt.device_code.expose()),
            ]),
            &[],
        )?;

        let parsed: TokenResponse = response.json(Destination::MicrosoftOAuth.host())?;
        interpret_token_response(parsed)
    }

    /// Exchange a stored refresh token for a fresh access token.
    pub fn refresh(&self, refresh_token: &Secret) -> Result<PollOutcome, AuthError> {
        let client_id = self.app.require()?;
        let request = RequestDescriptor::new(
            Destination::MicrosoftOAuth,
            Purpose::ExchangeToken,
            Feature::SessionRefresh,
            TOKEN_PATH,
        );
        let response = self.http.send(
            &request,
            None,
            Method::Post,
            Body::Form(&[
                ("grant_type", "refresh_token"),
                ("client_id", client_id),
                ("refresh_token", refresh_token.expose()),
                ("scope", SCOPE),
            ]),
            &[],
        )?;
        let parsed: TokenResponse = response.json(Destination::MicrosoftOAuth.host())?;
        interpret_token_response(parsed)
    }

    /// Steps 2 through 6: Microsoft token in, identity and entitlement out.
    ///
    /// Deliberately does **not** decide whether the user may launch. It reports
    /// what the entitlement check concluded and leaves the decision to
    /// [`AuthSession`], so there is exactly one place in the codebase that
    /// grants permission to start the game.
    pub fn establish_session(
        &self,
        microsoft_access_token: &Secret,
        feature: Feature,
    ) -> Result<Session, AuthError> {
        let (xbl_token, user_hash) = self.xbox_authenticate(microsoft_access_token, feature)?;
        let xsts_token = self.xsts_authorize(&xbl_token, feature)?;
        let (mc_token, lifetime) = self.minecraft_login(&xsts_token, &user_hash, feature)?;

        // Entitlement, via the abstraction rather than inline: the verifier
        // fails closed on anything unsigned, expired or malformed.
        let entitlement = self.verify_ownership(&mc_token, feature)?;

        let profile = self.fetch_profile(&mc_token, feature)?;
        Ok(Session {
            identity: provider::MinecraftIdentity {
                profile,
                access_token: mc_token,
                kind: provider::IdentityKind::Microsoft,
                expires_at: std::time::SystemTime::now() + lifetime,
            },
            entitlement,
        })
    }

    fn xbox_authenticate(
        &self,
        microsoft_access_token: &Secret,
        feature: Feature,
    ) -> Result<(Secret, String), AuthError> {
        let request = RequestDescriptor::new(
            Destination::XboxUserAuth,
            Purpose::ExchangeToken,
            feature,
            "/user/authenticate",
        );
        let body = serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={}", microsoft_access_token.expose()),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        });
        let response = self
            .http
            .send(&request, None, Method::Post, Body::Json(&body), &[])?;
        if !response.is_success() {
            return Err(AuthError::Xbox(XboxError::Unknown(u64::from(
                response.status,
            ))));
        }
        let parsed: XboxAuthResponse = response.json(Destination::XboxUserAuth.host())?;
        let user_hash = parsed
            .display_claims
            .and_then(|c| c.xui.into_iter().next())
            .and_then(|x| x.uhs)
            .ok_or(AuthError::Xbox(XboxError::NoXboxAccount))?;
        Ok((parsed.token, user_hash))
    }

    fn xsts_authorize(&self, xbl_token: &Secret, feature: Feature) -> Result<Secret, AuthError> {
        let request = RequestDescriptor::new(
            Destination::XboxXsts,
            Purpose::ExchangeToken,
            feature,
            "/xsts/authorize",
        );
        let body = serde_json::json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token.expose()] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        });
        let response = self
            .http
            .send(&request, None, Method::Post, Body::Json(&body), &[])?;

        if response.status == 401 {
            let parsed: XstsErrorResponse = response
                .json(Destination::XboxXsts.host())
                .unwrap_or(XstsErrorResponse { xerr: None });
            let xerr = parsed.xerr.unwrap_or(0);
            return Err(AuthError::Xbox(XboxError::from_xerr(xerr)));
        }
        if !response.is_success() {
            return Err(AuthError::Xbox(XboxError::Unknown(u64::from(
                response.status,
            ))));
        }
        let parsed: XboxAuthResponse = response.json(Destination::XboxXsts.host())?;
        Ok(parsed.token)
    }

    fn minecraft_login(
        &self,
        xsts_token: &Secret,
        user_hash: &str,
        feature: Feature,
    ) -> Result<(Secret, std::time::Duration), AuthError> {
        let request = RequestDescriptor::new(
            Destination::MinecraftServices,
            Purpose::ExchangeToken,
            feature,
            "/authentication/login_with_xbox",
        );
        let identity = format!("XBL3.0 x={user_hash};{}", xsts_token.expose());
        let body = serde_json::json!({ "identityToken": identity });
        let response = self
            .http
            .send(&request, None, Method::Post, Body::Json(&body), &[])?;

        if response.status == 403 {
            return Err(AuthError::NotApproved);
        }
        if !response.is_success() {
            return Err(AuthError::MinecraftService {
                status: response.status,
            });
        }
        let parsed: MinecraftLoginResponse =
            response.json(Destination::MinecraftServices.host())?;
        Ok((
            parsed.access_token,
            std::time::Duration::from_secs(parsed.expires_in),
        ))
    }

    /// Step 5: the entitlement check. Signature verified before the list is
    /// read, and delegated to an [`EntitlementVerifier`] so the policy is
    /// testable in isolation.
    fn verify_ownership(
        &self,
        mc_token: &Secret,
        feature: Feature,
    ) -> Result<EntitlementProof, AuthError> {
        let request = RequestDescriptor::new(
            Destination::MinecraftServices,
            Purpose::VerifyEntitlement,
            feature,
            "/entitlements/mcstore",
        );
        let bearer = Secret::new(format!("Bearer {}", mc_token.expose()));
        let response = self.http.send(
            &request,
            None,
            Method::Get,
            Body::Empty,
            &[("Authorization", &bearer)],
        )?;
        if response.status == 403 {
            return Err(AuthError::NotApproved);
        }
        if !response.is_success() {
            return Err(AuthError::MinecraftService {
                status: response.status,
            });
        }

        let envelope: EntitlementsResponse =
            response.json(Destination::MinecraftServices.host())?;
        Ok(provider::MojangEntitlementVerifier.verify(&envelope)?)
    }

    fn fetch_profile(
        &self,
        mc_token: &Secret,
        feature: Feature,
    ) -> Result<AccountProfile, AuthError> {
        let request = RequestDescriptor::new(
            Destination::MinecraftServices,
            Purpose::FetchProfile,
            feature,
            "/minecraft/profile",
        );
        let bearer = Secret::new(format!("Bearer {}", mc_token.expose()));
        let response = self.http.send(
            &request,
            None,
            Method::Get,
            Body::Empty,
            &[("Authorization", &bearer)],
        )?;

        // The documented Game Pass case: entitled, but no username chosen yet.
        if response.status == 404 {
            return Err(AuthError::NoProfile);
        }
        if !response.is_success() {
            return Err(AuthError::MinecraftService {
                status: response.status,
            });
        }
        Ok(response.json(Destination::MinecraftServices.host())?)
    }
}

impl AuthService {
    /// Download a skin image from Mojang's texture host.
    ///
    /// Takes the URL from the profile response, which is untrusted input: it
    /// must name `textures.minecraft.net` and a hex texture id, or nothing is
    /// fetched. No credential is sent — skins are public.
    pub fn fetch_skin(&self, url: &str, feature: Feature) -> Result<Vec<u8>, AuthError> {
        let path = account::texture_path(url).ok_or(AuthError::MinecraftService { status: 0 })?;
        let request = RequestDescriptor::new(
            Destination::MinecraftTextures,
            Purpose::FetchProfile,
            feature,
            path,
        );
        let response = self
            .http
            .send(&request, None, Method::Get, Body::Empty, &[])?;
        if !response.is_success() {
            return Err(AuthError::MinecraftService {
                status: response.status,
            });
        }
        if response.body.len() > account::MAX_SKIN_BYTES {
            return Err(AuthError::MinecraftService { status: 0 });
        }
        Ok(response.body)
    }
}

fn interpret_token_response(parsed: TokenResponse) -> Result<PollOutcome, AuthError> {
    if let Some(error) = parsed.error.as_deref() {
        return match error {
            "authorization_pending" => Ok(PollOutcome::Pending),
            "slow_down" => Ok(PollOutcome::SlowDown),
            "expired_token" | "code_expired" => Err(AuthError::DeviceCodeExpired),
            "authorization_declined" | "access_denied" => Err(AuthError::Declined),
            other => Err(AuthError::OAuth {
                code: other.to_owned(),
                description: parsed.error_description,
            }),
        };
    }
    match (parsed.access_token, parsed.refresh_token) {
        (Some(access), Some(refresh)) => Ok(PollOutcome::Complete {
            access_token: access,
            refresh_token: refresh,
        }),
        (Some(_), None) => Err(AuthError::OAuth {
            code: "no_refresh_token".to_owned(),
            description: Some(
                "Microsoft did not return a refresh token; the offline_access scope is missing"
                    .to_owned(),
            ),
        }),
        _ => Err(AuthError::OAuth {
            code: "malformed_response".to_owned(),
            description: None,
        }),
    }
}

/// Persist the refresh token, keyed by the account's UUID.
pub fn remember_session(
    store: &dyn CredentialStore,
    profile: &AccountProfile,
    refresh_token: &Secret,
) -> Result<(), AuthError> {
    store.store(&profile.id, refresh_token)?;
    Ok(())
}

/// Forget a signed-in account. Removes the only persisted secret.
pub fn forget_session(store: &dyn CredentialStore, account_id: &str) -> Result<(), AuthError> {
    store.delete(account_id)?;
    Ok(())
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
    use crate::credentials::EphemeralCredentialStore;
    use crate::network::{NetworkJournal, NetworkPolicy};

    fn service(app: AzureApp) -> AuthService {
        AuthService::new(
            HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0.0"),
            app,
        )
    }

    #[test]
    fn every_xerr_from_the_research_maps_to_a_specific_explanation() {
        let cases = [
            (2_148_916_227_u64, XboxError::Banned),
            (2_148_916_233, XboxError::NoXboxAccount),
            (2_148_916_235, XboxError::RegionUnavailable),
            (2_148_916_236, XboxError::AdultVerificationRequired),
            (2_148_916_237, XboxError::AdultVerificationRequired),
            (2_148_916_238, XboxError::ChildAccount),
        ];
        for (code, expected) in cases {
            assert_eq!(XboxError::from_xerr(code), expected, "XErr {code}");
            // The user-facing string must be an explanation, not a number.
            assert!(expected.to_string().len() > 20);
        }
        assert_eq!(XboxError::from_xerr(1), XboxError::Unknown(1));
    }

    #[test]
    fn sign_in_without_a_client_id_fails_with_a_clear_explanation() {
        let svc = service(AzureApp::default());
        let err = svc
            .begin_device_code(Feature::AccountSignIn)
            .expect_err("must refuse without a client id");
        assert!(matches!(err, AuthError::NoClientId));
        // Nothing was sent.
        assert_eq!(svc.http.journal().summary().total, 0);
    }

    #[test]
    fn pending_and_slow_down_are_not_failures() {
        let pending = TokenResponse {
            access_token: None,
            refresh_token: None,
            error: Some("authorization_pending".into()),
            error_description: None,
        };
        assert!(matches!(
            interpret_token_response(pending),
            Ok(PollOutcome::Pending)
        ));

        let slow = TokenResponse {
            access_token: None,
            refresh_token: None,
            error: Some("slow_down".into()),
            error_description: None,
        };
        assert!(matches!(
            interpret_token_response(slow),
            Ok(PollOutcome::SlowDown)
        ));
    }

    #[test]
    fn declined_and_expired_are_distinct_errors() {
        let declined = TokenResponse {
            access_token: None,
            refresh_token: None,
            error: Some("authorization_declined".into()),
            error_description: None,
        };
        assert!(matches!(
            interpret_token_response(declined),
            Err(AuthError::Declined)
        ));

        let expired = TokenResponse {
            access_token: None,
            refresh_token: None,
            error: Some("expired_token".into()),
            error_description: None,
        };
        assert!(matches!(
            interpret_token_response(expired),
            Err(AuthError::DeviceCodeExpired)
        ));
    }

    #[test]
    fn a_response_without_a_refresh_token_is_rejected() {
        // Would mean offline_access was dropped; silently continuing would
        // force a full sign-in every launch with no explanation.
        let no_refresh = TokenResponse {
            access_token: Some("a".into()),
            refresh_token: None,
            error: None,
            error_description: None,
        };
        assert!(matches!(
            interpret_token_response(no_refresh),
            Err(AuthError::OAuth { .. })
        ));
    }

    #[test]
    fn a_complete_response_yields_both_tokens() {
        let complete = TokenResponse {
            access_token: Some("access".into()),
            refresh_token: Some("refresh".into()),
            error: None,
            error_description: None,
        };
        match interpret_token_response(complete).unwrap() {
            PollOutcome::Complete {
                access_token,
                refresh_token,
            } => {
                assert_eq!(access_token.expose(), "access");
                assert_eq!(refresh_token.expose(), "refresh");
            }
            other => panic!("expected Complete, got {other:?}"),
        }
    }

    #[test]
    fn only_the_refresh_token_is_persisted() {
        let store = EphemeralCredentialStore::default();
        let profile = AccountProfile {
            id: "986dec87b7ec47ff89ff033fdb95c4b5".into(),
            name: "Steve".into(),
            ..AccountProfile::default()
        };
        remember_session(&store, &profile, &Secret::new("the-refresh-token")).unwrap();

        assert_eq!(
            store.retrieve(&profile.id).unwrap().expose(),
            "the-refresh-token"
        );
        // No access token, no XSTS token, nothing keyed by name.
        assert!(store.retrieve("Steve").is_err());

        forget_session(&store, &profile.id).unwrap();
        assert!(store.retrieve(&profile.id).is_err());
    }

    #[test]
    fn the_device_code_prompt_keeps_the_device_code_secret() {
        let prompt = DeviceCodePrompt {
            user_code: "ABCD-EFGH".into(),
            verification_uri: "https://microsoft.com/link".into(),
            device_code: Secret::new("opaque-device-code"),
            expires_in_secs: 900,
            poll_interval_secs: 5,
        };
        let rendered = format!("{prompt:?}");
        assert!(
            rendered.contains("ABCD-EFGH"),
            "the user code is meant to be shown"
        );
        assert!(
            !rendered.contains("opaque-device-code"),
            "the device code must not appear in logs"
        );
    }

    #[test]
    fn azure_app_reports_whether_it_is_configured() {
        assert!(!AzureApp::default().is_configured());
        assert!(AzureApp {
            client_id: Some("00000000-0000-0000-0000-000000000000".into())
        }
        .is_configured());
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod live {
    use super::*;
    use crate::network::{NetworkJournal, NetworkPolicy};

    /// Asks Microsoft for a device code with this build's application id and
    /// checks the answer has the shape the launcher opens. Polls nothing, so
    /// no sign-in happens; the code simply expires.
    ///
    /// Run with `cargo test -p launcher-core -- --ignored device_code_live`.
    #[test]
    #[ignore = "reaches login.microsoftonline.com; needs FATHOM_AZURE_CLIENT_ID"]
    fn device_code_live_request_is_accepted() {
        let app = AzureApp::from_env();
        if !app.is_configured() {
            eprintln!("skipped: no client id");
            return;
        }
        let service = AuthService::new(
            HttpClient::new(
                NetworkPolicy::default(),
                NetworkJournal::new(),
                "fathom-test",
            ),
            app,
        );
        let prompt = service
            .begin_device_code(Feature::AccountSignIn)
            .expect("a device code");
        assert!(prompt.verification_uri.starts_with("https://"));
        assert!(prompt.expires_in_secs >= 60);
        assert!(!prompt.user_code.is_empty());
        eprintln!(
            "verification page host ok: {}; code length {}; expires in {}s",
            prompt.verification_uri,
            prompt.user_code.len(),
            prompt.expires_in_secs
        );
    }
}
