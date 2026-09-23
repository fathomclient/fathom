//! What a Fathom Profile *is*.
//!
//! A loadout: which modules are on, how they are configured, where the HUD sits,
//! and the client's own interface preferences. See `docs/fathom-profiles.md` for
//! why this is a different thing from the Game Instance in [`crate::profiles`],
//! which owns the Minecraft version, the loader, the Java runtime and the game
//! directory.
//!
//! # Two rules
//!
//! 1. **Nothing about the installation.** No Minecraft version, no loader, no
//!    Java path, no game directory, no external jar set, no account. A profile
//!    is switched while the game is running, so anything in it that could not
//!    change under a running client does not belong in it.
//! 2. **Only what differs.** A module whose settings are all at their declared
//!    defaults writes no settings. Recording defaults would freeze whatever the
//!    default happened to be on the day the profile was first saved into every
//!    profile forever, and the next person to improve a default would find it
//!    had no effect on anybody.
//!
//! # Why the Rust module is called `loadout` and the directory is `profiles`
//!
//! Because `profile` means two things in this codebase until the Game Instance
//! rename lands, and a type called `Profile` in two modules is how the mistake
//! this file exists to fix gets made a second time. On disk the directory is
//! `profiles/`, which is what the user calls them and what the specification
//! asks for.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Format version of one profile document. Must match `FathomProfile.SCHEMA`
/// on the Java side.
pub const SCHEMA: u32 = 1;

/// The id every collection is guaranteed to be able to fall back to.
pub const DEFAULT_ID: &str = "default";

/// And what it is called when it has to be created.
pub const DEFAULT_NAME: &str = "Default";

const MAX_ID_LEN: usize = 48;
const MAX_NAME_LEN: usize = 64;

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/// A profile's id: lowercase, digits and hyphens, and a filename on three
/// platforms.
///
/// The rules are deliberately the same as the Game Instance id's, because they
/// solve the same problem — this ends up as `profiles/<id>.json` — and having
/// two nearly-identical validators would guarantee they drift.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ProfileId(String);

/// Why an id was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum IdError {
    #[error("a profile id cannot be empty")]
    Empty,
    #[error("a profile id cannot be longer than {MAX_ID_LEN} characters, got {0}")]
    TooLong(usize),
    #[error("a profile id cannot contain {0:?}")]
    InvalidCharacter(char),
    #[error("a profile id has to start and end with a letter or digit")]
    BadBoundary,
    #[error("{0:?} is reserved")]
    Reserved(String),
}

/// Names Windows refuses to create a file with, whatever the extension.
const DEVICE_NAMES: &[&str] = &[
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
    "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// Names this module writes alongside the profiles and must not collide with.
const RESERVED_IDS: &[&str] = &["client-state", "index", "backup", "backups"];

impl ProfileId {
    pub fn new(raw: &str) -> Result<Self, IdError> {
        if raw.is_empty() {
            return Err(IdError::Empty);
        }
        if raw.len() > MAX_ID_LEN {
            return Err(IdError::TooLong(raw.len()));
        }
        for c in raw.chars() {
            if !(c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-') {
                return Err(IdError::InvalidCharacter(c));
            }
        }
        let starts_ok = raw.starts_with(|c: char| c.is_ascii_alphanumeric());
        let ends_ok = raw.ends_with(|c: char| c.is_ascii_alphanumeric());
        if !starts_ok || !ends_ok {
            return Err(IdError::BadBoundary);
        }
        if RESERVED_IDS.contains(&raw) || DEVICE_NAMES.contains(&raw) {
            return Err(IdError::Reserved(raw.to_owned()));
        }
        Ok(Self(raw.to_owned()))
    }

    /// The id for the fallback profile. Infallible by construction.
    pub fn default_id() -> Self {
        Self(DEFAULT_ID.to_owned())
    }

    /// Derive an id from what somebody typed, avoiding ids already taken.
    ///
    /// Returns `None` when nothing usable survives — an all-emoji name — rather
    /// than inventing something the user did not ask for.
    pub fn from_display_name(name: &str, taken: &[ProfileId]) -> Option<Self> {
        let mut slug = String::new();
        let mut last_hyphen = false;
        for c in name.chars() {
            if c.is_ascii_alphanumeric() {
                slug.push(c.to_ascii_lowercase());
                last_hyphen = false;
            } else if !slug.is_empty() && !last_hyphen {
                slug.push('-');
                last_hyphen = true;
            }
        }
        while slug.ends_with('-') {
            slug.pop();
        }
        slug.truncate(MAX_ID_LEN);
        while slug.ends_with('-') {
            slug.pop();
        }
        if slug.is_empty() {
            return None;
        }
        let base = Self::new(&slug)
            .ok()
            .or_else(|| Self::new(&format!("{slug}-1")).ok())?;
        if !taken.contains(&base) {
            return Some(base);
        }
        // "bedwars" taken, try "bedwars-2". Not a random suffix: somebody who
        // duplicates a profile twice should get 2 and 3, not two hex strings.
        for suffix in 2..1000u32 {
            let candidate = format!("{}-{suffix}", base.as_str());
            let truncated = if candidate.len() > MAX_ID_LEN {
                let keep = MAX_ID_LEN - suffix.to_string().len() - 1;
                format!("{}-{suffix}", base.as_str().get(..keep).unwrap_or(""))
            } else {
                candidate
            };
            if let Ok(id) = Self::new(&truncated) {
                if !taken.contains(&id) {
                    return Some(id);
                }
            }
        }
        None
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn is_default(&self) -> bool {
        self.0 == DEFAULT_ID
    }
}

impl std::fmt::Display for ProfileId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

// ---------------------------------------------------------------------------
// The parts
// ---------------------------------------------------------------------------

/// One module's state, as far as it differs from the module's own defaults.
///
/// `enabled` is an `Option` on purpose. `None` means "whatever this module
/// says it should be", which is how a module added in a later version arrives
/// switched on for people who asked for it to be on by default and off for
/// people who did not — rather than arriving off in every profile written
/// before it existed.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleState {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub favourite: bool,
    /// Setting id to its serialised form. Strings because the game owns the
    /// parsing: a setting's kind is declared by the module, and a launcher that
    /// decoded them would be a second opinion about what a setting means.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub settings: BTreeMap<String, String>,
    /// Keybind id to LWJGL key code, as a string for the same reason.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub keys: BTreeMap<String, String>,
}

impl ModuleState {
    /// Whether this is worth writing at all.
    pub fn is_empty(&self) -> bool {
        self.enabled.is_none()
            && !self.favourite
            && self.settings.is_empty()
            && self.keys.is_empty()
    }
}

/// Where one HUD widget sits.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HudEntry {
    /// The corner or edge it is anchored to, e.g. `top-left`.
    pub anchor: String,
    /// Offset from that anchor, inward, in Minecraft's scaled pixels.
    pub x: i32,
    pub y: i32,
    #[serde(default = "one")]
    pub scale: f32,
    #[serde(default = "one")]
    pub opacity: f32,
    #[serde(default, skip_serializing_if = "is_false")]
    pub hidden: bool,
}

impl Default for HudEntry {
    fn default() -> Self {
        Self {
            anchor: "top-left".to_owned(),
            x: 0,
            y: 0,
            scale: 1.0,
            opacity: 1.0,
            hidden: false,
        }
    }
}

/// The client's own interface preferences — not any one module's.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientPrefs {
    /// LWJGL key code for the key that opens the hub.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hub_key: Option<i32>,
    /// One of `Auto`, `90%`, `100%`, `110%`, `125%`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ui_scale: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub high_quality_ui: Option<bool>,
    /// `Fathom` or `Minecraft`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hud_font: Option<String>,
}

/// The performance settings a profile may carry.
///
/// Live-safe ones only. The JVM flags and anything else fixed for the life of
/// the process belong to the Game Instance: a profile switch must never be the
/// thing that asks somebody to restart.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Performance {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub preset: Option<String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub switches: BTreeMap<String, bool>,
}

// ---------------------------------------------------------------------------
// The document
// ---------------------------------------------------------------------------

/// One Fathom Profile.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FathomProfile {
    pub schema: u32,
    pub id: ProfileId,
    pub name: String,
    #[serde(default)]
    pub created_at: u64,
    #[serde(default)]
    pub updated_at: u64,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub modules: BTreeMap<String, ModuleState>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub hud: BTreeMap<String, HudEntry>,
    #[serde(default)]
    pub client: ClientPrefs,
    #[serde(default)]
    pub performance: Performance,
}

impl FathomProfile {
    pub fn new(id: ProfileId, name: impl Into<String>) -> Self {
        let stamp = now();
        Self {
            schema: SCHEMA,
            id,
            name: name.into(),
            created_at: stamp,
            updated_at: stamp,
            modules: BTreeMap::new(),
            hud: BTreeMap::new(),
            client: ClientPrefs::default(),
            performance: Performance::default(),
        }
    }

    /// The profile a collection falls back to. Empty: every module at the
    /// default its own author chose.
    pub fn default_profile() -> Self {
        Self::new(ProfileId::default_id(), DEFAULT_NAME)
    }

    pub fn touch(&mut self) {
        self.updated_at = now();
    }

    /// A copy under a new identity, for Duplicate.
    #[must_use]
    pub fn duplicated(&self, id: ProfileId, name: impl Into<String>) -> Self {
        let stamp = now();
        Self {
            schema: SCHEMA,
            id,
            name: name.into(),
            created_at: stamp,
            updated_at: stamp,
            modules: self.modules.clone(),
            hud: self.hud.clone(),
            client: self.client.clone(),
            performance: self.performance.clone(),
        }
    }

    /// Whether this document is one this build understands and can trust.
    ///
    /// Checked before a switch, not after: the whole point of the transaction
    /// is that a profile which will not load leaves the running one alone.
    pub fn validate(&self) -> Result<(), ValidationError> {
        if self.schema == 0 || self.schema > SCHEMA {
            return Err(ValidationError::Schema(self.schema));
        }
        let trimmed = self.name.trim();
        if trimmed.is_empty() {
            return Err(ValidationError::NoName);
        }
        if trimmed.chars().count() > MAX_NAME_LEN {
            return Err(ValidationError::NameTooLong(trimmed.chars().count()));
        }
        for (widget, entry) in &self.hud {
            if !entry.scale.is_finite() || entry.scale <= 0.0 {
                return Err(ValidationError::BadScale(widget.clone(), entry.scale));
            }
            if !entry.opacity.is_finite() || !(0.0..=1.0).contains(&entry.opacity) {
                return Err(ValidationError::BadOpacity(widget.clone(), entry.opacity));
            }
        }
        Ok(())
    }

    /// Whether this profile holds nothing anybody chose.
    ///
    /// Asked by migration, which may fill in a blank placeholder Default with
    /// real data but must never replace a Default somebody has edited.
    pub fn is_blank(&self) -> bool {
        self.modules.values().all(ModuleState::is_empty)
            && self.hud.is_empty()
            && self.client == ClientPrefs::default()
            && self.performance == Performance::default()
    }

    /// Drop module entries that say nothing, so a saved profile does not grow a
    /// key for every module somebody merely looked at.
    pub fn prune(&mut self) {
        self.modules.retain(|_, state| !state.is_empty());
    }
}

/// Why a profile was refused.
#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum ValidationError {
    #[error("profile schema {0} is not one this build can read")]
    Schema(u32),
    #[error("a profile has to have a name")]
    NoName,
    #[error("a profile name cannot be longer than {MAX_NAME_LEN} characters, got {0}")]
    NameTooLong(usize),
    #[error("widget {0} has an impossible scale of {1}")]
    BadScale(String, f32),
    #[error("widget {0} has an opacity of {1}, which is not between 0 and 1")]
    BadOpacity(String, f32),
}

// ---------------------------------------------------------------------------
// The pointer
// ---------------------------------------------------------------------------

/// `client-state.json`: which profile is live, and whether migration has run.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientState {
    #[serde(default = "schema_default")]
    pub schema: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_profile_id: Option<ProfileId>,
    /// Set once, when the properties files were migrated. Its presence is what
    /// stops migration running a second time and producing a second Default.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub migrated_from: Option<String>,
}

impl ClientState {
    pub fn with_last(id: ProfileId) -> Self {
        Self {
            schema: SCHEMA,
            last_profile_id: Some(id),
            migrated_from: None,
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn schema_default() -> u32 {
    SCHEMA
}

fn one() -> f32 {
    1.0
}

#[allow(clippy::trivially_copy_pass_by_ref)]
fn is_false(value: &bool) -> bool {
    !*value
}

/// Seconds since the epoch, or zero if the clock is before it.
fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_id_is_a_safe_filename() {
        assert!(ProfileId::new("bedwars").is_ok());
        assert!(ProfileId::new("bed-wars-2").is_ok());
        assert_eq!(ProfileId::new(""), Err(IdError::Empty));
        assert!(matches!(
            ProfileId::new("Bedwars"),
            Err(IdError::InvalidCharacter('B'))
        ));
        assert!(matches!(ProfileId::new("-bed"), Err(IdError::BadBoundary)));
        assert!(matches!(ProfileId::new("con"), Err(IdError::Reserved(_))));
        // The one this module writes next to the profiles.
        assert!(matches!(
            ProfileId::new("client-state"),
            Err(IdError::Reserved(_))
        ));
    }

    #[test]
    fn a_name_becomes_an_id_and_then_a_free_one() {
        let taken = vec![ProfileId::new("bedwars").unwrap()];
        assert_eq!(
            ProfileId::from_display_name("Bedwars", &[])
                .unwrap()
                .as_str(),
            "bedwars"
        );
        // Duplicating twice gives 2 then 3, not two hex strings.
        assert_eq!(
            ProfileId::from_display_name("Bedwars", &taken)
                .unwrap()
                .as_str(),
            "bedwars-2"
        );
        assert_eq!(
            ProfileId::from_display_name("  Sky  Wars!! ", &[])
                .unwrap()
                .as_str(),
            "sky-wars"
        );
        // Nothing usable is None rather than an invented name.
        assert_eq!(ProfileId::from_display_name("🎮🎮", &[]), None);
    }

    #[test]
    fn only_what_differs_is_written() {
        let mut profile = FathomProfile::default_profile();
        profile
            .modules
            .insert("fps".to_owned(), ModuleState::default());
        profile.modules.insert(
            "cps".to_owned(),
            ModuleState {
                enabled: Some(true),
                ..ModuleState::default()
            },
        );
        profile.prune();
        assert!(
            !profile.modules.contains_key("fps"),
            "a silent module is dropped"
        );
        assert!(profile.modules.contains_key("cps"));

        let json = serde_json::to_string(&profile).unwrap();
        assert!(!json.contains("favourite"), "a false flag is not written");
        assert!(!json.contains("\"hud\""), "an empty section is not written");
    }

    #[test]
    fn a_profile_from_the_future_is_refused_rather_than_guessed_at() {
        let mut profile = FathomProfile::default_profile();
        assert!(profile.validate().is_ok());
        profile.schema = SCHEMA + 1;
        assert!(matches!(
            profile.validate(),
            Err(ValidationError::Schema(_))
        ));
    }

    #[test]
    fn an_impossible_hud_entry_fails_validation_before_it_is_applied() {
        let mut profile = FathomProfile::default_profile();
        profile.hud.insert(
            "fps".to_owned(),
            HudEntry {
                opacity: 4.0,
                ..HudEntry::default()
            },
        );
        assert!(matches!(
            profile.validate(),
            Err(ValidationError::BadOpacity(_, _))
        ));

        profile.hud.insert(
            "fps".to_owned(),
            HudEntry {
                scale: f32::NAN,
                ..HudEntry::default()
            },
        );
        assert!(matches!(
            profile.validate(),
            Err(ValidationError::BadScale(_, _))
        ));
    }

    #[test]
    fn a_profile_carries_nothing_about_the_installation() {
        // The whole point of the split, as an assertion. If somebody adds a
        // version or a java path to this struct, this fails.
        let profile = FathomProfile::default_profile();
        let json = serde_json::to_string(&profile).unwrap().to_lowercase();
        for forbidden in [
            "version",
            "loader",
            "java",
            "memory",
            "jvm",
            "directory",
            "account",
            "token",
            "username",
            "uuid",
        ] {
            assert!(
                !json.contains(forbidden),
                "a Fathom Profile must not carry {forbidden}: {json}"
            );
        }
    }

    #[test]
    fn a_duplicate_keeps_the_loadout_and_nothing_else() {
        let mut original = FathomProfile::new(ProfileId::new("bedwars").unwrap(), "Bedwars");
        original.modules.insert(
            "cps".to_owned(),
            ModuleState {
                enabled: Some(true),
                ..ModuleState::default()
            },
        );
        let copy = original.duplicated(ProfileId::new("bedwars-2").unwrap(), "Bedwars copy");
        assert_eq!(copy.modules, original.modules);
        assert_ne!(copy.id, original.id);
        assert_ne!(copy.name, original.name);
    }
}
