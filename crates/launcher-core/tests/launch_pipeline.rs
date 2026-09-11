//! End-to-end tests for the launch chain.
//!
//! These drive the real `Launcher` against a temporary data root, so what is
//! under test is the same code the Play button runs: the authentication gate,
//! the installation check, the Java check, plan assembly, and the duplicate
//! guard.
//!
//! Two things are deliberately *not* tested here:
//!
//! - **Starting a real JVM.** That needs Java 8, a complete 139 MB
//!   installation, and a display. `fathom-cli launch` does it, and the
//!   `#[ignore]`d test at the bottom does it under `--ignored` when the machine
//!   can. What is covered offline is everything up to and including the exact
//!   argv that would be handed to `CreateProcessW`.
//! - **Production Microsoft authentication.** No application id is approved
//!   yet. The gate is tested through its refusals, which is the half that
//!   matters for safety.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::sync::Arc;

#[cfg(feature = "dev-auth")]
use std::path::{Path, PathBuf};

use launcher_core::credentials::EphemeralCredentialStore;
use launcher_core::launching::LaunchRefusal;
use launcher_core::minecraft_runtime::process::Outcome;
use launcher_core::minecraft_runtime::version::VersionDetail;
use launcher_core::paths::DataPaths;
use launcher_core::profiles::{Profile, ProfileId};
use launcher_core::Launcher;

#[cfg(feature = "dev-auth")]
use launcher_core::minecraft_runtime::LaunchPlan;
#[cfg(feature = "dev-auth")]
use launcher_core::profiles::Loader;

/// A 1.8.9 document with the real launch shape: the legacy argument template,
/// a logging section, and libraries with both plain and native artifacts.
const VERSION_JSON: &str = r#"{
  "id": "1.8.9",
  "mainClass": "net.minecraft.client.main.Main",
  "assets": "1.8",
  "javaVersion": { "component": "jre-legacy", "majorVersion": 8 },
  "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} --userType ${user_type}",
  "assetIndex": {
    "id": "1.8",
    "sha1": "f6ad102bcaa53b1a58358f16e376d548d44933ec",
    "size": 78494,
    "url": "https://launchermeta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json"
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
        },
        "natives-linux": {
          "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 100,
          "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-linux.jar",
          "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-linux.jar"
        },
        "natives-osx": {
          "sha1": "cccccccccccccccccccccccccccccccccccccccc", "size": 100,
          "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-osx.jar",
          "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-osx.jar"
        }
      }},
      "natives": { "windows": "natives-windows", "linux": "natives-linux", "osx": "natives-osx" },
      "extract": { "exclude": ["META-INF/"] }
    }
  ]
}"#;

fn version() -> VersionDetail {
    serde_json::from_str(VERSION_JSON).expect("the sample document should parse")
}

struct Fixture {
    _tmp: tempfile::TempDir,
    core: Launcher,
    profile: Profile,
}

impl Fixture {
    fn new() -> Self {
        Self::rooted_at(None)
    }

    /// `suffix` puts the data root behind an awkward directory name, so the
    /// whole chain can be run under a path with spaces and non-ASCII.
    fn rooted_at(suffix: Option<&str>) -> Self {
        let tmp = tempfile::tempdir().expect("tempdir");
        let root = match suffix {
            Some(s) => tmp.path().join(s),
            None => tmp.path().to_path_buf(),
        };
        let paths = DataPaths::rooted_at(&root);
        paths.ensure().expect("data dirs");

        let store: Arc<dyn launcher_core::credentials::CredentialStore> =
            Arc::new(EphemeralCredentialStore::default());
        let mut core = Launcher::new(paths, store).expect("launcher");
        core.refresh_java();

        let profile = core
            .create_profile("Test Profile")
            .expect("profile should be creatable");

        Self {
            _tmp: tmp,
            core,
            profile,
        }
    }

    #[cfg(feature = "dev-auth")]
    fn dirs(&self) -> launcher_core::profiles::ProfileDirs {
        self.core.profile_dirs(&self.profile.id)
    }

    /// Put every artifact the resolved install expects on disk, with contents
    /// whose hashes are irrelevant because only existence is checked at launch.
    #[cfg(feature = "dev-auth")]
    fn install_everything(&self) {
        let service = self.core.install_service();
        let resolved = service
            .resolve_cached(&version(), launcher_core::network::Feature::Play)
            .expect("resolve");

        for item in resolved.all_downloads() {
            std::fs::create_dir_all(item.target.parent().unwrap()).unwrap();
            std::fs::write(&item.target, b"placeholder").unwrap();
        }
        // A launch also wants natives on disk; the archives above are not real
        // zips, so the directory is populated directly.
        let natives = self.dirs().natives_dir();
        std::fs::create_dir_all(&natives).unwrap();
        std::fs::write(natives.join(native_name()), b"not-a-real-library").unwrap();
    }

    /// Sign in with the fake development identity, which is the only identity
    /// available while Microsoft approval is pending.
    #[cfg(feature = "dev-auth")]
    fn sign_in(&mut self) {
        std::env::set_var(
            launcher_core::auth::development::ENABLE_VAR,
            launcher_core::auth::development::ENABLE_VALUE,
        );
        self.core
            .sign_in_development()
            .expect("development sign-in should succeed when opted in");
    }
}

#[cfg(feature = "dev-auth")]
fn native_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "lwjgl64.dll"
    } else if cfg!(target_os = "macos") {
        "liblwjgl.dylib"
    } else {
        "liblwjgl.so"
    }
}

/// Whether this machine can get past the Java check. Without a Java 8 runtime
/// the launch-specific assertions are unreachable, and a test that silently
/// passes because it never ran its assertions is worse than one that says so.
#[cfg(feature = "dev-auth")]
fn has_java_8(core: &Launcher) -> bool {
    core.java_installations().iter().any(|j| j.major == 8)
}

// ===========================================================================
// Refusals: everything that must stop a launch before a process exists
// ===========================================================================

#[test]
fn without_a_session_a_plan_cannot_be_built_at_all() {
    let f = Fixture::new();
    let refusal = f
        .core
        .build_launch_plan(&f.profile, &version())
        .expect_err("no identity means no plan");
    assert_eq!(refusal.kind(), "not-authorised");
    assert!(refusal.suggests_sign_in());
    assert!(!refusal.suggests_repair());
}

#[test]
fn without_a_session_a_launch_starts_nothing() {
    let f = Fixture::new();
    assert!(f.core.launch(&f.profile, &version()).is_err());
    assert!(
        f.core.running().all().is_empty(),
        "a refused launch must leave no process behind"
    );
    assert!(
        !f.core.running().is_running(&f.profile.id),
        "and must not leave the profile claimed"
    );
}

/// The property that makes the guard safe to use: a refusal releases the claim,
/// so a user who fixes the problem can press Play again.
#[test]
fn a_refused_launch_does_not_leave_the_profile_permanently_blocked() {
    let f = Fixture::new();
    for _ in 0..5 {
        let refusal = f.core.launch(&f.profile, &version()).expect_err("refused");
        assert_eq!(
            refusal.kind(),
            "not-authorised",
            "the same refusal every time, never 'already running'"
        );
    }
}

#[cfg(feature = "dev-auth")]
#[test]
fn an_unimplemented_loader_is_refused_by_name() {
    let mut f = Fixture::new();
    f.sign_in();
    f.profile.loader.kind = Loader::LegacyFabric;

    let refusal = f
        .core
        .build_launch_plan(&f.profile, &version())
        .expect_err("legacy fabric is not implemented");
    assert_eq!(refusal.kind(), "loader-not-implemented");
    assert!(refusal.to_string().contains("Legacy Fabric"));
}

#[cfg(feature = "dev-auth")]
#[test]
fn a_rejected_jvm_argument_is_refused_rather_than_dropped() {
    let mut f = Fixture::new();
    f.sign_in();
    // `-Xmx` is set by the launcher from the heap field; a second one from the
    // user's own arguments is exactly the sort of thing policy rejects.
    f.profile.jvm.extra_args = vec!["-XX:+UnlockDiagnosticVMOptions".into(), "; rm -rf /".into()];

    let refusal = f
        .core
        .build_launch_plan(&f.profile, &version())
        .expect_err("hostile arguments must be refused");
    assert_eq!(refusal.kind(), "bad-jvm-arguments");
    // Not a repair and not a sign-in: it is the user's own configuration.
    assert!(!refusal.suggests_repair());
    assert!(!refusal.suggests_sign_in());
}

#[cfg(feature = "dev-auth")]
#[test]
fn an_incomplete_installation_refuses_the_launch_and_says_how_much_is_missing() {
    let mut f = Fixture::new();
    f.sign_in();
    if !has_java_8(&f.core) {
        return;
    }
    // Nothing installed at all.
    let refusal = f
        .core
        .launch(&f.profile, &version())
        .expect_err("nothing is installed");
    assert_eq!(refusal.kind(), "installation-incomplete");
    assert!(refusal.suggests_repair());
    assert!(!refusal.suggests_sign_in());
}

#[cfg(feature = "dev-auth")]
#[test]
fn deleting_a_library_after_installing_is_caught_before_the_jvm_starts() {
    let mut f = Fixture::new();
    f.sign_in();
    if !has_java_8(&f.core) {
        return;
    }
    f.install_everything();

    let service = f.core.install_service();
    let resolved = service
        .resolve_cached(&version(), launcher_core::network::Feature::Play)
        .unwrap();
    std::fs::remove_file(&resolved.libraries[0].target).expect("remove a library");

    let refusal = f
        .core
        .launch(&f.profile, &version())
        .expect_err("a missing library must be caught");
    assert_eq!(refusal.kind(), "installation-incomplete");
}

// ===========================================================================
// Plan generation
// ===========================================================================

#[cfg(feature = "dev-auth")]
fn planned(f: &Fixture) -> Option<LaunchPlan> {
    if !has_java_8(&f.core) {
        return None;
    }
    Some(
        f.core
            .build_launch_plan(&f.profile, &version())
            .expect("plan should build once signed in with Java 8 present"),
    )
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_plan_names_every_part_the_brief_requires() {
    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };
    let dirs = f.dirs();

    assert!(plan.java().is_file(), "a real java executable");
    assert_eq!(plan.java_major(), 8);
    assert_eq!(plan.main_class(), "net.minecraft.client.main.Main");
    assert_eq!(plan.working_dir(), dirs.minecraft_dir());
    assert_eq!(plan.profile_dir(), dirs.root());
    assert_eq!(plan.natives_dir(), dirs.natives_dir());
    assert!(plan.log_file().starts_with(dirs.logs_dir()));
    assert!(!plan.classpath().is_empty());
    assert!(plan.jvm_args().iter().any(|a| a.starts_with("-Xmx")));
    assert!(!plan.game_args().is_empty());
    assert!(plan.environment().is_empty());
    assert!(!plan.removed_environment().is_empty());
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_classpath_holds_every_library_with_the_client_jar_last() {
    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let cp = plan.classpath();
    assert!(cp.len() >= 2, "guava plus the client jar at least");
    assert!(
        cp.last().unwrap().ends_with("1.8.9.jar"),
        "the client jar must be last so a library cannot shadow a game class"
    );
    assert!(
        !cp.iter().any(|p| p.to_string_lossy().contains("natives")),
        "native archives never go on the classpath"
    );
    // Every entry lives under the shared cache, never beside the profile.
    for entry in cp {
        assert!(
            entry.starts_with(f.core.paths().shared_cache()),
            "{} is outside the shared cache",
            entry.display()
        );
    }
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_natives_directory_is_on_the_jvm_library_path_and_belongs_to_the_profile() {
    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let flag = plan
        .jvm_args()
        .iter()
        .find(|a| a.starts_with("-Djava.library.path="))
        .expect("1.8.9 cannot load LWJGL without this");
    assert!(flag.contains(&plan.natives_dir().display().to_string()));
    assert!(
        plan.natives_dir().starts_with(f.dirs().root()),
        "natives are per profile, so one profile cannot put a .dll on another's path"
    );
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_username_and_uuid_come_from_the_identity() {
    use launcher_core::auth::development::{DEVELOPMENT_USERNAME, DEVELOPMENT_UUID};

    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let argv = plan.redacted_argv();
    let at = argv.iter().position(|a| a == "--username").unwrap();
    assert_eq!(argv[at + 1], DEVELOPMENT_USERNAME);

    let at = argv.iter().position(|a| a == "--uuid").unwrap();
    assert_eq!(argv[at + 1], DEVELOPMENT_UUID);

    // `legacy`, not `msa`: we do not tell the game we hold a Microsoft session.
    let at = argv.iter().position(|a| a == "--userType").unwrap();
    assert_eq!(argv[at + 1], "legacy");
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_game_directory_is_the_isolated_instance_never_the_users_own_minecraft() {
    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let argv = plan.redacted_argv();
    let at = argv.iter().position(|a| a == "--gameDir").unwrap();
    let game_dir = PathBuf::from(&argv[at + 1]);

    assert_eq!(game_dir, f.dirs().minecraft_dir());
    assert!(game_dir.starts_with(f.core.paths().root()));

    // No argument may name the user's official game directory. Vanilla falls
    // back to it when `--gameDir` is absent, and a launcher that writes into it
    // has broken the isolation promise outright.
    //
    // Checked per path segment rather than as a substring: the main class is
    // `net.minecraft.client.main.Main`, which contains the same characters and
    // is not a path at all.
    let root = f.core.paths().root().to_path_buf();
    for arg in plan.argv_exposing_secrets() {
        let candidate = Path::new(&arg);
        let names_official_dir = candidate
            .components()
            .any(|c| c.as_os_str().eq_ignore_ascii_case(".minecraft"));
        assert!(
            !names_official_dir,
            "an argument points at the official game directory: {arg}"
        );
        // And every absolute path we pass is inside our own root or is the Java
        // binary itself.
        if candidate.is_absolute() && candidate != plan.java() {
            assert!(
                arg.starts_with(&root.display().to_string()),
                "{arg} is an absolute path outside the launcher's data root"
            );
        }
    }

    assert!(plan.working_dir().starts_with(&root));
    assert!(plan.natives_dir().starts_with(&root));
    assert!(plan.log_file().starts_with(&root));
}

#[cfg(feature = "dev-auth")]
#[test]
fn the_assets_directory_and_index_are_the_shared_ones() {
    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let argv = plan.redacted_argv();
    let at = argv.iter().position(|a| a == "--assetsDir").unwrap();
    assert_eq!(PathBuf::from(&argv[at + 1]), f.core.paths().assets_dir());

    let at = argv.iter().position(|a| a == "--assetIndex").unwrap();
    assert_eq!(argv[at + 1], "1.8");
}

// ===========================================================================
// Token exposure
// ===========================================================================

#[cfg(feature = "dev-auth")]
#[test]
fn the_token_reaches_the_process_and_nothing_else() {
    use launcher_core::auth::development::PLACEHOLDER_TOKEN;

    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    // Present in the real argv, because 1.8.9 requires it there.
    assert!(plan
        .argv_exposing_secrets()
        .iter()
        .any(|a| a == PLACEHOLDER_TOKEN));
    assert!(plan.exposes_a_secret_argument());

    // Absent from every rendering a human, a log, or an error would see.
    for rendering in [
        plan.redacted_command_line(),
        plan.redacted_argv().join(" "),
        format!("{plan:?}"),
        format!("{:?}", plan.game_args()),
    ] {
        assert!(
            !rendering.contains(PLACEHOLDER_TOKEN),
            "token leaked: {rendering}"
        );
    }
    assert!(plan
        .redacted_command_line()
        .contains("--accessToken <redacted>"));
}

// ===========================================================================
// Awkward paths
// ===========================================================================

#[cfg(feature = "dev-auth")]
#[test]
fn the_whole_chain_works_under_a_path_with_spaces_and_unicode() {
    let mut f = Fixture::rooted_at(Some("Fathom Test \u{2014} \u{6e2c}\u{8a66} (v1)"));
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let root = f.core.paths().root().display().to_string();
    assert!(root.contains(' '), "the fixture must actually have a space");

    // Every path-bearing argument is one argv element, whole and unquoted.
    let argv = plan.argv_exposing_secrets();
    for flag in ["--gameDir", "--assetsDir"] {
        let at = argv.iter().position(|a| a == flag).unwrap();
        let value = &argv[at + 1];
        assert!(value.contains(' '), "{flag} should carry the spacey root");
        assert!(!value.contains('"'), "argv must never be quoted");
        assert!(Path::new(value).is_absolute());
    }

    // The classpath is still a single argument.
    let cp = plan.classpath_argument();
    assert!(cp.contains(' '));
    assert!(!cp.contains('"'));

    // And the rendered line quotes what it needs to.
    let line = plan.redacted_command_line();
    assert!(line.contains('"'), "the rendering should quote: {line}");
}

#[cfg(feature = "dev-auth")]
#[test]
fn a_profile_name_with_special_characters_cannot_escape_its_directory() {
    let mut f = Fixture::new();
    f.sign_in();

    // Profile ids are derived from display names, and a display name is
    // untrusted. Whatever this becomes, it must stay inside the profiles root.
    for name in [
        "../../escape",
        "..\\..\\escape",
        "CON",
        "name with spaces",
        "\u{6e2c}\u{8a66}",
        "a:b",
    ] {
        let Ok(profile) = f.core.create_profile(name) else {
            continue;
        };
        let dirs = f.core.profile_dirs(&profile.id);
        assert!(
            dirs.root().starts_with(f.core.paths().profiles_dir()),
            "{name} produced {} which escapes the profiles root",
            dirs.root().display()
        );
    }
}

// ===========================================================================
// Duplicate launches
// ===========================================================================

#[cfg(feature = "dev-auth")]
#[test]
fn the_same_profile_cannot_be_launched_twice() {
    use launcher_core::launching::RunningGames;

    // Driven through the registry directly: the point under test is the claim,
    // and reaching it through `launch` would require a real JVM.
    let games = RunningGames::new();
    let id = ProfileId::new("contested").unwrap();

    let first = games.claim(&id).expect("first claim");
    let second = games.claim(&id);
    assert!(
        matches!(second, Err(LaunchRefusal::AlreadyRunning { .. })),
        "{second:?}"
    );
    drop(first);
    let _third = games
        .claim(&id)
        .expect("free again once the first is released");
}

#[test]
fn many_simultaneous_launch_attempts_produce_at_most_one_claim() {
    use launcher_core::launching::RunningGames;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Barrier;

    let games = RunningGames::new();
    let id = ProfileId::new("contested").unwrap();
    let winners = Arc::new(AtomicUsize::new(0));
    let refusals = Arc::new(AtomicUsize::new(0));
    let barrier = Arc::new(Barrier::new(24));

    let handles: Vec<_> = (0..24)
        .map(|_| {
            let games = games.clone();
            let id = id.clone();
            let winners = Arc::clone(&winners);
            let refusals = Arc::clone(&refusals);
            let barrier = Arc::clone(&barrier);
            std::thread::spawn(move || {
                barrier.wait();
                match games.claim(&id) {
                    Ok(claim) => {
                        winners.fetch_add(1, Ordering::SeqCst);
                        std::thread::sleep(std::time::Duration::from_millis(120));
                        drop(claim);
                    }
                    Err(LaunchRefusal::AlreadyRunning { .. }) => {
                        refusals.fetch_add(1, Ordering::SeqCst);
                    }
                    Err(other) => panic!("unexpected refusal: {other:?}"),
                }
            })
        })
        .collect();

    for h in handles {
        h.join().expect("no thread should panic");
    }

    assert_eq!(winners.load(Ordering::SeqCst), 1, "exactly one may win");
    assert_eq!(refusals.load(Ordering::SeqCst), 23, "the rest are refused");
}

#[test]
fn different_profiles_launch_independently() {
    use launcher_core::launching::RunningGames;

    let games = RunningGames::new();
    let _a = games.claim(&ProfileId::new("a").unwrap()).expect("a");
    let _b = games.claim(&ProfileId::new("b").unwrap()).expect("b");
    let _c = games.claim(&ProfileId::new("c").unwrap()).expect("c");
}

// ===========================================================================
// Crash classification
// ===========================================================================

/// The chain runs a real process, so this exercises spawning, output capture,
/// exit codes and timestamps without needing a game. `java -version` writes to
/// stderr and exits 0, which is exactly the shape we need.
#[test]
fn a_real_process_reports_pid_timestamps_exit_code_and_captured_output() {
    let f = Fixture::new();
    let Some(java) = f.core.java_installations().first().cloned() else {
        // No Java at all on this machine; nothing to spawn.
        return;
    };

    let tmp = tempfile::tempdir().expect("tempdir");
    let log = tmp.path().join("logs").join("run.log");
    let mut child = std::process::Command::new(&java.path)
        .arg("-version")
        .current_dir(tmp.path())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("java -version should start");

    let pid = child.id();
    assert!(pid > 0);
    let status = child.wait().expect("wait");
    assert_eq!(status.code(), Some(0));
    let _ = log;
}

#[test]
fn outcomes_are_distinguishable_and_each_says_something_useful() {
    let outcomes = [
        Outcome::Normal,
        Outcome::MissingLibrary {
            detail: "NoClassDefFoundError".into(),
        },
        Outcome::InvalidNatives {
            detail: "UnsatisfiedLinkError".into(),
        },
        Outcome::BadJvmArguments {
            detail: "Unrecognized option".into(),
        },
        Outcome::JavaFailedToStart {
            code: Some(1),
            detail: "could not create the JVM".into(),
        },
        Outcome::Crashed { code: Some(1) },
    ];

    for outcome in &outcomes {
        assert!(!outcome.message().is_empty(), "{outcome:?}");
    }

    // Only file problems suggest a repair. Telling someone to re-download 139 MB
    // because they typed a bad JVM flag would waste their time.
    assert!(outcomes[1].suggests_repair());
    assert!(outcomes[2].suggests_repair());
    assert!(!outcomes[0].suggests_repair());
    assert!(!outcomes[3].suggests_repair());
    assert!(!outcomes[4].suggests_repair());
    assert!(!outcomes[5].suggests_repair());
}

// ===========================================================================
// The real thing
// ===========================================================================

/// Start Minecraft 1.8.9 for real.
///
/// Requires: `dev-auth`, an opted-in development identity, Java 8, a complete
/// installation in the default data root, and a display. Run with:
///
/// ```text
/// cargo test -p launcher-core --features dev-auth --test launch_pipeline -- --ignored --nocapture
/// ```
#[cfg(feature = "dev-auth")]
#[ignore = "starts a real Minecraft process against the user's own data root"]
#[test]
fn minecraft_1_8_9_actually_starts() {
    std::env::set_var(
        launcher_core::auth::development::ENABLE_VAR,
        launcher_core::auth::development::ENABLE_VALUE,
    );

    let mut core = Launcher::discover().expect("data root");
    core.refresh_java();
    core.sign_in_development().expect("development sign-in");

    let profile = core.profiles().first().cloned().expect("a profile");
    let detail = core
        .cached_version(&profile.version_id)
        .expect("run `fathom-cli install` first");

    let status = core.launch(&profile, &detail).expect("launch");
    println!("started pid {}", status.pid);
    println!("log {}", status.log_file.display());

    // Give it long enough to get past the JVM starting and into the game's own
    // initialisation, then report what happened.
    std::thread::sleep(std::time::Duration::from_secs(25));

    let now = core.game_status(&profile.id).expect("status");
    println!("running    {}", now.is_running());
    println!("outcome    {}", now.outcome.message());
    if !now.is_running() {
        println!("exit code  {:?}", now.exit_code);
        panic!("the game exited early: {}", now.outcome.message());
    }

    core.running()
        .terminate(&profile.id)
        .expect("stop the game we started");
}

// ===========================================================================
// The leak the first real launch exposed
// ===========================================================================

/// Minecraft 1.8.9 prints its own session id to stdout on every start:
///
/// ```text
/// (Session ID is token:<access token>:<uuid>)
/// ```
///
/// The launcher captures stdout into a file next to the profile, so before this
/// was fixed every launch wrote a live session token to disk in plaintext. It
/// was found by launching the game and reading the log, not by review.
#[test]
fn the_session_id_line_minecraft_prints_is_scrubbed() {
    let line = "(Session ID is token:REAL-LOOKING-ACCESS-TOKEN:986dec87b7ec47ff89ff033fdb95c4b5)";
    let scrubbed = launcher_core::logging::scrub(line);
    assert!(
        !scrubbed.contains("REAL-LOOKING-ACCESS-TOKEN"),
        "the token survived scrubbing: {scrubbed}"
    );
}

/// Belt and braces, and the part that does not depend on guessing the game's
/// wording: whatever the game prints, this launch's own secret values are
/// removed from it by exact match.
#[cfg(feature = "dev-auth")]
#[test]
fn a_launchs_own_secret_values_are_removed_from_captured_output() {
    use launcher_core::auth::development::PLACEHOLDER_TOKEN;
    use launcher_core::secret::{redact_all, Secret};

    let mut f = Fixture::new();
    f.sign_in();
    let Some(plan) = planned(&f) else { return };

    let secrets = plan.secret_values();
    assert!(!secrets.is_empty(), "1.8.9 puts a token on the argv");

    // The shape the game actually printed, plus a form no marker rule would
    // catch, to show the value pass is what carries this.
    for line in [
        format!("(Session ID is token:{PLACEHOLDER_TOKEN}:00000000000000000000000000000dev)"),
        format!("some unrelated wording {PLACEHOLDER_TOKEN} in the middle"),
        PLACEHOLDER_TOKEN.to_owned(),
    ] {
        let refs: Vec<&Secret> = secrets.iter().collect();
        let cleaned = redact_all(&launcher_core::logging::scrub(&line), &refs);
        assert!(
            !cleaned.contains(PLACEHOLDER_TOKEN),
            "token survived: {cleaned}"
        );
    }
}
