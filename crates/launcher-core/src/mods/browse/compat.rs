//! Will this build load in Fathom? Three answers, each with its reasons.
//!
//! # Why not a boolean
//!
//! The first version of the browser asked [`ModFile::supports`], which is a
//! yes/no on the index's tags. That is the right check for "is this tagged
//! exactly for us", and it stays the strict test. It is the wrong shape for a
//! browser, because the 1.8.9 catalogue is not tidy:
//!
//! - Modrinth only gained a `legacy-fabric` loader tag in 2023. Legacy Fabric
//!   mods uploaded before that are tagged plain `fabric` with game version
//!   1.8.9. Upstream Fabric has never supported 1.8.9, so such a build was
//!   built for *something* else — usually Legacy Fabric, sometimes Ornithe,
//!   which uses different mappings and does not load in Fathom.
//! - `CurseForge` has no Legacy Fabric loader at all; a 1.8.9 Fabric mod there
//!   is tagged `Fabric` and is in exactly the same position.
//! - Forge and Ornithe builds for 1.8.9 are common and never load.
//!
//! Saying "compatible" about the middle group would be a claim Fathom cannot
//! back; hiding it would hide real, working mods. So the resolver answers
//! [`Verdict::Unverified`] for it, says why, and the installer will only take
//! an unverified build when the user has seen that and said yes.
//!
//! # What counts as evidence
//!
//! Only what the index publishes about the *file*: its game versions, its
//! loader tags and its declared dependencies — plus a short table of facts
//! Fathom knows about specific projects ([`KNOWN`]). The jar itself is never
//! opened to ask it what it is: `mods/mod.rs` rule 6. Which means some things
//! cannot be known before launch — chiefly the Fabric Loader version a mod's
//! `fabric.mod.json` demands — and the launch-time resolver remains the last
//! word.

use serde::Serialize;

use super::model::{
    DependencyKind, ModFile, ModProject, ProjectRef, Provider, ReleaseChannel, Target,
};
use crate::profiles::Loader;

/// How far a build can be trusted to load. Ordered best first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Verdict {
    /// The index tags it for this exact Minecraft version and loader, and
    /// nothing it declares contradicts that.
    Compatible,
    /// Nothing rules it out, but nothing proves it either. Installable only
    /// with the user's explicit consent.
    Unverified,
    /// Built for another version or loader, or needs something that cannot
    /// run here. Never installed.
    Incompatible,
}

impl Verdict {
    pub fn label(self) -> &'static str {
        match self {
            Verdict::Compatible => "Compatible",
            Verdict::Unverified => "Unverified",
            Verdict::Incompatible => "Incompatible",
        }
    }
}

/// One reason, and which way it points.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Finding {
    /// Stable machine name, for tests and for the interface to pick an icon.
    pub code: &'static str,
    /// The verdict this finding alone would give.
    pub verdict: Verdict,
    /// One or two sentences for a person.
    pub detail: String,
}

impl Finding {
    fn new(code: &'static str, verdict: Verdict, detail: impl Into<String>) -> Self {
        Self {
            code,
            verdict,
            detail: detail.into(),
        }
    }
}

/// The resolver's answer about one build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Assessment {
    pub verdict: Verdict,
    /// Every finding, the decisive ones first.
    pub findings: Vec<Finding>,
}

impl Assessment {
    fn from_findings(mut findings: Vec<Finding>) -> Self {
        let verdict = findings
            .iter()
            .map(|finding| finding.verdict)
            .max()
            .unwrap_or(Verdict::Unverified);
        // Worst first, then in the order they were found.
        findings.sort_by_key(|finding| std::cmp::Reverse(finding.verdict));
        Self { verdict, findings }
    }

    /// Whether the installer may take this build at all (unverified ones still
    /// need consent).
    pub fn installable(&self) -> bool {
        self.verdict != Verdict::Incompatible
    }

    /// The sentence a row shows: the most decisive finding.
    pub fn headline(&self) -> String {
        self.findings
            .first()
            .map(|finding| finding.detail.clone())
            .unwrap_or_default()
    }

    /// The findings that made the verdict what it is.
    pub fn reasons(&self) -> impl Iterator<Item = &Finding> {
        self.findings
            .iter()
            .filter(move |finding| finding.verdict == self.verdict)
    }
}

// ---------------------------------------------------------------------------
// Known projects
// ---------------------------------------------------------------------------

/// What Fathom knows about one project, beyond what its index says.
#[derive(Debug, Clone, Copy)]
pub struct Known {
    pub provider: Provider,
    pub id: &'static str,
    pub slug: &'static str,
    pub fact: Fact,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fact {
    /// Requiring this marks a build as Ornithe's. Ornithe's intermediary
    /// mappings differ from Legacy Fabric's, so nothing built against it loads
    /// in Fathom.
    OrnitheLibrary,
    /// Upstream Fabric's API. It has no 1.8.9 build, so a build requiring it
    /// was built for modern Fabric.
    ModernFabricLibrary,
    /// Legacy Fabric's API. Requiring it is consistent with Legacy Fabric but
    /// not proof of it: the project ships Ornithe builds too.
    LegacyFabricLibrary,
    /// Fathom installs and pins this itself. Installing a second copy from
    /// the browser would fight the pin.
    ManagedByFathom { how: &'static str },
}

/// Projects whose role was checked by hand against the live index
/// (2026-10-12): the ids are Modrinth's, the slugs resolve to them.
pub const KNOWN: &[Known] = &[
    Known {
        provider: Provider::Modrinth,
        id: "EHGtwpJv",
        slug: "osl",
        fact: Fact::OrnitheLibrary,
    },
    Known {
        provider: Provider::Modrinth,
        id: "P7dR8mSH",
        slug: "fabric-api",
        fact: Fact::ModernFabricLibrary,
    },
    Known {
        provider: Provider::Modrinth,
        id: "9CJED7xi",
        slug: "legacy-fabric-api",
        fact: Fact::LegacyFabricLibrary,
    },
    Known {
        provider: Provider::Modrinth,
        id: "FCvC00y3",
        slug: crate::mods::catalog::MODRINTH_OPTIFABRIC,
        fact: Fact::ManagedByFathom {
            how: "Fathom installs OptiFabric itself, pinned to the build that works with \
                  OptiFine HD U M5. Use Add OptiFine on the Installed tab instead.",
        },
    },
];

/// The fact Fathom holds about a project, if any.
pub fn known(provider: Provider, id_or_slug: &str) -> Option<Fact> {
    KNOWN
        .iter()
        .find(|known| {
            known.provider == provider && (known.id == id_or_slug || known.slug == id_or_slug)
        })
        .map(|known| known.fact)
}

// ---------------------------------------------------------------------------
// Builds
// ---------------------------------------------------------------------------

fn loader_name(tag: &str) -> &str {
    match tag {
        "forge" => "Forge",
        "neoforge" => "NeoForge",
        "ornithe" => "Ornithe",
        "babric" => "Babric",
        "quilt" => "Quilt",
        "liteloader" => "LiteLoader",
        "rift" => "Rift",
        "modloader" | "risugami's modloader" => "ModLoader",
        "bukkit" | "spigot" | "paper" | "purpur" | "folia" => "a server platform",
        "fabric" => "Fabric",
        other => other,
    }
}

/// A short, readable range of the versions a build lists.
pub fn describe_versions<'a>(versions: impl IntoIterator<Item = &'a String>) -> String {
    let all: Vec<&String> = versions.into_iter().collect();
    match all.as_slice() {
        [] => "no stated Minecraft version".to_owned(),
        [one] => format!("Minecraft {one}"),
        [first, .., last] if all.len() > 3 => {
            format!("Minecraft {first} to {last} ({} versions)", all.len())
        }
        many => format!(
            "Minecraft {}",
            many.iter()
                .map(|v| v.as_str())
                .collect::<Vec<_>>()
                .join(", ")
        ),
    }
}

fn loaders_phrase(tags: &[&str]) -> String {
    let mut names: Vec<&str> = tags.iter().map(|tag| loader_name(tag)).collect();
    names.dedup();
    match names.as_slice() {
        [] => String::new(),
        [one] => (*one).to_owned(),
        [init @ .., last] => format!("{} and {last}", init.join(", ")),
    }
}

fn game_version_findings(file: &ModFile, target: &Target, out: &mut Vec<Finding>) {
    if file.game_versions.is_empty() {
        out.push(Finding::new(
            "version-unstated",
            Verdict::Unverified,
            "This build does not say which Minecraft versions it is for.",
        ));
    } else if file.game_versions.contains(&target.minecraft) {
        out.push(Finding::new(
            "version",
            Verdict::Compatible,
            format!("Built for Minecraft {}.", target.minecraft),
        ));
    } else {
        out.push(Finding::new(
            "version",
            Verdict::Incompatible,
            format!(
                "Built for {}, not {}.",
                describe_versions(&file.game_versions),
                target.minecraft
            ),
        ));
    }
}

fn loader_findings(file: &ModFile, target: &Target, out: &mut Vec<Finding>) {
    let wanted = target.loader_names();
    if wanted.iter().any(|name| file.loaders.contains(*name)) {
        out.push(Finding::new(
            "loader",
            Verdict::Compatible,
            format!("Tagged for {}.", target.loader.label()),
        ));
        return;
    }
    if file.loaders.is_empty() {
        out.push(Finding::new(
            "loader-unstated",
            Verdict::Unverified,
            "This build does not say which mod loader it is for.",
        ));
        return;
    }
    if target.loader == Loader::LegacyFabric && file.loaders.contains("fabric") {
        let detail = match file.project.provider {
            Provider::CurseForge => {
                "Tagged \u{201c}Fabric\u{201d}. CurseForge has no separate Legacy Fabric tag, \
                 so a 1.8.9 Fabric build there may be for Legacy Fabric or for Ornithe, \
                 which Fathom cannot load."
            }
            Provider::Modrinth => {
                "Tagged for Fabric rather than Legacy Fabric. Fabric itself has never \
                 supported 1.8.9, so builds like this are usually Legacy Fabric mods \
                 uploaded before that tag existed \u{2014} but some are for Ornithe, \
                 which Fathom cannot load."
            }
        };
        out.push(Finding::new("loader-fabric", Verdict::Unverified, detail));
        return;
    }
    let tags: Vec<&str> = file.loaders.iter().map(String::as_str).collect();
    let detail = if tags.contains(&"ornithe") && tags.len() == 1 {
        "Built for Ornithe. Ornithe uses different mappings from Legacy Fabric, so its \
         mods do not load in Fathom."
            .to_owned()
    } else {
        format!(
            "Built for {}, not {}.",
            loaders_phrase(&tags),
            target.loader.label()
        )
    };
    out.push(Finding::new("loader", Verdict::Incompatible, detail));
}

fn dependency_findings(file: &ModFile, target: &Target, out: &mut Vec<Finding>) {
    if target.loader != Loader::LegacyFabric {
        return;
    }
    for dependency in &file.dependencies {
        if dependency.kind != DependencyKind::Required {
            continue;
        }
        let Some(reference) = &dependency.project else {
            continue;
        };
        match known(reference.provider, &reference.id) {
            Some(Fact::OrnitheLibrary) => out.push(Finding::new(
                "needs-ornithe",
                Verdict::Incompatible,
                "Requires Ornithe Standard Libraries, so it is an Ornithe mod. Ornithe's \
                 mappings differ from Legacy Fabric's and its mods do not load in Fathom.",
            )),
            Some(Fact::ModernFabricLibrary) => out.push(Finding::new(
                "needs-modern-fabric",
                Verdict::Incompatible,
                "Requires Fabric API for modern Fabric, which has no 1.8.9 build. \
                 (Legacy Fabric API is a different project.)",
            )),
            Some(Fact::LegacyFabricLibrary) => out.push(Finding::new(
                "needs-legacy-fabric-api",
                Verdict::Compatible,
                "Requires Legacy Fabric API, which Fathom installs with it.",
            )),
            Some(Fact::ManagedByFathom { .. }) | None => {}
        }
    }
}

/// Everything Fathom can say about one build for one target, without opening it.
pub fn assess_file(file: &ModFile, target: &Target) -> Assessment {
    let mut findings = Vec::new();
    if !target.loads_mods() {
        findings.push(Finding::new(
            "no-loader",
            Verdict::Incompatible,
            "This profile has no mod loader, so no mod would ever be loaded.",
        ));
        return Assessment::from_findings(findings);
    }
    if let Some(Fact::ManagedByFathom { how }) = known(file.project.provider, &file.project.id) {
        findings.push(Finding::new("managed", Verdict::Incompatible, how));
    }
    game_version_findings(file, target, &mut findings);
    loader_findings(file, target, &mut findings);
    dependency_findings(file, target, &mut findings);
    match file.channel {
        ReleaseChannel::Release => {}
        channel => findings.push(Finding::new(
            "pre-release",
            Verdict::Compatible,
            format!(
                "A {} build: its author marks it as not finished.",
                channel.label().to_lowercase()
            ),
        )),
    }
    Assessment::from_findings(findings)
}

/// The same judgement about a build already installed, from what was recorded
/// about it when it was installed or identified: its loader tags and game
/// versions. Dependencies are checked separately, against the profile.
pub fn assess_recorded(
    loaders: &std::collections::BTreeSet<String>,
    game_versions: &std::collections::BTreeSet<String>,
    target: &Target,
) -> Assessment {
    let file = ModFile {
        project: ProjectRef::new(Provider::Modrinth, "recorded"),
        version_id: String::new(),
        number: String::new(),
        name: String::new(),
        channel: ReleaseChannel::Release,
        loaders: loaders.clone(),
        game_versions: game_versions.clone(),
        file_name: String::new(),
        download: super::model::FileDownload::Withheld {
            where_to_get: String::new(),
        },
        hash: crate::mods::definition::ArtifactHash::new("0".repeat(40), None)
            .unwrap_or_else(|| unreachable!("forty zeros are a valid SHA-1")),
        dependencies: Vec::new(),
        published: None,
    };
    assess_file(&file, target)
}

/// One build and what the resolver made of it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AssessedFile {
    pub file: ModFile,
    pub assessment: Assessment,
}

/// Assess every build and order them the way a picker should offer them:
/// compatible before unverified before incompatible; within each, releases
/// before betas before alphas; within each of those, the index's own order
/// (newest first).
pub fn assess_files(files: Vec<ModFile>, target: &Target) -> Vec<AssessedFile> {
    let mut assessed: Vec<AssessedFile> = files
        .into_iter()
        .map(|file| AssessedFile {
            assessment: assess_file(&file, target),
            file,
        })
        .collect();
    assessed.sort_by_key(|entry| (entry.assessment.verdict, entry.file.channel));
    assessed
}

// ---------------------------------------------------------------------------
// Projects
// ---------------------------------------------------------------------------

/// The resolver's answer about a whole project: the best of its builds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectVerdict {
    pub verdict: Verdict,
    /// The full reason, for a tooltip or a detail page.
    pub headline: String,
    /// The same reason in one short line, for a result card.
    pub short: String,
    /// The build an install would take, when there is one.
    pub best: Option<BestFile>,
    /// How many builds for this Minecraft version exist at all.
    pub builds: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BestFile {
    pub version_id: String,
    pub number: String,
    pub channel: ReleaseChannel,
    pub published: Option<String>,
}

/// A finding in a few words, for a card with room for one line.
fn short_reason(finding: Option<&Finding>, fallback: &str) -> String {
    let Some(finding) = finding else {
        return fallback.to_owned();
    };
    match finding.code {
        "loader-fabric" => "Tagged Fabric, not Legacy Fabric: not confirmed to load.".to_owned(),
        "version-unstated" | "loader-unstated" => {
            "Its builds don't say what they are for.".to_owned()
        }
        "needs-ornithe" => "Needs Ornithe's libraries, which Fathom can't load.".to_owned(),
        "needs-modern-fabric" => "Needs modern Fabric API, which has no 1.8.9 build.".to_owned(),
        "managed" => "Fathom installs this itself, with OptiFine.".to_owned(),
        "loader" if finding.detail.starts_with("Built for Ornithe.") => {
            "Built for Ornithe, which Fathom can't load.".to_owned()
        }
        _ => finding.detail.clone(),
    }
}

/// Summarise a project from its assessed builds for the target's version.
pub fn summarise(project: &ProjectRef, files: &[AssessedFile], target: &Target) -> ProjectVerdict {
    if let Some(Fact::ManagedByFathom { how }) = known(project.provider, &project.id) {
        return ProjectVerdict {
            verdict: Verdict::Incompatible,
            headline: how.to_owned(),
            short: short_reason(None, "Fathom installs this itself, with OptiFine."),
            best: None,
            builds: files.len(),
        };
    }
    let Some(best) = files.first() else {
        let headline = format!("No build for Minecraft {}.", target.minecraft);
        return ProjectVerdict {
            verdict: Verdict::Incompatible,
            short: headline.clone(),
            headline,
            best: None,
            builds: 0,
        };
    };
    let headline = match best.assessment.verdict {
        Verdict::Compatible => format!(
            "{} is tagged for Minecraft {} on {}.",
            best.file.number,
            target.minecraft,
            target.loader.label()
        ),
        _ => best
            .assessment
            .reasons()
            .next()
            .map(|finding| finding.detail.clone())
            .unwrap_or_default(),
    };
    let short = match best.assessment.verdict {
        Verdict::Compatible => headline.clone(),
        _ => short_reason(best.assessment.reasons().next(), &headline),
    };
    ProjectVerdict {
        verdict: best.assessment.verdict,
        short,
        headline,
        best: best.assessment.installable().then(|| BestFile {
            version_id: best.file.version_id.clone(),
            number: best.file.number.clone(),
            channel: best.file.channel,
            published: best.file.published.clone(),
        }),
        builds: files.len(),
    }
}

/// What a search hit's own facets already rule out, before any build is read.
///
/// `Some` only for a definite no. Facets are project-wide unions — a project
/// with Legacy Fabric builds for 1.12 and Forge builds for 1.8.9 matches both
/// halves of a "Legacy Fabric and 1.8.9" search — so a yes here would be a
/// claim the builds might not support. The definite answer comes from
/// [`summarise`].
pub fn rule_out(project: &ModProject, target: &Target) -> Option<String> {
    if !target.loads_mods() {
        return Some("This profile has no mod loader.".to_owned());
    }
    if !project.minecraft_versions.is_empty()
        && !project.minecraft_versions.contains(&target.minecraft)
    {
        return Some(format!("No build for Minecraft {}.", target.minecraft));
    }
    if target.loader == Loader::LegacyFabric && !project.loaders.is_empty() {
        let plausible = target
            .loader_names()
            .iter()
            .any(|name| project.loaders.contains(*name))
            || project.loaders.contains("fabric");
        if !plausible {
            let tags: Vec<&str> = project.loaders.iter().map(String::as_str).collect();
            return Some(format!("Only built for {}.", loaders_phrase(&tags)));
        }
    }
    None
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::mods::browse::model::{FileDependency, FileDownload};
    use crate::mods::definition::ArtifactHash;

    fn target() -> Target {
        Target::new("1.8.9", Loader::LegacyFabric)
    }

    fn file(provider: Provider, loaders: &[&str], versions: &[&str]) -> ModFile {
        ModFile {
            project: ProjectRef::new(provider, "AANobbMI"),
            version_id: "v1".into(),
            number: "1.0.0".into(),
            name: "One".into(),
            channel: ReleaseChannel::Release,
            loaders: loaders.iter().map(|s| (*s).to_owned()).collect(),
            game_versions: versions.iter().map(|s| (*s).to_owned()).collect(),
            file_name: "one.jar".into(),
            download: FileDownload::Url {
                url: "https://cdn.modrinth.com/data/AANobbMI/versions/v1/one.jar".into(),
            },
            hash: ArtifactHash::new("a".repeat(40), Some(10)).unwrap(),
            dependencies: Vec::new(),
            published: None,
        }
    }

    fn requiring(mut file: ModFile, id: &str) -> ModFile {
        file.dependencies.push(FileDependency {
            kind: DependencyKind::Required,
            project: Some(ProjectRef::new(Provider::Modrinth, id)),
            version_id: None,
        });
        file
    }

    #[test]
    fn a_legacy_fabric_build_for_1_8_9_is_compatible() {
        let a = assess_file(
            &file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Compatible);
        // So is one tagged for every loader under the sun, as long as ours is
        // among them (ksyxis, 2026-10-12).
        let a = assess_file(
            &file(
                Provider::Modrinth,
                &[
                    "fabric",
                    "forge",
                    "legacy-fabric",
                    "neoforge",
                    "ornithe",
                    "quilt",
                ],
                &["1.8.9", "1.12.2"],
            ),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Compatible);
    }

    #[test]
    fn a_plain_fabric_build_for_1_8_9_is_unverified_on_both_indexes() {
        for provider in [Provider::Modrinth, Provider::CurseForge] {
            let a = assess_file(&file(provider, &["fabric"], &["1.8.9"]), &target());
            assert_eq!(a.verdict, Verdict::Unverified, "{provider}");
            assert_eq!(a.findings[0].code, "loader-fabric");
            assert!(a.headline().contains("Ornithe"));
        }
    }

    #[test]
    fn modern_fabric_forge_and_ornithe_builds_are_incompatible() {
        let cases = [
            (vec!["fabric"], vec!["1.20.1"], "version"),
            (vec!["forge"], vec!["1.8.9"], "loader"),
            (vec!["ornithe"], vec!["1.8.9"], "loader"),
            (vec!["legacy-fabric"], vec!["1.12.2"], "version"),
            (vec!["quilt"], vec!["1.8.9"], "loader"),
        ];
        for (loaders, versions, code) in cases {
            let a = assess_file(&file(Provider::Modrinth, &loaders, &versions), &target());
            assert_eq!(a.verdict, Verdict::Incompatible, "{loaders:?} {versions:?}");
            assert_eq!(a.findings[0].code, code);
        }
    }

    #[test]
    fn a_dependency_can_rule_a_build_out_but_never_in() {
        // Tagged correctly, but needs Ornithe's libraries: not ours.
        let a = assess_file(
            &requiring(
                file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]),
                "EHGtwpJv",
            ),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Incompatible);
        assert_eq!(a.findings[0].code, "needs-ornithe");

        let a = assess_file(
            &requiring(
                file(Provider::Modrinth, &["fabric"], &["1.8.9"]),
                "P7dR8mSH",
            ),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Incompatible);

        // Needing Legacy Fabric API is consistent with us but does not prove
        // a plain-Fabric build is ours: that project ships Ornithe builds too.
        let a = assess_file(
            &requiring(
                file(Provider::Modrinth, &["fabric"], &["1.8.9"]),
                "9CJED7xi",
            ),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Unverified);
        assert!(a
            .findings
            .iter()
            .any(|f| f.code == "needs-legacy-fabric-api"));
    }

    #[test]
    fn missing_metadata_is_unverified_not_compatible() {
        let a = assess_file(&file(Provider::Modrinth, &[], &["1.8.9"]), &target());
        assert_eq!(a.verdict, Verdict::Unverified);
        let a = assess_file(
            &file(Provider::Modrinth, &["legacy-fabric"], &[]),
            &target(),
        );
        assert_eq!(a.verdict, Verdict::Unverified);
    }

    #[test]
    fn optifabric_is_left_to_fathom() {
        let mut f = file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]);
        f.project = ProjectRef::new(Provider::Modrinth, "FCvC00y3");
        let a = assess_file(&f, &target());
        assert_eq!(a.verdict, Verdict::Incompatible);
        assert_eq!(a.findings[0].code, "managed");
    }

    #[test]
    fn vanilla_loads_nothing() {
        let a = assess_file(
            &file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]),
            &Target::new("1.8.9", Loader::Vanilla),
        );
        assert_eq!(a.verdict, Verdict::Incompatible);
    }

    #[test]
    fn builds_are_offered_best_first_and_releases_before_betas() {
        let mut beta = file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]);
        beta.channel = ReleaseChannel::Beta;
        beta.version_id = "beta".into();
        let mut fabric = file(Provider::Modrinth, &["fabric"], &["1.8.9"]);
        fabric.version_id = "fabric".into();
        let mut forge = file(Provider::Modrinth, &["forge"], &["1.8.9"]);
        forge.version_id = "forge".into();
        let mut release = file(Provider::Modrinth, &["legacy-fabric"], &["1.8.9"]);
        release.version_id = "release".into();
        let order: Vec<String> = assess_files(vec![forge, fabric, beta, release], &target())
            .into_iter()
            .map(|a| a.file.version_id)
            .collect();
        assert_eq!(order, ["release", "beta", "fabric", "forge"]);
    }

    #[test]
    fn a_project_with_no_installable_build_offers_none() {
        let project = ModProject {
            reference: ProjectRef::new(Provider::Modrinth, "AANobbMI"),
            slug: "x".into(),
            name: "X".into(),
            summary: String::new(),
            description: None,
            authors: Vec::new(),
            icon: None,
            gallery: Vec::new(),
            downloads: 0,
            categories: Vec::new(),
            project_url: "https://modrinth.com/mod/x".into(),
            source_url: None,
            issues_url: None,
            license: crate::mods::definition::License::Unknown,
            minecraft_versions: ["1.8.9".to_owned(), "1.12.2".to_owned()]
                .into_iter()
                .collect(),
            loaders: ["forge".to_owned()].into_iter().collect(),
            updated: None,
            distribution: super::super::model::Distribution::Allowed,
            detailed: false,
        };
        let files = assess_files(
            vec![file(Provider::Modrinth, &["forge"], &["1.8.9"])],
            &target(),
        );
        let summary = summarise(&project.reference, &files, &target());
        assert_eq!(summary.verdict, Verdict::Incompatible);
        assert_eq!(summary.short, "Built for Forge, not Legacy Fabric.");
        assert!(summary.best.is_none());
        assert_eq!(summary.builds, 1);
        assert!(
            rule_out(&project, &target()).is_some(),
            "facets alone rule out a Forge-only project"
        );

        let none = summarise(&project.reference, &[], &target());
        assert_eq!(none.verdict, Verdict::Incompatible);
        assert!(none.headline.starts_with("No build for Minecraft 1.8.9"));
    }

    #[test]
    fn facets_never_rule_a_plausible_project_in() {
        // rule_out answers only "definitely not"; a plausible project is None,
        // which the interface shows as "checking", not as compatible.
        let mut project_loaders = std::collections::BTreeSet::new();
        project_loaders.insert("fabric".to_owned());
        let project = ModProject {
            reference: ProjectRef::new(Provider::Modrinth, "AANobbMI"),
            slug: "x".into(),
            name: "X".into(),
            summary: String::new(),
            description: None,
            authors: Vec::new(),
            icon: None,
            gallery: Vec::new(),
            downloads: 0,
            categories: Vec::new(),
            project_url: "https://modrinth.com/mod/x".into(),
            source_url: None,
            issues_url: None,
            license: crate::mods::definition::License::Unknown,
            minecraft_versions: ["1.8.9".to_owned()].into_iter().collect(),
            loaders: project_loaders,
            updated: None,
            distribution: super::super::model::Distribution::Allowed,
            detailed: false,
        };
        assert!(rule_out(&project, &target()).is_none());
    }
}
