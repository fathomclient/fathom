//! Which mods a profile wants.
//!
//! This is the *intent*, stored in the profile. It is not a description of the
//! mods directory — that is derived from this, at launch, by
//! [`super::materialize`].
//!
//! # Why toggling is not a rename
//!
//! The obvious way to disable a mod is to rename `x.jar` to `x.jar.disabled`.
//! It is also the wrong way:
//!
//! - It is **destructive**. A crash between the two renames leaves a file with
//!   a name nobody chose, and a user who looks at the directory sees something
//!   the launcher did to their files.
//! - It is **not idempotent**. Two launches racing, or a launcher that dies
//!   mid-toggle, leave the directory in a state that depends on interleaving.
//! - It **loses information**. `x.jar.disabled` cannot record *why* it is off,
//!   or that it was disabled automatically because it conflicted with something.
//! - It **cannot express a decision that has not happened yet**, such as "this
//!   is enabled but its dependency is missing".
//!
//! The profile-level representation has none of those problems. The enabled set
//! is a fact about the profile; the directory is a consequence, rebuilt from
//! scratch whenever it needs to be. Toggling is a field assignment.

use serde::{Deserialize, Serialize};

use std::collections::BTreeSet;

use super::definition::{ModId, ModVersion};

/// Why a mod is off.
///
/// A user who turned something off and a resolver that turned it off are
/// different situations, and only one of them should be silently re-enabled
/// when the conflict goes away.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum DisabledReason {
    /// The user turned it off.
    #[default]
    UserChoice,
    /// The launcher turned it off because of a conflict, and recorded which.
    Conflict { with: ModId, detail: String },
}

/// Where a mod in a profile came from.
///
/// Recorded per entry rather than inferred from the catalogue, because the same
/// jar can arrive by different routes and the route is the fact that matters
/// afterwards. `OptiFine` is the reason: its licence forbids redistribution, so
/// the only lawful way for it to be in a profile is that a person put it there,
/// and this field is the record that they did. A support question about a
/// profile containing `OptiFine` is answered by this field, not by guesswork.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ModSource {
    /// A file the user chose from their own disk. The default, because every
    /// entry written before this field existed got there exactly that way.
    #[default]
    UserSupplied,
    /// A Fathom component the launcher installs and maintains.
    FirstParty,
    /// A third-party mod the launcher fetched from a repository and can keep
    /// up to date.
    ///
    /// Spelled `catalogue` on disk, and read under either name. The concept is
    /// the same one it always was -- "the launcher obtained this, so the
    /// launcher may replace it" -- but "managed" is what the product calls it
    /// now that a real repository exists behind it, and renaming the variant
    /// without renaming the stored value keeps every profile written before
    /// this readable.
    #[serde(rename = "catalogue", alias = "managed")]
    Managed,
}

impl ModSource {
    pub fn label(self) -> &'static str {
        match self {
            ModSource::UserSupplied => "You added this",
            ModSource::FirstParty => "Fathom component",
            ModSource::Managed => "Managed",
        }
    }

    /// Whether the launcher put this here and may replace or remove it.
    ///
    /// Only first-party components. A user-supplied jar is the user's, and the
    /// launcher removing one during an upgrade would be deleting a file it did
    /// not obtain.
    pub fn is_launcher_owned(self) -> bool {
        matches!(self, ModSource::FirstParty)
    }
}

/// What the launcher knows about where a mod came from.
///
/// # Why this is stored with the profile and not looked up
///
/// Because the answer has to survive going offline, the repository changing its
/// mind, and the project being taken down. "Which version of what did I
/// install, under what licence, and from where" is a question about a past
/// action, and a past action does not become unanswerable because a server is
/// unreachable.
///
/// Every field is optional and skipped when empty, so a user-supplied jar --
/// which genuinely has none of this -- adds nothing to the file. That is also
/// what makes the field safe to add: a profile written before it existed reads
/// back with an empty record rather than failing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Origin {
    /// The name the repository gave it, for display instead of a filename.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// One line about what it does, as published.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    /// The licence as it stood when this was installed. A string rather than a
    /// [`super::License`] because it is a record of what was reported, not a
    /// classification we are asserting.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub license: Option<String>,
    /// Which Minecraft versions the published metadata claimed.
    #[serde(default, skip_serializing_if = "BTreeSet::is_empty")]
    pub minecraft: BTreeSet<String>,
    /// Which loaders it claimed, in the repository's vocabulary.
    #[serde(default, skip_serializing_if = "BTreeSet::is_empty")]
    pub loaders: BTreeSet<String>,
    /// The repository this came from, e.g. `modrinth`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repository: Option<String>,
    /// The repository's own project identifier, which is what an update check
    /// asks about.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project: Option<String>,
    /// The exact version installed, so an update can tell whether one is
    /// available without comparing version strings.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version_id: Option<String>,
    /// A page a person can read. Built from an identifier, never taken from a
    /// response body.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub page: Option<String>,
    /// Other entries in this profile, by [`ModId`], that this one needs.
    ///
    /// Recorded by the mod browser from the required dependencies it resolved
    /// at install time, so removing a library can be refused while something
    /// still depends on it. Empty for a jar somebody added by hand, which means
    /// "not known" rather than "needs nothing".
    #[serde(default, skip_serializing_if = "BTreeSet::is_empty")]
    pub requires: BTreeSet<String>,
}

impl Origin {
    pub fn is_empty(&self) -> bool {
        self == &Origin::default()
    }

    /// Whether this entry can be checked for updates.
    pub fn is_updatable(&self) -> bool {
        self.project.is_some() && self.version_id.is_some()
    }
}

/// One mod this profile wants, and whether it is on.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProfileMod {
    pub id: ModId,
    /// The file in the mod store this entry refers to, by SHA-1.
    ///
    /// A hash rather than a filename: the store is content-addressed, so this
    /// identifies exactly one set of bytes. Two profiles referring to the same
    /// hash are referring to the same file, and a file that changed underneath
    /// us is a different hash rather than the same name with different contents.
    pub sha1: String,
    /// The name the file had when it was added. Display only; never a path.
    pub file_name: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disabled_reason: Option<DisabledReason>,
    /// The version recorded when it was added, for display.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<ModVersion>,
    /// How this entry got here. See [`ModSource`].
    #[serde(default)]
    pub source: ModSource,
    /// What was known about it when it was added. Empty for a jar somebody
    /// dropped in, which is the honest answer for one.
    #[serde(default, skip_serializing_if = "Origin::is_empty")]
    pub origin: Origin,
}

fn default_true() -> bool {
    true
}

impl ProfileMod {
    pub fn new(id: ModId, sha1: impl Into<String>, file_name: impl Into<String>) -> Self {
        Self {
            id,
            sha1: sha1.into(),
            file_name: file_name.into(),
            enabled: true,
            disabled_reason: None,
            version: None,
            source: ModSource::default(),
            origin: Origin::default(),
        }
    }

    /// Record what the repository said about it.
    #[must_use]
    pub fn with_origin(mut self, origin: Origin) -> Self {
        self.origin = origin;
        self
    }

    /// Mark where this entry came from. Chained onto `new`, because the source
    /// is known at the moment an entry is created and nowhere else.
    #[must_use]
    pub fn from_source(mut self, source: ModSource) -> Self {
        self.source = source;
        self
    }

    /// Turn it on, clearing whatever reason it was off for.
    pub fn enable(&mut self) {
        self.enabled = true;
        self.disabled_reason = None;
    }

    pub fn disable(&mut self, reason: DisabledReason) {
        self.enabled = false;
        self.disabled_reason = Some(reason);
    }

    /// Whether the launcher turned this off rather than the user.
    ///
    /// The distinction that matters when a conflict is resolved: something the
    /// launcher disabled may be offered back, something the user disabled stays
    /// off until they say otherwise.
    pub fn disabled_automatically(&self) -> bool {
        matches!(self.disabled_reason, Some(DisabledReason::Conflict { .. }))
    }
}

/// Everything a profile records about its mods.
///
/// Ordered by id, so the file is stable between saves and a diff shows what
/// actually changed rather than a reshuffle.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ProfileModConfiguration {
    #[serde(default)]
    pub mods: Vec<ProfileMod>,
}

impl ProfileModConfiguration {
    pub fn is_empty(&self) -> bool {
        self.mods.is_empty()
    }

    pub fn len(&self) -> usize {
        self.mods.len()
    }

    pub fn get(&self, id: &ModId) -> Option<&ProfileMod> {
        self.mods.iter().find(|m| &m.id == id)
    }

    pub fn get_mut(&mut self, id: &ModId) -> Option<&mut ProfileMod> {
        self.mods.iter_mut().find(|m| &m.id == id)
    }

    pub fn contains(&self, id: &ModId) -> bool {
        self.get(id).is_some()
    }

    /// Every configured entry, enabled or not.
    ///
    /// The interface needs this to show a disabled mod as disabled rather than
    /// as absent; `enabled()` alone cannot distinguish "switched off" from
    /// "never added".
    pub fn iter(&self) -> impl Iterator<Item = &ProfileMod> {
        self.mods.iter()
    }

    /// The mods the user has switched on. Not the effective set — that is what
    /// [`super::resolve`] produces after dependencies and conflicts.
    pub fn enabled(&self) -> impl Iterator<Item = &ProfileMod> {
        self.mods.iter().filter(|m| m.enabled)
    }

    /// Add or replace an entry, keeping the list sorted.
    ///
    /// Replacing rather than appending is what makes adding the same mod twice
    /// an update instead of a duplicate. Duplicate ids in a mods directory mean
    /// two jars claiming the same mod, which is a launch failure.
    pub fn upsert(&mut self, entry: ProfileMod) {
        match self.mods.iter_mut().find(|m| m.id == entry.id) {
            Some(existing) => *existing = entry,
            None => self.mods.push(entry),
        }
        self.mods.sort_by(|a, b| a.id.cmp(&b.id));
    }

    pub fn remove(&mut self, id: &ModId) -> Option<ProfileMod> {
        let index = self.mods.iter().position(|m| &m.id == id)?;
        Some(self.mods.remove(index))
    }

    /// Toggle deterministically.
    ///
    /// Returns whether anything changed, so a caller can avoid a pointless
    /// save. Setting a mod to the state it is already in is a no-op rather than
    /// an error: idempotence is the point.
    pub fn set_enabled(&mut self, id: &ModId, enabled: bool) -> bool {
        let Some(entry) = self.get_mut(id) else {
            return false;
        };
        if entry.enabled == enabled {
            return false;
        }
        if enabled {
            entry.enable();
        } else {
            entry.disable(DisabledReason::UserChoice);
        }
        true
    }

    /// Every distinct file hash the profile refers to.
    ///
    /// What the mod store has to hold for this profile to be materializable.
    pub fn referenced_hashes(&self) -> Vec<&str> {
        let mut out: Vec<&str> = self.mods.iter().map(|m| m.sha1.as_str()).collect();
        out.sort_unstable();
        out.dedup();
        out
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

    fn id(raw: &str) -> ModId {
        ModId::new(raw).unwrap()
    }

    fn entry(name: &str) -> ProfileMod {
        ProfileMod::new(id(name), "a".repeat(40), format!("{name}.jar"))
    }

    #[test]
    fn a_new_entry_is_enabled_with_no_reason_to_be_off() {
        let m = entry("example");
        assert!(m.enabled);
        assert!(m.disabled_reason.is_none());
        assert!(!m.disabled_automatically());
    }

    /// The property that makes this better than renaming: doing it twice is the
    /// same as doing it once.
    #[test]
    fn toggling_is_idempotent() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("example"));

        assert!(config.set_enabled(&id("example"), false));
        assert!(
            !config.set_enabled(&id("example"), false),
            "no-op the second time"
        );
        assert!(!config.get(&id("example")).unwrap().enabled);

        assert!(config.set_enabled(&id("example"), true));
        assert!(!config.set_enabled(&id("example"), true));
        assert!(config.get(&id("example")).unwrap().enabled);
    }

    #[test]
    fn toggling_something_that_is_not_there_changes_nothing() {
        let mut config = ProfileModConfiguration::default();
        assert!(!config.set_enabled(&id("absent"), false));
        assert!(config.is_empty());
    }

    /// A user's decision and the launcher's are different, and re-enabling
    /// automatically must not silently override the user.
    #[test]
    fn who_disabled_a_mod_is_recorded() {
        let mut user = entry("a");
        user.disable(DisabledReason::UserChoice);
        assert!(!user.disabled_automatically());

        let mut automatic = entry("b");
        automatic.disable(DisabledReason::Conflict {
            with: id("optifine"),
            detail: "renderer overlap".into(),
        });
        assert!(automatic.disabled_automatically());

        // Enabling clears the reason, so a stale explanation cannot linger.
        automatic.enable();
        assert!(automatic.disabled_reason.is_none());
    }

    /// Two jars claiming the same mod is a launch failure. Adding the same id
    /// twice must update rather than duplicate.
    #[test]
    fn adding_the_same_id_twice_updates_rather_than_duplicating() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("example"));

        let mut newer = ProfileMod::new(id("example"), "b".repeat(40), "example-2.0.jar");
        newer.version = Some(ModVersion::new("2.0"));
        config.upsert(newer);

        assert_eq!(config.len(), 1);
        assert_eq!(
            config.get(&id("example")).unwrap().file_name,
            "example-2.0.jar"
        );
        assert_eq!(config.get(&id("example")).unwrap().sha1, "b".repeat(40));
    }

    /// A stable file makes a diff mean something.
    #[test]
    fn entries_stay_sorted_by_id() {
        let mut config = ProfileModConfiguration::default();
        for name in ["zebra", "apple", "mango"] {
            config.upsert(entry(name));
        }
        let ids: Vec<&str> = config.mods.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, vec!["apple", "mango", "zebra"]);
    }

    #[test]
    fn removing_returns_what_was_removed() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("example"));
        let removed = config.remove(&id("example")).expect("was there");
        assert_eq!(removed.id, id("example"));
        assert!(config.is_empty());
        assert!(config.remove(&id("example")).is_none());
    }

    #[test]
    fn the_enabled_set_excludes_disabled_entries() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("on"));
        config.upsert(entry("off"));
        config.set_enabled(&id("off"), false);

        let enabled: Vec<&str> = config.enabled().map(|m| m.id.as_str()).collect();
        assert_eq!(enabled, vec!["on"]);
    }

    #[test]
    fn referenced_hashes_are_deduplicated() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(ProfileMod::new(id("a"), "f".repeat(40), "a.jar"));
        config.upsert(ProfileMod::new(id("b"), "f".repeat(40), "b.jar"));
        config.upsert(ProfileMod::new(id("c"), "e".repeat(40), "c.jar"));

        assert_eq!(config.referenced_hashes().len(), 2);
    }

    #[test]
    fn a_configuration_round_trips_through_serialisation() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("a"));
        let mut b = entry("b");
        b.disable(DisabledReason::Conflict {
            with: id("a"),
            detail: "overlap".into(),
        });
        config.upsert(b);

        let json = serde_json::to_string(&config).unwrap();
        let back: ProfileModConfiguration = serde_json::from_str(&json).unwrap();
        assert_eq!(config, back);
    }

    /// The stored form never contains a path, only a file name and a hash.
    #[test]
    fn the_stored_form_carries_no_paths() {
        let mut config = ProfileModConfiguration::default();
        config.upsert(entry("example"));
        let json = serde_json::to_string(&config).unwrap();
        assert!(!json.contains('/'), "{json}");
        assert!(!json.contains('\\'), "{json}");
    }
}
