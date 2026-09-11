//! The ownership gate.
//!
//! `security-model.md` §2.2 is emphatic that reading `items` out of the
//! unsigned JSON body is **not** sufficient: an attacker who controls the
//! network path or the machine's trust store could forge it. The entitlements
//! response carries an RS256 JWT signed by Mojang, and the entitlement list we
//! act on must come from **the verified JWT payload**.
//!
//! So this module is two halves, deliberately separated:
//!
//! - [`verify_signed_entitlements`] — cryptography. Fails closed.
//! - [`decide`] — pure policy over a verified claim list. No I/O, no crypto,
//!   exhaustively testable.

use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use serde::Deserialize;

/// Mojang's entitlement signing key.
///
/// Published alongside the entitlements API documentation. Compiled in rather
/// than fetched, so a compromised network cannot substitute its own key.
///
/// NOTE: this value came from public documentation, not from a live response we
/// captured ourselves. Until it has been checked against a real signed
/// entitlement, verification failing means "refuse to launch", which is the
/// safe direction but would be a bad launch-day bug. See `ARCHITECTURE.md`
/// open questions.
pub const MOJANG_ENTITLEMENT_PUBLIC_KEY_PEM: &str = "-----BEGIN PUBLIC KEY-----\n\
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAtz7jy4jRH3psj5AbVS6W\n\
NHjniqlr/f5JDly2M8OKGK81nPEq765tJuSILOWrC3KQRvHJIhf84+ekMGH7iGlO\n\
4DPGDVb6hBGoMMBhCq2jkBjuJ7fVi3oOxy5EsA/IQqa69e55ugM+GJKUndLyHeNn\n\
X6RzRzDT4tX/i68WJikwL8rR8Jq49aVJlIEFT6F+1rDQdU2qcpfT04CBYLM5gMxE\n\
fWRl6u1PNQixz8vSOv8pA6hB2DU8Y08VvbK7X2ls+BiS3wqqj3nyVWqoxrwVKiXR\n\
kIqIyIAedYDFSaIq5vbmnVtIonWQPeug4/0spLQoWnTUpXRZe2/+uAKN1RY9mmaB\n\
pRFV/Osz3PDOoICGb5AZ0asLFf/qEvGJ+di6Ltt8/aaoBuVw+7fnTw2BhkhSq1S/\n\
va6LxHZGXE9wsLj4CN8mZXHfwVD9QG0VNQTUgEGZ4ngf7+0u30p7mPt5sYy3H+Fm\n\
sWXqFZn55pecmrgNLqtETPWMNpWc2fJu/qqnxE9o2tBGy/MqJiw3iLYxf7U+4le4\n\
jM49AUKrO16bD1rdFwyVuNaTefObKjEMTX9gyVUF6o7oDEItp5NHxFm3CqnQRmch\n\
HsMs+NxEnN4E9a8PDB23b4yjKOQ9VHDxBxuaZJU60GBCIOF9tslb7OAkheSJx5Xy\n\
EYblHbogFGPRFU++NrSQRX0CAwEAAQ==\n\
-----END PUBLIC KEY-----\n";

#[derive(Debug, thiserror::Error)]
pub enum EntitlementError {
    #[error("the entitlements response had no signature; refusing to trust it")]
    Unsigned,
    #[error("the entitlement signature is not valid: {0}")]
    BadSignature(String),
    #[error("the compiled-in Mojang signing key could not be parsed: {0}")]
    BadKey(String),
}

/// One entitlement, as named inside the signed payload.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct NamedEntitlement {
    pub name: String,
}

/// The verified claims.
#[derive(Debug, Clone, Deserialize)]
pub struct EntitlementClaims {
    #[serde(default)]
    pub entitlements: Vec<NamedEntitlement>,
    #[serde(rename = "signerId", default)]
    pub signer_id: Option<String>,
    #[serde(default)]
    pub platform: Option<String>,
}

impl EntitlementClaims {
    pub fn names(&self) -> Vec<&str> {
        self.entitlements.iter().map(|e| e.name.as_str()).collect()
    }
}

/// The unsigned envelope Mojang returns. Only `signature` is trusted.
#[derive(Debug, Clone, Deserialize)]
pub struct EntitlementsResponse {
    #[serde(default)]
    pub items: Vec<NamedEntitlement>,
    #[serde(default)]
    pub signature: Option<String>,
    #[serde(rename = "keyId", default)]
    pub key_id: Option<String>,
}

/// What the account is actually entitled to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    /// Owns Minecraft: Java Edition outright.
    JavaEdition,
    /// Has Java Edition access through Game Pass.
    JavaEditionViaGamePass,
    /// Owns other Minecraft products, but not Java Edition. Worth
    /// distinguishing because it is a genuinely confusing situation for users.
    OtherProductsOnly { products: Vec<String> },
    /// Nothing.
    Nothing,
}

impl Ownership {
    /// The only question the launcher actually asks.
    pub fn may_launch(&self) -> bool {
        matches!(
            self,
            Ownership::JavaEdition | Ownership::JavaEditionViaGamePass
        )
    }

    /// What to tell the user when they may not launch.
    pub fn explanation(&self) -> String {
        match self {
            Ownership::JavaEdition => "Minecraft: Java Edition entitlement confirmed.".to_owned(),
            Ownership::JavaEditionViaGamePass => {
                "Java Edition access confirmed through Game Pass.".to_owned()
            }
            Ownership::OtherProductsOnly { products } => format!(
                "This account owns {} but not Minecraft: Java Edition. \
                 Java Edition is a separate purchase from Bedrock, Dungeons and Legends.",
                products.join(", ")
            ),
            Ownership::Nothing => {
                "This Microsoft account has no Minecraft entitlement. If you bought the game on a \
                 different account, sign in with that one."
                    .to_owned()
            }
        }
    }
}

const JAVA_EDITION_MARKERS: &[&str] = &["game_minecraft", "product_minecraft"];
const GAME_PASS_MARKERS: &[&str] = &["product_game_pass_pc", "product_game_pass_ultimate"];

/// Map a verified entitlement list to a launch decision.
pub fn decide(names: &[&str]) -> Ownership {
    if names.iter().any(|n| JAVA_EDITION_MARKERS.contains(n)) {
        return Ownership::JavaEdition;
    }
    if names.iter().any(|n| GAME_PASS_MARKERS.contains(n)) {
        return Ownership::JavaEditionViaGamePass;
    }

    let mut products: Vec<String> = names
        .iter()
        .filter(|n| n.starts_with("product_"))
        .filter_map(|n| friendly_product_name(n).map(str::to_owned))
        .collect();
    products.sort_unstable();
    products.dedup();

    if products.is_empty() {
        Ownership::Nothing
    } else {
        Ownership::OtherProductsOnly { products }
    }
}

fn friendly_product_name(raw: &str) -> Option<&'static str> {
    match raw {
        "product_minecraft_bedrock" => Some("Minecraft: Bedrock Edition"),
        "product_dungeons" => Some("Minecraft Dungeons"),
        "product_legends" => Some("Minecraft Legends"),
        _ => None,
    }
}

/// Verify the signature on an entitlements response and return the claims.
///
/// Fails closed: any problem at all means we do not launch.
pub fn verify_signed_entitlements(
    response: &EntitlementsResponse,
) -> Result<EntitlementClaims, EntitlementError> {
    let token = response
        .signature
        .as_deref()
        .ok_or(EntitlementError::Unsigned)?;
    let key = DecodingKey::from_rsa_pem(MOJANG_ENTITLEMENT_PUBLIC_KEY_PEM.as_bytes())
        .map_err(|e| EntitlementError::BadKey(e.to_string()))?;
    verify_with(token, &key, &mojang_validation())
}

/// The validation profile applied to Mojang's token.
fn mojang_validation() -> Validation {
    let mut v = Validation::new(Algorithm::RS256);
    // The token carries no `aud`, so requiring one would reject every valid
    // response.
    v.validate_aud = false;
    // Both bounds matter: `exp` stops replay of an old entitlement, `nbf` stops
    // a token minted for the future.
    v.validate_exp = true;
    v.validate_nbf = true;
    v.set_required_spec_claims(&["exp", "nbf"]);
    v
}

/// Signature verification with an injectable key and validation profile.
///
/// Splitting this out is what makes the gate testable: the tests exercise the
/// real code path with a key they control, rather than mocking it away.
pub fn verify_with(
    token: &str,
    key: &DecodingKey,
    validation: &Validation,
) -> Result<EntitlementClaims, EntitlementError> {
    jsonwebtoken::decode::<EntitlementClaims>(token, key, validation)
        .map(|data| data.claims)
        .map_err(|e| EntitlementError::BadSignature(e.to_string()))
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
    use jsonwebtoken::{encode, EncodingKey, Header};
    use serde::Serialize;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn now() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs())
    }

    #[derive(Serialize)]
    struct TestClaims {
        entitlements: Vec<TestItem>,
        #[serde(rename = "signerId")]
        signer_id: String,
        nbf: u64,
        exp: u64,
    }

    #[derive(Serialize)]
    struct TestItem {
        name: String,
    }

    /// HS256 stands in for Mojang's RS256 so the tests can own the key. The
    /// code path under test — decode, signature check, exp/nbf check, claim
    /// extraction — is identical.
    fn hs_validation() -> Validation {
        let mut v = Validation::new(Algorithm::HS256);
        v.validate_aud = false;
        v.validate_exp = true;
        v.validate_nbf = true;
        v.set_required_spec_claims(&["exp", "nbf"]);
        v
    }

    fn signed(names: &[&str], nbf: u64, exp: u64, key: &[u8]) -> String {
        let claims = TestClaims {
            entitlements: names
                .iter()
                .map(|n| TestItem {
                    name: (*n).to_owned(),
                })
                .collect(),
            signer_id: "2535436228373764".to_owned(),
            nbf,
            exp,
        };
        encode(
            &Header::new(Algorithm::HS256),
            &claims,
            &EncodingKey::from_secret(key),
        )
        .expect("test token should encode")
    }

    #[test]
    fn a_correctly_signed_token_yields_its_claims() {
        let key = b"correct-key";
        let token = signed(
            &["game_minecraft", "product_minecraft"],
            now() - 10,
            now() + 3600,
            key,
        );
        let claims = verify_with(&token, &DecodingKey::from_secret(key), &hs_validation()).unwrap();
        assert_eq!(claims.names(), vec!["game_minecraft", "product_minecraft"]);
        assert_eq!(claims.signer_id.as_deref(), Some("2535436228373764"));
    }

    #[test]
    fn a_token_signed_with_the_wrong_key_is_refused() {
        let token = signed(
            &["game_minecraft"],
            now() - 10,
            now() + 3600,
            b"attacker-key",
        );
        let err = verify_with(
            &token,
            &DecodingKey::from_secret(b"correct-key"),
            &hs_validation(),
        )
        .expect_err("a forged signature must be refused");
        assert!(matches!(err, EntitlementError::BadSignature(_)));
    }

    #[test]
    fn an_expired_token_is_refused() {
        let key = b"correct-key";
        // Expired well beyond jsonwebtoken's default 60s leeway.
        let token = signed(&["game_minecraft"], now() - 7200, now() - 3600, key);
        assert!(matches!(
            verify_with(&token, &DecodingKey::from_secret(key), &hs_validation()),
            Err(EntitlementError::BadSignature(_))
        ));
    }

    #[test]
    fn a_not_yet_valid_token_is_refused() {
        let key = b"correct-key";
        let token = signed(&["game_minecraft"], now() + 3600, now() + 7200, key);
        assert!(matches!(
            verify_with(&token, &DecodingKey::from_secret(key), &hs_validation()),
            Err(EntitlementError::BadSignature(_))
        ));
    }

    #[test]
    fn a_tampered_payload_is_refused() {
        let key = b"correct-key";
        let token = signed(&["product_dungeons"], now() - 10, now() + 3600, key);

        // Swap in a payload claiming Java Edition, keeping the original
        // signature. This is precisely the attack the gate exists to stop.
        let parts: Vec<&str> = token.split('.').collect();
        assert_eq!(parts.len(), 3);
        let forged_payload = {
            use base64::Engine as _;
            let claims = serde_json::json!({
                "entitlements": [{ "name": "game_minecraft" }],
                "signerId": "0",
                "nbf": now() - 10,
                "exp": now() + 3600,
            });
            base64::engine::general_purpose::URL_SAFE_NO_PAD
                .encode(serde_json::to_vec(&claims).unwrap())
        };
        let forged = format!("{}.{}.{}", parts[0], forged_payload, parts[2]);

        assert!(matches!(
            verify_with(&forged, &DecodingKey::from_secret(key), &hs_validation()),
            Err(EntitlementError::BadSignature(_))
        ));
    }

    #[test]
    fn an_unsigned_response_is_refused_outright() {
        let response = EntitlementsResponse {
            items: vec![NamedEntitlement {
                name: "game_minecraft".into(),
            }],
            signature: None,
            key_id: None,
        };
        assert!(matches!(
            verify_signed_entitlements(&response),
            Err(EntitlementError::Unsigned)
        ));
    }

    #[test]
    fn the_compiled_in_mojang_key_parses() {
        // If this fails, every real sign-in fails closed, so it is worth an
        // explicit test rather than discovering it in production.
        assert!(DecodingKey::from_rsa_pem(MOJANG_ENTITLEMENT_PUBLIC_KEY_PEM.as_bytes()).is_ok());
    }

    #[test]
    fn java_edition_entitlements_permit_launch() {
        assert_eq!(decide(&["game_minecraft"]), Ownership::JavaEdition);
        assert_eq!(decide(&["product_minecraft"]), Ownership::JavaEdition);
        assert!(decide(&["game_minecraft", "product_minecraft"]).may_launch());
    }

    #[test]
    fn game_pass_permits_launch_and_is_distinguished() {
        let o = decide(&["product_game_pass_pc"]);
        assert_eq!(o, Ownership::JavaEditionViaGamePass);
        assert!(o.may_launch());
    }

    #[test]
    fn other_products_only_does_not_permit_launch_and_says_why() {
        let o = decide(&[
            "product_minecraft_bedrock",
            "game_minecraft_bedrock",
            "product_dungeons",
        ]);
        assert!(!o.may_launch());
        match &o {
            Ownership::OtherProductsOnly { products } => {
                assert!(products.iter().any(|p| p.contains("Bedrock")));
                assert!(products.iter().any(|p| p.contains("Dungeons")));
            }
            other => panic!("expected OtherProductsOnly, got {other:?}"),
        }
        assert!(o.explanation().contains("not Minecraft: Java Edition"));
    }

    #[test]
    fn an_empty_entitlement_list_does_not_permit_launch() {
        let o = decide(&[]);
        assert_eq!(o, Ownership::Nothing);
        assert!(!o.may_launch());
    }

    #[test]
    fn an_unrecognised_entitlement_does_not_permit_launch() {
        // Fail closed on anything we do not understand.
        let o = decide(&["product_something_new_2027"]);
        assert!(!o.may_launch());
    }
}
