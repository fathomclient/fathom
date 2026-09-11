//! Profile operations: create, clone, rename, delete, export, import.
//!
//! # Deletion
//!
//! Deleting a profile can destroy worlds someone has spent years in. Three
//! rules, and none of them are negotiable:
//!
//! 1. **Nothing is removed on the first ask.** Deletion moves the profile to
//!    `<data>/trash/<id>-<stamp>/`. Emptying the trash is a separate,
//!    explicit act. A rename is a move; a delete should be too.
//! 2. **No path from profile metadata is ever recursively removed without
//!    being proved to be inside launcher storage.** `profile.json` is a file
//!    on disk that a user, a bad merge or a hostile import can edit. A
//!    `game_directory` pointing at `C:\Users\me\Documents` must not become an
//!    `rm -rf`. [`ProfileOps::removable_root`] is the single gate, and it fails
//!    closed.
//! 3. **Shared storage is never touched.** Libraries, assets and version jars
//!    are content-addressed, immutable, and shared between every profile.
//!    Deleting a profile that happens to be the only user of a library still
//!    does not delete the library — reclaiming that space is a separate,
//!    deliberate operation that does not exist yet.
//!
//! # Export and import
//!
//! An exported profile is *configuration only*: no worlds, no mod jars, no
//! credentials. It is the thing you paste into an issue or send to a friend so
//! they can reproduce your setup, not a backup of your saves. Import treats
//! every field as untrusted, because the file came from somewhere else.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::model::{now, GameDirectory, Profile};
use super::store::{ProfileStore, SCHEMA_VERSION};
use super::{ProfileDirs, ProfileError, ProfileId};
use crate::paths::DataPaths;

/// Where deleted profiles go before they are gone.
const TRASH_DIR: &str = "trash";

/// Ceiling on an imported document. A profile is a few kilobytes; anything
/// vastly larger is corrupt or hostile.
const MAX_IMPORT_BYTES: usize = 1024 * 1024;

/// Heap bounds an imported value has to satisfy. Wider than anything useful,
/// because the job here is catching nonsense rather than policing taste.
const MIN_IMPORT_HEAP_MB: u32 = 512;
const MAX_IMPORT_HEAP_MB: u32 = 65_536;

/// What a deletion did, so the caller can tell the user precisely.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeletionReceipt {
    pub profile: ProfileId,
    /// Where the instance now lives, if it was moved rather than removed.
    pub moved_to: Option<PathBuf>,
    /// True only when the bytes are actually gone.
    pub permanent: bool,
    /// A game directory the launcher did not own, and therefore left alone.
    pub left_alone: Option<PathBuf>,
}

impl DeletionReceipt {
    pub fn message(&self) -> String {
        let mut text = match (&self.moved_to, self.permanent) {
            (Some(path), false) => format!(
                "Moved {} to the recovery area at {}. Nothing has been permanently deleted.",
                self.profile,
                path.display()
            ),
            (_, true) => format!("Permanently deleted {}.", self.profile),
            (None, false) => format!("Removed the profile entry for {}.", self.profile),
        };
        if let Some(path) = &self.left_alone {
            use std::fmt::Write as _;
            let _ = write!(
                text,
                " The game directory at {} was chosen by you rather than created by \
                 the launcher, so it was left untouched.",
                path.display()
            );
        }
        text
    }
}

/// A profile's configuration, in a form that can leave this machine.
///
/// Deliberately not just `Profile`: the id is advisory on import (the target
/// machine may already have one by that name), and the envelope carries what
/// wrote it so a future format change is detectable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExportedProfile {
    /// Schema of the embedded profile.
    pub schema: u32,
    /// What produced this file. Informational.
    pub exported_by: String,
    pub exported_at: u64,
    pub profile: Profile,
}

/// What an import produced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportOutcome {
    pub id: ProfileId,
    /// True when the requested id was taken and a new one was derived.
    pub renamed: bool,
    /// Fields that were reset because the imported value was not usable here.
    pub adjustments: Vec<String>,
}

/// Operations over the profile collection.
#[derive(Debug, Clone)]
pub struct ProfileOps {
    store: ProfileStore,
    paths: DataPaths,
}

impl ProfileOps {
    pub fn new(paths: DataPaths) -> Self {
        Self {
            store: ProfileStore::new(paths.clone()),
            paths,
        }
    }

    pub fn store(&self) -> &ProfileStore {
        &self.store
    }

    pub fn trash_dir(&self) -> PathBuf {
        self.paths.root().join(TRASH_DIR)
    }

    fn taken(&self) -> impl Fn(&ProfileId) -> bool + '_ {
        move |id: &ProfileId| self.store.exists(id)
    }

    // -- create ------------------------------------------------------------

    /// Create a profile from a display name, deriving a free id.
    pub fn create(&self, display_name: &str) -> Result<Profile, ProfileError> {
        let id = ProfileId::unique_from_display_name(display_name, self.taken())?;
        let profile = Profile::new(id, display_name.trim());
        self.store.create(&profile)?;
        Ok(profile)
    }

    // -- clone -------------------------------------------------------------

    /// Copy a profile, including its instance data.
    ///
    /// A clone is for "same setup, different worlds" *and* for "snapshot before
    /// I break something", so the saves come with it. What does **not** come
    /// with it: the natives directory, which is rebuilt from the shared cache
    /// on the next launch and would otherwise carry a stale stamp; and the
    /// logs, which describe the original's runs, not the copy's.
    ///
    /// An external game directory is **not** copied. Two profiles pointing at
    /// one directory would have them fighting over the same `level.dat`, so the
    /// clone gets a managed directory of its own and the caller is told.
    pub fn clone_profile(
        &self,
        source: &ProfileId,
        new_display_name: &str,
    ) -> Result<Profile, ProfileError> {
        let original = self.store.load(source)?;
        let id = ProfileId::unique_from_display_name(new_display_name, self.taken())?;

        let mut copy = original.clone();
        copy.id = id.clone();
        new_display_name.trim().clone_into(&mut copy.display_name);
        copy.created_at = now();
        copy.updated_at = copy.created_at;

        let external_source = match &original.game_directory {
            GameDirectory::External { path } => Some(path.clone()),
            GameDirectory::Managed => None,
        };
        if external_source.is_some() {
            copy.game_directory = GameDirectory::Managed;
        }

        let target_dirs = self.store.dirs(&id);
        target_dirs.ensure()?;

        // Copy the game directory's contents. For a managed source this is the
        // profile's own `minecraft/`; for an external one we deliberately start
        // empty rather than duplicating a directory we do not own.
        if external_source.is_none() {
            let from = self.dirs_for(&original).minecraft_dir();
            let to = target_dirs.minecraft_dir();
            if from.is_dir() {
                copy_tree(&from, &to)?;
            }
        }

        self.store.save(&copy)?;
        Ok(copy)
    }

    // -- rename ------------------------------------------------------------

    /// Change a profile's display name.
    ///
    /// The **id does not change**. The id is the directory name and appears in
    /// the launcher's own logs; renaming it would mean moving every file for a
    /// cosmetic change, and would break anything holding the old id. Display
    /// name and identity are different things, and this is where that
    /// distinction earns its keep.
    pub fn rename(&self, id: &ProfileId, new_display_name: &str) -> Result<Profile, ProfileError> {
        let name = new_display_name.trim();
        if name.is_empty() {
            return Err(ProfileError::UnusableName(new_display_name.to_owned()));
        }
        let mut profile = self.store.load(id)?;
        name.clone_into(&mut profile.display_name);
        self.store.save(&profile)?;
        Ok(profile)
    }

    // -- delete ------------------------------------------------------------

    /// The single gate before anything is recursively removed.
    ///
    /// Returns the directory that may be deleted, or refuses. Fails closed:
    /// anything that cannot be proved to be inside launcher storage is refused,
    /// including paths that do not exist, symlinks pointing outward, and
    /// anything `canonicalize` will not resolve.
    ///
    /// This exists because `profile.json` is an ordinary file that a user, a
    /// bad merge, or a hostile import can edit, and a recursive delete driven
    /// by its contents is the most destructive thing this program could do.
    pub fn removable_root(&self, candidate: &Path) -> Result<PathBuf, ProfileError> {
        let profiles_root = self.paths.profiles_dir();

        // Resolve both sides. Comparing unresolved paths would let
        // `profiles/../../Documents` or a symlink out of the tree pass a
        // textual check.
        let resolved = candidate.canonicalize().map_err(|_| {
            ProfileError::RefusingToDeleteOutsideStorage(candidate.display().to_string())
        })?;
        let root = profiles_root.canonicalize().map_err(|_| {
            ProfileError::RefusingToDeleteOutsideStorage(candidate.display().to_string())
        })?;

        // Inside, and not the profiles directory itself: deleting that would
        // take every profile with it.
        if resolved == root || !resolved.starts_with(&root) {
            return Err(ProfileError::RefusingToDeleteOutsideStorage(
                candidate.display().to_string(),
            ));
        }
        Ok(resolved)
    }

    /// The gate for the recovery-area operations.
    ///
    /// Deliberately a different refusal from [`ProfileOps::removable_root`]:
    /// `shared/` is inside launcher storage and still must not be restored or
    /// purged, and telling a user it is "outside launcher storage" would send
    /// them looking for a problem that is not there.
    ///
    /// Fails closed, and refuses the recovery directory itself — purging that
    /// would take every recoverable instance with it.
    fn inside_recovery_area(&self, candidate: &Path) -> Result<PathBuf, ProfileError> {
        let refuse = || ProfileError::NotInRecoveryArea(candidate.display().to_string());

        let resolved = candidate.canonicalize().map_err(|_| refuse())?;
        let trash = self.trash_dir().canonicalize().map_err(|_| refuse())?;

        if resolved == trash || !resolved.starts_with(&trash) {
            return Err(refuse());
        }
        Ok(resolved)
    }

    /// Move a profile to the recovery area.
    ///
    /// Nothing is destroyed. The instance is moved wholesale, so worlds,
    /// screenshots and configuration are all still there, and
    /// [`ProfileOps::restore`] puts it back.
    pub fn delete(&self, id: &ProfileId) -> Result<DeletionReceipt, ProfileError> {
        let profile = self.store.load(id).ok();
        let dirs = self.store.dirs(id);
        let root = self.removable_root(dirs.root())?;

        let left_alone = profile.as_ref().and_then(|p| match &p.game_directory {
            GameDirectory::External { path } => Some(path.clone()),
            GameDirectory::Managed => None,
        });

        let trash = self.trash_dir();
        std::fs::create_dir_all(&trash).map_err(|source| ProfileError::Io {
            path: trash.display().to_string(),
            source,
        })?;

        let destination = unique_trash_path(&trash, id);

        // A rename is atomic within a filesystem and cheap regardless of how
        // many worlds are inside. Copy-then-delete would double the disk use
        // and could fail halfway.
        std::fs::rename(&root, &destination).map_err(|source| ProfileError::Io {
            path: root.display().to_string(),
            source,
        })?;

        tracing::info!(profile = %id, trash = %destination.display(), "profile moved to the recovery area");

        Ok(DeletionReceipt {
            profile: id.clone(),
            moved_to: Some(destination),
            permanent: false,
            left_alone,
        })
    }

    /// What is in the recovery area, newest first.
    pub fn trashed(&self) -> Vec<PathBuf> {
        let Ok(entries) = std::fs::read_dir(self.trash_dir()) else {
            return Vec::new();
        };
        let mut out: Vec<PathBuf> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.is_dir())
            .collect();
        out.sort();
        out.reverse();
        out
    }

    /// Put a trashed instance back.
    pub fn restore(&self, trashed: &Path, id: &ProfileId) -> Result<(), ProfileError> {
        if self.store.exists(id) {
            return Err(ProfileError::AlreadyExists(id.to_string()));
        }
        // The source must be inside the trash, for the same reason deletion
        // checks its target: this is a move driven by a path from outside.
        let resolved = self.inside_recovery_area(trashed)?;

        let target = self.store.dirs(id).root().to_path_buf();
        std::fs::rename(&resolved, &target).map_err(|source| ProfileError::Io {
            path: resolved.display().to_string(),
            source,
        })?;
        Ok(())
    }

    /// Permanently remove one trashed instance.
    ///
    /// Separate from [`ProfileOps::delete`] so that destroying data is always a
    /// second, explicit act. Goes through the same containment check, because
    /// the path comes from a directory listing rather than from us.
    pub fn purge(&self, trashed: &Path) -> Result<DeletionReceipt, ProfileError> {
        let resolved = self.inside_recovery_area(trashed)?;

        std::fs::remove_dir_all(&resolved).map_err(|source| ProfileError::Io {
            path: resolved.display().to_string(),
            source,
        })?;

        let name = resolved
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("unknown")
            .to_owned();
        tracing::info!(entry = %name, "recovery-area entry permanently removed");

        Ok(DeletionReceipt {
            profile: ProfileId::new("deleted").unwrap_or_else(|_| unreachable!()),
            moved_to: None,
            permanent: true,
            left_alone: None,
        })
    }

    // -- export and import -------------------------------------------------

    /// Configuration only. No worlds, no jars, no credentials.
    pub fn export(&self, id: &ProfileId) -> Result<ExportedProfile, ProfileError> {
        let mut profile = self.store.load(id)?;

        // An absolute Java path and an external game directory are facts about
        // one machine. Carrying them to another would produce a profile that
        // silently points somewhere wrong.
        profile.java = super::model::JavaPreference::Automatic;
        profile.game_directory = GameDirectory::Managed;

        Ok(ExportedProfile {
            schema: SCHEMA_VERSION,
            exported_by: format!("{} {}", crate::PRODUCT_NAME, crate::PRODUCT_VERSION),
            exported_at: now(),
            profile,
        })
    }

    pub fn export_json(&self, id: &ProfileId) -> Result<String, ProfileError> {
        let exported = self.export(id)?;
        serde_json::to_string_pretty(&exported).map_err(|source| ProfileError::Malformed {
            path: format!("export of {id}"),
            source,
        })
    }

    /// Create a profile from an exported document.
    ///
    /// Everything in `bytes` is untrusted. The id is re-derived rather than
    /// trusted, anything referring to a path on the exporting machine is
    /// dropped, and values that are out of range are replaced with defaults —
    /// each one reported, never silently.
    pub fn import(&self, bytes: &[u8]) -> Result<(Profile, ImportOutcome), ProfileError> {
        if bytes.len() > MAX_IMPORT_BYTES {
            return Err(ProfileError::Corrupt {
                profile: "import".to_owned(),
                detail: format!("{} bytes is far larger than a profile", bytes.len()),
            });
        }

        // Parsed loosely first. An export from an older build has an older
        // profile shape inside it, and the whole point of migrations is that
        // such a file still opens -- a launcher that can only import documents
        // written by its own version is not much of an import.
        let envelope: serde_json::Value =
            serde_json::from_slice(bytes).map_err(|source| ProfileError::Malformed {
                path: "imported profile".to_owned(),
                source,
            })?;

        let schema = envelope
            .get("schema")
            .and_then(serde_json::Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .unwrap_or(1);

        if schema > SCHEMA_VERSION {
            return Err(ProfileError::SchemaTooNew {
                profile: envelope
                    .get("profile")
                    .and_then(|p| p.get("display_name"))
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("imported profile")
                    .to_owned(),
                found: schema,
                supported: SCHEMA_VERSION,
            });
        }

        let body = envelope
            .get("profile")
            .cloned()
            .ok_or_else(|| ProfileError::Corrupt {
                profile: "import".to_owned(),
                detail: "the document carries no profile".to_owned(),
            })?;

        // The id inside is not trusted -- it is replaced below -- but `migrate`
        // wants one for its error messages, and the recorded one is the most
        // useful thing to name.
        let recorded_id = body
            .get("id")
            .and_then(serde_json::Value::as_str)
            .and_then(|raw| ProfileId::new(raw).ok())
            .unwrap_or_else(|| {
                ProfileId::new("imported").unwrap_or_else(|_| unreachable!("'imported' is valid"))
            });

        let mut envelope_body = body;
        if let Some(object) = envelope_body.as_object_mut() {
            object.insert("schema".to_owned(), serde_json::json!(schema));
        }
        let (migrated, from) = super::store::migrate(envelope_body, &recorded_id)?;

        let mut profile: Profile =
            serde_json::from_value(migrated).map_err(|source| ProfileError::Malformed {
                path: "imported profile".to_owned(),
                source,
            })?;
        let mut adjustments = Vec::new();
        if from != SCHEMA_VERSION {
            adjustments.push(format!(
                "The profile was written by an older version (schema v{from}) and was \
                 upgraded on import."
            ));
        }

        // A fresh id on this machine, derived from the name rather than taken
        // from the file. Two people exporting "main" must not collide.
        let wanted = profile.display_name.clone();
        let id = ProfileId::unique_from_display_name(&wanted, self.taken())?;
        let renamed = id != profile.id;
        profile.id = id.clone();

        self.sanitise_import(&mut profile, &id, &mut adjustments);

        profile.created_at = now();
        profile.updated_at = profile.created_at;

        self.store.create(&profile)?;
        Ok((
            profile,
            ImportOutcome {
                id,
                renamed,
                adjustments,
            },
        ))
    }

    /// Replace anything an imported profile claims that is not usable here.
    ///
    /// Every correction is pushed onto `adjustments`, because a value silently
    /// changed is a value the user will later be surprised by.
    fn sanitise_import(
        &self,
        profile: &mut Profile,
        id: &ProfileId,
        adjustments: &mut Vec<String>,
    ) {
        // Machine-specific fields never survive an import.
        if profile.java.explicit_path().is_some() {
            profile.java = super::model::JavaPreference::Automatic;
            adjustments
                .push("The Java path was reset: it referred to the other machine.".to_owned());
        }

        // JVM arguments never survive an import either, and this one is a
        // security boundary rather than a portability nicety.
        //
        // A JVM flag list is a code-execution surface: HotSpot starts a shell of
        // its own for `-XX:OnError`, and loads a native library for
        // `-agentpath:`, before any Minecraft class is touched. `jvm_args`
        // refuses both families, and every other family anyone has thought of —
        // but a denylist over an option set with hundreds of entries that grows
        // every release cannot be complete, and a profile arrives from whoever
        // wrote it.
        //
        // So the arguments are dropped wholesale rather than filtered. The user
        // is told what they were, in full, and can add back the ones they meant
        // to have; retyping four flags is a smaller cost than the alternative,
        // which is that the safety of an import depends on our having predicted
        // every dangerous flag in advance.
        if !profile.jvm.extra_args.is_empty() {
            let dropped = profile.jvm.extra_args.join(" ");
            profile.jvm.extra_args.clear();
            adjustments.push(format!(
                "The JVM arguments were not imported. A flag list can load code into the                  game, so it is never carried over from a file someone else wrote. Add back                  any you want, in the profile's Java settings: {dropped}"
            ));
        }
        if !profile.game_directory.is_launcher_owned() {
            profile.game_directory = GameDirectory::Managed;
            adjustments.push("The game directory was reset to this profile's own.".to_owned());
        }

        // Ranges. An imported file can say anything.
        if !profile.display.resolution.is_sane() {
            profile.display.resolution = super::model::Resolution::default();
            adjustments.push("The window size was out of range and was reset.".to_owned());
        }
        if !(MIN_IMPORT_HEAP_MB..=MAX_IMPORT_HEAP_MB).contains(&profile.memory.heap_mb) {
            profile.memory.heap_mb = super::model::MemoryConfig::default().heap_mb;
            adjustments.push("The heap size was out of range and was reset.".to_owned());
        }

        // A mod entry is a file name, and it becomes a path. Anything that
        // could escape is dropped rather than sanitised into something the user
        // did not ask for.
        let before = profile.mods.len();
        let dirs = self.store.dirs(id);
        profile.mods.mods.retain(|m| {
            dirs.contain_in_game(&format!("mods/{}", m.file_name))
                .is_ok()
        });
        if profile.mods.len() != before {
            adjustments.push(format!(
                "{} mod entr{} dropped: the file name was not a safe path.",
                before - profile.mods.len(),
                if before - profile.mods.len() == 1 {
                    "y was"
                } else {
                    "ies were"
                }
            ));
        }
    }

    // -- helpers -----------------------------------------------------------

    pub fn dirs_for(&self, profile: &Profile) -> ProfileDirs {
        ProfileDirs::with_game_directory(&self.paths, &profile.id, &profile.game_directory)
    }
}

/// A trash path that does not collide, even if a profile is created and deleted
/// twice in the same second.
fn unique_trash_path(trash: &Path, id: &ProfileId) -> PathBuf {
    let stamp = now();
    let base = trash.join(format!("{id}-{stamp}"));
    if !base.exists() {
        return base;
    }
    for n in 2..1000u32 {
        let candidate = trash.join(format!("{id}-{stamp}-{n}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    trash.join(format!("{id}-{stamp}-overflow"))
}

/// Recursive copy, following no symlinks.
///
/// A symlink inside a profile is not something the launcher creates, so one
/// found here came from elsewhere; copying it would carry a link pointing
/// outside the new profile, quietly breaking isolation.
fn copy_tree(from: &Path, to: &Path) -> Result<(), ProfileError> {
    std::fs::create_dir_all(to).map_err(|source| ProfileError::Io {
        path: to.display().to_string(),
        source,
    })?;

    let entries = std::fs::read_dir(from).map_err(|source| ProfileError::Io {
        path: from.display().to_string(),
        source,
    })?;

    for entry in entries.flatten() {
        let source_path = entry.path();
        let Ok(metadata) = entry.metadata() else {
            continue;
        };

        // `metadata()` on a DirEntry does not follow symlinks on any platform
        // we target, so this is the link's own type.
        if metadata.file_type().is_symlink() {
            tracing::warn!(path = %source_path.display(), "not copying a symlink into the clone");
            continue;
        }

        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let target = to.join(&name);

        if metadata.is_dir() {
            copy_tree(&source_path, &target)?;
        } else {
            std::fs::copy(&source_path, &target).map_err(|source| ProfileError::Io {
                path: source_path.display().to_string(),
                source,
            })?;
        }
    }
    Ok(())
}
