//! Unpacking native libraries.
//!
//! This is the most dangerous step in the installer: it takes a downloaded
//! archive and writes files out of it. Archives are attacker-shaped input even
//! when they arrive over TLS from a Mojang hostname, because the hostname says
//! nothing about what is inside the zip.
//!
//! Four rules, enforced in [`crate::fs_safety::extract_zip`] and by the filter
//! below:
//!
//! 1. **Every written path is inside the profile's natives directory.** Entry
//!    names go through `contain`, which rejects `..`, absolute paths, drive
//!    letters, ADS and Windows device names.
//! 2. **Symlink entries are refused, not followed.** Otherwise an archive could
//!    plant a link and a later entry would write through it.
//! 3. **Only shared libraries are extracted.** `.dll`, `.so`, `.dylib`,
//!    `.jnilib`. A natives jar has no business delivering a `.bat`, a `.cmd`,
//!    or another `.jar`, and this allowlist is ours rather than Mojang's.
//! 4. **Bounded.** Entry count, per-entry size and total size, so a
//!    decompression bomb stops rather than filling the disk.
//!
//! Natives are extracted **per profile** rather than shared, because the set
//! depends on the platform and the version, and because the game loads them
//! from a directory it is given.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::fs_safety::{self, ExtractLimits, FsError};

use super::resolver::NativeArtifact;

/// Written next to the extracted libraries so a later launch can tell whether
/// what is on disk is what this version and platform actually need.
const STAMP_FILE: &str = ".fathom-natives.json";

#[derive(Debug, thiserror::Error)]
pub enum NativeError {
    #[error("{library}: {source}")]
    Extract {
        library: String,
        #[source]
        source: FsError,
    },
    #[error("{library}: archive is missing at {path}")]
    Missing { library: String, path: String },
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
}

/// What came out of an extraction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedNatives {
    pub directory: PathBuf,
    pub files: Vec<PathBuf>,
}

/// A record of which archives produced the current contents of a natives
/// directory.
///
/// Without this, "there is a `.dll` here" is the only available check, and that
/// is true of a directory left over from a different version — which is exactly
/// how a launcher ends up loading last month's LWJGL into this month's game and
/// producing an `UnsatisfiedLinkError` nobody can explain. The stamp makes
/// staleness detectable rather than invisible.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NativesStamp {
    pub version_id: String,
    /// `windows`, `linux` or `osx`, plus the bitness, as the resolver saw it.
    pub platform: String,
    /// The SHA-1 of every archive that was extracted, sorted, so the check does
    /// not depend on iteration order.
    pub archives: Vec<String>,
}

impl NativesStamp {
    pub fn new(version_id: &str, platform: &str, natives: &[NativeArtifact]) -> Self {
        let mut archives: Vec<String> = natives
            .iter()
            .map(|n| n.download.expect.sha1.to_ascii_lowercase())
            .collect();
        archives.sort();
        Self {
            version_id: version_id.to_owned(),
            platform: platform.to_owned(),
            archives,
        }
    }

    fn path(destination: &Path) -> PathBuf {
        destination.join(STAMP_FILE)
    }

    /// Read the stamp beside an extracted set. `None` means it was written by a
    /// version of the launcher that did not stamp, or not written at all --
    /// treated as stale, never as a match.
    pub fn read(destination: &Path) -> Option<Self> {
        let bytes = std::fs::read(Self::path(destination)).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    fn write(&self, destination: &Path) -> Result<(), NativeError> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|e| NativeError::Io {
            path: Self::path(destination).display().to_string(),
            source: std::io::Error::other(e),
        })?;
        crate::fs_safety::atomic_write(&Self::path(destination), &bytes).map_err(|source| {
            NativeError::Extract {
                library: STAMP_FILE.to_owned(),
                source,
            }
        })
    }
}

/// Extracts native archives into a profile.
#[derive(Debug, Clone, Default)]
pub struct NativeLibraryManager {
    limits: ExtractLimits,
}

impl NativeLibraryManager {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_limits(limits: ExtractLimits) -> Self {
        Self { limits }
    }

    /// Extract every native archive into `destination`.
    ///
    /// The destination is emptied of previously extracted libraries first, so a
    /// repair does not leave a stale `.dll` from an older version alongside the
    /// new one. Only files this manager would itself have written are removed;
    /// anything else in the directory is left alone.
    pub fn extract_all(
        &self,
        natives: &[NativeArtifact],
        destination: &Path,
    ) -> Result<ExtractedNatives, NativeError> {
        self.extract_stamped(natives, destination, None)
    }

    /// Extract, and record what was extracted.
    ///
    /// `stamp` is what a later launch compares against. Passing `None` extracts
    /// without stamping, which leaves the directory looking stale to the next
    /// check -- correct for callers that do not know the version, and never
    /// wrong in the dangerous direction.
    pub fn extract_stamped(
        &self,
        natives: &[NativeArtifact],
        destination: &Path,
        stamp: Option<&NativesStamp>,
    ) -> Result<ExtractedNatives, NativeError> {
        std::fs::create_dir_all(destination).map_err(|source| NativeError::Io {
            path: destination.display().to_string(),
            source,
        })?;
        Self::clear(destination);

        let mut files = Vec::new();
        for native in natives {
            let archive = &native.download.target;
            if !archive.is_file() {
                return Err(NativeError::Missing {
                    library: native.library.clone(),
                    path: archive.display().to_string(),
                });
            }

            // The version document's own exclusions, plus our allowlist. Both
            // apply; neither is sufficient alone.
            let exclude = native.exclude.clone();
            let filter = move |entry: &str| {
                !exclude.iter().any(|prefix| entry.starts_with(prefix))
                    && is_native_library_name(entry)
            };

            let mut written = fs_safety::extract_zip(archive, destination, self.limits, &filter)
                .map_err(|source| NativeError::Extract {
                    library: native.library.clone(),
                    source,
                })?;
            files.append(&mut written);
        }

        match stamp {
            Some(stamp) => stamp.write(destination)?,
            // No stamp means the next check must not trust this directory. An
            // old stamp left in place would be worse than none.
            None => {
                let _ = std::fs::remove_file(NativesStamp::path(destination));
            }
        }

        Ok(ExtractedNatives {
            directory: destination.to_path_buf(),
            files,
        })
    }

    /// Whether the extracted set matches what this version needs on this
    /// platform.
    ///
    /// Fails closed: a missing stamp, an unreadable one, or one that disagrees
    /// all mean "extract again". Re-extracting costs a fraction of a second;
    /// loading the wrong natives costs a crash the user cannot diagnose.
    pub fn is_current(destination: &Path, expected: &NativesStamp) -> bool {
        if !Self::looks_extracted(destination) {
            return false;
        }
        NativesStamp::read(destination).is_some_and(|found| &found == expected)
    }

    /// Remove previously extracted shared libraries, and the stamp that
    /// described them.
    pub fn clear_extracted(destination: &Path) {
        let _ = std::fs::remove_file(NativesStamp::path(destination));
        Self::clear(destination);
    }

    /// Remove previously extracted shared libraries, including nested ones.
    ///
    /// Recursive, and the security pass is why. The extractor writes whatever
    /// path an entry names — `nested/OpenAL64.dll` is a real case in a real
    /// natives jar — but this only ever swept the top level, so a library from
    /// a previous version, or from an archive that later failed verification,
    /// survived every repair in a subdirectory. "Repair" that leaves the file
    /// you are repairing away from is not a repair.
    ///
    /// The JVM's `java.library.path` search is not recursive, so a stale nested
    /// `.dll` is not loaded and this is not an exploitable hijack on its own.
    /// It is still an unverified binary left inside the profile by a launcher
    /// that reported the profile as clean, which is enough of a reason.
    ///
    /// Bounded depth, because the directory is on disk and disks contain
    /// surprises. Nothing below the limit is our own layout, so refusing to
    /// walk further loses nothing.
    fn clear(destination: &Path) {
        Self::clear_within(destination, destination, 0);
    }

    /// How deep to sweep. Natives jars nest one level in practice; eight is
    /// room to spare without following a link farm forever.
    const MAX_CLEAR_DEPTH: u32 = 8;

    fn clear_within(root: &Path, dir: &Path, depth: u32) {
        if depth > Self::MAX_CLEAR_DEPTH {
            return;
        }
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !fs_safety::is_lexically_inside(root, &path) {
                continue;
            }
            // `symlink_metadata` rather than `is_dir`/`is_file`: those follow
            // links, so a symlink or junction pointing outside the profile
            // would be descended into and its contents deleted. We never create
            // one here, so one found is not ours and is left alone.
            let Ok(meta) = std::fs::symlink_metadata(&path) else {
                continue;
            };
            if meta.file_type().is_symlink() {
                continue;
            }
            if meta.is_dir() {
                Self::clear_within(root, &path, depth + 1);
                // Remove the directory only if the sweep emptied it, so a
                // foreign file inside is never taken with it.
                let _ = std::fs::remove_dir(&path);
                continue;
            }
            if path
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(is_native_library_name)
            {
                let _ = std::fs::remove_file(path);
            }
        }
    }

    /// Whether an extraction still looks complete.
    ///
    /// Cheap: checks the directory has at least one shared library. The archives
    /// themselves are hash-verified in the cache, so a deeper check would be
    /// re-verifying what the cache already guarantees.
    pub fn looks_extracted(destination: &Path) -> bool {
        let Ok(entries) = std::fs::read_dir(destination) else {
            return false;
        };
        entries.flatten().any(|e| {
            e.path().is_file() && e.file_name().to_str().is_some_and(is_native_library_name)
        })
    }
}

/// Only shared libraries come out of a natives jar.
///
/// Ours, not Mojang's: it stops a tampered archive from dropping an executable
/// or a second jar next to the `.dll` the game will load.
#[allow(clippy::case_sensitive_file_extension_comparisons)] // `lower` is lowercased
pub fn is_native_library_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".dll")
        || lower.ends_with(".so")
        || lower.ends_with(".dylib")
        || lower.ends_with(".jnilib")
        // Versioned unix sonames such as `libfoo.so.1`.
        || lower.contains(".so.")
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::case_sensitive_file_extension_comparisons
)]
mod tests {
    use super::*;
    use crate::install::download::{DownloadRequest, Expectation};
    use crate::network::{Destination, Feature, Purpose, RequestDescriptor};
    use std::io::{Cursor, Write};

    fn build_zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut buf);
            let opts: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            for (name, data) in entries {
                w.start_file(*name, opts).unwrap();
                w.write_all(data).unwrap();
            }
            w.finish().unwrap();
        }
        buf.into_inner()
    }

    fn native(archive: &Path, exclude: Vec<String>) -> NativeArtifact {
        NativeArtifact {
            library: "org.lwjgl.lwjgl:lwjgl-platform:2.9.4".into(),
            download: DownloadRequest {
                name: "lwjgl natives".into(),
                request: RequestDescriptor::new(
                    Destination::LibrariesMinecraft,
                    Purpose::DownloadGameArtifact,
                    Feature::ProfileInstall,
                    "/x.jar",
                ),
                expect: Expectation::new("a".repeat(40), None),
                target: archive.to_path_buf(),
            },
            exclude,
        }
    }

    #[test]
    fn only_shared_libraries_are_recognised() {
        for good in [
            "lwjgl64.dll",
            "libopenal.so",
            "liblwjgl.dylib",
            "x.jnilib",
            "libfoo.so.1",
        ] {
            assert!(is_native_library_name(good), "{good} should be extracted");
        }
        for bad in [
            "evil.bat",
            "run.cmd",
            "payload.jar",
            "readme.txt",
            "META-INF/MANIFEST.MF",
            "setup.exe",
            "script.ps1",
        ] {
            assert!(!is_native_library_name(bad), "{bad} must not be extracted");
        }
    }

    #[test]
    fn extraction_writes_only_libraries_and_honours_the_exclude_list() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("natives.jar");
        std::fs::write(
            &archive,
            build_zip(&[
                ("lwjgl64.dll", b"MZ"),
                ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0"),
                ("META-INF/native.dll", b"excluded by the version document"),
                ("evil.bat", b"@echo pwned"),
                ("nested/OpenAL64.dll", b"native"),
            ]),
        )
        .unwrap();

        let dest = tmp.path().join("profile/natives");
        let m = NativeLibraryManager::new();
        let out = m
            .extract_all(&[native(&archive, vec!["META-INF/".into()])], &dest)
            .unwrap();

        assert_eq!(out.files.len(), 2);
        assert!(dest.join("lwjgl64.dll").exists());
        assert!(dest.join("nested/OpenAL64.dll").exists());
        assert!(!dest.join("evil.bat").exists());
        assert!(
            !dest.join("META-INF/native.dll").exists(),
            "the version document's exclude list must be honoured even for a .dll"
        );
    }

    #[test]
    fn archive_traversal_is_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("evil.jar");
        std::fs::write(&archive, build_zip(&[("../../escaped.dll", b"pwned")])).unwrap();

        let dest = tmp.path().join("profile/natives");
        let err = NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .expect_err("zip slip must be refused");

        assert!(matches!(err, NativeError::Extract { .. }));
        assert!(!tmp.path().join("escaped.dll").exists());
        assert!(!tmp.path().join("profile/escaped.dll").exists());
    }

    #[test]
    fn absolute_entry_paths_cannot_escape() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("abs.jar");
        // A leading slash must be treated as relative to the destination, not
        // as the filesystem root.
        std::fs::write(&archive, build_zip(&[("/tmp/evil.dll", b"x")])).unwrap();

        let dest = tmp.path().join("natives");
        let out = NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .unwrap();

        for f in &out.files {
            assert!(fs_safety::is_lexically_inside(&dest, f));
        }
    }

    #[test]
    fn a_decompression_bomb_is_stopped() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("bomb.jar");
        let big = vec![0u8; 8192];
        std::fs::write(
            &archive,
            build_zip(&[("a.dll", &big), ("b.dll", &big), ("c.dll", &big)]),
        )
        .unwrap();

        let m = NativeLibraryManager::with_limits(ExtractLimits {
            max_total_bytes: 10_000,
            ..ExtractLimits::default()
        });
        assert!(m
            .extract_all(&[native(&archive, vec![])], &tmp.path().join("n"))
            .is_err());
    }

    #[test]
    fn a_missing_archive_is_reported_rather_than_silently_skipped() {
        let tmp = tempfile::tempdir().unwrap();
        let absent = tmp.path().join("not-downloaded.jar");
        let err = NativeLibraryManager::new()
            .extract_all(&[native(&absent, vec![])], &tmp.path().join("n"))
            .expect_err("a missing archive must fail the install");
        assert!(matches!(err, NativeError::Missing { .. }));
    }

    #[test]
    fn re_extracting_replaces_stale_libraries() {
        let tmp = tempfile::tempdir().unwrap();
        let dest = tmp.path().join("natives");
        std::fs::create_dir_all(&dest).unwrap();

        // A leftover from an older version, plus a user file that is not ours.
        std::fs::write(dest.join("old_stale.dll"), b"old").unwrap();
        std::fs::write(dest.join("notes.txt"), b"keep me").unwrap();

        let archive = tmp.path().join("n.jar");
        std::fs::write(&archive, build_zip(&[("new.dll", b"new")])).unwrap();

        NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .unwrap();

        assert!(
            !dest.join("old_stale.dll").exists(),
            "stale library removed"
        );
        assert!(dest.join("new.dll").exists());
        assert!(
            dest.join("notes.txt").exists(),
            "files we did not write are left alone"
        );
    }

    /// The security pass found this: the sweep only looked at the top level,
    /// but the extractor writes whatever path an entry names, and
    /// `nested/OpenAL64.dll` is a real entry in a real natives jar. A library
    /// from a previous version survived every repair in a subdirectory.
    #[test]
    fn a_stale_library_in_a_subdirectory_is_removed_too() {
        let tmp = tempfile::tempdir().unwrap();
        let dest = tmp.path().join("natives");
        std::fs::create_dir_all(dest.join("nested/deeper")).unwrap();

        std::fs::write(dest.join("nested/old_stale.dll"), b"old").unwrap();
        std::fs::write(dest.join("nested/deeper/older.so"), b"older").unwrap();
        // Not a library, and not ours: it stays, and so does the directory
        // holding it.
        std::fs::write(dest.join("nested/readme.txt"), b"keep me").unwrap();

        let archive = tmp.path().join("n.jar");
        std::fs::write(&archive, build_zip(&[("new.dll", b"new")])).unwrap();

        NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .unwrap();

        assert!(
            !dest.join("nested/old_stale.dll").exists(),
            "a nested stale library must not survive a repair"
        );
        assert!(
            !dest.join("nested/deeper/older.so").exists(),
            "however deep it is"
        );
        assert!(
            dest.join("nested/readme.txt").exists(),
            "a foreign file is still left alone"
        );
        assert!(
            dest.join("nested").is_dir(),
            "and so is the directory holding it"
        );
        assert!(dest.join("new.dll").exists());
    }

    /// The sweep must not follow a link out of the profile and delete what it
    /// finds there. We never create one, so one found is not ours.
    #[cfg(unix)]
    #[test]
    fn the_sweep_does_not_follow_a_symlink_out_of_the_profile() {
        let tmp = tempfile::tempdir().unwrap();
        let outside = tmp.path().join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("precious.dll"), b"not yours").unwrap();

        let dest = tmp.path().join("natives");
        std::fs::create_dir_all(&dest).unwrap();
        std::os::unix::fs::symlink(&outside, dest.join("link")).unwrap();

        let archive = tmp.path().join("n.jar");
        std::fs::write(&archive, build_zip(&[("new.dll", b"new")])).unwrap();

        NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .unwrap();

        assert!(
            outside.join("precious.dll").exists(),
            "the sweep followed a symlink out of the profile"
        );
    }

    #[test]
    fn extraction_state_is_detectable() {
        let tmp = tempfile::tempdir().unwrap();
        let dest = tmp.path().join("natives");
        assert!(!NativeLibraryManager::looks_extracted(&dest));

        std::fs::create_dir_all(&dest).unwrap();
        assert!(!NativeLibraryManager::looks_extracted(&dest));

        std::fs::write(dest.join("readme.txt"), b"x").unwrap();
        assert!(
            !NativeLibraryManager::looks_extracted(&dest),
            "a non-library file does not count as extracted"
        );

        std::fs::write(dest.join("lwjgl.dll"), b"x").unwrap();
        assert!(NativeLibraryManager::looks_extracted(&dest));
    }

    #[test]
    fn destinations_with_spaces_and_unicode_work() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("n.jar");
        std::fs::write(&archive, build_zip(&[("lwjgl64.dll", b"x")])).unwrap();

        let dest = tmp.path().join("Jos\u{e9} M\u{fc}ller/My Profile/natives");
        let out = NativeLibraryManager::new()
            .extract_all(&[native(&archive, vec![])], &dest)
            .unwrap();

        assert_eq!(out.files.len(), 1);
        assert!(dest.join("lwjgl64.dll").exists());
    }
}
