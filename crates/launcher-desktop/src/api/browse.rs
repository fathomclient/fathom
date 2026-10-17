//! The mod browser, as the interface sees it.
//!
//! # Off the interface thread, outside the lock
//!
//! Every command here can wait on the network, so every one is `async` and
//! does its work on a blocking worker (Tauri runs a plain command on the main
//! thread, which would freeze the window for as long as Modrinth takes to
//! answer). And none of them holds the launcher's mutex while it waits: each
//! takes what it needs out of the lock — the profile, the catalogue handle,
//! whether browsing is allowed — and lets go before the first request.
//!
//! # Installing in two passes
//!
//! An install is the one operation that has to change a profile, and the
//! profile lives behind that lock. So it runs twice:
//!
//! 1. **Fetch**, outside the lock, against a copy of the profile's mods. Every
//!    file is downloaded into the content-addressed store and checked against
//!    its published SHA-1. This is the slow half, and nothing it does is
//!    visible to the profile.
//! 2. **Record**, inside the lock, against the profile as it is *now*. The
//!    same plan is re-validated (the profile may have changed while we were
//!    downloading) and applied; every file is already in the store, so this is
//!    a hash check per file and a save.
//!
//! # What the interface is trusted with
//!
//! Nothing it sends is used as a URL, a path, or a decision. It names a
//! project by provider and id, a build by version id, and its consent with two
//! booleans; the plan is always rebuilt here from the index, the resolver's
//! verdict is always recomputed, and the installer re-checks both.

use std::collections::HashMap;
use std::sync::{Condvar, Mutex};

use base64::Engine as _;
use serde::{Deserialize, Serialize};
use tauri::{Emitter as _, Manager as _};

use launcher_core::mods::browse::compat::{self, Finding, ProjectVerdict, Verdict};
use launcher_core::mods::browse::dependencies::Blocker;
use launcher_core::mods::browse::model::{
    DependencyKind, Distribution, FileDownload, ImageRef, ReleaseChannel,
};
use launcher_core::mods::browse::{
    Breadth, BrowseError, CatalogEntry, Category, InstallOptions, InstallPlan, InstallProgress,
    InstallState, ModCatalog, ModProject, ProjectRef, Provider, ProviderStatus, SearchQuery, Sort,
    Target,
};
use launcher_core::mods::ModId;
use launcher_core::network::PerActionGrant;
use launcher_core::profiles::Profile;
use launcher_core::{Cancel, Launcher};

use crate::State;

// ---------------------------------------------------------------------------
// Worker plumbing
// ---------------------------------------------------------------------------

/// Cancellation for the browser's long-lived requests: one search and one
/// detail page at a time, and one handle per install so each can be stopped.
#[derive(Debug, Default)]
pub struct Slots {
    search: Mutex<Option<Cancel>>,
    detail: Mutex<Option<Cancel>>,
    installs: Mutex<HashMap<String, Cancel>>,
    /// Image fetches in flight. A page of forty icons should not be forty
    /// simultaneous connections.
    images: Gate,
}

impl Slots {
    /// Cancel whatever was in `slot` and put a fresh handle there.
    fn replace(slot: &Mutex<Option<Cancel>>) -> Cancel {
        let fresh = Cancel::new();
        if let Ok(mut current) = slot.lock() {
            if let Some(previous) = current.replace(fresh.clone()) {
                previous.cancel();
            }
        }
        fresh
    }
}

/// A counting semaphore: at most `LIMIT` holders at once.
#[derive(Debug, Default)]
struct Gate {
    held: Mutex<usize>,
    freed: Condvar,
}

struct Pass<'a>(&'a Gate);

impl Gate {
    const LIMIT: usize = 6;

    fn enter(&self) -> Option<Pass<'_>> {
        let mut held = self.held.lock().ok()?;
        while *held >= Self::LIMIT {
            held = self.freed.wait(held).ok()?;
        }
        *held += 1;
        Some(Pass(self))
    }
}

impl Drop for Pass<'_> {
    fn drop(&mut self) {
        if let Ok(mut held) = self.0.held.lock() {
            *held = held.saturating_sub(1);
        }
        self.0.freed.notify_one();
    }
}

/// What every command takes out of the lock before it lets go.
struct Prepared {
    catalog: ModCatalog,
    profile: Profile,
    target: Target,
}

fn prepare(app: &tauri::AppHandle, profile: Option<&str>) -> Result<Prepared, String> {
    let state = app.state::<State>();
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned".to_owned())?;
    if let Some(reason) = core.browsing_blocked() {
        return Err(reason.to_owned());
    }
    let profile = crate::commands::resolve_profile(&core, profile)?;
    Ok(Prepared {
        catalog: core.mod_catalog(),
        target: Target::of(&profile),
        profile,
    })
}

/// Run blocking catalogue work on a worker thread.
async fn worker<T: Send + 'static>(
    work: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(work)
        .await
        .map_err(|_| "the mod browser's worker stopped unexpectedly".to_owned())?
}

fn message(error: &BrowseError) -> String {
    error.user_message()
}

fn grant(reason: impl Into<String>) -> PerActionGrant {
    PerActionGrant::user_requested(reason.into())
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderView {
    pub id: &'static str,
    pub label: &'static str,
    pub ready: bool,
    /// Why it cannot be used, when it cannot.
    pub note: Option<String>,
}

fn provider_view(status: &ProviderStatus) -> ProviderView {
    ProviderView {
        id: status.provider.id(),
        label: status.provider.label(),
        ready: status.availability.is_ready(),
        note: match &status.availability {
            launcher_core::mods::browse::Availability::Ready => None,
            launcher_core::mods::browse::Availability::NotConfigured { reason } => {
                Some(reason.clone())
            }
        },
    }
}

/// What the page needs before it can search: no network.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowseContext {
    pub profile_id: String,
    pub profile_name: String,
    pub minecraft: String,
    pub loader: String,
    /// Whether this profile loads mods at all. A Vanilla profile does not.
    pub loads_mods: bool,
    /// Why browsing is off (offline, or mod repositories disabled).
    pub blocked: Option<String>,
    pub providers: Vec<ProviderView>,
}

pub fn context(core: &Launcher, profile: &Profile) -> BrowseContext {
    let target = Target::of(profile);
    BrowseContext {
        profile_id: profile.id.to_string(),
        profile_name: profile.display_name.clone(),
        minecraft: target.minecraft.clone(),
        loader: target.loader.label().to_owned(),
        loads_mods: target.loads_mods(),
        blocked: core.browsing_blocked().map(str::to_owned),
        providers: core
            .mod_catalog()
            .provider_statuses()
            .iter()
            .map(provider_view)
            .collect(),
    }
}

/// Modrinth's topics, for the category filter.
pub async fn categories(app: tauri::AppHandle) -> Result<Vec<Category>, String> {
    let prepared = prepare(&app, None)?;
    worker(move || {
        prepared
            .catalog
            .categories(
                Provider::Modrinth,
                &grant("listing mod categories"),
                &Cancel::new(),
            )
            .map_err(|error| message(&error))
    })
    .await
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchArgs {
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub sort: Sort,
    #[serde(default)]
    pub offset: usize,
    #[serde(default)]
    pub breadth: Breadth,
}

/// One result card.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EntryView {
    /// `modrinth:AABBCC`: the identity the interface keys rows by.
    pub key: String,
    pub reference: ProjectRef,
    pub provider_label: &'static str,
    pub slug: String,
    pub name: String,
    pub summary: String,
    pub authors: Vec<String>,
    /// The icon's URL, which the interface hands back to [`image`] to load.
    /// Never fetched by the WebView itself.
    pub icon: Option<String>,
    pub downloads: u64,
    pub categories: Vec<String>,
    pub updated: Option<String>,
    pub install: InstallState,
    /// The profile entry for this project, when it is installed, so the card
    /// can offer removal without a second lookup.
    pub installed_id: Option<String>,
    /// Set when the project's own facets already rule it out. A card with no
    /// reason here is *unchecked*, not compatible; [`verdicts`] answers that.
    pub ruled_out: Option<String>,
    pub possible_duplicate_of: Option<String>,
    pub also_on: Vec<String>,
}

fn installed_id(profile: &Profile, reference: &ProjectRef) -> Option<String> {
    profile
        .mods
        .iter()
        .find(|entry| {
            entry.origin.repository.as_deref().and_then(Provider::parse) == Some(reference.provider)
                && entry.origin.project.as_deref() == Some(reference.id.as_str())
        })
        .map(|entry| entry.id.to_string())
}

/// Loader names an index may list among a project's categories.
const LOADER_TAGS: &[&str] = &[
    "fabric",
    "legacy-fabric",
    "forge",
    "neoforge",
    "quilt",
    "ornithe",
    "babric",
    "liteloader",
    "modloader",
    "rift",
    "bta-babric",
    "java-agent",
    "nilloader",
];

fn entry_view(entry: CatalogEntry, profile: &Profile, target: &Target) -> EntryView {
    let project = entry.project;
    EntryView {
        key: project.reference.qualified(),
        provider_label: project.reference.provider.label(),
        slug: project.slug.clone(),
        name: project.name.clone(),
        summary: project.summary.clone(),
        authors: project.authors.clone(),
        icon: project.icon.as_ref().map(|icon| icon.url.clone()),
        downloads: project.downloads,
        // Modrinth files loaders among a hit's categories; they are not topics,
        // and showing "Ornithe" as a tag beside a verdict saying it cannot load
        // would contradict it.
        categories: project
            .categories
            .iter()
            .filter(|category| !LOADER_TAGS.contains(&category.to_ascii_lowercase().as_str()))
            .cloned()
            .collect(),
        updated: project.updated.clone(),
        installed_id: installed_id(profile, &project.reference),
        ruled_out: compat::rule_out(&project, target),
        possible_duplicate_of: entry.possible_duplicate_of,
        also_on: entry.also_on.iter().map(ProjectRef::qualified).collect(),
        install: entry.installed,
        reference: project.reference,
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderOutcome {
    pub id: &'static str,
    pub label: &'static str,
    pub configured: bool,
    /// What went wrong at this provider on this request, for a person.
    pub problem: Option<String>,
    pub retryable: bool,
    pub results: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchView {
    pub entries: Vec<EntryView>,
    pub providers: Vec<ProviderOutcome>,
    pub offset: usize,
    pub next_offset: usize,
    pub has_more: bool,
    /// True when this search was overtaken by a newer one. The interface drops
    /// the result rather than showing it.
    pub superseded: bool,
}

fn page_size() -> usize {
    24
}

pub async fn search(
    app: tauri::AppHandle,
    profile: Option<String>,
    args: SearchArgs,
) -> Result<SearchView, String> {
    let prepared = prepare(&app, profile.as_deref())?;
    let cancel = {
        let state = app.state::<State>();
        Slots::replace(&state.browse.search)
    };
    worker(move || {
        let Prepared {
            catalog,
            profile,
            target,
        } = prepared;
        let mut query = SearchQuery::new(target.clone())
            .with_text(args.text.chars().take(120).collect::<String>())
            .with_page(args.offset, page_size());
        query.sort = args.sort;
        query.breadth = args.breadth;
        query.category = args
            .category
            .filter(|category| !category.is_empty() && category.len() <= 40);
        if query.category.is_some() {
            // Categories are Modrinth's vocabulary. Sending one to CurseForge
            // would return its unfiltered results beside filtered ones.
            query = query.with_provider(Provider::Modrinth);
        }
        let reason = if query.text.trim().is_empty() {
            "browsing mods for a profile"
        } else {
            "searching mods"
        };
        let installed: Vec<_> = profile.mods.iter().cloned().collect();
        match catalog.search(
            profile.id.as_str(),
            &query,
            &installed,
            &grant(reason),
            &cancel,
        ) {
            Ok(page) => {
                let fetched: usize = page.providers.iter().map(|status| status.results).sum();
                Ok(SearchView {
                    providers: page
                        .providers
                        .iter()
                        .map(|status| ProviderOutcome {
                            id: status.provider.id(),
                            label: status.provider.label(),
                            configured: status.availability.is_ready(),
                            problem: status.error.as_ref().and_then(|error| {
                                (!matches!(error, BrowseError::NotConfigured { .. }))
                                    .then(|| message(error))
                            }),
                            retryable: status.error.as_ref().is_some_and(BrowseError::is_retryable),
                            results: status.results,
                        })
                        .collect(),
                    entries: page
                        .entries
                        .into_iter()
                        .map(|entry| entry_view(entry, &profile, &target))
                        .collect(),
                    offset: page.offset,
                    next_offset: page.offset + fetched.max(1).min(page_size()),
                    has_more: page.has_more,
                    superseded: false,
                })
            }
            Err(BrowseError::Cancelled) => Ok(SearchView {
                entries: Vec::new(),
                providers: Vec::new(),
                offset: args.offset,
                next_offset: args.offset,
                has_more: false,
                superseded: true,
            }),
            Err(error) => Err(message(&error)),
        }
    })
    .await
}

// ---------------------------------------------------------------------------
// Verdicts
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VerdictView {
    pub key: String,
    pub verdict: Option<ProjectVerdict>,
    /// Why there is no verdict, when the check itself failed.
    pub problem: Option<String>,
}

/// The resolver's verdict for a handful of cards, checked against each
/// project's actual builds. One version-list request per project, paced by
/// the provider's throttle and cached for ten minutes.
pub async fn verdicts(
    app: tauri::AppHandle,
    profile: Option<String>,
    projects: Vec<ProjectRef>,
) -> Result<Vec<VerdictView>, String> {
    let prepared = prepare(&app, profile.as_deref())?;
    // Tied to the search slot: a new search makes these answers moot.
    let cancel = {
        let state = app.state::<State>();
        state
            .browse
            .search
            .lock()
            .ok()
            .and_then(|slot| slot.clone())
            .unwrap_or_default()
    };
    worker(move || {
        let grant = grant("checking which mods fit this profile");
        let mut out = Vec::new();
        for reference in projects.into_iter().take(12) {
            if cancel.is_cancelled() {
                break;
            }
            let key = reference.qualified();
            match prepared
                .catalog
                .verdict(&reference, &prepared.target, &grant, &cancel)
            {
                Ok(verdict) => out.push(VerdictView {
                    key,
                    verdict: Some(verdict),
                    problem: None,
                }),
                Err(BrowseError::Cancelled) => break,
                Err(error) => out.push(VerdictView {
                    key,
                    verdict: None,
                    problem: Some(message(&error)),
                }),
            }
        }
        Ok(out)
    })
    .await
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/// The raster formats a card or a gallery shows. SVG is deliberately absent:
/// it is a document, not a picture, and nothing here needs one.
fn sniff(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if bytes.len() > 12 && bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP") {
        Some("image/webp")
    } else {
        None
    }
}

/// An icon or screenshot as a `data:` URL.
///
/// The URL must be one an index published and the allowlist accepts; it is
/// fetched once into the on-disk image cache and served from there after.
pub async fn image(
    app: tauri::AppHandle,
    provider: Provider,
    url: String,
) -> Result<String, String> {
    let prepared = prepare(&app, None)?;
    if ImageRef::new(&url, None).is_none() {
        return Err("that image is not from a source Fathom loads images from".to_owned());
    }
    let handle = app.clone();
    worker(move || {
        let state = handle.state::<State>();
        let _pass = state.browse.images.enter();
        let path = prepared
            .catalog
            .image(
                &url,
                provider,
                &grant("loading a mod's images"),
                &Cancel::new(),
            )
            .map_err(|error| message(&error))?;
        let bytes = std::fs::read(&path).map_err(|_| "the image could not be read".to_owned())?;
        if bytes.len() as u64 > launcher_core::mods::browse::cache::MAX_IMAGE_BYTES {
            return Err("that image is too large to show".to_owned());
        }
        let mime =
            sniff(&bytes).ok_or_else(|| "that image is not in a format Fathom shows".to_owned())?;
        Ok(format!(
            "data:{mime};base64,{}",
            base64::engine::general_purpose::STANDARD.encode(&bytes)
        ))
    })
    .await
}

// ---------------------------------------------------------------------------
// Details
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DependencyView {
    pub kind: DependencyKind,
    pub key: Option<String>,
    /// The project's name, when it could be looked up.
    pub name: Option<String>,
    pub installed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionView {
    pub version_id: String,
    pub number: String,
    pub name: String,
    pub channel: ReleaseChannel,
    pub published: Option<String>,
    pub loaders: Vec<String>,
    pub game_versions: String,
    pub file_name: String,
    pub size: Option<u64>,
    pub verdict: Verdict,
    pub findings: Vec<Finding>,
    pub dependencies: Vec<DependencyView>,
    /// Whether the index will serve the file to Fathom at all.
    pub downloadable: bool,
    /// Whether this exact build is the one the profile has.
    pub installed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GalleryImage {
    pub url: String,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailView {
    pub entry: EntryView,
    /// Markdown (Modrinth) or HTML (`CurseForge`), shown as text by the
    /// interface's own renderer — never inserted as HTML.
    pub description: Option<String>,
    pub description_format: &'static str,
    pub gallery: Vec<GalleryImage>,
    pub license: String,
    pub has_source: bool,
    pub has_issues: bool,
    pub project_url: String,
    pub verdict: ProjectVerdict,
    pub versions: Vec<VersionView>,
    /// Set when the author does not allow Fathom to download the files.
    pub withheld: bool,
}

/// How many dependency names a detail page looks up. Each is a request; the
/// install plan names the rest.
const NAMED_DEPENDENCIES: usize = 8;

pub async fn detail(
    app: tauri::AppHandle,
    profile: Option<String>,
    reference: ProjectRef,
) -> Result<DetailView, String> {
    let prepared = prepare(&app, profile.as_deref())?;
    let cancel = {
        let state = app.state::<State>();
        Slots::replace(&state.browse.detail)
    };
    worker(move || {
        let Prepared {
            catalog,
            profile,
            target,
        } = prepared;
        let grant = grant(format!("viewing {reference}"));
        let project: ModProject = catalog
            .project(&reference, &grant, &cancel)
            .map_err(|error| message(&error))?;
        let assessed = if target.loads_mods() {
            catalog
                .assessed_files(&reference, &target, &grant, &cancel)
                .map_err(|error| message(&error))?
        } else {
            Vec::new()
        };
        let verdict = compat::summarise(&reference, &assessed, &target);
        let installed: Vec<_> = profile.mods.iter().cloned().collect();
        let best = assessed
            .iter()
            .find(|file| file.assessment.installable())
            .map(|file| &file.file);
        let (install, possible_duplicate_of) =
            catalog.install_state(profile.id.as_str(), &installed, &project, best);
        let installed_entry = installed_id(&profile, &reference)
            .and_then(|id| ModId::new(&id).ok())
            .and_then(|id| profile.mods.get(&id).cloned());

        // Names for the dependencies of the builds a person will look at
        // first. Cached by the catalogue, so a second visit is free.
        let mut names: HashMap<String, String> = HashMap::new();
        for dependency in assessed
            .iter()
            .take(3)
            .flat_map(|file| file.file.dependencies.iter())
            .filter_map(|dependency| dependency.project.as_ref())
        {
            if names.len() >= NAMED_DEPENDENCIES || cancel.is_cancelled() {
                break;
            }
            let key = dependency.qualified();
            if names.contains_key(&key) {
                continue;
            }
            if let Ok(found) = catalog.project(dependency, &grant, &cancel) {
                names.insert(key, found.name);
            }
        }

        let versions = assessed
            .iter()
            .map(|assessed| {
                let file = &assessed.file;
                VersionView {
                    version_id: file.version_id.clone(),
                    number: file.number.clone(),
                    name: file.name.clone(),
                    channel: file.channel,
                    published: file.published.clone(),
                    loaders: file.loaders.iter().cloned().collect(),
                    game_versions: compat::describe_versions(&file.game_versions),
                    file_name: file.file_name.clone(),
                    size: file.hash.size,
                    verdict: assessed.assessment.verdict,
                    findings: assessed.assessment.findings.clone(),
                    dependencies: file
                        .dependencies
                        .iter()
                        .map(|dependency| {
                            let key = dependency.project.as_ref().map(ProjectRef::qualified);
                            DependencyView {
                                kind: dependency.kind,
                                name: key.as_ref().and_then(|key| names.get(key).cloned()),
                                installed: dependency.project.as_ref().is_some_and(|reference| {
                                    installed_id(&profile, reference).is_some()
                                }),
                                key,
                            }
                        })
                        .collect(),
                    downloadable: matches!(file.download, FileDownload::Url { .. }),
                    installed: installed_entry.as_ref().is_some_and(|entry| {
                        entry.origin.version_id.as_deref() == Some(file.version_id.as_str())
                            || entry.sha1.eq_ignore_ascii_case(&file.hash.sha1)
                    }),
                }
            })
            .collect();

        let entry = entry_view(
            CatalogEntry {
                project: project.clone(),
                installed: install,
                also_on: Vec::new(),
                possible_duplicate_of,
            },
            &profile,
            &target,
        );
        Ok(DetailView {
            entry,
            description_format: match reference.provider {
                Provider::Modrinth => "markdown",
                Provider::CurseForge => "html",
            },
            description: project.description.clone(),
            gallery: project
                .gallery
                .iter()
                .map(|image| GalleryImage {
                    url: image.url.clone(),
                    title: image.title.clone(),
                })
                .collect(),
            license: project.license.describe(),
            has_source: project.source_url.is_some(),
            has_issues: project.issues_url.is_some(),
            project_url: project.project_url.clone(),
            withheld: !matches!(project.distribution, Distribution::Allowed),
            verdict,
            versions,
        })
    })
    .await
}

/// Open a project's page, source or issue tracker in the user's browser.
///
/// The interface names the project and which link; the URL comes from the
/// catalogue's cached copy of what the index said, and `pages` checks it again.
///
/// A link from the description (`which == "description"`) is opened only if
/// that exact URL appears in the description the index sent: the interface can
/// ask to follow a link the author wrote, not one it made up.
pub fn open_link(
    core: &Launcher,
    reference: &ProjectRef,
    which: &str,
    url: Option<&str>,
) -> Result<(), String> {
    let project = core
        .mod_catalog()
        .cache()
        .project(reference.provider, &reference.id)
        .ok_or_else(|| "open the mod's page in Fathom first".to_owned())?;
    let url = match which {
        "page" => Some(project.project_url),
        "source" => project.source_url,
        "issues" => project.issues_url,
        "description" => url
            .filter(|url| {
                project
                    .description
                    .as_deref()
                    .is_some_and(|text| text.contains(url))
            })
            .map(str::to_owned),
        _ => None,
    }
    .ok_or_else(|| "Fathom will not open that link".to_owned())?;
    crate::pages::open_external(&url)
}

// ---------------------------------------------------------------------------
// Plans and installs
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallArgs {
    pub reference: ProjectRef,
    #[serde(default)]
    pub version_id: Option<String>,
    #[serde(default)]
    pub optional: Vec<ProjectRef>,
    #[serde(default)]
    pub accept_unverified: bool,
    #[serde(default)]
    pub accept_duplicates: bool,
    /// When set, this is an update of that profile entry rather than an
    /// install, and `version_id` is ignored: the newest build is taken.
    #[serde(default)]
    pub replacing: Option<String>,
    /// With `replacing`: fetch the exact build that entry records again,
    /// because its file is missing or damaged. Never a newer build.
    #[serde(default)]
    pub repair: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedView {
    pub key: String,
    pub name: String,
    pub number: String,
    pub file_name: String,
    pub size: Option<u64>,
    pub required_by: Option<String>,
    pub optional: bool,
    pub possible_duplicate: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptionalView {
    pub reference: ProjectRef,
    pub name: Option<String>,
    pub suggested_by: String,
    pub already_installed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UnverifiedView {
    pub name: String,
    pub number: String,
    pub reasons: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanView {
    pub root: PlannedView,
    pub dependencies: Vec<PlannedView>,
    /// Libraries the profile already has, by name.
    pub satisfied: Vec<String>,
    /// A satisfying library that is switched off, which satisfies nothing.
    pub disabled_dependencies: Vec<String>,
    pub optional: Vec<OptionalView>,
    /// Why this cannot be installed, one sentence each. Empty means it can.
    pub blockers: Vec<String>,
    pub unverified: Vec<UnverifiedView>,
    pub needs_duplicate_confirmation: bool,
    pub is_update: bool,
    pub download_bytes: u64,
}

fn name_of(plan: &InstallPlan, reference: &ProjectRef) -> String {
    plan.installs()
        .find(|planned| &planned.project.reference == reference)
        .map_or_else(
            || reference.qualified(),
            |planned| planned.project.name.clone(),
        )
}

fn blocker_text(plan: &InstallPlan, blocker: &Blocker) -> String {
    match blocker {
        Blocker::NoModLoader => "This profile has no mod loader.".to_owned(),
        Blocker::Incompatible { detail, .. } => detail.clone(),
        Blocker::NotDownloadable { project, .. } => format!(
            "{}'s author does not allow launchers to download it. Get it from its page instead.",
            name_of(plan, project)
        ),
        // The detail already names the dependency when its project could be
        // read ("Fabric Language Kotlin has no build for ..."); its raw id is
        // only a fallback.
        Blocker::Unresolved {
            dependency,
            required_by,
            detail,
        } => format!(
            "{} needs a library Fathom cannot install here ({}): {detail}",
            name_of(plan, required_by),
            dependency.qualified()
        ),
        Blocker::UnnamedDependency { required_by } => format!(
            "{} needs a library its index does not name.",
            name_of(plan, required_by)
        ),
        Blocker::AlreadyInstalled { detail, .. } => detail.clone(),
        Blocker::Conflict {
            project,
            conflicts_with,
        } => format!(
            "{} is marked incompatible with {conflicts_with}.",
            name_of(plan, project)
        ),
        Blocker::TooLarge => "Its dependency tree is larger than Fathom will resolve.".to_owned(),
    }
}

fn plan_view(plan: &InstallPlan, optional_names: &HashMap<String, String>) -> PlanView {
    let planned = |planned: &launcher_core::mods::browse::PlannedMod| PlannedView {
        key: planned.project.reference.qualified(),
        name: planned.project.name.clone(),
        number: planned.file.number.clone(),
        file_name: planned.file.file_name.clone(),
        size: planned.file.hash.size,
        required_by: planned
            .required_by
            .as_ref()
            .map(|reference| name_of(plan, reference)),
        optional: planned.optional,
        possible_duplicate: planned.possible_duplicate.clone(),
    };
    PlanView {
        root: planned(&plan.root),
        dependencies: plan.dependencies.iter().map(planned).collect(),
        satisfied: plan
            .satisfied
            .iter()
            .map(|satisfied| satisfied.installed_file.clone())
            .collect(),
        disabled_dependencies: plan
            .satisfied
            .iter()
            .filter(|satisfied| !satisfied.enabled)
            .map(|satisfied| satisfied.installed_file.clone())
            .collect(),
        optional: plan
            .optional
            .iter()
            .map(|optional| OptionalView {
                name: optional_names
                    .get(&optional.dependency.qualified())
                    .cloned(),
                reference: optional.dependency.clone(),
                suggested_by: name_of(plan, &optional.suggested_by),
                already_installed: optional.already_installed,
            })
            .collect(),
        blockers: plan
            .blockers
            .iter()
            .map(|blocker| blocker_text(plan, blocker))
            .collect(),
        unverified: plan
            .unverified
            .iter()
            .map(|unverified| UnverifiedView {
                name: name_of(plan, &unverified.project),
                number: unverified.number.clone(),
                reasons: unverified.reasons.clone(),
            })
            .collect(),
        needs_duplicate_confirmation: plan.needs_duplicate_confirmation(),
        is_update: plan.replaces.is_some(),
        download_bytes: plan.installs().filter_map(|p| p.file.hash.size).sum(),
    }
}

/// Build a plan from the index, whatever the interface last saw.
fn build_plan(
    catalog: &ModCatalog,
    profile: &Profile,
    target: &Target,
    args: &InstallArgs,
    grant: &PerActionGrant,
    cancel: &Cancel,
) -> Result<InstallPlan, String> {
    let installed: Vec<_> = profile.mods.iter().cloned().collect();
    if let (Some(replacing), true) = (&args.replacing, args.repair) {
        let id = ModId::new(replacing).map_err(|_| "that is not a mod".to_owned())?;
        return catalog
            .plan_reinstall(&id, target, &installed, grant, cancel)
            .map_err(|error| message(&error));
    }
    if let Some(replacing) = &args.replacing {
        let id = ModId::new(replacing).map_err(|_| "that is not a mod".to_owned())?;
        return catalog
            .plan_update(&id, target, &installed, grant, cancel)
            .map_err(|error| message(&error))?
            .ok_or_else(|| "That mod is already the newest build for this profile.".to_owned());
    }
    catalog
        .plan_install(
            &args.reference,
            args.version_id.as_deref(),
            target,
            &installed,
            &args.optional,
            grant,
            cancel,
        )
        .map_err(|error| message(&error))
}

pub async fn plan(
    app: tauri::AppHandle,
    profile: Option<String>,
    args: InstallArgs,
) -> Result<PlanView, String> {
    let prepared = prepare(&app, profile.as_deref())?;
    worker(move || {
        let cancel = Cancel::new();
        let grant = grant(format!("planning to install {}", args.reference));
        let plan = build_plan(
            &prepared.catalog,
            &prepared.profile,
            &prepared.target,
            &args,
            &grant,
            &cancel,
        )?;
        let mut names = HashMap::new();
        for optional in plan.optional.iter().take(NAMED_DEPENDENCIES) {
            if let Ok(found) = prepared
                .catalog
                .project(&optional.dependency, &grant, &cancel)
            {
                names.insert(optional.dependency.qualified(), found.name);
            }
        }
        Ok(plan_view(&plan, &names))
    })
    .await
}

/// Progress, as the `fathom://mod-install` event carries it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallEvent {
    pub key: String,
    pub progress: Option<InstallProgress>,
    /// Set on the last event: whether it worked, and what to say.
    pub finished: Option<InstallOutcome>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOutcome {
    pub ok: bool,
    pub message: String,
    pub cancelled: bool,
}

pub const INSTALL_EVENT: &str = "fathom://mod-install";

pub async fn install(
    app: tauri::AppHandle,
    profile: Option<String>,
    args: InstallArgs,
) -> Result<InstallOutcome, String> {
    let prepared = prepare(&app, profile.as_deref())?;
    let key = args.reference.qualified();
    let cancel = Cancel::new();
    {
        let state = app.state::<State>();
        let mut installs = state
            .browse
            .installs
            .lock()
            .map_err(|_| "the mod browser is in a bad state".to_owned())?;
        if installs.contains_key(&key) {
            return Err("That mod is already being installed.".to_owned());
        }
        installs.insert(key.clone(), cancel.clone());
    }
    let handle = app.clone();
    let worker_key = key.clone();
    let outcome = worker(move || {
        Ok(perform_install(
            &handle,
            prepared,
            &args,
            &worker_key,
            &cancel,
        ))
    })
    .await;
    {
        let state = app.state::<State>();
        if let Ok(mut installs) = state.browse.installs.lock() {
            installs.remove(&key);
        };
    }
    let outcome = outcome?;
    let _ = app.emit(
        INSTALL_EVENT,
        InstallEvent {
            key,
            progress: None,
            finished: Some(outcome.clone()),
        },
    );
    Ok(outcome)
}

fn perform_install(
    app: &tauri::AppHandle,
    prepared: Prepared,
    args: &InstallArgs,
    key: &str,
    cancel: &Cancel,
) -> InstallOutcome {
    let failed = |message: String| InstallOutcome {
        ok: false,
        message,
        cancelled: false,
    };
    let Prepared {
        catalog,
        profile,
        target,
    } = prepared;
    let grant = grant(format!("installing {}", args.reference));
    let plan = match build_plan(&catalog, &profile, &target, args, &grant, cancel) {
        Ok(plan) => plan,
        Err(message) => return failed(message),
    };
    if let Some(blocker) = plan.blockers.first() {
        return failed(blocker_text(&plan, blocker));
    }
    let options = InstallOptions {
        accept_possible_duplicates: args.accept_duplicates,
        accept_unverified: args.accept_unverified,
    };

    // -- pass one: fetch and verify, outside the lock -----------------------
    let emit = |progress: &InstallProgress| {
        let _ = app.emit(
            INSTALL_EVENT,
            InstallEvent {
                key: key.to_owned(),
                progress: Some(progress.clone()),
                finished: None,
            },
        );
    };
    let mut scratch = profile.mods.clone();
    let mut forward = emit;
    match catalog.install(
        profile.id.as_str(),
        &plan,
        &target,
        &mut scratch,
        options,
        &grant,
        cancel,
        &mut forward,
    ) {
        Ok(_) => {}
        Err(BrowseError::Cancelled) => {
            return InstallOutcome {
                ok: false,
                message: "Cancelled. Nothing was changed.".to_owned(),
                cancelled: true,
            }
        }
        Err(error) => return failed(message(&error)),
    }

    // -- pass two: record against the profile as it is now ------------------
    let state = app.state::<State>();
    let Ok(core) = state.core.lock() else {
        return failed("launcher state is poisoned".to_owned());
    };
    let mut current = match crate::commands::resolve_profile(&core, Some(profile.id.as_str())) {
        Ok(current) => current,
        Err(message) => return failed(message),
    };
    let mut quiet = |_: &InstallProgress| {};
    match core.install_mod_plan(&mut current, &plan, options, cancel, &mut quiet) {
        Ok(report) => {
            let names: Vec<String> = report
                .added
                .iter()
                .filter_map(|entry| entry.origin.name.clone())
                .collect();
            InstallOutcome {
                ok: true,
                message: if args.repair {
                    format!("Fetched {} again and checked it.", plan.root.project.name)
                } else if plan.replaces.is_some() {
                    format!(
                        "Updated {} to {}.",
                        plan.root.project.name, plan.root.file.number
                    )
                } else if names.len() > 1 {
                    format!("Installed {}.", names.join(", "))
                } else {
                    format!(
                        "Installed {} {}.",
                        plan.root.project.name, plan.root.file.number
                    )
                },
                cancelled: false,
            }
        }
        Err(error) => failed(error.to_string()),
    }
}

pub fn cancel_install(app: &tauri::AppHandle, reference: &ProjectRef) -> bool {
    let state = app.state::<State>();
    let Ok(installs) = state.browse.installs.lock() else {
        return false;
    };
    match installs.get(&reference.qualified()) {
        Some(cancel) => {
            cancel.cancel();
            true
        }
        None => false,
    }
}

/// What removing a browser-installed mod would do, and do it if nothing needs it.
pub fn uninstall(core: &Launcher, profile: &mut Profile, id: &str) -> Result<String, String> {
    let id = ModId::new(id).map_err(|_| "that is not a mod".to_owned())?;
    let plan = core
        .plan_mod_uninstall(profile, &id)
        .map_err(|error| error.to_string())?;
    if plan.is_blocked() {
        let name = |id: &str| {
            ModId::new(id)
                .ok()
                .and_then(|id| profile.mods.get(&id).cloned())
                .map_or_else(
                    || id.to_owned(),
                    |entry| entry.origin.name.unwrap_or(entry.file_name),
                )
        };
        let needed_by: Vec<String> = plan.needed_by.iter().map(|id| name(id)).collect();
        return Err(format!(
            "{} is needed by {}. Remove {} first.",
            plan.entry
                .origin
                .name
                .clone()
                .unwrap_or(plan.entry.file_name.clone()),
            needed_by.join(", "),
            if needed_by.len() == 1 { "it" } else { "them" }
        ));
    }
    let (removed, _) = core
        .uninstall_mod(profile, &id)
        .map_err(|error| error.to_string())?;
    Ok(format!(
        "Removed {}.",
        removed.origin.name.unwrap_or(removed.file_name)
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_raster_images_are_shown() {
        assert_eq!(sniff(b"\x89PNG\r\n\x1a\nrest"), Some("image/png"));
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff(b"GIF89a...."), Some("image/gif"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff(b"<svg xmlns=\"http://www.w3.org/2000/svg\"/>"), None);
        assert_eq!(sniff(b"<html>"), None);
        assert_eq!(sniff(b""), None);
    }

    #[test]
    fn the_image_gate_admits_at_most_its_limit() {
        let gate = Gate::default();
        let passes: Vec<_> = (0..Gate::LIMIT).filter_map(|_| gate.enter()).collect();
        assert_eq!(passes.len(), Gate::LIMIT);
        assert_eq!(
            *gate.held.lock().unwrap_or_else(|e| e.into_inner()),
            Gate::LIMIT
        );
        drop(passes);
        assert_eq!(*gate.held.lock().unwrap_or_else(|e| e.into_inner()), 0);
    }
}
