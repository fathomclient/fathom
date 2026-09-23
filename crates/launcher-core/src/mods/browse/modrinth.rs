//! Modrinth, as a [`CatalogProvider`].
//!
//! The wire client lives in [`crate::mods::modrinth`] and predates the browser:
//! the profile's "add a mod" flow and the component installer already use it.
//! This file does not talk to Modrinth. It translates what that client returns
//! into the unified model, so nothing past this line knows what a Modrinth
//! `Hit` looks like — which is the rule that lets `CurseForge` sit beside it.
//!
//! Everything Modrinth sends is untrusted. Names and summaries are clipped,
//! slugs are reduced to slug characters, image and download URLs have to name
//! an allowlisted host, file names have to be safe jar names, and a file with
//! no SHA-1 is not a file.

use super::model::{
    clip, limits, safe_file_name, safe_link, sanitise_slug, DependencyKind, Distribution,
    FileDependency, FileDownload, ImageRef, ModFile, ModProject, ProjectRef, Provider,
    ReleaseChannel, Target,
};
use super::provider::{
    Availability, BrowseError, CatalogProvider, ProviderContext, SearchPage, SearchQuery, Sort,
};
use crate::mods::definition::License;
use crate::mods::modrinth::{
    Channel, DependencyType, Hit, Modrinth, ModrinthError, Project, SearchOptions, Version,
};
use crate::network::{descriptor_from_url, Feature, HttpClient, Purpose};

/// The Modrinth adapter. Cheap to clone; holds nothing but the client.
#[derive(Debug, Clone)]
pub struct ModrinthProvider {
    client: Modrinth,
}

impl ModrinthProvider {
    pub fn new(http: HttpClient) -> Self {
        Self {
            client: Modrinth::new(http),
        }
    }

    fn index(sort: Sort) -> &'static str {
        match sort {
            // Modrinth has no alphabetical index. Sorting one page locally would
            // produce an order that changes from page to page, which is worse
            // than admitting the index does not offer it.
            Sort::Relevance | Sort::Name => "relevance",
            Sort::Downloads => "downloads",
            Sort::Updated => "updated",
            Sort::Newest => "newest",
        }
    }
}

/// Map the wire client's error onto the browser's.
fn browse_error(error: ModrinthError) -> BrowseError {
    let provider = Provider::Modrinth;
    match error {
        ModrinthError::Network(network) => BrowseError::from_network(provider, &network),
        ModrinthError::Status { status } => BrowseError::from_status(provider, status, None),
        ModrinthError::Malformed(detail) => BrowseError::Malformed {
            provider,
            detail: clip(&detail, 200),
        },
        ModrinthError::UntrustedHost(host) => BrowseError::Refused(format!(
            "{} is not on the download allowlist",
            clip(&host, 80)
        )),
        ModrinthError::NoCompatibleVersion { name, minecraft } => {
            BrowseError::Incompatible(format!(
                "{} has no build for Minecraft {}",
                clip(&name, 80),
                clip(&minecraft, 20)
            ))
        }
        ModrinthError::Store(store) => BrowseError::Refused(store.to_string()),
    }
}

/// Modrinth ids are base62. Refusing anything else keeps a hostile id from
/// becoming part of a request path, even though the client encodes it too.
fn project_id(id: &str) -> Result<&str, BrowseError> {
    let id = id.trim();
    if id.is_empty()
        || id.len() > 64
        || !id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err(BrowseError::Refused(format!(
            "{:?} is not a Modrinth project id",
            clip(id, 40)
        )));
    }
    Ok(id)
}

fn licence(id: Option<&str>) -> License {
    match id {
        Some(id) if !id.is_empty() && !id.starts_with("LicenseRef") && id.len() <= 64 => {
            License::Spdx { id: id.to_owned() }
        }
        _ => License::Unknown,
    }
}

fn capped(values: impl IntoIterator<Item = String>, max: usize) -> Vec<String> {
    values
        .into_iter()
        .filter(|value| !value.is_empty())
        .take(max)
        .map(|value| clip(&value, limits::NAME))
        .collect()
}

fn page_for(slug: &str, id: &str) -> String {
    let slug = sanitise_slug(slug);
    if slug.is_empty() {
        format!("https://modrinth.com/mod/{}", sanitise_slug(id))
    } else {
        format!("https://modrinth.com/mod/{slug}")
    }
}

pub(super) fn from_hit(hit: Hit) -> Option<ModProject> {
    let id = project_id(&hit.project_id).ok()?.to_owned();
    if hit.title.trim().is_empty() {
        return None;
    }
    Some(ModProject {
        reference: ProjectRef::new(Provider::Modrinth, id.clone()),
        project_url: page_for(&hit.slug, &id),
        slug: sanitise_slug(&hit.slug),
        name: clip(hit.title.trim(), limits::NAME),
        summary: clip(&hit.summary, limits::SUMMARY),
        description: None,
        authors: capped(hit.author, limits::LIST),
        icon: hit
            .icon_url
            .as_deref()
            .and_then(|url| ImageRef::new(url, None)),
        gallery: Vec::new(),
        downloads: hit.downloads,
        categories: capped(hit.categories, limits::LIST),
        source_url: None,
        issues_url: None,
        license: licence(hit.license.as_deref()),
        minecraft_versions: hit.game_versions.into_iter().take(limits::FACETS).collect(),
        loaders: hit.loaders.into_iter().take(limits::FACETS).collect(),
        updated: hit.updated.map(|when| clip(&when, 40)),
        distribution: Distribution::Allowed,
        detailed: false,
    })
}

pub(super) fn from_project(project: Project, authors: Vec<String>) -> Option<ModProject> {
    let id = project_id(&project.id).ok()?.to_owned();
    if project.title.trim().is_empty() {
        return None;
    }
    Some(ModProject {
        reference: ProjectRef::new(Provider::Modrinth, id.clone()),
        project_url: page_for(&project.slug, &id),
        slug: sanitise_slug(&project.slug),
        name: clip(project.title.trim(), limits::NAME),
        summary: clip(&project.summary, limits::SUMMARY),
        // Markdown, rendered by the interface as text. Never as HTML.
        description: Some(clip(&project.body, limits::DESCRIPTION)).filter(|body| !body.is_empty()),
        authors: capped(authors, limits::LIST),
        icon: project
            .icon_url
            .as_deref()
            .and_then(|url| ImageRef::new(url, None)),
        gallery: project
            .gallery
            .into_iter()
            .filter_map(|image| ImageRef::new(&image.url, image.title))
            .take(limits::LIST)
            .collect(),
        downloads: project.downloads,
        categories: capped(project.categories, limits::LIST),
        source_url: safe_link(project.source_url.as_deref()),
        issues_url: safe_link(project.issues_url.as_deref()),
        license: licence(project.license.as_deref()),
        minecraft_versions: project
            .game_versions
            .into_iter()
            .take(limits::FACETS)
            .collect(),
        loaders: project.loaders.into_iter().take(limits::FACETS).collect(),
        updated: project.updated.map(|when| clip(&when, 40)),
        distribution: Distribution::Allowed,
        detailed: true,
    })
}

pub(super) fn from_version(version: Version) -> Option<ModFile> {
    let project = project_id(&version.project_id).ok()?.to_owned();
    if !safe_file_name(&version.file_name) {
        tracing::warn!(
            file = %clip(&version.file_name, 60),
            "Modrinth offered a file name that is not a safe jar name; skipped"
        );
        return None;
    }
    // The URL has to be one we would fetch. Checked here so the interface does
    // not offer an install the downloader would then refuse.
    let download =
        if descriptor_from_url(&version.url, Purpose::DownloadMod, Feature::ModBrowse).is_ok() {
            FileDownload::Url { url: version.url }
        } else {
            tracing::warn!(
                "Modrinth offered a download off the allowlist; the file is not installable"
            );
            FileDownload::Withheld {
                where_to_get: page_for("", &project),
            }
        };
    let dependencies = version
        .dependencies
        .into_iter()
        .take(limits::DEPENDENCIES)
        .map(|dependency| FileDependency {
            kind: match dependency.kind {
                DependencyType::Required => DependencyKind::Required,
                DependencyType::Optional => DependencyKind::Optional,
                DependencyType::Incompatible => DependencyKind::Incompatible,
                DependencyType::Embedded => DependencyKind::Embedded,
            },
            project: dependency
                .project_id
                .as_deref()
                .and_then(|id| project_id(id).ok())
                .map(|id| ProjectRef::new(Provider::Modrinth, id)),
            version_id: dependency.version_id.filter(|id| project_id(id).is_ok()),
        })
        .collect();
    Some(ModFile {
        project: ProjectRef::new(Provider::Modrinth, project),
        version_id: version.version_id,
        number: clip(&version.number, limits::NAME),
        name: clip(&version.name, limits::NAME),
        channel: match version.channel {
            Channel::Release => ReleaseChannel::Release,
            Channel::Beta => ReleaseChannel::Beta,
            Channel::Alpha => ReleaseChannel::Alpha,
        },
        loaders: version.loaders.into_iter().take(limits::FACETS).collect(),
        game_versions: version
            .game_versions
            .into_iter()
            .take(limits::FACETS)
            .collect(),
        file_name: version.file_name,
        download,
        hash: version.hash,
        dependencies,
        published: version.published.map(|when| clip(&when, 40)),
    })
}

impl CatalogProvider for ModrinthProvider {
    fn provider(&self) -> Provider {
        Provider::Modrinth
    }

    /// Modrinth needs no key. Whether the user allowed mod repositories at all
    /// is the catalogue's check, and applies to both providers equally.
    fn availability(&self) -> Availability {
        Availability::Ready
    }

    fn search(
        &self,
        query: &SearchQuery,
        ctx: ProviderContext<'_>,
    ) -> Result<SearchPage, BrowseError> {
        ctx.check()?;
        let search = self
            .client
            .search_with(
                &SearchOptions {
                    query: query.text.trim(),
                    loader: query.target.loader,
                    minecraft: &query.target.minecraft,
                    offset: query.offset,
                    limit: query.limit,
                    category: query.category.as_deref(),
                    index: Self::index(query.sort),
                    compatible_only: !query.include_incompatible,
                },
                ctx.grant,
            )
            .map_err(browse_error)?;
        ctx.check()?;
        Ok(SearchPage {
            total: search.total,
            offset: query.offset,
            hits: search.hits.into_iter().filter_map(from_hit).collect(),
        })
    }

    fn project(&self, id: &str, ctx: ProviderContext<'_>) -> Result<ModProject, BrowseError> {
        ctx.check()?;
        let id = project_id(id)?;
        let project = self.client.project(id, ctx.grant).map_err(browse_error)?;
        ctx.check()?;
        // Authorship is a second request. Losing it is not losing the project,
        // so a failure here leaves the list empty rather than failing the page.
        let authors = match self.client.members(id, ctx.grant) {
            Ok(authors) => authors,
            Err(error) => {
                tracing::debug!(error = %browse_error(error), "Modrinth members unavailable");
                Vec::new()
            }
        };
        from_project(project, authors).ok_or_else(|| BrowseError::Malformed {
            provider: Provider::Modrinth,
            detail: "a project with no usable name or id".to_owned(),
        })
    }

    fn files(
        &self,
        id: &str,
        target: &Target,
        ctx: ProviderContext<'_>,
    ) -> Result<Vec<ModFile>, BrowseError> {
        ctx.check()?;
        if !target.loads_mods() {
            return Ok(Vec::new());
        }
        let id = project_id(id)?;
        let versions = self
            .client
            .versions(id, target.loader, &target.minecraft, ctx.grant)
            .map_err(browse_error)?;
        Ok(versions
            .into_iter()
            .take(limits::VERSIONS)
            .filter_map(from_version)
            // The client filtered already; this is the unified model's rule,
            // checked again because the two are allowed to disagree.
            .filter(|file| file.supports(target))
            .collect())
    }

    fn file(
        &self,
        project: &str,
        version_id: &str,
        ctx: ProviderContext<'_>,
    ) -> Result<ModFile, BrowseError> {
        ctx.check()?;
        let project = project_id(project)?;
        let version_id = project_id(version_id)?;
        let version = self
            .client
            .version_by_id(version_id, ctx.grant)
            .map_err(browse_error)?;
        let file = from_version(version).ok_or_else(|| BrowseError::Malformed {
            provider: Provider::Modrinth,
            detail: "that build has no installable file".to_owned(),
        })?;
        // A version id belongs to exactly one project. An answer for a
        // different one is either a mistake or an attempt to swap a dependency.
        if file.project.id != project {
            return Err(BrowseError::Malformed {
                provider: Provider::Modrinth,
                detail: "that build belongs to a different project".to_owned(),
            });
        }
        Ok(file)
    }

    fn lookup_by_hash(
        &self,
        sha1: &str,
        ctx: ProviderContext<'_>,
    ) -> Result<Option<(ModProject, ModFile)>, BrowseError> {
        ctx.check()?;
        let Some(version) = self.client.by_hash(sha1, ctx.grant).map_err(browse_error)? else {
            return Ok(None);
        };
        let Some(file) = from_version(version) else {
            return Ok(None);
        };
        // The index says these bytes are that file. Believe it only if the file
        // it names has the hash we asked about.
        if !file.hash.sha1.eq_ignore_ascii_case(sha1) {
            return Ok(None);
        }
        let project = self.project(&file.project.id, ctx)?;
        Ok(Some((project, file)))
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
mod tests {
    use super::*;
    use crate::mods::definition::ArtifactHash;
    use crate::mods::modrinth::VersionDependency;
    use crate::profiles::Loader;

    fn version(file_name: &str, url: &str) -> Version {
        Version {
            project_id: "AANobbMI".into(),
            version_id: "v1".into(),
            number: "1.0.0".into(),
            name: "One".into(),
            channel: Channel::Release,
            loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
            game_versions: ["1.8.9".to_owned()].into_iter().collect(),
            file_name: file_name.into(),
            url: url.into(),
            hash: ArtifactHash::new("a".repeat(40), Some(10)).unwrap(),
            dependencies: vec![
                VersionDependency {
                    project_id: Some("P7dR8mSH".into()),
                    version_id: None,
                    kind: DependencyType::Required,
                },
                VersionDependency {
                    project_id: Some("../../etc".into()),
                    version_id: None,
                    kind: DependencyType::Required,
                },
            ],
            published: None,
        }
    }

    #[test]
    fn a_version_becomes_a_compatible_file_with_its_dependencies() {
        let file = from_version(version(
            "sodium-1.0.0.jar",
            "https://cdn.modrinth.com/data/AANobbMI/versions/v1/sodium-1.0.0.jar",
        ))
        .expect("a file");
        assert!(file.is_downloadable());
        assert!(file.supports(&Target::new("1.8.9", Loader::LegacyFabric)));
        assert!(!file.supports(&Target::new("1.12.2", Loader::LegacyFabric)));
        assert_eq!(file.dependencies.len(), 2);
        assert_eq!(
            file.dependencies[0].project,
            Some(ProjectRef::new(Provider::Modrinth, "P7dR8mSH"))
        );
        assert_eq!(
            file.dependencies[1].project, None,
            "a hostile project id is not followed"
        );
    }

    #[test]
    fn a_hostile_file_name_is_not_a_file() {
        assert!(from_version(version(
            "../../mods/evil.jar",
            "https://cdn.modrinth.com/data/x/evil.jar"
        ))
        .is_none());
        assert!(from_version(version(
            "evil.exe",
            "https://cdn.modrinth.com/data/x/evil.exe"
        ))
        .is_none());
    }

    #[test]
    fn a_download_off_the_allowlist_is_withheld_rather_than_fetched() {
        let file =
            from_version(version("ok.jar", "https://evil.example.com/ok.jar")).expect("a file");
        assert!(!file.is_downloadable());
    }

    #[test]
    fn a_hit_keeps_nothing_it_should_not() {
        let hit = Hit {
            project_id: "AANobbMI".into(),
            slug: "sodium/../../x".into(),
            title: "Sodium".into(),
            summary: "s".repeat(10_000),
            downloads: 5,
            license: Some("LicenseRef-Custom".into()),
            categories: vec!["optimization".into()],
            author: Some("jellysquid3".into()),
            icon_url: Some("javascript:alert(1)".into()),
            game_versions: vec!["1.8.9".into()],
            loaders: vec!["legacy-fabric".into()],
            updated: None,
        };
        let project = from_hit(hit).expect("a project");
        assert_eq!(project.slug, "sodiumx");
        assert_eq!(project.project_url, "https://modrinth.com/mod/sodiumx");
        assert!(project.summary.len() <= limits::SUMMARY + 4);
        assert!(project.icon.is_none());
        assert_eq!(project.license, License::Unknown);
        assert_eq!(project.authors, vec!["jellysquid3".to_owned()]);
    }

    #[test]
    fn project_ids_that_are_not_ids_are_refused() {
        assert!(project_id("AANobbMI").is_ok());
        for hostile in ["", "../x", "a/b", "a?b=c", "a b"] {
            assert!(project_id(hostile).is_err(), "{hostile:?}");
        }
    }

    #[test]
    fn statuses_become_structured_errors() {
        assert!(matches!(
            browse_error(ModrinthError::Status { status: 429 }),
            BrowseError::RateLimited { .. }
        ));
        assert!(matches!(
            browse_error(ModrinthError::Status { status: 404 }),
            BrowseError::NotFound { .. }
        ));
    }
}
