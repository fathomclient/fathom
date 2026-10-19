//! The config bridge: how the launcher tells the module what to do.
//!
//! # The shape of the seam
//!
//! The launcher owns the settings. It knows the profile, it has the screen
//! space, and it can say what an optimization costs before you switch it on.
//! The game side needs the answers, not a second settings screen. So exactly
//! one file crosses between them, written immediately before launch and read
//! once at startup by `net.fathom.optimizations.core.config.LauncherConfig`.
//!
//! # Why a properties file
//!
//! Because the Java side can parse it with nothing at all. `java.util.Properties`
//! ships in the JDK, which lets the module's `core` package keep the property
//! that makes it testable: no dependencies, builds with a bare JDK 8. JSON
//! would mean shipping a parser library that every user then needs on the
//! classpath, or hand-rolling one, which is a bug surface in exchange for
//! punctuation.
//!
//! # What it is not
//!
//! It is not a general settings channel, and nothing reads it back. It is a
//! one-way statement of "these are the switches for this launch". State that
//! belongs to the launcher stays in the profile; state that belongs to the game
//! stays in the game.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use crate::profiles::{OptimizationPreset, Profile};

/// Where the file goes, relative to the game directory. Must match
/// `LauncherConfig.RELATIVE_PATH` on the Java side.
pub const RELATIVE_PATH: &str = "config/fathom-performance.properties";

/// Format version. Must match `LauncherConfig.SCHEMA`.
///
/// Bumped only for a change an old reader cannot survive. Adding a key is not
/// such a change: unknown keys are ignored by design on both sides, which is
/// what lets a newer launcher run against an older module.
pub const SCHEMA: u32 = 1;

/// The settings for one launch, ready to serialise.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settings {
    pub preset: OptimizationPreset,
    /// Registry id to on/off. Only ids the catalogue knows about.
    pub switches: BTreeMap<String, bool>,
    /// Optimizations stood down for another mod, and why.
    ///
    /// Separate from `switches` on purpose. A switch says what the user chose;
    /// this says what the launcher overrode and for what, and the module needs
    /// both — the first to know the setting, the second to be able to tell
    /// somebody why the setting they chose is not in effect. Collapsing them
    /// into one map would lose exactly the sentence the Hub has to show.
    pub conflicts: BTreeMap<String, String>,
}

impl Settings {
    /// Read a profile's optimization configuration.
    ///
    /// Only game-side ids are included. The two JVM options are deliberately
    /// absent: they are command-line flags the launcher already applies, and
    /// repeating them here would invite the module to think it owns something
    /// it cannot affect.
    pub fn from_profile(profile: &Profile) -> Self {
        let mut switches = BTreeMap::new();
        // Launcher switches only. An entry that is always on, or set from an
        // in-game module's own settings, has no launcher answer to send.
        for optimization in super::game()
            .iter()
            .filter(|o| o.control == super::Control::Switch)
        {
            // Through the shared resolver, not the stored map. The map and the
            // preset can disagree, and reading it directly here is what made the
            // game run a different set of optimizations from the ones the
            // Performance page was showing. See `optimizations::enabled_for`.
            // An entry for another loader is sent as off: a Forge-only fix has
            // no target on Legacy Fabric, and reporting it on there would make
            // the file claim a configuration the game is not in.
            let enabled = super::enabled_for(profile, optimization)
                && optimization.applies_to(profile.loader_kind());
            switches.insert(optimization.id.to_owned(), enabled);
        }
        // Anything another mod is already doing is turned off here rather
        // than left on to fight it. The switch keeps the user's own answer;
        // what changes is that the module is told not to act on it, and told
        // why, so the Hub can say so instead of the optimization looking broken.
        let standdowns = super::conflicts::for_profile(profile);
        for standdown in &standdowns {
            switches.insert(standdown.optimization.clone(), false);
        }

        Self {
            preset: profile.optimizations.preset,
            switches,
            conflicts: super::conflicts::as_map(&standdowns),
        }
    }

    /// The file's contents.
    ///
    /// Ordering is deterministic because `switches` is a `BTreeMap`: the file
    /// is diffable, and writing it twice with the same settings produces
    /// identical bytes, so nothing downstream sees a spurious change.
    pub fn to_properties(&self) -> String {
        let mut out = String::new();
        out.push_str(
            "# Written by Fathom before launch. Do not edit: it is replaced every time\n\
             # the game starts. Change these settings in the launcher's Performance page.\n",
        );
        // `writeln!` into a String cannot fail, so the results are discarded
        // rather than unwrapped.
        let _ = writeln!(out, "schema = {SCHEMA}");
        let _ = writeln!(out, "preset = {}", preset_key(self.preset));
        for (id, enabled) in &self.switches {
            let _ = writeln!(out, "opt.{id} = {enabled}");
        }
        for (id, reason) in &self.conflicts {
            // One line, and the reason is prose with no escaping rules of its
            // own: `java.util.Properties` ends a value at the newline, and
            // `conflicts.rs` writes single-line reasons. Flattened here anyway,
            // because a value that runs onto a second line would silently
            // become a key.
            let _ = writeln!(out, "conflict.{id} = {}", one_line(reason));
        }
        out
    }

    /// Write the file into a game directory, creating `config/` if needed.
    pub fn write_to(&self, game_directory: &Path) -> std::io::Result<PathBuf> {
        let path = game_directory.join(RELATIVE_PATH);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        // Written whole rather than appended, so a previous launch's settings
        // can never survive into this one.
        std::fs::write(&path, self.to_properties())?;
        Ok(path)
    }
}

/// Everything on one line, for a format whose values end at the newline.
fn one_line(value: &str) -> String {
    value
        .chars()
        .map(|c| if c == '\n' || c == '\r' { ' ' } else { c })
        .collect()
}

/// The wire name for a preset. Lower-case and stable; the Java side compares
/// against these exact strings.
const fn preset_key(preset: OptimizationPreset) -> &'static str {
    match preset {
        OptimizationPreset::Balanced => "balanced",
        OptimizationPreset::Competitive => "competitive",
        OptimizationPreset::MaximumFps => "maximum-fps",
        OptimizationPreset::Custom => "custom",
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    use crate::profiles::ProfileId;

    fn profile() -> Profile {
        Profile::new(ProfileId::new("test").unwrap(), "Test")
    }

    /// A default profile is on `Balanced`, so the file says on for exactly the
    /// entries Balanced includes -- the ones that are implemented, classified
    /// Safe and actually measured -- and off for everything else.
    ///
    /// This used to assert that *everything* was off, from when no game-side
    /// optimization was implemented and Balanced was therefore empty. It stayed
    /// passing by accident after the first entries were promoted, because the
    /// bridge read the stored switch map instead of the preset -- which is the
    /// same defect that left users on Balanced running nothing.
    #[test]
    fn a_default_profile_writes_the_balanced_set() {
        let profile = profile();
        let settings = Settings::from_profile(&profile);
        let switchable: Vec<_> = super::super::game()
            .iter()
            .filter(|o| o.control == super::super::Control::Switch)
            .collect();
        assert_eq!(settings.switches.len(), switchable.len());

        for optimization in switchable {
            let on = settings.switches.get(optimization.id).copied().unwrap();
            assert_eq!(
                on,
                OptimizationPreset::Balanced.includes(optimization),
                "{} does not match what Balanced includes",
                optimization.id
            );
        }
        assert!(
            settings.switches.values().any(|on| *on),
            "Balanced has measured entries in it, so the file cannot be all off"
        );
    }

    /// A loader-specific entry is on only under its loader. The Legacy Fabric
    /// profile's file used to say the Forge sky colour cache was on.
    #[test]
    fn an_entry_for_another_loader_is_sent_as_off() {
        use crate::profiles::Loader;
        let mut profile = profile();
        profile.optimizations.preset = OptimizationPreset::MaximumFps;
        let id = "render.forge.sky-blend-cache";

        profile.loader.kind = Loader::LegacyFabric;
        assert_eq!(Settings::from_profile(&profile).switches.get(id), Some(&false));

        profile.loader.kind = Loader::Forge;
        assert_eq!(Settings::from_profile(&profile).switches.get(id), Some(&true));
    }

    /// The JVM flags are the launcher's business. Sending them would suggest
    /// the module could act on them, which it cannot.
    #[test]
    fn jvm_options_are_not_sent_to_the_module() {
        let settings = Settings::from_profile(&profile());
        for id in settings.switches.keys() {
            assert!(!id.starts_with("jvm."), "{id} should not cross the bridge");
        }
    }

    /// Under `Custom`, and only under Custom, the stored map is the truth.
    ///
    /// A named preset deliberately outranks it (see
    /// `optimizations::enabled_for`), and toggling any single entry moves a
    /// profile to Custom anyway -- so this is the state a hand-picked switch
    /// actually lives in.
    #[test]
    fn switches_round_trip_through_the_file_format() {
        let mut p = profile();
        p.optimizations.preset = OptimizationPreset::Custom;
        p.optimizations
            .rendering
            .insert("input.unfocused-fps-cap".to_owned(), true);
        let text = Settings::from_profile(&p).to_properties();

        assert!(text.contains("schema = 1"));
        assert!(text.contains("opt.input.unfocused-fps-cap = true"));
        assert!(text.contains("opt.input.frame-limiter-accuracy = false"));
    }

    /// The file is rewritten on every launch, so two identical configurations
    /// must produce identical bytes.
    #[test]
    fn the_same_settings_produce_the_same_bytes() {
        let p = profile();
        assert_eq!(
            Settings::from_profile(&p).to_properties(),
            Settings::from_profile(&p).to_properties()
        );
    }

    #[test]
    fn writing_creates_the_config_directory() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let written = Settings::from_profile(&profile())
            .write_to(directory.path())
            .expect("write");

        assert!(written.is_file());
        assert!(written.ends_with("fathom-performance.properties"));
        let text = std::fs::read_to_string(&written).expect("read back");
        assert!(text.contains("preset = balanced"));
    }

    /// A rewrite must not leave any of the previous launch's lines behind.
    /// The conflict half of the file, which is what the module reads to know
    /// its own switch is being overridden and what to say about it.
    #[test]
    fn a_conflicting_mod_stands_optimizations_down_in_the_file() {
        use crate::mods::{ModId, ModSource, ProfileMod};

        let mut p = profile();
        p.optimizations
            .rendering
            .insert("world.chunk.lookup-cache".to_owned(), true);
        p.mods.upsert(
            ProfileMod::new(
                ModId::new("optifine").unwrap(),
                "0000000000000000000000000000000000000000",
                "OptiFine_1.8.9_HD_U_M5.jar",
            )
            .from_source(ModSource::UserSupplied),
        );

        let text = Settings::from_profile(&p).to_properties();
        assert!(
            text.contains("opt.world.chunk.lookup-cache = false"),
            "the switch is forced off: {text}"
        );
        assert!(
            text.contains("conflict.world.chunk.lookup-cache = OptiFine:"),
            "and the reason travels with it: {text}"
        );
        // The unfocused-window policy has nothing to do with OptiFine and must survive.
        assert!(!text.contains("conflict.input.unfocused-fps-cap"));
    }

    #[test]
    fn a_profile_with_nothing_conflicting_writes_no_conflict_lines() {
        assert!(!Settings::from_profile(&profile())
            .to_properties()
            .contains("conflict."));
    }

    #[test]
    fn rewriting_replaces_rather_than_appends() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let mut p = profile();
        // Under Custom, where the stored answer is the answer. Under a named
        // preset the preset decides, and both writes would say the same thing.
        p.optimizations.preset = OptimizationPreset::Custom;
        p.optimizations
            .rendering
            .insert("input.unfocused-fps-cap".to_owned(), true);
        Settings::from_profile(&p)
            .write_to(directory.path())
            .unwrap();

        p.optimizations
            .rendering
            .insert("input.unfocused-fps-cap".to_owned(), false);
        let written = Settings::from_profile(&p)
            .write_to(directory.path())
            .unwrap();

        let text = std::fs::read_to_string(&written).expect("read back");
        assert!(text.contains("opt.input.unfocused-fps-cap = false"));
        assert!(
            !text.contains("opt.input.unfocused-fps-cap = true"),
            "the previous launch's value survived"
        );
    }

    /// Both sides hard-code these strings; a rename on one side alone would
    /// silently stop the module from reading its settings.
    #[test]
    fn the_wire_contract_matches_the_java_side() {
        let source = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../minecraft/fathom-optimizations/src/main/java/net/fathom/",
            "optimizations/core/config/LauncherConfig.java"
        );
        let Ok(java) = std::fs::read_to_string(source) else {
            return;
        };
        assert!(
            java.contains(&format!("RELATIVE_PATH = \"{RELATIVE_PATH}\"")),
            "the Java reader looks for a different path than the writer produces"
        );
        assert!(
            java.contains(&format!("SCHEMA = {SCHEMA}")),
            "schema version disagrees across the bridge"
        );
        assert!(
            java.contains("PREFIX = \"opt.\""),
            "the key prefix disagrees across the bridge"
        );
        for preset in [
            OptimizationPreset::Balanced,
            OptimizationPreset::Competitive,
            OptimizationPreset::MaximumFps,
            OptimizationPreset::Custom,
        ] {
            let _ = preset_key(preset);
        }
    }
}
