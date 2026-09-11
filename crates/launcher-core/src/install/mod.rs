//! The Minecraft installation engine.
//!
//! Seven components, each with one job, none of which knows the interface
//! exists:
//!
//! | Component | Responsibility |
//! |---|---|
//! | [`VersionMetadataProvider`] | Fetch and hash-verify Mojang's version documents |
//! | [`ArtifactResolver`] | Turn a version document into the exact artifact set |
//! | [`DownloadManager`] | Stream, verify and atomically place one file |
//! | [`ArtifactCache`] | Where shared, immutable artifacts live |
//! | [`AssetManager`] | Asset index parsing and object addressing |
//! | [`NativeLibraryManager`] | Safe extraction of native archives |
//! | [`InstallationVerifier`] | Is it usable, and what needs repairing |
//!
//! [`InstallService`] wires them together; it is the only thing a caller
//! normally needs.
//!
//! # The two invariants
//!
//! **Nothing unverified is ever usable.** Every artifact is streamed to a
//! `.partial`, hashed during the write, checked against the publisher's SHA-1
//! and declared size, and only then renamed into place. A hash mismatch fails
//! the installation; it is never kept, never renamed, never retried into
//! acceptance.
//!
//! **Installation state is derived, never remembered.** A profile's state comes
//! from looking at the disk. There is no "installed" flag to disagree with
//! reality, so a crash mid-download leaves a profile that correctly reports
//! itself incomplete rather than one that claims to be ready.

pub mod assets;
pub mod cache;
pub mod download;
pub mod metadata;
pub mod natives;
pub mod resolver;
pub mod verify;

use std::path::Path;

pub use assets::{AssetIndex, AssetManager, AssetObject};
pub use cache::{ArtifactCache, CacheError};
pub use download::{
    DownloadError, DownloadManager, DownloadRequest, Expectation, Outcome, Progress,
};
pub use metadata::{MetadataError, VersionMetadataProvider};
pub use natives::{ExtractedNatives, NativeError, NativeLibraryManager};
pub use resolver::{
    ArtifactResolver, LoggingConfig, ResolveError, ResolvedInstall, SUPPORTED_VERSION,
};
pub use verify::{Defect, DefectKind, InstallationState, InstallationVerifier, VerificationReport};

use crate::network::{Feature, HttpClient};
use crate::paths::DataPaths;
use crate::tasks::Cancel;

#[derive(Debug, thiserror::Error)]
pub enum InstallError {
    #[error(transparent)]
    Metadata(#[from] MetadataError),
    #[error(transparent)]
    Resolve(#[from] ResolveError),
    #[error(transparent)]
    Download(#[from] DownloadError),
    #[error(transparent)]
    Native(#[from] NativeError),
    #[error(transparent)]
    Cache(#[from] CacheError),
    #[error("could not parse the asset index: {0}")]
    AssetIndex(#[from] serde_json::Error),
    #[error(
        "this asset index uses the legacy copy-into-game-directory layout, which is not \
         implemented. 1.8.9 does not use it, so this is unexpected."
    )]
    LegacyAssetLayout,
    #[error("cancelled")]
    Cancelled,
}

/// How far an install has got. Reported through the progress callback so a
/// caller can say something more useful than a percentage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallStage {
    ResolvingMetadata,
    FetchingAssetIndex,
    Downloading(Progress),
    ExtractingNatives,
    Verifying,
    Done,
}

/// The assembled engine.
#[derive(Debug, Clone)]
pub struct InstallService {
    metadata: VersionMetadataProvider,
    resolver: ArtifactResolver,
    assets: AssetManager,
    downloads: DownloadManager,
    natives: NativeLibraryManager,
    verifier: InstallationVerifier,
}

impl InstallService {
    pub fn new(http: HttpClient, paths: DataPaths) -> Self {
        let cache = ArtifactCache::new(paths);
        let resolver = ArtifactResolver::new(cache.clone());
        Self {
            metadata: VersionMetadataProvider::new(http.clone(), cache.clone()),
            assets: AssetManager::new(resolver.clone()),
            resolver,
            downloads: DownloadManager::new(http),
            natives: NativeLibraryManager::new(),
            verifier: InstallationVerifier::new(),
        }
    }

    pub fn cache(&self) -> &ArtifactCache {
        self.resolver.cache()
    }

    pub fn metadata(&self) -> &VersionMetadataProvider {
        &self.metadata
    }

    pub fn verifier(&self) -> &InstallationVerifier {
        &self.verifier
    }

    /// The download manager, for callers with artifacts of their own to fetch.
    ///
    /// The loader providers are the reason this is public: a loader's libraries
    /// are `DownloadRequest`s exactly like the game's, carry hashes exactly like
    /// the game's, and must go through the same verification. Giving the loader
    /// its own downloader would be a second path to the same guarantee, and the
    /// second one is always the one that gets it wrong.
    pub fn downloads(&self) -> &DownloadManager {
        &self.downloads
    }

    /// Work out everything a version needs, fetching metadata if necessary.
    ///
    /// The asset index is downloaded here rather than during the main transfer,
    /// because until it is parsed we cannot say how many files an install
    /// involves — and telling a user "4 files" when the answer is 738 is worse
    /// than making them wait for a 78 KB download.
    pub fn prepare(
        &self,
        version_id: &str,
        feature: Feature,
        cancel: &Cancel,
    ) -> Result<ResolvedInstall, InstallError> {
        self.cache().ensure()?;

        let version = match self.metadata.cached(version_id) {
            Some(cached) => cached,
            None => self.metadata.fetch(version_id, feature)?,
        };

        let os = crate::minecraft_runtime::version::Os::current();
        let mut resolved = self.resolver.resolve(&version, os, feature)?;

        if let Some(index_request) = resolved.asset_index.clone() {
            self.downloads.fetch(&index_request, None, cancel)?;

            let bytes = std::fs::read(&index_request.target).map_err(|source| {
                InstallError::Download(DownloadError::Io {
                    path: index_request.target.display().to_string(),
                    source,
                })
            })?;
            let index = AssetManager::parse(&bytes)?;

            if index.is_legacy_layout() {
                return Err(InstallError::LegacyAssetLayout);
            }
            resolved.assets = self.assets.resolve_objects(&index, feature)?;
        }

        Ok(resolved)
    }

    /// Resolve from a document already in the cache, plus the asset index if it
    /// is on disk too. Performs no network I/O, so `verify` works offline.
    pub fn resolve_cached(
        &self,
        version: &crate::minecraft_runtime::version::VersionDetail,
        feature: Feature,
    ) -> Result<ResolvedInstall, InstallError> {
        let os = crate::minecraft_runtime::version::Os::current();
        let mut resolved = self.resolver.resolve(version, os, feature)?;

        if let Some(index_request) = &resolved.asset_index {
            if let Ok(bytes) = std::fs::read(&index_request.target) {
                if let Ok(index) = AssetManager::parse(&bytes) {
                    resolved.assets = self.assets.resolve_objects(&index, feature)?;
                }
            }
        }
        Ok(resolved)
    }

    /// Inspect what is on disk, hashing everything.
    pub fn verify(&self, install: &ResolvedInstall, natives_dir: &Path) -> VerificationReport {
        self.verifier.verify(install, natives_dir)
    }

    /// Existence-only pass. Cheap enough to run whenever a screen is drawn.
    pub fn quick_check(&self, install: &ResolvedInstall, natives_dir: &Path) -> VerificationReport {
        self.verifier.quick_check(install, natives_dir)
    }

    /// Download whatever is outstanding, then extract natives.
    ///
    /// Idempotent: files that are already present and correct are skipped, so
    /// running this on a complete installation transfers nothing. That is what
    /// makes install and repair the same code path.
    pub fn install(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
        cancel: &Cancel,
        on_stage: &mut dyn FnMut(&InstallStage),
    ) -> Result<u64, InstallError> {
        let items: Vec<DownloadRequest> = resolved.all_downloads().into_iter().cloned().collect();

        let bytes = self
            .downloads
            .fetch_all(&items, cancel, &mut |p| {
                on_stage(&InstallStage::Downloading(p.clone()));
            })
            .map_err(|e| match e {
                DownloadError::Cancelled => InstallError::Cancelled,
                other => InstallError::Download(other),
            })?;

        if cancel.is_cancelled() {
            return Err(InstallError::Cancelled);
        }

        // Natives are unpacked only after every archive is on disk and verified.
        on_stage(&InstallStage::ExtractingNatives);
        self.natives.extract_all(&resolved.natives, natives_dir)?;

        on_stage(&InstallStage::Done);
        Ok(bytes)
    }

    /// Verify, then fetch only what is broken.
    ///
    /// Corrupt files are deleted first so the downloader replaces them rather
    /// than seeing them present and skipping. Nothing outside the shared cache
    /// and the profile's `natives/` directory is read or written, so worlds,
    /// screenshots, options and mods are untouched.
    pub fn repair(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
        cancel: &Cancel,
        on_stage: &mut dyn FnMut(&InstallStage),
    ) -> Result<RepairOutcome, InstallError> {
        on_stage(&InstallStage::Verifying);
        let report = self.verify(resolved, natives_dir);

        // Clear anything a previous crash left half-written.
        download::sweep_partials(&self.cache().root());

        let discarded = self.verifier.discard_corrupt(&report);
        let broken = self.verifier.repair_set(resolved, &report).len();

        let bytes = self.install(resolved, natives_dir, cancel, on_stage)?;

        let after = self.verify(resolved, natives_dir);
        Ok(RepairOutcome {
            before: report.state,
            after: after.state,
            repaired: broken,
            discarded,
            bytes,
        })
    }

    /// Extract natives without re-downloading. Used when the archives are
    /// present but the profile's directory was cleared.
    pub fn extract_natives(
        &self,
        resolved: &ResolvedInstall,
        natives_dir: &Path,
    ) -> Result<ExtractedNatives, InstallError> {
        Ok(self.natives.extract_all(&resolved.natives, natives_dir)?)
    }
}

/// What a repair did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepairOutcome {
    pub before: InstallationState,
    pub after: InstallationState,
    /// How many artifacts were missing or wrong.
    pub repaired: usize,
    /// How many corrupt files were deleted before re-fetching.
    pub discarded: usize,
    pub bytes: u64,
}

impl RepairOutcome {
    pub fn succeeded(&self) -> bool {
        self.after.is_playable()
    }

    pub fn summary(&self) -> String {
        if self.repaired == 0 {
            return "Nothing needed repairing.".to_owned();
        }
        format!(
            "Repaired {} file{} ({} re-downloaded after failing verification).",
            self.repaired,
            if self.repaired == 1 { "" } else { "s" },
            self.discarded
        )
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
    use crate::network::{NetworkJournal, NetworkPolicy};

    fn service() -> (tempfile::TempDir, InstallService) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0.0");
        (tmp, InstallService::new(http, paths))
    }

    #[test]
    fn constructing_the_service_touches_no_network() {
        let (_tmp, s) = service();
        assert_eq!(s.downloads.http().journal().summary().total, 0);
    }

    #[test]
    fn offline_mode_stops_preparation_before_any_socket_opens() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let http = HttpClient::new(
            NetworkPolicy {
                online: false,
                ..NetworkPolicy::default()
            },
            NetworkJournal::new(),
            "test/0.0",
        );
        let s = InstallService::new(http, paths);

        assert!(s
            .prepare("1.8.9", Feature::ProfileInstall, &Cancel::new())
            .is_err());
    }

    #[test]
    fn a_cancelled_install_reports_cancellation_rather_than_partial_success() {
        let (tmp, s) = service();
        let cancel = Cancel::new();
        cancel.cancel();

        let resolved = ResolvedInstall {
            version_id: "1.8.9".into(),
            main_class: "M".into(),
            argument_template: None,
            required_java_major: 8,
            client: DownloadRequest {
                name: "client".into(),
                request: crate::network::RequestDescriptor::new(
                    crate::network::Destination::PistonData,
                    crate::network::Purpose::DownloadGameArtifact,
                    Feature::ProfileInstall,
                    "/x.jar",
                ),
                expect: Expectation::new("a".repeat(40), None),
                target: tmp.path().join("client.jar"),
            },
            libraries: vec![],
            natives: vec![],
            asset_index: None,
            asset_index_id: None,
            assets: vec![],
            logging: None,
        };

        let err = s
            .install(&resolved, &tmp.path().join("natives"), &cancel, &mut |_| {})
            .expect_err("a cancelled install must not report success");
        assert!(matches!(err, InstallError::Cancelled));
    }

    #[test]
    fn the_repair_summary_reads_sensibly() {
        let nothing = RepairOutcome {
            before: InstallationState::Installed,
            after: InstallationState::Installed,
            repaired: 0,
            discarded: 0,
            bytes: 0,
        };
        assert!(nothing.succeeded());
        assert!(nothing.summary().contains("Nothing needed"));

        let one = RepairOutcome {
            before: InstallationState::Corrupted,
            after: InstallationState::Installed,
            repaired: 1,
            discarded: 1,
            bytes: 100,
        };
        assert!(one.succeeded());
        assert!(one.summary().contains("Repaired 1 file "));
    }

    #[test]
    fn a_repair_that_did_not_fix_things_says_so() {
        let failed = RepairOutcome {
            before: InstallationState::Corrupted,
            after: InstallationState::Corrupted,
            repaired: 3,
            discarded: 3,
            bytes: 0,
        };
        assert!(!failed.succeeded());
    }
}
