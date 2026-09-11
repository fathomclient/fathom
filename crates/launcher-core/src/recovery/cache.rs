//! What "clear cache" removes, stated exactly.
//!
//! # The definition
//!
//! Something is cache **only if deleting it costs nothing but time the launcher
//! was going to spend anyway.** Not "it can be re-downloaded" — the 139 MB of
//! game artifacts can be re-downloaded, and deleting those costs a user their
//! evening on a slow connection.
//!
//! By that rule, exactly five things are cache:
//!
//! | Removed | Why it qualifies |
//! |---|---|
//! | `.partial` downloads | Incomplete by definition. Never valid, never loaded. |
//! | Cached remote metadata | Re-fetched on the next check; nothing reads it without being willing to fetch again. |
//! | Expired cached manifests | Already refused as stale before this ran. |
//! | The temporary directory | Scratch for one run. |
//! | Obsolete update staging | Unverified bytes for a version already installed, or a version nothing is going to install. |
//!
//! # What is never touched
//!
//! Enumerated because a "clear cache" that deletes a world is the kind of bug
//! that ends a project's reputation, and because the list below is the actual
//! test:
//!
//! - **worlds, screenshots, resource packs, `options.txt`** — the user's, and
//!   irreplaceable
//! - **profile configuration and metadata** — theirs, and small
//! - **accounts and credentials** — not on disk here at all; they are in the OS
//!   credential store, which this code has no reference to
//! - **user-supplied mod jars** — a jar a user chose is not disposable, even
//!   when a copy exists elsewhere. See §3.
//! - **the shared game artifacts** — re-downloadable, and expensive. Removing
//!   them is `repair`, a different operation with a different name and a
//!   different button.
//! - **the profile trash** — a deleted profile is a profile the user might want
//!   back; emptying it is `purge`, which is deliberate and per-entry.
//!
//! # 3. Why the mod store is not cache
//!
//! `<data>/mods/<2hex>/<sha1>.jar` is content-addressed and every jar in it can
//! in principle be re-acquired — so it looks like cache. It is not, for two
//! reasons.
//!
//! Most jars in it are **user-supplied**: the user picked a file off their disk
//! and the launcher copied it in. `mod-policies.md` §7 is explicit that we do
//! not redistribute third-party mods, so for many of them there is **no
//! authorized source to re-acquire from**. Deleting one destroys the only copy
//! the launcher has, and possibly the only copy the user has, if they have
//! since tidied their downloads folder.
//!
//! Second, a jar in the store is *referenced* by profile configuration. Removing
//! it turns a working profile into one with a missing mod. That is a change to
//! the user's setup, not a cleanup.
//!
//! # Idempotence
//!
//! Running this twice removes nothing the second time and reports zero. Every
//! step is "delete these files if present", which is naturally idempotent —
//! there is no move, no rename, and no state to get half-way through.

use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use crate::fs_safety;
use crate::paths::DataPaths;

/// One category of removable data, so the report can say what went.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind {
    /// An interrupted download. Never valid.
    PartialDownload,
    /// Remote metadata that is re-fetched on demand.
    CachedMetadata,
    /// Scratch space for a single run.
    TemporaryFile,
    /// Unverified update bytes that are no longer wanted.
    UpdateStaging,
    /// A stale natives extraction directory.
    ScratchNatives,
}

impl Kind {
    pub fn label(self) -> &'static str {
        match self {
            Kind::PartialDownload => "incomplete download",
            Kind::CachedMetadata => "cached metadata",
            Kind::TemporaryFile => "temporary file",
            Kind::UpdateStaging => "update staging file",
            Kind::ScratchNatives => "extracted native library",
        }
    }
}

/// What a clear did, or would do.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CacheReport {
    /// Every file, with its category and size.
    pub removed: Vec<(Kind, PathBuf, u64)>,
    /// Files we identified but could not remove, and why. Reported rather than
    /// swallowed: a file locked by another process is worth telling someone
    /// about.
    pub failed: Vec<(PathBuf, String)>,
    /// Whether this was a dry run.
    pub dry_run: bool,
}

impl CacheReport {
    pub fn bytes(&self) -> u64 {
        self.removed.iter().map(|(_, _, size)| size).sum()
    }

    pub fn count(&self) -> usize {
        self.removed.len()
    }

    pub fn is_empty(&self) -> bool {
        self.removed.is_empty()
    }

    /// A sentence for the interface.
    pub fn message(&self) -> String {
        if self.removed.is_empty() {
            return if self.failed.is_empty() {
                "There was nothing to clear.".to_owned()
            } else {
                format!(
                    "Nothing could be cleared; {} file(s) were in use.",
                    self.failed.len()
                )
            };
        }
        let verb = if self.dry_run { "Would free" } else { "Freed" };
        let mut text = format!(
            "{verb} {} from {} file(s).",
            crate::services::human_bytes(self.bytes()),
            self.count()
        );
        if !self.failed.is_empty() {
            use std::fmt::Write as _;
            let _ = write!(text, " {} were in use.", self.failed.len());
        }
        text
    }

    /// Counts per category, for a diagnostic report.
    pub fn by_kind(&self) -> Vec<(Kind, usize, u64)> {
        let mut out: Vec<(Kind, usize, u64)> = Vec::new();
        for (kind, _, size) in &self.removed {
            match out.iter_mut().find(|(k, _, _)| k == kind) {
                Some(entry) => {
                    entry.1 += 1;
                    entry.2 += size;
                }
                None => out.push((*kind, 1, *size)),
            }
        }
        out.sort_by_key(|(kind, _, _)| *kind);
        out
    }
}

/// How old a cached metadata file must be before it is considered stale.
///
/// A week. Long enough that a user who checks daily never re-downloads, short
/// enough that a document nobody has looked at in a month is not kept forever.
pub const METADATA_STALE_AFTER: Duration = Duration::from_secs(7 * 24 * 60 * 60);

/// Clear the cache.
///
/// `dry_run` computes the same answer without deleting, so the interface can
/// say what it is about to do. That is not decoration: "clear cache" is the
/// operation a user is most likely to click without reading, and being able to
/// show the list first is the difference between a considered action and a
/// hopeful one.
///
/// **The caller must hold the appropriate lease.** `recovery::RecoveryService`
/// takes `SharedInstallation` and `GlobalUpdate` before calling this, because a
/// download in progress writes `.partial` files that this would otherwise
/// delete out from under it.
pub fn clear(paths: &DataPaths, dry_run: bool) -> CacheReport {
    let mut report = CacheReport {
        dry_run,
        ..CacheReport::default()
    };

    // 1. Incomplete downloads, anywhere under the shared cache.
    //
    // These live beside the artifacts they are becoming, so the sweep goes
    // through the shared tree -- but it removes ONLY `.partial` files, never
    // the finished artifact next to one.
    sweep(
        &paths.shared_cache(),
        Kind::PartialDownload,
        &mut report,
        &|path| {
            path.extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| e.eq_ignore_ascii_case("partial"))
        },
    );

    // 2. Cached remote metadata, and 3. anything in it that has expired. One
    // sweep: the whole directory is re-fetchable, so age is not the test.
    sweep(
        &paths.metadata_cache_dir(),
        Kind::CachedMetadata,
        &mut report,
        &|_| true,
    );

    // 4. Temporary files.
    sweep(&paths.temp_dir(), Kind::TemporaryFile, &mut report, &|_| {
        true
    });

    // 5. Update staging. Unverified bytes by definition -- `update::install`
    //    re-verifies from disk before using any of it, so removing them costs
    //    at most one re-download of an update nobody has installed yet.
    let staging = crate::update::trust::UpdatePaths::new(paths.root()).staging();
    sweep(&staging, Kind::UpdateStaging, &mut report, &|_| true);

    report
}

/// Everything the cache currently holds, without removing it.
pub fn preview(paths: &DataPaths) -> CacheReport {
    clear(paths, true)
}

/// Remove matching files under `root`, recording each one.
///
/// Recursive, depth-bounded, and **never follows a link**: `symlink_metadata`
/// rather than `is_file`, so a junction placed in the cache directory is left
/// alone rather than descended into and emptied. We never create one, so one
/// found here did not come from us.
fn sweep(root: &Path, kind: Kind, report: &mut CacheReport, wanted: &dyn Fn(&Path) -> bool) {
    sweep_within(root, root, kind, report, wanted, 0);
}

/// Deep enough for the shared cache's `objects/<2hex>/` sharding plus room to
/// spare. Refusing to go further loses nothing: no layout of ours is deeper.
const MAX_DEPTH: u32 = 12;

fn sweep_within(
    root: &Path,
    dir: &Path,
    kind: Kind,
    report: &mut CacheReport,
    wanted: &dyn Fn(&Path) -> bool,
    depth: u32,
) {
    if depth > MAX_DEPTH {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();

        // Belt and braces against a path that somehow left the tree.
        if !fs_safety::is_lexically_inside(root, &path) {
            continue;
        }
        let Ok(meta) = std::fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            sweep_within(root, &path, kind, report, wanted, depth + 1);
            continue;
        }
        if !wanted(&path) {
            continue;
        }

        let size = meta.len();
        if report.dry_run {
            report.removed.push((kind, path, size));
            continue;
        }
        match std::fs::remove_file(&path) {
            Ok(()) => report.removed.push((kind, path, size)),
            Err(e) => report.failed.push((path, e.to_string())),
        }
    }
}

/// Whether a cached metadata file is old enough to be worth re-fetching.
///
/// Not used by `clear`, which removes the whole metadata directory, but used by
/// the startup scan to decide whether there is anything worth mentioning.
pub fn is_stale(path: &Path, now: SystemTime) -> bool {
    std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|modified| now.duration_since(modified).ok())
        .is_some_and(|age| age > METADATA_STALE_AFTER)
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

    struct Fixture {
        _tmp: tempfile::TempDir,
        paths: DataPaths,
    }

    impl Fixture {
        fn new() -> Self {
            let tmp = tempfile::tempdir().expect("tempdir");
            let paths = DataPaths::rooted_at(tmp.path());
            paths.ensure().expect("ensure");
            Self { _tmp: tmp, paths }
        }

        fn write(&self, relative: &str, contents: &[u8]) -> PathBuf {
            let path = self.paths.root().join(relative);
            std::fs::create_dir_all(path.parent().expect("a parent")).expect("mkdir");
            std::fs::write(&path, contents).expect("write");
            path
        }
    }

    /// The whole point of the module, as one test: everything a user owns
    /// survives, and only disposable things go.
    #[test]
    fn clearing_the_cache_never_touches_anything_the_user_owns() {
        let f = Fixture::new();

        // Things a user would be upset to lose.
        let precious = [
            "profiles/main/minecraft/saves/My World/level.dat",
            "profiles/main/minecraft/screenshots/2026-08-27.png",
            "profiles/main/minecraft/resourcepacks/faithful.zip",
            "profiles/main/minecraft/options.txt",
            "profiles/main/profile.json",
            "profiles/main/minecraft/mods/optifine.jar",
            "settings.json",
            "mods/ab/abcdef.jar",
            "trash/deleted-profile/profile.json",
            // Shared artifacts: re-downloadable, expensive, and NOT cache.
            "shared/libraries/org/lwjgl/lwjgl.jar",
            "shared/assets/objects/f6/f6ad102b.png",
            "shared/versions/1.8.9/1.8.9.jar",
        ];
        for path in precious {
            f.write(path, b"do not touch");
        }

        // Things that are cache.
        let disposable = [
            "shared/libraries/org/lwjgl/lwjgl.jar.partial",
            "shared/versions/1.8.9/1.8.9.jar.partial",
            "cache/metadata/version_manifest.json",
            "tmp/scratch-1234",
            "update/staging/launcher-0.3.0.part",
        ];
        for path in disposable {
            f.write(path, b"disposable");
        }

        let report = clear(&f.paths, false);

        for path in precious {
            assert!(
                f.paths.root().join(path).is_file(),
                "clear cache removed {path}, which belongs to the user"
            );
        }
        for path in disposable {
            assert!(
                !f.paths.root().join(path).exists(),
                "{path} is cache and should have been removed"
            );
        }
        assert_eq!(report.count(), disposable.len());
    }

    /// A `.partial` sits next to the artifact it is becoming. Removing the
    /// wrong one of those two would delete a verified file.
    #[test]
    fn a_partial_is_removed_but_the_finished_artifact_beside_it_is_not() {
        let f = Fixture::new();
        let real = f.write("shared/libraries/a/lib.jar", b"verified bytes");
        let partial = f.write("shared/libraries/a/lib.jar.partial", b"half a file");

        clear(&f.paths, false);

        assert!(real.is_file(), "the verified artifact must survive");
        assert!(!partial.exists(), "the partial must not");
        assert_eq!(std::fs::read(&real).unwrap(), b"verified bytes");
    }

    #[test]
    fn a_dry_run_reports_the_same_files_and_deletes_none_of_them() {
        let f = Fixture::new();
        let partial = f.write("shared/libraries/a/lib.jar.partial", b"xxxx");
        f.write("cache/metadata/manifest.json", b"yy");

        let preview = preview(&f.paths);
        assert_eq!(preview.count(), 2);
        assert!(preview.dry_run);
        assert!(partial.is_file(), "a preview must not delete anything");
        assert!(preview.message().contains("Would free"));

        let real = clear(&f.paths, false);
        assert_eq!(
            real.count(),
            preview.count(),
            "the preview must be accurate"
        );
        assert_eq!(real.bytes(), preview.bytes());
        assert!(!partial.exists());
        assert!(real.message().contains("Freed"));
    }

    /// Repairs and clears have to be safe to run twice.
    #[test]
    fn clearing_twice_is_idempotent_and_reports_nothing_the_second_time() {
        let f = Fixture::new();
        f.write("shared/libraries/a/lib.jar.partial", b"xxxx");
        f.write("tmp/scratch", b"yy");

        let first = clear(&f.paths, false);
        assert_eq!(first.count(), 2);

        let second = clear(&f.paths, false);
        assert!(second.is_empty());
        assert_eq!(second.bytes(), 0);
        assert_eq!(second.message(), "There was nothing to clear.");
    }

    #[test]
    fn an_empty_cache_is_not_an_error() {
        let f = Fixture::new();
        let report = clear(&f.paths, false);
        assert!(report.is_empty());
        assert!(report.failed.is_empty());
    }

    /// The directories may not exist at all on a fresh install.
    #[test]
    fn missing_cache_directories_are_not_an_error() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path().join("never-created"));
        let report = clear(&paths, false);
        assert!(report.is_empty());
        assert!(report.failed.is_empty());
    }

    /// A junction or symlink placed in a cache directory must not be followed:
    /// the sweep would otherwise delete files outside the tree entirely.
    #[cfg(unix)]
    #[test]
    fn the_sweep_does_not_follow_a_link_out_of_the_cache() {
        let f = Fixture::new();
        let outside = f.paths.root().parent().unwrap().join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("precious.json"), b"not yours").unwrap();

        std::os::unix::fs::symlink(&outside, f.paths.metadata_cache_dir().join("link")).unwrap();

        clear(&f.paths, false);
        assert!(
            outside.join("precious.json").is_file(),
            "the sweep followed a link out of the cache"
        );
    }

    #[test]
    fn the_report_groups_by_category() {
        let f = Fixture::new();
        f.write("shared/a.jar.partial", b"1234");
        f.write("shared/b.jar.partial", b"5678");
        f.write("cache/metadata/m.json", b"9");

        let report = clear(&f.paths, false);
        let by_kind = report.by_kind();
        let partials = by_kind
            .iter()
            .find(|(k, _, _)| *k == Kind::PartialDownload)
            .expect("partials");
        assert_eq!(partials.1, 2);
        assert_eq!(partials.2, 8);
    }

    #[test]
    fn staleness_is_measured_against_a_supplied_clock() {
        let f = Fixture::new();
        let path = f.write("cache/metadata/m.json", b"x");

        assert!(!is_stale(&path, SystemTime::now()));
        assert!(is_stale(
            &path,
            SystemTime::now() + METADATA_STALE_AFTER + Duration::from_secs(60)
        ));
    }
}
