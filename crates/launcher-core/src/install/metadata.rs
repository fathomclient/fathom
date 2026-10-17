//! Fetching and verifying Mojang's version metadata.
//!
//! Two documents, both untrusted until checked:
//!
//! 1. `version_manifest_v2.json` — the list of versions, each with a URL **and a
//!    SHA-1**.
//! 2. the per-version document, verified against that SHA-1 before it is parsed
//!    as anything meaningful.
//!
//! The manifest is the trust anchor for the version document, which is in turn
//! the trust anchor for every artifact. Skipping the manifest's hash would mean
//! trusting whatever the version URL returned, which is the whole chain's weakest
//! link — so it is checked first, every time, before `serde` sees the bytes.
//!
//! The version URL itself comes out of a downloaded document, so it goes through
//! the network allowlist rather than being fetched directly.

use crate::minecraft_runtime::version::{VersionDetail, VersionManifest};
use crate::network::policy::descriptor_from_url;
use crate::network::{Destination, Feature, HttpClient, NetworkError, Purpose, RequestDescriptor};

use super::cache::{ArtifactCache, CacheError};
use super::download::sha1_hex;

#[derive(Debug, thiserror::Error)]
pub enum MetadataError {
    #[error(transparent)]
    Network(#[from] NetworkError),
    #[error(transparent)]
    Cache(#[from] CacheError),
    #[error("Mojang's manifest does not list version {0}")]
    UnknownVersion(String),
    #[error(
        "the version document for {version} did not match the hash in Mojang's manifest. \
         Expected {expected}, got {actual}. Refusing to use it."
    )]
    HashMismatch {
        version: String,
        expected: String,
        actual: String,
    },
    #[error("could not parse the version document for {version}: {source}")]
    Malformed {
        version: String,
        #[source]
        source: serde_json::Error,
    },
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error(
        "Mojang's manifest points version {version} at {url}, which is not on the allowlist.          Refusing to fetch it."
    )]
    UrlOffAllowlist { version: String, url: String },
}

/// Supplies version documents.
#[derive(Debug, Clone)]
pub struct VersionMetadataProvider {
    http: HttpClient,
    cache: ArtifactCache,
}

impl VersionMetadataProvider {
    pub fn new(http: HttpClient, cache: ArtifactCache) -> Self {
        Self { http, cache }
    }

    /// The version list.
    pub fn manifest(&self, feature: Feature) -> Result<VersionManifest, MetadataError> {
        let request = RequestDescriptor::new(
            Destination::PistonMeta,
            Purpose::ResolveGameMetadata,
            feature,
            "/mc/game/version_manifest_v2.json",
        );
        let response = self.http.get(&request)?;
        Ok(response.json(Destination::PistonMeta.host())?)
    }

    /// A previously fetched and verified document, if we have one.
    ///
    /// The cached copy was hash-checked before being written, so re-reading it
    /// does not re-open the trust question. It is still parsed defensively.
    pub fn cached(&self, version_id: &str) -> Option<VersionDetail> {
        let path = self.cache.version_json(version_id).ok()?;
        let bytes = std::fs::read(path).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    /// Fetch, verify against the manifest hash, cache, and parse.
    pub fn fetch(
        &self,
        version_id: &str,
        feature: Feature,
    ) -> Result<VersionDetail, MetadataError> {
        // Reject a hostile version id before it becomes a path.
        let target = self.cache.version_json(version_id)?;

        let manifest = self.manifest(feature)?;
        let entry = manifest
            .find(version_id)
            .ok_or_else(|| MetadataError::UnknownVersion(version_id.to_owned()))?;

        // The URL came from a document we just downloaded: untrusted.
        let request = descriptor_from_url(&entry.url, Purpose::ResolveGameMetadata, feature)
            .map_err(|source| {
                tracing::warn!(url = %entry.url, error = %source, "version URL refused");
                MetadataError::UrlOffAllowlist {
                    version: version_id.to_owned(),
                    url: entry.url.clone(),
                }
            })?;

        let response = self.http.get(&request)?;
        let actual = sha1_hex(&response.body);
        if !actual.eq_ignore_ascii_case(&entry.sha1) {
            return Err(MetadataError::HashMismatch {
                version: version_id.to_owned(),
                expected: entry.sha1.clone(),
                actual,
            });
        }

        let detail: VersionDetail =
            serde_json::from_slice(&response.body).map_err(|source| MetadataError::Malformed {
                version: version_id.to_owned(),
                source,
            })?;

        // Cache only after the hash matched.
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|source| MetadataError::Io {
                path: parent.display().to_string(),
                source,
            })?;
        }
        crate::fs_safety::atomic_write(&target, &response.body).map_err(CacheError::Fs)?;

        Ok(detail)
    }

    pub fn cache(&self) -> &ArtifactCache {
        &self.cache
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
    use crate::paths::DataPaths;

    fn provider() -> (tempfile::TempDir, VersionMetadataProvider) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let cache = ArtifactCache::new(paths);
        cache.ensure().unwrap();
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0.0");
        (tmp, VersionMetadataProvider::new(http, cache))
    }

    #[test]
    fn the_official_manifest_shape_parses() {
        let json = r#"{
            "latest": { "release": "1.21.4", "snapshot": "25w02a" },
            "versions": [
                {
                    "id": "1.8.9",
                    "type": "release",
                    "url": "https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json",
                    "time": "2021-12-15T15:58:22+00:00",
                    "releaseTime": "2015-12-09T09:41:14+00:00",
                    "sha1": "d546f1707a3f2b7d034eece5ea2e311eda875787",
                    "complianceLevel": 0
                }
            ]
        }"#;
        let manifest: VersionManifest = serde_json::from_str(json).unwrap();
        let entry = manifest.find("1.8.9").expect("1.8.9 should be listed");
        assert_eq!(entry.sha1, "d546f1707a3f2b7d034eece5ea2e311eda875787");
        assert_eq!(entry.release_type, "release");
        assert!(manifest.find("1.20.4").is_none());
    }

    #[test]
    fn a_version_id_that_would_escape_the_cache_is_refused_before_any_request() {
        let (_tmp, p) = provider();
        // Fails on the path check, so no network request is attempted.
        assert!(p.fetch("../../evil", Feature::ProfileInstall).is_err());
        assert_eq!(
            p.http.journal().summary().total,
            0,
            "a hostile version id must be rejected before we open a socket"
        );
    }

    #[test]
    fn nothing_is_cached_when_there_is_nothing_to_read() {
        let (_tmp, p) = provider();
        assert!(p.cached("1.8.9").is_none());
    }

    #[test]
    fn a_cached_document_round_trips() {
        let (_tmp, p) = provider();
        let target = p.cache().version_json("1.8.9").unwrap();
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        std::fs::write(
            &target,
            br#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main"}"#,
        )
        .unwrap();

        let detail = p.cached("1.8.9").expect("should read the cached document");
        assert_eq!(detail.id, "1.8.9");
    }

    #[test]
    fn a_corrupt_cached_document_reads_as_absent_rather_than_panicking() {
        let (_tmp, p) = provider();
        let target = p.cache().version_json("1.8.9").unwrap();
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        std::fs::write(&target, b"{ not json").unwrap();
        assert!(p.cached("1.8.9").is_none());
    }
}
