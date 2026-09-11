//! Credential storage.
//!
//! Exactly one thing is ever persisted: the **Microsoft refresh token**. Not the
//! password (we never see it), not the Xbox tokens, not the Minecraft access
//! token. `security-model.md` §3.
//!
//! The store is behind a trait so that the auth code can be tested without
//! touching the user's real keychain, and so that a platform with no secret
//! service degrades to something honest rather than to a plaintext file.

use crate::secret::Secret;

#[derive(Debug, thiserror::Error)]
pub enum CredentialError {
    #[error("no credential stored for {0}")]
    NotFound(String),
    #[error("this system has no usable credential store: {0}")]
    Unavailable(String),
    #[error("credential store error: {0}")]
    Backend(String),
}

/// Somewhere to keep a refresh token.
pub trait CredentialStore: std::fmt::Debug + Send + Sync {
    /// Human-readable description for the Account and Privacy screens, e.g.
    /// "Windows Credential Manager".
    fn describe(&self) -> String;

    /// Whether this store actually persists anything across restarts.
    fn is_persistent(&self) -> bool;

    fn store(&self, account: &str, token: &Secret) -> Result<(), CredentialError>;
    fn retrieve(&self, account: &str) -> Result<Secret, CredentialError>;
    fn delete(&self, account: &str) -> Result<(), CredentialError>;
}

/// The service name under which entries are filed. Visible to the user in
/// Credential Manager / Keychain, so it should be recognisable.
const SERVICE: &str = "Fathom Launcher";

/// The real OS-backed store: DPAPI-backed Credential Manager on Windows,
/// Keychain on macOS, Secret Service on Linux.
#[derive(Debug, Default)]
pub struct OsCredentialStore;

impl CredentialStore for OsCredentialStore {
    fn describe(&self) -> String {
        if cfg!(target_os = "windows") {
            "Windows Credential Manager (DPAPI-protected)".to_owned()
        } else if cfg!(target_os = "macos") {
            "macOS Keychain".to_owned()
        } else {
            "Secret Service (GNOME Keyring / KWallet)".to_owned()
        }
    }

    fn is_persistent(&self) -> bool {
        true
    }

    fn store(&self, account: &str, token: &Secret) -> Result<(), CredentialError> {
        let entry = keyring::v1::Entry::new(SERVICE, account)
            .map_err(|e| CredentialError::Unavailable(e.to_string()))?;
        entry
            .set_password(token.expose())
            .map_err(|e| CredentialError::Backend(e.to_string()))
    }

    fn retrieve(&self, account: &str) -> Result<Secret, CredentialError> {
        let entry = keyring::v1::Entry::new(SERVICE, account)
            .map_err(|e| CredentialError::Unavailable(e.to_string()))?;
        match entry.get_password() {
            Ok(value) => Ok(Secret::new(value)),
            Err(keyring::v1::Error::NoEntry) => Err(CredentialError::NotFound(account.to_owned())),
            Err(e) => Err(CredentialError::Backend(e.to_string())),
        }
    }

    fn delete(&self, account: &str) -> Result<(), CredentialError> {
        let entry = keyring::v1::Entry::new(SERVICE, account)
            .map_err(|e| CredentialError::Unavailable(e.to_string()))?;
        match entry.delete_credential() {
            Ok(()) | Err(keyring::v1::Error::NoEntry) => Ok(()),
            Err(e) => Err(CredentialError::Backend(e.to_string())),
        }
    }
}

/// Used when the platform has no secret service.
///
/// It deliberately does **not** fall back to a file. `security-model.md` §3:
/// "Silently degrading to plaintext is how launchers leak tokens." The user is
/// told they will have to sign in again next launch, and that is that.
/// # Why the token is a `Secret` and the `Debug` is hand-written
///
/// The security pass found this holding raw `String` tokens behind a
/// `#[derive(Debug)]`. One `tracing::debug!(store = ?store)`, or one error type
/// that happened to contain the store, and every refresh token in it would have
/// gone to the log in plaintext — which is the exact failure `Secret` exists to
/// make impossible, arrived at by storing something other than a `Secret`.
///
/// Both halves are needed. `Secret` redacts when formatted, and the explicit
/// `Debug` below means a future field cannot reintroduce the problem by being
/// added to a derive.
#[derive(Default)]
pub struct EphemeralCredentialStore {
    inner: std::sync::Mutex<std::collections::BTreeMap<String, Secret>>,
}

impl std::fmt::Debug for EphemeralCredentialStore {
    /// Reports how many entries there are and nothing about any of them. Not
    /// even the account names: `security-model.md` treats the signed-in
    /// identity as private, and a count is all a diagnostic needs.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let count = self
            .inner
            .lock()
            .map_or_else(|poisoned| poisoned.into_inner().len(), |guard| guard.len());
        f.debug_struct("EphemeralCredentialStore")
            .field("entries", &count)
            .finish()
    }
}

impl CredentialStore for EphemeralCredentialStore {
    fn describe(&self) -> String {
        "This session only (no OS credential store available)".to_owned()
    }

    fn is_persistent(&self) -> bool {
        false
    }

    fn store(&self, account: &str, token: &Secret) -> Result<(), CredentialError> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| CredentialError::Backend("lock poisoned".into()))?;
        guard.insert(account.to_owned(), token.clone());
        Ok(())
    }

    fn retrieve(&self, account: &str) -> Result<Secret, CredentialError> {
        let guard = self
            .inner
            .lock()
            .map_err(|_| CredentialError::Backend("lock poisoned".into()))?;
        guard
            .get(account)
            .cloned()
            .ok_or_else(|| CredentialError::NotFound(account.to_owned()))
    }

    fn delete(&self, account: &str) -> Result<(), CredentialError> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| CredentialError::Backend("lock poisoned".into()))?;
        guard.remove(account);
        Ok(())
    }
}

/// Probe the OS store once and fall back if it is unusable.
///
/// The probe writes and deletes a throwaway entry, because "the API exists" and
/// "the API works" are different things on a headless Linux box.
pub fn best_available() -> Box<dyn CredentialStore> {
    let os_store = OsCredentialStore;
    let probe = Secret::new("probe");
    match os_store.store("__probe__", &probe) {
        Ok(()) => {
            let _ = os_store.delete("__probe__");
            Box::new(os_store)
        }
        Err(e) => {
            tracing::warn!(
                error = %e,
                "no usable OS credential store; the session will not persist"
            );
            Box::new(EphemeralCredentialStore::default())
        }
    }
}

#[cfg(test)]
#[allow(clippy::indexing_slicing, clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn ephemeral_store_round_trips() {
        let store = EphemeralCredentialStore::default();
        assert!(matches!(
            store.retrieve("steve"),
            Err(CredentialError::NotFound(_))
        ));

        store.store("steve", &Secret::new("refresh-token")).unwrap();
        assert_eq!(store.retrieve("steve").unwrap().expose(), "refresh-token");

        store.delete("steve").unwrap();
        assert!(matches!(
            store.retrieve("steve"),
            Err(CredentialError::NotFound(_))
        ));
    }

    #[test]
    fn deleting_a_missing_credential_is_not_an_error() {
        let store = EphemeralCredentialStore::default();
        assert!(store.delete("nobody").is_ok());
    }

    #[test]
    fn ephemeral_store_admits_it_is_not_persistent() {
        let store = EphemeralCredentialStore::default();
        assert!(!store.is_persistent());
        assert!(store.describe().contains("session only"));
    }

    /// The security pass found this store holding raw `String` tokens behind a
    /// `#[derive(Debug)]`. One `tracing::debug!(store = ?store)` would have put
    /// every refresh token it held into the log in plaintext.
    #[test]
    fn a_credential_store_never_reveals_a_token_when_formatted() {
        let store = EphemeralCredentialStore::default();
        store
            .store("steve", &Secret::new("REFRESH-TOKEN-VALUE"))
            .unwrap();
        store
            .store("alex", &Secret::new("ANOTHER-TOKEN-VALUE"))
            .unwrap();

        let rendered = format!("{store:?}");
        assert!(
            !rendered.contains("REFRESH-TOKEN-VALUE"),
            "a token reached a Debug rendering: {rendered}"
        );
        assert!(
            !rendered.contains("ANOTHER-TOKEN-VALUE"),
            "a token reached a Debug rendering: {rendered}"
        );
        // Account names are private too: `security-model.md` treats the
        // signed-in identity as something we do not put in a log.
        assert!(
            !rendered.contains("steve"),
            "an account name reached a Debug rendering: {rendered}"
        );
        // And it still says something useful.
        assert!(rendered.contains('2'), "the entry count is worth having");

        // The same guarantee through the trait object, which is how the rest of
        // the program holds a store.
        let boxed: Box<dyn CredentialStore> = Box::new(store);
        let via_trait = format!("{boxed:?}");
        assert!(!via_trait.contains("REFRESH-TOKEN-VALUE"));
    }

    #[test]
    fn accounts_are_isolated_from_each_other() {
        let store = EphemeralCredentialStore::default();
        store.store("a", &Secret::new("token-a")).unwrap();
        store.store("b", &Secret::new("token-b")).unwrap();
        assert_eq!(store.retrieve("a").unwrap().expose(), "token-a");
        assert_eq!(store.retrieve("b").unwrap().expose(), "token-b");
        store.delete("a").unwrap();
        assert!(store.retrieve("a").is_err());
        assert!(store.retrieve("b").is_ok());
    }
}
