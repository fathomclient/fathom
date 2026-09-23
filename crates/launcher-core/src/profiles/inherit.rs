//! Profiles that derive from other profiles.
//!
//! # The shape of the problem
//!
//! Somebody has a *Fathom 1.8.9 Competitive* profile and wants a *Bedwars* one
//! that is the same client with a different resource pack, a different HUD and
//! two extra mods. Copying the profile works exactly once: the day the parent's
//! loader version moves, the copy is stale and nobody remembers why.
//!
//! So a derived profile stores its overrides and points at its parent, and this
//! module works out what it actually launches with.
//!
//! # What is inherited and what is not
//!
//! Inherited, always, and **not** editable on the child:
//!
//! - Minecraft version
//! - loader and loader version
//! - optimization preset and switches
//! - components
//! - Java policy, memory and JVM arguments
//!
//! Overridden by the child when the child says so:
//!
//! - display name (always the child's -- that is what makes it a separate row)
//! - presentation: theme, resource pack, HUD layout, artwork
//! - mods: the child's list is the parent's plus its own
//! - server shortcuts
//! - display resolution and `keep_launcher_open`
//!
//! The split is not arbitrary. The inherited half is everything that decides
//! *what client starts*, which is what makes "same client, different loadout"
//! expressible at all. The overridden half is everything that decides how it
//! looks and what else is loaded into it.
//!
//! # Nothing large is copied
//!
//! A derived profile costs one JSON file. Game jars, libraries and assets live
//! in the shared content-addressed cache; mod jars live in the shared mod store
//! keyed by SHA-1. Deriving ten profiles from one parent duplicates ten
//! filenames, not ten copies of Minecraft.
//!
//! What a derived profile *does* get of its own is a game directory -- worlds,
//! screenshots, options. Sharing those would make "a different way to play"
//! mean "the same saves with different mods loaded", which is how worlds get
//! corrupted.

use super::model::Profile;
use super::ProfileId;

/// Why a chain could not be resolved.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum InheritError {
    #[error("this profile derives from {0}, which does not exist")]
    MissingParent(ProfileId),
    /// A cycle, or a chain deeper than [`MAX_DEPTH`].
    ///
    /// Both are reported the same way because both mean the same thing to the
    /// user -- the chain cannot be followed -- and because a cycle discovered
    /// at depth 8 is indistinguishable from a legitimate chain of 9 without
    /// tracking every id, which is not worth it for a limit nobody will reach
    /// honestly.
    #[error("this profile's inheritance chain is circular or too deep")]
    Unresolvable,
}

/// How many parents deep a chain may go.
///
/// Four is generous: base, variant, event, one-off. The limit exists because
/// the file on disk is user-editable and `a -> b -> a` must terminate.
pub const MAX_DEPTH: usize = 4;

/// Resolve a profile against its ancestry.
///
/// `lookup` is how parents are found -- the store in production, a map in
/// tests. Returns the profile as it should actually be launched: the child's
/// identity and overrides, over the root ancestor's technical configuration.
///
/// A profile with no parent resolves to itself, unchanged and without touching
/// `lookup`, which is the overwhelmingly common case.
pub fn resolve<F>(profile: &Profile, mut lookup: F) -> Result<Profile, InheritError>
where
    F: FnMut(&ProfileId) -> Option<Profile>,
{
    if profile.parent.is_none() {
        return Ok(profile.clone());
    }

    // Walk to the root, collecting the chain. Built outermost-last so that
    // applying it is a fold from the root down, which is the order a reader
    // expects: the base, then what each generation changed.
    let mut chain = vec![profile.clone()];
    let mut depth = 0usize;
    while let Some(parent_id) = chain.last().and_then(|last| last.parent.clone()) {
        depth += 1;
        if depth > MAX_DEPTH {
            return Err(InheritError::Unresolvable);
        }
        let parent = lookup(&parent_id).ok_or(InheritError::MissingParent(parent_id))?;
        chain.push(parent);
    }

    let mut effective = chain.pop().unwrap_or_else(|| profile.clone());
    // Fold back down: each child overlays its overrides on what it inherited.
    while let Some(child) = chain.pop() {
        effective = overlay(&effective, &child);
    }
    Ok(effective)
}

/// Apply one child's overrides to what it inherits.
///
/// Written as a whole-struct construction rather than a sequence of
/// assignments, so that a field added to [`Profile`] is a compile error here
/// until somebody decides which half it belongs in. A new field that silently
/// defaulted to "inherited" would be a bug discovered by a user, not by us.
fn overlay(parent: &Profile, child: &Profile) -> Profile {
    Profile {
        // -- the child's own identity ------------------------------------
        id: child.id.clone(),
        display_name: child.display_name.clone(),
        parent: child.parent.clone(),
        created_at: child.created_at,
        updated_at: child.updated_at,

        // -- inherited: what decides which client starts -----------------
        kind: parent.kind,
        version_id: parent.version_id.clone(),
        loader: parent.loader.clone(),
        optimizations: parent.optimizations.clone(),
        components: parent.components.clone(),
        java: parent.java.clone(),
        memory: parent.memory,
        jvm: parent.jvm.clone(),

        // -- the child's own directory -----------------------------------
        // Never inherited. Two profiles pointing at one game directory means
        // two mod sets over one set of worlds.
        game_directory: child.game_directory.clone(),

        // -- overridden: how it looks and what else is loaded ------------
        display: child.display,
        keep_launcher_open: child.keep_launcher_open,
        servers: if child.servers.is_empty() {
            parent.servers.clone()
        } else {
            child.servers.clone()
        },
        presentation: if child.presentation.is_empty() {
            parent.presentation.clone()
        } else {
            child.presentation.clone()
        },
        // Additive rather than replacing: a derived profile is the parent's
        // client *plus* something, and a Bedwars profile that silently lost the
        // parent's mods would be a puzzle rather than a feature.
        mods: merge_mods(parent, child),
    }
}

fn merge_mods(parent: &Profile, child: &Profile) -> crate::mods::ProfileModConfiguration {
    let mut merged = parent.mods.clone();
    for entry in child.mods.iter() {
        // The child wins on collision: overriding a mod's enabled state is one
        // of the things a variant profile is for.
        merged.upsert(entry.clone());
    }
    merged
}

/// Build a profile derived from `parent`.
///
/// Copies nothing technical -- that is what [`resolve`] is for. The new profile
/// is a name, a parent, and a blank slate of overrides.
pub fn derive(parent: &Profile, id: ProfileId, display_name: impl Into<String>) -> Profile {
    let mut derived = Profile::new(id, display_name);
    derived.parent = Some(parent.id.clone());
    // Carried rather than inherited-at-resolve-time so that the row in the
    // profile list reads correctly before anything is resolved.
    derived.kind = parent.kind;
    derived.version_id.clone_from(&parent.version_id);
    derived.loader.clone_from(&parent.loader);
    derived
}

/// Whether `candidate` may be made the parent of `profile`.
///
/// Refuses self-parenting and any choice that would close a cycle. Checked
/// before the edge is written, because a stored cycle is a profile that cannot
/// be launched or edited until somebody finds the JSON.
pub fn may_parent<F>(profile: &ProfileId, candidate: &Profile, mut lookup: F) -> bool
where
    F: FnMut(&ProfileId) -> Option<Profile>,
{
    if &candidate.id == profile {
        return false;
    }
    let mut current = candidate.parent.clone();
    for _ in 0..=MAX_DEPTH {
        let Some(id) = current else { return true };
        if &id == profile {
            return false;
        }
        current = lookup(&id).and_then(|found| found.parent);
    }
    false
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::mods::{ModId, ProfileMod};
    use crate::profiles::model::ProfileKind;
    use crate::profiles::{Loader, OptimizationPreset};
    use std::collections::BTreeMap;

    fn id(raw: &str) -> ProfileId {
        ProfileId::new(raw).unwrap()
    }

    fn base() -> Profile {
        let mut profile = Profile::new(id("competitive"), "Fathom 1.8.9 Competitive");
        profile.kind = ProfileKind::Fathom;
        profile.loader.kind = Loader::LegacyFabric;
        profile.loader.version = Some("0.15.3".to_owned());
        profile.optimizations.preset = OptimizationPreset::Competitive;
        profile.memory.heap_mb = 3072;
        profile.presentation.theme = Some("bg_2".to_owned());
        profile
    }

    fn store(profiles: Vec<Profile>) -> impl FnMut(&ProfileId) -> Option<Profile> {
        move |wanted| profiles.iter().find(|p| &p.id == wanted).cloned()
    }

    fn a_mod(name: &str) -> ProfileMod {
        ProfileMod::new(
            ModId::new(name).unwrap(),
            "0".repeat(40),
            format!("{name}.jar"),
        )
    }

    #[test]
    fn a_profile_with_no_parent_resolves_to_itself() {
        let profile = base();
        let resolved = resolve(&profile, |_| panic!("must not look anything up")).unwrap();
        assert_eq!(resolved, profile);
    }

    /// The example from the brief: Bedwars derives from Competitive.
    #[test]
    fn a_derived_profile_inherits_the_client_and_keeps_its_own_look() {
        let parent = base();
        let mut child = derive(&parent, id("bedwars"), "Bedwars");
        child.presentation.resource_pack = Some("bedwars-16x.zip".to_owned());
        child.presentation.hud_layout = Some("bedwars".to_owned());

        let resolved = resolve(&child, store(vec![parent.clone()])).unwrap();

        // Inherited.
        assert_eq!(resolved.version_id, parent.version_id);
        assert_eq!(resolved.loader, parent.loader);
        assert_eq!(
            resolved.optimizations.preset,
            OptimizationPreset::Competitive
        );
        assert_eq!(resolved.memory.heap_mb, 3072);
        assert_eq!(resolved.kind, ProfileKind::Fathom);

        // Its own.
        assert_eq!(resolved.id, child.id);
        assert_eq!(resolved.display_name, "Bedwars");
        assert_eq!(
            resolved.presentation.resource_pack.as_deref(),
            Some("bedwars-16x.zip")
        );
        // Presentation is overridden as a unit, so the parent's theme does not
        // leak into a child that set a pack and a layout.
        assert_eq!(resolved.presentation.theme, None);
    }

    /// A child that overrides nothing cosmetic still gets the parent's look
    /// rather than a blank one.
    #[test]
    fn an_untouched_child_inherits_presentation_too() {
        let parent = base();
        let child = derive(&parent, id("variant"), "Variant");
        let resolved = resolve(&child, store(vec![parent])).unwrap();
        assert_eq!(resolved.presentation.theme.as_deref(), Some("bg_2"));
    }

    /// Mods add up. A derived profile is the parent's client plus something.
    #[test]
    fn mods_are_additive_and_the_child_wins_a_collision() {
        let mut parent = base();
        parent.mods.upsert(a_mod("fathom-optimizations"));
        parent.mods.upsert(a_mod("shared"));

        let mut child = derive(&parent, id("bedwars"), "Bedwars");
        child.mods.upsert(a_mod("bedwars-only"));
        let mut disabled = a_mod("shared");
        disabled.enabled = false;
        child.mods.upsert(disabled);

        let resolved = resolve(&child, store(vec![parent])).unwrap();
        let names: Vec<String> = resolved.mods.iter().map(|m| m.id.to_string()).collect();
        assert!(names.contains(&"fathom-optimizations".to_owned()));
        assert!(names.contains(&"bedwars-only".to_owned()));
        assert!(
            !resolved
                .mods
                .iter()
                .find(|m| m.id.as_str() == "shared")
                .expect("shared survived")
                .enabled,
            "the child's decision about a shared mod must win"
        );
    }

    /// Game directories are never shared: two mod sets over one set of worlds
    /// is how saves get damaged.
    #[test]
    fn a_derived_profile_keeps_its_own_game_directory() {
        let parent = base();
        let child = derive(&parent, id("bedwars"), "Bedwars");
        let resolved = resolve(&child, store(vec![parent])).unwrap();
        assert_eq!(resolved.game_directory, child.game_directory);
    }

    #[test]
    fn a_chain_of_three_resolves_from_the_root() {
        let root = base();
        let mut middle = derive(&root, id("middle"), "Middle");
        middle.presentation.theme = Some("bg_3".to_owned());
        let leaf = derive(&middle, id("leaf"), "Leaf");

        let resolved = resolve(&leaf, store(vec![root, middle])).unwrap();
        assert_eq!(
            resolved.optimizations.preset,
            OptimizationPreset::Competitive
        );
        // The middle generation's override survives to the leaf.
        assert_eq!(resolved.presentation.theme.as_deref(), Some("bg_3"));
        assert_eq!(resolved.display_name, "Leaf");
    }

    #[test]
    fn a_missing_parent_is_named_rather_than_ignored() {
        let parent = base();
        let child = derive(&parent, id("orphan"), "Orphan");
        let error = resolve(&child, |_| None).unwrap_err();
        assert_eq!(error, InheritError::MissingParent(id("competitive")));
    }

    /// The file on disk is user-editable, so `a -> b -> a` has to terminate.
    #[test]
    fn a_cycle_terminates_instead_of_hanging() {
        let mut a = Profile::new(id("a"), "A");
        let mut b = Profile::new(id("b"), "B");
        a.parent = Some(id("b"));
        b.parent = Some(id("a"));
        let error = resolve(&a, store(vec![a.clone(), b])).unwrap_err();
        assert_eq!(error, InheritError::Unresolvable);
    }

    #[test]
    fn a_chain_deeper_than_the_limit_is_refused() {
        let mut profiles = vec![Profile::new(id("p0"), "P0")];
        for n in 1..=(MAX_DEPTH + 1) {
            let parent = profiles.last().cloned().unwrap();
            profiles.push(derive(&parent, id(&format!("p{n}")), format!("P{n}")));
        }
        let leaf = profiles.last().cloned().unwrap();
        assert_eq!(
            resolve(&leaf, store(profiles)).unwrap_err(),
            InheritError::Unresolvable
        );
    }

    #[test]
    fn a_profile_may_not_be_its_own_parent() {
        let profile = base();
        assert!(!may_parent(&profile.id, &profile, |_| None));
    }

    #[test]
    fn a_choice_that_would_close_a_cycle_is_refused() {
        let root = base();
        let child = derive(&root, id("child"), "Child");
        let everything = vec![root.clone(), child.clone()];
        // Making the child the root's parent would close root -> child -> root.
        assert!(!may_parent(&root.id, &child, store(everything.clone())));
        // The other direction is fine, and is what the child already does.
        assert!(may_parent(&child.id, &root, store(everything)));
    }

    /// `overlay` is written as a whole-struct construction so a new field is a
    /// compile error rather than a silent inherit. This checks the *behaviour*
    /// that guarantee exists to protect: nothing technical survives from the
    /// child.
    #[test]
    fn a_child_cannot_override_the_client_it_launches() {
        let parent = base();
        let mut child = derive(&parent, id("liar"), "Liar");
        child.version_id = "1.12.2".to_owned();
        child.loader.kind = Loader::Forge;
        child.memory.heap_mb = 16_384;
        child.optimizations.preset = OptimizationPreset::Custom;
        child.optimizations.rendering = BTreeMap::from([("x".to_owned(), true)]);

        let resolved = resolve(&child, store(vec![parent.clone()])).unwrap();
        assert_eq!(resolved.version_id, "1.8.9");
        assert_eq!(resolved.loader_kind(), Loader::LegacyFabric);
        assert_eq!(resolved.memory.heap_mb, 3072);
        assert_eq!(resolved.optimizations, parent.optimizations);
    }
}
