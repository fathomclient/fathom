//! Is this profile actually usable, and if not, what exactly is wrong?
//!
//! Validation here is deliberately *not* a boolean. "Invalid profile" is not
//! something a user can act on; "your Java path points at a file that no longer
//! exists" is. Every [`Defect`] names one specific thing and says whether the
//! launcher can fix it without being told to.
//!
//! What this module does **not** do: touch the network, hash 139 MB, or start a
//! JVM. It answers questions that can be answered from the profile and from
//! `stat`, so it is cheap enough to run when a screen opens. Whether the
//! *installation* is complete is the install engine's question
//! ([`crate::install::InstallationVerifier`]), and this module takes its answer
//! rather than duplicating it.

use std::path::PathBuf;

use super::model::{GameDirectory, Profile};
use super::{ProfileDirs, ProfileError, ProfileId};
use crate::minecraft_runtime::jvm_args;
use crate::paths::DataPaths;

/// One specific thing wrong with a profile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Defect {
    // -- data ------------------------------------------------------------
    /// The manifest would not parse, and no backup helped.
    CorruptManifest { detail: String },
    /// Written by a newer build.
    SchemaTooNew { found: u32, supported: u32 },
    /// The display name is empty or only whitespace.
    EmptyDisplayName,

    // -- directories -----------------------------------------------------
    /// A directory the profile needs is missing.
    MissingDirectory { path: PathBuf },
    /// The game directory is not a directory, or cannot be read.
    InvalidGameDirectory { path: PathBuf, why: String },
    /// The game directory points outside launcher storage. Allowed, but worth
    /// saying out loud, because it changes what deletion will do.
    ExternalGameDirectory { path: PathBuf },

    // -- loader ----------------------------------------------------------
    /// The chosen loader is not implemented in this build.
    LoaderNotImplemented { loader: super::Loader },
    /// A loader that needs its own version does not have one.
    LoaderVersionMissing { loader: super::Loader },
    /// A loader version is recorded against a loader that has none.
    LoaderVersionUnexpected { loader: super::Loader },
    /// Mods are configured but the loader cannot load them.
    ModsWithoutALoader { count: usize },
    /// A mod is installed that cannot work alone under this loader, and the
    /// component it needs is not installed.
    ///
    /// OptiFine under Legacy Fabric. The launch gate already refuses this
    /// (`LaunchRefusal::MissingBridge`), but a profile that cannot launch was
    /// still reporting **Healthy** here -- so the only way to find out was to
    /// press Play. A validator that disagrees with the launch gate is worse
    /// than no validator, because it is trusted.
    MissingBridge {
        component: &'static str,
        bridge: &'static str,
    },

    // -- runtime ---------------------------------------------------------
    /// The explicitly chosen Java executable is not there any more.
    JavaMissing { path: PathBuf },
    /// The heap size is outside anything sensible.
    HeapOutOfRange { heap_mb: u32 },
    /// A JVM argument the policy refuses.
    RejectedJvmArgument { argument: String, why: String },
    /// The window size would produce an unusable window.
    UnusableResolution { width: u32, height: u32 },

    // -- content ---------------------------------------------------------
    /// A mod entry names a file that is not in the mods directory.
    ModFileMissing { file_name: String },
    /// A mod entry's file name is not a safe path component.
    UnsafeModFileName { file_name: String },

    // -- installation ----------------------------------------------------
    /// The game files this profile needs are not complete. Reported by the
    /// caller passing an installation snapshot in; not measured here.
    InstallationIncomplete { detail: String },
}

impl Defect {
    /// A sentence naming the problem and what to do about it.
    pub fn message(&self) -> String {
        match self {
            Defect::CorruptManifest { detail } => {
                format!("The profile file could not be read and no backup was usable. {detail}")
            }
            Defect::SchemaTooNew { found, supported } => format!(
                "This profile was written by a newer version of Fathom (v{found}; this \
                 build understands v{supported}). Upgrade rather than opening it here."
            ),
            Defect::EmptyDisplayName => {
                "The profile has no name. Rename it to something you will recognise.".to_owned()
            }
            Defect::MissingDirectory { path } => format!(
                "A directory this profile needs is missing: {}. Repair recreates it.",
                path.display()
            ),
            Defect::InvalidGameDirectory { path, why } => format!(
                "The game directory at {} cannot be used: {why}",
                path.display()
            ),
            Defect::ExternalGameDirectory { path } => format!(
                "This profile's game directory is {}, which you chose rather than the \
                 launcher creating it. Deleting the profile will not delete it.",
                path.display()
            ),
            Defect::LoaderNotImplemented { loader } => format!(
                "{} is not implemented yet. Switch this profile to Vanilla to play.",
                loader.label()
            ),
            Defect::LoaderVersionMissing { loader } => format!(
                "No {} build is chosen yet. The first launch uses the newest stable one and                  keeps it.",
                loader.label()
            ),
            Defect::LoaderVersionUnexpected { loader } => format!(
                "A loader version is recorded, but {} does not use one. Repair clears it.",
                loader.label()
            ),
            Defect::MissingBridge { component, bridge } => format!(
                "{component} cannot run on its own under this loader. Install {bridge}, or                  remove {component}."
            ),
            Defect::ModsWithoutALoader { count } => format!(
                "{count} mod(s) are configured, but Vanilla cannot load mods. Switch to a \
                 loader, or remove them."
            ),
            Defect::JavaMissing { path } => format!(
                "The Java executable chosen for this profile is not at {} any more. \
                 Repair switches back to automatic detection.",
                path.display()
            ),
            Defect::HeapOutOfRange { heap_mb } => {
                format!("A heap of {heap_mb} MB is outside the usable range. Repair resets it.")
            }
            Defect::RejectedJvmArgument { argument, why } => {
                format!("The JVM argument {argument:?} is refused: {why}")
            }
            Defect::UnusableResolution { width, height } => {
                format!("A {width}x{height} window is not usable. Repair resets it.")
            }
            Defect::ModFileMissing { file_name } => format!(
                "The mod {file_name} is listed but its file is not in the mods folder. \
                 Repair removes the entry."
            ),
            Defect::UnsafeModFileName { file_name } => format!(
                "The mod entry {file_name:?} is not a safe file name and will not be used. \
                 Repair removes it."
            ),
            Defect::InstallationIncomplete { detail } => detail.clone(),
        }
    }

    /// Whether [`validate::repair`] can fix this without asking.
    ///
    /// The rule: repair may reset a setting to a safe default or drop an entry
    /// that names nothing. It may never delete the user's data, change which
    /// loader they chose, or download anything.
    pub fn is_auto_repairable(&self) -> bool {
        matches!(
            self,
            Defect::MissingDirectory { .. }
                | Defect::LoaderVersionUnexpected { .. }
                | Defect::JavaMissing { .. }
                | Defect::HeapOutOfRange { .. }
                | Defect::UnusableResolution { .. }
                | Defect::ModFileMissing { .. }
                | Defect::UnsafeModFileName { .. }
        )
    }

    /// Whether this stops the profile launching at all.
    pub fn is_blocking(&self) -> bool {
        matches!(
            self,
            Defect::CorruptManifest { .. }
                | Defect::SchemaTooNew { .. }
                | Defect::InvalidGameDirectory { .. }
                | Defect::LoaderNotImplemented { .. }
                // Not LoaderVersionMissing: Play resolves and pins the newest
                // stable build itself (`Launcher::ensure_loader`), so calling the
                // profile unusable for it was a claim the launch path disproved.
                | Defect::JavaMissing { .. }
                | Defect::RejectedJvmArgument { .. }
                | Defect::InstallationIncomplete { .. }
                | Defect::MissingBridge { .. }
        )
    }

    pub fn kind(&self) -> &'static str {
        match self {
            Defect::CorruptManifest { .. } => "corrupt-manifest",
            Defect::SchemaTooNew { .. } => "schema-too-new",
            Defect::EmptyDisplayName => "empty-display-name",
            Defect::MissingDirectory { .. } => "missing-directory",
            Defect::InvalidGameDirectory { .. } => "invalid-game-directory",
            Defect::ExternalGameDirectory { .. } => "external-game-directory",
            Defect::LoaderNotImplemented { .. } => "loader-not-implemented",
            Defect::LoaderVersionMissing { .. } => "loader-version-missing",
            Defect::LoaderVersionUnexpected { .. } => "loader-version-unexpected",
            Defect::ModsWithoutALoader { .. } => "mods-without-a-loader",
            Defect::MissingBridge { .. } => "missing-bridge",
            Defect::JavaMissing { .. } => "java-missing",
            Defect::HeapOutOfRange { .. } => "heap-out-of-range",
            Defect::RejectedJvmArgument { .. } => "rejected-jvm-argument",
            Defect::UnusableResolution { .. } => "unusable-resolution",
            Defect::ModFileMissing { .. } => "mod-file-missing",
            Defect::UnsafeModFileName { .. } => "unsafe-mod-file-name",
            Defect::InstallationIncomplete { .. } => "installation-incomplete",
        }
    }
}

/// Overall verdict.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileHealth {
    /// Nothing wrong.
    Healthy,
    /// Usable, but something is worth saying.
    Warnings,
    /// Cannot launch until something is fixed.
    Unusable,
}

impl ProfileHealth {
    pub fn label(self) -> &'static str {
        match self {
            ProfileHealth::Healthy => "Healthy",
            ProfileHealth::Warnings => "Usable, with warnings",
            ProfileHealth::Unusable => "Not usable",
        }
    }

    pub fn is_launchable(self) -> bool {
        !matches!(self, ProfileHealth::Unusable)
    }
}

/// What validation found.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileReport {
    pub profile: ProfileId,
    pub health: ProfileHealth,
    pub defects: Vec<Defect>,
}

impl ProfileReport {
    pub fn is_healthy(&self) -> bool {
        self.defects.is_empty()
    }

    pub fn blocking(&self) -> impl Iterator<Item = &Defect> {
        self.defects.iter().filter(|d| d.is_blocking())
    }

    pub fn auto_repairable(&self) -> impl Iterator<Item = &Defect> {
        self.defects.iter().filter(|d| d.is_auto_repairable())
    }
}

/// One thing a repair did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepairAction {
    pub defect: &'static str,
    pub description: String,
}

/// The result of repairing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepairSummary {
    pub actions: Vec<RepairAction>,
    /// Defects that were found but that a repair must not touch on its own.
    pub left_alone: Vec<Defect>,
}

impl RepairSummary {
    pub fn changed_anything(&self) -> bool {
        !self.actions.is_empty()
    }

    pub fn message(&self) -> String {
        if self.actions.is_empty() && self.left_alone.is_empty() {
            return "Nothing needed repairing.".to_owned();
        }
        let mut text = if self.actions.is_empty() {
            "Nothing could be repaired automatically.".to_owned()
        } else {
            format!(
                "Repaired {} thing{}.",
                self.actions.len(),
                if self.actions.len() == 1 { "" } else { "s" }
            )
        };
        if !self.left_alone.is_empty() {
            use std::fmt::Write as _;
            let _ = write!(
                text,
                " {} need{} a decision from you.",
                self.left_alone.len(),
                if self.left_alone.len() == 1 { "s" } else { "" }
            );
        }
        text
    }
}

/// Heap bounds a profile has to stay within.
///
/// The lower bound is where 1.8.9 stops starting; the upper is well past
/// anything useful and exists to catch a hand-edited or imported value rather
/// than to police taste. `jvm_args::judge_heap` handles the "this is more than
/// your machine has" judgement, which needs to know the machine.
const MIN_HEAP_MB: u32 = 512;
const MAX_HEAP_MB: u32 = 65_536;

/// Inspect a profile.
///
/// `installation` is passed in rather than measured, for the same reason
/// `Launcher::readiness` takes one: deciding whether an installation is
/// complete means hashing, and this function is meant to be cheap.
pub fn validate(
    paths: &DataPaths,
    profile: &Profile,
    installation: Option<&crate::InstallSnapshot>,
) -> ProfileReport {
    let mut defects = Vec::new();
    let dirs = ProfileDirs::with_game_directory(paths, &profile.id, &profile.game_directory);

    // -- data ------------------------------------------------------------
    if profile.display_name.trim().is_empty() {
        defects.push(Defect::EmptyDisplayName);
    }

    // -- directories -----------------------------------------------------
    match &profile.game_directory {
        GameDirectory::Managed => {
            for dir in dirs.all_dirs() {
                if !dir.is_dir() {
                    defects.push(Defect::MissingDirectory { path: dir });
                }
            }
        }
        GameDirectory::External { path } => {
            // Said out loud because it changes what deletion does, not because
            // it is wrong.
            defects.push(Defect::ExternalGameDirectory { path: path.clone() });
            if !path.is_dir() {
                defects.push(Defect::InvalidGameDirectory {
                    path: path.clone(),
                    why: "it is not a directory, or cannot be read".to_owned(),
                });
            }
            // The launcher's own directories are still ours and still required.
            for dir in [
                dirs.root().to_path_buf(),
                dirs.natives_dir(),
                dirs.logs_dir(),
            ] {
                if !dir.is_dir() {
                    defects.push(Defect::MissingDirectory { path: dir });
                }
            }
        }
    }

    check_loader(profile, &mut defects);
    check_runtime(profile, &mut defects);
    check_mods(profile, &dirs, &mut defects);

    // -- installation ----------------------------------------------------
    if let Some(snapshot) = installation {
        if !snapshot.is_playable() {
            defects.push(Defect::InstallationIncomplete {
                detail: snapshot.message(),
            });
        }
    }

    let health = if defects.iter().any(Defect::is_blocking) {
        ProfileHealth::Unusable
    } else if defects.is_empty() {
        ProfileHealth::Healthy
    } else {
        ProfileHealth::Warnings
    };

    ProfileReport {
        profile: profile.id.clone(),
        health,
        defects,
    }
}

/// Is the loader choice coherent, and can this build honour it?
fn check_loader(profile: &Profile, defects: &mut Vec<Defect>) {
    let loader = profile.loader.kind;
    if !loader.is_implemented() {
        defects.push(Defect::LoaderNotImplemented { loader });
    }
    if loader.needs_version() && profile.loader.version.is_none() {
        defects.push(Defect::LoaderVersionMissing { loader });
    }
    if !loader.needs_version() && profile.loader.version.is_some() {
        defects.push(Defect::LoaderVersionUnexpected { loader });
    }
    if !loader.supports_mods() && !profile.mods.is_empty() {
        defects.push(Defect::ModsWithoutALoader {
            count: profile.mods.len(),
        });
    }
}

/// Java, memory, flags and window size.
fn check_runtime(profile: &Profile, defects: &mut Vec<Defect>) {
    if let Some(path) = profile.java.explicit_path() {
        if !path.is_file() {
            defects.push(Defect::JavaMissing {
                path: path.to_path_buf(),
            });
        }
    }
    if !(MIN_HEAP_MB..=MAX_HEAP_MB).contains(&profile.memory.heap_mb) {
        defects.push(Defect::HeapOutOfRange {
            heap_mb: profile.memory.heap_mb,
        });
    }
    for (argument, rejection) in jvm_args::validate_args(&profile.jvm.extra_args).rejected {
        defects.push(Defect::RejectedJvmArgument {
            argument,
            why: rejection.to_string(),
        });
    }
    if !profile.display.resolution.is_sane() {
        defects.push(Defect::UnusableResolution {
            width: profile.display.resolution.width,
            height: profile.display.resolution.height,
        });
    }
}

/// Every mod entry names a file that is both safe and present.
fn check_mods(profile: &Profile, dirs: &ProfileDirs, defects: &mut Vec<Defect>) {
    // The same question the launch gate asks, asked here so the answer is the
    // same in both places. See `components::missing_bridge`.
    if let Some((component, bridge)) = crate::components::missing_bridge(profile) {
        defects.push(Defect::MissingBridge {
            component: component.name,
            bridge: bridge.name,
        });
    }
    for entry in &profile.mods.mods {
        match dirs.contain_in_game(&format!("mods/{}", entry.file_name)) {
            Err(_) => defects.push(Defect::UnsafeModFileName {
                file_name: entry.file_name.clone(),
            }),
            Ok(path) if !path.is_file() => defects.push(Defect::ModFileMissing {
                file_name: entry.file_name.clone(),
            }),
            Ok(_) => {}
        }
    }
}

/// Fix what can be fixed without a decision.
///
/// Mutates `profile` in place and creates directories. Never deletes user data,
/// never changes the loader, never downloads. The caller saves the profile if
/// [`RepairSummary::changed_anything`].
pub fn repair(
    paths: &DataPaths,
    profile: &mut Profile,
    installation: Option<&crate::InstallSnapshot>,
) -> Result<RepairSummary, ProfileError> {
    let report = validate(paths, profile, installation);
    let dirs = ProfileDirs::with_game_directory(paths, &profile.id, &profile.game_directory);

    let mut actions = Vec::new();
    let mut left_alone = Vec::new();

    for defect in &report.defects {
        if !defect.is_auto_repairable() {
            left_alone.push(defect.clone());
            continue;
        }
        match defect {
            Defect::MissingDirectory { path } => {
                std::fs::create_dir_all(path).map_err(|source| ProfileError::Io {
                    path: path.display().to_string(),
                    source,
                })?;
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: format!("Recreated {}", path.display()),
                });
            }
            Defect::LoaderVersionUnexpected { .. } => {
                profile.loader.version = None;
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: "Cleared a loader version that does not apply".to_owned(),
                });
            }
            Defect::JavaMissing { path } => {
                profile.java = super::model::JavaPreference::Automatic;
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: format!(
                        "Switched to automatic Java detection; {} is gone",
                        path.display()
                    ),
                });
            }
            Defect::HeapOutOfRange { heap_mb } => {
                let was = *heap_mb;
                profile.memory = super::model::MemoryConfig::default();
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: format!(
                        "Reset the heap from {was} MB to {} MB",
                        profile.memory.heap_mb
                    ),
                });
            }
            Defect::UnusableResolution { width, height } => {
                let (w, h) = (*width, *height);
                profile.display.resolution = super::model::Resolution::default();
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: format!(
                        "Reset the window size from {w}x{h} to {}x{}",
                        profile.display.resolution.width, profile.display.resolution.height
                    ),
                });
            }
            Defect::ModFileMissing { file_name } | Defect::UnsafeModFileName { file_name } => {
                // Removes the *entry*, never a file. If a jar is there and the
                // entry is unsafe, the jar stays where the user put it.
                let name = file_name.clone();
                profile.mods.mods.retain(|m| m.file_name != name);
                actions.push(RepairAction {
                    defect: defect.kind(),
                    description: format!("Removed the mod entry for {name}"),
                });
            }
            other => left_alone.push(other.clone()),
        }
    }

    // Anything that repaired a directory may have depended on the root existing.
    if !actions.is_empty() {
        dirs.ensure()?;
    }

    Ok(RepairSummary {
        actions,
        left_alone,
    })
}
