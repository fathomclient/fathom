//! Finding and vetting a Java runtime.
//!
//! 1.8.9 declares `javaVersion: { component: "jre-legacy", majorVersion: 8 }`,
//! so the target is Java 8. Running it on a modern JVM is possible but needs
//! the LWJGL 3 work that `architecture-options.md` §4.4 classifies as a spike,
//! so for now a mismatch is reported rather than worked around.
//!
//! Managed downloads live in [`managed`]. This module finds Java the machine
//! already has; that one fetches a Temurin runtime into Fathom's own data
//! directory when the user allows it. Both end at the same place: a
//! [`JavaInstallation`] that has been executed and identified.

pub mod managed;

use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, thiserror::Error)]
pub enum JavaError {
    #[error("no java executable at {0}")]
    NotFound(String),
    #[error("could not run {path}: {source}")]
    NotRunnable {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("{0} did not report a recognisable Java version")]
    Unparsable(String),
}

/// A Java installation we have actually executed and identified.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaInstallation {
    pub path: PathBuf,
    /// 8, 17, 21, ...
    pub major: u32,
    /// The raw version string, for display.
    pub version_string: String,
    /// Best-effort vendor name from the banner.
    pub vendor: Option<String>,
}

/// How well an installation matches what a version needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compatibility {
    /// Exactly the declared major version.
    Exact,
    /// Newer than declared. For 1.8.9 this generally does not work without the
    /// LWJGL 3 path, so it is a warning rather than an endorsement.
    NewerThanDeclared,
    /// Older than declared. Will not run.
    TooOld,
}

impl Compatibility {
    pub fn is_runnable(self) -> bool {
        matches!(self, Compatibility::Exact)
    }

    pub fn explanation(self, required: u32, found: u32) -> String {
        match self {
            Compatibility::Exact => format!("Java {found} matches what this version needs."),
            Compatibility::NewerThanDeclared => format!(
                "This version of Minecraft declares Java {required}, but Java {found} was found. \
                 1.8.9 uses LWJGL 2 and will not start on a modern JVM without extra work that \
                 this launcher does not yet do."
            ),
            Compatibility::TooOld => {
                format!("Java {found} is older than the Java {required} this version needs.")
            }
        }
    }
}

pub fn compatibility(required_major: u32, found_major: u32) -> Compatibility {
    match found_major.cmp(&required_major) {
        std::cmp::Ordering::Equal => Compatibility::Exact,
        std::cmp::Ordering::Greater => Compatibility::NewerThanDeclared,
        std::cmp::Ordering::Less => Compatibility::TooOld,
    }
}

/// Parse a Java major version from a version string.
///
/// Handles both schemes: `1.8.0_402` (Java 8 and earlier) and `17.0.9`
/// (Java 9 onwards).
pub fn parse_major(version: &str) -> Option<u32> {
    let trimmed = version.trim().trim_matches('"');
    let mut parts = trimmed.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        // Legacy `1.N.x` scheme.
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// Pull the version out of a `java -version` banner.
///
/// The banner goes to stderr and looks like:
/// `openjdk version "1.8.0_402"` / `java version "17.0.9" 2023-10-17`.
pub fn parse_banner(banner: &str) -> Option<(u32, String, Option<String>)> {
    let line = banner.lines().find(|l| l.contains("version \""))?;
    let start = line.find('"')? + 1;
    let rest = line.get(start..)?;
    let end = rest.find('"')?;
    let version = rest.get(..end)?.to_owned();
    let major = parse_major(&version)?;

    let vendor = if banner.contains("OpenJDK") {
        Some("OpenJDK".to_owned())
    } else if banner.contains("Java(TM)") || banner.contains("HotSpot") {
        Some("Oracle".to_owned())
    } else {
        None
    };

    Some((major, version, vendor))
}

/// Execute a candidate `java` and identify it.
///
/// Runs with an argument vector, never a shell. The only argument is
/// `-version`, so there is nothing to inject into.
pub fn probe(java: &Path) -> Result<JavaInstallation, JavaError> {
    if !java.is_file() {
        return Err(JavaError::NotFound(java.display().to_string()));
    }
    let mut command = Command::new(java);
    suppress_console_window(&mut command);
    let output = command
        .arg("-version")
        .output()
        .map_err(|source| JavaError::NotRunnable {
            path: java.display().to_string(),
            source,
        })?;

    // The banner is on stderr for every JVM in living memory, but check both.
    let banner = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stderr),
        String::from_utf8_lossy(&output.stdout)
    );
    let (major, version_string, vendor) =
        parse_banner(&banner).ok_or_else(|| JavaError::Unparsable(java.display().to_string()))?;

    Ok(JavaInstallation {
        path: java.to_path_buf(),
        major,
        version_string,
        vendor,
    })
}

/// A console-subsystem child started by the windowed launcher otherwise asks
/// Windows to create a transient terminal for `java -version`. Discovery may
/// probe several runtimes, which is why startup used to flash several black
/// windows before the launcher finished loading.
#[cfg(windows)]
fn suppress_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt as _;

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn suppress_console_window(_command: &mut Command) {}

fn executable_name() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}

/// Candidate locations, in preference order. Nothing is executed here.
pub fn candidate_paths() -> Vec<PathBuf> {
    let mut out = Vec::new();

    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        if !java_home.trim().is_empty() {
            out.push(PathBuf::from(java_home).join("bin").join(executable_name()));
        }
    }

    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            out.push(dir.join(executable_name()));
        }
    }

    out.retain(|p| p.is_file());
    out.dedup();
    out
}

/// Probe every candidate and return the ones that work.
pub fn discover() -> Vec<JavaInstallation> {
    let mut found: Vec<JavaInstallation> = Vec::new();
    for candidate in candidate_paths() {
        match probe(&candidate) {
            Ok(install) => {
                if !found.iter().any(|f| f.path == install.path) {
                    found.push(install);
                }
            }
            Err(e) => {
                tracing::debug!(path = %candidate.display(), error = %e, "java candidate rejected");
            }
        }
    }
    found
}

/// Pick the best installation for a required major version.
pub fn select(
    installations: &[JavaInstallation],
    required_major: u32,
) -> Option<&JavaInstallation> {
    installations
        .iter()
        .find(|i| i.major == required_major)
        .or_else(|| {
            // No exact match. Return the closest newer one so the UI can
            // explain the mismatch rather than saying "no Java found".
            installations
                .iter()
                .filter(|i| i.major > required_major)
                .min_by_key(|i| i.major)
        })
}

#[cfg(test)]
#[allow(clippy::indexing_slicing, clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn legacy_and_modern_version_schemes_both_parse() {
        assert_eq!(parse_major("1.8.0_402"), Some(8));
        assert_eq!(parse_major("1.7.0_80"), Some(7));
        assert_eq!(parse_major("17.0.9"), Some(17));
        assert_eq!(parse_major("21"), Some(21));
        assert_eq!(parse_major("21.0.1+12"), Some(21));
        assert_eq!(parse_major("\"1.8.0_402\""), Some(8));
        assert_eq!(parse_major("not a version"), None);
        assert_eq!(parse_major(""), None);
    }

    #[test]
    fn real_java_banners_are_understood() {
        let temurin8 = "openjdk version \"1.8.0_402\"\n\
                        OpenJDK Runtime Environment (Temurin)(build 1.8.0_402-b06)\n\
                        OpenJDK 64-Bit Server VM (Temurin)(build 25.402-b06, mixed mode)";
        let (major, version, vendor) = parse_banner(temurin8).unwrap();
        assert_eq!(major, 8);
        assert_eq!(version, "1.8.0_402");
        assert_eq!(vendor.as_deref(), Some("OpenJDK"));

        let modern = "openjdk version \"21.0.1\" 2023-10-17 LTS\n\
                      OpenJDK Runtime Environment Temurin-21.0.1+12 (build 21.0.1+12-LTS)";
        let (major, _, _) = parse_banner(modern).unwrap();
        assert_eq!(major, 21);

        let oracle = "java version \"1.8.0_202\"\n\
                      Java(TM) SE Runtime Environment (build 1.8.0_202-b08)\n\
                      Java HotSpot(TM) 64-Bit Server VM (build 25.202-b08, mixed mode)";
        let (major, _, vendor) = parse_banner(oracle).unwrap();
        assert_eq!(major, 8);
        assert_eq!(vendor.as_deref(), Some("Oracle"));
    }

    #[test]
    fn a_banner_with_no_version_is_rejected() {
        assert!(parse_banner("something went wrong").is_none());
        assert!(parse_banner("").is_none());
    }

    #[test]
    fn compatibility_is_strict_about_1_8_9() {
        assert_eq!(compatibility(8, 8), Compatibility::Exact);
        assert!(compatibility(8, 8).is_runnable());

        // The research is explicit that 1.8.9 on a modern JVM needs the LWJGL 3
        // spike, which is not done. Claiming it works would be a lie.
        assert_eq!(compatibility(8, 17), Compatibility::NewerThanDeclared);
        assert!(!compatibility(8, 17).is_runnable());

        assert_eq!(compatibility(8, 7), Compatibility::TooOld);
        assert!(!compatibility(8, 7).is_runnable());
    }

    #[test]
    fn explanations_name_both_versions() {
        let text = Compatibility::NewerThanDeclared.explanation(8, 21);
        assert!(text.contains('8') && text.contains("21"));
        assert!(text.contains("LWJGL 2"));
    }

    fn install(major: u32) -> JavaInstallation {
        JavaInstallation {
            path: PathBuf::from(format!("/jdk{major}/bin/java")),
            major,
            version_string: format!("{major}.0.0"),
            vendor: None,
        }
    }

    #[test]
    fn selection_prefers_an_exact_match() {
        let list = vec![install(21), install(8), install(17)];
        assert_eq!(select(&list, 8).unwrap().major, 8);
    }

    #[test]
    fn selection_falls_back_to_the_closest_newer_version() {
        let list = vec![install(21), install(17)];
        assert_eq!(select(&list, 8).unwrap().major, 17);
    }

    #[test]
    fn selection_returns_nothing_when_only_older_versions_exist() {
        let list = vec![install(7)];
        assert!(select(&list, 8).is_none());
    }

    #[test]
    fn probing_a_nonexistent_path_fails_cleanly() {
        assert!(matches!(
            probe(Path::new("/definitely/not/here/java")),
            Err(JavaError::NotFound(_))
        ));
    }
}
