//! `CurseForge`, through its official API and nothing else.
//!
//! # Why this took longer than Modrinth
//!
//! `docs/research/mod-policies.md` §7 is explicit: every `CurseForge` project
//! carries a third-party distribution flag, and when an author switches it off
//! *"any 3rd-party service, website or client will not have access to your
//! projects or files via the 3rd-party API"*. Honouring that is not optional and
//! working around it — by scraping the site, by guessing a CDN path, by using a
//! browser user agent — is forbidden.
//!
//! So this provider reads `allowModDistribution` and `downloadUrl`, and when
//! either says no it produces [`FileDownload::Withheld`] with a link to the
//! project page. The install path refuses to fetch a withheld file; the
//! interface can say *"the author asks that you download this from
//! `CurseForge`"* and open the page. That is the whole difference between the
//! two providers, and it is a difference of policy rather than of plumbing.
//!
//! # The API key
//!
//! `CurseForge` requires one, issued to whoever is running the software. It is
//! deliberately **not** compiled in, not shipped, and not fetched from a Fathom
//! server:
//!
//! - a key in the binary is a key in everybody's binary, and revoking it breaks
//!   every install at once;
//! - a key served by us would make Fathom a proxy for somebody else's terms.
//!
//! It comes from the environment (`FATHOM_CURSEFORGE_API_KEY`) or from a file
//! the user creates ([`KEY_FILE`] in the data directory). With no key the
//! provider reports [`Availability::NotConfigured`] and the catalog simply
//! searches Modrinth — a `CurseForge` outage or a missing key is never an error
//! the whole browser has to show.
//!
//! # What is sent
//!
//! The key, as a header, to `api.curseforge.com` and to nowhere else. No
//! account, no profile, no identifier of any kind. The key is a
//! [`Secret`], so it redacts in logs and in every `Debug`.

use std::collections::BTreeSet;
use std::fmt::Write as _;

use serde::Deserialize;

use super::model::{
    clip, limits, safe_file_name, safe_link, sanitise_slug, DependencyKind, Distribution,
    FileDependency, FileDownload, ImageRef, ModFile, ModProject, ProjectRef, Provider,
    ReleaseChannel, Target,
};
use super::provider::{
    Availability, BrowseError, CatalogProvider, ProviderContext, SearchPage, SearchQuery, Sort,
};
use crate::mods::definition::{ArtifactHash, License};
use crate::network::{
    descriptor_from_url, Destination, Feature, HttpClient, Purpose, RequestDescriptor,
};
use crate::paths::DataPaths;
use crate::profiles::Loader;
use crate::secret::Secret;

/// The API version this client speaks.
const API: &str = "/v1";

/// Minecraft, in `CurseForge`'s game list.
const GAME_MINECRAFT: u32 = 432;

/// The "Mods" class within Minecraft. Without it a search also returns resource
/// packs, worlds and modpacks, none of which belong in a mods browser.
const CLASS_MODS: u32 = 6;

/// Where a user may put their key instead of setting an environment variable.
///
/// A plain file in the launcher's own data directory, one line, nothing else.
/// Deliberately not in `settings.json`: settings are copied between machines
/// and pasted into bug reports, and a credential should be neither.
pub const KEY_FILE: &str = "curseforge-api-key.txt";

/// The environment variable checked first.
pub const KEY_ENV: &str = "FATHOM_CURSEFORGE_API_KEY";

/// The longest a key may be. Generous; the point is that a file somebody
/// pointed at by accident does not become a header.
const MAX_KEY_BYTES: usize = 256;

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/// Where the key came from, for the interface to explain itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeySource {
    Environment,
    File,
    None,
}

/// The `CurseForge` API key, or the absence of one.
#[derive(Debug, Clone)]
pub struct CurseForgeConfig {
    key: Option<Secret>,
    source: KeySource,
}

impl CurseForgeConfig {
    /// No key. `CurseForge` is then simply not one of the providers.
    pub fn unconfigured() -> Self {
        Self {
            key: None,
            source: KeySource::None,
        }
    }

    /// Read the environment first, then the key file.
    ///
    /// Neither existing is the ordinary case and not a failure: most people
    /// will never have a `CurseForge` key, and Modrinth indexes most of what a
    /// 1.8.9 profile wants.
    pub fn discover(paths: &DataPaths) -> Self {
        if let Some(key) = std::env::var(KEY_ENV).ok().and_then(|raw| clean_key(&raw)) {
            return Self {
                key: Some(Secret::new(key)),
                source: KeySource::Environment,
            };
        }
        let path = paths.root().join(KEY_FILE);
        match std::fs::read_to_string(&path) {
            Ok(contents) => {
                if let Some(key) = clean_key(&contents) {
                    Self {
                        key: Some(Secret::new(key)),
                        source: KeySource::File,
                    }
                } else {
                    tracing::warn!(
                        "the CurseForge key file exists but does not contain a usable key"
                    );
                    Self::unconfigured()
                }
            }
            Err(_) => Self::unconfigured(),
        }
    }

    /// Use a key supplied in code. For tests, and for a caller that got one
    /// from somewhere else.
    pub fn with_key(key: impl Into<String>) -> Self {
        match clean_key(&key.into()) {
            Some(key) => Self {
                key: Some(Secret::new(key)),
                source: KeySource::Environment,
            },
            None => Self::unconfigured(),
        }
    }

    pub fn is_configured(&self) -> bool {
        self.key.is_some()
    }

    pub fn source(&self) -> KeySource {
        self.source
    }

    /// What to tell the user when there is no key.
    ///
    /// Names the file rather than the directory: "put your key in
    /// `curseforge-api-key.txt`" is actionable, and the full path of somebody's
    /// data directory is not something to print into a log or a screenshot.
    pub fn how_to_configure() -> String {
        format!(
            "CurseForge needs an API key of your own. Put it in {KEY_FILE} in Fathom's data \
             folder, or set {KEY_ENV}, then reopen the browser."
        )
    }
}

/// Reduce whatever was read to a usable key, or nothing.
///
/// A key becomes an HTTP header, so anything that could end a header line or
/// start another one disqualifies the whole value. This is the one place that
/// can happen, so the check is here rather than at the call site.
fn clean_key(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.len() > MAX_KEY_BYTES {
        return None;
    }
    if !trimmed
        .chars()
        .all(|c| c.is_ascii_graphic() && c != '\u{7f}')
    {
        return None;
    }
    Some(trimmed.to_owned())
}

// ---------------------------------------------------------------------------
// The provider
// ---------------------------------------------------------------------------

/// Talks to `CurseForge`. Holds an HTTP client and, if there is one, a key.
#[derive(Debug, Clone)]
pub struct CurseForgeProvider {
    http: HttpClient,
    config: CurseForgeConfig,
}

impl CurseForgeProvider {
    pub fn new(http: HttpClient, config: CurseForgeConfig) -> Self {
        Self { http, config }
    }

    fn key(&self) -> Result<&Secret, BrowseError> {
        self.config
            .key
            .as_ref()
            .ok_or_else(|| BrowseError::NotConfigured {
                provider: Provider::CurseForge,
                reason: CurseForgeConfig::how_to_configure(),
            })
    }

    /// One GET against the API, with the key attached and the failures mapped.
    fn get_json<T: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        ctx: ProviderContext<'_>,
    ) -> Result<T, BrowseError> {
        ctx.check()?;
        let key = self.key()?;
        let request = RequestDescriptor::new(
            Destination::CurseForgeApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            path.to_owned(),
        );
        let response = self
            .http
            .send(
                &request,
                Some(ctx.grant),
                crate::network::Method::Get,
                crate::network::Body::Empty,
                &[("x-api-key", key)],
            )
            .map_err(|error| BrowseError::from_network(Provider::CurseForge, &error))?;

        if !response.is_success() {
            return Err(BrowseError::from_status(
                Provider::CurseForge,
                response.status,
                None,
            ));
        }
        response
            .json(Destination::CurseForgeApi.host())
            .map_err(|error| BrowseError::from_network(Provider::CurseForge, &error))
    }

    /// `CurseForge`'s loader ids. Only the two Fathom can launch are named.
    fn loader_type(loader: Loader) -> Option<u8> {
        match loader {
            // CurseForge has no Legacy Fabric loader: a 1.8.9 mod that runs on
            // Legacy Fabric is tagged plain Fabric there, and the Minecraft
            // version is what distinguishes it from a modern one. The version
            // filter is always applied beside this, so the pair is exact.
            Loader::LegacyFabric => Some(4),
            Loader::Forge => Some(1),
            Loader::Vanilla => None,
        }
    }

    fn sort_field(sort: Sort) -> u8 {
        // 1 Featured, 2 Popularity, 3 LastUpdated, 4 Name, 6 TotalDownloads.
        match sort {
            Sort::Relevance => 1,
            Sort::Downloads => 6,
            Sort::Updated | Sort::Newest => 3,
            Sort::Name => 4,
        }
    }
}

impl CatalogProvider for CurseForgeProvider {
    fn provider(&self) -> Provider {
        Provider::CurseForge
    }

    fn availability(&self) -> Availability {
        if self.config.is_configured() {
            Availability::Ready
        } else {
            Availability::NotConfigured {
                reason: CurseForgeConfig::how_to_configure(),
            }
        }
    }

    fn search(
        &self,
        query: &SearchQuery,
        ctx: ProviderContext<'_>,
    ) -> Result<SearchPage, BrowseError> {
        let Some(loader_type) = Self::loader_type(query.target.loader) else {
            // Vanilla loads no mods. An empty page is the honest answer and it
            // saves a request nobody wanted.
            return Ok(SearchPage::empty());
        };

        let mut path = format!(
            "{API}/mods/search?gameId={GAME_MINECRAFT}&classId={CLASS_MODS}&index={}&pageSize={}&sortField={}&sortOrder=desc",
            query.offset,
            query.limit.clamp(1, 50),
            Self::sort_field(query.sort),
        );
        if query.include_incompatible {
            // Still Minecraft mods, but no version or loader filter. The
            // installer refuses an incompatible file regardless.
        } else {
            let _ = write!(
                path,
                "&gameVersion={}&modLoaderType={loader_type}",
                encode(&query.target.minecraft)
            );
        }
        if !query.text.trim().is_empty() {
            let _ = write!(path, "&searchFilter={}", encode(query.text.trim()));
        }
        // CurseForge categories are numeric ids, not names. A caller that has
        // one passes it through; anything else is ignored rather than guessed
        // at, because a wrong category id silently returns the wrong mods.
        if let Some(category) = query
            .category
            .as_deref()
            .filter(|category| category.chars().all(|c| c.is_ascii_digit()) && !category.is_empty())
        {
            let _ = write!(path, "&categoryId={category}");
        }

        let raw: RawSearch = self.get_json(&path, ctx)?;
        let hits: Vec<ModProject> = raw
            .data
            .into_iter()
            .filter_map(RawMod::into_project)
            .collect();
        Ok(SearchPage {
            total: raw.pagination.total_count,
            offset: query.offset,
            hits,
        })
    }

    fn project(&self, id: &str, ctx: ProviderContext<'_>) -> Result<ModProject, BrowseError> {
        let raw: RawOne<RawMod> = self.get_json(&format!("{API}/mods/{}", numeric(id)?), ctx)?;
        raw.data
            .into_project()
            .map(ModProject::detailed)
            .ok_or_else(|| BrowseError::Malformed {
                provider: Provider::CurseForge,
                detail: "a project with no usable name or id".to_owned(),
            })
    }

    fn files(
        &self,
        id: &str,
        target: &Target,
        ctx: ProviderContext<'_>,
    ) -> Result<Vec<ModFile>, BrowseError> {
        let Some(loader_type) = Self::loader_type(target.loader) else {
            return Ok(Vec::new());
        };
        let path = format!(
            "{API}/mods/{}/files?gameVersion={}&modLoaderType={loader_type}&index=0&pageSize={}",
            numeric(id)?,
            encode(&target.minecraft),
            limits::VERSIONS.min(50),
        );
        let raw: RawList<RawFile> = self.get_json(&path, ctx)?;

        // The project is fetched for one fact: whether its author allows
        // third-party downloads. A file list that offered downloads the API
        // will not serve is a list of buttons that fail.
        let project: RawOne<RawMod> =
            self.get_json(&format!("{API}/mods/{}", numeric(id)?), ctx)?;
        let distribution = project.data.distribution();

        let mut files: Vec<ModFile> = raw
            .data
            .into_iter()
            .filter_map(|file| file.into_file(id, &distribution))
            .filter(|file| file.supports(target))
            .collect();
        // Releases first, then betas, then alphas; newest within each, which is
        // the order CurseForge already returns.
        files.sort_by_key(|file| file.channel);
        files.truncate(limits::VERSIONS);
        Ok(files)
    }

    fn file(
        &self,
        project: &str,
        version_id: &str,
        ctx: ProviderContext<'_>,
    ) -> Result<ModFile, BrowseError> {
        let path = format!(
            "{API}/mods/{}/files/{}",
            numeric(project)?,
            numeric(version_id)?
        );
        let raw: RawOne<RawFile> = self.get_json(&path, ctx)?;
        let details: RawOne<RawMod> =
            self.get_json(&format!("{API}/mods/{}", numeric(project)?), ctx)?;
        raw.data
            .into_file(project, &details.data.distribution())
            .ok_or_else(|| {
                BrowseError::Refused(
                    "that CurseForge file has no published SHA-1, so Fathom will not install it"
                        .to_owned(),
                )
            })
    }
}

/// `CurseForge` ids are numbers. Anything else never becomes part of a path.
fn numeric(id: &str) -> Result<String, BrowseError> {
    let trimmed = id.trim();
    if trimmed.is_empty() || trimmed.len() > 12 || !trimmed.chars().all(|c| c.is_ascii_digit()) {
        return Err(BrowseError::Refused(format!(
            "{trimmed:?} is not a CurseForge project id"
        )));
    }
    Ok(trimmed.to_owned())
}

/// Percent-encode a query-string value, conservatively.
fn encode(value: &str) -> String {
    use std::fmt::Write as _;

    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(char::from(byte));
            }
            other => {
                let _ = write!(out, "%{other:02X}");
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct RawSearch {
    #[serde(default)]
    data: Vec<RawMod>,
    #[serde(default)]
    pagination: RawPagination,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawPagination {
    #[serde(default)]
    total_count: u64,
}

#[derive(Debug, Deserialize)]
struct RawOne<T> {
    data: T,
}

#[derive(Debug, Deserialize)]
struct RawList<T> {
    #[serde(default = "Vec::new")]
    data: Vec<T>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawMod {
    #[serde(default)]
    id: u64,
    #[serde(default)]
    name: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    download_count: f64,
    #[serde(default)]
    links: RawLinks,
    #[serde(default)]
    categories: Vec<RawCategory>,
    #[serde(default)]
    authors: Vec<RawAuthor>,
    #[serde(default)]
    logo: Option<RawImage>,
    #[serde(default)]
    screenshots: Vec<RawImage>,
    #[serde(default)]
    latest_files: Vec<RawFile>,
    #[serde(default)]
    date_modified: Option<String>,
    /// `None` when the API did not say. Only an explicit `false` withholds.
    #[serde(default)]
    allow_mod_distribution: Option<bool>,
    #[serde(default)]
    is_available: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(clippy::struct_field_names)] // The API's names.
struct RawLinks {
    #[serde(default)]
    website_url: Option<String>,
    #[serde(default)]
    source_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawCategory {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
struct RawAuthor {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawImage {
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    thumbnail_url: Option<String>,
    #[serde(default)]
    title: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawFile {
    #[serde(default)]
    id: u64,
    #[serde(default)]
    display_name: String,
    #[serde(default)]
    file_name: String,
    #[serde(default)]
    release_type: u8,
    #[serde(default)]
    hashes: Vec<RawHash>,
    #[serde(default)]
    file_date: Option<String>,
    #[serde(default)]
    file_length: Option<u64>,
    #[serde(default)]
    download_url: Option<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    dependencies: Vec<RawRelation>,
}

#[derive(Debug, Deserialize)]
struct RawHash {
    #[serde(default)]
    value: String,
    /// 1 is SHA-1, 2 is MD5. Only the first is a hash this launcher verifies
    /// against, and a file with only the second is treated as unhashed.
    #[serde(default)]
    algo: u8,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawRelation {
    #[serde(default)]
    mod_id: u64,
    /// 1 embedded, 2 optional, 3 required, 4 tool, 5 incompatible, 6 include.
    #[serde(default)]
    relation_type: u8,
}

impl RawMod {
    /// Whether the author allows third-party applications to fetch the files.
    fn distribution(&self) -> Distribution {
        if self.allow_mod_distribution == Some(false) {
            Distribution::ForbiddenByAuthor {
                where_to_get: self.page_url(),
            }
        } else {
            Distribution::Allowed
        }
    }

    /// The project page.
    ///
    /// Taken from the API only when it is a `CurseForge` page; otherwise built
    /// from the slug. A link field from an index is a link an index controls,
    /// and this one ends up in front of a user.
    fn page_url(&self) -> String {
        let from_api = self
            .links
            .website_url
            .as_deref()
            .map(str::trim)
            .filter(|url| url.starts_with("https://www.curseforge.com/"))
            .map(str::to_owned);
        from_api.unwrap_or_else(|| {
            format!(
                "https://www.curseforge.com/minecraft/mc-mods/{}",
                sanitise_slug(&self.slug)
            )
        })
    }

    fn into_project(self) -> Option<ModProject> {
        if self.id == 0 || self.name.is_empty() {
            return None;
        }
        // `isAvailable: false` is CurseForge's word for a project that is
        // hidden or under review. Offering it would be offering something that
        // cannot be fetched.
        if self.is_available == Some(false) {
            return None;
        }
        let distribution = self.distribution();
        let project_url = self.page_url();

        let (minecraft_versions, loaders) = facets_of(
            self.latest_files
                .iter()
                .flat_map(|file| file.game_versions.iter().map(String::as_str)),
        );

        let icon = self.logo.as_ref().and_then(|logo| {
            logo.thumbnail_url
                .as_deref()
                .or(logo.url.as_deref())
                .and_then(|url| ImageRef::new(url, None))
        });
        let gallery = self
            .screenshots
            .iter()
            .filter_map(|shot| {
                shot.url
                    .as_deref()
                    .or(shot.thumbnail_url.as_deref())
                    .and_then(|url| ImageRef::new(url, shot.title.clone()))
            })
            .take(limits::LIST)
            .collect();

        Some(ModProject {
            reference: ProjectRef::new(Provider::CurseForge, self.id.to_string()),
            slug: sanitise_slug(&self.slug),
            name: clip(&self.name, limits::NAME),
            summary: clip(&self.summary, limits::SUMMARY),
            description: None,
            authors: self
                .authors
                .iter()
                .map(|author| clip(&author.name, limits::NAME))
                .filter(|name| !name.is_empty())
                .take(limits::LIST)
                .collect(),
            icon,
            gallery,
            // The API reports downloads as a float. Clamped rather than cast
            // blindly: a negative or absurd value is not a count.
            #[allow(
                clippy::cast_possible_truncation,
                clippy::cast_sign_loss,
                clippy::cast_precision_loss
            )]
            downloads: self.download_count.clamp(0.0, u64::MAX as f64) as u64,
            categories: self
                .categories
                .iter()
                .map(|category| clip(&category.name, limits::NAME))
                .filter(|name| !name.is_empty())
                .take(limits::LIST)
                .collect(),
            project_url,
            source_url: safe_link(self.links.source_url.as_deref()),
            issues_url: safe_link(self.links.issues_url.as_deref()),
            // CurseForge's API does not publish a licence field. Saying
            // "unknown" is the truth; inferring one from the project page would
            // be inventing a legal fact.
            license: License::Unknown,
            minecraft_versions,
            loaders,
            updated: self.date_modified.filter(|when| !when.is_empty()),
            distribution,
            detailed: false,
        })
    }
}

impl ModProject {
    /// Mark a project as fully fetched. The `CurseForge` project document is
    /// the same shape as a search hit, so this is what distinguishes them.
    fn detailed(mut self) -> Self {
        self.detailed = true;
        self
    }
}

impl RawFile {
    fn into_file(self, project_id: &str, distribution: &Distribution) -> Option<ModFile> {
        if !safe_file_name(&self.file_name) {
            tracing::warn!(
                file = %clip(&self.file_name, 60),
                "CurseForge offered a file name this launcher will not write"
            );
            return None;
        }
        // Only SHA-1: it is what the downloader verifies against, and a file we
        // cannot verify is a file we will not install.
        let sha1 = self
            .hashes
            .iter()
            .find(|hash| hash.algo == 1)
            .map(|hash| hash.value.clone())?;
        let hash = ArtifactHash::new(sha1, self.file_length)?;

        let download = match (&self.download_url, distribution) {
            (_, Distribution::ForbiddenByAuthor { where_to_get }) => FileDownload::Withheld {
                where_to_get: where_to_get.clone(),
            },
            (Some(url), Distribution::Allowed) if !url.is_empty() => {
                // A URL from the index is checked against the allowlist here,
                // not at download time, so a file that could never be fetched
                // is never offered as one that can.
                match descriptor_from_url(url, Purpose::DownloadMod, Feature::ModBrowse) {
                    Ok(_) => FileDownload::Url {
                        url: clip(url, limits::URL),
                    },
                    Err(_) => FileDownload::Withheld {
                        where_to_get: String::new(),
                    },
                }
            }
            // No URL and no explicit refusal is still no URL: CurseForge omits
            // it for files it will not serve to third parties.
            (_, Distribution::Allowed) => FileDownload::Withheld {
                where_to_get: String::new(),
            },
        };

        let (game_versions, loaders) = facets_of(self.game_versions.iter().map(String::as_str));

        let dependencies = self
            .dependencies
            .iter()
            .filter_map(|relation| {
                let kind = match relation.relation_type {
                    1 => DependencyKind::Embedded,
                    2 => DependencyKind::Optional,
                    3 => DependencyKind::Required,
                    5 => DependencyKind::Incompatible,
                    // 4 is a tool and 6 is an include; neither is a mod this
                    // launcher would install, and inventing a requirement from
                    // one would install something nobody asked for.
                    _ => return None,
                };
                (relation.mod_id != 0).then(|| FileDependency {
                    kind,
                    project: Some(ProjectRef::new(
                        Provider::CurseForge,
                        relation.mod_id.to_string(),
                    )),
                    version_id: None,
                })
            })
            .take(limits::DEPENDENCIES)
            .collect();

        Some(ModFile {
            project: ProjectRef::new(Provider::CurseForge, project_id.to_owned()),
            version_id: self.id.to_string(),
            number: clip(&self.display_name, limits::NAME),
            name: clip(&self.display_name, limits::NAME),
            channel: match self.release_type {
                2 => ReleaseChannel::Beta,
                3 => ReleaseChannel::Alpha,
                _ => ReleaseChannel::Release,
            },
            loaders,
            game_versions,
            file_name: self.file_name,
            download,
            hash,
            dependencies,
            published: self.file_date.filter(|when| !when.is_empty()),
        })
    }
}

/// Split `CurseForge`'s one list of "game versions" into versions and loaders.
///
/// `["1.8.9", "Fabric", "Client"]` is how a 1.8.9 Fabric mod arrives. The
/// loader names are mapped into Fathom's vocabulary, and a Fabric tag on a
/// pre-1.14 file becomes `legacy-fabric`: that is what such a mod actually
/// needs, and `CurseForge` has no separate tag for it.
fn facets_of<'a>(values: impl Iterator<Item = &'a str>) -> (BTreeSet<String>, BTreeSet<String>) {
    let mut versions: BTreeSet<String> = BTreeSet::new();
    let mut raw_loaders: BTreeSet<String> = BTreeSet::new();
    for value in values.take(limits::FACETS) {
        let trimmed = value.trim();
        if trimmed.is_empty() || trimmed.len() > 40 {
            continue;
        }
        if looks_like_a_minecraft_version(trimmed) {
            versions.insert(trimmed.to_owned());
        } else {
            raw_loaders.insert(trimmed.to_ascii_lowercase());
        }
    }

    let legacy = versions.iter().any(|version| is_pre_1_14(version));
    let mut loaders: BTreeSet<String> = BTreeSet::new();
    for loader in &raw_loaders {
        match loader.as_str() {
            "fabric" => {
                if legacy {
                    loaders.insert("legacy-fabric".to_owned());
                } else {
                    loaders.insert("fabric".to_owned());
                }
            }
            "forge" => {
                loaders.insert("forge".to_owned());
            }
            "neoforge" | "quilt" | "liteloader" | "rift" | "risugami's modloader" => {
                loaders.insert(loader.clone());
            }
            // "Client", "Server" and the rest are environment tags, not
            // loaders, and are not kept as either.
            _ => {}
        }
    }
    (versions, loaders)
}

fn looks_like_a_minecraft_version(value: &str) -> bool {
    let mut parts = value.split('.');
    let Some(first) = parts.next() else {
        return false;
    };
    if first.is_empty() || !first.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    let Some(second) = parts.next() else {
        return false;
    };
    !second.is_empty()
        && second.chars().all(|c| c.is_ascii_digit())
        && parts.next().is_none_or(|third| {
            !third.is_empty() && third.chars().all(|c| c.is_ascii_alphanumeric())
        })
}

/// Whether a Minecraft version predates Fabric proper, which is 1.14.
fn is_pre_1_14(version: &str) -> bool {
    let mut parts = version.split('.');
    let major: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    let minor: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    major == 1 && minor < 14
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn parse_mod(json: &str) -> Option<ModProject> {
        serde_json::from_str::<RawMod>(json)
            .expect("the fixture parses")
            .into_project()
    }

    fn parse_file(json: &str, distribution: &Distribution) -> Option<ModFile> {
        serde_json::from_str::<RawFile>(json)
            .expect("the fixture parses")
            .into_file("1234", distribution)
    }

    #[test]
    fn a_key_that_could_forge_a_header_is_not_a_key() {
        for hostile in [
            "abc\r\nx-evil: 1",
            "abc\ndef",
            "abc def",
            "",
            "   ",
            &"k".repeat(MAX_KEY_BYTES + 1),
        ] {
            assert!(clean_key(hostile).is_none(), "{hostile:?} must be refused");
        }
        assert_eq!(
            clean_key("  $2a$10$abcdef  ").as_deref(),
            Some("$2a$10$abcdef")
        );
    }

    #[test]
    fn without_a_key_the_provider_says_so_instead_of_failing_requests() {
        let http = crate::network::HttpClient::new(
            crate::network::NetworkPolicy::default(),
            crate::network::NetworkJournal::new(),
            "test",
        );
        let provider = CurseForgeProvider::new(http, CurseForgeConfig::unconfigured());
        match provider.availability() {
            Availability::NotConfigured { reason } => {
                assert!(reason.contains(KEY_FILE));
                assert!(
                    !reason.contains("api-key:"),
                    "no key material in the advice"
                );
            }
            other @ Availability::Ready => panic!("expected NotConfigured, got {other:?}"),
        }
    }

    #[test]
    fn an_author_who_forbids_distribution_gets_a_link_and_not_a_download() {
        let project = parse_mod(
            r#"{"id":1234,"name":"Some Mod","slug":"some-mod","summary":"s",
                "allowModDistribution":false,
                "links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/some-mod"}}"#,
        )
        .expect("a project");
        match &project.distribution {
            Distribution::ForbiddenByAuthor { where_to_get } => {
                assert!(where_to_get.contains("curseforge.com"));
            }
            other @ Distribution::Allowed => {
                panic!("expected the author's opt-out to be honoured, got {other:?}")
            }
        }

        let file = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"some-mod-1.0.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://edge.forgecdn.net/files/1/2/some-mod-1.0.jar",
                "gameVersions":["1.8.9","Fabric"]}"#,
            &project.distribution,
        )
        .expect("a file");
        assert!(
            !file.is_downloadable(),
            "a URL must not be used when the author opted out"
        );
    }

    #[test]
    fn a_file_with_no_sha1_is_never_offered() {
        let without = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"m.jar","releaseType":1,
                "hashes":[{"value":"d41d8cd98f00b204e9800998ecf8427e","algo":2}],
                "downloadUrl":"https://edge.forgecdn.net/files/1/2/m.jar",
                "gameVersions":["1.8.9","Fabric"]}"#,
            &Distribution::Allowed,
        );
        assert!(
            without.is_none(),
            "MD5 alone is not a hash we verify against"
        );
    }

    #[test]
    fn a_hostile_file_name_is_refused_before_it_can_become_a_path() {
        let hostile = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"../../evil.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://edge.forgecdn.net/files/1/2/evil.jar",
                "gameVersions":["1.8.9","Fabric"]}"#,
            &Distribution::Allowed,
        );
        assert!(hostile.is_none());
    }

    #[test]
    fn a_download_url_off_the_allowlist_becomes_a_link_rather_than_a_fetch() {
        let file = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"m.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://evil.example/m.jar",
                "gameVersions":["1.8.9","Fabric"]}"#,
            &Distribution::Allowed,
        )
        .expect("a file");
        assert!(!file.is_downloadable());
    }

    #[test]
    fn fabric_on_an_old_minecraft_version_means_legacy_fabric() {
        let file = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"m.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://mediafilez.forgecdn.net/files/1/2/m.jar",
                "gameVersions":["1.8.9","Fabric","Client"]}"#,
            &Distribution::Allowed,
        )
        .expect("a file");
        assert!(file.loaders.contains("legacy-fabric"));
        assert!(
            !file.loaders.contains("client"),
            "an environment tag is not a loader"
        );
        assert!(file.game_versions.contains("1.8.9"));
        assert!(file.supports(&Target::new("1.8.9", Loader::LegacyFabric)));
        assert!(file.is_downloadable());
    }

    #[test]
    fn fabric_on_a_modern_version_stays_modern_fabric() {
        let file = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"m.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://mediafilez.forgecdn.net/files/1/2/m.jar",
                "gameVersions":["1.20.1","Fabric"]}"#,
            &Distribution::Allowed,
        )
        .expect("a file");
        assert!(file.loaders.contains("fabric"));
        assert!(!file.supports(&Target::new("1.8.9", Loader::LegacyFabric)));
    }

    #[test]
    fn dependencies_are_mapped_and_tools_are_not_invented_into_requirements() {
        let file = parse_file(
            r#"{"id":9,"displayName":"1.0","fileName":"m.jar","releaseType":1,
                "hashes":[{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","algo":1}],
                "downloadUrl":"https://edge.forgecdn.net/files/1/2/m.jar",
                "gameVersions":["1.8.9","Fabric"],
                "dependencies":[{"modId":10,"relationType":3},{"modId":11,"relationType":2},
                                {"modId":12,"relationType":5},{"modId":13,"relationType":1},
                                {"modId":14,"relationType":4},{"modId":15,"relationType":6}]}"#,
            &Distribution::Allowed,
        )
        .expect("a file");
        let kinds: Vec<DependencyKind> = file.dependencies.iter().map(|d| d.kind).collect();
        assert_eq!(
            kinds,
            vec![
                DependencyKind::Required,
                DependencyKind::Optional,
                DependencyKind::Incompatible,
                DependencyKind::Embedded
            ]
        );
    }

    #[test]
    fn a_project_page_link_from_the_index_is_only_used_when_it_is_a_curseforge_page() {
        let hijacked = parse_mod(
            r#"{"id":1,"name":"M","slug":"m","summary":"",
                "links":{"websiteUrl":"https://evil.example/phish"}}"#,
        )
        .expect("a project");
        assert_eq!(
            hijacked.project_url,
            "https://www.curseforge.com/minecraft/mc-mods/m"
        );

        let honest = parse_mod(
            r#"{"id":1,"name":"M","slug":"m","summary":"",
                "links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/m"}}"#,
        )
        .expect("a project");
        assert_eq!(
            honest.project_url,
            "https://www.curseforge.com/minecraft/mc-mods/m"
        );
    }

    #[test]
    fn a_hidden_project_is_not_offered() {
        assert!(parse_mod(r#"{"id":1,"name":"M","slug":"m","isAvailable":false}"#).is_none());
    }

    #[test]
    fn a_project_id_is_a_number_and_nothing_else_reaches_a_path() {
        assert!(numeric("1234").is_ok());
        for hostile in ["../1", "1/2", "abc", "", "1 2", "99999999999999"] {
            assert!(numeric(hostile).is_err(), "{hostile:?} must be refused");
        }
        assert!(numeric(" 1234 ").is_ok(), "surrounding space is trimmed");
    }

    #[test]
    fn a_version_string_is_told_apart_from_a_loader_name() {
        assert!(looks_like_a_minecraft_version("1.8.9"));
        assert!(looks_like_a_minecraft_version("1.20"));
        assert!(!looks_like_a_minecraft_version("Fabric"));
        assert!(!looks_like_a_minecraft_version("Client"));
        assert!(is_pre_1_14("1.8.9"));
        assert!(!is_pre_1_14("1.20.1"));
    }
}
