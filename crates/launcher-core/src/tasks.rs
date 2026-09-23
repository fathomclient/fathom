//! Blocking operations, packaged so they can run off the interface thread.
//!
//! [`Launcher`](crate::Launcher) owns interface state — settings, the profile
//! list, the current session — and is therefore not `Send`. The slow work
//! (HTTP, hashing, spawning `java -version`) needs none of that state, only a
//! handful of cheap handles. [`Tasks`] is those handles, and it is
//! `Clone + Send + Sync`, so a caller can hand a clone to a thread.
//!
//! Nothing here starts a thread of its own. It is the caller's job to decide
//! where these run; `Launcher` calls them inline for the CLI, and the desktop
//! app runs them on a worker thread.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::auth::{AuthService, DeviceCodePrompt, PollOutcome, Session};
use crate::credentials::CredentialStore;
use crate::install::{
    InstallService, InstallStage, RepairOutcome, ResolvedInstall, VerificationReport,
};
use crate::java_runtime::{self, JavaInstallation};
use crate::minecraft_runtime::version::VersionDetail;
use crate::network::{Feature, HttpClient};
use crate::paths::DataPaths;
use crate::secret::Secret;
use crate::services::{InstallSnapshot, ServiceError};

/// A cooperative cancellation flag.
///
/// Long operations check it between steps and while sleeping, so "Cancel"
/// takes effect within a fraction of a second rather than at the end of a
/// 30-second HTTP timeout. Cloning shares the same flag.
#[derive(Debug, Clone, Default)]
pub struct Cancel(Arc<AtomicBool>);

impl Cancel {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    fn check(&self) -> Result<(), ServiceError> {
        if self.is_cancelled() {
            Err(ServiceError::Cancelled)
        } else {
            Ok(())
        }
    }

    /// Sleep, returning `false` if cancellation interrupted the wait.
    ///
    /// Public because retry backoff in the downloader needs the same
    /// stay-responsive behaviour.
    pub fn sleep_interruptibly(&self, total: Duration) -> bool {
        self.sleep(total);
        !self.is_cancelled()
    }

    /// Sleep, but wake early if cancelled. Polls in short slices rather than
    /// blocking for the whole interval.
    fn sleep(&self, total: Duration) {
        const SLICE: Duration = Duration::from_millis(100);
        let deadline = Instant::now() + total;
        while Instant::now() < deadline {
            if self.is_cancelled() {
                return;
            }
            std::thread::sleep(SLICE.min(deadline.saturating_duration_since(Instant::now())));
        }
    }
}

/// What a completed sign-in yields: a verified session, and the one secret we
/// persist.
///
/// `Clone` so that a UI toolkit which requires cloneable messages can carry it;
/// cloning duplicates the refresh token, which is why it stays a [`Secret`].
#[derive(Debug, Clone)]
pub struct SignedIn {
    pub session: Session,
    pub refresh_token: Secret,
    /// The player's head, rendered from their skin, when it was (re)fetched
    /// on this sign-in. `None` means "keep whatever is cached": the skin had
    /// not changed, the account wears a default skin, or the download failed.
    pub head: Option<Vec<u8>>,
}

/// Cheap, shareable handle to everything the blocking operations need.
#[derive(Clone)]
pub struct Tasks {
    paths: DataPaths,
    auth: Arc<AuthService>,
    credentials: Arc<dyn CredentialStore>,
    install: InstallService,
}

impl std::fmt::Debug for Tasks {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Tasks")
            .field("root", &self.paths.root())
            .finish_non_exhaustive()
    }
}

impl Tasks {
    pub(crate) fn new(
        http: HttpClient,
        paths: DataPaths,
        auth: Arc<AuthService>,
        credentials: Arc<dyn CredentialStore>,
    ) -> Self {
        Self {
            install: InstallService::new(http, paths.clone()),
            paths,
            auth,
            credentials,
        }
    }

    /// The installation engine these tasks drive.
    pub fn install_service(&self) -> &InstallService {
        &self.install
    }

    // -- sign in ----------------------------------------------------------

    /// Run a whole device-code sign-in to completion.
    ///
    /// `on_code` is called once, as soon as Microsoft returns the code the user
    /// has to type. After that this blocks, polling at the interval Microsoft
    /// asked for, until the user finishes, declines, the code expires, or
    /// `cancel` fires.
    ///
    /// Polling on a timer rather than on a button press is the whole point of
    /// running this off the interface thread.
    pub fn sign_in(
        &self,
        cancel: &Cancel,
        mut on_code: impl FnMut(&DeviceCodePrompt),
    ) -> Result<SignedIn, ServiceError> {
        cancel.check()?;
        let prompt = self.auth.begin_device_code(Feature::AccountSignIn)?;
        on_code(&prompt);

        // Clamp both values: they come from a remote response, and a hostile or
        // broken one should not be able to pin a thread for a day or spin it at
        // full speed.
        let window = Duration::from_secs(prompt.expires_in_secs.clamp(30, 1800));
        let deadline = Instant::now() + window;
        let mut interval = Duration::from_secs(prompt.poll_interval_secs.clamp(1, 60));

        loop {
            cancel.sleep(interval);
            cancel.check()?;

            if Instant::now() >= deadline {
                return Err(ServiceError::Auth(
                    crate::auth::AuthError::DeviceCodeExpired,
                ));
            }

            match self
                .auth
                .poll_device_code(&prompt, Feature::AccountSignIn)?
            {
                PollOutcome::Pending => {}
                PollOutcome::SlowDown => {
                    // Microsoft asked us to back off. Honour it.
                    interval = (interval + Duration::from_secs(5)).min(Duration::from_secs(60));
                }
                PollOutcome::Complete {
                    access_token,
                    refresh_token,
                } => {
                    cancel.check()?;
                    let session = self
                        .auth
                        .establish_session(&access_token, Feature::AccountSignIn)?;
                    let head = self.fetch_head(&session.identity.profile, Feature::AccountSignIn);
                    return Ok(SignedIn {
                        session,
                        refresh_token,
                        head,
                    });
                }
            }
        }
    }

    /// Download the account's skin and cut out its head, if the cached one is
    /// missing or out of date.
    ///
    /// Never fails the sign-in: a face is a nicety, and an account that could
    /// not have its skin fetched is still signed in. The interface keeps the
    /// initial it shows today.
    fn fetch_head(
        &self,
        profile: &crate::auth::AccountProfile,
        feature: Feature,
    ) -> Option<Vec<u8>> {
        let cache = crate::auth::account::AccountCache::new(&self.paths);
        if !cache.needs_skin(profile, std::time::SystemTime::now()) {
            return None;
        }
        let url = profile.active_skin()?.url.clone();
        let skin = match self.auth.fetch_skin(&url, feature) {
            Ok(skin) => skin,
            Err(error) => {
                tracing::info!(%error, "could not download the account's skin");
                return None;
            }
        };
        match crate::auth::account::head_from_skin(&skin) {
            Ok(head) => Some(head),
            Err(error) => {
                tracing::info!(%error, "could not read the account's skin");
                None
            }
        }
    }

    /// Exchange a stored refresh token for a session.
    ///
    /// `Ok(None)` means there was nothing stored, which is not an error.
    pub fn restore_session(&self, account_id: &str) -> Result<Option<SignedIn>, ServiceError> {
        let Ok(stored) = self.credentials.retrieve(account_id) else {
            return Ok(None);
        };
        match self.auth.refresh(&stored)? {
            PollOutcome::Complete {
                access_token,
                refresh_token,
            } => {
                let session = self
                    .auth
                    .establish_session(&access_token, Feature::SessionRefresh)?;
                let head = self.fetch_head(&session.identity.profile, Feature::SessionRefresh);
                Ok(Some(SignedIn {
                    session,
                    refresh_token,
                    head,
                }))
            }
            _ => Ok(None),
        }
    }

    // -- game metadata and files ------------------------------------------
    //
    // Every method here is a thin wrapper over `install::InstallService`. The
    // wrapping exists so the worker deals in one error type and one handle,
    // not so this module gets a say in how installation works.

    /// Fetch, verify and cache a version document.
    pub fn fetch_version(&self, version_id: &str) -> Result<VersionDetail, ServiceError> {
        Ok(self
            .install
            .metadata()
            .fetch(version_id, Feature::ProfileInstall)?)
    }

    /// Resolve everything a version needs, fetching metadata and the asset
    /// index if they are not cached.
    ///
    /// This is what turns "1.8.9" into 771 named artifacts with expected
    /// hashes. It makes network requests, so it belongs on a worker thread.
    pub fn prepare(
        &self,
        version_id: &str,
        cancel: &Cancel,
    ) -> Result<ResolvedInstall, ServiceError> {
        cancel.check()?;
        Ok(self
            .install
            .prepare(version_id, Feature::ProfileInstall, cancel)?)
    }

    /// Resolve from an already-cached version document. No network, no hashing.
    pub fn resolve_cached(&self, version: &VersionDetail) -> Result<ResolvedInstall, ServiceError> {
        Ok(self
            .install
            .resolve_cached(version, Feature::ProfileInstall)?)
    }

    /// Download whatever is outstanding, then extract natives.
    pub fn install(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
        cancel: &Cancel,
        on_stage: &mut dyn FnMut(&InstallStage),
    ) -> Result<u64, ServiceError> {
        cancel.check()?;
        Ok(self
            .install
            .install(resolved, natives_dir, cancel, on_stage)?)
    }

    /// Hash every artifact and report what is really on disk.
    ///
    /// Slow by design — it reads 139 MB for 1.8.9 — which is exactly why it is
    /// here and not somewhere the interface can call it while drawing.
    pub fn verify(&self, resolved: &ResolvedInstall, natives_dir: &Path) -> VerificationReport {
        self.install.verify(resolved, natives_dir)
    }

    /// Existence-only pass, cheap enough to run after a state change.
    pub fn quick_check(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
    ) -> VerificationReport {
        self.install.quick_check(resolved, natives_dir)
    }

    /// Verify, discard corrupt bytes, then fetch only what is broken.
    pub fn repair(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
        cancel: &Cancel,
        on_stage: &mut dyn FnMut(&InstallStage),
    ) -> Result<RepairOutcome, ServiceError> {
        cancel.check()?;
        Ok(self
            .install
            .repair(resolved, natives_dir, cancel, on_stage)?)
    }

    /// Re-extract natives from archives that are already present.
    pub fn extract_natives(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
    ) -> Result<usize, ServiceError> {
        Ok(self
            .install
            .extract_natives(resolved, natives_dir)?
            .files
            .len())
    }

    /// A verification reduced to what `readiness` needs.
    pub fn snapshot(&self, resolved: &ResolvedInstall, natives_dir: &Path) -> InstallSnapshot {
        InstallSnapshot::from_report(&self.verify(resolved, natives_dir), resolved)
    }

    // -- java --------------------------------------------------------------

    /// Probe for Java runtimes. Spawns `java -version` for each candidate, so
    /// this is genuinely slow and belongs off the interface thread.
    pub fn discover_java(&self) -> Vec<JavaInstallation> {
        java_runtime::discover()
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
    fn cancel_is_shared_between_clones() {
        let a = Cancel::new();
        let b = a.clone();
        assert!(!a.is_cancelled());
        b.cancel();
        assert!(
            a.is_cancelled(),
            "cancelling a clone must cancel the original"
        );
    }

    #[test]
    fn cancel_check_reports_a_distinct_error() {
        let c = Cancel::new();
        assert!(c.check().is_ok());
        c.cancel();
        assert!(matches!(c.check(), Err(ServiceError::Cancelled)));
    }

    #[test]
    fn sleeping_wakes_early_when_cancelled() {
        let c = Cancel::new();
        let flag = c.clone();
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(50));
            flag.cancel();
        });

        let started = Instant::now();
        c.sleep(Duration::from_secs(30));
        let elapsed = started.elapsed();

        assert!(
            elapsed < Duration::from_secs(2),
            "cancel should interrupt the sleep, took {elapsed:?}"
        );
    }

    #[test]
    fn sleeping_without_cancellation_waits_the_full_time() {
        let c = Cancel::new();
        let started = Instant::now();
        c.sleep(Duration::from_millis(250));
        assert!(started.elapsed() >= Duration::from_millis(200));
    }

    /// The whole reason `Tasks` exists: it must be movable to another thread.
    #[test]
    fn tasks_is_send_and_sync() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<Tasks>();
        assert_send_sync::<Cancel>();
    }

    fn test_tasks(root: &std::path::Path) -> Tasks {
        use crate::auth::AzureApp;
        use crate::credentials::EphemeralCredentialStore;
        use crate::network::{NetworkJournal, NetworkPolicy};

        let paths = DataPaths::rooted_at(root);
        paths.ensure().expect("data dirs");
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0.0");
        let auth = Arc::new(crate::auth::AuthService::new(
            http.clone(),
            AzureApp::default(),
        ));
        Tasks::new(
            http,
            paths,
            auth,
            Arc::new(EphemeralCredentialStore::default()),
        )
    }

    /// A resolved install whose artifacts are already on disk and correct, so
    /// the run needs no network at all.
    fn present_install(root: &std::path::Path, count: usize) -> ResolvedInstall {
        use crate::install::download::{sha1_hex, Expectation};
        use crate::install::DownloadRequest;
        use crate::network::{Destination, Purpose, RequestDescriptor};

        let body = b"abc";
        let make = |name: String, target: std::path::PathBuf| {
            std::fs::create_dir_all(target.parent().expect("parent")).expect("mkdir");
            std::fs::write(&target, body).expect("write fixture");
            DownloadRequest {
                name,
                request: RequestDescriptor::new(
                    Destination::LibrariesMinecraft,
                    Purpose::DownloadGameArtifact,
                    Feature::ProfileInstall,
                    "/present.jar",
                ),
                expect: Expectation::new(sha1_hex(body), Some(body.len() as u64)),
                target,
            }
        };

        ResolvedInstall {
            version_id: "1.8.9".into(),
            main_class: "net.minecraft.client.main.Main".into(),
            argument_template: None,
            required_java_major: 8,
            client: make("client".into(), root.join("present-client.jar")),
            libraries: (0..count)
                .map(|i| {
                    make(
                        format!("present-{i}"),
                        root.join(format!("present-{i}.jar")),
                    )
                })
                .collect(),
            natives: Vec::new(),
            asset_index: None,
            asset_index_id: None,
            assets: Vec::new(),
            logging: None,
        }
    }

    /// The property the whole worker design rests on: installing something
    /// already present transfers nothing and contacts nobody. It is what makes
    /// install and repair the same call.
    #[test]
    fn installing_what_is_already_present_transfers_nothing() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let tasks = test_tasks(tmp.path());
        let resolved = present_install(tmp.path(), 3);
        let natives = tmp.path().join("natives");

        let mut stages = Vec::new();
        let bytes = tasks
            .install(&resolved, &natives, &Cancel::new(), &mut |stage| {
                stages.push(stage.clone());
            })
            .expect("install should succeed");

        assert_eq!(bytes, 0);
        assert!(
            tasks.install_service().cache().root().exists(),
            "the cache directory should have been created"
        );
        assert!(
            stages.contains(&InstallStage::Done),
            "the caller must be told it finished, got {stages:?}"
        );
    }

    #[test]
    fn a_verified_install_reports_a_playable_snapshot() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let tasks = test_tasks(tmp.path());
        let resolved = present_install(tmp.path(), 2);
        let natives = tmp.path().join("natives");

        // No natives to extract, so the directory counts as populated.
        let snapshot = tasks.snapshot(&resolved, &natives);
        assert!(
            snapshot.is_playable(),
            "everything is present and correct: {snapshot:?}"
        );
        assert_eq!(snapshot.outstanding, 0);
        assert_eq!(snapshot.bytes, 0);
    }

    #[test]
    fn a_corrupt_artifact_shows_up_in_the_snapshot() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let tasks = test_tasks(tmp.path());
        let resolved = present_install(tmp.path(), 2);
        let natives = tmp.path().join("natives");

        std::fs::write(&resolved.libraries[0].target, b"WRONG").expect("corrupt it");

        let snapshot = tasks.snapshot(&resolved, &natives);
        assert_eq!(snapshot.state, crate::install::InstallationState::Corrupted);
        assert_eq!(snapshot.outstanding, 1);
        assert_eq!(
            snapshot.bytes, 3,
            "the snapshot should account for re-fetching the bad file"
        );
    }

    #[test]
    fn install_refuses_to_start_when_already_cancelled() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let tasks = test_tasks(tmp.path());
        let resolved = present_install(tmp.path(), 3);

        let cancel = Cancel::new();
        cancel.cancel();

        let mut stages = 0;
        let natives = tmp.path().join("natives");
        let result = tasks.install(&resolved, &natives, &cancel, &mut |_| stages += 1);

        assert!(matches!(result, Err(ServiceError::Cancelled)));
        assert_eq!(stages, 0, "nothing should have been attempted");
    }

    /// Cancellation must survive the trip through `InstallError` rather than
    /// arriving as a generic failure, because the interface stays quiet about
    /// one and shows a notice for the other.
    #[test]
    fn cancellation_is_not_reported_as_an_install_failure() {
        let converted: ServiceError = crate::install::InstallError::Cancelled.into();
        assert!(matches!(converted, ServiceError::Cancelled));
    }

    /// The property the worker relies on: a `Tasks` clone really can do work on
    /// another thread while the original stays usable.
    #[test]
    fn a_clone_can_install_on_another_thread() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let tasks = test_tasks(tmp.path());
        let resolved = present_install(tmp.path(), 2);
        let natives = tmp.path().join("natives");

        let worker = tasks.clone();
        let handle = std::thread::spawn(move || {
            worker.install(&resolved, &natives, &Cancel::new(), &mut |_| {})
        });

        // The original is still usable while that runs.
        assert!(tasks.discover_java().len() < 100);

        let bytes = handle
            .join()
            .expect("thread should not panic")
            .expect("install");
        assert_eq!(bytes, 0);
    }
}
