//! Logging setup and log hygiene.
//!
//! `THREAT_MODEL.md` lists sensitive log leakage as a real threat, and there
//! are two halves to preventing it:
//!
//! - structurally, tokens live in [`crate::secret::Secret`], whose `Debug` and
//!   `Display` redact, so the ordinary path cannot leak one;
//! - defensively, [`scrub`] catches values that arrived as plain strings from
//!   somewhere we do not control, such as a JVM's stderr.

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock, PoisonError};

use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::EnvFilter;

static INITIALISED: OnceLock<()> = OnceLock::new();
static LOG_FILE: OnceLock<PathBuf> = OnceLock::new();

/// Install the tracing subscriber, writing to stderr only.
///
/// For `fathom-cli` and for tests. The desktop application uses
/// [`init_with_file`], because a windowed build has no stderr for anything to
/// go to.
///
/// Safe to call more than once; only the first call takes effect.
pub fn init(verbose: bool) {
    INITIALISED.get_or_init(|| {
        tracing_subscriber::fmt()
            .with_env_filter(filter(verbose))
            .with_writer(std::io::stderr)
            .with_target(false)
            .init();
    });
}

/// How large the log may get before it is rotated.
///
/// 4 MB. Large enough to hold a session with a crash loop in it, small enough
/// that a user can attach it to a report without thinking about it.
const MAX_LOG_BYTES: u64 = 4 * 1024 * 1024;

/// Install the subscriber, writing to a file in the application data directory.
///
/// # Why the desktop build needs this
///
/// The release build is `windows_subsystem = "windows"`: there is no console,
/// so `stderr` is a handle nothing is reading. Every `tracing::warn!` in the
/// program would go nowhere, and the first time someone asked "what happened"
/// the answer would be that we threw it away.
///
/// So a windowed build writes to `<data>/logs/launcher.log`, which is also
/// where `recovery::diagnostics` reads from when a user exports a report.
///
/// # Debug builds keep the console output
///
/// `also_stderr` is true in development. Useful console output during
/// development is not something to hide; the file is *added*, not substituted.
///
/// # Every line is scrubbed on the way to disk
///
/// The structural defence is that tokens are `Secret` and cannot be formatted.
/// This is the same net `process::pump` puts under the game's output, applied
/// to our own: a log file is a thing users paste into issues, and it is worth
/// paying a string scan per line for.
pub fn init_with_file(verbose: bool, logs_dir: &Path, also_stderr: bool) -> io::Result<PathBuf> {
    let path = logs_dir.join("launcher.log");
    std::fs::create_dir_all(logs_dir)?;
    rotate_if_large(&path);

    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)?;
    let writer = ScrubbingWriter::new(file, also_stderr);

    let _ = LOG_FILE.set(path.clone());
    INITIALISED.get_or_init(|| {
        tracing_subscriber::fmt()
            .with_env_filter(filter(verbose))
            .with_writer(writer)
            .with_ansi(false)
            .with_target(false)
            .init();
    });

    Ok(path)
}

/// What is worth recording.
///
/// **Third-party crates default to `warn`; ours to `info`, or `debug` when
/// verbose.**
///
/// # The bug this replaced
///
/// The previous version built a comma-separated string and passed it to
/// `EnvFilter::add_directive`, which takes **one** `Directive`. A string
/// containing commas is not one directive, so the parse failed every time and
/// the `unwrap_or_else` quietly substituted a global `INFO`.
///
/// Nothing looked wrong until a log file existed: `winit` then logged the whole
/// `WindowAttributes` struct — forty lines of formatting for one window — into
/// the file a user is meant to attach to a bug report. A log only helps if
/// someone reads it.
///
/// `FATHOM_LOG` still overrides everything, which is how a developer chasing a
/// `winit` problem turns it back on.
fn filter(verbose: bool) -> EnvFilter {
    // An explicit request wins, whatever it says.
    if let Ok(requested) = std::env::var("FATHOM_LOG") {
        if !requested.trim().is_empty() {
            return EnvFilter::builder()
                .with_default_directive(LevelFilter::WARN.into())
                .parse_lossy(requested);
        }
    }

    let ours = if verbose { "debug" } else { "info" };
    // `EnvFilter::new` parses a full directive *list*, which is what this is.
    EnvFilter::new(format!(
        "warn,launcher_core={ours},launcher_ui={ours},fathom={ours},fathom_cli={ours}"
    ))
}

/// Where the log is, if a file was installed. For the diagnostic exporter.
pub fn log_file_path() -> Option<&'static Path> {
    LOG_FILE.get().map(PathBuf::as_path)
}

/// Keep one previous log, so a crash on start does not destroy the record of
/// the crash before it.
///
/// Infallible on purpose. A failed rotation must not stop the launcher
/// starting: losing the old log is a nuisance, and refusing to run because of
/// one is not a trade worth making.
fn rotate_if_large(path: &Path) {
    let Ok(meta) = std::fs::metadata(path) else {
        return;
    };
    if meta.len() < MAX_LOG_BYTES {
        return;
    }
    let previous = path.with_extension("log.1");
    let _ = std::fs::remove_file(&previous);
    if let Err(error) = std::fs::rename(path, &previous) {
        tracing::debug!(%error, "could not rotate the log; appending to the existing one");
    }
}

/// Writes complete lines, scrubbed, to a file and optionally to stderr.
///
/// Line-buffered on purpose: `scrub` works on lines, and a writer that scrubbed
/// arbitrary chunks could split a token across two writes and miss it.
#[derive(Clone)]
struct ScrubbingWriter {
    file: Arc<Mutex<std::fs::File>>,
    buffer: Arc<Mutex<Vec<u8>>>,
    also_stderr: bool,
}

impl ScrubbingWriter {
    fn new(file: std::fs::File, also_stderr: bool) -> Self {
        Self {
            file: Arc::new(Mutex::new(file)),
            buffer: Arc::new(Mutex::new(Vec::new())),
            also_stderr,
        }
    }

    fn emit(&self, line: &str) {
        let clean = scrub(line);
        if let Ok(mut file) = self.file.lock() {
            let _ = writeln!(file, "{clean}");
            let _ = file.flush();
        }
        if self.also_stderr {
            let mut err = io::stderr();
            let _ = writeln!(err, "{clean}");
        }
    }
}

impl Write for ScrubbingWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        let mut buffer = self.buffer.lock().unwrap_or_else(PoisonError::into_inner);
        buffer.extend_from_slice(data);

        // Emit every complete line, keep the remainder.
        while let Some(index) = buffer.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = buffer.drain(..=index).collect();
            let text = String::from_utf8_lossy(&line);
            self.emit(text.trim_end_matches(['\n', '\r']));
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        let mut buffer = self.buffer.lock().unwrap_or_else(PoisonError::into_inner);
        if !buffer.is_empty() {
            let text = String::from_utf8_lossy(&buffer).into_owned();
            buffer.clear();
            drop(buffer);
            self.emit(text.trim_end_matches(['\n', '\r']));
        }
        Ok(())
    }
}

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for ScrubbingWriter {
    type Writer = ScrubbingWriter;

    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

/// Markers whose value is a single whitespace-delimited token: command-line
/// flags. Scrubbing to end-of-line here would eat the arguments that follow.
const ARGUMENT_MARKERS: &[&str] = &["--accessToken", "--session"];

/// Whole phrases that precede a credential.
///
/// `Session ID is token:` is Minecraft 1.8.9's own startup line. The game
/// prints its session id -- which contains the access token verbatim -- to
/// stdout on every launch, and the launcher captures stdout into a file. Found
/// by running the game and reading the log, not by reasoning about it, which is
/// why the phrase is here rather than in `FIELD_MARKERS`: the space-separated
/// words would not match a marker rule.
const PHRASE_MARKERS: &[&str] = &["Session ID is token:", "Session ID is"];

/// Markers whose value may itself contain spaces and runs to the next quote,
/// comma, brace, or end of line: HTTP headers and JSON fields.
///
/// Order matters. `Authorization` comes before `Bearer` so that
/// `Authorization: Bearer <token>` is consumed in one go.
const FIELD_MARKERS: &[&str] = &[
    "Authorization",
    "identityToken",
    "access_token",
    "refresh_token",
    "device_code",
    "RpsTicket",
    "accessToken",
    // Session credentials. We persist no cookie jar and enable no cookie
    // feature, but a proxy log or a pasted capture can still contain one.
    "Set-Cookie",
    "Cookie",
    "sessionid",
    "Bearer",
];

/// Characters separating a marker from its value.
const SEPARATORS: &[char] = &[' ', '=', ':', '"', '\t'];

/// Remove anything that looks like a credential from a line of text.
///
/// Applied to output we did not produce — chiefly the game's stdout and stderr,
/// which echo the launch arguments on some crash paths.
///
/// This is a safety net, not a guarantee. The real defence is that tokens are
/// [`crate::secret::Secret`] values everywhere in our own code.
pub fn scrub(line: &str) -> String {
    let mut out = line.to_owned();
    // Argument markers first: `--accessToken` must be handled before the
    // `accessToken` field marker would match inside it.
    // Phrases first: `Session ID is token:<token>:<uuid>` has to be consumed
    // whole, before a narrower marker takes a bite out of the middle of it.
    for marker in PHRASE_MARKERS {
        out = scrub_after(&out, marker, true);
    }
    for marker in ARGUMENT_MARKERS {
        out = scrub_after(&out, marker, true);
    }
    for marker in FIELD_MARKERS {
        out = scrub_after(&out, marker, false);
    }
    out
}

/// Replace the value following `marker`.
///
/// `token_only` selects the terminator set: whitespace-delimited for command
/// line flags, quote/comma/brace-delimited for headers and JSON fields.
fn scrub_after(text: &str, marker: &str, token_only: bool) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;

    while let Some(idx) = rest.find(marker) {
        let (before, from_marker) = rest.split_at(idx);

        // A marker preceded by `-` or a word character is part of a longer
        // token (`--accessToken` contains `accessToken`), not a field of its
        // own. Skip past it without redacting.
        let is_standalone = before
            .chars()
            .next_back()
            .is_none_or(|c| !(c == '-' || c == '_' || c.is_ascii_alphanumeric()));
        if !token_only && !is_standalone {
            out.push_str(before);
            out.push_str(marker);
            rest = from_marker.get(marker.len()..).unwrap_or_default();
            continue;
        }

        out.push_str(before);
        out.push_str(marker);

        let after = from_marker.get(marker.len()..).unwrap_or_default();
        let value_start = after
            .char_indices()
            .find(|(_, c)| !SEPARATORS.contains(c))
            .map_or(after.len(), |(i, _)| i);
        out.push_str(after.get(..value_start).unwrap_or_default());

        let value = after.get(value_start..).unwrap_or_default();
        let value_end = value
            .char_indices()
            .find(|(_, c)| {
                if token_only {
                    c.is_whitespace()
                } else {
                    matches!(c, '"' | ',' | '}' | '\n' | '\r')
                }
            })
            .map_or(value.len(), |(i, _)| i);

        if value_end > 0 {
            out.push_str("<redacted>");
        }
        rest = value.get(value_end..).unwrap_or_default();
    }
    out.push_str(rest);
    out
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
    fn command_line_tokens_are_scrubbed() {
        let line =
            "java -cp x Main --username Steve --accessToken eyJhbGciOi.abc.def --userType msa";
        let scrubbed = scrub(line);
        assert!(!scrubbed.contains("eyJhbGciOi.abc.def"));
        assert!(scrubbed.contains("--username Steve"));
        assert!(scrubbed.contains("--userType msa"));
        assert!(scrubbed.contains("<redacted>"));
    }

    #[test]
    fn json_shaped_tokens_are_scrubbed() {
        let line = r#"{"access_token":"secret-value","expires_in":86400}"#;
        let scrubbed = scrub(line);
        assert!(!scrubbed.contains("secret-value"));
        assert!(scrubbed.contains("expires_in"));
    }

    #[test]
    fn bearer_headers_are_scrubbed() {
        let scrubbed = scrub("Authorization: Bearer abcdef123456");
        assert!(!scrubbed.contains("abcdef123456"));
    }

    #[test]
    fn xbox_identity_tokens_are_scrubbed() {
        let scrubbed = scrub("identityToken=XBL3.0 x=userhash;the-xsts-token");
        assert!(!scrubbed.contains("the-xsts-token"));
    }

    #[test]
    fn rps_tickets_are_scrubbed() {
        let scrubbed = scrub(r#"{"RpsTicket":"d=EwAoA8l6BAAU..."}"#);
        assert!(!scrubbed.contains("EwAoA8l6BAAU"));
    }

    #[test]
    fn ordinary_lines_are_untouched() {
        for line in [
            "[Client thread/INFO]: Setting user: Steve",
            "OpenAL initialized.",
            "Loaded 7 mods",
            "",
        ] {
            assert_eq!(scrub(line), line, "{line:?} should not be modified");
        }
    }

    #[test]
    fn multiple_occurrences_are_all_scrubbed() {
        let line = "--accessToken aaa then --accessToken bbb";
        let scrubbed = scrub(line);
        assert!(!scrubbed.contains("aaa"));
        assert!(!scrubbed.contains("bbb"));
        assert_eq!(scrubbed.matches("<redacted>").count(), 2);
    }

    #[test]
    fn a_marker_with_nothing_after_it_does_not_panic() {
        assert_eq!(scrub("--accessToken"), "--accessToken");
        assert_eq!(scrub("--accessToken "), "--accessToken ");
    }

    #[test]
    fn init_is_idempotent() {
        init(false);
        init(true);
    }

    /// A windowed build has no stderr, so a line that does not reach the file
    /// does not exist.
    #[test]
    fn the_file_writer_emits_complete_scrubbed_lines() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("launcher.log");
        let file = std::fs::File::create(&path).expect("create");

        let mut writer = ScrubbingWriter::new(file, false);
        writer
            .write_all(b"starting up\n--accessToken eyJ.SECRETVALUE.sig done\n")
            .expect("write");

        let text = std::fs::read_to_string(&path).expect("read");
        assert!(text.contains("starting up"));
        assert!(
            !text.contains("SECRETVALUE"),
            "a credential reached the log file: {text}"
        );
        assert!(text.contains("<redacted>"));
    }

    /// `scrub` works on lines. A writer that scrubbed arbitrary chunks could
    /// split a token across two writes and miss it, so the buffer holds a
    /// partial line until the newline arrives.
    #[test]
    fn a_token_split_across_two_writes_is_still_scrubbed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("launcher.log");
        let file = std::fs::File::create(&path).expect("create");

        let mut writer = ScrubbingWriter::new(file, false);
        writer
            .write_all(b"--accessToken eyJ.SPLIT")
            .expect("first half");
        assert_eq!(
            std::fs::read_to_string(&path).expect("read"),
            "",
            "nothing may be written before the line is complete"
        );

        writer.write_all(b"SECRET.sig\n").expect("second half");
        let text = std::fs::read_to_string(&path).expect("read");
        assert!(
            !text.contains("SPLITSECRET"),
            "the token was written in halves and escaped scrubbing: {text}"
        );
    }

    #[test]
    fn a_large_log_is_rotated_rather_than_growing_forever() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("launcher.log");
        let over = usize::try_from(MAX_LOG_BYTES + 1).expect("fits on a 64-bit test host");
        std::fs::write(&path, vec![b'x'; over]).expect("write");

        rotate_if_large(&path);

        assert!(
            path.with_extension("log.1").is_file(),
            "the previous log should be kept: a crash on start must not destroy \
             the record of the crash before it"
        );
        assert!(!path.exists(), "and the current one starts fresh");
    }

    /// The regression that produced forty lines of `winit` internals in a
    /// user-facing log file.
    ///
    /// `EnvFilter`'s `Display` renders the directives it actually holds, so
    /// this asserts they were taken rather than silently dropped — which is
    /// exactly what the old `add_directive` call did.
    #[test]
    fn the_filter_keeps_third_party_crates_quiet() {
        let rendered = filter(false).to_string();

        assert!(
            rendered.contains("launcher_core=info"),
            "our own crates must log at info: {rendered}"
        );
        assert!(
            rendered.contains("launcher_ui=info"),
            "including the interface: {rendered}"
        );
        assert!(
            rendered.contains("warn"),
            "and everything else must default to warn, or a windowing library \
             fills the log: {rendered}"
        );
        assert!(
            !rendered.trim().is_empty(),
            "an empty filter means the directives failed to parse and something \
             substituted a default"
        );
    }

    #[test]
    fn verbose_raises_our_crates_and_not_everyone_elses() {
        let rendered = filter(true).to_string();
        assert!(rendered.contains("launcher_core=debug"), "{rendered}");
        assert!(
            !rendered.contains("winit"),
            "verbose is about our code, not a dependency's: {rendered}"
        );
    }

    #[test]
    fn a_small_log_is_left_alone() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("launcher.log");
        std::fs::write(&path, b"short").expect("write");
        rotate_if_large(&path);
        assert!(path.is_file());
        assert!(!path.with_extension("log.1").exists());
    }
}
