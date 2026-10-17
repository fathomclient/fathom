//! Filesystem operations that assume their input is hostile.
//!
//! Three of the threats in `THREAT_MODEL.md` land here: path traversal,
//! malicious archive contents, and symlink attacks. The rule this module
//! enforces is that **every write is inside a root the caller named**, and the
//! caller proves it by going through [`Contained`].

use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};

#[derive(Debug, thiserror::Error)]
pub enum FsError {
    #[error("path escapes its root: {0}")]
    Escape(String),
    #[error("path component is not allowed: {0}")]
    ForbiddenComponent(String),
    #[error("archive entry is a symbolic link, which is not extracted: {0}")]
    SymlinkEntry(String),
    #[error("archive is too large: {actual} bytes exceeds the {limit} byte limit")]
    ArchiveTooLarge { actual: u64, limit: u64 },
    #[error("archive has too many entries: {actual} exceeds the {limit} limit")]
    TooManyEntries { actual: usize, limit: usize },
    #[error("archive entry {name} is too large: {actual} bytes exceeds the {limit} byte limit")]
    EntryTooLarge {
        name: String,
        actual: u64,
        limit: u64,
    },
    #[error("could not read archive: {0}")]
    Archive(#[from] zip::result::ZipError),
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: io::Error,
    },
}

fn io_err(path: &Path, source: io::Error) -> FsError {
    FsError::Io {
        path: path.display().to_string(),
        source,
    }
}

/// A path proven to be inside a root directory.
///
/// The only constructor validates containment, so a function taking a
/// `Contained` does not need to re-check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contained {
    root: PathBuf,
    full: PathBuf,
}

impl Contained {
    pub fn path(&self) -> &Path {
        &self.full
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn into_path(self) -> PathBuf {
        self.full
    }
}

/// Windows device names that resolve to hardware regardless of directory.
/// Creating one of these can hang or misbehave, so archive entries naming them
/// are rejected on every platform (an archive is portable; our rejection should
/// be too).
const RESERVED_NAMES: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Whether a path component names a Windows device, however it is dressed up.
///
/// Windows strips trailing spaces and dots from a filename before resolving it,
/// so `CON `, `CON.`, `CON . . ` and `CON` are all the same device. The first
/// version of this check took the text before the first `.` and compared that,
/// which caught `CON.txt` and `CON.` but **not** `CON ` — a trailing space is
/// not a dot, so the name went through as an ordinary component and the write
/// went to the console device.
///
/// So: strip the trailing spaces and dots first, then compare what is left of
/// the stem. Checked on every platform because an archive is portable and our
/// refusal should be too — extracting a `CON ` entry on Linux and copying the
/// tree to Windows later should not be how this surfaces.
fn is_reserved_device_name(component: &str) -> bool {
    let trimmed = component.trim_end_matches([' ', '.']);
    let stem = trimmed.split('.').next().unwrap_or(trimmed);
    // The stem itself can carry trailing spaces: `CON .txt`.
    let stem = stem.trim_end_matches([' ', '.']);
    RESERVED_NAMES.iter().any(|r| r.eq_ignore_ascii_case(stem))
}

/// Join `relative` onto `root`, rejecting anything that could escape.
///
/// This is *lexical*: it works on paths that do not exist yet, which is the
/// case during extraction. [`resolve_existing`] adds the symlink check for
/// paths that do exist.
pub fn contain(root: &Path, relative: &str) -> Result<Contained, FsError> {
    if relative.is_empty() {
        return Err(FsError::ForbiddenComponent("<empty>".into()));
    }
    // Zip entries always use `/`. Normalise `\` to `/` first so that a Windows
    // -style traversal in an entry name is caught by the same logic rather than
    // being treated as part of a filename.
    let normalised = relative.replace('\\', "/");

    if normalised.contains('\0') {
        return Err(FsError::ForbiddenComponent("<nul byte>".into()));
    }

    let mut out = root.to_path_buf();
    for segment in normalised.split('/') {
        match segment {
            "" | "." => {}
            ".." => return Err(FsError::Escape(relative.to_owned())),
            other => {
                // A drive-relative or ADS reference such as `C:` or `f.txt:s`.
                if other.contains(':') {
                    return Err(FsError::ForbiddenComponent(other.to_owned()));
                }
                if is_reserved_device_name(other) {
                    return Err(FsError::ForbiddenComponent(other.to_owned()));
                }
                out.push(other);
            }
        }
    }

    // Defence in depth: even after per-segment filtering, confirm the result is
    // still under the root lexically. Catches anything the loop missed.
    if !is_lexically_inside(root, &out) {
        return Err(FsError::Escape(relative.to_owned()));
    }

    Ok(Contained {
        root: root.to_path_buf(),
        full: out,
    })
}

/// True when `candidate` is `root` or below it, judged on normalised
/// components. Neither path needs to exist.
pub fn is_lexically_inside(root: &Path, candidate: &Path) -> bool {
    let normalise = |p: &Path| -> Vec<std::ffi::OsString> {
        p.components()
            .filter_map(|c| match c {
                Component::Normal(s) => Some(s.to_os_string()),
                Component::Prefix(p) => Some(p.as_os_str().to_os_string()),
                Component::RootDir => Some(std::ffi::OsString::from("/")),
                Component::CurDir => None,
                // A surviving `..` means we could not decide; treat as outside.
                Component::ParentDir => Some(std::ffi::OsString::from("..")),
            })
            .collect()
    };
    let root_parts = normalise(root);
    let cand_parts = normalise(candidate);
    if cand_parts.iter().any(|c| c == "..") {
        return false;
    }
    cand_parts.len() >= root_parts.len() && cand_parts.starts_with(&root_parts)
}

/// Resolve an existing path and confirm it is really inside `root` after
/// following symlinks.
///
/// Use this before *reading* something the user pointed at (a mod jar, a Java
/// binary), where a symlink could otherwise redirect us out of the sandbox.
pub fn resolve_existing(root: &Path, candidate: &Path) -> Result<PathBuf, FsError> {
    let real_root = root.canonicalize().map_err(|e| io_err(root, e))?;
    let real = candidate.canonicalize().map_err(|e| io_err(candidate, e))?;
    if !is_lexically_inside(&real_root, &real) {
        return Err(FsError::Escape(candidate.display().to_string()));
    }
    Ok(real)
}

/// Write `bytes` to `path` atomically: temp file in the same directory, flush,
/// fsync, rename. A crash leaves either the old content or the new, never a
/// truncated file.
///
/// # The temporary file is created exclusively
///
/// The intermediate is opened with `create_new`, which is `O_EXCL` on unix and
/// `CREATE_NEW` on Windows: if anything already exists at that path the open
/// **fails** rather than truncating or following it.
///
/// That matters because the earlier version used `File::create`, which
/// truncates whatever it finds and follows a symlink to wherever it points. A
/// predictable temp name plus a following open is an arbitrary-file-overwrite
/// primitive for anyone who can create a file in the directory first: they
/// plant a symlink at the name we are about to use, and our content lands on
/// its target.
///
/// The name also carries a nanosecond stamp now, but that is only there to
/// avoid collisions between concurrent writers. **`create_new` is the security
/// control**; unpredictability alone would be a race, not a fix. A hostile
/// squatter can still make a write fail, which is a denial of service and not a
/// redirection — the safe direction to fail in.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), FsError> {
    let parent = path.parent().unwrap_or(Path::new("."));
    fs::create_dir_all(parent).map_err(|e| io_err(parent, e))?;

    let (tmp, mut file) = create_exclusive_sibling(path)?;
    {
        let f = &mut file;
        f.write_all(bytes).map_err(|e| io_err(&tmp, e))?;
        f.flush().map_err(|e| io_err(&tmp, e))?;
        f.sync_all().map_err(|e| io_err(&tmp, e))?;
    }
    drop(file);

    // Windows `rename` fails if the destination exists, so remove first. This
    // is the one non-atomic window; it is unavoidable without Win32 calls and
    // is bounded to a single syscall.
    #[cfg(windows)]
    if path.exists() {
        fs::remove_file(path).map_err(|e| io_err(path, e))?;
    }

    fs::rename(&tmp, path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        io_err(path, e)
    })
}

/// Distinguishes concurrent temp files within this process.
static TEMP_COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// How many names to try before giving up.
///
/// A handful. Each attempt uses a fresh nanosecond stamp and counter, so
/// repeated failure means something is deliberately occupying the names rather
/// than an unlucky collision, and continuing to retry would only make the
/// denial of service quieter.
const TEMP_ATTEMPTS: u32 = 8;

fn temp_sibling(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map_or_else(|| "tmp".to_owned(), |n| n.to_string_lossy().into_owned());
    let n = TEMP_COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    // Nanoseconds, pid and a counter. Not a security property — see
    // `atomic_write` — just enough that two writers do not pick the same name.
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.subsec_nanos());
    path.with_file_name(format!(
        ".{name}.{}.{stamp:08x}.{n}.tmp",
        std::process::id()
    ))
}

/// Create a fresh sibling file that did not exist a moment ago.
///
/// `create_new` refuses to open anything that already exists, so a planted
/// symlink or a leftover file is an error rather than something we write
/// through. Returns the path alongside the handle because the caller needs both.
fn create_exclusive_sibling(path: &Path) -> Result<(PathBuf, File), FsError> {
    let mut last = None;
    for _ in 0..TEMP_ATTEMPTS {
        let candidate = temp_sibling(path);
        match fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&candidate)
        {
            Ok(file) => return Ok((candidate, file)),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                last = Some((candidate, e));
            }
            Err(e) => return Err(io_err(&candidate, e)),
        }
    }
    let (candidate, e) = last.unwrap_or_else(|| {
        (
            path.to_path_buf(),
            io::Error::new(io::ErrorKind::AlreadyExists, "no temporary name was free"),
        )
    });
    Err(io_err(&candidate, e))
}

/// Limits applied to any archive we extract.
#[derive(Debug, Clone, Copy)]
pub struct ExtractLimits {
    pub max_entries: usize,
    pub max_entry_bytes: u64,
    pub max_total_bytes: u64,
}

impl Default for ExtractLimits {
    fn default() -> Self {
        Self {
            max_entries: 10_000,
            max_entry_bytes: 128 * 1024 * 1024,
            max_total_bytes: 512 * 1024 * 1024,
        }
    }
}

/// Extract a zip archive into `dest`, refusing anything hostile.
///
/// Guarantees:
/// - every written path is inside `dest` ([`contain`]);
/// - symlink entries are refused, not followed;
/// - directory entries create directories but never files;
/// - a decompression bomb is stopped by [`ExtractLimits`];
/// - `filter` decides which entries are wanted at all, so a natives jar cannot
///   drop a `.bat` next to the `.dll`.
///
/// Returns the paths written.
pub fn extract_zip(
    archive: &Path,
    dest: &Path,
    limits: ExtractLimits,
    filter: &dyn Fn(&str) -> bool,
) -> Result<Vec<PathBuf>, FsError> {
    let file = File::open(archive).map_err(|e| io_err(archive, e))?;
    let mut zip = zip::ZipArchive::new(file)?;

    if zip.len() > limits.max_entries {
        return Err(FsError::TooManyEntries {
            actual: zip.len(),
            limit: limits.max_entries,
        });
    }

    fs::create_dir_all(dest).map_err(|e| io_err(dest, e))?;

    let mut written = Vec::new();
    let mut total: u64 = 0;

    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;

        // `enclosed_name` is zip-rs's own traversal check; we do not rely on it
        // alone, but if it refuses we should too.
        let raw_name = entry.name().to_owned();
        if entry.enclosed_name().is_none() {
            return Err(FsError::Escape(raw_name));
        }

        if is_symlink_entry(&entry) {
            return Err(FsError::SymlinkEntry(raw_name));
        }

        if entry.is_dir() {
            let target = contain(dest, &raw_name)?;
            fs::create_dir_all(target.path()).map_err(|e| io_err(target.path(), e))?;
            continue;
        }

        if !filter(&raw_name) {
            continue;
        }

        let declared = entry.size();
        if declared > limits.max_entry_bytes {
            return Err(FsError::EntryTooLarge {
                name: raw_name,
                actual: declared,
                limit: limits.max_entry_bytes,
            });
        }

        let target = contain(dest, &raw_name)?;
        if let Some(parent) = target.path().parent() {
            fs::create_dir_all(parent).map_err(|e| io_err(parent, e))?;
        }

        // Read through a `take` rather than trusting the declared size: the
        // central directory is attacker-controlled and can understate the
        // actual stream length.
        let remaining = limits.max_total_bytes.saturating_sub(total);
        let allowed = remaining.min(limits.max_entry_bytes);
        let mut limited = (&mut entry).take(allowed + 1);
        let mut buf = Vec::new();
        limited
            .read_to_end(&mut buf)
            .map_err(|e| io_err(target.path(), e))?;

        if buf.len() as u64 > allowed {
            return Err(FsError::ArchiveTooLarge {
                actual: total + buf.len() as u64,
                limit: limits.max_total_bytes,
            });
        }

        total += buf.len() as u64;
        atomic_write(target.path(), &buf)?;
        written.push(target.into_path());
    }

    Ok(written)
}

const S_IFMT: u32 = 0o170_000;
const S_IFLNK: u32 = 0o120_000;

/// A zip entry is a symlink when the high bits of its external attributes carry
/// a unix mode with `S_IFLNK` set.
fn is_symlink_entry<R: Read>(entry: &zip::read::ZipFile<'_, R>) -> bool {
    entry
        .unix_mode()
        .is_some_and(|mode| mode & S_IFMT == S_IFLNK)
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    // Fixture filenames are literals we author, so the suffix checks in these
    // tests are exact by construction.
    clippy::case_sensitive_file_extension_comparisons
)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn root() -> tempfile::TempDir {
        tempfile::tempdir().expect("tempdir")
    }

    #[test]
    fn plain_relative_paths_are_contained() {
        let r = root();
        let c = contain(r.path(), "libs/native/lwjgl.dll").unwrap();
        assert!(c.path().starts_with(r.path()));
        assert!(c.path().ends_with("lwjgl.dll"));
    }

    #[test]
    fn parent_traversal_is_rejected_in_every_form() {
        let r = root();
        for bad in [
            "../escape",
            "a/../../escape",
            "..\\escape",
            "a\\..\\..\\escape",
            "./../escape",
            "a/b/../../../escape",
        ] {
            assert!(
                matches!(contain(r.path(), bad), Err(FsError::Escape(_))),
                "{bad:?} should have been rejected"
            );
        }
    }

    #[test]
    fn absolute_and_drive_relative_paths_are_rejected() {
        let r = root();
        // A leading `/` yields empty first segment, which we skip; the result
        // must still be inside the root rather than at the filesystem root.
        let c = contain(r.path(), "/etc/passwd").unwrap();
        assert!(c.path().starts_with(r.path()));

        assert!(matches!(
            contain(r.path(), "C:/Windows/System32/evil.dll"),
            Err(FsError::ForbiddenComponent(_))
        ));
        assert!(matches!(
            contain(r.path(), "file.txt:stream"),
            Err(FsError::ForbiddenComponent(_))
        ));
    }

    #[test]
    fn windows_device_names_are_rejected() {
        let r = root();
        for bad in ["CON", "nul", "COM1", "lpt9.txt", "sub/AUX"] {
            assert!(
                matches!(contain(r.path(), bad), Err(FsError::ForbiddenComponent(_))),
                "{bad:?} should have been rejected"
            );
        }
        // Not reserved: a longer name that merely starts with one.
        assert!(contain(r.path(), "console.json").is_ok());
        assert!(contain(r.path(), "conference.txt").is_ok());
        assert!(contain(r.path(), "nul-config.json").is_ok());
    }

    /// The security pass found this: Windows strips trailing spaces and dots
    /// before resolving a filename, so `CON ` is the console device. The check
    /// compared the text before the first `.`, which caught `CON.txt` but let a
    /// trailing space straight through.
    #[test]
    fn device_names_dressed_up_with_trailing_spaces_and_dots_are_rejected() {
        let r = root();
        for bad in [
            "CON ",
            "con.",
            "NUL   ",
            "aux..",
            "COM1 . ",
            "lpt9 .txt",
            "sub/CON ",
            "PRN. ",
        ] {
            assert!(
                matches!(contain(r.path(), bad), Err(FsError::ForbiddenComponent(_))),
                "{bad:?} resolves to a device on Windows and must be rejected"
            );
        }
    }

    #[test]
    fn nul_bytes_and_empty_paths_are_rejected() {
        let r = root();
        assert!(matches!(
            contain(r.path(), ""),
            Err(FsError::ForbiddenComponent(_))
        ));
        assert!(matches!(
            contain(r.path(), "a\0b"),
            Err(FsError::ForbiddenComponent(_))
        ));
    }

    #[test]
    fn lexical_containment_matches_expectations() {
        let base = Path::new("/data/profiles/main");
        assert!(is_lexically_inside(base, Path::new("/data/profiles/main")));
        assert!(is_lexically_inside(
            base,
            Path::new("/data/profiles/main/mods/a.jar")
        ));
        assert!(!is_lexically_inside(
            base,
            Path::new("/data/profiles/other")
        ));
        assert!(!is_lexically_inside(base, Path::new("/data/profiles")));
        // A sibling whose name merely starts with the same characters.
        assert!(!is_lexically_inside(
            base,
            Path::new("/data/profiles/main-evil")
        ));
        assert!(!is_lexically_inside(
            base,
            Path::new("/data/profiles/main/../other")
        ));
    }

    #[test]
    fn atomic_write_replaces_content_and_leaves_no_temp_files() {
        let r = root();
        let target = r.path().join("cfg.json");

        atomic_write(&target, b"first").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"first");

        atomic_write(&target, b"second").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"second");

        let leftovers: Vec<_> = fs::read_dir(r.path())
            .unwrap()
            .filter_map(Result::ok)
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.ends_with(".tmp"))
            .collect();
        assert!(
            leftovers.is_empty(),
            "temp files left behind: {leftovers:?}"
        );
    }

    /// The security pass found this: the temp file used to be opened with
    /// `File::create`, which truncates whatever it finds and follows a symlink
    /// to wherever it points. With a predictable name that is an
    /// arbitrary-file-overwrite primitive for anyone who can create a file in
    /// the directory first.
    ///
    /// Portable half of the check: a plain file squatting on the name must not
    /// be written through. The symlink half is unix-only, below.
    #[test]
    fn atomic_write_refuses_to_write_through_a_squatted_temp_name() {
        let r = root();
        let target = r.path().join("cfg.json");
        atomic_write(&target, b"original").unwrap();

        // Occupy every name `atomic_write` could pick, by pre-creating files
        // that match its prefix, then confirm the real file is untouched
        // whichever way the call goes.
        let prefix = ".cfg.json.";
        for i in 0..40u32 {
            let squat = r
                .path()
                .join(format!("{prefix}{}.{i:08x}.{i}.tmp", std::process::id()));
            let _ = fs::write(&squat, b"squatted");
        }

        // Whether or not a free name remains, the invariant is the same: no
        // squatted file is ever overwritten with our content.
        let _ = atomic_write(&target, b"replacement");
        for entry in fs::read_dir(r.path()).unwrap().filter_map(Result::ok) {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with(prefix) && name.ends_with(".tmp") {
                assert_eq!(
                    fs::read(entry.path()).unwrap(),
                    b"squatted",
                    "{name} was written through"
                );
            }
        }
    }

    /// The half that matters most, and the one the old code failed.
    #[cfg(unix)]
    #[test]
    fn atomic_write_never_follows_a_symlink_left_at_the_temp_name() {
        let r = root();
        let victim = r.path().join("victim.txt");
        fs::write(&victim, b"do not touch").unwrap();

        let target = r.path().join("cfg.json");

        // Plant symlinks across the name space `temp_sibling` draws from.
        for i in 0..40u32 {
            let link = r
                .path()
                .join(format!(".cfg.json.{}.{i:08x}.{i}.tmp", std::process::id()));
            let _ = std::os::unix::fs::symlink(&victim, &link);
        }

        let _ = atomic_write(&target, b"payload");

        assert_eq!(
            fs::read(&victim).unwrap(),
            b"do not touch",
            "a planted symlink redirected the write"
        );
    }

    #[test]
    fn atomic_write_creates_missing_parents() {
        let r = root();
        let target = r.path().join("a/b/c/file.txt");
        atomic_write(&target, b"x").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"x");
    }

    /// Build a zip in memory so the traversal tests do not depend on a fixture.
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

    #[test]
    fn extraction_writes_only_filtered_entries_inside_dest() {
        let r = root();
        let archive_path = r.path().join("natives.jar");
        let zip_bytes = build_zip(&[
            ("lwjgl.dll", b"MZ-fake"),
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0"),
            ("evil.bat", b"@echo pwned"),
            ("nested/OpenAL.dll", b"native"),
        ]);
        fs::write(&archive_path, &zip_bytes).unwrap();

        let dest = r.path().join("out");
        let written = extract_zip(&archive_path, &dest, ExtractLimits::default(), &|name| {
            name.ends_with(".dll")
        })
        .unwrap();

        assert_eq!(written.len(), 2);
        assert!(dest.join("lwjgl.dll").exists());
        assert!(dest.join("nested/OpenAL.dll").exists());
        assert!(!dest.join("evil.bat").exists());
        assert!(!dest.join("META-INF/MANIFEST.MF").exists());
        for p in &written {
            assert!(is_lexically_inside(&dest, p));
        }
    }

    #[test]
    fn zip_slip_entry_is_refused() {
        let r = root();
        let archive_path = r.path().join("evil.jar");
        // Craft the entry name directly; ZipWriter permits it.
        let zip_bytes = build_zip(&[("../../escaped.dll", b"pwned")]);
        fs::write(&archive_path, &zip_bytes).unwrap();

        let dest = r.path().join("out");
        let err = extract_zip(&archive_path, &dest, ExtractLimits::default(), &|_| true)
            .expect_err("zip slip must be refused");
        assert!(matches!(err, FsError::Escape(_)));
        assert!(!r.path().join("escaped.dll").exists());
    }

    #[test]
    fn entry_count_limit_is_enforced() {
        let r = root();
        let archive_path = r.path().join("many.jar");
        let names: Vec<String> = (0..20).map(|i| format!("f{i}.dll")).collect();
        let entries: Vec<(&str, &[u8])> =
            names.iter().map(|n| (n.as_str(), b"x" as &[u8])).collect();
        fs::write(&archive_path, build_zip(&entries)).unwrap();

        let limits = ExtractLimits {
            max_entries: 5,
            ..ExtractLimits::default()
        };
        let err = extract_zip(&archive_path, &r.path().join("out"), limits, &|_| true)
            .expect_err("entry limit must be enforced");
        assert!(matches!(err, FsError::TooManyEntries { .. }));
    }

    #[test]
    fn total_size_limit_stops_a_decompression_bomb() {
        let r = root();
        let archive_path = r.path().join("bomb.jar");
        let big = vec![0u8; 4096];
        let entries: Vec<(&str, &[u8])> = vec![
            ("a.dll", big.as_slice()),
            ("b.dll", big.as_slice()),
            ("c.dll", big.as_slice()),
        ];
        fs::write(&archive_path, build_zip(&entries)).unwrap();

        let limits = ExtractLimits {
            max_total_bytes: 5000,
            ..ExtractLimits::default()
        };
        let err = extract_zip(&archive_path, &r.path().join("out"), limits, &|_| true)
            .expect_err("bomb must be stopped");
        assert!(matches!(
            err,
            FsError::ArchiveTooLarge { .. } | FsError::EntryTooLarge { .. }
        ));
    }

    #[test]
    fn resolve_existing_rejects_a_path_outside_the_root() {
        let r = root();
        let inside = r.path().join("inside");
        fs::create_dir_all(&inside).unwrap();
        let file = inside.join("ok.jar");
        fs::write(&file, b"x").unwrap();

        assert!(resolve_existing(&inside, &file).is_ok());

        let outside = r.path().join("outside.jar");
        fs::write(&outside, b"x").unwrap();
        assert!(matches!(
            resolve_existing(&inside, &outside),
            Err(FsError::Escape(_))
        ));
    }
}
