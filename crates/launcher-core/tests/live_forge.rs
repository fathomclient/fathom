//! Forge 1.8.9 against Forge's and Mojang's real repositories.
//!
//! Ignored by default: it contacts maven.minecraftforge.net,
//! files.minecraftforge.net and libraries.minecraft.net.
//!
//! ```text
//! cargo test -p launcher-core --test live_forge -- --ignored --nocapture
//! ```

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use launcher_core::install::ArtifactCache;
use launcher_core::loaders::{self, ModDirectorySemantics};
use launcher_core::network::{Feature, HttpClient, NetworkJournal, NetworkPolicy};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::Loader;

fn http() -> HttpClient {
    HttpClient::new(
        NetworkPolicy::default(),
        NetworkJournal::new(),
        &launcher_core::user_agent(),
    )
}

#[test]
#[ignore = "contacts Forge's repositories"]
fn forge_lists_1_8_9_builds_with_the_recommended_one_marked() {
    let versions = loaders::provider_for(Loader::Forge)
        .available_versions("1.8.9", &http(), Feature::ProfileInstall)
        .expect("Forge's version list");
    println!("{} builds; first {:?}", versions.len(), versions.first());
    assert!(versions.len() > 50);
    let recommended: Vec<_> = versions.iter().filter(|v| v.stable).collect();
    assert_eq!(recommended.len(), 1, "{recommended:?}");
    assert_eq!(recommended[0].version, "11.15.1.2318");
    assert_eq!(versions[0].version, "11.15.1.2318", "newest first");
}

#[test]
#[ignore = "contacts Forge's and Mojang's repositories"]
fn forge_resolves_every_library_with_a_hash_and_then_needs_nobody() {
    let tmp = tempfile::tempdir().unwrap();
    let cache = ArtifactCache::new(DataPaths::rooted_at(tmp.path()));
    let provider = loaders::provider_for(Loader::Forge);

    let first = provider
        .resolve(
            "1.8.9",
            "11.15.1.2318",
            &cache,
            &http(),
            Feature::ProfileInstall,
        )
        .expect("resolved from the repositories");
    assert_eq!(
        first.main_class.as_deref(),
        Some("net.minecraft.launchwrapper.Launch")
    );
    assert_eq!(
        first.game_arguments,
        [
            "--tweakClass",
            "net.minecraftforge.fml.common.launcher.FMLTweaker"
        ]
    );
    assert_eq!(
        first.mod_directory,
        ModDirectorySemantics::FlatDirectoryWithCoremods
    );
    for library in &first.libraries {
        println!(
            "{} <- {}{}",
            library.name,
            library.request.destination.host(),
            library.request.path
        );
    }
    assert_eq!(
        first.libraries.len(),
        18,
        "all nineteen listed libraries but jline, which is server-only"
    );
    assert!(first
        .libraries
        .iter()
        .all(|library| library.expect.sha1.len() == 40));
    assert!(first
        .libraries
        .iter()
        .any(|library| library.name.starts_with("net.minecraftforge:forge:")));
    assert!(!first
        .libraries
        .iter()
        .any(|library| library.name.starts_with("jline:")));

    // The kept build answers the second time, with the network off.
    let offline = HttpClient::new(
        NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        },
        NetworkJournal::new(),
        "test/0",
    );
    let again = provider
        .resolve("1.8.9", "11.15.1.2318", &cache, &offline, Feature::Play)
        .expect("resolved from the kept build");
    assert_eq!(again.libraries.len(), first.libraries.len());
}
