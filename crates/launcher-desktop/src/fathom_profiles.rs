//! The Fathom Profile collection, for the launcher's interface.
//!
//! These commands open the same `profiles/<id>.json` files the running game
//! reads and writes; see `docs/fathom-profiles.md`. There is no launcher copy.
//!
//! # What "switch" means from out here
//!
//! It moves `lastProfileId`. The launcher has no channel into a running game's
//! module registry — the bridge protocol is a closed four-verb enum on purpose,
//! and "reach into the game and rewrite its settings" is not a verb it should
//! grow — so a switch made here is the profile the next game opens. A game that
//! is already running keeps its live profile until somebody switches it in game,
//! where the switch is immediate.
//!
//! # Every command returns the whole view
//!
//! The same rule as the rest of the command surface: a page that applied an
//! optimistic update the store then refused would drift from what is on disk.

use launcher_core::loadout::{summarise, ProfileId, ProfileStore, ProfileSummary};
use serde::Serialize;

use crate::State;

/// The collection as the Profiles page shows it.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FathomProfilesView {
    pub profiles: Vec<ProfileSummary>,
    /// The profile the next game opens.
    pub live: String,
    /// Documents that would not load, by file name, with the reason.
    pub problems: Vec<String>,
}

/// Open the collection. The lock is held only long enough to read the path.
fn store(state: &State) -> Result<ProfileStore, String> {
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned".to_owned())?;
    Ok(ProfileStore::new(core.paths().fathom_profiles_dir()))
}

fn view_of(store: &ProfileStore) -> Result<FathomProfilesView, String> {
    // Guarantees exactly one Default in an empty collection, and repairs a
    // pointer that names a profile which is gone. Never creates a second.
    let live = store.resolve_live().map_err(|e| e.to_string())?;
    let problems = store
        .list()
        .complaints
        .into_iter()
        .map(|complaint| {
            let name = complaint
                .path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            format!("{name}: {}", complaint.reason)
        })
        .collect();
    Ok(FathomProfilesView {
        profiles: summarise(store),
        live: live.id.as_str().to_owned(),
        problems,
    })
}

fn parse(id: &str) -> Result<ProfileId, String> {
    ProfileId::new(id).map_err(|e| e.to_string())
}

fn name_of(raw: &str) -> Result<&str, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("A profile needs a name.".to_owned());
    }
    Ok(trimmed)
}

#[tauri::command]
pub fn fathom_profiles_view(state: tauri::State<'_, State>) -> Result<FathomProfilesView, String> {
    view_of(&store(&state)?)
}

/// Make `id` the profile the next game opens.
#[tauri::command]
pub fn fathom_profile_switch(
    state: tauri::State<'_, State>,
    id: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    let id = parse(&id)?;
    // Refused rather than recorded: a pointer at nothing would be repaired on
    // the next read, silently, to something the user did not pick.
    store.load(&id).map_err(|e| e.to_string())?;
    store.set_last_profile_id(&id).map_err(|e| e.to_string())?;
    view_of(&store)
}

/// A new profile at every module's defaults, made the next one to open.
#[tauri::command]
pub fn fathom_profile_create(
    state: tauri::State<'_, State>,
    name: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    let created = store.create(name_of(&name)?).map_err(|e| e.to_string())?;
    store
        .set_last_profile_id(&created.id)
        .map_err(|e| e.to_string())?;
    view_of(&store)
}

#[tauri::command]
pub fn fathom_profile_rename(
    state: tauri::State<'_, State>,
    id: String,
    name: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    store
        .rename(&parse(&id)?, name_of(&name)?)
        .map_err(|e| e.to_string())?;
    view_of(&store)
}

#[tauri::command]
pub fn fathom_profile_duplicate(
    state: tauri::State<'_, State>,
    id: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    let id = parse(&id)?;
    let source = store.load(&id).map_err(|e| e.to_string())?;
    store
        .duplicate(&id, &format!("{} copy", source.name))
        .map_err(|e| e.to_string())?;
    view_of(&store)
}

/// Remove a profile. The last one is refused by the store, not here, so the
/// game and the launcher cannot disagree about it.
#[tauri::command]
pub fn fathom_profile_delete(
    state: tauri::State<'_, State>,
    id: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    store.delete(&parse(&id)?).map_err(|e| e.to_string())?;
    view_of(&store)
}

/// The document, for the interface to offer as a download or a copy.
#[tauri::command]
pub fn fathom_profile_export(state: tauri::State<'_, State>, id: String) -> Result<String, String> {
    store(&state)?
        .export(&parse(&id)?)
        .map_err(|e| e.to_string())
}

/// Take a document from elsewhere, under a free id; never an overwrite.
#[tauri::command]
pub fn fathom_profile_import(
    state: tauri::State<'_, State>,
    json: String,
) -> Result<FathomProfilesView, String> {
    let store = store(&state)?;
    store.import(&json).map_err(|e| e.to_string())?;
    view_of(&store)
}
