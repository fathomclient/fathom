//! The mod browser, end to end, without a network.
//!
//! A fake index stands in for Modrinth and `CurseForge`; the store, the profile
//! records, materialize and the launcher facade are the real ones. What these
//! tests pin down is the part a unit test cannot: that a browser install lands
//! in the selected profile's own game directory and nowhere else, that an
//! update leaves exactly one version of a mod in place, and that the facade
//! refuses to remove a library something still needs.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::collections::BTreeSet;
use std::path::Path;
use std::sync::{Arc, Mutex};

use launcher_core::credentials::{CredentialStore, EphemeralCredentialStore};
use launcher_core::mods::browse::model::{
    DependencyKind, Distribution, FileDependency, FileDownload, ReleaseChannel,
};
use launcher_core::mods::browse::{
    Availability, BrowseError, CatalogProvider, InstallOptions, ModCatalog, ModFile, ModProject,
    ProjectRef, Provider, ProviderContext, SearchPage, SearchQuery, Target,
};
use launcher_core::mods::definition::{ArtifactHash, License};
use launcher_core::mods::store::ModStore;
use launcher_core::mods::ModId;
use launcher_core::network::{HttpClient, NetworkJournal, NetworkPolicy, PerActionGrant};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{Loader, Profile, ProfileId, ProfileKind};
use launcher_core::services::{Launcher, ServiceError};
use launcher_core::tasks::Cancel;

#[derive(Debug, Default)]
struct Index {
    projects: Vec<ModProject>,
    files: Mutex<Vec<ModFile>>,
}

impl CatalogProvider for Index {
    fn provider(&self) -> Provider {
        Provider::Modrinth
    }

    fn availability(&self) -> Availability {
        Availability::Ready
    }

    fn search(
        &self,
        query: &SearchQuery,
        _: ProviderContext<'_>,
    ) -> Result<SearchPage, BrowseError> {
        Ok(SearchPage {
            hits: self.projects.clone(),
            total: self.projects.len() as u64,
            offset: query.offset,
        })
    }

    fn project(&self, id: &str, _: ProviderContext<'_>) -> Result<ModProject, BrowseError> {
        self.projects
            .iter()
            .find(|p| p.reference.id == id)
            .cloned()
            .ok_or(BrowseError::NotFound {
                provider: Provider::Modrinth,
                what: id.to_owned(),
            })
    }

    fn files(
        &self,
        id: &str,
        _: &Target,
        _: ProviderContext<'_>,
    ) -> Result<Vec<ModFile>, BrowseError> {
        // Newest first, the way the real providers answer.
        let mut files: Vec<ModFile> = self
            .files
            .lock()
            .unwrap()
            .iter()
            .filter(|f| f.project.id == id)
            .cloned()
            .collect();
        files.reverse();
        Ok(files)
    }

    fn file(
        &self,
        project: &str,
        version_id: &str,
        _: ProviderContext<'_>,
    ) -> Result<ModFile, BrowseError> {
        self.files
            .lock()
            .unwrap()
            .iter()
            .find(|f| f.project.id == project && f.version_id == version_id)
            .cloned()
            .ok_or(BrowseError::NotFound {
                provider: Provider::Modrinth,
                what: version_id.to_owned(),
            })
    }
}

fn project(id: &str, slug: &str) -> ModProject {
    ModProject {
        reference: ProjectRef::new(Provider::Modrinth, id),
        slug: slug.to_owned(),
        name: slug.to_owned(),
        summary: String::new(),
        description: None,
        authors: Vec::new(),
        icon: None,
        gallery: Vec::new(),
        downloads: 0,
        categories: Vec::new(),
        project_url: format!("https://modrinth.com/mod/{slug}"),
        source_url: None,
        issues_url: None,
        license: License::Unknown,
        minecraft_versions: ["1.8.9".to_owned()].into_iter().collect(),
        loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
        updated: None,
        distribution: Distribution::Allowed,
        detailed: true,
    }
}

/// A build whose verified bytes are already in the store, so installing it
/// needs no network — the store is what a download would have filled.
fn stored_file(paths: &DataPaths, id: &str, version: &str, requires: &[&str]) -> ModFile {
    let bytes = format!("PK\u{3}\u{4} {id} {version}").into_bytes();
    let sha1 = launcher_core::install::download::sha1_hex(&bytes);
    ModStore::new(paths.clone())
        .adopt_bytes(&bytes, &sha1)
        .unwrap();
    ModFile {
        project: ProjectRef::new(Provider::Modrinth, id),
        version_id: format!("{id}-{version}"),
        number: version.to_owned(),
        name: version.to_owned(),
        channel: ReleaseChannel::Release,
        loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
        game_versions: ["1.8.9".to_owned()].into_iter().collect(),
        file_name: format!("{id}-{version}.jar"),
        download: FileDownload::Url {
            url: format!("https://cdn.modrinth.com/data/{id}/{version}.jar"),
        },
        hash: ArtifactHash::new(sha1, Some(bytes.len() as u64)).unwrap(),
        dependencies: requires
            .iter()
            .map(|dependency| FileDependency {
                kind: DependencyKind::Required,
                project: Some(ProjectRef::new(Provider::Modrinth, *dependency)),
                version_id: None,
            })
            .collect(),
        published: None,
    }
}

fn profile(id: &str) -> Profile {
    let mut profile = Profile::new(ProfileId::new(id).unwrap(), id);
    profile.kind = ProfileKind::Custom;
    profile.loader.kind = Loader::LegacyFabric;
    "1.8.9".clone_into(&mut profile.version_id);
    profile
}

fn jars_in(dir: &Path) -> BTreeSet<String> {
    std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .filter_map(|e| e.file_name().to_str().map(str::to_owned))
                .filter(|name| !name.starts_with('.') && name != "fathom-mods.json")
                .collect()
        })
        .unwrap_or_default()
}

struct World {
    _tmp: tempfile::TempDir,
    paths: DataPaths,
    launcher: Launcher,
    index: Arc<Index>,
    catalog: ModCatalog,
}

fn world() -> World {
    let tmp = tempfile::tempdir().unwrap();
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().unwrap();
    let store: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
    let launcher = Launcher::new(paths.clone(), store).unwrap();

    let index = Arc::new(Index {
        projects: vec![project("lib", "library"), project("root", "root-mod")],
        ..Index::default()
    });
    index.files.lock().unwrap().extend([
        stored_file(&paths, "lib", "1", &[]),
        stored_file(&paths, "root", "1", &["lib"]),
    ]);

    // Offline: anything that tried to reach a real index would fail.
    let http = HttpClient::new(
        NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        },
        NetworkJournal::new(),
        "test/0",
    );
    let catalog = ModCatalog::with_providers(vec![index.clone()], http, paths.clone());
    World {
        _tmp: tmp,
        paths,
        launcher,
        index,
        catalog,
    }
}

fn grant() -> PerActionGrant {
    PerActionGrant::user_requested("test".to_owned())
}

fn install(world: &World, into: &mut Profile, reference: &str) {
    let installed: Vec<_> = into.mods.iter().cloned().collect();
    let plan = world
        .catalog
        .plan_install(
            &ProjectRef::new(Provider::Modrinth, reference),
            None,
            &Target::of(into),
            &installed,
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    assert!(!plan.is_blocked(), "{:?}", plan.blockers);
    world
        .catalog
        .install(
            into.id.as_str(),
            &plan,
            &Target::of(into),
            &mut into.mods,
            InstallOptions::default(),
            &grant(),
            &Cancel::new(),
            &mut |_| {},
        )
        .unwrap();
    world.launcher.save_profile(into).unwrap();
}

#[test]
fn an_install_reaches_only_the_selected_profiles_mods_directory() {
    let world = world();
    let mut chosen = profile("chosen");
    let mut other = profile("other");
    world.launcher.save_profile(&chosen).unwrap();
    world.launcher.save_profile(&other).unwrap();

    install(&world, &mut chosen, "root");
    world.launcher.materialize_mods(&mut chosen).unwrap();
    world.launcher.materialize_mods(&mut other).unwrap();

    let chosen_dir = world.launcher.dirs_for(&chosen).mods_dir();
    let other_dir = world.launcher.dirs_for(&other).mods_dir();
    assert_eq!(
        jars_in(&chosen_dir),
        ["lib-1.jar".to_owned(), "root-1.jar".to_owned()]
            .into_iter()
            .collect(),
        "the mod and its dependency, and nothing half-written"
    );
    assert!(
        jars_in(&other_dir).is_empty(),
        "the other profile is untouched"
    );
    assert!(
        !world.paths.root().join(".minecraft").join("mods").exists(),
        "nothing is written to a global mods folder"
    );

    // And the record survives a reload from disk.
    let reloaded = world
        .launcher
        .profiles()
        .into_iter()
        .find(|p| p.id == chosen.id)
        .unwrap();
    assert_eq!(reloaded.mods.len(), 2);
}

#[test]
fn an_update_leaves_exactly_one_version_active() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    world.launcher.materialize_mods(&mut chosen).unwrap();

    // A new build is published, with a different file name.
    world
        .index
        .files
        .lock()
        .unwrap()
        .push(stored_file(&world.paths, "root", "2", &["lib"]));
    world.catalog.cache().clear();

    let installed: Vec<_> = chosen.mods.iter().cloned().collect();
    let id = ModId::new("root-mod").unwrap();
    let plan = world
        .catalog
        .plan_update(
            &id,
            &Target::of(&chosen),
            &installed,
            &grant(),
            &Cancel::new(),
        )
        .unwrap()
        .expect("an update is available");
    world
        .catalog
        .install(
            chosen.id.as_str(),
            &plan,
            &Target::of(&chosen),
            &mut chosen.mods,
            InstallOptions::default(),
            &grant(),
            &Cancel::new(),
            &mut |_| {},
        )
        .unwrap();
    world.launcher.save_profile(&chosen).unwrap();
    world.launcher.materialize_mods(&mut chosen).unwrap();

    let jars = jars_in(&world.launcher.dirs_for(&chosen).mods_dir());
    assert!(jars.contains("root-2.jar"));
    assert!(
        !jars.contains("root-1.jar"),
        "the old version is gone: {jars:?}"
    );
    assert_eq!(chosen.mods.len(), 2);
}

#[test]
fn the_launcher_refuses_to_remove_a_library_that_is_still_needed() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");

    let library = ModId::new("library").unwrap();
    let plan = world
        .launcher
        .plan_mod_uninstall(&chosen, &library)
        .unwrap();
    assert_eq!(plan.needed_by, vec!["root-mod".to_owned()]);
    assert!(matches!(
        world.launcher.uninstall_mod(&mut chosen, &library),
        Err(ServiceError::Browse(BrowseError::Refused(_)))
    ));
    assert!(chosen.mods.contains(&library));

    // Removing the dependent first is fine, and then the library may go.
    world
        .launcher
        .uninstall_mod(&mut chosen, &ModId::new("root-mod").unwrap())
        .unwrap();
    let (_, deleted) = world.launcher.uninstall_mod(&mut chosen, &library).unwrap();
    assert!(deleted, "no profile refers to the bytes any more");
}

#[test]
fn the_facade_refuses_to_browse_until_repositories_are_switched_on() {
    let world = world();
    let chosen = profile("chosen");
    let query = SearchQuery::new(Target::of(&chosen));
    assert!(matches!(
        world
            .launcher
            .search_mod_catalog(&chosen, &query, &Cancel::new()),
        Err(ServiceError::Browse(BrowseError::Blocked { .. }))
    ));
    assert!(matches!(
        world.launcher.plan_mod_install(
            &chosen,
            &ProjectRef::new(Provider::Modrinth, "root"),
            None,
            &[],
            &Cancel::new()
        ),
        Err(ServiceError::Browse(BrowseError::Blocked { .. }))
    ));
}
