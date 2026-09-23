//! The unified mod model: what Fathom knows about a mod, whoever indexed it.
//!
//! # Why this type exists at all
//!
//! Modrinth and `CurseForge` describe the same objects with different words,
//! different ids, different shapes and different ideas about what a "version"
//! is. If those shapes reached the rest of the launcher, every screen, every
//! install path and every test would have to know both — and adding a third
//! index later would mean touching all of them.
//!
//! So the providers are the only code in the launcher allowed to see a
//! provider's JSON. Everything above them sees [`ModProject`], [`ModFile`] and
//! the enums here, and a new provider is a new file rather than a new case in
//! ten files.
//!
//! # Everything here came off the network
//!
//! Every string in this module was written by somebody else and fetched over
//! HTTP. Three rules follow, and they are enforced when the provider builds
//! these values rather than trusted afterwards:
//!
//! 1. **Lengths are capped.** A summary is a sentence, not a megabyte; the
//!    caps are in [`limits`] and the truncation is visible rather than silent.
//! 2. **URLs are not strings we act on.** An [`ImageRef`] and a
//!    [`FileDownload`] keep a URL for display, but reaching either goes through
//!    [`crate::network::descriptor_from_url`], which only accepts an
//!    allowlisted host.
//! 3. **File names are checked before they can become paths.** See
//!    [`safe_file_name`].

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::mods::definition::{ArtifactHash, License, ModVersion};
use crate::profiles::Loader;

/// Ceilings on anything that arrived from an index.
///
/// Not arbitrary: each is the point past which the value is certainly not what
/// it claims to be, and keeping a hundred megabytes of "summary" in memory to
/// render forty characters of it is a denial of service somebody else controls.
pub mod limits {
    /// A project name.
    pub const NAME: usize = 120;
    /// The one-line summary a card shows.
    pub const SUMMARY: usize = 400;
    /// The long description a detail page shows.
    pub const DESCRIPTION: usize = 64 * 1024;
    /// Any single URL.
    pub const URL: usize = 512;
    /// A file name.
    pub const FILE_NAME: usize = 200;
    /// How many categories, authors or gallery images are kept per project.
    pub const LIST: usize = 32;
    /// How many versions of one project are kept.
    pub const VERSIONS: usize = 200;
    /// How many dependencies one file may declare.
    pub const DEPENDENCIES: usize = 64;
    /// How many Minecraft versions or loaders are kept per project.
    pub const FACETS: usize = 512;
}

/// Shorten an untrusted string to a sane length, on a character boundary.
pub fn clip(value: &str, max: usize) -> String {
    if value.len() <= max {
        return value.to_owned();
    }
    let mut end = max;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value.get(..end).unwrap_or_default().to_owned()
}

/// Whether a file name from an index may be written to disk.
///
/// The mod store is content-addressed, so a hostile name cannot choose where
/// bytes land — but the name is also recorded in the profile and later becomes
/// a file inside the profile's `mods/` directory, so it is checked here, at the
/// point it enters the launcher, rather than at the point it becomes a path.
///
/// Deliberately strict: a mod jar is `letters, digits, dot, dash, underscore,
/// plus, space` and ends in `.jar`. Anything else — a separator, a drive
/// letter, a Windows device name, a control character, a leading dot — is not a
/// mod file name, whatever the index says.
pub fn safe_file_name(name: &str) -> bool {
    // Windows device names are refused whatever the extension.
    const DEVICES: [&str; 22] = [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
        "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];

    if name.is_empty() || name.len() > limits::FILE_NAME {
        return false;
    }
    if !name.to_ascii_lowercase().ends_with(".jar") {
        return false;
    }
    if name.starts_with('.') || name.starts_with(' ') || name.ends_with(' ') {
        return false;
    }
    if name.contains("..") {
        return false;
    }
    if !name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+' | ' ' | '(' | ')'))
    {
        return false;
    }
    let stem = name
        .split('.')
        .next()
        .unwrap_or_default()
        .to_ascii_uppercase();
    !DEVICES.contains(&stem.as_str())
}

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

/// Which index a project came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Provider {
    Modrinth,
    CurseForge,
}

impl Provider {
    pub const ALL: [Provider; 2] = [Provider::Modrinth, Provider::CurseForge];

    /// The stable id recorded in a profile. Never translated, never renamed.
    pub fn id(self) -> &'static str {
        match self {
            Provider::Modrinth => "modrinth",
            Provider::CurseForge => "curseforge",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Provider::Modrinth => "Modrinth",
            Provider::CurseForge => "CurseForge",
        }
    }

    pub fn parse(raw: &str) -> Option<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "modrinth" => Some(Provider::Modrinth),
            "curseforge" | "curse" => Some(Provider::CurseForge),
            _ => None,
        }
    }
}

impl std::fmt::Display for Provider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.label())
    }
}

/// One project, at one provider.
///
/// The pair is the identity: a `CurseForge` numeric id and a Modrinth base62 id
/// can collide as strings and mean entirely different mods.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectRef {
    pub provider: Provider,
    /// The provider's own project id, as a string. Opaque: never parsed, never
    /// constructed by us, only ever echoed back to the provider it came from.
    pub id: String,
}

impl ProjectRef {
    pub fn new(provider: Provider, id: impl Into<String>) -> Self {
        Self {
            provider,
            id: id.into(),
        }
    }

    /// `modrinth:AABBCC`, for logs and for a profile record.
    pub fn qualified(&self) -> String {
        format!("{}:{}", self.provider.id(), self.id)
    }
}

impl std::fmt::Display for ProjectRef {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.qualified())
    }
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/// An icon or a screenshot, as a URL nobody has fetched yet.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageRef {
    /// Absolute HTTPS URL, capped in length. Reaching it still goes through the
    /// allowlist; this type is not permission to fetch anything.
    pub url: String,
    /// The author's caption, where the provider has one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
}

impl ImageRef {
    /// Build from an untrusted URL, or `None` when it is not a URL we would
    /// ever fetch.
    pub fn new(url: &str, title: Option<String>) -> Option<Self> {
        let url = url.trim();
        if url.is_empty() || url.len() > limits::URL || !url.starts_with("https://") {
            return None;
        }
        // The host is checked here as well as at fetch time, so a project whose
        // images live somewhere we will not go does not show a broken image: it
        // shows no image, and the UI can say so.
        crate::network::descriptor_from_url(
            url,
            crate::network::Purpose::ResolveModMetadata,
            crate::network::Feature::ModBrowse,
        )
        .ok()?;
        Some(Self {
            url: url.to_owned(),
            title: title.map(|t| clip(&t, limits::SUMMARY)),
        })
    }
}

/// A slug, reduced to the characters a slug can legitimately contain.
///
/// Slugs become URL path segments, so anything else is dropped rather than
/// escaped.
pub fn sanitise_slug(slug: &str) -> String {
    slug.chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .take(limits::NAME)
        .collect()
}

/// An outbound link, kept only when it is an HTTPS URL of sane length.
///
/// These are opened in a browser rather than fetched, so the allowlist does not
/// apply — but a `javascript:` or `file:` link in front of a user does not
/// become one of ours.
pub fn safe_link(url: Option<&str>) -> Option<String> {
    let url = url?.trim();
    (url.starts_with("https://") && url.len() <= limits::URL).then(|| url.to_owned())
}

// ---------------------------------------------------------------------------
// Distribution
// ---------------------------------------------------------------------------

/// Whether this launcher may fetch a project's files on the user's behalf.
///
/// `CurseForge` lets an author switch third-party distribution off, and when
/// they do the API returns no download URL at all. `docs/research/mod-policies.md`
/// §7 is explicit that the flag must be honoured and never worked around, so it
/// is modelled as a state of the project rather than as a download that happens
/// to fail.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "kebab-case")]
pub enum Distribution {
    /// The provider serves the file to third-party applications.
    Allowed,
    /// The author opted out. The project page is where a human can get it.
    ForbiddenByAuthor { where_to_get: String },
}

impl Distribution {
    pub fn is_allowed(&self) -> bool {
        matches!(self, Distribution::Allowed)
    }
}

// ---------------------------------------------------------------------------
// Projects
// ---------------------------------------------------------------------------

/// A mod, as the browser understands it.
///
/// Filled in as far as the request allowed: a search result has no long
/// description and no gallery, and says so by leaving them empty rather than by
/// being a different type with the same fields spelled differently.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModProject {
    pub reference: ProjectRef,
    /// The provider's URL-safe name. Used for identity matching across
    /// providers, and for building the project page link.
    pub slug: String,
    pub name: String,
    pub summary: String,
    /// The long description, when the details were fetched. Markdown or HTML
    /// depending on the provider; the UI decides what to do with it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<ImageRef>,
    #[serde(default)]
    pub gallery: Vec<ImageRef>,
    pub downloads: u64,
    #[serde(default)]
    pub categories: Vec<String>,
    /// The page a human should read. Built by the provider from its own slug or
    /// taken from a field the provider controls — never a link the index can
    /// point anywhere.
    pub project_url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub issues_url: Option<String>,
    #[serde(default = "unknown_license")]
    pub license: License,
    /// Minecraft versions the project claims, across all its files.
    #[serde(default)]
    pub minecraft_versions: BTreeSet<String>,
    /// Loader names the project claims, in Fathom's spelling where it could be
    /// mapped and the provider's otherwise.
    #[serde(default)]
    pub loaders: BTreeSet<String>,
    /// When the project last changed, as the provider reports it. An opaque
    /// timestamp string: it is displayed and compared for equality, never
    /// parsed into a calendar.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated: Option<String>,
    #[serde(default = "distribution_allowed")]
    pub distribution: Distribution,
    /// Whether [`Self::description`] and [`Self::gallery`] were actually asked
    /// for. Distinguishes "no screenshots" from "we did not look".
    #[serde(default)]
    pub detailed: bool,
}

fn distribution_allowed() -> Distribution {
    Distribution::Allowed
}

fn unknown_license() -> License {
    License::Unknown
}

impl ModProject {
    /// Whether this project claims a Minecraft version and loader at project
    /// level. A coarse filter: the authoritative answer is whether any of its
    /// files is compatible, which needs the file list.
    pub fn claims(&self, target: &Target) -> bool {
        let version = self.minecraft_versions.is_empty()
            || self.minecraft_versions.contains(&target.minecraft);
        let loader = self.loaders.is_empty()
            || target
                .loader_names()
                .iter()
                .any(|name| self.loaders.contains(*name));
        version && loader
    }
}

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

/// How finished a release claims to be.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ReleaseChannel {
    Release,
    Beta,
    Alpha,
}

impl ReleaseChannel {
    pub fn label(self) -> &'static str {
        match self {
            ReleaseChannel::Release => "Release",
            ReleaseChannel::Beta => "Beta",
            ReleaseChannel::Alpha => "Alpha",
        }
    }
}

/// How one file relates to another project.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum DependencyKind {
    /// Will not work without it. Resolved and installed with the mod.
    Required,
    /// Works better with it. Offered, never installed on its own account.
    Optional,
    /// Must not be installed alongside. Reported before anything is written.
    Incompatible,
    /// Already inside this jar. Nothing to install, and installing the separate
    /// copy is usually how a duplicate-class crash starts.
    Embedded,
}

/// One declared relationship.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileDependency {
    pub kind: DependencyKind,
    /// The project it names, when the provider gave one. `None` happens: both
    /// APIs allow a dependency on a specific file without naming its project.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project: Option<ProjectRef>,
    /// A specific version of that project, when the author pinned one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version_id: Option<String>,
}

/// Where a file's bytes come from.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum FileDownload {
    /// An absolute URL the provider published. Still validated against the
    /// allowlist before anything is fetched.
    Url { url: String },
    /// The provider knows the file but will not serve it to us, because its
    /// author switched third-party distribution off.
    Withheld { where_to_get: String },
}

/// One downloadable build of a project.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModFile {
    pub project: ProjectRef,
    /// The provider's id for this build.
    pub version_id: String,
    /// The author's version string, e.g. `1.13.5`.
    pub number: String,
    /// The display name of the build, which is often prettier than the number.
    pub name: String,
    pub channel: ReleaseChannel,
    #[serde(default)]
    pub loaders: BTreeSet<String>,
    #[serde(default)]
    pub game_versions: BTreeSet<String>,
    pub file_name: String,
    pub download: FileDownload,
    /// SHA-1 and size, as published. A file with no usable hash never becomes a
    /// `ModFile`: see the provider modules.
    pub hash: ArtifactHash,
    #[serde(default)]
    pub dependencies: Vec<FileDependency>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published: Option<String>,
}

impl ModFile {
    /// Whether this build fits the target, asked of the file itself.
    ///
    /// Called by the provider on the way in *and* again by the installer on the
    /// way out. Both, deliberately: the first is a filter, the second is a
    /// check, and a filter that ran against a response somebody else wrote is
    /// not a check.
    pub fn supports(&self, target: &Target) -> bool {
        if !self.game_versions.is_empty() && !self.game_versions.contains(&target.minecraft) {
            return false;
        }
        if self.loaders.is_empty() {
            // A file that names no loader is a file we cannot place on either
            // side of the question. Vanilla loads no mods, so the only honest
            // answer for a modded target is "unknown", and unknown is refused
            // at install time rather than offered.
            return false;
        }
        target
            .loader_names()
            .iter()
            .any(|name| self.loaders.contains(*name))
    }

    pub fn version(&self) -> ModVersion {
        ModVersion::new(&self.number)
    }

    /// Whether the launcher may actually fetch this.
    pub fn is_downloadable(&self) -> bool {
        matches!(self.download, FileDownload::Url { .. })
    }
}

// ---------------------------------------------------------------------------
// The target
// ---------------------------------------------------------------------------

/// What a profile can actually load: one Minecraft version and one loader.
///
/// Every search, every version list and every install is asked in terms of one
/// of these. There is no "all versions" variant on purpose — the browser
/// defaults to the profile it is browsing for, and a browser that showed a
/// 1.21 mod to a 1.8.9 profile by default is the failure this type prevents.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Target {
    pub minecraft: String,
    pub loader: Loader,
}

impl Target {
    pub fn new(minecraft: impl Into<String>, loader: Loader) -> Self {
        Self {
            minecraft: minecraft.into(),
            loader,
        }
    }

    /// Build the target a profile browses under.
    pub fn of(profile: &crate::profiles::Profile) -> Self {
        Self::new(profile.version_id.clone(), profile.loader_kind())
    }

    /// Whether this target can load mods at all.
    pub fn loads_mods(&self) -> bool {
        self.loader.supports_mods()
    }

    /// Every spelling of this loader that a file might carry.
    ///
    /// The 1.8.9 trap in one function: Modrinth tags these `legacy-fabric`,
    /// `CurseForge` files carry `Fabric`, and a mod tagged plain `fabric` is
    /// for modern Fabric and will not load. Both names are accepted for Legacy
    /// Fabric because both indexes are right about their own catalogue, and
    /// neither `fabric` alone nor `quilt` is.
    pub fn loader_names(&self) -> &'static [&'static str] {
        match self.loader {
            Loader::LegacyFabric => &["legacy-fabric", "legacyfabric"],
            Loader::Forge => &["forge"],
            Loader::Vanilla => &[],
        }
    }
}

// ---------------------------------------------------------------------------
// Installed state
// ---------------------------------------------------------------------------

/// What the browser should show on a row, for the profile being browsed.
///
/// Derived from the profile's own configuration — the file it will actually
/// load — rather than from a history of what the browser did. A mod somebody
/// dropped into `mods/` is installed whether or not this browser put it there.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "kebab-case")]
pub enum InstallState {
    NotInstalled,
    Installed {
        /// The version string recorded at install, when there is one.
        version: Option<String>,
        file_name: String,
    },
    UpdateAvailable {
        installed: Option<String>,
        latest: String,
        /// The build to install, so accepting the update needs no second search.
        version_id: String,
        file_name: String,
    },
    /// A download or install is running for this project right now.
    Installing,
    Updating,
    /// Removal was asked for and has not finished.
    RemovalPending,
    /// The last operation failed, with the reason the user was shown.
    Failed {
        reason: String,
    },
}

impl InstallState {
    pub fn is_installed(&self) -> bool {
        matches!(
            self,
            InstallState::Installed { .. } | InstallState::UpdateAvailable { .. }
        )
    }

    pub fn is_busy(&self) -> bool {
        matches!(
            self,
            InstallState::Installing | InstallState::Updating | InstallState::RemovalPending
        )
    }

    pub fn label(&self) -> &'static str {
        match self {
            InstallState::NotInstalled => "Not installed",
            InstallState::Installed { .. } => "Installed",
            InstallState::UpdateAvailable { .. } => "Update available",
            InstallState::Installing => "Installing",
            InstallState::Updating => "Updating",
            InstallState::RemovalPending => "Removing",
            InstallState::Failed { .. } => "Failed",
        }
    }
}

/// A project plus everything that is true about it *for this profile*.
///
/// The catalog produces these; a provider never does, because a provider does
/// not know which profile is being browsed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogEntry {
    pub project: ModProject,
    pub installed: InstallState,
    /// The same mod, indexed by another provider as well. Set when the two
    /// listings were matched; see [`super::identity`].
    #[serde(default)]
    pub also_on: Vec<ProjectRef>,
    /// An installed mod this listing is probably another copy of, when the
    /// match was not certain enough to merge. Surfaced rather than acted on.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub possible_duplicate_of: Option<String>,
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn a_file_name_from_an_index_cannot_escape_the_mods_directory() {
        for hostile in [
            "../../evil.jar",
            "..\\evil.jar",
            "/etc/passwd.jar",
            "C:\\windows\\system32\\evil.jar",
            ".hidden.jar",
            "mod.jar\u{0}.txt",
            "CON.jar",
            "nul.jar",
            "mod.sh",
            "mod.jar.exe",
            "",
            " leading.jar",
            "trailing .jar ",
        ] {
            assert!(!safe_file_name(hostile), "{hostile:?} must be refused");
        }
        for fine in [
            "sodium-1.8.9.jar",
            "Legacy Fabric API (1.8.9).jar",
            "mod_name+1.0.jar",
            "MOD.JAR",
        ] {
            assert!(safe_file_name(fine), "{fine:?} must be accepted");
        }
    }

    #[test]
    fn a_name_longer_than_the_cap_is_refused_rather_than_truncated() {
        let long = format!("{}.jar", "a".repeat(limits::FILE_NAME));
        assert!(!safe_file_name(&long));
    }

    #[test]
    fn clipping_never_splits_a_character() {
        let text = "\u{e9}\u{e9}\u{e9}\u{e9}";
        let clipped = clip(text, 3);
        assert!(text.starts_with(&clipped));
        assert!(clipped.len() <= 3);
    }

    #[test]
    fn an_image_url_off_the_allowlist_is_not_an_image() {
        assert!(ImageRef::new("https://evil.example/icon.png", None).is_none());
        assert!(ImageRef::new("http://cdn.modrinth.com/icon.png", None).is_none());
        assert!(ImageRef::new("", None).is_none());
        assert!(ImageRef::new("https://cdn.modrinth.com/data/AA/icon.png", None).is_some());
        assert!(ImageRef::new("https://media.forgecdn.net/avatars/1/2.png", None).is_some());
    }

    #[test]
    fn a_file_must_name_a_loader_and_the_version_to_be_compatible() {
        let target = Target::new("1.8.9", Loader::LegacyFabric);
        let mut file = ModFile {
            project: ProjectRef::new(Provider::Modrinth, "p"),
            version_id: "v".into(),
            number: "1.0".into(),
            name: "1.0".into(),
            channel: ReleaseChannel::Release,
            loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
            game_versions: ["1.8.9".to_owned()].into_iter().collect(),
            file_name: "mod.jar".into(),
            download: FileDownload::Url {
                url: "https://cdn.modrinth.com/a.jar".into(),
            },
            hash: ArtifactHash::new("a".repeat(40), Some(10)).unwrap(),
            dependencies: Vec::new(),
            published: None,
        };
        assert!(file.supports(&target));

        // Modern Fabric is not Legacy Fabric, and this is the single most
        // common way a 1.8.9 browser offers something that cannot load.
        file.loaders = ["fabric".to_owned()].into_iter().collect();
        assert!(!file.supports(&target));

        file.loaders = ["legacy-fabric".to_owned()].into_iter().collect();
        file.game_versions = ["1.21".to_owned()].into_iter().collect();
        assert!(!file.supports(&target));

        // A file that names no loader at all is not assumed to fit.
        file.game_versions = ["1.8.9".to_owned()].into_iter().collect();
        file.loaders = BTreeSet::new();
        assert!(!file.supports(&target));
    }

    #[test]
    fn vanilla_matches_no_loader_name_so_nothing_is_compatible_with_it() {
        let target = Target::new("1.8.9", Loader::Vanilla);
        assert!(target.loader_names().is_empty());
        assert!(!target.loads_mods());
    }
}
