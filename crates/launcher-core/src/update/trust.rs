//! What this installation already knows, and refuses to un-know.
//!
//! Rollback protection is a memory problem. A signature says "we published
//! this"; it does not say "this is current". Every old, correctly-signed
//! document is a valid document forever, so an attacker who can answer a
//! request — a compromised CDN, a hijacked DNS entry, anyone on a hostile
//! network — can serve last year's release and it will verify perfectly.
//!
//! The only defence is remembering. This file is that memory:
//!
//! - the highest **root** version we have accepted, and the release keys it
//!   named — so a revoked key cannot be reinstated by replaying the root
//!   document that authorised it;
//! - the highest **release** version we have accepted — so an old release
//!   document cannot be served;
//! - the installed **version of each component** — so an old artifact cannot be
//!   offered as an upgrade.
//!
//! # This is not tamper-proof, and it is not pretending to be
//!
//! The file lives in the user's own data directory and is not signed. Anyone
//! who can write to it can reset our memory and replay old metadata.
//!
//! That is accepted rather than overlooked, because the attacker it would
//! defend against — someone with write access to `%LOCALAPPDATA%` — can already
//! replace the launcher binary outright, which is strictly easier than
//! constructing a downgrade. Signing the state file with a key that also lives
//! on the same disk would be theatre.
//!
//! What it *does* defend against is the entire network-side compromise model,
//! which is the one this subsystem exists for.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::manifest::ComponentId;

/// Schema version of this file. A file from the future is not read.
pub const TRUST_STATE_SCHEMA: u32 = 1;

/// Ceiling on the state file. It holds a handful of integers and key ids.
const MAX_STATE_BYTES: u64 = 64 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum TrustError {
    #[error("could not read the update trust state at {path}: {source}")]
    Read {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("could not write the update trust state at {path}: {source}")]
    Write {
        path: String,
        #[source]
        source: crate::fs_safety::FsError,
    },
}

/// The persisted record.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct TrustState {
    pub schema: u32,
    /// Highest root version accepted. 0 means "never seen one".
    #[serde(default)]
    pub root_version: u64,
    /// Base64 release keys named by that root document.
    ///
    /// Cached so that a client which has accepted a root can keep using its key
    /// set without re-fetching, and so that a *downgrade* of the key set is
    /// visible rather than silent.
    #[serde(default)]
    pub release_keys: Vec<String>,
    #[serde(default)]
    pub release_threshold: usize,
    /// Highest release-metadata version accepted.
    #[serde(default)]
    pub release_version: u64,
    /// Installed version per component, by component id string.
    #[serde(default)]
    pub installed: std::collections::BTreeMap<String, String>,
}

impl Default for TrustState {
    fn default() -> Self {
        Self {
            schema: TRUST_STATE_SCHEMA,
            root_version: 0,
            release_keys: Vec::new(),
            release_threshold: 0,
            release_version: 0,
            installed: std::collections::BTreeMap::new(),
        }
    }
}

impl TrustState {
    /// Read the state, or start fresh.
    ///
    /// **A corrupt or unreadable state file is treated as "no state".** That is
    /// the conservative direction: starting from zero means we fall back to the
    /// compiled-in root keys and re-establish the chain from the anchor, which
    /// is exactly what a fresh install does. Refusing to run because a cache
    /// file is malformed would turn a corrupted byte into a bricked launcher.
    ///
    /// It does mean a local attacker who can corrupt the file can reset our
    /// rollback memory. See the module comment: that attacker has better
    /// options already.
    pub fn load(path: &Path) -> Self {
        let Ok(metadata) = std::fs::metadata(path) else {
            return Self::default();
        };
        if metadata.len() > MAX_STATE_BYTES {
            tracing::warn!(
                path = %path.display(),
                bytes = metadata.len(),
                "the update trust state is implausibly large; starting from the compiled-in root"
            );
            return Self::default();
        }
        let Ok(bytes) = std::fs::read(path) else {
            return Self::default();
        };
        match serde_json::from_slice::<Self>(&bytes) {
            Ok(state) if state.schema == TRUST_STATE_SCHEMA => state,
            Ok(state) => {
                tracing::warn!(
                    found = state.schema,
                    supported = TRUST_STATE_SCHEMA,
                    "the update trust state has an unrecognised schema; starting from the \
                     compiled-in root"
                );
                Self::default()
            }
            Err(error) => {
                tracing::warn!(
                    %error,
                    "the update trust state is unreadable; starting from the compiled-in root"
                );
                Self::default()
            }
        }
    }

    /// Persist atomically. A half-written state file would be read as "no
    /// state" on the next run, which loses rollback memory.
    pub fn save(&self, path: &Path) -> Result<(), TrustError> {
        let bytes = serde_json::to_vec_pretty(self).unwrap_or_else(|_| b"{}".to_vec());
        crate::fs_safety::atomic_write(path, &bytes).map_err(|source| TrustError::Write {
            path: path.display().to_string(),
            source,
        })
    }

    pub fn installed_version(&self, component: ComponentId) -> Option<&str> {
        self.installed.get(component.as_str()).map(String::as_str)
    }

    pub fn record_installed(&mut self, component: ComponentId, version: &str) {
        self.installed
            .insert(component.as_str().to_owned(), version.to_owned());
    }

    /// The release key set as a lookup-friendly collection.
    pub fn release_key_set(&self) -> BTreeSet<&str> {
        self.release_keys.iter().map(String::as_str).collect()
    }
}

/// Where the update subsystem keeps its files.
#[derive(Debug, Clone)]
pub struct UpdatePaths {
    root: PathBuf,
}

impl UpdatePaths {
    pub fn new(data_root: &Path) -> Self {
        Self {
            root: data_root.join("update"),
        }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The rollback memory.
    pub fn trust_state(&self) -> PathBuf {
        self.root.join("trust.json")
    }

    /// The last accepted root document, cached so a client that cannot reach
    /// the network still knows which release keys are current.
    pub fn cached_root(&self) -> PathBuf {
        self.root.join("root.json")
    }

    /// Where a download lands before it is verified. Never on the load path of
    /// anything.
    pub fn staging(&self) -> PathBuf {
        self.root.join("staging")
    }

    /// Where the previous copy of a replaced component is kept.
    ///
    /// The known-good copy, retained until the replacement has proved it
    /// starts. Nothing deletes this until something else has been shown to
    /// work.
    pub fn backup(&self) -> PathBuf {
        self.root.join("backup")
    }

    /// The record of an in-progress replacement, used to recover from an
    /// interruption.
    pub fn journal(&self) -> PathBuf {
        self.root.join("in-progress.json")
    }

    pub fn ensure(&self) -> std::io::Result<()> {
        std::fs::create_dir_all(&self.root)?;
        std::fs::create_dir_all(self.staging())?;
        std::fs::create_dir_all(self.backup())?;
        Ok(())
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

    fn temp() -> tempfile::TempDir {
        tempfile::tempdir().expect("tempdir")
    }

    #[test]
    fn a_missing_state_file_is_a_fresh_start_not_an_error() {
        let dir = temp();
        let state = TrustState::load(&dir.path().join("nothing.json"));
        assert_eq!(state, TrustState::default());
        assert_eq!(state.root_version, 0);
    }

    #[test]
    fn state_round_trips() {
        let dir = temp();
        let path = dir.path().join("trust.json");

        let mut state = TrustState {
            root_version: 4,
            release_version: 11,
            release_keys: vec!["AAAA".to_owned()],
            release_threshold: 1,
            ..TrustState::default()
        };
        state.record_installed(ComponentId::Launcher, "0.2.0");
        state.save(&path).unwrap();

        let read = TrustState::load(&path);
        assert_eq!(read, state);
        assert_eq!(read.installed_version(ComponentId::Launcher), Some("0.2.0"));
    }

    /// A corrupt cache must not brick the launcher. Falling back to the
    /// compiled-in anchor is what a fresh install does, so it is a state we
    /// already support.
    #[test]
    fn a_corrupt_state_file_falls_back_to_the_compiled_in_anchor() {
        let dir = temp();
        let path = dir.path().join("trust.json");

        for corrupt in [
            &b"not json at all"[..],
            &b"{"[..],
            &b""[..],
            &b"[1,2,3]"[..],
            &b"{\"schema\": 99}"[..],
        ] {
            std::fs::write(&path, corrupt).unwrap();
            let state = TrustState::load(&path);
            assert_eq!(
                state.root_version, 0,
                "a corrupt state must read as no state, not as a trusted one"
            );
        }
    }

    #[test]
    fn an_implausibly_large_state_file_is_not_read() {
        let dir = temp();
        let path = dir.path().join("trust.json");
        let too_big = usize::try_from(MAX_STATE_BYTES + 1).expect("fits on a 64-bit test host");
        std::fs::write(&path, vec![b'x'; too_big]).unwrap();
        assert_eq!(TrustState::load(&path).root_version, 0);
    }

    #[test]
    fn update_paths_all_sit_under_one_directory() {
        let dir = temp();
        let paths = UpdatePaths::new(dir.path());
        paths.ensure().unwrap();

        for path in [
            paths.trust_state(),
            paths.cached_root(),
            paths.staging(),
            paths.backup(),
            paths.journal(),
        ] {
            assert!(
                crate::fs_safety::is_lexically_inside(paths.root(), &path),
                "{path:?} escapes the update directory"
            );
        }
        assert!(paths.staging().is_dir());
        assert!(paths.backup().is_dir());
    }
}
