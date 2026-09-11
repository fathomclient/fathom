//! Where the launcher is allowed to put things.
//!
//! # The process working directory is never used for anything
//!
//! The launcher is a desktop application. It is started by double-clicking an
//! icon, from a Start-menu shortcut, from a pinned taskbar entry, or by another
//! program — and each of those hands it a different working directory. A
//! shortcut's "Start in" field can be blank, in which case Windows may pick
//! `C:\Windows\System32`.
//!
//! So **nothing here is relative to the working directory**, and
//! [`DataPaths::discover`] never consults it. A test asserts that the resolved
//! layout is identical whatever the process's current directory happens to be.
//!
//! # Six distinct concepts, deliberately not four
//!
//! | Concept | Where | Survives | Safe to delete? |
//! |---|---|---|---|
//! | **Install directory** | next to the executable | reinstall | Not ours to touch |
//! | **Application data** | `%LOCALAPPDATA%\Fathom` | forever | **No** — this is the user's |
//! | **Shared artifacts** | `<data>/shared` | forever | Re-acquirable, but only by downloading again |
//! | **Cache** | `<data>/cache` | discardable | **Yes** — that is what it is for |
//! | **Temporary** | `<data>/tmp` | this run | **Yes** |
//! | **Logs** | `<data>/logs` | until rotated | Yes, at a cost to diagnosis |
//!
//! The distinction that matters most is between **shared artifacts** and
//! **cache**. Both are re-downloadable, so an early version of this had them as
//! one thing — but they behave differently under "clear the cache": deleting
//! `shared/` means re-downloading 139 MB and cannot be done while an install is
//! running, whereas deleting `cache/` costs nothing and is always safe.
//! Conflating them is how a launcher ends up with a "clear cache" button that
//! re-downloads the game. See `recovery::cache`.
//!
//! The install directory is a distinct concept precisely so that nothing writes
//! to it. A portable build sits in a directory the user unzipped; a future
//! installed build sits in `Program Files`, which is not writable without
//! elevation. Persistent state goes in application data either way.
//!
//! Everything mutable and profile-specific lives under `profiles/<id>/`, and
//! `profiles::ProfileId` is the only way to name one.

use std::path::{Path, PathBuf};

use crate::fs_safety::{self, FsError};

#[derive(Debug, thiserror::Error)]
pub enum PathError {
    #[error("could not determine a per-user data directory for this platform")]
    NoDataDirectory,
    #[error(transparent)]
    Fs(#[from] FsError),
    #[error("i/o error creating {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
}

/// The launcher's directory layout.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataPaths {
    root: PathBuf,
}

impl DataPaths {
    /// Resolve the platform's per-user data directory.
    pub fn discover() -> Result<Self, PathError> {
        let dirs = directories::ProjectDirs::from("", "", crate::PRODUCT_NAME)
            .ok_or(PathError::NoDataDirectory)?;
        Ok(Self {
            root: dirs.data_local_dir().to_path_buf(),
        })
    }

    /// Point the layout at an explicit directory. Used by tests and by a
    /// portable install.
    pub fn rooted_at(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Launcher-wide settings file.
    pub fn settings_file(&self) -> PathBuf {
        self.root.join("settings.json")
    }

    /// Parent of all profiles.
    pub fn profiles_dir(&self) -> PathBuf {
        self.root.join("profiles")
    }

    /// The Fathom Profile collection: loadouts, one `<id>.json` each, plus
    /// `client-state.json`. See `docs/fathom-profiles.md`.
    ///
    /// The same directory as [`Self::profiles_dir`] for now, deliberately. The
    /// Game Instance store lists only directories and the profile store lists
    /// only `.json` files, so the two share it without seeing each other — and
    /// that means the collection can exist today without moving any instance
    /// directory out from under a game that might be running from it. The
    /// instances move to `instances/` in their own restart-safe step.
    pub fn fathom_profiles_dir(&self) -> PathBuf {
        crate::loadout::directory_in(&self.root)
    }

    /// Immutable, hash-verified artifacts shared between profiles.
    ///
    /// Safe to share precisely because every file here is content-addressed and
    /// verified on write: two profiles asking for the same SHA-1 want the same
    /// bytes. Nothing mutable ever lands here.
    pub fn shared_cache(&self) -> PathBuf {
        self.root.join("shared")
    }

    pub fn libraries_dir(&self) -> PathBuf {
        self.shared_cache().join("libraries")
    }

    pub fn assets_dir(&self) -> PathBuf {
        self.shared_cache().join("assets")
    }

    pub fn asset_objects_dir(&self) -> PathBuf {
        self.assets_dir().join("objects")
    }

    pub fn asset_indexes_dir(&self) -> PathBuf {
        self.assets_dir().join("indexes")
    }

    pub fn versions_dir(&self) -> PathBuf {
        self.shared_cache().join("versions")
    }

    /// Java runtimes we manage. Empty unless the user opted in.
    pub fn java_dir(&self) -> PathBuf {
        self.root.join("java")
    }

    /// Launcher's own logs, as opposed to a profile's game logs.
    pub fn launcher_logs_dir(&self) -> PathBuf {
        self.root.join("logs")
    }

    /// Disposable data. **Everything under here may be deleted at any time.**
    ///
    /// The rule for putting something here is not "it can be re-downloaded" —
    /// the shared artifacts can be re-downloaded too, and deleting those costs
    /// the user 139 MB and several minutes. The rule is that deleting it costs
    /// *nothing but time we were going to spend anyway*: a metadata document
    /// that is re-fetched on the next check, a manifest with an expiry.
    ///
    /// `recovery::cache` is the only thing that empties it, and it enumerates
    /// what it removes rather than deleting the directory wholesale.
    pub fn cache_dir(&self) -> PathBuf {
        self.root.join("cache")
    }

    /// Cached remote metadata: version manifests, loader indexes.
    ///
    /// Re-fetched on demand. Nothing is ever loaded from here without the
    /// launcher being willing to fetch it again.
    pub fn metadata_cache_dir(&self) -> PathBuf {
        self.cache_dir().join("metadata")
    }

    /// Scratch space for this run.
    ///
    /// Distinct from the cache: the cache is a *deliberate* saving between
    /// runs, and this is a working area that nothing should expect to survive.
    /// Swept at startup.
    pub fn temp_dir(&self) -> PathBuf {
        self.root.join("tmp")
    }

    /// Where a profile goes when it is deleted, before it is destroyed.
    pub fn trash_dir(&self) -> PathBuf {
        self.root.join("trash")
    }

    /// The directory the running executable is in.
    ///
    /// **Read-only as far as this program is concerned.** A portable build sits
    /// wherever the user extracted it; an installed build may sit in
    /// `Program Files`, which needs elevation to write. Neither is a place for
    /// state, and nothing in this codebase writes here.
    ///
    /// Used for one thing: finding first-party resources shipped *beside* the
    /// executable, such as the optimization module jar in a portable build.
    /// `None` when the executable path cannot be determined, which is not fatal
    /// — the caller falls back to the copy in application data.
    pub fn install_dir() -> Option<PathBuf> {
        std::env::current_exe()
            .ok()
            .and_then(|exe| exe.parent().map(Path::to_path_buf))
    }

    /// First-party resources that ship with the build.
    ///
    /// Looked for beside the executable first, then in application data. The
    /// order matters: a portable build carries its own copy, and a copy the
    /// updater placed in application data is the newer one only if the
    /// executable did not bring one.
    pub fn bundled_resource(&self, relative: &str) -> Option<PathBuf> {
        if let Some(beside) = Self::install_dir().map(|d| d.join("resources").join(relative)) {
            if beside.is_file() {
                return Some(beside);
            }
        }
        let in_data = self.root.join("resources").join(relative);
        in_data.is_file().then_some(in_data)
    }

    /// Create the directories that must exist before anything else works.
    pub fn ensure(&self) -> Result<(), PathError> {
        for dir in [
            self.root.clone(),
            self.profiles_dir(),
            self.libraries_dir(),
            self.asset_objects_dir(),
            self.asset_indexes_dir(),
            self.versions_dir(),
            self.launcher_logs_dir(),
            self.cache_dir(),
            self.metadata_cache_dir(),
            self.temp_dir(),
        ] {
            std::fs::create_dir_all(&dir).map_err(|source| PathError::Io {
                path: dir.display().to_string(),
                source,
            })?;
        }
        Ok(())
    }

    /// Confirm a path we are about to write to is inside the data root.
    ///
    /// Belt and braces: every caller should already be using
    /// [`crate::profiles`] or the shared-cache helpers, but a single
    /// assertion point makes the "profile escape" threat cheap to test.
    pub fn assert_inside(&self, candidate: &Path) -> Result<(), PathError> {
        if fs_safety::is_lexically_inside(&self.root, candidate) {
            Ok(())
        } else {
            Err(PathError::Fs(FsError::Escape(
                candidate.display().to_string(),
            )))
        }
    }
}

/// Mojang stores assets as `objects/<first two hex chars>/<full sha1>`. We
/// mirror that layout so the cache is content-addressed end to end.
pub fn asset_object_relative(sha1: &str) -> Option<PathBuf> {
    if sha1.len() != 40 || !sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let prefix = sha1.get(..2)?;
    Some(PathBuf::from(prefix).join(sha1))
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
    fn layout_is_entirely_under_the_root() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path());
        for dir in [
            p.settings_file(),
            p.profiles_dir(),
            p.shared_cache(),
            p.libraries_dir(),
            p.asset_objects_dir(),
            p.asset_indexes_dir(),
            p.versions_dir(),
            p.java_dir(),
            p.launcher_logs_dir(),
        ] {
            assert!(p.assert_inside(&dir).is_ok(), "{} escaped", dir.display());
        }
    }

    #[test]
    fn ensure_creates_the_tree() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path().join("nested"));
        p.ensure().unwrap();
        assert!(p.profiles_dir().is_dir());
        assert!(p.asset_objects_dir().is_dir());
        assert!(p.versions_dir().is_dir());
    }

    #[test]
    fn assert_inside_rejects_an_outside_path() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path().join("data"));
        assert!(p.assert_inside(Path::new("/etc/passwd")).is_err());
        assert!(p
            .assert_inside(&tmp.path().join("data/../elsewhere"))
            .is_err());
    }

    /// The property that makes double-clicking work.
    ///
    /// A shortcut, a Start-menu entry and a taskbar pin can each hand the
    /// process a different working directory, and a shortcut with a blank
    /// "Start in" may get `C:\Windows\System32`. If any persistent path were
    /// relative to that, the launcher would keep its data somewhere different
    /// depending on how it was started -- or fail outright in a directory it
    /// cannot write to.
    #[test]
    fn the_resolved_layout_does_not_depend_on_the_working_directory() {
        // `discover` may legitimately fail in a stripped environment; the point
        // is that when it works, it works the same way from anywhere.
        let Ok(first) = DataPaths::discover() else {
            return;
        };

        let elsewhere = tempfile::tempdir().expect("tempdir");
        let original = std::env::current_dir().expect("a working directory");
        std::env::set_current_dir(elsewhere.path()).expect("move");
        let second = DataPaths::discover().expect("discover from another directory");
        std::env::set_current_dir(&original).expect("move back");

        assert_eq!(
            first, second,
            "the data root must not move when the working directory does"
        );
        assert!(
            first.root().is_absolute(),
            "an absolute root is what makes it independent of the caller"
        );
    }

    /// Every path the launcher persists to must be absolute, or it is secretly
    /// relative to whatever directory the process happens to be in.
    #[test]
    fn every_persistent_path_is_absolute() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path());
        for path in [
            p.settings_file(),
            p.profiles_dir(),
            p.shared_cache(),
            p.libraries_dir(),
            p.assets_dir(),
            p.versions_dir(),
            p.java_dir(),
            p.launcher_logs_dir(),
            p.cache_dir(),
            p.metadata_cache_dir(),
            p.temp_dir(),
            p.trash_dir(),
        ] {
            assert!(path.is_absolute(), "{} is not absolute", path.display());
        }
    }

    /// Cache and shared artifacts are separate trees, and clearing one must not
    /// be able to reach the other. See `recovery::cache`.
    #[test]
    fn the_cache_and_the_shared_artifacts_are_separate_trees() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path());

        assert!(!fs_safety::is_lexically_inside(
            &p.cache_dir(),
            &p.shared_cache()
        ));
        assert!(!fs_safety::is_lexically_inside(
            &p.shared_cache(),
            &p.cache_dir()
        ));
        // And neither contains a profile, which is the user's own data.
        assert!(!fs_safety::is_lexically_inside(
            &p.cache_dir(),
            &p.profiles_dir()
        ));
        assert!(!fs_safety::is_lexically_inside(
            &p.shared_cache(),
            &p.profiles_dir()
        ));
    }

    /// The install directory is not a place for state, so nothing may resolve
    /// into it.
    #[test]
    fn no_persistent_path_lands_in_the_install_directory() {
        let Some(install) = DataPaths::install_dir() else {
            return;
        };
        let Ok(p) = DataPaths::discover() else {
            return;
        };
        assert!(
            !fs_safety::is_lexically_inside(&install, p.root()),
            "application data must not live next to the executable: a portable build              sits where the user unzipped it and an installed one may need elevation"
        );
    }

    #[test]
    fn asset_object_paths_are_sharded_and_validated() {
        let sha = "f6ad102bcaa5a6ba0c1f2a9b41d4b1f3d6d8f8aa";
        let rel = asset_object_relative(sha).unwrap();
        assert_eq!(rel, PathBuf::from("f6").join(sha));

        assert!(asset_object_relative("short").is_none());
        assert!(asset_object_relative(&"g".repeat(40)).is_none());
        assert!(asset_object_relative("../../etc/passwd").is_none());
    }
}
