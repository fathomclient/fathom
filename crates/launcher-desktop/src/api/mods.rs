//! Mods, as the interface sees them.
//!
//! # What changed, and what did not
//!
//! This page used to say, at length, that it was not a store: no browse, no
//! search, no install button, because the launcher could not lawfully or safely
//! fetch anything. That was true and it is no longer the whole truth. Modrinth
//! grants third-party applications an API licence, publishes a SHA-1 with every
//! file, and indexes real 1.8.9 mods — so there is now exactly one route by
//! which the launcher can install something on request, and a Browse tab that
//! uses it is describing a capability that exists.
//!
//! What did not change is the rule underneath. **Nothing is offered that cannot
//! be delivered.** Browse only lists versions that match this profile's
//! Minecraft version and loader. Nothing is fetched without a per-action grant.
//! Nothing is written to disk without matching a hash the publisher stated. And
//! anything the launcher may not fetch is still shown as what it is, with the
//! reason, rather than behind a button that fails.
//!
//! OptiFine remains the case that makes this concrete. It is the mod people
//! most want, its licence forbids redistribution, and so the honest surface is
//! still a drop target and a link to the author — not a download button.
//!
//! # The four tabs
//!
//! | Tab | Question |
//! |---|---|
//! | Installed | What will this profile load? |
//! | Browse | What else could it load? |
//! | Updates | What is out of date? |
//! | Issues | What is wrong? |
//!
//! Four tabs rather than one long page because they are four different
//! questions, and only the first has an answer when the user has not asked for
//! anything from the network.

use serde::Serialize;

use launcher_core::mods::resolve::{NameLookup, Severity};
use launcher_core::mods::{DisabledReason, ModId, ModSource};
use launcher_core::profiles::Profile;
use launcher_core::Launcher;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModsView {
    pub profile_name: String,
    pub rows: Vec<ModRow>,
    /// What is wrong overall, if anything: a missing dependency, a conflict.
    pub problems: Vec<ModProblem>,
    /// "4 mods · 3 on", for the page header.
    pub summary: String,
    /// Whether this loader reads a `mods/` directory at all. Vanilla does not.
    ///
    /// Deliberately not the same question as `loader_launchable` below. The two
    /// were conflated once and the result was an "Add a JAR" button on a
    /// Vanilla profile, which would have copied a file somewhere the game never
    /// looks -- the exact class of dishonest affordance this page exists to
    /// avoid.
    pub loader_supports_mods: bool,
    /// Whether Fathom can actually launch this loader today. Vanilla can; the
    /// other two are catalogued and not implemented.
    pub loader_launchable: bool,
    pub loader: String,
    /// What to say, and offer, when this loader cannot load mods.
    ///
    /// Present instead of a page full of disabled controls. A Mods screen with
    /// a greyed-out Add button and eight inert rows tells the user they did
    /// something wrong; one sentence and a button that fixes it tells them what
    /// to do.
    pub loader_notice: Option<LoaderNotice>,
    /// How many rows each filter would show, so a filter that would empty the
    /// list can say so before it is pressed.
    pub counts: Counts,
    /// Why browsing is unavailable, when it is. `None` means it works.
    ///
    /// Sent with the page rather than discovered by failing a search, so the
    /// Browse tab can explain itself instead of showing an error where results
    /// should be.
    pub browse_blocked: Option<String>,
    /// The first-party components this profile calls for, and what state each
    /// is in. Empty for anything that is not a Fathom profile.
    pub components: Vec<ComponentRow>,
    /// The Minecraft version this profile runs. The Browse tab says which
    /// version it is filtering to, and reading that out of the profile name
    /// would be guessing at a string somebody chose.
    pub minecraft: String,
}

/// What each filter would show.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Counts {
    pub all: usize,
    pub enabled: usize,
    pub fathom: usize,
    pub managed: usize,
    pub yours: usize,
    /// Rows with something wrong, which is what the Issues tab counts.
    pub issues: usize,
}

/// One first-party component, and everything recorded about it.
///
/// The component model `FATHOM_MODS_AND_LICENSING.md` asks for, rendered:
/// id, version, Minecraft and loader compatibility, source, hash, dependencies,
/// conflicts, licence, and whether it is managed or user-supplied. Assembled
/// from three places that each own part of the answer — the component registry
/// knows the tier and the supply route, the catalogue knows the licence and the
/// relationships, and the profile knows which bytes are actually installed.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentRow {
    pub id: String,
    pub name: String,
    pub summary: String,
    /// `Core` / `Recommended` / `Optional`.
    pub tier: &'static str,
    /// One word: `Installed`, `Available`, `Missing`, `Yours`.
    pub state: &'static str,
    /// Why it is not installed, when it is not.
    pub blocker: Option<String>,
    /// Present when pressing a button would install it.
    pub installable: bool,
    pub version: Option<String>,
    /// Short SHA-1 prefix of the installed file.
    pub fingerprint: Option<String>,
    pub license: String,
    pub minecraft: String,
    pub loaders: String,
    /// Where the bytes came from, in one word.
    pub source: &'static str,
    pub dependencies: Vec<String>,
    pub conflicts: Vec<String>,
    /// A page a person can read, when there is one.
    pub page: Option<String>,
}

/// A page of search results from the repository.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowseView {
    pub rows: Vec<BrowseRow>,
    pub total: u64,
    pub offset: usize,
    pub query: String,
    /// True when there is another page after this one.
    pub more: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowseRow {
    /// The opaque project id, which is what an install is asked for.
    pub project: String,
    /// The human slug, which is what a project page is built from.
    pub slug: String,
    pub title: String,
    pub summary: String,
    /// "1.2M downloads", already in words.
    pub downloads: String,
    pub license: String,
    /// True when this profile already has it, so the row says so instead of
    /// offering an install that would be a no-op.
    pub installed: bool,
}

/// What is out of date.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdatesView {
    pub rows: Vec<UpdateRow>,
    /// Why the check could not run, when it could not.
    pub blocked: Option<String>,
    /// How many of this profile's mods could be checked at all. A user-supplied
    /// jar has no project to ask about, and saying "0 updates" without saying
    /// that would imply a check that never happened.
    pub checkable: usize,
    pub total: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateRow {
    pub id: String,
    pub name: String,
    pub from: String,
    pub to: String,
    pub project: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderNotice {
    /// One short sentence.
    pub message: String,
    /// The button. Empty when there is nothing useful to offer.
    pub action: String,
    /// The loader that button switches to.
    pub target: String,
    /// The consequence, said before it happens rather than after.
    pub warning: String,
}

/// The notice for a loader that reads no `mods/` directory.
///
/// `None` for every loader that does -- including Legacy Fabric, which is why a
/// Fathom profile never sees this at all. That is the point: the profile type
/// solved it at setup time.
fn loader_notice(loader: launcher_core::profiles::Loader) -> Option<LoaderNotice> {
    if loader.supports_mods() {
        return None;
    }
    // Legacy Fabric rather than Forge: it is the loader this build can
    // actually resolve and start, and offering one that refuses would be a
    // button that produces an error.
    let target = launcher_core::profiles::Loader::LegacyFabric;
    Some(LoaderNotice {
        message: "Mods need a loader.".to_owned(),
        action: format!("Switch to {}", target.label()),
        target: crate::api::profiles::loader_id(target).to_owned(),
        warning: "Changing the loader takes effect next launch.".to_owned(),
    })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModRow {
    pub id: String,
    pub name: String,
    /// One line, or empty. Never a paragraph.
    pub summary: String,
    pub enabled: bool,
    /// Whether the user may switch it: a mod disabled because it conflicts is
    /// not a mod the user should be able to switch back on by accident.
    pub can_toggle: bool,
    /// "Yours" or "Fathom" — where the file came from, in one word.
    pub origin: &'static str,
    pub version: String,
    pub size: String,
    /// Present when something is wrong with this specific mod.
    pub warning: Option<String>,
    /// Present when the launcher no longer has the bytes this entry names.
    ///
    /// Deliberately a question about the **mod store**, not about the profile's
    /// `mods/` directory. That directory is rebuilt from the store at launch,
    /// so a mod added five seconds ago is legitimately not in it yet -- and
    /// reporting that as "File missing" told every freshly imported mod it had
    /// gone astray. The store is the record; the directory is a layout.
    pub missing: bool,
    /// Which filter chip this row belongs to: `fathom`, `managed` or `yours`.
    pub group: &'static str,
    /// The licence, as recorded when it was installed or as the catalogue
    /// states it. "Unknown" for a jar somebody dropped in, which is the honest
    /// answer for one.
    pub license: String,
    /// The repository slug, when this came from one, so its page can be opened.
    pub slug: Option<String>,
    /// True when this entry carries what an update check needs.
    pub updatable: bool,
}

/// Bytes, in words.
///
/// Coarse on purpose: nobody choosing which mods to run needs three
/// significant figures, and the precision would only make the row noisier.
fn humanise_size(bytes: u64) -> String {
    const MB: u64 = 1024 * 1024;
    const KB: u64 = 1024;
    if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

/// Where this file came from, in one word.
///
/// Read from the entry's own `source` -- the route the *file* took -- and not
/// from the catalogue's provenance, which describes where our *knowledge* of
/// the mod came from. Those are different facts, and conflating them put a
/// "Fathom" badge on an OptiFine jar the user had supplied themselves, which
/// is the opposite of true for the one mod whose whole legal position is that
/// Fathom never provides it.
fn origin_of(source: ModSource) -> &'static str {
    match source {
        ModSource::UserSupplied => "Yours",
        ModSource::FirstParty => "Fathom",
        ModSource::Managed => "Managed",
    }
}

/// One thing wrong with the set, with a tone so the page can rank them.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModProblem {
    pub message: String,
    /// `note` / `warning` / `blocks launch`.
    pub severity: String,
    pub blocking: bool,
}

/// Why a mod is off, in a sentence somebody can act on.
fn warning_for(reason: &DisabledReason) -> Option<String> {
    match reason {
        // The ordinary case: the user switched it off. Not a warning, and
        // rendering it as one would put a badge on every deliberate choice.
        DisabledReason::UserChoice => None,
        DisabledReason::Conflict { detail, .. } => Some(if detail.is_empty() {
            "Off because it conflicts with another mod in this profile.".to_owned()
        } else {
            detail.clone()
        }),
    }
}

/// The result of importing a jar the user chose.
///
/// `cancelled` is a first-class answer rather than an error: closing a file
/// picker is a normal thing to do, and reporting it as a failure would put a
/// red message on screen for a decision the user made deliberately.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Imported {
    pub cancelled: bool,
    /// What it turned out to be. The filename when nothing better is known.
    pub name: String,
    /// The mod's own version, when the filename said.
    pub version: Option<String>,
    /// The Minecraft version it names, when the filename said.
    pub minecraft: Option<String>,
    /// True when this is a file the launcher could never have fetched itself.
    /// Shown as reassurance, not as a warning.
    pub user_supplied: bool,
    /// Short SHA-1 prefix, so somebody can tell two builds apart.
    pub fingerprint: String,
}

impl Imported {
    pub fn cancelled() -> Self {
        Self {
            cancelled: true,
            name: String::new(),
            version: None,
            minecraft: None,
            user_supplied: false,
            fingerprint: String::new(),
        }
    }

    pub fn of(
        entry: &launcher_core::mods::ProfileMod,
        identified: &launcher_core::mods::Identified,
    ) -> Self {
        Self {
            cancelled: false,
            name: identified
                .name
                .clone()
                .unwrap_or_else(|| entry.file_name.clone()),
            version: identified.version.clone(),
            minecraft: identified.minecraft.clone(),
            user_supplied: entry.source == launcher_core::mods::ModSource::UserSupplied,
            fingerprint: entry.sha1.chars().take(7).collect(),
        }
    }
}

pub fn view(core: &Launcher, profile: &Profile) -> ModsView {
    let installed = core.mods_for(&profile.id);
    let resolution = core.resolve_mods(profile);
    // Problems name mods by id; the lookup turns those into the names the page
    // is already showing, so a warning and the row it is about agree.
    let names = NameLookup::from_definitions(&core.mod_catalogue());

    let rows: Vec<ModRow> = profile
        .mods
        .iter()
        .map(|entry| {
            let definition = core.mod_definition(&entry.id);
            // Laid out in the profile already? Only used for the size, which
            // is a fact about a file that exists.
            let on_disk = installed
                .iter()
                .find(|candidate| candidate.sha1 == entry.sha1);
            // Do we still have the bytes at all? This is the one that decides
            // whether something is actually wrong.
            let in_store = core.mod_engine().store().contains(&entry.sha1);

            ModRow {
                id: entry.id.as_str().to_owned(),
                name: definition
                    .as_ref()
                    .map_or_else(|| tidy(&entry.file_name), |d| d.name.clone()),
                summary: definition
                    .as_ref()
                    .map_or_else(String::new, |d| d.summary.clone()),
                enabled: entry.enabled,
                // A mod switched off automatically is describing a fact about
                // the world, not a preference, so it is not the user's to flip.
                can_toggle: !entry.disabled_automatically(),
                origin: origin_of(entry.source),
                version: entry.version.as_ref().map_or_else(
                    || {
                        definition
                            .as_ref()
                            .map_or_else(|| "—".to_owned(), |d| d.version.0.clone())
                    },
                    |version| version.0.clone(),
                ),
                size: on_disk.map_or_else(|| "—".to_owned(), |file| humanise_size(file.size)),
                warning: entry.disabled_reason.as_ref().and_then(warning_for),
                missing: !in_store,
                group: group_of(entry.source),
                // The entry's own record first, the catalogue second. The
                // record is what was true when this file was installed; the
                // catalogue is what Fathom believes about the mod in general,
                // and only one of those is a fact about the bytes on disk.
                license: entry.origin.license.clone().unwrap_or_else(|| {
                    definition
                        .as_ref()
                        .map_or_else(|| "Unknown".to_owned(), |d| d.license.describe())
                }),
                slug: entry.origin.project.clone(),
                updatable: entry.origin.is_updatable(),
            }
        })
        .collect();

    let enabled = rows.iter().filter(|row| row.enabled).count();
    // Counted from the resolution rather than from the rows: a problem can be
    // about the set as a whole -- a missing component, a loader that reads no
    // mods -- and belongs in the Issues count even though no single row carries
    // it.
    let problems_count = resolution.problems.len();

    ModsView {
        profile_name: profile.display_name.clone(),
        summary: format!(
            "{} {} · {enabled} on",
            rows.len(),
            if rows.len() == 1 { "mod" } else { "mods" }
        ),
        problems: resolution
            .problems
            .iter()
            .map(|problem| ModProblem {
                message: problem.message(&names),
                severity: problem.severity().label().to_owned(),
                blocking: problem.severity() == Severity::Blocking,
            })
            .collect(),
        loader_notice: loader_notice(profile.loader_kind()),
        loader_supports_mods: profile.loader_kind().supports_mods(),
        loader_launchable: profile.loader_kind().is_implemented(),
        loader: profile.loader_kind().label().to_owned(),
        counts: Counts {
            all: rows.len(),
            enabled,
            fathom: rows.iter().filter(|row| row.group == "fathom").count(),
            managed: rows.iter().filter(|row| row.group == "managed").count(),
            yours: rows.iter().filter(|row| row.group == "yours").count(),
            issues: problems_count,
        },
        browse_blocked: core.browsing_blocked().map(str::to_owned),
        components: components_for(core, profile),
        minecraft: profile.version_id.clone(),
        rows,
    }
}

/// Which filter chip a row belongs to.
fn group_of(source: ModSource) -> &'static str {
    match source {
        ModSource::FirstParty => "fathom",
        ModSource::Managed => "managed",
        ModSource::UserSupplied => "yours",
    }
}

/// The first-party components this profile calls for, fully described.
///
/// Only for a profile whose kind actually owns components. A Custom profile
/// containing the same jars chose them, and listing them here as "components"
/// would imply Fathom maintains them, which for a Custom profile it does not.
fn components_for(core: &Launcher, profile: &Profile) -> Vec<ComponentRow> {
    use launcher_core::components::{self, Supply, Tier};

    if !profile.kind.owns_components() {
        return Vec::new();
    }
    let loader = profile.loader_kind();
    let mut out = Vec::new();

    for component in components::required_for(profile.kind, loader)
        .into_iter()
        .chain(components::optional_for(loader))
    {
        let mod_id = component.parsed_mod_id();
        let installed = mod_id.as_ref().and_then(|id| profile.mods.get(id));
        let definition = mod_id.as_ref().and_then(|id| core.mod_definition(id));

        let (state, source) = match installed {
            Some(entry) if entry.source == launcher_core::mods::ModSource::UserSupplied => {
                ("Yours", origin_of(entry.source))
            }
            Some(entry) => ("Installed", origin_of(entry.source)),
            None if component.supply.is_requestable() => ("Available", "—"),
            // Three different absences, and calling them all "Missing" was
            // wrong for two of them.
            //
            // OptiFine has never been added, cannot be added by Fathom, and
            // nothing is broken -- it is an optional extra the user has not
            // supplied. Rendering that as "Missing" beside a red-ish tone reads
            // as a fault, and the fault it suggests is one nobody can fix,
            // because Fathom is never going to fetch it.
            //
            // A CORE component that is absent genuinely is missing: the profile
            // promised it. That is the only case that keeps the word.
            None if matches!(component.supply, Supply::UserSupplied { .. }) => ("Not added", "—"),
            None if component.tier == Tier::Core => ("Missing", "—"),
            None => ("Not added", "—"),
        };

        out.push(ComponentRow {
            id: component.id.to_owned(),
            name: component.name.to_owned(),
            summary: component.summary.to_owned(),
            tier: match component.tier {
                Tier::Core => "Core",
                Tier::Recommended => "Recommended",
                Tier::Optional => "Optional",
            },
            state,
            blocker: if installed.is_some() {
                None
            } else {
                component.supply.blocker().map(str::to_owned)
            },
            // Only ever true for something the launcher can actually fetch on
            // request. A bundled component that is missing is a build problem,
            // not a button.
            installable: installed.is_none() && component.supply.is_requestable(),
            version: installed
                .and_then(|entry| entry.version.as_ref().map(ToString::to_string))
                .or_else(|| definition.as_ref().map(|d| d.version.0.clone())),
            fingerprint: installed.map(|entry| entry.sha1.chars().take(7).collect()),
            license: installed
                .and_then(|entry| entry.origin.license.clone())
                .or_else(|| definition.as_ref().map(|d| d.license.describe()))
                .unwrap_or_else(|| "Unknown".to_owned()),
            minecraft: definition
                .as_ref()
                .map_or_else(|| "1.8.9".to_owned(), |d| d.minecraft.describe()),
            loaders: component
                .loaders
                .iter()
                .map(|loader| loader.label())
                .collect::<Vec<_>>()
                .join(", "),
            source,
            dependencies: definition
                .as_ref()
                .map(|d| {
                    d.dependencies
                        .iter()
                        .map(|dependency| dependency.id.as_str().to_owned())
                        .collect()
                })
                .unwrap_or_default(),
            conflicts: definition
                .as_ref()
                .map(|d| {
                    d.conflicts
                        .iter()
                        .map(|conflict| conflict.id.as_str().to_owned())
                        .collect()
                })
                .unwrap_or_default(),
            page: match component.supply {
                Supply::UserSupplied { where_to_get } | Supply::AwaitingSource { where_to_get } => {
                    Some(where_to_get.to_owned())
                }
                _ => component.supply.where_to_get().map(str::to_owned),
            },
        });
    }
    out
}

/// A page of search results, ready to render.
pub fn browse(
    core: &Launcher,
    profile: &Profile,
    query: &str,
    offset: usize,
) -> Result<BrowseView, String> {
    let found = core
        .browse_mods(profile, query, offset)
        .map_err(|error| error.to_string())?;

    let rows = found
        .hits
        .iter()
        .map(|hit| BrowseRow {
            project: hit.project_id.clone(),
            slug: hit.slug.clone(),
            title: hit.title.clone(),
            summary: hit.summary.clone(),
            downloads: downloads(hit.downloads),
            license: hit.licence().describe(),
            // Matched on the project rather than on a name: two projects can
            // share a title, and only the id is the same thing.
            installed: profile
                .mods
                .iter()
                .any(|entry| entry.origin.project.as_deref() == Some(hit.project_id.as_str())),
        })
        .collect::<Vec<_>>();

    let shown = found.offset + rows.len();
    Ok(BrowseView {
        more: (shown as u64) < found.total,
        rows,
        total: found.total,
        offset: found.offset,
        query: query.to_owned(),
    })
}

/// A download count, in words.
fn downloads(count: u64) -> String {
    if count >= 1_000_000 {
        format!("{:.1}M downloads", count as f64 / 1_000_000.0)
    } else if count >= 1_000 {
        format!("{}K downloads", count / 1_000)
    } else {
        format!("{count} downloads")
    }
}

/// What is out of date, and what could not be checked.
pub fn updates(core: &Launcher, profile: &Profile) -> UpdatesView {
    let checkable = profile
        .mods
        .iter()
        .filter(|entry| entry.origin.is_updatable())
        .count();
    let total = profile.mods.len();

    if let Some(reason) = core.browsing_blocked() {
        return UpdatesView {
            rows: Vec::new(),
            blocked: Some(reason.to_owned()),
            checkable,
            total,
        };
    }

    match core.mod_updates(profile) {
        Ok(found) => UpdatesView {
            rows: found
                .into_iter()
                .map(|update| UpdateRow {
                    id: update.id.as_str().to_owned(),
                    name: tidy(&update.display_name),
                    from: update.from,
                    to: update.to,
                    project: update.project,
                })
                .collect(),
            blocked: None,
            checkable,
            total,
        },
        Err(error) => UpdatesView {
            rows: Vec::new(),
            blocked: Some(error.to_string()),
            checkable,
            total,
        },
    }
}

pub fn parse_id(id: &str) -> Result<ModId, String> {
    ModId::new(id).map_err(|error| format!("invalid mod: {error}"))
}

/// A filename, made readable.
///
/// Only used when the catalogue has never heard of the mod, which is the normal
/// case for a jar the user supplied: there is nothing to look up, and the
/// filename is the only name it has.
fn tidy(file_name: &str) -> String {
    file_name
        .strip_suffix(".jar")
        .unwrap_or(file_name)
        .replace(['_', '-'], " ")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The two loader questions are different, and answering one with the other
    /// is how "Add a JAR" appeared on a profile that reads no mods directory.
    ///
    /// Vanilla is the case that separates them: Fathom can launch it, and it
    /// loads no mods. If these ever agree for every loader, one of them has
    /// stopped meaning anything.
    #[test]
    fn launchable_and_mod_capable_are_different_questions() {
        use launcher_core::profiles::Loader;
        assert!(Loader::Vanilla.is_implemented());
        assert!(!Loader::Vanilla.supports_mods());
        // Legacy Fabric is now both. Vanilla alone carries the distinction,
        // which is enough: the two questions have different answers for at
        // least one loader, which is what stops either being a synonym for the
        // other.
        assert!(Loader::LegacyFabric.supports_mods());
        assert!(Loader::LegacyFabric.is_implemented());
    }

    #[test]
    fn sizes_read_as_words() {
        assert_eq!(humanise_size(512), "512 B");
        assert_eq!(humanise_size(2048), "2 KB");
        assert_eq!(humanise_size(3 * 1024 * 1024), "3.0 MB");
    }

    /// A jar name is not a title, and the page should not print one as if it
    /// were.
    /// A Vanilla profile gets one sentence and a way out, not a page of
    /// disabled controls.
    #[test]
    fn a_loader_that_reads_no_mods_directory_offers_a_way_out() {
        let notice = loader_notice(launcher_core::profiles::Loader::Vanilla)
            .expect("vanilla needs a notice");
        assert!(notice.message.len() < 40, "{}", notice.message);
        assert_eq!(notice.target, "legacy-fabric");
        assert!(!notice.warning.is_empty());
    }

    /// And the loader a Fathom profile uses never sees one, which is the whole
    /// reason the profile type exists.
    #[test]
    fn a_loader_that_reads_mods_needs_no_notice() {
        assert!(loader_notice(launcher_core::profiles::Loader::LegacyFabric).is_none());
        assert!(loader_notice(launcher_core::profiles::Loader::Forge).is_none());
    }

    /// The way out must lead somewhere that works.
    #[test]
    fn the_offered_loader_is_one_that_launches() {
        let notice = loader_notice(launcher_core::profiles::Loader::Vanilla).unwrap();
        let target = crate::api::profiles::loader_from_id(&notice.target).unwrap();
        assert!(target.is_implemented());
        assert!(target.supports_mods());
    }

    #[test]
    fn a_filename_becomes_a_name() {
        assert_eq!(tidy("fathom-optimizations.jar"), "fathom optimizations");
        assert_eq!(tidy("some_mod"), "some mod");
    }

    /// The user switching something off is not a warning, and rendering it as
    /// one would put a yellow badge on every deliberately disabled mod.
    #[test]
    fn a_deliberate_choice_is_not_a_warning() {
        assert!(warning_for(&DisabledReason::UserChoice).is_none());
        assert!(warning_for(&DisabledReason::Conflict {
            with: ModId::new("optifine").expect("a valid id"),
            detail: "OptiFine replaces the same renderer.".to_owned(),
        })
        .is_some());
    }
}
