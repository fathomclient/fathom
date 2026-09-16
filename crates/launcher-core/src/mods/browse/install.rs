//! Carrying out a plan: fetch, verify, record — in that order, all or nothing.
//!
//! # Where bytes go
//!
//! Nothing here writes to a profile's `mods/` directory. Downloads land in the
//! content-addressed [`ModStore`] through [`DownloadManager`], which streams to
//! a `.partial`, hashes while it writes, checks size and SHA-1, and only then
//! renames. The profile's [`ProfileModConfiguration`] is what changes, and the
//! existing materialize step copies verified store files into the profile's
//! own game directory at launch. So:
//!
//! - a half-downloaded jar never exists under a real name anywhere;
//! - a jar never reaches a mods directory that is not the selected profile's;
//! - nothing downloaded is ever opened, unpacked or executed — the first two
//!   bytes are read to confirm it is a zip at all, and that is all.
//!
//! # All or nothing
//!
//! Every file in a plan is fetched and verified before the profile is touched.
//! If the third of four downloads fails, the profile is exactly as it was. The
//! verified files already fetched stay in the store, unreferenced, where a
//! retry reuses them and garbage collection eventually removes them.
//!
//! # Updates
//!
//! An update is a plan whose root replaces an existing entry. The new entry
//! takes the old one's [`ModId`], so the profile can never hold both versions:
//! there is one entry, and it points at one hash. The old entry is returned in
//! the [`InstallReport`] so a failure after this point (saving the profile, say)
//! can put it back, and the old bytes stay in the store until garbage
//! collection finds nothing refers to them.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;

use serde::Serialize;

use super::dependencies::{Blocker, InstallPlan, PlannedMod};
use super::identity::{already_installed, same_project, Duplicate};
use super::model::{clip, limits, safe_file_name, FileDownload, Provider, Target};
use super::provider::BrowseError;
use crate::install::download::{
    verify_file, DownloadError, DownloadManager, DownloadRequest, Expectation,
};
use crate::mods::config::{ModSource, Origin, ProfileMod, ProfileModConfiguration};
use crate::mods::definition::ModId;
use crate::mods::store::ModStore;
use crate::network::{descriptor_from_url, Feature, PerActionGrant, Purpose};
use crate::tasks::Cancel;

/// Choices the user made about a plan.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct InstallOptions {
    /// The user saw the "this may already be installed from the other index"
    /// warning and chose to install anyway.
    pub accept_possible_duplicates: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum InstallPhase {
    Checking,
    Downloading,
    Recording,
    Done,
}

/// Progress, reported per file. Cheap to clone and send across threads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallProgress {
    pub phase: InstallPhase,
    pub completed: usize,
    pub total: usize,
    /// The mod being worked on, for display.
    pub current: String,
}

/// What an install changed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallReport {
    /// Entries added or rewritten, dependencies first.
    pub added: Vec<ProfileMod>,
    /// The entry an update replaced, kept for [`rollback`].
    pub replaced: Option<ProfileMod>,
    /// Files transferred.
    pub downloaded: usize,
    /// Files the store already had.
    pub reused: usize,
}

/// Undo the profile half of an install whose save failed.
///
/// Removes what was added and restores what was replaced. The store is not
/// touched: verified bytes are harmless there and garbage collection owns them.
pub fn rollback(report: &InstallReport, mods: &mut ProfileModConfiguration) {
    for added in &report.added {
        mods.remove(&added.id);
    }
    if let Some(replaced) = &report.replaced {
        mods.upsert(replaced.clone());
    }
}

/// Fetches and records plans. Clone-cheap and `Send`.
#[derive(Debug, Clone)]
pub struct Installer {
    downloads: DownloadManager,
    store: ModStore,
}

fn describe(blocker: &Blocker) -> String {
    match blocker {
        Blocker::NoModLoader => "this profile has no mod loader".to_owned(),
        Blocker::Incompatible { detail, .. } | Blocker::AlreadyInstalled { detail, .. } => {
            detail.clone()
        }
        Blocker::NotDownloadable { project, .. } => format!(
            "{project} cannot be downloaded by Fathom; its author distributes it elsewhere"
        ),
        Blocker::Unresolved { dependency, detail, .. } => {
            format!("a required dependency ({dependency}) could not be resolved: {detail}")
        }
        Blocker::UnnamedDependency { .. } => {
            "a required dependency does not say which project it is".to_owned()
        }
        Blocker::Conflict { conflicts_with, .. } => {
            format!("it is marked incompatible with {conflicts_with}")
        }
        Blocker::TooLarge => "its dependency tree is larger than Fathom will resolve".to_owned(),
    }
}

fn download_error(provider: Provider, error: DownloadError) -> BrowseError {
    match error {
        DownloadError::Network(network) => BrowseError::from_network(provider, &network),
        DownloadError::Cancelled => BrowseError::Cancelled,
        DownloadError::HashMismatch { name, .. } => BrowseError::Refused(format!(
            "{} did not match its published hash and was discarded",
            clip(&name, limits::NAME)
        )),
        DownloadError::SizeMismatch { name, .. } => BrowseError::Refused(format!(
            "{} was not the size its index declared and was discarded",
            clip(&name, limits::NAME)
        )),
        DownloadError::TooLarge { name, .. } => BrowseError::Refused(format!(
            "{} is larger than Fathom will download",
            clip(&name, limits::NAME)
        )),
        DownloadError::Exhausted { last, .. } => BrowseError::Unreachable {
            provider,
            detail: clip(&last, 200),
        },
        // The path is private and the user cannot act on it; the log has it.
        DownloadError::Fs(error) => {
            tracing::warn!(%error, "could not write to the mod store");
            BrowseError::Refused("Fathom could not write to its mod store".to_owned())
        }
        DownloadError::Io { source, .. } => {
            tracing::warn!(error = %source, "could not write to the mod store");
            BrowseError::Refused("Fathom could not write to its mod store".to_owned())
        }
    }
}

/// Whether a file begins like a zip. Reads two bytes and nothing else.
fn looks_like_a_jar(path: &std::path::Path) -> bool {
    let mut magic = [0_u8; 2];
    std::fs::File::open(path)
        .and_then(|mut file| file.read_exact(&mut magic))
        .is_ok()
        && &magic == b"PK"
}

/// Whether an entry was installed from this project at this index.
fn entry_is(entry: &ProfileMod, planned: &PlannedMod) -> bool {
    let reference = &planned.project.reference;
    entry.origin.repository.as_deref().and_then(Provider::parse) == Some(reference.provider)
        && entry.origin.project.as_deref() == Some(reference.id.as_str())
}

/// A stable profile id for a mod, derived from its slug rather than its file
/// name, so the next version (whose file name differs) is the same entry.
fn choose_id(mods: &ProfileModConfiguration, planned: &PlannedMod) -> Result<ModId, BrowseError> {
    let stem = if planned.project.slug.is_empty() {
        planned.project.name.as_str()
    } else {
        planned.project.slug.as_str()
    };
    let base = crate::mods::id_from_file_name(&format!("{stem}.jar"))
        .or_else(|_| crate::mods::id_from_file_name(&planned.file.file_name))
        .map_err(|_| BrowseError::Refused("that mod has no usable name".to_owned()))?;
    let provider = planned.project.reference.provider.id();
    for candidate in [base.to_string(), format!("{base}-{provider}")] {
        let Ok(id) = ModId::new(&candidate) else {
            continue;
        };
        match mods.get(&id) {
            None => return Ok(id),
            // Only an update may reuse an id, and updates do not come here.
            Some(existing) if entry_is(existing, planned) => {
                return Err(BrowseError::Refused(format!(
                    "{} is already installed",
                    planned.project.name
                )))
            }
            Some(_) => {}
        }
    }
    Err(BrowseError::Refused(format!(
        "another mod in this profile already uses the name {base}"
    )))
}

impl Installer {
    pub fn new(downloads: DownloadManager, store: ModStore) -> Self {
        Self { downloads, store }
    }

    pub fn store(&self) -> &ModStore {
        &self.store
    }

    /// Everything checked again, immediately before any bytes move.
    ///
    /// A plan is a snapshot. The profile may have changed version, gained a
    /// mod, or lost the entry being updated since it was made; and a plan is a
    /// value the interface held, so it is re-validated rather than trusted.
    fn validate(
        plan: &InstallPlan,
        target: &Target,
        mods: &ProfileModConfiguration,
        options: InstallOptions,
    ) -> Result<(), BrowseError> {
        if &plan.target != target {
            return Err(BrowseError::Incompatible(
                "the profile's Minecraft version or loader changed since this was planned"
                    .to_owned(),
            ));
        }
        if !target.loads_mods() {
            return Err(BrowseError::Incompatible(
                "this profile has no mod loader".to_owned(),
            ));
        }
        if let Some(blocker) = plan.blockers.first() {
            return Err(match blocker {
                Blocker::Incompatible { detail, .. } => BrowseError::Incompatible(detail.clone()),
                other => BrowseError::Refused(describe(other)),
            });
        }
        if plan.needs_duplicate_confirmation() && !options.accept_possible_duplicates {
            return Err(BrowseError::Refused(
                "this may already be installed from another source; confirm before installing"
                    .to_owned(),
            ));
        }
        let replacing = match &plan.replaces {
            Some(id) => {
                let id = ModId::new(id)
                    .map_err(|_| BrowseError::Refused("that is not a mod id".to_owned()))?;
                Some(mods.get(&id).cloned().ok_or_else(|| {
                    BrowseError::Refused("the mod being updated is no longer installed".to_owned())
                })?)
            }
            None => None,
        };

        let others: Vec<ProfileMod> = mods
            .iter()
            .filter(|entry| replacing.as_ref().is_none_or(|r| r.id != entry.id))
            .cloned()
            .collect();
        let mut names = BTreeSet::new();
        for planned in plan.installs() {
            let file = &planned.file;
            if !file.supports(target) {
                return Err(BrowseError::Incompatible(format!(
                    "{} {} is not built for Minecraft {} on {}",
                    planned.project.name,
                    file.number,
                    target.minecraft,
                    target.loader.label()
                )));
            }
            if !safe_file_name(&file.file_name) {
                return Err(BrowseError::Refused(
                    "the index offered a file name Fathom will not write".to_owned(),
                ));
            }
            if !names.insert(file.file_name.to_ascii_lowercase()) {
                return Err(BrowseError::Refused(format!(
                    "two mods in this install share the file name {}",
                    file.file_name
                )));
            }
            match &file.download {
                FileDownload::Url { url } => {
                    descriptor_from_url(url, Purpose::DownloadMod, Feature::ModBrowse).map_err(
                        |_| BrowseError::Refused(format!(
                            "{} would be downloaded from a host Fathom does not allow",
                            planned.project.name
                        )),
                    )?;
                }
                FileDownload::Withheld { .. } => {
                    return Err(BrowseError::Refused(format!(
                        "{} cannot be downloaded by Fathom; its author distributes it elsewhere",
                        planned.project.name
                    )))
                }
            }
            // Installed since the plan was made?
            if let Some((entry, Duplicate::Same { reason })) =
                already_installed(&others, &planned.project, Some(file))
            {
                tracing::debug!(entry = %entry.id, "refusing a duplicate install");
                return Err(BrowseError::Refused(reason));
            }
            if others
                .iter()
                .any(|entry| entry.file_name.eq_ignore_ascii_case(&file.file_name))
            {
                return Err(BrowseError::Refused(format!(
                    "this profile already has a file named {}",
                    file.file_name
                )));
            }
        }
        Ok(())
    }

    /// Fetch, verify and record a plan into `mods`.
    ///
    /// `target` is the profile's target *now*. `mods` is modified only after
    /// every file is verified in the store; the caller saves the profile and
    /// calls [`rollback`] if that fails.
    ///
    /// Blocking. Run on a worker thread.
    ///
    /// Long because it is one transaction: splitting the stage and record
    /// halves apart would invite a caller to run one without the other.
    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    pub fn execute(
        &self,
        plan: &InstallPlan,
        target: &Target,
        mods: &mut ProfileModConfiguration,
        options: InstallOptions,
        grant: &PerActionGrant,
        cancel: &Cancel,
        progress: &mut dyn FnMut(&InstallProgress),
    ) -> Result<InstallReport, BrowseError> {
        let total = plan.download_count();
        progress(&InstallProgress {
            phase: InstallPhase::Checking,
            completed: 0,
            total,
            current: plan.root.project.name.clone(),
        });
        Self::validate(plan, target, mods, options)?;

        // -- stage: every file verified in the store before anything else ----
        let mut downloaded = 0;
        let mut reused = 0;
        for (index, planned) in plan.installs().enumerate() {
            if cancel.is_cancelled() {
                return Err(BrowseError::Cancelled);
            }
            progress(&InstallProgress {
                phase: InstallPhase::Downloading,
                completed: index,
                total,
                current: planned.project.name.clone(),
            });
            let provider = planned.project.reference.provider;
            let file = &planned.file;
            let path = self
                .store
                .path_for(&file.hash.sha1)
                .map_err(|_| BrowseError::Refused("the index published an invalid hash".to_owned()))?;

            if verify_file(&path, &file.hash.sha1) {
                reused += 1;
            } else {
                let FileDownload::Url { url } = &file.download else {
                    return Err(BrowseError::Refused("that file is not downloadable".to_owned()));
                };
                let request = descriptor_from_url(url, Purpose::DownloadMod, Feature::ModBrowse)
                    .map_err(|_| BrowseError::Refused("that download host is not allowed".to_owned()))?;
                self.downloads
                    .fetch(
                        &DownloadRequest {
                            name: clip(&planned.project.name, limits::NAME),
                            request,
                            expect: Expectation::new(file.hash.sha1.clone(), file.hash.size),
                            target: path.clone(),
                        },
                        Some(grant),
                        cancel,
                    )
                    .map_err(|error| download_error(provider, error))?;
                downloaded += 1;
            }
            // The hash matched what the index published. Whether the index
            // published a jar is a separate question, and a file that is not a
            // zip never becomes a mod.
            if !looks_like_a_jar(&path) {
                let _ = std::fs::remove_file(&path);
                return Err(BrowseError::Refused(format!(
                    "{} is not a jar file and was discarded",
                    planned.project.name
                )));
            }
        }
        if cancel.is_cancelled() {
            return Err(BrowseError::Cancelled);
        }

        // -- record: one swap of the whole configuration -------------------
        progress(&InstallProgress {
            phase: InstallPhase::Recording,
            completed: total,
            total,
            current: plan.root.project.name.clone(),
        });
        let replaced = plan
            .replaces
            .as_deref()
            .and_then(|id| ModId::new(id).ok())
            .and_then(|id| mods.get(&id).cloned());

        let mut next = mods.clone();
        let mut ids: BTreeMap<String, String> = plan
            .satisfied
            .iter()
            .map(|s| (s.dependency.qualified(), s.installed_id.clone()))
            .collect();
        let mut added = Vec::new();
        let planned_all: Vec<&PlannedMod> = plan.installs().collect();

        for planned in &planned_all {
            let is_root = std::ptr::eq(*planned, std::ptr::from_ref(&plan.root));
            let id = match (&replaced, is_root) {
                (Some(previous), true) => previous.id.clone(),
                _ => choose_id(&next, planned)?,
            };
            let previous = next.get(&id).cloned();

            let requires: BTreeSet<String> = planned
                .file
                .dependencies
                .iter()
                .filter_map(|dependency| dependency.project.as_ref())
                .filter_map(|reference| {
                    ids.get(&reference.qualified()).cloned().or_else(|| {
                        // Named on one index, satisfied by the same mod from
                        // the other.
                        planned_all
                            .iter()
                            .find(|other| {
                                other.project.reference == *reference
                                    || plan.dependencies.iter().any(|d| {
                                        d.project.reference == *reference
                                            && same_project(&d.project, &other.project)
                                                .is_certain()
                                    })
                            })
                            .and_then(|other| ids.get(&other.project.reference.qualified()).cloned())
                    })
                })
                .filter(|required| required != id.as_str())
                .collect();

            let project = &planned.project;
            let file = &planned.file;
            let mut entry = ProfileMod::new(id.clone(), &file.hash.sha1, file.file_name.clone())
                .from_source(ModSource::Managed)
                .with_origin(Origin {
                    name: Some(clip(&project.name, limits::NAME)),
                    summary: Some(clip(&project.summary, limits::SUMMARY)).filter(|s| !s.is_empty()),
                    license: Some(project.license.describe()),
                    minecraft: file.game_versions.clone(),
                    loaders: file.loaders.clone(),
                    repository: Some(project.reference.provider.id().to_owned()),
                    project: Some(project.reference.id.clone()),
                    version_id: Some(file.version_id.clone()),
                    page: Some(project.project_url.clone()),
                    requires,
                });
            entry.version = Some(file.version());
            // An update must not switch back on something the user turned off.
            if let Some(previous) = &previous {
                entry.enabled = previous.enabled;
                entry.disabled_reason.clone_from(&previous.disabled_reason);
            }
            ids.insert(project.reference.qualified(), id.to_string());
            next.upsert(entry.clone());
            added.push(entry);
        }

        *mods = next;
        progress(&InstallProgress {
            phase: InstallPhase::Done,
            completed: total,
            total,
            current: plan.root.project.name.clone(),
        });
        tracing::info!(
            project = %plan.root.project.reference,
            files = total,
            downloaded,
            reused,
            update = replaced.is_some(),
            "installed from the mod browser"
        );
        Ok(InstallReport {
            added,
            replaced,
            downloaded,
            reused,
        })
    }
}

// ---------------------------------------------------------------------------
// Uninstall
// ---------------------------------------------------------------------------

/// What removing one mod would do.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UninstallPlan {
    pub entry: ProfileMod,
    /// Installed mods that recorded this one as a required dependency. While
    /// this is non-empty the removal is refused.
    pub needed_by: Vec<String>,
    /// Dependencies this mod pulled in that nothing else needs once it is
    /// gone. Offered to the user, never removed automatically.
    pub unused_dependencies: Vec<String>,
}

impl UninstallPlan {
    pub fn is_blocked(&self) -> bool {
        !self.needed_by.is_empty()
    }
}

/// Plan removing `id` from a profile.
pub fn plan_uninstall(
    mods: &ProfileModConfiguration,
    id: &ModId,
) -> Result<UninstallPlan, BrowseError> {
    let entry = mods
        .get(id)
        .cloned()
        .ok_or_else(|| BrowseError::Refused("that mod is not installed".to_owned()))?;
    if entry.source.is_launcher_owned() {
        return Err(BrowseError::Refused(
            "that is a Fathom component; manage it from the components list".to_owned(),
        ));
    }
    let needed_by = mods
        .iter()
        .filter(|other| other.id != entry.id && other.origin.requires.contains(id.as_str()))
        .map(|other| other.id.to_string())
        .collect();
    let unused_dependencies = entry
        .origin
        .requires
        .iter()
        .filter(|dependency| {
            ModId::new(dependency).is_ok_and(|dependency_id| mods.contains(&dependency_id))
                && !mods.iter().any(|other| {
                    other.id != entry.id && other.origin.requires.contains(dependency.as_str())
                })
        })
        .cloned()
        .collect();
    Ok(UninstallPlan {
        entry,
        needed_by,
        unused_dependencies,
    })
}

/// Remove `id` from `mods`, refusing while another mod needs it.
///
/// Only the profile entry goes. Whether the bytes can be deleted is a question
/// about every profile, answered by the store's garbage collection.
pub fn uninstall(
    mods: &mut ProfileModConfiguration,
    id: &ModId,
) -> Result<ProfileMod, BrowseError> {
    let plan = plan_uninstall(mods, id)?;
    if plan.is_blocked() {
        return Err(BrowseError::Refused(format!(
            "{} is needed by {}",
            plan.entry.file_name,
            plan.needed_by.join(", ")
        )));
    }
    mods.remove(id)
        .ok_or_else(|| BrowseError::Refused("that mod is not installed".to_owned()))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::install::download::sha1_hex;
    use crate::mods::browse::dependencies::tests::{file, needs, project, target, FakeIndex};
    use crate::mods::browse::dependencies::{plan, InstallPlan};
    use crate::mods::browse::model::{DependencyKind, ModFile, ProjectRef};
    use crate::mods::definition::ArtifactHash;
    use crate::network::{HttpClient, NetworkJournal, NetworkPolicy};
    use crate::paths::DataPaths;
    use crate::profiles::Loader;

    struct Fixture {
        _tmp: tempfile::TempDir,
        installer: Installer,
    }

    fn fixture() -> Fixture {
        let tmp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(tmp.path());
        // Offline: any test that reaches the network fails loudly.
        let policy = NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        };
        let http = HttpClient::new(policy, NetworkJournal::new(), "test/0");
        Fixture {
            installer: Installer::new(DownloadManager::new(http), ModStore::new(paths)),
            _tmp: tmp,
        }
    }

    /// A file whose bytes are already in the store, so executing needs no
    /// network.
    fn stored(fixture: &Fixture, mut file: ModFile) -> ModFile {
        let bytes = format!("PK\u{3}\u{4}{}{}", file.project.qualified(), file.version_id);
        let sha1 = sha1_hex(bytes.as_bytes());
        fixture
            .installer
            .store()
            .adopt_bytes(bytes.as_bytes(), &sha1)
            .unwrap();
        file.hash = ArtifactHash::new(sha1, None).unwrap();
        file
    }

    fn grant() -> PerActionGrant {
        PerActionGrant::user_requested("test".to_owned())
    }

    fn run(
        fixture: &Fixture,
        plan: &InstallPlan,
        mods: &mut ProfileModConfiguration,
        options: InstallOptions,
    ) -> Result<InstallReport, BrowseError> {
        fixture
            .installer
            .execute(plan, &target(), mods, options, &grant(), &Cancel::new(), &mut |_| {})
    }

    fn with_library(fixture: &Fixture) -> (FakeIndex, ModFile) {
        let lib = ProjectRef::new(Provider::Modrinth, "lib");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut index = FakeIndex::default();
        index.add(
            project(Provider::Modrinth, "lib", "library"),
            vec![stored(fixture, file(&lib, "1", vec![]))],
        );
        let root_file = stored(fixture, file(&root, "1", vec![needs(DependencyKind::Required, &lib)]));
        (index, root_file)
    }

    #[test]
    fn installing_records_the_mod_and_its_dependency_and_links_them() {
        let fixture = fixture();
        let (index, root_file) = with_library(&fixture);
        let mut mods = ProfileModConfiguration::default();
        let planned = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            root_file,
            &[],
            None,
        )
        .unwrap();
        let report = run(&fixture, &planned, &mut mods, InstallOptions::default()).unwrap();
        assert_eq!(report.added.len(), 2);
        assert_eq!(report.reused, 2);
        let root = mods.get(&ModId::new("root-mod").unwrap()).unwrap();
        assert!(root.origin.requires.contains("library"));
        assert_eq!(root.source, ModSource::Managed);
    }

    #[test]
    fn an_incompatible_plan_is_refused_at_install_even_if_planning_missed_it() {
        let fixture = fixture();
        let (index, root_file) = with_library(&fixture);
        let mut planned = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            root_file,
            &[],
            None,
        )
        .unwrap();
        // Tamper with the plan the way a stale or hostile caller could.
        planned.root.file.game_versions = ["1.21".to_owned()].into_iter().collect();
        let mut mods = ProfileModConfiguration::default();
        let error = run(&fixture, &planned, &mut mods, InstallOptions::default()).unwrap_err();
        assert!(matches!(error, BrowseError::Incompatible(_)));
        assert!(mods.is_empty(), "nothing is recorded");

        // And a plan made for a different profile target.
        let mut other = planned.clone();
        other.root.file.game_versions = ["1.8.9".to_owned()].into_iter().collect();
        other.target = Target::new("1.12.2", Loader::Forge);
        assert!(matches!(
            run(&fixture, &other, &mut mods, InstallOptions::default()),
            Err(BrowseError::Incompatible(_))
        ));
    }

    #[test]
    fn a_failed_download_leaves_the_profile_untouched() {
        let fixture = fixture();
        let (index, _) = with_library(&fixture);
        // Not in the store, and the client is offline: the fetch fails.
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let missing = file(&root, "2", vec![needs(DependencyKind::Required, &ProjectRef::new(Provider::Modrinth, "lib"))]);
        let planned = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            missing,
            &[],
            None,
        )
        .unwrap();
        let mut mods = ProfileModConfiguration::default();
        assert!(run(&fixture, &planned, &mut mods, InstallOptions::default()).is_err());
        assert!(mods.is_empty(), "the library that did verify is not recorded alone");
        // No partial file anywhere in the store.
        let partials = walk(&fixture.installer.store().root())
            .into_iter()
            .filter(|p| p.extension().is_some_and(|e| e == "partial"))
            .count();
        assert_eq!(partials, 0);
    }

    fn walk(root: &std::path::Path) -> Vec<std::path::PathBuf> {
        let mut out = Vec::new();
        if let Ok(entries) = std::fs::read_dir(root) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    out.extend(walk(&path));
                } else {
                    out.push(path);
                }
            }
        }
        out
    }

    #[test]
    fn a_possible_duplicate_needs_explicit_confirmation() {
        let fixture = fixture();
        let reference = ProjectRef::new(Provider::CurseForge, "1234");
        let root_file = stored(&fixture, file(&reference, "1", vec![]));
        let mut existing = ProfileMod::new(ModId::new("sodium").unwrap(), "e".repeat(40), "sodium-0.jar")
            .with_origin(Origin {
                name: Some("Sodium".into()),
                repository: Some("modrinth".into()),
                project: Some("abcd".into()),
                ..Origin::default()
            });
        existing.source = ModSource::Managed;
        let mut mods = ProfileModConfiguration::default();
        mods.upsert(existing.clone());

        let planned = plan(
            &FakeIndex::default(),
            &target(),
            &[existing],
            project(Provider::CurseForge, "1234", "sodium"),
            root_file,
            &[],
            None,
        )
        .unwrap();
        assert!(matches!(
            run(&fixture, &planned, &mut mods, InstallOptions::default()),
            Err(BrowseError::Refused(_))
        ));
        let accepted = run(
            &fixture,
            &planned,
            &mut mods,
            InstallOptions {
                accept_possible_duplicates: true,
            },
        )
        .unwrap();
        assert_eq!(
            accepted.added[0].id.as_str(),
            "sodium-curseforge",
            "the id does not collide with the existing entry"
        );
    }

    #[test]
    fn an_update_replaces_one_entry_keeps_its_state_and_can_be_rolled_back() {
        let fixture = fixture();
        let reference = ProjectRef::new(Provider::Modrinth, "root");
        let old_file = stored(&fixture, file(&reference, "1", vec![]));
        let new_file = stored(&fixture, file(&reference, "2", vec![]));

        let mut mods = ProfileModConfiguration::default();
        let first = plan(
            &FakeIndex::default(),
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            old_file.clone(),
            &[],
            None,
        )
        .unwrap();
        run(&fixture, &first, &mut mods, InstallOptions::default()).unwrap();
        let id = ModId::new("root-mod").unwrap();
        mods.set_enabled(&id, false);
        let installed = mods.get(&id).cloned().unwrap();

        let update = plan(
            &FakeIndex::default(),
            &target(),
            mods.iter().cloned().collect::<Vec<_>>().as_slice(),
            project(Provider::Modrinth, "root", "root-mod"),
            new_file.clone(),
            &[],
            Some(&installed),
        )
        .unwrap();
        let report = run(&fixture, &update, &mut mods, InstallOptions::default()).unwrap();

        assert_eq!(mods.len(), 1, "never both versions");
        let now = mods.get(&id).unwrap();
        assert_eq!(now.sha1, new_file.hash.sha1);
        assert!(!now.enabled, "a disabled mod stays disabled through an update");
        assert!(
            fixture.installer.store().contains(&old_file.hash.sha1),
            "the old bytes remain until garbage collection"
        );

        rollback(&report, &mut mods);
        assert_eq!(mods.get(&id).unwrap().sha1, old_file.hash.sha1);
        assert_eq!(mods.len(), 1);
    }

    #[test]
    fn a_shared_dependency_cannot_be_removed_while_something_needs_it() {
        let fixture = fixture();
        let (index, root_file) = with_library(&fixture);
        let mut mods = ProfileModConfiguration::default();
        let planned = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            root_file,
            &[],
            None,
        )
        .unwrap();
        run(&fixture, &planned, &mut mods, InstallOptions::default()).unwrap();

        let library = ModId::new("library").unwrap();
        let blocked = plan_uninstall(&mods, &library).unwrap();
        assert_eq!(blocked.needed_by, vec!["root-mod".to_owned()]);
        assert!(uninstall(&mut mods, &library).is_err());
        assert!(mods.contains(&library));

        let root = ModId::new("root-mod").unwrap();
        let removal = plan_uninstall(&mods, &root).unwrap();
        assert_eq!(removal.unused_dependencies, vec!["library".to_owned()]);
        uninstall(&mut mods, &root).unwrap();
        assert!(mods.contains(&library), "a dependency is offered, never removed silently");
        uninstall(&mut mods, &library).unwrap();
        assert!(mods.is_empty());
    }

    #[test]
    fn a_file_that_is_not_a_jar_never_becomes_a_mod() {
        let fixture = fixture();
        let reference = ProjectRef::new(Provider::Modrinth, "root");
        let mut fake = file(&reference, "1", vec![]);
        let bytes = b"#!/bin/sh\necho not a jar";
        let sha1 = sha1_hex(bytes);
        let path = fixture.installer.store().path_for(&sha1).unwrap();
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, bytes).unwrap();
        fake.hash = ArtifactHash::new(sha1, None).unwrap();

        let planned = plan(
            &FakeIndex::default(),
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root-mod"),
            fake,
            &[],
            None,
        )
        .unwrap();
        let mut mods = ProfileModConfiguration::default();
        assert!(run(&fixture, &planned, &mut mods, InstallOptions::default()).is_err());
        assert!(mods.is_empty());
        assert!(!path.exists());
    }
}
