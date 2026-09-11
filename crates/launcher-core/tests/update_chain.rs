//! The update trust chain, end to end, against every attack in the compromise
//! model.
//!
//! These tests build real signed documents with real Ed25519 keys and run them
//! through the real verification code. Nothing is mocked except the transport,
//! because the transport is the one part that is explicitly **not** trusted:
//! the whole design assumes an attacker who can answer any request, so a test
//! that simulates a hostile server is testing the thing that matters.
//!
//! The keys here are fixed seeds in a test file. They sign nothing anyone will
//! trust, and the real signing key is outside this repository by construction —
//! `crates/fathom-release` is the only crate that knows what a private key is,
//! and nothing depends on it.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use ed25519_dalek::{Signer, SigningKey};
use launcher_core::update::manifest::{
    ComponentId, Platform, SignatureEntry, SignedEnvelope, MANIFEST_FORMAT,
};
use launcher_core::update::signing::{self, PublicKey, Role};
use launcher_core::update::verify::{self, VerificationContext, VerifyError};

// ===========================================================================
// Building signed documents
// ===========================================================================

fn key(seed: u8) -> SigningKey {
    SigningKey::from_bytes(&[seed; 32])
}

fn public(k: &SigningKey) -> PublicKey {
    PublicKey::from_bytes(k.verifying_key().as_bytes()).unwrap()
}

fn envelope(role: Role, payload: &serde_json::Value, signers: &[&SigningKey]) -> Vec<u8> {
    let bytes = serde_json::to_vec(payload).unwrap();
    let signatures = signers
        .iter()
        .map(|k| {
            let sig = k.sign(&signing::preimage(role, &bytes));
            SignatureEntry {
                keyid: public(k).id().to_string(),
                sig: signing::encode_base64(&sig.to_bytes()),
            }
        })
        .collect();
    serde_json::to_vec(&SignedEnvelope {
        payload: signing::encode_base64(&bytes),
        signatures,
    })
    .unwrap()
}

fn root_payload(version: u64, expires: i64, release_keys: &[&SigningKey]) -> serde_json::Value {
    serde_json::json!({
        "format": MANIFEST_FORMAT,
        "role": "root",
        "version": version,
        "expires": expires,
        "release_keys": release_keys.iter().map(|k| public(k).to_base64()).collect::<Vec<_>>(),
        "release_threshold": 1,
        "root_keys": [public(&key(1)).to_base64(), public(&key(2)).to_base64()],
        "root_threshold": 2,
    })
}

fn release_payload(
    version: u64,
    expires: i64,
    components: &serde_json::Value,
) -> serde_json::Value {
    serde_json::json!({
        "format": MANIFEST_FORMAT,
        "role": "release",
        "version": version,
        "expires": expires,
        "components": components.clone(),
    })
}

fn launcher_component(version: &str, artifact: &[u8]) -> serde_json::Value {
    serde_json::json!({
        "launcher": [{
            "version": version,
            "os": "windows",
            "arch": "x86_64",
            "sha256": verify::sha256_hex(artifact),
            "size": artifact.len(),
            "path": "/v1/artifacts/fathom.exe",
        }]
    })
}

fn windows() -> Platform {
    Platform {
        os: "windows".to_owned(),
        arch: "x86_64".to_owned(),
    }
}

fn context(now: i64, trusted: u64, platform: &Platform) -> VerificationContext<'_> {
    VerificationContext {
        now,
        trusted_version: trusted,
        platform,
    }
}

const FUTURE: i64 = 4_000_000_000;

// ===========================================================================
// The happy path, so the failures below mean something
// ===========================================================================

#[test]
fn a_correctly_signed_chain_verifies_end_to_end() {
    let (root_a, root_b) = (key(1), key(2));
    let release_key = key(10);
    let artifact = b"the launcher binary";

    // Root, signed by two root keys, naming the release key.
    let root_bytes = envelope(
        Role::Root,
        &root_payload(1, FUTURE, &[&release_key]),
        &[&root_a, &root_b],
    );
    let root = verify::verify_root(
        &root_bytes,
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 0, &windows()),
    )
    .expect("a correctly signed root verifies");

    // Release, signed by the key the root just authorised.
    let release_bytes = envelope(
        Role::Release,
        &release_payload(1, FUTURE, &launcher_component("0.3.0", artifact)),
        &[&release_key],
    );
    let keys: Vec<PublicKey> = root
        .release_keys
        .iter()
        .map(|k| PublicKey::from_base64(k).unwrap())
        .collect();
    let release = verify::verify_release(
        &release_bytes,
        &keys,
        root.release_threshold,
        context(0, 0, &windows()),
    )
    .expect("a correctly signed release verifies");

    // The artifact, bound by the hash in the signed document.
    let update = verify::select_update(ComponentId::Launcher, &release, "0.2.0", &windows())
        .unwrap()
        .expect("an update is available");
    assert_eq!(update.release.version, "0.3.0");
    verify::verify_artifact(ComponentId::Launcher, &update.release, artifact)
        .expect("the real artifact matches");
}

// ===========================================================================
// Bad signature
// ===========================================================================

#[test]
fn a_document_signed_by_the_wrong_key_is_refused() {
    let attacker = key(99);
    let bytes = envelope(
        Role::Root,
        &root_payload(1, FUTURE, &[&key(10)]),
        &[&attacker],
    );

    let err = verify::verify_root(
        &bytes,
        &[public(&key(1)), public(&key(2))],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a key we do not trust must not satisfy the threshold");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");
}

#[test]
fn a_corrupted_signature_is_refused() {
    let (root_a, root_b) = (key(1), key(2));
    let bytes = envelope(
        Role::Root,
        &root_payload(1, FUTURE, &[&key(10)]),
        &[&root_a, &root_b],
    );

    // Flip a bit inside one signature.
    let mut doc: SignedEnvelope = serde_json::from_slice(&bytes).unwrap();
    let mut sig = signing::decode_base64(&doc.signatures[0].sig, "sig").unwrap();
    sig[0] ^= 0x01;
    doc.signatures[0].sig = signing::encode_base64(&sig);
    let tampered = serde_json::to_vec(&doc).unwrap();

    let err = verify::verify_root(
        &tampered,
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a corrupted signature must not count");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");
}

/// The payload is what an attacker actually wants to change. Editing one byte
/// of it must break every signature over it.
#[test]
fn a_payload_edited_after_signing_is_refused() {
    let (root_a, root_b) = (key(1), key(2));
    let bytes = envelope(
        Role::Root,
        &root_payload(1, FUTURE, &[&key(10)]),
        &[&root_a, &root_b],
    );

    let mut doc: SignedEnvelope = serde_json::from_slice(&bytes).unwrap();
    let mut payload = signing::decode_base64(&doc.payload, "payload").unwrap();
    // Swap the authorised release key for the attacker's, keeping the length.
    let attacker_key = public(&key(99)).to_base64();
    let original = String::from_utf8(payload.clone()).unwrap();
    let edited = original.replace(&public(&key(10)).to_base64(), &attacker_key);
    assert_ne!(edited, original, "the test must actually change something");
    payload = edited.into_bytes();
    doc.payload = signing::encode_base64(&payload);

    let err = verify::verify_root(
        &serde_json::to_vec(&doc).unwrap(),
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("an edited payload must not verify");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");
}

/// A threshold counts distinct keys. Otherwise one compromised key satisfies a
/// threshold of two by signing twice, and the threshold is decoration.
#[test]
fn one_key_cannot_satisfy_a_threshold_of_two_by_signing_twice() {
    let root_a = key(1);
    let payload = root_payload(1, FUTURE, &[&key(10)]);
    let bytes = envelope(Role::Root, &payload, &[&root_a, &root_a]);

    let err = verify::verify_root(
        &bytes,
        &[public(&root_a), public(&key(2))],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("two signatures from one key are one key");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");
}

/// A release signature presented as a root signature, and the reverse. The
/// domain separation in the preimage is what stops a document being moved
/// between positions in the chain.
#[test]
fn a_signature_cannot_be_replayed_into_a_different_role() {
    let signer = key(1);
    // A payload that is structurally a root document, signed as `release`.
    let bytes = envelope(
        Role::Release,
        &root_payload(1, FUTURE, &[&key(10)]),
        &[&signer, &key(2)],
    );

    let err = verify::verify_root(
        &bytes,
        &[public(&signer), public(&key(2))],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a release signature is not a root signature");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");
}

/// Even with a valid signature for the right role, a document whose own `role`
/// field is wrong is refused. Belt and braces, and a clearer message.
#[test]
fn a_mislabelled_document_is_refused() {
    let (a, b) = (key(1), key(2));
    let mut payload = root_payload(1, FUTURE, &[&key(10)]);
    payload["role"] = serde_json::json!("release");
    let bytes = envelope(Role::Root, &payload, &[&a, &b]);

    let err = verify::verify_root(
        &bytes,
        &[public(&a), public(&b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a mislabelled document must be refused");
    assert!(matches!(err, VerifyError::WrongRole { .. }), "{err:?}");
}

// ===========================================================================
// Replayed metadata (rollback)
// ===========================================================================

/// The attack an attacker with a compromised CDN reaches for first: serve an
/// old, perfectly-signed document. Every signature is genuine; only the memory
/// of having seen something newer stops it.
#[test]
fn a_replayed_older_root_is_refused() {
    let (a, b) = (key(1), key(2));
    let old = envelope(Role::Root, &root_payload(3, FUTURE, &[&key(10)]), &[&a, &b]);

    // A client that has already accepted version 7.
    let err = verify::verify_root(
        &old,
        &[public(&a), public(&b)],
        2,
        context(0, 7, &windows()),
    )
    .expect_err("an old root must be refused");
    match err {
        VerifyError::Rollback {
            offered, trusted, ..
        } => {
            assert_eq!(offered, 3);
            assert_eq!(trusted, 7);
        }
        other => panic!("expected a rollback refusal, got {other:?}"),
    }
}

#[test]
fn a_replayed_older_release_is_refused() {
    let signer = key(10);
    let old = envelope(
        Role::Release,
        &release_payload(2, FUTURE, &launcher_component("0.9.0", b"x")),
        &[&signer],
    );
    let err = verify::verify_release(&old, &[public(&signer)], 1, context(0, 5, &windows()))
        .expect_err("an old release document must be refused");
    assert!(matches!(err, VerifyError::Rollback { .. }), "{err:?}");
}

/// Re-fetching the current document is normal and must not fail. Only a
/// strictly lower version is a rollback.
#[test]
fn the_same_version_is_accepted_so_a_second_check_in_a_day_works() {
    let signer = key(10);
    let bytes = envelope(
        Role::Release,
        &release_payload(5, FUTURE, &launcher_component("0.9.0", b"x")),
        &[&signer],
    );
    verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 5, &windows()))
        .expect("re-fetching the version we already have is not an attack");
}

/// The other half of replay: current metadata, but naming an old artifact.
/// The version floor for artifacts is the installed version.
#[test]
fn current_metadata_offering_an_old_build_is_not_an_upgrade() {
    let signer = key(10);
    let bytes = envelope(
        Role::Release,
        &release_payload(9, FUTURE, &launcher_component("0.1.0", b"old build")),
        &[&signer],
    );
    let release =
        verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 0, &windows())).unwrap();

    let chosen =
        verify::select_update(ComponentId::Launcher, &release, "0.5.0", &windows()).unwrap();
    assert!(
        chosen.is_none(),
        "a downgrade offered through current metadata must not be selected"
    );
}

/// Freeze attack: an attacker who can block traffic serves the last document
/// forever, keeping a client on a version known to be vulnerable. Expiry is
/// what bounds how long that works.
#[test]
fn expired_metadata_is_refused_however_well_signed() {
    let signer = key(10);
    let bytes = envelope(
        Role::Release,
        &release_payload(1, 1_000, &launcher_component("0.9.0", b"x")),
        &[&signer],
    );

    verify::verify_release(&bytes, &[public(&signer)], 1, context(999, 0, &windows()))
        .expect("still valid a second before it expires");

    let err = verify::verify_release(&bytes, &[public(&signer)], 1, context(1_001, 0, &windows()))
        .expect_err("expired metadata must be refused");
    assert!(matches!(err, VerifyError::Expired { .. }), "{err:?}");
}

// ===========================================================================
// Modified artifact, partial download
// ===========================================================================

#[test]
fn an_artifact_that_does_not_match_the_signed_hash_is_refused() {
    let signer = key(10);
    let real = b"the genuine launcher";
    let bytes = envelope(
        Role::Release,
        &release_payload(1, FUTURE, &launcher_component("0.3.0", real)),
        &[&signer],
    );
    let release =
        verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 0, &windows())).unwrap();
    let update = verify::select_update(ComponentId::Launcher, &release, "0.2.0", &windows())
        .unwrap()
        .unwrap();

    // A compromised CDN serving something else of exactly the same length.
    let swapped = b"the maliciou5 binary";
    assert_eq!(
        swapped.len(),
        real.len(),
        "same length, so only the hash catches it"
    );
    let err = verify::verify_artifact(ComponentId::Launcher, &update.release, swapped)
        .expect_err("substituted bytes must be refused");
    assert!(err.to_string().contains("do not match"), "{err}");

    // And the genuine one still passes, so the test is not vacuous.
    verify::verify_artifact(ComponentId::Launcher, &update.release, real).unwrap();
}

#[test]
fn a_truncated_download_is_refused_and_named_as_such() {
    let signer = key(10);
    let real = b"the genuine launcher binary, at full length";
    let bytes = envelope(
        Role::Release,
        &release_payload(1, FUTURE, &launcher_component("0.3.0", real)),
        &[&signer],
    );
    let release =
        verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 0, &windows())).unwrap();
    let update = verify::select_update(ComponentId::Launcher, &release, "0.2.0", &windows())
        .unwrap()
        .unwrap();

    for cut in [0usize, 1, 10, real.len() - 1] {
        let err = verify::verify_artifact(ComponentId::Launcher, &update.release, &real[..cut])
            .expect_err("a partial download must be refused");
        assert!(
            err.to_string().contains("bytes"),
            "a truncation should be reported as a size problem: {err}"
        );
    }
}

// ===========================================================================
// Wrong architecture
// ===========================================================================

#[test]
fn a_build_for_another_architecture_is_never_offered() {
    let signer = key(10);
    let components = serde_json::json!({
        "launcher": [
            { "version": "9.0.0", "os": "windows", "arch": "aarch64",
              "sha256": "a".repeat(64), "size": 100, "path": "/v1/a.exe" },
            { "version": "9.0.0", "os": "linux", "arch": "x86_64",
              "sha256": "b".repeat(64), "size": 100, "path": "/v1/b" }
        ]
    });
    let bytes = envelope(
        Role::Release,
        &release_payload(1, FUTURE, &components),
        &[&signer],
    );
    let release =
        verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 0, &windows())).unwrap();

    let err = verify::select_update(ComponentId::Launcher, &release, "0.1.0", &windows())
        .expect_err("nothing here runs on this machine");
    assert!(
        matches!(err, VerifyError::NoBuildForPlatform { .. }),
        "{err:?}"
    );
}

#[test]
fn the_right_architecture_is_chosen_from_a_mixed_list() {
    let signer = key(10);
    let artifact = b"windows x86_64 build";
    let components = serde_json::json!({
        "launcher": [
            { "version": "9.9.9", "os": "linux", "arch": "x86_64",
              "sha256": "b".repeat(64), "size": 100, "path": "/v1/b" },
            { "version": "0.3.0", "os": "windows", "arch": "x86_64",
              "sha256": verify::sha256_hex(artifact), "size": artifact.len(),
              "path": "/v1/w.exe" },
            { "version": "9.9.9", "os": "windows", "arch": "aarch64",
              "sha256": "c".repeat(64), "size": 100, "path": "/v1/c.exe" }
        ]
    });
    let bytes = envelope(
        Role::Release,
        &release_payload(1, FUTURE, &components),
        &[&signer],
    );
    let release =
        verify::verify_release(&bytes, &[public(&signer)], 1, context(0, 0, &windows())).unwrap();

    let update = verify::select_update(ComponentId::Launcher, &release, "0.1.0", &windows())
        .unwrap()
        .expect("the windows x86_64 build");
    assert_eq!(update.release.version, "0.3.0");
    assert_eq!(update.release.arch, "x86_64");
    verify::verify_artifact(ComponentId::Launcher, &update.release, artifact).unwrap();
}

// ===========================================================================
// Malformed manifests
// ===========================================================================

/// Every one of these must produce an error rather than a panic, a hang, or an
/// accepted document. The envelope parser is the only thing that touches
/// unverified bytes, so it is the one that has to be boring.
#[test]
fn malformed_documents_are_refused_without_panicking() {
    let keys = [public(&key(1)), public(&key(2))];

    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("empty", Vec::new()),
        ("not json", b"hello".to_vec()),
        ("truncated json", b"{\"payload\":".to_vec()),
        ("an array", b"[1,2,3]".to_vec()),
        ("no payload", br#"{"signatures":[]}"#.to_vec()),
        ("no signatures", br#"{"payload":"e30="}"#.to_vec()),
        (
            "payload is not base64",
            br#"{"payload":"not base64 !!","signatures":[]}"#.to_vec(),
        ),
        (
            "payload is base64 of nothing useful",
            br#"{"payload":"","signatures":[]}"#.to_vec(),
        ),
        (
            "signature entries are junk",
            br#"{"payload":"e30=","signatures":[{"keyid":"","sig":""}]}"#.to_vec(),
        ),
        (
            "keyid is not hex",
            br#"{"payload":"e30=","signatures":[{"keyid":"zzzz","sig":"AAAA"}]}"#.to_vec(),
        ),
        ("deeply nested", {
            let mut v = br#"{"payload":"e30=","signatures":"#.to_vec();
            v.extend(std::iter::repeat_n(b'[', 10_000));
            v.extend(std::iter::repeat_n(b']', 10_000));
            v.push(b'}');
            v
        }),
        ("oversized", vec![b'x'; 300 * 1024]),
    ];

    for (name, bytes) in cases {
        let result = verify::verify_root(&bytes, &keys, 2, context(0, 0, &windows()));
        assert!(result.is_err(), "{name} must be refused, but it verified");
    }
}

/// A document that verifies but whose payload is not the document we expected.
/// The signature is over bytes; it does not promise those bytes are a root
/// document, so the parse after verification still has to be defensive.
#[test]
fn a_verified_payload_that_is_not_the_expected_shape_is_refused() {
    let (a, b) = (key(1), key(2));
    for payload in [
        serde_json::json!({"role": "root"}),
        serde_json::json!({"role": "root", "format": MANIFEST_FORMAT, "version": 1}),
        serde_json::json!([1, 2, 3]),
        serde_json::json!("just a string"),
        serde_json::json!(null),
    ] {
        let bytes = envelope(Role::Root, &payload, &[&a, &b]);
        let err = verify::verify_root(
            &bytes,
            &[public(&a), public(&b)],
            2,
            context(0, 0, &windows()),
        )
        .expect_err("an incomplete payload must be refused");
        assert!(
            matches!(err, VerifyError::PayloadMalformed { .. }),
            "{payload}: {err:?}"
        );
    }
}

/// A root that authorises nobody, or sets a threshold it cannot meet, would
/// leave the client unable to verify anything. Refused at the root step so the
/// message names the real problem.
#[test]
fn a_root_that_cannot_authorise_anything_is_refused() {
    let (a, b) = (key(1), key(2));

    let mut empty = root_payload(1, FUTURE, &[]);
    empty["release_keys"] = serde_json::json!([]);
    let err = verify::verify_root(
        &envelope(Role::Root, &empty, &[&a, &b]),
        &[public(&a), public(&b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a root naming no release keys is useless");
    assert!(matches!(err, VerifyError::EmptyKeySet), "{err:?}");

    let mut impossible = root_payload(1, FUTURE, &[&key(10)]);
    impossible["release_threshold"] = serde_json::json!(5);
    let err = verify::verify_root(
        &envelope(Role::Root, &impossible, &[&a, &b]),
        &[public(&a), public(&b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a threshold above the key count can never be met");
    assert!(
        matches!(err, VerifyError::ImpossibleThreshold { .. }),
        "{err:?}"
    );

    let mut zero = root_payload(1, FUTURE, &[&key(10)]);
    zero["release_threshold"] = serde_json::json!(0);
    let err = verify::verify_root(
        &envelope(Role::Root, &zero, &[&a, &b]),
        &[public(&a), public(&b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("a threshold of zero would accept unsigned releases");
    assert!(matches!(err, VerifyError::EmptyKeySet), "{err:?}");
}

/// A newer format is refused rather than guessed at: an older client cannot
/// know what a field it has never heard of was supposed to prevent.
#[test]
fn a_future_format_is_refused_rather_than_interpreted() {
    let (a, b) = (key(1), key(2));
    let mut payload = root_payload(1, FUTURE, &[&key(10)]);
    payload["format"] = serde_json::json!(MANIFEST_FORMAT + 1);

    let err = verify::verify_root(
        &envelope(Role::Root, &payload, &[&a, &b]),
        &[public(&a), public(&b)],
        2,
        context(0, 0, &windows()),
    )
    .expect_err("an unknown format must not be interpreted");
    assert!(
        matches!(err, VerifyError::UnsupportedFormat { .. }),
        "{err:?}"
    );
}

// ===========================================================================
// Key revocation
// ===========================================================================

/// The reason the root role exists. A release key is compromised; the root
/// signs a new document that drops it; a client that picks up the new root
/// stops accepting anything the old key signs.
#[test]
fn a_new_root_revokes_a_compromised_release_key() {
    let (root_a, root_b) = (key(1), key(2));
    let compromised = key(10);
    let replacement = key(11);

    // v1 authorises the key that will later be stolen.
    let v1 = verify::verify_root(
        &envelope(
            Role::Root,
            &root_payload(1, FUTURE, &[&compromised]),
            &[&root_a, &root_b],
        ),
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 0, &windows()),
    )
    .unwrap();
    assert_eq!(v1.release_keys, vec![public(&compromised).to_base64()]);

    // v2 drops it.
    let v2 = verify::verify_root(
        &envelope(
            Role::Root,
            &root_payload(2, FUTURE, &[&replacement]),
            &[&root_a, &root_b],
        ),
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 1, &windows()),
    )
    .unwrap();
    assert_eq!(v2.release_keys, vec![public(&replacement).to_base64()]);

    // The attacker's release document is correctly signed by the stolen key,
    // and now verifies against nothing.
    let forged = envelope(
        Role::Release,
        &release_payload(99, FUTURE, &launcher_component("99.0.0", b"malware")),
        &[&compromised],
    );
    let current_keys: Vec<PublicKey> = v2
        .release_keys
        .iter()
        .map(|k| PublicKey::from_base64(k).unwrap())
        .collect();
    let err = verify::verify_release(
        &forged,
        &current_keys,
        v2.release_threshold,
        context(0, 0, &windows()),
    )
    .expect_err("a revoked key must no longer be accepted");
    assert!(matches!(err, VerifyError::Signature(_)), "{err:?}");

    // And replaying the *old root* to bring the stolen key back is the same
    // rollback attack, refused by the version floor.
    let replayed = envelope(
        Role::Root,
        &root_payload(1, FUTURE, &[&compromised]),
        &[&root_a, &root_b],
    );
    let err = verify::verify_root(
        &replayed,
        &[public(&root_a), public(&root_b)],
        2,
        context(0, 2, &windows()),
    )
    .expect_err("reinstating a revoked key by replaying an old root must be refused");
    assert!(matches!(err, VerifyError::Rollback { .. }), "{err:?}");
}

// ===========================================================================
// Interrupted update, against real files
// ===========================================================================

/// The install state machine, driven by the real verified metadata rather than
/// by hand-built inputs, so the two halves are known to fit together.
#[test]
fn a_verified_update_installs_and_keeps_the_previous_copy() {
    use launcher_core::update::install::{HashOnlyValidator, Installer, Replacement};
    use launcher_core::update::trust::UpdatePaths;

    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path();
    let paths = UpdatePaths::new(root);
    paths.ensure().unwrap();
    let installer = Installer::new(paths, root);

    let artifact = b"the new module jar";
    let signer = key(10);
    let components = serde_json::json!({
        "optimization-module": [{
            "version": "0.3.0", "os": "any", "arch": "any",
            "sha256": verify::sha256_hex(artifact), "size": artifact.len(),
            "path": "/v1/fathom-optimizations.jar",
        }]
    });
    let release = verify::verify_release(
        &envelope(
            Role::Release,
            &release_payload(1, FUTURE, &components),
            &[&signer],
        ),
        &[public(&signer)],
        1,
        context(0, 0, &windows()),
    )
    .unwrap();

    let update = verify::select_update(
        ComponentId::OptimizationModule,
        &release,
        "0.2.0",
        &windows(),
    )
    .unwrap()
    .unwrap();

    let target = root.join("components/fathom-optimizations.jar");
    std::fs::create_dir_all(target.parent().unwrap()).unwrap();
    std::fs::write(&target, b"the old module jar").unwrap();

    let staged = installer.staged_path(update.component, &update.release.version);
    std::fs::write(&staged, artifact).unwrap();

    installer
        .apply(
            &Replacement {
                component: update.component,
                target: &target,
                staged: &staged,
                expected_sha256: &update.release.sha256,
                new_version: &update.release.version,
                previous_version: &update.from_version,
            },
            &HashOnlyValidator,
        )
        .expect("a verified update installs");

    assert_eq!(std::fs::read(&target).unwrap(), artifact);
    assert!(
        installer.has_backup(ComponentId::OptimizationModule),
        "the previous copy must still exist"
    );
}
