//! Where mod jars live, and how the mods directory is built from them.
//!
//! # The store
//!
//! `<data>/mods/<first two hex>/<sha1>.jar`. Content-addressed, like the artifact
//! cache, and for the same reasons: two profiles using the same mod hold one
//! copy, and a file that changed is a different hash rather than the same name
//! with different bytes.
//!
//! Kept out of `shared/`, which is Mojang's content fetched from Mojang's hosts.
//! A jar the user supplied is a different kind of thing and lives under its own
//! root.
//!
//! # Materialization
//!
//! The mods directory is **derived**, not edited. At launch it is made to
//! contain exactly the resolved set: files are copied in from the store, and
//! files the launcher previously put there and no longer wants are removed.
//!
//! The critical rule: **only files the launcher placed are ever removed.** A
//! `.fathom-mods.json` manifest records what was materialized, the same pattern
//! as the natives stamp. A jar someone dropped in by hand is not in the
//! manifest, so it is left alone and *adopted* into the configuration instead.
//! Deleting a user's file because it was not in a list we generated would be the
//! worst kind of bug this module could have.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::resolve::ResolvedMod;
use super::ModError;
use crate::fs_safety;
use crate::install::download::{sha1_file, sha1_hex};
use crate::paths::DataPaths;
use crate::profiles::ProfileDirs;

/// Names what the launcher put in a mods directory.
const MANIFEST_FILE: &str = ".fathom-mods.json";

/// A ceiling on a mod jar. Large for a mod; small enough that a mistake does not
/// fill a disk.
const MAX_MOD_BYTES: u64 = 256 * 1024 * 1024;

/// One jar in the store.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredMod {
    pub sha1: String,
    pub path: PathBuf,
    pub size: u64,
}

/// A jar present in a profile's mods directory.
///
/// Distinct from [`super::ModDefinition`] (what a mod *is*) and from
/// [`super::ProfileMod`] (what a profile *wants*). This is only ever "these
/// bytes are at this path".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledMod {
    pub file_name: String,
    pub path: PathBuf,
    pub size: u64,
    pub sha1: String,
    /// Whether the launcher put it there, as opposed to the user.
    pub managed: bool,
}

impl InstalledMod {
    /// A display name: the file stem, tidied a little.
    pub fn display_name(&self) -> String {
        self.file_name
            .strip_suffix(".jar")
            .unwrap_or(&self.file_name)
            .replace(['_', '-'], " ")
    }

    pub fn short_hash(&self) -> String {
        self.sha1.chars().take(12).collect()
    }
}

/// What the launcher last materialized into a mods directory.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Manifest {
    /// File names the launcher wrote. Names, not paths: everything is one level
    /// inside the mods directory.
    #[serde(default)]
    placed: BTreeSet<String>,
}

impl Manifest {
    fn read(mods_dir: &Path) -> Self {
        std::fs::read(mods_dir.join(MANIFEST_FILE))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    fn write(&self, mods_dir: &Path) -> Result<(), ModError> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|source| ModError::Malformed {
            path: MANIFEST_FILE.to_owned(),
            source,
        })?;
        fs_safety::atomic_write(&mods_dir.join(MANIFEST_FILE), &bytes)?;
        Ok(())
    }
}

/// The content-addressed store of mod jars.
#[derive(Debug, Clone)]
pub struct ModStore {
    paths: DataPaths,
}

impl ModStore {
    pub fn new(paths: DataPaths) -> Self {
        Self { paths }
    }

    pub fn root(&self) -> PathBuf {
        self.paths.root().join("mods")
    }

    /// Where a hash's bytes live.
    ///
    /// The hash is validated rather than trusted: it arrives from a profile
    /// file, and forty hex characters is the only shape that may become a path.
    pub fn path_for(&self, sha1: &str) -> Result<PathBuf, ModError> {
        if sha1.len() != 40 || !sha1.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(ModError::BadHash {
                value: sha1.to_owned(),
            });
        }
        let lower = sha1.to_ascii_lowercase();
        let prefix = lower.get(..2).unwrap_or("00");
        Ok(self.root().join(prefix).join(format!("{lower}.jar")))
    }

    pub fn contains(&self, sha1: &str) -> bool {
        self.path_for(sha1).is_ok_and(|p| p.is_file())
    }

    /// [`Self::adopt`], without re-reading a file that has not changed.
    ///
    /// For the jars the launcher adopts on every start -- Fathom's own client
    /// module, once per Fathom profile. That jar carries the menu's video
    /// frames and is about 35 MB, so plain `adopt` read and SHA-1 hashed all of
    /// it, per profile, on every launch of the launcher, before its window
    /// could open.
    ///
    /// The answer is remembered under the file's path, length and modification
    /// time. Any of those changing -- a new build staged over it, an update --
    /// is a different key and hashes again. A remembered hash is trusted only
    /// while the stored copy it names is still there at the same length.
    pub fn adopt_remembered(&self, source: &Path) -> Result<StoredMod, ModError> {
        let metadata = std::fs::metadata(source).map_err(|source_error| ModError::Io {
            path: source.display().to_string(),
            source: source_error,
        })?;
        let memo = remember_key(source, &metadata)
            .map(|key| self.root().join(".adopted").join(format!("{key}.sha1")));

        if let Some(memo) = &memo {
            if let Ok(recorded) = std::fs::read_to_string(memo) {
                let sha1 = recorded.trim();
                if sha1.len() == 40 && sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
                    if let Ok(target) = self.path_for(sha1) {
                        let same_size = std::fs::metadata(&target)
                            .is_ok_and(|stored| stored.is_file() && stored.len() == metadata.len());
                        if same_size {
                            return Ok(StoredMod {
                                sha1: sha1.to_owned(),
                                path: target,
                                size: metadata.len(),
                            });
                        }
                    }
                }
            }
        }

        let stored = self.adopt(source)?;
        if let Some(memo) = &memo {
            // Best effort: a memo that cannot be written only costs a hash
            // next time.
            if let Err(error) = fs_safety::atomic_write(memo, stored.sha1.as_bytes()) {
                tracing::debug!(%error, "could not remember an adopted jar's hash");
            }
        }
        Ok(stored)
    }

    /// Take a copy of a jar the user chose.
    ///
    /// Never moves or deletes the original: it is the user's file, sitting
    /// wherever they keep it. The hash is computed from the bytes we actually
    /// read, so what ends up in the store is what was hashed.
    pub fn adopt(&self, source: &Path) -> Result<StoredMod, ModError> {
        let metadata = std::fs::metadata(source).map_err(|source_error| ModError::Io {
            path: source.display().to_string(),
            source: source_error,
        })?;

        if !metadata.is_file() {
            return Err(ModError::NotAJar {
                path: source.display().to_string(),
            });
        }
        if metadata.len() > MAX_MOD_BYTES {
            return Err(ModError::TooLarge {
                path: source.display().to_string(),
                size: metadata.len(),
            });
        }
        // A `.jar` is a zip. We do not open it — see the module note in
        // `mods/mod.rs` — but a file that is not one at all is worth refusing
        // before it reaches a mods directory.
        if !looks_like_a_jar(source) {
            return Err(ModError::NotAJar {
                path: source.display().to_string(),
            });
        }

        let bytes = std::fs::read(source).map_err(|source_error| ModError::Io {
            path: source.display().to_string(),
            source: source_error,
        })?;
        let sha1 = sha1_hex(&bytes);
        let target = self.path_for(&sha1)?;

        if target.is_file() {
            // Already have these exact bytes. Nothing to do, and re-writing
            // would risk replacing a good file with a partial one.
            return Ok(StoredMod {
                sha1,
                path: target,
                size: metadata.len(),
            });
        }

        fs_safety::atomic_write(&target, &bytes)?;
        Ok(StoredMod {
            sha1,
            path: target,
            size: metadata.len(),
        })
    }

    /// Take bytes the launcher downloaded, checking them against the hash the
    /// publisher declared.
    ///
    /// The counterpart to [`Self::adopt`], and deliberately a different
    /// function rather than a flag on it. Adopting a file the user chose has no
    /// expected hash -- whatever they picked is what they meant. Adopting bytes
    /// off the network has one, and a mismatch is the single most important
    /// thing this type can detect: it is the difference between "the index said
    /// this was Legacy Fabric API" and "these bytes are Legacy Fabric API".
    ///
    /// Nothing is written when the hash disagrees. The bytes are dropped.
    pub fn adopt_bytes(&self, bytes: &[u8], expected_sha1: &str) -> Result<StoredMod, ModError> {
        if bytes.len() as u64 > MAX_MOD_BYTES {
            return Err(ModError::TooLarge {
                path: expected_sha1.to_owned(),
                size: bytes.len() as u64,
            });
        }
        if !bytes.starts_with(b"PK") {
            return Err(ModError::NotAJar {
                path: expected_sha1.to_owned(),
            });
        }

        let actual = sha1_hex(bytes);
        if !actual.eq_ignore_ascii_case(expected_sha1) {
            return Err(ModError::HashMismatch {
                expected: expected_sha1.to_ascii_lowercase(),
                actual,
            });
        }

        let target = self.path_for(&actual)?;
        if !target.is_file() {
            fs_safety::atomic_write(&target, bytes)?;
        }
        Ok(StoredMod {
            sha1: actual,
            path: target,
            size: bytes.len() as u64,
        })
    }

    /// Every hash the store holds.
    pub fn contents(&self) -> Vec<String> {
        let mut out = Vec::new();
        let Ok(shards) = std::fs::read_dir(self.root()) else {
            return out;
        };
        for shard in shards.flatten() {
            let Ok(files) = std::fs::read_dir(shard.path()) else {
                continue;
            };
            for file in files.flatten() {
                if let Some(name) = file.file_name().to_str() {
                    if let Some(hash) = name.strip_suffix(".jar") {
                        if hash.len() == 40 && hash.chars().all(|c| c.is_ascii_hexdigit()) {
                            out.push(hash.to_ascii_lowercase());
                        }
                    }
                }
            }
        }
        out.sort();
        out
    }

    /// Delete stored files nothing refers to any more.
    ///
    /// # Why this is not automatic
    ///
    /// Because the store is shared. Two profiles referring to the same hash are
    /// referring to the same file, which is the point -- adding `OptiFine` to
    /// four profiles stores one copy. That also means "this profile stopped
    /// using it" is not "nobody is using it", and a store that tidied up after
    /// every removal would delete a file another profile still needs.
    ///
    /// So the caller passes every hash that is still referenced, from every
    /// profile, and anything else goes. Getting that list wrong deletes a file
    /// somebody supplied, which is why this takes the whole set rather than a
    /// single profile.
    ///
    /// Returns the hashes removed.
    pub fn collect_garbage(&self, still_referenced: &BTreeSet<String>) -> Vec<String> {
        let mut removed = Vec::new();
        for hash in self.contents() {
            if still_referenced.contains(&hash) {
                continue;
            }
            let Ok(path) = self.path_for(&hash) else {
                continue;
            };
            if std::fs::remove_file(&path).is_ok() {
                removed.push(hash);
            }
        }
        removed
    }

    /// Verify a stored file still hashes to its name.
    ///
    /// The store is content-addressed, so this is a real check rather than a
    /// formality: a mismatch means the file was replaced on disk.
    pub fn verify(&self, sha1: &str) -> Result<bool, ModError> {
        let path = self.path_for(sha1)?;
        Ok(sha1_file(&path).is_some_and(|actual| actual.eq_ignore_ascii_case(sha1)))
    }
}

// ---------------------------------------------------------------------------
// Materialization
// ---------------------------------------------------------------------------

/// What materializing a mods directory did.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Materialization {
    pub placed: Vec<String>,
    pub removed: Vec<String>,
    /// Files present that the launcher did not put there. Left alone, and
    /// reported so they can be adopted rather than silently ignored.
    pub foreign: Vec<String>,
    /// Mods that should have been placed but whose bytes are not in the store.
    pub missing: Vec<String>,
}

impl Materialization {
    pub fn changed_anything(&self) -> bool {
        !self.placed.is_empty() || !self.removed.is_empty()
    }

    pub fn summary(&self) -> String {
        format!(
            "{} placed, {} removed, {} left alone",
            self.placed.len(),
            self.removed.len(),
            self.foreign.len()
        )
    }
}

/// Make the mods directory contain exactly the resolved set.
///
/// Idempotent: running it twice over the same input does the same thing the
/// second time as it would have if the first had not happened.
pub fn materialize(
    store: &ModStore,
    dirs: &ProfileDirs,
    effective: &[ResolvedMod],
) -> Result<Materialization, ModError> {
    let mods_dir = dirs.mods_dir();
    std::fs::create_dir_all(&mods_dir).map_err(|source| ModError::Io {
        path: mods_dir.display().to_string(),
        source,
    })?;

    let previous = Manifest::read(&mods_dir);
    let mut outcome = Materialization::default();
    let mut now_placed: BTreeSet<String> = BTreeSet::new();

    // -- put the wanted files there ----------------------------------------
    for wanted in effective {
        // The file name comes from a profile file. It becomes a path, so it is
        // contained rather than joined.
        let target = dirs
            .contain_in_game(&format!("mods/{}", wanted.file_name))
            .map_err(|_| ModError::UnsafeName {
                name: wanted.file_name.clone(),
            })?;

        let source = store.path_for(&wanted.sha1)?;
        if !source.is_file() {
            outcome.missing.push(wanted.file_name.clone());
            continue;
        }

        now_placed.insert(wanted.file_name.clone());

        // Already correct? Leave it. Copying every launch would churn the disk
        // and change mtimes for no reason.
        if target.is_file()
            && sha1_file(&target).is_some_and(|h| h.eq_ignore_ascii_case(&wanted.sha1))
        {
            continue;
        }

        // Copied under a name the game will not load, then renamed. A copy
        // interrupted half way leaves a `.partial` rather than a truncated jar
        // that the loader would try to open at the next launch.
        let staging = crate::install::download::partial_path(&target);
        let placed = std::fs::copy(&source, &staging).and_then(|_| {
            #[cfg(windows)]
            if target.exists() {
                std::fs::remove_file(&target)?;
            }
            std::fs::rename(&staging, &target)
        });
        if let Err(source_error) = placed {
            let _ = std::fs::remove_file(&staging);
            return Err(ModError::Io {
                path: target.display().to_string(),
                source: source_error,
            });
        }
        outcome.placed.push(wanted.file_name.clone());
    }

    // -- take away what we previously put there and no longer want ----------
    for stale in previous.placed.difference(&now_placed) {
        let Ok(path) = dirs.contain_in_game(&format!("mods/{stale}")) else {
            // A name in our own manifest that will not contain is a manifest we
            // should not act on. Skipping is the safe direction.
            continue;
        };
        if path.is_file() {
            std::fs::remove_file(&path).map_err(|source| ModError::Io {
                path: path.display().to_string(),
                source,
            })?;
            outcome.removed.push(stale.clone());
        }
    }

    // -- notice, but never touch, anything else ----------------------------
    if let Ok(entries) = std::fs::read_dir(&mods_dir) {
        for entry in entries.flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if name == MANIFEST_FILE || !is_jar_name(&name) {
                continue;
            }
            if !now_placed.contains(&name) {
                outcome.foreign.push(name);
            }
        }
    }
    outcome.foreign.sort();

    Manifest { placed: now_placed }.write(&mods_dir)?;

    Ok(outcome)
}

/// Every jar currently in a profile's mods directory.
pub fn list_installed(dirs: &ProfileDirs) -> Vec<InstalledMod> {
    let mods_dir = dirs.mods_dir();
    let manifest = Manifest::read(&mods_dir);

    let Ok(entries) = std::fs::read_dir(&mods_dir) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(file_name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if !is_jar_name(&file_name) {
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        let Some(sha1) = sha1_file(&path) else {
            tracing::warn!(path = %path.display(), "could not hash a mod jar; skipping it");
            continue;
        };

        out.push(InstalledMod {
            managed: manifest.placed.contains(&file_name),
            file_name,
            path,
            size: metadata.len(),
            sha1,
        });
    }

    out.sort_by(|a, b| a.file_name.cmp(&b.file_name));
    out
}

/// Whether a name ends in `.jar`, whatever its casing.
///
/// Case-insensitively, because Windows filesystems are, and a mod called
/// `Cool.JAR` is a mod.
fn is_jar_name(name: &str) -> bool {
    std::path::Path::new(name)
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("jar"))
}

/// A cheap check that a file is a zip archive.
///
/// Reads the four-byte local file header signature. Deliberately **not** an
/// archive walk and certainly not a class load: `mods/mod.rs` records why we do
/// not inspect jars by running or deeply parsing them. This catches a `.txt`
/// renamed to `.jar`, which is the mistake worth catching cheaply.
fn looks_like_a_jar(path: &Path) -> bool {
    use std::io::Read as _;

    let Ok(mut file) = std::fs::File::open(path) else {
        return false;
    };
    let mut magic = [0u8; 4];
    if file.read_exact(&mut magic).is_err() {
        return false;
    }
    // "PK\x03\x04" for a normal archive; "PK\x05\x06" for an empty one.
    magic == [0x50, 0x4B, 0x03, 0x04] || magic == [0x50, 0x4B, 0x05, 0x06]
}

/// A file's identity for [`ModStore::adopt_remembered`]: path, length and
/// modification time, folded into a name. `None` when the platform cannot say
/// when the file was modified, in which case nothing is remembered.
fn remember_key(path: &Path, metadata: &std::fs::Metadata) -> Option<String> {
    let modified = metadata
        .modified()
        .ok()?
        .duration_since(std::time::UNIX_EPOCH)
        .ok()?;
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    let mut feed = |bytes: &[u8]| {
        for byte in bytes {
            hash = (hash ^ u64::from(*byte)).wrapping_mul(0x0100_0000_01b3);
        }
    };
    feed(path.to_string_lossy().as_bytes());
    feed(&metadata.len().to_le_bytes());
    feed(&modified.as_nanos().to_le_bytes());
    Some(format!("{hash:016x}"))
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
    use crate::profiles::ProfileId;

    /// Minimal but real: an empty zip.
    const EMPTY_ZIP: &[u8] = &[
        0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];

    fn fixture() -> (tempfile::TempDir, ModStore, ProfileDirs) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("dirs");
        let dirs = ProfileDirs::new(&paths, &ProfileId::new("main").unwrap());
        dirs.ensure().expect("profile dirs");
        (tmp, ModStore::new(paths), dirs)
    }

    fn jar(tmp: &tempfile::TempDir, name: &str, extra: &[u8]) -> PathBuf {
        let path = tmp.path().join(name);
        let mut bytes = EMPTY_ZIP.to_vec();
        bytes.extend_from_slice(extra);
        std::fs::write(&path, bytes).expect("write jar");
        path
    }

    // -- the store ---------------------------------------------------------

    #[test]
    fn a_hash_that_is_not_a_hash_cannot_become_a_path() {
        let (_tmp, store, _dirs) = fixture();
        for bad in ["", "../../escape", "a".repeat(39).as_str(), "zzz", "a/b"] {
            assert!(store.path_for(bad).is_err(), "{bad:?} must be refused");
        }
        assert!(store.path_for(&"a".repeat(40)).is_ok());
    }

    #[test]
    fn adopting_copies_rather_than_moving_the_users_file() {
        let (tmp, store, _dirs) = fixture();
        let source = jar(&tmp, "cool.jar", b"cool");

        let stored = store.adopt(&source).expect("adopt");
        assert!(source.is_file(), "the user's own file must stay put");
        assert!(stored.path.is_file());
        assert!(stored.path.starts_with(store.root()));
        assert!(store.contains(&stored.sha1));
        assert!(store.verify(&stored.sha1).unwrap());
    }

    /// Content addressing: the same bytes twice is one file.
    #[test]
    fn adopting_the_same_bytes_twice_stores_one_copy() {
        let (tmp, store, _dirs) = fixture();
        let a = jar(&tmp, "a.jar", b"identical");
        let b = jar(&tmp, "b.jar", b"identical");

        let first = store.adopt(&a).unwrap();
        let second = store.adopt(&b).unwrap();
        assert_eq!(first.sha1, second.sha1);
        assert_eq!(first.path, second.path);
    }

    /// An unchanged jar is answered from the memo -- the same hash and the same
    /// stored file, without reading it -- and a changed one is hashed again.
    #[test]
    fn an_unchanged_jar_is_remembered_and_a_changed_one_is_not() {
        let (tmp, store, _dirs) = fixture();
        let source = jar(&tmp, "fathom-optimizations-1.jar", b"first");

        let first = store.adopt_remembered(&source).expect("adopt");
        let memo_dir = store.root().join(".adopted");
        assert_eq!(
            std::fs::read_dir(&memo_dir).unwrap().count(),
            1,
            "a memo is written"
        );

        let again = store.adopt_remembered(&source).expect("remembered");
        assert_eq!(again.sha1, first.sha1);
        assert_eq!(again.path, first.path);

        // New bytes of a different length: a different key, hashed afresh.
        std::fs::write(&source, [EMPTY_ZIP, b"second, longer"].concat()).unwrap();
        let changed = store.adopt_remembered(&source).expect("rehash");
        assert_ne!(changed.sha1, first.sha1);
        assert!(store.verify(&changed.sha1).unwrap());

        // A memo naming a stored file that has gone is not trusted.
        std::fs::remove_file(&changed.path).unwrap();
        let restored = store.adopt_remembered(&source).expect("readopt");
        assert_eq!(restored.sha1, changed.sha1);
        assert!(restored.path.is_file());
    }

    #[test]
    fn a_file_that_is_not_an_archive_is_refused() {
        let (tmp, store, _dirs) = fixture();
        let fake = tmp.path().join("not-really.jar");
        std::fs::write(&fake, b"this is plain text").unwrap();

        assert!(matches!(store.adopt(&fake), Err(ModError::NotAJar { .. })));
    }

    #[test]
    fn a_directory_is_not_a_mod() {
        let (tmp, store, _dirs) = fixture();
        let dir = tmp.path().join("folder.jar");
        std::fs::create_dir_all(&dir).unwrap();
        assert!(store.adopt(&dir).is_err());
    }

    #[test]
    fn a_replaced_store_file_fails_verification() {
        let (tmp, store, _dirs) = fixture();
        let stored = store.adopt(&jar(&tmp, "x.jar", b"original")).unwrap();
        assert!(store.verify(&stored.sha1).unwrap());

        std::fs::write(&stored.path, b"swapped").unwrap();
        assert!(!store.verify(&stored.sha1).unwrap());
    }

    // -- materialization ---------------------------------------------------

    fn resolved(name: &str, sha1: &str) -> ResolvedMod {
        ResolvedMod {
            id: crate::mods::ModId::new(name).unwrap(),
            file_name: format!("{name}.jar"),
            sha1: sha1.to_owned(),
        }
    }

    #[test]
    fn materializing_places_the_resolved_set() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();
        let b = store.adopt(&jar(&tmp, "b.jar", b"b")).unwrap();

        let outcome = materialize(
            &store,
            &dirs,
            &[resolved("a", &a.sha1), resolved("b", &b.sha1)],
        )
        .unwrap();

        assert_eq!(outcome.placed.len(), 2);
        assert!(dirs.mods_dir().join("a.jar").is_file());
        assert!(dirs.mods_dir().join("b.jar").is_file());
        assert!(outcome.missing.is_empty());
    }

    /// The property that makes this a replacement for renaming.
    #[test]
    fn materializing_is_idempotent() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();
        let set = [resolved("a", &a.sha1)];

        let first = materialize(&store, &dirs, &set).unwrap();
        assert!(first.changed_anything());

        let second = materialize(&store, &dirs, &set).unwrap();
        assert!(!second.changed_anything(), "{second:?}");
        assert!(dirs.mods_dir().join("a.jar").is_file());
    }

    /// Disabling is removing from the resolved set. No renaming, and the file
    /// is still in the store, so re-enabling costs a copy.
    #[test]
    fn removing_a_mod_from_the_set_takes_it_out_of_the_directory() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();
        let b = store.adopt(&jar(&tmp, "b.jar", b"b")).unwrap();

        materialize(
            &store,
            &dirs,
            &[resolved("a", &a.sha1), resolved("b", &b.sha1)],
        )
        .unwrap();

        let outcome = materialize(&store, &dirs, &[resolved("a", &a.sha1)]).unwrap();
        assert_eq!(outcome.removed, vec!["b.jar".to_owned()]);
        assert!(dirs.mods_dir().join("a.jar").is_file());
        assert!(!dirs.mods_dir().join("b.jar").exists());

        // And nothing was destroyed: the bytes are still in the store.
        assert!(store.contains(&b.sha1));

        // Re-enabling puts it back.
        let back = materialize(
            &store,
            &dirs,
            &[resolved("a", &a.sha1), resolved("b", &b.sha1)],
        )
        .unwrap();
        assert_eq!(back.placed, vec!["b.jar".to_owned()]);
    }

    /// The most important test in this file. A jar the user dropped in by hand
    /// must survive materialization.
    #[test]
    fn a_file_the_launcher_did_not_place_is_never_deleted() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();

        // The user drops their own jar in.
        let theirs = dirs.mods_dir().join("their-own-mod.jar");
        std::fs::write(&theirs, b"IRREPLACEABLE").unwrap();

        let outcome = materialize(&store, &dirs, &[resolved("a", &a.sha1)]).unwrap();

        assert!(theirs.is_file(), "the user's jar must not be removed");
        assert_eq!(std::fs::read(&theirs).unwrap(), b"IRREPLACEABLE");
        assert_eq!(outcome.foreign, vec!["their-own-mod.jar".to_owned()]);
        assert!(outcome.removed.is_empty());

        // And still there after a second pass, and a pass with an empty set.
        materialize(&store, &dirs, &[]).unwrap();
        assert!(theirs.is_file());
    }

    #[test]
    fn a_mod_whose_bytes_are_missing_is_reported_not_skipped_silently() {
        let (_tmp, store, dirs) = fixture();
        let outcome = materialize(&store, &dirs, &[resolved("gone", &"f".repeat(40))]).unwrap();

        assert_eq!(outcome.missing, vec!["gone.jar".to_owned()]);
        assert!(outcome.placed.is_empty());
        assert!(!dirs.mods_dir().join("gone.jar").exists());
    }

    /// A file name from a profile file becomes a path.
    #[test]
    fn a_traversing_file_name_is_refused() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();

        let hostile = ResolvedMod {
            id: crate::mods::ModId::new("evil").unwrap(),
            file_name: "../../escaped.jar".into(),
            sha1: a.sha1.clone(),
        };

        assert!(matches!(
            materialize(&store, &dirs, &[hostile]),
            Err(ModError::UnsafeName { .. })
        ));
        assert!(!dirs.root().parent().unwrap().join("escaped.jar").exists());
    }

    #[test]
    fn a_replaced_file_in_the_directory_is_restored() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();
        materialize(&store, &dirs, &[resolved("a", &a.sha1)]).unwrap();

        // Something corrupts the placed file.
        std::fs::write(dirs.mods_dir().join("a.jar"), b"corrupted").unwrap();

        let outcome = materialize(&store, &dirs, &[resolved("a", &a.sha1)]).unwrap();
        assert_eq!(outcome.placed, vec!["a.jar".to_owned()]);
        assert_eq!(
            sha1_file(&dirs.mods_dir().join("a.jar")).unwrap(),
            a.sha1,
            "the correct bytes must be restored"
        );
    }

    // -- listing -----------------------------------------------------------

    #[test]
    fn listing_distinguishes_managed_from_user_supplied() {
        let (tmp, store, dirs) = fixture();
        let a = store.adopt(&jar(&tmp, "a.jar", b"a")).unwrap();
        materialize(&store, &dirs, &[resolved("a", &a.sha1)]).unwrap();
        std::fs::write(dirs.mods_dir().join("theirs.jar"), EMPTY_ZIP).unwrap();

        let listed = list_installed(&dirs);
        assert_eq!(listed.len(), 2);

        let managed: Vec<&str> = listed
            .iter()
            .filter(|m| m.managed)
            .map(|m| m.file_name.as_str())
            .collect();
        assert_eq!(managed, vec!["a.jar"]);

        let theirs = listed.iter().find(|m| m.file_name == "theirs.jar").unwrap();
        assert!(!theirs.managed);
        assert_eq!(theirs.sha1.len(), 40);
    }

    #[test]
    fn the_manifest_itself_is_not_listed_as_a_mod() {
        let (_tmp, store, dirs) = fixture();
        materialize(&store, &dirs, &[]).unwrap();
        assert!(dirs.mods_dir().join(MANIFEST_FILE).is_file());
        assert!(list_installed(&dirs).is_empty());
    }

    #[test]
    fn a_jar_carries_a_readable_display_name_and_short_hash() {
        let installed = InstalledMod {
            file_name: "some_cool-mod.jar".into(),
            path: PathBuf::from("/x/some_cool-mod.jar"),
            size: 10,
            sha1: "abcdef0123456789".repeat(2) + "abcdefgh",
            managed: false,
        };
        assert_eq!(installed.display_name(), "some cool mod");
        assert_eq!(installed.short_hash().len(), 12);
    }
}
