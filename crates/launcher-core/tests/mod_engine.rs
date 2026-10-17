//! End-to-end tests for the loader and mod engine.
//!
//! The unit tests in each module cover their own logic; these cover the chain:
//! a profile's configuration, through resolution, into a materialized mods
//! directory, with the policy engine refusing what the research says it must.
//!
//! Everything runs against a real temporary data root. The one `#[ignore]`d test
//! talks to Legacy Fabric's real metadata service.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::path::PathBuf;

use launcher_core::loaders::{self, LoaderError, ModDirectorySemantics};
use launcher_core::mods::{
    catalog, ConflictKind, ModDefinition, ModEngine, ModError, ModId, ProfileMod,
    ProfileModConfiguration, Severity,
};
use launcher_core::network::{Destination, Feature, HttpClient, NetworkJournal, NetworkPolicy};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{Loader, ProfileDirs, ProfileId};

/// A real, minimal zip. The store refuses anything that is not an archive.
const EMPTY_ZIP: &[u8] = &[
    0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
];

struct Fixture {
    tmp: tempfile::TempDir,
    paths: DataPaths,
    engine: ModEngine,
    dirs: ProfileDirs,
}

impl Fixture {
    fn new() -> Self {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().expect("data dirs");
        let dirs = ProfileDirs::new(&paths, &ProfileId::new("main").unwrap());
        dirs.ensure().expect("profile dirs");
        Self {
            engine: ModEngine::new(paths.clone()),
            tmp,
            paths,
            dirs,
        }
    }

    /// A jar on the user's own disk, outside launcher storage.
    fn user_jar(&self, name: &str, contents: &[u8]) -> PathBuf {
        let dir = self.tmp.path().join("downloads");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        let mut bytes = EMPTY_ZIP.to_vec();
        bytes.extend_from_slice(contents);
        std::fs::write(&path, bytes).unwrap();
        path
    }
}

fn id(raw: &str) -> ModId {
    ModId::new(raw).unwrap()
}

fn http() -> HttpClient {
    HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0")
}

// ===========================================================================
// Policy: what the research forbids, the engine refuses
// ===========================================================================

/// The headline rule. `mod-policies.md` §7 names these explicitly under "never
/// auto-downloaded, under any circumstance".
#[test]
fn the_engine_refuses_to_download_anything_the_research_forbids() {
    let f = Fixture::new();

    for name in ["optifine", "memoryfix", "mousedelayfix"] {
        let definition = catalog::find(&id(name)).expect(name);
        let error = f.engine.fetch(&definition).expect_err("must refuse");
        assert!(
            matches!(error, ModError::RefusedByPolicy { .. }),
            "{name}: {error:?}"
        );
        // And the refusal is useful: it names the mod and says what to do.
        let message = error.to_string();
        assert!(message.contains(&definition.name), "{message}");
        assert!(message.contains("yourself"), "{message}");
    }
}

/// `OptiFine` specifically, because it is the one users ask for most.
#[test]
fn optifine_is_never_fetchable_and_says_why() {
    let definition = catalog::find(&id("optifine")).expect("in the catalogue");

    assert!(!definition.is_launcher_fetchable());
    assert!(!definition.redistribution.may_fetch());
    assert!(!definition.redistribution.may_bundle());

    let why = definition.why_not_fetchable().expect("must explain");
    assert!(why.contains("proprietary"), "{why}");

    // And the user is told where to get it themselves.
    match &definition.download {
        Some(launcher_core::mods::DownloadSource::UserMustSupply { where_to_get, .. }) => {
            assert!(where_to_get.contains("optifine.net"), "{where_to_get}");
        }
        other => panic!("expected UserMustSupply, got {other:?}"),
    }
}

/// Nothing is bundled, whatever its licence. The permissive ones too.
#[test]
fn nothing_in_the_catalogue_may_be_bundled() {
    for definition in catalog::entries() {
        assert!(
            !definition.redistribution.may_bundle(),
            "{} must never be bundled",
            definition.id
        );
    }
}

// ===========================================================================
// User-supplied mods
// ===========================================================================

#[test]
fn adding_a_jar_copies_it_into_the_store_and_leaves_the_original_alone() {
    let f = Fixture::new();
    let source = f.user_jar("CoolMod-1.2.jar", b"cool");
    let mut config = ProfileModConfiguration::default();

    let entry = f.engine.add_user_supplied(&mut config, &source).unwrap();

    assert!(source.is_file(), "the user's own file must stay put");
    assert!(f.engine.store().contains(&entry.sha1));
    assert_eq!(entry.id.as_str(), "coolmod-1-2");
    assert_eq!(entry.file_name, "CoolMod-1.2.jar");
    assert!(entry.enabled);
    assert_eq!(config.len(), 1);
}

/// Two profiles wanting the same mod hold one copy of the bytes.
#[test]
fn identical_jars_share_one_file_in_the_store() {
    let f = Fixture::new();
    let a = f.user_jar("a.jar", b"same bytes");
    let b = f.user_jar("b.jar", b"same bytes");

    let mut config = ProfileModConfiguration::default();
    let first = f.engine.add_user_supplied(&mut config, &a).unwrap();
    let second = f.engine.add_user_supplied(&mut config, &b).unwrap();

    assert_eq!(first.sha1, second.sha1);
    assert_eq!(
        f.engine.store().path_for(&first.sha1).unwrap(),
        f.engine.store().path_for(&second.sha1).unwrap()
    );
}

#[test]
fn a_file_that_is_not_an_archive_is_refused() {
    let f = Fixture::new();
    let fake = f.tmp.path().join("not-a-mod.jar");
    std::fs::write(&fake, b"just some text").unwrap();

    let mut config = ProfileModConfiguration::default();
    assert!(matches!(
        f.engine.add_user_supplied(&mut config, &fake),
        Err(ModError::NotAJar { .. })
    ));
    assert!(config.is_empty());
}

/// A user may install whatever they like; they are told we cannot vouch for it.
#[test]
fn an_unknown_jar_resolves_but_is_flagged_as_unvouched_for() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    f.engine
        .add_user_supplied(&mut config, &f.user_jar("mystery.jar", b"?"))
        .unwrap();

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(resolution.is_launchable());
    assert_eq!(resolution.effective.len(), 1);
    assert_eq!(resolution.problems[0].severity(), Severity::Advisory);
}

// ===========================================================================
// Resolution through the engine
// ===========================================================================

fn config_with(ids: &[&str]) -> ProfileModConfiguration {
    let mut config = ProfileModConfiguration::default();
    for (index, name) in ids.iter().enumerate() {
        let sha1 = format!("{index:040}");
        config.upsert(ProfileMod::new(id(name), sha1, format!("{name}.jar")));
    }
    config
}

/// `mod-policies.md` §8 documents this conflict specifically. It is the reason
/// the resolver exists.
#[test]
fn our_optimizations_alongside_optifine_is_reported_not_crashed_into() {
    let f = Fixture::new();
    let config = config_with(&[catalog::FATHOM_OPTIMIZATIONS, "optifine", "fabric-api"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    let names = launcher_core::mods::NameLookup::from_definitions(&catalog::entries());

    let conflict = resolution
        .problems
        .iter()
        .find(|p| {
            matches!(
                p,
                launcher_core::mods::Problem::Conflict {
                    kind: ConflictKind::OverlappingOptimization,
                    ..
                }
            )
        })
        .expect("the OptiFine overlap must be reported");

    let message = conflict.message(&names);
    assert!(message.contains("OptiFine"), "{message}");
    assert!(message.contains("renderer"), "{message}");

    // An overlap is a warning, not a refusal: the user may well want both.
    assert_eq!(conflict.severity(), Severity::Warning);
}

/// Fathom's module hooks the game with its own Mixins and asks only for the
/// loader. It was once catalogued as needing Fabric API, which blocked a
/// profile that ran perfectly.
#[test]
fn fathom_alone_needs_nothing_else() {
    let f = Fixture::new();
    let config = config_with(&[catalog::FATHOM_OPTIMIZATIONS]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(resolution.is_launchable(), "{:?}", resolution.problems);
}

/// OptiFine on Legacy Fabric without the bridge that loads it.
#[test]
fn a_missing_dependency_is_named_before_anything_starts() {
    let f = Fixture::new();
    let config = config_with(&["optifine"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(!resolution.is_launchable());

    let names = launcher_core::mods::NameLookup::from_definitions(&catalog::entries());
    let message = resolution.problems[0].message(&names);
    assert!(message.contains("OptiFabric"), "{message}");
    assert!(message.contains("requires"), "{message}");
}

/// OptiFine with OptiFabric is how Fathom's OptiFine profiles run: no
/// "not a Legacy Fabric mod", and OptiFabric is a catalogued mod, not an
/// unknown file.
#[test]
fn adding_the_dependency_resolves_it() {
    let f = Fixture::new();
    let config = config_with(&["optifine", "optifabric"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(resolution.is_launchable(), "{:?}", resolution.problems);
    assert!(
        !resolution.problems.iter().any(|p| matches!(
            p,
            launcher_core::mods::Problem::WrongLoader { .. }
                | launcher_core::mods::Problem::UnknownMod { .. }
        )),
        "{:?}",
        resolution.problems
    );
    assert_eq!(resolution.effective.len(), 2);
}

/// `Patcher` and `PolyPatcher`: one supersedes the other.
#[test]
fn a_superseded_mod_is_reported_as_redundant_not_broken() {
    let f = Fixture::new();
    let config = config_with(&["patcher", "polypatcher"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::Forge);
    let supersedes = resolution
        .problems
        .iter()
        .find(|p| {
            matches!(
                p,
                launcher_core::mods::Problem::Conflict {
                    kind: ConflictKind::Supersedes,
                    ..
                }
            )
        })
        .expect("must be reported");

    // Redundant, not fatal.
    assert_eq!(supersedes.severity(), Severity::Warning);
    assert!(resolution.is_launchable());
}

#[test]
fn a_forge_mod_in_a_fabric_profile_is_refused() {
    let f = Fixture::new();
    // Patcher is Forge-only, and nothing bridges it.
    let config = config_with(&["patcher"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(!resolution.is_launchable());
    assert!(resolution
        .problems
        .iter()
        .any(|p| matches!(p, launcher_core::mods::Problem::WrongLoader { .. })));
}

#[test]
fn mods_on_a_vanilla_profile_are_reported_once() {
    let f = Fixture::new();
    let config = config_with(&["optifine", "patcher", "memoryfix"]);

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::Vanilla);
    assert_eq!(resolution.problems.len(), 1, "{:?}", resolution.problems);
    assert!(matches!(
        resolution.problems[0],
        launcher_core::mods::Problem::LoaderLoadsNoMods { count: 3, .. }
    ));
}

#[test]
fn a_mod_for_another_minecraft_version_is_refused() {
    let f = Fixture::new();
    let config = config_with(&["optifine"]);

    let resolution = f.engine.resolve(&config, "1.12.2", Loader::Forge);
    assert!(!resolution.is_launchable());
    assert!(resolution.problems.iter().any(|p| matches!(
        p,
        launcher_core::mods::Problem::WrongMinecraftVersion { .. }
    )));
}

// ===========================================================================
// Toggling and materialization
// ===========================================================================

#[test]
fn toggling_changes_the_configuration_not_the_filesystem() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    let entry = f
        .engine
        .add_user_supplied(&mut config, &f.user_jar("a.jar", b"a"))
        .unwrap();

    // Materialize once, so the file is there.
    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    f.engine.materialize(&f.dirs, &resolution).unwrap();
    assert!(f.dirs.mods_dir().join("a.jar").is_file());

    // Toggling off touches nothing on disk.
    config.set_enabled(&entry.id, false);
    assert!(
        f.dirs.mods_dir().join("a.jar").is_file(),
        "no renaming, no deletion, until materialization"
    );

    // Materializing then removes it, and the bytes stay in the store.
    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    let outcome = f.engine.materialize(&f.dirs, &resolution).unwrap();
    assert_eq!(outcome.removed, vec!["a.jar".to_owned()]);
    assert!(!f.dirs.mods_dir().join("a.jar").exists());
    assert!(f.engine.store().contains(&entry.sha1));

    // And back on again.
    config.set_enabled(&entry.id, true);
    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    f.engine.materialize(&f.dirs, &resolution).unwrap();
    assert!(f.dirs.mods_dir().join("a.jar").is_file());
}

/// No `.disabled` suffixes, ever. That is the hack this design replaces.
#[test]
fn nothing_is_ever_renamed_with_a_disabled_suffix() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    let entry = f
        .engine
        .add_user_supplied(&mut config, &f.user_jar("a.jar", b"a"))
        .unwrap();

    for enabled in [true, false, true, false] {
        config.set_enabled(&entry.id, enabled);
        let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
        f.engine.materialize(&f.dirs, &resolution).unwrap();

        let names: Vec<String> = std::fs::read_dir(f.dirs.mods_dir())
            .unwrap()
            .flatten()
            .filter_map(|e| e.file_name().to_str().map(str::to_owned))
            .collect();
        assert!(
            !names.iter().any(|n| n.contains(".disabled")),
            "found a renamed file: {names:?}"
        );
    }
}

#[test]
fn materializing_twice_does_nothing_the_second_time() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    f.engine
        .add_user_supplied(&mut config, &f.user_jar("a.jar", b"a"))
        .unwrap();

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(f
        .engine
        .materialize(&f.dirs, &resolution)
        .unwrap()
        .changed_anything());
    assert!(!f
        .engine
        .materialize(&f.dirs, &resolution)
        .unwrap()
        .changed_anything());
}

/// The rule that matters most in this module.
#[test]
fn a_jar_the_user_dropped_in_by_hand_is_adopted_never_deleted() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();

    // They put it straight into the mods folder.
    let theirs = f.dirs.mods_dir().join("HandPlaced.jar");
    std::fs::write(&theirs, EMPTY_ZIP).unwrap();

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    let outcome = f.engine.materialize(&f.dirs, &resolution).unwrap();

    assert!(theirs.is_file(), "it must survive");
    assert_eq!(outcome.foreign, vec!["HandPlaced.jar".to_owned()]);
    assert!(outcome.removed.is_empty());

    // Adopting turns it into a managed mod.
    let adopted = f.engine.adopt_foreign(&mut config, &f.dirs, &outcome);
    assert_eq!(adopted.len(), 1);
    assert_eq!(adopted[0].id.as_str(), "handplaced");
    assert!(config.contains(&id("handplaced")));
    assert!(f.engine.store().contains(&adopted[0].sha1));
    assert!(theirs.is_file(), "and it is still there afterwards");
}

/// Duplicate ids mean two jars claiming to be the same mod, which the loader
/// cannot resolve.
#[test]
fn two_jars_claiming_the_same_mod_block_the_launch() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    config
        .mods
        .push(ProfileMod::new(id("same"), "a".repeat(40), "same-1.0.jar"));
    config
        .mods
        .push(ProfileMod::new(id("same"), "b".repeat(40), "same-2.0.jar"));

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(!resolution.is_launchable());
    assert!(resolution
        .problems
        .iter()
        .any(|p| matches!(p, launcher_core::mods::Problem::DuplicateMod { .. })));
}

#[test]
fn a_mod_whose_bytes_are_gone_is_reported_rather_than_silently_skipped() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    config.upsert(ProfileMod::new(id("gone"), "f".repeat(40), "gone.jar"));

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    let outcome = f.engine.materialize(&f.dirs, &resolution).unwrap();

    assert_eq!(outcome.missing, vec!["gone.jar".to_owned()]);
    assert!(!f.dirs.mods_dir().join("gone.jar").exists());
}

/// A file name from a profile file becomes a path.
#[test]
fn a_traversing_file_name_cannot_escape_the_mods_directory() {
    let f = Fixture::new();
    let mut config = ProfileModConfiguration::default();
    let entry = f
        .engine
        .add_user_supplied(&mut config, &f.user_jar("a.jar", b"a"))
        .unwrap();

    // Tamper with the recorded name, as a hand-edited profile could.
    config.get_mut(&entry.id).unwrap().file_name = "../../escaped.jar".into();

    let resolution = f.engine.resolve(&config, "1.8.9", Loader::LegacyFabric);
    assert!(matches!(
        f.engine.materialize(&f.dirs, &resolution),
        Err(ModError::UnsafeName { .. })
    ));
    assert!(!f.paths.profiles_dir().join("escaped.jar").exists());
    assert!(!f.paths.root().join("escaped.jar").exists());
}

/// A hash from a profile file becomes a path too.
#[test]
fn a_hash_that_is_a_path_is_refused() {
    let f = Fixture::new();
    for hostile in ["../../etc/passwd", "..", "", "not-a-hash"] {
        assert!(
            f.engine.store().path_for(hostile).is_err(),
            "{hostile:?} must be refused"
        );
    }
}

// ===========================================================================
// Loaders
// ===========================================================================

#[test]
fn vanilla_adds_nothing_and_contacts_nobody() {
    let f = Fixture::new();
    let http = http();
    let cache = launcher_core::install::ArtifactCache::new(f.paths.clone());

    let installation = loaders::provider_for(Loader::Vanilla)
        .resolve("1.8.9", "", &cache, &http, Feature::Play)
        .expect("vanilla always resolves");

    assert!(installation.main_class.is_none());
    assert!(installation.libraries.is_empty());
    assert_eq!(installation.mod_directory, ModDirectorySemantics::None);
    assert_eq!(http.journal().summary().total, 0);
}

/// Forge resolves from its own repository the first time, so with the network
/// off it fails as a network refusal -- not as "not implemented", and without
/// writing anything that a later launch could mistake for a resolved build.
#[test]
fn forge_offline_is_a_network_refusal_and_keeps_nothing() {
    let f = Fixture::new();
    let offline = HttpClient::new(
        NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        },
        NetworkJournal::new(),
        "test/0",
    );
    let cache = launcher_core::install::ArtifactCache::new(f.paths.clone());

    let error = loaders::provider_for(Loader::Forge)
        .resolve("1.8.9", "11.15.1.2318", &cache, &offline, Feature::Play)
        .expect_err("nothing kept and nothing reachable");
    assert!(matches!(error, LoaderError::Network(_)), "{error}");
    assert!(!f.paths.metadata_cache_dir().join("forge").exists());

    let provider = loaders::provider_for(Loader::Forge);
    assert!(provider.is_implemented());
    assert!(provider.mod_directory().accepts_mods());
    assert!(provider.needs_version());
}

/// The abstraction has to be complete: every loader in the profile model has a
/// provider, and the two never disagree.
#[test]
fn every_loader_has_a_provider_that_agrees_with_the_model() {
    for loader in Loader::ALL {
        let provider = loaders::provider_for(loader);
        assert_eq!(provider.kind(), loader);
        assert_eq!(provider.is_implemented(), loader.is_implemented());
        assert_eq!(
            provider.mod_directory().accepts_mods(),
            loader.supports_mods()
        );
    }
}

// ===========================================================================
// The real Legacy Fabric metadata
// ===========================================================================

/// Resolve Legacy Fabric 1.8.9 against the project's own metadata service.
///
/// ```text
/// cargo test -p launcher-core --test mod_engine -- --ignored --nocapture
/// ```
#[ignore = "makes real network requests to Legacy Fabric"]
#[test]
fn legacy_fabric_resolves_against_its_own_metadata() {
    let f = Fixture::new();
    let http = http();
    let cache = launcher_core::install::ArtifactCache::new(f.paths.clone());
    let provider = loaders::provider_for(Loader::LegacyFabric);

    let versions = provider
        .available_versions("1.8.9", &http, Feature::ProfileInstall)
        .expect("Legacy Fabric supports 1.8.9");
    assert!(!versions.is_empty());
    println!(
        "{} loader versions; newest {}",
        versions.len(),
        versions[0].version
    );

    let installation = provider
        .resolve("1.8.9", &versions[0].version, &cache, &http, Feature::Play)
        .expect("should resolve");

    println!("main class   {:?}", installation.main_class);
    println!("libraries    {}", installation.libraries.len());
    println!("minimum java {:?}", installation.minimum_java_major);

    assert_eq!(
        installation.main_class.as_deref(),
        Some("net.fabricmc.loader.impl.launch.knot.KnotClient")
    );
    assert!(
        installation.libraries.len() >= 3,
        "loader, mappings, and its libraries"
    );
    assert_eq!(
        installation.mod_directory,
        ModDirectorySemantics::FlatDirectory
    );

    // Every artifact is verifiable and lands inside the shared cache.
    for library in &installation.libraries {
        assert_eq!(
            library.expect.sha1.len(),
            40,
            "{} has no usable hash",
            library.name
        );
        assert!(
            library.target.starts_with(f.paths.libraries_dir()),
            "{} lands outside the cache",
            library.name
        );
        assert!(
            Destination::ALL.contains(&library.request.destination),
            "{} targets an unlisted host",
            library.name
        );
    }

    // The loader and the mappings come first: they intercept class loading.
    assert!(installation.libraries[0].name.contains("fabric-loader"));
    assert!(installation.libraries[1].name.contains("intermediary"));

    // Only the two metadata hosts and the two repositories were contacted.
    for entry in http.journal().recent(100) {
        assert!(
            matches!(
                entry.destination,
                Destination::LegacyFabricMeta
                    | Destination::LegacyFabricMaven
                    // maven.legacyfabric.net 302s artifact requests here. The
                    // hop was re-checked against the allowlist before it was
                    // followed, which is why it is allowed to appear.
                    | Destination::LegacyFabricRepo
                    | Destination::FabricMaven
            ),
            "unexpected host: {}",
            entry.destination
        );
    }
}

/// A definition is a definition wherever it came from; the catalogue's entries
/// must survive a round trip so an export could carry them.
#[test]
fn every_catalogue_entry_round_trips() {
    for definition in catalog::entries() {
        let json = serde_json::to_string(&definition).unwrap();
        let back: ModDefinition = serde_json::from_str(&json).unwrap();
        assert_eq!(definition, back, "{} did not round trip", definition.id);
    }
}
