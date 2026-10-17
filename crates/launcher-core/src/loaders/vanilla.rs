//! Unmodified Minecraft.
//!
//! Exists so the launch path has no special case. "No loader" is a loader that
//! adds nothing, rather than an `Option<Loader>` that every caller has to
//! remember to handle.

use super::{
    LoaderError, LoaderInstallation, LoaderProvider, LoaderVersion, ModDirectorySemantics,
};
use crate::install::ArtifactCache;
use crate::network::{Feature, HttpClient};
use crate::profiles::Loader;

#[derive(Debug, Clone, Copy, Default)]
pub struct VanillaProvider;

impl LoaderProvider for VanillaProvider {
    fn kind(&self) -> Loader {
        Loader::Vanilla
    }

    fn name(&self) -> &'static str {
        "Vanilla"
    }

    fn is_implemented(&self) -> bool {
        true
    }

    /// No mods. Not "mods are unsupported for now" — there is nothing in an
    /// unmodified client that reads a `mods/` directory, so a jar placed there
    /// is inert. Saying `None` lets the rest of the launcher tell a user that
    /// plainly instead of letting them wonder why nothing happened.
    fn mod_directory(&self) -> ModDirectorySemantics {
        ModDirectorySemantics::None
    }

    fn needs_version(&self) -> bool {
        false
    }

    /// Vanilla has no versions of its own: the Minecraft version *is* the
    /// version. An empty list is the honest answer, not an error.
    fn available_versions(
        &self,
        _minecraft_version: &str,
        _http: &HttpClient,
        _feature: Feature,
    ) -> Result<Vec<LoaderVersion>, LoaderError> {
        Ok(Vec::new())
    }

    fn resolve(
        &self,
        minecraft_version: &str,
        _loader_version: &str,
        _cache: &ArtifactCache,
        _http: &HttpClient,
        _feature: Feature,
    ) -> Result<LoaderInstallation, LoaderError> {
        Ok(LoaderInstallation::none(minecraft_version))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::network::{NetworkJournal, NetworkPolicy};
    use crate::paths::DataPaths;

    fn http() -> HttpClient {
        HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0")
    }

    /// The property that makes vanilla safe to treat as "just another loader":
    /// resolving it contacts nobody.
    #[test]
    fn resolving_vanilla_makes_no_network_request() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        let http = http();
        let cache = ArtifactCache::new(paths);

        let installation = VanillaProvider
            .resolve("1.8.9", "", &cache, &http, Feature::ProfileInstall)
            .expect("vanilla always resolves");

        assert_eq!(http.journal().summary().total, 0);
        assert!(installation.libraries.is_empty());
        assert!(installation.main_class.is_none());
    }

    #[test]
    fn vanilla_needs_no_version_of_its_own() {
        assert!(!VanillaProvider.needs_version());
        let http = http();
        assert!(VanillaProvider
            .available_versions("1.8.9", &http, Feature::ProfileInstall)
            .unwrap()
            .is_empty());
        assert_eq!(http.journal().summary().total, 0);
    }
}
