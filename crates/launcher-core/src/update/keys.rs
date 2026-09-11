//! The trust anchor: root public keys, compiled into the binary.
//!
//! # Why this list is empty, and why that is the right state to ship in
//!
//! There is no key here because **there is no key**. Generating an Ed25519
//! keypair takes a second; the hard part is the private half, which must be
//! created on an offline machine, stored on hardware or in a safe, and never
//! touch this repository, this filesystem, or a CI environment. That has not
//! been done, so there is nothing honest to put here.
//!
//! The alternatives were both worse:
//!
//! - **A placeholder key whose private half exists in the repository.** Then
//!   the update channel is signed by a key every reader of this project has,
//!   which is indistinguishable from no signature at all while looking like
//!   one.
//! - **A placeholder key whose private half does not exist.** Then updates
//!   silently never verify, and the failure looks like a network problem rather
//!   than a missing anchor.
//!
//! So the list is empty and [`update_channel_is_configured`] is false, and the
//! update service **refuses to check at all** with a message that says why.
//! Nothing about the mechanism is stubbed: every verification path is
//! implemented and tested against injected keys. What is missing is a secret
//! that must not be created here.
//!
//! # Adding the real anchor
//!
//! 1. On an offline machine: `fathom-release keygen --out root-1.key`. Three
//!    times, on three machines, if the threshold is to mean anything.
//! 2. Put each private key on separate hardware. Never copy it anywhere else.
//! 3. Paste the printed **public** keys into [`ROOT_KEYS`] and set
//!    [`ROOT_THRESHOLD`].
//! 4. Sign a `root.json` naming the release keys, and publish it.
//!
//! Only step 3 touches this repository, and it adds public material only.
//!
//! # Why a threshold at all
//!
//! One key means one machine, one disk, one person, one mistake. A threshold of
//! two of three means an attacker needs two independent compromises, and it
//! means losing one key is a recoverable accident rather than the end of the
//! update channel. The cost is that a release needs two people, which for a
//! channel that can execute code on every installation is a feature.

use super::signing::{PublicKey, SignatureError};

/// Base64 Ed25519 public keys that may sign a `root` document.
///
/// **Empty in this build.** See the module comment.
pub const ROOT_KEYS: &[&str] = &[];

/// How many distinct [`ROOT_KEYS`] must sign a root document.
///
/// Two, so that a single compromised or lost key is neither sufficient for an
/// attacker nor fatal for us. Meaningless while `ROOT_KEYS` is empty, and
/// stated now so the intent is not decided under pressure later.
pub const ROOT_THRESHOLD: usize = 2;

/// Whether this build can verify an update at all.
///
/// Checked before any network request. A launcher with no anchor must not
/// download a manifest it has no way to judge — not even to display it — and
/// must say so plainly rather than reporting "up to date".
pub fn update_channel_is_configured() -> bool {
    !ROOT_KEYS.is_empty() && ROOT_THRESHOLD > 0 && ROOT_THRESHOLD <= ROOT_KEYS.len()
}

/// Parse the compiled-in anchor.
///
/// Returns an error rather than panicking if a key was pasted in wrong: a typo
/// in this file should fail the tests below, and if it somehow reaches a user it
/// should disable updates rather than crash the launcher.
pub fn root_keys() -> Result<Vec<PublicKey>, SignatureError> {
    ROOT_KEYS
        .iter()
        .map(|k| PublicKey::from_base64(k))
        .collect()
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

    /// The state this build is deliberately in. If someone adds a key, this
    /// test fails and they have to come here and read why the list was empty —
    /// which is the conversation worth forcing.
    #[test]
    fn this_build_ships_without_a_trust_anchor() {
        assert!(
            ROOT_KEYS.is_empty(),
            "a root key was added. Confirm its private half was generated offline and never \
             touched this repository, then update this test."
        );
        assert!(
            !update_channel_is_configured(),
            "with no keys, the update channel must report itself as unconfigured"
        );
    }

    /// Whatever is in the list must parse. A typo here would otherwise surface
    /// as a mysterious verification failure on a user's machine.
    #[test]
    fn every_compiled_in_key_is_a_valid_ed25519_key() {
        let parsed = root_keys().expect("every compiled-in root key must parse");
        assert_eq!(parsed.len(), ROOT_KEYS.len());

        // No duplicates: two entries with the same key would let one key
        // satisfy a threshold of two.
        let mut ids: Vec<String> = parsed.iter().map(|k| k.id().to_string()).collect();
        ids.sort();
        let before = ids.len();
        ids.dedup();
        assert_eq!(before, ids.len(), "the root key list contains a duplicate");
    }

    /// A threshold above the number of keys would make the channel permanently
    /// unusable; a threshold of zero would make signatures optional.
    #[test]
    #[allow(clippy::assertions_on_constants)]
    fn the_threshold_is_meetable_and_not_zero() {
        // Constant today, and the point is that it stays non-zero when someone
        // edits it. A threshold of zero would accept unsigned metadata.
        assert!(
            ROOT_THRESHOLD > 0,
            "a threshold of zero accepts unsigned metadata"
        );
        if !ROOT_KEYS.is_empty() {
            assert!(
                ROOT_THRESHOLD <= ROOT_KEYS.len(),
                "a threshold of {ROOT_THRESHOLD} cannot be met by {} keys",
                ROOT_KEYS.len()
            );
        }
    }

    /// The private half must never appear here. A crude check, but it would
    /// have caught the mistake in every incident where this has happened to
    /// someone else.
    #[test]
    fn no_private_key_material_is_present_in_this_file() {
        // Only the part above the tests. The test itself has to name the
        // markers it looks for, so scanning the whole file finds them in this
        // very list -- which is how the first version of this test failed.
        let source = include_str!("keys.rs");
        let source = source
            .split_once("#[cfg(test)]")
            .map_or(source, |(before, _)| before);

        for marker in [
            "BEGIN PRIVATE KEY",
            "BEGIN OPENSSH PRIVATE KEY",
            "BEGIN EC PRIVATE KEY",
            "SigningKey::from_bytes",
            "SECRET_KEY",
            "PRIVATE_KEY",
        ] {
            assert!(
                !source.contains(marker),
                "{marker} appears in keys.rs; a private key must never be in this repository"
            );
        }
    }
}
