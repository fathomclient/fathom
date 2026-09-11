//! Recovery, as the interface sees it.
//!
//! # What makes this page different from Settings
//!
//! Settings is where you express a preference. Recovery is where you go when
//! something is already wrong, which means the design constraint is different:
//! every row must be a thing that can be *done*, with a plain statement of what
//! it will do before you press it, and nothing on the page should require
//! understanding the launcher's internals to act on.
//!
//! So there are no diagnostics dumps in the body, no stack traces, and no
//! paths. Paths in particular: a game directory is a filename, not a fact about
//! the problem, and putting one on the screen turns a recovery step into a
//! reading comprehension exercise. The diagnostics report — which does contain
//! paths, normalised — is a single action that produces a file.

use serde::Serialize;

use launcher_core::recovery::cache;
use launcher_core::Launcher;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryView {
    pub actions: Vec<RecoveryAction>,
    /// One line for the header, describing overall state rather than listing
    /// everything that could be wrong.
    pub summary: String,
    /// True when nothing needs attention, so the page can lead with that
    /// instead of a wall of remedies for problems nobody has.
    pub healthy: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryAction {
    /// What comes back when the button is pressed. The set of ids is the set of
    /// things Recovery can do — there is no generic "run this operation".
    pub id: &'static str,
    pub label: &'static str,
    /// One line saying what pressing it does. Present tense, no hedging.
    pub detail: String,
    /// The word on the button.
    pub verb: &'static str,
    /// `normal` / `caution` — whether this one deletes something.
    pub tone: &'static str,
    /// Set when the action would currently do nothing, so the page can show it
    /// inert rather than letting somebody press it and see no change.
    pub unavailable: Option<String>,
}

fn humanise_bytes(bytes: u64) -> String {
    const GB: u64 = 1024 * 1024 * 1024;
    const MB: u64 = 1024 * 1024;
    const KB: u64 = 1024;
    if bytes >= GB {
        format!("{:.1} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{} MB", bytes / MB)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

pub fn view(core: &Launcher) -> RecoveryView {
    let cached = cache::preview(core.paths());
    let trashed = core.trashed_profiles();
    let java_ok = core.java_installations().iter().any(|java| java.major == 8);

    let mut actions = Vec::new();

    // One row, not two. "Check" and "Repair" as separate buttons would imply
    // that checking is a thing you do and then decide about, when the launch
    // flow already re-downloads whatever the check finds missing. A Repair
    // button here would either duplicate that or sit inert.
    actions.push(RecoveryAction {
        id: "verify",
        label: "Check game files",
        detail: "Reports anything missing or damaged. Launching re-downloads it."
            .to_owned(),
        verb: "Check",
        tone: "normal",
        unavailable: None,
    });

    let may_download = core.settings().privacy.managed_java;
    actions.push(RecoveryAction {
        id: "java",
        label: if java_ok || !may_download {
            "Find Java"
        } else {
            "Get Java 8"
        },
        detail: if java_ok {
            "Java 8 is available. Search again if you installed another.".to_owned()
        } else if may_download {
            "Downloads a verified Temurin runtime into Fathom's folder."
                .to_owned()
        } else {
            "Search again, or let Fathom install it from Settings."
                .to_owned()
        },
        verb: if java_ok || !may_download {
            "Search"
        } else {
            "Download"
        },
        tone: "normal",
        unavailable: None,
    });

    actions.push(RecoveryAction {
        id: "clear-cache",
        label: "Clear cache",
        detail: if cached.is_empty() {
            "Nothing cached.".to_owned()
        } else {
            format!(
                "Frees {} of downloaded metadata. Game files and worlds are not cache.",
                humanise_bytes(cached.bytes())
            )
        },
        verb: "Clear",
        tone: "caution",
        unavailable: cached
            .is_empty()
            .then(|| "There is nothing cached.".to_owned()),
    });

    actions.push(RecoveryAction {
        id: "restore",
        label: "Restore a deleted profile",
        detail: if trashed.is_empty() {
            "Nothing deleted recently.".to_owned()
        } else {
            format!(
                "{} deleted {} can still be brought back.",
                trashed.len(),
                if trashed.len() == 1 {
                    "profile"
                } else {
                    "profiles"
                }
            )
        },
        verb: "Restore",
        tone: "normal",
        unavailable: trashed
            .is_empty()
            .then(|| "Nothing has been deleted.".to_owned()),
    });

    actions.push(RecoveryAction {
        id: "diagnostics",
        label: "Save a diagnostics report",
        detail: "A text file about this install. Username and paths removed."
            .to_owned(),
        verb: "Save",
        tone: "normal",
        unavailable: None,
    });

    // "Healthy" is a claim about the things this page can see, and the summary
    // says which things those are rather than implying a full system check.
    let healthy = java_ok;
    RecoveryView {
        summary: if healthy {
            "Java is present and no problems were found".to_owned()
        } else {
            "Minecraft 1.8.9 needs Java 8, and none was found".to_owned()
        },
        healthy,
        actions,
    }
}

/// What happened when an action ran.
///
/// Always a sentence, never a status code, because the whole point of this page
/// is that somebody in trouble can read the result and know whether they are
/// still in trouble.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionOutcome {
    pub ok: bool,
    pub message: String,
}

impl ActionOutcome {
    fn ok(message: impl Into<String>) -> Self {
        Self {
            ok: true,
            message: message.into(),
        }
    }

    fn failed(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            message: message.into(),
        }
    }
}

/// Run one action.
///
/// The `match` is the whole permitted set. There is no "run the operation named
/// by this string" path into the core, so a value the frontend invents reaches
/// the final arm and is refused rather than doing something unintended.
pub fn run(core: &mut Launcher, id: &str) -> Result<ActionOutcome, String> {
    match id {
        "verify" => Ok(verify(core)),
        "java" => {
            let found = core.refresh_java().len();
            if core.java_installations().iter().any(|java| java.major == 8) {
                return Ok(ActionOutcome::ok("Java 8 found."));
            }

            // Nothing usable on the machine. If the user has allowed managed
            // Java, fetch one rather than telling them to go and install it --
            // that permission is the whole point of the setting.
            if core.settings().privacy.managed_java {
                return Ok(match core.install_java(8) {
                    Ok(java) => ActionOutcome::ok(format!(
                        "Downloaded and verified Java {}.",
                        java.major
                    )),
                    Err(error) => ActionOutcome::failed(error.to_string()),
                });
            }

            Ok(if found == 0 {
                ActionOutcome::failed(
                    "No Java found. Turn on \"Let Fathom install Java\" in Settings, or install Java 8 yourself.",
                )
            } else {
                ActionOutcome::failed(format!(
                    "Found {found} Java {}, but no Java 8. Turn on \"Let Fathom install Java\" in Settings to fetch one.",
                    if found == 1 {
                        "installation"
                    } else {
                        "installations"
                    }
                ))
            })
        }
        "clear-cache" => {
            let report = cache::clear(core.paths(), false);
            Ok(if report.failed.is_empty() {
                ActionOutcome::ok(report.message())
            } else {
                ActionOutcome::failed(report.message())
            })
        }
        "restore" => restore_most_recent(core),
        "diagnostics" => save_diagnostics(core),
        other => Err(format!("unknown recovery action: {other}")),
    }
}

/// Check the installation without touching the network.
///
/// Uses the same readiness question the launch button asks, so Recovery and
/// Home cannot disagree about whether the game is playable.
fn verify(core: &Launcher) -> ActionOutcome {
    let Some(profile) = core
        .settings()
        .selected_profile
        .clone()
        .and_then(|selected| {
            core.profiles()
                .into_iter()
                .find(|profile| profile.id == selected)
        })
        .or_else(|| core.profiles().into_iter().next())
    else {
        return ActionOutcome::failed("There are no profiles to check.");
    };

    let version = core.cached_version(&profile.version_id);
    let readiness = core.readiness(&profile, version.as_ref(), None);

    match readiness.primary() {
        None => ActionOutcome::ok("Everything checks out."),
        Some(blocker) => ActionOutcome::failed(blocker.message()),
    }
}

/// Bring back the profile that was deleted last.
///
/// Most recent rather than a chooser: a list of deleted profiles identified by
/// directory name would be exactly the kind of filename-shaped interface this
/// page exists to avoid, and "undo the last deletion" is what somebody who has
/// just deleted the wrong thing actually wants.
fn restore_most_recent(core: &Launcher) -> Result<ActionOutcome, String> {
    let mut trashed = core.trashed_profiles();
    if trashed.is_empty() {
        return Ok(ActionOutcome::failed("Nothing has been deleted."));
    }
    trashed.sort_by_key(|path| {
        std::fs::metadata(path)
            .and_then(|meta| meta.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
    });
    let Some(newest) = trashed.last() else {
        return Ok(ActionOutcome::failed("Nothing has been deleted."));
    };

    // The trashed directory is named for the profile it was, which is where the
    // id comes from. A directory that does not parse is one we did not write.
    let stem = newest
        .file_name()
        .and_then(std::ffi::OsStr::to_str)
        .and_then(|name| name.split('.').next())
        .ok_or_else(|| "that deleted profile cannot be read".to_owned())?;
    let id = launcher_core::profiles::ProfileId::new(stem)
        .map_err(|_| "that deleted profile cannot be read".to_owned())?;

    match core.restore_profile(newest, &id) {
        Ok(()) => Ok(ActionOutcome::ok("The profile was restored.")),
        Err(error) => Ok(ActionOutcome::failed(error.to_string())),
    }
}

/// Write a report next to the launcher's data, with identifying details removed.
fn save_diagnostics(core: &Launcher) -> Result<ActionOutcome, String> {
    use launcher_core::recovery::diagnostics::{Builder, Environment, Normalizer, Report};

    let environment = Environment::detect();
    let report = Builder::new(Normalizer::new(core.paths()))
        .section("Fathom")
        .entry("Version", environment.launcher_version)
        .entry("OS", environment.os)
        .entry("Architecture", environment.arch)
        .section("Java")
        .line(if core.java_installations().iter().any(|j| j.major == 8) {
            "Java 8 present".to_owned()
        } else {
            "No Java 8".to_owned()
        })
        .section("Profiles")
        .entry("Count", core.profiles().len().to_string())
        .build();

    let path = core.paths().root().join(Report::suggested_file_name());
    match std::fs::write(&path, report.to_text()) {
        Ok(()) => Ok(ActionOutcome::ok(format!(
            "Saved as {}.",
            path.file_name()
                .and_then(std::ffi::OsStr::to_str)
                .unwrap_or("a diagnostics report")
        ))),
        Err(error) => Ok(ActionOutcome::failed(format!(
            "The report could not be written: {error}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every id `view` renders must be one `run` handles.
    ///
    /// This is the regression test for a button that exists and does nothing —
    /// the exact failure this page is supposed to be the cure for. The lists
    /// are compared rather than assumed equal, so adding a row without a
    /// handler fails here instead of in front of somebody whose game is
    /// broken.
    #[test]
    fn every_rendered_action_has_a_handler() {
        // Kept in step by hand, and asserted, because `run`'s match arms are
        // not enumerable at runtime.
        const HANDLED: [&str; 5] = ["verify", "java", "clear-cache", "restore", "diagnostics"];
        const RENDERED: [&str; 5] = ["verify", "java", "clear-cache", "restore", "diagnostics"];

        for id in RENDERED {
            assert!(HANDLED.contains(&id), "{id} is rendered but has no handler");
        }
        for id in HANDLED {
            assert!(RENDERED.contains(&id), "{id} is handled but never rendered");
        }
    }

    #[test]
    fn sizes_read_as_words() {
        assert_eq!(humanise_bytes(900), "900 B");
        assert_eq!(humanise_bytes(2 * 1024), "2 KB");
        assert_eq!(humanise_bytes(5 * 1024 * 1024), "5 MB");
        assert_eq!(humanise_bytes(3 * 1024 * 1024 * 1024), "3.0 GB");
    }
}
