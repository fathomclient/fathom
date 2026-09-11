//! The end-to-end check: everything except the authenticated token.
//!
//! # Why this exists
//!
//! Production Microsoft authentication is blocked on an approval Fathom does
//! not hold (`docs/research/auth-feasibility.md` §11). That blocks the last
//! step of launching and nothing else — but "nothing else" is easy to claim and
//! hard to believe without evidence, and a launcher whose pipeline quietly
//! rotted while it waited for an approval would look identical from the outside.
//!
//! So this walks the whole chain and reports on each stage:
//!
//! ```text
//!   metadata -> libraries -> assets -> natives -> java -> loader
//!            -> mods -> performance config -> launch plan -> [TOKEN]
//! ```
//!
//! Every stage before `[TOKEN]` is verified for real: files on disk, hashes
//! checked, a plan assembled and validated. The token stage is reported as
//! blocked, with the reason, and is never faked.
//!
//! # What it does not do
//!
//! It does not start Minecraft. A launch needs a session, and there is no
//! legitimate way to obtain one without the approval. See `--help` for the two
//! developer workflows that do run the game.

use std::fmt::Write as _;
use std::path::Path;

use launcher_core::network::Feature;
use launcher_core::profiles::{Loader, Profile};
use launcher_core::tasks::Cancel;
use launcher_core::Launcher;

/// One stage's result.
///
/// `Blocked` is deliberately distinct from `Failed`. A stage that cannot run
/// because of an external approval is not a defect in this code, and colouring
/// them the same would either make a healthy pipeline look broken or make a
/// broken one look expected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Passed,
    Failed,
    Blocked,
    Skipped,
}

impl Status {
    const fn mark(self) -> &'static str {
        match self {
            Status::Passed => "PASS",
            Status::Failed => "FAIL",
            Status::Blocked => "BLOCKED",
            Status::Skipped => "SKIP",
        }
    }
}

pub struct Stage {
    pub name: &'static str,
    pub status: Status,
    pub detail: String,
}

pub struct Report {
    pub stages: Vec<Stage>,
}

impl Report {
    fn push(&mut self, name: &'static str, status: Status, detail: impl Into<String>) {
        let stage = Stage {
            name,
            status,
            detail: detail.into(),
        };
        println!("  {:<7} {:<22} {}", stage.status.mark(), stage.name, stage.detail);
        self.stages.push(stage);
    }

    /// Whether everything that *could* run did.
    ///
    /// `Blocked` does not count against this: the whole point is to report a
    /// healthy pipeline behind an external blocker as healthy.
    pub fn ok(&self) -> bool {
        !self.stages.iter().any(|stage| stage.status == Status::Failed)
    }

    pub fn summary(&self) -> String {
        let count = |want: Status| self.stages.iter().filter(|s| s.status == want).count();
        let mut out = String::new();
        let _ = write!(
            out,
            "{} passed, {} failed, {} blocked, {} skipped",
            count(Status::Passed),
            count(Status::Failed),
            count(Status::Blocked),
            count(Status::Skipped)
        );
        out
    }
}

/// Run the whole chain against one profile.
///
/// `offline` skips every stage that would open a socket, so the check is still
/// useful on a machine with no network or with the launcher in offline mode.
pub fn run(core: &mut Launcher, profile: &Profile, offline: bool) -> Report {
    let mut report = Report { stages: Vec::new() };

    println!("Integration check: {} ({})", profile.display_name, profile.version_id);
    println!("  loader: {}", profile.loader_kind().label());
    println!();

    // -- 1. metadata ------------------------------------------------------
    let version = match core.cached_version(&profile.version_id) {
        Some(version) => {
            report.push("metadata", Status::Passed, format!("{} (cached)", version.id));
            Some(version)
        }
        None if offline => {
            report.push("metadata", Status::Skipped, "offline, and not cached");
            None
        }
        None => match core.fetch_version(&profile.version_id) {
            Ok(version) => {
                report.push("metadata", Status::Passed, format!("{} (fetched)", version.id));
                Some(version)
            }
            Err(error) => {
                report.push("metadata", Status::Failed, error.to_string());
                None
            }
        },
    };

    let Some(version) = version else {
        report.push("everything after", Status::Skipped, "no version metadata");
        return report;
    };

    // -- 2. artefact resolution -------------------------------------------
    let service = core.install_service();
    let resolved = match service.resolve_cached(&version, Feature::ProfileInstall) {
        Ok(resolved) => resolved,
        Err(error) => {
            report.push("resolve", Status::Failed, error.to_string());
            return report;
        }
    };
    report.push(
        "resolve",
        Status::Passed,
        format!(
            "{} libraries, {} assets, {} natives, java {}",
            resolved.libraries.len(),
            resolved.assets.len(),
            resolved.natives.len(),
            resolved.required_java_major
        ),
    );

    // -- 3. what is actually on disk --------------------------------------
    let dirs = core.dirs_for(profile);
    let check = service.quick_check(&resolved, &dirs.natives_dir());
    let defects = check.defects.len();
    report.push(
        "installed files",
        if defects == 0 { Status::Passed } else { Status::Failed },
        if defects == 0 {
            format!("{} of {} verified", check.verified, check.total)
        } else {
            format!("{defects} defective or missing; run `fathom-cli install`")
        },
    );

    // -- 4. java ----------------------------------------------------------
    core.refresh_java();
    let required = resolved.required_java_major;
    match core
        .java_installations()
        .iter()
        .find(|java| java.major == required)
    {
        Some(java) => report.push(
            "java",
            Status::Passed,
            format!("java {} at {}", java.major, short(&java.path)),
        ),
        None => report.push(
            "java",
            Status::Failed,
            format!("no java {required}; enable managed Java or install one"),
        ),
    }

    // -- 5. loader --------------------------------------------------------
    if profile.loader_kind() == Loader::Vanilla {
        report.push("loader", Status::Skipped, "vanilla profile");
    } else if offline {
        report.push("loader", Status::Skipped, "offline");
    } else {
        match core.resolve_loader(profile) {
            Ok(installation) => {
                let main = installation.main_class.as_deref().unwrap_or("(vanilla)");
                report.push(
                    "loader",
                    Status::Passed,
                    format!(
                        "{} {}, {} libraries, main class {main}",
                        installation.loader.label(),
                        installation.loader_version,
                        installation.libraries.len()
                    ),
                );

                // The libraries have to be on disk for the classpath to mean
                // anything, so this fetches them the same way a launch would.
                let cancel = Cancel::new();
                match core.install_service().downloads().fetch_all(
                    &installation.libraries,
                    &cancel,
                    &mut |_| {},
                ) {
                    Ok(_) => report.push(
                        "loader libraries",
                        Status::Passed,
                        format!("{} verified on disk", installation.libraries.len()),
                    ),
                    Err(error) => {
                        report.push("loader libraries", Status::Failed, error.to_string());
                    }
                }
            }
            Err(error) => report.push("loader", Status::Failed, error.to_string()),
        }
    }

    // -- 6. mods ----------------------------------------------------------
    if profile.loader_kind().supports_mods() {
        let mut copy = profile.clone();
        match core.materialize_mods(&mut copy) {
            Ok(outcome) => {
                let directory = dirs.mods_dir();
                let on_disk = std::fs::read_dir(&directory)
                    .map(|entries| {
                        entries
                            .flatten()
                            .filter(|e| {
                                e.path()
                                    .extension()
                                    .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
                            })
                            .count()
                    })
                    .unwrap_or(0);
                report.push(
                    "mods",
                    Status::Passed,
                    format!("{on_disk} jar(s) in the effective mods directory"),
                );

                // The module is the one mod the launcher ships, so whether it
                // reached the directory is worth reporting on its own.
                let module_present = std::fs::read_dir(&directory)
                    .map(|entries| {
                        entries.flatten().any(|e| {
                            e.file_name()
                                .to_string_lossy()
                                .contains("fathom-optimizations")
                        })
                    })
                    .unwrap_or(false);
                report.push(
                    "performance module",
                    if module_present { Status::Passed } else { Status::Skipped },
                    if module_present {
                        "present in the mods directory".to_owned()
                    } else {
                        "not added to this profile".to_owned()
                    },
                );
                let _ = outcome;
            }
            Err(error) => report.push("mods", Status::Failed, error.to_string()),
        }
    } else {
        report.push("mods", Status::Skipped, "this loader reads no mods directory");
        report.push("performance module", Status::Skipped, "needs a mod loader");
    }

    // -- 7. the config bridge ---------------------------------------------
    match launcher_core::optimizations::bridge::Settings::from_profile(profile)
        .write_to(&dirs.minecraft_dir())
    {
        Ok(path) => {
            let switches = std::fs::read_to_string(&path)
                .map(|text| text.lines().filter(|line| line.starts_with("opt.")).count())
                .unwrap_or(0);
            report.push(
                "performance config",
                Status::Passed,
                format!("{switches} switches written for the module to read"),
            );
        }
        Err(error) => report.push("performance config", Status::Failed, error.to_string()),
    }

    // -- 8. the launch plan -----------------------------------------------
    //
    // This is the stage the approval actually blocks: assembling a plan needs
    // an identity, because the command line contains the username, UUID and
    // access token. Everything above is genuinely verified; this is reported
    // honestly as blocked rather than stubbed.
    match core.build_launch_plan(profile, &version) {
        Ok(plan) => report.push(
            "launch plan",
            Status::Passed,
            format!(
                "{} classpath entries, main class {}",
                plan.classpath().len(),
                plan.main_class()
            ),
        ),
        Err(error) => {
            let text = error.to_string();
            let is_auth = matches!(
                error,
                launcher_core::launching::LaunchRefusal::NotAuthorised(_)
            );
            report.push(
                "launch plan",
                if is_auth { Status::Blocked } else { Status::Failed },
                text,
            );
        }
    }

    report.push(
        "authenticated launch",
        Status::Blocked,
        "needs an approved Microsoft application id; see docs/research/auth-feasibility.md",
    );

    report
}

/// A path with the user's home directory trimmed off.
fn short(path: &Path) -> String {
    let text = path.display().to_string();
    std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .ok()
        .and_then(|home| text.strip_prefix(&home).map(|rest| format!("~{rest}")))
        .unwrap_or(text)
}
