//! Deciding whether two listings are the same mod.
//!
//! # The problem this exists for
//!
//! Most 1.8.9 mods worth installing are on both Modrinth and `CurseForge`, with
//! different project ids, sometimes different slugs and often slightly
//! different names. A browser that merges the two indexes naively offers the
//! same mod twice and, worse, will happily install both — two jars, the same
//! classes, a crash at startup that looks like nothing the user did.
//!
//! # What is and is not evidence
//!
//! In descending order of how much it proves:
//!
//! 1. **The same bytes.** An installed file whose SHA-1 equals the candidate's
//!    is the candidate, whatever either index calls it. Certain.
//! 2. **The same provider and project id.** Certain, and the ordinary
//!    "already installed" case.
//! 3. **The same normalised slug.** Strong: slugs are chosen by the author and
//!    the same author usually picks the same one on both sites.
//! 4. **The same normalised name.** Good, but names collide — "Better Sprinting"
//!    and `BetterSprinting` are one mod; "Backpacks" is four.
//! 5. **A file name stem that matches an installed jar.** Weak on its own: it
//!    is how a mod installed outside the browser can be recognised at all, and
//!    it is the reason the weak result is `Possible` rather than `Same`.
//!
//! Anything below certainty produces [`Duplicate::Possible`], which the catalog
//! surfaces and never acts on. Guessing wrong in the destructive direction —
//! refusing an install, or replacing a file — is much worse than showing a
//! sentence saying two things look alike.

use super::model::{ModFile, ModProject, ProjectRef};
use crate::mods::config::ProfileMod;

/// A name reduced to the part that identifies it.
///
/// Lowercased, stripped of everything that is not a letter or a digit, with the
/// words every mod carries removed: loader names, "mod", "minecraft", the
/// Minecraft version, and the `fabric`/`forge` port suffixes. What is left of
/// "Sodium (Fabric) for Minecraft 1.8.9" is "sodium", which is the point.
pub fn normalise(raw: &str) -> String {
    const NOISE: [&str; 12] = [
        "fabric", "forge", "legacy", "mod", "mods", "minecraft", "mc", "port", "unofficial", "fork",
        "for", "the",
    ];

    let lowered = raw.to_ascii_lowercase();
    let mut words: Vec<String> = Vec::new();
    let mut current = String::new();
    for character in lowered.chars() {
        if character.is_ascii_alphanumeric() {
            current.push(character);
        } else if !current.is_empty() {
            words.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        words.push(current);
    }

    let mut out = String::new();
    for word in words {
        // A bare number is a version, a year or a Minecraft release; none of
        // them identify a mod.
        if word.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        if NOISE.contains(&word.as_str()) {
            continue;
        }
        out.push_str(&word);
    }
    // Everything was noise: fall back to the raw alphanumerics rather than
    // declaring the mod nameless, which would match every other nameless one.
    if out.is_empty() {
        out = lowered.chars().filter(char::is_ascii_alphanumeric).collect();
    }
    out
}

/// The stem of a jar file name, normalised the same way.
///
/// `sodium-fabric-0.4.4+1.8.9.jar` becomes `sodium`. Used only as weak
/// evidence, because file names are the least reliable thing about a mod.
pub fn normalise_file_name(file_name: &str) -> String {
    let stem = file_name
        .strip_suffix(".jar")
        .or_else(|| file_name.strip_suffix(".JAR"))
        .unwrap_or(file_name);
    // Cut at the first version-looking token: everything after it is build
    // metadata rather than identity.
    let mut identity = String::new();
    for part in stem.split(['-', '_', '+']) {
        let looks_like_a_version = part
            .chars()
            .next()
            .is_some_and(|first| first.is_ascii_digit())
            || part.eq_ignore_ascii_case("v");
        if looks_like_a_version {
            break;
        }
        identity.push_str(part);
        identity.push('-');
    }
    if identity.is_empty() {
        stem.clone_into(&mut identity);
    }
    normalise(&identity)
}

/// How sure we are that two things are the same mod.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Duplicate {
    /// No evidence at all.
    None,
    /// Certain: the same bytes, or the same project at the same provider.
    Same { reason: String },
    /// Enough of a match to tell the user about, and never enough to act on.
    Possible { reason: String },
}

impl Duplicate {
    pub fn is_certain(&self) -> bool {
        matches!(self, Duplicate::Same { .. })
    }

    pub fn reason(&self) -> Option<&str> {
        match self {
            Duplicate::None => None,
            Duplicate::Same { reason } | Duplicate::Possible { reason } => Some(reason),
        }
    }
}

/// Whether two listings from different indexes are the same mod.
///
/// Used to merge search results, so it only ever returns `Same` for slug
/// equality — the strongest signal two indexes can share — and `Possible` for a
/// name match, which the interface shows as "also on `CurseForge`" rather than
/// hiding a row.
pub fn same_project(left: &ModProject, right: &ModProject) -> Duplicate {
    if left.reference == right.reference {
        return Duplicate::Same {
            reason: "the same project".to_owned(),
        };
    }
    let left_slug = normalise(&left.slug);
    let right_slug = normalise(&right.slug);
    if !left_slug.is_empty() && left_slug == right_slug {
        return Duplicate::Same {
            reason: format!("both indexes call this {}", left.slug),
        };
    }
    let left_name = normalise(&left.name);
    let right_name = normalise(&right.name);
    if !left_name.is_empty() && left_name == right_name {
        return Duplicate::Possible {
            reason: format!("{} is listed on both indexes under this name", left.name),
        };
    }
    Duplicate::None
}

/// Whether a profile already has the mod this listing describes.
///
/// `file` is optional: it is the strongest evidence there is (its hash) and it
/// is not always known yet — the search results grid has projects, not files.
pub fn already_installed(
    installed: &[ProfileMod],
    project: &ModProject,
    file: Option<&ModFile>,
) -> Option<(ProfileMod, Duplicate)> {
    // 1. The same bytes. Nothing beats this.
    if let Some(file) = file {
        if let Some(entry) = installed
            .iter()
            .find(|entry| entry.sha1.eq_ignore_ascii_case(&file.hash.sha1))
        {
            return Some((
                entry.clone(),
                Duplicate::Same {
                    reason: format!("{} is already installed; it is the same file", entry.file_name),
                },
            ));
        }
    }

    // 2. The same project at the same provider, as recorded when it was
    //    installed.
    if let Some(entry) = installed.iter().find(|entry| {
        entry
            .origin
            .repository
            .as_deref()
            .and_then(super::model::Provider::parse)
            == Some(project.reference.provider)
            && entry.origin.project.as_deref() == Some(project.reference.id.as_str())
    }) {
        return Some((
            entry.clone(),
            Duplicate::Same {
                reason: format!("{} is already installed", project.name),
            },
        ));
    }

    // 3. The same project at the *other* provider. This is the cross-index
    //    duplicate the whole module exists for: certain, because the recorded
    //    project id and slug both came from an index rather than from a guess.
    let project_key = normalise(&project.slug);
    if let Some(entry) = installed.iter().find(|entry| {
        entry
            .origin
            .name
            .as_deref()
            .is_some_and(|name| !project_key.is_empty() && normalise(name) == project_key)
    }) {
        return Some((
            entry.clone(),
            Duplicate::Possible {
                reason: format!(
                    "{} is already installed from {}",
                    entry
                        .origin
                        .name
                        .clone()
                        .unwrap_or_else(|| entry.file_name.clone()),
                    entry
                        .origin
                        .repository
                        .clone()
                        .unwrap_or_else(|| "another source".to_owned())
                ),
            },
        ));
    }

    // 4. A jar whose name looks like this mod. The only evidence available for
    //    a file somebody dropped in by hand.
    let name_key = normalise(&project.name);
    if !name_key.is_empty() {
        if let Some(entry) = installed
            .iter()
            .find(|entry| normalise_file_name(&entry.file_name) == name_key
                || normalise_file_name(&entry.file_name) == project_key)
        {
            return Some((
                entry.clone(),
                Duplicate::Possible {
                    reason: format!(
                        "{} is already in this profile and looks like the same mod",
                        entry.file_name
                    ),
                },
            ));
        }
    }

    None
}

/// Merge listings of the same mod from different indexes.
///
/// The first listing wins the row and the rest become [`ModProject`]
/// references beside it, so a user can see that a mod is on both and pick.
/// Order is the caller's: the catalog passes results in provider preference
/// order, and preference is not this module's business.
pub fn merge(projects: Vec<ModProject>) -> Vec<(ModProject, Vec<ProjectRef>)> {
    let mut out: Vec<(ModProject, Vec<ProjectRef>)> = Vec::new();
    for project in projects {
        let mut merged = false;
        for (kept, others) in &mut out {
            if same_project(kept, &project).is_certain() {
                if !others.contains(&project.reference) {
                    others.push(project.reference.clone());
                }
                // Keep the richer of the two summaries: an empty one on the
                // winning row would hide information the other index gave us.
                if kept.summary.is_empty() && !project.summary.is_empty() {
                    kept.summary.clone_from(&project.summary);
                }
                if kept.icon.is_none() {
                    kept.icon.clone_from(&project.icon);
                }
                merged = true;
                break;
            }
        }
        if !merged {
            out.push((project, Vec::new()));
        }
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use std::collections::BTreeSet;

    use super::*;
    use crate::mods::browse::model::{Distribution, Provider};
    use crate::mods::definition::{License, ModId};

    fn project(provider: Provider, id: &str, slug: &str, name: &str) -> ModProject {
        ModProject {
            reference: ProjectRef::new(provider, id),
            slug: slug.to_owned(),
            name: name.to_owned(),
            summary: String::new(),
            description: None,
            authors: Vec::new(),
            icon: None,
            gallery: Vec::new(),
            downloads: 0,
            categories: Vec::new(),
            project_url: String::new(),
            source_url: None,
            issues_url: None,
            license: License::Unknown,
            minecraft_versions: BTreeSet::default(),
            loaders: BTreeSet::default(),
            updated: None,
            distribution: Distribution::Allowed,
            detailed: false,
        }
    }

    #[test]
    fn normalising_strips_the_words_every_mod_carries() {
        assert_eq!(normalise("Sodium (Fabric) for Minecraft 1.8.9"), "sodium");
        assert_eq!(normalise("Legacy Fabric API"), "api");
        assert_eq!(normalise("Better-Sprinting"), "bettersprinting");
        assert_eq!(normalise("BetterSprinting"), "bettersprinting");
        // Nothing but noise still produces something, rather than matching
        // every other empty key.
        assert_eq!(normalise("Fabric"), "fabric");
    }

    #[test]
    fn a_jar_name_is_reduced_to_the_part_that_identifies_it() {
        assert_eq!(normalise_file_name("sodium-fabric-0.4.4+1.8.9.jar"), "sodium");
        assert_eq!(normalise_file_name("BetterSprinting-1.8.9.jar"), "bettersprinting");
        assert_eq!(normalise_file_name("mystery.jar"), "mystery");
    }

    #[test]
    fn the_same_slug_on_two_indexes_is_the_same_mod() {
        let modrinth = project(Provider::Modrinth, "AAA", "sodium", "Sodium");
        let curse = project(Provider::CurseForge, "12345", "sodium", "Sodium (Fabric)");
        assert!(same_project(&modrinth, &curse).is_certain());
    }

    #[test]
    fn a_name_match_alone_is_reported_and_never_acted_on() {
        let modrinth = project(Provider::Modrinth, "AAA", "backpacks-redux", "Backpacks");
        let curse = project(Provider::CurseForge, "999", "simple-backpacks", "Backpacks");
        match same_project(&modrinth, &curse) {
            Duplicate::Possible { .. } => {}
            other => panic!("a name match must not be certain: {other:?}"),
        }
    }

    #[test]
    fn merging_keeps_one_row_and_records_where_else_it_lives() {
        let rows = merge(vec![
            project(Provider::Modrinth, "AAA", "sodium", "Sodium"),
            project(Provider::CurseForge, "12345", "sodium", "Sodium"),
            project(Provider::CurseForge, "777", "iris", "Iris"),
        ]);
        assert_eq!(rows.len(), 2);
        let (first, others) = rows.first().unwrap();
        assert_eq!(first.reference.provider, Provider::Modrinth);
        assert_eq!(others.len(), 1);
        assert_eq!(others.first().unwrap().provider, Provider::CurseForge);
    }

    #[test]
    fn the_same_bytes_are_the_same_mod_whatever_it_is_called() {
        let sha = "b".repeat(40);
        let installed = vec![ProfileMod::new(
            ModId::new("something-else").unwrap(),
            &sha,
            "something-else.jar",
        )];
        let file = ModFile {
            project: ProjectRef::new(Provider::CurseForge, "1"),
            version_id: "9".into(),
            number: "1.0".into(),
            name: "1.0".into(),
            channel: super::super::model::ReleaseChannel::Release,
            loaders: BTreeSet::default(),
            game_versions: BTreeSet::default(),
            file_name: "sodium.jar".into(),
            download: super::super::model::FileDownload::Url {
                url: "https://edge.forgecdn.net/files/1/2/sodium.jar".into(),
            },
            hash: crate::mods::definition::ArtifactHash::new(&sha, None).unwrap(),
            dependencies: Vec::new(),
            published: None,
        };
        let candidate = project(Provider::CurseForge, "1", "sodium", "Sodium");
        let (entry, duplicate) =
            already_installed(&installed, &candidate, Some(&file)).expect("found");
        assert_eq!(entry.file_name, "something-else.jar");
        assert!(duplicate.is_certain());
    }

    #[test]
    fn a_hand_installed_jar_is_recognised_but_only_as_a_possibility() {
        let installed = vec![ProfileMod::new(
            ModId::new("bettersprinting").unwrap(),
            "c".repeat(40),
            "BetterSprinting-1.8.9.jar",
        )];
        let candidate = project(Provider::Modrinth, "XYZ", "better-sprinting", "Better Sprinting");
        let (_, duplicate) = already_installed(&installed, &candidate, None).expect("found");
        assert!(
            !duplicate.is_certain(),
            "a filename match must never be certain: {duplicate:?}"
        );
        assert!(duplicate.reason().is_some_and(|r| r.contains("BetterSprinting")));
    }

    #[test]
    fn an_unrelated_profile_produces_no_match_at_all() {
        let installed = vec![ProfileMod::new(
            ModId::new("optifine").unwrap(),
            "d".repeat(40),
            "OptiFine_1.8.9_HD_U_M5.jar",
        )];
        let candidate = project(Provider::Modrinth, "XYZ", "sodium", "Sodium");
        assert!(already_installed(&installed, &candidate, None).is_none());
    }
}
