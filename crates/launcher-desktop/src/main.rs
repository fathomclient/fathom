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

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

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
    /// The jar the user has just supplied, between accepting it and choosing a
    /// profile for it.
    ///
    /// Held here rather than handed to the window and back, because the second
    /// step would then be trusting the window's copy of a hash and a mod id to
    /// decide what goes into a profile. There is one window and one drop target,
    /// so there is at most one of these.
    supplied: Mutex<Option<launcher_core::mods::supplied::AcceptedJar>>,
}

#[tauri::command]
fn home_view(state: tauri::State<'_, State>) -> Result<api::HomeView, String> {
    let mut core = state.core.lock().map_err(|_| "launcher state is poisoned")?;
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
    let core = state.core.lock().map_err(|_| "launcher state is poisoned")?;
    Ok(api::shell_status(&core))
}

#[tauri::command]
fn background(state: tauri::State<'_, State>) -> Result<String, String> {
    let choice = state
        .background
        .lock()
        .map_err(|_| "background state is poisoned")?;
    Ok(choice.clone())
}

/// The background ids the frontend may ask for.
///
/// These are also asset filenames, which is why nothing else is accepted: the
/// value ends up inside a URL, and "the WebView asked for it" is not a reason
/// to trust a string.
///
/// Every id here must appear in `desktop/src/lib/backgrounds.ts`, which is the
/// catalogue the interface offers and says what each id's asset is. The two
/// lists drifted once: the picker offered the bg_5 and bg_6 recordings while
/// this list still ended at `bg_4`, so choosing one was refused here and the
/// window kept the background it already had, with nothing on screen to say so.
/// The test at the bottom of this file reads that catalogue and fails if they
/// disagree again.
const BACKGROUNDS: [&str; 15] = [
    "static",
    "bg_1",
    "bg_2",
    "bg_3",
    "bg_4",
    "bg_5rain",
    "bg_5noraintickfrozen",
    "bg_5noraintickunfrozen",
    "bg_6midnight",
    "bg_6clear",
    "bg_6pull_city-overlook",
    "bg_6pull_moon-tower",
    "bg_6pull_red-tower",
    "bg_6pull_storm-panorama",
    "bg_6pull_neon-grid",
];

/// Whether a background decodes video, for the interface's "Animated
/// background" switch.
///
/// Three kinds, two answers: the midnight gradient and the still frames are not
/// animated, and everything else is a clip. Spelled as a prefix rather than a
/// second list because the stills are all crops of one recording and are named
/// for it.
fn animated(choice: &str) -> bool {
    choice != "static" && !choice.starts_with("bg_6pull_")
}

/// What a first run shows, and what an unreadable preference falls back to.
const DEFAULT_BACKGROUND: &str = "bg_5rain";

/// Accept an id written by an older build.
///
/// The previous interface serialised its choice through serde's `snake_case`
/// rename, which turns `Bg1` into `bg1` -- no underscore. The asset files are
/// `bg_1.webp`, so a preference file written by that build produces a URL that
/// 404s. Nothing reported an error: the launcher asked for a background that
/// did not exist and got an HTML error page, which the image and media decoders
/// both refused, and the window simply stayed black. Normalising here means one
/// spelling exists past this point.
fn normalise(raw: &str) -> Option<&'static str> {
    let candidate = match raw.trim() {
        "bg1" => "bg_1",
        "bg2" => "bg_2",
        "bg3" => "bg_3",
        "bg4" => "bg_4",
        other => other,
    };
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

    if let Ok(paths) = DataPaths::discover() {
        let path = paths.root().join("appearance.json");
        let body = serde_json::json!({
            "background": choice,
            "animated_background": animated(&choice),
        });
        if let Err(error) = std::fs::write(&path, body.to_string()) {
            // Not fatal. The background changed for this session; only the
            // memory of it is lost, and refusing the change would be worse.
            tracing::warn!(%error, "could not save the background preference");
        }
    }
    Ok(())
}

fn stored_background() -> String {
    let Ok(paths) = DataPaths::discover() else {
        return DEFAULT_BACKGROUND.to_owned();
    };
    let Ok(raw) = std::fs::read_to_string(paths.root().join("appearance.json")) else {
        return DEFAULT_BACKGROUND.to_owned();
    };
    // A byte-order mark is what a hand-edit in Notepad leaves behind, and
    // serde_json treats one as a syntax error.
    let raw = raw.trim_start_matches('\u{feff}');
    let Ok(value) = serde_json::from_str::<serde_json::Value>(raw) else {
        return DEFAULT_BACKGROUND.to_owned();
    };
    // The id wins when there is one. The flag is only consulted when there is
    // not: a still frame is saved as `animated_background: false` and is still a
    // background, so reading the flag first would turn every saved still back
    // into the midnight gradient on the next launch.
    if let Some(id) = value
        .get("background")
        .and_then(serde_json::Value::as_str)
        .and_then(normalise)
    {
        return id.to_owned();
    }
    if value.get("animated_background") == Some(&serde_json::Value::Bool(false)) {
        return "static".to_owned();
    }
    DEFAULT_BACKGROUND.to_owned()
}

fn main() {
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
            supplied: Mutex::new(None),
        })
        // A stored refresh token is tried once, on a thread, so a signed-in
        // user is signed in again without the window waiting on Microsoft to
        // answer before it opens.
        .setup(|app| {
            signin::restore(&app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            home_view,
            shell_status,
            background,
            set_background,
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
            commands::sign_in_progress,
            commands::sign_out
        ])
        .run(tauri::generate_context!())
        .expect("the Fathom window could not start");
}

#[cfg(test)]
mod tests {
    use super::*;

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
            .match_indices("{ id: '")
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

    /// Only clips are animated. The gradient and the still frames are not, and
    /// a still that reported itself as animated would be described wrongly by
    /// the switch in Settings.
    #[test]
    fn only_video_backgrounds_count_as_animated() {
        assert!(animated("bg_1"));
        assert!(animated("bg_5rain"));
        assert!(!animated("static"));
        assert!(!animated("bg_6pull_neon-grid"));
        assert!(!animated("bg_6pull_city-overlook"));
    }

    /// The bug that made the background black: a preference written by the
    /// previous interface spells the id without an underscore, and the asset
    /// files have one.
    #[test]
    fn ids_from_the_previous_build_are_accepted() {
        assert_eq!(normalise("bg1"), Some("bg_1"));
        assert_eq!(normalise("bg4"), Some("bg_4"));
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
        for hostile in ["../../secret", "bg_9", "", "bg_1/../..", "http://elsewhere"] {
            assert_eq!(normalise(hostile), None, "{hostile} should be refused");
        }
    }
}
