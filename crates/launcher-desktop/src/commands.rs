//! Every command the WebView may invoke.
//!
//! Separated from `main.rs` so that the window's setup and the boundary's
//! surface are two things you can read independently. The list of functions in
//! this file *is* the list of things the frontend can cause to happen — there
//! is no dynamic dispatch, no "run operation by name", and no escape hatch. If
//! something is not here, the interface cannot do it.
//!
//! Each command takes plain data and returns plain data. Several of them return
//! a whole refreshed view rather than an acknowledgement: a toggle that returns
//! the new state means the page cannot drift from the core by applying an
//! optimistic update that the core then declines.

use launcher_core::profiles::Profile;
use launcher_core::Launcher;

use crate::api;
use crate::State;

/// Lock the launcher, or fail with a message the interface can show.
macro_rules! locked {
    ($state:expr) => {
        $state
            .core
            .lock()
            .map_err(|_| "launcher state is poisoned")?
    };
}

/// Resolve the profile a page is about.
///
/// `None` means "whatever is selected", which is what every page sends when the
/// user has not explicitly picked one. Falling back to the first profile rather
/// than erroring keeps a fresh install from showing six broken pages.
fn resolve_profile(core: &Launcher, id: Option<&str>) -> Result<Profile, String> {
    if let Some(id) = id {
        return api::profiles::find(core, id).ok_or_else(|| "no such profile".to_owned());
    }
    let selected = core.settings().selected_profile.clone();
    let profiles = core.profiles();
    selected
        .and_then(|selected| {
            profiles
                .iter()
                .find(|profile| profile.id == selected)
                .cloned()
        })
        .or_else(|| profiles.into_iter().next())
        .ok_or_else(|| "there are no profiles".to_owned())
}

// ---------------------------------------------------------------------------
// Profiles
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn profiles_view(
    state: tauri::State<'_, State>,
) -> Result<Vec<api::profiles::ProfileRow>, String> {
    let core = locked!(state);
    Ok(api::profiles::list(&core))
}

#[tauri::command]
pub fn profile_detail(
    state: tauri::State<'_, State>,
    id: String,
) -> Result<api::profiles::ProfileDetail, String> {
    let core = locked!(state);
    api::profiles::detail(&core, &id).ok_or_else(|| "no such profile".to_owned())
}

/// A profile's artwork, if the user placed one. See `api::profiles::art`.
#[tauri::command]
pub fn profile_art(state: tauri::State<'_, State>, id: String) -> Result<Option<String>, String> {
    let core = locked!(state);
    Ok(api::profiles::art(&core, &id))
}

#[tauri::command]
pub fn select_profile(state: tauri::State<'_, State>, id: String) -> Result<(), String> {
    let mut core = locked!(state);
    let parsed = api::profiles::parse_id(&id)?;
    let mut settings = core.settings().clone();
    settings.selected_profile = Some(parsed);
    core.update_settings(settings).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn create_profile(state: tauri::State<'_, State>, name: String) -> Result<(), String> {
    let core = locked!(state);
    let name = name.trim();
    if name.is_empty() {
        return Err("a profile needs a name".to_owned());
    }
    core.create_profile(name)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn rename_profile(
    state: tauri::State<'_, State>,
    id: String,
    name: String,
) -> Result<(), String> {
    let core = locked!(state);
    let parsed = api::profiles::parse_id(&id)?;
    let name = name.trim();
    if name.is_empty() {
        return Err("a profile needs a name".to_owned());
    }
    core.rename_profile(&parsed, name)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn duplicate_profile(state: tauri::State<'_, State>, id: String) -> Result<(), String> {
    let core = locked!(state);
    let parsed = api::profiles::parse_id(&id)?;
    let source = api::profiles::find(&core, &id).ok_or_else(|| "no such profile".to_owned())?;
    core.clone_profile(&parsed, &format!("{} copy", source.display_name))
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn delete_profile(state: tauri::State<'_, State>, id: String) -> Result<(), String> {
    let core = locked!(state);
    let parsed = api::profiles::parse_id(&id)?;
    core.delete_profile(&parsed)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Save the drawer's fields.
///
/// One command rather than one per field: the drawer has a Save button, and
/// writing the profile on every keystroke would be many chances for a
/// half-applied edit and no benefit.
/// One profile edit, as the drawer has it.
///
/// A struct rather than eight positional parameters: the edit form grew a Java
/// policy and the signature stopped being readable. Adding a field here is a
/// line in two files instead of a new argument threaded through both sides.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileEdit {
    pub id: String,
    pub name: String,
    pub memory_mb: u32,
    pub extra_args: String,
    pub keep_launcher_open: bool,
    pub loader: String,
    /// Absent leaves the profile's existing policy alone.
    pub java: Option<String>,
}

#[tauri::command]
pub fn save_profile(state: tauri::State<'_, State>, edit: ProfileEdit) -> Result<(), String> {
    let ProfileEdit {
        id,
        name,
        memory_mb,
        extra_args,
        keep_launcher_open,
        loader,
        java,
    } = edit;

    let core = locked!(state);
    let mut profile =
        api::profiles::find(&core, &id).ok_or_else(|| "no such profile".to_owned())?;

    let name = name.trim();
    if name.is_empty() {
        return Err("a profile needs a name".to_owned());
    }
    // A guard rail, not a preference. Below 512 MB 1.8.9 will not start, and
    // above 16 GB somebody has typed a number rather than chosen one.
    if !(512..=16_384).contains(&memory_mb) {
        return Err("memory must be between 512 MB and 16 GB".to_owned());
    }
    let loader =
        api::profiles::loader_from_id(&loader).ok_or_else(|| "unknown loader".to_owned())?;

    // A Fathom profile's loader is not a field, it is what the type means. A
    // save that moved one to Vanilla would produce a profile whose components
    // could never load, and the next launch would put them back -- so it is
    // refused here rather than half-applied.
    if profile.kind.owns_components() && profile.kind.implied_loader() != Some(loader) {
        return Err(
            "A Fathom profile's loader comes with the profile type. Change the type to Custom \
             first."
                .to_owned(),
        );
    }

    // A derived profile inherits the technical half from its parent, so
    // accepting these here would write values the next launch overwrites.
    if profile.is_derived() {
        return Err(
            "This profile inherits its version, loader and memory from another one. Edit the \
             parent, or make this an independent profile."
                .to_owned(),
        );
    }

    profile.display_name = name.to_owned();
    profile.memory.heap_mb = memory_mb;
    profile.jvm.extra_args = extra_args.split_whitespace().map(str::to_owned).collect();
    profile.keep_launcher_open = keep_launcher_open;
    profile.loader.kind = loader;

    if let Some(policy) = java {
        // A path only ever comes from the picker, so a policy that needs one and
        // arrives without it keeps whatever the profile already had rather than
        // being stored half-set.
        let existing = profile
            .java
            .explicit_path()
            .map(std::path::Path::to_path_buf);
        profile.java = launcher_core::profiles::JavaPolicy::from_id(&policy, existing)
            .ok_or_else(|| format!("unknown Java policy: {policy}"))?;
    }

    core.save_profile(&profile).map_err(|e| e.to_string())
}

/// Change a profile's loader, from the Mods screen's way out.
///
/// Separate from `save_profile` because it is one decision with one
/// consequence, and routing it through the whole edit form would mean the Mods
/// page had to send five fields it does not have.
///
/// A Vanilla profile that gains a loader becomes Custom: it is being assembled
/// by hand from here on, and calling it Vanilla afterwards would be a label
/// that no longer describes it.
#[tauri::command]
pub fn set_loader(
    state: tauri::State<'_, State>,
    id: Option<String>,
    loader: String,
) -> Result<(), String> {
    let core = locked!(state);
    let mut profile = resolve_profile(&core, id.as_deref())?;
    let loader =
        api::profiles::loader_from_id(&loader).ok_or_else(|| "unknown loader".to_owned())?;
    if !loader.is_implemented() {
        return Err(format!("{} is not ready yet.", loader.label()));
    }

    profile.loader.kind = loader;
    // Left unset: `ensure_loader` pins the newest stable build at first launch.
    profile.loader.version = None;
    if profile.kind == launcher_core::profiles::ProfileKind::Vanilla {
        profile.kind = launcher_core::profiles::ProfileKind::Custom;
    }
    core.save_profile(&profile).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// Mods
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn mods_view(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::mods::ModsView, String> {
    let core = locked!(state);
    let profile = resolve_profile(&core, profile.as_deref())?;
    Ok(api::mods::view(&core, &profile))
}

#[tauri::command]
pub fn set_mod_enabled(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    id: String,
    enabled: bool,
) -> Result<api::mods::ModsView, String> {
    let core = locked!(state);
    let mut profile = resolve_profile(&core, profile.as_deref())?;
    let mod_id = api::mods::parse_id(&id)?;
    core.set_mod_enabled(&mut profile, &mod_id, enabled)
        .map_err(|e| e.to_string())?;
    Ok(api::mods::view(&core, &profile))
}

/// Forget a mod, and delete its bytes when nothing else refers to them.
///
/// The store is shared between profiles, so "this profile stopped using it" is
/// not "nobody is using it". The core consults every profile before deleting
/// anything; see `Launcher::remove_mod_and_collect`.
#[tauri::command]
pub fn remove_mod(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    id: String,
) -> Result<api::mods::ModsView, String> {
    let core = locked!(state);
    let mut profile = resolve_profile(&core, profile.as_deref())?;
    let mod_id = api::mods::parse_id(&id)?;
    core.remove_mod_and_collect(&mut profile, &mod_id)
        .map_err(|e| e.to_string())?;
    Ok(api::mods::view(&core, &profile))
}

/// Search the mod repository for things this profile could load.
///
/// Every request needs a per-action grant, which the core mints from this call
/// -- the user pressing a key in a search box is the action. Nothing here can
/// name a host: the destination comes from the compile-time allowlist.
#[tauri::command]
pub fn browse_mods(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    query: String,
    offset: Option<usize>,
) -> Result<api::mods::BrowseView, String> {
    let core = locked!(state);
    let target = resolve_profile(&core, profile.as_deref())?;
    api::mods::browse(&core, &target, &query, offset.unwrap_or(0))
}

/// Install a mod the user picked out of the browse results.
///
/// Takes the repository's project id rather than a URL or a filename. The file
/// to fetch is resolved from the profile's Minecraft version and loader, and
/// its bytes are checked against the hash the repository published before
/// anything is written.
#[tauri::command]
pub fn install_mod(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    project: String,
) -> Result<api::mods::ModsView, String> {
    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;
    core.install_mod_from_repository(&mut target, &project)
        .map_err(|e| e.to_string())?;
    Ok(api::mods::view(&core, &target))
}

/// Install a first-party component the user asked for, such as Fabric API.
#[tauri::command]
pub fn install_component(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    component: String,
) -> Result<api::mods::ModsView, String> {
    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;
    core.install_component(&mut target, &component)
        .map_err(|e| e.to_string())?;
    Ok(api::mods::view(&core, &target))
}

/// Which of this profile's managed mods have a newer build.
///
/// One request per updatable mod, so this is a deliberate action rather than
/// something the page does on open. A user-supplied jar has no project to ask
/// about and is counted separately rather than silently reported as current.
#[tauri::command]
pub fn mod_updates(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::mods::UpdatesView, String> {
    let core = locked!(state);
    let target = resolve_profile(&core, profile.as_deref())?;
    Ok(api::mods::updates(&core, &target))
}

/// Open a mod's page on the repository.
///
/// Takes a slug, not a URL. The URL is built in `pages::project_url` from a
/// host written in that file and a slug checked character by character, so the
/// WebView gains "open a project page" rather than "open a URL".
#[tauri::command]
pub fn open_project_page(slug: String) -> Result<(), String> {
    crate::pages::open_project(&slug)
}

/// Add a jar the user picks from their own disk.
///
/// The dialog opens here, in Rust, not in the WebView. The frontend asks for a
/// mod to be added and is told what happened; it never sees the path, never
/// reads the file, and cannot open a picker of its own. That is the reason this
/// crate depends on `rfd` rather than on Tauri's dialog plugin.
#[tauri::command]
pub fn add_mod(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::mods::ModsView, String> {
    let chosen = rfd::FileDialog::new()
        .add_filter("Minecraft mod", &["jar"])
        .set_title("Add a mod")
        .pick_file();

    let core = locked!(state);
    let mut profile = resolve_profile(&core, profile.as_deref())?;

    // Cancelling is not an error. Returning the unchanged view means the page
    // re-renders identically instead of showing a failure for a decision the
    // user made on purpose.
    if let Some(path) = chosen {
        core.add_mod_from_file(&mut profile, &path)
            .map_err(|e| e.to_string())?;
    }
    Ok(api::mods::view(&core, &profile))
}

// ---------------------------------------------------------------------------
// Performance
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn performance_view(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::performance::PerformanceView, String> {
    let core = locked!(state);
    let profile = resolve_profile(&core, profile.as_deref())?;
    Ok(api::performance::view(&core, &profile))
}

#[tauri::command]
pub fn set_preset(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    preset: String,
) -> Result<api::performance::PerformanceView, String> {
    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;
    let preset = api::performance::preset_from_id(&preset)
        .ok_or_else(|| format!("unknown preset: {preset}"))?;
    api::performance::apply_preset(&mut target, preset);
    core.save_profile(&target).map_err(|e| e.to_string())?;
    Ok(api::performance::view(&core, &target))
}

#[tauri::command]
pub fn set_optimization(
    state: tauri::State<'_, State>,
    profile: Option<String>,
    id: String,
    enabled: bool,
) -> Result<api::performance::PerformanceView, String> {
    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;
    api::performance::set_optimization(&mut target, &id, enabled)?;
    core.save_profile(&target).map_err(|e| e.to_string())?;
    Ok(api::performance::view(&core, &target))
}

/// Raise a profile's frame limit and turn VSync off.
///
/// The profile is not saved here because nothing about the profile changes:
/// this edits the game's own `options.txt`, which is not part of the profile
/// record. The view is returned so the page can drop the notice it was showing.
#[tauri::command]
pub fn unlock_frame_rate(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::performance::PerformanceView, String> {
    let core = locked!(state);
    let target = resolve_profile(&core, profile.as_deref())?;
    api::performance::unlock_frame_rate(&core, &target)?;
    Ok(api::performance::view(&core, &target))
}

// ---------------------------------------------------------------------------
// Settings, Privacy, Recovery
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn settings_view(
    state: tauri::State<'_, State>,
) -> Result<api::settings::SettingsView, String> {
    let mut core = locked!(state);
    scan_java_once(&mut core);
    Ok(api::settings::view(&core))
}

/// Make sure Java has been looked for at least once.
///
/// `Launcher::new` starts with an empty list and nothing scanned, because
/// probing runs `java -version` on every candidate and that is not something to
/// do while a window is opening. Nothing then called `refresh_java`, so Settings
/// and Recovery both reported "No Java found" on a machine with Java 8
/// installed -- the launcher had simply never looked.
///
/// Scanned once, lazily, on the first screen that asks. An empty list after a
/// scan is a real answer and is not re-scanned on every render; the Recovery
/// page has an explicit Search button for that.
fn scan_java_once(core: &mut Launcher) {
    if core.java_installations().is_empty() {
        core.refresh_java();
    }
}

#[tauri::command]
pub fn set_setting(
    state: tauri::State<'_, State>,
    id: String,
    value: bool,
) -> Result<api::settings::SettingsView, String> {
    let mut core = locked!(state);
    let next = api::settings::apply(core.settings(), &id, value)?;
    core.update_settings(next).map_err(|e| e.to_string())?;
    Ok(api::settings::view(&core))
}

/// The launcher and game logs, as they are on disk.
#[tauri::command]
pub fn logs_view(state: tauri::State<'_, State>) -> Result<Vec<api::logs::LogSource>, String> {
    let core = locked!(state);
    Ok(api::logs::view(&core))
}

#[tauri::command]
pub fn privacy_view(state: tauri::State<'_, State>) -> Result<api::privacy::PrivacyView, String> {
    let core = locked!(state);
    Ok(api::privacy::view(&core))
}

#[tauri::command]
pub fn recovery_view(
    state: tauri::State<'_, State>,
) -> Result<api::recovery::RecoveryView, String> {
    let mut core = locked!(state);
    scan_java_once(&mut core);
    Ok(api::recovery::view(&core))
}

#[tauri::command]
pub fn run_recovery(
    state: tauri::State<'_, State>,
    id: String,
) -> Result<api::recovery::ActionOutcome, String> {
    let mut core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    api::recovery::run(&mut core, &id)
}

// ---------------------------------------------------------------------------
// Playing
// ---------------------------------------------------------------------------

/// Start a launch.
///
/// Returns immediately, with the first progress snapshot. The work happens on a
/// thread; see `session` for why, and for why this cannot simply block until
/// Minecraft exits.
///
/// Takes an `AppHandle` rather than `State` because the thread outlives this
/// call and needs its own way back to the shared state.
#[tauri::command]
pub fn play(app: tauri::AppHandle) -> Result<api::launch::LaunchProgress, String> {
    crate::session::start(&app)
}

/// Ask the launch in flight to stop.
///
/// Only meaningful before the game process exists. Once Minecraft is up this
/// does nothing and says so, rather than killing a game somebody is playing.
#[tauri::command]
pub fn cancel_launch(app: tauri::AppHandle) -> Result<api::launch::LaunchProgress, String> {
    crate::session::cancel(&app)
}

/// Where the launch has got to. Polled by the launch screen while one is live.
///
/// Deliberately touches `state.launch` and **not** `state.core`. Tauri runs a
/// synchronous command on the main thread, so a command that waited on the
/// launcher's mutex would freeze the window for exactly as long as the launch
/// thread held it -- which is the whole of `Launcher::launch`, including a
/// loader download. The progress cell is a separate lock held for nanoseconds,
/// which is what keeps the progress bar moving while that is going on.
#[tauri::command]
pub fn launch_progress(
    state: tauri::State<'_, State>,
) -> Result<api::launch::LaunchProgress, String> {
    Ok(state.launch.progress())
}

/// The game console window's content.
#[tauri::command]
pub fn console_view(state: tauri::State<'_, State>) -> Result<api::logs::ConsoleView, String> {
    let core = locked!(state);
    Ok(api::logs::console(&core))
}

/// Close the console window from inside it.
///
/// The window has its own frame, so this is not the only way out. It exists
/// because a console that has to be dismissed by hunting for the OS close
/// button is a console people leave open and then complain about.
#[tauri::command]
pub fn close_console(app: tauri::AppHandle) {
    crate::session::close_console(&app);
}

/// Set one multiple-choice setting.
///
/// Separate from `set_setting` rather than a stringly-typed union of the two:
/// the switch command takes a `bool` and is checked by the compiler, and
/// widening it to `serde_json::Value` to accommodate one three-state control
/// would weaken every existing caller to gain nothing.
#[tauri::command]
pub fn set_choice(
    state: tauri::State<'_, State>,
    id: String,
    value: String,
) -> Result<api::settings::SettingsView, String> {
    let mut core = locked!(state);
    let next = api::settings::apply_choice(core.settings(), &id, &value)?;
    core.update_settings(next).map_err(|e| e.to_string())?;
    Ok(api::settings::view(&core))
}

// ---------------------------------------------------------------------------
// Signing in
// ---------------------------------------------------------------------------

/// Begin a Microsoft sign-in. Returns as soon as the attempt is under way.
///
/// Fails immediately when this build has no application id, rather than
/// offering a flow that cannot complete.
#[tauri::command]
pub fn sign_in(app: tauri::AppHandle) -> Result<api::auth::AuthProgress, String> {
    crate::signin::start(&app)
}

/// Sign in with the fake development identity.
///
/// Present in every build so the command list has one shape, and refused in
/// every build that was not compiled with `dev-auth` *and* run with the
/// environment variable set. See `signin::development`.
#[tauri::command]
pub fn sign_in_development(app: tauri::AppHandle) -> Result<api::auth::AuthProgress, String> {
    crate::signin::development(&app)
}

#[tauri::command]
pub fn cancel_sign_in(app: tauri::AppHandle) -> Result<api::auth::AuthProgress, String> {
    crate::signin::cancel(&app)
}

/// Open Microsoft's sign-in page again for the code on screen, for when the
/// browser was closed or never opened. Takes nothing: the page and the code
/// come from the sign-in in flight, not from the window.
#[tauri::command]
pub fn reopen_sign_in_page(app: tauri::AppHandle) -> Result<api::auth::AuthProgress, String> {
    crate::signin::reopen_page(&app)
}

#[tauri::command]
pub fn sign_in_progress(state: tauri::State<'_, State>) -> Result<api::auth::AuthProgress, String> {
    Ok(state.auth.progress())
}

/// Forget the account, deleting the stored refresh token.
#[tauri::command]
pub fn sign_out(app: tauri::AppHandle) -> Result<api::auth::AuthProgress, String> {
    crate::signin::sign_out(&app)
}

#[tauri::command]
pub fn switch_account(
    app: tauri::AppHandle,
    id: String,
) -> Result<api::auth::AuthProgress, String> {
    crate::signin::switch_account(&app, id)
}

#[tauri::command]
pub fn forget_account(
    app: tauri::AppHandle,
    id: String,
) -> Result<api::auth::AuthProgress, String> {
    crate::signin::forget_account(&app, id)
}

// Native window controls for the integrated title bar. Keeping these as
// narrow commands avoids granting the WebView general window-management
// permissions.
#[tauri::command]
pub fn window_show(window: tauri::Window) -> Result<(), String> {
    window.show().map_err(|error| error.to_string())?;
    window.set_focus().map_err(|error| error.to_string())
}

#[tauri::command]
pub fn window_minimize(window: tauri::Window) -> Result<(), String> {
    window.minimize().map_err(|error| error.to_string())
}

#[tauri::command]
pub fn window_toggle_maximize(window: tauri::Window) -> Result<(), String> {
    window
        .is_maximized()
        .map_err(|error| error.to_string())?
        .then(|| window.unmaximize())
        .unwrap_or_else(|| window.maximize())
        .map_err(|error| error.to_string())
}

/// Toggle the launcher between windowed and fullscreen.
///
/// The window manager restores the previous windowed bounds on the way out, so
/// nothing is recorded here: asking Tauri for the size and position and putting
/// them back by hand would be a second source of truth that drifts the first
/// time somebody moves the window between monitors while fullscreen.
///
/// Only the main window. The game console is a separate window and has no
/// business going fullscreen behind the launcher.
#[tauri::command]
pub fn window_toggle_fullscreen(window: tauri::Window) -> Result<bool, String> {
    let now = window.is_fullscreen().map_err(|error| error.to_string())?;
    window
        .set_fullscreen(!now)
        .map_err(|error| error.to_string())?;
    Ok(!now)
}

#[tauri::command]
pub fn window_close(window: tauri::Window) -> Result<(), String> {
    window.close().map_err(|error| error.to_string())
}

// ---------------------------------------------------------------------------
// Setting a profile up
// ---------------------------------------------------------------------------

/// Everything the setup drawer needs to draw itself.
///
/// Versions, profile types, loaders, Java policies and the answers to open on.
/// One call, because they are one form and rendering half of it while the rest
/// arrives would reflow the drawer as it opens.
#[tauri::command]
pub fn setup_options(state: tauri::State<'_, State>) -> Result<api::setup::SetupOptions, String> {
    let core = locked!(state);
    Ok(api::setup::options(&core))
}

/// The optional components available under a loader.
///
/// Asked separately from `setup_options` because it changes as the user moves
/// between profile types, and re-fetching one list is cheaper than re-fetching
/// the form.
#[tauri::command]
pub fn setup_components(loader: String, selected: Vec<String>) -> Vec<api::setup::ComponentOption> {
    api::setup::components_for(&loader, &selected)
}

/// What is wrong with the answers so far, if anything.
///
/// Lets the drawer disable Create and say why, using the same validation the
/// creation path runs -- rather than a second copy of the rules in TypeScript
/// that can disagree with the first.
#[tauri::command]
pub fn setup_problems(draft: api::setup::Draft) -> Vec<String> {
    match draft.to_blueprint() {
        Ok(blueprint) => blueprint.problems(),
        Err(message) => vec![message],
    }
}

/// Create the profile, install its components, and select it.
///
/// Selected on creation deliberately: somebody who just set a profile up wants
/// to play it, and making them find it in a list afterwards is a step with no
/// decision in it.
#[tauri::command]
pub fn create_profile_from_setup(
    state: tauri::State<'_, State>,
    draft: api::setup::Draft,
) -> Result<api::setup::Created, String> {
    let mut core = locked!(state);
    let blueprint = draft.to_blueprint()?;
    let (profile, report) = core
        .create_from_blueprint(&blueprint)
        .map_err(|error| error.to_string())?;

    let mut settings = core.settings().clone();
    settings.selected_profile = Some(profile.id.clone());
    core.update_settings(settings)
        .map_err(|error| error.to_string())?;

    Ok(api::setup::Created {
        id: profile.id.as_str().to_owned(),
        name: profile.display_name.clone(),
        summary: report.summary(),
        unavailable: report
            .unavailable
            .iter()
            .map(|(id, reason)| format!("{id}: {reason}"))
            .collect(),
    })
}

/// Create a profile that inherits from another.
#[tauri::command]
pub fn derive_profile(
    state: tauri::State<'_, State>,
    parent: String,
    name: String,
) -> Result<(), String> {
    let core = locked!(state);
    let parent = api::profiles::parse_id(&parent)?;
    core.derive_profile(&parent, &name)
        .map(|_| ())
        .map_err(|error| error.to_string())
}

/// Import a jar the user chose, and say what it turned out to be.
///
/// The OptiFine path. Opens the native picker on the Rust side -- the WebView
/// never learns a filesystem path -- copies the bytes into the local
/// content-addressed store, and records the entry as user-supplied.
#[tauri::command]
pub fn import_mod(
    state: tauri::State<'_, State>,
    profile: Option<String>,
) -> Result<api::mods::Imported, String> {
    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;

    let Some(chosen) = rfd::FileDialog::new()
        .add_filter("Mod", &["jar"])
        .set_title("Choose a mod jar")
        .pick_file()
    else {
        return Ok(api::mods::Imported::cancelled());
    };

    let (entry, identified) = core
        .import_mod_from_file(&mut target, &chosen)
        .map_err(|error| error.to_string())?;
    Ok(api::mods::Imported::of(&entry, &identified))
}

/// Import a jar the user dragged onto the window.
///
/// # Why a path is accepted here and nowhere else
///
/// The boundary rule is that the WebView never learns a filesystem path. This
/// is the one place a path travels the other way, and it is safe for a specific
/// reason: the string did not come *from* the WebView's own knowledge of the
/// disk, it came from the operating system's drop event, which is the user
/// physically pointing at a file. The WebView is relaying, not choosing.
///
/// It is still checked as though it were hostile, because a relayed string is
/// still a string: it must be an existing file with a `.jar` extension, and the
/// mod store then verifies the bytes begin like an archive before copying
/// anything. Nothing here reads a directory, follows a pattern, or accepts a
/// second path.
#[tauri::command]
pub fn import_dropped_mod(
    state: tauri::State<'_, State>,
    path: String,
    profile: Option<String>,
) -> Result<api::mods::Imported, String> {
    let source = std::path::PathBuf::from(&path);
    if !source.is_file() {
        return Err("That is not a file.".to_owned());
    }
    if !source
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
    {
        return Err("Only .jar files can be added.".to_owned());
    }

    let core = locked!(state);
    let mut target = resolve_profile(&core, profile.as_deref())?;
    let (entry, identified) = core
        .import_mod_from_file(&mut target, &source)
        .map_err(|error| error.to_string())?;
    Ok(api::mods::Imported::of(&entry, &identified))
}

// ---------------------------------------------------------------------------
// User-supplied jars: the Add OptiFine flow
// ---------------------------------------------------------------------------

/// Accept a jar the user picked, and say what it is and where it could go.
///
/// The first half of the Add OptiFine flow. Nothing is added to a profile: the
/// file is verified, hashed, copied into the local content-addressed store and
/// identified, and the answer comes back with the list of profiles it suits.
///
/// The picker opens on this side, so the WebView never learns a filesystem
/// path — the same boundary `import_mod` keeps.
#[tauri::command]
pub fn supply_jar(state: tauri::State<'_, State>) -> Result<api::supplied::SuppliedView, String> {
    let Some(chosen) = rfd::FileDialog::new()
        .add_filter("Mod", &["jar"])
        .set_title("Choose the OptiFine jar you downloaded")
        .pick_file()
    else {
        return Ok(api::supplied::SuppliedView::cancelled());
    };
    accept_supplied(&state, &chosen)
}

/// Accept a jar the user dragged onto the window.
///
/// # Why a path is accepted here
///
/// The boundary rule is that the WebView never learns a filesystem path. A drop
/// is the one place a path travels the other way, and it is safe for a specific
/// reason: the string did not come from the WebView's own knowledge of the disk,
/// it came from the operating system's drop event, which is the user physically
/// pointing at a file. The WebView is relaying, not choosing.
///
/// It is still treated as hostile, because a relayed string is still a string:
/// it must be an existing file with a `.jar` extension, and `mods::jar` then
/// checks the bytes are an archive before anything is copied.
#[tauri::command]
pub fn supply_dropped_jar(
    state: tauri::State<'_, State>,
    path: String,
) -> Result<api::supplied::SuppliedView, String> {
    let source = std::path::PathBuf::from(&path);
    if !source.is_file() {
        return Err("That is not a file.".to_owned());
    }
    if !source
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
    {
        return Err("Only .jar files can be added. OptiFine downloads as a .jar.".to_owned());
    }
    accept_supplied(&state, &source)
}

fn accept_supplied(
    state: &tauri::State<'_, State>,
    source: &std::path::Path,
) -> Result<api::supplied::SuppliedView, String> {
    let core = locked!(state);
    let (accepted, view) = api::supplied::accept(&core, source)?;
    drop(core);

    *state
        .supplied
        .lock()
        .map_err(|_| "the launcher is in a bad state")? = Some(accepted);
    Ok(view)
}

/// What was supplied last, so the screen survives being navigated away from.
#[tauri::command]
pub fn supplied_jar(state: tauri::State<'_, State>) -> Result<api::supplied::SuppliedView, String> {
    let held = state
        .supplied
        .lock()
        .map_err(|_| "the launcher is in a bad state")?
        .clone();
    let Some(accepted) = held else {
        return Ok(api::supplied::SuppliedView::cancelled());
    };
    let core = locked!(state);
    let fits = launcher_core::mods::supplied::compatible_profiles(&accepted, &core.profiles());
    Ok(api::supplied::SuppliedView::of(&accepted, &fits))
}

/// Add the supplied jar to a profile.
///
/// The second half. Reads the accepted jar from this side rather than from the
/// window, so the hash written into the profile is the one that was actually
/// verified and stored.
#[tauri::command]
pub fn add_supplied_jar(
    state: tauri::State<'_, State>,
    profile: String,
) -> Result<api::supplied::AddedView, String> {
    let accepted = state
        .supplied
        .lock()
        .map_err(|_| "the launcher is in a bad state")?
        .clone()
        .ok_or("There is no file to add. Choose one first.")?;

    let core = locked!(state);
    let mut target = resolve_profile(&core, Some(&profile))?;
    launcher_core::mods::supplied::attach(&mut target.mods, &accepted)
        .map_err(|error| error.to_string())?;
    core.save_profile(&target)
        .map_err(|error| error.to_string())?;
    Ok(api::supplied::AddedView::of(&accepted, &target))
}

/// Open a page in the user's browser.
///
/// The one URL-opening command, and it takes an id rather than a URL: the
/// WebView naming a page to open is a very different thing from the WebView
/// naming a URL to open, and only the first is safe to grant.
#[tauri::command]
pub fn open_page(id: String) -> Result<(), String> {
    crate::pages::open(&id)
}
