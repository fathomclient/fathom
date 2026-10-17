//! Managing installed mods, end to end, without a network.
//!
//! Versions, shared files, dependencies, safe launches and recovery, against a
//! real data root, the real store, real profile files and the launcher facade.
//! The fake index is the same one `mod_browser.rs` uses.

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
use launcher_core::mods::history::{LaunchHistory as History, Outcome};
use launcher_core::mods::store::ModStore;
use launcher_core::mods::{ModId, ModSource, ProfileMod};
use launcher_core::network::{HttpClient, NetworkJournal, NetworkPolicy, PerActionGrant};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{Loader, Profile, ProfileId, ProfileKind};
use launcher_core::services::{safe_mode_mods, LaunchOptions, Launcher};
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

    let index = Index {
        projects: vec![project("lib", "library"), project("root", "root-mod")],
        ..Index::default()
    };
    let index = Arc::new(index);
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

fn update_root(world: &World, chosen: &mut Profile, version: &str) {
    world
        .index
        .files
        .lock()
        .unwrap()
        .push(stored_file(&world.paths, "root", version, &["lib"]));
    world.catalog.cache().clear();
    let installed: Vec<_> = chosen.mods.iter().cloned().collect();
    let plan = world
        .catalog
        .plan_update(
            &root_id(),
            &Target::of(chosen),
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
            &Target::of(chosen),
            &mut chosen.mods,
            InstallOptions::default(),
            &grant(),
            &Cancel::new(),
            &mut |_| {},
        )
        .unwrap();
    world.launcher.save_profile(chosen).unwrap();
}

fn root_id() -> ModId {
    ModId::new("root-mod").unwrap()
}

fn lib_id() -> ModId {
    ModId::new("library").unwrap()
}

fn store(world: &World) -> ModStore {
    ModStore::new(world.paths.clone())
}

// ---------------------------------------------------------------------------
// Versions
// ---------------------------------------------------------------------------

/// A bad update can be undone without the network: the replaced build is
/// remembered, its bytes kept, and putting it back swaps the two.
#[test]
fn a_previous_build_can_be_restored_offline_and_lands_in_the_mods_directory() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    let first = chosen.mods.get(&root_id()).unwrap().sha1.clone();
    update_root(&world, &mut chosen, "2");
    world.launcher.materialize_mods(&mut chosen).unwrap();
    assert!(jars_in(&world.launcher.dirs_for(&chosen).mods_dir()).contains("root-2.jar"));

    let restored = world
        .launcher
        .restore_mod_build(&mut chosen, &root_id(), &first)
        .unwrap();
    assert_eq!(restored.sha1, first);
    world.launcher.materialize_mods(&mut chosen).unwrap();
    let jars = jars_in(&world.launcher.dirs_for(&chosen).mods_dir());
    assert!(
        jars.contains("root-1.jar") && !jars.contains("root-2.jar"),
        "{jars:?}"
    );

    // Survives a restart of the launcher.
    let reloaded = reopen(&world)
        .profiles()
        .into_iter()
        .find(|p| p.id == chosen.id)
        .unwrap();
    let entry = reloaded.mods.get(&root_id()).unwrap();
    assert_eq!(entry.sha1, first);
    assert_eq!(
        entry.previous.len(),
        1,
        "and the newer build can be put back again"
    );
}

fn reopen(world: &World) -> Launcher {
    let credentials: Arc<dyn CredentialStore> = Arc::new(EphemeralCredentialStore::default());
    Launcher::new(world.paths.clone(), credentials).unwrap()
}

// ---------------------------------------------------------------------------
// Shared files
// ---------------------------------------------------------------------------

/// The store is shared and deduplicated. Removing a mod from one profile must
/// never delete bytes something else still names -- including a profile that
/// cannot currently be read, one in the trash, an earlier build, or the last
/// set that worked.
#[test]
fn removing_a_mod_never_deletes_bytes_anything_else_names() {
    let world = world();
    let mut first = profile("first");
    let mut second = profile("second");
    world.launcher.save_profile(&first).unwrap();
    world.launcher.save_profile(&second).unwrap();
    install(&world, &mut first, "root");
    install(&world, &mut second, "root");
    let root_sha = first.mods.get(&root_id()).unwrap().sha1.clone();

    // The second profile becomes unreadable (both its file and its backup):
    // cut off mid-document, the way an interrupted write leaves a file.
    let dirs = world.launcher.dirs_for(&second);
    for file in [dirs.manifest_file(), dirs.root().join("profile.json.bak")] {
        let text = std::fs::read_to_string(&file).unwrap();
        std::fs::write(&file, &text[..text.len() - 20]).unwrap();
    }
    assert!(
        world.launcher.profiles().iter().all(|p| p.id != second.id),
        "it no longer loads"
    );

    world
        .launcher
        .remove_mod_and_collect(&mut first, &root_id())
        .unwrap();
    assert!(
        store(&world).contains(&root_sha),
        "an unreadable profile still names it, so its bytes stay"
    );

    // A trashed profile counts as well: deleting a profile moves it to the
    // trash, and its jars must be there if it is restored.
    std::fs::remove_dir_all(dirs.root()).unwrap();
    let mut third = profile("third");
    world.launcher.save_profile(&third).unwrap();
    install(&world, &mut third, "root");
    world.launcher.delete_profile(&third.id).unwrap();
    world.launcher.save_profile(&first).unwrap();
    world.launcher.save_profile(&first).unwrap();
    let (_, deleted) = world
        .launcher
        .remove_mod_and_collect(&mut first, &lib_id())
        .unwrap();
    assert!(!deleted || store(&world).contains(&root_sha));
    assert!(
        store(&world).contains(&root_sha),
        "the trashed profile still names it"
    );
}

#[test]
fn an_earlier_build_and_the_last_working_set_keep_their_bytes() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    let first = chosen.mods.get(&root_id()).unwrap().sha1.clone();

    // A launch that worked, then an update.
    let dirs = world.launcher.dirs_for(&chosen);
    History::begin(&dirs, &chosen, &chosen.mods, false);
    History::judge(&dirs, Outcome::Worked);
    update_root(&world, &mut chosen, "2");

    // Something unrelated is removed, which runs garbage collection.
    world
        .launcher
        .remove_mod_and_collect(&mut chosen, &lib_id())
        .unwrap();
    assert!(
        store(&world).contains(&first),
        "the earlier build is kept for Restore"
    );

    // Even with the history gone, the profile's own record of the earlier
    // build keeps it.
    std::fs::remove_file(dirs.root().join(launcher_core::mods::history::HISTORY_FILE)).unwrap();
    let mut spare = profile("spare");
    world.launcher.save_profile(&spare).unwrap();
    install(&world, &mut spare, "lib");
    world
        .launcher
        .remove_mod_and_collect(&mut spare, &lib_id())
        .unwrap();
    assert!(store(&world).contains(&first));
}

// ---------------------------------------------------------------------------
// Dependencies and safe launches
// ---------------------------------------------------------------------------

/// A library switched off makes the mod that needs it fail at the loader's
/// dependency check. The launcher says so first, by name.
#[test]
fn a_switched_off_library_is_caught_before_launch() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    assert_eq!(world.launcher.mod_launch_check(&chosen), None);

    chosen.mods.set_enabled(&lib_id(), false);
    let message = world.launcher.mod_launch_check(&chosen).expect("refused");
    assert!(
        message.contains("root-mod") && message.contains("library"),
        "{message}"
    );
    assert!(message.contains("without external mods"), "{message}");

    // Removing the library outright is refused while something needs it.
    assert!(world
        .launcher
        .uninstall_mod(&mut chosen, &lib_id())
        .is_err());
}

/// "Launch without external mods" takes every external jar out of the mods
/// directory -- including one dropped in by hand -- without deleting
/// anything, and the next ordinary launch puts them all back.
#[test]
fn a_safe_launch_lays_out_no_external_mods_and_loses_nothing() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    let mods_dir = world.launcher.dirs_for(&chosen).mods_dir();
    std::fs::create_dir_all(&mods_dir).unwrap();
    std::fs::write(mods_dir.join("dropped-in.jar"), b"PK\x03\x04 dropped").unwrap();
    std::fs::write(mods_dir.join("notes.txt"), b"mine").unwrap();

    world
        .launcher
        .lay_out_mods(
            &mut chosen,
            LaunchOptions {
                without_external_mods: true,
            },
        )
        .unwrap();
    let jars = jars_in(&mods_dir);
    assert!(
        !jars.iter().any(|name| Path::new(name)
            .extension()
            .is_some_and(|extension| extension.eq_ignore_ascii_case("jar"))),
        "no external jar is loaded: {jars:?}"
    );
    assert!(
        jars.contains("notes.txt"),
        "files that are not mods are never touched"
    );
    assert_eq!(
        chosen.mods.len(),
        3,
        "the dropped jar was adopted, nothing was removed"
    );
    assert!(chosen
        .mods
        .iter()
        .all(|entry| store(&world).contains(&entry.sha1)));

    world
        .launcher
        .lay_out_mods(&mut chosen, LaunchOptions::default())
        .unwrap();
    let jars = jars_in(&mods_dir);
    for expected in ["root-1.jar", "lib-1.jar", "dropped-in.jar"] {
        assert!(jars.contains(expected), "{expected} is back: {jars:?}");
    }
}

/// The safe set is Fathom's own components and nothing a user supplied --
/// not even `OptiFabric`, whose only job is loading the user's `OptiFine`.
#[test]
fn the_safe_set_is_only_fathoms_own_components() {
    let mut mods = launcher_core::mods::ProfileModConfiguration::default();
    for (id, source) in [
        ("fathom-optimizations", ModSource::FirstParty),
        ("fabric-api", ModSource::FirstParty),
        ("optifabric", ModSource::FirstParty),
        ("optifine", ModSource::UserSupplied),
        ("ksyxis", ModSource::Managed),
    ] {
        mods.upsert(
            ProfileMod::new(ModId::new(id).unwrap(), "a".repeat(40), format!("{id}.jar"))
                .from_source(source),
        );
    }
    let safe = safe_mode_mods(&mods);
    let ids: Vec<&str> = safe.iter().map(|entry| entry.id.as_str()).collect();
    assert_eq!(ids, ["fabric-api", "fathom-optimizations"]);
}

// ---------------------------------------------------------------------------
// Recovery
// ---------------------------------------------------------------------------

#[test]
fn recovery_lists_what_changed_and_restoring_puts_the_working_set_back() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "lib");
    let dirs = world.launcher.dirs_for(&chosen);
    History::begin(&dirs, &chosen, &chosen.mods, false);
    History::judge(&dirs, Outcome::Worked);

    // A new mod, then a launch that fails.
    install(&world, &mut chosen, "root");
    History::begin(&dirs, &chosen, &chosen.mods, false);
    History::judge(
        &dirs,
        Outcome::Failed {
            message: "Minecraft crashed (exit 1).".into(),
        },
    );

    let report = world.launcher.mod_recovery_report(&chosen);
    assert!(report.last_launch.as_ref().unwrap().outcome.is_failure());
    let changed: Vec<&str> = report.changes.iter().map(|c| c.id.as_str()).collect();
    assert_eq!(
        changed,
        ["root-mod"],
        "only what changed since it last worked"
    );
    assert!(
        report.named_by_logs.is_empty(),
        "no log names anything, so nothing is blamed"
    );
    assert!(report.can_restore);

    let restored = world.launcher.restore_last_good_mods(&mut chosen).unwrap();
    assert_eq!(restored.switched_off, vec!["root-mod".to_owned()]);
    let reloaded = world
        .launcher
        .profiles()
        .into_iter()
        .find(|p| p.id == chosen.id)
        .unwrap();
    let root = reloaded.mods.get(&root_id()).unwrap();
    assert!(!root.enabled, "switched off, not deleted");
    assert!(world
        .launcher
        .mod_recovery_report(&reloaded)
        .changes
        .is_empty());
}

/// A jar damaged on disk is found, named, marked repairable when Fathom
/// knows which build it was, and kept out of the game.
#[test]
fn a_damaged_jar_is_reported_and_kept_out_of_the_game() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "root");
    let sha = chosen.mods.get(&root_id()).unwrap().sha1.clone();
    std::fs::write(store(&world).path_for(&sha).unwrap(), b"damaged").unwrap();

    let report = world.launcher.mod_recovery_report(&chosen);
    assert_eq!(report.damaged.len(), 1);
    assert_eq!(report.damaged[0].id, "root-mod");
    assert!(report.damaged[0].repairable);

    let outcome = world.launcher.materialize_mods(&mut chosen).unwrap();
    assert_eq!(outcome.corrupt, vec!["root-1.jar".to_owned()]);
    assert!(!jars_in(&world.launcher.dirs_for(&chosen).mods_dir()).contains("root-1.jar"));
}

/// An install whose download cannot complete (here: the network is off and
/// the store copy is damaged) changes nothing in the profile.
#[test]
fn an_interrupted_install_leaves_the_profile_exactly_as_it_was() {
    let world = world();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();
    install(&world, &mut chosen, "lib");
    let before = chosen.mods.clone();

    // The root build's bytes are damaged in the store, so installing it would
    // have to download them again -- and this world is offline.
    let root_file = world
        .index
        .files
        .lock()
        .unwrap()
        .iter()
        .find(|f| f.project.id == "root")
        .cloned()
        .unwrap();
    std::fs::write(
        store(&world).path_for(&root_file.hash.sha1).unwrap(),
        b"half a download",
    )
    .unwrap();

    let installed: Vec<_> = chosen.mods.iter().cloned().collect();
    let plan = world
        .catalog
        .plan_install(
            &ProjectRef::new(Provider::Modrinth, "root"),
            None,
            &Target::of(&chosen),
            &installed,
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    let error = world
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
        .unwrap_err();
    assert!(
        matches!(
            error,
            BrowseError::Blocked { .. } | BrowseError::Unreachable { .. }
        ),
        "{error:?}"
    );
    assert_eq!(chosen.mods, before, "all or nothing");

    // And a cancelled install likewise changes nothing.
    let cancel = Cancel::new();
    cancel.cancel();
    let cancelled = world
        .catalog
        .install(
            chosen.id.as_str(),
            &plan,
            &Target::of(&chosen),
            &mut chosen.mods,
            InstallOptions::default(),
            &grant(),
            &cancel,
            &mut |_| {},
        )
        .unwrap_err();
    assert!(matches!(cancelled, BrowseError::Cancelled), "{cancelled:?}");
    assert_eq!(chosen.mods, before);
}

/// A Forge mod cannot be installed into a Legacy Fabric profile by the
/// browser, however it is asked for.
#[test]
fn a_forge_build_is_never_installed_into_a_legacy_fabric_profile() {
    let world = world();
    let mut forge = stored_file(&world.paths, "root", "9", &[]);
    forge.loaders = ["forge".to_owned()].into_iter().collect();
    world.index.files.lock().unwrap().push(forge);
    world.catalog.cache().clear();
    let mut chosen = profile("chosen");
    world.launcher.save_profile(&chosen).unwrap();

    // Asked for by its exact version id.
    let plan = world
        .catalog
        .plan_install(
            &ProjectRef::new(Provider::Modrinth, "root"),
            Some("root-9"),
            &Target::of(&chosen),
            &[],
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    assert!(plan.is_blocked(), "an incompatible build is a blocker");
    let refused = world
        .catalog
        .install(
            chosen.id.as_str(),
            &plan,
            &Target::of(&chosen),
            &mut chosen.mods,
            InstallOptions {
                accept_unverified: true,
                accept_possible_duplicates: true,
            },
            &grant(),
            &Cancel::new(),
            &mut |_| {},
        )
        .unwrap_err();
    assert!(
        matches!(refused, BrowseError::Incompatible(_)),
        "{refused:?}"
    );
    assert!(chosen.mods.is_empty());
}

/// A cycle in declared dependencies ends rather than looping, and installs
/// each project once.
#[test]
fn a_dependency_cycle_is_planned_once_and_terminates() {
    let world = world();
    world
        .index
        .files
        .lock()
        .unwrap()
        .push(stored_file(&world.paths, "lib", "2", &["root"]));
    world.catalog.cache().clear();
    let chosen = profile("chosen");
    let plan = world
        .catalog
        .plan_install(
            &ProjectRef::new(Provider::Modrinth, "root"),
            None,
            &Target::of(&chosen),
            &[],
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    assert_eq!(
        plan.dependencies.len(),
        1,
        "the library once, not again for the cycle"
    );
    assert!(!plan.is_blocked(), "{:?}", plan.blockers);
}
