//! Fathom's own game-side components, and who is responsible for installing
//! them.
//!
//! # The rule this module exists to enforce
//!
//! **Nobody should ever have to put a Fathom jar into a mods folder.**
//!
//! Before this existed, "the Fathom experience" meant: know that the
//! optimizations need a loader, know that the loader is Legacy Fabric, know
//! that the module is a jar, find the jar, and add it by hand. Every one of
//! those is a thing the launcher knows and the user should not have to. A
//! profile that declares itself [`crate::profiles::ProfileKind::Fathom`] gets
//! them installed, kept current, and repaired if they go missing.
//!
//! # Why a registry and not a hard-coded list in the installer
//!
//! Three reasons, in order of how much they cost when ignored:
//!
//! 1. **Availability is a fact, not a bug.** Some of these are built and ship
//!    in `resources/`. Some are part of another component's jar. One is
//!    third-party, permissively licensed, and fetchable from Modrinth once
//!    somebody asks. One cannot be redistributed at all, ever. Those are four
//!    different answers to "why is this not installed", and a user deserves the
//!    right one. See [`Supply`].
//! 2. **The setup screen and the installer must agree.** The optional
//!    components offered during setup are read from this table, so a component
//!    cannot be offered and then not installable, or installable and never
//!    offered.
//! 3. **The launcher must never claim more than it ships.** A component listed
//!    as [`Supply::Planned`] renders as planned. It is not silently omitted,
//!    and it is not silently promised.
//!
//! # What is never done here
//!
//! Nothing is downloaded from an unverified source, and nothing whose licence
//! forbids redistribution is fetched at all. `OptiFine` appears in this table
//! precisely so the launcher can explain that the user has to supply it, and
//! then make supplying it easy.

use std::path::{Path, PathBuf};

use crate::mods::{ModEngine, ModError, ModId, ModSource, ProfileMod};
use crate::paths::DataPaths;
use crate::profiles::{Loader, Profile, ProfileKind};

// ---------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------

/// How a component reaches a profile.
///
/// The variants are ordered by how much the launcher can do about it: it can
/// install the first two itself, and the last three need something else to
/// happen first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Supply {
    /// Ships inside this installation, under `resources/`, as a jar whose name
    /// starts with the given prefix.
    ///
    /// A prefix rather than a filename because the jar carries its version, and
    /// hard-coding one means a version bump silently stops installing it while
    /// everything still appears to work.
    Bundled { jar_prefix: &'static str },
    /// Currently part of another component's jar.
    ///
    /// Real and installed, just not as a separate file yet. Modelled rather
    /// than hidden so that splitting it out later is a change to this line and
    /// nothing else.
    PartOf { component: &'static str },
    /// Designed, not built. Shown as planned; never promised.
    Planned,
    /// Third-party, permissively licensed, and fetchable from a repository the
    /// launcher talks to.
    ///
    /// # Why this is not [`Supply::is_installable`]
    ///
    /// Because installing it is a network request to a third party, and the
    /// consent model says that needs a specific decision every time --
    /// `Consent::ExplicitPerAction`. [`ensure`] runs when a profile is created,
    /// when the launcher opens, and before every launch, none of which is a
    /// person asking for a download.
    ///
    /// So the automatic pass reports a managed component as unavailable and
    /// says why, and [`install_managed`] does the work when somebody actually
    /// asks. A component that quietly fetched itself at launch would be exactly
    /// the behaviour the per-action grant exists to make impossible.
    Managed {
        repository: &'static str,
        project: &'static str,
    },
    /// Third-party, permitted by its licence, but with no verified download
    /// source yet. The launcher will not guess at one.
    AwaitingSource { where_to_get: &'static str },
    /// The user has to bring this file themselves, because its licence does not
    /// permit anyone else to distribute it.
    UserSupplied { where_to_get: &'static str },
}

impl Supply {
    /// Whether the launcher can install this without help.
    pub fn is_installable(self) -> bool {
        matches!(self, Supply::Bundled { .. } | Supply::PartOf { .. })
    }

    /// One sentence for the interface, when it is not installable.
    pub fn blocker(self) -> Option<&'static str> {
        match self {
            Supply::Bundled { .. } | Supply::PartOf { .. } => None,
            Supply::Planned => Some("Not in this build yet."),
            Supply::Managed { .. } => Some("Needs a download you have not asked for yet."),
            Supply::AwaitingSource { .. } => Some("No verified download source yet."),
            Supply::UserSupplied { .. } => Some("You supply the file."),
        }
    }

    /// Whether a person can ask for this and have the launcher fetch it.
    ///
    /// The difference between "Fathom cannot get this for you" and "Fathom can,
    /// once you say so", which is a distinction the interface has to be able to
    /// draw: one is a dead end and the other is a button.
    pub fn is_requestable(self) -> bool {
        matches!(self, Supply::Managed { .. })
    }

    /// The repository project this comes from, when it comes from one.
    pub fn project(self) -> Option<&'static str> {
        match self {
            Supply::Managed { project, .. } => Some(project),
            _ => None,
        }
    }

    pub fn where_to_get(self) -> Option<&'static str> {
        match self {
            Supply::AwaitingSource { where_to_get } | Supply::UserSupplied { where_to_get } => {
                Some(where_to_get)
            }
            Supply::Managed { .. } => Some("https://modrinth.com/mod/legacy-fabric-api"),
            _ => None,
        }
    }
}

/// How strongly a component belongs in a profile.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Tier {
    /// Part of what [`ProfileKind::Fathom`] *means*. Installed without asking,
    /// and put back if removed.
    Core,
    /// Offered during setup, on by default, but the user's answer stands.
    Recommended,
    /// Offered during setup, off by default.
    Optional,
}

/// One thing the launcher can put into a profile.
#[derive(Debug, Clone, Copy)]
pub struct Component {
    /// Stable id. Crosses the command bridge and is stored in profiles.
    pub id: &'static str,
    pub name: &'static str,
    /// One sentence. No implementation detail.
    pub summary: &'static str,
    /// The id this component's jar takes in a profile's mod list.
    pub mod_id: &'static str,
    pub tier: Tier,
    pub supply: Supply,
    /// Loaders this component can be installed under.
    pub loaders: &'static [Loader],
}

impl Component {
    pub fn supports(&self, loader: Loader) -> bool {
        self.loaders.contains(&loader)
    }

    pub fn parsed_mod_id(&self) -> Option<ModId> {
        ModId::new(self.mod_id).ok()
    }
}

// ---------------------------------------------------------------------------
// The registry
// ---------------------------------------------------------------------------

const FABRIC: &[Loader] = &[Loader::LegacyFabric];
const FABRIC_OR_FORGE: &[Loader] = &[Loader::LegacyFabric, Loader::Forge];

/// Everything the launcher knows how to put into a profile.
///
/// Ordered as the setup screen shows them: what a Fathom profile is, then what
/// can be added to one.
pub const ALL: [Component; 5] = [
    Component {
        id: "fathom-core",
        name: "Fathom Core",
        summary: "Config, keybinds and the launcher bridge.",
        mod_id: "fathom-optimizations",
        tier: Tier::Core,
        // The shipped module already contains the config registry and the
        // per-profile bridge. It is one jar today and two later; saying so is
        // better than listing a component that installs nothing.
        supply: Supply::PartOf {
            component: "fathom-performance",
        },
        loaders: FABRIC,
    },
    Component {
        id: "fathom-performance",
        name: "Fathom Performance",
        summary: "Fathom's own in-game optimizations.",
        mod_id: "fathom-optimizations",
        tier: Tier::Core,
        supply: Supply::Bundled {
            jar_prefix: "fathom-optimizations-",
        },
        loaders: FABRIC,
    },
    Component {
        id: "fathom-hud",
        name: "Fathom HUD",
        summary: "The in-game hub on Right Shift, the HUD widgets and the HUD editor.",
        mod_id: "fathom-optimizations",
        tier: Tier::Core,
        // Shipped inside the same jar as Fathom Performance. One jar rather
        // than three is a packaging decision, not a product one: the components
        // stay separate here because they are separately describable and will
        // be separately shippable, and modelling them as one thing now would
        // make splitting them later a change to every caller instead of to this
        // line.
        supply: Supply::PartOf {
            component: "fathom-performance",
        },
        loaders: FABRIC,
    },
    Component {
        id: "fabric-api",
        name: "Fabric API",
        summary: "The shared API most Fabric mods build against. Apache-2.0.",
        mod_id: "fabric-api",
        tier: Tier::Recommended,
        // Apache-2.0, published on Modrinth with a per-file SHA-1, and covered
        // by Modrinth's API licence for third-party applications. That is a
        // verified source, which is what this entry was waiting for.
        supply: Supply::Managed {
            repository: "modrinth",
            project: crate::mods::catalog::MODRINTH_FABRIC_API,
        },
        loaders: FABRIC,
    },
    Component {
        id: "optifine",
        name: "OptiFine",
        summary: "Shaders and detailed video settings.",
        mod_id: "optifine",
        tier: Tier::Optional,
        // The whole reason this table has a `UserSupplied` variant. See
        // `docs/research/mod-policies.md`: proprietary, all rights reserved,
        // redistribution prohibited. Fathom will never host, mirror or fetch it.
        supply: Supply::UserSupplied {
            where_to_get: "https://optifine.net/downloads",
        },
        loaders: FABRIC_OR_FORGE,
    },
];

pub fn find(id: &str) -> Option<&'static Component> {
    ALL.iter().find(|component| component.id == id)
}

/// The components a profile of this kind and loader must have.
///
/// Empty for every kind except [`ProfileKind::Fathom`]. A Custom profile may
/// contain all of them, but they got there because somebody chose them.
pub fn required_for(kind: ProfileKind, loader: Loader) -> Vec<&'static Component> {
    if !kind.owns_components() {
        return Vec::new();
    }
    ALL.iter()
        .filter(|component| component.tier == Tier::Core && component.supports(loader))
        .collect()
}

/// The components a setup screen offers as choices.
pub fn optional_for(loader: Loader) -> Vec<&'static Component> {
    ALL.iter()
        .filter(|component| component.tier != Tier::Core && component.supports(loader))
        .collect()
}

// ---------------------------------------------------------------------------
// Installing
// ---------------------------------------------------------------------------

#[derive(Debug, thiserror::Error)]
pub enum ComponentError {
    #[error("{component} could not be installed: {source}")]
    Install {
        component: &'static str,
        #[source]
        source: ModError,
    },
    #[error("{0} is not a component this build knows about")]
    Unknown(String),
    #[error("{component} is not something this launcher can fetch for you")]
    NotRequestable { component: &'static str },
    #[error("{component} does not work with this profile's loader")]
    WrongLoader { component: &'static str },
    #[error("{component} has no build for this Minecraft version and loader")]
    NoVersion { component: &'static str },
    #[error("{component} could not be downloaded: {source}")]
    Fetch {
        component: &'static str,
        #[source]
        source: Box<crate::mods::ModrinthError>,
    },
}

/// What [`ensure`] did, and what it could not do.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Report {
    /// Installed just now.
    pub added: Vec<&'static str>,
    /// Replaced with a newer build that ships with this launcher.
    pub updated: Vec<&'static str>,
    /// Already present and current.
    pub current: Vec<&'static str>,
    /// Required, but this build cannot supply it. Each entry is a component id
    /// and the reason, both suitable for display.
    pub unavailable: Vec<(&'static str, &'static str)>,
}

impl Report {
    pub fn changed_anything(&self) -> bool {
        !self.added.is_empty() || !self.updated.is_empty()
    }

    /// One line for a log or a status row.
    pub fn summary(&self) -> String {
        if !self.changed_anything() {
            return if self.unavailable.is_empty() {
                "Fathom components are up to date.".to_owned()
            } else {
                format!("{} component(s) not available yet.", self.unavailable.len())
            };
        }
        let added = self.added.len();
        let updated = self.updated.len();
        match (added, updated) {
            (0, n) => format!("Updated {n} Fathom component(s)."),
            (n, 0) => format!("Installed {n} Fathom component(s)."),
            (a, u) => format!("Installed {a} and updated {u} Fathom component(s)."),
        }
    }
}

/// Make the profile's mod list match what its kind requires.
///
/// Idempotent, and safe to call before every launch: with everything present
/// and current it hashes nothing and writes nothing.
///
/// Only ever touches entries marked [`ModSource::FirstParty`]. A jar the user
/// added is theirs, even if it happens to share an id with a component -- the
/// launcher replacing it during an upgrade would be deleting a file it did not
/// obtain.
pub fn ensure(
    paths: &DataPaths,
    engine: &ModEngine,
    profile: &mut Profile,
) -> Result<Report, ComponentError> {
    let mut report = Report::default();
    let required = required_for(profile.kind, profile.loader_kind());

    for component in required {
        match component.supply {
            // Provided by another component's jar. Nothing to install for this
            // one; the component it points at is in the same required list and
            // is handled on its own iteration.
            Supply::PartOf { .. } => report.current.push(component.id),
            Supply::Bundled { jar_prefix } => {
                install_bundled(paths, engine, profile, component, jar_prefix, &mut report)?;
            }
            other => {
                // A managed component already in the profile is present and
                // current: `ensure` has nothing to do and must not report it as
                // missing. Only an absent one is a gap, and even then the gap is
                // reported rather than closed, because closing it is a network
                // request nobody asked for. See `Supply::Managed`.
                let installed = component
                    .parsed_mod_id()
                    .is_some_and(|id| profile.mods.get(&id).is_some());
                if installed {
                    report.current.push(component.id);
                } else if let Some(reason) = other.blocker() {
                    report.unavailable.push((component.id, reason));
                }
            }
        }
    }

    Ok(report)
}

/// Install a component the user asked for, from the repository it lives in.
///
/// # Why this is separate from [`ensure`]
///
/// Because it needs two things `ensure` does not have and must not invent: a
/// network client, and a [`PerActionGrant`] recording that a person asked for
/// this specific thing. `ensure` runs on a timer of its own -- profile created,
/// launcher opened, launch about to happen -- and none of those is somebody
/// pressing a button.
///
/// Everything else is the same as a bundled install: the bytes go into the
/// content-addressed store after being checked against the hash the repository
/// published, and the profile records where they came from so the question
/// "what is this and where did it come from" stays answerable offline.
pub fn install_managed(
    engine: &ModEngine,
    modrinth: &crate::mods::Modrinth,
    profile: &mut Profile,
    component: &'static Component,
    minecraft: &str,
    grant: &crate::network::PerActionGrant,
) -> Result<ProfileMod, ComponentError> {
    let Some(project) = component.supply.project() else {
        return Err(ComponentError::NotRequestable {
            component: component.id,
        });
    };
    let loader = profile.loader_kind();
    if !component.supports(loader) {
        return Err(ComponentError::WrongLoader {
            component: component.id,
        });
    }

    let version = modrinth
        .newest(project, loader, minecraft, grant)
        .map_err(|source| ComponentError::Fetch {
            component: component.id,
            source: Box::new(source),
        })?
        .ok_or(ComponentError::NoVersion {
            component: component.id,
        })?;

    let stored = modrinth
        .download(&version, engine.store(), grant)
        .map_err(|source| ComponentError::Fetch {
            component: component.id,
            source: Box::new(source),
        })?;

    let Some(mod_id) = component.parsed_mod_id() else {
        return Err(ComponentError::Unknown(component.mod_id.to_owned()));
    };

    let previous = profile.mods.get(&mod_id).cloned();
    let mut entry = ProfileMod::new(mod_id, &stored.sha1, version.file_name.clone())
        .from_source(ModSource::Managed)
        .with_origin(origin_of(component, &version));
    entry.version = Some(version.version());
    // An upgrade must not switch back on something that was switched off.
    entry.enabled = previous.as_ref().is_none_or(|entry| entry.enabled);
    profile.mods.upsert(entry.clone());
    Ok(entry)
}

/// What we knew about a version at the moment it was installed.
fn origin_of(
    component: &'static Component,
    version: &crate::mods::modrinth::Version,
) -> crate::mods::Origin {
    crate::mods::Origin {
        name: Some(component.name.to_owned()),
        summary: Some(component.summary.to_owned()),
        // Read from the catalogue rather than from the response, because this
        // is a first-party component whose licence Fathom has actually checked.
        license: crate::mods::catalog::find(&component.parsed_mod_id().unwrap_or_else(|| {
            ModId::new("fabric-api").unwrap_or_else(|_| unreachable!("a constant id"))
        }))
        .map(|definition| definition.license.describe()),
        minecraft: version.game_versions.clone(),
        loaders: version.loaders.clone(),
        repository: Some("modrinth".to_owned()),
        project: Some(version.project_id.clone()),
        version_id: Some(version.version_id.clone()),
        page: component.supply.where_to_get().map(str::to_owned),
    }
}

fn install_bundled(
    paths: &DataPaths,
    engine: &ModEngine,
    profile: &mut Profile,
    component: &'static Component,
    jar_prefix: &'static str,
    report: &mut Report,
) -> Result<(), ComponentError> {
    let Some(jar) = newest_jar(paths, jar_prefix) else {
        // A developer `cargo run` has no `resources/` beside the executable.
        // That is not an error and not a reason to refuse the profile; it is a
        // component this particular build cannot supply.
        report
            .unavailable
            .push((component.id, "Not included in this build."));
        return Ok(());
    };

    let Some(mod_id) = component.parsed_mod_id() else {
        return Err(ComponentError::Unknown(component.mod_id.to_owned()));
    };

    let existing = profile.mods.iter().find(|entry| entry.id == mod_id).cloned();

    // A user-supplied jar under the same id stays exactly as it is.
    if let Some(entry) = &existing {
        if !entry.source.is_launcher_owned() {
            report.current.push(component.id);
            return Ok(());
        }
    }

    // Adopting is content-addressed, so this is also how "is it already the
    // right bytes" gets answered: same file, same hash, nothing written.
    let stored = engine
        .store()
        .adopt(&jar)
        .map_err(|source| ComponentError::Install {
            component: component.id,
            source,
        })?;

    if existing.as_ref().is_some_and(|entry| entry.sha1 == stored.sha1) {
        report.current.push(component.id);
        return Ok(());
    }

    let file_name = jar
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(component.name)
        .to_owned();

    let mut entry = ProfileMod::new(mod_id, &stored.sha1, file_name).from_source(ModSource::FirstParty);
    // A component the launcher installs arrives switched on. The Performance
    // page is where it gets switched off, and that decision is preserved
    // because an existing entry's enabled state is carried over.
    entry.enabled = existing.as_ref().is_none_or(|previous| previous.enabled);
    profile.mods.upsert(entry);

    if existing.is_some() {
        report.updated.push(component.id);
    } else {
        report.added.push(component.id);
    }
    Ok(())
}

/// The newest-looking jar in `resources/` whose name starts with `prefix`.
///
/// Excludes the artifacts that are not loadable mods by the same rule
/// `tools/package-portable.ps1` uses. Packaging should never put them there;
/// this is the second lock on the same door, because the consequence of getting
/// it wrong is a profile that refuses to start over a mod nobody chose.
pub fn newest_jar(paths: &DataPaths, prefix: &str) -> Option<PathBuf> {
    let directory = resources_dir(paths)?;
    let mut candidates: Vec<PathBuf> = std::fs::read_dir(&directory)
        .ok()?
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| is_loadable_jar(path, prefix))
        .collect();

    // Deterministic: the same build must install the same jar every time.
    candidates.sort();
    candidates.pop()
}

pub fn is_loadable_jar(path: &Path, prefix: &str) -> bool {
    let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
        return false;
    };
    // The extension goes through `Path` rather than a string suffix, so
    // `archive.jar.bak` is not treated as a jar. Case-insensitive throughout:
    // Windows filenames are, and a jar delivered as `.JAR` is still a jar.
    let is_jar = path
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"));
    let lower = name.to_ascii_lowercase();
    is_jar
        && lower.starts_with(&prefix.to_ascii_lowercase())
        && !lower.ends_with("-sources.jar")
        && !lower.ends_with("-core.jar")
}

/// The `resources/` directory, wherever it is for this build.
fn resources_dir(paths: &DataPaths) -> Option<PathBuf> {
    if let Some(beside) = DataPaths::install_dir().map(|dir| dir.join("resources")) {
        if beside.is_dir() {
            return Some(beside);
        }
    }
    let in_data = paths.root().join("resources");
    in_data.is_dir().then_some(in_data)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::profiles::ProfileId;

    fn profile(kind: ProfileKind, loader: Loader) -> Profile {
        let mut profile = Profile::new(ProfileId::new("p").unwrap(), "P");
        profile.kind = kind;
        profile.loader.kind = loader;
        profile
    }

    #[test]
    fn every_component_id_is_unique_and_parseable() {
        let mut ids: Vec<&str> = ALL.iter().map(|component| component.id).collect();
        let before = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), before, "component ids must be unique");

        for component in ALL {
            assert!(
                component.parsed_mod_id().is_some(),
                "{} has an unusable mod id",
                component.id
            );
        }
    }

    /// The rule the whole module exists for: a Fathom profile's components are
    /// not a choice, and no other kind gets any installed without asking.
    #[test]
    fn only_a_fathom_profile_has_required_components() {
        assert!(!required_for(ProfileKind::Fathom, Loader::LegacyFabric).is_empty());
        assert!(required_for(ProfileKind::Vanilla, Loader::Vanilla).is_empty());
        assert!(required_for(ProfileKind::Custom, Loader::LegacyFabric).is_empty());
    }

    /// Vanilla loads no jars at all, so requiring one would produce a profile
    /// that can never satisfy its own requirements.
    #[test]
    fn nothing_is_required_under_a_loader_that_cannot_load_mods() {
        assert!(required_for(ProfileKind::Fathom, Loader::Vanilla).is_empty());
        for component in ALL {
            assert!(
                !component.supports(Loader::Vanilla),
                "{} claims to work without a loader",
                component.id
            );
        }
    }

    /// The setup screen reads `optional_for`, and it must never offer something
    /// that is actually part of what a Fathom profile means.
    #[test]
    fn optional_and_core_do_not_overlap() {
        let optional = optional_for(Loader::LegacyFabric);
        let core = required_for(ProfileKind::Fathom, Loader::LegacyFabric);
        for component in &optional {
            assert!(
                !core.iter().any(|other| other.id == component.id),
                "{} is offered as optional and required at the same time",
                component.id
            );
        }
    }

    /// `OptiFine`'s licence forbids redistribution. The launcher must never be
    /// able to describe it as something it can install.
    #[test]
    fn optifine_is_user_supplied_and_never_installable() {
        let optifine = find("optifine").expect("optifine is in the registry");
        assert!(!optifine.supply.is_installable());
        assert!(matches!(optifine.supply, Supply::UserSupplied { .. }));
        assert_eq!(
            optifine.supply.where_to_get(),
            Some("https://optifine.net/downloads")
        );
        assert_ne!(optifine.tier, Tier::Core, "it must never be installed by default");
    }

    /// Every component that is not installable must say why, or the interface
    /// has a row with a missing explanation.
    #[test]
    fn anything_not_installable_carries_a_reason() {
        for component in ALL {
            if !component.supply.is_installable() {
                assert!(
                    component.supply.blocker().is_some(),
                    "{} is not installable and does not say why",
                    component.id
                );
            }
        }
    }

    #[test]
    fn a_vanilla_profile_needs_nothing_and_ensure_says_so() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Vanilla, Loader::Vanilla);

        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert!(!report.changed_anything());
        assert!(profile.mods.is_empty());
    }

    /// Without a `resources/` directory -- a developer build -- a Fathom
    /// profile still resolves, and the components it could not get are named
    /// rather than silently missing.
    #[test]
    fn a_build_without_resources_reports_what_it_cannot_supply() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);

        let report = ensure(&paths, &engine, &mut profile).unwrap();
        // No `resources/` directory, so the jar that carries every core
        // component is not there to install.
        assert!(
            report
                .unavailable
                .iter()
                .any(|(id, _)| *id == "fathom-performance"),
            "{report:?}"
        );
        for (_, reason) in &report.unavailable {
            assert!(!reason.is_empty());
        }
    }

    /// The consent rule, as a test. `ensure` runs at profile creation, at
    /// launcher startup and before every launch -- none of which is somebody
    /// asking for a download -- so it must never fetch a managed component.
    #[test]
    fn the_automatic_pass_never_fetches_a_managed_component() {
        for component in ALL {
            if matches!(component.supply, Supply::Managed { .. }) {
                assert!(
                    !component.supply.is_installable(),
                    "{} would be installed without anybody asking",
                    component.id
                );
                assert!(
                    component.supply.is_requestable(),
                    "{} should still be offerable as a button",
                    component.id
                );
                assert_ne!(
                    component.tier,
                    Tier::Core,
                    "{} is required, so a profile could never satisfy itself offline",
                    component.id
                );
            }
        }
    }

    /// A managed component the user already installed is present, not missing.
    /// Reporting it as a gap on every launch would put a permanent warning on a
    /// profile that is complete.
    #[test]
    fn a_managed_component_already_installed_is_reported_as_current() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);

        let fabric_api = find("fabric-api").expect("it is in the registry");
        profile.mods.upsert(
            ProfileMod::new(
                fabric_api.parsed_mod_id().expect("a usable id"),
                "c".repeat(40),
                "legacy-fabric-api-1.13.5.jar",
            )
            .from_source(ModSource::Managed),
        );

        // Required components are the Core tier; Fabric API is Recommended, so
        // `ensure` does not consider it at all and cannot report it either way.
        // Asserting that directly is the point: a Recommended component is never
        // a reason a launch is blocked.
        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert!(
            !report.unavailable.iter().any(|(id, _)| *id == "fabric-api"),
            "a recommended component is not a gap: {report:?}"
        );
    }

    /// The end-to-end promise: a Fathom profile gets the shipped jar installed
    /// without anybody adding it, marked as the launcher's own.
    #[test]
    fn a_fathom_profile_installs_the_shipped_module_by_itself() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(
            resources.join("fathom-optimizations-9.9.9.jar"),
            b"PK\x03\x04 not really a jar, but it is bytes",
        )
        .unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);

        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert_eq!(report.added, vec!["fathom-performance"]);

        let entry = profile
            .mods
            .iter()
            .find(|entry| entry.id.as_str() == "fathom-optimizations")
            .expect("the module was installed");
        assert!(entry.enabled);
        assert_eq!(entry.source, ModSource::FirstParty);

        // Idempotent: a second pass with the same jar changes nothing.
        let again = ensure(&paths, &engine, &mut profile).unwrap();
        assert!(!again.changed_anything(), "{again:?}");
        assert!(again.current.contains(&"fathom-performance"));
    }

    /// A newer jar in the build replaces the one the launcher installed.
    #[test]
    fn a_newer_shipped_build_replaces_the_installed_one() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(resources.join("fathom-optimizations-1.0.0.jar"), b"PK\x03\x04 old").unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        ensure(&paths, &engine, &mut profile).unwrap();

        std::fs::write(resources.join("fathom-optimizations-1.1.0.jar"), b"PK\x03\x04 new").unwrap();
        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert_eq!(report.updated, vec!["fathom-performance"]);
    }

    /// And a jar the *user* added under the same id is never replaced. The
    /// launcher removing a file it did not obtain is the one thing this must
    /// not do.
    #[test]
    fn a_user_supplied_jar_is_left_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(resources.join("fathom-optimizations-1.0.0.jar"), b"PK\x03\x04 ours").unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        profile.mods.upsert(
            ProfileMod::new(
                ModId::new("fathom-optimizations").unwrap(),
                "a".repeat(40),
                "their-own-build.jar",
            )
            .from_source(ModSource::UserSupplied),
        );

        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert!(!report.changed_anything(), "{report:?}");
        let entry = profile
            .mods
            .iter()
            .find(|entry| entry.id.as_str() == "fathom-optimizations")
            .unwrap();
        assert_eq!(entry.file_name, "their-own-build.jar");
    }

    /// The disabled state survives an update. Somebody who switched the module
    /// off on the Performance page must not find it back on after an upgrade.
    #[test]
    fn switching_a_component_off_survives_an_update() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(resources.join("fathom-optimizations-1.0.0.jar"), b"PK\x03\x04 one").unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        ensure(&paths, &engine, &mut profile).unwrap();

        let id = ModId::new("fathom-optimizations").unwrap();
        let mut entry = profile.mods.iter().find(|e| e.id == id).unwrap().clone();
        entry.enabled = false;
        profile.mods.upsert(entry);

        std::fs::write(resources.join("fathom-optimizations-1.1.0.jar"), b"PK\x03\x04 two").unwrap();
        ensure(&paths, &engine, &mut profile).unwrap();

        assert!(
            !profile.mods.iter().find(|e| e.id == id).unwrap().enabled,
            "an upgrade re-enabled a component the user switched off"
        );
    }

    #[test]
    fn only_loadable_jars_are_picked_up() {
        let prefix = "fathom-optimizations-";
        assert!(is_loadable_jar(
            Path::new("fathom-optimizations-0.2.0-first-batch.jar"),
            prefix
        ));
        assert!(!is_loadable_jar(
            Path::new("fathom-optimizations-0.2.0-sources.jar"),
            prefix
        ));
        assert!(!is_loadable_jar(
            Path::new("fathom-optimizations-core.jar"),
            prefix
        ));
        assert!(!is_loadable_jar(Path::new("optifine.jar"), prefix));
        assert!(!is_loadable_jar(
            Path::new("fathom-optimizations-0.2.0.jar.bak"),
            prefix
        ));
        assert!(is_loadable_jar(
            Path::new("FATHOM-OPTIMIZATIONS-0.2.0.JAR"),
            prefix
        ));
    }
}
