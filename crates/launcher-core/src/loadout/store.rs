//! The one Fathom Profile collection.
//!
//! `profiles/<id>.json` plus `client-state.json`, in the launcher's data root.
//! Both the launcher and the running game open this same directory — see
//! `docs/fathom-profiles.md` for how the game finds it. There is no second
//! copy, no synchronisation step, and therefore no way for the two to disagree
//! about what a profile contains.
//!
//! # Why the directory is the index
//!
//! There is no `index.json` listing the profiles. Listing the directory is the
//! listing, so a profile cannot exist in the index and not on disk, or the
//! reverse. An index would buy one `readdir` on a collection that is realistically
//! a handful of files, and cost a class of bug where deleting a profile leaves a
//! ghost in a list.
//!
//! # Failure is per profile
//!
//! One unreadable document does not fail the load. [`ProfileStore::list`]
//! returns what parsed and reports what did not, because a corrupt Bedwars
//! profile is not a reason to hide somebody's other four.

use std::path::{Path, PathBuf};

use super::model::{ClientState, FathomProfile, ProfileId, ValidationError};
use crate::fs_safety;

/// Where the pointer lives, relative to the collection's directory.
pub const STATE_FILE: &str = "client-state.json";

/// Why an operation on the collection failed.
#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("{path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
    #[error("{path}: {source}")]
    Parse {
        path: PathBuf,
        #[source]
        source: serde_json::Error,
    },
    #[error("{path}: {source}")]
    Write {
        path: PathBuf,
        #[source]
        source: fs_safety::FsError,
    },
    #[error("no profile called {0}")]
    NotFound(ProfileId),
    #[error("a profile called {0} already exists")]
    Exists(ProfileId),
    #[error("invalid profile: {0}")]
    Invalid(#[from] ValidationError),
    #[error("the last profile cannot be deleted")]
    LastProfile,
}

/// One document that would not load, and why. Reported, never thrown away.
#[derive(Debug, Clone)]
pub struct Complaint {
    pub path: PathBuf,
    pub reason: String,
}

/// Everything the collection holds, plus whatever refused to parse.
#[derive(Debug, Default)]
pub struct Listing {
    pub profiles: Vec<FathomProfile>,
    pub complaints: Vec<Complaint>,
}

/// The collection, rooted at a directory.
#[derive(Debug, Clone)]
pub struct ProfileStore {
    dir: PathBuf,
}

impl ProfileStore {
    /// Open the collection in `dir`. Creates nothing until something is written.
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    pub fn directory(&self) -> &Path {
        &self.dir
    }

    pub fn path_for(&self, id: &ProfileId) -> PathBuf {
        self.dir.join(format!("{id}.json"))
    }

    pub fn state_path(&self) -> PathBuf {
        self.dir.join(STATE_FILE)
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    /// Every profile that parsed, sorted by name, and every one that did not.
    ///
    /// Sorted by name rather than by id or by mtime: this is what a picker
    /// shows, and a list whose order changes when somebody edits a profile is
    /// a list people misclick.
    pub fn list(&self) -> Listing {
        let mut out = Listing::default();
        // A collection that does not exist yet is an empty collection, not an
        // error. The first save creates the directory.
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return out;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            if path.file_name().and_then(|n| n.to_str()) == Some(STATE_FILE) {
                continue;
            }
            match Self::read_file(&path) {
                Ok(profile) => out.profiles.push(profile),
                Err(reason) => out.complaints.push(Complaint { path, reason }),
            }
        }
        out.profiles.sort_by_key(|profile| profile.name.to_lowercase());
        out
    }

    /// Just the ids, for callers picking a free one.
    pub fn ids(&self) -> Vec<ProfileId> {
        self.list().profiles.into_iter().map(|p| p.id).collect()
    }

    pub fn load(&self, id: &ProfileId) -> Result<FathomProfile, StoreError> {
        let path = self.path_for(id);
        if !path.is_file() {
            return Err(StoreError::NotFound(id.clone()));
        }
        Self::read_file(&path).map_err(|reason| StoreError::Parse {
            path,
            source: serde_json::Error::io(std::io::Error::other(reason)),
        })
    }

    fn read_file(path: &Path) -> Result<FathomProfile, String> {
        let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
        let profile: FathomProfile =
            serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
        profile.validate().map_err(|e| e.to_string())?;
        // The filename is the identity. A document whose `id` disagrees with the
        // file it is in was renamed by hand, and trusting the field would give
        // two profiles the same id the moment one of them was saved.
        let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or_default();
        if profile.id.as_str() != stem {
            return Err(format!(
                "the document says its id is {} but it is stored as {stem}.json",
                profile.id
            ));
        }
        Ok(profile)
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /// Write a profile, creating the directory if this is the first one.
    pub fn save(&self, profile: &FathomProfile) -> Result<(), StoreError> {
        profile.validate()?;
        std::fs::create_dir_all(&self.dir).map_err(|source| StoreError::Io {
            path: self.dir.clone(),
            source,
        })?;
        let mut document = profile.clone();
        document.prune();
        document.touch();
        let path = self.path_for(&profile.id);
        let json = serde_json::to_vec_pretty(&document)
            .map_err(|source| StoreError::Parse {
                path: path.clone(),
                source,
            })?;
        fs_safety::atomic_write(&path, &json).map_err(|source| StoreError::Write {
            path,
            source,
        })
    }

    /// Create a profile under a name, choosing a free id.
    pub fn create(&self, name: &str) -> Result<FathomProfile, StoreError> {
        let taken = self.ids();
        let id = ProfileId::from_display_name(name, &taken)
            .unwrap_or_else(|| Self::free_numbered(&taken));
        let profile = FathomProfile::new(id, name.trim());
        self.save(&profile)?;
        Ok(profile)
    }

    /// A copy of `id` under a new name.
    pub fn duplicate(&self, id: &ProfileId, name: &str) -> Result<FathomProfile, StoreError> {
        let original = self.load(id)?;
        let taken = self.ids();
        let new_id = ProfileId::from_display_name(name, &taken)
            .unwrap_or_else(|| Self::free_numbered(&taken));
        let copy = original.duplicated(new_id, name.trim());
        self.save(&copy)?;
        Ok(copy)
    }

    /// Change a profile's display name. The id does not move.
    ///
    /// Deliberately: the id is in `client-state.json`, in whatever the launcher
    /// has selected, and possibly in a shortcut. Renaming a file to match a
    /// display name is how a rename becomes a delete plus a create, and how the
    /// live profile becomes "not found" halfway through.
    pub fn rename(&self, id: &ProfileId, name: &str) -> Result<FathomProfile, StoreError> {
        let mut profile = self.load(id)?;
        name.trim().clone_into(&mut profile.name);
        profile.validate()?;
        self.save(&profile)?;
        Ok(profile)
    }

    /// Remove a profile.
    ///
    /// Refuses to remove the last one. A collection with nothing in it is a
    /// client with no loadout, and the recovery for that is inventing a Default
    /// — which is exactly the "redundant default" this model exists to stop. So
    /// the last profile is kept instead.
    pub fn delete(&self, id: &ProfileId) -> Result<(), StoreError> {
        let remaining: Vec<ProfileId> = self.ids().into_iter().filter(|other| other != id).collect();
        if remaining.is_empty() {
            return Err(StoreError::LastProfile);
        }
        let path = self.path_for(id);
        if !path.is_file() {
            return Err(StoreError::NotFound(id.clone()));
        }
        std::fs::remove_file(&path).map_err(|source| StoreError::Io { path, source })?;

        // If the one that went was live, point at something that exists. Doing
        // it here rather than in the caller means no code path can delete the
        // live profile and leave the pointer dangling.
        if self.last_profile_id().as_ref() == Some(id) {
            let next = remaining
                .iter()
                .find(|other| other.is_default())
                .or_else(|| remaining.first())
                .cloned();
            if let Some(next) = next {
                let _ = self.set_last_profile_id(&next);
            }
        }
        Ok(())
    }

    /// Guarantee the collection has something in it, and return what is live.
    ///
    /// This is the only place a profile is created without somebody asking, and
    /// it only fires when the collection is genuinely empty. A `lastProfileId`
    /// that names a profile which is not there falls back to an existing
    /// `Default`, or to the first profile there is — it does not create a
    /// second one.
    pub fn resolve_live(&self) -> Result<FathomProfile, StoreError> {
        let listing = self.list();
        if listing.profiles.is_empty() {
            let profile = FathomProfile::default_profile();
            self.save(&profile)?;
            self.set_last_profile_id(&profile.id)?;
            return Ok(profile);
        }
        if let Some(id) = self.last_profile_id() {
            if let Some(found) = listing.profiles.iter().find(|p| p.id == id) {
                return Ok(found.clone());
            }
        }
        let chosen = listing
            .profiles
            .iter()
            .find(|p| p.id.is_default())
            .or_else(|| listing.profiles.first())
            .cloned()
            .ok_or(StoreError::NotFound(ProfileId::default_id()))?;
        self.set_last_profile_id(&chosen.id)?;
        Ok(chosen)
    }

    // -----------------------------------------------------------------------
    // The pointer
    // -----------------------------------------------------------------------

    pub fn client_state(&self) -> ClientState {
        std::fs::read(self.state_path())
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    pub fn last_profile_id(&self) -> Option<ProfileId> {
        self.client_state().last_profile_id
    }

    pub fn set_last_profile_id(&self, id: &ProfileId) -> Result<(), StoreError> {
        let mut state = self.client_state();
        state.last_profile_id = Some(id.clone());
        self.write_state(&state)
    }

    pub fn write_state(&self, state: &ClientState) -> Result<(), StoreError> {
        std::fs::create_dir_all(&self.dir).map_err(|source| StoreError::Io {
            path: self.dir.clone(),
            source,
        })?;
        let path = self.state_path();
        let json = serde_json::to_vec_pretty(state).map_err(|source| StoreError::Parse {
            path: path.clone(),
            source,
        })?;
        fs_safety::atomic_write(&path, &json).map_err(|source| StoreError::Write { path, source })
    }

    // -----------------------------------------------------------------------
    // Import and export
    // -----------------------------------------------------------------------

    /// A profile as a document somebody can send to somebody else.
    pub fn export(&self, id: &ProfileId) -> Result<String, StoreError> {
        let profile = self.load(id)?;
        serde_json::to_string_pretty(&profile).map_err(|source| StoreError::Parse {
            path: self.path_for(id),
            source,
        })
    }

    /// Take a document from elsewhere, under a free id.
    ///
    /// The incoming id is never honoured. Somebody importing a friend's
    /// "default" must not overwrite their own, and an import that silently
    /// replaced a profile would be a data-loss bug that looks like a feature.
    pub fn import(&self, json: &str) -> Result<FathomProfile, StoreError> {
        let incoming: FathomProfile =
            serde_json::from_str(json).map_err(|source| StoreError::Parse {
                path: self.dir.join("<import>"),
                source,
            })?;
        incoming.validate()?;
        let taken = self.ids();
        let id = ProfileId::from_display_name(&incoming.name, &taken)
            .unwrap_or_else(|| Self::free_numbered(&taken));
        let mut profile = incoming.duplicated(id, incoming.name.clone());
        profile.schema = super::model::SCHEMA;
        self.save(&profile)?;
        Ok(profile)
    }

    /// `profile-2`, `profile-3`, ... for a name that produced no usable slug.
    fn free_numbered(taken: &[ProfileId]) -> ProfileId {
        for suffix in 1..10_000u32 {
            if let Ok(id) = ProfileId::new(&format!("profile-{suffix}")) {
                if !taken.contains(&id) {
                    return id;
                }
            }
        }
        ProfileId::default_id()
    }
}

/// Everything a picker needs about one profile, without its contents.
///
/// The hub header and the launcher's dropdown want a name and a count, not four
/// hundred settings, and this is what crosses the command bridge.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSummary {
    pub id: String,
    pub name: String,
    pub modules_on: usize,
    pub hud_widgets: usize,
    pub updated_at: u64,
    pub live: bool,
}

impl ProfileSummary {
    pub fn of(profile: &FathomProfile, live: bool) -> Self {
        Self {
            id: profile.id.as_str().to_owned(),
            name: profile.name.clone(),
            modules_on: profile
                .modules
                .values()
                .filter(|state| state.enabled == Some(true))
                .count(),
            hud_widgets: profile.hud.values().filter(|entry| !entry.hidden).count(),
            updated_at: profile.updated_at,
            live,
        }
    }
}

/// Summaries for a whole collection, live profile marked.
pub fn summarise(store: &ProfileStore) -> Vec<ProfileSummary> {
    let live = store.last_profile_id();
    store
        .list()
        .profiles
        .iter()
        .map(|profile| ProfileSummary::of(profile, live.as_ref() == Some(&profile.id)))
        .collect()
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::super::model::{ModuleState, DEFAULT_NAME, SCHEMA};
    use super::*;

    fn store() -> (tempfile::TempDir, ProfileStore) {
        let dir = tempfile::tempdir().unwrap();
        let store = ProfileStore::new(dir.path().join("profiles"));
        (dir, store)
    }

    #[test]
    fn an_empty_collection_produces_exactly_one_default() {
        let (_guard, store) = store();
        let first = store.resolve_live().unwrap();
        assert_eq!(first.id.as_str(), "default");
        assert_eq!(first.name, DEFAULT_NAME);

        // The bug this model exists to fix: asking again must not make another.
        let second = store.resolve_live().unwrap();
        assert_eq!(second.id, first.id);
        assert_eq!(store.list().profiles.len(), 1, "one Default, not two");
    }

    #[test]
    fn a_dangling_pointer_falls_back_without_creating_anything() {
        let (_guard, store) = store();
        store.create("Bedwars").unwrap();
        store
            .set_last_profile_id(&ProfileId::new("deleted-elsewhere").unwrap())
            .unwrap();

        let live = store.resolve_live().unwrap();
        assert_eq!(live.name, "Bedwars");
        assert_eq!(store.list().profiles.len(), 1, "no Default was invented");
        // And the pointer was repaired rather than left dangling.
        assert_eq!(store.last_profile_id().unwrap(), live.id);
    }

    #[test]
    fn the_last_profile_cannot_be_deleted() {
        let (_guard, store) = store();
        let only = store.create("Only").unwrap();
        assert!(matches!(
            store.delete(&only.id),
            Err(StoreError::LastProfile)
        ));
        assert_eq!(store.list().profiles.len(), 1);
    }

    #[test]
    fn deleting_the_live_profile_moves_the_pointer_somewhere_real() {
        let (_guard, store) = store();
        let default = store.create("Default").unwrap();
        let bedwars = store.create("Bedwars").unwrap();
        store.set_last_profile_id(&bedwars.id).unwrap();

        store.delete(&bedwars.id).unwrap();
        assert_eq!(store.last_profile_id().unwrap(), default.id);
        assert!(store.resolve_live().is_ok());
    }

    #[test]
    fn one_corrupt_document_does_not_hide_the_others() {
        let (_guard, store) = store();
        store.create("Bedwars").unwrap();
        store.create("Survival").unwrap();
        std::fs::write(store.directory().join("broken.json"), b"{not json").unwrap();

        let listing = store.list();
        assert_eq!(listing.profiles.len(), 2);
        assert_eq!(listing.complaints.len(), 1);
        assert!(listing.complaints.first().unwrap().path.ends_with("broken.json"));
    }

    #[test]
    fn a_document_whose_id_does_not_match_its_filename_is_refused() {
        let (_guard, store) = store();
        let profile = FathomProfile::new(ProfileId::new("bedwars").unwrap(), "Bedwars");
        let json = serde_json::to_vec_pretty(&profile).unwrap();
        // Written under the wrong name, as a hand-copied file would be.
        std::fs::create_dir_all(store.directory()).unwrap();
        std::fs::write(store.directory().join("survival.json"), json).unwrap();
        let listing = store.list();
        assert!(listing.profiles.is_empty());
        assert_eq!(listing.complaints.len(), 1);
    }

    #[test]
    fn a_rename_keeps_the_id_so_the_live_pointer_survives_it() {
        let (_guard, store) = store();
        let profile = store.create("Bedwars").unwrap();
        store.set_last_profile_id(&profile.id).unwrap();

        let renamed = store.rename(&profile.id, "Bed Wars").unwrap();
        assert_eq!(renamed.id, profile.id);
        assert_eq!(renamed.name, "Bed Wars");
        assert_eq!(store.last_profile_id().unwrap(), profile.id);
        assert!(store.resolve_live().is_ok());
    }

    #[test]
    fn an_import_never_overwrites_a_profile_that_is_already_there() {
        let (_guard, store) = store();
        let mine = store.create("Default").unwrap();
        let theirs = FathomProfile::new(ProfileId::new("default").unwrap(), "Default");
        let json = serde_json::to_string(&theirs).unwrap();

        let imported = store.import(&json).unwrap();
        assert_ne!(imported.id, mine.id, "the incoming id was not honoured");
        assert_eq!(store.list().profiles.len(), 2);
    }

    #[test]
    fn a_profile_round_trips_through_the_disk_unchanged() {
        let (_guard, store) = store();
        let mut profile = FathomProfile::new(ProfileId::new("bedwars").unwrap(), "Bedwars");
        profile.modules.insert(
            "cps".to_owned(),
            ModuleState {
                enabled: Some(true),
                favourite: true,
                settings: [("window".to_owned(), "1000".to_owned())].into_iter().collect(),
                keys: BTreeMap::new(),
            },
        );
        profile.client.hud_font = Some("Minecraft".to_owned());
        store.save(&profile).unwrap();

        let back = store.load(&profile.id).unwrap();
        assert_eq!(back.modules, profile.modules);
        assert_eq!(back.client, profile.client);
        assert_eq!(back.schema, SCHEMA);
    }

    #[test]
    fn duplicating_twice_numbers_rather_than_collides() {
        let (_guard, store) = store();
        let original = store.create("Bedwars").unwrap();
        let first = store.duplicate(&original.id, "Bedwars").unwrap();
        let second = store.duplicate(&original.id, "Bedwars").unwrap();
        assert_eq!(first.id.as_str(), "bedwars-2");
        assert_eq!(second.id.as_str(), "bedwars-3");
        assert_eq!(store.list().profiles.len(), 3);
    }

    #[test]
    fn summaries_mark_exactly_one_live() {
        let (_guard, store) = store();
        store.create("Default").unwrap();
        let bedwars = store.create("Bedwars").unwrap();
        store.set_last_profile_id(&bedwars.id).unwrap();

        let summaries = summarise(&store);
        assert_eq!(summaries.len(), 2);
        assert_eq!(summaries.iter().filter(|s| s.live).count(), 1);
        assert_eq!(summaries.iter().find(|s| s.live).unwrap().name, "Bedwars");
    }
}
