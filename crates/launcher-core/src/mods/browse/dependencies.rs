//! What installing a mod actually installs.
//!
//! # The plan comes before the bytes
//!
//! Clicking "Install" on a mod that needs a library is two installs, and the
//! user is owed that fact before either happens. [`plan`] walks the required
//! dependencies, recursively, and returns an [`InstallPlan`]: what will be
//! fetched, what is already satisfied, what is optional and was not chosen,
//! and what stops the install outright. Nothing is downloaded while planning.
//!
//! # Rules
//!
//! - **Required** dependencies are followed, recursively, to [`MAX_DEPTH`].
//! - **Optional** dependencies are listed and never installed unless the caller
//!   names them. "Silently installs extras" is the failure this avoids.
//! - **Incompatible** dependencies that are installed, or that the plan would
//!   install, are blockers. Launching both is how a game fails to start.
//! - **Embedded** dependencies ship inside the jar and are nothing to fetch.
//! - A project is visited once. A cycle (A needs B needs A) ends at the second
//!   visit instead of looping, and two mods needing the same library fetch it
//!   once.
//! - Every file is checked against the target here *and again* by the
//!   installer. A dependency pinned to an incompatible build is a blocker, not
//!   a quiet substitution.
//! - A dependency that *looks* like something already installed from the
//!   other index is not guessed at in either direction. It is planned, flagged,
//!   and the installer refuses to proceed until the user has said which.

use std::collections::{BTreeSet, VecDeque};

use serde::Serialize;

use super::identity::{already_installed, same_project, Duplicate};
use super::model::{DependencyKind, ModFile, ModProject, ProjectRef, Target};
use super::provider::BrowseError;
use crate::mods::config::ProfileMod;

/// How deep required dependencies are followed.
///
/// Real mod stacks are two or three levels deep. Anything past this is either
/// a mistake in someone's metadata or an attempt to make the launcher do a lot
/// of work.
pub const MAX_DEPTH: usize = 6;

/// How many distinct projects one plan may involve.
pub const MAX_PROJECTS: usize = 48;

/// What the planner needs from the outside world.
///
/// Implemented by the catalogue over its providers and cache, and by a fake in
/// tests. Every call may hit the network; the planner is always run off the
/// interface thread.
pub trait DependencySource {
    fn project(&self, reference: &ProjectRef) -> Result<ModProject, BrowseError>;

    /// The newest build of a project that fits the target, if there is one.
    fn latest(&self, reference: &ProjectRef, target: &Target)
        -> Result<Option<ModFile>, BrowseError>;

    /// One exact build.
    fn file(&self, reference: &ProjectRef, version_id: &str) -> Result<ModFile, BrowseError>;

    /// Checked between steps so a long plan stops when the user leaves.
    fn check(&self) -> Result<(), BrowseError> {
        Ok(())
    }
}

/// One mod the plan will fetch and add.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedMod {
    pub project: ModProject,
    pub file: ModFile,
    /// The project that needs this one; `None` for the mod the user picked.
    pub required_by: Option<ProjectRef>,
    /// Set when the user opted into this optional dependency.
    pub optional: bool,
    /// Something already installed that may be this same mod from another
    /// index. The installer refuses a plan with any of these until the caller
    /// accepts them explicitly.
    pub possible_duplicate: Option<String>,
}

/// A required dependency the profile already has.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SatisfiedDependency {
    pub dependency: ProjectRef,
    /// The entry that satisfies it.
    pub installed_id: String,
    pub installed_file: String,
    /// A disabled library satisfies nothing at launch. Reported so the
    /// interface can offer to turn it on rather than pretending all is well.
    pub enabled: bool,
    pub required_by: ProjectRef,
}

/// An optional dependency the author suggested and the user has not chosen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptionalDependency {
    pub dependency: ProjectRef,
    pub suggested_by: ProjectRef,
    pub already_installed: bool,
}

/// Why a plan cannot be carried out.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Blocker {
    /// The profile has no mod loader.
    NoModLoader,
    /// A file in the plan does not fit this Minecraft version and loader.
    Incompatible { project: ProjectRef, detail: String },
    /// The author does not allow third-party downloads, or the index offered
    /// no fetchable URL.
    NotDownloadable {
        project: ProjectRef,
        where_to_get: String,
    },
    /// A required dependency could not be found or has no compatible build.
    Unresolved {
        dependency: ProjectRef,
        required_by: ProjectRef,
        detail: String,
    },
    /// A required dependency named only a file, not a project.
    UnnamedDependency { required_by: ProjectRef },
    /// This mod is already installed. Use update instead.
    AlreadyInstalled { installed_id: String, detail: String },
    /// Something the plan would install is marked incompatible with something
    /// installed or planned.
    Conflict {
        project: ProjectRef,
        conflicts_with: String,
    },
    /// The dependency tree is deeper or wider than any real one.
    TooLarge,
}

/// Everything installing one mod involves.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallPlan {
    pub target: Target,
    /// The mod the user picked.
    pub root: PlannedMod,
    /// Dependencies to fetch, in the order they should be added: the deepest
    /// first, so nothing is recorded before what it needs.
    pub dependencies: Vec<PlannedMod>,
    pub satisfied: Vec<SatisfiedDependency>,
    pub optional: Vec<OptionalDependency>,
    pub blockers: Vec<Blocker>,
    /// The profile entry being replaced, when this plan is an update.
    pub replaces: Option<String>,
}

impl InstallPlan {
    pub fn is_blocked(&self) -> bool {
        !self.blockers.is_empty()
    }

    /// Every file to fetch, dependencies first and the chosen mod last.
    pub fn installs(&self) -> impl Iterator<Item = &PlannedMod> {
        self.dependencies.iter().chain(std::iter::once(&self.root))
    }

    /// Whether the plan contains anything the user has to confirm as not a
    /// duplicate.
    pub fn needs_duplicate_confirmation(&self) -> bool {
        self.installs().any(|planned| planned.possible_duplicate.is_some())
    }

    pub fn download_count(&self) -> usize {
        self.installs().count()
    }
}

/// Whether an installed entry is a given project at a given index.
fn entry_is(entry: &ProfileMod, reference: &ProjectRef) -> bool {
    entry
        .origin
        .repository
        .as_deref()
        .and_then(super::model::Provider::parse)
        == Some(reference.provider)
        && entry.origin.project.as_deref() == Some(reference.id.as_str())
}

fn check_file(file: &ModFile, target: &Target, blockers: &mut Vec<Blocker>) {
    if !file.supports(target) {
        blockers.push(Blocker::Incompatible {
            project: file.project.clone(),
            detail: format!(
                "{} is not built for Minecraft {} on {}",
                file.number,
                target.minecraft,
                target.loader.label()
            ),
        });
    }
    if let super::model::FileDownload::Withheld { where_to_get } = &file.download {
        blockers.push(Blocker::NotDownloadable {
            project: file.project.clone(),
            where_to_get: where_to_get.clone(),
        });
    }
}

/// Plan installing `file` of `project` into a profile whose mods are
/// `installed`.
///
/// `chosen_optional` names the optional dependencies the user ticked. Anything
/// else optional is listed and left alone. `replacing` is the entry an update
/// replaces; it does not count as "already installed".
///
/// Returns `Err` only for failures that make the whole plan meaningless
/// (cancelled, blocked by settings). A dependency that cannot be resolved is a
/// [`Blocker`] in an `Ok` plan, so the interface can show which one.
///
/// One walk, kept in one function so the visited set, the queue and the plan
/// cannot drift apart.
#[allow(clippy::too_many_lines)]
pub fn plan(
    source: &dyn DependencySource,
    target: &Target,
    installed: &[ProfileMod],
    project: ModProject,
    file: ModFile,
    chosen_optional: &[ProjectRef],
    replacing: Option<&ProfileMod>,
) -> Result<InstallPlan, BrowseError> {
    let mut blockers = Vec::new();
    if !target.loads_mods() {
        blockers.push(Blocker::NoModLoader);
    }
    check_file(&file, target, &mut blockers);

    // Everything already in the profile except the entry being replaced.
    let others: Vec<ProfileMod> = installed
        .iter()
        .filter(|entry| replacing.is_none_or(|replaced| replaced.id != entry.id))
        .cloned()
        .collect();

    let mut root_duplicate = None;
    match already_installed(&others, &project, Some(&file)) {
        Some((entry, Duplicate::Same { reason })) => blockers.push(Blocker::AlreadyInstalled {
            installed_id: entry.id.to_string(),
            detail: reason,
        }),
        Some((_, Duplicate::Possible { reason })) => root_duplicate = Some(reason),
        Some((_, Duplicate::None)) | None => {}
    }

    let mut plan = InstallPlan {
        target: target.clone(),
        root: PlannedMod {
            project,
            file: file.clone(),
            required_by: None,
            optional: false,
            possible_duplicate: root_duplicate,
        },
        dependencies: Vec::new(),
        satisfied: Vec::new(),
        optional: Vec::new(),
        blockers,
        replaces: replacing.map(|entry| entry.id.to_string()),
    };

    let mut visited: BTreeSet<String> = BTreeSet::new();
    visited.insert(plan.root.project.reference.qualified());

    // Breadth first: (file whose dependencies to read, its project, depth).
    let mut queue: VecDeque<(ModFile, ProjectRef, usize)> = VecDeque::new();
    queue.push_back((file, plan.root.project.reference.clone(), 0));
    // Incompatibilities are checked once everything is known, because the
    // conflicting project may only be added later in the walk.
    let mut incompatible: Vec<(ProjectRef, ProjectRef)> = Vec::new();

    while let Some((current, owner, depth)) = queue.pop_front() {
        source.check()?;
        for dependency in &current.dependencies {
            let chosen = dependency
                .project
                .as_ref()
                .is_some_and(|reference| chosen_optional.contains(reference));
            match dependency.kind {
                DependencyKind::Embedded => continue,
                DependencyKind::Incompatible => {
                    if let Some(reference) = &dependency.project {
                        incompatible.push((owner.clone(), reference.clone()));
                    }
                    continue;
                }
                DependencyKind::Optional if !chosen => {
                    if let Some(reference) = &dependency.project {
                        if !plan.optional.iter().any(|o| &o.dependency == reference) {
                            plan.optional.push(OptionalDependency {
                                dependency: reference.clone(),
                                suggested_by: owner.clone(),
                                already_installed: others.iter().any(|e| entry_is(e, reference)),
                            });
                        }
                    }
                    continue;
                }
                DependencyKind::Optional | DependencyKind::Required => {}
            }

            let Some(reference) = dependency.project.clone() else {
                plan.blockers.push(Blocker::UnnamedDependency {
                    required_by: owner.clone(),
                });
                continue;
            };
            if !visited.insert(reference.qualified()) {
                // Already planned, satisfied, or the project that started the
                // walk: a shared library or a cycle. Either way, done.
                continue;
            }
            if visited.len() > MAX_PROJECTS || depth >= MAX_DEPTH {
                if !plan.blockers.contains(&Blocker::TooLarge) {
                    plan.blockers.push(Blocker::TooLarge);
                }
                continue;
            }

            // Installed from this index already: satisfied, no request needed.
            if let Some(entry) = others.iter().find(|entry| entry_is(entry, &reference)) {
                plan.satisfied.push(SatisfiedDependency {
                    dependency: reference,
                    installed_id: entry.id.to_string(),
                    installed_file: entry.file_name.clone(),
                    enabled: entry.enabled,
                    required_by: owner.clone(),
                });
                continue;
            }

            let dependency_project = match source.project(&reference) {
                Ok(found) => found,
                Err(BrowseError::Cancelled) => return Err(BrowseError::Cancelled),
                Err(error) => {
                    plan.blockers.push(Blocker::Unresolved {
                        dependency: reference,
                        required_by: owner.clone(),
                        detail: error.user_message(),
                    });
                    continue;
                }
            };

            let chosen_file = match &dependency.version_id {
                // The author pinned an exact build. Honoured, and checked: a pin
                // to an incompatible build is a blocker, not a reason to pick a
                // different one the author did not ask for.
                Some(version_id) => source.file(&reference, version_id).map(Some),
                None => source.latest(&reference, target),
            };
            let chosen_file = match chosen_file {
                Ok(Some(found)) => found,
                Ok(None) => {
                    plan.blockers.push(Blocker::Unresolved {
                        dependency: reference,
                        required_by: owner.clone(),
                        detail: format!(
                            "{} has no build for Minecraft {} on {}",
                            dependency_project.name,
                            target.minecraft,
                            target.loader.label()
                        ),
                    });
                    continue;
                }
                Err(BrowseError::Cancelled) => return Err(BrowseError::Cancelled),
                Err(error) => {
                    plan.blockers.push(Blocker::Unresolved {
                        dependency: reference,
                        required_by: owner.clone(),
                        detail: error.user_message(),
                    });
                    continue;
                }
            };
            if chosen_file.project != reference {
                plan.blockers.push(Blocker::Unresolved {
                    dependency: reference,
                    required_by: owner.clone(),
                    detail: "the index answered with a file from a different project".to_owned(),
                });
                continue;
            }
            check_file(&chosen_file, target, &mut plan.blockers);

            // The same library from the other index, or a hand-added jar that
            // looks like it. Satisfied if the bytes are identical; otherwise
            // flagged for the user rather than guessed.
            let mut possible_duplicate = None;
            match already_installed(&others, &dependency_project, Some(&chosen_file)) {
                Some((entry, Duplicate::Same { .. })) => {
                    plan.satisfied.push(SatisfiedDependency {
                        dependency: reference,
                        installed_id: entry.id.to_string(),
                        installed_file: entry.file_name.clone(),
                        enabled: entry.enabled,
                        required_by: owner.clone(),
                    });
                    continue;
                }
                Some((_, Duplicate::Possible { reason })) => possible_duplicate = Some(reason),
                Some((_, Duplicate::None)) | None => {}
            }
            // Two different dependencies in this plan that are the same mod
            // from two indexes: fetch one.
            if let Some(existing) = plan
                .dependencies
                .iter()
                .find(|planned| same_project(&planned.project, &dependency_project).is_certain())
            {
                tracing::debug!(
                    kept = %existing.project.reference,
                    skipped = %reference,
                    "the same dependency was named on two indexes; fetching one"
                );
                continue;
            }

            queue.push_back((chosen_file.clone(), reference.clone(), depth + 1));
            plan.dependencies.push(PlannedMod {
                project: dependency_project,
                file: chosen_file,
                required_by: Some(owner.clone()),
                optional: dependency.kind == DependencyKind::Optional,
                possible_duplicate,
            });
        }
    }

    for (owner, excluded) in incompatible {
        let installed_conflict = others.iter().find(|entry| entry_is(entry, &excluded));
        let planned_conflict = plan
            .installs()
            .find(|planned| planned.project.reference == excluded)
            .map(|planned| planned.project.name.clone());
        if let Some(name) = installed_conflict
            .map(|entry| entry.file_name.clone())
            .or(planned_conflict)
        {
            plan.blockers.push(Blocker::Conflict {
                project: owner,
                conflicts_with: name,
            });
        }
    }

    // Deepest first, so a library is recorded before what needs it.
    plan.dependencies.reverse();
    Ok(plan)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]
pub(crate) mod tests {
    use std::cell::RefCell;
    use std::collections::BTreeMap;

    use super::*;
    use crate::mods::browse::model::{
        Distribution, FileDependency, FileDownload, Provider, ReleaseChannel,
    };
    use crate::mods::definition::{ArtifactHash, License};
    use crate::mods::{ModId, Origin};
    use crate::profiles::Loader;

    pub(crate) fn target() -> Target {
        Target::new("1.8.9", Loader::LegacyFabric)
    }

    pub(crate) fn project(provider: Provider, id: &str, slug: &str) -> ModProject {
        ModProject {
            reference: ProjectRef::new(provider, id),
            slug: slug.into(),
            name: slug.into(),
            summary: String::new(),
            description: None,
            authors: Vec::new(),
            icon: None,
            gallery: Vec::new(),
            downloads: 0,
            categories: Vec::new(),
            project_url: format!("https://modrinth.com/mod/{slug}"),
            source_url: None,
            issues_url: None,
            license: License::Unknown,
            minecraft_versions: BTreeSet::new(),
            loaders: BTreeSet::new(),
            updated: None,
            distribution: Distribution::Allowed,
            detailed: true,
        }
    }

    pub(crate) fn file(reference: &ProjectRef, version: &str, deps: Vec<FileDependency>) -> ModFile {
        let seed = format!("{}{}", reference.qualified(), version);
        let sha1 = crate::install::download::sha1_hex(seed.as_bytes());
        ModFile {
            project: reference.clone(),
            version_id: version.into(),
            number: version.into(),
            name: version.into(),
            channel: ReleaseChannel::Release,
            loaders: ["legacy-fabric".to_owned()].into_iter().collect(),
            game_versions: ["1.8.9".to_owned()].into_iter().collect(),
            file_name: format!("{}-{version}.jar", reference.id),
            download: FileDownload::Url {
                url: format!("https://cdn.modrinth.com/data/{}/{version}.jar", reference.id),
            },
            hash: ArtifactHash::new(sha1, None).unwrap(),
            dependencies: deps,
            published: None,
        }
    }

    pub(crate) fn needs(kind: DependencyKind, reference: &ProjectRef) -> FileDependency {
        FileDependency {
            kind,
            project: Some(reference.clone()),
            version_id: None,
        }
    }

    /// An index in memory. Counts requests so tests can prove things are not
    /// fetched twice.
    #[derive(Default)]
    pub(crate) struct FakeIndex {
        pub projects: BTreeMap<String, ModProject>,
        pub files: BTreeMap<String, Vec<ModFile>>,
        pub requests: RefCell<Vec<String>>,
    }

    impl FakeIndex {
        pub(crate) fn add(&mut self, project: ModProject, files: Vec<ModFile>) {
            let key = project.reference.qualified();
            self.projects.insert(key.clone(), project);
            self.files.insert(key, files);
        }
    }

    impl DependencySource for FakeIndex {
        fn project(&self, reference: &ProjectRef) -> Result<ModProject, BrowseError> {
            self.requests.borrow_mut().push(format!("project {reference}"));
            self.projects
                .get(&reference.qualified())
                .cloned()
                .ok_or(BrowseError::NotFound {
                    provider: reference.provider,
                    what: reference.id.clone(),
                })
        }

        fn latest(
            &self,
            reference: &ProjectRef,
            target: &Target,
        ) -> Result<Option<ModFile>, BrowseError> {
            self.requests.borrow_mut().push(format!("latest {reference}"));
            Ok(self
                .files
                .get(&reference.qualified())
                .and_then(|files| files.iter().find(|f| f.supports(target)).cloned()))
        }

        fn file(&self, reference: &ProjectRef, version_id: &str) -> Result<ModFile, BrowseError> {
            self.files
                .get(&reference.qualified())
                .and_then(|files| files.iter().find(|f| f.version_id == version_id).cloned())
                .ok_or(BrowseError::NotFound {
                    provider: reference.provider,
                    what: version_id.to_owned(),
                })
        }
    }

    fn installed_from(provider: Provider, id: &str, file_name: &str) -> ProfileMod {
        ProfileMod::new(
            ModId::new(&crate::mods::id_from_file_name(file_name).unwrap().to_string()).unwrap(),
            "f".repeat(40),
            file_name,
        )
        .with_origin(Origin {
            repository: Some(provider.id().to_owned()),
            project: Some(id.to_owned()),
            version_id: Some("old".to_owned()),
            ..Origin::default()
        })
    }

    #[test]
    fn required_dependencies_are_followed_and_ordered_deepest_first() {
        let api = ProjectRef::new(Provider::Modrinth, "api");
        let lib = ProjectRef::new(Provider::Modrinth, "lib");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut index = FakeIndex::default();
        index.add(project(Provider::Modrinth, "lib", "lib"), vec![file(&lib, "1", vec![])]);
        index.add(
            project(Provider::Modrinth, "api", "api-thing"),
            vec![file(&api, "1", vec![needs(DependencyKind::Required, &lib)])],
        );

        let root_file = file(&root, "1", vec![needs(DependencyKind::Required, &api)]);
        let plan = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root"),
            root_file,
            &[],
            None,
        )
        .unwrap();
        assert!(!plan.is_blocked(), "{:?}", plan.blockers);
        let order: Vec<_> = plan.installs().map(|p| p.project.reference.id.clone()).collect();
        assert_eq!(order, vec!["lib", "api", "root"]);
    }

    #[test]
    fn a_cycle_ends_and_a_shared_library_is_fetched_once() {
        let a = ProjectRef::new(Provider::Modrinth, "a");
        let b = ProjectRef::new(Provider::Modrinth, "b");
        let lib = ProjectRef::new(Provider::Modrinth, "lib");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut index = FakeIndex::default();
        index.add(project(Provider::Modrinth, "lib", "lib"), vec![file(&lib, "1", vec![])]);
        index.add(
            project(Provider::Modrinth, "a", "aaa"),
            vec![file(
                &a,
                "1",
                vec![needs(DependencyKind::Required, &b), needs(DependencyKind::Required, &lib)],
            )],
        );
        index.add(
            project(Provider::Modrinth, "b", "bbb"),
            vec![file(
                &b,
                "1",
                vec![
                    needs(DependencyKind::Required, &a),
                    needs(DependencyKind::Required, &lib),
                    needs(DependencyKind::Required, &root),
                ],
            )],
        );
        let plan = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![needs(DependencyKind::Required, &a)]),
            &[],
            None,
        )
        .unwrap();
        assert!(!plan.is_blocked(), "{:?}", plan.blockers);
        assert_eq!(plan.download_count(), 4);
        let lib_requests = index
            .requests
            .borrow()
            .iter()
            .filter(|r| r.ends_with("modrinth:lib"))
            .count();
        assert_eq!(lib_requests, 2, "one project and one version lookup, not one per dependent");
    }

    #[test]
    fn optional_dependencies_are_listed_and_not_installed_unless_chosen() {
        let extra = ProjectRef::new(Provider::Modrinth, "extra");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut index = FakeIndex::default();
        index.add(project(Provider::Modrinth, "extra", "extra"), vec![file(&extra, "1", vec![])]);
        let root_file = file(&root, "1", vec![needs(DependencyKind::Optional, &extra)]);
        let root_project = project(Provider::Modrinth, "root", "root");

        let silent = plan(&index, &target(), &[], root_project.clone(), root_file.clone(), &[], None)
            .unwrap();
        assert_eq!(silent.download_count(), 1);
        assert_eq!(silent.optional.len(), 1);

        let chosen = plan(
            &index,
            &target(),
            &[],
            root_project,
            root_file,
            std::slice::from_ref(&extra),
            None,
        )
        .unwrap();
        assert_eq!(chosen.download_count(), 2);
        assert!(chosen.dependencies[0].optional);
    }

    #[test]
    fn an_installed_dependency_is_satisfied_and_not_fetched() {
        let lib = ProjectRef::new(Provider::Modrinth, "lib");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let index = FakeIndex::default();
        let installed = vec![installed_from(Provider::Modrinth, "lib", "lib-0.9.jar")];
        let plan = plan(
            &index,
            &target(),
            &installed,
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![needs(DependencyKind::Required, &lib)]),
            &[],
            None,
        )
        .unwrap();
        assert_eq!(plan.download_count(), 1);
        assert_eq!(plan.satisfied.len(), 1);
        assert!(index.requests.borrow().is_empty(), "no request for a satisfied dependency");
    }

    #[test]
    fn a_dependency_with_no_compatible_build_blocks_the_install() {
        let lib = ProjectRef::new(Provider::Modrinth, "lib");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut index = FakeIndex::default();
        let mut modern = file(&lib, "1", vec![]);
        modern.game_versions = ["1.21".to_owned()].into_iter().collect();
        modern.loaders = ["fabric".to_owned()].into_iter().collect();
        index.add(project(Provider::Modrinth, "lib", "lib"), vec![modern]);
        let plan = plan(
            &index,
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![needs(DependencyKind::Required, &lib)]),
            &[],
            None,
        )
        .unwrap();
        assert!(matches!(plan.blockers[0], Blocker::Unresolved { .. }));
    }

    #[test]
    fn an_incompatible_root_file_is_a_blocker_whatever_search_said() {
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let mut modern = file(&root, "1", vec![]);
        modern.game_versions = ["1.21".to_owned()].into_iter().collect();
        let plan = plan(
            &FakeIndex::default(),
            &target(),
            &[],
            project(Provider::Modrinth, "root", "root"),
            modern,
            &[],
            None,
        )
        .unwrap();
        assert!(matches!(plan.blockers[0], Blocker::Incompatible { .. }));
    }

    #[test]
    fn declared_incompatibility_with_an_installed_mod_blocks() {
        let rival = ProjectRef::new(Provider::Modrinth, "rival");
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let installed = vec![installed_from(Provider::Modrinth, "rival", "rival-1.jar")];
        let plan = plan(
            &FakeIndex::default(),
            &target(),
            &installed,
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![needs(DependencyKind::Incompatible, &rival)]),
            &[],
            None,
        )
        .unwrap();
        assert!(plan.blockers.iter().any(|b| matches!(b, Blocker::Conflict { .. })));
    }

    #[test]
    fn installing_what_is_installed_is_refused_and_an_update_is_not() {
        let root = ProjectRef::new(Provider::Modrinth, "root");
        let installed = vec![installed_from(Provider::Modrinth, "root", "root-0.jar")];
        let again = plan(
            &FakeIndex::default(),
            &target(),
            &installed,
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![]),
            &[],
            None,
        )
        .unwrap();
        assert!(matches!(again.blockers[0], Blocker::AlreadyInstalled { .. }));

        let update = plan(
            &FakeIndex::default(),
            &target(),
            &installed,
            project(Provider::Modrinth, "root", "root"),
            file(&root, "1", vec![]),
            &[],
            Some(&installed[0]),
        )
        .unwrap();
        assert!(!update.is_blocked(), "{:?}", update.blockers);
        assert_eq!(update.replaces.as_deref(), Some("root-0"));
    }

    #[test]
    fn the_same_mod_from_the_other_index_needs_confirmation() {
        let root = ProjectRef::new(Provider::CurseForge, "1234");
        let mut installed = installed_from(Provider::Modrinth, "abcd", "sodium-1.jar");
        installed.origin.name = Some("Sodium".into());
        let plan = plan(
            &FakeIndex::default(),
            &target(),
            &[installed],
            project(Provider::CurseForge, "1234", "sodium"),
            file(&root, "1", vec![]),
            &[],
            None,
        )
        .unwrap();
        assert!(!plan.is_blocked());
        assert!(plan.needs_duplicate_confirmation());
    }
}
