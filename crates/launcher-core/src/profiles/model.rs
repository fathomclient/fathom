//! What a profile *is*.
//!
//! A profile is not a bag of UI settings. It is the unit of isolation: one way
//! of playing, owning its own game directory, mods, worlds and configuration,
//! sharing nothing with its neighbours except the content-addressed artifact
//! cache, which is immutable.
//!
//! Two rules shape everything here:
//!
//! 1. **No secrets.** A profile is exported, imported, copied between machines
//!    and pasted into bug reports. Nothing in this file may hold a token, a
//!    password, or a username. Identity lives in the credential store; see
//!    `PRIVACY.md`.
//! 2. **Forward room.** The loader model carries Forge and Legacy Fabric today
//!    even though only vanilla launches, so adding them later is a change to
//!    behaviour rather than to the schema. A stored profile written by a build
//!    that supports Forge must round-trip through one that does not.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::SystemTime;

use serde::{Deserialize, Serialize};

use super::ProfileId;

// ---------------------------------------------------------------------------
// What kind of setup this is
// ---------------------------------------------------------------------------

/// The three ways to set a profile up.
///
/// # Why this is a stored field and not a guess
///
/// "Is this a Fathom profile" is asked by the component manager on every
/// launch, and it must not be answered by inspecting the mod list. A user who
/// removes one component from a Fathom profile still has a Fathom profile with
/// a component missing -- which is a thing to put back, not a reason to
/// silently downgrade them to Custom. Intent and contents are different facts,
/// so they are stored separately.
///
/// The default is [`Self::Custom`] rather than [`Self::Fathom`], because every
/// profile written before this field existed was assembled by hand and it would
/// be wrong to start installing components into it on the next launch.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ProfileKind {
    /// Unmodified Minecraft. No loader, no components, no mods.
    Vanilla,
    /// The Fathom experience: a supported loader with Fathom's own game-side
    /// components kept installed and up to date by the launcher.
    Fathom,
    /// The user picks the loader and the components themselves.
    #[default]
    Custom,
}

impl ProfileKind {
    pub const ALL: [ProfileKind; 3] = [
        ProfileKind::Vanilla,
        ProfileKind::Fathom,
        ProfileKind::Custom,
    ];

    /// The stable id used by settings files and the command bridge.
    pub fn id(self) -> &'static str {
        match self {
            ProfileKind::Vanilla => "vanilla",
            ProfileKind::Fathom => "fathom",
            ProfileKind::Custom => "custom",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|kind| kind.id() == id)
    }

    pub fn label(self) -> &'static str {
        match self {
            ProfileKind::Vanilla => "Vanilla",
            ProfileKind::Fathom => "Fathom",
            ProfileKind::Custom => "Custom",
        }
    }

    /// One sentence, for the setup screen.
    pub fn summary(self) -> &'static str {
        match self {
            ProfileKind::Vanilla => "Minecraft as it ships. No mods.",
            ProfileKind::Fathom => "Fathom's loader, modules and performance stack.",
            ProfileKind::Custom => "Choose the loader and components yourself.",
        }
    }

    /// Whether the launcher keeps first-party components installed for this
    /// profile without being asked.
    ///
    /// Only [`Self::Fathom`]. A Custom profile may well contain every one of
    /// them, but they got there because somebody chose them, and the launcher
    /// putting one back after it was removed would be overruling that choice.
    pub fn owns_components(self) -> bool {
        matches!(self, ProfileKind::Fathom)
    }

    /// The loader this kind implies, if it implies one.
    ///
    /// `None` for Custom, which is the whole point of Custom.
    pub fn implied_loader(self) -> Option<Loader> {
        match self {
            ProfileKind::Vanilla => Some(Loader::Vanilla),
            // 1.8.9's supported loader. Legacy Fabric rather than Forge because
            // its whole stack is permissively licensed, it is actively
            // maintained, and it is the one this launcher can actually resolve
            // and start today.
            ProfileKind::Fathom => Some(Loader::LegacyFabric),
            ProfileKind::Custom => None,
        }
    }
}

// ---------------------------------------------------------------------------
// Loader
// ---------------------------------------------------------------------------

/// Which loader a profile launches with.
///
/// `architecture-options.md` §4.2: Legacy Fabric is the intended default
/// because its whole stack is permissively licensed and actively maintained;
/// Forge exists as an opt-in compatibility mode for users who need `OptiFine` or
/// `PolyPatcher`.
///
/// All three exist in the model now. Only vanilla is *implemented*, and only
/// implemented loaders are offered to users — but a profile file naming Forge
/// parses, stores and round-trips today, so adding Forge is a matter of
/// implementing it rather than migrating everyone's profiles.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum Loader {
    /// Unmodified Minecraft. The current default because it is the only path
    /// that is wired up.
    #[default]
    Vanilla,
    /// Legacy Fabric loader. The intended default once implemented.
    LegacyFabric,
    /// Forge 1.8.9. Opt-in compatibility profile.
    Forge,
}

impl Loader {
    pub const ALL: [Loader; 3] = [Loader::Vanilla, Loader::LegacyFabric, Loader::Forge];

    pub fn label(self) -> &'static str {
        match self {
            Loader::Vanilla => "Vanilla",
            Loader::LegacyFabric => "Legacy Fabric",
            Loader::Forge => "Forge (compatibility)",
        }
    }

    /// Whether launching with this loader actually works.
    /// Whether launching with this loader actually works in this build.
    ///
    /// Legacy Fabric joined Vanilla once `services::launch` learned to resolve a
    /// loader, fetch its libraries through the same verified download path the
    /// game uses, prepend them to the classpath and take its main class. Before
    /// that the provider could resolve a loader perfectly well and the launch
    /// pipeline ignored the result, so saying "implemented" would have been a
    /// claim about code that was never called.
    ///
    /// Forge is still false, and honestly so: `ForgeProvider` refuses.
    pub fn is_implemented(self) -> bool {
        matches!(self, Loader::Vanilla | Loader::LegacyFabric)
    }

    /// Whether a loader needs a version of its own alongside the Minecraft
    /// version. Vanilla does not; the other two do.
    pub fn needs_version(self) -> bool {
        !matches!(self, Loader::Vanilla)
    }

    /// Whether this loader reads a `mods/` directory.
    pub fn supports_mods(self) -> bool {
        !matches!(self, Loader::Vanilla)
    }
}

/// A loader together with its own version.
///
/// Separate from [`Loader`] because "Forge" is not enough to launch with —
/// `11.15.1.2318` is. Vanilla carries no version, and a version recorded
/// against vanilla is a contradiction that [`super::validate`] reports.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct LoaderConfig {
    #[serde(default)]
    pub kind: Loader,
    /// The loader's own version, e.g. Forge's `11.15.1.2318`. `None` for
    /// vanilla, and for a loader whose version has not been chosen yet.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

impl LoaderConfig {
    pub fn vanilla() -> Self {
        Self {
            kind: Loader::Vanilla,
            version: None,
        }
    }

    /// Whether this configuration is complete enough to launch.
    pub fn is_resolved(&self) -> bool {
        !self.kind.needs_version() || self.version.is_some()
    }
}

// ---------------------------------------------------------------------------
// Where the game writes
// ---------------------------------------------------------------------------

/// Which directory the game uses for its mutable data.
///
/// `Managed` is the only variant the launcher creates, and the only one it will
/// ever delete. `External` exists because importing an existing installation is
/// a reasonable future feature, and because modelling it now means the deletion
/// rules can be written once, correctly, rather than retrofitted around it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum GameDirectory {
    /// `<profiles>/<id>/minecraft`. Owned by the launcher.
    #[default]
    Managed,
    /// A directory the user chose. **Never deleted by the launcher**, whatever
    /// happens to the profile, because it is not ours to delete.
    External { path: PathBuf },
}

impl GameDirectory {
    /// Whether the launcher owns this directory and may remove it.
    ///
    /// The single question every destructive operation asks. `External` answers
    /// no, always, without needing to know anything else about the path.
    pub fn is_launcher_owned(&self) -> bool {
        matches!(self, GameDirectory::Managed)
    }
}

// ---------------------------------------------------------------------------
// Java
// ---------------------------------------------------------------------------

/// How this profile chooses a Java runtime.
///
/// # Why four states and not a switch
///
/// The old model was "automatic, or a path you typed", sitting next to a
/// separate launcher-wide switch called *Let Fathom install Java* that was
/// greyed out whenever Java was already present. Between them they could not
/// express the two things people actually want: "use the runtime you manage,
/// even though I have one installed", and "never touch mine".
///
/// So this is a policy, and the switch is gone. [`Self::Automatic`] is the
/// recommendation and resolves in a documented order; the other three are the
/// user saying they know better, which they sometimes do.
///
/// Whatever is chosen, `PATH`, `JAVA_HOME` and the system Java installation are
/// never modified. A managed runtime lives under the launcher's own data
/// directory and is passed to the game by absolute path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum JavaPolicy {
    /// Managed runtime if there is one, else a compatible system runtime, else
    /// offer to install the managed one.
    #[default]
    Automatic,
    /// Always the runtime Fathom manages, installing it if necessary.
    ///
    /// Deliberately selectable even when a perfectly good system Java exists:
    /// a runtime the launcher controls is one that cannot be upgraded out from
    /// under a profile by something else on the machine.
    Managed,
    /// Only runtimes already installed on the machine. Never downloads.
    System,
    /// One executable the user named.
    ///
    /// `alias` accepts `explicit`, which is what earlier builds wrote for the
    /// same thing, so a profile from one of those still resolves to the path
    /// its owner chose.
    #[serde(rename = "custom", alias = "explicit")]
    Custom { path: PathBuf },
}

impl JavaPolicy {
    pub const IDS: [&'static str; 4] = ["automatic", "managed", "system", "custom"];

    pub fn id(&self) -> &'static str {
        match self {
            JavaPolicy::Automatic => "automatic",
            JavaPolicy::Managed => "managed",
            JavaPolicy::System => "system",
            JavaPolicy::Custom { .. } => "custom",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            JavaPolicy::Automatic => "Automatic",
            JavaPolicy::Managed => "Fathom managed",
            JavaPolicy::System => "System",
            JavaPolicy::Custom { .. } => "Custom",
        }
    }

    /// One short line saying what this policy will do.
    ///
    /// Kept under about forty characters: it renders in a settings row beside a
    /// three-segment control, and the row truncates rather than wrapping. The
    /// first draft read "Uses the best runtime available, installing one if
    /// needed." and arrived on screen as "...installi...".
    pub fn describe(&self) -> &'static str {
        match self {
            JavaPolicy::Automatic => "Best available, installed if needed.",
            JavaPolicy::Managed => "Downloads and keeps its own Java 8.",
            JavaPolicy::System => "Only Java already on this machine.",
            JavaPolicy::Custom { .. } => "The executable you chose.",
        }
    }

    /// The path the user named, for [`Self::Custom`] only.
    ///
    /// A method rather than a public field so that adding a fifth policy cannot
    /// silently start returning a path that means something else.
    pub fn explicit_path(&self) -> Option<&std::path::Path> {
        match self {
            JavaPolicy::Custom { path } => Some(path),
            _ => None,
        }
    }

    /// Whether this policy permits downloading a runtime.
    ///
    /// [`Self::System`] and [`Self::Custom`] say no: the user named where Java
    /// comes from, and fetching one anyway would ignore them.
    pub fn may_install(&self) -> bool {
        matches!(self, JavaPolicy::Automatic | JavaPolicy::Managed)
    }

    /// Whether a runtime found on the machine may be used.
    ///
    /// [`Self::Managed`] says no -- it wants the one the launcher controls.
    pub fn may_use_system(&self) -> bool {
        matches!(self, JavaPolicy::Automatic | JavaPolicy::System)
    }

    /// Whether the runtime the launcher manages may be used.
    pub fn may_use_managed(&self) -> bool {
        matches!(self, JavaPolicy::Automatic | JavaPolicy::Managed)
    }

    pub fn from_id(id: &str, path: Option<PathBuf>) -> Option<Self> {
        match id {
            "automatic" => Some(JavaPolicy::Automatic),
            "managed" => Some(JavaPolicy::Managed),
            "system" => Some(JavaPolicy::System),
            // A custom policy without a path is not a policy, it is a
            // half-filled form. Refused rather than stored as an empty path
            // that fails at launch with a confusing message.
            "custom" => path.map(|path| JavaPolicy::Custom { path }),
            _ => None,
        }
    }
}

/// The previous name for [`JavaPolicy`].
///
/// Kept as an alias so that a rename does not become a diff across every file
/// that mentions Java. New code should say `JavaPolicy`.
pub type JavaPreference = JavaPolicy;

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/// The parts of a profile that make it feel like *a* profile rather than a
/// configuration.
///
/// All optional, all cosmetic, and none of them affect whether the game starts.
/// They are grouped rather than scattered across [`Profile`] because this is
/// exactly the set a derived profile overrides while inheriting everything
/// technical from its parent.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Presentation {
    /// Background/theme id, matching the launcher's background list.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub theme: Option<String>,
    /// A resource pack file name inside the profile's `resourcepacks/`.
    ///
    /// A name, never a path: a profile is exported and imported, and an
    /// absolute path from somebody else's machine is worse than nothing.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resource_pack: Option<String>,
    /// Named HUD layout. The launcher stores the name and the game module owns
    /// what it means, which is what keeps HUD editing out of this schema.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hud_layout: Option<String>,
    /// Artwork file name inside the profile directory.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artwork: Option<String>,
}

impl Presentation {
    /// Whether anything has been set. Used to decide whether a derived profile
    /// overrides presentation at all.
    pub fn is_empty(&self) -> bool {
        self.theme.is_none()
            && self.resource_pack.is_none()
            && self.hud_layout.is_none()
            && self.artwork.is_none()
    }
}

/// A server the user wants one click away.
///
/// Deliberately no password, no token and no auto-join flag: this is a
/// bookmark, and a profile file is something people paste into bug reports.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerShortcut {
    pub name: String,
    /// Host, optionally with `:port`. Not checked for reachability -- a server
    /// that is down today is still a bookmark.
    pub address: String,
}

// ---------------------------------------------------------------------------
// Memory and JVM
// ---------------------------------------------------------------------------

/// Heap sizing.
///
/// `-Xms` and `-Xmx` are always set to the same value, which removes
/// heap-resize pauses at no cost. The default is deliberately modest:
/// `PERFORMANCE_MATRIX.md` §F is explicit that the community's "allocate 8–16
/// GB" advice makes GC pauses longer, not shorter.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct MemoryConfig {
    pub heap_mb: u32,
}

impl Default for MemoryConfig {
    fn default() -> Self {
        Self { heap_mb: 2048 }
    }
}

/// Extra JVM arguments supplied by the user.
///
/// Stored verbatim and validated at launch rather than on entry, so a profile
/// written by a build with different policy still loads and can be corrected,
/// instead of failing to parse.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct JvmOptions {
    #[serde(default)]
    pub extra_args: Vec<String>,
}

// ---------------------------------------------------------------------------
// Display
// ---------------------------------------------------------------------------

/// Window size the game starts at.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Resolution {
    pub width: u32,
    pub height: u32,
}

impl Default for Resolution {
    fn default() -> Self {
        // 1.8.9's own default.
        Self {
            width: 854,
            height: 480,
        }
    }
}

impl Resolution {
    /// Bounds a stored value has to satisfy to be usable.
    ///
    /// Not a matter of taste: a zero or absurd size produces a window the user
    /// cannot interact with, and the value comes from a file that may have been
    /// hand-edited.
    pub fn is_sane(&self) -> bool {
        const MIN: u32 = 320;
        const MAX: u32 = 16_384;
        (MIN..=MAX).contains(&self.width) && (MIN..=MAX).contains(&self.height)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct DisplayConfig {
    #[serde(default)]
    pub resolution: Resolution,
    #[serde(default)]
    pub fullscreen: bool,
}

// ---------------------------------------------------------------------------
// Mods
// ---------------------------------------------------------------------------

/// Which mods this profile wants.
///
/// The real type lives in [`crate::mods`], because a mod configuration is only
/// meaningful alongside the definitions and the resolver that interpret it. The
/// profile owns the *intent*; the mod engine owns what it means.
///
/// This replaced a placeholder that was a list of file names and enabled flags.
/// That shape could not express a mod's identity separately from the file it
/// happens to live in, which is what dependency and conflict resolution needs.
pub use crate::mods::ProfileModConfiguration as ModConfiguration;

fn default_true() -> bool {
    true
}

// ---------------------------------------------------------------------------
// Optimizations
// ---------------------------------------------------------------------------

/// Which optimization set a profile runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum OptimizationPreset {
    /// Everything classified SAFE that we have benchmarked ourselves.
    ///
    /// `alias` accepts `safe`, which is what earlier builds wrote for the same
    /// set. The name changed; the membership rule did not.
    #[default]
    #[serde(alias = "safe")]
    Balanced,
    /// Adds the entries that can visibly change something in exchange for work
    /// the client no longer has to do.
    Competitive,
    /// Everything, experimental entries included.
    MaximumFps,
    /// The user has chosen individually.
    ///
    /// `alias` accepts `none`, the old "nothing switched on" preset. It maps
    /// here rather than to `Balanced` because a profile that stored `none` has
    /// a switch map with everything off, and `Custom` is precisely "the map on
    /// disk is the truth" -- so the profile keeps behaving exactly as it did.
    #[serde(alias = "none")]
    Custom,
}

impl OptimizationPreset {
    pub fn label(self) -> &'static str {
        match self {
            OptimizationPreset::Balanced => "Balanced",
            OptimizationPreset::Competitive => "Competitive",
            OptimizationPreset::MaximumFps => "Maximum FPS",
            OptimizationPreset::Custom => "Custom",
        }
    }
}

/// Per-profile optimization configuration.
///
/// Two halves, because they are two different kinds of thing:
///
/// - `jvm` takes effect **today**. These are flags the launcher passes.
/// - `rendering` will take effect once the rendering work exists as a loader
///   mod. It is stored now so that enabling a loader later does not mean
///   migrating every profile, and it is honest about being inert: nothing reads
///   these yet.
///
/// The `rendering` field is a map rather than a struct of named booleans on
/// purpose. The
/// matrix has around forty entries and will grow; a map lets a new toggle land
/// without a schema migration, and lets a profile written by a newer build
/// round-trip through an older one without losing settings it does not
/// understand.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct OptimizationConfig {
    #[serde(default)]
    pub preset: OptimizationPreset,
    #[serde(default)]
    pub jvm: JvmOptimizations,
    /// Named rendering toggles. Unknown keys are preserved, never dropped.
    #[serde(default)]
    pub rendering: BTreeMap<String, bool>,
}

/// JVM-level optimizations. These work now.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct JvmOptimizations {
    /// `-XX:+DisableExplicitGC`. Neutralises vanilla's `System.gc()` on world
    /// load, which otherwise freezes the client for seconds on every world
    /// change. SAFE in the matrix.
    #[serde(default = "default_true")]
    pub disable_explicit_gc: bool,
    /// `-XX:-DontCompileHugeMethods` plus a larger code cache.
    ///
    /// **Measured, and it does nothing on 1.8.9.** Exactly one method in the
    /// game exceeds HotSpot's 8000-byte `HugeMethodLimit`:
    /// `RecipeDispatcher.<init>` at 8296 bytes, which runs once at startup.
    /// There is no hot render method for this flag to unblock — see
    /// `minecraft_runtime::jvm_args`. Off, and it stays off.
    #[serde(default)]
    pub compile_huge_methods: bool,
}

impl Default for JvmOptimizations {
    fn default() -> Self {
        Self {
            disable_explicit_gc: true,
            compile_huge_methods: false,
        }
    }
}

// ---------------------------------------------------------------------------
// The profile
// ---------------------------------------------------------------------------

/// Seconds since the Unix epoch. Stored as a plain integer rather than a
/// formatted string so that comparison is exact and locale-independent.
pub type Timestamp = u64;

pub fn now() -> Timestamp {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// The persisted description of a profile.
///
/// Every field has a `#[serde(default)]` so that a file written by an older
/// build loads without a migration step of its own. Migrations
/// ([`super::store::migrate`]) exist for changes that defaults cannot express —
/// a field that moved, or one whose meaning changed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Profile {
    pub id: ProfileId,
    pub display_name: String,
    /// How this profile was set up. See [`ProfileKind`].
    #[serde(default)]
    pub kind: ProfileKind,
    /// The profile this one derives from, if any.
    ///
    /// A derived profile inherits everything technical -- version, loader,
    /// components, performance preset -- and overrides only presentation and
    /// its own extra mods. See [`super::inherit`]. Nothing large is duplicated:
    /// game artifacts live in the shared content-addressed cache and mod jars
    /// in the shared mod store, so a derived profile costs a JSON file.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<ProfileId>,
    /// Minecraft version id, e.g. `1.8.9`.
    pub version_id: String,
    #[serde(default)]
    pub loader: LoaderConfig,
    #[serde(default)]
    pub game_directory: GameDirectory,
    #[serde(default)]
    pub java: JavaPreference,
    #[serde(default)]
    pub memory: MemoryConfig,
    #[serde(default)]
    pub jvm: JvmOptions,
    #[serde(default)]
    pub display: DisplayConfig,
    #[serde(default)]
    pub mods: ModConfiguration,
    #[serde(default)]
    pub optimizations: OptimizationConfig,
    /// Optional components the user chose, by component id.
    ///
    /// Only the *optional* ones. The components a [`ProfileKind::Fathom`]
    /// profile requires are not listed here, because they are not a choice --
    /// they are what the kind means, and storing them would let a stale file
    /// disagree with the launcher about what a Fathom profile is.
    #[serde(default)]
    pub components: std::collections::BTreeSet<String>,
    #[serde(default)]
    pub presentation: Presentation,
    /// Servers this profile puts one click away.
    #[serde(default)]
    pub servers: Vec<ServerShortcut>,
    /// Whether the launcher stays open after the game starts.
    #[serde(default = "default_true")]
    pub keep_launcher_open: bool,
    #[serde(default)]
    pub created_at: Timestamp,
    #[serde(default)]
    pub updated_at: Timestamp,
}

impl Profile {
    pub fn new(id: ProfileId, display_name: impl Into<String>) -> Self {
        let stamp = now();
        Self {
            id,
            display_name: display_name.into(),
            kind: ProfileKind::default(),
            parent: None,
            version_id: "1.8.9".to_owned(),
            loader: LoaderConfig::vanilla(),
            game_directory: GameDirectory::Managed,
            java: JavaPreference::Automatic,
            memory: MemoryConfig::default(),
            jvm: JvmOptions::default(),
            display: DisplayConfig::default(),
            mods: ModConfiguration::default(),
            optimizations: OptimizationConfig::default(),
            components: std::collections::BTreeSet::new(),
            presentation: Presentation::default(),
            servers: Vec::new(),
            keep_launcher_open: true,
            created_at: stamp,
            updated_at: stamp,
        }
    }

    /// Record that something changed. Called by the store on every save, so
    /// `updated_at` cannot drift from what is actually on disk.
    pub fn touch(&mut self) {
        self.updated_at = now();
    }

    /// Convenience: the loader kind, which is what most callers want.
    pub fn loader_kind(&self) -> Loader {
        self.loader.kind
    }

    /// Whether the launcher keeps first-party components installed here.
    pub fn owns_components(&self) -> bool {
        self.kind.owns_components()
    }

    /// Whether this profile inherits from another.
    pub fn is_derived(&self) -> bool {
        self.parent.is_some()
    }
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;

    fn profile() -> Profile {
        Profile::new(ProfileId::new("main").unwrap(), "Main")
    }

    #[test]
    fn a_new_profile_is_vanilla_managed_and_automatic() {
        let p = profile();
        assert_eq!(p.loader_kind(), Loader::Vanilla);
        assert!(p.game_directory.is_launcher_owned());
        assert!(p.java.explicit_path().is_none());
        assert!(p.created_at > 0);
        assert_eq!(p.created_at, p.updated_at);
    }

    #[test]
    fn the_default_heap_follows_the_research_not_the_folklore() {
        // PERFORMANCE_MATRIX.md §F: bigger heaps make pauses longer.
        assert_eq!(MemoryConfig::default().heap_mb, 2048);
        assert!(MemoryConfig::default().heap_mb < 4096);
    }

    /// The claim has to stay narrower than the catalogue.
    ///
    /// Legacy Fabric moved here once the launch pipeline actually resolved a
    /// loader, fetched its libraries and used its main class -- not when the
    /// provider was written. Forge is still false because `ForgeProvider`
    /// refuses, and it is the reason this test is still worth having: if every
    /// loader ever returns true, this stops asserting anything.
    #[test]
    fn only_implemented_loaders_claim_to_be_implemented() {
        assert!(Loader::Vanilla.is_implemented());
        assert!(Loader::LegacyFabric.is_implemented());
        assert!(!Loader::Forge.is_implemented());
        assert!(
            Loader::ALL.iter().any(|loader| !loader.is_implemented()),
            "if nothing is unimplemented this test asserts nothing"
        );
    }

    /// The forward-compatibility property the brief asks for: all three loaders
    /// exist in the model now, so introducing Forge is implementing it, not
    /// migrating everyone's files.
    #[test]
    fn every_loader_round_trips_through_serialisation() {
        for kind in Loader::ALL {
            let config = LoaderConfig {
                kind,
                version: kind.needs_version().then(|| "1.2.3".to_owned()),
            };
            let json = serde_json::to_string(&config).unwrap();
            let back: LoaderConfig = serde_json::from_str(&json).unwrap();
            assert_eq!(config, back, "{kind:?} did not survive a round trip");
        }
    }

    #[test]
    fn a_loader_that_needs_a_version_is_unresolved_without_one() {
        assert!(LoaderConfig::vanilla().is_resolved());
        assert!(!LoaderConfig {
            kind: Loader::Forge,
            version: None
        }
        .is_resolved());
        assert!(LoaderConfig {
            kind: Loader::Forge,
            version: Some("11.15.1.2318".into())
        }
        .is_resolved());
    }

    /// An external game directory is never ours to delete, whatever else is
    /// true about it. This is the predicate every destructive operation asks.
    #[test]
    fn only_a_managed_game_directory_is_launcher_owned() {
        assert!(GameDirectory::Managed.is_launcher_owned());
        assert!(!GameDirectory::External {
            path: PathBuf::from("/home/someone/.minecraft")
        }
        .is_launcher_owned());
    }

    #[test]
    fn absurd_resolutions_are_recognised_as_unusable() {
        assert!(Resolution::default().is_sane());
        assert!(Resolution {
            width: 1920,
            height: 1080
        }
        .is_sane());
        for bad in [
            Resolution {
                width: 0,
                height: 0,
            },
            Resolution {
                width: 10,
                height: 10,
            },
            Resolution {
                width: 100_000,
                height: 480,
            },
        ] {
            assert!(!bad.is_sane(), "{bad:?} should be rejected");
        }
    }

    /// Rendering toggles a newer build knows about must survive being loaded and
    /// saved by an older one, or a user who downgrades loses their settings.
    #[test]
    fn unknown_rendering_toggles_are_preserved_not_dropped() {
        let json = r#"{
            "preset": "custom",
            "rendering": { "a-toggle-from-the-future": true, "another": false }
        }"#;
        let config: OptimizationConfig = serde_json::from_str(json).unwrap();
        assert_eq!(config.rendering.len(), 2);
        assert_eq!(
            config.rendering.get("a-toggle-from-the-future"),
            Some(&true)
        );
        assert_eq!(config.rendering.get("another"), Some(&false));

        let round_tripped: OptimizationConfig =
            serde_json::from_str(&serde_json::to_string(&config).unwrap()).unwrap();
        assert_eq!(config, round_tripped);
    }

    /// The one rule that cannot be relaxed: a profile is exported, imported and
    /// pasted into bug reports, so nothing in it may be a credential.
    #[test]
    fn a_serialised_profile_contains_nothing_that_looks_like_a_secret() {
        let mut p = profile();
        p.jvm.extra_args = vec!["-XX:+UseG1GC".into()];
        p.java = JavaPolicy::Custom {
            path: PathBuf::from("/usr/bin/java"),
        };

        let json = serde_json::to_string(&p).unwrap().to_ascii_lowercase();
        for forbidden in [
            "token",
            "secret",
            "password",
            "refresh",
            "bearer",
            "accesstoken",
            "uuid",
            "xuid",
            "session",
        ] {
            assert!(
                !json.contains(forbidden),
                "a profile must not carry {forbidden}: {json}"
            );
        }
    }

    #[test]
    fn touching_a_profile_moves_only_the_updated_timestamp() {
        let mut p = profile();
        let created = p.created_at;
        p.updated_at = 0;
        p.touch();
        assert_eq!(p.created_at, created);
        assert!(p.updated_at >= created);
    }

    #[test]
    fn only_modded_loaders_read_a_mods_directory() {
        assert!(!Loader::Vanilla.supports_mods());
        assert!(Loader::LegacyFabric.supports_mods());
        assert!(Loader::Forge.supports_mods());
    }

    /// The profile carries intent, not a directory listing. What that intent
    /// means is [`crate::mods::resolve`]'s job, and it is tested there.
    #[test]
    fn a_new_profile_wants_no_mods() {
        assert!(profile().mods.is_empty());
    }
}
