//! The network policy: a compile-time allowlist of everywhere this program is
//! permitted to talk to, and why.
//!
//! The design rule is that **callers cannot express a URL**. They name a
//! [`Destination`] variant and a path. There is no code path from user input,
//! remote metadata, or a mod manifest to an arbitrary host. Adding a
//! destination is a reviewable diff against this file, which is exactly what
//! `security-model.md` §5 asks for.

use std::fmt;

/// Every host this launcher may contact. There is no `Other(String)` variant,
/// and there must never be one.
///
/// Source: `docs/research/security-model.md` §5 "Network egress allowlist".
/// Serialisable so a mod catalogue entry can name its download host by
/// variant rather than by URL. Serde uses the variant name, not the hostname,
/// which keeps the stored form stable if a host ever changes.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "kebab-case")]
pub enum Destination {
    /// Microsoft identity platform. Device-code start and token polling.
    MicrosoftOAuth,
    /// Xbox Live user authentication (step 2 of the ownership chain).
    XboxUserAuth,
    /// XSTS authorization (step 3 of the ownership chain).
    XboxXsts,
    /// Minecraft Services: `login_with_xbox`, entitlements, profile.
    MinecraftServices,
    /// Version manifest and per-version JSON.
    PistonMeta,
    /// Mojang's older metadata host. 1.8.9's own version document still points
    /// its `assetIndex` at this hostname, so the newer `piston-meta` is not a
    /// complete replacement for the versions we target.
    LauncherMeta,
    /// Client jar and most libraries.
    PistonData,
    /// Legacy library/asset host still referenced by 1.8.9 metadata.
    LauncherMojang,
    /// Library artifacts referenced by older version JSON.
    LibrariesMinecraft,
    /// Asset objects, addressed by SHA-1 prefix.
    MinecraftResources,
    /// Our own static release host. Update manifest and artifacts only.
    LauncherUpdates,
    /// Java vendor API, used only if the user opts into managed Java.
    JavaVendor,
    /// Legacy Fabric's metadata service: which loader versions exist for a
    /// Minecraft version, and what each one needs.
    LegacyFabricMeta,
    /// Legacy Fabric's Maven repository. Serves the intermediary mappings, and
    /// redirects artifact requests to [`Destination::LegacyFabricRepo`].
    LegacyFabricMaven,
    /// Where `maven.legacyfabric.net` redirects artifact downloads to. Listed in
    /// its own right because redirects are followed by hand and every hop is
    /// re-checked against this allowlist -- an unlisted redirect target is a
    /// refused download, not a silent follow.
    LegacyFabricRepo,
    /// Fabric's own Maven. Legacy Fabric's metadata points the loader jar and
    /// its shared libraries here rather than mirroring them.
    FabricMaven,
    /// Modrinth API. Only ever on an explicit, per-mod user action.
    ModrinthApi,
    /// Modrinth CDN. Only ever on an explicit, per-mod user action.
    ModrinthCdn,
}

impl Destination {
    /// Every destination, for display in the Privacy screen and for the
    /// allowlist test.
    pub const ALL: &'static [Destination] = &[
        Destination::MicrosoftOAuth,
        Destination::XboxUserAuth,
        Destination::XboxXsts,
        Destination::MinecraftServices,
        Destination::PistonMeta,
        Destination::LauncherMeta,
        Destination::PistonData,
        Destination::LauncherMojang,
        Destination::LibrariesMinecraft,
        Destination::MinecraftResources,
        Destination::LauncherUpdates,
        Destination::JavaVendor,
        Destination::LegacyFabricMeta,
        Destination::LegacyFabricMaven,
        Destination::LegacyFabricRepo,
        Destination::FabricMaven,
        Destination::ModrinthApi,
        Destination::ModrinthCdn,
    ];

    /// The single hostname this destination resolves to.
    pub fn host(self) -> &'static str {
        match self {
            Destination::MicrosoftOAuth => "login.microsoftonline.com",
            Destination::XboxUserAuth => "user.auth.xboxlive.com",
            Destination::XboxXsts => "xsts.auth.xboxlive.com",
            Destination::MinecraftServices => "api.minecraftservices.com",
            Destination::PistonMeta => "piston-meta.mojang.com",
            Destination::LauncherMeta => "launchermeta.mojang.com",
            Destination::PistonData => "piston-data.mojang.com",
            Destination::LauncherMojang => "launcher.mojang.com",
            Destination::LibrariesMinecraft => "libraries.minecraft.net",
            Destination::MinecraftResources => "resources.download.minecraft.net",
            Destination::LauncherUpdates => "releases.invalid",
            Destination::JavaVendor => "api.adoptium.net",
            Destination::LegacyFabricMeta => "meta.legacyfabric.net",
            Destination::LegacyFabricMaven => "maven.legacyfabric.net",
            Destination::LegacyFabricRepo => "repo.legacyfabric.net",
            Destination::FabricMaven => "maven.fabricmc.net",
            Destination::ModrinthApi => "api.modrinth.com",
            Destination::ModrinthCdn => "cdn.modrinth.com",
        }
    }

    /// Plain-language explanation shown in the Privacy / Network screen.
    ///
    /// This exists so the user never has to read our source to know why a
    /// hostname appeared in their firewall log.
    pub fn rationale(self) -> &'static str {
        match self {
            Destination::MicrosoftOAuth => {
                "Signs you in with Microsoft. We never see your password; the code you enter goes \
                 to Microsoft directly."
            }
            Destination::XboxUserAuth => {
                "Exchanges your Microsoft token for an Xbox Live token. Required by Mojang's login \
                 chain."
            }
            Destination::XboxXsts => {
                "Authorises that Xbox Live token for Minecraft. Required by Mojang's login chain."
            }
            Destination::MinecraftServices => {
                "Confirms you own Minecraft: Java Edition, and fetches your username and UUID."
            }
            Destination::PistonMeta => "Mojang's list of game versions and what each one needs.",
            Destination::LauncherMeta => {
                "Mojang's older metadata host. 1.8.9's asset index is still served from here."
            }
            Destination::PistonData => "Mojang's download host for the game client and libraries.",
            Destination::LauncherMojang => {
                "Mojang's older download host. Some 1.8.9 libraries are still served from here."
            }
            Destination::LibrariesMinecraft => {
                "Mojang's library host, referenced by 1.8.9's version metadata."
            }
            Destination::MinecraftResources => {
                "Mojang's asset host: sounds, language files, and textures."
            }
            Destination::LauncherUpdates => {
                "Checks whether a newer version of this launcher exists. No identifier is sent. \
                 Can be turned off."
            }
            Destination::JavaVendor => {
                "Downloads a Java runtime, only if you asked us to manage Java for you."
            }
            Destination::LegacyFabricMeta => {
                "Legacy Fabric's own metadata service. Asked which loader versions exist for your \
                 Minecraft version, and what each one needs, when you choose that loader."
            }
            Destination::LegacyFabricMaven | Destination::LegacyFabricRepo => {
                "Legacy Fabric's own artifact repository. Downloads the mappings the loader needs."
            }
            Destination::FabricMaven => {
                "Fabric's own artifact repository. Legacy Fabric's metadata points the loader jar \
                 and its libraries here rather than mirroring them."
            }
            Destination::ModrinthApi | Destination::ModrinthCdn => {
                "Looks up and downloads a mod, only when you explicitly ask for that specific mod."
            }
        }
    }

    /// Whether reaching this destination requires a deliberate, per-action user
    /// decision rather than merely being part of a core flow.
    pub fn consent(self) -> Consent {
        match self {
            Destination::ModrinthApi | Destination::ModrinthCdn => Consent::ExplicitPerAction,
            Destination::JavaVendor => Consent::OptIn,
            Destination::LauncherUpdates => Consent::OptOut,
            // Everything else is implied by an action the user already took.
            //
            // That includes the Legacy Fabric and Fabric hosts: they are only
            // reached because the user set this profile's loader, and once that
            // choice is made, fetching what the loader needs is the same kind
            // of act as fetching the game's own libraries.
            _ => Consent::CoreFlow,
        }
    }
}

impl fmt::Display for Destination {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.host())
    }
}

/// How a destination becomes reachable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Consent {
    /// Part of signing in, installing, or launching. Implied by the action the
    /// user already took.
    CoreFlow,
    /// Happens unless the user turns it off (currently: the update check).
    OptOut,
    /// Happens only if the user turned it on (currently: managed Java).
    OptIn,
    /// Requires a fresh, specific decision every time (currently: mods).
    ExplicitPerAction,
}

/// Why a request is being made. Paired with [`Feature`] this gives every
/// request a two-part justification that shows up in the journal.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Purpose {
    /// Starting a device-code sign-in.
    BeginSignIn,
    /// Polling for, or refreshing, an OAuth token.
    ExchangeToken,
    /// Proving Minecraft: Java Edition ownership.
    VerifyEntitlement,
    /// Fetching the public profile (username, UUID) of the signed-in account.
    FetchProfile,
    /// Reading Mojang's version metadata.
    ResolveGameMetadata,
    /// Downloading a game artifact (client jar, library, asset, natives).
    DownloadGameArtifact,
    /// Downloading a Java runtime.
    DownloadJavaRuntime,
    /// Checking for or downloading a launcher update.
    LauncherUpdate,
    /// Reading a loader's metadata: which versions exist, what they need.
    ResolveLoaderMetadata,
    /// Downloading a loader's own jar, mappings or libraries.
    DownloadLoaderArtifact,
    /// Looking up mod metadata the user asked about.
    ResolveModMetadata,
    /// Downloading a mod the user explicitly chose.
    DownloadMod,
}

impl Purpose {
    pub fn label(self) -> &'static str {
        match self {
            Purpose::BeginSignIn => "Begin sign-in",
            Purpose::ExchangeToken => "Exchange token",
            Purpose::VerifyEntitlement => "Verify entitlement",
            Purpose::FetchProfile => "Fetch profile",
            Purpose::ResolveGameMetadata => "Resolve game metadata",
            Purpose::DownloadGameArtifact => "Download game artifact",
            Purpose::DownloadJavaRuntime => "Download Java runtime",
            Purpose::LauncherUpdate => "Launcher update",
            Purpose::ResolveLoaderMetadata => "Resolve loader metadata",
            Purpose::DownloadLoaderArtifact => "Download loader artifact",
            Purpose::ResolveModMetadata => "Resolve mod metadata",
            Purpose::DownloadMod => "Download mod",
        }
    }
}

/// The user-visible feature that initiated a request. Answers "I clicked one
/// button, why did three hosts get contacted?".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Feature {
    /// The Account screen's sign-in button.
    AccountSignIn,
    /// Silent re-authentication using the stored refresh token.
    SessionRefresh,
    /// Installing or repairing a profile's game files.
    ProfileInstall,
    /// Pressing Play.
    Play,
    /// The Settings screen's Java management.
    JavaManagement,
    /// The launcher's own update check.
    SelfUpdate,
    /// The Mods screen, acting on a specific mod the user named.
    ModBrowse,
}

impl Feature {
    pub fn label(self) -> &'static str {
        match self {
            Feature::AccountSignIn => "Account sign-in",
            Feature::SessionRefresh => "Session refresh",
            Feature::ProfileInstall => "Profile install",
            Feature::Play => "Play",
            Feature::JavaManagement => "Java management",
            Feature::SelfUpdate => "Self update",
            Feature::ModBrowse => "Mods",
        }
    }
}

/// A fully justified outbound request. Constructing one of these is the only
/// way to reach the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestDescriptor {
    pub destination: Destination,
    pub purpose: Purpose,
    pub feature: Feature,
    /// Absolute path beginning with `/`. Never a full URL.
    pub path: String,
}

impl RequestDescriptor {
    pub fn new(
        destination: Destination,
        purpose: Purpose,
        feature: Feature,
        path: impl Into<String>,
    ) -> Self {
        Self {
            destination,
            purpose,
            feature,
            path: path.into(),
        }
    }

    /// The URL this descriptor resolves to. Always HTTPS, always a host from
    /// the allowlist.
    pub fn url(&self) -> String {
        format!("https://{}{}", self.destination.host(), self.path)
    }
}

/// Runtime switches layered on top of the compile-time allowlist.
///
/// Four independent booleans on purpose; see `configuration::PrivacySettings`.
#[allow(clippy::struct_excessive_bools)]
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NetworkPolicy {
    /// Master switch. When false, every request is denied.
    pub online: bool,
    /// Whether the update check may run.
    pub allow_update_check: bool,
    /// Whether managed-Java downloads are permitted.
    pub allow_managed_java: bool,
    /// Whether mod-repository access is permitted at all. Even when true, each
    /// request still needs a per-action grant.
    pub allow_mod_repositories: bool,
}

impl Default for NetworkPolicy {
    fn default() -> Self {
        Self {
            online: true,
            // security-model.md §5: the update check is the one request that
            // may happen without the user asking, and it must be disableable.
            allow_update_check: true,
            // Opt-in: we do not download a JRE unless asked.
            allow_managed_java: false,
            // The user has to turn this on before mods can be fetched at all.
            allow_mod_repositories: false,
        }
    }
}

/// Why a request was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum PolicyDenial {
    #[error("the launcher is in offline mode")]
    Offline,
    #[error("update checks are turned off in Settings")]
    UpdateCheckDisabled,
    #[error("managed Java downloads are turned off in Settings")]
    ManagedJavaDisabled,
    #[error("mod repository access is turned off in Settings")]
    ModRepositoriesDisabled,
    #[error("this request needs an explicit action for this specific item")]
    MissingPerActionGrant,
    #[error("request path must be absolute and must not contain a scheme or host")]
    MalformedPath,
}

/// A one-shot capability proving the user made a specific choice, for
/// destinations whose consent is [`Consent::ExplicitPerAction`].
///
/// It is deliberately not `Clone` and not constructible from deserialised data:
/// remote content cannot mint one.
#[derive(Debug)]
pub struct PerActionGrant {
    reason: String,
}

impl PerActionGrant {
    /// Record that the user asked for this specific thing.
    pub fn user_requested(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
        }
    }

    pub fn reason(&self) -> &str {
        &self.reason
    }
}

impl NetworkPolicy {
    /// Decide whether a request may proceed.
    pub fn evaluate(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
    ) -> Result<(), PolicyDenial> {
        if !is_safe_path(&request.path) {
            return Err(PolicyDenial::MalformedPath);
        }
        if !self.online {
            return Err(PolicyDenial::Offline);
        }
        match request.destination.consent() {
            Consent::CoreFlow => Ok(()),
            Consent::OptOut => {
                if self.allow_update_check {
                    Ok(())
                } else {
                    Err(PolicyDenial::UpdateCheckDisabled)
                }
            }
            Consent::OptIn => {
                if self.allow_managed_java {
                    Ok(())
                } else {
                    Err(PolicyDenial::ManagedJavaDisabled)
                }
            }
            Consent::ExplicitPerAction => {
                if !self.allow_mod_repositories {
                    return Err(PolicyDenial::ModRepositoriesDisabled);
                }
                if grant.is_none() {
                    return Err(PolicyDenial::MissingPerActionGrant);
                }
                Ok(())
            }
        }
    }
}

/// Why an absolute URL from remote metadata was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum UrlRejection {
    #[error("only https URLs are accepted, got {0:?}")]
    NotHttps(String),
    #[error("host {0:?} is not on the allowlist")]
    HostNotAllowed(String),
    #[error("URL contains credentials, a port, or another disallowed element: {0:?}")]
    Malformed(String),
}

impl Destination {
    /// Look up a destination by exact hostname.
    pub fn from_host(host: &str) -> Option<Destination> {
        Destination::ALL
            .iter()
            .copied()
            .find(|d| d.host().eq_ignore_ascii_case(host))
    }
}

/// Turn an absolute URL that arrived in remote metadata into a descriptor.
///
/// Mojang's version JSON contains absolute URLs for every library and asset.
/// Those documents are untrusted input: a tampered manifest could point a
/// "library" at an attacker's host. This function is the chokepoint that makes
/// that impossible — an unrecognised host is refused rather than fetched.
pub fn descriptor_from_url(
    url: &str,
    purpose: Purpose,
    feature: Feature,
) -> Result<RequestDescriptor, UrlRejection> {
    let rest = url
        .strip_prefix("https://")
        .ok_or_else(|| UrlRejection::NotHttps(url.to_owned()))?;

    let (authority, path) = match rest.find('/') {
        Some(i) => (
            rest.get(..i).unwrap_or_default(),
            rest.get(i..).unwrap_or("/"),
        ),
        None => (rest, "/"),
    };

    // No userinfo, no port, no empty host, no wildcards.
    if authority.is_empty()
        || authority.contains('@')
        || authority.contains(':')
        || authority.contains('*')
    {
        return Err(UrlRejection::Malformed(url.to_owned()));
    }
    // Strip any query or fragment: we never need them and they are a place for
    // an identifier to hide.
    let path = path
        .split(['?', '#'])
        .next()
        .filter(|p| !p.is_empty())
        .unwrap_or("/");

    if !is_safe_path(path) {
        return Err(UrlRejection::Malformed(url.to_owned()));
    }

    let destination = Destination::from_host(authority)
        .ok_or_else(|| UrlRejection::HostNotAllowed(authority.to_owned()))?;

    Ok(RequestDescriptor::new(destination, purpose, feature, path))
}

/// Whether a path may be used in a request. Public so the redirect follower in
/// [`crate::network::http`] can validate a `Location` header that keeps the same
/// host but changes the path.
pub fn path_is_acceptable(path: &str) -> bool {
    is_safe_path(path)
}

/// Reject anything that could smuggle a different host into the URL we build.
///
/// `RequestDescriptor::url` concatenates `https://<host>` with this path, so a
/// path beginning `//evil.example` or containing a scheme would redirect the
/// request. Backslashes are rejected because some parsers normalise them to
/// forward slashes.
fn is_safe_path(path: &str) -> bool {
    if !path.starts_with('/') || path.starts_with("//") {
        return false;
    }
    if path.contains('\\') || path.contains("://") {
        return false;
    }
    if path.contains("/../") || path.ends_with("/..") {
        return false;
    }
    !path.chars().any(|c| c.is_control() || c == ' ')
}

#[cfg(test)]
#[allow(clippy::indexing_slicing, clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    fn core_request() -> RequestDescriptor {
        RequestDescriptor::new(
            Destination::PistonMeta,
            Purpose::ResolveGameMetadata,
            Feature::ProfileInstall,
            "/mc/game/version_manifest_v2.json",
        )
    }

    #[test]
    fn every_destination_has_a_distinct_host_and_a_rationale() {
        let mut hosts: Vec<&str> = Destination::ALL.iter().map(|d| d.host()).collect();
        let before = hosts.len();
        hosts.sort_unstable();
        hosts.dedup();
        assert_eq!(hosts.len(), before, "two destinations share a host");

        for d in Destination::ALL {
            assert!(!d.rationale().trim().is_empty(), "{d:?} has no rationale");
            assert!(!d.host().contains('/'), "{d:?} host must be a bare host");
        }
    }

    #[test]
    fn urls_are_always_https_and_always_on_an_allowlisted_host() {
        for d in Destination::ALL {
            let req = RequestDescriptor::new(
                *d,
                Purpose::ResolveGameMetadata,
                Feature::ProfileInstall,
                "/x",
            );
            let url = req.url();
            assert!(url.starts_with("https://"), "{url} is not https");
            assert!(url.starts_with(&format!("https://{}/", d.host())));
        }
    }

    #[test]
    fn core_flow_requests_are_allowed_by_default() {
        let policy = NetworkPolicy::default();
        assert!(policy.evaluate(&core_request(), None).is_ok());
    }

    #[test]
    fn offline_mode_denies_everything() {
        let policy = NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        };
        assert_eq!(
            policy.evaluate(&core_request(), None),
            Err(PolicyDenial::Offline)
        );
    }

    #[test]
    fn update_check_is_opt_out() {
        let req = RequestDescriptor::new(
            Destination::LauncherUpdates,
            Purpose::LauncherUpdate,
            Feature::SelfUpdate,
            "/update.json",
        );
        assert!(NetworkPolicy::default().evaluate(&req, None).is_ok());

        let policy = NetworkPolicy {
            allow_update_check: false,
            ..NetworkPolicy::default()
        };
        assert_eq!(
            policy.evaluate(&req, None),
            Err(PolicyDenial::UpdateCheckDisabled)
        );
    }

    #[test]
    fn managed_java_is_off_by_default() {
        let req = RequestDescriptor::new(
            Destination::JavaVendor,
            Purpose::DownloadJavaRuntime,
            Feature::JavaManagement,
            "/v3/assets/latest/8/hotspot",
        );
        assert_eq!(
            NetworkPolicy::default().evaluate(&req, None),
            Err(PolicyDenial::ManagedJavaDisabled)
        );
    }

    #[test]
    fn mod_downloads_need_both_the_setting_and_a_per_action_grant() {
        let req = RequestDescriptor::new(
            Destination::ModrinthApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            "/v2/project/sodium",
        );

        // Default: repositories off entirely.
        assert_eq!(
            NetworkPolicy::default().evaluate(&req, None),
            Err(PolicyDenial::ModRepositoriesDisabled)
        );

        let policy = NetworkPolicy {
            allow_mod_repositories: true,
            ..NetworkPolicy::default()
        };
        // Setting on, but no specific user action.
        assert_eq!(
            policy.evaluate(&req, None),
            Err(PolicyDenial::MissingPerActionGrant)
        );

        let grant = PerActionGrant::user_requested("user chose mod 'sodium'");
        assert!(policy.evaluate(&req, Some(&grant)).is_ok());
    }

    #[test]
    fn mojang_urls_from_metadata_resolve_to_allowlisted_destinations() {
        let cases = [
            (
                "https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar",
                Destination::LibrariesMinecraft,
            ),
            (
                "https://piston-data.mojang.com/v1/objects/abc/client.jar",
                Destination::PistonData,
            ),
            (
                "https://resources.download.minecraft.net/f6/f6ad1",
                Destination::MinecraftResources,
            ),
            (
                "https://launcher.mojang.com/v1/objects/abc/lib.jar",
                Destination::LauncherMojang,
            ),
        ];
        for (url, expected) in cases {
            let d =
                descriptor_from_url(url, Purpose::DownloadGameArtifact, Feature::ProfileInstall)
                    .unwrap_or_else(|e| panic!("{url} should resolve: {e}"));
            assert_eq!(d.destination, expected);
            assert!(d.path.starts_with('/'));
        }
    }

    #[test]
    fn a_tampered_manifest_cannot_redirect_a_download_off_the_allowlist() {
        for hostile in [
            "https://evil.example/client.jar",
            "http://libraries.minecraft.net/x.jar",
            "https://libraries.minecraft.net.evil.example/x.jar",
            "https://user:pass@libraries.minecraft.net/x.jar",
            "https://libraries.minecraft.net:8443/x.jar",
            "ftp://libraries.minecraft.net/x.jar",
        ] {
            assert!(
                descriptor_from_url(
                    hostile,
                    Purpose::DownloadGameArtifact,
                    Feature::ProfileInstall
                )
                .is_err(),
                "{hostile} should have been refused"
            );
        }
    }

    #[test]
    fn query_strings_and_fragments_are_stripped() {
        let d = descriptor_from_url(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json?trackingId=abc#frag",
            Purpose::ResolveGameMetadata,
            Feature::ProfileInstall,
        )
        .unwrap();
        assert_eq!(d.path, "/mc/game/version_manifest_v2.json");
        assert!(!d.url().contains("trackingId"));
    }

    #[test]
    fn host_lookup_is_case_insensitive_but_exact() {
        assert_eq!(
            Destination::from_host("PISTON-META.mojang.com"),
            Some(Destination::PistonMeta)
        );
        assert_eq!(Destination::from_host("mojang.com"), None);
        assert_eq!(Destination::from_host("evil.piston-meta.mojang.com"), None);
    }

    #[test]
    fn paths_that_could_redirect_to_another_host_are_rejected() {
        let policy = NetworkPolicy::default();
        for bad in [
            "//evil.example/x",
            "https://evil.example/x",
            "/x\\..\\y",
            "relative/path",
            "/a/../../b",
            "/has space",
            "/has\u{0}nul",
        ] {
            let req = RequestDescriptor::new(
                Destination::PistonMeta,
                Purpose::ResolveGameMetadata,
                Feature::ProfileInstall,
                bad,
            );
            assert_eq!(
                policy.evaluate(&req, None),
                Err(PolicyDenial::MalformedPath),
                "path {bad:?} should have been rejected"
            );
        }
    }
}
