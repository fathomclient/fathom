//! Fathom's desktop shell.
//!
//! # What this process is
//!
//! A window, a WebView, and a list of typed commands. Nothing else. The
//! launcher itself — authentication, the installer, the Java manager, profiles,
//! mods, downloads, network policy, recovery, the updater, path handling,
//! credential storage and the launch pipeline — is `launcher-core`, unchanged
//! and unmoved. None of it was rewritten in TypeScript and none of it is
//! reachable from the WebView except through the commands in [`api`].
//!
//! # The boundary
//!
//! The frontend renders. It does not decide, fetch, or hold anything.
//!
//! `tauri` is depended on with **no features and no plugins**. There is no
//! filesystem plugin, no shell plugin, no HTTP plugin, no dialog plugin. Every
//! capability those would add is either something the frontend must not have,
//! or something the core already does behind one of the commands below. The
//! Content-Security-Policy in `tauri.conf.json` is `default-src 'self'`, so the
//! WebView cannot load a remote page or call a remote API even if something
//! tried to make it.
//!
//! Auth tokens never cross the boundary: they live in the OS credential store
//! and are read by the core at launch time. The frontend is told whether
//! somebody is signed in and what their name is, which is what it needs to draw
//! an avatar, and nothing more.

mod api;
mod commands;
mod fathom_profiles;
mod pages;
mod session;
mod signin;

use std::sync::Mutex;

use launcher_core::paths::DataPaths;
use launcher_core::Launcher;

/// Everything the commands share.
///
/// A `Mutex` rather than anything cleverer: commands are short, the contention
/// is one window's worth, and the alternative is inventing a concurrency story
/// for a launcher that does not need one.
struct State {
    core: Mutex<Launcher>,
    background: Mutex<String>,
    /// The one launch in flight, if any. Deliberately *not* inside the
    /// launcher's mutex: its whole purpose is to be readable while a launch
    /// thread is doing something long, which is exactly when that lock is
    /// unavailable.
    launch: api::launch::Supervisor,
    /// The one sign-in in flight, if any. Same reasoning as `launch`.
    auth: api::auth::Supervisor,
    /// The launcher-lifetime bridge, so a game Fathom did not start can still
    /// reach the account drawer. `None` when it could not listen.
    launcher_bridge: Mutex<Option<launcher_core::bridge::Bridge>>,
    /// The jar the user has just supplied, between accepting it and choosing a
    /// profile for it.
    ///
    /// Held here rather than handed to the window and back, because the second
    /// step would then be trusting the window's copy of a hash and a mod id to
    /// decide what goes into a profile. There is one window and one drop target,
    /// so there is at most one of these.
    supplied: Mutex<Option<launcher_core::mods::supplied::AcceptedJar>>,
}

const EMBEDDED_CLIENT: &[u8] = include_bytes!(env!("FATHOM_CLIENT_JAR_PATH"));
const EMBEDDED_CLIENT_NAME: &str = env!("FATHOM_CLIENT_JAR_NAME");

/// Materialise the client module carried inside the executable.
///
/// This is what makes `Fathom.exe` genuinely portable rather than a launcher
/// that only works while a sibling `resources` directory happens to follow it.
fn stage_embedded_client(paths: &DataPaths) -> Result<(), std::io::Error> {
    let directory = paths.resources_dir();
    std::fs::create_dir_all(&directory)?;
    let destination = directory.join(EMBEDDED_CLIENT_NAME);
    if file_matches(&destination, EMBEDDED_CLIENT)? {
        return Ok(());
    }
    let temporary = directory.join(format!(".{EMBEDDED_CLIENT_NAME}.new"));
    std::fs::write(&temporary, EMBEDDED_CLIENT)?;
    if destination.exists() {
        std::fs::remove_file(&destination)?;
    }
    std::fs::rename(temporary, destination)
}

/// A rebuilt jar can have exactly the same byte length as the jar it replaces.
/// Comparing only metadata therefore leaves stale game code installed even
/// though the launcher carries newer bytes. Compare the payload in bounded
/// chunks so startup remains authoritative without allocating another jar-sized
/// buffer.
fn file_matches(path: &std::path::Path, expected: &[u8]) -> Result<bool, std::io::Error> {
    use std::io::Read;

    let Ok(metadata) = std::fs::metadata(path) else {
        return Ok(false);
    };
    if metadata.len() != expected.len() as u64 {
        return Ok(false);
    }

    let mut installed = std::io::BufReader::new(std::fs::File::open(path)?);
    let mut offset = 0;
    let mut buffer = [0_u8; 64 * 1024];
    while offset < expected.len() {
        let amount = buffer.len().min(expected.len() - offset);
        installed.read_exact(&mut buffer[..amount])?;
        if buffer[..amount] != expected[offset..offset + amount] {
            return Ok(false);
        }
        offset += amount;
    }
    Ok(true)
}

#[tauri::command]
fn home_view(state: tauri::State<'_, State>) -> Result<api::HomeView, String> {
    let mut core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    // Home's launch button is derived from `readiness`, which raises a
    // "no Java" blocker from the scanned list. Without a scan it told the user
    // to install a Java they already had.
    if core.java_installations().is_empty() {
        core.refresh_java();
    }
    Ok(api::home_view(&core))
}

#[tauri::command]
fn shell_status(state: tauri::State<'_, State>) -> Result<api::ShellStatus, String> {
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    Ok(api::shell_status(&core))
}

#[tauri::command]
fn accounts_view(state: tauri::State<'_, State>) -> Result<Vec<api::AccountSummary>, String> {
    let core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    Ok(api::accounts_view(&core))
}

#[tauri::command]
fn background(state: tauri::State<'_, State>) -> Result<String, String> {
    let choice = state
        .background
        .lock()
        .map_err(|_| "background state is poisoned")?;
    Ok(choice.clone())
}

/// The background ids the frontend may ask for: the approved backgrounds.
///
/// These are also asset filenames, which is why nothing else is accepted: the
/// value ends up inside a URL, and "the WebView asked for it" is not a reason
/// to trust a string.
///
/// Fathom's backgrounds are curated, and right now there is one. The gameplay
/// recordings, their stills, and the plain gradient that used to be listed here
/// are retired: a preference naming one of them is migrated to the default when
/// it is read (`migrate_appearance`), and this list refuses them if asked.
///
/// Every id here must appear in `desktop/src/lib/backgrounds.ts`, which is the
/// catalogue the interface offers. The test at the bottom of this file reads
/// that catalogue and fails if the two disagree.
const BACKGROUNDS: [&str; 1] = ["golden-hour"];

/// What a first run shows, and what any retired, unknown or unreadable
/// preference becomes. `desktop/src/lib/backgrounds.ts` carries the same
/// default for the moment before this answer arrives.
const DEFAULT_BACKGROUND: &str = "golden-hour";

/// The approved id a stored or requested value names, if it names one.
fn normalise(raw: &str) -> Option<&'static str> {
    let candidate = raw.trim();
    BACKGROUNDS
        .iter()
        .find(|allowed| **allowed == candidate)
        .copied()
}

/// Persist the chosen background.
#[tauri::command]
fn set_background(state: tauri::State<'_, State>, choice: String) -> Result<(), String> {
    let choice = normalise(&choice)
        .ok_or_else(|| format!("unknown background: {choice}"))?
        .to_owned();

    let mut current = state
        .background
        .lock()
        .map_err(|_| "background state is poisoned")?;
    current.clone_from(&choice);

    let mut stored = read_appearance();
    stored.insert(
        "background".to_owned(),
        serde_json::Value::from(choice.clone()),
    );
    stored.remove(RETIRED_ANIMATED_KEY);
    if let Err(error) = write_appearance(stored) {
        // Not fatal. The background changed for this session; only the
        // memory of it is lost, and refusing the change would be worse.
        tracing::warn!(%error, "could not save the background preference");
    }
    Ok(())
}

/// The glass accessibility switches: see `desktop/src/lib/appearance.ts`.
///
/// Purely visual. They are stored beside the background in `appearance.json`
/// and nothing outside the interface reads them.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct AppearanceModes {
    reduce_transparency: bool,
    reduce_blur: bool,
}

impl AppearanceModes {
    /// Missing or mistyped keys read as off: full glass is the default.
    fn from_stored(stored: &serde_json::Map<String, serde_json::Value>) -> Self {
        let flag = |key: &str| stored.get(key).and_then(serde_json::Value::as_bool) == Some(true);
        Self {
            reduce_transparency: flag("reduce_transparency"),
            reduce_blur: flag("reduce_blur"),
        }
    }

    fn store_into(self, stored: &mut serde_json::Map<String, serde_json::Value>) {
        stored.insert(
            "reduce_transparency".to_owned(),
            serde_json::Value::Bool(self.reduce_transparency),
        );
        stored.insert(
            "reduce_blur".to_owned(),
            serde_json::Value::Bool(self.reduce_blur),
        );
    }
}

#[tauri::command]
fn appearance_modes() -> AppearanceModes {
    AppearanceModes::from_stored(&read_appearance())
}

#[tauri::command]
fn set_appearance_modes(modes: AppearanceModes) -> Result<(), String> {
    let mut stored = read_appearance();
    modes.store_into(&mut stored);
    write_appearance(stored).map_err(|error| {
        tracing::warn!(%error, "could not save the appearance modes");
        format!("could not save the appearance setting: {error}")
    })
}

/// Parse `appearance.json`. Anything unreadable is an empty object.
fn parse_appearance(raw: &str) -> serde_json::Map<String, serde_json::Value> {
    // A byte-order mark is what a hand-edit in Notepad leaves behind, and
    // serde_json treats one as a syntax error.
    let raw = raw.trim_start_matches('\u{feff}');
    match serde_json::from_str::<serde_json::Value>(raw) {
        Ok(serde_json::Value::Object(map)) => map,
        _ => serde_json::Map::new(),
    }
}

/// The whole of `appearance.json`, so that each writer replaces only its own
/// keys. It used to be written whole by the background picker alone; with a
/// second writer, rewriting it from scratch would silently reset the other
/// one's settings.
fn read_appearance() -> serde_json::Map<String, serde_json::Value> {
    DataPaths::discover()
        .ok()
        .and_then(|paths| std::fs::read_to_string(paths.root().join("appearance.json")).ok())
        .map(|raw| parse_appearance(&raw))
        .unwrap_or_default()
}

fn write_appearance(stored: serde_json::Map<String, serde_json::Value>) -> Result<(), String> {
    let paths = DataPaths::discover().map_err(|error| error.to_string())?;
    std::fs::write(
        paths.root().join("appearance.json"),
        serde_json::Value::Object(stored).to_string(),
    )
    .map_err(|error| error.to_string())
}

/// The retired "Animated background" flag. Only ever removed now.
const RETIRED_ANIMATED_KEY: &str = "animated_background";

/// Bring a stored `appearance.json` onto the current background policy.
///
/// A background that is not approved -- a retired recording, the old gradient,
/// the pre-underscore `bg1` spellings, or anything unreadable -- becomes the
/// default, and the retired animation flag is dropped. Every other key is left
/// exactly as it was, so the glass accessibility switches survive. Returns
/// whether anything changed.
fn migrate_appearance(stored: &mut serde_json::Map<String, serde_json::Value>) -> bool {
    let approved = stored
        .get("background")
        .and_then(serde_json::Value::as_str)
        .and_then(normalise);
    let mut changed = false;
    if approved.is_none() {
        stored.insert(
            "background".to_owned(),
            serde_json::Value::from(DEFAULT_BACKGROUND),
        );
        changed = true;
    }
    if stored.remove(RETIRED_ANIMATED_KEY).is_some() {
        changed = true;
    }
    changed
}

/// The background to show, migrating the stored preference first.
///
/// A first run has no file and writes none. A file that exists but names a
/// retired background is rewritten once, in place, so the next read is already
/// clean and nothing downstream ever sees the old id.
fn stored_background() -> String {
    let mut stored = read_appearance();
    if stored.is_empty() {
        return DEFAULT_BACKGROUND.to_owned();
    }
    if migrate_appearance(&mut stored) {
        if let Err(error) = write_appearance(stored.clone()) {
            // Not fatal: the default is shown this session either way, and the
            // migration runs again next start.
            tracing::warn!(%error, "could not migrate the background preference");
        }
    }
    stored
        .get("background")
        .and_then(serde_json::Value::as_str)
        .and_then(normalise)
        .unwrap_or(DEFAULT_BACKGROUND)
        .to_owned()
}

/// Start Fathom's one canonical desktop launcher.
///
/// Both the shipped `fathom-desktop` binary and the historical
/// `cargo run -p launcher-ui` command call this function. Keeping the Tauri
/// application here prevents those entry points from growing separate user
/// interfaces again.
pub fn run() {
    // A log FILE, not just stderr.
    //
    // `init` alone writes to stderr, and a windowed build has no stderr -- so
    // this binary produced no log anybody could read, and the Logs screen would
    // have had nothing real to show. `init_with_file` adds a file in the data
    // directory; its writer scrubs secrets on the way out, which is why the
    // viewer can display it verbatim.
    //
    // Falls back to stderr-only rather than refusing to start: not being able
    // to write a log is not a reason to withhold the launcher.
    match DataPaths::discover() {
        Ok(paths) => {
            if launcher_core::logging::init_with_file(
                cfg!(debug_assertions),
                &paths.launcher_logs_dir(),
                cfg!(debug_assertions),
            )
            .is_err()
            {
                launcher_core::logging::init(cfg!(debug_assertions));
            }
        }
        Err(_) => launcher_core::logging::init(cfg!(debug_assertions)),
    }

    let executable = std::env::current_exe()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|_| "unavailable".to_owned());
    let build_kind = if cfg!(debug_assertions) {
        "debug"
    } else {
        "release"
    };
    let frontend_source = if cfg!(feature = "custom-protocol") {
        "embedded desktop/dist (custom protocol)"
    } else {
        "ephemeral loopback Vite server (development only)"
    };
    tracing::info!(
        launcher_version = env!("CARGO_PKG_VERSION"),
        build_kind,
        frontend_id = env!("FATHOM_FRONTEND_ID"),
        frontend_source,
        executable = %executable,
        "launcher build"
    );

    if let Ok(paths) = DataPaths::discover() {
        if let Err(error) = stage_embedded_client(&paths) {
            tracing::error!(%error, "the embedded Fathom client could not be staged");
        }
    }

    let core = match Launcher::discover() {
        Ok(core) => core,
        Err(error) => {
            // The iced build opened a failure window for this. The equivalent
            // here is not written yet, so this exits loudly rather than
            // pretending: see docs/frontend-migration.md.
            tracing::error!(%error, "Fathom could not open its data directory");
            eprintln!("Fathom could not open its data directory: {error}");
            std::process::exit(1);
        }
    };

    tauri::Builder::default()
        .manage(State {
            core: Mutex::new(core),
            background: Mutex::new(stored_background()),
            launch: api::launch::Supervisor::default(),
            auth: api::auth::Supervisor::default(),
            launcher_bridge: Mutex::new(None),
            supplied: Mutex::new(None),
        })
        // A stored refresh token is tried once, on a thread, so a signed-in
        // user is signed in again without the window waiting on Microsoft to
        // answer before it opens.
        .setup(|app| {
            signin::restore(&app.handle().clone());
            match session::open_launcher_bridge(app.handle()) {
                Ok(bridge) => {
                    use tauri::Manager as _;
                    if let Ok(mut slot) = app.state::<State>().launcher_bridge.lock() {
                        *slot = Some(bridge);
                    }
                }
                Err(error) => tracing::info!(%error, "the launcher bridge is not available"),
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            home_view,
            shell_status,
            accounts_view,
            background,
            set_background,
            appearance_modes,
            set_appearance_modes,
            fathom_profiles::fathom_profiles_view,
            fathom_profiles::fathom_profile_switch,
            fathom_profiles::fathom_profile_create,
            fathom_profiles::fathom_profile_rename,
            fathom_profiles::fathom_profile_duplicate,
            fathom_profiles::fathom_profile_delete,
            fathom_profiles::fathom_profile_export,
            fathom_profiles::fathom_profile_import,
            commands::profiles_view,
            commands::profile_detail,
            commands::profile_art,
            commands::select_profile,
            commands::create_profile,
            commands::rename_profile,
            commands::duplicate_profile,
            commands::delete_profile,
            commands::save_profile,
            commands::mods_view,
            commands::set_mod_enabled,
            commands::remove_mod,
            commands::add_mod,
            commands::performance_view,
            commands::set_preset,
            commands::set_optimization,
            commands::unlock_frame_rate,
            commands::settings_view,
            commands::set_setting,
            commands::logs_view,
            commands::privacy_view,
            commands::recovery_view,
            commands::run_recovery,
            commands::play,
            commands::cancel_launch,
            commands::launch_progress,
            commands::console_view,
            commands::close_console,
            commands::set_choice,
            commands::setup_options,
            commands::setup_components,
            commands::setup_problems,
            commands::create_profile_from_setup,
            commands::derive_profile,
            commands::import_mod,
            commands::import_dropped_mod,
            commands::browse_mods,
            commands::supply_jar,
            commands::supply_dropped_jar,
            commands::supplied_jar,
            commands::add_supplied_jar,
            commands::install_mod,
            commands::install_component,
            commands::mod_updates,
            commands::open_project_page,
            commands::open_page,
            commands::set_loader,
            commands::sign_in,
            commands::sign_in_development,
            commands::cancel_sign_in,
            commands::reopen_sign_in_page,
            commands::sign_in_progress,
            commands::sign_out,
            commands::switch_account,
            commands::forget_account,
            commands::window_show,
            commands::window_minimize,
            commands::window_toggle_maximize,
            commands::window_toggle_fullscreen,
            commands::window_close
        ])
        .build(tauri::generate_context!())
        .expect("the Fathom window could not start")
        .run(|app, event| {
            // Stop the launcher bridge on the way out, so its handshake does not
            // outlive the listener it names.
            if let tauri::RunEvent::Exit = event {
                use tauri::Manager as _;
                if let Ok(mut slot) = app.state::<State>().launcher_bridge.lock() {
                    slot.take();
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn equal_length_client_payloads_are_compared_by_content() {
        let path = std::env::temp_dir().join(format!(
            "fathom-client-match-{}-{}.jar",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::write(&path, b"old-client").unwrap();

        assert!(!file_matches(&path, b"new-client").unwrap());
        assert!(file_matches(&path, b"old-client").unwrap());

        std::fs::remove_file(path).unwrap();
    }

    /// The picker and this allowlist have to name the same backgrounds.
    ///
    /// They drifted once, and the failure was silent in both directions: the
    /// interface offered five recordings this file refused, so the click did
    /// nothing and the only evidence was a line in the log. Reading the
    /// interface's own list is the cheapest way to make that a build failure
    /// instead.
    #[test]
    fn the_picker_offers_exactly_what_this_file_allows() {
        let shell = include_str!("../../../desktop/src/lib/backgrounds.ts");
        let list = shell
            .split_once("export const BACKGROUNDS: BackgroundOption[] = [")
            .expect("the interface must declare a background list")
            .1
            .split_once("];")
            .expect("the list must end")
            .0;
        let offered: Vec<&str> = list
            .match_indices("id: '")
            .map(|(at, marker)| {
                let rest = &list[at + marker.len()..];
                rest.split_once("'").expect("an id is quoted").0
            })
            .collect();

        for id in &offered {
            assert!(
                BACKGROUNDS.contains(id),
                "the picker offers {id}, which this file would refuse"
            );
        }
        for id in BACKGROUNDS {
            assert!(
                offered.contains(&id),
                "{id} is allowed here but the picker never offers it"
            );
        }
    }

    /// A default nothing accepts is a first run with no background at all.
    #[test]
    fn the_default_background_is_one_of_the_allowed_ids() {
        assert_eq!(normalise(DEFAULT_BACKGROUND), Some(DEFAULT_BACKGROUND));
    }

    /// Every retired background, the old gradient, and the pre-underscore
    /// spellings of the first recordings migrate to the approved default.
    #[test]
    fn retired_backgrounds_migrate_to_golden_hour() {
        for retired in [
            "bg_1",
            "bg1",
            "bg_5rain",
            "bg_6midnight",
            "bg_6pull_neon-grid",
            "bg_6pull_red-tower",
            "static",
            "../../secret",
        ] {
            let mut stored = serde_json::Map::new();
            stored.insert("background".to_owned(), serde_json::Value::from(retired));
            assert!(migrate_appearance(&mut stored), "{retired} should migrate");
            assert_eq!(
                stored.get("background").and_then(|v| v.as_str()),
                Some(DEFAULT_BACKGROUND),
                "{retired}"
            );
        }
    }

    /// The migration touches the background and the retired animation flag, and
    /// nothing else: the glass accessibility switches survive it.
    #[test]
    fn migration_keeps_the_accessibility_switches() {
        let mut stored = parse_appearance(
            r#"{"background":"bg_5rain","animated_background":true,"reduce_transparency":true,"reduce_blur":true}"#,
        );
        assert!(migrate_appearance(&mut stored));
        assert_eq!(
            stored.get("background").and_then(|v| v.as_str()),
            Some("golden-hour")
        );
        assert!(stored.get("animated_background").is_none());
        assert_eq!(
            AppearanceModes::from_stored(&stored),
            AppearanceModes {
                reduce_transparency: true,
                reduce_blur: true
            }
        );
    }

    /// A preference already on policy is left alone, so it is not rewritten on
    /// every start.
    #[test]
    fn an_approved_preference_is_not_rewritten() {
        let mut stored = parse_appearance(r#"{"background":"golden-hour","reduce_blur":true}"#);
        assert!(!migrate_appearance(&mut stored));
    }

    /// A preference with no background at all, or a mistyped one, still gets one.
    #[test]
    fn a_missing_or_mistyped_background_becomes_the_default() {
        let mut missing = parse_appearance(r#"{"reduce_blur":true}"#);
        assert!(migrate_appearance(&mut missing));
        assert_eq!(
            missing.get("background").and_then(|v| v.as_str()),
            Some(DEFAULT_BACKGROUND)
        );
        let mut mistyped = parse_appearance(r#"{"background":7}"#);
        assert!(migrate_appearance(&mut mistyped));
        assert_eq!(
            mistyped.get("background").and_then(|v| v.as_str()),
            Some(DEFAULT_BACKGROUND)
        );
    }

    /// The background picker and the accessibility switches share one file.
    /// Each writer must keep the other's keys.
    #[test]
    fn appearance_writers_keep_each_others_keys() {
        let mut stored = parse_appearance(r#"{"background":"golden-hour"}"#);
        AppearanceModes {
            reduce_transparency: true,
            reduce_blur: false,
        }
        .store_into(&mut stored);
        assert_eq!(
            stored.get("background").and_then(|v| v.as_str()),
            Some("golden-hour")
        );
        assert_eq!(
            AppearanceModes::from_stored(&stored),
            AppearanceModes {
                reduce_transparency: true,
                reduce_blur: false
            }
        );
    }

    /// A file written before the switches existed, or one that is not JSON at
    /// all, reads as full glass rather than as an error.
    #[test]
    fn missing_or_unreadable_modes_are_off() {
        let old = parse_appearance(r#"{"background":"bg_1","animated_background":true}"#);
        assert_eq!(
            AppearanceModes::from_stored(&old),
            AppearanceModes::default()
        );
        let garbage = parse_appearance("not json");
        assert_eq!(
            AppearanceModes::from_stored(&garbage),
            AppearanceModes::default()
        );
        let bom = parse_appearance("\u{feff}{\"reduce_blur\":true}");
        assert!(AppearanceModes::from_stored(&bom).reduce_blur);
    }

    /// The frontend sends camelCase; the file stores snake_case.
    #[test]
    fn appearance_modes_cross_the_bridge_in_camel_case() {
        let modes: AppearanceModes =
            serde_json::from_str(r#"{"reduceTransparency":false,"reduceBlur":true}"#).unwrap();
        assert_eq!(
            modes,
            AppearanceModes {
                reduce_transparency: false,
                reduce_blur: true
            }
        );
    }

    #[test]
    fn current_ids_pass_through() {
        for id in BACKGROUNDS {
            assert_eq!(normalise(id), Some(id));
        }
    }

    /// The value becomes part of a URL, so anything unrecognised is refused
    /// rather than normalised into something plausible.
    #[test]
    fn anything_else_is_refused() {
        for hostile in [
            "../../secret",
            "bg_9",
            "",
            "bg_1/../..",
            "http://elsewhere",
            "bg_5rain",
            "static",
        ] {
            assert_eq!(normalise(hostile), None, "{hostile} should be refused");
        }
    }

    /// The native window must not expose the frontend's null/fixture state.
    /// App.svelte shows it after the real shell data has settled.
    #[test]
    fn the_main_window_starts_hidden_until_the_frontend_is_ready() {
        let config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        assert_eq!(
            config["app"]["windows"][0]["visible"],
            serde_json::Value::Bool(false)
        );

        let shell = include_str!("../../../desktop/src/App.svelte");
        let load = shell.find("homeView(), shellStatus()").unwrap();
        let show = shell.find("await windowShow()").unwrap();
        assert!(
            show > load,
            "the window may only be shown after shell data loads"
        );
    }
}
