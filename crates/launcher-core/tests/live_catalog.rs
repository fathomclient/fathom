//! The mod browser against the real Modrinth API.
//!
//! Every test here is `#[ignore]`d: they make real requests, their answers
//! change as authors publish, and CI must not depend on a third party being up.
//! Run them by hand before a release, or after touching the browser:
//!
//! ```text
//! cargo test -p launcher-core --test live_catalog -- --ignored --nocapture
//! ```
//!
//! The projects named below were checked by hand on 2026-10-12. If one of them
//! changes its builds, the failure says which and what it saw — update the
//! expectation, not the resolver, unless the resolver is what is wrong.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::sync::Arc;

use launcher_core::mods::browse::compat::Verdict;
use launcher_core::mods::browse::{
    Breadth, CatalogProvider, ModCatalog, ModProject, ModrinthProvider, ProjectRef, Provider,
    SearchQuery, Sort, Target,
};

use launcher_core::network::{HttpClient, NetworkJournal, NetworkPolicy, PerActionGrant};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::Loader;
use launcher_core::Cancel;

/// The policy a user has after turning on mod repositories in Privacy, which
/// is off by default.
fn policy() -> NetworkPolicy {
    NetworkPolicy {
        allow_mod_repositories: true,
        ..NetworkPolicy::default()
    }
}

fn catalog(root: &std::path::Path) -> ModCatalog {
    let paths = DataPaths::rooted_at(root);
    paths.ensure().unwrap();
    let http = HttpClient::new(
        policy(),
        NetworkJournal::new(),
        "fathomclient/fathom-live-test (github.com/fathomclient/fathom)",
    );
    let providers: Vec<Arc<dyn CatalogProvider>> =
        vec![Arc::new(ModrinthProvider::new(http.clone()))];
    ModCatalog::with_providers(providers, http, paths)
}

fn target() -> Target {
    Target::new("1.8.9", Loader::LegacyFabric)
}

fn grant() -> PerActionGrant {
    PerActionGrant::user_requested("live test".to_owned())
}

fn by_slug(catalog: &ModCatalog, slug: &str) -> ModProject {
    // The catalogue insists an answer names the id it asked for, and Modrinth
    // answers a slug with the project's id, so resolve the slug first.
    let http = HttpClient::new(policy(), NetworkJournal::new(), "test/0");
    let raw = launcher_core::mods::Modrinth::new(http)
        .project(slug, &grant())
        .unwrap_or_else(|error| panic!("{slug}: {error}"));
    catalog
        .project(
            &ProjectRef::new(Provider::Modrinth, raw.id),
            &grant(),
            &Cancel::new(),
        )
        .unwrap()
}

#[test]
#[ignore = "makes real network requests to Modrinth"]
fn a_search_returns_real_1_8_9_mods_and_pages_on() {
    let tmp = tempfile::tempdir().unwrap();
    let catalog = catalog(tmp.path());
    let mut query = SearchQuery::new(target()).with_page(0, 20);
    query.sort = Sort::Downloads;
    query.breadth = Breadth::Plausible;
    let first = catalog
        .search("live", &query, &[], &grant(), &Cancel::new())
        .unwrap();
    println!(
        "first page: {} results, more: {}",
        first.entries.len(),
        first.has_more
    );
    assert!(first.entries.len() >= 10, "Modrinth has many 1.8.9 mods");
    assert!(first.has_more);
    for entry in &first.entries {
        assert!(
            entry.project.minecraft_versions.contains("1.8.9"),
            "{} does not list 1.8.9 at all",
            entry.project.name
        );
    }

    let second = catalog
        .search(
            "live",
            &query.clone().with_page(20, 20),
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    let firsts: Vec<_> = first.entries.iter().map(|e| &e.project.reference).collect();
    assert!(
        second
            .entries
            .iter()
            .all(|entry| !firsts.contains(&&entry.project.reference)),
        "the second page repeats the first"
    );

    // The narrowest breadth is a subset of the default.
    query.breadth = Breadth::Tagged;
    let tagged = catalog
        .search("live", &query, &[], &grant(), &Cancel::new())
        .unwrap();
    for entry in &tagged.entries {
        assert!(entry.project.loaders.contains("legacy-fabric"));
    }
}

#[test]
#[ignore = "makes real network requests to Modrinth"]
fn real_projects_get_the_verdicts_their_builds_deserve() {
    let tmp = tempfile::tempdir().unwrap();
    let catalog = catalog(tmp.path());
    let cases = [
        // Tagged for every loader including legacy-fabric, for 1.8.9.
        ("ksyxis", Verdict::Compatible),
        // Has legacy-fabric builds for 1.8.9 (3.0.x) beside newer Ornithe ones.
        ("axolotlclient", Verdict::Compatible),
        // 1.8.9 builds tagged only plain fabric, never legacy-fabric.
        ("yosbr", Verdict::Unverified),
        // 1.8.9 builds are Forge and Ornithe only.
        ("chatting", Verdict::Incompatible),
        // Forge only for 1.8.9.
        ("entityculling", Verdict::Incompatible),
    ];
    let mut wrong = Vec::new();
    for (slug, expected) in cases {
        let project = by_slug(&catalog, slug);
        let verdict = catalog
            .verdict(&project.reference, &target(), &grant(), &Cancel::new())
            .unwrap();
        println!(
            "{slug:>14}: {:<12} {} builds  best: {:?}  — {}",
            verdict.verdict.label(),
            verdict.builds,
            verdict.best.as_ref().map(|b| &b.number),
            verdict.headline
        );
        if verdict.verdict != expected {
            wrong.push(format!(
                "{slug}: expected {expected:?}, got {:?}",
                verdict.verdict
            ));
        }
        // Whatever the verdict, an install may only take an installable build.
        let installable = catalog
            .files(&project.reference, &target(), &grant(), &Cancel::new())
            .unwrap();
        assert_eq!(
            installable.is_empty(),
            expected == Verdict::Incompatible,
            "{slug}: installable builds disagree with the verdict"
        );
    }
    assert!(wrong.is_empty(), "{wrong:#?}");
}

#[test]
#[ignore = "makes real network requests to Modrinth"]
fn planning_a_real_mod_resolves_its_real_dependencies() {
    let tmp = tempfile::tempdir().unwrap();
    let catalog = catalog(tmp.path());
    // noexpensive's Legacy Fabric build requires Legacy Fabric API and others.
    let project = by_slug(&catalog, "noexpensive");
    let plan = catalog
        .plan_install(
            &project.reference,
            None,
            &target(),
            &[],
            &[],
            &grant(),
            &Cancel::new(),
        )
        .unwrap();
    println!(
        "noexpensive {}: {} dependencies, {} blockers, {} unverified",
        plan.root.file.number,
        plan.dependencies.len(),
        plan.blockers.len(),
        plan.unverified.len()
    );
    for planned in &plan.dependencies {
        println!("  needs {} {}", planned.project.name, planned.file.number);
    }
    for blocker in &plan.blockers {
        println!("  blocked: {blocker:?}");
    }
    for unverified in &plan.unverified {
        println!("  unverified: {} {}", unverified.project, unverified.number);
    }
    assert!(
        plan.root.file.game_versions.contains("1.8.9"),
        "the chosen build is not for 1.8.9"
    );
    // The plan must name Legacy Fabric API somewhere: as a dependency to
    // fetch, or as a blocker explaining why it cannot be.
    let lfapi = ProjectRef::new(Provider::Modrinth, "9CJED7xi");
    assert!(
        plan.dependencies
            .iter()
            .any(|planned| planned.project.reference == lfapi)
            || plan
                .blockers
                .iter()
                .any(|b| format!("{b:?}").contains("9CJED7xi")),
        "Legacy Fabric API was not planned"
    );
}

#[test]
#[ignore = "makes real network requests to Modrinth"]
fn categories_come_from_modrinth() {
    let tmp = tempfile::tempdir().unwrap();
    let catalog = catalog(tmp.path());
    let categories = catalog
        .categories(Provider::Modrinth, &grant(), &Cancel::new())
        .unwrap();
    println!(
        "{} categories: {:?}",
        categories.len(),
        categories.iter().map(|c| &c.id).collect::<Vec<_>>()
    );
    assert!(categories.iter().any(|c| c.id == "optimization"));
    assert!(categories.iter().any(|c| c.id == "utility"));
    // Loaders are not topics.
    assert!(!categories
        .iter()
        .any(|c| c.id == "fabric" || c.id == "forge"));
}

/// The same real projects, from a Forge 1.8.9 profile: the verdicts follow the
/// profile's loader, not Fathom's own.
#[test]
#[ignore = "makes real network requests to Modrinth"]
fn a_forge_profile_sees_forge_builds_as_compatible() {
    let tmp = tempfile::tempdir().unwrap();
    let catalog = catalog(tmp.path());
    let forge = Target::new("1.8.9", Loader::Forge);
    let cases = [
        // 1.8.9 builds for Forge (and Ornithe).
        ("chatting", Verdict::Compatible),
        // Forge only for 1.8.9.
        ("entityculling", Verdict::Compatible),
        // 1.8.9 builds tagged plain fabric only: never a Forge mod.
        ("yosbr", Verdict::Incompatible),
    ];
    let mut wrong = Vec::new();
    for (slug, expected) in cases {
        let project = by_slug(&catalog, slug);
        let verdict = catalog
            .verdict(&project.reference, &forge, &grant(), &Cancel::new())
            .unwrap();
        println!("{slug:>14}: {:<12} — {}", verdict.verdict.label(), verdict.headline);
        if verdict.verdict != expected {
            wrong.push(format!("{slug}: expected {expected:?}, got {:?}", verdict.verdict));
        }
    }
    assert!(wrong.is_empty(), "{wrong:#?}");

    // A Forge search asks for Forge, and plain Fabric is not "plausible" here.
    for breadth in [Breadth::Tagged, Breadth::Plausible] {
        let mut query = SearchQuery::new(forge.clone()).with_page(0, 20);
        query.sort = Sort::Downloads;
        query.breadth = breadth;
        let page = catalog.search("live", &query, &[], &grant(), &Cancel::new()).unwrap();
        assert!(page.entries.len() >= 10, "{breadth:?}: Modrinth has many 1.8.9 Forge mods");
        for entry in &page.entries {
            assert!(
                entry.project.loaders.contains("forge"),
                "{breadth:?}: {} is not tagged forge",
                entry.project.name
            );
        }
    }
}
