//! Integration test against Mojang's real metadata endpoints.
//!
//! `#[ignore]` by default: the rest of the suite must stay offline and
//! deterministic, and a unit test that depends on someone else's uptime is a
//! flaky test. Run it deliberately:
//!
//! ```text
//! cargo test -p launcher-core --test live_mojang -- --ignored --nocapture
//! ```
//!
//! What it covers that the offline tests cannot: that the HTTP client actually
//! works, that TLS negotiates, that the allowlist accepts the hosts Mojang
//! really uses today, and that the SHA-1 Mojang publishes for the 1.8.9 version
//! document still matches the bytes they serve.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use launcher_core::install::download::sha1_hex;
use launcher_core::minecraft_runtime::version::{VersionDetail, VersionManifest};
use launcher_core::minecraft_runtime::Os;
use launcher_core::network::policy::descriptor_from_url;
use launcher_core::network::{
    Destination, Feature, HttpClient, NetworkJournal, NetworkPolicy, Purpose, RequestDescriptor,
};

fn client() -> HttpClient {
    HttpClient::new(
        NetworkPolicy::default(),
        NetworkJournal::new(),
        &launcher_core::user_agent(),
    )
}

/// Every artifact URL in the real document must resolve to an allowlisted host.
/// This is the check that would catch Mojang moving to a CDN we have not
/// accounted for. Returns how many URLs were checked.
fn assert_every_artifact_url_is_allowlisted(detail: &VersionDetail) -> usize {
    let mut checked = 0;
    let mut check = |url: &str, what: &str| {
        descriptor_from_url(url, Purpose::DownloadGameArtifact, Feature::ProfileInstall)
            .unwrap_or_else(|e| panic!("{what} URL {url} is not allowlisted: {e}"));
        checked += 1;
    };

    for library in &detail.libraries {
        if let Some(artifact) = &library.downloads.artifact {
            check(&artifact.url, "library");
        }
        for download in library.downloads.classifiers.values() {
            check(&download.url, "classifier");
        }
    }
    if let Some(client_jar) = &detail.downloads.client {
        check(&client_jar.url, "client jar");
    }
    if let Some(index) = &detail.asset_index {
        check(&index.url, "asset index");
    }
    checked
}

#[test]
#[ignore = "makes real network requests to piston-meta.mojang.com"]
fn resolves_and_verifies_the_real_1_8_9_version_document() {
    let http = client();

    let manifest_request = RequestDescriptor::new(
        Destination::PistonMeta,
        Purpose::ResolveGameMetadata,
        Feature::ProfileInstall,
        "/mc/game/version_manifest_v2.json",
    );
    let response = http.get(&manifest_request).expect("manifest request");
    assert_eq!(response.status, 200, "manifest should return 200");

    let manifest: VersionManifest = response
        .json(Destination::PistonMeta.host())
        .expect("manifest should parse");
    let entry = manifest
        .find("1.8.9")
        .expect("1.8.9 should still be in the manifest");

    // The URL comes from a document we just downloaded, so it must survive the
    // allowlist rather than being fetched directly.
    let detail_request = descriptor_from_url(
        &entry.url,
        Purpose::ResolveGameMetadata,
        Feature::ProfileInstall,
    )
    .expect("Mojang's own version URL must be on the allowlist");

    let detail_response = http.get(&detail_request).expect("version document request");
    assert_eq!(detail_response.status, 200);

    // The integrity check the installer relies on.
    let actual = sha1_hex(&detail_response.body);
    assert_eq!(
        actual.to_lowercase(),
        entry.sha1.to_lowercase(),
        "the version document did not match the SHA-1 in the manifest"
    );

    let detail: VersionDetail = detail_response
        .json(Destination::PistonMeta.host())
        .expect("version document should parse");

    // Facts the research recorded. If any of these change, the launcher's
    // assumptions about 1.8.9 need revisiting.
    assert_eq!(detail.id, "1.8.9");
    assert_eq!(detail.main_class, "net.minecraft.client.main.Main");
    assert_eq!(detail.assets.as_deref(), Some("1.8"));
    assert_eq!(detail.required_java_major(), 8);
    assert!(
        detail.minecraft_arguments.is_some(),
        "1.8.9 must still use the legacy minecraftArguments string"
    );
    assert_eq!(
        detail.libraries.len(),
        37,
        "library count changed from what the research recorded"
    );

    let checked = assert_every_artifact_url_is_allowlisted(&detail);
    assert!(
        checked > 30,
        "expected to check many URLs, checked {checked}"
    );

    // And the platform filtering produces a sane result on this machine.
    assert!(!detail.classpath_libraries(Os::current()).is_empty());

    println!(
        "verified 1.8.9: {} libraries, {} URLs allowlisted, sha1 {}",
        detail.libraries.len(),
        checked,
        &actual[..12]
    );

    // Only the two hosts we intended were contacted.
    let journal = http.journal().recent(50);
    for entry in &journal {
        assert_eq!(
            entry.destination,
            Destination::PistonMeta,
            "unexpected host contacted: {}",
            entry.destination
        );
    }
    assert_eq!(
        journal.len(),
        2,
        "exactly two requests should have been made"
    );
}
