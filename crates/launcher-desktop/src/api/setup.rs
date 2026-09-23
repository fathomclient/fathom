//! Setting a profile up, as the interface sees it.
//!
//! # Why the options come from Rust
//!
//! Because every one of them is a fact the core owns: which Minecraft versions
//! this build supports, what "Fathom" implies on each of them, which components
//! exist and which of those can actually be installed today. A setup screen
//! that hard-coded "Fathom means Legacy Fabric" would be a second place for
//! that to be true, and the first time it stopped being true on some version,
//! only one of them would find out.
//!
//! So the frontend asks for [`SetupOptions`], renders it, and sends back a
//! [`Draft`] of the answers. It never constructs a profile, and it never
//! decides what a profile type means.

use serde::{Deserialize, Serialize};

use launcher_core::components;
use launcher_core::profiles::blueprint::{self, Blueprint, MemoryChoice};
use launcher_core::profiles::{JavaPolicy, Loader, ProfileKind};
use launcher_core::Launcher;

// ---------------------------------------------------------------------------
// What the screen offers
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupOptions {
    pub versions: Vec<VersionOption>,
    pub kinds: Vec<KindOption>,
    pub loaders: Vec<LoaderOption>,
    pub java: Vec<JavaOption>,
    /// What Automatic memory resolves to, so the screen can show the number
    /// instead of the word.
    pub recommended_memory_mb: u32,
    pub min_memory_mb: u32,
    pub max_memory_mb: u32,
    /// The answers the screen opens on.
    pub default_draft: Draft,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionOption {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KindOption {
    pub id: &'static str,
    pub label: &'static str,
    /// One sentence.
    pub summary: &'static str,
    /// What this choice means in practice, as a short list of lines. Built
    /// here rather than in Svelte because it is derived from the component
    /// registry, and a hard-coded copy would go stale silently.
    pub includes: Vec<String>,
    /// True for the one the product recommends.
    pub recommended: bool,
    /// Set when this choice cannot be offered on this build, with the reason.
    pub unavailable: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderOption {
    pub id: String,
    pub label: String,
    pub available: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaOption {
    pub id: &'static str,
    pub label: &'static str,
    pub detail: &'static str,
}

/// One optional component, as a row with a checkbox.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentOption {
    pub id: &'static str,
    pub name: &'static str,
    pub summary: &'static str,
    /// `core` / `recommended` / `optional`.
    pub tier: &'static str,
    /// Whether the launcher can install this itself.
    pub installable: bool,
    /// Why not, when it cannot.
    pub blocker: Option<&'static str>,
    /// Where the user gets it themselves, when that is the answer.
    pub where_to_get: Option<&'static str>,
    pub selected: bool,
}

fn tier_id(tier: components::Tier) -> &'static str {
    match tier {
        components::Tier::Core => "core",
        components::Tier::Recommended => "recommended",
        components::Tier::Optional => "optional",
    }
}

// ---------------------------------------------------------------------------
// What comes back
// ---------------------------------------------------------------------------

/// The answers, as the screen has them.
///
/// Deliberately mirrors [`Blueprint`] rather than [`launcher_core::profiles::Profile`]:
/// these are choices, not storage. `memoryMb` of `null` means Automatic, which
/// is why it is an option and not a number with a magic value.
// Both directions: it arrives as the screen's answers, and goes back out as
// `SetupOptions::default_draft` -- the same shape, so the same derive.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Draft {
    pub name: String,
    pub version: String,
    /// `vanilla` / `fathom` / `custom`.
    pub kind: String,
    /// Only read for `custom`.
    pub loader: Option<String>,
    pub components: Vec<String>,
    /// `None` is Automatic.
    pub memory_mb: Option<u32>,
    /// `automatic` / `managed` / `system` / `custom`.
    pub java: String,
    /// Required when `java` is `custom`.
    pub java_path: Option<String>,
    /// Set when deriving from another profile.
    pub parent: Option<String>,
}

impl Draft {
    /// Turn the screen's answers into something the core can validate.
    ///
    /// Unknown ids become an error rather than a default. A kind the frontend
    /// invented is a drift between the two sides, and quietly creating a Custom
    /// profile instead would hide it until somebody noticed their Fathom
    /// profile had no components.
    pub fn to_blueprint(&self) -> Result<Blueprint, String> {
        let kind = ProfileKind::from_id(&self.kind)
            .ok_or_else(|| format!("unknown profile type: {}", self.kind))?;

        let loader = match &self.loader {
            Some(id) => Some(
                crate::api::profiles::loader_from_id(id)
                    .ok_or_else(|| format!("unknown loader: {id}"))?,
            ),
            None => None,
        };

        let path = self.java_path.as_ref().map(std::path::PathBuf::from);
        let java = JavaPolicy::from_id(&self.java, path)
            .ok_or_else(|| format!("unknown Java policy: {}", self.java))?;

        let parent = match &self.parent {
            Some(id) => Some(crate::api::profiles::parse_id(id)?),
            None => None,
        };

        Ok(Blueprint {
            name: self.name.clone(),
            version_id: self.version.clone(),
            kind,
            loader,
            components: self.components.iter().cloned().collect(),
            memory: self
                .memory_mb
                .map_or(MemoryChoice::Automatic, MemoryChoice::Explicit),
            java,
            parent,
        })
    }
}

/// What came of a setup.
///
/// Carries `unavailable` rather than swallowing it: a Fathom profile whose HUD
/// component is not in this build is still a working profile, and the person
/// who just created it should be told once rather than discovering it later.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Created {
    pub id: String,
    pub name: String,
    /// One line about the components. Already suitable for display.
    pub summary: String,
    /// Components this build could not supply, each with its reason.
    pub unavailable: Vec<String>,
}

// ---------------------------------------------------------------------------
// Building the offer
// ---------------------------------------------------------------------------

pub fn options(core: &Launcher) -> SetupOptions {
    let default = Blueprint::recommended(String::new());

    SetupOptions {
        versions: blueprint::SUPPORTED_VERSIONS
            .iter()
            .map(|id| VersionOption {
                id: (*id).to_owned(),
                label: format!("Minecraft {id}"),
            })
            .collect(),
        kinds: ProfileKind::ALL
            .iter()
            .map(|kind| kind_option(*kind))
            .collect(),
        loaders: Loader::ALL
            .iter()
            .map(|loader| LoaderOption {
                id: crate::api::profiles::loader_id(*loader).to_owned(),
                label: loader.label().to_owned(),
                available: loader.is_implemented(),
            })
            .collect(),
        java: java_options(),
        recommended_memory_mb: blueprint::recommended_heap_mb(),
        min_memory_mb: blueprint::MIN_HEAP_MB,
        max_memory_mb: blueprint::MAX_HEAP_MB,
        default_draft: Draft {
            name: suggested_name(core, &default),
            version: default.version_id.clone(),
            kind: default.kind.id().to_owned(),
            loader: None,
            components: default.components.iter().cloned().collect(),
            memory_mb: None,
            // The launcher-wide default, so somebody who set "Fathom managed"
            // once is not asked again on every new profile.
            java: core.settings().java.id().to_owned(),
            java_path: core
                .settings()
                .java
                .explicit_path()
                .map(|path| path.display().to_string()),
            parent: None,
        },
    }
}

/// A name that is not already taken.
///
/// "Fathom 1.8.9", then "Fathom 1.8.9 2". The setup screen opens with the field
/// filled in, because an empty required field as the first thing you see is a
/// form asking you to work before it has done anything for you.
fn suggested_name(core: &Launcher, blueprint: &Blueprint) -> String {
    let base = format!("Fathom {}", blueprint.version_id);
    let taken: Vec<String> = core
        .profiles()
        .into_iter()
        .map(|profile| profile.display_name)
        .collect();
    if !taken.contains(&base) {
        return base;
    }
    (2..50)
        .map(|n| format!("{base} {n}"))
        .find(|candidate| !taken.contains(candidate))
        .unwrap_or(base)
}

fn kind_option(kind: ProfileKind) -> KindOption {
    let loader = kind.implied_loader().unwrap_or(Loader::LegacyFabric);

    let mut includes = Vec::new();
    match kind {
        ProfileKind::Vanilla => includes.push("Minecraft only".to_owned()),
        ProfileKind::Fathom => {
            includes.push(loader.label().to_owned());
            for component in components::required_for(kind, loader) {
                // Named with their status, so the screen never promises a
                // component this build does not have.
                match component.supply.blocker() {
                    Some(blocker) => includes.push(format!("{} ({blocker})", component.name)),
                    None => includes.push(component.name.to_owned()),
                }
            }
        }
        ProfileKind::Custom => includes.push("Your choice of loader and components".to_owned()),
    }

    let unavailable = kind
        .implied_loader()
        .filter(|loader| !loader.is_implemented())
        .map(|loader| format!("{} is not ready yet.", loader.label()));

    KindOption {
        id: kind.id(),
        label: kind.label(),
        summary: kind.summary(),
        includes,
        recommended: kind == ProfileKind::Fathom,
        unavailable,
    }
}

fn java_options() -> Vec<JavaOption> {
    [
        JavaPolicy::Automatic,
        JavaPolicy::Managed,
        JavaPolicy::System,
        JavaPolicy::Custom {
            path: std::path::PathBuf::new(),
        },
    ]
    .iter()
    .map(|policy| JavaOption {
        id: policy.id(),
        label: policy.label(),
        detail: policy.describe(),
    })
    .collect()
}

/// The optional components available under a loader, with the draft's choices
/// marked.
pub fn components_for(loader_id: &str, selected: &[String]) -> Vec<ComponentOption> {
    let Some(loader) = crate::api::profiles::loader_from_id(loader_id) else {
        return Vec::new();
    };
    components::optional_for(loader)
        .into_iter()
        .map(|component| ComponentOption {
            id: component.id,
            name: component.name,
            summary: component.summary,
            tier: tier_id(component.tier),
            // Two different things can put a component into a profile: the
            // launcher having the file already, and the launcher being able to
            // fetch it when asked. Both are "yes, this can be added" from the
            // setup screen's point of view, and the difference -- one costs a
            // download -- is what `blocker` says.
            installable: component.supply.is_installable() || component.supply.is_requestable(),
            blocker: component.supply.blocker(),
            where_to_get: component.supply.where_to_get(),
            selected: selected.iter().any(|id| id == component.id),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn draft() -> Draft {
        Draft {
            name: "Test".to_owned(),
            version: "1.8.9".to_owned(),
            kind: "fathom".to_owned(),
            loader: None,
            components: Vec::new(),
            memory_mb: None,
            java: "automatic".to_owned(),
            java_path: None,
            parent: None,
        }
    }

    #[test]
    fn a_draft_becomes_a_blueprint() {
        let blueprint = draft().to_blueprint().expect("a valid draft");
        assert_eq!(blueprint.kind, ProfileKind::Fathom);
        assert_eq!(blueprint.effective_loader(), Loader::LegacyFabric);
        assert!(blueprint.is_valid(), "{:?}", blueprint.problems());
    }

    /// A `null` memory field is Automatic, not zero. This is the one place a
    /// wrong reading would silently give somebody a 0 MB heap.
    #[test]
    fn absent_memory_means_automatic() {
        let blueprint = draft().to_blueprint().unwrap();
        assert_eq!(blueprint.memory, MemoryChoice::Automatic);
        assert_eq!(
            blueprint.memory.resolve().heap_mb,
            blueprint::recommended_heap_mb()
        );

        let mut explicit = draft();
        explicit.memory_mb = Some(4096);
        assert_eq!(
            explicit.to_blueprint().unwrap().memory,
            MemoryChoice::Explicit(4096)
        );
    }

    /// Drift between the two sides must be an error, not a silent downgrade to
    /// Custom -- which would produce a profile with no components and no
    /// explanation.
    #[test]
    fn an_unknown_kind_is_refused_rather_than_defaulted() {
        let mut bad = draft();
        bad.kind = "optimised".to_owned();
        let error = bad.to_blueprint().unwrap_err();
        assert!(error.contains("optimised"), "{error}");
    }

    #[test]
    fn an_unknown_loader_or_java_policy_is_refused() {
        let mut bad = draft();
        bad.kind = "custom".to_owned();
        bad.loader = Some("quilt".to_owned());
        assert!(bad.to_blueprint().is_err());

        let mut bad = draft();
        bad.java = "whatever".to_owned();
        assert!(bad.to_blueprint().is_err());
    }

    /// `custom` without a path is not a policy, it is a half-filled form.
    #[test]
    fn a_custom_java_policy_without_a_path_is_refused() {
        let mut bad = draft();
        bad.java = "custom".to_owned();
        bad.java_path = None;
        assert!(bad.to_blueprint().is_err());
    }

    /// The screen renders `kinds` directly, so every one of them has to carry
    /// enough to draw a card.
    #[test]
    fn every_kind_offers_a_label_a_sentence_and_a_list() {
        for kind in ProfileKind::ALL {
            let option = kind_option(kind);
            assert!(!option.label.is_empty());
            assert!(!option.summary.is_empty());
            assert!(!option.includes.is_empty(), "{} has no contents", option.id);
        }
    }

    /// Exactly one recommendation. Two would be no recommendation.
    #[test]
    fn fathom_is_the_one_recommended_setup() {
        let recommended: Vec<&'static str> = ProfileKind::ALL
            .iter()
            .map(|kind| kind_option(*kind))
            .filter(|option| option.recommended)
            .map(|option| option.id)
            .collect();
        assert_eq!(recommended, vec!["fathom"]);
    }

    /// The Fathom card lists what it installs, and marks anything this build
    /// cannot supply rather than promising it.
    #[test]
    fn the_fathom_card_names_its_components_and_their_status() {
        let option = kind_option(ProfileKind::Fathom);
        let joined = option.includes.join(" | ");
        assert!(joined.contains("Legacy Fabric"), "{joined}");
        assert!(joined.contains("Fathom Performance"), "{joined}");
        assert!(joined.contains("Fathom Core"), "{joined}");
        assert!(joined.contains("Fathom HUD"), "{joined}");

        // The HUD used to be listed as "Not in this build yet", and this test
        // asserted that it said so -- which was right while it was true. It
        // ships now, inside the same jar as Fathom Performance, so nothing on
        // this card may claim otherwise. A core component that is planned is a
        // profile type that does not deliver what it promises.
        assert!(
            !joined.contains("Not in this build yet"),
            "a core component is still unbuilt: {joined}"
        );
    }

    #[test]
    fn every_java_policy_is_offered_with_a_description() {
        let options = java_options();
        assert_eq!(options.len(), 4);
        for option in options {
            assert!(
                !option.detail.is_empty(),
                "{} has no description",
                option.id
            );
        }
    }

    /// OptiFine is offered as an optional component under a loader that can
    /// take it, and it is never marked installable.
    #[test]
    fn optifine_is_offered_as_something_the_user_supplies() {
        let offered = components_for("legacy-fabric", &[]);
        let optifine = offered
            .iter()
            .find(|option| option.id == "optifine")
            .expect("offered under Legacy Fabric");
        assert!(!optifine.installable);
        assert_eq!(
            optifine.where_to_get,
            Some("https://optifine.net/downloads")
        );
        assert!(optifine.blocker.is_some());
    }

    /// Vanilla loads no jars, so the component step has nothing to show.
    #[test]
    fn no_components_are_offered_for_vanilla() {
        assert!(components_for("vanilla", &[]).is_empty());
    }

    #[test]
    fn selection_is_reflected_back() {
        let selected = vec!["optifine".to_owned()];
        let offered = components_for("legacy-fabric", &selected);
        let optifine = offered.iter().find(|o| o.id == "optifine").unwrap();
        assert!(optifine.selected);
    }
}
