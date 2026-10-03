//! What the launcher knows about Fathom's optimizations.
//!
//! # Where this comes from
//!
//! The game module's `OptimizationRegistry` (Java) is the one source. It is
//! written to `assets/optimizations/registry.json` by `gradle exportRegistry`,
//! and that file is compiled in here. A core test on the Java side fails when
//! the checked-in file is stale, so the launcher cannot describe a catalogue the
//! game does not have.
//!
//! This used to be a hand-written copy of the Java list with a test that only
//! compared ids. Everything else drifted: names, status, and which mods stand
//! an entry down. The launcher said the chunk lookup cache ran under OptiFine
//! while the game skipped it. The file is the fix for that class of bug, not
//! just that instance.
//!
//! The two JVM flags are the one part that stays here: the launcher passes
//! them itself, so they are the launcher's facts, not the module's.
//!
//! # The honest part
//!
//! - [`Status::Active`] means the module hooks it into the game. Nothing else
//!   is ever switched on, by any preset.
//! - [`Status::Planned`] is a described problem with no implementation. It is
//!   listed as planned and never offered a switch.
//! - [`Control`] says whether the launcher can switch it at all: some entries
//!   are always on, and some are set from an in-game module's own settings.

pub mod benchmarks;
pub mod bridge;
pub mod conflicts;

use std::collections::BTreeMap;
use std::sync::OnceLock;

use crate::profiles::{OptimizationPreset, Profile};

/// The registry the game module exports, compiled in.
const REGISTRY_JSON: &str = include_str!("../../../../assets/optimizations/registry.json");

/// The export format this launcher reads. Must match `RegistryJson.SCHEMA`.
const REGISTRY_SCHEMA: u32 = 1;

/// Where an optimization sits on the risk scale, mirroring Java's `Category`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Category {
    /// Output-identical to vanilla. Nothing a player can see changes.
    Safe,
    /// Visibly changes something, or trades correctness for speed in a way a
    /// player might notice and might not want.
    Optional,
    /// Not understood well enough to recommend.
    Experimental,
}

impl Category {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Safe => "Safe",
            Self::Optional => "Optional",
            Self::Experimental => "Experimental",
        }
    }

    pub const fn explanation(self) -> &'static str {
        match self {
            Self::Safe => "Produces the same picture as vanilla.",
            Self::Optional => "Changes something you can see. Your call.",
            Self::Experimental => "Not measured enough to recommend.",
        }
    }
}

/// Whether this does anything for the player *right now*.
///
/// The distinction the whole page rests on. A launcher that lists twenty
/// optimizations and lets you toggle them all, while none of them are running,
/// is lying — politely, and with a nice interface, but lying.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// Implemented: the launcher applies it, or the module hooks it into the game.
    Active,
    /// Described, not built. Never switched on by anything.
    Planned,
}

impl Status {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Active => "Active",
            Self::Planned => "Planned",
        }
    }
}

/// How far the implementation has actually got, behind the status.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Readiness {
    /// Runs now. Only the JVM flags, which the launcher applies itself.
    Working,
    /// The logic exists in `core/` and is unit-tested, but nothing hooks it to
    /// the game yet.
    CoreWritten,
    /// Hooked in and shipped in the jar -- but never observed in a running game.
    ///
    /// A separate state from `RunsInGame` on purpose. "It compiles and the hook
    /// applies" and "it does the right thing in Minecraft" are different
    /// claims, and collapsing them is the quiet overstatement this exists to
    /// prevent.
    InModule,
    /// Observed applying inside a running Minecraft 1.8.9.
    RunsInGame,
    /// A registry entry with a described problem and no implementation.
    Planned,
}

impl Readiness {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Working => "Implemented",
            Self::CoreWritten => "Core written, not hooked up",
            Self::InModule => "In the module, not yet seen in game",
            Self::RunsInGame => "Verified running in game",
            Self::Planned => "Planned",
        }
    }
}

/// Which part of the product owns this.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Surface {
    /// A JVM flag. The launcher applies it.
    Java,
    /// Inside the game, via the module.
    Game,
}

/// How a player turns it on or off -- or whether they do at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Control {
    /// A launcher switch, sent to the game in the config bridge.
    Switch,
    /// Always on where it applies. No decision to offer.
    Automatic,
    /// Part of an in-game module's settings, changed from the Hub. The
    /// launcher shows it and says where it is set, and offers no switch it
    /// cannot honour.
    InGame,
}

/// The grouping the registry files each entry under.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Group {
    Rendering,
    Chunks,
    Entities,
    Memory,
    Frames,
    Hud,
    Resources,
}

impl Group {
    pub const ALL: [Self; 7] = [
        Self::Rendering,
        Self::Chunks,
        Self::Entities,
        Self::Memory,
        Self::Frames,
        Self::Hud,
        Self::Resources,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::Rendering => "Rendering",
            Self::Chunks => "Chunks",
            Self::Entities => "Entities",
            Self::Memory => "Memory",
            Self::Frames => "Frames and input",
            Self::Hud => "HUD and interface",
            Self::Resources => "Resources",
        }
    }
}

/// Another mod whose presence stands an entry down, and what goes wrong.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Conflict {
    /// The mod's display name as the registry writes it: `OptiFine`.
    pub with: &'static str,
    pub detail: &'static str,
}

/// One catalogue entry.
#[derive(Debug, Clone, Copy)]
pub struct Optimization {
    /// The registry id. Identical to the Java side, and the key used in a
    /// profile's `optimizations.rendering` map.
    pub id: &'static str,
    pub name: &'static str,
    pub summary: &'static str,
    pub group: Group,
    pub category: Category,
    pub surface: Surface,
    pub status: Status,
    pub readiness: Readiness,
    pub control: Control,
    /// What we actually measured, or `None` where we measured nothing.
    pub evidence: Option<&'static str>,
    /// Mods that stand this down when installed.
    pub conflicts: &'static [Conflict],
}

impl Optimization {
    /// Whether the launcher can meaningfully switch it today: implemented, and
    /// controlled by a launcher switch rather than always-on or set in game.
    pub fn is_actionable(&self) -> bool {
        self.status == Status::Active && self.control == Control::Switch
    }

    /// Implemented, whoever controls it.
    pub fn is_implemented(&self) -> bool {
        self.status == Status::Active
    }

    /// The conflict that stands this down when `mod_name` is installed.
    pub fn conflict_with(&self, mod_name: &str) -> Option<&'static Conflict> {
        self.conflicts
            .iter()
            .find(|conflict| conflict.with.eq_ignore_ascii_case(mod_name))
    }
}

/// The two JVM flags, which the launcher applies itself.
pub const JAVA: &[Optimization] = &[
    Optimization {
        id: "jvm.disable-explicit-gc",
        name: "Suppress explicit garbage collection",
        summary: "Vanilla asks the JVM to collect garbage every time you change world. \
                  -XX:+DisableExplicitGC makes it a no-op.",
        group: Group::Memory,
        category: Category::Safe,
        surface: Surface::Java,
        status: Status::Active,
        readiness: Readiness::Working,
        control: Control::Switch,
        evidence: Some("Neutralises the System.gc() vanilla calls on world load, which otherwise stalls the client for seconds."),
        conflicts: &[],
    },
    Optimization {
        id: "jvm.compile-huge-methods",
        name: "Compile oversized methods",
        summary: "On this version, nothing. HotSpot will not compile methods over 8000 bytes, and \
                  1.8.9 has one -- a startup recipe constructor. Lifting the limit compiles that one \
                  method and reserves a larger code cache for it. Left switchable for other versions.",
        group: Group::Memory,
        category: Category::Optional,
        surface: Surface::Java,
        status: Status::Active,
        readiness: Readiness::Working,
        control: Control::Switch,
        evidence: Some("Measured on 1.8.9: exactly one method in the game exceeds the 8000-byte limit -- RecipeDispatcher.<init> at 8296 bytes, which registers crafting recipes once during startup. No render-path method is near the limit, so there is no frame-time effect to gain."),
        conflicts: &[],
    },
];

/// The game-side catalogue, from the registry the module exports.
pub fn game() -> &'static [Optimization] {
    static GAME: OnceLock<Vec<Optimization>> = OnceLock::new();
    GAME.get_or_init(|| parse_registry(REGISTRY_JSON))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RegistryFile {
    schema: u32,
    optimizations: Vec<RegistryEntry>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RegistryEntry {
    id: String,
    name: String,
    description: String,
    group: String,
    category: String,
    control: String,
    status: String,
    evidence: Option<RegistryEvidence>,
    #[serde(default)]
    conflicts: Vec<RegistryConflict>,
}

#[derive(serde::Deserialize)]
struct RegistryEvidence {
    summary: String,
}

#[derive(serde::Deserialize)]
struct RegistryConflict {
    with: String,
    resolution: String,
    detail: String,
}

/// Parse the exported registry. Leaked once, for the life of the process: the
/// catalogue is a constant that happens to be read from a file, and `'static`
/// strings keep every caller as simple as the hand-written array was.
///
/// Panics on a malformed file. It is compiled in and checked by tests on both
/// sides, so a parse failure is a build defect, not a runtime condition.
fn parse_registry(json: &str) -> Vec<Optimization> {
    fn leak(value: String) -> &'static str {
        Box::leak(value.into_boxed_str())
    }

    #[allow(clippy::expect_used)]
    let file: RegistryFile =
        serde_json::from_str(json).expect("assets/optimizations/registry.json is valid");
    assert_eq!(
        file.schema, REGISTRY_SCHEMA,
        "the compiled-in registry has a schema this launcher does not read"
    );

    file.optimizations
        .into_iter()
        .map(|entry| {
            let (status, readiness) = match entry.status.as_str() {
                "RUNS_IN_GAME" => (Status::Active, Readiness::RunsInGame),
                "IN_MODULE" => (Status::Active, Readiness::InModule),
                "CORE_WRITTEN" => (Status::Planned, Readiness::CoreWritten),
                _ => (Status::Planned, Readiness::Planned),
            };
            let conflicts: Vec<Conflict> = entry
                .conflicts
                .into_iter()
                .filter(|conflict| conflict.resolution == "DISABLE_OURS")
                .map(|conflict| Conflict {
                    with: leak(conflict.with),
                    detail: leak(conflict.detail),
                })
                .collect();
            Optimization {
                id: leak(entry.id),
                name: leak(entry.name),
                summary: leak(entry.description),
                group: match entry.group.as_str() {
                    "CHUNKS" => Group::Chunks,
                    "ENTITIES" => Group::Entities,
                    "MEMORY" => Group::Memory,
                    "FRAMES" => Group::Frames,
                    "HUD" => Group::Hud,
                    "RESOURCES" => Group::Resources,
                    _ => Group::Rendering,
                },
                category: match entry.category.as_str() {
                    "SAFE" => Category::Safe,
                    "OPTIONAL" => Category::Optional,
                    _ => Category::Experimental,
                },
                surface: Surface::Game,
                status,
                readiness,
                control: match entry.control.as_str() {
                    "AUTOMATIC" => Control::Automatic,
                    "IN_GAME" => Control::InGame,
                    _ => Control::Switch,
                },
                evidence: entry.evidence.map(|evidence| leak(evidence.summary)),
                conflicts: Box::leak(conflicts.into_boxed_slice()),
            }
        })
        .collect()
}

/// Everything, Java first.
pub fn all() -> Vec<Optimization> {
    JAVA.iter().chain(game().iter()).copied().collect()
}

pub fn in_group(group: Group) -> Vec<Optimization> {
    all().into_iter().filter(|o| o.group == group).collect()
}

pub fn find(id: &str) -> Option<Optimization> {
    all().into_iter().find(|o| o.id == id)
}

/// Described, not built: what the Performance page lists as planned work.
pub fn planned() -> Vec<Optimization> {
    all().into_iter().filter(|o| o.status == Status::Planned).collect()
}

/// How many optimizations are doing something right now.
/// Whether an optimization is on for a profile.
///
/// # Why the stored map does not simply win
///
/// A profile records a materialised switch map *and* the preset it was chosen
/// from. Those two can disagree, and when they did the stored map won — which
/// quietly broke the property [`OptimizationPreset::includes`] is written to
/// have: "a preset gains entries automatically as they become `Active`".
///
/// What happened in practice: an entry that was not yet implemented was written
/// into the map as `false`, because at that moment `Balanced` genuinely did not
/// include it. The entry was later finished, measured and promoted, so
/// `Balanced` began including it — but every profile created before that kept
/// its explicit `false` for ever. A user on Balanced was silently held at the
/// set of optimizations that existed the day their profile was made.
///
/// So a named preset is the source of truth, and only `Custom` reads the map.
/// Choosing "Balanced" means "keep me on what Balanced means", not "freeze me
/// at what Balanced meant once".
///
/// # Why this is shared rather than written twice
///
/// Because it already was written twice, and the two copies disagreed. The
/// Performance page fell back to the preset for an id the map did not mention;
/// the bridge that tells the game what to run fell back to `false`. An
/// optimization missing from the map therefore rendered as **on** in the
/// launcher and ran as **off** in the game, which is the worst of both — a page
/// reporting a configuration the client was not in.
pub fn enabled_for(profile: &Profile, optimization: &Optimization) -> bool {
    if profile.optimizations.preset != OptimizationPreset::Custom {
        return profile.optimizations.preset.includes(optimization);
    }
    profile
        .optimizations
        .rendering
        .get(optimization.id)
        .copied()
        // An entry a Custom profile has never expressed an opinion about is
        // off: Custom means the user curated this list, and switching something
        // on because it was added later is not curation.
        .unwrap_or(false)
}

pub fn active_count() -> usize {
    all().iter().filter(|o| o.is_implemented()).count()
}

// ---------------------------------------------------------------------------
// Presets
// ---------------------------------------------------------------------------

/// Preset behaviour, hung on the enum profiles already persist.
///
/// Deliberately not a second preset type. `OptimizationPreset` is stored in
/// every profile on disk; defining a parallel set here would mean two
/// vocabularies for one idea and a mapping between them to keep in step.
///
/// # On the names
///
/// These are named for intent, not for outcome. `MaximumFps` is a statement
/// about how much this switches on, not a promise about a frame counter: no
/// in-game benchmark has been run against this module, and a preset cannot
/// deliver a number nobody has measured. The descriptions below say so, and
/// none of them quotes a frame rate.
impl OptimizationPreset {
    pub const ALL: [Self; 4] = [
        Self::Balanced,
        Self::Competitive,
        Self::MaximumFps,
        Self::Custom,
    ];

    pub const fn description(self) -> &'static str {
        match self {
            Self::Balanced => {
                "Everything we have benchmarked ourselves and found output-identical to                  vanilla. Nothing here changes what you see."
            }
            Self::Competitive => {
                "Adds work we have not benchmarked, and a few things that can change what                  is drawn -- a distant entity culled a frame early, slightly different                  particle lighting."
            }
            Self::MaximumFps => {
                "Every optimization the module actually implements, experimental ones                  included. Entries with no implementation stay off in every preset."
            }
            Self::Custom => "Choose each option yourself.",
        }
    }

    /// Whether this preset includes a given optimization.
    ///
    /// `Balanced` reads the same rule the module's own `defaultEnabled()`
    /// enforces -- classified Safe *and* actually measured -- rather than
    /// asserting membership independently, so the preset and the module cannot
    /// disagree.
    pub fn includes(self, optimization: &Optimization) -> bool {
        // NOTHING THAT DOES NOT EXIST IS EVER SWITCHED ON.
        //
        // This gate is the difference between a preset and a promise. Without
        // it, `MaximumFps` turned on all eighteen catalogue entries, fifteen of
        // which have no implementation -- so the switch map written to the
        // bridge file claimed a configuration the module cannot honour, and the
        // page reported "18 on" for a game doing nothing differently.
        //
        // A preset is a statement about what will run. An entry that is not
        // implemented cannot run, so no preset includes it, and a preset gains
        // entries automatically as they become `Active`.
        if !optimization.is_actionable() {
            return false;
        }

        match self {
            // Measured, and output-identical to vanilla.
            Self::Balanced => {
                optimization.category == Category::Safe && optimization.evidence.is_some()
            }
            // Adds work we have not benchmarked and entries that can visibly
            // change something.
            Self::Competitive => {
                matches!(optimization.category, Category::Safe | Category::Optional)
            }
            // Everything implemented, experimental included.
            Self::MaximumFps => true,
            // Custom is whatever the profile stores, so membership is not
            // something the preset itself can answer.
            Self::Custom => false,
        }
    }

    /// The switch map this preset implies.
    pub fn switches(self) -> BTreeMap<String, bool> {
        all()
            .iter()
            .map(|o| (o.id.to_owned(), self.includes(o)))
            .collect()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    /// The catalogue is a hand-written mirror of the Java registry, so the one
    /// thing that must never silently drift is which entries exist. Reads the
    /// Java source directly: if someone adds an optimization there and not
    /// here, the launcher would quietly never show it.
    fn profile_with(preset: OptimizationPreset) -> Profile {
        use crate::profiles::ProfileId;
        let mut profile = Profile::new(ProfileId::new("p").unwrap(), "P");
        profile.optimizations.preset = preset;
        profile
    }

    /// The regression this resolver exists for.
    ///
    /// A profile made before an optimization was implemented has an explicit
    /// `false` for it. Once the entry is finished and promoted, `Balanced`
    /// includes it -- and the profile must follow, or a user on Balanced is
    /// frozen at whatever Balanced meant the day they made the profile.
    #[test]
    fn a_named_preset_outranks_a_stale_stored_false() {
        let measured = all()
            .iter()
            .find(|o| {
                o.is_actionable() && o.category == Category::Safe && o.evidence.is_some()
            })
            .copied()
            .expect("at least one measured, safe, active optimization");

        let mut profile = profile_with(OptimizationPreset::Balanced);
        profile
            .optimizations
            .rendering
            .insert(measured.id.to_owned(), false);

        assert!(
            enabled_for(&profile, &measured),
            "{} is in Balanced, so a stale stored false must not hold it off",
            measured.id
        );
    }

    /// And the other half: Custom means the user curated the list, so the
    /// stored answer is the answer.
    #[test]
    fn custom_honours_what_was_stored() {
        let entry = all()
            .iter()
            .find(|o| o.is_actionable())
            .copied()
            .expect("an actionable optimization");

        let mut profile = profile_with(OptimizationPreset::Custom);
        profile
            .optimizations
            .rendering
            .insert(entry.id.to_owned(), true);
        assert!(enabled_for(&profile, &entry));

        profile
            .optimizations
            .rendering
            .insert(entry.id.to_owned(), false);
        assert!(!enabled_for(&profile, &entry));
    }

    /// An entry a Custom profile has never seen stays off rather than switching
    /// itself on because the catalogue grew.
    #[test]
    fn custom_leaves_an_unmentioned_entry_off() {
        let entry = all()
            .iter()
            .find(|o| o.is_actionable())
            .copied()
            .expect("an actionable optimization");
        let profile = profile_with(OptimizationPreset::Custom);

        assert!(!enabled_for(&profile, &entry));
    }

    /// The divergence that made this one function instead of two.
    ///
    /// The game is told what to run by `bridge::Settings`; the Performance page
    /// draws from `enabled_for`. If those two can disagree, the launcher is
    /// reporting a configuration the client is not in.
    #[test]
    fn the_game_is_told_exactly_what_the_page_shows() {
        for preset in [
            OptimizationPreset::Balanced,
            OptimizationPreset::Competitive,
            OptimizationPreset::MaximumFps,
            OptimizationPreset::Custom,
        ] {
            let profile = profile_with(preset);
            let settings = bridge::Settings::from_profile(&profile);
            for optimization in game().iter().filter(|o| o.control == Control::Switch) {
                // Conflict stand-downs are the one legitimate difference, and
                // this profile has no mods, so there are none here.
                let told = settings.switches.get(optimization.id).copied();
                assert_eq!(
                    told,
                    Some(enabled_for(&profile, optimization)),
                    "{} disagrees between the page and the game under {preset:?}",
                    optimization.id
                );
            }
        }
    }

    /// The compiled-in registry is the game module's, parsed whole.
    ///
    /// The Java side's `RegistryTests` fails when the checked-in file is stale;
    /// this checks the other half -- that the launcher reads every entry it
    /// contains, with nothing dropped by a parse it did not understand.
    #[test]
    fn the_registry_file_is_read_whole() {
        let declared = REGISTRY_JSON.matches("\"id\":").count();
        assert_eq!(game().len(), declared, "an entry in registry.json was not read");
        assert!(!game().is_empty());
        for optimization in game() {
            assert_eq!(optimization.surface, Surface::Game, "{}", optimization.id);
        }
    }

    /// The claim the Performance page is built on.
    ///
    /// This test has been narrowed twice, each time because the product earned
    /// it. It first said "nothing game-side is active" (true while the Fabric
    /// layer would not compile), then "active entries are `InModule`" (true
    /// once the jar shipped). Now one entry has been observed applying inside a
    /// running game, so `RunsInGame` is permitted -- and `Working` still is
    /// not, because `Working` would imply a measured effect on the game and the
    /// only numbers we have are from an isolated microbenchmark.
    #[test]
    fn no_game_entry_claims_more_than_it_has_earned() {
        for optimization in game() {
            assert_ne!(
                optimization.readiness,
                Readiness::Working,
                "{} claims a measured in-game effect, which nothing here has",
                optimization.id
            );
            match optimization.status {
                // Implemented in the shipped module. Either it has been seen
                // running or it has not; both are honest, neither is `Working`.
                Status::Active => assert!(
                    matches!(
                        optimization.readiness,
                        Readiness::InModule | Readiness::RunsInGame
                    ),
                    "{} is switched on but is not implemented in the module",
                    optimization.id
                ),
                // Not implemented, so it must not claim to be.
                Status::Planned => assert!(
                    !matches!(
                        optimization.readiness,
                        Readiness::InModule | Readiness::RunsInGame
                    ),
                    "{} is implemented in the module but reports as needing one",
                    optimization.id
                ),
            }
        }
    }

    /// The JVM flags really do work, and the page should say so.
    #[test]
    fn the_java_flags_are_active() {
        assert_eq!(JAVA.len(), 2);
        for optimization in JAVA {
            assert!(optimization.is_actionable(), "{}", optimization.id);
            assert_eq!(optimization.surface, Surface::Java);
        }
    }

    /// The property the Performance page's honesty rests on: a preset never
    /// switches on something that does not exist.
    ///
    /// Without this, "Maximum FPS" wrote eighteen `true`s into the bridge file
    /// for a module that implements three of them -- a configuration claiming a
    /// result nobody could get.
    #[test]
    fn no_preset_enables_an_unimplemented_optimization() {
        for preset in OptimizationPreset::ALL {
            for (id, enabled) in preset.switches() {
                if !enabled {
                    continue;
                }
                let entry = find(&id).expect("a catalogue entry");
                assert!(
                    entry.is_actionable(),
                    "{} switches on {id}, which has no implementation",
                    preset.label()
                );
            }
        }
    }

    /// Maximum FPS must be a superset of Competitive, which must be a superset
    /// of Balanced. A preset ladder where a rung turns something OFF that the
    /// rung below turned on is not a ladder.
    #[test]
    fn the_presets_are_nested() {
        let on = |preset: OptimizationPreset| -> Vec<String> {
            preset
                .switches()
                .into_iter()
                .filter_map(|(id, enabled)| enabled.then_some(id))
                .collect()
        };
        let balanced = on(OptimizationPreset::Balanced);
        let competitive = on(OptimizationPreset::Competitive);
        let maximum = on(OptimizationPreset::MaximumFps);

        for id in &balanced {
            assert!(competitive.contains(id), "Competitive drops {id}");
        }
        for id in &competitive {
            assert!(maximum.contains(id), "Maximum FPS drops {id}");
        }
    }

    /// `active_count` is what the page's header reports, so it must equal the
    /// number of entries that are genuinely switched on -- counted here from
    /// the catalogue rather than hard-coded, so adding an implementation
    /// updates the header without anybody having to remember to.
    #[test]
    fn the_active_count_is_what_is_actually_active() {
        let expected = all().iter().filter(|o| o.status == Status::Active).count();
        assert_eq!(active_count(), expected);
        assert!(
            active_count() >= JAVA.len(),
            "the JVM flags alone should already be active"
        );
    }

    #[test]
    fn ids_are_unique() {
        let mut ids: Vec<&str> = all().iter().map(|o| o.id).collect();
        let total = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(total, ids.len(), "duplicate optimization id");
    }

    /// None is empty, Safe never reaches past measured-and-safe, and no preset
    /// enables anything Experimental.
    #[test]
    fn presets_never_include_experimental_or_unmeasured_work() {
        for optimization in &all() {
            assert!(!OptimizationPreset::Custom.includes(optimization));
            if OptimizationPreset::Balanced.includes(optimization) {
                assert_eq!(optimization.category, Category::Safe, "{}", optimization.id);
                assert!(
                    optimization.evidence.is_some(),
                    "{} is in Safe defaults but was never measured",
                    optimization.id
                );
            }
            if optimization.category == Category::Experimental {
                assert!(!OptimizationPreset::Balanced.includes(optimization));
            }
        }
        assert!(
            OptimizationPreset::Balanced
                .switches()
                .values()
                .any(|on| *on),
            "Safe defaults enables nothing at all"
        );
    }

    /// A switch map has to cover the whole catalogue, or a profile written from
    /// a preset would leave newer optimizations unset and undefined.
    #[test]
    fn a_preset_covers_every_optimization() {
        let switches = OptimizationPreset::Balanced.switches();
        assert_eq!(switches.len(), all().len());
        for optimization in all() {
            assert!(
                switches.contains_key(optimization.id),
                "{}",
                optimization.id
            );
        }
    }

    /// Every entry has to say something specific. A blank summary would leave
    /// a row in the interface with a name and nothing else.
    #[test]
    fn every_entry_describes_itself() {
        for optimization in all() {
            assert!(!optimization.name.is_empty(), "{}", optimization.id);
            assert!(
                optimization.summary.len() > 30,
                "{} has no real description",
                optimization.id
            );
            assert!(
                optimization.id.contains('.'),
                "{} is not a namespaced id",
                optimization.id
            );
        }
    }
}
