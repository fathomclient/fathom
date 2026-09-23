//! What the launcher knows about Fathom's optimizations.
//!
//! # Where this comes from
//!
//! `minecraft/fathom-optimizations` holds the real registry, in Java, as
//! `OptimizationRegistry`. Its own documentation says the launcher is what
//! reads it "to show a user what is available, what it would cost them, and
//! what it conflicts with". This module is the launcher's side of that: the
//! same catalogue, in the same order, with the same ids.
//!
//! It is written out by hand rather than generated, and that is a deliberate
//! trade with a cost attached. Generating it would mean the Java module has to
//! be built before the launcher can be, which would make a Rust developer
//! install a JDK to compile a window. The cost is that the two can drift, so
//! [`tests`] checks every id here against the Java source file, and fails if
//! either side gains or loses an entry.
//!
//! # The honest part
//!
//! Nothing here claims a frame rate. The Java registry is explicit that
//! **nothing is enabled by default**, that the evidence it has is isolated
//! microbenchmarks rather than in-game measurement, and that the Fabric layer
//! has never been compiled. So:
//!
//! - The two JVM options are [`Status::Active`]. They are command-line flags
//!   the launcher passes to Java, they are covered by tests in
//!   `minecraft_runtime::jvm_args`, and they work whether or not any mod is
//!   installed.
//! - Every game-side optimization is [`Status::RequiresModule`], because the
//!   module jar is not built and not shipped. Five of them have working core
//!   logic behind them; that is recorded as [`Readiness`], and it is still not
//!   the same as doing anything for a player today.
//!
//! A UI built on this can therefore say what is true, which is the only reason
//! to have the type at all.

pub mod benchmarks;
pub mod bridge;
pub mod conflicts;

use std::collections::BTreeMap;

use crate::profiles::{OptimizationPreset, Profile};

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
    /// Working today. The launcher does this itself.
    Active,
    /// Implemented nowhere yet, or implemented in `core/` but with no Mixin
    /// hooking it to the game. The module exists and loads; these entries are
    /// the ones it does not act on.
    RequiresModule,
}

impl Status {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Active => "Active",
            Self::RequiresModule => "Needs module",
        }
    }
}

/// How far the implementation has actually got, behind the status.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Readiness {
    /// Runs now.
    Working,
    /// The logic exists in `core/` and is unit-tested, but nothing hooks it to
    /// the game yet. The Fabric layer compiles now, so this is a missing Mixin
    /// rather than a missing toolchain.
    CoreWritten,
    /// Implemented in the module, remapped against the real 1.8.9 mappings and
    /// shipped in the jar -- but never observed in a running game.
    ///
    /// A separate state from `Working` on purpose. "It compiles and its unit
    /// tests pass" and "it does the right thing in Minecraft" are different
    /// claims, and collapsing them is exactly the kind of quiet overstatement
    /// this enum exists to prevent.
    InModule,
    /// Observed applying inside a running Minecraft 1.8.9.
    ///
    /// Earned by launching the game with `injectors.defaultRequire = 1`, which
    /// makes a Mixin that fails to inject crash at class load instead of being
    /// skipped. Reaching the title screen is therefore positive evidence that
    /// every injector applied, rather than the absence of an error message --
    /// which is what the previous `defaultRequire = 0` gave, and what let a
    /// Mixin targeting a method that does not exist sit broken for a milestone.
    ///
    /// Still NOT a claim about frame rate. What this states is "the code runs
    /// in the game and produces correct output"; what fraction of a frame it
    /// saves has not been measured in a real client.
    RunsInGame,
    /// A registry entry with a described problem and no implementation.
    Planned,
}

impl Readiness {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Working => "Implemented",
            Self::CoreWritten => "Core written, not hooked up",
            Self::InModule => "In the module, unverified in game",
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

/// The grouping shown on the Performance page.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Group {
    Rendering,
    Chunks,
    Entities,
    Memory,
    Frames,
    Resources,
}

impl Group {
    pub const ALL: [Self; 6] = [
        Self::Rendering,
        Self::Chunks,
        Self::Entities,
        Self::Memory,
        Self::Frames,
        Self::Resources,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::Rendering => "Rendering",
            Self::Chunks => "Chunks",
            Self::Entities => "Entities",
            Self::Memory => "Memory",
            Self::Frames => "Frames and input",
            Self::Resources => "Resources",
        }
    }
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
    /// What we actually measured, or `None` where we measured nothing. Never a
    /// frame-rate claim: no in-game benchmark has been run.
    pub evidence: Option<&'static str>,
}

impl Optimization {
    /// Whether the user can meaningfully turn this on today.
    pub const fn is_actionable(&self) -> bool {
        matches!(self.status, Status::Active)
    }
}

macro_rules! entry {
    ($id:literal, $name:literal, $group:ident, $category:ident, $surface:ident,
     $status:ident, $readiness:ident, $evidence:expr, $summary:literal) => {
        Optimization {
            id: $id,
            name: $name,
            summary: $summary,
            group: Group::$group,
            category: Category::$category,
            surface: Surface::$surface,
            status: Status::$status,
            readiness: Readiness::$readiness,
            evidence: $evidence,
        }
    };
}

/// The two JVM flags, which are the only optimizations that do anything today.
pub const JAVA: &[Optimization] = &[
    entry!(
        "jvm.disable-explicit-gc",
        "Suppress explicit garbage collection",
        Memory, Safe, Java, Active, Working,
        Some("Neutralises the System.gc() vanilla calls on world load, which otherwise stalls the client for seconds."),
        "Vanilla asks the JVM to collect garbage every time you change world. \
         -XX:+DisableExplicitGC makes it a no-op."
    ),
    entry!(
        "jvm.compile-huge-methods",
        "Compile oversized methods",
        Memory, Optional, Java, Active, Working,
        Some("Measured on 1.8.9: exactly one method in the game exceeds the 8000-byte limit -- RecipeDispatcher.<init> at 8296 bytes, which registers crafting recipes once during startup. No render-path method is near the limit, so there is no frame-time effect to gain."),
        "On this version, nothing. HotSpot will not compile methods over 8000 bytes, and \
         1.8.9 has one -- a startup recipe constructor. Lifting the limit compiles that one \
         method and reserves a larger code cache for it. Left switchable for other versions."
    ),
];

/// The game-side catalogue, mirroring `OptimizationRegistry.Entries.all()`.
pub const GAME: &[Optimization] = &[
    entry!("render.font.batching", "Batched font rendering", Rendering, Safe, Game, RequiresModule, CoreWritten,
        Some("A 240-glyph line issues 3 draw calls instead of 240, vertex-for-vertex identical to the unbatched path."),
        "Vanilla issues one draw call per glyph. Batching a text run into one buffer turns a line of chat from dozens of calls into one."),
    entry!("render.font.metrics-cache", "Font metrics caching", Rendering, Safe, Game, Active, RunsInGame,
        Some("235 strings of a busy server's frame: 14.3us recomputed against 1.8us memoised (-87%). At 5% churn -67%. At 50% churn the difference is inside the noise floor; at 100% churn it is 53% slower. Zero width mismatches at every churn level."),
        "Character width lookups are recomputed every frame. Caching them is output-identical as long as the cache clears on resource reload."),
    entry!("render.hud.quad-batching", "Batched HUD and GUI quads", Rendering, Safe, Game, RequiresModule, Planned, None,
        "Quads sharing a texture are collected into one buffer instead of being submitted one at a time."),
    entry!("render.entity.model-batching", "Batched entity and item models", Entities, Safe, Game, RequiresModule, Planned, None,
        "Entity and item models sharing render state are drawn together."),
    entry!("render.tile-entity.batching", "Tile entity batching", Rendering, Safe, Game, RequiresModule, Planned, None,
        "Chests, signs and banners sharing a texture are grouped rather than drawn individually."),
    entry!("render.tile-entity.double-render-fix", "Fix double-rendered tile entities", Rendering, Safe, Game, RequiresModule, Planned, None,
        "Vanilla draws some tile entities twice in the same frame. Drawing them once is both faster and correct."),
    entry!("render.entity.occlusion-culling", "Entity occlusion culling", Entities, Optional, Game, RequiresModule, CoreWritten, None,
        "Skip entities hidden behind terrain. Changes what is drawn, so a wrong answer means a missing mob."),
    entry!("render.chunk.rebuild-priority", "Prioritised chunk rebuilds", Chunks, Safe, Game, RequiresModule, Planned, None,
        "Rebuild the chunks you are looking at before the ones behind you."),
    entry!("render.chunk.skip-redundant-rebuilds", "Skip redundant chunk rebuilds", Chunks, Safe, Game, RequiresModule, Planned, None,
        "A rebuild that cannot change the mesh does not need to run."),
    entry!("render.chunk.frustum-false-negative-fix", "Fix invisible chunks", Chunks, Optional, Game, RequiresModule, Planned, None,
        "MC-63020 and MC-70850: vanilla's frustum test discards chunks that are actually on screen."),
    entry!("cpu.allocation.hot-path-reduction", "Reduce hot-path allocation", Rendering, Safe, Game, Active, RunsInGame,
        Some("Interleaved in-game A/B in the development world: allocation while panning 51.8 -> 23.4 MB/s and while moving 203 -> 132 MB/s, with fewer collections. The six-direction array clones fell from 1890 MB to 91 MB over a profiled run."),
        "Vanilla clones the six-direction array at the top of loops in light queries and chunk visibility. Those loops only read it, so they share one copy. Output-identical."),
    entry!("world.chunk.lookup-cache", "Chunk lookup cache", Chunks, Safe, Game, Active, RunsInGame,
        Some("Toggled alone in four interleaved in-game blocks with alternating order: mean frame time 6.5-9.9% lower and p99 lower in every block. Chunk lookups fell from 14.3% to 5.1% of render-thread CPU samples."),
        "Light queries and entity rendering resolve chunks through a hash lookup many times a frame. The client thread remembers recent lookups and clears them whenever a chunk loads or unloads."),
    entry!("memory.world-change-gc", "Remove world-change collection", Memory, Safe, Game, Active, RunsInGame,
        Some("The in-game half of the JVM flag above, for the paths a flag cannot reach. Joining the development world ran three forced full collections (22-24 ms each on its small heap); with this on, the first runs and the next two are skipped."),
        "The same explicit collection as the JVM flag, removed at its source rather than suppressed."),
    entry!("resources.pack-icon-downscale", "Downscale resource pack icons", Resources, Safe, Game, RequiresModule, CoreWritten,
        Some("Pack icons are loaded at full resolution and drawn at 32x32; downscaling on load cuts the memory held for them."),
        "A 512x512 pack icon is kept in memory at full size to be drawn as a thumbnail."),
    entry!("input.mc-67665-look-vector", "Fix stale look vector", Frames, Safe, Game, RequiresModule, Planned, None,
        "MC-67665: the look vector used for interaction can be a frame behind what you see."),
    entry!("input.frame-limiter-accuracy", "Accurate frame limiter", Frames, Safe, Game, RequiresModule, Planned, None,
        "Vanilla's frame cap overshoots and undershoots. A more accurate limiter makes a capped frame rate steadier."),
    entry!("input.unfocused-fps-cap", "Cap frame rate when unfocused", Frames, Safe, Game, Active, InModule,
        Some("Exact rather than measured, because it is arithmetic: while enabled and unfocused the module returns the smaller of vanilla's own answer and the configured background limit. What that frees on a given machine has not been measured."),
        "Vanilla renders at full speed into a window you have alt-tabbed away from. This optional policy caps only the unfocused window and changes nothing while it has focus."),
    entry!("render.chunk.worker-pool-sizing", "Size the chunk worker pool", Chunks, Experimental, Game, RequiresModule, Planned, None,
        "Match the chunk build thread count to the machine rather than a fixed guess."),
    entry!("render.particle.brightness-cache", "Cache particle brightness", Rendering, Optional, Game, RequiresModule, Planned, None,
        "Particle brightness is recomputed per particle per frame. Caching it can change lighting slightly."),
];

/// Everything, Java first.
pub fn all() -> Vec<Optimization> {
    JAVA.iter().chain(GAME.iter()).copied().collect()
}

pub fn in_group(group: Group) -> Vec<Optimization> {
    all().into_iter().filter(|o| o.group == group).collect()
}

pub fn find(id: &str) -> Option<Optimization> {
    all().into_iter().find(|o| o.id == id)
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
    all().iter().filter(|o| o.is_actionable()).count()
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
            for optimization in GAME {
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

    #[test]
    fn the_catalogue_matches_the_java_registry() {
        let source = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../minecraft/fathom-optimizations/src/main/java/net/fathom/",
            "optimizations/core/registry/OptimizationRegistry.java"
        );
        let Ok(java) = std::fs::read_to_string(source) else {
            // The Java module is optional in a source checkout.
            return;
        };

        let mut in_java: Vec<&str> = java
            .split("Optimization.builder(\"")
            .skip(1)
            .filter_map(|rest| rest.split('"').next())
            .collect();
        in_java.sort_unstable();

        let mut in_rust: Vec<&str> = GAME.iter().map(|o| o.id).collect();
        in_rust.sort_unstable();

        assert_eq!(
            in_rust, in_java,
            "the Rust catalogue and the Java registry disagree about which \
             optimizations exist"
        );
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
        for optimization in GAME {
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
                Status::RequiresModule => assert!(
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
