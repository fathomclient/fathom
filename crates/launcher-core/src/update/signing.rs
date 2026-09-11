//! Ed25519 verification, key identity, and the bytes that actually get signed.
//!
//! This module holds the two decisions that most signed-update designs get
//! wrong, and it holds them in one place so they can be argued about once.
//!
//! # 1. We verify the bytes we received, never a re-serialization
//!
//! The common design signs a JSON object, and the verifier re-serializes the
//! parsed object to reconstruct what was signed. That requires a canonical form
//! — key ordering, number formatting, string escaping, duplicate-key handling —
//! and every one of those is a place where the verifier's idea of the document
//! can differ from the signer's. TUF needs canonical JSON for this reason and
//! has had implementation bugs in it.
//!
//! We avoid the problem instead of solving it. A signed document is an envelope
//! whose payload is an **opaque base64 blob**:
//!
//! ```text
//! { "payload": "<base64 of the UTF-8 JSON>", "signatures": [ ... ] }
//! ```
//!
//! Signatures are verified over the decoded bytes, exactly as they arrived.
//! Only after every signature checks out are those bytes parsed as JSON. There
//! is no canonical form to get wrong, and no parser runs on unverified input
//! beyond the base64 decoder and the envelope itself.
//!
//! # 2. Every signature is bound to the role it was made for
//!
//! A signature over a bare payload says "somebody with this key signed these
//! bytes". It does not say what those bytes were *for*. If root metadata and
//! release metadata were both signed the same way, a root signature could be
//! presented as a release signature, and a document that a key was authorised
//! to sign in one position could be replayed into another.
//!
//! So the signed message is a domain-separated preimage:
//!
//! ```text
//! "fathom-update-v1" 0x00 <role> 0x00 <payload bytes>
//! ```
//!
//! The separators are NUL bytes, which cannot occur in either constant, so no
//! two distinct (role, payload) pairs can produce the same preimage. The version
//! prefix means a future format change cannot be mistaken for this one.

use std::fmt;

use base64::Engine as _;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};

/// The domain separation prefix. Changing this invalidates every signature ever
/// made, which is the point: it is how a format revision is fenced off.
const DOMAIN: &[u8] = b"fathom-update-v1";

/// Which position a document occupies in the trust chain.
///
/// Part of the signed preimage, so a document cannot be moved from one role to
/// another even by someone holding a valid key for the other role.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Role {
    /// The trust anchor. Names the release keys. Signed by keys that live
    /// offline and are used a handful of times a year.
    Root,
    /// The document that actually names artifacts. Signed by keys that a
    /// release process uses often, and which are therefore likelier to be
    /// exposed.
    Release,
}

impl Role {
    /// The bytes that appear in the preimage. Short, fixed, and never reused.
    pub fn label(self) -> &'static str {
        match self {
            Role::Root => "root",
            Role::Release => "release",
        }
    }
}

impl fmt::Display for Role {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

#[derive(Debug, thiserror::Error)]
pub enum SignatureError {
    #[error("a public key must be 32 bytes of Ed25519, got {0}")]
    KeyLength(usize),
    #[error("{0} is not a usable Ed25519 public key")]
    KeyMalformed(String),
    #[error("a signature must be 64 bytes, got {0}")]
    SignatureLength(usize),
    #[error("could not decode base64 in {field}: {detail}")]
    Base64 { field: String, detail: String },
    #[error(
        "{role} metadata carries {present} valid signature(s) but needs {threshold}. \
         Refusing to trust it."
    )]
    BelowThreshold {
        role: Role,
        present: usize,
        threshold: usize,
    },
    #[error("{role} metadata is signed by {key}, which is not a {role} key")]
    UnknownKey { role: Role, key: String },
    #[error("{role} metadata carries two signatures from the same key {key}")]
    DuplicateSignature { role: Role, key: String },
}

/// A verification key, plus the identifier it is referred to by.
///
/// Deliberately owns a `VerifyingKey` rather than raw bytes: an
/// [`PublicKey`] cannot exist unless the bytes were a valid curve point, so
/// nothing downstream has to handle "a key that will not parse".
#[derive(Clone)]
pub struct PublicKey {
    id: KeyId,
    key: VerifyingKey,
}

impl PublicKey {
    /// Parse from 32 raw bytes.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, SignatureError> {
        let array: [u8; 32] = bytes
            .try_into()
            .map_err(|_| SignatureError::KeyLength(bytes.len()))?;
        let key = VerifyingKey::from_bytes(&array)
            .map_err(|e| SignatureError::KeyMalformed(e.to_string()))?;
        Ok(Self {
            id: KeyId::of(&array),
            key,
        })
    }

    /// Parse from the base64 form used in metadata and in the compiled-in list.
    pub fn from_base64(text: &str) -> Result<Self, SignatureError> {
        let bytes = decode_base64(text, "public key")?;
        Self::from_bytes(&bytes)
    }

    pub fn id(&self) -> &KeyId {
        &self.id
    }

    pub fn to_base64(&self) -> String {
        base64::engine::general_purpose::STANDARD.encode(self.key.as_bytes())
    }

    /// Whether this key signed `payload` in the position `role`.
    ///
    /// Constant-time-ish by construction: Ed25519 verification does not branch
    /// on secret data, and there is no secret here anyway — the key is public
    /// and the payload is public.
    pub fn verifies(&self, role: Role, payload: &[u8], signature: &Signature) -> bool {
        self.key.verify(&preimage(role, payload), signature).is_ok()
    }
}

/// Prints the id rather than the key.
///
/// Not for secrecy — a public key is public — but because the id is what error
/// messages, logs and metadata all refer to, and a 44-character base64 blob in
/// a log is noise. Deliberately omits the key itself.
#[allow(clippy::missing_fields_in_debug)]
impl fmt::Debug for PublicKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PublicKey").field("id", &self.id).finish()
    }
}

impl PartialEq for PublicKey {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl Eq for PublicKey {}

/// A short, stable name for a key.
///
/// The first 16 bytes of `SHA-256(public key)`, hex encoded. Truncation is safe
/// here because a key id is a *lookup label*, never a security decision: a
/// signature is checked against the key it names, and a colliding id would at
/// worst make us try the wrong key and fail verification. It is short so that
/// it fits in an error message a user can read out.
#[derive(Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct KeyId(String);

impl KeyId {
    fn of(key_bytes: &[u8; 32]) -> Self {
        use std::fmt::Write as _;
        let digest = Sha256::digest(key_bytes);
        let mut hex = String::with_capacity(32);
        for byte in digest.iter().take(16) {
            let _ = write!(hex, "{byte:02x}");
        }
        Self(hex)
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Parse an id as written in metadata. Only the shape is checked; an id
    /// that names no key we hold is rejected later, by not matching.
    pub fn parse(text: &str) -> Option<Self> {
        let trimmed = text.trim();
        if trimmed.len() == 32 && trimmed.bytes().all(|b| b.is_ascii_hexdigit()) {
            Some(Self(trimmed.to_ascii_lowercase()))
        } else {
            None
        }
    }
}

impl fmt::Debug for KeyId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl fmt::Display for KeyId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// The exact bytes an Ed25519 signature is made over.
///
/// `DOMAIN 0x00 role 0x00 payload`. Both separators are NUL, which cannot
/// appear in `DOMAIN` or in any `Role::label`, so the encoding is injective:
/// no two distinct (role, payload) pairs share a preimage.
///
/// Public because `fathom-release` signs with it, and a signer that computes
/// the preimage differently from the verifier produces signatures nobody can
/// check. One function, used by both.
pub fn preimage(role: Role, payload: &[u8]) -> Vec<u8> {
    let label = role.label().as_bytes();
    let mut out = Vec::with_capacity(DOMAIN.len() + label.len() + payload.len() + 2);
    out.extend_from_slice(DOMAIN);
    out.push(0);
    out.extend_from_slice(label);
    out.push(0);
    out.extend_from_slice(payload);
    out
}

/// Parse a base64 signature into the fixed-size form Ed25519 needs.
pub fn signature_from_base64(text: &str) -> Result<Signature, SignatureError> {
    let bytes = decode_base64(text, "signature")?;
    let array: [u8; 64] = bytes
        .as_slice()
        .try_into()
        .map_err(|_| SignatureError::SignatureLength(bytes.len()))?;
    Ok(Signature::from_bytes(&array))
}

pub fn decode_base64(text: &str, field: &str) -> Result<Vec<u8>, SignatureError> {
    base64::engine::general_purpose::STANDARD
        .decode(text.trim())
        .map_err(|e| SignatureError::Base64 {
            field: field.to_owned(),
            detail: e.to_string(),
        })
}

pub fn encode_base64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
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

    /// A fixed keypair, so the tests do not need a random source and so a
    /// failure is reproducible.
    ///
    /// The seed is a literal in a test file. That is fine and is worth saying
    /// out loud: it signs nothing anyone will ever trust, and the real signing
    /// key lives outside this repository by construction — see
    /// `crates/fathom-release`.
    fn test_key() -> ed25519_dalek::SigningKey {
        ed25519_dalek::SigningKey::from_bytes(&[7u8; 32])
    }

    fn public(signing: &ed25519_dalek::SigningKey) -> PublicKey {
        PublicKey::from_bytes(signing.verifying_key().as_bytes()).unwrap()
    }

    #[test]
    fn a_signature_verifies_for_the_role_it_was_made_for() {
        use ed25519_dalek::Signer as _;
        let signing = test_key();
        let key = public(&signing);
        let payload = b"{\"version\":1}";

        let signature = signing.sign(&preimage(Role::Release, payload));
        assert!(key.verifies(Role::Release, payload, &signature));
    }

    /// The domain separation, checked directly. A signature made as `release`
    /// must not verify as `root`, even though the key and the bytes are the
    /// same — otherwise a document could be moved between positions in the
    /// trust chain.
    #[test]
    fn a_signature_does_not_verify_for_a_different_role() {
        use ed25519_dalek::Signer as _;
        let signing = test_key();
        let key = public(&signing);
        let payload = b"{\"version\":1}";

        let as_release = signing.sign(&preimage(Role::Release, payload));
        assert!(!key.verifies(Role::Root, payload, &as_release));

        let as_root = signing.sign(&preimage(Role::Root, payload));
        assert!(!key.verifies(Role::Release, payload, &as_root));
    }

    /// The preimage encoding has to be injective, or the separation above is
    /// decorative. NUL cannot appear in the domain or in a role label, so no
    /// (role, payload) pair can be confused with another.
    #[test]
    fn no_two_role_and_payload_pairs_share_a_preimage() {
        let mut seen = std::collections::HashSet::new();
        for role in [Role::Root, Role::Release] {
            // Payloads chosen to try to smuggle a role boundary: the label of
            // the other role, with and without NULs around it.
            for payload in [
                b"".to_vec(),
                b"root".to_vec(),
                b"release".to_vec(),
                b"\0root\0".to_vec(),
                b"\0release\0".to_vec(),
                b"fathom-update-v1\0root\0".to_vec(),
            ] {
                assert!(
                    seen.insert(preimage(role, &payload)),
                    "{role} + {payload:?} collided with an earlier preimage"
                );
            }
        }
    }

    #[test]
    fn a_tampered_payload_does_not_verify() {
        use ed25519_dalek::Signer as _;
        let signing = test_key();
        let key = public(&signing);

        let signature = signing.sign(&preimage(Role::Release, b"original"));
        assert!(!key.verifies(Role::Release, b"originaL", &signature));
        assert!(!key.verifies(Role::Release, b"original ", &signature));
        assert!(!key.verifies(Role::Release, b"", &signature));
    }

    #[test]
    fn a_different_key_does_not_verify() {
        use ed25519_dalek::Signer as _;
        let signing = test_key();
        let other = ed25519_dalek::SigningKey::from_bytes(&[9u8; 32]);

        let signature = signing.sign(&preimage(Role::Release, b"payload"));
        assert!(!public(&other).verifies(Role::Release, b"payload", &signature));
    }

    #[test]
    fn key_ids_are_stable_and_distinct() {
        let a = public(&test_key());
        let b = public(&ed25519_dalek::SigningKey::from_bytes(&[9u8; 32]));

        assert_eq!(
            a.id(),
            public(&test_key()).id(),
            "the same key, the same id"
        );
        assert_ne!(a.id(), b.id());
        assert_eq!(a.id().as_str().len(), 32);
        assert!(a.id().as_str().bytes().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn key_ids_round_trip_through_their_text_form() {
        let key = public(&test_key());
        let parsed = KeyId::parse(key.id().as_str()).expect("a real id parses");
        assert_eq!(&parsed, key.id());

        for bad in [
            "",
            "xyz",
            "ZZ",
            &"a".repeat(31),
            &"a".repeat(33),
            "not hex here!!!!",
        ] {
            assert!(KeyId::parse(bad).is_none(), "{bad:?} is not an id");
        }
    }

    #[test]
    fn keys_and_signatures_reject_the_wrong_length() {
        assert!(matches!(
            PublicKey::from_bytes(&[0u8; 31]),
            Err(SignatureError::KeyLength(31))
        ));
        assert!(matches!(
            PublicKey::from_bytes(&[]),
            Err(SignatureError::KeyLength(0))
        ));
        assert!(matches!(
            signature_from_base64(&encode_base64(&[0u8; 63])),
            Err(SignatureError::SignatureLength(63))
        ));
        assert!(matches!(
            signature_from_base64("not base64 !!"),
            Err(SignatureError::Base64 { .. })
        ));
    }

    #[test]
    fn a_public_key_round_trips_through_base64() {
        let key = public(&test_key());
        let text = key.to_base64();
        assert_eq!(PublicKey::from_base64(&text).unwrap(), key);
    }
}
