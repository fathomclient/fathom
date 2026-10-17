//! Asset objects.
//!
//! 1.8.9's index lists 734 objects totalling ~110 MB, addressed by SHA-1 in
//! Mojang's two-character shard layout. That is the bulk of an installation and
//! the main reason the cache is shared rather than copied per profile.
//!
//! Two things this module is careful about:
//!
//! **Hashes become paths.** Every object's filename *is* its hash, so a
//! malformed or hostile hash in the index would become a path. Each is
//! validated as 40 hex characters before it is used, and the result still goes
//! through the cache's containment check.
//!
//! **The index is untrusted.** It is a downloaded document. An entry we cannot
//! make sense of is skipped with a warning rather than guessed at.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::network::{Feature, Purpose};

use super::download::{DownloadRequest, Expectation};
use super::resolver::{ArtifactResolver, ResolveError};

/// The asset index document.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AssetIndex {
    pub objects: BTreeMap<String, AssetObject>,
    /// Pre-1.7 layouts copy assets into the game directory instead of using the
    /// object store. 1.8.9 does not, but carrying the flag means we notice
    /// rather than silently mislaying files if it ever appears.
    #[serde(default)]
    pub map_to_resources: Option<bool>,
    #[serde(default)]
    pub r#virtual: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

impl AssetIndex {
    /// Whether this index uses the legacy copy-into-game-directory layout.
    ///
    /// Reported rather than handled: 1.8.9 does not use it, and guessing at a
    /// layout we have never tested would be worse than saying so.
    pub fn is_legacy_layout(&self) -> bool {
        self.r#virtual.unwrap_or(false) || self.map_to_resources.unwrap_or(false)
    }

    pub fn total_bytes(&self) -> u64 {
        self.objects.values().map(|o| o.size).sum()
    }
}

/// Turns an index into download requests.
#[derive(Debug, Clone)]
pub struct AssetManager {
    resolver: ArtifactResolver,
}

impl AssetManager {
    pub fn new(resolver: ArtifactResolver) -> Self {
        Self { resolver }
    }

    /// Parse an index document that has already been downloaded and verified.
    pub fn parse(bytes: &[u8]) -> Result<AssetIndex, serde_json::Error> {
        serde_json::from_slice(bytes)
    }

    /// Build a download request per object.
    ///
    /// Objects whose hash is not a hash are skipped, loudly. Returning an error
    /// for the whole index would mean one bad entry blocks an otherwise valid
    /// installation; skipping is the proportionate response, and the verifier
    /// will notice anything actually missing later.
    pub fn resolve_objects(
        &self,
        index: &AssetIndex,
        feature: Feature,
    ) -> Result<Vec<DownloadRequest>, ResolveError> {
        let mut out = Vec::with_capacity(index.objects.len());

        for (name, object) in &index.objects {
            let Some(relative) = super::cache::asset_object_relative(&object.hash) else {
                tracing::warn!(
                    asset = %name,
                    "asset index entry has a malformed hash; skipping it"
                );
                continue;
            };

            let target = match self.resolver.cache().asset_object(&object.hash) {
                Ok(path) => path,
                Err(e) => {
                    tracing::warn!(asset = %name, error = %e, "asset path refused; skipping");
                    continue;
                }
            };

            // Objects are served from their shard path on the resources host.
            let url = format!("https://resources.download.minecraft.net/{relative}");

            out.push(self.resolver.request(
                name.clone(),
                &url,
                Expectation::new(&object.hash, Some(object.size)),
                target,
                Purpose::DownloadGameArtifact,
                feature,
            )?);
        }

        Ok(out)
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
    use crate::install::cache::ArtifactCache;
    use crate::network::Destination;
    use crate::paths::DataPaths;

    fn manager() -> (tempfile::TempDir, AssetManager) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let cache = ArtifactCache::new(paths);
        cache.ensure().unwrap();
        (tmp, AssetManager::new(ArtifactResolver::new(cache)))
    }

    const SAMPLE: &str = r#"{
        "objects": {
            "icons/icon_16x16.png": {
                "hash": "bdf48ef6b5d0d23bbb02e17d04865216179f510a", "size": 3665
            },
            "minecraft/sounds/ambient/cave/cave1.ogg": {
                "hash": "ecdd0e39a4bce0d1e6e93b2b1c1e91e0a5c74d0f", "size": 41880
            }
        }
    }"#;

    #[test]
    fn the_official_index_shape_parses() {
        let index: AssetIndex = AssetManager::parse(SAMPLE.as_bytes()).unwrap();
        assert_eq!(index.objects.len(), 2);
        assert_eq!(index.total_bytes(), 3665 + 41880);
        assert!(
            !index.is_legacy_layout(),
            "1.8.9 uses the object store, not the legacy copy layout"
        );
    }

    #[test]
    fn asset_paths_follow_the_published_shard_layout() {
        let (_tmp, m) = manager();
        let index = AssetManager::parse(SAMPLE.as_bytes()).unwrap();
        let objects = m.resolve_objects(&index, Feature::ProfileInstall).unwrap();

        let icon = objects
            .iter()
            .find(|o| o.name == "icons/icon_16x16.png")
            .unwrap();

        assert_eq!(
            icon.request.path,
            "/bd/bdf48ef6b5d0d23bbb02e17d04865216179f510a"
        );
        assert_eq!(icon.request.destination, Destination::MinecraftResources);
        assert!(icon
            .target
            .ends_with("bd/bdf48ef6b5d0d23bbb02e17d04865216179f510a"));
        assert_eq!(icon.expect.size, Some(3665));
    }

    #[test]
    fn the_logical_name_never_becomes_the_path() {
        // A file called `minecraft/sounds/.../cave1.ogg` is stored by hash, not
        // by name. Treating the name as a path would be a traversal sink.
        let (_tmp, m) = manager();
        let index = AssetManager::parse(SAMPLE.as_bytes()).unwrap();
        let objects = m.resolve_objects(&index, Feature::ProfileInstall).unwrap();

        for o in &objects {
            assert!(
                !o.target.to_string_lossy().contains("sounds"),
                "the object path must be hash-derived, not name-derived"
            );
        }
    }

    #[test]
    fn a_malformed_hash_is_skipped_rather_than_becoming_a_path() {
        let (_tmp, m) = manager();
        let hostile = r#"{
            "objects": {
                "good": { "hash": "bdf48ef6b5d0d23bbb02e17d04865216179f510a", "size": 1 },
                "traversal": { "hash": "../../../../etc/passwd", "size": 1 },
                "short": { "hash": "abc", "size": 1 },
                "nonhex": { "hash": "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "size": 1 }
            }
        }"#;
        let index = AssetManager::parse(hostile.as_bytes()).unwrap();
        let objects = m.resolve_objects(&index, Feature::ProfileInstall).unwrap();

        assert_eq!(objects.len(), 1, "only the well-formed entry survives");
        assert_eq!(objects[0].name, "good");
    }

    #[test]
    fn every_object_target_stays_inside_the_cache() {
        let (_tmp, m) = manager();
        let index = AssetManager::parse(SAMPLE.as_bytes()).unwrap();
        let objects = m.resolve_objects(&index, Feature::ProfileInstall).unwrap();
        for o in &objects {
            assert!(m.resolver.cache().contains(&o.target));
        }
    }

    #[test]
    fn a_legacy_layout_index_is_detected_rather_than_mishandled() {
        let legacy: AssetIndex = AssetManager::parse(br#"{"objects":{},"virtual":true}"#).unwrap();
        assert!(legacy.is_legacy_layout());

        let mapped: AssetIndex =
            AssetManager::parse(br#"{"objects":{},"map_to_resources":true}"#).unwrap();
        assert!(mapped.is_legacy_layout());
    }

    #[test]
    fn an_empty_index_resolves_to_nothing() {
        let (_tmp, m) = manager();
        let index = AssetManager::parse(br#"{"objects":{}}"#).unwrap();
        assert!(m
            .resolve_objects(&index, Feature::ProfileInstall)
            .unwrap()
            .is_empty());
    }

    #[test]
    fn asset_names_with_spaces_and_unicode_are_carried_through() {
        let (_tmp, m) = manager();
        let json = r#"{
            "objects": {
                "minecraft/lang/中文 file.lang": {
                    "hash": "bdf48ef6b5d0d23bbb02e17d04865216179f510a", "size": 5
                }
            }
        }"#;
        let index = AssetManager::parse(json.as_bytes()).unwrap();
        let objects = m.resolve_objects(&index, Feature::ProfileInstall).unwrap();

        assert_eq!(objects.len(), 1);
        assert!(objects[0].name.contains('\u{4e2d}'));
        // The awkward name is only ever a label; the path is still the hash.
        assert!(objects[0]
            .target
            .ends_with("bd/bdf48ef6b5d0d23bbb02e17d04865216179f510a"));
    }
}
