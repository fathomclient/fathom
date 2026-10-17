//! What a mod *is*.
//!
//! Three types that are deliberately not one type:
//!
//! | Type | Answers |
//! |---|---|
//! | [`ModDefinition`] | What is this mod? Where does it come from, what may we do with it, what does it need? |
//! | [`super::InstalledMod`] | Which bytes are on this machine, and what is their hash? |
//! | [`super::ProfileModConfiguration`] | Which mods does *this profile* want, and are they on? |
//!
//! Collapsing them is the mistake this milestone exists to avoid. "A list of jar
//! filenames" cannot express *`OptiFine` may never be auto-downloaded*, cannot say
//! *this needs Fabric API 0.4 or newer*, and cannot tell a user why their game
//! crashed when two renderer patches fought.
//!
//! # Provenance is a first-class field
//!
//! Every definition records where it came from ([`Provenance`]). A mod the user
//! dropped in themselves and a mod we fetched from Modrinth are different
//! things, and the difference has to survive into the interface — a user should
//! never have to guess whether the launcher chose something on their behalf.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::network::Destination;
use crate::profiles::Loader;

/// A stable identifier for a mod.
///
/// Lowercase, hyphenated, and validated: it becomes a map key, appears in
/// dependency edges, and is written to a profile file that a user can edit.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize)]
pub struct ModId(String);

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ModIdError {
    #[error("a mod id must not be empty")]
    Empty,
    #[error("a mod id is at most {MAX_MOD_ID} characters; this one is {0}")]
    TooLong(usize),
    #[error("a mod id may only contain lowercase letters, digits, '-' and '_'; found {0:?}")]
    InvalidCharacter(char),
}

const MAX_MOD_ID: usize = 96;

impl ModId {
    pub fn new(raw: &str) -> Result<Self, ModIdError> {
        if raw.is_empty() {
            return Err(ModIdError::Empty);
        }
        if raw.len() > MAX_MOD_ID {
            return Err(ModIdError::TooLong(raw.len()));
        }
        for c in raw.chars() {
            if !(c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_') {
                return Err(ModIdError::InvalidCharacter(c));
            }
        }
        Ok(Self(raw.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ModId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

// A mod id read from a profile file is untrusted exactly like one typed in.
impl<'de> Deserialize<'de> for ModId {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let raw = String::deserialize(d)?;
        ModId::new(&raw).map_err(serde::de::Error::custom)
    }
}

// ---------------------------------------------------------------------------
// Versions
// ---------------------------------------------------------------------------

/// Which Minecraft versions a mod works with.
///
/// Deliberately not a semver range solver. 1.8.9 mods declare exact versions,
/// and inventing a range language we then have to match against other people's
/// conventions would create failures that are harder to explain than the ones it
/// prevents.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum MinecraftCompatibility {
    /// Works with exactly these versions.
    Exactly { versions: BTreeSet<String> },
    /// The author says it works with any version. Rare and usually optimistic,
    /// but it is a claim some mods make and pretending otherwise would be worse.
    Any,
}

impl MinecraftCompatibility {
    pub fn only(version: &str) -> Self {
        Self::Exactly {
            versions: [version.to_owned()].into_iter().collect(),
        }
    }

    pub fn accepts(&self, version: &str) -> bool {
        match self {
            MinecraftCompatibility::Any => true,
            MinecraftCompatibility::Exactly { versions } => versions.contains(version),
        }
    }

    pub fn describe(&self) -> String {
        match self {
            MinecraftCompatibility::Any => "any version".to_owned(),
            MinecraftCompatibility::Exactly { versions } => {
                versions.iter().cloned().collect::<Vec<_>>().join(", ")
            }
        }
    }
}

/// A mod's own version.
///
/// An opaque string compared by equality and ordered lexically. Mod versions in
/// this ecosystem are not reliably semver, and a comparison that is *sometimes*
/// meaningful is worse than one that is honestly just a label.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct ModVersion(pub String);

impl ModVersion {
    pub fn new(raw: impl Into<String>) -> Self {
        Self(raw.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ModVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

// ---------------------------------------------------------------------------
// Provenance and distribution
// ---------------------------------------------------------------------------

/// Where a definition's facts came from.
///
/// Shown to the user, always. The difference between "we know this because the
/// project documents it" and "we read it out of a jar you gave us" is exactly
/// the sort of thing a launcher should not blur.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum Provenance {
    /// From this launcher's built-in catalogue, which records what
    /// `docs/research/mod-policies.md` established.
    BuiltInCatalogue { reviewed: String },
    /// Read from the jar's own metadata, which the author wrote.
    JarMetadata { file_name: String },
    /// The user placed this file and told us nothing about it.
    UserSupplied { file_name: String },
    /// A mod index the user explicitly queried.
    Index { name: String, project_url: String },
}

impl Provenance {
    /// One line naming the source, for the interface.
    pub fn describe(&self) -> String {
        match self {
            Provenance::BuiltInCatalogue { reviewed } => {
                format!("Fathom's built-in catalogue (reviewed {reviewed})")
            }
            Provenance::JarMetadata { file_name } => {
                format!("read from {file_name}'s own metadata")
            }
            Provenance::UserSupplied { file_name } => format!("you added {file_name}"),
            Provenance::Index { name, project_url } => format!("{name} ({project_url})"),
        }
    }

    /// Whether the user put this here themselves.
    pub fn is_user_supplied(&self) -> bool {
        matches!(
            self,
            Provenance::UserSupplied { .. } | Provenance::JarMetadata { .. }
        )
    }
}

/// Where the bytes can be fetched from, if anywhere.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum DownloadSource {
    /// The launcher will not fetch this. The user has to supply the file.
    ///
    /// Carries the reason, so the interface can say *why* rather than leaving a
    /// button mysteriously absent.
    UserMustSupply {
        reason: String,
        where_to_get: String,
    },
    /// An allowlisted host, with the exact path.
    Allowlisted {
        destination: Destination,
        path: String,
    },
    /// A Modrinth project, resolved to a concrete file when it is fetched.
    ///
    /// # Why a project id and not a URL
    ///
    /// Because the URL is not knowable in advance and should not be guessed.
    /// Which file to fetch depends on the profile's Minecraft version and
    /// loader, and the answer changes when the project publishes a new build.
    /// Recording the project instead means the catalogue entry stays correct
    /// across releases, and the exact file -- with the hash the launcher checks
    /// it against -- comes from the API at the moment of the download.
    ///
    /// This is still not a licence to fetch: [`RedistributionPolicy`] decides
    /// that, and a project whose terms forbid redistribution does not become
    /// fetchable by being on Modrinth.
    Modrinth { project: String },
}

impl DownloadSource {
    pub fn is_fetchable(&self) -> bool {
        matches!(
            self,
            DownloadSource::Allowlisted { .. } | DownloadSource::Modrinth { .. }
        )
    }

    /// Whether fetching this resolves a hash of its own at download time.
    ///
    /// True only for sources that hand back a publisher-stated hash with the
    /// file. It is not "we trust this host"; it is "there is something to check
    /// the bytes against", which is a different and much smaller claim.
    pub fn carries_its_own_hash(&self) -> bool {
        matches!(self, DownloadSource::Modrinth { .. })
    }

    /// The Modrinth project id, when there is one.
    pub fn modrinth_project(&self) -> Option<&str> {
        match self {
            DownloadSource::Modrinth { project } => Some(project),
            _ => None,
        }
    }
}

/// SHA-1 of the expected file.
///
/// Required for anything the launcher fetches. A download with no hash to check
/// against is a download we will not do.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArtifactHash {
    pub sha1: String,
    pub size: Option<u64>,
}

impl ArtifactHash {
    pub fn new(sha1: impl Into<String>, size: Option<u64>) -> Option<Self> {
        let sha1: String = sha1.into();
        if sha1.len() != 40 || !sha1.chars().all(|c| c.is_ascii_hexdigit()) {
            return None;
        }
        Some(Self {
            sha1: sha1.to_ascii_lowercase(),
            size,
        })
    }
}

// ---------------------------------------------------------------------------
// Licensing and policy
// ---------------------------------------------------------------------------

/// A mod's licence, as `mod-policies.md` §2 recorded it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum License {
    /// An SPDX identifier we recognised.
    Spdx { id: String },
    /// Proprietary, all rights reserved.
    Proprietary,
    /// Published with no licence at all, which means all rights reserved by
    /// default — not "public domain", which is the usual misreading.
    None,
    /// A licence plus terms the project added on top. Needs reading before we
    /// act on it.
    WithAdditionalTerms { base: String, note: String },
    /// We have not established it.
    Unknown,
}

impl License {
    pub fn describe(&self) -> String {
        match self {
            License::Spdx { id } => id.clone(),
            License::Proprietary => "proprietary, all rights reserved".to_owned(),
            License::None => {
                "no licence published, so all rights are reserved by default".to_owned()
            }
            License::WithAdditionalTerms { base, note } => format!("{base} plus {note}"),
            License::Unknown => "unknown".to_owned(),
        }
    }
}

/// What the launcher is permitted to do with a mod's distribution.
///
/// From `mod-policies.md` §7. This is the field that stops the launcher doing
/// something a licence forbids, and it is deliberately conservative: `Unclear`
/// behaves exactly like `Forbidden`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RedistributionPolicy {
    /// May be fetched from the author's official channel on the user's explicit
    /// request. Never bundled.
    FetchOnRequest,
    /// The launcher must never fetch it. The user supplies the file.
    UserSuppliedOnly,
    /// We have not established the terms. Treated exactly like
    /// `UserSuppliedOnly`, because acting on an unread licence is the thing the
    /// policy exists to prevent.
    Unclear,
}

impl RedistributionPolicy {
    /// Whether the launcher may fetch this mod at all.
    ///
    /// The single question every download path asks. `Unclear` answers no, which
    /// is the whole reason it is a distinct variant rather than an `Option`.
    pub fn may_fetch(self) -> bool {
        matches!(self, RedistributionPolicy::FetchOnRequest)
    }

    /// Nothing is ever bundled into the installer. Present as a method so the
    /// rule is stated in code rather than only in prose.
    pub fn may_bundle(self) -> bool {
        false
    }

    pub fn describe(self) -> &'static str {
        match self {
            RedistributionPolicy::FetchOnRequest => {
                "may be downloaded from the author's official host, only when you ask for it"
            }
            RedistributionPolicy::UserSuppliedOnly => {
                "must be downloaded by you from the author; this launcher will not fetch it"
            }
            RedistributionPolicy::Unclear => {
                "licensing is unclear, so this launcher will not fetch it"
            }
        }
    }
}

/// What a mod does that touches the user's privacy.
///
/// `PRIVACY.md` promises the launcher itself sends nothing. A mod is separate
/// software running inside the game, and the honest thing is to say what is
/// known about it rather than let the launcher's promise be read as covering it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PrivacyClassification {
    /// No network activity or data collection that we know of.
    Local,
    /// Contacts the network as part of its function, e.g. a mod that queries a
    /// server list.
    NetworkFeature,
    /// Known to report usage or telemetry.
    Telemetry,
    /// Not established. Shown as unknown rather than assumed benign.
    Unknown,
}

impl PrivacyClassification {
    pub fn describe(self) -> &'static str {
        match self {
            PrivacyClassification::Local => "no network activity known",
            PrivacyClassification::NetworkFeature => "uses the network as part of what it does",
            PrivacyClassification::Telemetry => "known to report usage data",
            PrivacyClassification::Unknown => "unknown; we have not established what it does",
        }
    }

    pub fn is_concerning(self) -> bool {
        matches!(
            self,
            PrivacyClassification::Telemetry | PrivacyClassification::Unknown
        )
    }
}

// ---------------------------------------------------------------------------
// Relationships
// ---------------------------------------------------------------------------

/// Something this mod needs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Dependency {
    pub id: ModId,
    /// A minimum version, if the author states one. Compared as a label, not as
    /// semver — see [`ModVersion`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minimum_version: Option<ModVersion>,
    /// Whether the mod simply works better with it, rather than needing it.
    #[serde(default)]
    pub optional: bool,
    /// Why, in the author's or our words. Shown to the user.
    #[serde(default)]
    pub reason: String,
}

/// How two mods interfere.
///
/// Four kinds, because "conflict" alone cannot tell a user whether their game
/// will fail to start or merely render text twice.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ConflictKind {
    /// They cannot both be loaded. One must go.
    Incompatible,
    /// **The declaring mod** does everything the named one does. Directional:
    /// `A` declaring `Supersedes` of `B` means A replaces B, never the reverse.
    /// Only the superseding side declares it — an edge in both directions would
    /// render as a contradiction, which is exactly what a live run caught.
    Supersedes,
    /// Both patch the same subsystem. Usually survivable, but the overlapping
    /// features should be turned off on one side. This is the `OptiFine` case
    /// from `mod-policies.md` §8.
    OverlappingOptimization,
    /// Known to break in combination, for a reason specific to these two.
    KnownBad,
}

impl ConflictKind {
    /// Whether this stops a launch outright.
    pub fn is_blocking(self) -> bool {
        matches!(self, ConflictKind::Incompatible | ConflictKind::KnownBad)
    }
}

/// A declared interference with another mod.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Conflict {
    pub id: ModId,
    pub kind: ConflictKind,
    /// What actually goes wrong. Required in practice: "conflicts with
    /// `OptiFine`" without saying how is not something a user can act on.
    pub detail: String,
}

// ---------------------------------------------------------------------------
// The definition
// ---------------------------------------------------------------------------

/// Everything known about one mod.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModDefinition {
    pub id: ModId,
    pub name: String,
    pub version: ModVersion,
    pub minecraft: MinecraftCompatibility,
    /// Which loaders can load it. A mod for Forge is not a mod for Fabric, and
    /// putting one in the other's directory does nothing useful.
    pub loaders: BTreeSet<Loader>,

    pub provenance: Provenance,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub download: Option<DownloadSource>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hash: Option<ArtifactHash>,

    pub license: License,
    pub redistribution: RedistributionPolicy,
    pub privacy: PrivacyClassification,

    #[serde(default)]
    pub dependencies: Vec<Dependency>,
    #[serde(default)]
    pub conflicts: Vec<Conflict>,

    /// A sentence about what it does.
    #[serde(default)]
    pub summary: String,
    /// Where a human can read about it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub homepage: Option<String>,
}

impl ModDefinition {
    /// Whether this definition could be launched under a given setup.
    pub fn supports(&self, minecraft_version: &str, loader: Loader) -> bool {
        self.minecraft.accepts(minecraft_version) && self.loaders.contains(&loader)
    }

    /// Whether the launcher may download this itself.
    ///
    /// Three conditions, all required: the policy permits it, there is an
    /// allowlisted source, and there is a hash to check the bytes against.
    /// `mod-policies.md` §7 lists all three, and the code requires all three.
    pub fn is_launcher_fetchable(&self) -> bool {
        self.redistribution.may_fetch()
            && self.download.as_ref().is_some_and(|source| {
                // The rule has always been "no download without a hash to
                // check it against". What changed is that a hash can now
                // arrive with the file instead of being written down here.
                //
                // For a source that names one exact file, the hash is
                // knowable when the catalogue is written and has to be in
                // it. For one that names a project, the correct file
                // depends on the profile and changes when the project
                // publishes; its hash comes back from the API alongside the
                // file, and `ModStore::adopt_bytes` refuses bytes that do
                // not match. Requiring a catalogue hash there would mean
                // pinning every profile to one build for ever.
                source.is_fetchable() && (source.carries_its_own_hash() || self.hash.is_some())
            })
    }

    /// Why the launcher will not fetch it, if it will not.
    pub fn why_not_fetchable(&self) -> Option<String> {
        if self.is_launcher_fetchable() {
            return None;
        }
        if !self.redistribution.may_fetch() {
            return Some(format!(
                "{}: {}. Its licence is {}.",
                self.name,
                self.redistribution.describe(),
                self.license.describe()
            ));
        }
        match &self.download {
            None => Some(format!("{}: no download source is recorded.", self.name)),
            Some(DownloadSource::UserMustSupply { reason, .. }) => {
                Some(format!("{}: {reason}", self.name))
            }
            Some(DownloadSource::Allowlisted { .. }) => Some(format!(
                "{}: no published hash, and unverified code will not be downloaded.",
                self.name
            )),
            // Unreachable while the policy permits it, because a Modrinth
            // source supplies its own hash and is therefore always fetchable.
            // Reachable the moment somebody records a project for something the
            // policy forbids, and answering with the policy is the right answer
            // then too.
            Some(DownloadSource::Modrinth { .. }) => Some(format!(
                "{}: {}.",
                self.name,
                self.redistribution.describe()
            )),
        }
    }

    /// A definition for a jar the user supplied, about which nothing else is
    /// known.
    ///
    /// Everything unknown is recorded as unknown rather than guessed. That is
    /// what makes it safe to show the user next to a catalogued mod without
    /// implying the same confidence.
    pub fn user_supplied(id: ModId, file_name: &str, loader: Loader) -> Self {
        Self {
            name: file_name.trim_end_matches(".jar").replace(['_', '-'], " "),
            id,
            version: ModVersion::new("unknown"),
            // The user chose to put it in this profile; second-guessing which
            // Minecraft versions it claims would mean executing or parsing its
            // contents, which §"Mod safety" rules out.
            minecraft: MinecraftCompatibility::Any,
            loaders: [loader].into_iter().collect(),
            provenance: Provenance::UserSupplied {
                file_name: file_name.to_owned(),
            },
            download: None,
            hash: None,
            license: License::Unknown,
            redistribution: RedistributionPolicy::Unclear,
            privacy: PrivacyClassification::Unknown,
            dependencies: Vec::new(),
            conflicts: Vec::new(),
            summary: String::new(),
            homepage: None,
        }
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

    #[test]
    fn mod_ids_reject_anything_that_could_become_a_path() {
        for raw in ["ok", "ok-1", "ok_1", "a1"] {
            assert!(ModId::new(raw).is_ok(), "{raw} should be valid");
        }
        for raw in [
            "", "../x", "a/b", "a\\b", "a:b", "A", "a b", "a.b", "\u{6e2c}",
        ] {
            assert!(ModId::new(raw).is_err(), "{raw:?} must be refused");
        }
    }

    #[test]
    fn a_mod_id_read_from_a_file_is_validated() {
        assert!(serde_json::from_str::<ModId>(r#""fine""#).is_ok());
        assert!(serde_json::from_str::<ModId>(r#""../escape""#).is_err());
        assert!(serde_json::from_str::<ModId>(r#""UPPER""#).is_err());
    }

    #[test]
    fn compatibility_is_exact_unless_the_author_claims_otherwise() {
        let only = MinecraftCompatibility::only("1.8.9");
        assert!(only.accepts("1.8.9"));
        assert!(!only.accepts("1.12.2"));
        assert!(!only.accepts("1.8"));

        assert!(MinecraftCompatibility::Any.accepts("anything"));
    }

    /// The rule the policy engine turns on: unclear is refused, not permitted.
    #[test]
    fn an_unclear_policy_behaves_exactly_like_a_forbidden_one() {
        assert!(!RedistributionPolicy::Unclear.may_fetch());
        assert!(!RedistributionPolicy::UserSuppliedOnly.may_fetch());
        assert!(RedistributionPolicy::FetchOnRequest.may_fetch());
    }

    /// Nothing is ever bundled. Stated in code so it cannot drift from the
    /// prose in `mod-policies.md` §7.
    #[test]
    fn nothing_may_ever_be_bundled_whatever_its_licence() {
        for policy in [
            RedistributionPolicy::FetchOnRequest,
            RedistributionPolicy::UserSuppliedOnly,
            RedistributionPolicy::Unclear,
        ] {
            assert!(!policy.may_bundle(), "{policy:?} must never be bundled");
        }
    }

    #[test]
    fn a_hash_must_be_forty_hex_characters() {
        assert!(ArtifactHash::new("a".repeat(40), None).is_some());
        assert!(ArtifactHash::new("A".repeat(40), None).is_some());
        for bad in ["", "abc", &"z".repeat(40), &"a".repeat(39), &"a".repeat(41)] {
            assert!(ArtifactHash::new(bad, None).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn a_hash_is_normalised_to_lowercase() {
        let hash = ArtifactHash::new("ABCDEF0123456789".repeat(2) + "abcdefgh", None);
        assert!(hash.is_none(), "'g' and 'h' are not hex");

        let good = ArtifactHash::new("A".repeat(40), None).unwrap();
        assert_eq!(good.sha1, "a".repeat(40));
    }

    fn definition() -> ModDefinition {
        ModDefinition {
            id: ModId::new("example").unwrap(),
            name: "Example".into(),
            version: ModVersion::new("1.0"),
            minecraft: MinecraftCompatibility::only("1.8.9"),
            loaders: [Loader::LegacyFabric].into_iter().collect(),
            provenance: Provenance::BuiltInCatalogue {
                reviewed: "2026-01".into(),
            },
            download: Some(DownloadSource::Allowlisted {
                destination: Destination::ModrinthCdn,
                path: "/data/x/versions/y/example.jar".into(),
            }),
            hash: ArtifactHash::new("a".repeat(40), Some(100)),
            license: License::Spdx {
                id: "Apache-2.0".into(),
            },
            redistribution: RedistributionPolicy::FetchOnRequest,
            privacy: PrivacyClassification::Local,
            dependencies: Vec::new(),
            conflicts: Vec::new(),
            summary: String::new(),
            homepage: None,
        }
    }

    /// All three conditions are required, and removing any one of them stops
    /// the download.
    #[test]
    fn fetching_needs_policy_source_and_hash_together() {
        assert!(definition().is_launcher_fetchable());

        let mut no_policy = definition();
        no_policy.redistribution = RedistributionPolicy::Unclear;
        assert!(!no_policy.is_launcher_fetchable());
        assert!(no_policy.why_not_fetchable().is_some());

        let mut no_hash = definition();
        no_hash.hash = None;
        assert!(!no_hash.is_launcher_fetchable());
        assert!(no_hash.why_not_fetchable().unwrap().contains("hash"));

        let mut no_source = definition();
        no_source.download = None;
        assert!(!no_source.is_launcher_fetchable());

        let mut user_supplied = definition();
        user_supplied.download = Some(DownloadSource::UserMustSupply {
            reason: "the author forbids it".into(),
            where_to_get: "https://example.invalid".into(),
        });
        assert!(!user_supplied.is_launcher_fetchable());
    }

    #[test]
    fn support_needs_both_the_version_and_the_loader() {
        let def = definition();
        assert!(def.supports("1.8.9", Loader::LegacyFabric));
        assert!(!def.supports("1.12.2", Loader::LegacyFabric));
        assert!(!def.supports("1.8.9", Loader::Forge));
        assert!(!def.supports("1.8.9", Loader::Vanilla));
    }

    /// A jar we know nothing about must not acquire the appearance of a
    /// reviewed one.
    #[test]
    fn a_user_supplied_definition_claims_nothing_it_does_not_know() {
        let def = ModDefinition::user_supplied(
            ModId::new("mystery").unwrap(),
            "mystery-mod.jar",
            Loader::LegacyFabric,
        );

        assert_eq!(def.license, License::Unknown);
        assert_eq!(def.redistribution, RedistributionPolicy::Unclear);
        assert_eq!(def.privacy, PrivacyClassification::Unknown);
        assert!(def.hash.is_none());
        assert!(def.download.is_none());
        assert!(!def.is_launcher_fetchable());
        assert!(def.provenance.is_user_supplied());
        assert!(def.provenance.describe().contains("mystery-mod.jar"));
    }

    #[test]
    fn a_conflict_says_whether_it_blocks_a_launch() {
        assert!(ConflictKind::Incompatible.is_blocking());
        assert!(ConflictKind::KnownBad.is_blocking());
        assert!(!ConflictKind::Supersedes.is_blocking());
        assert!(!ConflictKind::OverlappingOptimization.is_blocking());
    }

    #[test]
    fn every_privacy_classification_says_something_specific() {
        for c in [
            PrivacyClassification::Local,
            PrivacyClassification::NetworkFeature,
            PrivacyClassification::Telemetry,
            PrivacyClassification::Unknown,
        ] {
            assert!(!c.describe().is_empty());
        }
        assert!(PrivacyClassification::Unknown.is_concerning());
        assert!(PrivacyClassification::Telemetry.is_concerning());
        assert!(!PrivacyClassification::Local.is_concerning());
    }

    #[test]
    fn a_definition_round_trips_through_serialisation() {
        let def = definition();
        let json = serde_json::to_string(&def).unwrap();
        let back: ModDefinition = serde_json::from_str(&json).unwrap();
        assert_eq!(def, back);
    }
}
