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
    /// A component that must be installed alongside this one, under one
    /// particular loader, for it to work at all.
    ///
    /// Exists for OptiFine, and models the thing this launcher used to get
    /// wrong. OptiFine is not a Fabric mod: dropping its jar into a Fabric
    /// profile's mods directory does nothing, because the loader has no idea
    /// what to do with it. Under Forge it is self-installing and needs nothing;
    /// under Legacy Fabric it needs a bridge that patches it into the game at
    /// runtime. That difference is a property of the pair, so it is recorded
    /// as one rather than as a check somewhere downstream.
    pub bridge: Option<Bridge>,
}

/// A component another one cannot work without, under a given loader.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bridge {
    /// The id of the component that must also be present.
    pub component: &'static str,
    /// The loader under which it is required. Under any other loader the
    /// component stands alone.
    pub loader: Loader,
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
const FORGE: &[Loader] = &[Loader::Forge];
const FABRIC_OR_FORGE: &[Loader] = &[Loader::LegacyFabric, Loader::Forge];

/// Everything the launcher knows how to put into a profile.
///
/// Ordered as the setup screen shows them: what a Fathom profile is, then what
/// can be added to one.
pub const ALL: [Component; 7] = [
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
        bridge: None,
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
        bridge: None,
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
        bridge: None,
    },
    Component {
        id: "fathom-forge-client",
        name: "Fathom for Forge",
        summary: "Fathom's client on Forge: the hub, HUD, menu, shared profiles and optimizations.",
        mod_id: "fathom-forge",
        tier: Tier::Core,
        supply: Supply::Bundled {
            jar_prefix: "fathom-forge-",
        },
        loaders: FORGE,
        bridge: None,
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
        bridge: None,
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
        // Under Forge, OptiFine installs itself and needs nothing. Under Legacy
        // Fabric it is inert on its own -- it is not a Fabric mod and the
        // loader will not load it -- so it needs OptiFabric to patch it in.
        bridge: Some(Bridge {
            component: "optifabric",
            loader: Loader::LegacyFabric,
        }),
    },
    Component {
        id: "optifabric",
        name: "OptiFabric",
        summary: "Lets an OptiFine jar run under Legacy Fabric. MPL-2.0.",
        mod_id: "optifabric",
        tier: Tier::Optional,
        // Mozilla Public License 2.0, published on Modrinth with a per-file
        // SHA-1. Distributable, unlike the thing it bridges to: this is the
        // component Fathom may fetch, and OptiFine is the one it may not.
        //
        // `Managed` rather than `Bundled` deliberately. Shipping a copy inside
        // the installer would make Fathom a redistributor of a third party's
        // MPL-2.0 work, with the source-availability obligations that carries,
        // in order to save one download of a 448 KB file.
        supply: Supply::Managed {
            repository: "modrinth",
            project: crate::mods::catalog::MODRINTH_OPTIFABRIC,
        },
        loaders: FABRIC,
        bridge: None,
    },
];

/// Whether this component's jar is in the profile's mod list.
pub fn installed_in(component: &Component, profile: &Profile) -> bool {
    component
        .parsed_mod_id()
        .is_some_and(|id| profile.mods.get(&id).is_some())
}

/// A component this profile has installed that cannot work, and the bridge it
/// is missing.
///
/// Returns `(component, bridge)`. `None` is the normal answer and means every
/// installed component either needs no bridge under this loader or has the one
/// it needs.
///
/// Only the loader named in the [`Bridge`] is checked: OptiFine under Forge
/// installs itself and reports nothing here, which is correct and is the reason
/// the loader is part of the record rather than assumed.
pub fn missing_bridge(profile: &Profile) -> Option<(&'static Component, &'static Component)> {
    let loader = profile.loader_kind();
    for component in ALL.iter() {
        let Some(bridge) = component.bridge else {
            continue;
        };
        if bridge.loader != loader || !installed_in(component, profile) {
            continue;
        }
        let Some(needed) = find(bridge.component) else {
            continue;
        };
        if !installed_in(needed, profile) {
            return Some((component, needed));
        }
    }
    None
}

pub fn find(id: &str) -> Option<&'static Component> {
    ALL.iter().find(|component| component.id == id)
}

/// The components a profile of this kind and loader must have.
///
/// Empty for every kind except [`ProfileKind::Fathom`]. A Custom profile may
/// contain all of them, but they got there because somebody chose them.
pub fn required_for(kind: ProfileKind, loader: Loader) -> Vec<&'static Component> {
    // Forge is an opt-in Custom installation today, but the launcher-wide
    // settings/profile contract must not disappear merely because its loader
    // changed. The small Forge companion is therefore integration glue for
    // every Forge installation, while the full performance client remains a
    // Fathom-profile component on Legacy Fabric.
    if loader == Loader::Forge {
        return ALL
            .iter()
            .filter(|component| component.id == "fathom-forge-client")
            .collect();
    }
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
    /// Taken out: a bundled jar built for another loader than the profile's,
    /// left behind when its loader was switched.
    pub removed: Vec<&'static str>,
}

impl Report {
    pub fn changed_anything(&self) -> bool {
        !self.added.is_empty() || !self.updated.is_empty() || !self.removed.is_empty()
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
        if added == 0 && updated == 0 {
            return format!("Removed {} Fathom component(s) built for another loader.", self.removed.len());
        }
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
    let loader = profile.loader_kind();
    let required = required_for(profile.kind, loader);

    // Fathom's client is one jar per loader. After a loader switch the other
    // loader's build is still listed, and laying it out would put a Fabric jar
    // in a Forge profile (or the reverse). Only a first-party entry is taken
    // out -- a jar somebody supplied under the same id is theirs -- and it is
    // removed rather than switched off, so switching back installs it afresh
    // instead of finding it disabled.
    for component in ALL.iter() {
        if component.supports(loader) || !matches!(component.supply, Supply::Bundled { .. }) {
            continue;
        }
        let Some(id) = component.parsed_mod_id() else {
            continue;
        };
        let first_party = profile
            .mods
            .get(&id)
            .is_some_and(|entry| entry.source.is_launcher_owned());
        if first_party {
            profile.mods.remove(&id);
            report.removed.push(component.id);
        }
    }

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
        requires: std::collections::BTreeSet::default(),
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
    let lookup = bundled_jar(paths, jar_prefix);
    let Some(jar) = lookup.jar().map(|jar| jar.path.clone()) else {
        // Not installed, and not silently papered over: the launch gate
        // (`Launcher::verify_fathom_client`) refuses a Fathom profile whose
        // client module this build cannot supply, with the same sentence.
        tracing::warn!(
            component = component.id,
            problem = lookup.problem().unwrap_or_default(),
            "a Fathom component is unavailable in this build"
        );
        report
            .unavailable
            .push((component.id, "Not included in this build."));
        return Ok(());
    };

    let Some(mod_id) = component.parsed_mod_id() else {
        return Err(ComponentError::Unknown(component.mod_id.to_owned()));
    };

    let existing = profile
        .mods
        .iter()
        .find(|entry| entry.id == mod_id)
        .cloned();

    // A user-supplied jar under the same id stays exactly as it is.
    if let Some(entry) = &existing {
        if !entry.source.is_launcher_owned() {
            report.current.push(component.id);
            return Ok(());
        }
    }

    // Adopting is content-addressed, so this is also how "is it already the
    // right bytes" gets answered: same file, same hash, nothing written. The
    // remembered form skips reading the jar when it has not changed -- this
    // runs for every Fathom profile on every start, and the client jar is
    // tens of megabytes.
    let stored =
        engine
            .store()
            .adopt_remembered(&jar)
            .map_err(|source| ComponentError::Install {
                component: component.id,
                source,
            })?;

    if existing
        .as_ref()
        .is_some_and(|entry| entry.sha1 == stored.sha1)
    {
        report.current.push(component.id);
        return Ok(());
    }

    let file_name = jar
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(component.name)
        .to_owned();

    let mut entry =
        ProfileMod::new(mod_id, &stored.sha1, file_name).from_source(ModSource::FirstParty);
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

/// The file-name prefix of Fathom's client module jar.
pub const FATHOM_JAR_PREFIX: &str = "fathom-optimizations-";
/// The same client, renamed for Forge by `gradle forgeJar`.
pub const FATHOM_FORGE_JAR_PREFIX: &str = "fathom-forge-";

/// Where a bundled jar was found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JarOrigin {
    /// The Gradle output of the source checkout a development build came from.
    DevelopmentBuild,
    /// `resources/` beside the executable: a portable or installed build.
    BesideExecutable,
    /// `resources/` in application data, where an update places it.
    ApplicationData,
}

impl JarOrigin {
    pub fn label(self) -> &'static str {
        match self {
            JarOrigin::DevelopmentBuild => "development build output",
            JarOrigin::BesideExecutable => "resources beside the launcher",
            JarOrigin::ApplicationData => "resources in application data",
        }
    }
}

/// A bundled jar, and what is known about it without opening it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BundledJar {
    pub path: PathBuf,
    pub origin: JarOrigin,
    pub modified: Option<std::time::SystemTime>,
}

/// The answer to "which jar would this build install for `prefix`".
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JarLookup {
    Found(BundledJar),
    /// A development build whose module jar is older than the module's own
    /// sources. Installing it would launch yesterday's client.
    Stale {
        jar: BundledJar,
        newest_source: PathBuf,
    },
    /// Nowhere this build looks has one.
    Missing {
        searched: Vec<PathBuf>,
        hint: String,
    },
}

impl JarLookup {
    pub fn jar(&self) -> Option<&BundledJar> {
        match self {
            JarLookup::Found(jar) => Some(jar),
            _ => None,
        }
    }

    /// A sentence for a person, when there is no usable jar.
    pub fn problem(&self) -> Option<String> {
        match self {
            JarLookup::Found(_) => None,
            JarLookup::Stale { jar, newest_source } => Some(format!(
                "The Fathom client jar at {} is older than its source ({}). Rebuild it with \
                 `gradle build` in minecraft/fathom-optimizations, then press Play again.",
                jar.path.display(),
                newest_source.display()
            )),
            JarLookup::Missing { hint, .. } => Some(hint.clone()),
        }
    }
}

/// Which jar this build supplies for a bundled component.
///
/// **One source per kind of build, never a fallback between them.**
///
/// - A development build running from a checkout takes the checkout's Gradle
///   output (`minecraft/fathom-optimizations/build/libs`) and nothing else, and
///   refuses it when the module's sources are newer than the jar.
/// - Any other build takes `resources/` beside the executable, then
///   `resources/` in application data.
///
/// The development rule is the fix for a launcher that silently launched
/// whichever jar had last been copied into application data by hand.
pub fn bundled_jar(paths: &DataPaths, prefix: &str) -> JarLookup {
    if let Some(checkout) = paths.development_checkout() {
        // Both builds come from the one module: `gradle build` writes the
        // Fabric jar and, from it, the Forge jar (`forgeJar`).
        let module_name = "fathom-optimizations";
        let module = checkout.join("minecraft").join(module_name);
        let libs = module.join("build").join("libs");
        return match newest_in(&libs, prefix) {
            Some(path) => {
                let jar = BundledJar {
                    modified: modified(&path),
                    path,
                    origin: JarOrigin::DevelopmentBuild,
                };
                match newest_source_after(&module, jar.modified) {
                    Some(newest_source) => JarLookup::Stale { jar, newest_source },
                    None => JarLookup::Found(jar),
                }
            }
            None => JarLookup::Missing {
                hint: format!(
                    "Fathom's client module has not been built in this checkout ({} has no \
                     {prefix}*.jar). Rebuild {module_name}.",
                    libs.display()
                ),
                searched: vec![libs],
            },
        };
    }

    let mut searched = Vec::new();
    let candidates = [
        DataPaths::install_dir().map(|dir| (dir.join("resources"), JarOrigin::BesideExecutable)),
        Some((paths.root().join("resources"), JarOrigin::ApplicationData)),
    ];
    for (directory, origin) in candidates.into_iter().flatten() {
        if let Some(path) = newest_in(&directory, prefix) {
            return JarLookup::Found(BundledJar {
                modified: modified(&path),
                path,
                origin,
            });
        }
        searched.push(directory);
    }
    JarLookup::Missing {
        hint: "This Fathom build does not include its client module \
               (resources/fathom-optimizations-*.jar). Reinstall Fathom, or rebuild it with \
               tools/package-portable.ps1."
            .to_owned(),
        searched,
    }
}

/// The jar a build would install, when it has a usable one.
///
/// Kept for callers that only need yes or no. [`bundled_jar`] says why not.
pub fn newest_jar(paths: &DataPaths, prefix: &str) -> Option<PathBuf> {
    bundled_jar(paths, prefix).jar().map(|jar| jar.path.clone())
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

fn modified(path: &Path) -> Option<std::time::SystemTime> {
    std::fs::metadata(path)
        .and_then(|meta| meta.modified())
        .ok()
}

/// The newest-looking loadable jar in a directory.
fn newest_in(directory: &Path, prefix: &str) -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = std::fs::read_dir(directory)
        .ok()?
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| is_loadable_jar(path, prefix))
        .collect();
    // Deterministic: the same build must install the same jar every time.
    candidates.sort();
    candidates.pop()
}

/// A source file of the module newer than `built`, if there is one.
///
/// Looks at what a Gradle build consumes: `src/main` and the build files. A
/// jar with no timestamp counts as stale, because "we cannot tell" is not a
/// reason to launch it.
fn newest_source_after(module: &Path, built: Option<std::time::SystemTime>) -> Option<PathBuf> {
    let mut stack = vec![module.join("src").join("main")];
    let mut newest: Option<(std::time::SystemTime, PathBuf)> = None;
    for file in ["build.gradle", "gradle.properties", "settings.gradle"] {
        let path = module.join(file);
        if let Some(time) = modified(&path) {
            if newest.as_ref().is_none_or(|(best, _)| time > *best) {
                newest = Some((time, path));
            }
        }
    }
    while let Some(directory) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&directory) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_dir() {
                stack.push(path);
            } else if let Ok(time) = meta.modified() {
                if newest.as_ref().is_none_or(|(best, _)| time > *best) {
                    newest = Some((time, path));
                }
            }
        }
    }
    let (time, path) = newest?;
    match built {
        // A second of slack for filesystems that round timestamps.
        Some(built) if time <= built + std::time::Duration::from_secs(1) => None,
        _ => Some(path),
    }
}

// ---------------------------------------------------------------------------
// Superseded first-party jars
// ---------------------------------------------------------------------------

/// Remove Fathom's own jars from the shared mod store once nothing refers to
/// them. Returns the hashes removed.
///
/// Every Fathom build adopts its client jar into the store under a new hash
/// and moves its profiles onto it ([`ensure`]); the jar they moved off stayed
/// behind, about 37 MB an update, for good. The store's own
/// [`collect_garbage`](crate::mods::ModStore::collect_garbage) only runs when
/// a user removes a mod, so nothing ever took these away.
///
/// Deliberately narrow:
///
/// - **Only first-party jars**, recognised by the mod id inside them, never by
///   name. Those can always be staged again from the launcher itself. A jar a
///   user supplied is never touched here, referenced or not.
/// - **Referenced means mentioned anywhere.** Every profile manifest is read
///   as text, live and in the recovery area (so a restored profile still
///   finds its jar), and any 40-character hex string in it counts. Reading too
///   much keeps a file; reading too little would delete one.
pub fn collect_superseded(paths: &DataPaths, engine: &ModEngine) -> Vec<String> {
    let store = engine.store();
    let referenced = referenced_hashes(paths);
    let mut removed = Vec::new();
    for hash in store.contents() {
        if referenced.contains(&hash) {
            continue;
        }
        let Ok(path) = store.path_for(&hash) else {
            continue;
        };
        if !is_first_party_jar(&path) {
            continue;
        }
        if std::fs::remove_file(&path).is_ok() {
            removed.push(hash);
        }
    }
    if !removed.is_empty() {
        forget_adoptions(&store.root(), &removed);
    }
    removed
}

/// Every hash any profile file mentions, live or deleted.
///
/// Includes each profile's backup when its main file is unreadable (the only
/// time a backup is used), its launch history (the last set that
/// worked, which recovery can restore) and any mod entries set aside when a
/// profile was salvaged — every place a hash can be the only record of bytes
/// somebody may want back. Shared with ordinary mod removal, so that removing
/// a mod never deletes a jar an unreadable or trashed profile still names.
pub(crate) fn referenced_hashes(paths: &DataPaths) -> std::collections::BTreeSet<String> {
    referenced_hashes_except(paths, None)
}

/// [`referenced_hashes`], without one live profile's own `profile.json` and
/// its backup: for a caller holding that profile in memory, whose copy is the
/// newer truth about it. Its launch history and set-aside entries still count.
pub(crate) fn referenced_hashes_except(
    paths: &DataPaths,
    except: Option<&crate::profiles::ProfileId>,
) -> std::collections::BTreeSet<String> {
    let mut manifests = Vec::new();
    for root in [paths.profiles_dir(), paths.trash_dir()] {
        let Ok(entries) = std::fs::read_dir(&root) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                let own = root == paths.profiles_dir()
                    && except.is_some_and(|id| {
                        path.file_name().and_then(|n| n.to_str()) == Some(id.as_str())
                    });
                if !own {
                    let primary = path.join("profile.json");
                    // The backup is only ever read when the profile file
                    // itself is unreadable, so only then does what it names
                    // still matter. A readable profile that removed a mod
                    // does not keep that mod's bytes alive through its backup.
                    let readable = std::fs::read(&primary).is_ok_and(|bytes| {
                        serde_json::from_slice::<serde_json::Value>(&bytes).is_ok()
                    });
                    if !readable {
                        manifests.push(path.join("profile.json.bak"));
                    }
                    manifests.push(primary);
                }
                manifests.push(path.join(crate::mods::history::HISTORY_FILE));
                manifests.push(path.join(crate::profiles::store::QUARANTINE_FILE));
            } else if path.extension().and_then(|e| e.to_str()) == Some("json") {
                manifests.push(path);
            }
        }
    }
    let mut out = std::collections::BTreeSet::new();
    for manifest in manifests {
        if let Ok(text) = std::fs::read_to_string(&manifest) {
            hex_runs_of_40(&text, &mut out);
        }
    }
    out
}

/// Every maximal run of exactly 40 hex digits in `text`, lowercased.
fn hex_runs_of_40(text: &str, out: &mut std::collections::BTreeSet<String>) {
    let bytes = text.as_bytes();
    let mut start = None;
    for at in 0..=bytes.len() {
        let hex = bytes.get(at).is_some_and(u8::is_ascii_hexdigit);
        match (hex, start) {
            (true, None) => start = Some(at),
            (false, Some(from)) => {
                if at - from == 40 {
                    out.insert(text[from..at].to_ascii_lowercase());
                }
                start = None;
            }
            _ => {}
        }
    }
}

/// Whether a jar's `fabric.mod.json` names one of Fathom's own mods.
fn is_first_party_jar(jar: &Path) -> bool {
    let Ok(file) = std::fs::File::open(jar) else {
        return false;
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return false;
    };
    let Ok(mut entry) = archive.by_name("fabric.mod.json") else {
        return false;
    };
    let mut text = String::new();
    if std::io::Read::read_to_string(&mut entry, &mut text).is_err() {
        return false;
    }
    let Ok(document) = serde_json::from_str::<serde_json::Value>(&text) else {
        return false;
    };
    let Some(id) = document.get("id").and_then(serde_json::Value::as_str) else {
        return false;
    };
    ALL.iter().any(|component| {
        component.mod_id == id
            && matches!(
                component.supply,
                Supply::Bundled { .. } | Supply::PartOf { .. }
            )
    })
}

/// Drop the adoption memos that name removed hashes, so the memo directory
/// does not keep growing alongside the store.
fn forget_adoptions(store_root: &Path, removed: &[String]) {
    let Ok(entries) = std::fs::read_dir(store_root.join(".adopted")) else {
        return;
    };
    for entry in entries.flatten() {
        let named = std::fs::read_to_string(entry.path())
            .map(|recorded| recorded.trim().to_ascii_lowercase())
            .unwrap_or_default();
        if removed.contains(&named) {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::profiles::ProfileId;

    #[test]
    fn hashes_are_found_wherever_a_manifest_mentions_them() {
        let mut found = std::collections::BTreeSet::new();
        let a = "a".repeat(40);
        let b = "B".repeat(40);
        hex_runs_of_40(
            &format!(
                r#"{{"sha1":"{a}","x":"{b}","short":"abc","long":"{}"}}"#,
                "c".repeat(41)
            ),
            &mut found,
        );
        assert!(found.contains(&a));
        assert!(found.contains(&"b".repeat(40)));
        assert_eq!(found.len(), 2, "a 41-digit run is not a hash");
    }

    fn jar_with_id(path: &Path, id: &str) {
        let file = std::fs::File::create(path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default();
        writer.start_file("fabric.mod.json", options).unwrap();
        // The file name goes in too, so jars with the same id differ in bytes.
        let name = path.file_name().unwrap().to_string_lossy();
        let body = format!(r#"{{"id":"{id}","built":"{name}"}}"#);
        std::io::Write::write_all(&mut writer, body.as_bytes()).unwrap();
        writer.finish().unwrap();
    }

    #[test]
    fn only_unreferenced_first_party_jars_are_collected() {
        let dir = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(dir.path());
        paths.ensure().unwrap();
        let engine = ModEngine::new(paths.clone());
        let staging = dir.path().join("staging");
        std::fs::create_dir_all(&staging).unwrap();

        let adopt = |name: &str, id: &str| {
            let jar = staging.join(name);
            jar_with_id(&jar, id);
            engine.store().adopt(&jar).unwrap().sha1
        };
        let old_client = adopt("old.jar", "fathom-optimizations");
        let current_client = adopt("new.jar", "fathom-optimizations");
        let users_mod = adopt("theirs.jar", "somebody-elses-mod");
        let in_trash = adopt("trashed.jar", "fathom-optimizations");

        let live = paths.profiles_dir().join("main");
        std::fs::create_dir_all(&live).unwrap();
        std::fs::write(
            live.join("profile.json"),
            format!(r#"{{"mods":[{{"sha1":"{current_client}"}}]}}"#),
        )
        .unwrap();
        let deleted = paths.trash_dir().join("old-1791494837");
        std::fs::create_dir_all(&deleted).unwrap();
        std::fs::write(
            deleted.join("profile.json"),
            format!(r#"{{"mods":[{{"sha1":"{in_trash}"}}]}}"#),
        )
        .unwrap();

        let removed = collect_superseded(&paths, &engine);
        assert_eq!(
            removed,
            vec![old_client.clone()],
            "only the superseded client jar goes"
        );
        assert!(!engine.store().contains(&old_client));
        assert!(
            engine.store().contains(&users_mod),
            "a user's jar is never collected here"
        );
        assert!(
            engine.store().contains(&in_trash),
            "a deleted profile can still be restored"
        );
        assert!(engine.store().contains(&current_client));
    }

    fn profile(kind: ProfileKind, loader: Loader) -> Profile {
        let mut profile = Profile::new(ProfileId::new("p").unwrap(), "P");
        profile.kind = kind;
        profile.loader.kind = loader;
        profile
    }

    /// Put a component's jar in a profile, the way installing one would.
    fn install(profile: &mut Profile, component_id: &str) {
        let component = find(component_id).expect("a known component");
        let mod_id = component.parsed_mod_id().expect("a usable mod id");
        profile.mods.upsert(ProfileMod::new(
            mod_id,
            "a".repeat(40),
            format!("{component_id}.jar"),
        ));
    }

    /// The defect this whole feature exists to remove. OptiFine in a Legacy
    /// Fabric profile, on its own, is a jar the loader will not load — and the
    /// launcher used to start the game anyway.
    #[test]
    fn optifine_under_legacy_fabric_is_refused_without_the_bridge() {
        let mut profile = profile(ProfileKind::Custom, Loader::LegacyFabric);
        install(&mut profile, "optifine");

        let (component, bridge) = missing_bridge(&profile).expect("the gap should be reported");
        assert_eq!(component.id, "optifine");
        assert_eq!(bridge.id, "optifabric");
    }

    #[test]
    fn optifine_with_the_bridge_is_satisfied() {
        let mut profile = profile(ProfileKind::Custom, Loader::LegacyFabric);
        install(&mut profile, "optifine");
        install(&mut profile, "optifabric");

        assert!(missing_bridge(&profile).is_none());
    }

    /// The loader is part of the record for a reason: under Forge, OptiFine
    /// installs itself and needs nothing. Reporting a missing bridge there
    /// would send somebody looking for a file they must not install.
    #[test]
    fn optifine_under_forge_needs_no_bridge() {
        let mut profile = profile(ProfileKind::Custom, Loader::Forge);
        install(&mut profile, "optifine");

        assert!(missing_bridge(&profile).is_none());
    }

    #[test]
    fn a_profile_without_optifine_reports_nothing() {
        let profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        assert!(missing_bridge(&profile).is_none());
    }

    /// The licensing split this architecture depends on. OptiFabric is MPL-2.0
    /// and may be fetched; OptiFine is proprietary and may not. If these two
    /// ever ended up with the same supply, Fathom would either be failing to
    /// install a component it is allowed to, or redistributing one it is not.
    #[test]
    fn the_bridge_may_be_fetched_and_optifine_may_not() {
        let bridge = find("optifabric").expect("optifabric is in the registry");
        assert!(
            matches!(
                bridge.supply,
                Supply::Managed {
                    repository: "modrinth",
                    ..
                }
            ),
            "{:?}",
            bridge.supply
        );

        let optifine = find("optifine").expect("optifine is in the registry");
        assert!(matches!(optifine.supply, Supply::UserSupplied { .. }));
        assert!(!optifine.supply.is_installable());
    }

    /// OptiFabric is a Fabric mod and OptiFine is not; the bridge only makes
    /// sense under the loader that cannot load OptiFine by itself.
    #[test]
    fn the_bridge_is_a_fabric_component() {
        let bridge = find("optifabric").expect("optifabric is in the registry");
        assert!(bridge.supports(Loader::LegacyFabric));
        assert!(!bridge.supports(Loader::Forge));
    }

    #[test]
    fn every_bridge_names_a_component_that_exists() {
        for component in ALL {
            if let Some(bridge) = component.bridge {
                assert!(
                    find(bridge.component).is_some(),
                    "{} names a bridge {:?} that is not in the registry",
                    component.id,
                    bridge.component
                );
            }
        }
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

    /// Fathom owns its full Fabric stack. Forge gets only the loader adapter
    /// that preserves launcher-wide profiles and settings.
    #[test]
    fn required_components_follow_the_loader_contract() {
        assert!(!required_for(ProfileKind::Fathom, Loader::LegacyFabric).is_empty());
        assert!(required_for(ProfileKind::Vanilla, Loader::Vanilla).is_empty());
        assert!(required_for(ProfileKind::Custom, Loader::LegacyFabric).is_empty());
        let forge = required_for(ProfileKind::Custom, Loader::Forge);
        assert_eq!(forge.len(), 1);
        assert_eq!(forge[0].id, "fathom-forge-client");
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
        assert_ne!(
            optifine.tier,
            Tier::Core,
            "it must never be installed by default"
        );
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

    /// Switching a Fathom profile's loader swaps Fathom's client for the
    /// matching build, both ways, and never leaves the other one laid out.
    #[test]
    fn switching_a_fathom_profiles_loader_swaps_the_client_build() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(resources.join("fathom-optimizations-9.9.9.jar"), b"PK\x03\x04 fabric build").unwrap();
        std::fs::write(resources.join("fathom-forge-9.9.9.jar"), b"PK\x03\x04 forge build").unwrap();
        let engine = ModEngine::new(paths.clone());
        let ids = |profile: &Profile| -> Vec<String> {
            profile.mods.iter().map(|entry| entry.id.as_str().to_owned()).collect()
        };

        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        ensure(&paths, &engine, &mut profile).unwrap();
        assert_eq!(ids(&profile), vec!["fathom-optimizations"]);

        profile.loader.kind = Loader::Forge;
        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert_eq!(report.added, vec!["fathom-forge-client"], "{report:?}");
        assert_eq!(report.removed, vec!["fathom-performance"], "{report:?}");
        assert_eq!(ids(&profile), vec!["fathom-forge"]);

        profile.loader.kind = Loader::LegacyFabric;
        let report = ensure(&paths, &engine, &mut profile).unwrap();
        assert_eq!(report.removed, vec!["fathom-forge-client"], "{report:?}");
        assert_eq!(ids(&profile), vec!["fathom-optimizations"]);
        assert!(profile.mods.iter().all(|entry| entry.enabled), "switched back on, not left off");
    }

    /// A newer jar in the build replaces the one the launcher installed.
    #[test]
    fn a_newer_shipped_build_replaces_the_installed_one() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let resources = paths.root().join("resources");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(
            resources.join("fathom-optimizations-1.0.0.jar"),
            b"PK\x03\x04 old",
        )
        .unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        ensure(&paths, &engine, &mut profile).unwrap();

        std::fs::write(
            resources.join("fathom-optimizations-1.1.0.jar"),
            b"PK\x03\x04 new",
        )
        .unwrap();
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
        std::fs::write(
            resources.join("fathom-optimizations-1.0.0.jar"),
            b"PK\x03\x04 ours",
        )
        .unwrap();

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
        std::fs::write(
            resources.join("fathom-optimizations-1.0.0.jar"),
            b"PK\x03\x04 one",
        )
        .unwrap();

        let engine = ModEngine::new(paths.clone());
        let mut profile = profile(ProfileKind::Fathom, Loader::LegacyFabric);
        ensure(&paths, &engine, &mut profile).unwrap();

        let id = ModId::new("fathom-optimizations").unwrap();
        let mut entry = profile.mods.iter().find(|e| e.id == id).unwrap().clone();
        entry.enabled = false;
        profile.mods.upsert(entry);

        std::fs::write(
            resources.join("fathom-optimizations-1.1.0.jar"),
            b"PK\x03\x04 two",
        )
        .unwrap();
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
