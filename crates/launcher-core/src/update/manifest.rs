//! The signed documents, and what they are allowed to say.
//!
//! Two roles, in a chain:
//!
//! ```text
//!   compiled-in root keys          in the binary, nothing can change them
//!            |  sign
//!            v
//!         root.json                names the release keys, and a threshold
//!            |  authorises
//!            v
//!       release.json                names components: version, hash, platform
//!            |  binds by SHA-256
//!            v
//!         artifacts                 bytes on a CDN, trusted only via that hash
//! ```
//!
//! # Why two roles rather than one
//!
//! One role would mean the key that signs every release is also the trust
//! anchor, so it can never be replaced without shipping a new binary, and it
//! has to be reachable by whatever automation cuts a release. Those two
//! requirements pull in opposite directions: an anchor wants to be offline and
//! rare, a release key wants to be available and frequent.
//!
//! Splitting them lets the root keys live offline — on hardware, in a safe,
//! used a few times a year to authorise a release key — while the release key
//! does the daily work. A stolen release key is then a **recoverable**
//! incident: the root signs a new `root.json` that drops it, and clients pick
//! that up on their next check. With one role, a stolen key is unrecoverable
//! for everyone who has already installed.
//!
//! This is the part of The Update Framework worth having at this scale. What is
//! deliberately *not* copied: separate snapshot and timestamp roles, delegated
//! targets, and consistent snapshots. Those solve problems a repository with
//! many independent publishers has, and this one has a single publisher and
//! three components.
//!
//! # Everything a client must decide is inside the signature
//!
//! Version, expiry, platform, architecture, hash and size all live in the
//! signed payload. Nothing that gates an install is read from a URL, a header,
//! a filename, or the artifact itself.

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};

use super::signing::Role;

/// The format version of the documents themselves.
///
/// A client refuses anything it does not recognise rather than guessing. An
/// older client meeting a newer format says so and stops, which is the correct
/// outcome: it cannot know what a field it has never heard of was supposed to
/// prevent.
pub const MANIFEST_FORMAT: u32 = 1;

/// The largest metadata document we will read into memory.
///
/// A root document is a few hundred bytes and a release document a few
/// kilobytes. 256 KB is far above anything legitimate and far below anything
/// that matters, and it is enforced before parsing so a hostile server cannot
/// make us allocate.
pub const MAX_METADATA_BYTES: usize = 256 * 1024;

/// The outer, unsigned wrapper.
///
/// The payload is **opaque** at this level: a base64 blob that is verified
/// before anything tries to interpret it. See `signing` for why the signed
/// bytes are the received bytes rather than a re-serialization.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SignedEnvelope {
    /// Base64 of the UTF-8 JSON payload.
    pub payload: String,
    /// One entry per signing key. Order is not significant.
    pub signatures: Vec<SignatureEntry>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SignatureEntry {
    /// Which key made this signature. A label for lookup, never a decision.
    pub keyid: String,
    /// Base64 of the 64-byte Ed25519 signature.
    pub sig: String,
}

/// The trust anchor's payload: which keys may sign a release, and how many.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RootMetadata {
    pub format: u32,
    /// Must be `"root"`. Checked even though the role is already bound into the
    /// signature preimage, so that a mislabelled document is a loud failure
    /// rather than a confusing one.
    pub role: String,
    /// Monotonic. A client refuses any root document numbered at or below the
    /// one it already trusts, which is what stops an old root — naming a key
    /// that has since been revoked — from being replayed.
    pub version: u64,
    /// Unix seconds. Past this, the document is refused.
    pub expires: i64,
    /// Base64 Ed25519 public keys authorised to sign `release` documents.
    pub release_keys: Vec<String>,
    /// How many distinct release keys must sign a release document.
    pub release_threshold: usize,
    /// Base64 Ed25519 public keys authorised to sign the *next* root document.
    ///
    /// Present so root keys can be rotated without shipping a new launcher:
    /// a new root is accepted if it satisfies the threshold of the root we
    /// already trust. The compiled-in list remains the anchor for a fresh
    /// install, and for any client that has never seen a root document.
    pub root_keys: Vec<String>,
    pub root_threshold: usize,
}

/// The release payload: what exists, and what it hashes to.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ReleaseMetadata {
    pub format: u32,
    /// Must be `"release"`.
    pub role: String,
    /// Monotonic. Rollback protection for the metadata itself.
    pub version: u64,
    /// Unix seconds. Past this, refused — which is what stops an attacker who
    /// can block traffic from freezing a client on a release that is known to
    /// be vulnerable.
    pub expires: i64,
    /// Every component this release describes, keyed by component id.
    pub components: BTreeMap<String, Vec<ComponentRelease>>,
}

/// One downloadable thing, for one platform.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct ComponentRelease {
    /// Semantic-ish version string. Compared componentwise; see [`Version`].
    pub version: String,
    /// `windows`, `macos`, `linux`, or `any` for platform-independent things
    /// such as a jar or a metadata bundle.
    pub os: String,
    /// `x86_64`, `aarch64`, or `any`.
    pub arch: String,
    /// Lowercase hex SHA-256 of the artifact. **The only thing that makes the
    /// downloaded bytes trustworthy.**
    pub sha256: String,
    /// Exact size in bytes. Checked as well as the hash: it lets a wrong-size
    /// response be refused before it is read, and it makes a truncated transfer
    /// fail as a size mismatch rather than as an opaque hash mismatch.
    pub size: u64,
    /// Path on the release host, beginning with `/`. Never a full URL: the host
    /// comes from the allowlist, so metadata cannot redirect us elsewhere.
    pub path: String,
    /// Optional human-readable release notes. Never acted on.
    #[serde(default)]
    pub notes: Option<String>,
}

/// Which component a release entry describes.
///
/// An enum rather than a free string: the set is fixed, and the launcher must
/// never be able to install "a component" it does not have handling for.
/// `mod-policies.md` §7 is the reason there is no `Other(String)` — a
/// third-party mod is not a first-party component and must not be installable
/// through this channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ComponentId {
    /// The launcher executable itself.
    Launcher,
    /// `fathom-optimizations`, the Minecraft-side jar.
    OptimizationModule,
    /// The bundled catalogue and policy data the launcher reads.
    Metadata,
}

impl ComponentId {
    pub const ALL: [ComponentId; 3] = [
        ComponentId::Launcher,
        ComponentId::OptimizationModule,
        ComponentId::Metadata,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            ComponentId::Launcher => "launcher",
            ComponentId::OptimizationModule => "optimization-module",
            ComponentId::Metadata => "metadata",
        }
    }

    /// Parse an id from metadata.
    ///
    /// Returns `None` for anything unrecognised, and the caller **skips** it
    /// rather than failing. A newer release naming a component this build does
    /// not know about should not stop it from updating the ones it does; and
    /// an unknown id must never become an install target.
    pub fn parse(text: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|c| c.as_str() == text)
    }

    /// Whether this component replaces the running program.
    ///
    /// The launcher cannot overwrite itself while running, so it takes the
    /// swap-and-supervise path in `install`. Everything else is an ordinary
    /// file.
    pub fn is_the_running_program(self) -> bool {
        matches!(self, ComponentId::Launcher)
    }

    pub fn describe(self) -> &'static str {
        match self {
            ComponentId::Launcher => "The launcher itself.",
            ComponentId::OptimizationModule => {
                "Fathom's own Minecraft-side optimization mod. Ours, not a third-party mod."
            }
            ComponentId::Metadata => {
                "The bundled mod catalogue and policy data. Data only; never code."
            }
        }
    }
}

impl fmt::Display for ComponentId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// A dotted version, compared componentwise.
///
/// Deliberately tiny and total: any string parses, and anything unparseable
/// sorts below anything parseable. Rollback protection compares versions, so a
/// comparison that can fail would be a comparison that can be made to fail.
///
/// Not full semver. Pre-release ordering and build metadata are not modelled
/// because the release process does not produce them; if it ever does, this is
/// the place that has to grow, and the tests below say what it currently
/// promises.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Version(Vec<u64>);

impl Version {
    pub fn parse(text: &str) -> Self {
        // Everything up to the first character that is neither a digit nor a
        // dot. `1.2.3-rc1` therefore compares as `1.2.3`, which is the
        // conservative reading: a pre-release does not sort above the release.
        let numeric: String = text
            .trim()
            .chars()
            .take_while(|c| c.is_ascii_digit() || *c == '.')
            .collect();
        let parts: Vec<u64> = numeric
            .split('.')
            .filter(|s| !s.is_empty())
            .filter_map(|s| s.parse().ok())
            .collect();
        Self(parts)
    }

    /// True when `self` is strictly newer than `other`.
    ///
    /// Trailing zeroes do not count: `1.2` and `1.2.0` are the same version.
    pub fn is_newer_than(&self, other: &Version) -> bool {
        self.cmp_padded(other) == std::cmp::Ordering::Greater
    }

    pub fn is_older_than(&self, other: &Version) -> bool {
        self.cmp_padded(other) == std::cmp::Ordering::Less
    }

    fn cmp_padded(&self, other: &Version) -> std::cmp::Ordering {
        let len = self.0.len().max(other.0.len());
        for i in 0..len {
            let a = self.0.get(i).copied().unwrap_or(0);
            let b = other.0.get(i).copied().unwrap_or(0);
            match a.cmp(&b) {
                std::cmp::Ordering::Equal => {}
                other => return other,
            }
        }
        std::cmp::Ordering::Equal
    }

    /// Whether the string carried any version at all.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl fmt::Display for Version {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let parts: Vec<String> = self.0.iter().map(u64::to_string).collect();
        f.write_str(&parts.join("."))
    }
}

/// The platform a client is running on, as metadata spells it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Platform {
    pub os: String,
    pub arch: String,
}

impl Platform {
    /// What this build is running on.
    pub fn current() -> Self {
        Self {
            os: match std::env::consts::OS {
                "windows" => "windows",
                "macos" => "macos",
                "linux" => "linux",
                other => other,
            }
            .to_owned(),
            arch: match std::env::consts::ARCH {
                "x86_64" => "x86_64",
                "aarch64" => "aarch64",
                other => other,
            }
            .to_owned(),
        }
    }

    /// Whether an entry is for this machine.
    ///
    /// `any` matches anything, for artifacts that are genuinely portable — a
    /// jar, a metadata bundle. An executable must never be published as `any`,
    /// and `verify` refuses that combination rather than trusting the publisher
    /// to remember.
    pub fn accepts(&self, os: &str, arch: &str) -> bool {
        (os == "any" || os == self.os) && (arch == "any" || arch == self.arch)
    }
}

impl fmt::Display for Platform {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}-{}", self.os, self.arch)
    }
}

/// Roles as they appear in the `role` field, for cross-checking.
pub fn role_label_matches(role: Role, text: &str) -> bool {
    role.label() == text
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

    #[test]
    fn versions_compare_componentwise_not_lexically() {
        // The bug this exists to prevent: "10" < "9" as a string.
        assert!(Version::parse("0.10.0").is_newer_than(&Version::parse("0.9.0")));
        assert!(Version::parse("1.0.0").is_newer_than(&Version::parse("0.99.99")));
        assert!(Version::parse("2").is_newer_than(&Version::parse("1.99")));
    }

    #[test]
    fn trailing_zeroes_do_not_make_a_new_version() {
        let a = Version::parse("1.2");
        let b = Version::parse("1.2.0");
        assert!(!a.is_newer_than(&b));
        assert!(!b.is_newer_than(&a));
        assert!(!a.is_older_than(&b));
    }

    #[test]
    fn a_prerelease_does_not_sort_above_its_release() {
        // Conservative on purpose: an unparseable suffix is dropped, so
        // `1.2.3-rc1` compares equal to `1.2.3` rather than above it. Equal is
        // not newer, so it will not trigger an update, which is the safe
        // direction for a string we do not fully model.
        assert!(!Version::parse("1.2.3-rc1").is_newer_than(&Version::parse("1.2.3")));
        assert!(!Version::parse("1.2.3").is_older_than(&Version::parse("1.2.3-rc1")));
    }

    #[test]
    fn any_string_parses_rather_than_failing() {
        // Rollback protection compares versions. A comparison that can fail is
        // a comparison an attacker can make fail.
        for text in [
            "",
            "  ",
            "not a version",
            "..",
            "v1.2.3",
            "\u{4e2d}\u{6587}",
        ] {
            let parsed = Version::parse(text);
            // Whatever it parsed to, it must not be newer than a real version.
            assert!(
                !parsed.is_newer_than(&Version::parse("0.0.1")),
                "{text:?} parsed to {parsed} and claimed to be newer than 0.0.1"
            );
        }
        assert!(Version::parse("").is_empty());
        assert!(
            Version::parse("v1.2.3").is_empty(),
            "a leading v is not a digit"
        );
    }

    #[test]
    fn a_huge_version_component_does_not_overflow_or_panic() {
        let huge = format!("{}.{}", u64::MAX, u64::MAX);
        let parsed = Version::parse(&huge);
        assert!(parsed.is_newer_than(&Version::parse("1.0.0")));
        // Beyond u64 is dropped rather than wrapping.
        let beyond = Version::parse("99999999999999999999999.1");
        assert!(!beyond.is_newer_than(&Version::parse("99999999999999999999999.2")));
    }

    #[test]
    fn platform_matching_is_exact_except_for_any() {
        let win = Platform {
            os: "windows".to_owned(),
            arch: "x86_64".to_owned(),
        };
        assert!(win.accepts("windows", "x86_64"));
        assert!(win.accepts("any", "any"));
        assert!(win.accepts("windows", "any"));
        assert!(!win.accepts("linux", "x86_64"));
        assert!(!win.accepts("windows", "aarch64"));
        // No prefix or case leniency: a near-miss is a miss.
        assert!(!win.accepts("Windows", "x86_64"));
        assert!(!win.accepts("win", "x86_64"));
        assert!(!win.accepts("windows", "x86"));
    }

    #[test]
    fn component_ids_are_a_closed_set() {
        assert_eq!(ComponentId::parse("launcher"), Some(ComponentId::Launcher));
        assert_eq!(
            ComponentId::parse("optimization-module"),
            Some(ComponentId::OptimizationModule)
        );
        // A third-party mod is not a first-party component, and there is no
        // variant that could make it one.
        for unknown in ["optifine", "sodium", "", "Launcher", "../launcher"] {
            assert_eq!(ComponentId::parse(unknown), None, "{unknown:?}");
        }
    }

    #[test]
    fn only_the_launcher_replaces_the_running_program() {
        assert!(ComponentId::Launcher.is_the_running_program());
        assert!(!ComponentId::OptimizationModule.is_the_running_program());
        assert!(!ComponentId::Metadata.is_the_running_program());
    }
}
