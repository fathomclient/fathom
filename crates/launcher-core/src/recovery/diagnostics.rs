//! A report a user can send someone, without sending anything they did not
//! mean to.
//!
//! # Nothing is ever uploaded
//!
//! There is no endpoint. [`Report::to_text`] returns a `String`; writing it
//! somewhere is the caller's act, taken because a person asked. `PRIVACY.md`
//! promises no telemetry, and a diagnostic uploader is telemetry with a
//! friendlier name.
//!
//! # Two defences against leaking, not one
//!
//! **Construction.** The report is built from a fixed set of fields. There is
//! no "dump everything" path, no environment capture, and no directory walk, so
//! the question is never "did we remember to exclude X" — X has to be added on
//! purpose to appear at all.
//!
//! **Scrubbing.** Every string still goes through [`crate::logging::scrub`] on
//! the way in, because a *log line* can contain anything and log excerpts are
//! the most useful part of a report. [`Report::to_text`] scrubs the assembled
//! document once more before returning it, so a field added carelessly later is
//! still caught.
//!
//! # Paths are normalised, and that is a privacy control
//!
//! `C:\Users\jane.doe\AppData\Local\Fathom\profiles\main` contains the user's
//! real name. It appears in almost every error message a launcher produces, so
//! a report that includes raw paths is a report that leaks an identity in a
//! dozen places.
//!
//! [`Normalizer`] rewrites the roots it knows to `<APP_DATA>`, `<INSTALL>`,
//! `<HOME>` and `<TEMP>`, longest first. What is left is a path that says what
//! it needs to — *which* file, relative to *which* root — and nothing about who
//! owns it.
//!
//! # What is never included
//!
//! Access, refresh, Xbox, XSTS and Minecraft tokens; `Authorization` headers;
//! the Microsoft email; the account UUID; machine identifiers, HWID or any
//! persistent device fingerprint; the credential store's contents; and paths
//! outside the roots above. The account is represented by whether one is signed
//! in, which is the only part of it that helps diagnose anything.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use crate::paths::DataPaths;
use crate::problem::Problem;

/// Rewrites known roots so a report does not carry a username.
///
/// Longest root first, so `<APP_DATA>` wins over `<HOME>` when one contains the
/// other — which it does on every platform we target.
#[derive(Debug, Clone)]
pub struct Normalizer {
    roots: Vec<(PathBuf, &'static str)>,
}

impl Normalizer {
    pub fn new(paths: &DataPaths) -> Self {
        let mut roots: Vec<(PathBuf, &'static str)> = Vec::new();
        roots.push((paths.root().to_path_buf(), "<APP_DATA>"));
        if let Some(install) = DataPaths::install_dir() {
            roots.push((install, "<INSTALL>"));
        }
        roots.push((std::env::temp_dir(), "<TEMP>"));
        if let Some(home) = home_directory() {
            roots.push((home, "<HOME>"));
        }
        // Longest first: `<APP_DATA>` is inside `<HOME>`, and replacing the
        // shorter one first would leave `<HOME>/AppData/Local/Fathom/...`,
        // which still says which account it is on a shared machine.
        roots.sort_by_key(|(path, _)| std::cmp::Reverse(path.as_os_str().len()));
        Self { roots }
    }

    /// For tests and for a caller that wants no normalisation.
    pub fn identity() -> Self {
        Self { roots: Vec::new() }
    }

    /// Rewrite a path.
    pub fn path(&self, path: &Path) -> String {
        let text = path.display().to_string();
        self.text(&text)
    }

    /// Rewrite every known root inside arbitrary text.
    ///
    /// Applied to log excerpts as well as to paths, because a log line is where
    /// a raw path most often appears.
    pub fn text(&self, text: &str) -> String {
        let mut out = text.to_owned();
        for (root, label) in &self.roots {
            let rendered = root.display().to_string();
            if rendered.is_empty() {
                continue;
            }
            out = out.replace(&rendered, label);
            // Windows paths appear both ways round depending on who wrote the
            // string: `std::fmt` gives backslashes, JSON and URLs give forward.
            let forward = rendered.replace('\\', "/");
            if forward != rendered {
                out = out.replace(&forward, label);
            }
        }
        out
    }
}

fn home_directory() -> Option<PathBuf> {
    directories::BaseDirs::new().map(|d| d.home_dir().to_path_buf())
}

/// A section of the report. Ordered, so the output is stable and diffable.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Section {
    pub title: String,
    pub entries: Vec<(String, String)>,
    pub lines: Vec<String>,
}

/// The report.
///
/// Built by [`Builder`] so that every string passes the same door.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Report {
    pub sections: Vec<Section>,
}

impl Report {
    /// The whole thing as text.
    ///
    /// Scrubbed once more on the way out. The per-field scrub already ran; this
    /// catches a field somebody adds later without going through the builder.
    pub fn to_text(&self) -> String {
        use std::fmt::Write as _;

        let mut out = String::new();
        out.push_str("Fathom diagnostic report\n");
        out.push_str("========================\n");
        out.push_str(
            "\nThis file is generated on request and is never sent anywhere by the\n\
             launcher. Read it before sharing it.\n",
        );
        for section in &self.sections {
            let _ = write!(out, "\n## {}\n\n", section.title);
            for (key, value) in &section.entries {
                let _ = writeln!(out, "{key:<28} {value}");
            }
            if !section.lines.is_empty() {
                if !section.entries.is_empty() {
                    out.push('\n');
                }
                for line in &section.lines {
                    out.push_str(line);
                    out.push('\n');
                }
            }
        }
        crate::logging::scrub(&out)
    }

    /// A suggested filename. No timestamp component that could identify a
    /// session beyond the date.
    pub fn suggested_file_name() -> String {
        "fathom-diagnostics.txt".to_owned()
    }

    pub fn section(&self, title: &str) -> Option<&Section> {
        self.sections.iter().find(|s| s.title == title)
    }
}

/// Assembles a report, scrubbing and normalising everything on the way in.
#[derive(Debug)]
pub struct Builder {
    normalizer: Normalizer,
    sections: Vec<Section>,
}

impl Builder {
    pub fn new(normalizer: Normalizer) -> Self {
        Self {
            normalizer,
            sections: Vec::new(),
        }
    }

    /// Start a section. Returns `self` so sections chain.
    #[must_use]
    pub fn section(mut self, title: &str) -> Self {
        self.sections.push(Section {
            title: title.to_owned(),
            ..Section::default()
        });
        self
    }

    /// Add a key and value to the current section.
    #[must_use]
    pub fn entry(mut self, key: &str, value: impl Into<String>) -> Self {
        let clean = self.clean(&value.into());
        if let Some(section) = self.sections.last_mut() {
            section.entries.push((key.to_owned(), clean));
        }
        self
    }

    /// Add a path, normalised.
    #[must_use]
    pub fn path_entry(mut self, key: &str, path: &Path) -> Self {
        let clean = self.clean(&self.normalizer.path(path));
        if let Some(section) = self.sections.last_mut() {
            section.entries.push((key.to_owned(), clean));
        }
        self
    }

    /// Add a free line to the current section.
    #[must_use]
    pub fn line(mut self, text: impl Into<String>) -> Self {
        let clean = self.clean(&text.into());
        if let Some(section) = self.sections.last_mut() {
            section.lines.push(clean);
        }
        self
    }

    /// Add a failure, in full.
    #[must_use]
    pub fn problem(self, problem: &Problem) -> Self {
        let report = problem.report();
        self.line(report)
    }

    fn clean(&self, text: &str) -> String {
        // Order matters: normalise first so a path inside a log line is
        // rewritten, then scrub so a credential inside the same line goes.
        crate::logging::scrub(&self.normalizer.text(text))
    }

    pub fn build(self) -> Report {
        Report {
            sections: self.sections,
        }
    }
}

/// Facts about the machine that help diagnose a problem and do not identify it.
///
/// Deliberately coarse. The OS *family* and version explain a class of
/// failures; the machine name, domain, user and locale explain none and
/// identify plenty.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Environment {
    pub os: String,
    pub os_version: String,
    pub arch: String,
    pub launcher_version: String,
    pub build_profile: &'static str,
}

impl Environment {
    pub fn detect() -> Self {
        Self {
            os: std::env::consts::OS.to_owned(),
            os_version: os_version(),
            arch: std::env::consts::ARCH.to_owned(),
            launcher_version: crate::PRODUCT_VERSION.to_owned(),
            build_profile: if cfg!(debug_assertions) {
                "debug"
            } else {
                "release"
            },
        }
    }
}

/// The OS version, without asking anything that identifies the machine.
///
/// Best effort by design: there is no portable API for this, and the fallback
/// of "windows" is more useful than a name obtained by shelling out to
/// something that also reports the machine's identity.
fn os_version() -> String {
    #[cfg(windows)]
    {
        // The kernel version, from the loaded ntdll, via the documented
        // registry-free route the OS itself uses. Not available without unsafe
        // or a crate, and `unsafe_code` is forbidden here -- so this reports
        // the family and says so rather than pretending to more precision.
        "windows (version not queried; see docs/reliability.md)".to_owned()
    }
    #[cfg(not(windows))]
    {
        std::env::consts::OS.to_owned()
    }
}

/// Fields that must never appear in a report, checked as data.
///
/// Used by the test below and by anything that wants to assert on a report it
/// did not build. A denylist is not the defence — construction is — but it is a
/// cheap second look at the finished document.
pub const FORBIDDEN_MARKERS: &[&str] = &[
    "accessToken",
    "access_token",
    "refresh_token",
    "identityToken",
    "RpsTicket",
    "Authorization",
    "Bearer ",
    "Set-Cookie",
    "XBL3.0",
    "Session ID is",
];

/// Whether a finished report still carries a credential.
///
/// Returns the markers whose **value** survived, so a failure names them.
///
/// The subtlety, and the reason the first version of this was wrong: `scrub`
/// leaves the marker in place and replaces what follows it. `--accessToken
/// <redacted>` is exactly what a correctly scrubbed report looks like, so
/// searching for the marker alone flags every clean report. What matters is
/// what comes *after* the marker.
pub fn audit(text: &str) -> Vec<&'static str> {
    FORBIDDEN_MARKERS
        .iter()
        .filter(|marker| carries_a_value(text, marker))
        .copied()
        .collect()
}

/// Whether any occurrence of `marker` is followed by something that is not a
/// redaction.
fn carries_a_value(text: &str, marker: &str) -> bool {
    const SEPARATORS: &[char] = &[' ', '=', ':', '"', '\t', ','];
    const TERMINATORS: &[char] = &['\n', '\r', '"', ',', '}'];

    let mut rest = text;
    while let Some(index) = rest.find(marker) {
        let after = rest
            .get(index + marker.len()..)
            .unwrap_or_default()
            .trim_start_matches(SEPARATORS);

        // The value is everything up to the next terminator.
        let value = after.split(TERMINATORS).next().unwrap_or_default().trim();

        // A value that *contains* a redaction has been scrubbed. Checking only
        // the start would flag `Session ID is token:<redacted>`, which is
        // exactly what a correctly scrubbed launch line looks like: `scrub`
        // matches the longer phrase `Session ID is token:` first, so the
        // shorter marker sees `token:` before the redaction.
        //
        // An empty value -- a marker at the end of a line -- is also fine.
        if !value.is_empty() && !value.contains("<redacted>") {
            return true;
        }
        rest = rest.get(index + marker.len()..).unwrap_or_default();
    }
    false
}

/// Read the tail of a log file, normalised and scrubbed by the caller.
///
/// Bounded: a crash loop can produce megabytes, and a report nobody can read is
/// a report nobody reads.
pub fn log_tail(path: &Path, lines: usize) -> Vec<String> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let all: Vec<&str> = text.lines().collect();
    let start = all.len().saturating_sub(lines);
    all.get(start..)
        .unwrap_or_default()
        .iter()
        .map(|line| (*line).to_owned())
        .collect()
}

/// Summarise a map of counts, for "enabled mod ids" and similar.
pub fn summarise(entries: &BTreeMap<String, String>) -> Vec<String> {
    entries
        .iter()
        .map(|(key, value)| format!("{key} = {value}"))
        .collect()
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
    use crate::problem::{Category, Problem, Repair, Retry};

    fn paths() -> (tempfile::TempDir, DataPaths) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path().join("Users/jane.doe/AppData/Local/Fathom"));
        (tmp, paths)
    }

    #[test]
    fn a_data_path_is_rewritten_so_it_carries_no_username() {
        let (_tmp, paths) = paths();
        let n = Normalizer::new(&paths);

        let leaky = paths.profiles_dir().join("main/minecraft/saves");
        let normalised = n.path(&leaky);

        assert!(
            !normalised.contains("jane.doe"),
            "the username survived normalisation: {normalised}"
        );
        assert!(
            normalised.starts_with("<APP_DATA>"),
            "the root should be labelled: {normalised}"
        );
        assert!(
            normalised.contains("profiles"),
            "and the useful part must survive: {normalised}"
        );
    }

    /// A raw path inside a log line is the most common way one leaks.
    #[test]
    fn a_path_inside_a_log_line_is_rewritten_too() {
        let (_tmp, paths) = paths();
        let n = Normalizer::new(&paths);

        let line = format!(
            "ERROR could not open {} for writing",
            paths.settings_file().display()
        );
        let normalised = n.text(&line);
        assert!(!normalised.contains("jane.doe"), "{normalised}");
        assert!(normalised.contains("<APP_DATA>"), "{normalised}");
    }

    /// Windows paths appear with either separator depending on who wrote them.
    #[test]
    fn both_path_separators_are_normalised() {
        let (_tmp, paths) = paths();
        let n = Normalizer::new(&paths);

        let forward = paths.root().display().to_string().replace('\\', "/");
        let normalised = n.text(&format!("opened {forward}/settings.json"));
        assert!(
            !normalised.contains("jane.doe"),
            "a forward-slash rendering slipped through: {normalised}"
        );
    }

    /// `<APP_DATA>` is inside `<HOME>`. Replacing the shorter root first would
    /// leave `<HOME>/AppData/Local/Fathom/...`, which still identifies the
    /// account on a shared machine.
    #[test]
    fn the_longest_matching_root_wins() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let home = tmp.path().join("home");
        let data = home.join("app/data");
        let paths = DataPaths::rooted_at(&data);

        let n = Normalizer {
            roots: {
                let mut roots = vec![(home.clone(), "<HOME>"), (data.clone(), "<APP_DATA>")];
                roots.sort_by_key(|(p, _)| std::cmp::Reverse(p.as_os_str().len()));
                roots
            },
        };

        let normalised = n.path(&paths.profiles_dir());
        assert!(
            normalised.starts_with("<APP_DATA>"),
            "expected the more specific root: {normalised}"
        );
    }

    /// The report is the one document designed to leave the machine, so the
    /// scrub matters more here than anywhere.
    #[test]
    fn a_credential_in_a_log_line_never_reaches_the_report() {
        let (_tmp, paths) = paths();
        let report = Builder::new(Normalizer::new(&paths))
            .section("Recent log")
            .line("[Client thread/INFO]: Setting user: Steve")
            .line("(Session ID is token:eyJhbGciOi.SUPERSECRET.sig:uuid)")
            .line("java -cp x Main --accessToken eyJhbGciOi.ANOTHERSECRET.sig --userType msa")
            .line(r#"{"refresh_token":"M.R3_BAY.THIRDSECRET"}"#)
            .build();

        let text = report.to_text();
        for secret in ["SUPERSECRET", "ANOTHERSECRET", "THIRDSECRET"] {
            assert!(
                !text.contains(secret),
                "{secret} reached the report:\n{text}"
            );
        }
        assert!(
            text.contains("Setting user: Steve"),
            "the useful part of the log must survive"
        );
        assert!(audit(&text).is_empty(), "audit found: {:?}", audit(&text));
    }

    /// The audit looks at what follows a marker, not at the marker.
    ///
    /// `scrub` deliberately leaves `--accessToken <redacted>`, so an audit that
    /// searched for the marker alone would flag every correctly scrubbed
    /// report — which is how the first version of this failed.
    #[test]
    fn the_audit_distinguishes_a_redacted_marker_from_a_live_one() {
        assert!(audit("nothing to see").is_empty());

        // Correctly scrubbed. These must NOT be flagged.
        for clean in [
            "java -cp x Main --accessToken <redacted> --userType msa",
            r#"{"refresh_token":"<redacted>","expires_in":86400}"#,
            "Authorization: <redacted>",
            "(Session ID is token:<redacted>)",
            "--accessToken",
        ] {
            assert!(
                audit(clean).is_empty(),
                "{clean:?} is scrubbed and must not be reported: {:?}",
                audit(clean)
            );
        }

        // Actually leaking. These must be flagged.
        for leaky in [
            "Authorization: Bearer abcdef123456",
            r#"{"refresh_token":"M.R3_BAY.realvalue"}"#,
            "--accessToken eyJhbGciOi.real.sig",
        ] {
            assert!(
                !audit(leaky).is_empty(),
                "{leaky:?} carries a value and must be reported"
            );
        }
    }

    #[test]
    fn a_report_renders_sections_entries_and_lines() {
        let (_tmp, paths) = paths();
        let report = Builder::new(Normalizer::new(&paths))
            .section("Launcher")
            .entry("Version", "0.1.0")
            .entry("Build", "release")
            .section("Profile")
            .entry("Loader", "legacy-fabric 0.19.3")
            .line("validation: 2 warnings")
            .build();

        let text = report.to_text();
        assert!(text.contains("## Launcher"));
        assert!(text.contains("Version"));
        assert!(text.contains("0.1.0"));
        assert!(text.contains("## Profile"));
        assert!(text.contains("validation: 2 warnings"));
        assert!(
            text.contains("never sent anywhere"),
            "the document should say what it is"
        );
        assert_eq!(report.sections.len(), 2);
        assert!(report.section("Launcher").is_some());
    }

    #[test]
    fn a_problem_is_included_in_full() {
        let (_tmp, paths) = paths();
        let problem = Problem::new(Category::Installation, "Some files are missing.")
            .detail("3 of 811 artifacts failed verification")
            .retry(Retry::AfterRepair)
            .repair(Repair::RepairInstallation);

        let report = Builder::new(Normalizer::new(&paths))
            .section("Failure")
            .problem(&problem)
            .build();

        let text = report.to_text();
        assert!(text.contains("[Installation]"));
        assert!(text.contains("811"));
        assert!(text.contains("AfterRepair"));
        assert!(text.contains("Repair the Minecraft installation"));
    }

    #[test]
    fn the_environment_carries_nothing_that_identifies_a_machine() {
        let env = Environment::detect();
        assert!(!env.os.is_empty());
        assert!(!env.arch.is_empty());
        assert_eq!(env.launcher_version, crate::PRODUCT_VERSION);

        // Nothing here should be able to hold a hostname, a user or a serial.
        let rendered = format!("{env:?}");
        for identifier in [&whoami_ish()[..], "SID-", "S-1-5-21"] {
            if identifier.is_empty() {
                continue;
            }
            assert!(
                !rendered.contains(identifier),
                "{identifier} appeared in the environment block: {rendered}"
            );
        }
    }

    /// The current username, if it can be found, so the assertion above has
    /// something real to look for. Empty when it cannot.
    fn whoami_ish() -> String {
        std::env::var("USERNAME")
            .or_else(|_| std::env::var("USER"))
            .unwrap_or_default()
    }

    #[test]
    fn a_log_tail_is_bounded() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let path = tmp.path().join("launcher.log");
        let mut body = String::new();
        for i in 0..5_000 {
            use std::fmt::Write as _;
            let _ = writeln!(body, "line {i}");
        }
        std::fs::write(&path, body).unwrap();

        let tail = log_tail(&path, 50);
        assert_eq!(tail.len(), 50);
        assert_eq!(tail.last().map(String::as_str), Some("line 4999"));
    }

    #[test]
    fn a_missing_log_is_not_an_error() {
        assert!(log_tail(Path::new("/definitely/not/here.log"), 10).is_empty());
    }
}
