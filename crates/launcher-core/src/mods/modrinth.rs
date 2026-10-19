//! Modrinth, the one mod repository this launcher talks to.
//!
//! # Why this one, and only through the API
//!
//! `FATHOM_MODS_AND_LICENSING.md` is explicit on both halves. Modrinth's terms
//! grant third-party applications an API licence to query, download and display
//! the content it indexes; so Fathom uses the API, and only the API. Nothing
//! here parses a web page, and nothing here constructs a URL out of a string
//! somebody typed.
//!
//! `CurseForge` lives beside this one now, in
//! [`crate::mods::browse::curseforge`], and the reason it took longer is in its
//! own module note: each project carries a third-party-distribution flag that
//! can forbid automated delivery regardless of the software licence, so the
//! provider has to read that field and obey it. This module is unchanged by
//! that: it is the Modrinth wire client, and
//! [`crate::mods::browse`] is where the two are made to look the same.
//!
//! # The four rules every request here obeys
//!
//! 1. **The host is never a string we were given.** Every request is a
//!    [`RequestDescriptor`], which can only name a [`Destination`] from the
//!    compile-time allowlist. A download URL from the API goes through
//!    [`descriptor_from_url`], which refuses any host that is not on it — so a
//!    compromised index cannot point a "mod" at somebody else's server.
//! 2. **Nothing happens without a per-action grant.** `api.modrinth.com` is
//!    [`Consent::ExplicitPerAction`], so even with mod repositories switched on
//!    in Settings, each search and each install needs a fresh
//!    [`PerActionGrant`] naming what the user asked for. There is no way to
//!    express "sync my mods in the background" with this type.
//! 3. **Every download is checked against a published hash.** Modrinth
//!    publishes SHA-1 per file; the downloader verifies against it and discards
//!    a mismatch. A version whose file carries no usable hash is dropped from
//!    the results rather than offered without one.
//! 4. **Only compatible versions are shown.** Filtering happens in the query
//!    *and* again on the response, because a server is free to return whatever
//!    it likes and "it said it was compatible" is not a check.
//!
//! # What is recorded
//!
//! Project id, version id, file name, SHA-1, size, licence, loaders and game
//! versions — all of it from the API, all of it kept with the profile entry, so
//! a mod's provenance is answerable later without asking the network again.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use super::definition::{ArtifactHash, License, ModVersion};
use super::store::{ModStore, StoredMod};
use super::ModError;
use crate::network::{
    descriptor_from_url, Destination, Feature, HttpClient, NetworkError, PerActionGrant, Purpose,
    RequestDescriptor,
};
use crate::profiles::Loader;

/// The API version this client speaks. Modrinth versions its API in the path.
const API: &str = "/v2";

/// The largest page we will ask for.
///
/// Modrinth permits 100. Twenty is a screen of results and a small response;
/// somebody who wants more scrolls, which asks for the next page.
pub const PAGE: usize = 20;

/// A ceiling on a mod download, in bytes.
///
/// Generous for a 1.8.9 mod — Legacy Fabric API is about 2 MB and the largest
/// thing anyone sensibly installs is a resource-heavy mod in the tens — and low
/// enough that a mistake cannot fill a disk.
const MAX_MOD_BYTES: u64 = 64 * 1024 * 1024;

// ---------------------------------------------------------------------------
// Loader names
// ---------------------------------------------------------------------------

/// Modrinth's own name for a loader.
///
/// Not the same word Fathom uses, and the difference matters: 1.8.9 mods are
/// tagged `legacy-fabric`, not `fabric`. Querying with `fabric` returns modern
/// Fabric mods that will not load, which is how a browse tab ends up offering
/// things that cannot work.
pub fn loader_facet(loader: Loader) -> Option<&'static str> {
    match loader {
        Loader::LegacyFabric => Some("legacy-fabric"),
        Loader::Forge => Some("forge"),
        Loader::Vanilla => None,
    }
}

// ---------------------------------------------------------------------------
// What comes back
// ---------------------------------------------------------------------------

/// One search result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub summary: String,
    pub downloads: u64,
    /// SPDX id as Modrinth reports it, when it reports one.
    pub license: Option<String>,
    pub categories: Vec<String>,
    /// The publishing account, which Modrinth includes with a search hit but
    /// not with a project document.
    pub author: Option<String>,
    /// Absolute URL of the project icon. Untrusted, like every URL here.
    pub icon_url: Option<String>,
    /// Minecraft versions the project claims across all its files.
    pub game_versions: Vec<String>,
    /// Loader names, taken from the facet categories Modrinth indexes by.
    pub loaders: Vec<String>,
    /// When the project last changed, as Modrinth formats it.
    pub updated: Option<String>,
}

impl Hit {
    /// The page a user should visit to read about this.
    ///
    /// Built from the slug rather than taken from the response, so it cannot be
    /// a link to somewhere else.
    pub fn project_url(&self) -> String {
        format!("https://modrinth.com/mod/{}", self.slug)
    }

    /// How Fathom classifies the licence Modrinth reported.
    pub fn licence(&self) -> License {
        match &self.license {
            Some(id) if !id.is_empty() && !id.starts_with("LicenseRef") => {
                License::Spdx { id: id.clone() }
            }
            // A `LicenseRef-` prefix means a custom licence with no SPDX
            // meaning, and no licence at all means all rights reserved by
            // default. Both are "we do not know what this permits", and saying
            // so is the honest answer.
            _ => License::Unknown,
        }
    }
}

/// A page of search results.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Search {
    pub hits: Vec<Hit>,
    pub total: u64,
    pub offset: usize,
}

/// How finished a release claims to be.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Channel {
    Release,
    Beta,
    Alpha,
}

impl Channel {
    pub fn label(self) -> &'static str {
        match self {
            Channel::Release => "Release",
            Channel::Beta => "Beta",
            Channel::Alpha => "Alpha",
        }
    }

    fn parse(raw: &str) -> Self {
        match raw {
            "beta" => Channel::Beta,
            "alpha" => Channel::Alpha,
            _ => Channel::Release,
        }
    }
}

/// One downloadable version of a project.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Version {
    pub project_id: String,
    pub version_id: String,
    /// The author's version string, e.g. `1.13.5`.
    pub number: String,
    pub name: String,
    pub channel: Channel,
    pub loaders: BTreeSet<String>,
    pub game_versions: BTreeSet<String>,
    pub file_name: String,
    /// Absolute URL, straight from the API, validated before it is ever used.
    pub url: String,
    pub hash: ArtifactHash,
    /// What this build says it needs, and what it says it conflicts with.
    pub dependencies: Vec<VersionDependency>,
    /// When this build was published, as Modrinth formats it.
    pub published: Option<String>,
}

/// How one version relates to another project.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DependencyType {
    Required,
    Optional,
    Incompatible,
    Embedded,
}

impl DependencyType {
    fn parse(raw: &str) -> Option<Self> {
        match raw {
            "required" => Some(DependencyType::Required),
            "optional" => Some(DependencyType::Optional),
            "incompatible" => Some(DependencyType::Incompatible),
            "embedded" => Some(DependencyType::Embedded),
            // An unknown relationship is not assumed to be a harmless one: it
            // is dropped, and the install proceeds without inventing a
            // requirement or a conflict nobody stated.
            _ => None,
        }
    }
}

/// One declared relationship on a version.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionDependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub kind: DependencyType,
}

/// A project document: everything the project page shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Project {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub summary: String,
    /// The long description, as Markdown.
    pub body: String,
    pub categories: Vec<String>,
    pub loaders: Vec<String>,
    pub game_versions: Vec<String>,
    pub icon_url: Option<String>,
    pub gallery: Vec<GalleryImage>,
    pub source_url: Option<String>,
    pub issues_url: Option<String>,
    pub license: Option<String>,
    pub downloads: u64,
    pub updated: Option<String>,
}

/// One screenshot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GalleryImage {
    pub url: String,
    pub title: Option<String>,
}

impl Version {
    pub fn supports(&self, loader: Loader, minecraft: &str) -> bool {
        loader_facet(loader).is_some_and(|facet| self.loaders.contains(facet))
            && self.game_versions.contains(minecraft)
    }

    pub fn version(&self) -> ModVersion {
        ModVersion::new(&self.number)
    }
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// Everything a search can vary.
///
/// A struct rather than eight arguments: the browser varies most of them and a
/// call with four booleans and three strings in a row is a call somebody will
/// eventually get out of order.
#[derive(Debug, Clone)]
pub struct SearchOptions<'a> {
    pub query: &'a str,
    pub loader: Loader,
    pub minecraft: &'a str,
    pub offset: usize,
    pub limit: usize,
    /// One Modrinth category facet, e.g. `optimization`.
    pub category: Option<&'a str>,
    /// Modrinth's own sort index: `relevance`, `downloads`, `follows`,
    /// `newest`, `updated`.
    pub index: &'static str,
    /// Whether to restrict the query to this loader and Minecraft version.
    pub compatible_only: bool,
    /// Which loader tags count as a match when `compatible_only` is set.
    pub loader_scope: LoaderScope,
}

/// Which loader tags a compatible-only search accepts.
///
/// Modrinth facets are project-wide, so none of these is a compatibility
/// check; they decide which projects are worth checking.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LoaderScope {
    /// Only the loader's own tag (`legacy-fabric`).
    #[default]
    Exact,
    /// Also plain `fabric`, which is how Legacy Fabric mods uploaded before
    /// Modrinth had a `legacy-fabric` tag are labelled.
    WithFabric,
    /// Any loader at all: only the Minecraft version is required. For showing
    /// why a mod does not fit, never for installing it.
    Any,
}

#[derive(Debug, thiserror::Error)]
pub enum ModrinthError {
    #[error(transparent)]
    Network(#[from] NetworkError),
    #[error("Modrinth returned HTTP {status}")]
    Status {
        status: u16,
        /// The wait Modrinth asked for on a 429, in seconds.
        retry_after: Option<u64>,
    },
    #[error("Modrinth returned something this launcher could not read: {0}")]
    Malformed(String),
    #[error("{0} is not on the download allowlist, so this file will not be fetched")]
    UntrustedHost(String),
    #[error(transparent)]
    Store(#[from] ModError),
    #[error("{name} has no version for Minecraft {minecraft} on this loader")]
    NoCompatibleVersion { name: String, minecraft: String },
}

/// One entry of Modrinth's category vocabulary.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Category {
    /// The facet value, e.g. `optimization`.
    pub name: String,
    /// Which group Modrinth files it under: `categories`, `features`,
    /// `resolutions` or `performance impact`.
    pub header: String,
}

#[derive(Debug, Deserialize)]
struct RawCategory {
    name: String,
    project_type: String,
    #[serde(default)]
    header: String,
}

// ---------------------------------------------------------------------------
// The client
// ---------------------------------------------------------------------------

/// Talks to Modrinth. Holds nothing but an HTTP client.
#[derive(Debug, Clone)]
pub struct Modrinth {
    http: HttpClient,
}

impl Modrinth {
    pub fn new(http: HttpClient) -> Self {
        Self { http }
    }

    /// One GET, parsed, with every failure mapped the same way.
    ///
    /// Written once so that a new endpoint cannot accidentally skip the status
    /// check, which is the mistake that turns an error page into a parse error
    /// and a parse error into "Modrinth is broken".
    fn get_json<T: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        purpose: Purpose,
        grant: &PerActionGrant,
    ) -> Result<T, ModrinthError> {
        let request = RequestDescriptor::new(
            Destination::ModrinthApi,
            purpose,
            Feature::ModBrowse,
            path.to_owned(),
        );
        let response = self
            .http
            .send(
                &request,
                Some(grant),
                crate::network::Method::Get,
                crate::network::Body::Empty,
                &[],
            )
            .map_err(ModrinthError::Network)?;
        if !response.is_success() {
            return Err(ModrinthError::Status {
                status: response.status,
                retry_after: response.retry_after,
            });
        }
        response
            .json(Destination::ModrinthApi.host())
            .map_err(ModrinthError::Network)
    }

    /// Search for mods compatible with a loader and version.
    ///
    /// `grant` is the user's explicit action. There is no overload without one.
    pub fn search(
        &self,
        query: &str,
        loader: Loader,
        minecraft: &str,
        offset: usize,
        grant: &PerActionGrant,
    ) -> Result<Search, ModrinthError> {
        self.search_with(
            &SearchOptions {
                query,
                loader,
                minecraft,
                offset,
                limit: PAGE,
                category: None,
                index: "relevance",
                compatible_only: true,
                loader_scope: LoaderScope::Exact,
            },
            grant,
        )
    }

    /// One project document, with its description and gallery.
    pub fn project(&self, id: &str, grant: &PerActionGrant) -> Result<Project, ModrinthError> {
        let path = format!("{API}/project/{}", encode_segment(id));
        let raw: RawProject = self.get_json(&path, Purpose::ResolveModMetadata, grant)?;
        Ok(raw.into_project())
    }

    /// The usernames on a project's team, in the order Modrinth lists them.
    ///
    /// A separate request because Modrinth keeps authorship on the team rather
    /// than on the project. Called only for a detail view, never for a search
    /// result, so browsing a page of forty mods does not make forty requests.
    pub fn members(&self, id: &str, grant: &PerActionGrant) -> Result<Vec<String>, ModrinthError> {
        let path = format!("{API}/project/{}/members", encode_segment(id));
        let raw: Vec<RawMember> = self.get_json(&path, Purpose::ResolveModMetadata, grant)?;
        Ok(raw
            .into_iter()
            .map(|member| member.user.username)
            .filter(|name| !name.is_empty())
            .collect())
    }

    /// One specific build, by Modrinth's version id.
    ///
    /// Not filtered by loader or version: the caller asked for this exact
    /// build, usually because an author pinned a dependency to it or because a
    /// profile records it as installed. Whether it is compatible is then the
    /// caller's check, and it is one the installer makes.
    pub fn version_by_id(
        &self,
        version_id: &str,
        grant: &PerActionGrant,
    ) -> Result<Version, ModrinthError> {
        let path = format!("{API}/version/{}", encode_segment(version_id));
        let raw: RawVersion = self.get_json(&path, Purpose::ResolveModMetadata, grant)?;
        raw.into_version().ok_or_else(|| {
            ModrinthError::Malformed("that version has no file with a published hash".to_owned())
        })
    }

    /// Which build, if any, has these exact bytes.
    ///
    /// This is how a jar somebody installed by hand acquires a name, a project
    /// and an update path. The hash is the one Fathom computed from the file on
    /// disk, so a match is proof rather than a claim.
    pub fn by_hash(
        &self,
        sha1: &str,
        grant: &PerActionGrant,
    ) -> Result<Option<Version>, ModrinthError> {
        if sha1.len() != 40 || !sha1.chars().all(|c| c.is_ascii_hexdigit()) {
            return Ok(None);
        }
        let path = format!(
            "{API}/version_file/{}?algorithm=sha1",
            encode_segment(&sha1.to_ascii_lowercase())
        );
        let request = RequestDescriptor::new(
            Destination::ModrinthApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            path,
        );
        let response = self
            .http
            .send(
                &request,
                Some(grant),
                crate::network::Method::Get,
                crate::network::Body::Empty,
                &[],
            )
            .map_err(ModrinthError::Network)?;
        // A hash nobody has indexed is the ordinary case for a mod that is not
        // on Modrinth, so it is an answer rather than an error.
        if response.status == 404 {
            return Ok(None);
        }
        if !response.is_success() {
            return Err(ModrinthError::Status {
                status: response.status,
                retry_after: response.retry_after,
            });
        }
        let raw: RawVersion = response
            .json(Destination::ModrinthApi.host())
            .map_err(ModrinthError::Network)?;
        Ok(raw.into_version())
    }

    /// The search above, with everything the browser can vary.
    pub fn search_with(
        &self,
        options: &SearchOptions<'_>,
        grant: &PerActionGrant,
    ) -> Result<Search, ModrinthError> {
        let query = options.query;
        let loader = options.loader;
        let minecraft = options.minecraft;
        let offset = options.offset;
        let Some(facet) = loader_facet(loader) else {
            // Vanilla loads no mods, so there is nothing a search could
            // usefully return. An empty result is the honest answer, and it
            // saves a request nobody wanted.
            return Ok(Search {
                hits: Vec::new(),
                total: 0,
                offset: 0,
            });
        };

        // Facets are AND across the outer list, OR within each inner one.
        // Every clause here is a hard compatibility requirement, so they are
        // all separate outer entries.
        //
        // `compatible_only` is what the Browse tab always uses. Turning it off
        // is for a user who deliberately asked to see everything, and it
        // narrows nothing but the project type: the installer refuses an
        // incompatible file either way.
        let mut clauses: Vec<String> = vec![r#"["project_type:mod"]"#.to_owned()];
        if options.compatible_only {
            clauses.push(format!(r#"["versions:{minecraft}"]"#));
            match options.loader_scope {
                // OR within one clause.
                LoaderScope::WithFabric if facet != "fabric" => {
                    clauses.push(format!(r#"["categories:{facet}","categories:fabric"]"#));
                }
                LoaderScope::Exact | LoaderScope::WithFabric => {
                    clauses.push(format!(r#"["categories:{facet}"]"#));
                }
                LoaderScope::Any => {}
            }
        }
        if let Some(category) = options.category.filter(|category| !category.is_empty()) {
            clauses.push(format!(r#"["categories:{}"]"#, escape_facet(category)));
        }
        let facets = format!("[{}]", clauses.join(","));
        let limit = options.limit.clamp(1, 100);
        let index = options.index;
        let path = format!(
            "{API}/search?query={}&facets={}&limit={limit}&offset={offset}&index={index}",
            encode(query),
            encode(&facets)
        );

        let request = RequestDescriptor::new(
            Destination::ModrinthApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            path,
        );
        let response = self
            .http
            .send(
                &request,
                Some(grant),
                crate::network::Method::Get,
                crate::network::Body::Empty,
                &[],
            )
            .map_err(ModrinthError::Network)?;
        if !response.is_success() {
            return Err(ModrinthError::Status {
                status: response.status,
                retry_after: response.retry_after,
            });
        }

        let raw: RawSearch = response
            .json(Destination::ModrinthApi.host())
            .map_err(ModrinthError::Network)?;

        Ok(Search {
            total: raw.total_hits,
            offset,
            hits: raw.hits.into_iter().map(RawHit::into_hit).collect(),
        })
    }

    /// Every version of a project that fits this loader and Minecraft version.
    ///
    /// Newest first. Filtered twice: the query asks for the right ones, and the
    /// response is checked again, because what a server returns is its own
    /// business and a compatibility claim is not a compatibility check.
    pub fn versions(
        &self,
        project: &str,
        loader: Loader,
        minecraft: &str,
        grant: &PerActionGrant,
    ) -> Result<Vec<Version>, ModrinthError> {
        let Some(facet) = loader_facet(loader) else {
            return Ok(Vec::new());
        };
        let path = format!(
            "{API}/project/{}/version?game_versions={}&loaders={}",
            encode_segment(project),
            encode(&format!(r#"["{minecraft}"]"#)),
            encode(&format!(r#"["{facet}"]"#)),
        );

        let request = RequestDescriptor::new(
            Destination::ModrinthApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            path,
        );
        let response = self
            .http
            .send(
                &request,
                Some(grant),
                crate::network::Method::Get,
                crate::network::Body::Empty,
                &[],
            )
            .map_err(ModrinthError::Network)?;
        if !response.is_success() {
            return Err(ModrinthError::Status {
                status: response.status,
                retry_after: response.retry_after,
            });
        }

        let raw: Vec<RawVersion> = response
            .json(Destination::ModrinthApi.host())
            .map_err(ModrinthError::Network)?;

        let mut versions: Vec<Version> = raw
            .into_iter()
            .filter_map(RawVersion::into_version)
            .filter(|version| version.supports(loader, minecraft))
            .collect();

        // Modrinth returns newest first already. Sorting by channel keeps that
        // order within each channel while putting releases above pre-releases,
        // so the default choice is the newest stable build rather than the
        // newest anything.
        versions.sort_by_key(|version| version.channel);
        Ok(versions)
    }

    /// Every build of a project that lists this Minecraft version, whatever
    /// loader it is tagged for. Newest first, as Modrinth returns them.
    ///
    /// For the browser's compatibility resolver, which has to see the plain
    /// `fabric`, `ornithe` and `forge` builds for 1.8.9 to say *why* a project
    /// does or does not fit; [`Self::versions`] would hide them. Nothing here
    /// decides compatibility, and nothing installs from this list without the
    /// resolver's verdict.
    pub fn versions_for_game(
        &self,
        project: &str,
        minecraft: &str,
        grant: &PerActionGrant,
    ) -> Result<Vec<Version>, ModrinthError> {
        let path = format!(
            "{API}/project/{}/version?game_versions={}",
            encode_segment(project),
            encode(&format!(r#"["{minecraft}"]"#)),
        );
        let raw: Vec<RawVersion> = self.get_json(&path, Purpose::ResolveModMetadata, grant)?;
        Ok(raw
            .into_iter()
            .filter_map(RawVersion::into_version)
            .collect())
    }

    /// Modrinth's category vocabulary for mods, as `(name, header)` pairs.
    ///
    /// Read from the API rather than written down here so the browser offers
    /// exactly the categories Modrinth will accept in a facet.
    pub fn categories(&self, grant: &PerActionGrant) -> Result<Vec<Category>, ModrinthError> {
        let raw: Vec<RawCategory> = self.get_json(
            &format!("{API}/tag/category"),
            Purpose::ResolveModMetadata,
            grant,
        )?;
        Ok(raw
            .into_iter()
            .filter(|category| category.project_type == "mod")
            .filter(|category| {
                !category.name.is_empty()
                    && category.name.len() <= 40
                    && category
                        .name
                        .chars()
                        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
            })
            .map(|category| Category {
                name: category.name,
                header: category.header,
            })
            .collect())
    }

    /// The newest release-channel version, falling back to the newest of any.
    pub fn newest(
        &self,
        project: &str,
        loader: Loader,
        minecraft: &str,
        grant: &PerActionGrant,
    ) -> Result<Option<Version>, ModrinthError> {
        Ok(self
            .versions(project, loader, minecraft, grant)?
            .into_iter()
            .next())
    }

    /// Download a version into the mod store, verifying its published hash.
    ///
    /// The URL comes from the API and is treated as untrusted: it has to parse
    /// as HTTPS, name a host on the compile-time allowlist, and carry a safe
    /// path, or nothing is fetched.
    pub fn download(
        &self,
        version: &Version,
        store: &ModStore,
        grant: &PerActionGrant,
    ) -> Result<StoredMod, ModrinthError> {
        // Already have exactly these bytes? Then this is free and offline.
        if store.contains(&version.hash.sha1) {
            return Ok(StoredMod {
                sha1: version.hash.sha1.clone(),
                size: version.hash.size.unwrap_or_default(),
                path: store.path_for(&version.hash.sha1)?,
            });
        }

        let request = descriptor_from_url(&version.url, Purpose::DownloadMod, Feature::ModBrowse)
            .map_err(|_| ModrinthError::UntrustedHost(version.url.clone()))?;

        let limit = version.hash.size.map_or(MAX_MOD_BYTES, |size| {
            size.saturating_add(4096).min(MAX_MOD_BYTES)
        });

        let mut bytes: Vec<u8> = Vec::new();
        self.http
            .stream(&request, Some(grant), limit, &mut bytes)
            .map_err(ModrinthError::Network)?;

        store
            .adopt_bytes(&bytes, &version.hash.sha1)
            .map_err(ModrinthError::Store)
    }
}

// ---------------------------------------------------------------------------
// Percent encoding
// ---------------------------------------------------------------------------

/// Percent-encode a query-string value.
///
/// Hand-rolled rather than pulled in as a dependency, because the rule is one
/// line and the alternative is another crate in the trust boundary for it. It
/// is deliberately conservative: only unreserved characters survive, so there
/// is no argument to have about which delimiters are safe in which context.
fn encode(value: &str) -> String {
    use std::fmt::Write as _;

    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(char::from(byte));
            }
            // `write!` into a String cannot fail, so the result is discarded
            // rather than unwrapped.
            other => {
                let _ = write!(out, "%{other:02X}");
            }
        }
    }
    out
}

/// Keep a facet value to characters a Modrinth facet can contain.
///
/// The value is interpolated into a JSON document that is then percent-encoded,
/// so a quote or a bracket would change the query's structure rather than its
/// content. Categories are lowercase words and hyphens; anything else is
/// dropped rather than escaped, because there is no legitimate category that
/// needs escaping.
fn escape_facet(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .take(64)
        .collect()
}

/// Encode a value that becomes a path segment.
///
/// Same rule. Separate function because the reason is different: a slash here
/// would add a path segment rather than a parameter, and `is_safe_path` would
/// then be checking a path the caller did not mean to write.
fn encode_segment(value: &str) -> String {
    encode(value)
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct RawSearch {
    #[serde(default)]
    hits: Vec<RawHit>,
    #[serde(default)]
    total_hits: u64,
}

#[derive(Debug, Deserialize)]
struct RawHit {
    #[serde(default)]
    project_id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    license: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    author: Option<String>,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    versions: Vec<String>,
    #[serde(default)]
    date_modified: Option<String>,
}

impl RawHit {
    fn into_hit(self) -> Hit {
        // Modrinth indexes loaders as categories, so the two arrive in one
        // list. Splitting them here means the browser never has to know that.
        let (loaders, categories): (Vec<String>, Vec<String>) = self
            .categories
            .into_iter()
            .partition(|category| LOADER_CATEGORIES.contains(&category.as_str()));
        Hit {
            project_id: self.project_id,
            slug: self.slug,
            title: self.title,
            summary: self.description,
            downloads: self.downloads,
            license: self.license,
            categories,
            author: self.author.filter(|author| !author.is_empty()),
            icon_url: self.icon_url.filter(|url| !url.is_empty()),
            game_versions: self.versions,
            loaders,
            updated: self.date_modified.filter(|when| !when.is_empty()),
        }
    }
}

/// Category names that are really loaders.
///
/// Modrinth has no separate loader field on a search hit: `legacy-fabric` and
/// `forge` arrive in `categories` beside `optimization` and `utility`. The list
/// is short and fixed because only these three can matter to a 1.8.9 launcher.
const LOADER_CATEGORIES: [&str; 6] = [
    "legacy-fabric",
    "fabric",
    "forge",
    "neoforge",
    "quilt",
    "liteloader",
];

#[derive(Debug, Deserialize)]
struct RawVersion {
    #[serde(default)]
    id: String,
    #[serde(default)]
    project_id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version_number: String,
    #[serde(default)]
    version_type: String,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    files: Vec<RawFile>,
    #[serde(default)]
    dependencies: Vec<RawDependency>,
    #[serde(default)]
    date_published: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawDependency {
    #[serde(default)]
    project_id: Option<String>,
    #[serde(default)]
    version_id: Option<String>,
    #[serde(default)]
    dependency_type: String,
}

#[derive(Debug, Deserialize)]
struct RawProject {
    #[serde(default)]
    id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    gallery: Vec<RawGallery>,
    #[serde(default)]
    source_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
    #[serde(default)]
    license: Option<RawLicense>,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    updated: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawGallery {
    #[serde(default)]
    url: String,
    #[serde(default)]
    title: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawLicense {
    #[serde(default)]
    id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawMember {
    #[serde(default)]
    user: RawUser,
}

#[derive(Debug, Default, Deserialize)]
struct RawUser {
    #[serde(default)]
    username: String,
}

impl RawProject {
    fn into_project(self) -> Project {
        Project {
            id: self.id,
            slug: self.slug,
            title: self.title,
            summary: self.description,
            body: self.body,
            categories: self.categories,
            loaders: self.loaders,
            game_versions: self.game_versions,
            icon_url: self.icon_url.filter(|url| !url.is_empty()),
            gallery: self
                .gallery
                .into_iter()
                .filter(|image| !image.url.is_empty())
                .map(|image| GalleryImage {
                    url: image.url,
                    title: image.title.filter(|title| !title.is_empty()),
                })
                .collect(),
            source_url: self.source_url.filter(|url| !url.is_empty()),
            issues_url: self.issues_url.filter(|url| !url.is_empty()),
            license: self.license.and_then(|license| license.id),
            downloads: self.downloads,
            updated: self.updated.filter(|when| !when.is_empty()),
        }
    }
}

#[derive(Debug, Deserialize)]
struct RawFile {
    #[serde(default)]
    filename: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    primary: bool,
    #[serde(default)]
    hashes: RawHashes,
}

#[derive(Debug, Default, Deserialize)]
struct RawHashes {
    #[serde(default)]
    sha1: Option<String>,
}

impl RawVersion {
    /// Convert, dropping anything unusable.
    ///
    /// A version with no primary file, or whose file carries no valid SHA-1, is
    /// returned as `None` rather than as an entry that cannot be installed.
    /// Offering a download the launcher would then refuse for want of a hash is
    /// a worse experience than not offering it.
    fn into_version(self) -> Option<Version> {
        let file = self
            .files
            .iter()
            .find(|candidate| candidate.primary)
            .or_else(|| self.files.first())?;
        let hash = ArtifactHash::new(file.hashes.sha1.clone()?, Some(file.size))?;
        if file.url.is_empty() || file.filename.is_empty() {
            return None;
        }
        let dependencies = self
            .dependencies
            .into_iter()
            .filter_map(|dependency| {
                DependencyType::parse(&dependency.dependency_type).map(|kind| VersionDependency {
                    project_id: dependency.project_id.filter(|id| !id.is_empty()),
                    version_id: dependency.version_id.filter(|id| !id.is_empty()),
                    kind,
                })
            })
            .collect();
        let published = self.date_published.filter(|when| !when.is_empty());
        Some(Version {
            project_id: self.project_id,
            version_id: self.id,
            number: if self.version_number.is_empty() {
                self.name.clone()
            } else {
                self.version_number
            },
            name: self.name,
            channel: Channel::parse(&self.version_type),
            loaders: self.loaders.into_iter().collect(),
            game_versions: self.game_versions.into_iter().collect(),
            file_name: file.filename.clone(),
            url: file.url.clone(),
            hash,
            dependencies,
            published,
        })
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn raw_version(json: &str) -> Option<Version> {
        serde_json::from_str::<RawVersion>(json)
            .expect("the test fixture parses")
            .into_version()
    }

    /// The 1.8.9 trap. Modrinth tags these `legacy-fabric`, and querying with
    /// `fabric` returns modern mods that cannot load — a browse tab full of
    /// things that fail at startup.
    #[test]
    fn the_loader_facet_for_our_version_is_legacy_fabric() {
        assert_eq!(loader_facet(Loader::LegacyFabric), Some("legacy-fabric"));
        assert_eq!(loader_facet(Loader::Forge), Some("forge"));
        assert_eq!(loader_facet(Loader::Vanilla), None);
    }

    /// Vanilla loads no mods, so a search under it makes no request at all.
    #[test]
    fn searching_under_vanilla_asks_nothing_and_returns_nothing() {
        // Constructed without a working client on purpose: if this reached the
        // network the test would fail rather than pass slowly.
        let modrinth = Modrinth::new(crate::network::HttpClient::new(
            crate::network::NetworkPolicy {
                online: false,
                allow_update_check: false,
                allow_managed_java: false,
                allow_mod_repositories: false,
            },
            crate::network::NetworkJournal::new(),
            "test",
        ));
        let grant = PerActionGrant::user_requested("a test");
        let result = modrinth
            .search("anything", Loader::Vanilla, "1.8.9", 0, &grant)
            .expect("no request, no failure");
        assert!(result.hits.is_empty());
        assert_eq!(result.total, 0);
    }

    #[test]
    fn a_version_without_a_hash_is_dropped_rather_than_offered() {
        let without = raw_version(
            r#"{"id":"v","project_id":"p","version_number":"1.0","version_type":"release",
                "loaders":["legacy-fabric"],"game_versions":["1.8.9"],
                "files":[{"filename":"a.jar","url":"https://cdn.modrinth.com/a.jar",
                          "size":10,"primary":true,"hashes":{}}]}"#,
        );
        assert!(without.is_none(), "no hash means no offer");

        let bad = raw_version(
            r#"{"id":"v","project_id":"p","version_number":"1.0","version_type":"release",
                "loaders":["legacy-fabric"],"game_versions":["1.8.9"],
                "files":[{"filename":"a.jar","url":"https://cdn.modrinth.com/a.jar",
                          "size":10,"primary":true,"hashes":{"sha1":"not-a-hash"}}]}"#,
        );
        assert!(bad.is_none(), "an unusable hash is the same as none");
    }

    #[test]
    fn the_primary_file_is_the_one_taken() {
        let version = raw_version(
            r#"{"id":"v","project_id":"p","version_number":"1.0","version_type":"release",
                "loaders":["legacy-fabric"],"game_versions":["1.8.9"],
                "files":[
                  {"filename":"sources.jar","url":"https://cdn.modrinth.com/s.jar","size":1,
                   "primary":false,"hashes":{"sha1":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}},
                  {"filename":"mod.jar","url":"https://cdn.modrinth.com/m.jar","size":2,
                   "primary":true,"hashes":{"sha1":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}"#,
        )
        .expect("a usable version");
        assert_eq!(version.file_name, "mod.jar");
        assert_eq!(version.hash.sha1, "a".repeat(40));
    }

    #[test]
    fn compatibility_is_checked_against_the_response_not_assumed_from_the_query() {
        let wrong_loader = raw_version(
            r#"{"id":"v","project_id":"p","version_number":"1.0","version_type":"release",
                "loaders":["fabric"],"game_versions":["1.8.9"],
                "files":[{"filename":"a.jar","url":"https://cdn.modrinth.com/a.jar","size":1,
                          "primary":true,"hashes":{"sha1":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}"#,
        )
        .expect("it parses");
        assert!(
            !wrong_loader.supports(Loader::LegacyFabric, "1.8.9"),
            "modern Fabric is not Legacy Fabric"
        );

        let wrong_version = raw_version(
            r#"{"id":"v","project_id":"p","version_number":"1.0","version_type":"release",
                "loaders":["legacy-fabric"],"game_versions":["1.12.2"],
                "files":[{"filename":"a.jar","url":"https://cdn.modrinth.com/a.jar","size":1,
                          "primary":true,"hashes":{"sha1":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}"#,
        )
        .expect("it parses");
        assert!(!wrong_version.supports(Loader::LegacyFabric, "1.8.9"));
    }

    #[test]
    fn release_channels_are_read_and_releases_sort_first() {
        assert_eq!(Channel::parse("release"), Channel::Release);
        assert_eq!(Channel::parse("beta"), Channel::Beta);
        assert_eq!(Channel::parse("alpha"), Channel::Alpha);
        // Anything unrecognised is treated as a release rather than hidden: a
        // new channel name should not make a project's versions vanish.
        assert_eq!(Channel::parse("something-new"), Channel::Release);
        assert!(Channel::Release < Channel::Beta);
        assert!(Channel::Beta < Channel::Alpha);
    }

    /// A project url is built from the slug, never taken from the response, so
    /// a tampered index cannot turn "open the project page" into a link
    /// somewhere else.
    #[test]
    fn a_project_link_is_built_from_the_slug() {
        let hit = Hit {
            project_id: "abc".into(),
            slug: "legacy-fabric-api".into(),
            title: "Legacy Fabric API".into(),
            summary: String::new(),
            downloads: 1,
            license: Some("Apache-2.0".into()),
            categories: Vec::new(),
            author: None,
            icon_url: None,
            game_versions: Vec::new(),
            loaders: Vec::new(),
            updated: None,
        };
        assert_eq!(
            hit.project_url(),
            "https://modrinth.com/mod/legacy-fabric-api"
        );
    }

    /// A `LicenseRef-` identifier is a custom licence with no SPDX meaning.
    /// Recording it as though it were an SPDX id would be claiming to know
    /// terms nobody has read.
    #[test]
    fn only_a_real_spdx_identifier_is_treated_as_one() {
        let with = |id: Option<&str>| Hit {
            project_id: String::new(),
            slug: String::new(),
            title: String::new(),
            summary: String::new(),
            downloads: 0,
            license: id.map(str::to_owned),
            categories: Vec::new(),
            author: None,
            icon_url: None,
            game_versions: Vec::new(),
            loaders: Vec::new(),
            updated: None,
        };
        assert_eq!(
            with(Some("Apache-2.0")).licence(),
            License::Spdx {
                id: "Apache-2.0".into()
            }
        );
        assert_eq!(
            with(Some("LicenseRef-Custom-1.0")).licence(),
            License::Unknown
        );
        assert_eq!(with(None).licence(), License::Unknown);
        assert_eq!(with(Some("")).licence(), License::Unknown);
    }

    /// The query string is built by us, and anything that could change its
    /// shape is escaped. A search for `a&b=c` must be one parameter.
    #[test]
    fn a_query_cannot_add_parameters_of_its_own() {
        assert_eq!(encode("a&b=c"), "a%26b%3Dc");
        assert_eq!(encode("../../etc"), "..%2F..%2Fetc");
        assert_eq!(encode("hello world"), "hello%20world");
        assert_eq!(encode("safe-._~"), "safe-._~");
        // Non-ASCII goes through as UTF-8 bytes rather than being dropped.
        assert_eq!(encode("é"), "%C3%A9");
    }

    /// The live check.
    ///
    /// `#[ignore]` because the test suite must pass with no network, and a test
    /// that reaches a third party's server is not a unit test. It is kept in
    /// the repository rather than done once by hand, because the facts it
    /// checks are facts about somebody else's API and they can change without
    /// telling us:
    ///
    /// - that `legacy-fabric` is the loader facet for 1.8.9;
    /// - that Legacy Fabric API is on Modrinth under Apache-2.0;
    /// - that its 1.8.9 versions carry a primary file with a SHA-1;
    /// - and that the file host is one the allowlist already knows.
    ///
    /// Run it with `cargo test -p launcher-core -- --ignored modrinth_live`.
    #[test]
    #[ignore = "reaches api.modrinth.com"]
    fn modrinth_live_answers_the_questions_the_launcher_asks() {
        let http = crate::network::HttpClient::new(
            crate::network::NetworkPolicy {
                online: true,
                allow_update_check: false,
                allow_managed_java: false,
                allow_mod_repositories: true,
            },
            crate::network::NetworkJournal::new(),
            "fathom-test",
        );
        let modrinth = Modrinth::new(http);
        let grant = PerActionGrant::user_requested("a live check");

        // An empty query is the browse case, which is what the Browse tab
        // opens on. A narrow term is a separate question and a separate
        // assertion, because "search works" and "browse works" have failed
        // independently before.
        let browsed = modrinth
            .search("", Loader::LegacyFabric, "1.8.9", 0, &grant)
            .expect("a browse");
        assert!(browsed.total > 0, "1.8.9 has mods on Modrinth");
        for hit in &browsed.hits {
            assert!(!hit.project_id.is_empty());
            assert!(!hit.slug.is_empty());
        }

        let searched = modrinth
            .search("ksyxis", Loader::LegacyFabric, "1.8.9", 0, &grant)
            .expect("a search");
        assert!(
            searched.hits.iter().any(|hit| hit.slug == "ksyxis"),
            "a search for a known 1.8.9 mod finds it: {:?}",
            searched.hits.iter().map(|h| &h.slug).collect::<Vec<_>>()
        );

        let versions = modrinth
            .versions("legacy-fabric-api", Loader::LegacyFabric, "1.8.9", &grant)
            .expect("versions");
        assert!(!versions.is_empty(), "Legacy Fabric API supports 1.8.9");
        let newest = versions.first().expect("at least one");
        assert!(newest.supports(Loader::LegacyFabric, "1.8.9"));
        assert_eq!(newest.hash.sha1.len(), 40);
        assert!(
            descriptor_from_url(&newest.url, Purpose::DownloadMod, Feature::ModBrowse).is_ok(),
            "the file host must already be on the allowlist: {}",
            newest.url
        );
    }

    /// The download path, end to end, against the real API.
    ///
    /// `#[ignore]` for the same reason as the search check above: the suite has
    /// to pass with no network.
    ///
    /// This one exists because a live UI check found a bug the unit tests could
    /// not: `HttpClient::stream` passed `|| false` as its `keep_going`
    /// predicate and cancelled every transfer before reading a byte. Nothing
    /// reached that function before -- the only other caller is the
    /// self-updater, which refuses to run without a signing key -- so the first
    /// real transfer through it was a mod download, and it failed with "the
    /// transfer was cancelled" when nobody had cancelled anything.
    ///
    /// `an_uncancellable_stream_is_not_cancelled` in `network::http` covers the
    /// predicate itself. This covers the whole path: resolve a version, fetch
    /// the file, verify it against the hash the API published, and land it in
    /// the store under that hash.
    ///
    /// Run it with `cargo test -p launcher-core -- --ignored modrinth_live`.
    #[test]
    #[ignore = "reaches api.modrinth.com and cdn.modrinth.com"]
    fn modrinth_live_downloads_and_verifies_a_real_file() {
        let http = crate::network::HttpClient::new(
            crate::network::NetworkPolicy {
                online: true,
                allow_update_check: false,
                allow_managed_java: false,
                allow_mod_repositories: true,
            },
            crate::network::NetworkJournal::new(),
            "fathom-test",
        );
        let modrinth = Modrinth::new(http);
        let grant = PerActionGrant::user_requested("a live download check");

        let version = modrinth
            .newest("legacy-fabric-api", Loader::LegacyFabric, "1.8.9", &grant)
            .expect("a version list")
            .expect("Legacy Fabric API has a 1.8.9 build");

        let tmp = tempfile::tempdir().expect("a temporary directory");
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("the data directories");
        let store = ModStore::new(paths);

        let stored = modrinth
            .download(&version, &store, &grant)
            .expect("the file downloads and verifies");

        assert_eq!(
            stored.sha1, version.hash.sha1,
            "the stored file is named by the hash the API published"
        );
        assert!(store.contains(&stored.sha1), "and it is in the store");
        assert!(stored.size > 100_000, "a real jar, not an error page");
        assert!(
            store.verify(&stored.sha1).expect("it can be re-hashed"),
            "the bytes on disk still hash to their own name"
        );

        // Twice is free and writes nothing: the store is content-addressed, so
        // the second call finds the bytes already there.
        let again = modrinth
            .download(&version, &store, &grant)
            .expect("a second download is a no-op");
        assert_eq!(again.sha1, stored.sha1);
    }

    /// A download URL is remote metadata. The allowlist is what makes it safe
    /// to use, and this is the test that says so.
    #[test]
    fn a_download_url_off_the_allowlist_is_refused() {
        let good = descriptor_from_url(
            "https://cdn.modrinth.com/data/9CJED7xi/versions/x/legacy-fabric-api-1.13.5.jar",
            Purpose::DownloadMod,
            Feature::ModBrowse,
        );
        assert!(good.is_ok(), "{good:?}");
        assert_eq!(good.unwrap().destination, Destination::ModrinthCdn);

        for hostile in [
            "https://cdn.modrinth.com.evil.example/a.jar",
            "https://evil.example/a.jar",
            "http://cdn.modrinth.com/a.jar",
            "https://user@cdn.modrinth.com/a.jar",
        ] {
            assert!(
                descriptor_from_url(hostile, Purpose::DownloadMod, Feature::ModBrowse).is_err(),
                "{hostile} should be refused"
            );
        }
    }
}
