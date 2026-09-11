//! End-to-end tests for the installation engine.
//!
//! The offline tests drive the whole lifecycle — install, corrupt, detect,
//! repair — by placing files directly, which lets them assert the interesting
//! properties without a network. The `#[ignore]`d test at the bottom does the
//! real thing against Mojang's own hosts.
//!
//! A note on what is *not* tested offline: the downloader's network path.
//! The allowlist only permits Mojang and Microsoft hosts over HTTPS, so a local
//! test server cannot be pointed at. That is the allowlist working as intended,
//! and it means the transfer path is covered by the live test and by unit tests
//! of its pieces (hashing, partial handling, finalisation) rather than by a
//! mocked socket.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::path::PathBuf;

use launcher_core::install::download::{partial_path, sha1_hex, sweep_partials, Expectation};
use launcher_core::install::{
    ArtifactCache, ArtifactResolver, AssetManager, DefectKind, DownloadRequest, InstallService,
    InstallationState, InstallationVerifier, NativeLibraryManager, ResolvedInstall,
    SUPPORTED_VERSION,
};
use launcher_core::minecraft_runtime::version::{Os, OsFamily, VersionDetail};
use launcher_core::network::{
    Destination, Feature, HttpClient, NetworkJournal, NetworkPolicy, Purpose, RequestDescriptor,
};
use launcher_core::paths::DataPaths;
use launcher_core::Cancel;

const WINDOWS: Os = Os {
    family: OsFamily::Windows,
    is_64bit: true,
};

/// A trimmed but structurally faithful 1.8.9 document.
const VERSION_JSON: &str = r#"{
  "id": "1.8.9",
  "mainClass": "net.minecraft.client.main.Main",
  "assets": "1.8",
  "minecraftArguments": "--username ${auth_player_name} --accessToken ${auth_access_token}",
  "javaVersion": { "component": "jre-legacy", "majorVersion": 8 },
  "assetIndex": {
    "id": "1.8",
    "sha1": "f6ad102bcaa53b1a58358f16e376d548d44933ec",
    "size": 78494,
    "url": "https://launchermeta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json"
  },
  "logging": {
    "client": {
      "argument": "-Dlog4j.configurationFile=${path}",
      "file": {
        "id": "client-1.7.xml",
        "sha1": "50c9cc4af6d853d9fc137c84bcd153e2bd3a9a82",
        "size": 966,
        "url": "https://launcher.mojang.com/v1/objects/50c9cc4af6d853d9fc137c84bcd153e2bd3a9a82/client-1.7.xml"
      },
      "type": "log4j2-xml"
    }
  },
  "downloads": {
    "client": {
      "sha1": "b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd",
      "size": 8461484,
      "url": "https://launcher.mojang.com/v1/objects/b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd/client.jar"
    }
  },
  "libraries": [
    {
      "name": "com.google.guava:guava:17.0",
      "downloads": { "artifact": {
        "sha1": "9c6ef172e8de35fd8d4d8783e4821e57cdef7445", "size": 2251809,
        "path": "com/google/guava/guava/17.0/guava-17.0.jar",
        "url": "https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar"
      }}
    },
    {
      "name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
      "downloads": { "classifiers": {
        "natives-windows": {
          "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 100,
          "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-windows.jar",
          "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-windows.jar"
        }
      }},
      "natives": { "windows": "natives-windows" },
      "extract": { "exclude": ["META-INF/"] }
    }
  ]
}"#;

struct Fixture {
    _tmp: tempfile::TempDir,
    paths: DataPaths,
    resolver: ArtifactResolver,
}

impl Fixture {
    fn new() -> Self {
        Self::rooted_at(None)
    }

    /// `suffix` lets a test put the data root behind an awkward directory name.
    fn rooted_at(suffix: Option<&str>) -> Self {
        let tmp = tempfile::tempdir().expect("tempdir");
        let root = match suffix {
            Some(s) => tmp.path().join(s),
            None => tmp.path().to_path_buf(),
        };
        let paths = DataPaths::rooted_at(root);
        paths.ensure().unwrap();
        let cache = ArtifactCache::new(paths.clone());
        cache.ensure().unwrap();
        Self {
            _tmp: tmp,
            paths,
            resolver: ArtifactResolver::new(cache),
        }
    }

    fn resolve(&self) -> ResolvedInstall {
        let version: VersionDetail = serde_json::from_str(VERSION_JSON).unwrap();
        self.resolver
            .resolve(&version, WINDOWS, Feature::ProfileInstall)
            .expect("the sample 1.8.9 document should resolve")
    }

    fn natives_dir(&self) -> PathBuf {
        self.paths.root().join("natives")
    }
}

/// Write a file whose bytes hash to what the request expects.
fn satisfy(item: &DownloadRequest, content: &[u8]) {
    assert_eq!(
        sha1_hex(content),
        item.expect.sha1,
        "test fixture content must match the declared hash"
    );
    std::fs::create_dir_all(item.target.parent().unwrap()).unwrap();
    std::fs::write(&item.target, content).unwrap();
}

/// Place a file with the *right* name but the wrong bytes.
fn corrupt(item: &DownloadRequest) {
    std::fs::create_dir_all(item.target.parent().unwrap()).unwrap();
    std::fs::write(&item.target, b"CORRUPTED").unwrap();
}

/// Fabricate a resolved install whose expectations match content we control,
/// so the lifecycle can be driven without downloading anything.
fn synthetic(fixture: &Fixture) -> (ResolvedInstall, Vec<(PathBuf, Vec<u8>)>) {
    let cache = fixture.resolver.cache();
    let mut contents = Vec::new();

    let make = |name: &str, target: PathBuf, body: &[u8]| DownloadRequest {
        name: name.to_owned(),
        request: RequestDescriptor::new(
            Destination::LibrariesMinecraft,
            Purpose::DownloadGameArtifact,
            Feature::ProfileInstall,
            "/x",
        ),
        expect: Expectation::new(sha1_hex(body), Some(body.len() as u64)),
        target,
    };

    let client_body = b"client-jar-bytes".to_vec();
    let client = make(
        "1.8.9.jar",
        cache.client_jar("1.8.9").unwrap(),
        &client_body,
    );
    contents.push((client.target.clone(), client_body));

    let mut libraries = Vec::new();
    for (n, body) in [
        ("guava", b"guava-bytes".to_vec()),
        ("gson", b"gson-bytes".to_vec()),
    ] {
        let item = make(
            n,
            cache.library_path(&format!("{n}/{n}.jar")).unwrap(),
            &body,
        );
        contents.push((item.target.clone(), body));
        libraries.push(item);
    }

    let asset_body = b"asset-bytes".to_vec();
    let asset_hash = sha1_hex(&asset_body);
    let asset = make(
        "minecraft/sounds/x.ogg",
        cache.asset_object(&asset_hash).unwrap(),
        &asset_body,
    );
    contents.push((asset.target.clone(), asset_body));

    let resolved = ResolvedInstall {
        version_id: "1.8.9".into(),
        main_class: "net.minecraft.client.main.Main".into(),
        argument_template: Some("--username ${auth_player_name}".into()),
        required_java_major: 8,
        client,
        libraries,
        natives: Vec::new(),
        asset_index: None,
        asset_index_id: Some("1.8".into()),
        assets: vec![asset],
        logging: None,
    };
    (resolved, contents)
}

fn install_everything(resolved: &ResolvedInstall, contents: &[(PathBuf, Vec<u8>)]) {
    for item in resolved.all_downloads() {
        let body = contents
            .iter()
            .find(|(p, _)| p == &item.target)
            .map(|(_, b)| b.clone())
            .expect("every artifact should have fixture content");
        satisfy(item, &body);
    }
}

// ---------------------------------------------------------------------------
// Version metadata and resolution
// ---------------------------------------------------------------------------

#[test]
fn the_official_1_8_9_shape_resolves_to_a_complete_artifact_set() {
    let f = Fixture::new();
    let plan = f.resolve();

    assert_eq!(plan.version_id, SUPPORTED_VERSION);
    assert_eq!(plan.main_class, "net.minecraft.client.main.Main");
    assert_eq!(plan.required_java_major, 8);
    assert!(plan.argument_template.is_some());
    assert_eq!(plan.libraries.len(), 1);
    assert_eq!(plan.natives.len(), 1);
    assert_eq!(plan.asset_index_id.as_deref(), Some("1.8"));
    assert!(plan.logging.is_some(), "1.8.9 declares a log4j2 config");
}

#[test]
fn every_resolved_target_is_inside_the_shared_cache() {
    let f = Fixture::new();
    let plan = f.resolve();
    for item in plan.all_downloads() {
        assert!(
            f.resolver.cache().contains(&item.target),
            "{} would be written outside the cache",
            item.name
        );
    }
}

#[test]
fn the_logging_config_is_resolved_and_its_argument_substituted() {
    let f = Fixture::new();
    let plan = f.resolve();
    let logging = plan.logging.as_ref().unwrap();

    assert_eq!(logging.download.expect.size, Some(966));
    assert_eq!(
        logging.download.request.destination,
        Destination::LauncherMojang
    );
    // `jvm_argument` returns None for anything that is not a log4j configuration
    // property: it is the only route by which a remote document reaches the JVM
    // command line, so it is checked rather than trusted. The real 1.8.9
    // document asks for exactly that property, so it is accepted.
    let arg = logging
        .jvm_argument()
        .expect("1.8.9 asks for a log4j configuration property");
    assert!(arg.starts_with("-Dlog4j.configurationFile="));
    assert!(!arg.contains("${path}"));
}

// ---------------------------------------------------------------------------
// The lifecycle: install, corrupt, detect, repair
// ---------------------------------------------------------------------------

#[test]
fn a_fresh_data_root_reports_not_installed() {
    let f = Fixture::new();
    let (resolved, _) = synthetic(&f);
    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());

    assert_eq!(report.state, InstallationState::NotInstalled);
    assert!(!report.state.is_playable());
    assert!(report.state.needs_repair());
}

#[test]
fn a_fully_populated_installation_verifies_as_installed() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());
    assert_eq!(report.state, InstallationState::Installed);
    assert!(report.is_complete());
    assert_eq!(report.verified, report.total);
}

#[test]
fn corrupting_one_artifact_is_detected_and_named() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    corrupt(&resolved.libraries[1]);

    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());
    assert_eq!(report.state, InstallationState::Corrupted);
    assert_eq!(report.defects.len(), 1);
    assert_eq!(report.defects[0].name, "gson");
    assert_eq!(report.defects[0].kind, DefectKind::HashMismatch);
}

#[test]
fn deleting_an_artifact_reports_repair_required_not_corruption() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    std::fs::remove_file(&resolved.assets[0].target).unwrap();

    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());
    assert_eq!(report.state, InstallationState::RepairRequired);
    assert_eq!(report.defects[0].kind, DefectKind::Missing);
}

#[test]
fn the_repair_set_is_the_broken_subset_only() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    corrupt(&resolved.libraries[0]);
    std::fs::remove_file(&resolved.assets[0].target).unwrap();

    let v = InstallationVerifier::new();
    let report = v.verify(&resolved, &f.natives_dir());
    let set = v.repair_set(&resolved, &report);

    assert_eq!(report.total, 4);
    assert_eq!(
        set.len(),
        2,
        "a repair must fetch two files, not the whole installation"
    );
    let names: Vec<&str> = set.iter().map(|d| d.name.as_str()).collect();
    assert!(names.contains(&"guava"));
    assert!(names.contains(&"minecraft/sounds/x.ogg"));
}

#[test]
fn repair_discards_corrupt_bytes_so_they_cannot_be_mistaken_for_present() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);
    corrupt(&resolved.client);

    let v = InstallationVerifier::new();
    let report = v.verify(&resolved, &f.natives_dir());
    assert_eq!(v.discard_corrupt(&report), 1);

    assert!(
        !resolved.client.target.exists(),
        "the corrupt file must be removed, or the downloader would skip it as present"
    );
    assert!(
        resolved.libraries[0].target.exists(),
        "good files are untouched"
    );
}

#[test]
fn repair_never_reads_or_writes_profile_data() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);
    corrupt(&resolved.client);

    // Everything a user would be upset to lose.
    let profile = f.paths.profiles_dir().join("main/minecraft");
    for (rel, body) in [
        ("saves/MyWorld/level.dat", &b"precious"[..]),
        ("screenshots/2026-01-01.png", &b"png"[..]),
        ("options.txt", &b"fov:90"[..]),
        ("mods/CoolMod.jar", &b"jar"[..]),
        ("config/settings.cfg", &b"cfg"[..]),
    ] {
        let path = profile.join(rel);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, body).unwrap();
    }

    let v = InstallationVerifier::new();
    let report = v.verify(&resolved, &f.natives_dir());
    v.discard_corrupt(&report);

    for rel in [
        "saves/MyWorld/level.dat",
        "screenshots/2026-01-01.png",
        "options.txt",
        "mods/CoolMod.jar",
        "config/settings.cfg",
    ] {
        assert!(
            profile.join(rel).exists(),
            "{rel} must survive a repair; it is not ours to delete"
        );
    }
    // And nothing profile-owned was even considered a defect.
    assert!(!report.defects.iter().any(|d| d.path.starts_with(&profile)));
}

#[test]
fn installation_state_is_derived_from_disk_not_remembered() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    let v = InstallationVerifier::new();
    assert_eq!(
        v.verify(&resolved, &f.natives_dir()).state,
        InstallationState::Installed
    );

    // Something outside the launcher removes a file.
    std::fs::remove_file(&resolved.libraries[0].target).unwrap();

    assert_eq!(
        v.verify(&resolved, &f.natives_dir()).state,
        InstallationState::RepairRequired,
        "the state must follow the filesystem, with no cached flag to disagree"
    );
}

// ---------------------------------------------------------------------------
// Interrupted downloads and atomic writes
// ---------------------------------------------------------------------------

#[test]
fn an_interrupted_download_leaves_no_usable_artifact() {
    let f = Fixture::new();
    let (resolved, _) = synthetic(&f);
    let target = &resolved.client.target;

    // Exactly what a crash mid-transfer leaves behind: a `.partial` with some
    // bytes in it, and no real file.
    std::fs::create_dir_all(target.parent().unwrap()).unwrap();
    std::fs::write(partial_path(target), b"half of a jar").unwrap();

    assert!(
        !target.exists(),
        "a partial transfer must never occupy the real filename"
    );

    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());
    assert!(
        report.defects.iter().any(|d| d.name == "1.8.9.jar"),
        "the interrupted artifact must be reported as outstanding"
    );
    assert!(!report.state.is_playable());
}

#[test]
fn stale_partials_are_swept_before_a_repair() {
    let f = Fixture::new();
    let (resolved, contents) = synthetic(&f);
    install_everything(&resolved, &contents);

    // Litter from earlier crashes, at two depths.
    let a = partial_path(&resolved.client.target);
    let b = partial_path(&resolved.libraries[0].target);
    std::fs::write(&a, b"junk").unwrap();
    std::fs::write(&b, b"junk").unwrap();

    let removed = sweep_partials(&f.resolver.cache().root());
    assert_eq!(removed, 2);
    assert!(!a.exists());
    assert!(!b.exists());
    assert!(
        resolved.client.target.exists(),
        "sweeping must not touch real artifacts"
    );
}

#[test]
fn a_partial_is_never_counted_as_the_artifact() {
    let f = Fixture::new();
    let (resolved, _) = synthetic(&f);

    // A partial whose *content* would hash correctly is still not the artifact,
    // because it does not have the artifact's name.
    let target = &resolved.client.target;
    std::fs::create_dir_all(target.parent().unwrap()).unwrap();
    std::fs::write(partial_path(target), b"client-jar-bytes").unwrap();

    let report = InstallationVerifier::new().verify(&resolved, &f.natives_dir());
    assert!(report.defects.iter().any(|d| d.name == "1.8.9.jar"));
}

// ---------------------------------------------------------------------------
// Path safety
// ---------------------------------------------------------------------------

#[test]
fn a_traversing_library_path_cannot_escape_the_cache() {
    let f = Fixture::new();
    for hostile in [
        "../../../../etc/passwd",
        "..\\..\\..\\Windows\\System32\\evil.dll",
        "a/../../../../outside.jar",
    ] {
        assert!(
            f.resolver.cache().library_path(hostile).is_err(),
            "{hostile:?} must be refused"
        );
    }
}

#[test]
fn an_asset_hash_that_is_a_path_is_refused() {
    let f = Fixture::new();
    assert!(f
        .resolver
        .cache()
        .asset_object("../../../../etc/passwd")
        .is_err());
}

#[test]
fn archive_traversal_during_native_extraction_is_refused() {
    use std::io::Write as _;

    let f = Fixture::new();
    let plan = f.resolve();

    let archive = &plan.natives[0].download.target;
    std::fs::create_dir_all(archive.parent().unwrap()).unwrap();

    let mut buf = std::io::Cursor::new(Vec::new());
    {
        let mut w = zip::ZipWriter::new(&mut buf);
        let opts: zip::write::FileOptions<'_, ()> =
            zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
        w.start_file("../../escaped.dll", opts).unwrap();
        w.write_all(b"pwned").unwrap();
        w.finish().unwrap();
    }
    std::fs::write(archive, buf.into_inner()).unwrap();

    let dest = f.natives_dir();
    assert!(
        NativeLibraryManager::new()
            .extract_all(&plan.natives, &dest)
            .is_err(),
        "zip slip must fail the install"
    );
    assert!(!f.paths.root().join("escaped.dll").exists());
    assert!(!dest.parent().unwrap().join("escaped.dll").exists());
}

#[test]
fn only_shared_libraries_come_out_of_a_natives_archive() {
    use std::io::Write as _;

    let f = Fixture::new();
    let plan = f.resolve();
    let archive = &plan.natives[0].download.target;
    std::fs::create_dir_all(archive.parent().unwrap()).unwrap();

    let mut buf = std::io::Cursor::new(Vec::new());
    {
        let mut w = zip::ZipWriter::new(&mut buf);
        let opts: zip::write::FileOptions<'_, ()> =
            zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
        for (name, body) in [
            ("lwjgl64.dll", &b"native"[..]),
            ("evil.bat", &b"@echo pwned"[..]),
            ("payload.jar", &b"jar"[..]),
            ("META-INF/MANIFEST.MF", &b"manifest"[..]),
        ] {
            w.start_file(name, opts).unwrap();
            w.write_all(body).unwrap();
        }
        w.finish().unwrap();
    }
    std::fs::write(archive, buf.into_inner()).unwrap();

    let dest = f.natives_dir();
    let out = NativeLibraryManager::new()
        .extract_all(&plan.natives, &dest)
        .unwrap();

    assert_eq!(out.files.len(), 1);
    assert!(dest.join("lwjgl64.dll").exists());
    assert!(!dest.join("evil.bat").exists());
    assert!(!dest.join("payload.jar").exists());
    assert!(!dest.join("META-INF/MANIFEST.MF").exists());
}

// ---------------------------------------------------------------------------
// Awkward paths
// ---------------------------------------------------------------------------

#[test]
fn the_whole_lifecycle_works_under_a_path_with_spaces_and_unicode() {
    // A data root of the kind a real user actually has.
    let f = Fixture::rooted_at(Some(
        "Jos\u{e9} M\u{fc}ller/Application Data/\u{6e38}\u{620f}",
    ));
    let (resolved, contents) = synthetic(&f);

    install_everything(&resolved, &contents);
    let v = InstallationVerifier::new();
    assert_eq!(
        v.verify(&resolved, &f.natives_dir()).state,
        InstallationState::Installed
    );

    corrupt(&resolved.libraries[0]);
    let report = v.verify(&resolved, &f.natives_dir());
    assert_eq!(report.state, InstallationState::Corrupted);
    assert_eq!(v.discard_corrupt(&report), 1);

    // And partial handling copes with the same paths.
    let p = partial_path(&resolved.client.target);
    std::fs::write(&p, b"x").unwrap();
    assert_eq!(sweep_partials(&f.resolver.cache().root()), 1);
}

#[test]
fn asset_names_with_spaces_and_unicode_do_not_affect_storage() {
    let f = Fixture::new();
    let manager = AssetManager::new(f.resolver.clone());
    // Built rather than written inline: a byte-string literal cannot hold
    // non-ASCII, and escaping it here keeps the source ASCII-clean.
    let name = "minecraft/lang/\u{4e2d}\u{6587} file.lang";
    let json = format!(
        r#"{{"objects":{{"{name}":{{"hash":"bdf48ef6b5d0d23bbb02e17d04865216179f510a","size":5}}}}}}"#
    );
    let index = AssetManager::parse(json.as_bytes()).unwrap();

    let objects = manager
        .resolve_objects(&index, Feature::ProfileInstall)
        .unwrap();
    assert_eq!(objects.len(), 1);
    // Stored by hash, so the awkward logical name never touches the filesystem.
    assert!(objects[0]
        .target
        .ends_with("bd/bdf48ef6b5d0d23bbb02e17d04865216179f510a"));
}

// ---------------------------------------------------------------------------
// Service wiring
// ---------------------------------------------------------------------------

#[test]
fn the_service_makes_no_requests_until_asked() {
    let tmp = tempfile::tempdir().unwrap();
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().unwrap();
    let journal = NetworkJournal::new();
    let http = HttpClient::new(NetworkPolicy::default(), journal.clone(), "test/0.0");
    let _service = InstallService::new(http, paths);

    assert_eq!(journal.summary().total, 0);
}

#[test]
fn offline_mode_refuses_to_prepare_rather_than_half_installing() {
    let tmp = tempfile::tempdir().unwrap();
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().unwrap();
    let http = HttpClient::new(
        NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        },
        NetworkJournal::new(),
        "test/0.0",
    );
    let service = InstallService::new(http, paths);

    assert!(service
        .prepare(SUPPORTED_VERSION, Feature::ProfileInstall, &Cancel::new())
        .is_err());
}

#[test]
fn an_unsupported_version_is_refused_by_the_resolver() {
    let f = Fixture::new();
    let mut version: VersionDetail = serde_json::from_str(VERSION_JSON).unwrap();
    version.id = "1.20.4".into();

    assert!(
        f.resolver
            .resolve(&version, WINDOWS, Feature::ProfileInstall)
            .is_err(),
        "only 1.8.9 is supported at this stage, and that should be explicit"
    );
}

// ---------------------------------------------------------------------------
// The real thing
// ---------------------------------------------------------------------------

/// Resolve a genuine 1.8.9 installation from Mojang and check the artifact set.
///
/// Downloads only metadata (~80 KB), not the 139 MB of artifacts, so it is cheap
/// enough to run deliberately:
///
/// ```text
/// cargo test -p launcher-core --test install_pipeline -- --ignored --nocapture
/// ```
#[test]
#[ignore = "makes real network requests to Mojang"]
fn a_real_1_8_9_installation_resolves_completely() {
    let tmp = tempfile::tempdir().unwrap();
    let paths = DataPaths::rooted_at(tmp.path());
    paths.ensure().unwrap();
    let journal = NetworkJournal::new();
    let http = HttpClient::new(NetworkPolicy::default(), journal.clone(), "test/0.0");
    let service = InstallService::new(http, paths.clone());

    let resolved = service
        .prepare(SUPPORTED_VERSION, Feature::ProfileInstall, &Cancel::new())
        .expect("1.8.9 should resolve against the live metadata");

    // The shape recorded in docs/research/architecture-options.md.
    assert_eq!(resolved.version_id, "1.8.9");
    assert_eq!(resolved.main_class, "net.minecraft.client.main.Main");
    assert_eq!(resolved.required_java_major, 8);
    assert!(resolved.argument_template.is_some());
    assert_eq!(resolved.asset_index_id.as_deref(), Some("1.8"));
    assert!(resolved.logging.is_some());

    // 1.8.9 has 734 asset objects; assert a floor rather than the exact number
    // so a Mojang-side addition does not fail the build.
    assert!(
        resolved.assets.len() > 700,
        "expected the full asset set, got {}",
        resolved.assets.len()
    );
    assert!(!resolved.libraries.is_empty());
    assert!(!resolved.natives.is_empty(), "this platform needs natives");

    // Every artifact must be allowlisted and land inside the cache.
    for item in resolved.all_downloads() {
        assert!(
            Destination::ALL.contains(&item.request.destination),
            "{} targets an unlisted host",
            item.name
        );
        assert!(service.cache().contains(&item.target));
        assert_eq!(
            item.expect.sha1.len(),
            40,
            "{} has no usable hash",
            item.name
        );
    }

    // The game itself is not installed. `prepare` did fetch the asset index --
    // it has to, to enumerate the objects -- so exactly one of the artifacts is
    // already on disk and the honest answer is `RepairRequired`, not
    // `NotInstalled`. That distinction is the point: the verifier reports what
    // is actually on disk rather than a remembered flag.
    let natives = paths.root().join("natives");
    let report = service.verify(&resolved, &natives);
    assert_eq!(report.state, InstallationState::RepairRequired);
    assert_eq!(report.total, resolved.all_downloads().len());
    assert_eq!(
        report.defects.len(),
        report.total - 1,
        "only the asset index should be present after `prepare`"
    );
    assert!(
        report.defects.iter().all(|d| d.kind == DefectKind::Missing),
        "a freshly fetched artifact must not hash badly"
    );
    let index = resolved.asset_index.as_ref().expect("1.8.9 has an index");
    assert!(
        !report.defects.iter().any(|d| d.name == index.name),
        "the asset index was downloaded, so it must not be reported missing"
    );

    println!(
        "resolved 1.8.9: {} artifacts, {}, {} libraries, {} natives, {} assets",
        resolved.all_downloads().len(),
        launcher_core::services::human_bytes(resolved.declared_bytes()),
        resolved.libraries.len(),
        resolved.natives.len(),
        resolved.assets.len()
    );

    // Only metadata hosts were contacted to get this far.
    for entry in journal.recent(50) {
        assert!(
            matches!(
                entry.destination,
                Destination::PistonMeta | Destination::LauncherMeta
            ),
            "unexpected host during resolution: {}",
            entry.destination
        );
    }
}
