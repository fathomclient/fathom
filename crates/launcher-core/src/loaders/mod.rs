//! Mod loaders.
//!
//! A loader changes four things about a launch: the main class, the classpath,
//! the JVM and game arguments, and whether a `mods/` directory means anything.
//! [`LoaderProvider`] is the seam that lets each loader describe those four
//! things without the launch path knowing which loader it is dealing with.
//!
//! Three providers exist:
//!
//! | Loader | Status |
//! |---|---|
//! | [`vanilla`] | Implemented. Changes nothing; reads no `mods/` directory. |
//! | [`legacy_fabric`] | Implemented, against Legacy Fabric's own metadata service. |
//! | [`forge`] | Described but not implemented, and honest about it. |
//!
//! Forge exists here as a real provider that refuses, rather than as a `todo!()`
//! or a missing arm. That is the point of the abstraction: the shape of what
//! Forge needs — a tweaker class, a different main class, coremod semantics — is
//! written down, so adding it later is filling in `resolve` rather than
//! reworking the launch path around a third case.
//!
//! **We do not scrape.** Legacy Fabric publishes a metadata API and a Maven
//! repository; those are the only places this module looks, and both are on the
//! network allowlist by name.

pub mod forge;
pub mod legacy_fabric;
pub mod vanilla;

use std::path::PathBuf;

use crate::install::download::DownloadRequest;
use crate::network::{Feature, HttpClient};
use crate::profiles::Loader;

pub use forge::ForgeProvider;
pub use legacy_fabric::LegacyFabricProvider;
pub use vanilla::VanillaProvider;

#[derive(Debug, thiserror::Error)]
pub enum LoaderError {
    #[error("{loader} support is not implemented yet: {detail}")]
    NotImplemented {
        loader: &'static str,
        detail: &'static str,
    },
    #[error("{loader} does not support Minecraft {minecraft_version}")]
    UnsupportedMinecraftVersion {
        loader: &'static str,
        minecraft_version: String,
    },
    #[error("{loader} has no version {version} for Minecraft {minecraft_version}")]
    UnknownLoaderVersion {
        loader: &'static str,
        version: String,
        minecraft_version: String,
    },
    #[error("the loader metadata was malformed: {0}")]
    Malformed(String),
    #[error(transparent)]
    Network(#[from] crate::network::NetworkError),
    #[error(transparent)]
    Url(#[from] crate::network::policy::UrlRejection),
    #[error(transparent)]
    Cache(#[from] crate::install::CacheError),
    #[error("a loader library declared no usable hash, and unverified code will not be run")]
    UnverifiableArtifact { name: String },
}

/// What a `mods/` directory means to a loader.
///
/// Vanilla has no such concept; asking it to install a mod is a category error
/// rather than a missing feature, and the type says so.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModDirectorySemantics {
    /// No mods, at all. There is nothing to load them.
    None,
    /// Jars in `mods/` are loaded by the loader. The loader reads the directory
    /// itself; the launcher's job is to put the right files there.
    FlatDirectory,
    /// Jars in `mods/`, plus a separate coremod mechanism that is loaded
    /// earlier and by different rules. Forge's shape.
    FlatDirectoryWithCoremods,
}

impl ModDirectorySemantics {
    pub fn accepts_mods(self) -> bool {
        !matches!(self, ModDirectorySemantics::None)
    }
}

/// One selectable loader version.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoaderVersion {
    pub version: String,
    /// Whether the publisher marks it stable. We do not hide unstable versions,
    /// but we do not default to them either.
    pub stable: bool,
}

/// Everything a loader adds to a launch.
///
/// Deliberately additive: the vanilla launch is computed first, and this is
/// layered on top. A loader that could *replace* parts of the vanilla plan would
/// be much harder to reason about, and none of the three needs to.
///
/// Not `PartialEq`: it carries `DownloadRequest`s, which hold a
/// `RequestDescriptor` and are compared by the fields that matter rather than
/// wholesale. Tests assert on the parts they care about.
#[derive(Debug, Clone)]
pub struct LoaderInstallation {
    pub loader: Loader,
    pub loader_version: String,
    pub minecraft_version: String,

    /// Replaces the vanilla main class. `None` leaves it alone.
    pub main_class: Option<String>,
    /// Libraries the loader needs, prepended to the classpath.
    ///
    /// Order matters: the loader and its mappings have to come before the game
    /// jar, because the whole point is that they intercept class loading.
    pub libraries: Vec<DownloadRequest>,
    /// Extra JVM arguments. Each still goes through `jvm_args::validate_args`.
    pub jvm_arguments: Vec<String>,
    /// Extra game arguments, appended after the vanilla ones.
    pub game_arguments: Vec<String>,
    /// Minimum Java major version this loader needs, if it declares one.
    pub minimum_java_major: Option<u32>,
    /// What `mods/` means once this loader is installed.
    pub mod_directory: ModDirectorySemantics,
}

impl LoaderInstallation {
    /// A loader that adds nothing.
    pub fn none(minecraft_version: &str) -> Self {
        Self {
            loader: Loader::Vanilla,
            loader_version: String::new(),
            minecraft_version: minecraft_version.to_owned(),
            main_class: None,
            libraries: Vec::new(),
            jvm_arguments: Vec::new(),
            game_arguments: Vec::new(),
            minimum_java_major: None,
            mod_directory: ModDirectorySemantics::None,
        }
    }

    /// Total bytes the loader's own artifacts declare.
    pub fn declared_bytes(&self) -> u64 {
        self.libraries.iter().filter_map(|l| l.expect.size).sum()
    }

    /// Where each library lands, in classpath order.
    pub fn classpath_additions(&self) -> Vec<PathBuf> {
        self.libraries.iter().map(|l| l.target.clone()).collect()
    }
}

/// What a loader is, and how to install it.
///
/// Object-safe on purpose: the launcher holds `&dyn LoaderProvider` chosen from
/// a profile's `Loader`, and never matches on the loader kind outside this
/// module.
pub trait LoaderProvider: Send + Sync {
    /// Which loader this provides.
    fn kind(&self) -> Loader;

    /// A short name for errors and logs.
    fn name(&self) -> &'static str;

    /// Whether launching with this loader actually works in this build.
    fn is_implemented(&self) -> bool;

    /// What a `mods/` directory means, before installation. Used to answer
    /// "can this profile have mods at all?" without touching the network.
    fn mod_directory(&self) -> ModDirectorySemantics;

    /// Whether this loader needs a version of its own alongside Minecraft's.
    fn needs_version(&self) -> bool {
        true
    }

    /// Versions available for a Minecraft version, newest first.
    ///
    /// Makes a network request. Only called when a user is choosing.
    fn available_versions(
        &self,
        minecraft_version: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<Vec<LoaderVersion>, LoaderError>;

    /// Work out exactly what installing this loader involves.
    ///
    /// Returns the artifact set with expected hashes, the same shape the
    /// install engine already knows how to fetch and verify. Nothing is
    /// downloaded here.
    fn resolve(
        &self,
        minecraft_version: &str,
        loader_version: &str,
        cache: &crate::install::ArtifactCache,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<LoaderInstallation, LoaderError>;
}

/// The provider for a loader kind.
///
/// A function rather than a map because the set is fixed at compile time and
/// this way an unhandled loader is a compile error.
pub fn provider_for(loader: Loader) -> Box<dyn LoaderProvider> {
    match loader {
        Loader::Vanilla => Box::new(VanillaProvider),
        Loader::LegacyFabric => Box::new(LegacyFabricProvider),
        Loader::Forge => Box::new(ForgeProvider),
    }
}

/// Every provider, for listing what this build can do.
pub fn all_providers() -> Vec<Box<dyn LoaderProvider>> {
    Loader::ALL.into_iter().map(provider_for).collect()
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
    fn every_loader_has_a_provider_that_agrees_with_the_profile_model() {
        for loader in Loader::ALL {
            let provider = provider_for(loader);
            assert_eq!(provider.kind(), loader);
            assert_eq!(
                provider.is_implemented(),
                loader.is_implemented(),
                "{loader:?}: the provider and the profile model must not disagree \
                 about whether a loader works"
            );
            assert_eq!(provider.needs_version(), loader.needs_version());
            assert_eq!(
                provider.mod_directory().accepts_mods(),
                loader.supports_mods(),
                "{loader:?}: mod support must be described the same way in both places"
            );
        }
    }

    #[test]
    fn a_loader_that_adds_nothing_says_so() {
        let none = LoaderInstallation::none("1.8.9");
        assert!(none.main_class.is_none());
        assert!(none.libraries.is_empty());
        assert!(none.classpath_additions().is_empty());
        assert_eq!(none.declared_bytes(), 0);
        assert!(!none.mod_directory.accepts_mods());
    }

    #[test]
    fn only_vanilla_refuses_mods_outright() {
        assert!(!VanillaProvider.mod_directory().accepts_mods());
        assert!(LegacyFabricProvider.mod_directory().accepts_mods());
        assert!(ForgeProvider.mod_directory().accepts_mods());
    }

    /// Forge is described, not implemented, and the difference is visible from
    /// outside rather than being a surprise at launch.
    #[test]
    fn forge_describes_itself_but_refuses_to_resolve() {
        assert!(!ForgeProvider.is_implemented());
        assert_eq!(
            ForgeProvider.mod_directory(),
            ModDirectorySemantics::FlatDirectoryWithCoremods
        );
    }

    #[test]
    fn all_providers_covers_every_loader() {
        assert_eq!(all_providers().len(), Loader::ALL.len());
    }
}
