//! The one place a file is fetched and put on disk.
//!
//! Everything the brief asks of a downloader lives here, and nothing else in the
//! installer opens a socket or writes an artifact:
//!
//! - HTTPS only, via the network allowlist (redirects re-validated per hop, in
//!   [`crate::network::http`]);
//! - streaming writes into a `.partial` file, so a large artifact never has to
//!   fit in memory and a half-written file is never mistaken for a complete one;
//! - hash and declared-size verification **before** the file takes its real
//!   name;
//! - atomic finalisation by rename;
//! - bounded retries with backoff, only for failures that could plausibly
//!   succeed on a second attempt;
//! - a size ceiling per artifact;
//! - progress and cancellation.
//!
//! The rule that matters: **a failed hash fails the install.** A corrupt
//! artifact is discarded, its `.partial` removed, and the error propagates. It
//! is never quietly kept, and never renamed into place "to be fixed later".

use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use sha1::{Digest, Sha1};

use crate::fs_safety::{self, FsError};
use crate::network::{HttpClient, NetworkError, PerActionGrant, RequestDescriptor};
use crate::tasks::Cancel;

/// Hard ceiling on any single artifact.
///
/// The 1.8.9 client jar is ~8 MB and the largest asset object is far smaller.
/// This is generous rather than tight, and exists so a hostile or broken
/// endpoint cannot fill the disk.
pub const MAX_ARTIFACT_BYTES: u64 = 512 * 1024 * 1024;

/// How many times to retry a transfer that failed for a transient reason.
pub const MAX_ATTEMPTS: u32 = 3;

/// Base backoff. Doubles each attempt.
pub const RETRY_BACKOFF: Duration = Duration::from_millis(400);

#[derive(Debug, thiserror::Error)]
pub enum DownloadError {
    #[error(transparent)]
    Network(#[from] NetworkError),
    #[error(transparent)]
    Fs(#[from] FsError),
    #[error(
        "{name}: content does not match the published hash. Expected {expected}, got {actual}. \
         The file was discarded."
    )]
    HashMismatch {
        name: String,
        expected: String,
        actual: String,
    },
    #[error("{name}: expected {expected} bytes, received {actual}. The file was discarded.")]
    SizeMismatch {
        name: String,
        expected: u64,
        actual: u64,
    },
    #[error("{name}: exceeded the {limit} byte ceiling for a single artifact")]
    TooLarge { name: String, limit: u64 },
    #[error("cancelled")]
    Cancelled,
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("{name}: gave up after {attempts} attempts. Last error: {last}")]
    Exhausted {
        name: String,
        attempts: u32,
        last: String,
    },
}

impl DownloadError {
    /// Whether retrying could plausibly help.
    ///
    /// A hash mismatch is deliberately **not** retryable at this level: if the
    /// bytes we got do not match the published hash, asking again is unlikely to
    /// help and might mask a substitution. The installer surfaces it instead.
    fn is_transient(&self) -> bool {
        match self {
            DownloadError::Network(e) => matches!(
                e,
                NetworkError::Transport { .. }
                    | NetworkError::Io { .. }
                    | NetworkError::Status { .. }
            ),
            DownloadError::Io { .. } => true,
            _ => false,
        }
    }
}

/// What we expect a file to be. Both fields come from Mojang's metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Expectation {
    /// Lowercase hex SHA-1, as Mojang publishes it.
    pub sha1: String,
    /// Declared size. `None` when the publisher did not state one.
    pub size: Option<u64>,
}

impl Expectation {
    pub fn new(sha1: impl Into<String>, size: Option<u64>) -> Self {
        Self {
            sha1: sha1.into(),
            size,
        }
    }

    /// The ceiling to enforce on this transfer.
    fn limit(&self) -> u64 {
        self.size
            // A little slack for a server that reports approximately, but never
            // more than the global ceiling.
            .map_or(MAX_ARTIFACT_BYTES, |s| {
                s.saturating_add(4096).min(MAX_ARTIFACT_BYTES)
            })
    }
}

/// One file to fetch.
#[derive(Debug, Clone)]
pub struct DownloadRequest {
    /// Short name for progress and error messages.
    pub name: String,
    pub request: RequestDescriptor,
    pub expect: Expectation,
    /// Final resting place. Must already be inside a launcher-owned root; the
    /// caller proves that by constructing it through
    /// [`crate::install::cache::ArtifactCache`].
    pub target: PathBuf,
}

/// What happened to one file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// Already present and verified. Nothing was transferred.
    AlreadyPresent,
    Downloaded {
        bytes: u64,
        attempts: u32,
    },
}

/// Progress during a batch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Progress {
    pub completed: usize,
    pub total: usize,
    pub current: String,
    pub bytes_transferred: u64,
}

/// Fetches files. Holds no state beyond its HTTP client.
#[derive(Debug, Clone)]
pub struct DownloadManager {
    http: HttpClient,
}

impl DownloadManager {
    pub fn new(http: HttpClient) -> Self {
        Self { http }
    }

    pub fn http(&self) -> &HttpClient {
        &self.http
    }

    /// Fetch one file, verify it, and move it into place atomically.
    ///
    /// Skips the transfer entirely when the target already exists and hashes
    /// correctly, which is what makes install and repair the same operation.
    pub fn fetch(
        &self,
        item: &DownloadRequest,
        grant: Option<&PerActionGrant>,
        cancel: &Cancel,
    ) -> Result<Outcome, DownloadError> {
        if verify_file(&item.target, &item.expect.sha1) {
            return Ok(Outcome::AlreadyPresent);
        }
        if cancel.is_cancelled() {
            return Err(DownloadError::Cancelled);
        }

        let mut last: Option<DownloadError> = None;
        for attempt in 1..=MAX_ATTEMPTS {
            match self.attempt(item, grant, cancel) {
                Ok(bytes) => {
                    return Ok(Outcome::Downloaded {
                        bytes,
                        attempts: attempt,
                    })
                }
                Err(DownloadError::Cancelled) => return Err(DownloadError::Cancelled),
                Err(e) => {
                    if !e.is_transient() || attempt == MAX_ATTEMPTS {
                        return Err(e);
                    }
                    tracing::warn!(
                        artifact = %item.name,
                        attempt,
                        error = %e,
                        "transfer failed, retrying"
                    );
                    last = Some(e);
                    // Backoff, but stay responsive to cancellation.
                    let wait = RETRY_BACKOFF * 2u32.saturating_pow(attempt - 1);
                    if !cancel.sleep_interruptibly(wait) {
                        return Err(DownloadError::Cancelled);
                    }
                }
            }
        }

        Err(DownloadError::Exhausted {
            name: item.name.clone(),
            attempts: MAX_ATTEMPTS,
            last: last.map_or_else(|| "unknown".to_owned(), |e| e.to_string()),
        })
    }

    /// One transfer: stream to `.partial`, verify, rename.
    fn attempt(
        &self,
        item: &DownloadRequest,
        grant: Option<&PerActionGrant>,
        cancel: &Cancel,
    ) -> Result<u64, DownloadError> {
        let parent = item.target.parent().unwrap_or(Path::new("."));
        fs::create_dir_all(parent).map_err(|source| DownloadError::Io {
            path: parent.display().to_string(),
            source,
        })?;

        let partial = partial_path(&item.target);

        // A `.partial` left by a previous crash is meaningless: we cannot know
        // how much of it is valid, and resuming without a strong ETag would risk
        // splicing two different responses together.
        let _ = fs::remove_file(&partial);

        let outcome = self.stream_to_partial(item, grant, cancel, &partial);

        match outcome {
            Ok((bytes, digest)) => {
                if !digest.eq_ignore_ascii_case(&item.expect.sha1) {
                    let _ = fs::remove_file(&partial);
                    return Err(DownloadError::HashMismatch {
                        name: item.name.clone(),
                        expected: item.expect.sha1.clone(),
                        actual: digest,
                    });
                }
                if let Some(expected) = item.expect.size {
                    if bytes != expected {
                        let _ = fs::remove_file(&partial);
                        return Err(DownloadError::SizeMismatch {
                            name: item.name.clone(),
                            expected,
                            actual: bytes,
                        });
                    }
                }

                // Verified. Only now does it take its real name.
                finalise(&partial, &item.target)?;
                Ok(bytes)
            }
            Err(e) => {
                // Never leave a partial behind to be mistaken for real data.
                let _ = fs::remove_file(&partial);
                Err(e)
            }
        }
    }

    /// Stream the body into `partial`, hashing as it goes.
    ///
    /// Hashing during the write means the bytes are never read back off disk to
    /// be checked, and a mismatch is known before anything is renamed.
    fn stream_to_partial(
        &self,
        item: &DownloadRequest,
        grant: Option<&PerActionGrant>,
        cancel: &Cancel,
        partial: &Path,
    ) -> Result<(u64, String), DownloadError> {
        let file = File::create(partial).map_err(|source| DownloadError::Io {
            path: partial.display().to_string(),
            source,
        })?;

        let mut sink = HashingWriter {
            inner: BufWriter::new(file),
            hasher: Sha1::new(),
            written: 0,
        };

        let keep_going = || !cancel.is_cancelled();
        let limit = item.expect.limit();

        let streamed =
            self.http
                .stream_cancellable(&item.request, grant, limit, &mut sink, &keep_going);

        match streamed {
            Ok(_) => {}
            Err(NetworkError::Cancelled) => return Err(DownloadError::Cancelled),
            Err(NetworkError::ResponseTooLarge { .. }) => {
                return Err(DownloadError::TooLarge {
                    name: item.name.clone(),
                    limit,
                })
            }
            Err(e) => return Err(DownloadError::Network(e)),
        }

        let written = sink.written;
        let digest = hex(&sink.hasher.finalize());

        // Flush and fsync before the rename, so the rename cannot expose a file
        // whose contents are still in a buffer.
        let mut inner = sink.inner;
        inner.flush().map_err(|source| DownloadError::Io {
            path: partial.display().to_string(),
            source,
        })?;
        inner
            .get_ref()
            .sync_all()
            .map_err(|source| DownloadError::Io {
                path: partial.display().to_string(),
                source,
            })?;

        Ok((written, digest))
    }

    /// Fetch a batch, reporting progress and stopping promptly on cancel.
    ///
    /// Sequential on purpose: correctness first. A bounded parallel fetcher is a
    /// worthwhile follow-up, but it needs careful cancellation and would not
    /// change any of the guarantees above.
    pub fn fetch_all(
        &self,
        items: &[DownloadRequest],
        cancel: &Cancel,
        on_progress: &mut dyn FnMut(&Progress),
    ) -> Result<u64, DownloadError> {
        let total = items.len();
        let mut bytes = 0u64;

        for (index, item) in items.iter().enumerate() {
            if cancel.is_cancelled() {
                return Err(DownloadError::Cancelled);
            }
            on_progress(&Progress {
                completed: index,
                total,
                current: item.name.clone(),
                bytes_transferred: bytes,
            });

            if let Outcome::Downloaded { bytes: n, .. } = self.fetch(item, None, cancel)? {
                bytes += n;
            }
        }

        on_progress(&Progress {
            completed: total,
            total,
            current: String::new(),
            bytes_transferred: bytes,
        });
        Ok(bytes)
    }
}

/// A writer that hashes and counts as it forwards.
struct HashingWriter {
    inner: BufWriter<File>,
    hasher: Sha1,
    written: u64,
}

impl Write for HashingWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.hasher.update(buf.get(..n).unwrap_or_default());
        self.written += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

/// The in-progress name for a target.
pub fn partial_path(target: &Path) -> PathBuf {
    let name = target.file_name().map_or_else(
        || "artifact".to_owned(),
        |n| n.to_string_lossy().into_owned(),
    );
    target.with_file_name(format!("{name}.partial"))
}

/// Move a verified `.partial` onto its real name.
fn finalise(partial: &Path, target: &Path) -> Result<(), DownloadError> {
    // Windows `rename` fails when the destination exists.
    #[cfg(windows)]
    if target.exists() {
        let _ = fs::remove_file(target);
    }
    fs::rename(partial, target).map_err(|source| DownloadError::Io {
        path: target.display().to_string(),
        source,
    })
}

/// Lowercase hex SHA-1 of a byte slice.
pub fn sha1_hex(bytes: &[u8]) -> String {
    hex(&Sha1::digest(bytes))
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        // Writing to a String is infallible.
        let _ = write!(out, "{b:02x}");
    }
    out
}

/// SHA-1 of a file on disk, streamed rather than read whole.
pub fn sha1_file(path: &Path) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        use std::io::Read as _;
        match file.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => hasher.update(buf.get(..n)?),
            Err(_) => return None,
        }
    }
    Some(hex(&hasher.finalize()))
}

/// Whether the file at `path` exists and matches `expected_sha1`.
pub fn verify_file(path: &Path, expected_sha1: &str) -> bool {
    sha1_file(path).is_some_and(|actual| actual.eq_ignore_ascii_case(expected_sha1))
}

/// Remove any stale `.partial` files under a directory.
///
/// Used before a repair, so a crash mid-download does not leave litter that
/// looks like progress.
pub fn sweep_partials(root: &Path) -> usize {
    let mut removed = 0;
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().is_some_and(|e| e == "partial")
                && fs_safety::is_lexically_inside(root, &path)
                && fs::remove_file(&path).is_ok()
            {
                removed += 1;
            }
        }
    }
    removed
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
    fn sha1_matches_known_vectors() {
        assert_eq!(sha1_hex(b""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn streamed_file_hashing_matches_in_memory_hashing() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("big.bin");
        // Larger than the 64 KiB read buffer, so the chunked path is exercised.
        let data = vec![0xABu8; 200 * 1024];
        fs::write(&path, &data).unwrap();
        assert_eq!(sha1_file(&path).unwrap(), sha1_hex(&data));
    }

    #[test]
    fn verification_accepts_a_good_file_and_rejects_a_changed_one() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("a.jar");
        fs::write(&path, b"abc").unwrap();

        assert!(verify_file(
            &path,
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        ));
        assert!(verify_file(
            &path,
            "A9993E364706816ABA3E25717850C26C9CD0D89D"
        ));

        fs::write(&path, b"abd").unwrap();
        assert!(!verify_file(
            &path,
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        ));
    }

    #[test]
    fn a_missing_file_never_verifies() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(!verify_file(&tmp.path().join("absent"), "aaa"));
    }

    #[test]
    fn the_partial_name_sits_beside_the_target() {
        let p = partial_path(Path::new("/a/b/client.jar"));
        assert_eq!(p, PathBuf::from("/a/b/client.jar.partial"));
        assert_eq!(p.parent(), Path::new("/a/b/client.jar").parent());
    }

    #[test]
    fn partial_names_survive_spaces_and_unicode() {
        let p = partial_path(Path::new("/a b/\u{4e2d}\u{6587} name.jar"));
        assert!(p
            .to_string_lossy()
            .ends_with("\u{4e2d}\u{6587} name.jar.partial"));
    }

    #[test]
    fn finalise_replaces_an_existing_file_atomically() {
        let tmp = tempfile::tempdir().unwrap();
        let target = tmp.path().join("f.jar");
        let partial = partial_path(&target);

        fs::write(&target, b"old").unwrap();
        fs::write(&partial, b"new").unwrap();
        finalise(&partial, &target).unwrap();

        assert_eq!(fs::read(&target).unwrap(), b"new");
        assert!(!partial.exists(), "the partial must not survive");
    }

    #[test]
    fn sweeping_removes_partials_and_leaves_real_files() {
        let tmp = tempfile::tempdir().unwrap();
        let nested = tmp.path().join("libs/deep");
        fs::create_dir_all(&nested).unwrap();

        fs::write(tmp.path().join("a.jar"), b"keep").unwrap();
        fs::write(tmp.path().join("a.jar.partial"), b"junk").unwrap();
        fs::write(nested.join("b.jar.partial"), b"junk").unwrap();
        fs::write(nested.join("b.jar"), b"keep").unwrap();

        assert_eq!(sweep_partials(tmp.path()), 2);
        assert!(tmp.path().join("a.jar").exists());
        assert!(nested.join("b.jar").exists());
        assert!(!tmp.path().join("a.jar.partial").exists());
        assert!(!nested.join("b.jar.partial").exists());
    }

    #[test]
    fn the_size_limit_tracks_the_declared_size() {
        let with = Expectation::new("x", Some(1000));
        assert!(with.limit() >= 1000);
        assert!(with.limit() < MAX_ARTIFACT_BYTES);

        let without = Expectation::new("x", None);
        assert_eq!(without.limit(), MAX_ARTIFACT_BYTES);

        // A declared size larger than the ceiling is clamped, not trusted.
        let huge = Expectation::new("x", Some(u64::MAX));
        assert_eq!(huge.limit(), MAX_ARTIFACT_BYTES);
    }

    #[test]
    fn hash_mismatches_are_not_treated_as_transient() {
        // Retrying a hash mismatch would waste time and could mask a
        // substitution attack behind an eventual success.
        let mismatch = DownloadError::HashMismatch {
            name: "x".into(),
            expected: "a".into(),
            actual: "b".into(),
        };
        assert!(!mismatch.is_transient());

        let size = DownloadError::SizeMismatch {
            name: "x".into(),
            expected: 1,
            actual: 2,
        };
        assert!(!size.is_transient());

        assert!(!DownloadError::Cancelled.is_transient());
    }

    #[test]
    fn transport_failures_are_transient() {
        let io = DownloadError::Io {
            path: "x".into(),
            source: std::io::Error::other("boom"),
        };
        assert!(io.is_transient());
    }

    #[test]
    fn a_hashing_writer_counts_and_digests_what_passes_through() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("w.bin");
        let mut w = HashingWriter {
            inner: BufWriter::new(File::create(&path).unwrap()),
            hasher: Sha1::new(),
            written: 0,
        };
        w.write_all(b"ab").unwrap();
        w.write_all(b"c").unwrap();
        w.flush().unwrap();

        assert_eq!(w.written, 3);
        assert_eq!(hex(&w.hasher.finalize()), sha1_hex(b"abc"));
    }
}
