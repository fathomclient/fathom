//! Mods.
//!
//! # Mods are untrusted third-party code
//!
//! A mod runs inside the JVM with the game's privileges. It can read the
//! filesystem, open sockets, and do anything the user can. The launcher's job is
//! not to sandbox it — that is not possible for 1.8.9 — but to be careful about
//! *which* bytes end up there and to be honest about what is known.
//!
//! Six rules, each of which is a thing this module does not do:
//!
//! 1. **Verify what we fetch.** A download needs a published hash *and* an
//!    allowlisted source *and* a policy that permits it. Any one missing means
//!    no download. [`ModDefinition::is_launcher_fetchable`].
//! 2. **Validate URLs against source metadata.** A download source is a
//!    [`crate::network::Destination`] and a path, not a URL a caller supplies.
//!    There is no way to express "fetch this from anywhere".
//! 3. **Never execute an installer.** Nothing in this module runs a process.
//!    Forge's installer jar is why [`crate::loaders::forge`] refuses.
//! 4. **Never launch helper executables.** Same.
//! 5. **Prevent path traversal.** Every file name goes through
//!    `ProfileDirs::contain_in_game`; every store hash is validated as forty hex
//!    characters before it becomes a path.
//! 6. **Do not inspect jars by running their code.** We read four bytes to check
//!    a file is an archive at all, and nothing else. We do not load classes, do
//!    not evaluate `build.gradle`, and do not walk archive entries looking for
//!    metadata. What we know about a mod comes from [`catalog`], which is
//!    research someone did, or from the user telling us.
//!
//! Rule 6 costs something: a jar the user supplies is `Unknown` in every field.
//! That is the honest trade. Parsing `fabric.mod.json` out of an archive would
//! be more convenient and would mean trusting an attacker-controlled document to
//! describe itself.
//!
//! # Three types, not one
//!
//! | Type | Answers |
//! |---|---|
//! | [`ModDefinition`] | What is this mod, and what may we do with it? |
//! | [`InstalledMod`] | Which bytes are on disk, and where? |
//! | [`ProfileModConfiguration`] | Which mods does this profile want, and are they on? |
//!
//! The pipeline is `configuration -> resolve -> materialize -> launch`.

pub mod browse;
pub mod catalog;
pub mod config;
pub mod definition;
pub mod history;
pub mod identify;
pub mod jar;
pub mod modrinth;
pub mod optifine;
pub mod resolve;
pub mod store;
pub mod supplied;

use std::path::Path;

use crate::fs_safety::FsError;
use crate::profiles::ProfileDirs;

pub use config::{DisabledReason, ModSource, Origin, ProfileMod, ProfileModConfiguration};
pub use definition::{
    ArtifactHash, Conflict, ConflictKind, Dependency, DownloadSource, License,
    MinecraftCompatibility, ModDefinition, ModId, ModIdError, ModVersion, PrivacyClassification,
    Provenance, RedistributionPolicy,
};
pub use identify::Identified;
pub use modrinth::{Modrinth, ModrinthError};
pub use resolve::{Candidate, NameLookup, Problem, Resolution, ResolvedMod, Severity};
pub use store::{materialize, InstalledMod, Materialization, ModStore, StoredMod};

#[derive(Debug, thiserror::Error)]
pub enum ModError {
    #[error("{path} is not a jar archive")]
    NotAJar { path: String },
    #[error("{path} is {size} bytes, which is far larger than a mod")]
    TooLarge { path: String, size: u64 },
    #[error("{value:?} is not a valid SHA-1, so it cannot name a file")]
    BadHash { value: String },
    #[error(
        "the file that arrived is not the one that was published: expected {expected}, got          {actual}. Nothing was saved."
    )]
    HashMismatch { expected: String, actual: String },
    #[error("{name:?} is not a safe file name")]
    UnsafeName { name: String },
    #[error(transparent)]
    Id(#[from] ModIdError),
    #[error(transparent)]
    Fs(#[from] FsError),
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("could not parse {path}: {source}")]
    Malformed {
        path: String,
        #[source]
        source: serde_json::Error,
    },
    #[error(
        "{name} will not be downloaded by this launcher: {reason} You can add the file \
         yourself once you have it."
    )]
    RefusedByPolicy { name: String, reason: String },
}

/// Everything needed to decide what a profile will actually load.
#[derive(Debug, Clone)]
pub struct ModEngine {
    store: ModStore,
}

impl ModEngine {
    pub fn new(paths: crate::paths::DataPaths) -> Self {
        Self {
            store: ModStore::new(paths),
        }
    }

    pub fn store(&self) -> &ModStore {
        &self.store
    }

    /// Add a jar the user chose to a profile.
    ///
    /// The file is copied into the content-addressed store and recorded in the
    /// profile's configuration. The user's original is untouched.
    ///
    /// The id is derived from the file name rather than read out of the jar,
    /// for the reason in the module note: we do not parse attacker-controlled
    /// archives to have them describe themselves.
    pub fn add_user_supplied(
        &self,
        config: &mut ProfileModConfiguration,
        source: &Path,
    ) -> Result<ProfileMod, ModError> {
        let stored = self.store.adopt(source)?;

        let file_name = source
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| ModError::UnsafeName {
                name: source.display().to_string(),
            })?
            .to_owned();

        let identified = identify::from_file_name(&file_name);

        // A recognised file takes the catalogue's id rather than one invented
        // from its filename. That is what connects an imported OptiFine jar to
        // the conflict rules the catalogue records about it -- without it, the
        // launcher would hold OptiFine under the id `optifine_1_8_9_hd_u_m5`
        // and never notice it conflicts with Fathom's own renderer patches.
        let id = match identified.catalogue_id {
            Some(known) => ModId::new(known)?,
            None => id_from_file_name(&file_name)?,
        };

        let mut entry =
            ProfileMod::new(id, &stored.sha1, &file_name).from_source(ModSource::UserSupplied);
        entry.version = identified.version.as_deref().map(ModVersion::new);
        config.upsert(entry.clone());
        Ok(entry)
    }

    /// Import a file the user chose, and say what it turned out to be.
    ///
    /// The same work as [`Self::add_user_supplied`] plus the reading of the
    /// filename, returned so the interface can say "`OptiFine` HD U M5" instead
    /// of echoing a filename back. Nothing is uploaded, mirrored or reported
    /// anywhere: the file is copied into the local content-addressed store and
    /// the profile records its hash.
    pub fn import(
        &self,
        config: &mut ProfileModConfiguration,
        source: &Path,
    ) -> Result<(ProfileMod, identify::Identified), ModError> {
        let entry = self.add_user_supplied(config, source)?;
        let identified = identify::from_file_name(&entry.file_name);
        Ok((entry, identified))
    }

    /// Refuse to download something policy forbids.
    ///
    /// The one entry point for launcher-initiated downloads, and it currently
    /// only ever refuses: no mod in the catalogue is both permitted and has a
    /// verified source. That is a true statement about the research rather than
    /// a missing feature, and it is better expressed as a function that says no
    /// than as an absent function.
    pub fn fetch(&self, definition: &ModDefinition) -> Result<StoredMod, ModError> {
        if !definition.is_launcher_fetchable() {
            return Err(ModError::RefusedByPolicy {
                name: definition.name.clone(),
                reason: definition
                    .why_not_fetchable()
                    .unwrap_or_else(|| "no reason recorded".to_owned()),
            });
        }
        // Reachable only once a catalogue entry has a permitted policy, an
        // allowlisted source and a published hash. Nothing satisfies all three
        // today; when something does, this is where the download goes, and the
        // three conditions above are already enforced.
        Err(ModError::RefusedByPolicy {
            name: definition.name.clone(),
            reason: "downloading mods is not implemented yet.".to_owned(),
        })
    }

    /// Turn a profile's configuration into the candidate set the resolver takes.
    ///
    /// Definitions come from the catalogue where we have one, and are `None`
    /// otherwise. `None` is not an error: it is what "the user gave us a jar we
    /// know nothing about" looks like.
    pub fn candidates(&self, config: &ProfileModConfiguration) -> Vec<Candidate> {
        config
            .mods
            .iter()
            .map(|entry| Candidate {
                id: entry.id.clone(),
                file_name: entry.file_name.clone(),
                sha1: entry.sha1.clone(),
                enabled: entry.enabled,
                definition: catalog::find(&entry.id),
                recorded: resolve::Recorded::of(&entry.origin),
            })
            .collect()
    }

    /// The whole chain: configuration -> resolution.
    pub fn resolve(
        &self,
        config: &ProfileModConfiguration,
        minecraft_version: &str,
        loader: crate::profiles::Loader,
    ) -> Resolution {
        resolve::resolve(&self.candidates(config), minecraft_version, loader)
    }

    /// ... and on to a mods directory that matches it.
    pub fn materialize(
        &self,
        dirs: &ProfileDirs,
        resolution: &Resolution,
    ) -> Result<Materialization, ModError> {
        materialize(&self.store, dirs, &resolution.effective)
    }

    /// Take jars the user dropped into `mods/` into the configuration.
    ///
    /// Called after materialization, which reports them as `foreign`. Adopting
    /// rather than deleting is the whole point: a file someone put there on
    /// purpose should become a managed mod, not disappear.
    pub fn adopt_foreign(
        &self,
        config: &mut ProfileModConfiguration,
        dirs: &ProfileDirs,
        found: &Materialization,
    ) -> Vec<ProfileMod> {
        let mut adopted = Vec::new();
        for file_name in &found.foreign {
            let Ok(path) = dirs.contain_in_game(&format!("mods/{file_name}")) else {
                continue;
            };
            match self.add_user_supplied(config, &path) {
                Ok(entry) => adopted.push(entry),
                Err(e) => {
                    tracing::warn!(file = %file_name, error = %e, "could not adopt a mod jar");
                }
            }
        }
        adopted
    }
}

/// Derive a mod id from a file name.
///
/// Lowercased, non-alphanumerics collapsed to `-`, version-looking suffixes
/// left alone. Deliberately simple and deliberately not read from inside the
/// jar. Two different files can produce the same id, which the resolver reports
/// as a duplicate rather than silently picking one.
pub fn id_from_file_name(file_name: &str) -> Result<ModId, ModError> {
    let stem = file_name.strip_suffix(".jar").unwrap_or(file_name);

    let mut slug = String::new();
    let mut last_dash = false;
    for c in stem.chars() {
        if c.is_ascii_alphanumeric() {
            slug.push(c.to_ascii_lowercase());
            last_dash = false;
        } else if !last_dash && !slug.is_empty() {
            slug.push('-');
            last_dash = true;
        }
    }
    while slug.ends_with('-') {
        slug.pop();
    }
    slug.truncate(90);
    while slug.ends_with('-') {
        slug.pop();
    }

    if slug.is_empty() {
        return Err(ModError::UnsafeName {
            name: file_name.to_owned(),
        });
    }
    Ok(ModId::new(&slug)?)
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
    fn ids_derived_from_file_names_are_safe_and_predictable() {
        let cases = [
            ("CoolMod-1.2.3.jar", "coolmod-1-2-3"),
            ("some_mod.jar", "some-mod"),
            ("UPPER.jar", "upper"),
            ("with spaces.jar", "with-spaces"),
            ("../../escape.jar", "escape"),
            ("a...b.jar", "a-b"),
        ];
        for (input, expected) in cases {
            assert_eq!(
                id_from_file_name(input).unwrap().as_str(),
                expected,
                "deriving from {input:?}"
            );
        }
    }

    #[test]
    fn a_file_name_with_nothing_usable_is_refused() {
        for bad in ["", ".jar", "---.jar", "\u{6e2c}\u{8a66}.jar"] {
            assert!(id_from_file_name(bad).is_err(), "{bad:?}");
        }
    }

    /// A derived id becomes a map key and is written to a profile file. It must
    /// pass the same validation as one typed in.
    #[test]
    fn a_derived_id_always_passes_mod_id_validation() {
        for name in [
            "Mod.jar",
            "mod-with-a-very-long-name-that-goes-on-and-on-and-on-and-on-and-on-and-on-and-on.jar",
            "1.jar",
        ] {
            let id = id_from_file_name(name).unwrap();
            assert!(ModId::new(id.as_str()).is_ok(), "{name} -> {id}");
        }
    }

    /// The single most important behaviour in this module: nothing the research
    /// forbids can be downloaded, and the refusal explains itself.
    #[test]
    fn the_fetch_path_refuses_everything_the_policy_forbids() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let engine = ModEngine::new(crate::paths::DataPaths::rooted_at(tmp.path()));

        for name in ["optifine", "memoryfix", "mousedelayfix", "patcher"] {
            let definition = catalog::find(&ModId::new(name).unwrap()).expect(name);
            let error = engine.fetch(&definition).expect_err("must refuse");
            let message = error.to_string();
            assert!(message.contains(&definition.name), "{message}");
            assert!(
                message.contains("yourself"),
                "the refusal must say what the user can do: {message}"
            );
        }
    }

    #[test]
    fn candidates_carry_a_definition_only_when_we_have_one() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let engine = ModEngine::new(crate::paths::DataPaths::rooted_at(tmp.path()));

        let mut config = ProfileModConfiguration::default();
        config.upsert(ProfileMod::new(
            ModId::new("optifine").unwrap(),
            "a".repeat(40),
            "OptiFine.jar",
        ));
        config.upsert(ProfileMod::new(
            ModId::new("mystery").unwrap(),
            "b".repeat(40),
            "mystery.jar",
        ));

        let candidates = engine.candidates(&config);
        let known = candidates
            .iter()
            .find(|c| c.id.as_str() == "optifine")
            .unwrap();
        let unknown = candidates
            .iter()
            .find(|c| c.id.as_str() == "mystery")
            .unwrap();

        assert!(known.definition.is_some());
        assert!(unknown.definition.is_none());
    }
}
