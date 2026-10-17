//! Where installed artifacts live, and the rule that keeps them apart.
//!
//! Two kinds of data, with different sharing rules:
//!
//! **Immutable, shared.** Libraries, assets, version documents, the client jar.
//! Every one is content-addressed and verified against a publisher hash on the
//! way in, so two profiles asking for the same SHA-1 want the same bytes. There
//! is nothing to diverge, so sharing is safe — and 1.8.9's assets alone are
//! ~110 MB, which is not worth copying per profile.
//!
//! **Mutable, isolated.** Worlds, screenshots, options, mods, config, logs.
//! These belong to one profile and are never shared, never deduplicated, and —
//! importantly for repair — never touched by the installer.
//!
//! ```text
//! <data root>/
//!   shared/                  immutable, verified, shared
//!     libraries/<maven path>
//!     assets/objects/<xx>/<sha1>
//!     assets/indexes/<id>.json
//!     versions/<id>/<id>.json, <id>.jar
//!     logging/<id>            log4j config, addressed by its own id
//!   profiles/<id>/           mutable, isolated  (see crate::profiles)
//!     minecraft/  natives/  logs/
//! ```
//!
//! Every path here is produced by joining a validated relative path onto a root
//! we own. Nothing derived from downloaded metadata is joined directly.

use std::path::{Path, PathBuf};

use crate::fs_safety::{self, FsError};
use crate::paths::DataPaths;

#[derive(Debug, thiserror::Error)]
pub enum CacheError {
    #[error(transparent)]
    Fs(#[from] FsError),
    #[error("{0:?} is not a usable identifier")]
    BadIdentifier(String),
    #[error("{0:?} is not a valid SHA-1")]
    BadHash(String),
}

/// The shared, immutable half of the layout.
#[derive(Debug, Clone)]
pub struct ArtifactCache {
    paths: DataPaths,
}

impl ArtifactCache {
    pub fn new(paths: DataPaths) -> Self {
        Self { paths }
    }

    pub fn paths(&self) -> &DataPaths {
        &self.paths
    }

    pub fn root(&self) -> PathBuf {
        self.paths.shared_cache()
    }

    /// Create the directories the installer writes into.
    pub fn ensure(&self) -> Result<(), CacheError> {
        for dir in [
            self.paths.libraries_dir(),
            self.paths.asset_objects_dir(),
            self.paths.asset_indexes_dir(),
            self.paths.versions_dir(),
            self.logging_dir(),
        ] {
            std::fs::create_dir_all(&dir).map_err(|e| {
                CacheError::Fs(FsError::Io {
                    path: dir.display().to_string(),
                    source: e,
                })
            })?;
        }
        Ok(())
    }

    pub fn logging_dir(&self) -> PathBuf {
        self.paths.shared_cache().join("logging")
    }

    // -- libraries ---------------------------------------------------------

    /// Where a library artifact lives.
    ///
    /// `relative` comes straight out of Mojang's metadata
    /// (`com/google/guava/guava/17.0/guava-17.0.jar`) and is therefore
    /// untrusted: it goes through [`fs_safety::contain`], which rejects
    /// traversal, absolute paths, drive letters and Windows device names.
    pub fn library_path(&self, relative: &str) -> Result<PathBuf, CacheError> {
        Ok(fs_safety::contain(&self.paths.libraries_dir(), relative)?.into_path())
    }

    // -- versions ----------------------------------------------------------

    /// Directory for one version's own files.
    pub fn version_dir(&self, version_id: &str) -> Result<PathBuf, CacheError> {
        Ok(fs_safety::contain(&self.paths.versions_dir(), version_id)?.into_path())
    }

    pub fn version_json(&self, version_id: &str) -> Result<PathBuf, CacheError> {
        Ok(self
            .version_dir(version_id)?
            .join(format!("{version_id}.json")))
    }

    pub fn client_jar(&self, version_id: &str) -> Result<PathBuf, CacheError> {
        Ok(self
            .version_dir(version_id)?
            .join(format!("{version_id}.jar")))
    }

    // -- assets ------------------------------------------------------------

    /// Asset index document, by its id (`1.8` for 1.8.9).
    pub fn asset_index(&self, index_id: &str) -> Result<PathBuf, CacheError> {
        Ok(
            fs_safety::contain(&self.paths.asset_indexes_dir(), &format!("{index_id}.json"))?
                .into_path(),
        )
    }

    /// An asset object, in Mojang's own two-character shard layout.
    ///
    /// The hash is validated as 40 hex characters before it becomes a path, so
    /// an index containing `../../etc/passwd` as a "hash" yields an error rather
    /// than a write outside the cache.
    pub fn asset_object(&self, sha1: &str) -> Result<PathBuf, CacheError> {
        let relative =
            asset_object_relative(sha1).ok_or_else(|| CacheError::BadHash(sha1.to_owned()))?;
        Ok(fs_safety::contain(&self.paths.asset_objects_dir(), &relative)?.into_path())
    }

    // -- logging config ----------------------------------------------------

    /// The log4j2 configuration a version asks for.
    ///
    /// 1.8.9 declares `client-1.7.xml`; the id is used as the filename so two
    /// versions sharing a config share the file.
    pub fn logging_config(&self, file_id: &str) -> Result<PathBuf, CacheError> {
        Ok(fs_safety::contain(&self.logging_dir(), file_id)?.into_path())
    }

    // -- guard -------------------------------------------------------------

    /// Whether a path is inside the shared cache.
    ///
    /// The installer asserts this on every target before writing, so a bug in a
    /// path helper surfaces as a refusal rather than a stray write.
    pub fn contains(&self, candidate: &Path) -> bool {
        fs_safety::is_lexically_inside(&self.paths.shared_cache(), candidate)
    }
}

/// `objects/<first two hex chars>/<full sha1>`, or `None` if the hash is not a
/// hash.
pub fn asset_object_relative(sha1: &str) -> Option<String> {
    if sha1.len() != 40 || !sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let prefix = sha1.get(..2)?;
    Some(format!("{prefix}/{sha1}"))
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

    fn cache() -> (tempfile::TempDir, ArtifactCache) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let cache = ArtifactCache::new(paths);
        cache.ensure().unwrap();
        (tmp, cache)
    }

    #[test]
    fn every_helper_lands_inside_the_shared_cache() {
        let (_tmp, c) = cache();
        let paths = vec![
            c.library_path("com/google/guava/guava/17.0/guava-17.0.jar")
                .unwrap(),
            c.version_dir("1.8.9").unwrap(),
            c.version_json("1.8.9").unwrap(),
            c.client_jar("1.8.9").unwrap(),
            c.asset_index("1.8").unwrap(),
            c.asset_object("f6ad102bcaa53b1a58358f16e376d548d44933ec")
                .unwrap(),
            c.logging_config("client-1.7.xml").unwrap(),
        ];
        for p in paths {
            assert!(c.contains(&p), "{} escaped the cache", p.display());
        }
    }

    #[test]
    fn library_paths_that_traverse_are_refused() {
        let (_tmp, c) = cache();
        for bad in [
            "../../../etc/passwd",
            "..\\..\\evil.jar",
            "C:/Windows/System32/evil.dll",
            "com/../../../outside.jar",
        ] {
            assert!(
                c.library_path(bad).is_err(),
                "{bad:?} should have been refused"
            );
        }
    }

    #[test]
    fn a_version_id_that_traverses_is_refused() {
        let (_tmp, c) = cache();
        assert!(c.version_dir("1.8.9").is_ok());
        for bad in ["../../evil", "..", "a/../../b", "C:evil"] {
            assert!(c.version_dir(bad).is_err(), "{bad:?} should be refused");
        }
    }

    #[test]
    fn asset_objects_are_sharded_the_way_mojang_shards_them() {
        let (_tmp, c) = cache();
        let sha = "f6ad102bcaa53b1a58358f16e376d548d44933ec";
        let p = c.asset_object(sha).unwrap();
        assert!(p.ends_with(format!("f6/{sha}")));
        assert!(p.starts_with(c.paths().asset_objects_dir()));
    }

    #[test]
    fn a_hash_that_is_not_a_hash_cannot_become_a_path() {
        let (_tmp, c) = cache();
        for bad in [
            "../../etc/passwd",
            "short",
            &"g".repeat(40),
            "",
            "f6ad102bcaa53b1a58358f16e376d548d44933e", // 39 chars
            "f6ad102bcaa53b1a58358f16e376d548d44933ecc", // 41 chars
        ] {
            assert!(
                matches!(c.asset_object(bad), Err(CacheError::BadHash(_))),
                "{bad:?} should have been refused"
            );
        }
    }

    #[test]
    fn asset_object_relative_matches_the_published_layout() {
        assert_eq!(
            asset_object_relative("f6ad102bcaa53b1a58358f16e376d548d44933ec").unwrap(),
            "f6/f6ad102bcaa53b1a58358f16e376d548d44933ec"
        );
        assert!(asset_object_relative("nope").is_none());
    }

    #[test]
    fn paths_with_spaces_and_unicode_are_handled() {
        let tmp = tempfile::tempdir().expect("tempdir");
        // A data root containing a space and non-ASCII characters, which is
        // ordinary on a real machine ("C:\Users\Jos\u{e9} M\u{fc}ller\...").
        let root = tmp.path().join("Jos\u{e9} M\u{fc}ller/App Data");
        let paths = DataPaths::rooted_at(&root);
        paths.ensure().unwrap();
        let c = ArtifactCache::new(paths);
        c.ensure().unwrap();

        let lib = c
            .library_path("net/example/my lib/1.0/my lib-1.0.jar")
            .unwrap();
        assert!(c.contains(&lib));
        std::fs::create_dir_all(lib.parent().unwrap()).unwrap();
        std::fs::write(&lib, b"x").unwrap();
        assert_eq!(std::fs::read(&lib).unwrap(), b"x");
    }

    #[test]
    fn contains_rejects_a_profile_path() {
        let (_tmp, c) = cache();
        // Profile data is deliberately outside the shared cache: it is mutable
        // and must never be deduplicated or repaired.
        let profile_file = c.paths().profiles_dir().join("main/minecraft/options.txt");
        assert!(!c.contains(&profile_file));
    }
}
