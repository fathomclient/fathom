//! Working out what will actually load, and what will go wrong.
//!
//! The point of this module is that Minecraft never crashes because two mods
//! fought. It says *"Fathom Optimizations requires Fabric API"* or *"`OptiFine`
//! and Fathom Optimizations both patch the renderer"* before anything starts,
//! rather than letting the user find out from a stack trace.
//!
//! # What resolution is, and is not
//!
//! It is a check over a fixed set: the mods this profile has, against the
//! Minecraft version and loader it is configured for. It is **not** a solver
//! that goes looking for versions to satisfy constraints. This ecosystem does
//! not have reliable version metadata to solve over, and a solver that is
//! sometimes right would produce failures harder to explain than the ones it
//! prevented.
//!
//! # Severity, not a boolean
//!
//! A missing hard dependency stops a launch. Two mods patching the same renderer
//! is worth saying out loud but is usually survivable. Reporting both as
//! "problem" would leave the user unable to tell which one they have to act on.

use std::collections::{BTreeMap, BTreeSet};

use super::definition::{ConflictKind, ModDefinition, ModId};
use crate::profiles::Loader;

/// How badly something is wrong.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    /// Worth saying; the launch will work.
    Advisory,
    /// The launch will work, but something will misbehave.
    Warning,
    /// The launch must not proceed.
    Blocking,
}

impl Severity {
    pub fn label(self) -> &'static str {
        match self {
            Severity::Advisory => "note",
            Severity::Warning => "warning",
            Severity::Blocking => "blocks launch",
        }
    }
}

/// One thing wrong with a mod set.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Problem {
    /// A mod needs another that is not present.
    MissingDependency {
        needed_by: ModId,
        missing: ModId,
        optional: bool,
        reason: String,
    },
    /// A dependency is present but older than required.
    DependencyTooOld {
        needed_by: ModId,
        dependency: ModId,
        required: String,
        found: String,
    },
    /// Two mods cannot both be loaded.
    Conflict {
        left: ModId,
        right: ModId,
        kind: ConflictKind,
        detail: String,
    },
    /// A dependency cycle. Reported rather than looped over.
    DependencyCycle { cycle: Vec<ModId> },
    /// The mod does not support this Minecraft version.
    WrongMinecraftVersion {
        id: ModId,
        wanted: String,
        supports: String,
    },
    /// The mod is not for this loader.
    WrongLoader {
        id: ModId,
        wanted: Loader,
        supports: Vec<Loader>,
    },
    /// The profile's loader cannot load mods at all.
    LoaderLoadsNoMods { loader: Loader, count: usize },
    /// Two enabled entries claim the same mod id.
    DuplicateMod { id: ModId, file_names: Vec<String> },
    /// A mod we know nothing about is enabled. Not an error — the user may
    /// supply whatever they like — but they should know we cannot vouch for it.
    UnknownMod { id: ModId, file_name: String },
    /// A Fathom profile is missing one of the components that make it a Fathom
    /// profile.
    ///
    /// # Why the severity depends on `repairable`
    ///
    /// Because the two cases are different questions to a user. A profile whose
    /// module was deleted is broken and fixable, and the launcher can put it
    /// back — so it blocks, and the fix is one action away. A build that does
    /// not ship the jar at all cannot supply it however loudly it complains,
    /// and blocking a launch over something nobody present can fix would turn a
    /// developer build into one that refuses to start.
    ///
    /// Both say the same true thing: this profile calls itself Fathom and is
    /// not currently getting Fathom's game-side features.
    FirstPartyModuleMissing {
        component: String,
        name: String,
        reason: String,
        repairable: bool,
    },
}

impl Problem {
    /// One line per variant, deliberately, even where two share an answer:
    /// this is the table that says how seriously to take each finding, and
    /// merging arms would make it unreadable as a table. The order is also
    /// load-bearing — `optional: true` has to precede the general
    /// `MissingDependency` arm.
    #[allow(clippy::match_same_arms)]
    pub fn severity(&self) -> Severity {
        match self {
            // A missing optional dependency is a note; a missing required one
            // is the difference between launching and a crash on startup.
            Problem::MissingDependency { optional: true, .. } => Severity::Advisory,
            Problem::MissingDependency { .. } => Severity::Blocking,
            Problem::DependencyTooOld { .. } => Severity::Warning,
            Problem::Conflict { kind, .. } => {
                if kind.is_blocking() {
                    Severity::Blocking
                } else {
                    Severity::Warning
                }
            }
            Problem::DependencyCycle { .. } => Severity::Blocking,
            Problem::WrongMinecraftVersion { .. } | Problem::WrongLoader { .. } => {
                Severity::Blocking
            }
            Problem::LoaderLoadsNoMods { .. } => Severity::Warning,
            Problem::DuplicateMod { .. } => Severity::Blocking,
            Problem::UnknownMod { .. } => Severity::Advisory,
            Problem::FirstPartyModuleMissing { repairable, .. } => {
                if *repairable {
                    Severity::Blocking
                } else {
                    Severity::Warning
                }
            }
        }
    }

    /// A sentence a user can act on.
    ///
    /// One arm per variant and no helpers, which puts it over the line limit.
    /// That is the right trade for this function specifically: it is the table
    /// of everything the launcher can say about a mod set, and it is read as a
    /// table. Splitting it into three functions would make each one shorter and
    /// the set harder to review for the thing that actually matters -- whether
    /// every message is a sentence somebody can act on.
    #[allow(clippy::too_many_lines)]
    pub fn message(&self, names: &NameLookup) -> String {
        match self {
            Problem::MissingDependency {
                needed_by,
                missing,
                optional,
                reason,
            } => {
                let base = format!(
                    "{} {} {}",
                    names.of(needed_by),
                    if *optional {
                        "works better with"
                    } else {
                        "requires"
                    },
                    names.of(missing)
                );
                if reason.is_empty() {
                    format!("{base}, which is not installed.")
                } else {
                    format!("{base}, which is not installed. {reason}")
                }
            }
            Problem::DependencyTooOld {
                needed_by,
                dependency,
                required,
                found,
            } => format!(
                "{} needs {} {required} or newer; {found} is installed.",
                names.of(needed_by),
                names.of(dependency)
            ),
            Problem::Conflict {
                left,
                right,
                kind,
                detail,
            } => {
                let verb = match kind {
                    ConflictKind::Incompatible => "cannot be used with",
                    ConflictKind::Supersedes => "replaces",
                    ConflictKind::OverlappingOptimization => "overlaps with",
                    ConflictKind::KnownBad => "is known to break with",
                };
                format!("{} {verb} {}. {detail}", names.of(left), names.of(right))
            }
            Problem::DependencyCycle { cycle } => format!(
                "These mods depend on each other in a loop, so no load order exists: {}.",
                cycle
                    .iter()
                    .map(|id| names.of(id))
                    .collect::<Vec<_>>()
                    .join(" -> ")
            ),
            Problem::WrongMinecraftVersion {
                id,
                wanted,
                supports,
            } => format!(
                "{} does not support Minecraft {wanted}; it supports {supports}.",
                names.of(id)
            ),
            Problem::WrongLoader {
                id,
                wanted,
                supports,
            } => format!(
                "{} is not a {} mod; it is built for {}.",
                names.of(id),
                wanted.label(),
                supports
                    .iter()
                    .map(|l| l.label())
                    .collect::<Vec<_>>()
                    .join(" or ")
            ),
            Problem::LoaderLoadsNoMods { loader, count } => format!(
                "{count} mod(s) are enabled, but {} loads no mods. Nothing in the mods \
                 folder will run. Switch this profile to a loader.",
                loader.label()
            ),
            Problem::DuplicateMod { id, file_names } => format!(
                "Two files both claim to be {}: {}. Only one can be loaded.",
                names.of(id),
                file_names.join(" and ")
            ),
            Problem::UnknownMod { id, file_name } => format!(
                "{file_name} was added by you and is not in Fathom's catalogue, so we cannot \
                 check its licence, its dependencies, or what it conflicts with. It will be \
                 loaded as-is. ({id})"
            ),
            Problem::FirstPartyModuleMissing {
                name,
                reason,
                repairable,
                ..
            } => {
                if *repairable {
                    format!(
                        "{name} is missing, so this Fathom profile would start without Fathom's \
                         in-game features. Fathom can put it back."
                    )
                } else {
                    format!("{name} is missing and cannot be installed: {reason}")
                }
            }
        }
    }
}

/// Turns ids into display names.
///
/// A separate type so `Problem::message` does not need the whole definition
/// map, and so a problem about a mod that has since been removed still renders
/// something useful rather than panicking.
#[derive(Debug, Default)]
pub struct NameLookup(BTreeMap<ModId, String>);

impl NameLookup {
    pub fn from_definitions(definitions: &[ModDefinition]) -> Self {
        Self(
            definitions
                .iter()
                .map(|d| (d.id.clone(), d.name.clone()))
                .collect(),
        )
    }

    pub fn of(&self, id: &ModId) -> String {
        self.0.get(id).cloned().unwrap_or_else(|| id.to_string())
    }
}

/// One mod that will be loaded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedMod {
    pub id: ModId,
    pub file_name: String,
    pub sha1: String,
}

/// What resolution concluded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Resolution {
    /// The mods that will be in the effective mods directory, in id order so
    /// materialization is deterministic.
    pub effective: Vec<ResolvedMod>,
    pub problems: Vec<Problem>,
}

impl Resolution {
    /// Whether the game can be started with this set.
    pub fn is_launchable(&self) -> bool {
        !self
            .problems
            .iter()
            .any(|p| p.severity() == Severity::Blocking)
    }

    pub fn blocking(&self) -> impl Iterator<Item = &Problem> {
        self.problems
            .iter()
            .filter(|p| p.severity() == Severity::Blocking)
    }

    pub fn worst_severity(&self) -> Option<Severity> {
        self.problems.iter().map(Problem::severity).max()
    }
}

/// What resolution needs to know about one candidate mod.
#[derive(Debug, Clone)]
pub struct Candidate {
    pub id: ModId,
    pub file_name: String,
    pub sha1: String,
    pub enabled: bool,
    /// What we know about it. `None` for a jar the user supplied that is not in
    /// the catalogue — which is allowed, and reported as an advisory.
    pub definition: Option<ModDefinition>,
}

/// Check a mod set against a profile's Minecraft version and loader.
///
/// Deterministic: the same input produces the same output, including the order
/// of `effective` and of `problems`.
pub fn resolve(candidates: &[Candidate], minecraft_version: &str, loader: Loader) -> Resolution {
    let mut problems = Vec::new();

    // Only enabled mods participate. A disabled mod cannot conflict with
    // anything, and reporting problems about mods the user has already turned
    // off is noise.
    let enabled: Vec<&Candidate> = candidates.iter().filter(|c| c.enabled).collect();

    // -- the loader has to load mods at all --------------------------------
    if !loader.supports_mods() && !enabled.is_empty() {
        problems.push(Problem::LoaderLoadsNoMods {
            loader,
            count: enabled.len(),
        });
    }

    // -- duplicates --------------------------------------------------------
    let mut by_id: BTreeMap<&ModId, Vec<&Candidate>> = BTreeMap::new();
    for candidate in &enabled {
        by_id.entry(&candidate.id).or_default().push(candidate);
    }
    for (id, group) in &by_id {
        if group.len() > 1 {
            problems.push(Problem::DuplicateMod {
                id: (*id).clone(),
                file_names: group.iter().map(|c| c.file_name.clone()).collect(),
            });
        }
    }

    let present: BTreeSet<&ModId> = by_id.keys().copied().collect();

    // -- per-mod checks ----------------------------------------------------
    for candidate in &enabled {
        let Some(definition) = &candidate.definition else {
            problems.push(Problem::UnknownMod {
                id: candidate.id.clone(),
                file_name: candidate.file_name.clone(),
            });
            continue;
        };

        if !definition.minecraft.accepts(minecraft_version) {
            problems.push(Problem::WrongMinecraftVersion {
                id: candidate.id.clone(),
                wanted: minecraft_version.to_owned(),
                supports: definition.minecraft.describe(),
            });
        }

        // OptiFine is a Forge mod, but OptiFabric loads it on Legacy Fabric --
        // which is how Fathom's own OptiFine profiles run. Reporting it as the
        // wrong loader there blocked a launch that works. Without OptiFabric it
        // genuinely cannot load, and the useful sentence is what it needs.
        if loader == Loader::LegacyFabric && candidate.id.as_str() == "optifine" {
            let bridge = ModId::new("optifabric")
                .unwrap_or_else(|e| unreachable!("a literal id is valid: {e}"));
            if !present.contains(&bridge) {
                problems.push(Problem::MissingDependency {
                    needed_by: candidate.id.clone(),
                    missing: bridge,
                    optional: false,
                    reason: "OptiFabric is what loads OptiFine on Legacy Fabric.".into(),
                });
            }
        } else if loader.supports_mods() && !definition.loaders.contains(&loader) {
            // Only meaningful when the loader loads mods at all; otherwise every
            // mod would also report "not a Vanilla mod", which is true but is the
            // same finding said twice.
            problems.push(Problem::WrongLoader {
                id: candidate.id.clone(),
                wanted: loader,
                supports: definition.loaders.iter().copied().collect(),
            });
        }

        for dependency in &definition.dependencies {
            if present.contains(&dependency.id) {
                continue;
            }
            problems.push(Problem::MissingDependency {
                needed_by: candidate.id.clone(),
                missing: dependency.id.clone(),
                optional: dependency.optional,
                reason: dependency.reason.clone(),
            });
        }
    }

    // -- conflicts ---------------------------------------------------------
    //
    // Recorded once per unordered pair. A conflict declared from both sides is
    // one conflict, and telling the user about it twice would suggest two
    // problems.
    let mut seen: BTreeSet<(ModId, ModId)> = BTreeSet::new();
    for candidate in &enabled {
        let Some(definition) = &candidate.definition else {
            continue;
        };
        for conflict in &definition.conflicts {
            if !present.contains(&conflict.id) {
                continue;
            }
            let pair = if candidate.id <= conflict.id {
                (candidate.id.clone(), conflict.id.clone())
            } else {
                (conflict.id.clone(), candidate.id.clone())
            };
            if !seen.insert(pair) {
                continue;
            }
            problems.push(Problem::Conflict {
                left: candidate.id.clone(),
                right: conflict.id.clone(),
                kind: conflict.kind,
                detail: conflict.detail.clone(),
            });
        }
    }

    // -- cycles ------------------------------------------------------------
    for cycle in find_cycles(&enabled) {
        problems.push(Problem::DependencyCycle { cycle });
    }

    // -- what will load ----------------------------------------------------
    //
    // Everything enabled, deduplicated by id and sorted, so the mods directory
    // is a function of the configuration and nothing else.
    let mut effective: Vec<ResolvedMod> = by_id
        .iter()
        .filter_map(|(id, group)| {
            group.first().map(|c| ResolvedMod {
                id: (*id).clone(),
                file_name: c.file_name.clone(),
                sha1: c.sha1.clone(),
            })
        })
        .collect();
    effective.sort_by(|a, b| a.id.cmp(&b.id));

    // Stable problem order, so two runs over the same input produce identical
    // output and a diff of a report means something.
    sort_problems(&mut problems);

    Resolution {
        effective,
        problems,
    }
}

/// A stable secondary sort key. Severity first, then this.
/// Put a problem list into the order the interface reads it in: worst first,
/// then a stable tiebreak so two runs never disagree.
///
/// Public because problems now come from two places -- the mod resolver and the
/// component check -- and a list assembled from both has to be sorted once,
/// after both have contributed, rather than each sorting its own half.
pub fn sort_problems(problems: &mut [Problem]) {
    problems.sort_by(|left, right| {
        right
            .severity()
            .cmp(&left.severity())
            .then_with(|| problem_order_key(left).cmp(&problem_order_key(right)))
    });
}

fn problem_order_key(problem: &Problem) -> String {
    match problem {
        Problem::MissingDependency {
            needed_by, missing, ..
        } => format!("1:{needed_by}:{missing}"),
        Problem::DependencyTooOld {
            needed_by,
            dependency,
            ..
        } => format!("2:{needed_by}:{dependency}"),
        Problem::Conflict { left, right, .. } => format!("3:{left}:{right}"),
        Problem::DependencyCycle { cycle } => format!(
            "4:{}",
            cycle
                .iter()
                .map(ModId::to_string)
                .collect::<Vec<_>>()
                .join(",")
        ),
        Problem::WrongMinecraftVersion { id, .. } => format!("5:{id}"),
        Problem::WrongLoader { id, .. } => format!("6:{id}"),
        Problem::LoaderLoadsNoMods { loader, .. } => format!("7:{loader:?}"),
        Problem::DuplicateMod { id, .. } => format!("8:{id}"),
        Problem::UnknownMod { id, .. } => format!("9:{id}"),
        Problem::FirstPartyModuleMissing { component, .. } => format!("0:{component}"),
    }
}

/// Which Fathom components a profile calls for and does not have.
///
/// # Why this is not part of [`resolve`]
///
/// Because it is a different question, asked of different data. [`resolve`]
/// looks at a set of jars and asks whether they can work together; this looks
/// at a profile's declared *kind* and asks whether the set contains what that
/// kind promises. Folding it in would mean the mod resolver taking a profile
/// and a filesystem, and it currently takes neither -- which is what makes it a
/// pure function with straightforward tests.
///
/// `available` answers "could this build install it if asked", which is what
/// separates a broken profile from a build that never shipped the jar. See
/// [`Problem::FirstPartyModuleMissing`].
pub fn missing_components(
    required: &[(&str, &str, &str)],
    present: &BTreeSet<String>,
    available: &dyn Fn(&str) -> bool,
) -> Vec<Problem> {
    let mut problems = Vec::new();
    for (component, name, reason) in required {
        if present.contains(*component) {
            continue;
        }
        problems.push(Problem::FirstPartyModuleMissing {
            component: (*component).to_owned(),
            name: (*name).to_owned(),
            reason: (*reason).to_owned(),
            repairable: available(component),
        });
    }
    problems
}

/// Find dependency cycles among the present mods.
///
/// Iterative depth-first search with an explicit stack. Recursion would be
/// simpler to read, but the input is a list from a file a user can edit, and a
/// deeply nested graph should produce a report rather than a stack overflow.
///
/// Each cycle is reported once, normalised so it starts at its smallest id —
/// otherwise the same loop found from three entry points would look like three
/// problems.
fn find_cycles(candidates: &[&Candidate]) -> Vec<Vec<ModId>> {
    /// Depth-first colouring: `Open` is on the current path, `Done` is fully
    /// explored. A back edge to an `Open` node is a cycle.
    #[derive(Clone, Copy, PartialEq)]
    enum Mark {
        Open,
        Done,
    }

    let edges: BTreeMap<ModId, Vec<ModId>> = candidates
        .iter()
        .filter_map(|c| {
            let definition = c.definition.as_ref()?;
            let present: BTreeSet<&ModId> = candidates.iter().map(|c| &c.id).collect();
            Some((
                c.id.clone(),
                definition
                    .dependencies
                    .iter()
                    .filter(|d| present.contains(&d.id))
                    .map(|d| d.id.clone())
                    .collect(),
            ))
        })
        .collect();

    let mut marks: BTreeMap<ModId, Mark> = BTreeMap::new();
    let mut found: BTreeSet<Vec<ModId>> = BTreeSet::new();

    for start in edges.keys() {
        if marks.contains_key(start) {
            continue;
        }

        // (node, index of the next child to visit)
        let mut stack: Vec<(ModId, usize)> = vec![(start.clone(), 0)];
        let mut path: Vec<ModId> = vec![start.clone()];
        marks.insert(start.clone(), Mark::Open);

        while let Some((node, child_index)) = stack.pop() {
            let children = edges.get(&node).map(Vec::as_slice).unwrap_or_default();

            if child_index >= children.len() {
                marks.insert(node.clone(), Mark::Done);
                path.pop();
                continue;
            }

            stack.push((node.clone(), child_index + 1));
            let Some(child) = children.get(child_index) else {
                continue;
            };

            match marks.get(child) {
                Some(Mark::Done) => {}
                Some(Mark::Open) => {
                    // Back edge: everything from `child` onwards in `path` is
                    // the cycle.
                    if let Some(at) = path.iter().position(|n| n == child) {
                        if let Some(loop_part) = path.get(at..) {
                            found.insert(normalise_cycle(loop_part));
                        }
                    }
                }
                None => {
                    marks.insert(child.clone(), Mark::Open);
                    path.push(child.clone());
                    stack.push((child.clone(), 0));
                }
            }
        }
    }

    found.into_iter().collect()
}

/// Rotate a cycle to start at its smallest id, so the same loop discovered from
/// different entry points is recognised as one cycle.
fn normalise_cycle(cycle: &[ModId]) -> Vec<ModId> {
    let Some(min) = cycle.iter().enumerate().min_by_key(|(_, id)| *id) else {
        return Vec::new();
    };
    let mut out: Vec<ModId> = cycle.get(min.0..).unwrap_or_default().to_vec();
    out.extend_from_slice(cycle.get(..min.0).unwrap_or_default());
    out
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
    use crate::mods::definition::{
        Conflict, Dependency, License, MinecraftCompatibility, ModVersion, PrivacyClassification,
        Provenance, RedistributionPolicy,
    };

    fn id(raw: &str) -> ModId {
        ModId::new(raw).unwrap()
    }

    fn definition(name: &str) -> ModDefinition {
        ModDefinition {
            id: id(name),
            name: name.to_owned(),
            version: ModVersion::new("1.0"),
            minecraft: MinecraftCompatibility::only("1.8.9"),
            loaders: [Loader::LegacyFabric].into_iter().collect(),
            provenance: Provenance::BuiltInCatalogue {
                reviewed: "test".into(),
            },
            download: None,
            hash: None,
            license: License::Unknown,
            redistribution: RedistributionPolicy::Unclear,
            privacy: PrivacyClassification::Unknown,
            dependencies: Vec::new(),
            conflicts: Vec::new(),
            summary: String::new(),
            homepage: None,
        }
    }

    fn candidate(definition: ModDefinition) -> Candidate {
        Candidate {
            id: definition.id.clone(),
            file_name: format!("{}.jar", definition.id),
            sha1: "a".repeat(40),
            enabled: true,
            definition: Some(definition),
        }
    }

    fn run(candidates: &[Candidate]) -> Resolution {
        resolve(candidates, "1.8.9", Loader::LegacyFabric)
    }

    // -- the happy path ----------------------------------------------------

    #[test]
    fn a_clean_set_resolves_with_no_problems() {
        let mut a = definition("a");
        a.dependencies = vec![Dependency {
            id: id("b"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];

        let result = run(&[candidate(a), candidate(definition("b"))]);
        assert!(result.problems.is_empty(), "{:?}", result.problems);
        assert!(result.is_launchable());
        assert_eq!(result.effective.len(), 2);
    }

    /// The mods directory must be a function of the configuration alone.
    #[test]
    fn the_effective_set_is_deterministic() {
        let forward = run(&[
            candidate(definition("zebra")),
            candidate(definition("apple")),
            candidate(definition("mango")),
        ]);
        let backward = run(&[
            candidate(definition("mango")),
            candidate(definition("apple")),
            candidate(definition("zebra")),
        ]);

        assert_eq!(forward.effective, backward.effective);
        let ids: Vec<&str> = forward.effective.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, vec!["apple", "mango", "zebra"]);
    }

    #[test]
    fn disabled_mods_do_not_participate() {
        let mut a = definition("a");
        a.conflicts = vec![Conflict {
            id: id("b"),
            kind: ConflictKind::Incompatible,
            detail: "they fight".into(),
        }];

        let mut b = candidate(definition("b"));
        b.enabled = false;

        let result = run(&[candidate(a), b]);
        assert!(result.problems.is_empty(), "{:?}", result.problems);
        assert_eq!(result.effective.len(), 1);
    }

    // -- dependencies ------------------------------------------------------

    #[test]
    fn a_missing_required_dependency_blocks_the_launch() {
        let mut a = definition("a");
        a.dependencies = vec![Dependency {
            id: id("b"),
            minimum_version: None,
            optional: false,
            reason: "Uses its hooks.".into(),
        }];

        let result = run(&[candidate(a)]);
        assert!(!result.is_launchable());
        assert_eq!(result.problems.len(), 1);
        assert_eq!(result.problems[0].severity(), Severity::Blocking);

        let names = NameLookup::default();
        let message = result.problems[0].message(&names);
        assert!(message.contains("requires"), "{message}");
        assert!(message.contains("Uses its hooks."), "{message}");
    }

    /// The distinction that matters: optional means the launch still works.
    #[test]
    fn a_missing_optional_dependency_does_not_block() {
        let mut a = definition("a");
        a.dependencies = vec![Dependency {
            id: id("b"),
            minimum_version: None,
            optional: true,
            reason: String::new(),
        }];

        let result = run(&[candidate(a)]);
        assert!(result.is_launchable());
        assert_eq!(result.problems[0].severity(), Severity::Advisory);
        assert!(result.problems[0]
            .message(&NameLookup::default())
            .contains("works better with"));
    }

    // -- cycles ------------------------------------------------------------

    #[test]
    fn a_two_node_cycle_is_detected() {
        let mut a = definition("a");
        a.dependencies = vec![Dependency {
            id: id("b"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];
        let mut b = definition("b");
        b.dependencies = vec![Dependency {
            id: id("a"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];

        let result = run(&[candidate(a), candidate(b)]);
        let cycles: Vec<&Problem> = result
            .problems
            .iter()
            .filter(|p| matches!(p, Problem::DependencyCycle { .. }))
            .collect();
        assert_eq!(cycles.len(), 1, "{:?}", result.problems);
        assert!(!result.is_launchable());
    }

    #[test]
    fn a_longer_cycle_is_detected_once_not_once_per_member() {
        let mut mods = Vec::new();
        let names = ["a", "b", "c", "d"];
        for (index, name) in names.iter().enumerate() {
            let mut def = definition(name);
            let next = names[(index + 1) % names.len()];
            def.dependencies = vec![Dependency {
                id: id(next),
                minimum_version: None,
                optional: false,
                reason: String::new(),
            }];
            mods.push(candidate(def));
        }

        let result = run(&mods);
        let cycles: Vec<&Problem> = result
            .problems
            .iter()
            .filter(|p| matches!(p, Problem::DependencyCycle { .. }))
            .collect();
        assert_eq!(cycles.len(), 1, "one loop is one problem: {cycles:?}");
    }

    #[test]
    fn a_self_dependency_is_a_cycle() {
        let mut a = definition("a");
        a.dependencies = vec![Dependency {
            id: id("a"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];

        let result = run(&[candidate(a)]);
        assert!(result
            .problems
            .iter()
            .any(|p| matches!(p, Problem::DependencyCycle { .. })));
    }

    /// A diamond is not a cycle, and reporting it as one would block a perfectly
    /// good set.
    #[test]
    fn a_diamond_is_not_a_cycle() {
        let mut top = definition("top");
        top.dependencies = vec![
            Dependency {
                id: id("left"),
                minimum_version: None,
                optional: false,
                reason: String::new(),
            },
            Dependency {
                id: id("right"),
                minimum_version: None,
                optional: false,
                reason: String::new(),
            },
        ];
        let mut left = definition("left");
        left.dependencies = vec![Dependency {
            id: id("base"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];
        let mut right = definition("right");
        right.dependencies = vec![Dependency {
            id: id("base"),
            minimum_version: None,
            optional: false,
            reason: String::new(),
        }];

        let result = run(&[
            candidate(top),
            candidate(left),
            candidate(right),
            candidate(definition("base")),
        ]);
        assert!(result.problems.is_empty(), "{:?}", result.problems);
        assert!(result.is_launchable());
    }

    /// A user can edit the profile file. A pathological graph must produce a
    /// report, not a stack overflow.
    #[test]
    fn a_very_deep_chain_does_not_overflow_the_stack() {
        let depth = 5_000;
        let mut mods = Vec::new();
        for index in 0..depth {
            let mut def = definition(&format!("m{index}"));
            if index + 1 < depth {
                def.dependencies = vec![Dependency {
                    id: id(&format!("m{}", index + 1)),
                    minimum_version: None,
                    optional: false,
                    reason: String::new(),
                }];
            }
            mods.push(candidate(def));
        }

        let result = run(&mods);
        assert!(
            !result
                .problems
                .iter()
                .any(|p| matches!(p, Problem::DependencyCycle { .. })),
            "a chain is not a cycle"
        );
        assert_eq!(result.effective.len(), depth);
    }

    // -- conflicts ---------------------------------------------------------

    #[test]
    fn an_incompatible_pair_blocks_the_launch() {
        let mut a = definition("a");
        a.conflicts = vec![Conflict {
            id: id("b"),
            kind: ConflictKind::Incompatible,
            detail: "Both install the same transformer.".into(),
        }];

        let result = run(&[candidate(a), candidate(definition("b"))]);
        assert!(!result.is_launchable());
        assert_eq!(result.problems[0].severity(), Severity::Blocking);
    }

    /// The `OptiFine` case: worth saying, not worth refusing to launch over.
    #[test]
    fn an_overlapping_optimization_warns_without_blocking() {
        let mut a = definition("a");
        a.conflicts = vec![Conflict {
            id: id("b"),
            kind: ConflictKind::OverlappingOptimization,
            detail: "Both patch the renderer.".into(),
        }];

        let result = run(&[candidate(a), candidate(definition("b"))]);
        assert!(result.is_launchable());
        assert_eq!(result.worst_severity(), Some(Severity::Warning));
        assert!(result.problems[0]
            .message(&NameLookup::default())
            .contains("overlaps with"));
    }

    /// One conflict is one problem, however many sides declare it.
    #[test]
    fn a_conflict_declared_from_both_sides_is_reported_once() {
        let mut a = definition("a");
        a.conflicts = vec![Conflict {
            id: id("b"),
            kind: ConflictKind::Incompatible,
            detail: "x".into(),
        }];
        let mut b = definition("b");
        b.conflicts = vec![Conflict {
            id: id("a"),
            kind: ConflictKind::Incompatible,
            detail: "x".into(),
        }];

        let result = run(&[candidate(a), candidate(b)]);
        let conflicts = result
            .problems
            .iter()
            .filter(|p| matches!(p, Problem::Conflict { .. }))
            .count();
        assert_eq!(conflicts, 1);
    }

    #[test]
    fn a_conflict_with_something_absent_is_not_a_problem() {
        let mut a = definition("a");
        a.conflicts = vec![Conflict {
            id: id("not-installed"),
            kind: ConflictKind::Incompatible,
            detail: "x".into(),
        }];

        let result = run(&[candidate(a)]);
        assert!(result.problems.is_empty(), "{:?}", result.problems);
    }

    // -- compatibility -----------------------------------------------------

    #[test]
    fn a_mod_for_another_minecraft_version_is_refused() {
        let mut a = definition("a");
        a.minecraft = MinecraftCompatibility::only("1.12.2");

        let result = run(&[candidate(a)]);
        assert!(!result.is_launchable());
        let message = result.problems[0].message(&NameLookup::default());
        assert!(message.contains("1.8.9"), "{message}");
        assert!(message.contains("1.12.2"), "{message}");
    }

    #[test]
    fn a_mod_for_another_loader_is_refused() {
        let mut a = definition("a");
        a.loaders = [Loader::Forge].into_iter().collect();

        let result = run(&[candidate(a)]);
        assert!(!result.is_launchable());
        assert!(result
            .problems
            .iter()
            .any(|p| matches!(p, Problem::WrongLoader { .. })));
    }

    /// Under vanilla, every mod would also be "not a Vanilla mod". Saying that
    /// once about the loader is more useful than saying it once per mod.
    #[test]
    fn vanilla_reports_one_problem_rather_than_one_per_mod() {
        let result = resolve(
            &[
                candidate(definition("a")),
                candidate(definition("b")),
                candidate(definition("c")),
            ],
            "1.8.9",
            Loader::Vanilla,
        );

        assert_eq!(result.problems.len(), 1, "{:?}", result.problems);
        assert!(matches!(
            result.problems[0],
            Problem::LoaderLoadsNoMods { count: 3, .. }
        ));
        assert!(result.problems[0]
            .message(&NameLookup::default())
            .contains("Nothing in the mods folder will run"));
    }

    // -- duplicates and unknowns ------------------------------------------

    #[test]
    fn two_files_claiming_the_same_mod_block_the_launch() {
        let mut first = candidate(definition("a"));
        first.file_name = "a-1.0.jar".into();
        let mut second = candidate(definition("a"));
        second.file_name = "a-2.0.jar".into();
        second.sha1 = "b".repeat(40);

        let result = run(&[first, second]);
        assert!(!result.is_launchable());
        let message = result.problems[0].message(&NameLookup::default());
        assert!(message.contains("a-1.0.jar"), "{message}");
        assert!(message.contains("a-2.0.jar"), "{message}");
        // Only one of them can be materialized.
        assert_eq!(result.effective.len(), 1);
    }

    /// A user may install whatever they like. They should simply be told we
    /// cannot vouch for it.
    #[test]
    fn an_unknown_user_supplied_mod_is_allowed_but_noted() {
        let unknown = Candidate {
            id: id("mystery"),
            file_name: "mystery.jar".into(),
            sha1: "c".repeat(40),
            enabled: true,
            definition: None,
        };

        let result = run(&[unknown]);
        assert!(result.is_launchable());
        assert_eq!(result.problems[0].severity(), Severity::Advisory);
        assert_eq!(result.effective.len(), 1);

        let message = result.problems[0].message(&NameLookup::default());
        assert!(message.contains("mystery.jar"), "{message}");
        assert!(message.contains("catalogue"), "{message}");
    }

    // -- ordering ----------------------------------------------------------

    #[test]
    fn problems_are_ordered_worst_first_and_deterministically() {
        let mut blocking = definition("zzz");
        blocking.minecraft = MinecraftCompatibility::only("1.12.2");

        let unknown = Candidate {
            id: id("aaa"),
            file_name: "aaa.jar".into(),
            sha1: "c".repeat(40),
            enabled: true,
            definition: None,
        };

        let result = run(&[candidate(blocking), unknown]);
        assert_eq!(result.problems[0].severity(), Severity::Blocking);
        assert_eq!(result.problems[1].severity(), Severity::Advisory);

        // And running it again gives the same answer.
        let again = run(&[
            Candidate {
                id: id("aaa"),
                file_name: "aaa.jar".into(),
                sha1: "c".repeat(40),
                enabled: true,
                definition: None,
            },
            candidate({
                let mut d = definition("zzz");
                d.minecraft = MinecraftCompatibility::only("1.12.2");
                d
            }),
        ]);
        assert_eq!(result.problems, again.problems);
    }

    #[test]
    fn an_empty_set_is_launchable() {
        let result = run(&[]);
        assert!(result.is_launchable());
        assert!(result.effective.is_empty());
        assert!(result.problems.is_empty());
        assert_eq!(result.worst_severity(), None);
    }

    /// Vanilla with no mods is not a problem, only vanilla *with* mods is.
    #[test]
    fn vanilla_with_no_mods_is_fine() {
        let result = resolve(&[], "1.8.9", Loader::Vanilla);
        assert!(result.problems.is_empty());
    }
}
