//! A string that does not leak into logs.
//!
//! `THREAT_MODEL.md` lists "sensitive log leakage" as a real threat, and the
//! usual cause is a `#[derive(Debug)]` on a struct that happens to contain a
//! token. Wrapping tokens in [`Secret`] makes that mistake impossible: the only
//! way to see the value is to call [`Secret::expose`], which greps well.

use std::fmt;

/// A secret string. `Debug` and `Display` both redact.
///
/// # It deserializes, and deliberately does not serialize
///
/// [`serde::Deserialize`] is implemented so a token can be a `Secret` from the
/// moment it is parsed off the wire, rather than living as a `String` in a
/// `#[derive(Debug)]` response struct until somebody remembers to wrap it. The
/// security pass found three such structs in `auth`, each one `Debug`-derived
/// with a bearer token inside; a single `tracing::debug!(?response)` would have
/// put a live Minecraft access token in the log.
///
/// [`serde::Serialize`] is **not** implemented, and must never be. That absence
/// is what makes it a compile error to put a token in a profile, a
/// configuration file or any other JSON this program writes — `security-model.md`
/// forbids it, and an unimplemented trait enforces it better than a review does.
///
/// That absence is load-bearing, so it is tested rather than asserted:
///
/// ```compile_fail
/// use launcher_core::secret::Secret;
/// #[derive(serde::Serialize)]
/// struct Profile {
///     name: String,
///     // A token must never be writable into a profile, a config file, or any
///     // other JSON this program produces. `Secret` has no `Serialize`, so
///     // this does not compile -- which is the point.
///     token: Secret,
/// }
/// ```
#[derive(Clone, PartialEq, Eq, serde::Deserialize)]
#[serde(transparent)]
pub struct Secret(String);

impl Secret {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    /// Read the underlying value. Call sites are deliberately easy to audit.
    pub fn expose(&self) -> &str {
        &self.0
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Compare against a candidate without leaking where they differ.
    ///
    /// # Why constant time matters for exactly this
    ///
    /// The bridge's token is checked against a string an unauthenticated caller
    /// supplied, and that caller can retry as fast as a socket allows. A
    /// byte-by-byte comparison that returns early tells them how many leading
    /// bytes were right, which turns a 2^256 search into 64 searches of 16.
    ///
    /// The length is deliberately folded in rather than short-circuited on:
    /// returning early for a wrong length would leak the length, which is a
    /// much smaller leak but a free one to avoid.
    pub fn equals_constant_time(&self, candidate: &str) -> bool {
        let expected = self.0.as_bytes();
        let actual = candidate.as_bytes();
        // Kept as a `usize`, not folded into the byte accumulator. Casting the
        // length difference to `u8` truncates, so two lengths differing by
        // exactly 256 would XOR to zero and the check would come down to the
        // bytes alone.
        let same_length = expected.len() == actual.len();
        let mut difference = 0u8;
        // Walk the longer of the two so the loop count does not depend on the
        // candidate, indexing the shorter one modulo its length -- which reads
        // real bytes rather than a constant an optimiser could hoist.
        let length = expected.len().max(actual.len());
        for index in 0..length {
            let left = expected
                .get(index % expected.len().max(1))
                .copied()
                .unwrap_or(0);
            let right = actual
                .get(index % actual.len().max(1))
                .copied()
                .unwrap_or(0);
            difference |= left ^ right;
        }
        // An empty secret never matches. It is the state `random_token` falls
        // back to when the operating system cannot produce randomness, and it
        // must disable the bridge rather than accept everything.
        difference == 0 && same_length && !expected.is_empty()
    }

    /// A stable, non-reversible hint for the UI: length only.
    pub fn redacted_hint(&self) -> String {
        format!("<redacted, {} chars>", self.0.len())
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Secret(<redacted>)")
    }
}

impl fmt::Display for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<redacted>")
    }
}

impl From<String> for Secret {
    fn from(value: String) -> Self {
        Self(value)
    }
}

impl From<&str> for Secret {
    fn from(value: &str) -> Self {
        Self(value.to_owned())
    }
}

/// Replace any occurrence of a secret's value in `text` with a redaction
/// marker. Used before a command line or log line reaches the UI or disk.
pub fn redact_all(text: &str, secrets: &[&Secret]) -> String {
    let mut out = text.to_owned();
    for s in secrets {
        if s.0.is_empty() {
            continue;
        }
        out = out.replace(&s.0, "<redacted>");
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

    #[test]
    fn debug_and_display_never_reveal_the_value() {
        let s = Secret::new("super-secret-token");
        assert_eq!(format!("{s:?}"), "Secret(<redacted>)");
        assert_eq!(format!("{s}"), "<redacted>");
        assert!(!format!("{s:?} {s}").contains("super-secret-token"));
    }

    #[test]
    fn nested_in_a_derived_debug_struct_it_still_redacts() {
        #[derive(Debug)]
        #[allow(dead_code)] // read only via the derived Debug impl
        struct Session {
            user: String,
            token: Secret,
        }
        let session = Session {
            user: "steve".into(),
            token: Secret::new("abc123"),
        };
        let rendered = format!("{session:?}");
        assert!(rendered.contains("steve"));
        assert!(!rendered.contains("abc123"));
    }

    #[test]
    fn redact_all_scrubs_every_secret() {
        let token = Secret::new("aaa");
        let other = Secret::new("bbb");
        let line = "--accessToken aaa --extra bbb --user steve";
        assert_eq!(
            redact_all(line, &[&token, &other]),
            "--accessToken <redacted> --extra <redacted> --user steve"
        );
    }

    #[test]
    fn redact_all_ignores_empty_secrets() {
        let empty = Secret::new("");
        assert_eq!(redact_all("anything", &[&empty]), "anything");
    }

    /// A token is a `Secret` from the moment it is parsed, not from the moment
    /// somebody remembers to wrap it.
    #[test]
    fn a_secret_parsed_from_json_still_redacts() {
        #[derive(Debug, serde::Deserialize)]
        struct Response {
            access_token: Secret,
            expires_in: u64,
        }

        let parsed: Response =
            serde_json::from_str(r#"{"access_token":"live-token-value","expires_in":86400}"#)
                .expect("parse");

        assert_eq!(parsed.access_token.expose(), "live-token-value");
        assert_eq!(parsed.expires_in, 86400);

        // The whole point: the derived Debug on the response cannot leak it.
        let rendered = format!("{parsed:?}");
        assert!(
            !rendered.contains("live-token-value"),
            "a parsed token reached a Debug rendering: {rendered}"
        );
        assert!(rendered.contains("86400"), "and the rest still renders");
    }

    #[test]
    fn hint_reveals_only_length() {
        let s = Secret::new("abcdef");
        assert_eq!(s.redacted_hint(), "<redacted, 6 chars>");
    }
}
