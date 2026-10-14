//! The mod browser: one catalogue over Modrinth and `CurseForge`.
//!
//! # Layers
//!
//! ```text
//! interface ──► ModCatalog ──► CatalogProvider (modrinth, curseforge)
//!                  │                 │
//!                  │                 └─► unified model (model.rs)
//!                  ├─► CatalogCache (search, projects, files, images)
//!                  ├─► dependencies::plan  ─► InstallPlan
//!                  └─► install::Installer  ─► ModStore + ProfileModConfiguration
//! ```
//!
//! Nothing outside [`modrinth`] and [`curseforge`] sees a provider's wire
//! types. The interface talks to [`ModCatalog`]; the catalogue talks to
//! providers through [`CatalogProvider`]; and what reaches a profile is a
//! [`crate::mods::ProfileMod`], the same record every other way of adding a
//! mod produces. The browser is a new way *in*, not a second mod system: the
//! store, the resolver, materialize and launch are the ones that already exist.
//!
//! # Threads
//!
//! Every method on [`ModCatalog`] that takes a [`PerActionGrant`] may do
//! network or disk work and blocks until it is done. None of them may be called
//! from a render or interface thread; the catalogue is `Clone + Send + Sync` so
//! that it can be moved onto a worker, and every such method takes a
//! [`Cancel`] so the worker can be stopped.
//!
//! # Consent
//!
//! Each call needs a [`PerActionGrant`], minted from a user action by the
//! caller. The HTTP client refuses a mod-repository request without one, and
//! refuses all of them while mod repositories are off in Privacy settings —
//! checked there, per request, rather than trusted here.

pub mod cache;
pub mod compat;
pub mod curseforge;
pub mod dependencies;
pub mod identity;
pub mod install;
pub mod model;
pub mod modrinth;
pub mod provider;
pub mod search;
pub mod throttle;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use serde::Serialize;

pub use cache::CatalogCache;
pub use curseforge::{CurseForgeConfig, CurseForgeProvider};
pub use dependencies::{Blocker, InstallPlan, PlannedMod};
pub use install::{InstallOptions, InstallProgress, InstallReport, Installer, UninstallPlan};
pub use model::{
    CatalogEntry, Category, InstallState, ModFile, ModProject, ProjectRef, Provider, Target,
};
pub use modrinth::ModrinthProvider;
pub use provider::{
    Availability, Breadth, BrowseError, CatalogProvider, ProviderContext, ProviderStatus,
    SearchPage, SearchQuery, Sort,
};
pub use search::{Decision, SearchCoordinator, SearchTicket};

use crate::install::download::DownloadManager;
use crate::mods::config::{ProfileMod, ProfileModConfiguration};
use crate::mods::definition::ModId;
use crate::mods::store::ModStore;
use crate::network::{HttpClient, PerActionGrant};
use crate::paths::DataPaths;
use crate::tasks::Cancel;
use dependencies::DependencySource;
use identity::{already_installed, Duplicate};

/// One page of combined results.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogPage {
    pub entries: Vec<CatalogEntry>,
    /// What happened at each provider asked, including the ones that failed
    /// or are not configured. The interface shows these beside the results;
    /// one provider failing is a notice, not an empty page.
    pub providers: Vec<ProviderStatus>,
    pub offset: usize,
    /// Whether asking for the next page could return more.
    pub has_more: bool,
}

/// A managed mod with a newer compatible build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCandidate {
    pub id: String,
    pub project: ProjectRef,
    pub installed: Option<String>,
    pub latest: ModFile,
}

/// A mod in a profile identified by its bytes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Identified {
    pub id: String,
    pub project: ModProject,
    pub file: ModFile,
}

struct Inner {
    providers: Vec<Arc<dyn CatalogProvider>>,
    cache: CatalogCache,
    installer: Installer,
    http: HttpClient,
    coordinator: SearchCoordinator,
    /// Operations in flight, keyed by profile and project, so a row shows
    /// "Installing" while the worker runs and a second click is refused.
    busy: Mutex<HashMap<String, InstallState>>,
    /// Each provider's category vocabulary, read once per run. It changes on
    /// the scale of years, and it is the same for every profile.
    categories: Mutex<HashMap<Provider, Vec<Category>>>,
}

impl std::fmt::Debug for Inner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ModCatalog")
            .field("providers", &self.providers)
            .field("cache", &self.cache.sizes())
            .finish_non_exhaustive()
    }
}

/// The browser's single entry point. Cheap to clone; clones share caches.
#[derive(Debug, Clone)]
pub struct ModCatalog {
    inner: Arc<Inner>,
}

/// Adapts the catalogue, with its cache, to what the dependency planner asks.
struct Source<'a> {
    catalog: &'a ModCatalog,
    grant: &'a PerActionGrant,
    cancel: &'a Cancel,
}

impl DependencySource for Source<'_> {
    fn project(&self, reference: &ProjectRef) -> Result<ModProject, BrowseError> {
        self.catalog.project(reference, self.grant, self.cancel)
    }

    fn latest(
        &self,
        reference: &ProjectRef,
        target: &Target,
    ) -> Result<Option<ModFile>, BrowseError> {
        Ok(self
            .catalog
            .files(reference, target, self.grant, self.cancel)?
            .into_iter()
            .next())
    }

    fn file(&self, reference: &ProjectRef, version_id: &str) -> Result<ModFile, BrowseError> {
        let provider = self.catalog.provider(reference.provider)?;
        provider.file(
            &reference.id,
            version_id,
            ProviderContext::new(self.grant, self.cancel),
        )
    }

    fn check(&self) -> Result<(), BrowseError> {
        ProviderContext::new(self.grant, self.cancel).check()
    }
}

fn busy_key(profile: &str, reference: &ProjectRef) -> String {
    format!("{profile}|{}", reference.qualified())
}

impl ModCatalog {
    /// The catalogue with both real providers.
    ///
    /// `CurseForge` is included whether or not it has a key; unconfigured, it
    /// reports itself as such in every search status and does no work.
    pub fn new(http: HttpClient, paths: DataPaths, curseforge: CurseForgeConfig) -> Self {
        let providers: Vec<Arc<dyn CatalogProvider>> = vec![
            Arc::new(ModrinthProvider::new(http.clone())),
            Arc::new(CurseForgeProvider::new(http.clone(), curseforge)),
        ];
        Self::with_providers(providers, http, paths)
    }

    /// The catalogue over any providers. Used by tests with fakes.
    pub fn with_providers(
        providers: Vec<Arc<dyn CatalogProvider>>,
        http: HttpClient,
        paths: DataPaths,
    ) -> Self {
        let installer = Installer::new(
            DownloadManager::new(http.clone()),
            ModStore::new(paths.clone()),
        );
        Self {
            inner: Arc::new(Inner {
                providers,
                cache: CatalogCache::new(paths),
                installer,
                http,
                coordinator: SearchCoordinator::default(),
                busy: Mutex::new(HashMap::new()),
                categories: Mutex::new(HashMap::new()),
            }),
        }
    }

    /// Debounce and stale-result bookkeeping for a search box.
    pub fn coordinator(&self) -> &SearchCoordinator {
        &self.inner.coordinator
    }

    pub fn cache(&self) -> &CatalogCache {
        &self.inner.cache
    }

    fn provider(&self, which: Provider) -> Result<&Arc<dyn CatalogProvider>, BrowseError> {
        self.inner
            .providers
            .iter()
            .find(|provider| provider.provider() == which)
            .ok_or_else(|| BrowseError::NotConfigured {
                provider: which,
                reason: format!("{} is not available in this build", which.label()),
            })
    }

    fn ready(&self, which: Provider) -> Result<&Arc<dyn CatalogProvider>, BrowseError> {
        let provider = self.provider(which)?;
        match provider.availability() {
            Availability::Ready => Ok(provider),
            Availability::NotConfigured { reason } => Err(BrowseError::NotConfigured {
                provider: which,
                reason,
            }),
        }
    }

    /// Which providers exist and whether each can be used. No network.
    pub fn provider_statuses(&self) -> Vec<ProviderStatus> {
        self.inner
            .providers
            .iter()
            .map(|provider| match provider.availability() {
                Availability::Ready => ProviderStatus::ok(provider.provider(), 0),
                Availability::NotConfigured { reason } => {
                    ProviderStatus::unconfigured(provider.provider(), reason)
                }
            })
            .collect()
    }

    /// The topics a provider can filter by. Read once, then remembered.
    pub fn categories(
        &self,
        which: Provider,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Vec<Category>, BrowseError> {
        if let Some(known) = self
            .inner
            .categories
            .lock()
            .ok()
            .and_then(|known| known.get(&which).cloned())
        {
            return Ok(known);
        }
        let found = self
            .ready(which)?
            .categories(ProviderContext::new(grant, cancel))?;
        if let Ok(mut known) = self.inner.categories.lock() {
            known.insert(which, found.clone());
        }
        Ok(found)
    }

    /// What a profile shows for a project, from the profile's own state.
    ///
    /// No network. `latest` is the newest compatible build when the caller has
    /// it, which is what turns "installed" into "update available".
    pub fn install_state(
        &self,
        profile: &str,
        installed: &[ProfileMod],
        project: &ModProject,
        latest: Option<&ModFile>,
    ) -> (InstallState, Option<String>) {
        if let Some(state) = self
            .inner
            .busy
            .lock()
            .ok()
            .and_then(|busy| busy.get(&busy_key(profile, &project.reference)).cloned())
        {
            return (state, None);
        }
        match already_installed(installed, project, None) {
            Some((entry, Duplicate::Same { .. })) => {
                let version = entry.version.as_ref().map(|v| v.as_str().to_owned());
                match latest {
                    Some(latest)
                        if entry.origin.version_id.as_deref()
                            != Some(latest.version_id.as_str())
                            && !entry.sha1.eq_ignore_ascii_case(&latest.hash.sha1)
                            && entry.origin.project.as_deref()
                                == Some(project.reference.id.as_str()) =>
                    {
                        (
                            InstallState::UpdateAvailable {
                                installed: version,
                                latest: latest.number.clone(),
                                version_id: latest.version_id.clone(),
                                file_name: latest.file_name.clone(),
                            },
                            None,
                        )
                    }
                    _ => (
                        InstallState::Installed {
                            version,
                            file_name: entry.file_name,
                        },
                        None,
                    ),
                }
            }
            Some((_, Duplicate::Possible { reason })) => (InstallState::NotInstalled, Some(reason)),
            Some((_, Duplicate::None)) | None => (InstallState::NotInstalled, None),
        }
    }

    /// Search every provider the query wants, in parallel, and combine.
    ///
    /// Returns `Err` only when the whole search is meaningless (cancelled).
    /// A provider that fails, is rate limited or has no key is reported in
    /// [`CatalogPage::providers`] and the others' results still come back.
    pub fn search(
        &self,
        profile: &str,
        query: &SearchQuery,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<CatalogPage, BrowseError> {
        let ctx = ProviderContext::new(grant, cancel);
        ctx.check()?;
        let key = query.cache_key();
        let wanted: Vec<&Arc<dyn CatalogProvider>> = self
            .inner
            .providers
            .iter()
            .filter(|provider| query.wants(provider.provider()))
            .collect();

        let outcomes: Vec<(Provider, Result<SearchPage, BrowseError>)> =
            std::thread::scope(|scope| {
                let handles: Vec<_> = wanted
                    .iter()
                    .map(|provider| {
                        let key = &key;
                        scope.spawn(move || {
                            let which = provider.provider();
                            if let Availability::NotConfigured { reason } = provider.availability()
                            {
                                return (
                                    which,
                                    Err(BrowseError::NotConfigured {
                                        provider: which,
                                        reason,
                                    }),
                                );
                            }
                            if let Some(page) = self.inner.cache.search_page(which, key) {
                                return (which, Ok(page));
                            }
                            let result = provider.search(query, ctx);
                            if let Ok(page) = &result {
                                self.inner.cache.store_search_page(which, key, page);
                            }
                            (which, result)
                        })
                    })
                    .collect();
                handles
                    .into_iter()
                    .filter_map(|handle| handle.join().ok())
                    .collect()
            });
        ctx.check()?;

        let mut statuses = Vec::new();
        let mut projects = Vec::new();
        let mut has_more = false;
        for (which, outcome) in outcomes {
            match outcome {
                Ok(page) => {
                    statuses.push(ProviderStatus::ok(which, page.hits.len()));
                    has_more |= page.has_more();
                    projects.extend(page.hits);
                }
                Err(BrowseError::Cancelled) => return Err(BrowseError::Cancelled),
                Err(BrowseError::NotConfigured { reason, .. }) => {
                    statuses.push(ProviderStatus::unconfigured(which, reason));
                }
                Err(error) => {
                    tracing::info!(provider = %which, error = %error, "a mod provider failed; showing the others");
                    statuses.push(ProviderStatus::failed(which, error));
                }
            }
        }

        let entries = identity::merge(projects)
            .into_iter()
            .map(|(project, also_on)| {
                let (installed_state, possible_duplicate_of) =
                    self.install_state(profile, installed, &project, None);
                CatalogEntry {
                    project,
                    installed: installed_state,
                    also_on,
                    possible_duplicate_of,
                }
            })
            .collect();

        Ok(CatalogPage {
            entries,
            providers: statuses,
            offset: query.offset,
            has_more,
        })
    }

    /// Everything about one project. Cached.
    pub fn project(
        &self,
        reference: &ProjectRef,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<ModProject, BrowseError> {
        if let Some(hit) = self
            .inner
            .cache
            .project(reference.provider, &reference.id)
            .filter(|project| project.detailed)
        {
            return Ok(hit);
        }
        let project = self
            .ready(reference.provider)?
            .project(&reference.id, ProviderContext::new(grant, cancel))?;
        if project.reference != *reference {
            return Err(BrowseError::Malformed {
                provider: reference.provider,
                detail: "the index answered with a different project".to_owned(),
            });
        }
        self.inner.cache.store_project(&project);
        Ok(project)
    }

    /// Every build of a project that lists the target's Minecraft version,
    /// whatever its loader, each with the compatibility resolver's verdict.
    /// Best first; see [`compat::assess_files`]. Cached.
    pub fn assessed_files(
        &self,
        reference: &ProjectRef,
        target: &Target,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Vec<compat::AssessedFile>, BrowseError> {
        // Keyed by Minecraft version alone: the list is no longer filtered by
        // loader, and the verdicts are recomputed on every read rather than
        // cached, so a change to the resolver applies to cached lists too.
        let target_key = format!("{}|all-loaders", target.minecraft);
        let files = if let Some(hit) =
            self.inner
                .cache
                .files(reference.provider, &reference.id, &target_key)
        {
            hit
        } else {
            let files: Vec<ModFile> = self
                .ready(reference.provider)?
                .files(&reference.id, target, ProviderContext::new(grant, cancel))?
                .into_iter()
                .filter(|file| file.project == *reference)
                .collect();
            self.inner
                .cache
                .store_files(reference.provider, &reference.id, &target_key, &files);
            files
        };
        Ok(compat::assess_files(files, target))
    }

    /// The builds an install may take, best first: compatible ones, then
    /// unverified ones. Never an incompatible build.
    pub fn files(
        &self,
        reference: &ProjectRef,
        target: &Target,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Vec<ModFile>, BrowseError> {
        Ok(self
            .assessed_files(reference, target, grant, cancel)?
            .into_iter()
            .filter(|assessed| assessed.assessment.installable())
            .map(|assessed| assessed.file)
            .collect())
    }

    /// The resolver's verdict on a whole project for this target.
    pub fn verdict(
        &self,
        project: &ProjectRef,
        target: &Target,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<compat::ProjectVerdict, BrowseError> {
        let files = self.assessed_files(project, target, grant, cancel)?;
        Ok(compat::summarise(project, &files, target))
    }

    /// A project as a row for this profile, with its update state resolved.
    pub fn entry(
        &self,
        profile: &str,
        reference: &ProjectRef,
        target: &Target,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<(CatalogEntry, Vec<ModFile>), BrowseError> {
        let project = self.project(reference, grant, cancel)?;
        // An incompatible project can still be inspected: its page loads, and
        // it simply has no builds to offer.
        let files = if target.loads_mods() {
            self.files(reference, target, grant, cancel)?
        } else {
            Vec::new()
        };
        let (installed_state, possible_duplicate_of) =
            self.install_state(profile, installed, &project, files.first());
        Ok((
            CatalogEntry {
                project,
                installed: installed_state,
                also_on: Vec::new(),
                possible_duplicate_of,
            },
            files,
        ))
    }

    /// An icon or screenshot on disk, fetching it if needed.
    pub fn image(
        &self,
        url: &str,
        provider: Provider,
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<PathBuf, BrowseError> {
        self.inner
            .cache
            .images()
            .fetch(url, &self.inner.http, grant, cancel, provider)
    }

    /// Plan installing a project into a profile.
    ///
    /// `version_id` picks an exact build; `None` takes the newest compatible
    /// one. `chosen_optional` names the optional dependencies the user ticked.
    #[allow(clippy::too_many_arguments)]
    pub fn plan_install(
        &self,
        reference: &ProjectRef,
        version_id: Option<&str>,
        target: &Target,
        installed: &[ProfileMod],
        chosen_optional: &[ProjectRef],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<InstallPlan, BrowseError> {
        if !target.loads_mods() {
            return Err(BrowseError::Incompatible(
                "this profile has no mod loader, so a mod would never be loaded".to_owned(),
            ));
        }
        let project = self.project(reference, grant, cancel)?;
        let source = Source {
            catalog: self,
            grant,
            cancel,
        };
        let file = match version_id {
            Some(version_id) => source.file(reference, version_id)?,
            None => source.latest(reference, target)?.ok_or_else(|| {
                BrowseError::Incompatible(format!(
                    "{} has no build for Minecraft {} on {}",
                    project.name,
                    target.minecraft,
                    target.loader.label()
                ))
            })?,
        };
        dependencies::plan(
            &source,
            target,
            installed,
            project,
            file,
            chosen_optional,
            None,
        )
    }

    /// Plan fetching the exact build an entry records, again: for a jar that
    /// is missing from the store or damaged there.
    ///
    /// The same build, by the index's version id, checked against the hash the
    /// profile recorded; never a newer one, which would be an update the user
    /// did not ask for.
    pub fn plan_reinstall(
        &self,
        id: &ModId,
        target: &Target,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<InstallPlan, BrowseError> {
        let entry = installed
            .iter()
            .find(|entry| &entry.id == id)
            .ok_or_else(|| BrowseError::Refused("that mod is not installed".to_owned()))?;
        let (Some(reference), Some(version_id)) =
            (Self::reference_of(entry), entry.origin.version_id.clone())
        else {
            return Err(BrowseError::Refused(
                "that mod was not installed from a mod index, so Fathom cannot fetch it again; \
                 add the file again yourself"
                    .to_owned(),
            ));
        };
        let project = self.project(&reference, grant, cancel)?;
        let source = Source {
            catalog: self,
            grant,
            cancel,
        };
        let file = source.file(&reference, &version_id)?;
        if !file.hash.sha1.eq_ignore_ascii_case(&entry.sha1) {
            return Err(BrowseError::Refused(
                "the index now publishes different bytes for that build than the ones that were \
                 installed, so Fathom will not substitute them"
                    .to_owned(),
            ));
        }
        dependencies::plan(&source, target, installed, project, file, &[], Some(entry))
    }

    /// Plan updating one managed entry to its newest compatible build.
    ///
    /// `Ok(None)` when it is already the newest.
    pub fn plan_update(
        &self,
        id: &ModId,
        target: &Target,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Option<InstallPlan>, BrowseError> {
        let entry = installed
            .iter()
            .find(|entry| &entry.id == id)
            .ok_or_else(|| BrowseError::Refused("that mod is not installed".to_owned()))?;
        let reference = Self::reference_of(entry).ok_or_else(|| {
            BrowseError::Refused(
                "that mod was not installed from a mod index, so there is nothing to update from"
                    .to_owned(),
            )
        })?;
        let project = self.project(&reference, grant, cancel)?;
        let Some(latest) = self
            .files(&reference, target, grant, cancel)?
            .into_iter()
            .next()
        else {
            return Ok(None);
        };
        if entry.origin.version_id.as_deref() == Some(latest.version_id.as_str())
            || entry.sha1.eq_ignore_ascii_case(&latest.hash.sha1)
        {
            return Ok(None);
        }
        let source = Source {
            catalog: self,
            grant,
            cancel,
        };
        dependencies::plan(
            &source,
            target,
            installed,
            project,
            latest,
            &[],
            Some(entry),
        )
        .map(Some)
    }

    fn reference_of(entry: &ProfileMod) -> Option<ProjectRef> {
        let provider = Provider::parse(entry.origin.repository.as_deref()?)?;
        Some(ProjectRef::new(provider, entry.origin.project.clone()?))
    }

    /// Which managed entries have a newer compatible build.
    ///
    /// One provider being down skips its mods rather than failing the check.
    pub fn updates(
        &self,
        target: &Target,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Vec<UpdateCandidate>, BrowseError> {
        let mut out = Vec::new();
        for entry in installed {
            ProviderContext::new(grant, cancel).check()?;
            if entry.source.is_launcher_owned() {
                continue;
            }
            let Some(reference) = Self::reference_of(entry) else {
                continue;
            };
            let latest = match self.files(&reference, target, grant, cancel) {
                Ok(files) => files.into_iter().next(),
                Err(BrowseError::Cancelled) => return Err(BrowseError::Cancelled),
                Err(error) => {
                    tracing::debug!(project = %reference, %error, "update check skipped");
                    continue;
                }
            };
            let Some(latest) = latest else { continue };
            if entry.origin.version_id.as_deref() == Some(latest.version_id.as_str())
                || entry.sha1.eq_ignore_ascii_case(&latest.hash.sha1)
            {
                continue;
            }
            out.push(UpdateCandidate {
                id: entry.id.to_string(),
                project: reference,
                installed: entry.version.as_ref().map(|v| v.as_str().to_owned()),
                latest,
            });
        }
        Ok(out)
    }

    /// Put a name to jars nobody recorded a source for, by their SHA-1.
    ///
    /// Only providers that publish a hash index answer. A jar with no match is
    /// simply not in the result: it stays what it was.
    pub fn identify(
        &self,
        installed: &[ProfileMod],
        grant: &PerActionGrant,
        cancel: &Cancel,
    ) -> Result<Vec<Identified>, BrowseError> {
        let mut out = Vec::new();
        for entry in installed
            .iter()
            .filter(|entry| entry.origin.project.is_none())
        {
            for provider in &self.inner.providers {
                let ctx = ProviderContext::new(grant, cancel);
                ctx.check()?;
                if !provider.availability().is_ready() {
                    continue;
                }
                match provider.lookup_by_hash(&entry.sha1, ctx) {
                    Ok(Some((project, file)))
                        if file.hash.sha1.eq_ignore_ascii_case(&entry.sha1) =>
                    {
                        out.push(Identified {
                            id: entry.id.to_string(),
                            project,
                            file,
                        });
                        break;
                    }
                    Ok(_) => {}
                    Err(BrowseError::Cancelled) => return Err(BrowseError::Cancelled),
                    Err(error) => tracing::debug!(%error, "hash lookup failed"),
                }
            }
        }
        Ok(out)
    }

    /// Carry out a plan. Blocking: run on a worker.
    ///
    /// `mods` is changed only once every file is verified. The caller saves the
    /// profile and, if that fails, calls [`install::rollback`] with the report.
    #[allow(clippy::too_many_arguments)]
    pub fn install(
        &self,
        profile: &str,
        plan: &InstallPlan,
        target: &Target,
        mods: &mut ProfileModConfiguration,
        options: InstallOptions,
        grant: &PerActionGrant,
        cancel: &Cancel,
        progress: &mut dyn FnMut(&InstallProgress),
    ) -> Result<InstallReport, BrowseError> {
        let key = busy_key(profile, &plan.root.project.reference);
        {
            let mut busy = self.inner.busy.lock().map_err(|_| {
                BrowseError::Refused("the mod browser is in a bad state".to_owned())
            })?;
            if busy.get(&key).is_some_and(InstallState::is_busy) {
                return Err(BrowseError::Refused(
                    "that mod is already being installed".to_owned(),
                ));
            }
            busy.insert(
                key.clone(),
                if plan.replaces.is_some() {
                    InstallState::Updating
                } else {
                    InstallState::Installing
                },
            );
        }

        let result = self
            .inner
            .installer
            .execute(plan, target, mods, options, grant, cancel, progress);

        if let Ok(mut busy) = self.inner.busy.lock() {
            match &result {
                Err(error) if !matches!(error, BrowseError::Cancelled) => {
                    busy.insert(
                        key,
                        InstallState::Failed {
                            reason: error.user_message(),
                        },
                    );
                }
                _ => {
                    busy.remove(&key);
                }
            }
        }
        for planned in plan.installs() {
            self.inner.cache.invalidate_project(
                planned.project.reference.provider,
                &planned.project.reference.id,
            );
        }
        result
    }

    /// Forget a failed state so the row goes back to what the profile says.
    pub fn clear_failure(&self, profile: &str, reference: &ProjectRef) {
        if let Ok(mut busy) = self.inner.busy.lock() {
            if matches!(
                busy.get(&busy_key(profile, reference)),
                Some(InstallState::Failed { .. })
            ) {
                busy.remove(&busy_key(profile, reference));
            }
        }
    }

    /// What removing a mod would do. No network.
    pub fn plan_uninstall(
        &self,
        mods: &ProfileModConfiguration,
        id: &ModId,
    ) -> Result<UninstallPlan, BrowseError> {
        install::plan_uninstall(mods, id)
    }

    /// Remove a mod's entry, refusing while another mod needs it.
    ///
    /// Marks the project "removal pending" for the duration. Deleting the
    /// bytes is the store's garbage collection, which checks every profile.
    pub fn uninstall(
        &self,
        profile: &str,
        mods: &mut ProfileModConfiguration,
        id: &ModId,
    ) -> Result<ProfileMod, BrowseError> {
        let key = mods
            .get(id)
            .and_then(Self::reference_of)
            .map(|reference| busy_key(profile, &reference));
        if let (Some(key), Ok(mut busy)) = (&key, self.inner.busy.lock()) {
            busy.insert(key.clone(), InstallState::RemovalPending);
        }
        let result = install::uninstall(mods, id);
        if let (Some(key), Ok(mut busy)) = (&key, self.inner.busy.lock()) {
            busy.remove(key);
        }
        result
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
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;
    use crate::mods::browse::dependencies::tests::{file, project, target};
    use crate::network::{NetworkJournal, NetworkPolicy};
    use crate::profiles::Loader;

    /// A provider that answers from memory, or fails on demand.
    #[derive(Debug)]
    struct Fake {
        which: Provider,
        fail: Option<u16>,
        configured: bool,
        projects: Vec<ModProject>,
        files: Vec<ModFile>,
        searches: AtomicUsize,
    }

    impl Fake {
        fn new(which: Provider) -> Self {
            Self {
                which,
                fail: None,
                configured: true,
                projects: Vec::new(),
                files: Vec::new(),
                searches: AtomicUsize::new(0),
            }
        }
    }

    impl CatalogProvider for Fake {
        fn provider(&self) -> Provider {
            self.which
        }

        fn availability(&self) -> Availability {
            if self.configured {
                Availability::Ready
            } else {
                Availability::NotConfigured {
                    reason: "no key".into(),
                }
            }
        }

        fn search(
            &self,
            query: &SearchQuery,
            ctx: ProviderContext<'_>,
        ) -> Result<SearchPage, BrowseError> {
            ctx.check()?;
            self.searches.fetch_add(1, Ordering::SeqCst);
            if let Some(status) = self.fail {
                return Err(BrowseError::from_status(self.which, status, None));
            }
            let hits: Vec<ModProject> = self
                .projects
                .iter()
                .filter(|p| query.include_incompatible || p.claims(&query.target))
                .cloned()
                .collect();
            Ok(SearchPage {
                total: hits.len() as u64,
                offset: query.offset,
                hits,
            })
        }

        fn project(&self, id: &str, _: ProviderContext<'_>) -> Result<ModProject, BrowseError> {
            self.projects
                .iter()
                .find(|p| p.reference.id == id)
                .cloned()
                .ok_or(BrowseError::NotFound {
                    provider: self.which,
                    what: id.into(),
                })
        }

        fn files(
            &self,
            id: &str,
            _: &Target,
            _: ProviderContext<'_>,
        ) -> Result<Vec<ModFile>, BrowseError> {
            // Deliberately unfiltered: the catalogue must not trust this.
            Ok(self
                .files
                .iter()
                .filter(|f| f.project.id == id)
                .cloned()
                .collect())
        }

        fn file(
            &self,
            project: &str,
            version_id: &str,
            _: ProviderContext<'_>,
        ) -> Result<ModFile, BrowseError> {
            self.files
                .iter()
                .find(|f| f.project.id == project && f.version_id == version_id)
                .cloned()
                .ok_or(BrowseError::NotFound {
                    provider: self.which,
                    what: version_id.into(),
                })
        }
    }

    fn compatible(mut project: ModProject) -> ModProject {
        project.minecraft_versions.insert("1.8.9".into());
        project.loaders.insert("legacy-fabric".into());
        project
    }

    fn catalog(providers: Vec<Arc<dyn CatalogProvider>>) -> (tempfile::TempDir, ModCatalog) {
        let tmp = tempfile::tempdir().unwrap();
        let http = HttpClient::new(
            NetworkPolicy {
                online: false,
                ..NetworkPolicy::default()
            },
            NetworkJournal::new(),
            "test/0",
        );
        let catalog = ModCatalog::with_providers(providers, http, DataPaths::rooted_at(tmp.path()));
        (tmp, catalog)
    }

    fn grant() -> PerActionGrant {
        PerActionGrant::user_requested("test".to_owned())
    }

    #[test]
    fn one_provider_failing_does_not_empty_the_page() {
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(compatible(project(Provider::Modrinth, "a", "sodium")));
        let mut curseforge = Fake::new(Provider::CurseForge);
        curseforge.fail = Some(503);
        let (_tmp, catalog) = catalog(vec![Arc::new(modrinth), Arc::new(curseforge)]);

        let page = catalog
            .search(
                "p",
                &SearchQuery::new(target()),
                &[],
                &grant(),
                &Cancel::new(),
            )
            .unwrap();
        assert_eq!(page.entries.len(), 1);
        let failed = page
            .providers
            .iter()
            .find(|s| s.provider == Provider::CurseForge)
            .unwrap();
        assert!(failed.error.as_ref().unwrap().is_provider_fault());
    }

    #[test]
    fn an_unconfigured_curseforge_is_reported_and_modrinth_still_works() {
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(compatible(project(Provider::Modrinth, "a", "sodium")));
        let mut curseforge = Fake::new(Provider::CurseForge);
        curseforge.configured = false;
        let curseforge = Arc::new(curseforge);
        let (_tmp, catalog) = catalog(vec![Arc::new(modrinth), curseforge.clone()]);

        let page = catalog
            .search(
                "p",
                &SearchQuery::new(target()),
                &[],
                &grant(),
                &Cancel::new(),
            )
            .unwrap();
        assert_eq!(page.entries.len(), 1);
        assert_eq!(
            curseforge.searches.load(Ordering::SeqCst),
            0,
            "no request without a key"
        );
        assert!(page
            .providers
            .iter()
            .any(|s| matches!(s.availability, Availability::NotConfigured { .. })));
    }

    #[test]
    fn the_same_mod_on_both_indexes_is_one_row() {
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(compatible(project(Provider::Modrinth, "a", "sodium")));
        let mut curseforge = Fake::new(Provider::CurseForge);
        curseforge
            .projects
            .push(compatible(project(Provider::CurseForge, "1", "sodium")));
        let (_tmp, catalog) = catalog(vec![Arc::new(modrinth), Arc::new(curseforge)]);
        let page = catalog
            .search(
                "p",
                &SearchQuery::new(target()),
                &[],
                &grant(),
                &Cancel::new(),
            )
            .unwrap();
        assert_eq!(page.entries.len(), 1);
        assert_eq!(page.entries[0].also_on.len(), 1);
    }

    #[test]
    fn a_repeated_search_is_served_from_the_cache() {
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(compatible(project(Provider::Modrinth, "a", "sodium")));
        let modrinth = Arc::new(modrinth);
        let (_tmp, catalog) = catalog(vec![modrinth.clone()]);
        let query = SearchQuery::new(target()).with_text("sodium");
        for _ in 0..3 {
            catalog
                .search("p", &query, &[], &grant(), &Cancel::new())
                .unwrap();
        }
        assert_eq!(modrinth.searches.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn a_cancelled_search_returns_cancelled_and_not_a_partial_page() {
        let (_tmp, catalog) = catalog(vec![Arc::new(Fake::new(Provider::Modrinth))]);
        let cancel = Cancel::new();
        cancel.cancel();
        assert!(matches!(
            catalog.search("p", &SearchQuery::new(target()), &[], &grant(), &cancel),
            Err(BrowseError::Cancelled)
        ));
    }

    #[test]
    fn files_the_provider_should_not_have_offered_are_dropped() {
        let reference = ProjectRef::new(Provider::Modrinth, "a");
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(project(Provider::Modrinth, "a", "sodium"));
        let good = file(&reference, "1", vec![]);
        let mut modern = file(&reference, "2", vec![]);
        modern.game_versions = ["1.21".to_owned()].into_iter().collect();
        modern.loaders = ["fabric".to_owned()].into_iter().collect();
        modrinth.files = vec![modern, good.clone()];
        let (_tmp, catalog) = catalog(vec![Arc::new(modrinth)]);
        let files = catalog
            .files(&reference, &target(), &grant(), &Cancel::new())
            .unwrap();
        assert_eq!(files, vec![good]);
    }

    #[test]
    fn an_incompatible_project_can_be_inspected_but_not_planned() {
        let reference = ProjectRef::new(Provider::Modrinth, "a");
        let mut modrinth = Fake::new(Provider::Modrinth);
        modrinth
            .projects
            .push(project(Provider::Modrinth, "a", "modern"));
        let mut modern = file(&reference, "1", vec![]);
        modern.game_versions = ["1.21".to_owned()].into_iter().collect();
        modrinth.files = vec![modern];
        let (_tmp, catalog) = catalog(vec![Arc::new(modrinth)]);

        let (entry, files) = catalog
            .entry("p", &reference, &target(), &[], &grant(), &Cancel::new())
            .unwrap();
        assert_eq!(entry.project.name, "modern");
        assert!(files.is_empty());
        assert!(matches!(
            catalog.plan_install(
                &reference,
                None,
                &target(),
                &[],
                &[],
                &grant(),
                &Cancel::new()
            ),
            Err(BrowseError::Incompatible(_))
        ));
        // Naming the incompatible build explicitly does not get around it.
        let plan = catalog
            .plan_install(
                &reference,
                Some("1"),
                &target(),
                &[],
                &[],
                &grant(),
                &Cancel::new(),
            )
            .unwrap();
        assert!(plan.is_blocked());
    }

    #[test]
    fn a_vanilla_profile_cannot_plan_an_install() {
        let (_tmp, catalog) = catalog(vec![Arc::new(Fake::new(Provider::Modrinth))]);
        assert!(matches!(
            catalog.plan_install(
                &ProjectRef::new(Provider::Modrinth, "a"),
                None,
                &Target::new("1.8.9", Loader::Vanilla),
                &[],
                &[],
                &grant(),
                &Cancel::new()
            ),
            Err(BrowseError::Incompatible(_))
        ));
    }

    #[test]
    fn install_state_follows_the_profile_including_updates() {
        let reference = ProjectRef::new(Provider::Modrinth, "a");
        let (_tmp, catalog) = catalog(vec![Arc::new(Fake::new(Provider::Modrinth))]);
        let listing = project(Provider::Modrinth, "a", "sodium");
        let entry = ProfileMod::new(
            ModId::new("sodium").unwrap(),
            "b".repeat(40),
            "sodium-1.jar",
        )
        .with_origin(crate::mods::Origin {
            repository: Some("modrinth".into()),
            project: Some("a".into()),
            version_id: Some("1".into()),
            ..crate::mods::Origin::default()
        });
        let installed = vec![entry];

        let (state, _) = catalog.install_state("p", &[], &listing, None);
        assert_eq!(state, InstallState::NotInstalled);
        let (state, _) = catalog.install_state("p", &installed, &listing, None);
        assert!(matches!(state, InstallState::Installed { .. }));
        let newer = file(&reference, "2", vec![]);
        let (state, _) = catalog.install_state("p", &installed, &listing, Some(&newer));
        assert!(matches!(state, InstallState::UpdateAvailable { .. }));
        // Another profile is unaffected by this profile's state.
        let (state, _) = catalog.install_state("other", &[], &listing, Some(&newer));
        assert_eq!(state, InstallState::NotInstalled);
    }

    fn live_catalog(tmp: &tempfile::TempDir) -> ModCatalog {
        let http = HttpClient::new(
            NetworkPolicy {
                online: true,
                allow_update_check: false,
                allow_managed_java: false,
                allow_mod_repositories: true,
            },
            NetworkJournal::new(),
            "fathom-test",
        );
        let paths = DataPaths::rooted_at(tmp.path());
        ModCatalog::new(http, paths.clone(), CurseForgeConfig::discover(&paths))
    }

    /// Search, detail, plan and install against the real Modrinth API, with
    /// `CurseForge` left unconfigured unless `FATHOM_CURSEFORGE_API_KEY` is set.
    ///
    /// Run with `cargo test -p launcher-core -- --ignored catalog_live`.
    #[test]
    #[ignore = "reaches api.modrinth.com and cdn.modrinth.com"]
    fn catalog_live_modrinth_end_to_end() {
        let tmp = tempfile::tempdir().unwrap();
        let catalog = live_catalog(&tmp);
        let cancel = Cancel::new();
        let grant = grant();

        let query = SearchQuery::new(target())
            .with_text("legacy fabric api")
            .with_provider(Provider::Modrinth);
        let page = catalog
            .search("live", &query, &[], &grant, &cancel)
            .unwrap();
        let status = &page.providers[0];
        assert!(status.error.is_none(), "{status:?}");
        let hit = page
            .entries
            .iter()
            .find(|entry| entry.project.slug == "legacy-fabric-api")
            .expect("Legacy Fabric API is on Modrinth");

        let (entry, files) = catalog
            .entry(
                "live",
                &hit.project.reference,
                &target(),
                &[],
                &grant,
                &cancel,
            )
            .unwrap();
        assert!(entry.project.detailed);
        assert!(!files.is_empty());
        assert!(files.iter().all(|file| file.supports(&target())));

        let plan = catalog
            .plan_install(
                &hit.project.reference,
                None,
                &target(),
                &[],
                &[],
                &grant,
                &cancel,
            )
            .unwrap();
        assert!(!plan.is_blocked(), "{:?}", plan.blockers);

        let mut mods = ProfileModConfiguration::default();
        let report = catalog
            .install(
                "live",
                &plan,
                &target(),
                &mut mods,
                InstallOptions::default(),
                &grant,
                &cancel,
                &mut |_| {},
            )
            .unwrap();
        assert_eq!(report.downloaded + report.reused, plan.download_count());
        assert!(!mods.is_empty());

        // Both providers, together: CurseForge reports itself honestly.
        let both = catalog
            .search(
                "live",
                &SearchQuery::new(target()).with_text("sodium"),
                &[],
                &grant,
                &cancel,
            )
            .unwrap();
        let curseforge = both
            .providers
            .iter()
            .find(|status| status.provider == Provider::CurseForge)
            .unwrap();
        if std::env::var("FATHOM_CURSEFORGE_API_KEY").is_err() {
            assert!(matches!(
                curseforge.availability,
                Availability::NotConfigured { .. }
            ));
        } else {
            assert!(curseforge.error.is_none(), "{curseforge:?}");
        }
        let modrinth = both
            .providers
            .iter()
            .find(|status| status.provider == Provider::Modrinth)
            .unwrap();
        assert!(
            modrinth.error.is_none(),
            "Modrinth works regardless: {modrinth:?}"
        );
    }

    /// `CurseForge` search and detail, when a key is configured.
    #[test]
    #[ignore = "reaches api.curseforge.com; needs FATHOM_CURSEFORGE_API_KEY"]
    fn catalog_live_curseforge_search() {
        if std::env::var("FATHOM_CURSEFORGE_API_KEY").is_err() {
            eprintln!("skipped: no CurseForge key configured");
            return;
        }
        let tmp = tempfile::tempdir().unwrap();
        let catalog = live_catalog(&tmp);
        let query = SearchQuery::new(target()).with_provider(Provider::CurseForge);
        let page = catalog
            .search("live", &query, &[], &grant(), &Cancel::new())
            .unwrap();
        assert!(page.providers[0].error.is_none(), "{:?}", page.providers[0]);
        let first = page
            .entries
            .first()
            .expect("1.8.9 Fabric mods on CurseForge");
        let (_, files) = catalog
            .entry(
                "live",
                &first.project.reference,
                &target(),
                &[],
                &grant(),
                &Cancel::new(),
            )
            .unwrap();
        assert!(files.iter().all(|file| file.supports(&target())));
    }

    #[test]
    fn the_catalog_can_move_to_a_worker_thread() {
        fn assert_send_sync<T: Send + Sync + Clone + 'static>() {}
        assert_send_sync::<ModCatalog>();
    }
}
