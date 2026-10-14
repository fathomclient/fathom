//! Installed mods, one at a time, and what to do when a launch goes wrong.
//!
//! The Installed tab's rows come from `api::mods::view`; this module is the
//! page behind a row (dependencies both ways, earlier builds, whether the file
//! is intact), the actions that change one entry (restore a build, show the
//! file), and the recovery page (`recovery`).
//!
//! Everything here reads the disk only, except the update check and the
//! identification of hand-added jars, which ask an index and so run on a worker
//! outside the launcher lock, like the browser's commands.

use serde::Serialize;
use tauri::Manager as _;

use launcher_core::mods::{ModId, ModSource, ProfileMod};
use launcher_core::profiles::Profile;
use launcher_core::recovery::mods::{ModRecoveryReport, RestoreReport};
use launcher_core::{Cancel, Launcher};

use super::mods::{kind_of, recorded_compatibility, source_label, RowCompatibility};
use crate::State;

fn name_of(entry: &ProfileMod) -> String {
    entry.origin.name.clone().unwrap_or_else(|| {
        entry
            .file_name
            .strip_suffix(".jar")
            .unwrap_or(&entry.file_name)
            .to_owned()
    })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Requirement {
    pub id: String,
    pub name: String,
    pub installed: bool,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EarlierBuild {
    pub sha1: String,
    pub version: Option<String>,
    pub file_name: String,
    /// When it stopped being used, in Unix seconds.
    pub replaced_at: u64,
    /// Whether its bytes are still on this computer, intact.
    pub available: bool,
}

/// One installed mod, with everything the launcher knows about it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledDetail {
    pub id: String,
    pub name: String,
    pub summary: String,
    pub kind: &'static str,
    pub source_label: String,
    pub enabled: bool,
    pub version: Option<String>,
    pub file_name: String,
    /// First twelve hex digits of the SHA-1, so two builds can be told apart.
    pub fingerprint: String,
    pub license: Option<String>,
    pub minecraft: Vec<String>,
    pub loaders: Vec<String>,
    pub compatibility: Option<RowCompatibility>,
    /// What this needs, as recorded when it was installed.
    pub requires: Vec<Requirement>,
    /// What needs this.
    pub required_by: Vec<String>,
    pub issues: Vec<String>,
    pub earlier: Vec<EarlierBuild>,
    /// The bytes are not in the store at all.
    pub missing: bool,
    /// The bytes are in the store but no longer match their hash.
    pub damaged: bool,
    /// Fathom can fetch this exact build again.
    pub repairable: bool,
    /// Fathom can check this for updates.
    pub updatable: bool,
    /// The index, project and build this came from, for the browser.
    pub provider: Option<String>,
    pub project: Option<String>,
    /// Why it is off, when it is off for a reason other than the user's.
    pub disabled_reason: Option<String>,
    pub game_running: bool,
}

pub fn detail(core: &Launcher, profile: &Profile, id: &str) -> Result<InstalledDetail, String> {
    let id = ModId::new(id).map_err(|_| "that is not a mod".to_owned())?;
    let entry = profile
        .mods
        .get(&id)
        .ok_or_else(|| "That mod is no longer in this profile.".to_owned())?;
    let store = core.mod_engine().store().clone();
    let missing = !store.contains(&entry.sha1);
    let damaged = !missing && !store.verify(&entry.sha1).unwrap_or(false);
    let resolution = core.resolve_mods(profile);
    let names = launcher_core::mods::NameLookup::from_definitions(&core.mod_catalogue())
        .with_profile(&profile.mods);
    let definition = core.mod_definition(&id);

    let requires = entry
        .origin
        .requires
        .iter()
        .map(|required| {
            let found = ModId::new(required)
                .ok()
                .and_then(|required| profile.mods.get(&required).cloned());
            Requirement {
                id: required.clone(),
                name: found.as_ref().map_or_else(|| required.clone(), name_of),
                installed: found.is_some(),
                enabled: found.as_ref().is_some_and(|f| f.enabled),
            }
        })
        .chain(definition.iter().flat_map(|definition| {
            definition.dependencies.iter().map(|dependency| {
                let found = profile.mods.get(&dependency.id);
                Requirement {
                    id: dependency.id.as_str().to_owned(),
                    name: names.of(&dependency.id),
                    installed: found.is_some(),
                    enabled: found.is_some_and(|f| f.enabled),
                }
            })
        }))
        .collect();

    let required_by = profile
        .mods
        .iter()
        .filter(|other| other.origin.requires.contains(id.as_str()))
        .map(name_of)
        .collect();

    let repairable = entry.origin.repository.is_some()
        && entry.origin.project.is_some()
        && entry.origin.version_id.is_some();

    Ok(InstalledDetail {
        id: id.as_str().to_owned(),
        name: definition
            .as_ref()
            .map_or_else(|| name_of(entry), |d| d.name.clone()),
        summary: entry
            .origin
            .summary
            .clone()
            .or_else(|| definition.as_ref().map(|d| d.summary.clone()))
            .unwrap_or_default(),
        kind: kind_of(entry),
        source_label: source_label(entry),
        enabled: entry.enabled,
        version: entry.version.as_ref().map(|v| v.as_str().to_owned()),
        file_name: entry.file_name.clone(),
        fingerprint: entry.sha1.chars().take(12).collect(),
        license: entry
            .origin
            .license
            .clone()
            .or_else(|| definition.as_ref().map(|d| d.license.describe())),
        minecraft: entry.origin.minecraft.iter().cloned().collect(),
        loaders: entry.origin.loaders.iter().cloned().collect(),
        compatibility: recorded_compatibility(entry, profile),
        requires,
        required_by,
        issues: resolution
            .problems
            .iter()
            .filter(|problem| problem.mods().contains(&&id))
            .map(|problem| problem.message(&names))
            .collect(),
        earlier: entry
            .previous
            .iter()
            .map(|build| EarlierBuild {
                sha1: build.sha1.clone(),
                version: build.version.as_ref().map(|v| v.as_str().to_owned()),
                file_name: build.file_name.clone(),
                replaced_at: build.replaced_at,
                available: store.verify(&build.sha1).unwrap_or(false),
            })
            .collect(),
        missing,
        damaged,
        repairable: repairable && entry.source != ModSource::FirstParty,
        // Components are kept current (and pinned) by Fathom itself.
        updatable: entry.origin.is_updatable() && kind_of(entry) == "external",
        provider: entry.origin.repository.clone(),
        project: entry.origin.project.clone(),
        disabled_reason: match &entry.disabled_reason {
            Some(launcher_core::mods::DisabledReason::Recovery { detail })
            | Some(launcher_core::mods::DisabledReason::Conflict { detail, .. }) => {
                Some(detail.clone())
            }
            _ => None,
        },
        game_running: core
            .game_status(&profile.id)
            .is_some_and(|status| status.is_running()),
    })
}

/// Show an installed mod's file in the system file manager.
///
/// The file in the profile's own `mods/` folder when it has been laid out
/// there, otherwise the copy in Fathom's mod store. The path is computed
/// here from the profile; the interface only names the mod.
pub fn reveal(core: &Launcher, profile: &Profile, id: &str) -> Result<(), String> {
    let id = ModId::new(id).map_err(|_| "that is not a mod".to_owned())?;
    let entry = profile
        .mods
        .get(&id)
        .ok_or_else(|| "That mod is no longer in this profile.".to_owned())?;
    let dirs = core.dirs_for(profile);
    let laid_out = dirs
        .contain_in_game(&format!("mods/{}", entry.file_name))
        .ok()
        .filter(|path| path.is_file());
    let path = match laid_out {
        Some(path) => path,
        None => core
            .mod_engine()
            .store()
            .path_for(&entry.sha1)
            .ok()
            .filter(|path| path.is_file())
            .ok_or_else(|| "The file is not on this computer.".to_owned())?,
    };
    crate::pages::reveal_file(&path)
}

// ---------------------------------------------------------------------------
// Recovery
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryView {
    pub profile_name: String,
    #[serde(flatten)]
    pub report: ModRecoveryReport,
    pub game_running: bool,
}

pub fn recovery(core: &Launcher, profile: &Profile) -> RecoveryView {
    RecoveryView {
        profile_name: profile.display_name.clone(),
        report: core.mod_recovery_report(profile),
        game_running: core
            .game_status(&profile.id)
            .is_some_and(|status| status.is_running()),
    }
}

pub fn restore_last_good(core: &Launcher, profile: &mut Profile) -> Result<RestoreReport, String> {
    core.restore_last_good_mods(profile)
        .map_err(|error| error.to_string())
}

pub fn restore_build(
    core: &Launcher,
    profile: &mut Profile,
    id: &str,
    sha1: &str,
) -> Result<String, String> {
    let id = ModId::new(id).map_err(|_| "that is not a mod".to_owned())?;
    let restored = core
        .restore_mod_build(profile, &id, sha1)
        .map_err(|error| error.to_string())?;
    Ok(format!(
        "Put back {}{}. It takes effect the next time Minecraft starts.",
        name_of(&restored),
        restored
            .version
            .as_ref()
            .map(|v| format!(" {}", v.as_str()))
            .unwrap_or_default()
    ))
}

// ---------------------------------------------------------------------------
// Asking an index
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateView {
    pub id: String,
    pub name: String,
    pub installed: Option<String>,
    pub latest: String,
    pub reference: launcher_core::mods::browse::ProjectRef,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdatesCheck {
    pub updates: Vec<UpdateView>,
    /// How many mods could be checked at all.
    pub checkable: usize,
    pub total: usize,
}

struct Taken {
    catalog: launcher_core::mods::browse::ModCatalog,
    profile: Profile,
}

fn take(app: &tauri::AppHandle, profile: Option<&str>) -> Result<Taken, String> {
    let state = app.state::<State>();
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned".to_owned())?;
    if let Some(reason) = core.browsing_blocked() {
        return Err(reason.to_owned());
    }
    Ok(Taken {
        catalog: core.mod_catalog(),
        profile: crate::commands::resolve_profile(&core, profile)?,
    })
}

/// Which mods from an index have a newer compatible build. On a worker.
pub async fn check_updates(
    app: tauri::AppHandle,
    profile: Option<String>,
) -> Result<UpdatesCheck, String> {
    let taken = take(&app, profile.as_deref())?;
    tauri::async_runtime::spawn_blocking(move || {
        let installed: Vec<ProfileMod> = taken.profile.mods.iter().cloned().collect();
        let grant = launcher_core::network::PerActionGrant::user_requested(
            "checking mods for updates".to_owned(),
        );
        // Only external mods: Fathom keeps its components current itself, and
        // pins some of them to builds it has verified.
        let external: Vec<ProfileMod> = installed
            .iter()
            .filter(|entry| kind_of(entry) == "external")
            .cloned()
            .collect();
        let found = taken
            .catalog
            .updates(
                &launcher_core::mods::browse::Target::of(&taken.profile),
                &external,
                &grant,
                &Cancel::new(),
            )
            .map_err(|error| error.user_message())?;
        let checkable = external
            .iter()
            .filter(|entry| entry.origin.is_updatable())
            .count();
        Ok(UpdatesCheck {
            updates: found
                .into_iter()
                .map(|candidate| {
                    let name = ModId::new(&candidate.id)
                        .ok()
                        .and_then(|id| taken.profile.mods.get(&id).map(name_of))
                        .unwrap_or_else(|| candidate.id.clone());
                    UpdateView {
                        id: candidate.id,
                        name,
                        installed: candidate.installed,
                        latest: candidate.latest.number,
                        reference: candidate.project,
                    }
                })
                .collect(),
            checkable,
            total: external.len(),
        })
    })
    .await
    .map_err(|_| "the update check stopped unexpectedly".to_owned())?
}

/// Look up hand-added jars by their exact bytes, and record what an index
/// says they are. Only fills in unknowns; returns how many were recognised.
pub async fn identify(app: tauri::AppHandle, profile: Option<String>) -> Result<usize, String> {
    let taken = take(&app, profile.as_deref())?;
    let profile_id = taken.profile.id.clone();
    let found = tauri::async_runtime::spawn_blocking(move || {
        let installed: Vec<ProfileMod> = taken
            .profile
            .mods
            .iter()
            .filter(|entry| entry.source == ModSource::UserSupplied)
            .cloned()
            .collect();
        let grant = launcher_core::network::PerActionGrant::user_requested(
            "identifying mods you added".to_owned(),
        );
        taken
            .catalog
            .identify(&installed, &grant, &Cancel::new())
            .map_err(|error| error.user_message())
    })
    .await
    .map_err(|_| "identification stopped unexpectedly".to_owned())??;
    if found.is_empty() {
        return Ok(0);
    }
    let state = app.state::<State>();
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned".to_owned())?;
    let mut current = crate::commands::resolve_profile(&core, Some(profile_id.as_str()))?;
    core.apply_identified(&mut current, &found)
        .map_err(|error| error.to_string())
}
