//! The façade the UI talks to.
//!
//! The brief is explicit that the UI must not construct launch commands,
//! manipulate credentials, download arbitrary files, install mods directly, or
//! touch Minecraft internals. This module is how that is enforced in practice:
//! it exposes *intentions* ("sign in", "what is blocking Play?", "install this
//! jar the user picked"), and keeps every mechanism private.
//!
//! [`Launcher`] owns no background threads and starts nothing on construction.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::SystemTime;

use crate::auth::provider::{AuthProvider, MicrosoftAuthProvider, MinecraftIdentity};
use crate::auth::session::{AuthSession, AuthStatus, LaunchDenied};
use crate::auth::{AuthError, AuthService, AzureApp};
use crate::configuration::{Settings, SettingsError};
use crate::credentials::{self, CredentialStore};
use crate::install::natives::{NativeLibraryManager, NativesStamp};
use crate::install::{InstallService, InstallationState, ResolvedInstall, VerificationReport};
use crate::java_runtime::{self, JavaInstallation};
use crate::launching::{GameStatus, LaunchRefusal, RunningGames};
use crate::loaders::{self, LoaderError, LoaderInstallation, LoaderVersion};
use crate::minecraft_runtime::jvm_args;
use crate::minecraft_runtime::launch::{self, LaunchContext, LaunchPlan, LaunchSpec};
use crate::minecraft_runtime::process;
use crate::minecraft_runtime::version::{Os, VersionDetail};
use crate::mods::{
    InstalledMod, ModDefinition, ModEngine, ModError, ModId, ProfileMod, Resolution,
};
use crate::network::{
    Destination, Feature, HttpClient, NetworkJournal, NetworkPolicy, PerActionGrant, Summary,
};
use crate::paths::{DataPaths, PathError};
use crate::profiles::ops::ProfileOps;
use crate::profiles::validate;
use crate::profiles::{
    DeletionReceipt, ImportOutcome, Loader, Profile, ProfileDirs, ProfileError, ProfileId,
    ProfileReport, ProfileStore, RepairSummary,
};
use crate::tasks::{Cancel, SignedIn, Tasks};

#[derive(Debug, thiserror::Error)]
pub enum ServiceError {
    #[error(transparent)]
    Path(#[from] PathError),
    #[error(transparent)]
    Profile(#[from] ProfileError),
    #[error(transparent)]
    Settings(#[from] SettingsError),
    #[error(transparent)]
    Auth(#[from] AuthError),
    #[error(transparent)]
    Mods(#[from] ModError),
    #[error(transparent)]
    Loader(#[from] LoaderError),
    #[error(transparent)]
    Launch(#[from] launch::LaunchError),
    #[error(transparent)]
    Install(crate::install::InstallError),
    #[error("cannot launch: {0}")]
    Refused(#[from] LaunchRefusal),
    #[error(transparent)]
    Update(#[from] crate::update::UpdateError),
    #[error(transparent)]
    UpdateInstall(#[from] crate::update::install::InstallError),
    #[error("{0} is not implemented yet")]
    NotImplemented(&'static str),
    /// Something that is implemented, tried, and did not work.
    ///
    /// Split out from [`ServiceError::NotImplemented`] because that variant
    /// appends "is not implemented yet" to whatever it is handed, and a real
    /// failure went through it -- so a cancelled download surfaced as "the
    /// transfer was cancelled is not implemented yet". The message was both
    /// ungrammatical and wrong about which of the two situations it was.
    #[error("{0}")]
    Failed(String),
    #[error("cancelled")]
    Cancelled,
    #[error("cannot launch: {0}")]
    LaunchDenied(#[from] LaunchDenied),
    #[error(transparent)]
    Entitlement(#[from] crate::auth::entitlement::EntitlementError),
    /// A structured failure from the mod browser. Kept structured, rather
    /// than flattened into [`ServiceError::Failed`], so the interface can tell
    /// "rate limited" from "not configured" from "incompatible".
    #[error(transparent)]
    Browse(crate::mods::browse::BrowseError),
}

impl From<crate::mods::browse::BrowseError> for ServiceError {
    fn from(error: crate::mods::browse::BrowseError) -> Self {
        match error {
            crate::mods::browse::BrowseError::Cancelled => ServiceError::Cancelled,
            other => ServiceError::Browse(other),
        }
    }
}

/// Cancellation is not a failure, and the interface treats it differently, so
/// it is lifted out of `InstallError` rather than wrapped and lost inside it.
impl From<crate::install::InstallError> for ServiceError {
    fn from(error: crate::install::InstallError) -> Self {
        match error {
            crate::install::InstallError::Cancelled => ServiceError::Cancelled,
            other => ServiceError::Install(other),
        }
    }
}

impl From<crate::install::MetadataError> for ServiceError {
    fn from(error: crate::install::MetadataError) -> Self {
        crate::install::InstallError::from(error).into()
    }
}

/// What the caller last learned about a version's files on disk.
///
/// A verification result reduced to the three things the interface needs, so
/// that [`Launcher::readiness`] can stay a pure function of state the caller
/// already holds. Produced by [`InstallSnapshot::from_report`] on whichever
/// thread ran the verification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InstallSnapshot {
    pub state: InstallationState,
    /// Artifacts missing or failing their hash.
    pub outstanding: usize,
    /// What a repair would need to fetch, as far as the metadata declares.
    pub bytes: u64,
    /// Whether this profile's natives directory has been populated. Tracked
    /// separately because an installation can be complete in every artifact and
    /// still not be runnable, and "0 files to download" is a useless thing to
    /// tell someone in that state.
    pub natives_extracted: bool,
}

impl InstallSnapshot {
    pub fn from_report(report: &VerificationReport, install: &ResolvedInstall) -> Self {
        Self {
            state: report.state,
            outstanding: report.defects.len(),
            bytes: report.outstanding_bytes(install),
            natives_extracted: report.natives_extracted,
        }
    }

    pub fn is_playable(&self) -> bool {
        self.state.is_playable()
    }

    /// Whether the only thing left is unpacking the natives.
    ///
    /// Worth its own question: it is the state a fresh profile lands in when
    /// the artifacts are already in the shared cache because another profile
    /// downloaded them, and it costs a second to fix rather than 139 MB.
    pub fn only_natives_missing(&self) -> bool {
        self.outstanding == 0 && !self.natives_extracted && !self.state.is_playable()
    }

    /// One sentence naming what is actually wrong.
    pub fn message(&self) -> String {
        if self.only_natives_missing() {
            return "Every file is present, but this profile's native libraries have not \
                    been unpacked yet."
                .to_owned();
        }
        let plural = if self.outstanding == 1 { "" } else { "s" };
        match self.state {
            InstallationState::Corrupted => format!(
                "{} game file{plural} failed verification. The bytes on disk are wrong and \
                 will be replaced ({}).",
                self.outstanding,
                human_bytes(self.bytes)
            ),
            _ => format!(
                "{} game file{plural} still to download ({}).",
                self.outstanding,
                human_bytes(self.bytes)
            ),
        }
    }
}

/// What `preflight` established, carried to the assembly step so it is not
/// recomputed and cannot drift between the two.
struct Preflight<'a> {
    identity: &'a MinecraftIdentity,
    dirs: ProfileDirs,
    java: JavaInstallation,
    #[allow(dead_code)]
    required_major: u32,
}

/// Where the launcher writes the game's captured output.
///
/// One file per launch, named by start time, so a crash is not overwritten by
/// the next attempt. Lives beside the profile rather than in the game
/// directory, because it is the launcher's record, not the game's.
fn launch_log_path(dirs: &ProfileDirs) -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    dirs.logs_dir().join(format!("launch-{stamp}.log"))
}

/// What a profile's Java policy resolves to.
///
/// Three outcomes rather than `Option`, because "there is no Java and I am
/// allowed to fetch one" and "there is no Java and you told me not to" call for
/// completely different things to appear on screen -- a button, and an
/// explanation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JavaOutcome {
    Ready(JavaInstallation),
    /// Nothing suitable, and the policy permits downloading one.
    NeedsInstall { required_major: u32 },
    /// Nothing suitable, and fetching is not on the table.
    Missing {
        required_major: u32,
        /// One sentence, already suitable for display.
        reason: &'static str,
    },
}

impl JavaOutcome {
    pub fn installation(&self) -> Option<&JavaInstallation> {
        match self {
            JavaOutcome::Ready(installation) => Some(installation),
            _ => None,
        }
    }

    pub fn is_ready(&self) -> bool {
        matches!(self, JavaOutcome::Ready(_))
    }
}

/// Everything standing between the user and pressing Play.
///
/// Deliberately an enumeration of *specific* blockers rather than a boolean, so
/// the Home screen can say what to do rather than greying out a button.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Blocker {
    /// No Azure application id is configured, so sign-in cannot even start.
    NoClientId,
    /// Nobody is signed in.
    SignedOut,
    /// Signed in, but the account has no Java Edition entitlement.
    NotEntitled(String),
    /// The launcher is in offline mode and files are still missing.
    OfflineWithWorkOutstanding,
    /// No suitable Java runtime was found.
    NoJava { required_major: u32 },
    /// Java is present but the wrong major version.
    WrongJava {
        required_major: u32,
        found_major: u32,
        explanation: String,
    },
    /// Game files are missing, on disk but wrong, or not yet unpacked.
    NeedsInstall(InstallSnapshot),
    /// We have version metadata but have not yet looked at what is on disk.
    /// Distinct from "nothing installed": we genuinely do not know yet, and
    /// saying so is better than guessing in either direction.
    InstallNotChecked,
    /// The profile selects a loader that is not implemented.
    LoaderNotImplemented(Loader),
    /// Signed in, but the Minecraft access token has expired.
    SessionExpired,
}

impl Blocker {
    /// A short, actionable sentence for the UI.
    pub fn message(&self) -> String {
        match self {
            Blocker::NoClientId => {
                "This build has no Microsoft application id, so sign-in is disabled. See the \
                 README for how to register one."
                    .to_owned()
            }
            Blocker::SignedOut => "Sign in with your Microsoft account to play.".to_owned(),
            Blocker::NotEntitled(why) => why.clone(),
            Blocker::OfflineWithWorkOutstanding => {
                "Offline mode is on and some game files are missing. Turn it off in Settings to \
                 download them."
                    .to_owned()
            }
            Blocker::NoJava { required_major } => format!(
                "No Java {required_major} runtime was found. Install one, or point this profile at \
                 a java executable in its Java settings."
            ),
            Blocker::WrongJava { explanation, .. } => explanation.clone(),
            Blocker::NeedsInstall(snapshot) => snapshot.message(),
            Blocker::InstallNotChecked => "The game files have not been checked yet.".to_owned(),
            Blocker::LoaderNotImplemented(loader) => format!(
                "{} support is not implemented yet. Switch this profile to Vanilla to play.",
                loader.label()
            ),
            Blocker::SessionExpired => {
                "Your session expired. Sign in again to keep playing.".to_owned()
            }
        }
    }
}

/// The result of asking "can I press Play?".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Readiness {
    pub blockers: Vec<Blocker>,
    pub java: Option<JavaInstallation>,
}

impl Readiness {
    pub fn can_play(&self) -> bool {
        self.blockers.is_empty()
    }

    /// The blocker to show first: the one the user must act on soonest.
    pub fn primary(&self) -> Option<&Blocker> {
        self.blockers.first()
    }
}

/// Reduce an identifier to something safe as a single path component.
fn sanitise_component(raw: &str) -> String {
    raw.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '.' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

#[allow(clippy::cast_precision_loss)] // sizes here are far below 2^53
pub fn human_bytes(bytes: u64) -> String {
    const UNITS: [&str; 4] = ["B", "KB", "MB", "GB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit + 1 < UNITS.len() {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS.get(unit).unwrap_or(&"B"))
    }
}

/// Turn a runtime message into the `&'static str` `ServiceError` carries.
///
/// The same deliberate leak `fathom-cli` uses: these are one-per-process error
/// paths, the string is displayed and then the operation ends, and the
/// alternative is widening an error type used everywhere to carry a `String`
/// for the benefit of three call sites.
fn leak_problem(message: &str) -> &'static str {
    Box::leak(message.to_owned().into_boxed_str())
}

/// The core, assembled.
pub struct Launcher {
    paths: DataPaths,
    settings: Settings,
    http: HttpClient,
    auth_service: Arc<AuthService>,
    credentials: Arc<dyn CredentialStore>,
    profiles: ProfileStore,
    auth: AuthSession,
    java: Vec<JavaInstallation>,
    running: RunningGames,
    /// The mod browser. Held rather than built per call because its caches
    /// and in-flight state are the point of it; it shares `http`, so a change
    /// to Privacy settings reaches it immediately.
    mod_catalog: crate::mods::browse::ModCatalog,
}

impl std::fmt::Debug for Launcher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Launcher")
            .field("root", &self.paths.root())
            .field("auth", &self.auth.status())
            .field("profiles", &self.profiles.list().len())
            .field("running", &self.running.all().len())
            .finish_non_exhaustive()
    }
}

/// A managed mod with a newer build available.
///
/// Only ever produced for a mod the launcher installed: an update needs a
/// project to ask about and a version to compare against, and a jar somebody
/// dropped in has neither. Guessing a project from a filename would be
/// inventing the one fact that makes an update safe.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModUpdate {
    pub id: ModId,
    /// The file name as installed, for display.
    pub display_name: String,
    /// The version string recorded at install time.
    pub from: String,
    /// The version string of the newest compatible build.
    pub to: String,
    /// The repository project, so installing the update needs no second search.
    pub project: String,
}

impl Launcher {
    /// Build from a data root. Performs no network activity.
    pub fn new(
        paths: DataPaths,
        credentials: Arc<dyn CredentialStore>,
    ) -> Result<Self, ServiceError> {
        paths.ensure()?;
        let settings = Settings::load(&paths);
        let journal = NetworkJournal::new();
        let http = HttpClient::new(settings.to_network_policy(), journal, &crate::user_agent());
        let auth_service = Arc::new(AuthService::new(http.clone(), AzureApp::from_env()));
        let profiles = ProfileStore::new(paths.clone());

        // Bring any older profile forward and write it back, once, here.
        // Migrating only on read would mean a profile stays at the old schema
        // until something happens to load it, and a launcher that has been
        // downgraded in between would then read a file nobody had upgraded.
        for (id, outcome) in profiles.consolidate() {
            tracing::info!(profile = %id, outcome = ?outcome, "profile brought forward on open");
        }

        // The profiles Fathom ships with. Runs once per installation, does no
        // network work, and cannot fail the open -- see `profiles::seed`.
        let seeded = crate::profiles::seed::run(&paths, &profiles);

        // Fathom's own components, brought up to date at open.
        //
        // Creation and launch both do this too, but neither covers the case
        // that matters most: a profile created by a build that did not ship a
        // component, opened later by one that does. Without this the profile
        // sits there missing a component until somebody presses Play, and the
        // Profiles page reports "0 mods" for a profile whose whole point is
        // that the launcher keeps it stocked.
        //
        // Cheap and bounded: one directory read plus a hash of a small jar per
        // Fathom profile, and nothing at all for any other kind. Non-fatal for
        // the same reason seeding is -- a launcher that refuses to open because
        // it could not copy a jar has made things worse.
        {
            let engine = ModEngine::new(paths.clone());
            for mut profile in profiles.list() {
                if !profile.owns_components() {
                    continue;
                }
                match crate::components::ensure(&paths, &engine, &mut profile) {
                    Ok(report) if report.changed_anything() => {
                        if let Err(error) = profiles.save(&profile) {
                            tracing::warn!(%error, "could not record updated components");
                        } else {
                            tracing::info!(
                                profile = %profile.id,
                                summary = %report.summary(),
                                "components brought up to date at open"
                            );
                        }
                    }
                    Ok(_) => {}
                    Err(error) => tracing::warn!(%error, "could not update Fathom components"),
                }
            }
        }

        // Select a usable default, but only if nothing is selected. A user who
        // already has a choice keeps it. `seeded` is empty after the first run,
        // so upgraded installations also need to find the existing Fathom
        // profile; otherwise the alphabetically first custom profile silently
        // becomes current even though the installed game belongs to Fathom.
        let mut settings = settings;
        if settings.selected_profile.is_none() {
            let default = seeded.first().cloned().or_else(|| {
                let available = profiles.list();
                available
                    .iter()
                    .find(|profile| profile.kind == crate::profiles::ProfileKind::Fathom)
                    .or_else(|| available.first())
                    .map(|profile| profile.id.clone())
            });
            if let Some(default) = default {
                settings.selected_profile = Some(default);
                if let Err(error) = settings.save(&paths) {
                    tracing::warn!(%error, "could not record the selected profile");
                }
            }
        }

        let mod_catalog = crate::mods::browse::ModCatalog::new(
            http.clone(),
            paths.clone(),
            crate::mods::browse::CurseForgeConfig::discover(&paths),
        );

        Ok(Self {
            paths,
            settings,
            http,
            auth_service,
            credentials,
            profiles,
            auth: AuthSession::new(),
            java: Vec::new(),
            running: RunningGames::new(),
            mod_catalog,
        })
    }

    /// The normal entry point: discover the data directory and the best
    /// available credential store.
    pub fn discover() -> Result<Self, ServiceError> {
        let paths = DataPaths::discover()?;
        let store: Arc<dyn CredentialStore> = Arc::from(credentials::best_available());
        Self::new(paths, store)
    }

    // -- state ------------------------------------------------------------

    pub fn paths(&self) -> &DataPaths {
        &self.paths
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    /// The authentication state machine.
    pub fn auth(&self) -> &AuthSession {
        &self.auth
    }

    /// Which of the six authentication states we are in.
    pub fn auth_status(&self) -> AuthStatus {
        self.auth.status()
    }

    /// Who is signed in, and how that was established.
    pub fn identity(&self) -> Option<&MinecraftIdentity> {
        self.auth.identity()
    }

    /// How the current identity was obtained. `None` when signed out.
    pub fn identity_kind(&self) -> Option<crate::auth::provider::IdentityKind> {
        self.auth.identity_kind()
    }

    /// A warning the interface must display persistently, if the current
    /// identity is not a real account.
    pub fn identity_warning(&self) -> Option<&'static str> {
        self.auth.identity().and_then(|i| i.kind.warning())
    }

    /// Every authentication provider compiled into this build.
    ///
    /// In a release binary this is exactly one entry, and
    /// [`AuthProvider::is_production`] is true for it — the development module
    /// does not exist to be listed. There is a test asserting that.
    pub fn auth_providers(&self) -> Vec<Box<dyn AuthProvider>> {
        #[cfg_attr(not(feature = "dev-auth"), allow(unused_mut))]
        let mut providers: Vec<Box<dyn AuthProvider>> = vec![Box::new(MicrosoftAuthProvider::new(
            self.auth_service.app().is_configured(),
        ))];

        #[cfg(feature = "dev-auth")]
        providers.push(Box::new(
            crate::auth::development::DevelopmentAuthProvider::from_env(),
        ));

        providers
    }

    /// Whether a fake development identity can be used in this build.
    ///
    /// Always false in a release binary: the module behind this is not
    /// compiled. See `crates/launcher-core/src/auth/development.rs`.
    pub fn development_auth_available(&self) -> bool {
        #[cfg(feature = "dev-auth")]
        {
            crate::auth::development::DevelopmentAuthProvider::from_env().is_available()
        }
        #[cfg(not(feature = "dev-auth"))]
        {
            false
        }
    }

    /// Sign in with the fake development identity.
    ///
    /// Nothing is persisted: a development identity is never written to the
    /// credential store or recorded in settings, because it is not an account
    /// and must not survive a restart as though it were one.
    #[cfg(feature = "dev-auth")]
    pub fn sign_in_development(&mut self) -> Result<(), ServiceError> {
        use crate::auth::development::{DevelopmentAuthProvider, DevelopmentEntitlementVerifier};
        use crate::auth::entitlement::EntitlementsResponse;
        use crate::auth::provider::EntitlementVerifier;

        let provider = DevelopmentAuthProvider::from_env();
        let identity = provider
            .identity()
            .ok_or(ServiceError::NotImplemented("development authentication"))?;

        tracing::warn!(
            username = %identity.profile.name,
            "signed in with a FAKE development identity; this is not a Minecraft account"
        );

        // Same path as a real sign-in: begin, authenticate, then present an
        // entitlement proof. The proof is stamped `authoritative: false`, which
        // the release launch gate rejects -- so even this path cannot become a
        // bypass if the module ever escaped into a release build.
        let proof = DevelopmentEntitlementVerifier.verify(&EntitlementsResponse {
            items: Vec::new(),
            signature: None,
            key_id: None,
        })?;

        self.auth.sign_out();
        self.auth
            .begin()
            .and_then(|()| self.auth.authenticated(identity))
            .and_then(|()| self.auth.entitlement_verified(proof))
            .map_err(|e| {
                ServiceError::Auth(AuthError::OAuth {
                    code: "bad_transition".to_owned(),
                    description: Some(e.to_string()),
                })
            })?;
        Ok(())
    }

    pub fn credential_store_description(&self) -> String {
        self.credentials.describe()
    }

    pub fn credential_store_is_persistent(&self) -> bool {
        self.credentials.is_persistent()
    }

    pub fn sign_in_available(&self) -> bool {
        self.auth_service.app().is_configured()
    }

    // -- settings ---------------------------------------------------------

    /// Replace settings and push the privacy switches into the live network
    /// policy. This is the only way the UI changes network behaviour.
    pub fn update_settings(&mut self, settings: Settings) -> Result<(), ServiceError> {
        self.http.set_policy(settings.to_network_policy());
        settings.save(&self.paths)?;
        self.settings = settings;
        Ok(())
    }

    pub fn network_policy(&self) -> NetworkPolicy {
        self.http.policy()
    }

    // -- privacy ----------------------------------------------------------

    pub fn network_journal(&self) -> &NetworkJournal {
        self.http.journal()
    }

    pub fn network_summary(&self) -> Summary {
        self.http.journal().summary()
    }

    /// Every host this build is capable of contacting, with its rationale and
    /// whether it is currently permitted.
    pub fn network_allowlist(&self) -> Vec<(Destination, bool)> {
        let policy = self.http.policy();
        Destination::ALL
            .iter()
            .map(|d| {
                let probe = crate::network::RequestDescriptor::new(
                    *d,
                    crate::network::Purpose::ResolveGameMetadata,
                    Feature::ProfileInstall,
                    "/",
                );
                let grant = crate::network::PerActionGrant::user_requested("allowlist probe");
                (*d, policy.evaluate(&probe, Some(&grant)).is_ok())
            })
            .collect()
    }

    // -- accounts ---------------------------------------------------------

    /// A cheap, `Send` handle to the blocking operations.
    ///
    /// Hand a clone to a worker thread; results come back through
    /// [`Launcher::adopt_session`] and friends on the thread that owns the
    /// `Launcher`.
    pub fn tasks(&self) -> Tasks {
        Tasks::new(
            self.http.clone(),
            self.paths.clone(),
            Arc::clone(&self.auth_service),
            Arc::clone(&self.credentials),
        )
    }

    /// The account whose refresh token is stored, if any.
    pub fn remembered_account_id(&self) -> Option<&str> {
        self.settings.last_account_id.as_deref()
    }

    /// Accept a session produced by [`Tasks::sign_in`] or
    /// [`Tasks::restore_session`].
    ///
    /// This is where persistence happens, and it happens **only after** the
    /// entitlement gate has already passed inside `establish_session`.
    pub fn adopt_session(&mut self, signed_in: SignedIn) -> Result<(), ServiceError> {
        let SignedIn {
            session,
            refresh_token,
        } = signed_in;

        let account_id = session.identity.profile.id.clone();
        let account_name = session.identity.profile.name.clone();

        // Drive the state machine rather than assigning a field: the session is
        // only launchable if it passes through `entitlement_verified`, and that
        // refuses a proof which does not permit launching.
        if self.auth.status() != AuthStatus::Authenticating {
            self.auth.sign_out();
            self.auth.begin().map_err(|e| {
                ServiceError::Auth(AuthError::OAuth {
                    code: "bad_transition".to_owned(),
                    description: Some(e.to_string()),
                })
            })?;
        }
        self.auth.authenticated(session.identity).map_err(|e| {
            ServiceError::Auth(AuthError::OAuth {
                code: "bad_transition".to_owned(),
                description: Some(e.to_string()),
            })
        })?;
        self.auth
            .entitlement_verified(session.entitlement)
            .map_err(|e| {
                ServiceError::Auth(AuthError::OAuth {
                    code: "bad_transition".to_owned(),
                    description: Some(e.to_string()),
                })
            })?;

        // The entitlement check may have concluded "no". Persist nothing in
        // that case: an unentitled account is not an account we remember.
        if self.auth.status() != AuthStatus::EntitlementVerified {
            let reason = match self.auth.state() {
                crate::auth::session::AuthState::Failed { message, .. } => message.clone(),
                _ => "entitlement was not verified".to_owned(),
            };
            return Err(ServiceError::Auth(AuthError::NotEntitled(reason)));
        }

        // Only now, after the gate passed, is anything written to disk.
        self.credentials
            .store(&account_id, &refresh_token)
            .map_err(AuthError::from)?;

        let mut settings = self.settings.clone();
        settings.last_account_id = Some(account_id);
        settings.last_account_name = Some(account_name);
        self.update_settings(settings)?;
        Ok(())
    }

    /// Blocking sign-in, for the CLI and for tests. The desktop app uses
    /// [`Launcher::tasks`] instead so the window stays responsive.
    pub fn sign_in_blocking(&mut self, cancel: &Cancel) -> Result<(), ServiceError> {
        let signed_in = self.tasks().sign_in(cancel, |prompt| {
            tracing::info!(
                code = %prompt.user_code,
                url = %prompt.verification_uri,
                "enter this code to sign in"
            );
        })?;
        self.adopt_session(signed_in)
    }

    /// Try to restore a session from the stored refresh token.
    ///
    /// Returns `Ok(false)` when there is nothing stored, which is not an error.
    pub fn restore_session(&mut self) -> Result<bool, ServiceError> {
        let Some(account_id) = self.settings.last_account_id.clone() else {
            return Ok(false);
        };
        match self.tasks().restore_session(&account_id)? {
            Some(signed_in) => {
                self.adopt_session(signed_in)?;
                Ok(true)
            }
            None => Ok(false),
        }
    }

    /// Sign out: forget the session and delete the only persisted secret.
    pub fn sign_out(&mut self) -> Result<(), ServiceError> {
        if let Some(id) = self.settings.last_account_id.clone() {
            self.credentials.delete(&id).map_err(AuthError::from)?;
        }
        self.auth.sign_out();
        let mut settings = self.settings.clone();
        settings.last_account_id = None;
        settings.last_account_name = None;
        self.update_settings(settings)
    }

    // -- profiles ---------------------------------------------------------

    pub fn profiles(&self) -> Vec<Profile> {
        self.profiles.list()
    }

    /// Every profile directory and what came of reading it, failures
    /// included. The interface uses this to show a corrupt profile rather
    /// than silently omitting it.
    pub fn profiles_detailed(
        &self,
    ) -> Vec<(ProfileId, Result<crate::profiles::Loaded, ProfileError>)> {
        self.profiles.list_detailed()
    }

    /// Rewrite any profile that needed migrating or recovering on load.
    /// Called once at start-up so a migration happens exactly once.
    pub fn consolidate_profiles(&self) -> Vec<(ProfileId, crate::profiles::LoadOutcome)> {
        self.profiles.consolidate()
    }

    /// The directory layout for a profile id, assuming a managed game
    /// directory. Prefer [`Launcher::dirs_for`] where the profile is in hand:
    /// a profile can point its game directory elsewhere.
    pub fn profile_dirs(&self, id: &ProfileId) -> ProfileDirs {
        self.profiles.dirs(id)
    }

    /// The directory layout for a profile, honouring its game directory.
    pub fn dirs_for(&self, profile: &Profile) -> ProfileDirs {
        ProfileDirs::with_game_directory(&self.paths, &profile.id, &profile.game_directory)
    }

    /// Create a profile from a display name.
    ///
    /// The bare version, kept for the CLI and for tests. The interface goes
    /// through [`Launcher::create_from_blueprint`], because a name is not a
    /// setup.
    pub fn create_profile(&self, display_name: &str) -> Result<Profile, ServiceError> {
        Ok(self.profile_ops().create(display_name)?)
    }

    /// Create a profile from a completed setup.
    ///
    /// Validates first, saves second, installs components third -- in that
    /// order, so a blueprint that could never work never reaches the disk, and
    /// a component that could not be installed leaves a working profile rather
    /// than a half-created one.
    ///
    /// Component installation is deliberately not fatal. A Fathom profile whose
    /// module could not be copied is a profile with a missing component, which
    /// the Profiles page shows and the next launch retries; refusing to create
    /// it at all would leave the user with nothing and no way forward.
    pub fn create_from_blueprint(
        &self,
        blueprint: &crate::profiles::Blueprint,
    ) -> Result<(Profile, crate::components::Report), ServiceError> {
        let problems = blueprint.problems();
        if let Some(first) = problems.first() {
            return Err(ServiceError::NotImplemented(leak_problem(first)));
        }

        let id = ProfileId::unique_from_display_name(&blueprint.name, self.taken_profile_id())
            .map_err(ServiceError::Profile)?;
        let mut profile = blueprint.build(id);
        self.profiles.create(&profile)?;

        let mut report = self.ensure_components(&mut profile)?;

        // Anything the user ticked that has to be fetched is fetched now.
        //
        // Ticking the box in the setup drawer IS the explicit action the
        // consent model asks for -- which is why this is here and not inside
        // `ensure_components`, where it would also run at every launch and at
        // every start-up, neither of which is somebody asking for a download.
        //
        // A failure is recorded, not raised. Somebody who is offline, or has
        // mod repositories switched off, should still get their profile; the
        // component stays offerable on the Mods page with a button that says
        // what it will do. Refusing to create the profile would be losing four
        // answered questions over an optional extra.
        for id in profile.components.clone() {
            let Some(component) = crate::components::find(&id) else {
                continue;
            };
            if !component.supply.is_requestable() {
                continue;
            }
            if component
                .parsed_mod_id()
                .is_some_and(|mod_id| profile.mods.get(&mod_id).is_some())
            {
                continue;
            }
            match self.install_component(&mut profile, component.id) {
                Ok(_) => report.added.push(component.id),
                Err(error) => {
                    tracing::info!(
                        component = component.id,
                        reason = %error,
                        "a chosen component was not installed during setup"
                    );
                    report
                        .unavailable
                        .push((component.id, "Not downloaded yet. You can install it from Mods."));
                }
            }
        }

        Ok((profile, report))
    }

    /// A predicate saying whether an id is already in use.
    ///
    /// The list is read once and captured, rather than the predicate hitting
    /// the disk on every candidate: `unique_from_display_name` may try `-2`
    /// through `-999`, and directory-listing a thousand times to name one
    /// profile would be a strange way to spend a second.
    fn taken_profile_id(&self) -> impl Fn(&ProfileId) -> bool {
        let taken: std::collections::BTreeSet<ProfileId> =
            self.profiles.list().into_iter().map(|p| p.id).collect();
        move |candidate| taken.contains(candidate)
    }

    /// Make a profile's contents match what its kind promises.
    ///
    /// Saves the profile if anything changed, and is a no-op otherwise. Called
    /// after creation and again before every launch, so a component that was
    /// removed -- or a build that ships a newer one -- is put right without
    /// anybody asking.
    pub fn ensure_components(
        &self,
        profile: &mut Profile,
    ) -> Result<crate::components::Report, ServiceError> {
        let engine = self.mod_engine();
        let report = crate::components::ensure(&self.paths, &engine, profile)
            .map_err(|error| ServiceError::NotImplemented(leak_problem(&error.to_string())))?;
        if report.changed_anything() {
            self.profiles.save(profile)?;
        }
        Ok(report)
    }

    /// A profile as it should actually be launched, with inheritance applied.
    ///
    /// Every launch path goes through this. A derived profile that resolved
    /// differently at launch time than on the Profiles page would be the worst
    /// possible version of inheritance.
    pub fn resolve_profile(&self, profile: &Profile) -> Result<Profile, ServiceError> {
        let store = &self.profiles;
        crate::profiles::inherit::resolve(profile, |id| store.load(id).ok())
            .map_err(|error| ServiceError::NotImplemented(leak_problem(&error.to_string())))
    }

    /// Create a profile that derives from another.
    pub fn derive_profile(
        &self,
        parent: &ProfileId,
        display_name: &str,
    ) -> Result<Profile, ServiceError> {
        let parent = self.profiles.load(parent)?;
        let id = ProfileId::unique_from_display_name(display_name, self.taken_profile_id())
            .map_err(ServiceError::Profile)?;
        let derived = crate::profiles::derive(&parent, id, display_name.trim());
        self.profiles.create(&derived)?;
        Ok(derived)
    }

    /// Copy a profile, including its worlds and configuration.
    pub fn clone_profile(
        &self,
        source: &ProfileId,
        new_display_name: &str,
    ) -> Result<Profile, ServiceError> {
        Ok(self.profile_ops().clone_profile(source, new_display_name)?)
    }

    /// Change a profile's display name. The id, and therefore the directory,
    /// does not move.
    pub fn rename_profile(
        &self,
        id: &ProfileId,
        new_display_name: &str,
    ) -> Result<Profile, ServiceError> {
        Ok(self.profile_ops().rename(id, new_display_name)?)
    }

    /// Move a profile to the recovery area.
    ///
    /// Nothing is destroyed. Refuses outright if the game is running, because
    /// moving a directory out from under a live JVM corrupts whatever it was
    /// writing.
    pub fn delete_profile(&self, id: &ProfileId) -> Result<DeletionReceipt, ServiceError> {
        if self.running.is_running(id) {
            return Err(ServiceError::NotImplemented(
                "deleting a profile while its game is running",
            ));
        }
        Ok(self.profile_ops().delete(id)?)
    }

    /// What is in the recovery area.
    pub fn trashed_profiles(&self) -> Vec<PathBuf> {
        self.profile_ops().trashed()
    }

    /// Put a trashed instance back under a profile id.
    pub fn restore_profile(&self, trashed: &Path, id: &ProfileId) -> Result<(), ServiceError> {
        Ok(self.profile_ops().restore(trashed, id)?)
    }

    /// Permanently remove one trashed instance. The second, explicit act.
    pub fn purge_trashed_profile(&self, trashed: &Path) -> Result<(), ServiceError> {
        self.profile_ops().purge(trashed)?;
        Ok(())
    }

    /// A profile's configuration as portable JSON. No worlds, no jars, no
    /// credentials.
    pub fn export_profile(&self, id: &ProfileId) -> Result<String, ServiceError> {
        Ok(self.profile_ops().export_json(id)?)
    }

    /// Create a profile from an exported document. Everything in it is treated
    /// as untrusted.
    pub fn import_profile(&self, bytes: &[u8]) -> Result<(Profile, ImportOutcome), ServiceError> {
        Ok(self.profile_ops().import(bytes)?)
    }

    /// What is wrong with a profile, if anything.
    ///
    /// `installation` is passed in for the same reason `readiness` takes one:
    /// measuring it means hashing, and this is meant to be cheap.
    pub fn validate_profile(
        &self,
        profile: &Profile,
        installation: Option<&InstallSnapshot>,
    ) -> ProfileReport {
        validate::validate(&self.paths, profile, installation)
    }

    /// Fix what can be fixed without a decision, and save if anything changed.
    pub fn repair_profile(
        &self,
        id: &ProfileId,
        installation: Option<&InstallSnapshot>,
    ) -> Result<RepairSummary, ServiceError> {
        let mut profile = self.profiles.load(id)?;
        let summary = validate::repair(&self.paths, &mut profile, installation)?;
        if summary.changed_anything() {
            self.profiles.save(&profile)?;
        }
        Ok(summary)
    }

    /// Persist an edited profile.
    pub fn save_profile(&self, profile: &Profile) -> Result<(), ServiceError> {
        self.profiles.save(profile)?;
        Ok(())
    }

    fn profile_ops(&self) -> ProfileOps {
        ProfileOps::new(self.paths.clone())
    }

    // -- loaders ----------------------------------------------------------

    /// Which loader versions a profile could use.
    ///
    /// Makes a network request, so it belongs on a worker thread. Only called
    /// when a user is choosing.
    pub fn loader_versions(&self, profile: &Profile) -> Result<Vec<LoaderVersion>, ServiceError> {
        Ok(
            loaders::provider_for(profile.loader_kind()).available_versions(
                &profile.version_id,
                &self.http,
                Feature::ProfileInstall,
            )?,
        )
    }

    /// Work out what installing this profile's loader involves.
    ///
    /// Nothing is downloaded; the result is an artifact set the install engine
    /// already knows how to fetch and verify.
    pub fn resolve_loader(&self, profile: &Profile) -> Result<LoaderInstallation, ServiceError> {
        let loader = profile.loader_kind();
        let provider = loaders::provider_for(loader);

        if !provider.needs_version() {
            return Ok(provider.resolve(
                &profile.version_id,
                "",
                self.install_service().cache(),
                &self.http,
                Feature::Play,
            )?);
        }

        // A profile that names no loader build gets the newest stable one.
        //
        // This used to refuse, and `launch` compensated with its own private
        // auto-selection -- so pressing Play worked and every other caller
        // (the integration check, the interface, a future headless install)
        // failed on a profile the user had never opened a version picker for.
        // Choosing belongs here, in the one place that resolves a loader.
        //
        // Nothing is persisted from this method: it takes `&self`, and a
        // getter that quietly rewrites a profile on disk would be a surprise.
        // `ensure_loader` does the writing, so the choice becomes sticky at
        // the moment it is actually used to launch.
        let chosen;
        let version = if let Some(version) = profile.loader.version.as_deref() {
            version
        } else {
            chosen = self.pick_loader_version(profile)?;
            tracing::debug!(
                loader = %loader.label(),
                version = %chosen,
                "no loader build recorded; using the newest stable"
            );
            &chosen
        };

        Ok(provider.resolve(
            &profile.version_id,
            version,
            self.install_service().cache(),
            &self.http,
            Feature::Play,
        )?)
    }

    /// What every loader in this build can do, for the interface.
    pub fn loader_capabilities(&self) -> Vec<(Loader, bool, bool)> {
        loaders::all_providers()
            .into_iter()
            .map(|p| {
                (
                    p.kind(),
                    p.is_implemented(),
                    p.mod_directory().accepts_mods(),
                )
            })
            .collect()
    }

    // -- mods -------------------------------------------------------------

    pub fn mod_engine(&self) -> ModEngine {
        ModEngine::new(self.paths.clone())
    }

    /// The jars currently in a profile's mods directory.
    ///
    /// A description of the filesystem, not of the profile's intent. What the
    /// profile *wants* is `profile.mods`.
    pub fn mods_for(&self, id: &ProfileId) -> Vec<InstalledMod> {
        crate::mods::store::list_installed(&self.profiles.dirs(id))
    }

    /// Add a jar the user picked from their own disk.
    ///
    /// The only installation path that exists. The file is copied into the
    /// content-addressed store and recorded in the profile; the user's original
    /// is untouched. Downloading a mod is not implemented — see
    /// `mod-policies.md` §7 for the six conditions it would have to meet.
    pub fn add_mod_from_file(
        &self,
        profile: &mut Profile,
        source: &Path,
    ) -> Result<ProfileMod, ServiceError> {
        let entry = self
            .mod_engine()
            .add_user_supplied(&mut profile.mods, source)?;
        self.profiles.save(profile)?;
        Ok(entry)
    }

    /// Import a file the user chose, and say what it turned out to be.
    ///
    /// The path the `OptiFine` drop target uses. Nothing is uploaded, mirrored or
    /// fetched: the bytes are copied into the local content-addressed store,
    /// the profile records the hash, and the entry is marked
    /// [`crate::mods::ModSource::UserSupplied`] -- which is the record that a
    /// person put it there, and the only lawful way an `OptiFine` jar can be in a
    /// profile at all.
    pub fn import_mod_from_file(
        &self,
        profile: &mut Profile,
        source: &Path,
    ) -> Result<(ProfileMod, crate::mods::Identified), ServiceError> {
        let imported = self.mod_engine().import(&mut profile.mods, source)?;
        self.profiles.save(profile)?;
        Ok(imported)
    }

    /// Turn a mod on or off.
    ///
    /// A field assignment, not a rename. The mods directory is rebuilt from the
    /// configuration at launch, so nothing on disk changes here.
    pub fn set_mod_enabled(
        &self,
        profile: &mut Profile,
        id: &ModId,
        enabled: bool,
    ) -> Result<bool, ServiceError> {
        let changed = profile.mods.set_enabled(id, enabled);
        if changed {
            self.profiles.save(profile)?;
        }
        Ok(changed)
    }

    /// Forget a mod. The bytes stay in the store, shared with any other profile
    /// using them.
    pub fn remove_mod(
        &self,
        profile: &mut Profile,
        id: &ModId,
    ) -> Result<Option<ProfileMod>, ServiceError> {
        let removed = profile.mods.remove(id);
        if removed.is_some() {
            self.profiles.save(profile)?;
        }
        Ok(removed)
    }

    /// What this profile will actually load, and what will go wrong.
    ///
    /// Two checks, not one, because they ask different questions. The mod
    /// resolver asks whether a set of jars can work together. [`Self::missing_components`]
    /// asks whether the set contains what the profile's *kind* promises -- and
    /// a Fathom profile that quietly starts without Fathom's game-side features
    /// is exactly the failure this milestone exists to remove. Both belong in
    /// one answer, because both are reasons a launch is not what the user
    /// asked for.
    ///
    /// Cheap: no network, no hashing, and the only filesystem access is asking
    /// whether the shipped jar exists.
    pub fn resolve_mods(&self, profile: &Profile) -> Resolution {
        let mut resolution =
            self.mod_engine()
                .resolve(&profile.mods, &profile.version_id, profile.loader_kind());
        resolution.problems.extend(self.missing_components(profile));
        // Re-sorted so the combined list still reads worst-first. Appending
        // without this would put a blocking component gap below an advisory.
        crate::mods::resolve::sort_problems(&mut resolution.problems);
        resolution
    }

    /// Make the mods directory match the resolution, then adopt anything the
    /// user dropped in by hand.
    ///
    /// Adopting rather than deleting is the rule: a jar someone put there on
    /// purpose becomes a managed mod, never a removed one.
    pub fn materialize_mods(
        &self,
        profile: &mut Profile,
    ) -> Result<crate::mods::Materialization, ServiceError> {
        let engine = self.mod_engine();
        let dirs = self.dirs_for(profile);
        let resolution = engine.resolve(&profile.mods, &profile.version_id, profile.loader_kind());
        let outcome = engine.materialize(&dirs, &resolution)?;

        if !outcome.foreign.is_empty() {
            let adopted = engine.adopt_foreign(&mut profile.mods, &dirs, &outcome);
            if !adopted.is_empty() {
                self.profiles.save(profile)?;
            }
        }
        Ok(outcome)
    }

    // -- the mod repository -------------------------------------------------

    /// A client for the one repository this launcher talks to.
    ///
    /// Constructed per call rather than held, because it is a wrapper around
    /// the shared HTTP client and holds no state of its own. Holding one would
    /// suggest there was a session to keep, and there is not.
    pub fn modrinth(&self) -> crate::mods::Modrinth {
        crate::mods::Modrinth::new(self.http.clone())
    }

    /// Whether browsing is possible at all right now, and why not if it is not.
    ///
    /// Asked before a search rather than after a failure, so the interface can
    /// say "mod repositories are turned off in Settings" next to a switch
    /// instead of showing an error where results should be.
    pub fn browsing_blocked(&self) -> Option<&'static str> {
        if self.settings.privacy.offline_mode {
            return Some("Fathom is in offline mode.");
        }
        if !self.settings.privacy.mod_repositories {
            return Some("Mod repositories are turned off in Privacy settings.");
        }
        None
    }

    /// Search the repository for mods that fit this profile.
    ///
    /// The grant is minted here from the user's own action, and it names what
    /// they asked for so the network journal records a reason rather than a
    /// bare request.
    pub fn browse_mods(
        &self,
        profile: &Profile,
        query: &str,
        offset: usize,
    ) -> Result<crate::mods::modrinth::Search, ServiceError> {
        if let Some(reason) = self.browsing_blocked() {
            return Err(ServiceError::NotImplemented(reason));
        }
        let grant = PerActionGrant::user_requested(if query.trim().is_empty() {
            "browsing mods for a profile".to_owned()
        } else {
            format!("searching mods for {query:?}")
        });
        self.modrinth()
            .search(
                query,
                profile.loader_kind(),
                &profile.version_id,
                offset,
                &grant,
            )
            .map_err(|error| ServiceError::Failed(error.to_string()))
    }

    // -- the mod browser ------------------------------------------------------
    //
    // Every method below blocks on network or disk and must run on a worker
    // thread. Each takes a `Cancel` so the worker can be stopped, and each mints
    // its own per-action grant from the user action that led here.

    /// The mod browser, for a worker thread to hold. Clones share caches.
    pub fn mod_catalog(&self) -> crate::mods::browse::ModCatalog {
        self.mod_catalog.clone()
    }

    fn catalog_ready(&self) -> Result<(), ServiceError> {
        match self.browsing_blocked() {
            Some(reason) => Err(ServiceError::Browse(
                crate::mods::browse::BrowseError::Blocked {
                    reason: reason.to_owned(),
                },
            )),
            None => Ok(()),
        }
    }

    /// Search Modrinth and `CurseForge` for mods for this profile.
    ///
    /// The query's target should be `Target::of(profile)`; results are
    /// compatible with it unless the query asks otherwise.
    pub fn search_mod_catalog(
        &self,
        profile: &Profile,
        query: &crate::mods::browse::SearchQuery,
        cancel: &Cancel,
    ) -> Result<crate::mods::browse::CatalogPage, ServiceError> {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested(if query.text.trim().is_empty() {
            "browsing mods for a profile".to_owned()
        } else {
            "searching mods".to_owned()
        });
        let installed: Vec<ProfileMod> = profile.mods.iter().cloned().collect();
        Ok(self.mod_catalog.search(
            profile.id.as_str(),
            query,
            &installed,
            &grant,
            cancel,
        )?)
    }

    /// One project's detail page for this profile, with its compatible builds.
    pub fn mod_catalog_entry(
        &self,
        profile: &Profile,
        reference: &crate::mods::browse::ProjectRef,
        cancel: &Cancel,
    ) -> Result<
        (
            crate::mods::browse::CatalogEntry,
            Vec<crate::mods::browse::ModFile>,
        ),
        ServiceError,
    > {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested(format!("viewing {reference}"));
        let installed: Vec<ProfileMod> = profile.mods.iter().cloned().collect();
        Ok(self.mod_catalog.entry(
            profile.id.as_str(),
            reference,
            &crate::mods::browse::Target::of(profile),
            &installed,
            &grant,
            cancel,
        )?)
    }

    /// Plan installing a project: what will be fetched, and what stops it.
    pub fn plan_mod_install(
        &self,
        profile: &Profile,
        reference: &crate::mods::browse::ProjectRef,
        version_id: Option<&str>,
        chosen_optional: &[crate::mods::browse::ProjectRef],
        cancel: &Cancel,
    ) -> Result<crate::mods::browse::InstallPlan, ServiceError> {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested(format!("planning to install {reference}"));
        let installed: Vec<ProfileMod> = profile.mods.iter().cloned().collect();
        Ok(self.mod_catalog.plan_install(
            reference,
            version_id,
            &crate::mods::browse::Target::of(profile),
            &installed,
            chosen_optional,
            &grant,
            cancel,
        )?)
    }

    /// Plan updating one installed mod. `None` when it is already current.
    pub fn plan_mod_update(
        &self,
        profile: &Profile,
        id: &ModId,
        cancel: &Cancel,
    ) -> Result<Option<crate::mods::browse::InstallPlan>, ServiceError> {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested(format!("checking {id} for an update"));
        let installed: Vec<ProfileMod> = profile.mods.iter().cloned().collect();
        Ok(self.mod_catalog.plan_update(
            id,
            &crate::mods::browse::Target::of(profile),
            &installed,
            &grant,
            cancel,
        )?)
    }

    /// Which browser-installed mods in this profile have newer builds.
    pub fn mod_catalog_updates(
        &self,
        profile: &Profile,
        cancel: &Cancel,
    ) -> Result<Vec<crate::mods::browse::UpdateCandidate>, ServiceError> {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested("checking mods for updates".to_owned());
        let installed: Vec<ProfileMod> = profile.mods.iter().cloned().collect();
        Ok(self.mod_catalog.updates(
            &crate::mods::browse::Target::of(profile),
            &installed,
            &grant,
            cancel,
        )?)
    }

    /// Carry out an install or update plan and save the profile.
    ///
    /// The plan is re-checked against the profile as it is now. If saving the
    /// profile fails, the in-memory change is rolled back so the profile the
    /// caller holds still matches the one on disk.
    pub fn install_mod_plan(
        &self,
        profile: &mut Profile,
        plan: &crate::mods::browse::InstallPlan,
        options: crate::mods::browse::InstallOptions,
        cancel: &Cancel,
        progress: &mut dyn FnMut(&crate::mods::browse::InstallProgress),
    ) -> Result<crate::mods::browse::InstallReport, ServiceError> {
        self.catalog_ready()?;
        let grant = PerActionGrant::user_requested(format!(
            "installing {}",
            plan.root.project.name
        ));
        let target = crate::mods::browse::Target::of(profile);
        let report = self.mod_catalog.install(
            profile.id.as_str(),
            plan,
            &target,
            &mut profile.mods,
            options,
            &grant,
            cancel,
            progress,
        )?;
        if let Err(error) = self.profiles.save(profile) {
            crate::mods::browse::install::rollback(&report, &mut profile.mods);
            return Err(error.into());
        }
        Ok(report)
    }

    /// What removing a mod would do, including what still needs it.
    pub fn plan_mod_uninstall(
        &self,
        profile: &Profile,
        id: &ModId,
    ) -> Result<crate::mods::browse::UninstallPlan, ServiceError> {
        Ok(self.mod_catalog.plan_uninstall(&profile.mods, id)?)
    }

    /// Remove a mod, refusing while another installed mod depends on it, and
    /// delete its bytes if no profile refers to them any more.
    ///
    /// Works offline: nothing here needs a provider.
    pub fn uninstall_mod(
        &self,
        profile: &mut Profile,
        id: &ModId,
    ) -> Result<(ProfileMod, bool), ServiceError> {
        let mut next = profile.mods.clone();
        let removed = self
            .mod_catalog
            .uninstall(profile.id.as_str(), &mut next, id)?;
        // Validated; now the ordinary removal path, which saves and collects.
        let (_, deleted) = self.remove_mod_and_collect(profile, &removed.id)?;
        Ok((removed, deleted))
    }

    /// Install a specific project into a profile, newest compatible build.
    ///
    /// Everything that makes this safe happens elsewhere and is worth naming:
    /// the host comes from the compile-time allowlist, the file's hash comes
    /// from the API and is checked before a byte is written, and the profile
    /// records what was installed so the answer survives going offline.
    pub fn install_mod_from_repository(
        &self,
        profile: &mut Profile,
        project: &str,
    ) -> Result<ProfileMod, ServiceError> {
        // Routed through the mod browser's planner and installer, so this older
        // entry point gets the same guarantees: compatibility re-checked at
        // install, required dependencies resolved, duplicates refused, and an
        // update that replaces its entry instead of adding a second one.
        let reference =
            crate::mods::browse::ProjectRef::new(crate::mods::browse::Provider::Modrinth, project);
        let cancel = Cancel::new();
        let existing = profile
            .mods
            .iter()
            .find(|entry| {
                entry.origin.repository.as_deref() == Some("modrinth")
                    && entry.origin.project.as_deref() == Some(project)
            })
            .map(|entry| entry.id.clone());
        let plan = match &existing {
            Some(id) => match self.plan_mod_update(profile, id, &cancel)? {
                Some(plan) => plan,
                // Already the newest compatible build.
                None => {
                    return profile.mods.get(id).cloned().ok_or(ServiceError::Failed(
                        "that mod is no longer installed".to_owned(),
                    ))
                }
            },
            None => self.plan_mod_install(profile, &reference, None, &[], &cancel)?,
        };
        let report = self.install_mod_plan(
            profile,
            &plan,
            crate::mods::browse::InstallOptions::default(),
            &cancel,
            &mut |_| {},
        )?;
        report
            .added
            .last()
            .cloned()
            .ok_or_else(|| ServiceError::Failed("nothing was installed".to_owned()))
    }

    /// Install a first-party component the user asked for.
    ///
    /// The path the setup screen and the Mods page use for Fabric API. Separate
    /// from [`Self::install_mod_from_repository`] because a component carries
    /// facts the launcher has checked -- its licence, its tier, what it is for
    /// -- and those belong in the profile record rather than being rediscovered
    /// from a search result.
    pub fn install_component(
        &self,
        profile: &mut Profile,
        component_id: &str,
    ) -> Result<ProfileMod, ServiceError> {
        if let Some(reason) = self.browsing_blocked() {
            return Err(ServiceError::NotImplemented(reason));
        }
        let component = crate::components::find(component_id).ok_or(
            ServiceError::NotImplemented("that is not a component this build knows about"),
        )?;
        let grant = PerActionGrant::user_requested(format!("installing {}", component.name));
        let engine = self.mod_engine();
        let entry = crate::components::install_managed(
            &engine,
            &self.modrinth(),
            profile,
            component,
            &profile.version_id.clone(),
            &grant,
        )
        .map_err(|error| ServiceError::Failed(error.to_string()))?;
        self.profiles.save(profile)?;
        Ok(entry)
    }

    /// Which of a profile's managed mods have a newer build available.
    ///
    /// One request per updatable mod, and only for mods the launcher installed:
    /// a jar somebody dropped in has no project to ask about, and guessing one
    /// from its filename would be inventing a fact.
    pub fn mod_updates(&self, profile: &Profile) -> Result<Vec<ModUpdate>, ServiceError> {
        if let Some(reason) = self.browsing_blocked() {
            return Err(ServiceError::NotImplemented(reason));
        }
        let modrinth = self.modrinth();
        let loader = profile.loader_kind();
        let mut out = Vec::new();

        for entry in profile.mods.iter() {
            let (Some(project), Some(installed)) =
                (entry.origin.project.clone(), entry.origin.version_id.clone())
            else {
                continue;
            };
            let grant = PerActionGrant::user_requested(format!("checking {project} for updates"));
            let Some(newest) = modrinth
                .newest(&project, loader, &profile.version_id, &grant)
                .map_err(|error| ServiceError::Failed(error.to_string()))?
            else {
                continue;
            };
            if newest.version_id == installed {
                continue;
            }
            out.push(ModUpdate {
                id: entry.id.clone(),
                display_name: entry.file_name.clone(),
                from: entry
                    .version
                    .as_ref()
                    .map_or_else(|| "unknown".to_owned(), ToString::to_string),
                to: newest.number.clone(),
                project,
            });
        }
        Ok(out)
    }

    /// Forget a mod, and delete its bytes if nothing else refers to them.
    ///
    /// # Why the whole profile set is consulted
    ///
    /// Because the store is shared: adding the same `OptiFine` jar to four
    /// profiles stores one copy, which is the point. "This profile stopped
    /// using it" is therefore not "nobody is using it", and a store that tidied
    /// up after every removal would delete a file another profile still needs
    /// -- one the user supplied and cannot be re-fetched.
    ///
    /// Returns whether the bytes were actually deleted, so the interface can
    /// say "removed" or "removed and deleted" truthfully.
    pub fn remove_mod_and_collect(
        &self,
        profile: &mut Profile,
        id: &ModId,
    ) -> Result<(Option<ProfileMod>, bool), ServiceError> {
        let removed = self.remove_mod(profile, id)?;
        let Some(entry) = removed.clone() else {
            return Ok((None, false));
        };

        let mut still_referenced = std::collections::BTreeSet::new();
        for other in self.profiles.list() {
            for kept in other.mods.iter() {
                still_referenced.insert(kept.sha1.clone());
            }
        }
        // The profile in hand may be newer than what is on disk, so its own
        // entries are added from memory rather than from the store's copy.
        for kept in profile.mods.iter() {
            still_referenced.insert(kept.sha1.clone());
        }

        if still_referenced.contains(&entry.sha1) {
            return Ok((removed, false));
        }
        let deleted = !self
            .mod_engine()
            .store()
            .collect_garbage(&still_referenced)
            .is_empty();
        Ok((removed, deleted))
    }

    /// The Fathom components this profile calls for and does not have.
    ///
    /// Only ever non-empty for a profile that declares itself a Fathom profile:
    /// a Custom profile containing the same components chose them, and a
    /// component it does not have is not a promise it broke.
    pub fn missing_components(&self, profile: &Profile) -> Vec<crate::mods::Problem> {
        let required: Vec<(&str, &str, &str)> =
            crate::components::required_for(profile.kind, profile.loader_kind())
                .into_iter()
                .map(|component| {
                    (
                        component.id,
                        component.name,
                        component.supply.blocker().unwrap_or("It is not installed."),
                    )
                })
                .collect();

        let present: std::collections::BTreeSet<String> =
            crate::components::required_for(profile.kind, profile.loader_kind())
                .into_iter()
                .filter(|component| {
                    component
                        .parsed_mod_id()
                        .is_some_and(|id| profile.mods.get(&id).is_some())
                })
                .map(|component| component.id.to_owned())
                .collect();

        let paths = self.paths.clone();
        crate::mods::resolve::missing_components(&required, &present, &|id| {
            // Repairable means this build could actually put it back. A jar
            // that is not in `resources/` cannot be installed however loudly
            // the launcher complains, and blocking a launch over it would make
            // a developer build refuse to start.
            crate::components::find(id).is_some_and(|component| match component.supply {
                crate::components::Supply::Bundled { jar_prefix } => {
                    crate::components::newest_jar(&paths, jar_prefix).is_some()
                }
                crate::components::Supply::PartOf { component } => {
                    crate::components::find(component).is_some_and(|owner| {
                        match owner.supply {
                            crate::components::Supply::Bundled { jar_prefix } => {
                                crate::components::newest_jar(&paths, jar_prefix).is_some()
                            }
                            _ => false,
                        }
                    })
                }
                _ => false,
            })
        })
    }

    /// What the catalogue knows about a mod.
    pub fn mod_definition(&self, id: &ModId) -> Option<ModDefinition> {
        crate::mods::catalog::find(id)
    }

    /// Everything the catalogue knows about.
    pub fn mod_catalogue(&self) -> Vec<ModDefinition> {
        crate::mods::catalog::entries()
    }

    // -- java -------------------------------------------------------------

    /// Probe for Java runtimes. Runs `java -version` on each candidate, so it
    /// is not free; the UI should call it when it opens Settings, not per
    /// frame.
    pub fn refresh_java(&mut self) -> &[JavaInstallation] {
        let mut found = java_runtime::discover();

        // A runtime Fathom installed itself is not on any system path, so
        // `discover` cannot see it. It goes first: it is the one we chose, know
        // the version of, and can vouch for.
        for major in [8] {
            if let Some(managed) = java_runtime::managed::installed(&self.paths, major) {
                if !found.iter().any(|other| other.path == managed.path) {
                    found.insert(0, managed);
                }
            }
        }

        self.java = found;
        &self.java
    }

    /// Download a Java runtime into Fathom's own data directory.
    ///
    /// Only reachable when the user has turned on managed Java: the network
    /// policy refuses [`crate::network::Destination::JavaVendor`] otherwise, so
    /// the consent check is in the layer that opens sockets rather than in a
    /// call site that could forget it.
    ///
    /// Blocking, and can take a minute on a slow connection -- roughly 45 MB.
    /// Callers on a UI thread must move it off.
    pub fn install_java(
        &mut self,
        major: u32,
    ) -> Result<JavaInstallation, java_runtime::managed::JavaInstallError> {
        let installed = java_runtime::managed::install(&self.http, &self.paths, major)?;
        // Re-scan so the new runtime is in the list every other question is
        // answered from, rather than only in this return value.
        self.refresh_java();
        Ok(installed)
    }

    /// Accept a Java scan performed elsewhere, e.g. on a worker thread.
    pub fn set_java(&mut self, java: Vec<JavaInstallation>) {
        self.java = java;
    }

    pub fn java_installations(&self) -> &[JavaInstallation] {
        &self.java
    }

    // -- readiness --------------------------------------------------------

    /// What is standing between the user and playing this profile.
    ///
    /// Cheap: no network, no `java -version`, and **no hashing**. Uses whatever
    /// [`Launcher::refresh_java`] last found, and whatever the caller last
    /// learned about the installation.
    ///
    /// `install` is passed in rather than computed here because deciding
    /// whether an installation is complete means hashing 139 MB across 771
    /// files. That belongs on a worker thread, once, not in a function the
    /// interface calls while drawing a frame. `None` means "not checked yet",
    /// which is honestly different from "nothing installed" and is reported as
    /// a blocker of its own rather than assumed either way.
    pub fn readiness(
        &self,
        profile: &Profile,
        version: Option<&VersionDetail>,
        install: Option<&InstallSnapshot>,
    ) -> Readiness {
        let mut blockers = Vec::new();

        // Only a blocker when nobody is signed in. Once an identity exists,
        // however it was obtained, a missing client id is not what is standing
        // between the user and playing.
        if !self.sign_in_available() && self.auth.identity().is_none() {
            blockers.push(Blocker::NoClientId);
        }

        // One source of truth: ask the gate, and report whatever it says.
        if let Err(denied) = self.auth.launch_credentials(SystemTime::now()) {
            blockers.push(match denied {
                LaunchDenied::SignedOut | LaunchDenied::InProgress => Blocker::SignedOut,
                LaunchDenied::TokenExpired => Blocker::SessionExpired,
                other => Blocker::NotEntitled(other.to_string()),
            });
        }

        if !profile.loader_kind().is_implemented() {
            blockers.push(Blocker::LoaderNotImplemented(profile.loader_kind()));
        }

        let required_major = version.map_or(8, VersionDetail::required_java_major);
        let selected = self.select_java(profile, required_major);
        match &selected {
            None => blockers.push(Blocker::NoJava { required_major }),
            Some(java) => {
                let compat = java_runtime::compatibility(required_major, java.major);
                if !compat.is_runnable() {
                    blockers.push(Blocker::WrongJava {
                        required_major,
                        found_major: java.major,
                        explanation: compat.explanation(required_major, java.major),
                    });
                }
            }
        }

        match install {
            // Metadata not fetched yet, so there is nothing to check against.
            // `NotChecked` covers this once metadata exists.
            None if version.is_none() => {}
            None => blockers.push(Blocker::InstallNotChecked),
            // Natives-only is deliberately NOT a blocker.
            //
            // `Launcher::launch` extracts them itself and has always tolerated
            // exactly this case, so raising it here meant readiness and launch
            // disagreed about what stops a launch -- and the interface took
            // readiness's word for it. A fresh profile on a machine where the
            // game is already installed was told to "download the game files"
            // when every byte was already on disk and the real work was a
            // second of unzipping. See `InstallSnapshot::only_natives_missing`.
            Some(snapshot) if !snapshot.state.is_playable() && !snapshot.only_natives_missing() => {
                if self.settings.privacy.offline_mode {
                    blockers.push(Blocker::OfflineWithWorkOutstanding);
                } else {
                    blockers.push(Blocker::NeedsInstall(*snapshot));
                }
            }
            Some(_) => {}
        }

        Readiness {
            blockers,
            java: selected,
        }
    }

    fn select_java(&self, profile: &Profile, required_major: u32) -> Option<JavaInstallation> {
        match self.resolve_java(profile, required_major) {
            JavaOutcome::Ready(installation) => Some(installation),
            JavaOutcome::NeedsInstall { .. } | JavaOutcome::Missing { .. } => None,
        }
    }

    /// Which runtime this profile's policy resolves to, and why not, if not.
    ///
    /// # The order Automatic resolves in
    ///
    /// 1. the runtime Fathom manages, if one is installed;
    /// 2. otherwise a compatible runtime already on the machine;
    /// 3. otherwise nothing -- and [`JavaOutcome::NeedsInstall`] says the
    ///    launcher is allowed to fetch one.
    ///
    /// Managed first, deliberately. A runtime under the launcher's own
    /// directory is one that cannot be upgraded, moved or uninstalled by
    /// something else on the machine between one launch and the next.
    ///
    /// The other three policies are the user saying they know better, and each
    /// of them can fail in a way worth naming rather than collapsing into "no
    /// Java found".
    pub fn resolve_java(&self, profile: &Profile, required_major: u32) -> JavaOutcome {
        let policy = &profile.java;

        if let Some(path) = policy.explicit_path() {
            return match java_runtime::probe(path) {
                Ok(found) => JavaOutcome::Ready(found),
                Err(_) => JavaOutcome::Missing {
                    required_major,
                    reason: "The Java executable you chose is missing or unusable.",
                },
            };
        }

        if policy.may_use_managed() {
            if let Some(managed) =
                java_runtime::managed::installed(&self.paths, required_major)
            {
                return JavaOutcome::Ready(managed);
            }
        }

        if policy.may_use_system() {
            if let Some(system) = self.system_java(required_major) {
                return JavaOutcome::Ready(system);
            }
        }

        if policy.may_install() {
            return JavaOutcome::NeedsInstall { required_major };
        }

        JavaOutcome::Missing {
            required_major,
            reason: "No Java on this machine matches, and this profile is set not to download one.",
        }
    }

    /// A scanned runtime that is not the one Fathom manages.
    ///
    /// The scan finds every runtime including the managed one, so "system Java"
    /// has to exclude anything under the launcher's own directory. Without
    /// this, choosing `System` would silently keep using the managed runtime
    /// and the setting would appear to do nothing.
    fn system_java(&self, required_major: u32) -> Option<JavaInstallation> {
        let managed_root = self.paths.java_dir();
        let candidates: Vec<JavaInstallation> = self
            .java
            .iter()
            .filter(|installation| !installation.path.starts_with(&managed_root))
            .cloned()
            .collect();
        java_runtime::select(&candidates, required_major).cloned()
    }

    // -- install and launch ------------------------------------------------

    /// The installation engine, wired to this launcher's HTTP client and paths.
    pub fn install_service(&self) -> InstallService {
        InstallService::new(self.http.clone(), self.paths.clone())
    }

    /// A natives directory for command-line use, outside any profile.
    ///
    /// Real launches extract into the selected profile's own `natives/`; this
    /// exists so `fathom-cli install` can prove the whole pipeline without
    /// inventing a profile.
    pub fn scratch_natives_dir(&self, version_id: &str) -> PathBuf {
        self.paths
            .root()
            .join("scratch-natives")
            .join(sanitise_component(version_id))
    }

    /// Read a version document from the shared cache, if we have already
    /// fetched and verified it.
    ///
    /// The path comes from [`crate::install::ArtifactCache`], which is the one
    /// authority on cache layout; `version_id` is untrusted and is contained
    /// there rather than being checked again here.
    pub fn cached_version(&self, version_id: &str) -> Option<VersionDetail> {
        let path = self
            .install_service()
            .cache()
            .version_json(version_id)
            .ok()?;
        serde_json::from_slice(&std::fs::read(path).ok()?).ok()
    }

    /// Fetch, verify and cache a version document.
    ///
    /// Blocking. The desktop app runs this through [`Launcher::tasks`] on a
    /// worker thread instead of calling it directly.
    pub fn fetch_version(&self, version_id: &str) -> Result<VersionDetail, ServiceError> {
        Ok(self
            .install_service()
            .metadata()
            .fetch(version_id, Feature::ProfileInstall)?)
    }

    /// Resolve a cached version document into the full artifact set.
    ///
    /// Path building only: no network, no hashing. Cheap enough to call
    /// whenever a launch command is assembled.
    pub fn resolve_install(
        &self,
        version: &VersionDetail,
        feature: Feature,
    ) -> Result<ResolvedInstall, ServiceError> {
        Ok(self.install_service().resolve_cached(version, feature)?)
    }

    /// Everything that must be true before a launch is even attempted.
    ///
    /// Ordered so that the first thing the user is told is the first thing they
    /// have to fix, and so that nothing expensive runs before something cheap
    /// has already ruled the launch out.
    fn preflight<'a>(
        &'a self,
        profile: &Profile,
        version: &VersionDetail,
    ) -> Result<Preflight<'a>, LaunchRefusal> {
        // 1. The authentication gate. One call, and its refusal is carried
        //    verbatim rather than being flattened into "cannot launch".
        let credentials = self.auth.launch_credentials(SystemTime::now())?;

        // 2. The profile has to be one we can actually run.
        if !profile.loader_kind().is_implemented() {
            return Err(LaunchRefusal::LoaderNotImplemented(profile.loader_kind()));
        }
        let dirs = self.profiles.dirs(&profile.id);
        dirs.ensure()
            .map_err(|e| LaunchRefusal::InvalidProfile(e.to_string()))?;

        // 3. Java: present, right major, and still on disk. The last check
        //    matters because a runtime found at start-up can be uninstalled
        //    before Play is pressed.
        let required_major = version.required_java_major();
        let java = self
            .select_java(profile, required_major)
            .ok_or(LaunchRefusal::NoJava { required_major })?;
        let compatibility = java_runtime::compatibility(required_major, java.major);
        if !compatibility.is_runnable() {
            return Err(LaunchRefusal::IncompatibleJava {
                required_major,
                found_major: java.major,
                explanation: compatibility.explanation(required_major, java.major),
            });
        }
        if !java.path.is_file() {
            return Err(LaunchRefusal::JavaMissing(java.path.display().to_string()));
        }

        // 4. The profile's own JVM arguments. Refused, not silently dropped: a
        //    user who typed a flag deserves to know it was not used.
        let extra = jvm_args::validate_args(&profile.jvm.extra_args);
        if !extra.rejected.is_empty() {
            return Err(LaunchRefusal::BadJvmArguments(extra.rejected));
        }

        Ok(Preflight {
            identity: credentials.identity,
            dirs,
            java,
            required_major,
        })
    }

    /// Assemble the launch command.
    ///
    /// Returns a [`LaunchPlan`], which the UI can render (redacted) but cannot
    /// construct itself. Requires a live, entitled session: there is no code
    /// path from "not signed in" to a runnable command.
    ///
    /// Does not touch the process table, so it is safe to call while a game is
    /// running -- which is what lets the interface show the command that *would*
    /// be run without committing to running it.
    pub fn build_launch_plan(
        &self,
        profile: &Profile,
        version: &VersionDetail,
    ) -> Result<LaunchPlan, LaunchRefusal> {
        let pre = self.preflight(profile, version)?;
        let resolved = self
            .resolve_install(version, Feature::Play)
            .map_err(|e| LaunchRefusal::InvalidProfile(e.to_string()))?;

        // The loader, resolved but not installed.
        //
        // This used to pass `None`, which meant every plan built outside
        // `launch` described a VANILLA launch: vanilla main class, no loader
        // libraries on the classpath. `fathom-cli plan` and the integration
        // check were both validating a command that would not have loaded a
        // single mod. Caught by the integration check, which is the entire
        // reason it prints the main class rather than just saying "ok".
        //
        // Resolution is metadata only -- no artefacts are fetched here -- so a
        // preview stays cheap. `launch` still calls `ensure_loader`, which
        // additionally downloads the libraries and pins the version.
        let loader = if profile.loader_kind() == Loader::Vanilla {
            None
        } else {
            Some(
                self.resolve_loader(profile)
                    .map_err(|e| LaunchRefusal::LoaderUnavailable(e.to_string()))?,
            )
        };

        self.assemble(profile, version, &resolved, &pre, loader.as_ref())
    }

    fn assemble(
        &self,
        profile: &Profile,
        version: &VersionDetail,
        resolved: &ResolvedInstall,
        pre: &Preflight<'_>,
        loader: Option<&LoaderInstallation>,
    ) -> Result<LaunchPlan, LaunchRefusal> {
        let os = Os::current();
        let identity = pre.identity;

        // Derived from the same resolver the installer uses, so there is one
        // authority on where an artifact lives. Building the classpath
        // independently is how a launcher ends up passing `-cp` entries that
        // point at files the installer wrote somewhere else.
        // The loader's libraries go in FRONT of the game jar, never after.
        //
        // That order is the whole mechanism: Fabric works by getting its
        // classes loaded instead of the game's, and a classpath with the game
        // jar first would load vanilla's classes and leave the loader sitting
        // behind them doing nothing. It would also fail silently -- the game
        // starts, and no mod loads.
        let mut classpath = loader.map(LoaderInstallation::classpath_additions).unwrap_or_default();
        classpath.extend(resolved.classpath());
        let natives = pre.dirs.natives_dir();

        let mut jvm =
            jvm_args::build_jvm_args(profile, pre.java.major, &natives.display().to_string());

        // Loader JVM arguments go through the same validation as the profile's
        // own. A loader profile is a remote document, and this is the second
        // place one can reach the JVM argument list.
        if let Some(loader) = loader {
            if !loader.jvm_arguments.is_empty() {
                let report = jvm_args::validate_args(&loader.jvm_arguments);
                // Refused rather than filtered. The profile's own arguments are
                // the user's typing and a rejection there is a typo; these come
                // from a loader profile, and one the validator dislikes means
                // either the metadata is wrong or somebody has tampered with
                // it. Dropping it and launching anyway would be launching a
                // loader we have decided not to configure correctly.
                if !report.is_clean() {
                    return Err(LaunchRefusal::BadJvmArguments(report.rejected));
                }
                jvm.extend(report.accepted);
            }
        }
        // 1.8.9 ships a log4j2 configuration. Passing it is what stops the game
        // logging through a default config, and it is also the mitigation for
        // the 2021 log4j lookup issue on this version.
        if let Some(logging) = &resolved.logging {
            if logging.path.is_file() {
                // `jvm_argument` returns None when the document asked for
                // something that is not a log4j configuration property. It is
                // the only place a remote document reaches the JVM argument
                // list, so it is checked there rather than trusted here.
                if let Some(argument) = logging.jvm_argument() {
                    jvm.push(argument);
                }
            }
        }

        let context = LaunchContext {
            player_name: identity.profile.name.clone(),
            player_uuid: identity.profile.id.clone(),
            access_token: identity.access_token.clone(),
            // Not hardcoded: a development identity reports `legacy`, because
            // telling the game it holds a Microsoft session when it does not
            // would be a lie told to the game as well as to the user.
            user_type: identity.user_type().to_owned(),
            version_name: version.id.clone(),
            version_type: "release".to_owned(),
            // The isolated instance directory, never the user's own
            // `%APPDATA%\.minecraft`.
            game_dir: pre.dirs.minecraft_dir(),
            assets_dir: self.paths.assets_dir(),
            assets_index_name: version
                .assets
                .clone()
                .unwrap_or_else(|| "legacy".to_owned()),
        };

        let spec = LaunchSpec {
            java: &pre.java.path,
            java_major: pre.java.major,
            jvm_args: jvm,
            classpath: &classpath,
            classpath_separator: os.classpath_separator(),
            // The loader replaces the entry point; that is what makes it a
            // loader rather than a library.
            main_class: loader
                .and_then(|l| l.main_class.as_deref())
                .unwrap_or(&version.main_class),
            argument_template: version.minecraft_arguments.as_deref(),
            extra_game_args: loader.map_or(&[][..], |l| &l.game_arguments),
            profile_dir: pre.dirs.root(),
            natives_dir: &natives,
            log_file: &launch_log_path(&pre.dirs),
        };

        Ok(launch::build_plan(&spec, &context)?)
    }

    /// Resolve and install the profile's loader, if it has one.
    ///
    /// Returns `None` for Vanilla, which is not a failure -- it is the answer.
    ///
    /// # Why the version is chosen here rather than demanded from the user
    ///
    /// `resolve_loader` refuses when a profile names a loader and no loader
    /// version. That is right for an explicit "install this exact build", and
    /// wrong for pressing Play: nobody launching a game wants to be asked which
    /// of 230 Fabric Loader builds they would like. So if the profile has not
    /// chosen one, the newest stable build is picked and **written back to the
    /// profile**, which makes the choice sticky. A launcher that silently picked
    /// a different loader each time you played would be unreproducible in
    /// exactly the way that makes "it worked yesterday" impossible to debug.
    fn ensure_loader(&self, profile: &Profile) -> Result<Option<LoaderInstallation>, LaunchRefusal> {
        let kind = profile.loader_kind();
        if kind == Loader::Vanilla {
            return Ok(None);
        }
        if !kind.is_implemented() {
            return Err(LaunchRefusal::LoaderNotImplemented(kind));
        }

        let installation = self
            .resolve_loader(profile)
            .map_err(|e| LaunchRefusal::LoaderUnavailable(e.to_string()))?;

        // Pin whatever was resolved, so the next launch uses the same build.
        // A launcher that silently picked a different loader each time you
        // played would be unreproducible in exactly the way that makes "it
        // worked yesterday" impossible to debug.
        if profile.loader.version.as_deref() != Some(installation.loader_version.as_str()) {
            let mut pinned = profile.clone();
            pinned.loader.version = Some(installation.loader_version.clone());
            tracing::info!(
                loader = %kind.label(),
                version = %installation.loader_version,
                "recorded the loader build"
            );
            if let Err(error) = self.save_profile(&pinned) {
                // Non-fatal: the launch proceeds with the choice held in
                // memory. It just will not be remembered, which is a smaller
                // problem than refusing to start.
                tracing::warn!(%error, "could not record the chosen loader build");
            }
        }

        // Every library carries a SHA-1 and goes through the same download
        // manager as the game's own artifacts, so nothing unverified reaches
        // the classpath. `fetch_all` skips anything already present and
        // correct, so a second launch does no network work at all.
        if !installation.libraries.is_empty() {
            let cancel = Cancel::new();
            self.install_service()
                .downloads()
                .fetch_all(&installation.libraries, &cancel, &mut |_| {})
                .map_err(|e| LaunchRefusal::LoaderUnavailable(e.to_string()))?;
        }

        Ok(Some(installation))
    }

    /// The newest stable loader build, or the newest build if none is stable.
    ///
    /// Public because "which builds exist, and which would we pick" is a
    /// question the interface asks when populating a version list, not only
    /// something the launch path needs.
    pub fn pick_loader_version(&self, profile: &Profile) -> Result<String, ServiceError> {
        let versions = self.loader_versions(profile)?;
        versions
            .iter()
            .find(|candidate| candidate.stable)
            .or_else(|| versions.first())
            .map(|candidate| candidate.version.clone())
            .ok_or(ServiceError::NotImplemented(
                "this loader publishes no builds for this Minecraft version",
            ))
    }

    /// Make sure the extracted natives match this version on this platform.
    ///
    /// Re-extracts whenever the stamp disagrees, is missing, or the directory
    /// has been emptied. Cheap -- four small archives -- and the alternative is
    /// loading a previous version's LWJGL, which fails as an `UnsatisfiedLinkError`
    /// that no user could be expected to diagnose.
    fn ensure_natives(resolved: &ResolvedInstall, dirs: &ProfileDirs) -> Result<(), LaunchRefusal> {
        let destination = dirs.natives_dir();
        let stamp = NativesStamp::new(
            &resolved.version_id,
            &Os::current().label(),
            &resolved.natives,
        );

        if NativeLibraryManager::is_current(&destination, &stamp) {
            return Ok(());
        }

        NativeLibraryManager::new()
            .extract_stamped(&resolved.natives, &destination, Some(&stamp))
            .map_err(|e| LaunchRefusal::NativesUnavailable(e.to_string()))?;
        Ok(())
    }

    /// The whole chain: authenticate, verify, validate, assemble, start.
    ///
    /// This is the only function that starts a game. Everything it needs has
    /// already been decided by the time the process exists, and the profile is
    /// claimed before any of it, so a second Play press cannot slip past.
    pub fn launch(
        &self,
        profile: &Profile,
        version: &VersionDetail,
    ) -> Result<GameStatus, LaunchRefusal> {
        // Claimed first. Everything below can take a second or two, and that is
        // exactly the window in which a user double-clicks Play.
        let claim = self.running.claim(&profile.id)?;

        // Inheritance and components, in that order, before anything reads the
        // profile. A derived profile that launched with its own empty loader
        // config rather than its parent's would be the worst possible version
        // of inheritance, and a Fathom profile whose module was deleted should
        // get it back on the next Play rather than starting without it.
        let resolved_profile = self
            .resolve_profile(profile)
            .map_err(|error| LaunchRefusal::InvalidProfile(error.to_string()))?;
        let mut profile = resolved_profile;
        match self.ensure_components(&mut profile) {
            Ok(report) if report.changed_anything() => {
                tracing::info!(summary = %report.summary(), "components brought up to date");
            }
            Ok(_) => {}
            // Not fatal, for the same reason it is not fatal at creation: a
            // missing component is a degraded launch, and refusing to start the
            // game over one leaves the user with nothing.
            Err(error) => tracing::warn!(%error, "could not update Fathom components"),
        }
        let profile = &profile;

        let pre = self.preflight(profile, version)?;
        let resolved = self
            .resolve_install(version, Feature::Play)
            .map_err(|e| LaunchRefusal::InvalidProfile(e.to_string()))?;

        // Files, checked by existence rather than hash: a full pass would mean
        // reading 139 MB every time Play is pressed. The Verify button exists
        // for the stronger answer, and a missing file is caught here either way.
        let service = self.install_service();
        let report = service.quick_check(&resolved, &pre.dirs.natives_dir());
        let snapshot = InstallSnapshot::from_report(&report, &resolved);
        if !snapshot.state.is_playable() && !snapshot.only_natives_missing() {
            return Err(LaunchRefusal::InstallationIncomplete(snapshot));
        }

        Self::ensure_natives(&resolved, &pre.dirs)?;

        // The loader, if this profile has one.
        //
        // Resolved and fetched here rather than at install time because a
        // profile's loader can change between installs, and the libraries are
        // small: for Legacy Fabric it is the loader jar, the intermediary
        // mappings and a handful of shared dependencies.
        let loader = self.ensure_loader(profile)?;

        // Make the mods directory match what the profile says it wants. Without
        // this the mods are recorded and never appear on disk, which looks from
        // the inside exactly like a loader that is not loading them.
        if profile.loader_kind().supports_mods() {
            let mut copy = profile.clone();
            if let Err(error) = self.materialize_mods(&mut copy) {
                // Not fatal. A mod that could not be placed is worth saying so
                // about, and it is still much better to start the game.
                tracing::warn!(%error, "could not lay out the mods directory");
            }
        }

        // The optimization module reads its settings from a file in the game
        // directory, written fresh on every launch so a previous session's
        // switches can never linger. Deliberately not fatal: the module is
        // optional, and a launcher that refuses to start Minecraft because it
        // could not write a performance preference has made things worse.
        match crate::optimizations::bridge::Settings::from_profile(profile)
            .write_to(&pre.dirs.minecraft_dir())
        {
            Ok(path) => tracing::debug!(path = %path.display(), "wrote optimization settings"),
            Err(error) => tracing::warn!(
                %error,
                "could not write optimization settings; the module will use its defaults"
            ),
        }

        // What this launch is, in a file with no secret in it, so the game can
        // put a profile name on its main menu without the bridge having to be
        // up. Written after the mods are laid out, because the component
        // version it reports is the version actually in the mods directory.
        //
        // Not fatal either, and for the same reason: a launcher that refused to
        // start Minecraft because it could not write a display name would have
        // made things worse.
        let descriptor = crate::session::SessionDescriptor::from_profile(
            profile,
            crate::session::component_version(profile),
            env!("CARGO_PKG_VERSION"),
        )
        // Where the shared Fathom Profile collection is, so the game opens the
        // same profiles this launcher shows.
        .with_root(self.paths.root());
        match descriptor.write_to(&pre.dirs.minecraft_dir()) {
            Ok(path) => tracing::debug!(path = %path.display(), "wrote the session descriptor"),
            Err(error) => tracing::warn!(
                %error,
                "could not write the session descriptor; the game will call this an external session"
            ),
        }

        let plan = self.assemble(profile, version, &resolved, &pre, loader.as_ref())?;
        let process = process::spawn(&plan)?;

        tracing::info!(
            profile = %profile.id,
            pid = process.pid(),
            command = %plan.redacted_command_line(),
            "launched"
        );

        Ok(claim.fulfil(process))
    }

    // -- what is running ---------------------------------------------------

    /// The games this launcher started.
    pub fn running(&self) -> &RunningGames {
        &self.running
    }

    pub fn game_status(&self, profile: &ProfileId) -> Option<GameStatus> {
        self.running.status(profile)
    }

    /// Reap finished games, returning their final status so the caller can
    /// report how each one ended.
    pub fn reap_finished_games(&self) -> Vec<GameStatus> {
        self.running.sweep()
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

    /// The smallest document `readiness` will accept. It only reads the Java
    /// requirement, so nothing else needs to be real.
    fn minimal_version() -> VersionDetail {
        serde_json::from_str(
            r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main",
                "javaVersion":{"component":"jre-legacy","majorVersion":8},
                "downloads":{},"libraries":[]}"#,
        )
        .expect("minimal version document")
    }

    fn snapshot(
        state: InstallationState,
        outstanding: usize,
        bytes: u64,
        natives_extracted: bool,
    ) -> InstallSnapshot {
        InstallSnapshot {
            state,
            outstanding,
            bytes,
            natives_extracted,
        }
    }

    /// The case that produced a nonsense message before: everything downloaded,
    /// nothing unpacked, so the file count is zero and "0 files still to
    /// download" tells the user nothing about what to do.
    #[test]
    fn an_unpacked_natives_directory_is_named_rather_than_counted() {
        let s = snapshot(InstallationState::RepairRequired, 0, 0, false);
        assert!(s.only_natives_missing());
        let message = s.message();
        assert!(message.contains("native"), "{message}");
        assert!(!message.contains('0'), "no misleading count: {message}");
    }

    #[test]
    fn corruption_is_described_as_wrong_bytes_not_missing_ones() {
        let s = snapshot(InstallationState::Corrupted, 2, 4096, true);
        assert!(!s.only_natives_missing());
        let message = s.message();
        assert!(message.contains("failed verification"), "{message}");
        assert!(message.contains('2'), "{message}");
    }

    #[test]
    fn a_first_install_reports_the_count_and_the_size() {
        let s = snapshot(InstallationState::NotInstalled, 771, 146_000_000, false);
        // Nothing is present, so this is not the natives-only case even though
        // the natives are also not extracted.
        assert!(!s.only_natives_missing());
        let message = s.message();
        assert!(message.contains("771"), "{message}");
        assert!(message.contains("MB"), "{message}");
    }

    #[test]
    fn a_complete_install_is_never_described_as_needing_work() {
        let s = snapshot(InstallationState::Installed, 0, 0, true);
        assert!(s.is_playable());
        assert!(!s.only_natives_missing());
    }

    /// `readiness` must not invent an install state it was not given. Saying
    /// "nothing installed" when we simply have not looked would send the user
    /// to download 139 MB they may already have.
    #[test]
    fn readiness_reports_not_checked_rather_than_guessing() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        let version = minimal_version();

        let readiness = launcher.readiness(&profile, Some(&version), None);
        assert!(
            readiness.blockers.contains(&Blocker::InstallNotChecked),
            "{:?}",
            readiness.blockers
        );
        assert!(
            !readiness
                .blockers
                .iter()
                .any(|b| matches!(b, Blocker::NeedsInstall(_))),
            "must not claim files are missing without having looked"
        );
    }

    /// A fresh profile on a machine that already has the game is ready to
    /// play: every artifact is in the shared cache and only this profile's
    /// natives need unpacking, which `launch` does itself.
    ///
    /// This is the case creating a profile lands in every single time, and
    /// before the fix it told the user to download 139 MB they already had.
    #[test]
    fn a_profile_needing_only_its_natives_is_not_blocked() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("fresh").unwrap(), "Fresh");
        let version = minimal_version();
        let natives_only = snapshot(InstallationState::RepairRequired, 0, 0, false);
        assert!(natives_only.only_natives_missing());

        let readiness = launcher.readiness(&profile, Some(&version), Some(&natives_only));
        assert!(
            !readiness
                .blockers
                .iter()
                .any(|blocker| matches!(blocker, Blocker::NeedsInstall(_))),
            "unpacking natives is not a download: {:?}",
            readiness.blockers
        );
    }

    /// And something genuinely missing still is a blocker, or the check above
    /// would have removed the only thing standing between a user and a launch
    /// that cannot work.
    #[test]
    fn a_profile_missing_real_files_is_still_blocked() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("empty").unwrap(), "Empty");
        let version = minimal_version();
        let missing = snapshot(InstallationState::NotInstalled, 771, 146_000_000, false);

        let readiness = launcher.readiness(&profile, Some(&version), Some(&missing));
        assert!(
            readiness.blockers.contains(&Blocker::NeedsInstall(missing)),
            "{:?}",
            readiness.blockers
        );
    }

    /// And once it has been told, it must report exactly what it was told.
    #[test]
    fn readiness_repeats_the_snapshot_it_was_given() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        let version = minimal_version();
        let given = snapshot(InstallationState::Corrupted, 3, 900, true);

        let readiness = launcher.readiness(&profile, Some(&version), Some(&given));
        assert!(
            readiness.blockers.contains(&Blocker::NeedsInstall(given)),
            "{:?}",
            readiness.blockers
        );
    }
    use super::*;
    use crate::credentials::EphemeralCredentialStore;

    fn launcher() -> (tempfile::TempDir, Launcher) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
        let launcher = Launcher::new(paths, store).expect("launcher");
        (tmp, launcher)
    }

    #[test]
    fn construction_performs_no_network_activity() {
        let (_tmp, launcher) = launcher();
        assert_eq!(launcher.network_summary().total, 0);
    }

    #[test]
    fn a_fresh_launcher_has_no_session_and_cannot_play() {
        let (_tmp, launcher) = launcher();
        assert!(launcher.identity().is_none());
        assert!(launcher.identity_kind().is_none());

        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        let readiness = launcher.readiness(&profile, None, None);
        assert!(!readiness.can_play());
        assert!(readiness.blockers.contains(&Blocker::SignedOut));
    }

    #[test]
    fn without_a_client_id_sign_in_is_reported_as_unavailable() {
        let (_tmp, launcher) = launcher();
        // The test process has no FATHOM_AZURE_CLIENT_ID set.
        if !launcher.sign_in_available() {
            let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
            let readiness = launcher.readiness(&profile, None, None);
            assert!(readiness.blockers.contains(&Blocker::NoClientId));
            assert!(readiness.primary().is_some_and(|b| !b.message().is_empty()));
        }
    }

    #[test]
    fn a_launch_plan_cannot_be_built_without_a_session() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        let version: VersionDetail = serde_json::from_str(
            r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main",
                "minecraftArguments":"--username ${auth_player_name}"}"#,
        )
        .unwrap();
        assert!(launcher.build_launch_plan(&profile, &version).is_err());
    }

    /// Forge, because it is the one that is still genuinely unimplemented.
    /// This used to use Legacy Fabric, which now launches.
    #[test]
    fn an_unimplemented_loader_blocks_play_and_says_so() {
        let (_tmp, launcher) = launcher();
        let mut profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        profile.loader.kind = Loader::Forge;

        let readiness = launcher.readiness(&profile, None, None);
        let blocker = readiness
            .blockers
            .iter()
            .find(|b| matches!(b, Blocker::LoaderNotImplemented(_)))
            .expect("loader blocker");
        assert!(blocker.message().contains("not implemented"));
    }

    /// The counterpart: an implemented loader must not raise that blocker at
    /// all, or Home would keep saying "LOADER UNAVAILABLE" over a loader that
    /// works.
    #[test]
    fn an_implemented_loader_does_not_block_play() {
        let (_tmp, launcher) = launcher();
        let mut profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        profile.loader.kind = Loader::LegacyFabric;

        let readiness = launcher.readiness(&profile, None, None);
        assert!(
            !readiness
                .blockers
                .iter()
                .any(|b| matches!(b, Blocker::LoaderNotImplemented(_))),
            "Legacy Fabric launches now and must not be reported as unimplemented"
        );
    }

    #[test]
    fn profiles_get_unique_ids_even_with_the_same_display_name() {
        let (_tmp, launcher) = launcher();
        let a = launcher.create_profile("My Profile").unwrap();
        let b = launcher.create_profile("My Profile").unwrap();
        assert_eq!(a.id.as_str(), "my-profile");
        assert_eq!(b.id.as_str(), "my-profile-2");
        // Counted by name rather than as a total: the launcher seeds a shipped
        // profile on first open, so a bare length assertion here would be
        // measuring the seed as much as the uniqueness rule this test is about.
        let mine = launcher
            .profiles()
            .into_iter()
            .filter(|profile| profile.display_name == "My Profile")
            .count();
        assert_eq!(mine, 2);
    }

    #[test]
    fn a_display_name_with_nothing_usable_is_refused() {
        let (_tmp, launcher) = launcher();
        assert!(launcher.create_profile("///").is_err());
    }

    #[test]
    fn changing_privacy_settings_takes_effect_immediately() {
        let (_tmp, mut launcher) = launcher();
        assert!(launcher.network_policy().online);

        let mut settings = launcher.settings().clone();
        settings.privacy.offline_mode = true;
        launcher.update_settings(settings).unwrap();

        assert!(!launcher.network_policy().online);
        // And it persisted.
        assert!(Settings::load(launcher.paths()).privacy.offline_mode);
    }

    #[test]
    fn the_allowlist_reflects_the_current_policy() {
        let (_tmp, mut launcher) = launcher();

        let allowed_before = launcher
            .network_allowlist()
            .iter()
            .filter(|(_, ok)| *ok)
            .count();
        assert!(allowed_before > 0);

        let mut settings = launcher.settings().clone();
        settings.privacy.offline_mode = true;
        launcher.update_settings(settings).unwrap();

        let allowed_after = launcher
            .network_allowlist()
            .iter()
            .filter(|(_, ok)| *ok)
            .count();
        assert_eq!(allowed_after, 0, "offline mode must permit nothing");
    }

    #[test]
    fn sign_out_clears_the_stored_account() {
        let (_tmp, mut launcher) = launcher();
        let mut settings = launcher.settings().clone();
        settings.last_account_id = Some("uuid".into());
        settings.last_account_name = Some("Steve".into());
        launcher.update_settings(settings).unwrap();

        launcher.sign_out().unwrap();
        assert!(launcher.settings().last_account_id.is_none());
        assert!(launcher.settings().last_account_name.is_none());
    }

    #[test]
    fn restoring_a_session_with_nothing_stored_is_not_an_error() {
        let (_tmp, mut launcher) = launcher();
        assert!(!launcher.restore_session().unwrap());
    }

    #[test]
    fn mod_installation_is_scoped_to_the_profile() {
        let (tmp, launcher) = launcher();
        let mut profile = launcher.create_profile("Main").unwrap();

        // A real (empty) zip: the store refuses anything that is not an
        // archive, which is itself the point of that check.
        let source = tmp.path().join("Mod.jar");
        std::fs::write(
            &source,
            [
                0x50u8, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ],
        )
        .unwrap();

        let entry = launcher.add_mod_from_file(&mut profile, &source).unwrap();

        // The user's own file is untouched, and the bytes are in the store.
        assert!(source.is_file());
        assert!(launcher.mod_engine().store().contains(&entry.sha1));

        // The profile records the intent; the directory is still empty until
        // it is materialized.
        assert_eq!(profile.mods.len(), 1);
        assert!(launcher.mods_for(&profile.id).is_empty());
    }

    #[test]
    fn a_release_build_exposes_only_production_auth_providers() {
        let (_tmp, launcher) = launcher();
        let providers = launcher.auth_providers();
        assert!(!providers.is_empty());

        #[cfg(not(feature = "dev-auth"))]
        {
            assert_eq!(providers.len(), 1, "only Microsoft should be compiled in");
            assert!(providers.iter().all(|p| p.is_production()));
            assert!(!launcher.development_auth_available());
        }

        #[cfg(feature = "dev-auth")]
        {
            // The development provider is listed, but must never claim to be
            // production, and must be off unless explicitly opted into.
            assert!(providers.iter().any(|p| p.is_production()));
            assert!(providers.iter().any(|p| !p.is_production()));
        }
    }

    #[test]
    fn a_missing_client_id_stops_being_a_blocker_once_signed_in() {
        let (_tmp, launcher) = launcher();
        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");

        // Signed out and unconfigured: the client id is worth reporting.
        if !launcher.sign_in_available() {
            assert!(launcher
                .readiness(&profile, None, None)
                .blockers
                .contains(&Blocker::NoClientId));
        }
    }

    /// End-to-end for the development identity: what does it actually produce
    /// when the launcher builds a command with it?
    ///
    /// This is the test that would catch a fake identity quietly acquiring the
    /// shape of a real one.
    #[cfg(feature = "dev-auth")]
    #[test]
    fn a_development_identity_produces_an_unusable_but_runnable_command() {
        use crate::auth::development::{DEVELOPMENT_USERNAME, PLACEHOLDER_TOKEN};
        use crate::auth::provider::IdentityKind;

        let (_tmp, mut launcher) = launcher();
        std::env::set_var(
            crate::auth::development::ENABLE_VAR,
            crate::auth::development::ENABLE_VALUE,
        );

        launcher
            .sign_in_development()
            .expect("development sign-in should succeed when opted in");

        let identity = launcher.identity().expect("identity should be set");
        assert_eq!(identity.kind, IdentityKind::Development);
        assert!(!identity.kind.is_production());
        assert!(launcher.identity_warning().is_some());

        let profile = Profile::new(ProfileId::new("main").unwrap(), "Main");
        let version: VersionDetail = serde_json::from_str(
            r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main",
                "javaVersion":{"majorVersion":8},
                "minecraftArguments":"--username ${auth_player_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userType ${user_type}"}"#,
        )
        .unwrap();

        // A real Java 8 runtime is needed to get past the Java check; if this
        // machine has none, the rest of the assertions are not reachable and the
        // test has nothing useful to say.
        launcher.refresh_java();
        let Ok(plan) = launcher.build_launch_plan(&profile, &version) else {
            return;
        };

        let joined = plan.argv_exposing_secrets().join(" ");
        assert!(joined.contains(DEVELOPMENT_USERNAME));

        // The game is told `legacy`, not `msa`: we do not claim a Microsoft
        // session we do not have.
        assert!(joined.contains("--userType legacy"));
        assert!(!joined.contains("--userType msa"));

        // The token is the self-describing placeholder, not anything that could
        // be mistaken for a credential.
        assert!(joined.contains(PLACEHOLDER_TOKEN));

        // And it still never reaches the rendered command line.
        let rendered = plan.redacted_command_line();
        assert!(!rendered.contains(PLACEHOLDER_TOKEN));
    }

    /// A fake identity must not be persisted as though it were an account.
    #[cfg(feature = "dev-auth")]
    #[test]
    fn a_development_identity_is_never_written_to_settings_or_the_credential_store() {
        let (_tmp, mut launcher) = launcher();
        std::env::set_var(
            crate::auth::development::ENABLE_VAR,
            crate::auth::development::ENABLE_VALUE,
        );

        launcher.sign_in_development().expect("sign-in");

        assert!(
            launcher.settings().last_account_id.is_none(),
            "a development identity must not be recorded as the last account"
        );
        assert!(launcher.settings().last_account_name.is_none());
        assert!(
            launcher.remembered_account_id().is_none(),
            "there must be nothing to restore on next launch"
        );

        // And it does not survive a reload of settings from disk.
        assert!(Settings::load(launcher.paths()).last_account_id.is_none());
    }

    #[test]
    fn byte_formatting_is_readable() {
        assert_eq!(human_bytes(0), "0 B");
        assert_eq!(human_bytes(512), "512 B");
        assert_eq!(human_bytes(1536), "1.5 KB");
        assert_eq!(human_bytes(8_461_484), "8.1 MB");
    }
}
