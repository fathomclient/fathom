//! The verification pipeline: bytes in, a decision out.
//!
//! Every check happens here, in a fixed order, and **nothing downstream is
//! allowed to re-decide**. The order matters as much as the checks:
//!
//! 1. size ceiling — before any allocation
//! 2. envelope parse — the only parse that runs on unverified bytes
//! 3. signature threshold — against keys we already trust
//! 4. payload parse — the first time the document's own claims are read
//! 5. role and format — the document says what we asked for
//! 6. expiry — not stale
//! 7. version monotonicity — not a replay
//!
//! A signature check that happens after a parse is a parser exposed to
//! unauthenticated input. Here the only things that touch unverified bytes are
//! a length comparison, a base64 decoder, and `serde_json` on a two-field
//! envelope — and that envelope is the smallest attack surface we could
//! arrange.

use std::collections::BTreeSet;

use super::manifest::{
    ComponentId, ComponentRelease, Platform, ReleaseMetadata, RootMetadata, SignedEnvelope,
    Version, MANIFEST_FORMAT, MAX_METADATA_BYTES,
};
use super::signing::{self, KeyId, PublicKey, Role, SignatureError};

#[derive(Debug, thiserror::Error)]
pub enum VerifyError {
    #[error("{role} metadata is {actual} bytes, above the {limit} byte ceiling")]
    TooLarge {
        role: Role,
        actual: usize,
        limit: usize,
    },
    #[error("{role} metadata is not a signed envelope: {detail}")]
    NotAnEnvelope { role: Role, detail: String },
    #[error("{role} metadata payload is not valid base64: {detail}")]
    PayloadNotBase64 { role: Role, detail: String },
    #[error(transparent)]
    Signature(#[from] SignatureError),
    #[error("{role} metadata did not parse after its signature verified: {detail}")]
    PayloadMalformed { role: Role, detail: String },
    #[error(
        "metadata claims to be {found:?} but was fetched as {expected}. Refusing to \
         interpret a document in a position it was not signed for."
    )]
    WrongRole { expected: Role, found: String },
    #[error(
        "{role} metadata is format v{found}; this launcher understands v{supported}. \
         Update the launcher first."
    )]
    UnsupportedFormat {
        role: Role,
        found: u32,
        supported: u32,
    },
    #[error(
        "{role} metadata expired at {expired_at} and it is now {now}. Refusing stale metadata."
    )]
    Expired {
        role: Role,
        expired_at: i64,
        now: i64,
    },
    #[error(
        "{role} metadata is version {offered}, but version {trusted} has already been \
         accepted. This is a rollback attempt or a misconfigured mirror."
    )]
    Rollback {
        role: Role,
        offered: u64,
        trusted: u64,
    },
    #[error("root metadata names no release keys, or a threshold of zero")]
    EmptyKeySet,
    #[error("root metadata sets a threshold of {threshold} but names only {keys} release key(s)")]
    ImpossibleThreshold { threshold: usize, keys: usize },
    #[error("component {component} version {offered} is not newer than the installed {installed}")]
    NotAnUpgrade {
        component: ComponentId,
        offered: String,
        installed: String,
    },
    #[error("no build of {component} for {platform}")]
    NoBuildForPlatform {
        component: ComponentId,
        platform: String,
    },
    #[error(
        "{component} is published for os={os} arch={arch}, but an executable must name a \
         real platform. Refusing an artifact that claims to run anywhere."
    )]
    ExecutableClaimsAnyPlatform {
        component: ComponentId,
        os: String,
        arch: String,
    },
    #[error("{component}: a SHA-256 must be 64 hex characters, got {found:?}")]
    BadHash {
        component: ComponentId,
        found: String,
    },
    #[error("{component}: the download path {path:?} is not an absolute path on the release host")]
    BadPath {
        component: ComponentId,
        path: String,
    },
    #[error("{component}: a declared size of {size} bytes is not plausible")]
    BadSize { component: ComponentId, size: u64 },
}

/// The largest artifact we will accept a declaration for.
///
/// The launcher is a few megabytes and the optimization jar far less. 512 MB is
/// well beyond anything real, and having a ceiling at all means a signed
/// document cannot ask us to fill the disk.
pub const MAX_ARTIFACT_BYTES: u64 = 512 * 1024 * 1024;

/// What a caller must supply for the version checks to mean anything.
#[derive(Debug, Clone, Copy)]
pub struct VerificationContext<'a> {
    /// Unix seconds. Passed in rather than read, so expiry is testable without
    /// waiting and so one clock reading covers the whole verification.
    pub now: i64,
    /// The highest version of this role already accepted. 0 for never.
    pub trusted_version: u64,
    pub platform: &'a Platform,
}

/// Verify an envelope and return its payload bytes.
///
/// The generic step under both roles. Split out so the ordering above exists in
/// exactly one place.
fn verify_envelope(
    role: Role,
    bytes: &[u8],
    keys: &[PublicKey],
    threshold: usize,
) -> Result<Vec<u8>, VerifyError> {
    // 1. Size, before anything allocates.
    if bytes.len() > MAX_METADATA_BYTES {
        return Err(VerifyError::TooLarge {
            role,
            actual: bytes.len(),
            limit: MAX_METADATA_BYTES,
        });
    }
    if threshold == 0 || keys.is_empty() {
        return Err(VerifyError::EmptyKeySet);
    }

    // 2. The only parse that touches unverified bytes. Two string fields.
    let envelope: SignedEnvelope =
        serde_json::from_slice(bytes).map_err(|e| VerifyError::NotAnEnvelope {
            role,
            detail: e.to_string(),
        })?;

    let payload = signing::decode_base64(&envelope.payload, "payload").map_err(|e| {
        VerifyError::PayloadNotBase64 {
            role,
            detail: e.to_string(),
        }
    })?;

    // 3. Threshold.
    //
    // Counted over *distinct keys*, not over signatures. Without that, one
    // compromised key could satisfy a threshold of three by signing three
    // times, which would turn the threshold into decoration.
    let mut satisfied: BTreeSet<KeyId> = BTreeSet::new();
    for entry in &envelope.signatures {
        let Some(id) = KeyId::parse(&entry.keyid) else {
            // A malformed id names no key we hold. Skipping is right: a
            // document may legitimately carry signatures for keys this client
            // does not know, and one bad entry must not discard the good ones.
            continue;
        };
        let Some(key) = keys.iter().find(|k| k.id() == &id) else {
            continue;
        };
        if satisfied.contains(&id) {
            return Err(SignatureError::DuplicateSignature {
                role,
                key: id.to_string(),
            }
            .into());
        }
        let Ok(signature) = signing::signature_from_base64(&entry.sig) else {
            continue;
        };
        if key.verifies(role, &payload, &signature) {
            satisfied.insert(id);
        }
    }

    if satisfied.len() < threshold {
        return Err(SignatureError::BelowThreshold {
            role,
            present: satisfied.len(),
            threshold,
        }
        .into());
    }

    Ok(payload)
}

/// Verify root metadata against the keys we currently anchor on.
///
/// `keys` and `threshold` come from either the compiled-in list (a fresh
/// install, or a client that has never accepted a root) or from the root we
/// already trust (rotation).
pub fn verify_root(
    bytes: &[u8],
    keys: &[PublicKey],
    threshold: usize,
    context: VerificationContext<'_>,
) -> Result<RootMetadata, VerifyError> {
    let payload = verify_envelope(Role::Root, bytes, keys, threshold)?;

    // 4. First parse of the document's own claims, on verified bytes.
    let root: RootMetadata =
        serde_json::from_slice(&payload).map_err(|e| VerifyError::PayloadMalformed {
            role: Role::Root,
            detail: e.to_string(),
        })?;

    // 5. The document must be what we asked for. Redundant with the signature's
    // domain separation, and kept because a mislabelled document should fail
    // with a clear message rather than a signature error.
    check_role_and_format(Role::Root, &root.role, root.format)?;
    check_freshness(Role::Root, root.expires, root.version, context)?;

    // A root that names no release keys, or an unmeetable threshold, would
    // leave the client unable to verify anything. Refuse it here rather than
    // discovering it one step later with a confusing message.
    if root.release_keys.is_empty() || root.release_threshold == 0 {
        return Err(VerifyError::EmptyKeySet);
    }
    if root.release_threshold > root.release_keys.len() {
        return Err(VerifyError::ImpossibleThreshold {
            threshold: root.release_threshold,
            keys: root.release_keys.len(),
        });
    }
    if root.root_threshold > root.root_keys.len() {
        return Err(VerifyError::ImpossibleThreshold {
            threshold: root.root_threshold,
            keys: root.root_keys.len(),
        });
    }

    Ok(root)
}

/// Verify release metadata against the keys the trusted root named.
pub fn verify_release(
    bytes: &[u8],
    keys: &[PublicKey],
    threshold: usize,
    context: VerificationContext<'_>,
) -> Result<ReleaseMetadata, VerifyError> {
    let payload = verify_envelope(Role::Release, bytes, keys, threshold)?;

    let release: ReleaseMetadata =
        serde_json::from_slice(&payload).map_err(|e| VerifyError::PayloadMalformed {
            role: Role::Release,
            detail: e.to_string(),
        })?;

    check_role_and_format(Role::Release, &release.role, release.format)?;
    check_freshness(Role::Release, release.expires, release.version, context)?;

    Ok(release)
}

fn check_role_and_format(role: Role, found_role: &str, format: u32) -> Result<(), VerifyError> {
    if !super::manifest::role_label_matches(role, found_role) {
        return Err(VerifyError::WrongRole {
            expected: role,
            found: found_role.to_owned(),
        });
    }
    if format != MANIFEST_FORMAT {
        return Err(VerifyError::UnsupportedFormat {
            role,
            found: format,
            supported: MANIFEST_FORMAT,
        });
    }
    Ok(())
}

/// Expiry and rollback, together, because they are the same idea from two
/// directions: too old to trust, and older than what we already have.
fn check_freshness(
    role: Role,
    expires: i64,
    version: u64,
    context: VerificationContext<'_>,
) -> Result<(), VerifyError> {
    if context.now > expires {
        return Err(VerifyError::Expired {
            role,
            expired_at: expires,
            now: context.now,
        });
    }
    // Strictly below is a rollback. Equal is allowed: re-fetching the current
    // document is normal, and refusing it would mean a client that checks twice
    // in a day fails the second time.
    if version < context.trusted_version {
        return Err(VerifyError::Rollback {
            role,
            offered: version,
            trusted: context.trusted_version,
        });
    }
    Ok(())
}

/// A component release that has passed every check and may be downloaded.
///
/// Constructing one is the only way to get to the download step, so a caller
/// cannot skip a check by accident.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApprovedUpdate {
    pub component: ComponentId,
    pub release: ComponentRelease,
    pub from_version: String,
}

impl ApprovedUpdate {
    pub fn version(&self) -> Version {
        Version::parse(&self.release.version)
    }
}

/// Choose the update for one component, if there is one.
///
/// Returns `Ok(None)` when the component is simply up to date, and an error
/// only when something was offered and refused — so "nothing to do" and "we
/// rejected what was offered" are different outcomes rather than the same
/// silence.
pub fn select_update(
    component: ComponentId,
    release: &ReleaseMetadata,
    installed_version: &str,
    platform: &Platform,
) -> Result<Option<ApprovedUpdate>, VerifyError> {
    let Some(entries) = release.components.get(component.as_str()) else {
        return Ok(None);
    };

    let installed = Version::parse(installed_version);

    // Every entry for this platform, newest first. Taking the newest rather
    // than the first means the ordering of the document does not decide what
    // gets installed.
    let mut candidates: Vec<&ComponentRelease> = entries
        .iter()
        .filter(|e| platform.accepts(&e.os, &e.arch))
        .collect();
    candidates.sort_by(|a, b| Version::parse(&b.version).cmp(&Version::parse(&a.version)));

    let Some(best) = candidates.first().copied() else {
        // There were entries, but none for us. Worth an error rather than a
        // silent no-op: a launcher that says "up to date" when the publisher
        // simply forgot this platform is lying to the user.
        if entries.is_empty() {
            return Ok(None);
        }
        return Err(VerifyError::NoBuildForPlatform {
            component,
            platform: platform.to_string(),
        });
    };

    validate_entry(component, best)?;

    let offered = Version::parse(&best.version);
    if !offered.is_newer_than(&installed) {
        // Not an error. Being up to date is the normal case.
        //
        // Note this is also the rollback check for artifacts: an entry at or
        // below the installed version is never selected, so a replayed release
        // document naming an old build produces "up to date" rather than a
        // downgrade.
        return Ok(None);
    }

    Ok(Some(ApprovedUpdate {
        component,
        release: best.clone(),
        from_version: installed_version.to_owned(),
    }))
}

/// Everything that must be true of an entry before it is worth downloading.
fn validate_entry(component: ComponentId, entry: &ComponentRelease) -> Result<(), VerifyError> {
    // An executable published as `any/any` would be installed on every machine
    // regardless of what it actually is. A jar legitimately runs anywhere; a
    // launcher binary does not, and a publisher error here is indistinguishable
    // from an attack.
    if component.is_the_running_program() && (entry.os == "any" || entry.arch == "any") {
        return Err(VerifyError::ExecutableClaimsAnyPlatform {
            component,
            os: entry.os.clone(),
            arch: entry.arch.clone(),
        });
    }

    if entry.sha256.len() != 64 || !entry.sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(VerifyError::BadHash {
            component,
            found: entry.sha256.clone(),
        });
    }

    if entry.size == 0 || entry.size > MAX_ARTIFACT_BYTES {
        return Err(VerifyError::BadSize {
            component,
            size: entry.size,
        });
    }

    // The path is joined onto an allowlisted host, so it must be a path and not
    // something that could smuggle in an authority. `network::policy` checks
    // this too; doing it here as well means a bad path is reported as a
    // metadata problem rather than as a network refusal.
    if !entry.path.starts_with('/')
        || entry.path.starts_with("//")
        || entry.path.contains("..")
        || entry.path.contains('\\')
        || entry.path.contains(':')
        || entry.path.chars().any(char::is_control)
    {
        return Err(VerifyError::BadPath {
            component,
            path: entry.path.clone(),
        });
    }

    Ok(())
}

/// Confirm downloaded bytes are what the signed metadata said they would be.
///
/// Size first: a wrong size is cheaper to detect and gives a better message,
/// and it is what a truncated transfer looks like.
pub fn verify_artifact(
    component: ComponentId,
    expected: &ComponentRelease,
    bytes: &[u8],
) -> Result<(), ArtifactError> {
    if bytes.len() as u64 != expected.size {
        return Err(ArtifactError::SizeMismatch {
            component,
            expected: expected.size,
            actual: bytes.len() as u64,
        });
    }
    let actual = sha256_hex(bytes);
    if !actual.eq_ignore_ascii_case(&expected.sha256) {
        return Err(ArtifactError::HashMismatch {
            component,
            expected: expected.sha256.clone(),
            actual,
        });
    }
    Ok(())
}

#[derive(Debug, thiserror::Error)]
pub enum ArtifactError {
    #[error(
        "{component}: expected {expected} bytes, received {actual}. The download was \
         truncated or replaced; it has been discarded."
    )]
    SizeMismatch {
        component: ComponentId,
        expected: u64,
        actual: u64,
    },
    #[error(
        "{component}: the downloaded bytes do not match the hash in the signed metadata. \
         Expected {expected}, got {actual}. Discarded and not installed."
    )]
    HashMismatch {
        component: ComponentId,
        expected: String,
        actual: String,
    },
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    use std::fmt::Write as _;
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for byte in digest {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn platform() -> Platform {
        Platform {
            os: "windows".to_owned(),
            arch: "x86_64".to_owned(),
        }
    }

    fn entry(version: &str, os: &str, arch: &str) -> ComponentRelease {
        ComponentRelease {
            version: version.to_owned(),
            os: os.to_owned(),
            arch: arch.to_owned(),
            sha256: "a".repeat(64),
            size: 1024,
            path: "/launcher/fathom.exe".to_owned(),
            notes: None,
        }
    }

    fn release_with(entries: Vec<ComponentRelease>) -> ReleaseMetadata {
        let mut components = BTreeMap::new();
        components.insert(ComponentId::Launcher.as_str().to_owned(), entries);
        ReleaseMetadata {
            format: MANIFEST_FORMAT,
            role: "release".to_owned(),
            version: 1,
            expires: i64::MAX,
            components,
        }
    }

    #[test]
    fn an_older_offer_is_not_an_upgrade() {
        let release = release_with(vec![entry("0.1.0", "windows", "x86_64")]);
        let chosen = select_update(ComponentId::Launcher, &release, "0.2.0", &platform()).unwrap();
        assert!(chosen.is_none(), "a downgrade must never be selected");
    }

    #[test]
    fn the_same_version_is_not_an_upgrade() {
        let release = release_with(vec![entry("0.2.0", "windows", "x86_64")]);
        assert!(
            select_update(ComponentId::Launcher, &release, "0.2.0", &platform())
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn the_newest_matching_entry_wins_regardless_of_document_order() {
        let release = release_with(vec![
            entry("0.3.0", "windows", "x86_64"),
            entry("0.9.0", "windows", "x86_64"),
            entry("0.5.0", "windows", "x86_64"),
        ]);
        let chosen = select_update(ComponentId::Launcher, &release, "0.1.0", &platform())
            .unwrap()
            .expect("an upgrade");
        assert_eq!(chosen.release.version, "0.9.0");
        assert_eq!(chosen.from_version, "0.1.0");
    }

    #[test]
    fn an_entry_for_another_platform_is_not_offered() {
        let release = release_with(vec![
            entry("9.0.0", "linux", "x86_64"),
            entry("9.0.0", "windows", "aarch64"),
            entry("9.0.0", "macos", "any"),
        ]);
        let err = select_update(ComponentId::Launcher, &release, "0.1.0", &platform())
            .expect_err("nothing here runs on this machine");
        assert!(
            matches!(err, VerifyError::NoBuildForPlatform { .. }),
            "{err:?}"
        );
    }

    #[test]
    fn a_matching_platform_is_chosen_over_a_newer_mismatched_one() {
        let release = release_with(vec![
            entry("9.9.9", "linux", "x86_64"),
            entry("0.3.0", "windows", "x86_64"),
        ]);
        let chosen = select_update(ComponentId::Launcher, &release, "0.1.0", &platform())
            .unwrap()
            .expect("the windows build");
        assert_eq!(chosen.release.version, "0.3.0");
    }

    /// An executable that claims to run anywhere would be installed everywhere.
    /// A publisher mistake and an attack look identical here, so it is refused.
    #[test]
    fn an_executable_may_not_be_published_for_any_platform() {
        for (os, arch) in [("any", "any"), ("any", "x86_64"), ("windows", "any")] {
            let release = release_with(vec![entry("9.0.0", os, arch)]);
            let err = select_update(ComponentId::Launcher, &release, "0.1.0", &platform())
                .expect_err("an executable must name a platform");
            assert!(
                matches!(err, VerifyError::ExecutableClaimsAnyPlatform { .. }),
                "{os}/{arch}: {err:?}"
            );
        }
    }

    /// A jar, on the other hand, genuinely does run anywhere.
    #[test]
    fn a_portable_component_may_be_published_for_any_platform() {
        let mut components = BTreeMap::new();
        let mut jar = entry("0.3.0", "any", "any");
        jar.path = "/module/fathom-optimizations.jar".to_owned();
        components.insert(
            ComponentId::OptimizationModule.as_str().to_owned(),
            vec![jar],
        );
        let release = ReleaseMetadata {
            format: MANIFEST_FORMAT,
            role: "release".to_owned(),
            version: 1,
            expires: i64::MAX,
            components,
        };
        let chosen = select_update(
            ComponentId::OptimizationModule,
            &release,
            "0.2.0",
            &platform(),
        )
        .unwrap();
        assert!(chosen.is_some());
    }

    #[test]
    fn a_malformed_hash_size_or_path_is_refused() {
        let cases: Vec<(ComponentRelease, &str)> = vec![
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.sha256 = "short".to_owned();
                    e
                },
                "hash",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.sha256 = "z".repeat(64);
                    e
                },
                "hash",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.size = 0;
                    e
                },
                "size",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.size = MAX_ARTIFACT_BYTES + 1;
                    e
                },
                "size",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.path = "//evil.example/x".to_owned();
                    e
                },
                "path",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.path = "/a/../../etc/passwd".to_owned();
                    e
                },
                "path",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.path = "https://evil.example/x".to_owned();
                    e
                },
                "path",
            ),
            (
                {
                    let mut e = entry("9.0.0", "windows", "x86_64");
                    e.path = "relative/path".to_owned();
                    e
                },
                "path",
            ),
        ];

        for (bad, what) in cases {
            let release = release_with(vec![bad.clone()]);
            let err = select_update(ComponentId::Launcher, &release, "0.1.0", &platform())
                .expect_err("must be refused");
            assert!(
                matches!(
                    err,
                    VerifyError::BadHash { .. }
                        | VerifyError::BadSize { .. }
                        | VerifyError::BadPath { .. }
                ),
                "{what} case produced {err:?}"
            );
        }
    }

    #[test]
    fn an_unknown_component_is_simply_absent() {
        let release = release_with(vec![entry("9.0.0", "windows", "x86_64")]);
        assert!(
            select_update(ComponentId::Metadata, &release, "0.1.0", &platform())
                .unwrap()
                .is_none(),
            "a component the document does not mention is not an error"
        );
    }

    #[test]
    fn artifact_verification_catches_truncation_and_substitution() {
        let bytes = b"the real artifact";
        let mut expected = entry("1.0.0", "windows", "x86_64");
        expected.size = bytes.len() as u64;
        expected.sha256 = sha256_hex(bytes);

        assert!(verify_artifact(ComponentId::Launcher, &expected, bytes).is_ok());

        // Truncated: reported as a size mismatch, which is the honest
        // description of a partial download.
        let err = verify_artifact(ComponentId::Launcher, &expected, b"the real")
            .expect_err("truncation must be caught");
        assert!(matches!(err, ArtifactError::SizeMismatch { .. }), "{err:?}");

        // Substituted at the same length: only the hash can catch this.
        let swapped = b"the fake artifact";
        assert_eq!(swapped.len(), bytes.len());
        let err = verify_artifact(ComponentId::Launcher, &expected, swapped)
            .expect_err("substitution must be caught");
        assert!(matches!(err, ArtifactError::HashMismatch { .. }), "{err:?}");
    }

    #[test]
    fn sha256_matches_a_known_vector() {
        // NIST's canonical empty-string and "abc" digests. A hex function that
        // is subtly wrong would make every artifact check meaningless.
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
