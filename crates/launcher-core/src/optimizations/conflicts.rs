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
//! # Why the list is here and not in the catalogue
//!
//! `mods/catalog.rs` records *that* `OptiFine` and Fathom conflict, in prose, as
//! a fact about the two projects. This records *which switches* that costs,
//! which is a fact about our own optimization ids — they are declared in
//! `optimizations/mod.rs`, they change when we implement something new, and a
//! list of them belongs beside them. A test checks every id here exists.
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

/// Everything Fathom stands down from when `OptiFine` is installed.
///
/// Each entry is an optimization id from [`crate::optimizations::GAME`] and the
/// reason, written to be read by somebody who did not choose this.
///
/// The set is the intersection of "what `OptiFine` rewrites" and "what we
/// patch": its renderer replaces font drawing, entity rendering, chunk building
/// and tile entity rendering wholesale. Frame caps, memory and resource
/// handling are untouched by it and stay on.
const OPTIFINE_OVERLAP: &[(&str, &str)] = &[
    (
        "render.font.batching",
        "OptiFine replaces the font renderer, so batching ours would draw through a path that is \
         no longer there.",
    ),
    (
        "render.font.metrics-cache",
        "OptiFine computes glyph widths differently. A cache of vanilla's answers would be a \
         cache of the wrong numbers.",
    ),
    (
        "render.hud.quad-batching",
        "OptiFine draws the HUD through its own path.",
    ),
    (
        "render.entity.model-batching",
        "OptiFine rewrites entity rendering.",
    ),
    (
        "render.tile-entity.batching",
        "OptiFine rewrites tile entity rendering.",
    ),
    (
        "render.tile-entity.double-render-fix",
        "OptiFine fixes this itself.",
    ),
    (
        "render.entity.occlusion-culling",
        "OptiFine has its own entity culling, and running both means entities culled twice by \
         two sets of rules — which is how a mob goes missing.",
    ),
    (
        "render.chunk.rebuild-priority",
        "OptiFine rebuilds chunks on its own schedule.",
    ),
    (
        "render.chunk.skip-redundant-rebuilds",
        "OptiFine decides what needs rebuilding.",
    ),
    (
        "render.chunk.frustum-false-negative-fix",
        "OptiFine has its own frustum test, so patching vanilla's changes nothing and risks \
         disagreeing with it.",
    ),
    (
        "render.chunk.worker-pool-sizing",
        "OptiFine sizes its own chunk workers.",
    ),
    (
        "render.particle.brightness-cache",
        "OptiFine changes particle lighting.",
    ),
];

/// The mod ids whose presence stands Fathom down, and what it costs.
fn overlaps_for(id: &str) -> &'static [(&'static str, &'static str)] {
    match id {
        "optifine" => OPTIFINE_OVERLAP,
        _ => &[],
    }
}

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
        let overlaps = overlaps_for(entry.id.as_str());
        if overlaps.is_empty() {
            continue;
        }
        let name = display_name(&entry.id);
        for (optimization, detail) in overlaps {
            out.push(Standdown {
                optimization: (*optimization).to_owned(),
                because_of: name.clone(),
                detail: (*detail).to_owned(),
            });
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
    fn every_optimization_named_here_actually_exists() {
        // The list is written by hand against ids declared elsewhere. Without
        // this, a rename in `optimizations/mod.rs` would silently stop
        // disabling something, and the failure mode is a broken renderer under
        // OptiFine that nobody can explain.
        for (id, _) in OPTIFINE_OVERLAP {
            assert!(
                crate::optimizations::find(id).is_some(),
                "{id} is not an optimization"
            );
        }
    }

    #[test]
    fn optifine_stands_our_renderer_patches_down() {
        let standdowns = for_profile(&profile_with("optifine", true));
        assert_eq!(standdowns.len(), OPTIFINE_OVERLAP.len());
        assert!(standdowns
            .iter()
            .any(|s| s.optimization == "render.entity.occlusion-culling"));
        assert!(standdowns.iter().all(|s| s.because_of == "OptiFine"));
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
        let culling = standdowns
            .iter()
            .find(|s| s.optimization == "render.entity.occlusion-culling")
            .unwrap();
        assert_eq!(
            culling.headline("Entity occlusion culling"),
            "Fathom Entity occlusion culling was disabled for OptiFine compatibility."
        );
    }
}
