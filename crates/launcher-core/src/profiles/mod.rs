//! Isolated profiles.
//!
//! A profile owns everything mutable about one way of playing: its own
//! Minecraft directory, mods, config, resource packs, saves, screenshots, logs
//! and JVM configuration. Two profiles share nothing except the
//! content-addressed artifact cache, which is immutable and therefore safe to
//! share.
//!
//! ```text
//! <data>/profiles/<id>/          owned by the profile, deletable
//!     profile.json               the manifest, schema-versioned
//!     profile.json.bak           last known-good copy
//!     minecraft/                 the game's working directory
//!         saves/ options.txt resourcepacks/ screenshots/ mods/ config/
//!     natives/                   extracted per profile, never shared
//!     logs/                      the launcher's capture of the game's output
//!
//! <data>/shared/                 immutable, content-addressed, never deleted
//!     libraries/ assets/ versions/          by a profile operation
//!
//! <data>/trash/<id>-<stamp>/     where deleted profiles go first
//! ```
//!
//! The security property this module carries is **profile escape**: a profile
//! id arrives from a config file or from user input and becomes a directory
//! name. [`ProfileId`] is the chokepoint — one cannot be built without passing
//! validation, and every path helper hangs off it.

pub mod blueprint;
pub mod game_options;
pub mod inherit;
pub mod model;
pub mod ops;
pub mod seed;
pub mod store;
pub mod validate;

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::fs_safety::{self, FsError};
use crate::paths::DataPaths;

pub use blueprint::{Blueprint, MemoryChoice};
pub use inherit::{derive, may_parent, InheritError};
pub use model::{
    DisplayConfig, GameDirectory, JavaPolicy, JavaPreference, JvmOptimizations, JvmOptions, Loader,
    LoaderConfig, MemoryConfig, ModConfiguration, OptimizationConfig, OptimizationPreset,
    Presentation, Profile, ProfileKind, Resolution, ServerShortcut, Timestamp,
};
pub use ops::{DeletionReceipt, ExportedProfile, ImportOutcome, ProfileOps};
pub use store::{LoadOutcome, Loaded, ProfileStore, SCHEMA_VERSION};
pub use validate::{Defect, ProfileHealth, ProfileReport, RepairAction, RepairSummary};

#[derive(Debug, thiserror::Error)]
pub enum ProfileError {
    #[error("profile id must not be empty")]
    Empty,
    #[error("profile id is too long ({0} characters, maximum {MAX_ID_LEN})")]
    TooLong(usize),
    #[error(
        "profile id may only contain lowercase letters, digits and hyphens; \
         found {0:?}"
    )]
    InvalidCharacter(char),
    #[error("profile id must start and end with a letter or digit")]
    BadBoundary,
    #[error("profile id {0:?} is reserved")]
    Reserved(String),
    #[error("profile {0} does not exist")]
    NotFound(String),
    #[error("profile {0} already exists")]
    AlreadyExists(String),
    #[error("no usable profile id could be derived from {0:?}")]
    UnusableName(String),

    #[error("profile {profile} is corrupt and has no recoverable backup: {detail}")]
    Corrupt { profile: String, detail: String },
    #[error(
        "profile {profile} was written by a newer version of Fathom \
         (schema v{found}, this build understands v{supported}). Upgrade rather than \
         opening it with this build."
    )]
    SchemaTooNew {
        profile: String,
        found: u32,
        supported: u32,
    },
    #[error("profile {profile} is at schema v{from}, which this build cannot migrate")]
    NoMigration { profile: String, from: u32 },
    #[error(
        "the profile in directory {directory} records its id as {recorded}; \
         refusing to load a profile that disagrees with where it lives"
    )]
    IdMismatch { directory: String, recorded: String },

    #[error(
        "refusing to delete {0}: it is not inside launcher-controlled storage. \
         This is a bug or a tampered profile file."
    )]
    RefusingToDeleteOutsideStorage(String),

    /// Distinct from the above, because the two refusals mean different things
    /// and a user told the wrong one will look in the wrong place. `shared/` is
    /// squarely inside launcher storage; it is simply not something the recovery
    /// area operations may touch.
    #[error(
        "refusing to act on {0}: only entries inside the recovery area can be \
         restored or permanently removed."
    )]
    NotInRecoveryArea(String),

    #[error(transparent)]
    Fs(#[from] FsError),
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("could not parse {path}: {source}")]
    Malformed {
        path: String,
        #[source]
        source: serde_json::Error,
    },
}

const MAX_ID_LEN: usize = 64;

/// Ids we refuse regardless of character validity, because they collide with
/// the layout or with platform special cases.
const RESERVED_IDS: &[&str] = &[
    "shared", "logs", "java", "profiles", "tmp", "temp", "trash", "scratch",
];

/// Windows device names, which cannot be used as a directory name on Windows
/// and behave unpredictably when tried.
///
/// Checked on **every** platform, not just Windows: a profile directory created
/// on Linux and synced to a Windows machine would be unopenable there, and a
/// launcher that produces data its other builds cannot read is broken by
/// design. The same reasoning is why `fs_safety::contain` checks them
/// unconditionally.
const DEVICE_NAMES: &[&str] = &[
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
    "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// A validated profile identifier. Safe to use as a single path component.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize)]
pub struct ProfileId(String);

impl ProfileId {
    pub fn new(raw: &str) -> Result<Self, ProfileError> {
        if raw.is_empty() {
            return Err(ProfileError::Empty);
        }
        if raw.len() > MAX_ID_LEN {
            return Err(ProfileError::TooLong(raw.len()));
        }
        for c in raw.chars() {
            if !(c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-') {
                return Err(ProfileError::InvalidCharacter(c));
            }
        }
        // Rejecting leading/trailing hyphens also rejects "-", "--", and
        // anything that could be read as a command-line flag.
        let starts_ok = raw.starts_with(|c: char| c.is_ascii_alphanumeric());
        let ends_ok = raw.ends_with(|c: char| c.is_ascii_alphanumeric());
        if !starts_ok || !ends_ok {
            return Err(ProfileError::BadBoundary);
        }
        if RESERVED_IDS.contains(&raw) {
            return Err(ProfileError::Reserved(raw.to_owned()));
        }
        // Ids are lowercase by the check above, so a direct comparison covers
        // every casing a user could have typed.
        if DEVICE_NAMES.contains(&raw) {
            return Err(ProfileError::Reserved(raw.to_owned()));
        }
        Ok(Self(raw.to_owned()))
    }

    /// Derive an id from a display name. Returns `None` when nothing usable
    /// survives, rather than inventing a name.
    pub fn from_display_name(name: &str) -> Option<Self> {
        let mut slug = String::new();
        let mut last_hyphen = false;
        for c in name.chars() {
            let mapped = if c.is_ascii_alphanumeric() {
                last_hyphen = false;
                c.to_ascii_lowercase()
            } else if !last_hyphen && !slug.is_empty() {
                last_hyphen = true;
                '-'
            } else {
                continue;
            };
            if slug.len() < MAX_ID_LEN {
                slug.push(mapped);
            }
        }
        while slug.ends_with('-') {
            slug.pop();
        }
        Self::new(&slug).ok()
    }

    /// Derive an id that is not already taken, appending `-2`, `-3`, and so on.
    ///
    /// Used by create and clone. The suffix is applied to the *slug*, so
    /// "My Profile" and "My Profile" produce `my-profile` and `my-profile-2`
    /// rather than something unrecognisable.
    pub fn unique_from_display_name(
        name: &str,
        taken: impl Fn(&ProfileId) -> bool,
    ) -> Result<Self, ProfileError> {
        let base = Self::from_display_name(name)
            .ok_or_else(|| ProfileError::UnusableName(name.to_owned()))?;
        if !taken(&base) {
            return Ok(base);
        }
        for n in 2..1000u32 {
            let suffix = format!("-{n}");
            // Keep room for the suffix rather than producing something over the
            // limit and failing at the last step.
            let room = MAX_ID_LEN - suffix.len();
            let mut candidate = base.0.clone();
            candidate.truncate(candidate.len().min(room));
            while candidate.ends_with('-') {
                candidate.pop();
            }
            candidate.push_str(&suffix);

            if let Ok(id) = Self::new(&candidate) {
                if !taken(&id) {
                    return Ok(id);
                }
            }
        }
        Err(ProfileError::AlreadyExists(base.0))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ProfileId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

// Deserialising must validate: a profile id read from a file is untrusted
// input, exactly like one typed by the user.
impl<'de> Deserialize<'de> for ProfileId {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let raw = String::deserialize(d)?;
        ProfileId::new(&raw).map_err(serde::de::Error::custom)
    }
}

/// Resolved, isolated directory layout for one profile.
///
/// Every accessor returns a path under `root`, and `root` is under the data
/// root, so nothing here can reach unrelated directories.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileDirs {
    root: PathBuf,
    /// Where the game actually writes. Usually `root/minecraft`, but a profile
    /// can point at a directory the user chose — which is then never ours to
    /// delete. See [`GameDirectory`].
    game: PathBuf,
}

impl ProfileDirs {
    pub fn new(paths: &DataPaths, id: &ProfileId) -> Self {
        let root = paths.profiles_dir().join(id.as_str());
        let game = root.join("minecraft");
        Self { root, game }
    }

    /// The layout for a profile whose game directory the user chose.
    ///
    /// Only the game directory moves. Natives, logs and the manifest stay
    /// inside launcher storage, because those are the launcher's own files.
    pub fn with_game_directory(paths: &DataPaths, id: &ProfileId, game: &GameDirectory) -> Self {
        let mut dirs = Self::new(paths, id);
        if let GameDirectory::External { path } = game {
            dirs.game.clone_from(path);
        }
        dirs
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn manifest_file(&self) -> PathBuf {
        self.root.join("profile.json")
    }

    /// The game's working directory.
    pub fn minecraft_dir(&self) -> PathBuf {
        self.game.clone()
    }

    pub fn mods_dir(&self) -> PathBuf {
        self.game.join("mods")
    }

    pub fn config_dir(&self) -> PathBuf {
        self.game.join("config")
    }

    pub fn resource_packs_dir(&self) -> PathBuf {
        self.game.join("resourcepacks")
    }

    pub fn saves_dir(&self) -> PathBuf {
        self.game.join("saves")
    }

    pub fn screenshots_dir(&self) -> PathBuf {
        self.game.join("screenshots")
    }

    pub fn options_file(&self) -> PathBuf {
        self.game.join("options.txt")
    }

    /// Game logs. Separate from the launcher's own logs.
    pub fn logs_dir(&self) -> PathBuf {
        self.root.join("logs")
    }

    /// Extracted native libraries, per profile because the extracted set
    /// depends on the version and the platform, and because this directory goes
    /// on the JVM's library search path.
    pub fn natives_dir(&self) -> PathBuf {
        self.root.join("natives")
    }

    /// Everything the profile owns that holds the user's own data.
    ///
    /// This is the list that must survive a repair and be carried by a clone.
    pub fn instance_data_dirs(&self) -> Vec<PathBuf> {
        vec![
            self.saves_dir(),
            self.resource_packs_dir(),
            self.screenshots_dir(),
            self.mods_dir(),
            self.config_dir(),
            self.logs_dir(),
        ]
    }

    /// Every directory that must exist before launching.
    pub fn all_dirs(&self) -> Vec<PathBuf> {
        vec![
            self.root.clone(),
            self.minecraft_dir(),
            self.mods_dir(),
            self.config_dir(),
            self.resource_packs_dir(),
            self.saves_dir(),
            self.screenshots_dir(),
            self.logs_dir(),
            self.natives_dir(),
        ]
    }

    pub fn ensure(&self) -> Result<(), ProfileError> {
        for dir in self.all_dirs() {
            std::fs::create_dir_all(&dir).map_err(|source| ProfileError::Io {
                path: dir.display().to_string(),
                source,
            })?;
        }
        Ok(())
    }

    /// Turn an untrusted relative name (a mod filename, a resource pack) into a
    /// path guaranteed to be inside this profile.
    pub fn contain(&self, relative: &str) -> Result<PathBuf, ProfileError> {
        Ok(fs_safety::contain(&self.root, relative)?.into_path())
    }

    /// The same, for the game directory rather than the profile root. Used for
    /// mods, which live under the game directory and may therefore be outside
    /// the profile when the game directory is external.
    pub fn contain_in_game(&self, relative: &str) -> Result<PathBuf, ProfileError> {
        Ok(fs_safety::contain(&self.game, relative)?.into_path())
    }
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

    fn paths() -> (tempfile::TempDir, DataPaths) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("dirs");
        (tmp, paths)
    }

    #[test]
    fn valid_ids_are_accepted() {
        for raw in ["main", "a", "1", "my-profile", "pvp-1-8-9", "a-b-c-1"] {
            assert!(ProfileId::new(raw).is_ok(), "{raw} should be valid");
        }
    }

    #[test]
    fn ids_that_could_escape_the_profiles_directory_are_rejected() {
        for raw in [
            "..",
            "../evil",
            "..\\evil",
            "/etc",
            "C:\\Windows",
            "a/b",
            "a\\b",
            "a:b",
            "a\0b",
            "CON",
            "con",
            "-leading",
            "trailing-",
            "-",
            "Upper",
            "sp ace",
            "emoji\u{1f600}",
            "",
        ] {
            assert!(
                ProfileId::new(raw).is_err(),
                "{raw:?} must not be a valid profile id"
            );
        }
    }

    #[test]
    fn reserved_ids_are_rejected() {
        for raw in RESERVED_IDS {
            assert!(matches!(
                ProfileId::new(raw),
                Err(ProfileError::Reserved(_))
            ));
        }
    }

    /// `profiles/con` cannot be created on Windows, and a profile directory one
    /// build can make and another cannot open is a data-loss bug waiting to
    /// happen. Refused on every platform for that reason.
    #[test]
    fn windows_device_names_are_rejected_as_profile_ids() {
        for raw in DEVICE_NAMES {
            assert!(
                matches!(ProfileId::new(raw), Err(ProfileError::Reserved(_))),
                "{raw} must not be a valid profile id"
            );
        }
        // And they must not be reachable by slugifying a display name either.
        assert!(ProfileId::from_display_name("CON").is_none());
        assert!(ProfileId::from_display_name("nul").is_none());
    }

    /// A profile id read from a file is untrusted exactly like one typed in.
    #[test]
    fn deserialising_an_id_validates_it() {
        assert!(serde_json::from_str::<ProfileId>(r#""main""#).is_ok());
        for hostile in [r#""../escape""#, r#""CON""#, r#""shared""#, r#""""#] {
            assert!(
                serde_json::from_str::<ProfileId>(hostile).is_err(),
                "{hostile} must not deserialise"
            );
        }
    }

    #[test]
    fn display_names_slugify_predictably() {
        let cases = [
            ("My Profile", Some("my-profile")),
            ("  spaced  out  ", Some("spaced-out")),
            ("1.8.9 PvP", Some("1-8-9-pvp")),
            ("UPPER case", Some("upper-case")),
            ("---", None),
            ("", None),
            ("\u{6e2c}\u{8a66}", None),
        ];
        for (input, expected) in cases {
            let got = ProfileId::from_display_name(input);
            assert_eq!(
                got.as_ref().map(ProfileId::as_str),
                expected,
                "slugifying {input:?}"
            );
        }
    }

    #[test]
    fn a_taken_id_gets_a_numeric_suffix() {
        let taken = |id: &ProfileId| matches!(id.as_str(), "my-profile" | "my-profile-2");
        let id = ProfileId::unique_from_display_name("My Profile", taken).unwrap();
        assert_eq!(id.as_str(), "my-profile-3");
    }

    #[test]
    fn a_name_with_nothing_usable_in_it_is_refused_rather_than_invented() {
        let err = ProfileId::unique_from_display_name("\u{6e2c}\u{8a66}", |_| false)
            .expect_err("nothing usable");
        assert!(matches!(err, ProfileError::UnusableName(_)));
    }

    #[test]
    fn a_very_long_name_still_leaves_room_for_a_suffix() {
        let long = "a".repeat(MAX_ID_LEN);
        let id = ProfileId::unique_from_display_name(&long, |c| c.as_str() == long).unwrap();
        assert!(id.as_str().len() <= MAX_ID_LEN);
        assert!(id.as_str().ends_with("-2"));
    }

    #[test]
    fn profiles_are_isolated_from_each_other() {
        let (_tmp, paths) = paths();
        let a = ProfileDirs::new(&paths, &ProfileId::new("a").unwrap());
        let b = ProfileDirs::new(&paths, &ProfileId::new("b").unwrap());

        assert_ne!(a.root(), b.root());
        assert!(!a.saves_dir().starts_with(b.root()));
        assert!(!b.saves_dir().starts_with(a.root()));
    }

    #[test]
    fn every_profile_directory_is_inside_the_profile_root() {
        let (_tmp, paths) = paths();
        let dirs = ProfileDirs::new(&paths, &ProfileId::new("main").unwrap());
        for dir in dirs.all_dirs() {
            assert!(
                dir.starts_with(dirs.root()),
                "{} escapes the profile",
                dir.display()
            );
        }
        assert!(dirs.manifest_file().starts_with(dirs.root()));
    }

    #[test]
    fn contain_rejects_traversal_out_of_a_profile() {
        let (_tmp, paths) = paths();
        let dirs = ProfileDirs::new(&paths, &ProfileId::new("main").unwrap());
        assert!(dirs.contain("mods/fine.jar").is_ok());
        for hostile in ["../../evil.jar", "..\\..\\evil.jar", "a:b", "con"] {
            assert!(dirs.contain(hostile).is_err(), "{hostile} must be refused");
        }

        // A leading separator is *stripped* rather than refused, because zip
        // entry names conventionally carry one. The property that matters is
        // that the result cannot leave the profile, so assert that instead of
        // assuming a particular error.
        let contained = dirs
            .contain("/etc/passwd")
            .expect("a rooted-looking name is contained, not refused");
        assert!(
            contained.starts_with(dirs.root()),
            "{} escaped the profile",
            contained.display()
        );
    }

    /// An external game directory moves only the game's data. The launcher's
    /// own files stay in launcher storage, where the deletion rules apply.
    #[test]
    fn an_external_game_directory_moves_only_the_game_data() {
        let (_tmp, paths) = paths();
        let id = ProfileId::new("main").unwrap();
        let external = PathBuf::from("/somewhere/else/.minecraft");
        let dirs = ProfileDirs::with_game_directory(
            &paths,
            &id,
            &GameDirectory::External {
                path: external.clone(),
            },
        );

        assert_eq!(dirs.minecraft_dir(), external);
        assert_eq!(dirs.saves_dir(), external.join("saves"));

        // But these remain ours.
        assert!(dirs.manifest_file().starts_with(dirs.root()));
        assert!(dirs.natives_dir().starts_with(dirs.root()));
        assert!(dirs.logs_dir().starts_with(dirs.root()));
    }
}
