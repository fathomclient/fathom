//! Application shell: state, messages, and the sidebar + content layout.
//!
//! The UI holds no credentials, builds no commands, and opens no sockets. Every
//! action goes through [`launcher_core::Launcher`]. Where a capability does not
//! exist yet, the screen says so rather than offering a control that fails.

use std::path::PathBuf;

use iced::futures::channel::mpsc;
use iced::widget::image::Handle;
use iced::widget::{button, column, container, image, mouse_area, row, text, Space, Stack};
use iced::{Alignment, ContentFit, Element, Length, Size, Subscription, Task, Theme};

use launcher_core::auth::DeviceCodePrompt;
use launcher_core::configuration::Settings;
use launcher_core::install::{
    InstallStage, InstallationState, Progress, ResolvedInstall, VerificationReport,
};
use launcher_core::java_runtime::JavaInstallation;
use launcher_core::launching::GameStatus;
use launcher_core::minecraft_runtime::VersionDetail;
use launcher_core::mods::InstalledMod;
use launcher_core::network::Entry as JournalEntry;
use launcher_core::profiles::{Loader, Profile, ProfileId};
use launcher_core::tasks::Cancel;
use launcher_core::{InstallSnapshot, Launcher, Readiness};

use crate::background::{self, BackgroundChoice, BackgroundPlayer};
use crate::components::{extruded, nav_item};
use crate::icons::{icon_inherit, Icon};
use crate::motion;
use crate::screens;
use crate::style;
use crate::worker::{self, Job};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Screen {
    Home,
    Profiles,
    Mods,
    Performance,
    Privacy,
    Recovery,
    Account,
    Settings,
}

impl Screen {
    /// The rail's upper group: the product areas.
    ///
    /// Performance is here rather than inside Settings on purpose. It is one
    /// of the things Fathom *is* -- a first-party optimization module with its
    /// own catalogue and presets -- and burying it under a settings tab would
    /// describe it as a preference.
    pub const PRIMARY: [Screen; 6] = [
        Screen::Home,
        Screen::Profiles,
        Screen::Mods,
        Screen::Performance,
        Screen::Privacy,
        Screen::Recovery,
    ];

    /// The rail's lower group, pinned to the bottom.
    pub const UTILITY: [Screen; 2] = [Screen::Settings, Screen::Account];

    pub fn title(self) -> &'static str {
        match self {
            Screen::Home => "Home",
            Screen::Profiles => "Profiles",
            Screen::Mods => "Mods",
            Screen::Performance => "Performance",
            Screen::Recovery => "Recovery",
            Screen::Account => "Account",
            Screen::Settings => "Settings",
            Screen::Privacy => "Privacy",
        }
    }

    pub fn subtitle(self) -> &'static str {
        match self {
            Screen::Home => "Select, prepare, and launch Minecraft",
            Screen::Profiles => "Separate setups, isolated from each other",
            Screen::Mods => "Jars installed in the selected profile",
            Screen::Performance => "Fathom's optimization module, and what it is doing",
            Screen::Recovery => "Repair the launcher and its game files",
            Screen::Account => "Your Microsoft account and game entitlement",
            Screen::Settings => "Java, appearance, and what may reach the network",
            Screen::Privacy => "Every request this launcher has made, and why",
        }
    }

    pub fn nav_label(self) -> &'static str {
        match self {
            Screen::Home => "Home",
            Screen::Profiles => "Profiles",
            Screen::Mods => "Mods",
            Screen::Performance => "Performance",
            Screen::Recovery => "Recovery",
            Screen::Account => "Account",
            Screen::Settings => "Settings",
            // Not "Diagnostics". This screen is the network journal and the
            // list of hosts the build can reach — there is no recovery or
            // diagnostics screen in this build, and a rail tooltip promising
            // one would be a label that lies about where it goes.
            Screen::Privacy => "Privacy",
        }
    }

    /// The rail glyph.
    ///
    /// One icon family, drawn from `assets/icons`. This used to return Unicode
    /// characters picked from whatever font happened to have them, which is
    /// why the set never looked like a set.
    pub fn icon(self) -> Icon {
        match self {
            Screen::Home => Icon::Home,
            Screen::Profiles => Icon::Profiles,
            Screen::Mods => Icon::Mods,
            Screen::Performance => Icon::Performance,
            Screen::Privacy => Icon::Privacy,
            Screen::Recovery => Icon::Recovery,
            Screen::Account => Icon::Account,
            Screen::Settings => Icon::Settings,
        }
    }
}

/// Transient feedback shown under the header.
#[derive(Debug, Clone)]
pub struct Notice {
    pub tone: Tone,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tone {
    Info,
    Success,
    Warning,
    Error,
}

impl Tone {
    pub fn color(self) -> iced::Color {
        match self {
            Tone::Info => style::ACCENT,
            Tone::Success => style::GOOD,
            Tone::Warning => style::WARN,
            Tone::Error => style::BAD,
        }
    }
}

/// Where sign-in has got to.
///
/// The worker drives this forward on its own; the interface only reflects it.
#[derive(Debug, Default)]
pub enum SignIn {
    #[default]
    Idle,
    /// The request for a code is in flight.
    Starting,
    /// Microsoft returned a code. The worker is polling in the background; the
    /// user has nothing to press.
    AwaitingUser(Box<DeviceCodePrompt>),
}

/// An install or repair in flight.
#[derive(Debug, Clone)]
pub struct InstallState {
    /// The engine's own stage, so the interface can name what is happening
    /// rather than showing a bar that sits at 100% during native extraction.
    pub stage: InstallStage,
    pub cancel: Cancel,
    /// Whether this run started as a repair, which changes what we say when it
    /// finishes.
    pub repairing: bool,
}

impl InstallState {
    /// Position through the download phase, if that is the current stage.
    ///
    /// `None` for every other stage, because there is nothing honest to show:
    /// a fraction invented for "extracting natives" is a guess dressed up as
    /// information.
    pub fn fraction(&self) -> Option<f32> {
        match &self.stage {
            InstallStage::Downloading(p) if p.total > 0 => {
                Some((p.completed as f32 / p.total as f32).clamp(0.0, 1.0))
            }
            InstallStage::Downloading(_) => Some(0.0),
            _ => None,
        }
    }

    pub fn label(&self) -> String {
        match &self.stage {
            InstallStage::ResolvingMetadata => "Resolving version metadata\u{2026}".to_owned(),
            InstallStage::FetchingAssetIndex => "Fetching the asset index\u{2026}".to_owned(),
            InstallStage::Downloading(p) => {
                if p.current.is_empty() {
                    format!("{} of {}", p.completed, p.total)
                } else {
                    format!("{} of {} \u{00B7} {}", p.completed, p.total, p.current)
                }
            }
            InstallStage::ExtractingNatives => "Extracting native libraries\u{2026}".to_owned(),
            InstallStage::Verifying => "Verifying every file\u{2026}".to_owned(),
            InstallStage::Done => "Finishing\u{2026}".to_owned(),
        }
    }

    pub fn bytes_transferred(&self) -> u64 {
        match &self.stage {
            InstallStage::Downloading(p) => p.bytes_transferred,
            _ => 0,
        }
    }
}

/// Everything the interface knows about the game files.
///
/// Held rather than recomputed, because working it out means hashing 139 MB
/// across 771 files. The worker produces it; `readiness` consumes it.
#[derive(Debug, Clone, Default)]
pub struct Installation {
    pub resolved: Option<ResolvedInstall>,
    pub report: Option<VerificationReport>,
    pub snapshot: Option<InstallSnapshot>,
    /// Whether the last report came from a full hash pass rather than an
    /// existence check. The distinction is shown, because "every file present"
    /// and "every file verified" are different claims.
    pub hashed: bool,
}

impl Installation {
    pub fn state(&self) -> Option<InstallationState> {
        self.snapshot.map(|s| s.state)
    }
}

#[derive(Debug, Clone)]
pub enum Message {
    Navigate(Screen),
    DismissNotice,
    /// Presentation-only viewport state used for compact desktop layouts.
    ///
    /// Windows reports a minimise as a resize to zero, which is also how the
    /// launcher learns to stop decoding background frames.
    WindowResized(Size),
    WindowFocusChanged(bool),
    /// A decoded background frame arrived from its decoder thread.
    BackgroundFrame(Box<background::Frame>),
    /// Advance a background crossfade. Only subscribed while one is running.
    BackgroundFade,
    /// Advance the page-change transition. Only subscribed while one is running.
    MotionTick,
    BackgroundSelected(BackgroundChoice),
    ToggleBackgroundMenu,
    CloseBackgroundMenu,

    // Profiles
    NewProfileNameChanged(String),
    CreateProfile,
    SelectProfile(ProfileId),
    CloneProfile(ProfileId),
    /// First press arms; second press within the same selection performs it.
    RequestDeleteProfile(ProfileId),
    ConfirmDeleteProfile(ProfileId),
    CancelDeleteProfile,
    ValidateProfile(ProfileId),
    RepairProfile(ProfileId),
    ExportProfile(ProfileId),
    ImportProfile,
    HeapChanged(String),
    ExtraArgsChanged(String),
    LoaderChanged(Loader),
    KeepOpenChanged(bool),
    SaveProfile,

    // Mods
    ModSearchChanged(String),
    ModFilterChanged(screens::mods::Filter),
    /// Open or close one mod's detail. Keyed by hash, which is what identifies
    /// a jar; a file name can repeat across profiles.
    ModExpanded(String),
    /// Open or close one Privacy group's hostname list.
    PrivacyGroupExpanded(&'static str),
    /// Open or close the editor for a profile, selecting it if needed.
    EditProfile(ProfileId),
    PickModFile,
    RemoveMod(launcher_core::mods::ModId),
    ToggleMod(launcher_core::mods::ModId, bool),
    /// Rebuild the mods directory from the profile's configuration.
    MaterializeMods,
    OpenModsFolder,

    // Account
    BeginSignIn,
    CancelSignIn,
    /// Sign in with the fake local identity. Only reachable in a build that
    /// compiled `launcher-core`'s `dev-auth` feature.
    BeginDevelopmentSignIn,
    SignOut,
    CopyToClipboard(String),

    // Performance
    PresetSelected(launcher_core::profiles::OptimizationPreset),
    OptimizationToggled(String),

    // Settings
    SettingsCategoryChanged(screens::settings::Category),
    SettingsSearchChanged(String),
    OfflineModeChanged(bool),
    UpdateCheckChanged(bool),
    ManagedJavaChanged(bool),
    ModRepositoriesChanged(bool),
    RefreshJava,

    // Home
    PrepareVersion,
    InstallGameFiles,
    VerifyGameFiles,
    RepairGameFiles,
    CancelInstall,
    Play,
    StopGame,

    /// Anything the background worker has to say.
    Worker(worker::Event),

    // Privacy
    RefreshJournal,
    ClearJournal,
}

pub struct App {
    core: Launcher,
    screen: Screen,
    notice: Option<Notice>,
    viewport: Size,
    window_focused: bool,
    /// False while the window is minimised. Windows delivers that as a resize
    /// to zero, and it is the signal that stops background decoding.
    window_visible: bool,
    background: BackgroundPlayer,
    /// Whether the bottom bar's background switcher is open.
    background_menu: bool,
    /// What the startup recovery scan found, in the order it found it.
    startup_findings: Vec<String>,
    /// Mods page: the search box, the active shelf, and which row is open.
    /// Presentation state, so it lives here rather than on the profile.
    mod_search: String,
    mod_filter: screens::mods::Filter,
    expanded_mod: Option<String>,
    /// Which Privacy group has its hostnames showing.
    expanded_privacy_group: Option<&'static str>,
    /// Whether the selected profile's editor is open. Editing is rarer than
    /// choosing what to launch, so it is a disclosure rather than always shown.
    editing_profile: bool,
    /// Settings: the open category, and the cross-category search.
    settings_category: screens::settings::Category,
    settings_search: String,
    /// Drives the page-change transition. 0.0 the instant the screen changes,
    /// 1.0 once it has settled.
    page_motion: motion::Animated,
    /// Optional user-supplied art for the selected profile's Home launch stage.
    /// Loaded on selection rather than during drawing so animated shell frames
    /// never cause repeated disk reads or image decoding.
    profile_art: Option<iced::widget::image::Handle>,

    profiles: Vec<Profile>,
    selected: Option<ProfileId>,
    new_profile_name: String,

    // Editable copy of the selected profile's settings.
    heap_input: String,
    extra_args_input: String,

    mods: Vec<InstalledMod>,
    java: Vec<JavaInstallation>,
    journal: Vec<JournalEntry>,
    version: Option<VersionDetail>,
    installation: Installation,

    sign_in: SignIn,
    install: Option<InstallState>,
    /// The game this launcher started, while it is running. Cleared once the
    /// exit has been reported, so the card does not keep showing a dead
    /// process.
    game: Option<GameStatus>,

    /// How to reach the worker. `None` for the first frame or two, until the
    /// subscription hands us the sender.
    jobs: Option<mpsc::UnboundedSender<Job>>,
    /// Jobs submitted before the worker was ready. Replayed on `Ready`.
    pending: Vec<Job>,
    /// Set while a job is in flight, so the interface can disable the button
    /// that would start a second one.
    busy: Option<&'static str>,
    /// Lets Cancel reach the sign-in thread.
    sign_in_cancel: Option<Cancel>,

    /// The profile a delete has been armed for. Deleting is the one action on
    /// this screen that can lose a world, so it takes two presses rather than
    /// one, and arming is per profile so a mis-click on a different row cannot
    /// inherit the confirmation.
    delete_armed: Option<ProfileId>,
    /// The last validation result, so the screen can show what is wrong without
    /// revalidating on every frame.
    profile_report: Option<launcher_core::profiles::ProfileReport>,
}

impl App {
    /// Build the application around a core that already exists.
    ///
    /// The core is constructed in `main` rather than here, because a failure to
    /// construct it has to open the failure window instead of this one. The
    /// previous version printed to stderr and called `exit(1)`, which in a
    /// release build -- no console -- meant the user double-clicked an icon and
    /// nothing happened.
    pub fn new_with_boot(boot: crate::Boot) -> (Self, Task<Message>) {
        let core = boot
            .core
            .expect("main only builds an App when the core exists");

        // Kept as data, not only as a dismissible notice: Recovery shows the
        // same findings, and a notice the user closed should not take the only
        // record of them with it.
        let startup_findings: Vec<String> = boot
            .startup
            .as_ref()
            .map(|report| {
                report
                    .cleaned_up()
                    .iter()
                    .map(|finding| finding.message().to_string())
                    .collect()
            })
            .unwrap_or_default();

        let startup_notice = boot.startup.as_ref().and_then(|report| {
            let handled = report.cleaned_up();
            (!handled.is_empty()).then(|| {
                handled
                    .iter()
                    .map(|f| f.message())
                    .collect::<Vec<_>>()
                    .join(" ")
            })
        });
        let boot_problems = boot.problems;

        let background = BackgroundPlayer::load(core.paths().root());
        let mut app = Self {
            core,
            screen: Screen::Home,
            notice: None,
            viewport: Size::new(1180.0, 780.0),
            window_focused: true,
            window_visible: true,
            background,
            background_menu: false,
            startup_findings,
            mod_search: String::new(),
            mod_filter: screens::mods::Filter::All,
            expanded_mod: None,
            expanded_privacy_group: None,
            editing_profile: false,
            settings_category: screens::settings::Category::General,
            settings_search: String::new(),
            page_motion: motion::Animated::settled(1.0).with_duration(motion::NORMAL),
            profile_art: None,
            profiles: Vec::new(),
            selected: None,
            new_profile_name: String::new(),
            heap_input: String::new(),
            extra_args_input: String::new(),
            mods: Vec::new(),
            java: Vec::new(),
            journal: Vec::new(),
            version: None,
            installation: Installation::default(),
            sign_in: SignIn::Idle,
            install: None,
            game: None,
            jobs: None,
            pending: Vec::new(),
            busy: None,
            sign_in_cancel: None,
            delete_armed: None,
            profile_report: None,
        };

        // Anything the boot sequence found, surfaced in the window rather
        // than in a console that a release build does not have.
        if let Some(problem) = boot_problems.first() {
            app.warn(problem.summary().to_owned());
        } else if let Some(notice) = startup_notice {
            app.warn(notice);
        }

        app.reload_profiles();
        if app.profiles.is_empty() {
            // A launcher with no profiles has nothing to show. Make one.
            match app.core.create_profile("Minecraft 1.8.9") {
                Ok(_) => app.reload_profiles(),
                Err(e) => app.warn(format!("Could not create the first profile: {e}")),
            }
        }
        app.selected = app
            .core
            .settings()
            .selected_profile
            .clone()
            .or_else(|| app.profiles.first().map(|p| p.id.clone()));
        app.reload_profile_art();
        app.sync_profile_inputs();
        app.reload_mods();
        app.version = app
            .selected_profile()
            .and_then(|p| app.core.cached_version(&p.version_id));

        (app, Task::none())
    }

    pub fn title(&self) -> String {
        format!(
            "{} \u{2014} {}",
            launcher_core::PRODUCT_NAME,
            self.screen.title()
        )
    }

    pub fn theme(&self) -> Theme {
        style::theme()
    }

    /// The single, long-lived worker subscription.
    ///
    /// `Subscription::run` identifies a subscription by the type of the
    /// function, so passing the same `worker::run` every frame keeps one
    /// worker alive for the whole session rather than restarting it.
    pub fn subscription(&self) -> Subscription<Message> {
        let mut subscriptions = vec![
            Subscription::run(worker::run).map(Message::Worker),
            iced::event::listen_with(window_event),
        ];

        // The decoder is keyed by which background is playing, so iced starts
        // one when the selection changes and — the part that matters — drops
        // the previous one, which is what ends its thread. Returning `None`
        // (minimised, or Minecraft running) stops decoding altogether.
        if let Some(choice) = self
            .background
            .active_sequence(self.window_visible, self.game.is_some())
        {
            subscriptions.push(
                Subscription::run_with_id(choice, background::frames(choice))
                    .map(|frame| Message::BackgroundFrame(Box::new(frame))),
            );
        }

        // Only alive while a page transition is actually running.
        if self.page_motion.is_moving() {
            subscriptions.push(Subscription::run(motion::ticks).map(|()| Message::MotionTick));
        }

        // Only alive during the ~420 ms of a crossfade.
        if self.background.transitioning() {
            subscriptions
                .push(Subscription::run(background::fade_ticks).map(|()| Message::BackgroundFade));
        }

        Subscription::batch(subscriptions)
    }

    // -- accessors used by the screen modules -----------------------------

    pub fn core(&self) -> &Launcher {
        &self.core
    }

    pub fn profiles(&self) -> &[Profile] {
        &self.profiles
    }

    pub fn selected_id(&self) -> Option<&ProfileId> {
        self.selected.as_ref()
    }

    pub fn selected_profile(&self) -> Option<&Profile> {
        let id = self.selected.as_ref()?;
        self.profiles.iter().find(|p| &p.id == id)
    }

    /// Where this profile's natives are extracted.
    ///
    /// Per profile rather than shared, because the extracted directory is on
    /// the JVM's library search path and a profile should not be able to put a
    /// `.dll` on another profile's path.
    fn natives_dir(&self) -> Option<PathBuf> {
        let id = self.selected.as_ref()?;
        Some(self.core.profile_dirs(id).natives_dir())
    }

    /// Take a verification result as the new truth about the files.
    ///
    /// Everything that produces a report goes through here so that the
    /// snapshot, the report and the `hashed` flag can never disagree.
    fn adopt_report(&mut self, report: VerificationReport, snapshot: InstallSnapshot) {
        self.installation.report = Some(report);
        self.installation.snapshot = Some(snapshot);
        self.installation.hashed = true;
    }

    pub fn new_profile_name(&self) -> &str {
        &self.new_profile_name
    }

    pub fn heap_input(&self) -> &str {
        &self.heap_input
    }

    pub fn extra_args_input(&self) -> &str {
        &self.extra_args_input
    }

    pub const fn settings_category(&self) -> screens::settings::Category {
        self.settings_category
    }

    pub fn settings_search(&self) -> &str {
        &self.settings_search
    }

    pub const fn editing_profile(&self) -> bool {
        self.editing_profile
    }

    /// Artwork for a specific profile.
    ///
    /// Only the selected profile's art is decoded, so this returns `None` for
    /// the others rather than reading a dozen images to draw a list.
    pub fn profile_art_for(
        &self,
        id: &ProfileId,
    ) -> Option<&iced::widget::image::Handle> {
        (self.selected.as_ref() == Some(id))
            .then_some(self.profile_art.as_ref())
            .flatten()
    }

    pub fn expanded_privacy_group(&self) -> Option<&'static str> {
        self.expanded_privacy_group
    }

    pub fn mod_search(&self) -> &str {
        &self.mod_search
    }

    pub fn mod_filter(&self) -> screens::mods::Filter {
        self.mod_filter
    }

    pub fn expanded_mod(&self) -> Option<&String> {
        self.expanded_mod.as_ref()
    }

    /// Whether the content-addressed store still holds these bytes.
    ///
    /// A managed jar whose store entry has gone is the one mod problem the
    /// launcher can detect on its own, and it is repairable by rebuilding.
    pub fn mod_store_has(&self, sha1: &str) -> bool {
        // `mod_engine()` hands back an owned engine, so it has to outlive the
        // borrow of its store.
        let engine = self.core.mod_engine();
        engine.store().contains(sha1)
    }

    pub fn mods(&self) -> &[InstalledMod] {
        &self.mods
    }

    pub fn java(&self) -> &[JavaInstallation] {
        &self.java
    }

    pub fn journal(&self) -> &[JournalEntry] {
        &self.journal
    }

    pub fn version(&self) -> Option<&VersionDetail> {
        self.version.as_ref()
    }

    pub fn sign_in(&self) -> &SignIn {
        &self.sign_in
    }

    pub fn install(&self) -> Option<&InstallState> {
        self.install.as_ref()
    }

    pub fn installation(&self) -> &Installation {
        &self.installation
    }

    /// The running game, if this launcher started one.
    pub fn game(&self) -> Option<&GameStatus> {
        self.game.as_ref()
    }

    /// Whether a delete has been armed for this profile.
    pub fn delete_armed_for(&self, id: &ProfileId) -> bool {
        self.delete_armed.as_ref() == Some(id)
    }

    pub fn profile_report(&self) -> Option<&launcher_core::profiles::ProfileReport> {
        self.profile_report.as_ref()
    }

    /// A label for whatever background job is running, if any.
    pub fn busy(&self) -> Option<&'static str> {
        self.busy
    }

    /// What the startup scan reported, kept so Recovery can show it rather
    /// than it existing only as a notice that gets dismissed.
    pub fn startup_findings(&self) -> &[String] {
        &self.startup_findings
    }

    /// Whether the worker is connected yet. Everything is submitted through it,
    /// so buttons stay inert for the frame or two before it is.
    pub fn worker_ready(&self) -> bool {
        self.jobs.is_some()
    }

    /// Compact mode protects useful content width at the minimum window size.
    pub fn compact_layout(&self) -> bool {
        self.viewport.width < 980.0
    }

    pub fn background(&self) -> &BackgroundPlayer {
        &self.background
    }

    /// The selected profile's optimization preset.
    pub fn optimization_preset(&self) -> launcher_core::profiles::OptimizationPreset {
        self.selected_profile()
            .map(|p| p.optimizations.preset)
            .unwrap_or_default()
    }

    /// Whether one optimization is switched on for the selected profile.
    ///
    /// The two JVM options live in their own struct rather than in the switch
    /// map, because the launch path reads them directly; everything else is a
    /// map entry keyed by the registry id.
    pub fn optimization_enabled(&self, id: &str) -> bool {
        let Some(profile) = self.selected_profile() else {
            return false;
        };
        match id {
            "jvm.disable-explicit-gc" => profile.optimizations.jvm.disable_explicit_gc,
            "jvm.compile-huge-methods" => profile.optimizations.jvm.compile_huge_methods,
            other => profile
                .optimizations
                .rendering
                .get(other)
                .copied()
                .unwrap_or(false),
        }
    }

    pub fn readiness(&self) -> Readiness {
        match self.selected_profile() {
            Some(profile) => self.core.readiness(
                profile,
                self.version.as_ref(),
                self.installation.snapshot.as_ref(),
            ),
            None => Readiness {
                blockers: Vec::new(),
                java: None,
            },
        }
    }

    // -- helpers -----------------------------------------------------------

    /// Hand a job to the worker, queuing it if the worker has not connected
    /// yet. Never blocks.
    /// Submit a job that is not the user waiting on something.
    ///
    /// Separate from `submit` because that one sets `busy`, which disables the
    /// buttons. Watching a running game lasts as long as the play session, and
    /// greying out the whole interface for it would be absurd.
    fn submit_quietly(&mut self, job: Job) {
        match &self.jobs {
            Some(tx) => {
                if let Err(e) = tx.unbounded_send(job) {
                    tracing::warn!(error = %e, "could not submit a background job");
                }
            }
            None => self.pending.push(job),
        }
    }

    fn submit(&mut self, job: Job, label: &'static str) {
        self.busy = Some(label);
        match &self.jobs {
            Some(tx) => {
                if let Err(e) = tx.unbounded_send(job) {
                    self.busy = None;
                    self.fail(format!("The background worker has stopped: {e}"));
                }
            }
            None => self.pending.push(job),
        }
    }

    fn reload_profiles(&mut self) {
        self.profiles = self.core.profiles();
    }

    fn reload_profile_art(&mut self) {
        self.profile_art = None;
        let Some(id) = self.selected.as_ref() else {
            return;
        };
        let directory = self.core.paths().root().join("profile-art");
        let candidates = ["png", "jpg", "jpeg"]
            .map(|extension| directory.join(format!("{}.{}", id.as_str(), extension)));

        for path in candidates {
            if !path.is_file() {
                continue;
            }
            match ::image::open(&path) {
                Ok(decoded) => {
                    let rgba = decoded.into_rgba8();
                    let (width, height) = rgba.dimensions();
                    self.profile_art = Some(iced::widget::image::Handle::from_rgba(
                        width,
                        height,
                        rgba.into_raw(),
                    ));
                    return;
                }
                Err(error) => {
                    tracing::warn!(
                        path = %path.display(),
                        %error,
                        "profile artwork could not be decoded"
                    );
                }
            }
        }
    }

    fn reload_mods(&mut self) {
        self.mods = match &self.selected {
            Some(id) => self.core.mods_for(id),
            None => Vec::new(),
        };
    }

    fn sync_profile_inputs(&mut self) {
        let values = self
            .selected_profile()
            .map(|p| (p.memory.heap_mb.to_string(), p.jvm.extra_args.join(" ")));
        if let Some((heap, args)) = values {
            self.heap_input = heap;
            self.extra_args_input = args;
        }
    }

    fn inform(&mut self, message: impl Into<String>) {
        self.notice = Some(Notice {
            tone: Tone::Info,
            message: message.into(),
        });
    }

    fn succeed(&mut self, message: impl Into<String>) {
        self.notice = Some(Notice {
            tone: Tone::Success,
            message: message.into(),
        });
    }

    fn warn(&mut self, message: impl Into<String>) {
        self.notice = Some(Notice {
            tone: Tone::Warning,
            message: message.into(),
        });
    }

    fn fail(&mut self, message: impl Into<String>) {
        self.notice = Some(Notice {
            tone: Tone::Error,
            message: message.into(),
        });
    }

    // -- update ------------------------------------------------------------

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Navigate(screen) => {
                if self.screen != screen {
                    // Restart from zero so the incoming page animates in. A
                    // repeat of the current destination is left alone, or
                    // clicking the rail entry you are already on would replay
                    // the transition for no reason.
                    self.page_motion.snap(0.0);
                    self.page_motion.retarget(1.0);
                }
                self.screen = screen;
                match screen {
                    Screen::Mods => self.reload_mods(),
                    Screen::Privacy => self.journal = self.core.network_journal().recent(200),
                    Screen::Settings if self.java.is_empty() => {
                        self.java = self.core.refresh_java().to_vec();
                    }
                    _ => {}
                }
            }
            Message::DismissNotice => self.notice = None,
            Message::WindowResized(size) => {
                // A minimised window is reported as a resize to zero. Keeping
                // the last real size means the layout does not thrash when it
                // is restored.
                self.window_visible = size.width > 0.0 && size.height > 0.0;
                if self.window_visible {
                    self.viewport = size;
                }
            }
            Message::WindowFocusChanged(focused) => self.window_focused = focused,
            Message::BackgroundFrame(frame) => {
                let background::Frame { choice, handle } = *frame;
                self.background.accept(choice, handle);
            }
            Message::MotionTick => {
                self.page_motion.advance(motion::TICK);
            }
            Message::BackgroundFade => {
                self.background
                    .tick_fade(std::time::Duration::from_millis(16));
            }
            Message::BackgroundSelected(choice) => {
                tracing::debug!(background = choice.slug(), "background selected");
                self.background_menu = false;
                if let Err(error) = self.background.select(choice) {
                    tracing::warn!(%error, "could not save background selection");
                    self.warn("The background changed for this session but could not be saved.");
                }
            }
            Message::ToggleBackgroundMenu => self.background_menu = !self.background_menu,
            Message::CloseBackgroundMenu => self.background_menu = false,

            // -- profiles ---------------------------------------------------
            Message::NewProfileNameChanged(value) => self.new_profile_name = value,
            Message::CreateProfile => {
                let name = self.new_profile_name.trim().to_owned();
                if name.is_empty() {
                    self.warn("Give the profile a name first.");
                } else {
                    match self.core.create_profile(&name) {
                        Ok(profile) => {
                            self.new_profile_name.clear();
                            self.selected = Some(profile.id.clone());
                            self.reload_profiles();
                            self.reload_profile_art();
                            self.sync_profile_inputs();
                            self.reload_mods();
                            self.succeed(format!("Created \u{201C}{name}\u{201D}."));
                        }
                        Err(e) => self.fail(format!("Could not create the profile: {e}")),
                    }
                }
            }
            Message::SelectProfile(id) => {
                self.selected = Some(id);
                self.reload_profile_art();
                self.sync_profile_inputs();
                self.reload_mods();
                self.version = self
                    .selected_profile()
                    .and_then(|p| self.core.cached_version(&p.version_id));
                // A different profile has its own natives directory and may
                // have a different version, so nothing we learned about the
                // last one still applies. Dropping it means the screen says
                // "not checked yet" rather than showing another profile's
                // answer as if it were this one's.
                self.installation = Installation::default();
                // Neither an armed delete nor a validation result from another
                // profile has any meaning here.
                self.delete_armed = None;
                self.profile_report = None;
                self.persist_selected_profile();
            }
            Message::CloneProfile(id) => {
                let name = self
                    .profiles
                    .iter()
                    .find(|p| p.id == id)
                    .map_or_else(|| "Copy".to_owned(), |p| format!("{} copy", p.display_name));
                match self.core.clone_profile(&id, &name) {
                    Ok(copy) => {
                        self.reload_profiles();
                        self.succeed(format!(
                            "Cloned to {}. Worlds, options and mods were copied; natives \
                             and logs are rebuilt per profile.",
                            copy.display_name
                        ));
                    }
                    Err(e) => self.fail(format!("Could not clone: {e}")),
                }
            }

            Message::RequestDeleteProfile(id) => {
                self.delete_armed = Some(id);
                self.inform(
                    "Press Delete again to confirm. The profile is moved to the recovery \
                     area, not destroyed.",
                );
            }
            Message::CancelDeleteProfile => self.delete_armed = None,

            Message::ConfirmDeleteProfile(id) => {
                self.delete_armed = None;
                match self.core.delete_profile(&id) {
                    Ok(receipt) => {
                        // The selection may have just been deleted.
                        if self.selected.as_ref() == Some(&id) {
                            self.selected = None;
                            self.installation = Installation::default();
                        }
                        self.reload_profiles();
                        if self.selected.is_none() {
                            self.selected = self.profiles.first().map(|p| p.id.clone());
                            self.sync_profile_inputs();
                        }
                        self.reload_profile_art();
                        self.succeed(receipt.message());
                    }
                    Err(e) => self.fail(format!("Could not delete: {e}")),
                }
            }

            Message::ValidateProfile(id) => {
                let Some(profile) = self.profiles.iter().find(|p| p.id == id).cloned() else {
                    return Task::none();
                };
                let report = self
                    .core
                    .validate_profile(&profile, self.installation.snapshot.as_ref());
                if report.is_healthy() {
                    self.succeed("Nothing wrong with this profile.");
                } else {
                    self.warn(format!(
                        "{}: {} thing(s) to look at.",
                        report.health.label(),
                        report.defects.len()
                    ));
                }
                self.profile_report = Some(report);
            }

            Message::RepairProfile(id) => {
                match self
                    .core
                    .repair_profile(&id, self.installation.snapshot.as_ref())
                {
                    Ok(summary) => {
                        self.reload_profiles();
                        self.sync_profile_inputs();
                        // Re-validate so the screen reflects what is left.
                        if let Some(profile) = self.profiles.iter().find(|p| p.id == id).cloned() {
                            self.profile_report =
                                Some(self.core.validate_profile(
                                    &profile,
                                    self.installation.snapshot.as_ref(),
                                ));
                        }
                        self.succeed(summary.message());
                    }
                    Err(e) => self.fail(format!("Could not repair: {e}")),
                }
            }

            Message::ExportProfile(id) => match self.core.export_profile(&id) {
                Ok(json) => {
                    let target = rfd::FileDialog::new()
                        .set_file_name(format!("{id}.fathom-profile.json"))
                        .add_filter("Fathom profile", &["json"])
                        .save_file();
                    match target {
                        Some(path) => match std::fs::write(&path, json) {
                            Ok(()) => self.succeed(format!(
                                "Exported configuration to {}. No worlds or credentials \
                                 are in it.",
                                path.display()
                            )),
                            Err(e) => self.fail(format!("Could not write the file: {e}")),
                        },
                        None => self.inform("Export cancelled."),
                    }
                }
                Err(e) => self.fail(format!("Could not export: {e}")),
            },

            Message::ImportProfile => {
                let Some(path) = rfd::FileDialog::new()
                    .add_filter("Fathom profile", &["json"])
                    .pick_file()
                else {
                    return Task::none();
                };
                match std::fs::read(&path) {
                    Ok(bytes) => match self.core.import_profile(&bytes) {
                        Ok((profile, outcome)) => {
                            self.reload_profiles();
                            let mut message = format!("Imported {}.", profile.display_name);
                            if outcome.renamed {
                                message.push_str(" The name was taken, so a new id was derived.");
                            }
                            for adjustment in &outcome.adjustments {
                                message.push(' ');
                                message.push_str(adjustment);
                            }
                            self.succeed(message);
                        }
                        Err(e) => self.fail(format!("Could not import: {e}")),
                    },
                    Err(e) => self.fail(format!("Could not read the file: {e}")),
                }
            }

            Message::HeapChanged(value) => {
                if value.chars().all(|c| c.is_ascii_digit()) && value.len() <= 6 {
                    self.heap_input = value;
                }
            }
            Message::ExtraArgsChanged(value) => self.extra_args_input = value,
            Message::LoaderChanged(loader) => {
                if let Some(profile) = self.selected_profile().cloned() {
                    let mut updated = profile;
                    updated.loader.kind = loader;
                    self.write_profile(updated);
                }
            }
            Message::KeepOpenChanged(value) => {
                if let Some(profile) = self.selected_profile().cloned() {
                    let mut updated = profile;
                    updated.keep_launcher_open = value;
                    self.write_profile(updated);
                }
            }
            Message::SaveProfile => return self.save_profile(),

            // -- performance ------------------------------------------------
            Message::PresetSelected(preset) => {
                if let Some(mut profile) = self.selected_profile().cloned() {
                    profile.optimizations.preset = preset;
                    // Choosing a named preset rewrites the switch map from it.
                    // Custom is the exception: it means "leave my switches
                    // alone", so selecting it keeps whatever is stored.
                    if preset != launcher_core::profiles::OptimizationPreset::Custom {
                        profile.optimizations.rendering = preset.switches();
                    }
                    self.write_profile(profile);
                }
            }
            Message::OptimizationToggled(id) => {
                if let Some(mut profile) = self.selected_profile().cloned() {
                    let now_on = !self.optimization_enabled(&id);
                    // A JVM flag is a real field, not a map entry, because it
                    // is read by the launch path rather than by the module.
                    match id.as_str() {
                        "jvm.disable-explicit-gc" => {
                            profile.optimizations.jvm.disable_explicit_gc = now_on;
                        }
                        "jvm.compile-huge-methods" => {
                            profile.optimizations.jvm.compile_huge_methods = now_on;
                        }
                        other => {
                            profile.optimizations.rendering.insert(other.to_owned(), now_on);
                        }
                    }
                    // Departing from a preset makes the selection Custom, or
                    // the page would claim a preset the switches no longer
                    // match.
                    profile.optimizations.preset =
                        launcher_core::profiles::OptimizationPreset::Custom;
                    self.write_profile(profile);
                }
            }

            // -- mods -------------------------------------------------------
            Message::ModSearchChanged(value) => self.mod_search = value,
            Message::ModFilterChanged(filter) => self.mod_filter = filter,
            Message::ModExpanded(sha1) => {
                self.expanded_mod = if self.expanded_mod.as_deref() == Some(sha1.as_str()) {
                    None
                } else {
                    Some(sha1)
                };
            }
            Message::EditProfile(id) => {
                if self.selected.as_ref() == Some(&id) {
                    self.editing_profile = !self.editing_profile;
                } else {
                    // Editing a profile you have not selected selects it first,
                    // so the editor is never showing one profile's settings
                    // while another is the one that would launch.
                    self.editing_profile = true;
                    return self.update(Message::SelectProfile(id));
                }
            }
            Message::PrivacyGroupExpanded(group) => {
                self.expanded_privacy_group = if self.expanded_privacy_group == Some(group) {
                    None
                } else {
                    Some(group)
                };
            }
            Message::PickModFile => return self.pick_mod_file(),
            Message::RemoveMod(id) => {
                let Some(mut profile) = self.selected_profile().cloned() else {
                    return Task::none();
                };
                match self.core.remove_mod(&mut profile, &id) {
                    Ok(Some(removed)) => {
                        // Forgotten by the profile, not deleted: the bytes stay
                        // in the shared store, and any other profile using them
                        // is unaffected.
                        self.succeed(format!(
                            "{} is no longer part of this profile. The file is still in \
                             Fathom's mod store.",
                            removed.file_name
                        ));
                        self.reload_profiles();
                    }
                    Ok(None) => {}
                    Err(e) => self.fail(format!("Could not remove the mod: {e}")),
                }
            }

            Message::ToggleMod(id, enabled) => {
                let Some(mut profile) = self.selected_profile().cloned() else {
                    return Task::none();
                };
                // A field assignment. Nothing on disk changes until the mods
                // directory is materialized, which happens at launch.
                match self.core.set_mod_enabled(&mut profile, &id, enabled) {
                    Ok(_) => self.reload_profiles(),
                    Err(e) => self.fail(format!("Could not change the mod: {e}")),
                }
            }

            Message::MaterializeMods => {
                let Some(mut profile) = self.selected_profile().cloned() else {
                    return Task::none();
                };
                match self.core.materialize_mods(&mut profile) {
                    Ok(outcome) => {
                        self.reload_profiles();
                        self.reload_mods();
                        let mut message = format!("Mods folder rebuilt: {}.", outcome.summary());
                        if !outcome.missing.is_empty() {
                            message.push_str(&format!(
                                " {} could not be placed: their files are not in the store.",
                                outcome.missing.join(", ")
                            ));
                        }
                        if !outcome.foreign.is_empty() {
                            message.push_str(&format!(
                                " Adopted {} file(s) you had added yourself.",
                                outcome.foreign.len()
                            ));
                        }
                        self.succeed(message);
                    }
                    Err(e) => self.fail(format!("Could not rebuild the mods folder: {e}")),
                }
            }
            Message::OpenModsFolder => {
                if let Some(id) = &self.selected {
                    let dir = self.core.profile_dirs(id).mods_dir();
                    let _ = std::fs::create_dir_all(&dir);
                    self.inform(format!("Mods folder: {}", dir.display()));
                }
            }

            // -- account ----------------------------------------------------
            Message::BeginSignIn => {
                self.sign_in = SignIn::Starting;
                let cancel = Cancel::new();
                self.sign_in_cancel = Some(cancel.clone());
                let tasks = self.core.tasks();
                self.submit(Job::SignIn { tasks, cancel }, "Signing in");
            }
            Message::BeginDevelopmentSignIn => return self.begin_development_sign_in(),
            Message::CancelSignIn => {
                if let Some(cancel) = self.sign_in_cancel.take() {
                    cancel.cancel();
                }
                self.sign_in = SignIn::Idle;
                self.busy = None;
                self.inform("Sign-in cancelled.");
            }
            Message::SignOut => match self.core.sign_out() {
                Ok(()) => {
                    self.sign_in = SignIn::Idle;
                    self.succeed("Signed out. The stored refresh token was deleted.");
                }
                Err(e) => self.fail(format!("Could not sign out cleanly: {e}")),
            },
            Message::CopyToClipboard(value) => {
                self.inform("Copied to the clipboard.");
                return iced::clipboard::write(value);
            }

            // -- settings ---------------------------------------------------
            Message::SettingsCategoryChanged(category) => {
                self.settings_category = category;
                // Choosing a category is an answer to the search, so the search
                // clears rather than leaving results and a selection fighting.
                self.settings_search.clear();
            }
            Message::SettingsSearchChanged(value) => self.settings_search = value,
            Message::OfflineModeChanged(value) => {
                self.mutate_settings(|s| s.privacy.offline_mode = value);
            }
            Message::UpdateCheckChanged(value) => {
                self.mutate_settings(|s| s.privacy.check_for_updates = value);
            }
            Message::ManagedJavaChanged(value) => {
                self.mutate_settings(|s| s.privacy.managed_java = value);
            }
            Message::ModRepositoriesChanged(value) => {
                self.mutate_settings(|s| s.privacy.mod_repositories = value);
            }
            Message::RefreshJava => {
                let tasks = self.core.tasks();
                self.submit(Job::ScanJava { tasks }, "Scanning for Java");
            }

            // -- home -------------------------------------------------------
            Message::PrepareVersion => {
                let Some(profile) = self.selected_profile().cloned() else {
                    return Task::none();
                };
                let Some(natives) = self.natives_dir() else {
                    return Task::none();
                };
                let tasks = self.core.tasks();
                self.submit(
                    Job::Prepare {
                        tasks,
                        version_id: profile.version_id,
                        natives_dir: natives,
                        cancel: Cancel::new(),
                    },
                    "Resolving game files",
                );
            }

            Message::InstallGameFiles | Message::RepairGameFiles => {
                let repairing = matches!(message, Message::RepairGameFiles);
                let Some(resolved) = self.installation.resolved.clone() else {
                    self.warn("Resolve the version first.");
                    return Task::none();
                };
                let Some(natives_dir) = self.natives_dir() else {
                    return Task::none();
                };

                let cancel = Cancel::new();
                self.install = Some(InstallState {
                    stage: InstallStage::Downloading(Progress {
                        completed: 0,
                        total: resolved.all_downloads().len(),
                        current: String::new(),
                        bytes_transferred: 0,
                    }),
                    cancel: cancel.clone(),
                    repairing,
                });

                let tasks = self.core.tasks();
                let resolved = Box::new(resolved);
                if repairing {
                    self.submit(
                        Job::Repair {
                            tasks,
                            resolved,
                            natives_dir,
                            cancel,
                        },
                        "Repairing game files",
                    );
                } else {
                    self.submit(
                        Job::Install {
                            tasks,
                            resolved,
                            natives_dir,
                            cancel,
                        },
                        "Downloading game files",
                    );
                }
            }

            Message::VerifyGameFiles => {
                let Some(resolved) = self.installation.resolved.clone() else {
                    self.warn("Resolve the version first.");
                    return Task::none();
                };
                let Some(natives_dir) = self.natives_dir() else {
                    return Task::none();
                };
                let tasks = self.core.tasks();
                self.submit(
                    Job::Verify {
                        tasks,
                        resolved: Box::new(resolved),
                        natives_dir,
                    },
                    "Verifying game files",
                );
            }

            Message::CancelInstall => {
                if let Some(state) = &self.install {
                    state.cancel.cancel();
                    self.inform("Stopping after the current file\u{2026}");
                }
            }
            Message::Play => return self.play(),

            Message::StopGame => {
                let Some(id) = self.selected.clone() else {
                    return Task::none();
                };
                match self.core.running().terminate(&id) {
                    Ok(Some(status)) => {
                        self.game = None;
                        self.inform(format!(
                            "Stopped Minecraft (pid {}) after {:.0}s.",
                            status.pid,
                            status.elapsed().as_secs_f64()
                        ));
                    }
                    Ok(None) => self.game = None,
                    Err(e) => self.fail(format!("Could not stop the game: {e}")),
                }
            }

            Message::Worker(event) => return self.on_worker_event(event),

            // -- privacy ----------------------------------------------------
            Message::RefreshJournal => self.journal = self.core.network_journal().recent(200),
            Message::ClearJournal => {
                self.core.network_journal().clear();
                self.journal.clear();
                self.succeed("Request log cleared. It was never written to disk.");
            }
        }
        Task::none()
    }

    fn persist_selected_profile(&mut self) {
        let mut settings = self.core.settings().clone();
        settings.selected_profile = self.selected.clone();
        if let Err(e) = self.core.update_settings(settings) {
            self.fail(format!("Could not save the selection: {e}"));
        }
    }

    fn mutate_settings(&mut self, edit: impl FnOnce(&mut Settings)) {
        let mut settings = self.core.settings().clone();
        edit(&mut settings);
        if let Err(e) = self.core.update_settings(settings) {
            self.fail(format!("Could not save settings: {e}"));
        }
    }

    fn write_profile(&mut self, profile: Profile) {
        match self.core.save_profile(&profile) {
            Ok(()) => {
                self.reload_profiles();
                self.sync_profile_inputs();
            }
            Err(e) => self.fail(format!("Could not save the profile: {e}")),
        }
    }

    fn save_profile(&mut self) -> Task<Message> {
        let Some(profile) = self.selected_profile().cloned() else {
            return Task::none();
        };
        let mut updated = profile;

        match self.heap_input.parse::<u32>() {
            Ok(heap) => {
                let verdict = launcher_core::minecraft_runtime::judge_heap(heap, None);
                use launcher_core::minecraft_runtime::HeapVerdict;
                match verdict {
                    HeapVerdict::TooSmall => {
                        self.warn("That heap is too small for 1.8.9 to start reliably.");
                        return Task::none();
                    }
                    HeapVerdict::ExceedsPhysical => {
                        self.warn(
                            "That heap is larger than this machine can back without swapping.",
                        );
                        return Task::none();
                    }
                    HeapVerdict::CounterProductive => {
                        self.warn(
                            "Saved, but be aware: past about 8 GB, a bigger heap makes pauses \
                             longer, not shorter. 1.8.9 rarely needs more than 2-3 GB.",
                        );
                    }
                    HeapVerdict::Ok => self.succeed("Profile saved."),
                }
                updated.memory.heap_mb = heap;
            }
            Err(_) => {
                self.warn("Heap size must be a number of megabytes.");
                return Task::none();
            }
        }

        let requested: Vec<String> = self
            .extra_args_input
            .split_whitespace()
            .map(str::to_owned)
            .collect();
        let report = launcher_core::minecraft_runtime::validate_args(&requested);
        if !report.is_clean() {
            // Refusals are explained, never silent.
            let explanation = report
                .rejected
                .iter()
                .map(|(flag, why)| format!("{flag}: {why}"))
                .collect::<Vec<_>>()
                .join("\n");
            self.fail(explanation);
            return Task::none();
        }
        updated.jvm.extra_args = report.accepted;

        self.write_profile(updated);
        Task::none()
    }

    fn pick_mod_file(&mut self) -> Task<Message> {
        if self.selected.is_none() {
            return Task::none();
        }
        let picked: Option<PathBuf> = rfd::FileDialog::new()
            .add_filter("Minecraft mod", &["jar"])
            .set_title("Choose a mod jar")
            .pick_file();

        if let Some(path) = picked {
            let Some(mut profile) = self.selected_profile().cloned() else {
                return Task::none();
            };
            match self.core.add_mod_from_file(&mut profile, &path) {
                Ok(entry) => {
                    self.succeed(format!(
                        "Added {} (sha1 {}\u{2026}). Your original file was copied, not moved.",
                        entry.file_name,
                        entry.sha1.chars().take(12).collect::<String>()
                    ));
                    self.reload_profiles();
                }
                Err(e) => self.fail(format!("Could not add that jar: {e}")),
            }
        }
        Task::none()
    }

    /// Everything the worker reports comes through here.
    fn on_worker_event(&mut self, event: worker::Event) -> Task<Message> {
        use worker::Event;

        match event {
            Event::Ready(sender) => {
                // Replay anything submitted before the worker connected.
                for job in self.pending.drain(..) {
                    let _ = sender.unbounded_send(job);
                }
                self.jobs = Some(sender);
                return self.on_worker_ready();
            }

            Event::JavaScanned(found) => {
                self.busy = None;
                self.java = found.clone();
                self.core.set_java(found);
                if self.java.is_empty() {
                    self.warn("No Java runtimes found on PATH or in JAVA_HOME.");
                }
            }

            Event::SignInCode(prompt) => {
                self.sign_in = SignIn::AwaitingUser(prompt);
                self.inform(
                    "Enter the code on the Microsoft page. This window will notice when you are \
                     done \u{2014} there is nothing else to press.",
                );
            }

            Event::SignInFinished(signed_in) => {
                self.busy = None;
                self.sign_in = SignIn::Idle;
                self.sign_in_cancel = None;
                let name = signed_in.session.identity.profile.name.clone();
                let ownership = signed_in.session.entitlement.ownership.explanation();
                match self.core.adopt_session(*signed_in) {
                    Ok(()) => self.succeed(format!("Signed in as {name}. {ownership}")),
                    Err(e) => self.fail(format!("Signed in, but could not save the session: {e}")),
                }
            }

            Event::SessionRestored(restored) => {
                self.busy = None;
                if let Some(signed_in) = restored {
                    let name = signed_in.session.identity.profile.name.clone();
                    match self.core.adopt_session(*signed_in) {
                        Ok(()) => self.inform(format!("Welcome back, {name}.")),
                        Err(e) => {
                            tracing::warn!(error = %e, "could not adopt the restored session");
                        }
                    }
                }
            }

            Event::Prepared {
                resolved,
                report,
                snapshot,
            } => {
                self.busy = None;
                let total = resolved.all_downloads().len();
                let bytes = resolved.declared_bytes();

                // Metadata came back verified, so the cached document is now
                // the one the resolver used. Reading it back is cheap and keeps
                // `version` and `installation` describing the same thing.
                self.version = self.core.cached_version(&resolved.version_id);

                let message = match snapshot.state {
                    InstallationState::Installed => format!(
                        "{} is complete: {total} file(s), {}.",
                        resolved.version_id,
                        launcher_core::services::human_bytes(bytes)
                    ),
                    InstallationState::Corrupted => format!(
                        "{} file(s) on disk fail their hash and will be replaced.",
                        snapshot.outstanding
                    ),
                    _ => format!(
                        "Resolved {}: {total} file(s), {}. {} still to download ({}).",
                        resolved.version_id,
                        launcher_core::services::human_bytes(bytes),
                        snapshot.outstanding,
                        launcher_core::services::human_bytes(snapshot.bytes)
                    ),
                };

                self.installation = Installation {
                    resolved: Some(*resolved),
                    report: Some(*report),
                    snapshot: Some(snapshot),
                    // An existence check, not a hash pass. Say so.
                    hashed: false,
                };

                if snapshot.state == InstallationState::Corrupted {
                    self.warn(message);
                } else {
                    self.succeed(message);
                }
            }

            Event::InstallProgress(stage) => {
                if let Some(state) = &mut self.install {
                    state.stage = stage;
                }
            }

            Event::InstallFinished {
                bytes,
                report,
                snapshot,
            } => {
                let repairing = self.install.as_ref().is_some_and(|s| s.repairing);
                self.busy = None;
                self.install = None;
                self.adopt_report(*report, snapshot);

                if snapshot.state.is_playable() {
                    self.succeed(format!(
                        "{} verified. {} transferred.",
                        if repairing {
                            "Repair complete"
                        } else {
                            "Game files"
                        },
                        launcher_core::services::human_bytes(bytes)
                    ));
                } else {
                    // The transfer returned Ok but the files do not verify.
                    // Say exactly that rather than reporting success.
                    self.warn(format!(
                        "{} file(s) still fail verification after downloading {}.",
                        snapshot.outstanding,
                        launcher_core::services::human_bytes(bytes)
                    ));
                }
            }

            Event::Verified { report, snapshot } => {
                self.busy = None;
                let verified = report.verified;
                let total = report.total;
                self.adopt_report(*report, snapshot);

                if snapshot.state.is_playable() {
                    self.succeed(format!("All {total} file(s) hashed and correct."));
                } else {
                    self.warn(format!(
                        "{verified} of {total} file(s) verified. {}",
                        snapshot.state.explanation()
                    ));
                }
            }

            Event::Repaired {
                outcome,
                report,
                snapshot,
            } => {
                self.busy = None;
                self.install = None;
                self.adopt_report(*report, snapshot);
                let summary = outcome.summary();
                if outcome.succeeded() {
                    self.succeed(format!(
                        "{summary} {} transferred.",
                        launcher_core::services::human_bytes(outcome.bytes)
                    ));
                } else {
                    self.warn(format!("Repair did not complete. {summary}"));
                }
            }

            Event::GameEnded(status) => self.report_game_ended(&status),

            Event::Cancelled { job } => {
                self.busy = None;
                self.install = None;
                if matches!(self.sign_in, SignIn::Starting | SignIn::AwaitingUser(_)) {
                    self.sign_in = SignIn::Idle;
                }
                self.inform(format!("Stopped the {job}."));
            }

            Event::Failed { job, message } => {
                self.busy = None;
                self.install = None;
                self.sign_in = SignIn::Idle;
                self.sign_in_cancel = None;
                self.fail(format!("{job} failed: {message}"));
            }
        }
        Task::none()
    }

    /// Sign in with the fake development identity.
    ///
    /// Runs inline rather than on the worker: it performs no I/O at all, it
    /// just fabricates a local struct.
    #[cfg(feature = "dev-auth")]
    fn begin_development_sign_in(&mut self) -> Task<Message> {
        match self.core.sign_in_development() {
            Ok(()) => self.warn(
                "Signed in with a FAKE development identity. It owns nothing and cannot join \
                 online-mode servers.",
            ),
            Err(e) => self.fail(format!("Could not use the development identity: {e}")),
        }
        Task::none()
    }

    /// Without the feature there is no development provider to sign in with, so
    /// this cannot happen. Kept so the message arm compiles in every build.
    #[cfg(not(feature = "dev-auth"))]
    fn begin_development_sign_in(&mut self) -> Task<Message> {
        self.fail("This build has no development authentication.");
        Task::none()
    }

    /// Kick off the work that should happen at startup, now that there is
    /// somewhere to run it.
    ///
    /// Both of these used to happen inline and made the window take a visible
    /// moment to appear: `java -version` spawns a process per candidate, and a
    /// session restore is three HTTP round trips.
    fn on_worker_ready(&mut self) -> Task<Message> {
        let tasks = self.core.tasks();
        self.submit(Job::ScanJava { tasks }, "Scanning for Java");

        // Opting into development auth is already a deliberate act: a
        // non-default build feature plus an awkward environment variable.
        // Making the user click through the Account screen on top of that is
        // friction for no safety gain, and adopting it here means the warning
        // banner is on screen from the first frame rather than after a click.
        if self.core.development_auth_available() && self.core.identity().is_none() {
            return self.begin_development_sign_in();
        }

        if let Some(account_id) = self.core.remembered_account_id().map(str::to_owned) {
            let tasks = self.core.tasks();
            self.submit(
                Job::RestoreSession { tasks, account_id },
                "Restoring your session",
            );
        }
        Task::none()
    }

    /// Say how the game ended.
    ///
    /// This is the whole point of tracking the process: a launcher that says
    /// "Minecraft started" and then never mentions it again leaves the user to
    /// work out for themselves why the window vanished.
    fn report_game_ended(&mut self, status: &GameStatus) {
        self.game = None;

        let ran_for = format!("{:.0}s", status.elapsed().as_secs_f64());
        if status.outcome.is_clean_exit() {
            self.succeed(format!("Minecraft exited normally after {ran_for}."));
        } else {
            // The outcome already names what went wrong and what to do about
            // it; putting a vaguer sentence in front would only bury it.
            let mut message = status.outcome.message();
            if status.outcome.suggests_repair() {
                message.push_str(
                    " Repairing the installation on this screen re-fetches what is wrong.",
                );
            }
            message.push_str(&format!(" The log is at {}.", status.log_file.display()));
            self.fail(message);
        }

        // A crash can leave a half-written natives directory, so what we
        // believe about the installation is no longer something we measured.
        self.installation.hashed = false;
    }

    fn play(&mut self) -> Task<Message> {
        let (Some(profile), Some(version)) =
            (self.selected_profile().cloned(), self.version.clone())
        else {
            self.warn("Fetch the version metadata first.");
            return Task::none();
        };
        match self.core.launch(&profile, &version) {
            Ok(status) => {
                self.succeed(format!(
                    "Minecraft started (pid {}). Output is being written to {}.",
                    status.pid,
                    status.log_file.display()
                ));
                // What the Home screen renders the running card from, and
                // the handle the watcher job reports back on.
                let profile_id = status.profile.clone();
                self.game = Some(status);
                self.submit_quietly(Job::WatchGame {
                    games: self.core.running().clone(),
                    profile: profile_id,
                });
            }
            // The refusal already names what to do about it, so it is shown
            // verbatim rather than wrapped in a vaguer sentence.
            Err(refusal) => self.fail(refusal.to_string()),
        }
        Task::none()
    }

    // -- view --------------------------------------------------------------

    /// The whole window, in layers.
    ///
    /// Bottom to top: the static midnight surface, the background being
    /// replaced, the background arriving, the scrim, the interface, and — only
    /// while it is open — the background switcher. The first layer is why no
    /// combination of states can produce a black window; there is always
    /// something already painted underneath.
    pub fn view(&self) -> Element<'_, Message> {
        let mut layers: Vec<Element<'_, Message>> = vec![fill(style::static_midnight)];

        if let Some((handle, opacity)) = self.background.outgoing() {
            layers.push(footage(handle, opacity));
        }
        if let Some((handle, opacity)) = self.background.current() {
            layers.push(footage(handle, opacity));
        }
        layers.push(fill(style::scrim));
        layers.push(self.shell());

        if self.background_menu {
            layers.push(self.background_switcher());
        }

        Stack::with_children(layers)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    /// Top bar, then rail beside content, then bottom bar.
    ///
    /// The bars span the full width and the rail sits between them, which is
    /// what makes the window read as one anchored application rather than a
    /// page of floating panels.
    fn shell(&self) -> Element<'_, Message> {
        column![
            self.top_bar(),
            rule(),
            row![self.rail(), self.content()]
                .width(Length::Fill)
                .height(Length::Fill),
            rule(),
            self.bottom_bar(),
        ]
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    // -- left rail ---------------------------------------------------------

    /// Icon-first navigation.
    ///
    /// Deliberately narrow enough that it cannot become a menu: there is room
    /// for a glyph and nothing else, so every destination is a tooltip away
    /// rather than a paragraph wide.
    fn rail(&self) -> Element<'_, Message> {
        let mut primary = column![].spacing(style::space::XXS);
        for screen in Screen::PRIMARY {
            primary = primary.push(nav_item(screen, self.screen == screen));
        }

        let mut utility = column![].spacing(style::space::XXS);
        for screen in Screen::UTILITY {
            utility = utility.push(nav_item(screen, self.screen == screen));
        }

        container(
            column![
                primary,
                Space::with_height(Length::Fill),
                utility,
            ]
            .padding([style::space::SM, 0])
            .align_x(Alignment::Center)
            .width(Length::Fill),
        )
        .width(Length::Fixed(style::RAIL_WIDTH))
        .height(Length::Fill)
        .style(style::rail)
        .into()
    }

    // -- top bar -----------------------------------------------------------

    /// Identity, what is selected, and who is signed in.
    ///
    /// The window keeps its native title bar, so this sits below the real one
    /// and holds no window controls of its own. Drawing fake ones would mean
    /// taking over dragging, snapping, and the maximise animation, and every
    /// one of those is better done by Windows than by us.
    fn top_bar(&self) -> Element<'_, Message> {
        let wordmark = row![
            container(
                text("F")
                    .size(style::type_size::CARD_TITLE)
                    .font(style::FONT_DISPLAY)
            )
            .center(Length::Fixed(24.0))
            .style(style::brand_mark),
            // Carved rather than printed. Small type, so one pixel of edge is
            // all it takes — two would close up the counters at this size.
            extruded(
                "FATHOM",
                style::type_size::CARD_TITLE,
                style::TEXT,
                style::VOID,
                1,
            ),
        ]
        .spacing(style::space::SM)
        .align_y(Alignment::Center);

        let mut left = row![wordmark]
            .spacing(style::space::MD)
            .align_y(Alignment::Center);
        if !self.compact_layout() {
            left = left.push(vertical_rule(16.0));
            left = left.push(
                text(self.screen.title())
                    .size(style::type_size::SUPPORTING)
                    .font(style::FONT_MEDIUM)
                    .color(style::TEXT_MUTED),
            );
        }

        container(
            // Explicit Fill width. A `Row` defaults to `Length::Shrink`, which
            // leaves "one Fill child plus two Shrink siblings" resolving
            // against a limit rather than a decided width. It lays out
            // correctly either way — measured at 1350 and 1844 wide, both with
            // the expected right margin — but the bar's whole job is to pin
            // content to two edges, so the width it pins to is worth stating.
            row![
                left.width(Length::Fill),
                self.profile_chip(),
                self.account_chip(),
            ]
            .width(Length::Fill)
            .spacing(style::space::SM)
            .align_y(Alignment::Center),
        )
        .height(Length::Fixed(style::TOP_BAR_HEIGHT))
        .padding([0, style::space::MD])
        .width(Length::Fill)
        .align_y(iced::alignment::Vertical::Center)
        .style(style::top_bar)
        .into()
    }

    fn account_chip(&self) -> Element<'_, Message> {
        let (name, tone) = match self.core.identity() {
            Some(identity) => {
                let tone = if identity.kind.is_production() {
                    style::GOOD
                } else {
                    style::WARN
                };
                (identity.profile.name.as_str(), tone)
            }
            None => ("Sign in", style::TEXT_FAINT),
        };

        let initial = name
            .chars()
            .next()
            .unwrap_or('?')
            .to_uppercase()
            .to_string();

        button(
            row![
                container(
                    text(initial)
                        .size(style::type_size::CAPTION)
                        .font(style::FONT_SEMIBOLD)
                )
                .center(Length::Fixed(20.0))
                .style(style::identity_mark(tone)),
                text(name)
                    .size(style::type_size::SUPPORTING)
                    .font(style::FONT_MEDIUM),
            ]
            .spacing(style::space::XS)
            .align_y(Alignment::Center),
        )
        .padding(style::COMPACT_CONTROL_PADDING)
        .style(style::bar_button(self.screen == Screen::Account))
        .on_press(Message::Navigate(Screen::Account))
        .into()
    }

    fn profile_chip(&self) -> Element<'_, Message> {
        let Some(profile) = self.selected_profile() else {
            return button(text("Choose profile").size(style::type_size::SUPPORTING))
                .padding(style::COMPACT_CONTROL_PADDING)
                .style(style::bar_button(false))
                .on_press(Message::Navigate(Screen::Profiles))
                .into();
        };
        let tone = if self.readiness().can_play() {
            style::GOOD
        } else {
            style::WARN
        };

        let mut label = row![
            text("\u{25CF}").size(style::type_size::MICRO).color(tone),
            text(&profile.display_name)
                .size(style::type_size::SUPPORTING)
                .font(style::FONT_MEDIUM),
        ]
        .spacing(style::space::XS)
        .align_y(Alignment::Center);
        if !profile_name_adds_nothing(profile) {
            label = label.push(
                text(format!("\u{00B7} {}", profile.version_id))
                    .size(style::type_size::SUPPORTING)
                    .color(style::TEXT_FAINT),
            );
        }

        button(label)
        .padding(style::COMPACT_CONTROL_PADDING)
        .style(style::bar_button(self.screen == Screen::Profiles))
        .on_press(Message::Navigate(Screen::Profiles))
        .into()
    }

    // -- bottom bar --------------------------------------------------------

    /// Launcher state and the controls worth reaching without navigating.
    ///
    /// The background switcher lives here on purpose: changing the wallpaper
    /// is a thing people do idly and often, and making them open Settings for
    /// it every time is the kind of small friction that makes an application
    /// feel unfinished. Settings keeps the full control as well.
    fn bottom_bar(&self) -> Element<'_, Message> {
        let offline = self.core.settings().privacy.offline_mode;
        let (state_tone, state_label) = if offline {
            (style::WARN, "Offline mode")
        } else {
            (style::TEXT_FAINT, "Online")
        };

        let left = row![
            text(format!("Fathom {}", launcher_core::PRODUCT_VERSION))
                .size(style::type_size::CAPTION)
                .color(style::TEXT_FAINT),
            text("\u{25CF}").size(8).color(state_tone),
            text(state_label)
                .size(style::type_size::CAPTION)
                .color(style::TEXT_MUTED),
        ]
        .spacing(style::space::XS)
        .align_y(Alignment::Center);

        let centre: Element<'_, Message> = if self.compact_layout() {
            Space::with_width(Length::Fill).into()
        } else {
            container(
                text(self.status_line())
                    .size(style::type_size::CAPTION)
                    .color(style::TEXT_MUTED),
            )
            .center_x(Length::Fill)
            .into()
        };

        let right = row![
            button(
                row![
                    icon_inherit(Icon::Backgrounds, crate::icons::size::SMALL),
                    text(self.background.selected().label()).size(style::text::CAPTION),
                ]
                .spacing(style::space::XS)
                .align_y(Alignment::Center)
            )
            .padding([3, style::space::SM])
            .style(style::bar_button(self.background_menu))
            .on_press(Message::ToggleBackgroundMenu),
            button(icon_inherit(Icon::Settings, crate::icons::size::SMALL))
                .padding([3, style::space::SM])
                .style(style::bar_button(self.screen == Screen::Settings))
                .on_press(Message::Navigate(Screen::Settings)),
        ]
        .spacing(style::space::XXS)
        .align_y(Alignment::Center);

        container(
            row![
                container(left).width(Length::Fill),
                centre,
                container(right).width(Length::Fill).align_x(Alignment::End),
            ]
            .width(Length::Fill)
            .spacing(style::space::SM)
            .align_y(Alignment::Center),
        )
        .height(Length::Fixed(style::BOTTOM_BAR_HEIGHT))
        // Same horizontal inset as the top bar, so the two bars' content lines
        // up with each other down both edges of the window.
        .padding([0, style::space::MD])
        .width(Length::Fill)
        .align_y(iced::alignment::Vertical::Center)
        .style(style::bottom_bar)
        .into()
    }

    /// One line describing what the launcher is doing, for the bottom bar.
    fn status_line(&self) -> String {
        if let Some(game) = self.game() {
            return format!("Minecraft running \u{00B7} process {}", game.pid);
        }
        if let Some(install) = self.install() {
            let transferred = install.bytes_transferred();
            return if transferred == 0 {
                install.label()
            } else {
                format!(
                    "{} \u{00B7} {} transferred",
                    install.label(),
                    launcher_core::services::human_bytes(transferred)
                )
            };
        }
        if let Some(job) = self.busy() {
            return format!("{job}\u{2026}");
        }
        match self.selected_profile() {
            Some(profile) => {
                let mods = profile.mods.enabled().count();
                let name = if profile_name_adds_nothing(profile) {
                    String::new()
                } else {
                    format!("{} \u{00B7} ", profile.display_name)
                };
                format!(
                    "{name}Minecraft {} \u{00B7} {} \u{00B7} {} {}",
                    profile.version_id,
                    profile.loader_kind().label(),
                    mods,
                    if mods == 1 { "mod" } else { "mods" },
                )
            }
            None => "No profile selected".to_owned(),
        }
    }

    /// The switcher raised from the bottom bar.
    ///
    /// A full-window layer: a transparent catcher that closes the menu on any
    /// click outside it, and the panel itself pushed to the bottom right with
    /// spacers. iced has no popover primitive, and this composes one out of
    /// the layout it does have without a second window.
    fn background_switcher(&self) -> Element<'_, Message> {
        let mut options = column![].spacing(style::space::XXS);
        for choice in BackgroundChoice::ALL {
            options = options.push(self.background_option(choice));
        }

        let panel = container(
            column![
                text("BACKGROUND")
                    .size(style::type_size::MICRO)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT_FAINT),
                options,
            ]
            .spacing(style::space::SM),
        )
        .padding(style::space::SM)
        .width(Length::Fixed(228.0))
        .style(style::popover);

        let anchored = column![
            Space::with_height(Length::Fill),
            row![
                Space::with_width(Length::Fill),
                panel,
                Space::with_width(Length::Fixed(f32::from(style::space::SM))),
            ],
            Space::with_height(Length::Fixed(style::BOTTOM_BAR_HEIGHT + 6.0)),
        ];

        Stack::with_children(vec![
            mouse_area(Space::new(Length::Fill, Length::Fill))
                .on_press(Message::CloseBackgroundMenu)
                .into(),
            anchored.into(),
        ])
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    fn background_option(&self, choice: BackgroundChoice) -> Element<'_, Message> {
        let selected = self.background.selected() == choice;
        let installed = self.background.is_installed(choice);

        let preview: Element<'_, Message> = match self.background.thumbnail(choice) {
            Some(handle) => image(handle.clone())
                .width(Length::Fixed(58.0))
                .height(Length::Fixed(33.0))
                .content_fit(ContentFit::Cover)
                .into(),
            // The static choice has no footage to preview, so it shows the
            // surface it actually produces.
            None => container(Space::new(Length::Fixed(58.0), Length::Fixed(33.0)))
                .style(style::static_midnight)
                .into(),
        };

        let label = column![
            text(choice.label())
                .size(style::type_size::SUPPORTING)
                .font(style::FONT_MEDIUM),
            text(if !installed {
                "Not installed"
            } else if choice.is_animated() {
                "1280\u{00D7}720 \u{00B7} 30 fps"
            } else {
                "No animation"
            })
            .size(style::type_size::MICRO)
            .color(style::TEXT_FAINT),
        ]
        .spacing(1);

        // A dot rather than a tick: Segoe UI has no U+2713, and a missing glyph
        // renders as a hollow box, which reads as a rendering fault rather than
        // as the current selection.
        let marker: Element<'_, Message> = if selected {
            text("\u{25CF}")
                .size(style::type_size::MICRO)
                .color(style::ACCENT_STRONG)
                .into()
        } else {
            Space::with_width(Length::Fixed(0.0)).into()
        };

        let mut control = button(
            row![preview, label.width(Length::Fill), marker]
                .width(Length::Fill)
                .spacing(style::space::SM)
                .align_y(Alignment::Center),
        )
        .padding(style::space::XS)
        .width(Length::Fill)
        .style(style::background_option(selected));

        // The current background stays pressable. Choosing it again is a no-op
        // in the player, and leaving the control enabled is what keeps it from
        // being drawn in the disabled palette — the selected row looking greyed
        // out is precisely backwards.
        if installed {
            control = control.on_press(Message::BackgroundSelected(choice));
        }
        control.into()
    }

    // -- content -----------------------------------------------------------

    fn content(&self) -> Element<'_, Message> {
        let mut notices = column![].spacing(style::space::SM);
        // Above everything, on every screen: a fake identity must never be
        // something the user can forget about by changing tabs.
        if let Some(warning) = self.core.identity_warning() {
            notices = notices.push(self.development_banner(warning));
        }
        if let Some(notice) = &self.notice {
            notices = notices.push(self.notice_bar(notice));
        }
        let has_notices = self.core.identity_warning().is_some() || self.notice.is_some();

        // Home is a canvas: it fills whatever is left and centres the launch
        // control in it. Every other screen is a document, and scrolls.
        if self.screen == Screen::Home {
            let mut canvas = column![].width(Length::Fill).height(Length::Fill);
            if has_notices {
                canvas = canvas.push(
                    container(notices)
                        .padding([style::space::MD, style::space::LG])
                        .width(Length::Fill),
                );
            }
            canvas = canvas.push(screens::home::view(self));
            return container(canvas)
                .width(Length::Fill)
                .height(Length::Fill)
                .into();
        }

        let page_padding = if self.compact_layout() {
            style::space::MD
        } else {
            style::space::XL
        };

        let mut stacked = column![]
            .spacing(style::space::MD)
            .padding(page_padding)
            .width(Length::Fill);
        if has_notices {
            stacked = stacked.push(notices);
        }
        stacked = stacked.push(
            column![
                text(self.screen.title())
                    .size(style::type_size::PROFILE_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text(self.screen.subtitle())
                    .size(style::type_size::SUPPORTING)
                    .color(style::TEXT_MUTED),
            ]
            .spacing(2),
        );

        let screen: Element<'_, Message> = match self.screen {
            // Handled by the early return above. Rendering it rather than
            // panicking, because a `view` that can panic is a crash waiting for
            // whoever edits the branch above.
            Screen::Home => screens::home::view(self),
            Screen::Profiles => screens::profiles::view(self),
            Screen::Mods => screens::mods::view(self),
            Screen::Performance => screens::performance::view(self),
            Screen::Recovery => screens::recovery::view(self),
            Screen::Account => screens::account::view(self),
            Screen::Settings => screens::settings::view(self),
            Screen::Privacy => screens::privacy::view(self),
        };
        stacked = stacked.push(screen);

        let page = container(stacked).width(Length::Fill).max_width(1180.0);

        // The page-change transition: the incoming content starts a few pixels
        // low and rises into place. Motion, not decoration -- it tells you the
        // region that changed, which on a shell where only the centre swaps is
        // otherwise easy to miss.
        let lift = (1.0 - self.page_motion.value()) * 10.0;

        container(
            iced::widget::scrollable(
                container(page)
                    .width(Length::Fill)
                    .center_x(Length::Fill)
                    .padding(iced::Padding {
                        top: lift,
                        right: 0.0,
                        bottom: 0.0,
                        left: 0.0,
                    }),
            )
            .style(style::scrollable),
        )
        .width(Length::Fill)
        .height(Length::Fill)
        .style(style::content_wash)
        .into()
    }

    /// Unmissable, permanent while a development identity is in use.
    ///
    /// Deliberately not dismissible: the whole point is that nobody mistakes a
    /// development build for a signed-in launcher.
    fn development_banner<'a>(&'a self, warning: &'a str) -> Element<'a, Message> {
        let body = column![
            row![
                container(text("DEVELOPMENT BUILD").size(11))
                    .padding([style::space::XXS, style::space::SM])
                    .style(style::pill(style::BAD)),
                text("You are not signed in to a real Minecraft account")
                    .size(style::type_size::BODY)
                    .font(style::FONT_MEDIUM)
                    .color(style::TEXT)
                    .width(Length::Fill),
            ]
            .spacing(style::space::MD)
            .align_y(Alignment::Center),
            text(warning)
                .size(style::type_size::SUPPORTING)
                .color(style::TEXT_MUTED),
        ]
        .spacing(style::space::XS);

        container(body)
            .padding(style::space::MD)
            .width(Length::Fill)
            .style(style::notice(style::BAD))
            .into()
    }

    fn notice_bar<'a>(&'a self, notice: &'a Notice) -> Element<'a, Message> {
        let body = row![
            text(&notice.message)
                .size(style::type_size::BODY)
                .width(Length::Fill),
            button(text("Dismiss").size(style::type_size::SUPPORTING))
                .padding(style::COMPACT_CONTROL_PADDING)
                .style(style::ghost_button)
                .on_press(Message::DismissNotice),
        ]
        .spacing(style::space::MD)
        .align_y(Alignment::Center);

        container(body)
            .padding(style::space::MD)
            .width(Length::Fill)
            .style(style::notice(notice.tone.color()))
            .into()
    }
}

/// Whether a profile's name is only ever going to repeat its version.
///
/// The first profile the launcher creates is called "Minecraft 1.8.9", so a
/// chip reading "Minecraft 1.8.9 · 1.8.9" above a heading reading
/// "MINECRAFT 1.8.9" is the same fact three times. Callers that would show
/// both drop the name in that case; a profile the user has actually named
/// keeps it.
pub fn profile_name_adds_nothing(profile: &Profile) -> bool {
    profile
        .display_name
        .trim()
        .eq_ignore_ascii_case(&format!("Minecraft {}", profile.version_id))
}

/// A full-window layer painted with one container style.
fn fill<'a>(
    style: fn(&Theme) -> container::Style,
) -> Element<'a, Message> {
    container(Space::new(Length::Fill, Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(style)
        .into()
}

/// One background frame, cropped to the window rather than stretched.
///
/// `ContentFit::Cover` is the whole reason the footage keeps its aspect ratio
/// at every window size: it scales to the larger axis and lets the other one
/// overflow, so a 16:9 frame in a 4:3 window loses its edges instead of
/// becoming squashed.
fn footage<'a>(handle: &Handle, opacity: f32) -> Element<'a, Message> {
    image(handle.clone())
        .width(Length::Fill)
        .height(Length::Fill)
        .content_fit(ContentFit::Cover)
        .opacity(opacity)
        .into()
}

/// A horizontal hairline between structural regions.
fn rule<'a>() -> Element<'a, Message> {
    container(Space::new(Length::Fill, Length::Fixed(1.0)))
        .width(Length::Fill)
        .height(Length::Fixed(1.0))
        .style(style::hairline)
        .into()
}

fn vertical_rule<'a>(height: f32) -> Element<'a, Message> {
    container(Space::new(Length::Fixed(1.0), Length::Fixed(height)))
        .width(Length::Fixed(1.0))
        .height(Length::Fixed(height))
        .style(style::hairline)
        .into()
}

fn window_event(
    event: iced::Event,
    _status: iced::event::Status,
    _window: iced::window::Id,
) -> Option<Message> {
    match event {
        // A zero size is how Windows reports a minimise, and it is what stops
        // background decoding. Logged because that is not obvious from the
        // event name, and because "did the launcher notice it was minimised"
        // is the first question when playback does not pause.
        iced::Event::Window(iced::window::Event::Resized(size)) => {
            tracing::debug!(width = size.width, height = size.height, "window resized");
            Some(Message::WindowResized(size))
        }
        iced::Event::Window(iced::window::Event::Focused) => {
            Some(Message::WindowFocusChanged(true))
        }
        iced::Event::Window(iced::window::Event::Unfocused) => {
            Some(Message::WindowFocusChanged(false))
        }
        _ => None,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn downloading(completed: usize, total: usize) -> InstallState {
        InstallState {
            stage: InstallStage::Downloading(Progress {
                completed,
                total,
                current: "guava-17.0.jar".into(),
                bytes_transferred: 1024,
            }),
            cancel: Cancel::new(),
            repairing: false,
        }
    }

    fn at(stage: InstallStage) -> InstallState {
        InstallState {
            stage,
            cancel: Cancel::new(),
            repairing: false,
        }
    }

    /// The property the progress bar depends on: a fraction is only offered
    /// when one can honestly be computed. Inventing 100% for "extracting
    /// natives" would leave the bar full while work is still happening.
    #[test]
    fn only_the_download_stage_reports_a_fraction() {
        assert_eq!(downloading(0, 4).fraction(), Some(0.0));
        assert_eq!(downloading(1, 4).fraction(), Some(0.25));
        assert_eq!(downloading(4, 4).fraction(), Some(1.0));

        for stage in [
            InstallStage::ResolvingMetadata,
            InstallStage::FetchingAssetIndex,
            InstallStage::ExtractingNatives,
            InstallStage::Verifying,
            InstallStage::Done,
        ] {
            assert_eq!(
                at(stage.clone()).fraction(),
                None,
                "{stage:?} has no honest fraction to report"
            );
        }
    }

    /// A zero total must not divide by zero or produce NaN.
    #[test]
    fn an_empty_download_reports_zero_rather_than_nan() {
        let fraction = downloading(0, 0).fraction().expect("still a download");
        assert!(fraction.is_finite());
        assert_eq!(fraction, 0.0);
    }

    /// Every stage says something specific. A stage that fell through to an
    /// empty string would leave the card blank mid-install.
    #[test]
    fn every_stage_has_a_label() {
        for stage in [
            InstallStage::ResolvingMetadata,
            InstallStage::FetchingAssetIndex,
            InstallStage::ExtractingNatives,
            InstallStage::Verifying,
            InstallStage::Done,
        ] {
            assert!(!at(stage.clone()).label().is_empty(), "{stage:?}");
        }
        assert!(downloading(3, 9).label().contains("3 of 9"));
        assert!(downloading(3, 9).label().contains("guava-17.0.jar"));
    }

    /// Bytes are only known during the transfer; other stages must not report
    /// a stale figure from a previous one.
    #[test]
    fn bytes_are_only_reported_while_transferring() {
        assert_eq!(downloading(1, 2).bytes_transferred(), 1024);
        assert_eq!(at(InstallStage::ExtractingNatives).bytes_transferred(), 0);
    }

    /// Until something has actually been checked, the interface must not claim
    /// to know the state.
    #[test]
    fn a_fresh_installation_knows_nothing() {
        let installation = Installation::default();
        assert_eq!(installation.state(), None);
        assert!(installation.resolved.is_none());
        assert!(!installation.hashed);
    }
}
