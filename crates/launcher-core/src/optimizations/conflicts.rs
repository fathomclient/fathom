//! What Fathom turns off when something else is doing the same job.
//!
//! # The rule
//!
//! **Do not silently run a known-conflicting patch.** Two mods rewriting the
//! same renderer path is not a performance question, it is a correctness one:
//! whichever loads second wins some frames and loses others, and the result is
//! a client that renders wrongly in ways neither project can reproduce.
//!
//! So when the launcher can see the other mod in the profile, it turns Fathom's
//! overlapping work off before the game starts, and — this is the half that
//! makes it acceptable — **says which ones and why**, in a sentence the user
//! reads in the Hub:
//!
//! > Fathom Entity occlusion culling was disabled for `OptiFine` compatibility.
//!
//! Silence would be worse than the conflict. Somebody who switched an
//! optimization on in the launcher and finds it inert has been lied to by a
//! settings screen, which is the defect the module milestone spent its length
//! removing.
//!
//! # Where the list comes from
//!
//! The game module's registry. Each entry there declares the mods that stand
//! it down and what goes wrong if both run (`Conflict.disableOurs` in Java),
//! and the exported `registry.json` carries that here. This file used to keep
//! its own hand-written copy, and the two drifted: it never learned that the
//! chunk lookup cache is skipped under OptiFine, so the Performance page said
//! it was running when it was not.
//!
//! # Why the launcher decides and not the module
//!
//! The module cannot see the mods directory in any meaningful way: it knows
//! what Fabric loaded, and `OptiFine` for 1.8.9 is not a Fabric mod — under
//! Legacy Fabric it arrives through `OptiFabric` or not at all, and under Forge
//! it is not in the same process model. The launcher put the file there. It is
//! the half of the system that knows.

use std::collections::BTreeMap;

use crate::mods::ModId;
use crate::profiles::Profile;

/// One optimization that was switched off, and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Standdown {
    /// The optimization id.
    pub optimization: String,
    /// The mod it stood down for, by display name.
    pub because_of: String,
    /// The sentence explaining it.
    pub detail: String,
}

impl Standdown {
    /// The line the interface shows.
    ///
    /// Built here so the launcher and the game word it the same way. The game
    /// receives the parts, not the sentence, because it knows the
    /// optimization's display name and this module deliberately does not carry
    /// a second copy of it.
    pub fn headline(&self, optimization_name: &str) -> String {
        format!(
            "Fathom {optimization_name} was disabled for {} compatibility.",
            self.because_of
        )
    }
}

/// Work out what to stand down for a profile.
///
/// Reads the profile's mod list — the ids it records, which for an imported
/// jar is the catalogue id `mods::ModEngine::add_user_supplied` resolved from
/// the filename. That is why identification matters: a jar filed under
/// `optifine_1_8_9_hd_u_m5` would not be recognised here, and the conflict
/// would go unnoticed.
///
/// Only enabled entries count. A mod present in the configuration but switched
/// off is not in the game, so standing down for it would disable a working
/// optimization for nothing.
pub fn for_profile(profile: &Profile) -> Vec<Standdown> {
    let mut out = Vec::new();
    for entry in profile.mods.enabled() {
        let name = display_name(&entry.id);
        for optimization in super::game() {
            // Matched on the id as well as the display name: the registry
            // writes "OptiFine", and a jar the catalogue does not recognise is
            // still filed under the id `optifine`.
            let conflict = optimization
                .conflict_with(&name)
                .or_else(|| optimization.conflict_with(entry.id.as_str()));
            if let Some(conflict) = conflict {
                out.push(Standdown {
                    optimization: optimization.id.to_owned(),
                    because_of: name.clone(),
                    detail: conflict.detail.to_owned(),
                });
            }
        }
    }
    out
}

/// The proper name of a conflicting mod.
///
/// From the catalogue when it knows the id, so the sentence says `OptiFine` and
/// not "optifine". A mod the catalogue has never heard of keeps its id, which
/// is ugly and true.
fn display_name(id: &ModId) -> String {
    crate::mods::catalog::find(id).map_or_else(
        || id.as_str().to_owned(),
        |definition| definition.name.clone(),
    )
}

/// The stand-downs as the settings file carries them: id to reason.
pub fn as_map(standdowns: &[Standdown]) -> BTreeMap<String, String> {
    standdowns
        .iter()
        .map(|standdown| {
            (
                standdown.optimization.clone(),
                format!("{}: {}", standdown.because_of, standdown.detail),
            )
        })
        .collect()
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation
)]
mod tests {
    use super::*;
    use crate::mods::{ModId, ModSource, ProfileMod};
    use crate::profiles::ProfileId;

    fn profile_with(mod_id: &str, enabled: bool) -> Profile {
        let mut profile = Profile::new(ProfileId::new("test").unwrap(), "Test");
        let mut entry = ProfileMod::new(
            ModId::new(mod_id).unwrap(),
            "0000000000000000000000000000000000000000",
            "whatever.jar",
        )
        .from_source(ModSource::UserSupplied);
        entry.enabled = enabled;
        profile.mods.upsert(entry);
        profile
    }

    #[test]
    fn optifine_stands_down_exactly_what_the_registry_says() {
        let standdowns = for_profile(&profile_with("optifine", true));
        let declared: Vec<&str> = crate::optimizations::game()
            .iter()
            .filter(|o| o.conflict_with("OptiFine").is_some())
            .map(|o| o.id)
            .collect();
        assert_eq!(standdowns.len(), declared.len());
        assert!(standdowns.iter().all(|s| s.because_of == "OptiFine"));
        // The one this rewrite exists for: skipped in game under OptiFine, and
        // for years described as running by this page.
        assert!(standdowns
            .iter()
            .any(|s| s.optimization == "world.chunk.lookup-cache"));
    }

    /// "OptiFine shaders" is a different condition from "OptiFine installed",
    /// and must not be read as one.
    #[test]
    fn a_shaders_conflict_is_not_an_optifine_conflict() {
        let standdowns = for_profile(&profile_with("optifine", true));
        assert!(!standdowns
            .iter()
            .any(|s| s.optimization == "render.entity.occlusion-culling"));
    }

    #[test]
    fn what_optifine_does_not_touch_is_left_alone() {
        let standdowns = for_profile(&profile_with("optifine", true));
        for untouched in [
            "input.unfocused-fps-cap",
            "memory.world-change-gc",
            "resources.pack-icon-downscale",
        ] {
            assert!(
                !standdowns.iter().any(|s| s.optimization == untouched),
                "{untouched} has nothing to do with OptiFine and must stay on"
            );
        }
    }

    #[test]
    fn a_disabled_mod_is_not_in_the_game_and_costs_nothing() {
        assert!(for_profile(&profile_with("optifine", false)).is_empty());
    }

    #[test]
    fn a_profile_with_nothing_conflicting_stands_nothing_down() {
        assert!(for_profile(&profile_with("fabric-api", true)).is_empty());
    }

    #[test]
    fn the_headline_is_the_sentence_the_brief_asked_for() {
        let standdowns = for_profile(&profile_with("optifine", true));
        let cache = standdowns
            .iter()
            .find(|s| s.optimization == "world.chunk.lookup-cache")
            .unwrap();
        assert_eq!(
            cache.headline("Chunk lookup cache"),
            "Fathom Chunk lookup cache was disabled for OptiFine compatibility."
        );
    }
}
