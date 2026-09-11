//! Signed updates for the launcher and its first-party components.
//!
//! # The trust chain, in one place
//!
//! ```text
//!   ROOT_KEYS            compiled into the binary. Cannot be changed remotely.
//!      | verify (threshold, Ed25519, domain-separated)
//!      v
//!   root.json            names the release keys and their threshold
//!      | authorises
//!      v
//!   release.json         names each component: version, os, arch, sha256, size
//!      | binds by SHA-256
//!      v
//!   artifact bytes       trusted only because that hash matches
//!      | staged, re-verified from disk, swapped, validated
//!      v
//!   installed            with the previous copy still on disk
//! ```
//!
//! **HTTPS is transport, not trust.** The allowlist and TLS stop a passive
//! observer and a random man-in-the-middle; they say nothing about whether the
//! host at the other end is honest. A compromised CDN, a hijacked DNS record
//! and a compromised release host all produce a perfectly valid TLS session
//! serving whatever the attacker likes. Every gate that decides whether bytes
//! get executed is therefore a signature check or a hash check, and none of
//! them depends on the connection.
//!
//! # What each attack in the compromise model runs into
//!
//! | Attack | What stops it |
//! |---|---|
//! | Compromised CDN | Artifacts are bound by SHA-256 in signed metadata. Serving different bytes fails the hash. |
//! | Compromised DNS | The imposter cannot produce a valid signature. The allowlist means it must at least be a name we know. |
//! | Compromised release host | Same: the host never holds a signing key. It serves files it cannot forge. |
//! | Replay of an old, correctly-signed release | Metadata versions are monotonic and expire; component versions must be strictly newer than what is installed. |
//! | Partial download | Declared size checked before the hash, so truncation is caught and named. |
//! | Corrupted artifact | SHA-256 over the received bytes, and again from disk before install. |
//! | Interrupted replacement | A journal, a retained backup, and a recovery pass that decides from what is on disk. |
//!
//! # Privacy
//!
//! An update check is a `GET` of a fixed path. No query string, no identifier,
//! no account, no hardware fingerprint, no install id, no OS build. The
//! `User-Agent` is `Fathom/<version>` and nothing else, which is a fact about
//! the software rather than about the machine. The request a client makes is
//! identical to the request every other client makes.
//!
//! # No background service
//!
//! There is no daemon, no scheduled task, and no separate updater executable.
//! A check happens when the launcher process decides to make one, and
//! installing is a call the user's action leads to. The supervision that a
//! self-replacing binary needs is done by the launcher process that is already
//! running; see `install`.

pub mod install;
pub mod keys;
pub mod manifest;
pub mod signing;
pub mod trust;
pub mod verify;

use std::path::{Path, PathBuf};

use manifest::{ComponentId, Platform, ReleaseMetadata, RootMetadata};
use signing::PublicKey;
use trust::{TrustState, UpdatePaths};
use verify::{ApprovedUpdate, VerificationContext};

use crate::network::http::HttpClient;
use crate::network::policy::{Destination, Feature, Purpose, RequestDescriptor};

/// Where the two metadata documents live on the release host.
///
/// Fixed paths, not templates. A path built from anything the client knows
/// would be a channel for the client to describe itself.
pub const ROOT_PATH: &str = "/v1/root.json";
pub const RELEASE_PATH: &str = "/v1/release.json";

#[derive(Debug, thiserror::Error)]
pub enum UpdateError {
    #[error(
        "this build has no update signing key compiled in, so an update could not be \
         verified even if one were offered. Updates are disabled. See \
         crates/launcher-core/src/update/keys.rs."
    )]
    NoTrustAnchor,
    #[error("the compiled-in root key list is unusable: {0}")]
    BadAnchor(#[from] signing::SignatureError),
    #[error(transparent)]
    Network(#[from] crate::network::http::NetworkError),
    #[error("the release host returned {status} for {path}")]
    BadStatus { status: u16, path: String },
    #[error(transparent)]
    Verify(#[from] verify::VerifyError),
    #[error(transparent)]
    Artifact(#[from] verify::ArtifactError),
    #[error(transparent)]
    Install(#[from] install::InstallError),
    #[error(transparent)]
    Trust(#[from] trust::TrustError),
    #[error("could not write the download to {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
}

/// The result of one check. Deliberately data, not an action.
#[derive(Debug, Clone)]
pub struct CheckOutcome {
    /// Updates that passed every check and could be installed.
    pub available: Vec<ApprovedUpdate>,
    /// Components that were looked at and are current.
    pub up_to_date: Vec<ComponentId>,
    /// Components whose entry was rejected, with why. Reported rather than
    /// hidden: "we refused what was offered" is not the same as "nothing was
    /// offered", and a user chasing a missing update deserves the difference.
    pub refused: Vec<(ComponentId, String)>,
    /// The release metadata version accepted, for display.
    pub metadata_version: u64,
}

impl CheckOutcome {
    pub fn has_updates(&self) -> bool {
        !self.available.is_empty()
    }
}

/// The update subsystem.
///
/// Holds no state of its own beyond paths: the trust state is read and written
/// per operation, so two callers cannot disagree about what has been accepted.
#[derive(Debug)]
pub struct UpdateService {
    paths: UpdatePaths,
    storage_root: PathBuf,
    platform: Platform,
}

impl UpdateService {
    pub fn new(data_root: &Path) -> Self {
        Self {
            paths: UpdatePaths::new(data_root),
            storage_root: data_root.to_path_buf(),
            platform: Platform::current(),
        }
    }

    /// For tests that need to pretend to be another machine.
    #[must_use]
    pub fn with_platform(mut self, platform: Platform) -> Self {
        self.platform = platform;
        self
    }

    pub fn paths(&self) -> &UpdatePaths {
        &self.paths
    }

    pub fn installer(&self) -> install::Installer {
        install::Installer::new(self.paths.clone(), &self.storage_root)
    }

    /// Whether this build could verify an update if one were offered.
    pub fn is_configured(&self) -> bool {
        keys::update_channel_is_configured()
    }

    /// Finish or undo anything an earlier run left half-done.
    ///
    /// Call this on start, before reading any component an update might have
    /// been replacing.
    pub fn recover(&self, running_version: &str) -> install::Recovery {
        self.installer().recover(running_version)
    }

    /// Check for updates.
    ///
    /// `now` is passed in so expiry is a decision the caller can see and tests
    /// can control, and so one clock reading covers the whole check.
    pub fn check(
        &self,
        http: &HttpClient,
        now: i64,
        installed: &dyn Fn(ComponentId) -> String,
    ) -> Result<CheckOutcome, UpdateError> {
        // Before the network. A launcher that cannot verify must not fetch: a
        // manifest it cannot judge is not information, and displaying one would
        // be presenting an attacker's text as ours.
        if !self.is_configured() {
            return Err(UpdateError::NoTrustAnchor);
        }

        let mut state = TrustState::load(&self.paths.trust_state());

        let root_bytes = self.fetch(http, ROOT_PATH)?;
        let root = self.accept_root(&root_bytes, &state, now)?;

        // The keys the root just authorised. From this document, never from the
        // one we had cached: a newer root's key set replaces the old one, which
        // is how revocation reaches a client.
        let release_keys = parse_keys(&root.release_keys)?;

        let release_bytes = self.fetch(http, RELEASE_PATH)?;
        let release = verify::verify_release(
            &release_bytes,
            &release_keys,
            root.release_threshold,
            VerificationContext {
                now,
                trusted_version: state.release_version,
                platform: &self.platform,
            },
        )?;

        // Only now is anything remembered. Persisting before verification would
        // let a rejected document raise the version floor and lock the client
        // out of the real one.
        state.root_version = root.version;
        state.release_keys.clone_from(&root.release_keys);
        state.release_threshold = root.release_threshold;
        state.release_version = release.version;
        state.save(&self.paths.trust_state())?;
        let _ = crate::fs_safety::atomic_write(&self.paths.cached_root(), &root_bytes);

        Ok(self.select_all(&release, installed))
    }

    /// Verify a root document against the anchor we currently hold.
    ///
    /// The anchor is the compiled-in list on a fresh install, and afterwards
    /// still the compiled-in list — rotation is accepted by *also* satisfying
    /// the previously trusted root, which is checked by the version floor. The
    /// compiled-in keys never stop being authoritative, so a client can always
    /// be brought back to a known state by reinstalling.
    fn accept_root(
        &self,
        bytes: &[u8],
        state: &TrustState,
        now: i64,
    ) -> Result<RootMetadata, UpdateError> {
        let anchor = keys::root_keys()?;
        Ok(verify::verify_root(
            bytes,
            &anchor,
            keys::ROOT_THRESHOLD,
            VerificationContext {
                now,
                trusted_version: state.root_version,
                platform: &self.platform,
            },
        )?)
    }

    fn select_all(
        &self,
        release: &ReleaseMetadata,
        installed: &dyn Fn(ComponentId) -> String,
    ) -> CheckOutcome {
        let mut outcome = CheckOutcome {
            available: Vec::new(),
            up_to_date: Vec::new(),
            refused: Vec::new(),
            metadata_version: release.version,
        };

        for component in ComponentId::ALL {
            let current = installed(component);
            match verify::select_update(component, release, &current, &self.platform) {
                Ok(Some(update)) => outcome.available.push(update),
                Ok(None) => outcome.up_to_date.push(component),
                Err(e) => outcome.refused.push((component, e.to_string())),
            }
        }
        outcome
    }

    /// Download one approved update into staging and verify it.
    ///
    /// Returns the staged path. Nothing is installed by this call: staging is
    /// deliberately a separate step so that downloading and replacing are two
    /// decisions rather than one.
    pub fn stage(
        &self,
        http: &HttpClient,
        update: &ApprovedUpdate,
    ) -> Result<PathBuf, UpdateError> {
        self.paths.ensure().map_err(|source| UpdateError::Io {
            path: self.paths.root().display().to_string(),
            source,
        })?;

        let installer = self.installer();
        let staged = installer.staged_path(update.component, &update.release.version);
        let _ = std::fs::remove_file(&staged);

        let request = RequestDescriptor::new(
            Destination::LauncherUpdates,
            Purpose::LauncherUpdate,
            Feature::SelfUpdate,
            update.release.path.clone(),
        );

        let mut buffer = Vec::with_capacity(
            usize::try_from(update.release.size)
                .unwrap_or(0)
                .min(64 * 1024 * 1024),
        );
        // The declared size, plus nothing. A server that sends more than the
        // signed metadata promised is refused mid-stream rather than after.
        http.stream(&request, None, update.release.size, &mut buffer)?;

        verify::verify_artifact(update.component, &update.release, &buffer)?;

        crate::fs_safety::atomic_write(&staged, &buffer).map_err(|e| UpdateError::Io {
            path: staged.display().to_string(),
            source: std::io::Error::other(e.to_string()),
        })?;
        Ok(staged)
    }

    /// Install a staged update over `target`.
    ///
    /// The hash is re-checked from disk inside `apply`; passing it here rather
    /// than trusting the staging step is the point.
    pub fn install(
        &self,
        update: &ApprovedUpdate,
        target: &Path,
        staged: &Path,
        validator: &dyn install::Validator,
    ) -> Result<(), UpdateError> {
        self.installer().apply(
            &install::Replacement {
                component: update.component,
                target,
                staged,
                expected_sha256: &update.release.sha256,
                new_version: &update.release.version,
                previous_version: &update.from_version,
            },
            validator,
        )?;

        let mut state = TrustState::load(&self.paths.trust_state());
        state.record_installed(update.component, &update.release.version);
        state.save(&self.paths.trust_state())?;
        Ok(())
    }

    #[allow(clippy::unused_self)]
    fn fetch(&self, http: &HttpClient, path: &str) -> Result<Vec<u8>, UpdateError> {
        let request = RequestDescriptor::new(
            Destination::LauncherUpdates,
            Purpose::LauncherUpdate,
            Feature::SelfUpdate,
            path.to_owned(),
        );
        let response = http.get(&request)?;
        if !response.is_success() {
            return Err(UpdateError::BadStatus {
                status: response.status,
                path: path.to_owned(),
            });
        }
        Ok(response.body)
    }
}

fn parse_keys(encoded: &[String]) -> Result<Vec<PublicKey>, signing::SignatureError> {
    encoded.iter().map(|k| PublicKey::from_base64(k)).collect()
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
    fn a_build_with_no_anchor_refuses_to_check_before_touching_the_network() {
        let tmp = tempfile::tempdir().unwrap();
        let service = UpdateService::new(tmp.path());
        assert!(!service.is_configured());

        // The client is never used, because the refusal happens first. A
        // launcher that cannot verify a manifest must not fetch one: displaying
        // an unverified manifest would be presenting an attacker's text as ours.
        let http = HttpClient::new(
            crate::network::policy::NetworkPolicy::default(),
            crate::network::journal::NetworkJournal::default(),
            "test",
        );
        let err = service
            .check(&http, 0, &|_| "0.1.0".to_owned())
            .expect_err("no anchor means no check");
        assert!(matches!(err, UpdateError::NoTrustAnchor), "{err:?}");
    }

    #[test]
    fn metadata_paths_are_fixed_and_carry_no_identifier() {
        for path in [ROOT_PATH, RELEASE_PATH] {
            assert!(path.starts_with('/'));
            assert!(!path.contains('?'), "{path}: no query string");
            assert!(!path.contains('&'), "{path}: nothing to append to");
            assert!(!path.contains('{'), "{path}: not a template");
            assert!(
                crate::network::policy::path_is_acceptable(path),
                "{path} must satisfy the network policy"
            );
        }
    }
}
