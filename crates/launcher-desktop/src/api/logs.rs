//! Logs, as the interface sees them.
//!
//! # These are real files
//!
//! Nothing here is a buffer the interface keeps. The launcher's own log is the
//! file `logging::init_with_file` writes; the game log is the file the launch
//! pipeline captures per profile. The screen reads what is on disk, which means
//! it shows the same bytes a user would attach to a bug report.
//!
//! # Secrets
//!
//! The launcher log is written through `logging::ScrubbingWriter`, which
//! redacts on the way *out* — so the file on disk never contained a token in
//! the first place, and this module does not have to be trusted to redact.
//!
//! The game log is different: it is Minecraft's own output, captured by us. It
//! is scanned here, because the access token appears on the command line and
//! some crash reports echo their arguments back.

use serde::Serialize;

use launcher_core::Launcher;

/// How many lines to hand over.
///
/// A launcher log can reach a few megabytes across a long session. Sending all
/// of it through the command bridge as one JSON string, on every refresh, is a
/// cost with no reader: nobody scrolls back ten thousand lines in a launcher.
const MAX_LINES: usize = 2_000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogSource {
    pub id: String,
    pub label: String,
    /// Present when this source has nothing to show, so the page can say which
    /// of "empty" or "not written yet" it is.
    pub note: Option<String>,
    pub lines: Vec<LogLine>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    pub text: String,
    /// `error` / `warn` / `info` / `debug` — for colour, not for filtering.
    pub level: &'static str,
}

/// Classify a line by what it says about itself.
///
/// Deliberately loose. This drives a colour, and a line that is coloured as
/// info when it was debug costs nothing; a regex strict enough to be exactly
/// right would break the first time a format changed.
fn level_of(line: &str) -> &'static str {
    let upper = line.to_ascii_uppercase();
    if upper.contains("ERROR") || upper.contains("SEVERE") || upper.contains("PANIC") {
        "error"
    } else if upper.contains("WARN") {
        "warn"
    } else if upper.contains("DEBUG") || upper.contains("TRACE") {
        "debug"
    } else {
        "info"
    }
}

/// Redact anything that looks like a credential.
///
/// Only used on Minecraft's output. The pattern is `--accessToken <value>`,
/// which is how it reaches the process and how a crash report echoes it.
fn scrub(line: &str) -> String {
    const NEEDLE: &str = "--accessToken";
    let Some(at) = line.find(NEEDLE) else {
        return line.to_owned();
    };
    let after = at + NEEDLE.len();
    let rest = line.get(after..).unwrap_or_default();
    let trimmed = rest.trim_start();
    let skipped = rest.len() - trimmed.len();
    let end = trimmed.find(char::is_whitespace).unwrap_or(trimmed.len());

    let mut out = String::with_capacity(line.len());
    out.push_str(line.get(..after).unwrap_or_default());
    out.push_str(rest.get(..skipped).unwrap_or_default());
    out.push_str("<redacted>");
    out.push_str(trimmed.get(end..).unwrap_or_default());
    out
}

fn read(path: &std::path::Path, redact: bool) -> Vec<LogLine> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let all: Vec<&str> = text.lines().collect();
    let start = all.len().saturating_sub(MAX_LINES);
    all.get(start..)
        .unwrap_or_default()
        .iter()
        .map(|line| LogLine {
            level: level_of(line),
            text: if redact { scrub(line) } else { (*line).to_owned() },
        })
        .collect()
}

/// The most recently written game log across every profile.
///
/// Newest across every profile rather than only the selected one: after a crash
/// the log you want is the one from the session that crashed, and by then the
/// selection may have moved.
fn newest_game_log(core: &Launcher) -> Option<std::path::PathBuf> {
    core.profiles()
        .into_iter()
        .flat_map(|profile| {
            let dirs = core.dirs_for(&profile);
            std::fs::read_dir(dirs.root().join("logs"))
                .into_iter()
                .flatten()
                .flatten()
                .map(|entry| entry.path())
                .collect::<Vec<_>>()
        })
        .filter(|path| path.extension().is_some_and(|ext| ext == "log"))
        .max_by_key(|path| {
            std::fs::metadata(path)
                .and_then(|meta| meta.modified())
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
        })
}

pub fn view(core: &Launcher) -> Vec<LogSource> {
    let mut sources = Vec::new();

    // -- the launcher's own log -------------------------------------------
    let launcher = launcher_core::logging::log_file_path().map(std::path::Path::to_path_buf);
    let lines = launcher.as_deref().map(|path| read(path, false)).unwrap_or_default();
    sources.push(LogSource {
        id: "launcher".to_owned(),
        label: "Launcher".to_owned(),
        note: if launcher.is_none() {
            Some("This build is not writing a log file.".to_owned())
        } else if lines.is_empty() {
            Some("Nothing logged yet.".to_owned())
        } else {
            None
        },
        lines,
    });

    let newest = newest_game_log(core);

    let lines = newest.as_deref().map(|path| read(path, true)).unwrap_or_default();
    sources.push(LogSource {
        id: "game".to_owned(),
        label: "Game".to_owned(),
        note: if newest.is_none() {
            Some("Minecraft has not been launched yet.".to_owned())
        } else if lines.is_empty() {
            Some("The last session logged nothing.".to_owned())
        } else {
            None
        },
        lines,
    });

    sources
}


// ---------------------------------------------------------------------------
// The console window
// ---------------------------------------------------------------------------

/// What the game console window draws.
///
/// The same bytes as the Logs screen's Game tab, plus a line saying whether the
/// session is still up. Separate from `LogSource` because the console is not a
/// log browser: it has one source, it never offers a choice, and the thing a
/// reader wants above the text is "did it crash", which a log source has no
/// field for.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConsoleView {
    /// Which profile this session belongs to. Empty when there is no session.
    pub profile: String,
    /// One line: running, or how it ended.
    pub state: String,
    pub running: bool,
    pub lines: Vec<LogLine>,
    /// Present instead of lines when there is nothing to show.
    pub note: Option<String>,
}

pub fn console(core: &Launcher) -> ConsoleView {
    // `all` reports every slot the registry knows about, live or reaped. The
    // most recent one is the session this window was opened for.
    let session = core
        .running()
        .all()
        .into_iter()
        .max_by_key(|status| status.started_at);

    // The session's own log when we have one, so the console cannot show a
    // different session's output than the state line above it describes.
    let path = session
        .as_ref()
        .map(|status| status.log_file.clone())
        .filter(|path| path.is_file())
        .or_else(|| newest_game_log(core));

    let lines = path.as_deref().map(|path| read(path, true)).unwrap_or_default();

    let (profile, state, running) = match session.as_ref() {
        Some(status) if status.is_running() => (
            status.profile.as_str().to_owned(),
            format!("Running \u{00B7} process {}", status.pid),
            true,
        ),
        Some(status) => (
            status.profile.as_str().to_owned(),
            status.outcome.message(),
            false,
        ),
        None => (String::new(), "No session".to_owned(), false),
    };

    ConsoleView {
        profile,
        state,
        running,
        note: if path.is_none() {
            Some("Minecraft has not been launched yet.".to_owned())
        } else if lines.is_empty() {
            Some("Waiting for the game to write its first line.".to_owned())
        } else {
            None
        },
        lines,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The one thing this module must never do is show a token. The game log is
    /// Minecraft's output, and the token is on the command line it echoes.
    #[test]
    fn an_access_token_never_survives() {
        let line = "[main] cmd: java -cp x.jar Main --username Bob --accessToken \
                    eyJhbGciOiJIUzI1NiJ9.secret --uuid 1234";
        let cleaned = scrub(line);
        assert!(!cleaned.contains("eyJhbGciOiJIUzI1NiJ9.secret"));
        assert!(cleaned.contains("--accessToken <redacted>"));
        // Everything after the token is preserved: redaction must not eat the
        // rest of the line.
        assert!(cleaned.contains("--uuid 1234"));
        assert!(cleaned.contains("--username Bob"));
    }

    #[test]
    fn a_line_with_no_token_is_untouched() {
        let line = "[main] Setting user: Steve";
        assert_eq!(scrub(line), line);
    }

    /// A token at the very end of a line has no trailing whitespace to find.
    #[test]
    fn a_token_at_the_end_of_a_line_is_still_redacted() {
        let cleaned = scrub("launching with --accessToken abc123");
        assert_eq!(cleaned, "launching with --accessToken <redacted>");
    }

    #[test]
    fn levels_come_from_what_the_line_says() {
        assert_eq!(level_of("2026-01-01 ERROR something broke"), "error");
        assert_eq!(level_of("[main/WARN] careful"), "warn");
        assert_eq!(level_of("[main/DEBUG] noisy"), "debug");
        assert_eq!(level_of("[main/INFO] fine"), "info");
        assert_eq!(level_of("no level here"), "info");
    }
}
