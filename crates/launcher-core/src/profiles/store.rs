//! Reading and writing profiles.
//!
//! # Why JSON, one file per profile
//!
//! `architecture-options.md` does not mandate a database, and a database would
//! be the wrong shape here. The whole dataset is a handful of small documents
//! that are written when a user changes a setting and read when the launcher
//! starts. What matters is that a corrupt or half-written file cannot take the
//! launcher down, and one file per profile gives that for free: a broken
//! profile is one broken profile. A single SQLite file would put every
//! profile's fate in one place, add a dependency with its own corruption modes,
//! and buy nothing at this size.
//!
//! # The four requirements
//!
//! - **Atomic persistence.** [`crate::fs_safety::atomic_write`] — temp file,
//!   fsync, rename. A crash leaves the old file or the new one, never half of
//!   either.
//! - **Schema versioning.** Every file carries `schema`. A file from the
//!   future is refused rather than guessed at.
//! - **Migrations.** [`migrate`] walks a document forward one version at a
//!   time, so adding v4 later means writing one step, not rewriting the chain.
//! - **Corruption detection.** A file that will not parse is not silently
//!   replaced with defaults. The last known-good copy is kept beside it and
//!   recovery is reported, so the user is told what happened.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::{Profile, ProfileDirs, ProfileError, ProfileId};
use crate::fs_safety;
use crate::paths::DataPaths;

/// The schema this build writes.
///
/// | Version | Shape |
/// |---|---|
/// | 1 | Flat: `loader` a bare string, `jvm` holding heap and java path together, no timestamps |
/// | 2 | Grouped: `loader` an object with a version, memory/java/display/mods/optimizations split out, timestamps |
/// | 3 | `mods` became a real configuration: entries carry a mod id and a store hash rather than a bare file name |
pub const SCHEMA_VERSION: u32 = 5;

/// Suffix for the last known-good copy of a profile.
const BACKUP_SUFFIX: &str = ".bak";

/// A ceiling on the size of a profile document.
///
/// A profile is a few kilobytes. Anything vastly larger is either corrupt or
/// hostile, and parsing it would mean handing an untrusted file an unbounded
/// allocation.
const MAX_PROFILE_BYTES: u64 = 1024 * 1024;

/// What happened when a profile was read.
///
/// Loading is not simply success or failure: a profile can load *from its
/// backup*, or load *after being migrated*, and the user deserves to be told
/// which. Silently returning a repaired profile as though nothing happened is
/// how data loss goes unnoticed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoadOutcome {
    /// Parsed as written, at the current schema.
    Clean,
    /// Parsed after being migrated forward. The caller should save it back.
    Migrated { from: u32 },
    /// The main file was unusable; this came from the backup.
    RecoveredFromBackup { why: String },
}

impl LoadOutcome {
    pub fn needs_rewrite(&self) -> bool {
        !matches!(self, LoadOutcome::Clean)
    }

    pub fn message(&self) -> Option<String> {
        match self {
            LoadOutcome::Clean => None,
            LoadOutcome::Migrated { from } => Some(format!(
                "Profile upgraded from schema v{from} to v{SCHEMA_VERSION}."
            )),
            LoadOutcome::RecoveredFromBackup { why } => Some(format!(
                "The profile file was unreadable and the last good copy was used instead. {why}"
            )),
        }
    }
}

/// A profile plus how it was obtained.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Loaded {
    pub profile: Profile,
    pub outcome: LoadOutcome,
}

/// The on-disk envelope.
///
/// The schema number lives outside the profile so it can be read before the
/// body is interpreted — which is the whole point, since the body's shape is
/// what the number describes.
#[derive(Debug, Serialize, Deserialize)]
struct Document {
    schema: u32,
    #[serde(flatten)]
    body: serde_json::Value,
}

/// Reads and writes profiles on disk.
#[derive(Debug, Clone)]
pub struct ProfileStore {
    paths: DataPaths,
}

impl ProfileStore {
    pub fn new(paths: DataPaths) -> Self {
        Self { paths }
    }

    pub fn paths(&self) -> &DataPaths {
        &self.paths
    }

    pub fn dirs(&self, id: &ProfileId) -> ProfileDirs {
        ProfileDirs::new(&self.paths, id)
    }

    pub fn exists(&self, id: &ProfileId) -> bool {
        self.dirs(id).manifest_file().is_file()
    }

    // -- writing -----------------------------------------------------------

    pub fn create(&self, profile: &Profile) -> Result<ProfileDirs, ProfileError> {
        if self.exists(&profile.id) {
            return Err(ProfileError::AlreadyExists(profile.id.to_string()));
        }
        let dirs = self.dirs(&profile.id);
        dirs.ensure()?;
        self.save(profile)?;
        Ok(dirs)
    }

    /// Persist a profile, keeping the previous content as a backup.
    ///
    /// The backup is written *before* the new file, so at no point is there
    /// neither a current nor a previous good copy. `updated_at` is stamped here
    /// rather than by callers, so it cannot drift from what is on disk.
    pub fn save(&self, profile: &Profile) -> Result<(), ProfileError> {
        let mut profile = profile.clone();
        profile.touch();

        let file = self.manifest_path(&profile.id)?;

        // Keep the outgoing content. A failure here is not fatal -- losing the
        // backup is worse than not having one, but neither is worth refusing to
        // save the user's change over.
        if file.is_file() {
            if let Err(e) = std::fs::copy(&file, backup_path(&file)) {
                tracing::warn!(error = %e, profile = %profile.id, "could not refresh the profile backup");
            }
        }

        let document = Document {
            schema: SCHEMA_VERSION,
            body: serde_json::to_value(&profile).map_err(|source| ProfileError::Malformed {
                path: file.display().to_string(),
                source,
            })?,
        };
        let json =
            serde_json::to_vec_pretty(&document).map_err(|source| ProfileError::Malformed {
                path: file.display().to_string(),
                source,
            })?;

        fs_safety::atomic_write(&file, &json)?;
        Ok(())
    }

    /// Resolve a profile's manifest path, refusing anything outside the data
    /// root.
    ///
    /// `ProfileId` already guarantees this, so reaching the error is a bug
    /// rather than untrusted input — but it is the last gate before a write,
    /// and a cheap assertion at a write boundary is worth keeping.
    fn manifest_path(&self, id: &ProfileId) -> Result<PathBuf, ProfileError> {
        let file = self.dirs(id).manifest_file();
        self.paths.assert_inside(&file).map_err(|_| {
            ProfileError::Fs(fs_safety::FsError::Escape(file.display().to_string()))
        })?;
        Ok(file)
    }

    // -- reading -----------------------------------------------------------

    /// Read a profile, migrating and recovering as needed.
    pub fn load_detailed(&self, id: &ProfileId) -> Result<Loaded, ProfileError> {
        let file = self.dirs(id).manifest_file();

        let primary = read_and_parse(&file, id);
        let why = match primary {
            Ok(loaded) => return Ok(loaded),

            // Not corruption, and not something a backup would answer
            // differently -- the backup would have the same id in it, and the
            // same schema number. Report the real reason rather than burying it
            // under a recovery attempt that is guaranteed to fail.
            Err(
                e @ (ProfileError::NotFound(_)
                | ProfileError::IdMismatch { .. }
                | ProfileError::SchemaTooNew { .. }
                | ProfileError::NoMigration { .. }),
            ) => return Err(e),

            Err(e) => e.to_string(),
        };

        // The main file exists and is unusable. Fall back rather than
        // presenting the user with a profile that has silently reverted to
        // defaults.
        let backup = backup_path(&file);
        tracing::warn!(profile = %id, error = %why, "profile is unreadable; trying the backup");

        match read_and_parse(&backup, id) {
            Ok(loaded) => Ok(Loaded {
                profile: loaded.profile,
                outcome: LoadOutcome::RecoveredFromBackup { why },
            }),
            Err(_) => Err(ProfileError::Corrupt {
                profile: id.to_string(),
                detail: why,
            }),
        }
    }

    /// Read a profile, discarding the detail of how it was obtained.
    pub fn load(&self, id: &ProfileId) -> Result<Profile, ProfileError> {
        Ok(self.load_detailed(id)?.profile)
    }

    /// All readable profiles, sorted by display name.
    ///
    /// Unreadable entries are skipped with a warning rather than failing the
    /// whole listing: one corrupt profile must not make the launcher unusable.
    /// [`ProfileStore::list_detailed`] reports them instead of hiding them.
    pub fn list(&self) -> Vec<Profile> {
        self.list_detailed()
            .into_iter()
            .filter_map(|(_, result)| result.ok().map(|l| l.profile))
            .collect()
    }

    /// Every profile directory and what came of reading it, including the
    /// failures. Sorted by display name, with unreadable ones last.
    pub fn list_detailed(&self) -> Vec<(ProfileId, Result<Loaded, ProfileError>)> {
        let Ok(entries) = std::fs::read_dir(self.paths.profiles_dir()) else {
            return Vec::new();
        };

        let mut out: Vec<(ProfileId, Result<Loaded, ProfileError>)> = Vec::new();
        for entry in entries.flatten() {
            if !entry.path().is_dir() {
                continue;
            }
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            let Ok(id) = ProfileId::new(&name) else {
                tracing::warn!(directory = %name, "not a valid profile id; ignoring");
                continue;
            };
            // A directory with no manifest at all is not a profile and not an
            // error -- the trash directory and scratch space live alongside.
            if !self.exists(&id) {
                continue;
            }
            let loaded = self.load_detailed(&id);
            out.push((id, loaded));
        }

        out.sort_by(|a, b| match (&a.1, &b.1) {
            (Ok(x), Ok(y)) => x.profile.display_name.cmp(&y.profile.display_name),
            (Ok(_), Err(_)) => std::cmp::Ordering::Less,
            (Err(_), Ok(_)) => std::cmp::Ordering::Greater,
            (Err(_), Err(_)) => a.0.cmp(&b.0),
        });
        out
    }

    /// Rewrite any profile that was migrated or recovered on load.
    ///
    /// Called once at start-up. Doing it eagerly means a migration happens
    /// exactly once rather than on every read, and means the backup beside it
    /// becomes a copy of the *migrated* form.
    pub fn consolidate(&self) -> Vec<(ProfileId, LoadOutcome)> {
        let mut done = Vec::new();
        for (id, result) in self.list_detailed() {
            let Ok(loaded) = result else { continue };
            if !loaded.outcome.needs_rewrite() {
                continue;
            }
            match self.save(&loaded.profile) {
                Ok(()) => done.push((id, loaded.outcome)),
                Err(e) => tracing::warn!(profile = %id, error = %e, "could not rewrite profile"),
            }
        }
        done
    }
}

fn backup_path(file: &Path) -> PathBuf {
    let mut name = file.as_os_str().to_os_string();
    name.push(BACKUP_SUFFIX);
    PathBuf::from(name)
}

/// Read one file and turn it into a profile, migrating if it is older.
fn read_and_parse(file: &Path, id: &ProfileId) -> Result<Loaded, ProfileError> {
    let metadata = std::fs::metadata(file).map_err(|source| {
        if source.kind() == std::io::ErrorKind::NotFound {
            ProfileError::NotFound(id.to_string())
        } else {
            ProfileError::Io {
                path: file.display().to_string(),
                source,
            }
        }
    })?;

    if metadata.len() > MAX_PROFILE_BYTES {
        return Err(ProfileError::Corrupt {
            profile: id.to_string(),
            detail: format!(
                "the file is {} bytes; a profile is a few kilobytes",
                metadata.len()
            ),
        });
    }

    let bytes = std::fs::read(file).map_err(|source| ProfileError::Io {
        path: file.display().to_string(),
        source,
    })?;

    let raw: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|source| ProfileError::Malformed {
            path: file.display().to_string(),
            source,
        })?;

    let (value, from) = migrate(raw, id)?;
    let profile: Profile =
        serde_json::from_value(value).map_err(|source| ProfileError::Malformed {
            path: file.display().to_string(),
            source,
        })?;

    // The directory name is the authority on identity. A file whose `id`
    // disagrees has been moved or hand-edited, and trusting the file would let
    // a profile claim to be a different one.
    if &profile.id != id {
        return Err(ProfileError::IdMismatch {
            directory: id.to_string(),
            recorded: profile.id.to_string(),
        });
    }

    Ok(Loaded {
        profile,
        outcome: if from == SCHEMA_VERSION {
            LoadOutcome::Clean
        } else {
            LoadOutcome::Migrated { from }
        },
    })
}

/// Walk a document forward to the current schema.
///
/// One step per version, so adding v4 later means writing `3 -> 4` rather than
/// touching anything that already works. Returns the migrated document and the
/// version it started at.
pub fn migrate(
    mut value: serde_json::Value,
    id: &ProfileId,
) -> Result<(serde_json::Value, u32), ProfileError> {
    let found = detect_version(&value);

    if found > SCHEMA_VERSION {
        // Refused, not guessed at. A newer build may have moved a field in a
        // way this one would misread, and silently reinterpreting someone's
        // configuration is worse than declining to open it.
        return Err(ProfileError::SchemaTooNew {
            profile: id.to_string(),
            found,
            supported: SCHEMA_VERSION,
        });
    }

    let mut version = found;
    while version < SCHEMA_VERSION {
        value = match version {
            1 => migrate_v1_to_v2(value),
            2 => migrate_v2_to_v3(value),
            3 => migrate_v3_to_v4(value),
            4 => migrate_v4_to_v5(value),
            other => {
                return Err(ProfileError::NoMigration {
                    profile: id.to_string(),
                    from: other,
                })
            }
        };
        version += 1;
    }

    if let Some(object) = value.as_object_mut() {
        object.remove("schema");
    }
    Ok((value, found))
}

/// A document with no `schema` key is v1: the shape that shipped before
/// versioning existed. Absence is information, not an error.
/// v4 -> v5.
///
/// Gives every profile a `kind`, and recognises the one shape that was already
/// a Fathom profile in everything but name.
///
/// # Why anything is recognised at all
///
/// `ProfileKind` defaults to `Custom`, deliberately: a profile assembled by
/// hand is the user's, and the launcher deciding to start maintaining its
/// contents would be overruling them. But one arrangement is not a hand-built
/// coincidence -- Legacy Fabric plus Fathom's own optimization module is
/// exactly what this launcher seeded, and exactly what its documentation told
/// people to build. Leaving those as `Custom` would mean the launcher declines
/// to keep its own components current in the profiles it created itself.
///
/// So the rule is narrow and checkable: **Legacy Fabric, and the Fathom module
/// present**. Anything else stays `Custom`. A Vanilla profile stays `Custom`
/// rather than becoming `Vanilla`, because "no loader" and "deliberately
/// unmodified" are different intentions and only the user knows which one they
/// had.
///
/// The recognised profile's module entry is also marked as the launcher's own,
/// which is what lets a later build replace it with a newer jar. That is the
/// point of the migration: without it, the component manager treats the jar it
/// installed itself as somebody else's file and never updates it.
fn migrate_v4_to_v5(mut value: serde_json::Value) -> serde_json::Value {
    let Some(object) = value.as_object_mut() else {
        return value;
    };

    // Never overwrite a kind that is already there. A v4 document should not
    // have one, but a hand-edited file might.
    if object.contains_key("kind") {
        return value;
    }

    let is_legacy_fabric = object
        .get("loader")
        .and_then(|loader| loader.get("kind"))
        .and_then(serde_json::Value::as_str)
        == Some("legacy-fabric");

    let module = crate::mods::catalog::FATHOM_OPTIMIZATIONS;
    let entries = object
        .get_mut("mods")
        .and_then(|mods| mods.get_mut("mods"))
        .and_then(serde_json::Value::as_array_mut);

    let mut has_module = false;
    if let Some(entries) = entries {
        for entry in entries.iter_mut() {
            let matches = entry.get("id").and_then(serde_json::Value::as_str) == Some(module);
            if !matches {
                continue;
            }
            has_module = true;
            if is_legacy_fabric {
                if let Some(entry) = entry.as_object_mut() {
                    entry.insert(
                        "source".to_owned(),
                        serde_json::Value::String("first-party".to_owned()),
                    );
                }
            }
        }
    }

    let kind = if is_legacy_fabric && has_module {
        "fathom"
    } else {
        "custom"
    };
    object.insert(
        "kind".to_owned(),
        serde_json::Value::String(kind.to_owned()),
    );
    value
}

/// v3 -> v4.
///
/// Drops `optimizations.rendering` entries for optimizations the module does
/// not implement.
///
/// # Why these exist at all
///
/// Presets used to expand into *every* catalogue entry, so choosing "Maximum
/// FPS" wrote eighteen `true` values for a module that implements three of
/// them. The bridge file then told the game to switch on work that does not
/// exist, and the Performance page counted them as on. Presets are now gated on
/// `is_actionable`, but that only fixes profiles written afterwards; the maps
/// already on disk keep their stale entries forever, because
/// `Settings::from_profile` writes what the profile stores rather than
/// re-expanding the preset.
///
/// # Why the keys are REMOVED rather than set to false
///
/// Because `false` is a decision and absence is not. The interface reads a
/// missing key as "ask the preset", so an entry dropped here comes back on by
/// itself the day it becomes implemented and the profile's preset includes it.
/// Writing `false` would pin it off permanently and quietly, and the user would
/// have to find and flip a switch they never set.
///
/// For a `Custom` profile the effect is the same either way -- the preset
/// answers `false` for everything -- so nothing a user deliberately chose is
/// lost.
///
/// # Why this reads the live catalogue
///
/// Migrations are normally frozen structural transforms, and this one is not:
/// it asks today's catalogue what is implemented. That is deliberate and safe
/// here precisely because it only ever *removes* keys, and a removed key falls
/// back to a live answer. It cannot strand a profile in a state a later build
/// disagrees with.
fn migrate_v3_to_v4(mut value: serde_json::Value) -> serde_json::Value {
    let Some(object) = value.as_object_mut() else {
        return value;
    };
    let Some(rendering) = object
        .get_mut("optimizations")
        .and_then(serde_json::Value::as_object_mut)
        .and_then(|optimizations| optimizations.get_mut("rendering"))
        .and_then(serde_json::Value::as_object_mut)
    else {
        return value;
    };

    rendering.retain(|id, _| {
        crate::optimizations::find(id).is_some_and(|entry| entry.is_actionable())
    });

    value
}

fn detect_version(value: &serde_json::Value) -> u32 {
    value
        .get("schema")
        .and_then(serde_json::Value::as_u64)
        .and_then(|v| u32::try_from(v).ok())
        .unwrap_or(1)
}

/// v1 -> v2.
///
/// What changed:
/// - `loader: "vanilla"` became `loader: { kind: "vanilla" }`, so a loader can
///   carry its own version.
/// - `jvm: { heap_mb, extra_args, java_path, compile_huge_methods }` split into
///   `memory`, `jvm`, `java` and `optimizations`, because those are four
///   different concerns that happened to be stored together.
/// - timestamps were added.
///
/// Anything absent gets the type's default, which is why this only has to move
/// what actually moved.
fn migrate_v1_to_v2(mut value: serde_json::Value) -> serde_json::Value {
    use serde_json::{json, Value};

    let Some(object) = value.as_object_mut() else {
        return value;
    };

    // loader: string -> object
    if let Some(Value::String(kind)) = object.get("loader").cloned() {
        object.insert("loader".to_owned(), json!({ "kind": kind }));
    }

    // jvm: one blob -> four fields
    if let Some(Value::Object(old)) = object.get("jvm").cloned() {
        if let Some(heap) = old.get("heap_mb").cloned() {
            object.insert("memory".to_owned(), json!({ "heap_mb": heap }));
        }
        if let Some(Value::String(path)) = old.get("java_path").cloned() {
            object.insert(
                "java".to_owned(),
                json!({ "kind": "explicit", "path": path }),
            );
        }
        if let Some(Value::Bool(huge)) = old.get("compile_huge_methods").cloned() {
            object.insert(
                "optimizations".to_owned(),
                json!({ "jvm": { "compile_huge_methods": huge } }),
            );
        }
        let extra = old.get("extra_args").cloned().unwrap_or(json!([]));
        object.insert("jvm".to_owned(), json!({ "extra_args": extra }));
    }

    // A v1 profile has no timestamps. Stamping "now" would claim it was created
    // at upgrade time, which is a lie; zero reads as "unknown" and sorts first.
    object.entry("created_at").or_insert(json!(0));
    object.entry("updated_at").or_insert(json!(0));

    value
}

/// v2 -> v3.
///
/// `mods.entries` was `[{ file_name, enabled, sha1? }]`. It became
/// `mods.mods` = `[{ id, sha1, file_name, enabled }]`, because a file name
/// cannot express identity: dependency and conflict resolution needs to know
/// *which mod* a jar is, separately from what it is called.
///
/// An entry with no recorded hash cannot be carried across — the new form
/// references the content-addressed store by hash, and inventing one is not
/// possible. Those entries are dropped, which is safe: **the jar itself is not
/// touched**, so the next materialization sees it as a foreign file and adopts
/// it with a real hash. The only thing lost is an enabled/disabled flag for a
/// mod that was, by definition, never in the store.
fn migrate_v2_to_v3(mut value: serde_json::Value) -> serde_json::Value {
    use serde_json::{json, Value};

    let Some(object) = value.as_object_mut() else {
        return value;
    };
    let Some(Value::Object(old)) = object.get("mods").cloned() else {
        return value;
    };
    let Some(Value::Array(entries)) = old.get("entries").cloned() else {
        return value;
    };

    let mut carried = Vec::new();
    for entry in entries {
        let Some(file_name) = entry.get("file_name").and_then(Value::as_str) else {
            continue;
        };
        let Some(sha1) = entry.get("sha1").and_then(Value::as_str) else {
            continue;
        };
        let Ok(id) = crate::mods::id_from_file_name(file_name) else {
            continue;
        };
        carried.push(json!({
            "id": id.as_str(),
            "sha1": sha1,
            "file_name": file_name,
            "enabled": entry.get("enabled").and_then(Value::as_bool).unwrap_or(true),
        }));
    }

    object.insert("mods".to_owned(), json!({ "mods": carried }));
    value
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

    /// The stale-switch-map migration.
    ///
    /// A profile written before presets were gated carries `true` for
    /// optimizations the module does not implement. Those keys are removed --
    /// not set to false -- so the preset answers for them again once they are
    /// implemented.
    #[test]
    fn v4_drops_switches_for_unimplemented_optimizations() {
        use serde_json::json;

        // One entry that IS implemented and one that is not, both on.
        let implemented = crate::optimizations::all()
            .into_iter()
            .find(super::super::super::optimizations::Optimization::is_actionable)
            .expect("at least one implemented optimization");
        let unimplemented = crate::optimizations::all()
            .into_iter()
            .find(|entry| !entry.is_actionable())
            .expect("at least one unimplemented optimization");

        let before = json!({
            "schema": 3,
            "optimizations": {
                "preset": "balanced",
                "rendering": {
                    implemented.id: true,
                    unimplemented.id: true,
                    "render.something.invented": true,
                }
            }
        });

        let after = migrate_v3_to_v4(before);
        let rendering = after["optimizations"]["rendering"]
            .as_object()
            .expect("the rendering map survives");

        assert_eq!(
            rendering.get(implemented.id),
            Some(&json!(true)),
            "an implemented optimization must keep its stored value"
        );
        assert!(
            !rendering.contains_key(unimplemented.id),
            "{} has no implementation and must not stay in the map",
            unimplemented.id
        );
        assert!(
            !rendering.contains_key("render.something.invented"),
            "an id the catalogue has never heard of must not survive either"
        );
    }

    /// The migration must not choke on a profile that has no optimization
    /// section at all, which is every profile written before that field
    /// existed.
    #[test]
    fn v4_tolerates_a_profile_with_no_optimizations() {
        use serde_json::json;
        let before = json!({ "schema": 3, "display_name": "Old" });
        let after = migrate_v3_to_v4(before.clone());
        assert_eq!(after, before);

        let empty = json!({ "schema": 3, "optimizations": { "preset": "balanced" } });
        let after = migrate_v3_to_v4(empty.clone());
        assert_eq!(after, empty);
    }

    use crate::profiles::model::{JavaPolicy, Loader};

    fn store() -> (tempfile::TempDir, ProfileStore) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("dirs");
        (tmp, ProfileStore::new(paths))
    }

    fn id(name: &str) -> ProfileId {
        ProfileId::new(name).unwrap()
    }

    /// The exact shape that shipped before schema versioning existed.
    const V1_DOCUMENT: &str = r#"{
        "id": "legacy",
        "display_name": "Legacy Profile",
        "version_id": "1.8.9",
        "loader": "vanilla",
        "jvm": {
            "heap_mb": 4096,
            "extra_args": ["-XX:+UseG1GC"],
            "java_path": "/opt/java8/bin/java",
            "compile_huge_methods": true
        },
        "keep_launcher_open": false
    }"#;

    // -- round trip --------------------------------------------------------

    #[test]
    fn create_load_and_list_round_trip() {
        let (_tmp, store) = store();
        let profile = Profile::new(id("main"), "Main");
        store.create(&profile).unwrap();

        let back = store.load(&id("main")).unwrap();
        assert_eq!(back.display_name, "Main");
        assert_eq!(back.version_id, "1.8.9");

        let listed = store.list();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, id("main"));
    }

    #[test]
    fn creating_the_same_profile_twice_is_refused() {
        let (_tmp, store) = store();
        let profile = Profile::new(id("main"), "Main");
        store.create(&profile).unwrap();
        assert!(matches!(
            store.create(&profile),
            Err(ProfileError::AlreadyExists(_))
        ));
    }

    #[test]
    fn a_saved_profile_carries_the_current_schema_number() {
        let (_tmp, store) = store();
        store.create(&Profile::new(id("main"), "Main")).unwrap();

        let raw = std::fs::read_to_string(store.dirs(&id("main")).manifest_file()).unwrap();
        let value: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(value["schema"], SCHEMA_VERSION);
    }

    #[test]
    fn saving_stamps_the_updated_time() {
        let (_tmp, store) = store();
        let mut profile = Profile::new(id("main"), "Main");
        profile.updated_at = 0;
        store.create(&profile).unwrap();

        let back = store.load(&id("main")).unwrap();
        assert!(back.updated_at > 0, "save must stamp updated_at");
    }

    // -- migrations --------------------------------------------------------

    #[test]
    fn a_v1_document_is_detected_as_v1() {
        let value: serde_json::Value = serde_json::from_str(V1_DOCUMENT).unwrap();
        assert_eq!(detect_version(&value), 1);
    }

    #[test]
    fn migrating_v1_moves_every_field_that_moved() {
        let value: serde_json::Value = serde_json::from_str(V1_DOCUMENT).unwrap();
        let (migrated, from) = migrate(value, &id("legacy")).unwrap();
        assert_eq!(from, 1);

        let profile: Profile = serde_json::from_value(migrated).unwrap();

        assert_eq!(profile.display_name, "Legacy Profile");
        assert_eq!(profile.loader.kind, Loader::Vanilla);
        assert_eq!(profile.memory.heap_mb, 4096);
        assert_eq!(profile.jvm.extra_args, vec!["-XX:+UseG1GC".to_owned()]);
        assert_eq!(
            profile.java,
            JavaPolicy::Custom {
                path: PathBuf::from("/opt/java8/bin/java")
            }
        );
        assert!(profile.optimizations.jvm.compile_huge_methods);
        assert!(!profile.keep_launcher_open);
    }

    /// A v1 profile has no creation time. Inventing "now" would claim it was
    /// created at upgrade time.
    #[test]
    fn migrating_v1_does_not_invent_timestamps() {
        let value: serde_json::Value = serde_json::from_str(V1_DOCUMENT).unwrap();
        let (migrated, _) = migrate(value, &id("legacy")).unwrap();
        let profile: Profile = serde_json::from_value(migrated).unwrap();
        assert_eq!(profile.created_at, 0);
    }

    #[test]
    fn a_v1_file_on_disk_loads_and_reports_that_it_was_migrated() {
        let (_tmp, store) = store();
        let dirs = store.dirs(&id("legacy"));
        dirs.ensure().unwrap();
        std::fs::write(dirs.manifest_file(), V1_DOCUMENT).unwrap();

        let loaded = store.load_detailed(&id("legacy")).unwrap();
        assert_eq!(loaded.outcome, LoadOutcome::Migrated { from: 1 });
        assert!(loaded.outcome.needs_rewrite());
        assert_eq!(loaded.profile.memory.heap_mb, 4096);
    }

    #[test]
    fn consolidating_rewrites_a_migrated_profile_once() {
        let (_tmp, store) = store();
        let dirs = store.dirs(&id("legacy"));
        dirs.ensure().unwrap();
        std::fs::write(dirs.manifest_file(), V1_DOCUMENT).unwrap();

        let done = store.consolidate();
        assert_eq!(done.len(), 1);
        assert_eq!(done[0].1, LoadOutcome::Migrated { from: 1 });

        // Now at the current schema, so a second pass has nothing to do.
        assert!(store.consolidate().is_empty());
        assert_eq!(
            store.load_detailed(&id("legacy")).unwrap().outcome,
            LoadOutcome::Clean
        );
    }

    /// The property that makes downgrades safe: a file from a newer build is
    /// refused rather than reinterpreted.
    #[test]
    fn a_document_from_the_future_is_refused_not_guessed_at() {
        let value = serde_json::json!({
            "schema": SCHEMA_VERSION + 1,
            "id": "future",
            "display_name": "From tomorrow",
            "version_id": "1.8.9"
        });
        let err = migrate(value, &id("future")).expect_err("must refuse");
        assert!(
            matches!(err, ProfileError::SchemaTooNew { found, supported, .. }
                if found == SCHEMA_VERSION + 1 && supported == SCHEMA_VERSION)
        );
    }

    // -- v4 -> v5 -----------------------------------------------------------

    fn v4(loader: &str, mods: &str) -> serde_json::Value {
        serde_json::from_str(&format!(
            r#"{{"schema":4,"id":"p","display_name":"P","version_id":"1.8.9",
                 "loader":{{"kind":"{loader}"}},
                 "mods":{{"mods":[{mods}]}}}}"#
        ))
        .unwrap()
    }

    fn module_entry() -> String {
        r#"{"id":"fathom-optimizations","sha1":"aa","file_name":"m.jar","enabled":true}"#.to_owned()
    }

    /// The one arrangement that was already a Fathom profile: Legacy Fabric
    /// plus the module. It is recognised so the launcher will keep its own
    /// component current in the profiles it seeded itself.
    #[test]
    fn v5_recognises_the_profile_this_launcher_seeded() {
        let migrated = migrate_v4_to_v5(v4("legacy-fabric", &module_entry()));
        assert_eq!(migrated["kind"], "fathom");
        assert_eq!(
            migrated["mods"]["mods"][0]["source"], "first-party",
            "the module must be marked as ours, or it is never updated"
        );
    }

    /// Everything else stays Custom. A hand-built profile is the user's, and
    /// the launcher deciding to maintain its contents would overrule them.
    #[test]
    fn v5_leaves_every_other_arrangement_alone() {
        // Legacy Fabric, but no Fathom module: somebody else's Fabric setup.
        let other = r#"{"id":"some-mod","sha1":"bb","file_name":"o.jar","enabled":true}"#;
        let migrated = migrate_v4_to_v5(v4("legacy-fabric", other));
        assert_eq!(migrated["kind"], "custom");
        assert!(migrated["mods"]["mods"][0].get("source").is_none());

        // The module, but under a loader that cannot load it.
        assert_eq!(migrate_v4_to_v5(v4("vanilla", &module_entry()))["kind"], "custom");

        // Nothing at all.
        assert_eq!(migrate_v4_to_v5(v4("vanilla", ""))["kind"], "custom");
    }

    /// A Vanilla profile stays Custom rather than becoming Vanilla: "no loader"
    /// and "deliberately unmodified" are different intentions, and only the
    /// person who made it knows which one they had.
    #[test]
    fn v5_does_not_guess_that_a_loaderless_profile_meant_vanilla() {
        assert_eq!(migrate_v4_to_v5(v4("vanilla", ""))["kind"], "custom");
    }

    /// A hand-edited file that already says what it is keeps its answer.
    #[test]
    fn v5_never_overwrites_a_kind_that_is_already_there() {
        let mut document = v4("legacy-fabric", &module_entry());
        document["kind"] = serde_json::Value::String("vanilla".to_owned());
        assert_eq!(migrate_v4_to_v5(document)["kind"], "vanilla");
    }

    /// The whole chain, as a profile written by the first build would take it.
    #[test]
    fn a_v1_profile_migrates_all_the_way_to_the_current_schema() {
        let id = ProfileId::new("p").unwrap();
        let document: serde_json::Value =
            serde_json::from_str(r#"{"id":"p","display_name":"P","version_id":"1.8.9"}"#).unwrap();
        let (migrated, from) = migrate(document, &id).unwrap();
        assert_eq!(from, 1);
        let profile: Profile = serde_json::from_value(migrated).unwrap();
        assert_eq!(profile.kind, crate::profiles::ProfileKind::Custom);
    }

    #[test]
    fn a_current_schema_document_needs_no_migration() {
        let (_tmp, store) = store();
        store.create(&Profile::new(id("main"), "Main")).unwrap();
        assert_eq!(
            store.load_detailed(&id("main")).unwrap().outcome,
            LoadOutcome::Clean
        );
    }

    // -- corruption --------------------------------------------------------

    #[test]
    fn a_corrupt_profile_falls_back_to_the_backup_and_says_so() {
        let (_tmp, store) = store();
        let mut profile = Profile::new(id("main"), "Main");
        store.create(&profile).unwrap();

        // A second save is what creates the backup from the first.
        profile.display_name = "Renamed".into();
        store.save(&profile).unwrap();

        let manifest = store.dirs(&id("main")).manifest_file();
        std::fs::write(&manifest, b"{ this is not json").unwrap();

        let loaded = store.load_detailed(&id("main")).unwrap();
        assert!(matches!(
            loaded.outcome,
            LoadOutcome::RecoveredFromBackup { .. }
        ));
        assert!(loaded.outcome.message().is_some());
        // The backup holds the *previous* content.
        assert_eq!(loaded.profile.display_name, "Main");
    }

    /// With no usable backup there is nothing to recover, and the honest answer
    /// is an error rather than a profile silently reset to defaults.
    #[test]
    fn a_corrupt_profile_with_no_backup_is_an_error_not_a_silent_reset() {
        let (_tmp, store) = store();
        store.create(&Profile::new(id("main"), "Main")).unwrap();
        std::fs::write(store.dirs(&id("main")).manifest_file(), b"garbage").unwrap();

        let err = store.load_detailed(&id("main")).expect_err("must fail");
        assert!(matches!(err, ProfileError::Corrupt { .. }), "{err:?}");
    }

    #[test]
    fn an_absurdly_large_profile_file_is_refused_before_parsing() {
        let (_tmp, store) = store();
        let dirs = store.dirs(&id("huge"));
        dirs.ensure().unwrap();
        let padding = "x".repeat(usize::try_from(MAX_PROFILE_BYTES + 1).expect("fits"));
        std::fs::write(
            dirs.manifest_file(),
            format!(r#"{{"schema":2,"note":"{padding}"}}"#),
        )
        .unwrap();

        let err = store.load_detailed(&id("huge")).expect_err("must refuse");
        assert!(matches!(err, ProfileError::Corrupt { .. }), "{err:?}");
    }

    /// A file whose recorded id disagrees with its directory has been moved or
    /// hand-edited. Trusting the file would let a profile impersonate another.
    #[test]
    fn a_profile_claiming_a_different_id_than_its_directory_is_refused() {
        let (_tmp, store) = store();
        let dirs = store.dirs(&id("real"));
        dirs.ensure().unwrap();
        std::fs::write(
            dirs.manifest_file(),
            r#"{"schema":2,"id":"impostor","display_name":"x","version_id":"1.8.9"}"#,
        )
        .unwrap();

        let err = store.load_detailed(&id("real")).expect_err("must refuse");
        assert!(matches!(err, ProfileError::IdMismatch { .. }), "{err:?}");
    }

    #[test]
    fn a_missing_profile_is_not_reported_as_corruption() {
        let (_tmp, store) = store();
        let err = store.load_detailed(&id("nope")).expect_err("must fail");
        assert!(matches!(err, ProfileError::NotFound(_)), "{err:?}");
    }

    /// One broken profile must not make the launcher unusable.
    #[test]
    fn listing_survives_a_corrupt_profile_and_reports_it_separately() {
        let (_tmp, store) = store();
        store.create(&Profile::new(id("good"), "Good")).unwrap();
        store.create(&Profile::new(id("bad"), "Bad")).unwrap();
        std::fs::write(store.dirs(&id("bad")).manifest_file(), b"!!!").unwrap();

        let listed = store.list();
        assert_eq!(listed.len(), 1, "the good one still lists");
        assert_eq!(listed[0].id, id("good"));

        let detailed = store.list_detailed();
        assert_eq!(detailed.len(), 2, "the broken one is reported, not hidden");
        assert!(detailed.iter().any(|(_, r)| r.is_err()));
    }

    #[test]
    fn a_directory_without_a_manifest_is_not_treated_as_a_profile() {
        let (_tmp, store) = store();
        std::fs::create_dir_all(store.paths().profiles_dir().join("scratch")).unwrap();
        assert!(store.list_detailed().is_empty());
    }

    // -- atomicity ---------------------------------------------------------

    /// The safety net: after any successful save there are two readable copies,
    /// so a crash during the *next* save still leaves a good one.
    #[test]
    fn a_second_save_leaves_both_a_current_and_a_previous_copy() {
        let (_tmp, store) = store();
        let mut profile = Profile::new(id("main"), "Main");
        store.create(&profile).unwrap();
        profile.display_name = "Second".into();
        store.save(&profile).unwrap();

        let manifest = store.dirs(&id("main")).manifest_file();
        assert!(manifest.is_file());
        assert!(backup_path(&manifest).is_file());
    }

    #[test]
    fn saving_never_leaves_a_temporary_file_behind() {
        let (_tmp, store) = store();
        store.create(&Profile::new(id("main"), "Main")).unwrap();

        let dir = store.dirs(&id("main")).root().to_path_buf();
        let strays: Vec<String> = std::fs::read_dir(&dir)
            .unwrap()
            .flatten()
            .filter_map(|e| e.file_name().to_str().map(str::to_owned))
            .filter(|n| n.contains(".tmp") || n.ends_with('~'))
            .collect();
        assert!(strays.is_empty(), "left behind: {strays:?}");
    }
}
