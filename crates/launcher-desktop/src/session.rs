//! Running a game session, from the button press to the exit code.
//!
//! # What happens when Play is pressed
//!
//! 1. `play` claims the supervisor and returns. The window never blocks.
//! 2. A thread reads what it needs from the launcher under a brief lock, then
//!    lets go.
//! 3. If the installation is incomplete it is downloaded, reporting progress
//!    and watching the cancel flag between files.
//! 4. The launcher is locked again for as long as it takes to authenticate,
//!    assemble the command and spawn the process — a second or two.
//! 5. The thread then polls the process until it exits, and reports how.
//!
//! # The console
//!
//! Step 4 or step 5 may open a second window showing the game's own output,
//! depending on [`GameConsole`]. That window is the same WebView bundle with a
//! different label; the frontend reads its label and renders the console
//! instead of the shell. It is not a terminal and does not own the process — it
//! reads the log file the launch pipeline is already writing, which is why
//! closing it cannot affect the game.

use std::time::Duration;

use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

use launcher_core::bridge::{self, Bridge};
use launcher_core::configuration::GameConsole;
use launcher_core::install::InstallStage;
use launcher_core::profiles::Profile;
use launcher_core::services::InstallSnapshot;
use launcher_core::{Cancel, Launcher};

use crate::api::launch::{self, LaunchProgress};
use crate::State;

/// How often the supervisor asks whether the game is still up.
///
/// Half a second: fast enough that "I closed Minecraft" and "the launcher
/// noticed" feel like one event, slow enough that a four-hour session costs
/// thirty thousand cheap mutex acquisitions rather than a core.
const POLL: Duration = Duration::from_millis(500);

/// Start a launch. Returns as soon as the thread is running.
pub fn start(app: &tauri::AppHandle) -> Result<LaunchProgress, String> {
    let cancel = {
        let state = app.state::<State>();
        state.launch.begin()?
    };

    let handle = app.clone();
    // Detached on purpose. The thread's whole output is the supervisor's
    // progress cell, which the interface polls; there is nothing for a join
    // handle to deliver, and holding one would mean deciding who joins it when
    // the window closes mid-download.
    std::thread::Builder::new()
        .name("fathom-launch".to_owned())
        .spawn(move || {
            let outcome = perform(&handle, &cancel);
            let state = handle.state::<State>();
            state.launch.set(outcome);
        })
        .map_err(|error| format!("could not start the launch: {error}"))?;

    let state = app.state::<State>();
    Ok(state.launch.progress())
}

/// Ask the launch in flight to stop.
pub fn cancel(app: &tauri::AppHandle) -> Result<LaunchProgress, String> {
    let state = app.state::<State>();
    state.launch.request_cancel()?;
    Ok(state.launch.progress())
}

/// Everything a launch needs from the launcher, read in one lock.
///
/// Gathered as a struct rather than a tuple of six things so that the one place
/// the lock is held reads as a list of what is being taken out of it.
struct Groundwork {
    profile: Profile,
    version_id: String,
    service: launcher_core::InstallService,
    profile_natives: std::path::PathBuf,
    scratch_natives: std::path::PathBuf,
    online: bool,
    console: GameConsole,
}

fn groundwork(core: &mut Launcher) -> Result<Groundwork, String> {
    // The Java list is populated lazily, and `preflight` refuses a launch when
    // it is empty. Without this, a machine with Java 8 installed is told to
    // install Java 8.
    if core.java_installations().is_empty() {
        core.refresh_java();
    }

    // The same profile Home is showing. Shared rather than reimplemented: a
    // launch that started a different profile than the one on the launch screen
    // would be the worst possible version of this bug.
    let profile = crate::api::current_profile(core)
        .ok_or_else(|| "there are no profiles".to_owned())?;

    let version_id = profile.version_id.clone();
    Ok(Groundwork {
        profile_natives: core.dirs_for(&profile).natives_dir(),
        scratch_natives: core.scratch_natives_dir(&version_id),
        service: core.install_service(),
        online: core.network_policy().online,
        console: core.settings().game_console,
        profile,
        version_id,
    })
}

/// The whole launch, on the launch thread.
///
/// Returns the progress the supervisor should settle on. Every early return is
/// a sentence somebody can act on, because this is the only thing the interface
/// will be able to show them.
fn perform(app: &tauri::AppHandle, cancel: &Cancel) -> LaunchProgress {
    let work = {
        let state = app.state::<State>();
        let Ok(mut core) = state.core.lock() else {
            return launch::failure("the launcher is in a bad state; restart Fathom");
        };
        match groundwork(&mut core) {
            Ok(work) => work,
            Err(message) => return launch::failure(message),
        }
    };

    let report = |progress: LaunchProgress| {
        app.state::<State>().launch.set(progress);
    };

    // -- 1. the version document ------------------------------------------
    report(LaunchProgress::from_stage(&InstallStage::ResolvingMetadata));
    let version = match work.service.metadata().cached(&work.version_id) {
        Some(version) => version,
        None if !work.online => {
            return launch::failure(
                "Offline mode is on and this version has not been downloaded yet.",
            );
        }
        None => match work.service.metadata().fetch(&work.version_id, launch::PURPOSE) {
            Ok(version) => version,
            Err(error) => {
                return launch::failure(format!("Could not read the version manifest. {error}"))
            }
        },
    };

    if cancel.is_cancelled() {
        return launch::cancelled();
    }

    // -- 2. is anything missing? -------------------------------------------
    //
    // Existence-only, against the profile's own natives directory, which is the
    // same check `Launcher::launch` will make in a moment. Doing it here as
    // well is what lets a complete installation go straight to Starting without
    // a network request — the overwhelmingly common case, and the one where a
    // spurious "Downloading" flash would be most obviously wrong.
    let needs_files = match work.service.resolve_cached(&version, launch::PURPOSE) {
        Ok(resolved) => {
            let check = work.service.quick_check(&resolved, &work.profile_natives);
            let snapshot = InstallSnapshot::from_report(&check, &resolved);
            // Natives alone are not a download: `Launcher::launch` extracts
            // them from archives that are already on disk.
            !snapshot.state.is_playable() && !snapshot.only_natives_missing()
        }
        // No cached asset index yet, so we cannot tell. Assume work is needed;
        // the install is idempotent and will transfer nothing if it is not.
        Err(_) => true,
    };

    // -- 3. download, if it is needed --------------------------------------
    if needs_files {
        if !work.online {
            return launch::failure(
                "Some game files are missing and Offline mode blocks downloads.",
            );
        }

        report(LaunchProgress::from_stage(&InstallStage::FetchingAssetIndex));
        let resolved = match work.service.prepare(&work.version_id, launch::PURPOSE, cancel) {
            Ok(resolved) => resolved,
            Err(error) => {
                return if cancel.is_cancelled() {
                    launch::cancelled()
                } else {
                    launch::failure(format!("Could not work out what to download. {error}"))
                }
            }
        };

        let result = work.service.install(
            &resolved,
            &work.scratch_natives,
            cancel,
            &mut |stage| report(LaunchProgress::from_stage(stage)),
        );
        if let Err(error) = result {
            return if cancel.is_cancelled() {
                launch::cancelled()
            } else {
                launch::failure(format!("The download did not finish. {error}"))
            };
        }
    }

    if cancel.is_cancelled() {
        return launch::cancelled();
    }

    // -- 4. start the game --------------------------------------------------
    //
    // Past this point Stop is no longer offered, so the phase changes before
    // the lock is taken rather than after: the interface must not show a
    // cancellable state during the second in which cancelling would do nothing.
    report(LaunchProgress::starting());

    let status = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else {
            return launch::failure("the launcher is in a bad state; restart Fathom");
        };
        match core.launch(&work.profile, &version) {
            Ok(status) => status,
            Err(refusal) => return launch::failure(sentence(&refusal.to_string())),
        }
    };

    tracing::info!(pid = status.pid, "the interface started a session");

    // -- 4a. the bridge -----------------------------------------------------
    //
    // Started after the game, so a game that never starts never leaves a
    // handshake naming a listener. Held by this thread and dropped when the
    // session ends, which stops the listener and removes the file -- a stale
    // handshake would tell the next game to talk to a port nobody is on.
    //
    // A bridge that cannot start is not a failed launch: the game runs
    // perfectly well without one, and the module reports "no launcher" rather
    // than complaining.
    let _bridge = match open_bridge(app, &work.profile) {
        Ok(bridge) => Some(bridge),
        Err(error) => {
            tracing::warn!(error = %error, "the launcher bridge did not start");
            None
        }
    };

    // The session descriptor is removed on the way out, alongside the
    // handshake. It is written by `services::launch` before the process starts;
    // leaving it behind would tell a game somebody later started by hand in the
    // same directory that Fathom launched it into a profile, and the main menu
    // would then show a profile name for a session the launcher had nothing to
    // do with.
    let descriptor_directory = {
        let state = app.state::<State>();
        state
            .core
            .lock()
            .ok()
            .map(|core| core.dirs_for(&work.profile).minecraft_dir())
    };
    let _descriptor = descriptor_directory.map(SessionDescriptorGuard);

    if work.console.opens_at_launch() {
        open_console(app);
    }

    // -- 5. wait ------------------------------------------------------------
    let started = launch::now();
    let profile_id = work.profile.id.clone();
    loop {
        std::thread::sleep(POLL);

        let current = {
            let state = app.state::<State>();
            let Ok(core) = state.core.lock() else {
                // The game is still running; we simply cannot see it any more.
                return launch::failure("Fathom lost track of the session, but Minecraft is still running.");
            };
            core.game_status(&profile_id)
        };

        let Some(current) = current else {
            // The registry forgot it, which means something else reaped it.
            // Not an error, and not something to invent a verdict for.
            return launch::finished(true, "The session ended.".to_owned());
        };

        if current.is_running() {
            app.state::<State>()
                .launch
                .set(launch::running(started.elapsed()));
            continue;
        }

        let clean = current.outcome.is_clean_exit();
        if work.console.opens_on_exit(!clean) {
            raise_console_if_absent(app);
        }
        return launch::finished(clean, current.outcome.message());
    }
}

/// Removes the session descriptor when the launch thread ends.
///
/// A guard rather than a call at the bottom of the loop, because that loop has
/// four returns and one of them is a lock failure. A drop happens on all of
/// them, including a panic.
struct SessionDescriptorGuard(std::path::PathBuf);

impl Drop for SessionDescriptorGuard {
    fn drop(&mut self) {
        launcher_core::session::SessionDescriptor::remove_from(&self.0);
    }
}

/// Capitalise a refusal so it reads as a sentence in the interface.
///
/// The core's errors are written lower-case, because they are usually embedded
/// in a larger message. Here one is the whole message.
/// Start the local bridge for a running game.
///
/// # What the game is told, and what it is not
///
/// The handshake carries the launcher's own path and version, this profile's id
/// and name, the theme, and the list of profiles so the in-game picker has
/// something to show. It carries **nothing about the account**: no token, no
/// username, no session id. `launcher_core::bridge` has a test that reads the
/// bytes back and checks.
fn open_bridge(
    app: &tauri::AppHandle,
    profile: &launcher_core::profiles::Profile,
) -> Result<Bridge, String> {
    // The background the launcher is showing, so the hub can wear a theme
    // derived from the same picture. The module maps `bg_1`..`bg_4` onto its
    // own names, so no translation table has to exist on this side.
    let theme = app
        .state::<State>()
        .background
        .lock()
        .map(|current| current.clone())
        .unwrap_or_default();

    let (game_directory, handshake) = {
        let state = app.state::<State>();
        let core = state.core.lock().map_err(|_| "the launcher is in a bad state")?;
        let directory = core.dirs_for(profile).minecraft_dir();

        let profiles = core
            .profiles()
            .into_iter()
            .map(|other| (other.id.as_str().to_owned(), other.display_name.clone()))
            .collect();

        (
            directory,
            bridge::Handshake {
                // Where this executable is, so the module can start it once the
                // launcher has been closed. Recorded rather than guessed, and
                // checked to still exist on the other side before anything runs.
                launcher_path: std::env::current_exe().ok(),
                launcher_version: env!("CARGO_PKG_VERSION").to_owned(),
                profile_id: profile.id.as_str().to_owned(),
                profile_name: profile.display_name.clone(),
                theme,
                profiles,
            },
        )
    };

    let handle = app.clone();
    bridge::start(&game_directory, &handshake, move |command| {
        serve_bridge(&handle, command)
    })
    .map_err(|error| error.to_string())
}

/// Act on one command from a running game.
///
/// Runs on the bridge's own thread, so anything touching a window is posted to
/// the main thread and this returns immediately: the game is waiting on the
/// reply with a quarter-second timeout, and a reply that arrived after the
/// window was raised would be a reply that never arrived.
fn serve_bridge(app: &tauri::AppHandle, command: bridge::Command) -> bridge::Reply {
    match command {
        bridge::Command::Ping => bridge::Reply::Ok("fathom".to_owned()),
        bridge::Command::Focus => {
            let handle = app.clone();
            let posted = app.run_on_main_thread(move || {
                if let Some(window) = handle.get_webview_window("main") {
                    // Unminimise first: `set_focus` on a minimised window
                    // raises nothing on Windows, which reads as the button
                    // doing nothing at all.
                    let _ = window.unminimize();
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            });
            match posted {
                Ok(()) => bridge::Reply::ok(),
                Err(error) => bridge::Reply::No(error.to_string()),
            }
        }
        bridge::Command::Diagnostics => {
            // Raise the window, then tell the interface which page to show. The
            // event is fire-and-forget: the reply says the launcher took the
            // request, not that a page has finished rendering, and a game
            // waiting a quarter of a second for a webview to navigate would time
            // out on a launcher that was working perfectly.
            let raised = serve_bridge(app, bridge::Command::Focus);
            if matches!(raised, bridge::Reply::No(_)) {
                return raised;
            }
            match app.emit("fathom://show-diagnostics", ()) {
                Ok(()) => bridge::Reply::ok(),
                Err(error) => bridge::Reply::No(error.to_string()),
            }
        }
        bridge::Command::NextProfile(id) => select_profile(app, &id),
        bridge::Command::RestartInto(id) => match select_profile(app, &id) {
            // The launcher does not kill the game. Selecting the profile and
            // raising the window is the whole of what "restart into" can
            // honestly do from here; the player closes the game themselves.
            bridge::Reply::Ok(_) => {
                let _ = serve_bridge(app, bridge::Command::Focus);
                bridge::Reply::Ok("selected".to_owned())
            }
            refusal => refusal,
        },
    }
}

/// Make a profile the one that launches next.
fn select_profile(app: &tauri::AppHandle, id: &str) -> bridge::Reply {
    let state = app.state::<State>();
    let Ok(mut core) = state.core.lock() else {
        return bridge::Reply::No("busy".to_owned());
    };
    let Ok(parsed) = launcher_core::profiles::ProfileId::new(id) else {
        return bridge::Reply::No("not a profile".to_owned());
    };
    if !core.profiles().iter().any(|profile| profile.id == parsed) {
        return bridge::Reply::No("no such profile".to_owned());
    }
    let mut settings = core.settings().clone();
    settings.selected_profile = Some(parsed);
    match core.update_settings(settings) {
        Ok(()) => bridge::Reply::ok(),
        Err(error) => bridge::Reply::No(error.to_string()),
    }
}

fn sentence(message: &str) -> String {
    let mut chars = message.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => "The launch was refused.".to_owned(),
    }
}

// ---------------------------------------------------------------------------
// The console window
// ---------------------------------------------------------------------------

/// The label the frontend checks to decide what to render.
pub const CONSOLE_LABEL: &str = "console";

/// Show the game console, creating it if it is not already open.
///
/// # This must never block
///
/// Every window API -- `build`, `show`, `set_focus` -- reaches the event loop.
/// Called from the launch thread they do not merely *reach* it, they **wait**
/// for it, and the first version of this file did exactly that. The result was
/// a hard deadlock: the launch thread sat inside `show()` waiting for the main
/// thread, while the main thread sat inside a synchronous command -- Tauri runs
/// those on the main thread -- and neither window pumped another message. The
/// launcher and the console both froze the moment a session ended.
///
/// So the work is *posted* to the main thread and this returns immediately.
/// Nothing here needs an answer: the console is a side effect, and the launch
/// has nothing to do with whether a window opened.
///
/// Never fatal, for the same reason. A window that could not be created is
/// worth a log line and nothing more -- the game is running, the output is on
/// disk, and the Logs screen has the same bytes.
pub fn open_console(app: &tauri::AppHandle) {
    post(app, true);
}

/// Open the console only if it is not already up.
///
/// Used when a session ends. With `Always` the window has been open since the
/// game started and is already showing the outcome; raising it again would take
/// the focus off whatever the user moved to while they played, to tell them
/// something already on their screen.
fn raise_console_if_absent(app: &tauri::AppHandle) {
    post(app, false);
}

/// Kept at `debug`: this trail is how the blocking-versus-posted question was
/// settled the first time (the "post returned" line must appear BEFORE "on the
/// main thread"), and it is the first thing worth looking at if a session ever
/// freezes the window again.
fn post(app: &tauri::AppHandle, focus_existing: bool) {
    tracing::debug!(focus_existing, "console: posting to the main thread");
    let handle = app.clone();
    let posted = app.run_on_main_thread(move || {
        tracing::debug!("console: on the main thread");
        if let Some(existing) = handle.get_webview_window(CONSOLE_LABEL) {
            tracing::debug!("console: already open");
            if focus_existing {
                let _ = existing.show();
                let _ = existing.unminimize();
                let _ = existing.set_focus();
            }
            return;
        }

        let built = WebviewWindowBuilder::new(
            &handle,
            CONSOLE_LABEL,
            WebviewUrl::App("index.html".into()),
        )
        .title("Fathom \u{2014} Game console")
        .inner_size(880.0, 560.0)
        .min_inner_size(520.0, 320.0)
        .background_color(tauri::window::Color(0, 0, 0, 255))
        .resizable(true)
        .build();
        tracing::debug!(ok = built.is_ok(), "console: build returned");

        if let Err(error) = built {
            tracing::warn!(%error, "could not open the game console window");
        }
    });
    tracing::debug!("console: post returned");

    if let Err(error) = posted {
        tracing::warn!(%error, "could not reach the main thread to open the console");
    }
}

/// Close the console, if it is open. Used by the console's own Close button.
///
/// Posted like the rest: this one *is* called from the main thread today, since
/// commands run there, but routing it through the same door means it stays
/// correct if that ever changes.
pub fn close_console(app: &tauri::AppHandle) {
    let handle = app.clone();
    let posted = app.run_on_main_thread(move || {
        if let Some(window) = handle.get_webview_window(CONSOLE_LABEL) {
            let _ = window.close();
        }
    });
    if let Err(error) = posted {
        tracing::warn!(%error, "could not reach the main thread to close the console");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::launch::Phase;

    #[test]
    fn a_refusal_is_capitalised_for_display() {
        assert_eq!(sentence("no Java 8 runtime was found"), "No Java 8 runtime was found");
    }

    /// An already-capital first letter must survive, and so must the rest of a
    /// message that starts with something that has no upper case at all.
    #[test]
    fn capitalising_does_not_damage_the_message() {
        assert_eq!(sentence("Java could not start"), "Java could not start");
        assert_eq!(sentence("1 file is missing"), "1 file is missing");
        assert_eq!(sentence(""), "The launch was refused.");
    }

    /// The console window is the same bundle under a different label. If that
    /// label ever drifts from the one the frontend checks, the console opens
    /// showing a second copy of the launcher.
    #[test]
    fn the_console_label_is_the_one_the_frontend_looks_for() {
        assert_eq!(CONSOLE_LABEL, "console");
    }

    #[test]
    fn the_poll_is_frequent_enough_to_feel_immediate() {
        assert!(POLL <= Duration::from_millis(500));
    }

    #[test]
    fn a_launch_that_has_reached_the_game_is_no_longer_cancellable() {
        assert!(!Phase::Running.is_cancellable());
    }
}
