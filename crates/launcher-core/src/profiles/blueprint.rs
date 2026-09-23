//! Setting a profile up.
//!
//! # What this replaces
//!
//! `+ New profile` used to mean "a vanilla 1.8.9 profile called whatever you
//! typed", and everything that made it a Fathom profile came afterwards, by
//! hand, if you knew what to do. That put the least-informed person in the
//! product in charge of the decision with the most consequences.
//!
//! A blueprint is the whole answer to "what am I setting up", validated before
//! anything is written: version, kind, components, memory, Java. The store
//! turns it into a [`Profile`]; the component manager makes the profile match
//! what its kind promised.
//!
//! # Why the blueprint is not just a Profile
//!
//! Because the answers are not the same shape as the storage. "Fathom" is a
//! choice; `loader: LegacyFabric` is what that choice means today, on this
//! version. "Automatic" memory is a choice; `heap_mb: 2048` is what it resolves
//! to. Keeping the two apart means the setup screen asks product questions and
//! this file owns the translation -- rather than the setup screen having to
//! know that Fathom implies Legacy Fabric, which is exactly the knowledge the
//! user was being asked for before.

use std::collections::BTreeSet;

use crate::components;
use crate::profiles::model::{JavaPolicy, Loader, MemoryConfig, ProfileKind};
use crate::profiles::{OptimizationPreset, Profile, ProfileId};

/// Minecraft versions this build can set up.
///
/// A list rather than a constant, because the whole point of the exercise is
/// that adding 1.12.2 later is a line here plus a loader adapter, not a
/// redesign. Ordered newest-first, which is the order a version picker shows.
pub const SUPPORTED_VERSIONS: [&str; 1] = ["1.8.9"];

pub fn supports_version(version: &str) -> bool {
    SUPPORTED_VERSIONS.contains(&version)
}

/// How much heap to give the game.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MemoryChoice {
    /// Let Fathom decide. See [`recommended_heap_mb`].
    #[default]
    Automatic,
    /// A number the user set.
    Explicit(u32),
}

impl MemoryChoice {
    pub fn resolve(self) -> MemoryConfig {
        MemoryConfig {
            heap_mb: match self {
                MemoryChoice::Automatic => recommended_heap_mb(),
                MemoryChoice::Explicit(mb) => mb.clamp(MIN_HEAP_MB, MAX_HEAP_MB),
            },
        }
    }
}

pub const MIN_HEAP_MB: u32 = 512;
pub const MAX_HEAP_MB: u32 = 16_384;

/// What "Automatic" means.
///
/// 2 GB, deliberately, and not a fraction of installed RAM.
/// `PERFORMANCE_MATRIX.md` §F is explicit that the community's "give it 8-16
/// GB" advice makes garbage-collection pauses *longer*: a bigger heap is a
/// bigger thing to collect, and 1.8.9 does not have the working set to justify
/// it. A machine with 32 GB does not want 16 of them here.
///
/// A function rather than a constant so that refining this later -- with a
/// measurement, not a guess -- is one edit. Nothing stores "automatic": the
/// setup screen resolves it to a number and the profile keeps that number, so
/// changing this never silently moves somebody's existing heap.
pub fn recommended_heap_mb() -> u32 {
    2048
}

/// Everything the setup screen collected.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Blueprint {
    pub name: String,
    pub version_id: String,
    pub kind: ProfileKind,
    /// Only meaningful for [`ProfileKind::Custom`]. The other kinds imply their
    /// loader, and a value here is ignored rather than allowed to contradict
    /// them.
    pub loader: Option<Loader>,
    /// Optional component ids the user switched on.
    pub components: BTreeSet<String>,
    pub memory: MemoryChoice,
    pub java: JavaPolicy,
    /// Set when this profile derives from another.
    pub parent: Option<ProfileId>,
}

impl Blueprint {
    /// A blueprint with the recommended answer to every question.
    ///
    /// This is what the setup screen opens on, and it is deliberately the
    /// Fathom profile: the whole milestone is that Fathom's own experience,
    /// not bare Vanilla, is the natural one.
    pub fn recommended(name: impl Into<String>) -> Self {
        let kind = ProfileKind::Fathom;
        let loader = kind.implied_loader().unwrap_or_default();
        Self {
            name: name.into(),
            version_id: SUPPORTED_VERSIONS
                .first()
                .copied()
                .unwrap_or("1.8.9")
                .to_owned(),
            kind,
            loader: None,
            // Recommended components start on. Optional ones start off.
            components: components::optional_for(loader)
                .into_iter()
                .filter(|component| component.tier == components::Tier::Recommended)
                .map(|component| component.id.to_owned())
                .collect(),
            memory: MemoryChoice::Automatic,
            java: JavaPolicy::Automatic,
            parent: None,
        }
    }

    /// The loader this blueprint will actually use.
    ///
    /// The kind wins over `loader` for every kind that implies one, so a
    /// Fathom profile cannot be created on Vanilla by sending a stale field.
    pub fn effective_loader(&self) -> Loader {
        self.kind
            .implied_loader()
            .or(self.loader)
            .unwrap_or_default()
    }

    /// Everything wrong with this blueprint, in the order a form shows it.
    ///
    /// Returns sentences, not codes: every one of these is displayed verbatim,
    /// and a caller that has to map codes to text is a second place for the
    /// wording to drift.
    pub fn problems(&self) -> Vec<String> {
        let mut problems = Vec::new();

        if self.name.trim().is_empty() {
            problems.push("Give this profile a name.".to_owned());
        } else if self.name.chars().count() > 60 {
            problems.push("That name is too long.".to_owned());
        }

        if !supports_version(&self.version_id) {
            problems.push(format!("Fathom does not support {} yet.", self.version_id));
        }

        let loader = self.effective_loader();
        if !loader.is_implemented() {
            problems.push(format!("{} is not ready yet.", loader.label()));
        }

        // A component offered under a loader that cannot load it is a setting
        // that would silently do nothing.
        for id in &self.components {
            match components::find(id) {
                None => problems.push(format!("{id} is not a component this build knows about.")),
                Some(component) if !component.supports(loader) => problems.push(format!(
                    "{} does not work with {}.",
                    component.name,
                    loader.label()
                )),
                Some(_) => {}
            }
        }

        if let MemoryChoice::Explicit(mb) = self.memory {
            if !(MIN_HEAP_MB..=MAX_HEAP_MB).contains(&mb) {
                problems.push(format!(
                    "Memory must be between {MIN_HEAP_MB} MB and {MAX_HEAP_MB} MB."
                ));
            }
        }

        if let JavaPolicy::Custom { path } = &self.java {
            if path.as_os_str().is_empty() {
                problems.push("Choose the Java executable to use.".to_owned());
            }
        }

        problems
    }

    pub fn is_valid(&self) -> bool {
        self.problems().is_empty()
    }

    /// Turn this into a profile.
    ///
    /// Does not touch the disk and installs nothing: the caller saves it and
    /// then runs [`crate::components::ensure`], which is what actually puts
    /// Fathom's jars in place. Split that way so this stays a pure function
    /// that a test can check without a filesystem.
    pub fn build(&self, id: ProfileId) -> Profile {
        let loader = self.effective_loader();
        let mut profile = Profile::new(id, self.name.trim());
        profile.kind = self.kind;
        profile.parent.clone_from(&self.parent);
        profile.version_id.clone_from(&self.version_id);
        profile.loader.kind = loader;
        // Left unset deliberately: `services::ensure_loader` picks the newest
        // stable build at first launch and writes it back, which pins it from
        // then on. Baking a number in here ships one that is stale next week.
        profile.loader.version = None;
        profile.java = self.java.clone();
        profile.memory = self.memory.resolve();
        profile.components.clone_from(&self.components);

        // A Fathom profile arrives tuned. That is the difference between the
        // Fathom experience and a Custom profile that happens to have the same
        // loader: somebody already made these decisions.
        profile.optimizations.preset = match self.kind {
            ProfileKind::Fathom => OptimizationPreset::Balanced,
            // Vanilla has no module, so a preset would describe switches that
            // cannot be read. Custom means the user decides.
            ProfileKind::Vanilla | ProfileKind::Custom => OptimizationPreset::Custom,
        };
        if self.kind == ProfileKind::Fathom {
            // Expanded, not just named: the bridge file the game reads is the
            // switch map, and a preset nobody expanded reaches the game as
            // nothing switched on.
            profile.optimizations.rendering = OptimizationPreset::Balanced.switches();
        }

        profile
    }

    /// A suggested profile id, derived from the name.
    ///
    /// The id is never shown -- see the interface rule about not exposing
    /// internal ids -- but it is the directory name, so deriving it from the
    /// name means a user browsing their own data directory recognises what they
    /// are looking at.
    pub fn suggested_id(&self) -> String {
        let mut slug = String::new();
        let mut last_was_dash = true;
        for ch in self.name.trim().chars() {
            if ch.is_ascii_alphanumeric() {
                slug.push(ch.to_ascii_lowercase());
                last_was_dash = false;
            } else if !last_was_dash {
                slug.push('-');
                last_was_dash = true;
            }
        }
        let slug = slug.trim_matches('-').to_owned();
        if slug.is_empty() {
            "profile".to_owned()
        } else {
            slug.chars().take(40).collect()
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn the_recommended_setup_is_a_fathom_profile_not_a_vanilla_one() {
        let blueprint = Blueprint::recommended("Fathom 1.8.9");
        assert_eq!(blueprint.kind, ProfileKind::Fathom);
        assert_eq!(blueprint.effective_loader(), Loader::LegacyFabric);
        assert!(blueprint.is_valid(), "{:?}", blueprint.problems());
    }

    /// The kind decides the loader. A stale `loader` field from a form that was
    /// switched from Custom to Fathom must not produce a Fathom profile on
    /// Vanilla, which would be a profile that cannot load its own components.
    #[test]
    fn the_kind_overrules_a_contradictory_loader() {
        let mut blueprint = Blueprint::recommended("X");
        blueprint.loader = Some(Loader::Vanilla);
        assert_eq!(blueprint.effective_loader(), Loader::LegacyFabric);

        blueprint.kind = ProfileKind::Vanilla;
        blueprint.loader = Some(Loader::LegacyFabric);
        assert_eq!(blueprint.effective_loader(), Loader::Vanilla);
    }

    #[test]
    fn a_custom_blueprint_uses_the_loader_it_was_given() {
        let mut blueprint = Blueprint::recommended("X");
        blueprint.kind = ProfileKind::Custom;
        blueprint.components.clear();
        blueprint.loader = Some(Loader::LegacyFabric);
        assert_eq!(blueprint.effective_loader(), Loader::LegacyFabric);
    }

    #[test]
    fn a_fathom_profile_is_built_tuned_and_a_custom_one_is_not() {
        let fathom = Blueprint::recommended("F").build(ProfileId::new("f").unwrap());
        assert_eq!(fathom.kind, ProfileKind::Fathom);
        assert_eq!(fathom.loader_kind(), Loader::LegacyFabric);
        assert_eq!(fathom.optimizations.preset, OptimizationPreset::Balanced);
        assert!(!fathom.optimizations.rendering.is_empty());
        assert!(fathom.owns_components());

        let mut custom = Blueprint::recommended("C");
        custom.kind = ProfileKind::Custom;
        custom.components.clear();
        custom.loader = Some(Loader::LegacyFabric);
        let custom = custom.build(ProfileId::new("c").unwrap());
        assert_eq!(custom.optimizations.preset, OptimizationPreset::Custom);
        assert!(!custom.owns_components());
    }

    #[test]
    fn a_vanilla_profile_has_no_loader_and_no_components() {
        let mut blueprint = Blueprint::recommended("V");
        blueprint.kind = ProfileKind::Vanilla;
        blueprint.components.clear();
        let profile = blueprint.build(ProfileId::new("v").unwrap());
        assert_eq!(profile.loader_kind(), Loader::Vanilla);
        assert!(profile.components.is_empty());
        assert!(
            components::required_for(profile.kind, profile.loader_kind()).is_empty(),
            "a vanilla profile must require nothing"
        );
    }

    /// The loader version is left for the launcher to pin at first launch.
    /// Baking one in ships a number that is stale the week after release.
    #[test]
    fn the_loader_version_is_not_guessed_at_setup_time() {
        let profile = Blueprint::recommended("F").build(ProfileId::new("f").unwrap());
        assert_eq!(profile.loader.version, None);
    }

    #[test]
    fn an_unnamed_or_unsupported_blueprint_says_what_is_wrong() {
        let mut blueprint = Blueprint::recommended("   ");
        assert!(blueprint.problems().iter().any(|p| p.contains("name")));

        blueprint.name = "Fine".to_owned();
        blueprint.version_id = "1.21.4".to_owned();
        assert!(blueprint.problems().iter().any(|p| p.contains("1.21.4")));
    }

    #[test]
    fn an_unimplemented_loader_is_refused_with_its_name() {
        let mut blueprint = Blueprint::recommended("F");
        blueprint.kind = ProfileKind::Custom;
        blueprint.components.clear();
        blueprint.loader = Some(Loader::Forge);
        assert!(blueprint.problems().iter().any(|p| p.contains("Forge")));
    }

    /// A component offered under a loader that cannot load it would be a
    /// setting that silently does nothing.
    #[test]
    fn a_component_that_cannot_work_here_is_refused() {
        let mut blueprint = Blueprint::recommended("V");
        blueprint.kind = ProfileKind::Vanilla;
        blueprint.components = ["optifine".to_owned()].into_iter().collect();
        assert!(
            blueprint.problems().iter().any(|p| p.contains("OptiFine")),
            "{:?}",
            blueprint.problems()
        );
    }

    #[test]
    fn an_unknown_component_is_refused_rather_than_ignored() {
        let mut blueprint = Blueprint::recommended("F");
        blueprint.components.insert("wishful-thinking".to_owned());
        assert!(!blueprint.is_valid());
    }

    #[test]
    fn automatic_memory_follows_the_research_not_the_folklore() {
        assert_eq!(MemoryChoice::Automatic.resolve().heap_mb, 2048);
        assert!(recommended_heap_mb() < 4096);
    }

    #[test]
    fn an_absurd_explicit_heap_is_clamped_rather_than_stored() {
        assert_eq!(
            MemoryChoice::Explicit(99_999).resolve().heap_mb,
            MAX_HEAP_MB
        );
        assert_eq!(MemoryChoice::Explicit(1).resolve().heap_mb, MIN_HEAP_MB);
    }

    #[test]
    fn a_custom_java_policy_needs_a_path() {
        let mut blueprint = Blueprint::recommended("F");
        blueprint.java = JavaPolicy::Custom {
            path: std::path::PathBuf::new(),
        };
        assert!(blueprint.problems().iter().any(|p| p.contains("Java")));
    }

    #[test]
    fn suggested_ids_are_readable_and_always_usable() {
        let cases = [
            ("Fathom 1.8.9", "fathom-1-8-9"),
            ("  Bedwars!!  ", "bedwars"),
            ("////", "profile"),
            ("", "profile"),
        ];
        for (name, expected) in cases {
            let mut blueprint = Blueprint::recommended(name);
            blueprint.name = name.to_owned();
            let id = blueprint.suggested_id();
            assert_eq!(id, expected, "{name:?}");
            assert!(ProfileId::new(&id).is_ok(), "{id} is not a usable id");
        }
    }

    /// The name is trimmed on the way in, so a profile is never called
    /// "  Bedwars  ".
    #[test]
    fn the_stored_name_is_trimmed() {
        let mut blueprint = Blueprint::recommended("x");
        blueprint.name = "  Bedwars  ".to_owned();
        assert_eq!(
            blueprint.build(ProfileId::new("b").unwrap()).display_name,
            "Bedwars"
        );
    }
}
