//! Forge 1.8.9 — described, not implemented.
//!
//! This file exists so that adding Forge later is *filling in `resolve`* rather
//! than reworking the launch path around a third case. Everything Forge needs
//! that differs from Legacy Fabric is written down here, in the type system
//! where possible and in prose where not.
//!
//! # What Forge needs that Legacy Fabric does not
//!
//! 1. **A tweaker, not just a main class.** Forge 1.8.9 launches through
//!    `net.minecraft.launchwrapper.Launch` with
//!    `--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker` appended
//!    to the *game* arguments. That is why [`super::LoaderInstallation`] carries
//!    `game_arguments` as well as `main_class`.
//! 2. **Coremods.** Forge loads some jars earlier and by different rules, which
//!    is why [`super::ModDirectorySemantics`] has a
//!    `FlatDirectoryWithCoremods` variant rather than a bare boolean.
//! 3. **An installer that we must not run.** Forge distributes an installer jar
//!    whose job is to execute code and write files. `THREAT_MODEL.md` and
//!    `mod-policies.md` both rule that out: the launcher never executes a
//!    third-party installer. A Forge implementation has to read the version
//!    JSON out of the installer archive and resolve the artifacts itself, the
//!    way it does for Mojang metadata.
//! 4. **Legal review.** `mod-policies.md` §2 records Forge as "LGPL-2.1 **plus
//!    additional terms**; GitHub reports `NOASSERTION`... Needs legal review."
//!    That review has not happened, and shipping a downloader for something
//!    whose terms we have not read would be the exact behaviour the mod policy
//!    exists to prevent.
//! 5. **Mixin version skew.** `mod-policies.md` §8 names this as a classic
//!    1.8.9 failure mode and as a reason Legacy Fabric is the primary target.
//!
//! Point 4 is the blocking one. The rest is work; that one is a decision.

use super::{
    LoaderError, LoaderInstallation, LoaderProvider, LoaderVersion, ModDirectorySemantics,
};
use crate::install::ArtifactCache;
use crate::network::{Feature, HttpClient};
use crate::profiles::Loader;

#[derive(Debug, Clone, Copy, Default)]
pub struct ForgeProvider;

/// Why Forge is not available, in one sentence a user can act on.
const WHY: &str =
    "Forge for 1.8.9 is distributed as an installer jar, and this launcher never runs a \
     third-party installer. Its licensing also needs review before we download it on your \
     behalf. Legacy Fabric is the supported loader.";

impl LoaderProvider for ForgeProvider {
    fn kind(&self) -> Loader {
        Loader::Forge
    }

    fn name(&self) -> &'static str {
        "Forge"
    }

    fn is_implemented(&self) -> bool {
        false
    }

    /// Described accurately even though it cannot be installed. A profile that
    /// records Forge and a list of mods is not nonsense — it is a profile
    /// waiting for a loader — and validation should say that rather than
    /// claiming the mods are pointless.
    fn mod_directory(&self) -> ModDirectorySemantics {
        ModDirectorySemantics::FlatDirectoryWithCoremods
    }

    /// Refuses rather than returning an empty list.
    ///
    /// An empty list would read as "Forge has no versions for 1.8.9", which is
    /// false and would send someone looking for the wrong problem.
    fn available_versions(
        &self,
        _minecraft_version: &str,
        _http: &HttpClient,
        _feature: Feature,
    ) -> Result<Vec<LoaderVersion>, LoaderError> {
        Err(LoaderError::NotImplemented {
            loader: "Forge",
            detail: WHY,
        })
    }

    fn resolve(
        &self,
        _minecraft_version: &str,
        _loader_version: &str,
        _cache: &ArtifactCache,
        _http: &HttpClient,
        _feature: Feature,
    ) -> Result<LoaderInstallation, LoaderError> {
        Err(LoaderError::NotImplemented {
            loader: "Forge",
            detail: WHY,
        })
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::network::{NetworkJournal, NetworkPolicy};
    use crate::paths::DataPaths;

    /// Refusing must not be a network request, and must not be a panic. Both
    /// would be ways of "not implementing" Forge that break something else.
    #[test]
    fn forge_refuses_cleanly_and_contacts_nobody() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let cache = ArtifactCache::new(DataPaths::rooted_at(tmp.path()));
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0");

        let versions = ForgeProvider.available_versions("1.8.9", &http, Feature::ProfileInstall);
        assert!(matches!(
            versions,
            Err(LoaderError::NotImplemented {
                loader: "Forge",
                ..
            })
        ));

        let resolved = ForgeProvider.resolve(
            "1.8.9",
            "11.15.1.2318",
            &cache,
            &http,
            Feature::ProfileInstall,
        );
        assert!(matches!(
            resolved,
            Err(LoaderError::NotImplemented {
                loader: "Forge",
                ..
            })
        ));

        assert_eq!(http.journal().summary().total, 0);
    }

    /// The refusal has to say why and what to do instead, because "not
    /// implemented" on its own tells a user nothing.
    #[test]
    fn the_refusal_names_the_reason_and_the_alternative() {
        let message = ForgeProvider
            .resolve(
                "1.8.9",
                "11.15.1.2318",
                &ArtifactCache::new(DataPaths::rooted_at(
                    tempfile::tempdir().expect("tempdir").path(),
                )),
                &HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0"),
                Feature::ProfileInstall,
            )
            .expect_err("must refuse")
            .to_string();

        assert!(message.contains("installer"), "{message}");
        assert!(message.contains("Legacy Fabric"), "{message}");
    }
}
