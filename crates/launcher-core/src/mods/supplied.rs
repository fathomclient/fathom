//! Taking a jar the user downloaded themselves.
//!
//! # Why this is its own module
//!
//! Because `OptiFine` is the one mod this launcher can never fetch, and making
//! the manual path pleasant is the launcher's entire contribution to it.
//! `mod-policies.md` §2, §4 and §7: proprietary, all rights reserved,
//! redistribution prohibited, **never auto-downloaded, under any
//! circumstance**. There is no version of this flow where the launcher goes and
//! gets the file.
//!
//! So the flow is: the user opens the author's own download page, downloads the
//! jar there, and drops it on the launcher. This module is what happens next,
//! and it is deliberately a separate step from adding it to a profile —
//! accepting a file answers "what is this?", and a profile is a different
//! question the user answers afterwards, having seen the answer.
//!
//! # What happens to the file
//!
//! It is **copied**, into the content-addressed store under `mods/` in the
//! launcher's own data directory. The original is untouched. Nothing is
//! uploaded, mirrored, reported, or sent anywhere: [`accept`] makes no network
//! call, and there is nothing in this module that could.
//!
//! Every accepted file is recorded as [`ModSource::UserSupplied`], which is what
//! the rest of the launcher reads to know it must never replace or redistribute
//! it.
//!
//! # What is checked, and in what order
//!
//! 1. **Integrity** — [`super::jar`] checks the envelope, so an HTML error page
//!    saved as a `.jar` is refused at the window rather than at a crash six
//!    minutes later.
//! 2. **Hash** — SHA-1, which is what names the file in the store. The store is
//!    content-addressed, so the same jar dropped twice is one file.
//! 3. **Identity** — from the filename, never from inside the archive. The
//!    module note in `mods/mod.rs` explains why: a filename is a string, a zip
//!    is a parser.
//!
//! Identification is never a gate. An unrecognised jar is still accepted, still
//! hashed, still stored; it simply carries its filename as its name.

use std::path::Path;

use crate::mods::config::{ModSource, ProfileMod, ProfileModConfiguration};
use crate::mods::definition::{ModId, ModVersion};
use crate::mods::{catalog, identify, jar, ModEngine, ModError};
use crate::profiles::{Loader, Profile};

/// A jar that has been accepted into the store.
///
/// Everything the interface needs to describe it, and the hash the profile will
/// record. Deliberately not a `ProfileMod`: nothing has been added to a profile
/// yet, and a type that looked like it had been would invite a screen to say so.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AcceptedJar {
    /// The id it will be filed under. The catalogue's id when recognised, which
    /// is what connects an imported `OptiFine` jar to the conflict rules the
    /// catalogue records about it.
    pub id: String,
    /// What to call it on screen.
    pub name: String,
    /// The mod's own version, e.g. `HD_U_M5`, when the filename said.
    pub version: Option<String>,
    /// The Minecraft version it is built for, when the filename said.
    pub minecraft: Option<String>,
    pub file_name: String,
    pub sha1: String,
    pub size: u64,
    /// How many entries the archive holds. Shown because it is the one fact the
    /// integrity check produces, and "412 entries" reads as a real file in a
    /// way "verified" does not.
    pub entries: u16,
    /// Always [`ModSource::UserSupplied`]. A field rather than an implication,
    /// because the interface says it out loud and should read it from here.
    pub source: ModSource,
    /// Whether the catalogue recognised it.
    pub recognised: bool,
    /// Whether the identification came from inside the file rather than from
    /// its name.
    ///
    /// The difference is the whole reason this field exists. A filename match
    /// is a guess that is wrong in both directions: rename an OptiFine jar and
    /// it stops being recognised, call any jar `OptiFine_1.8.9_HD_U_M5.jar` and
    /// it starts being. When this is true, the version and Minecraft version
    /// below were read out of the jar's own bytecode and are facts about the
    /// file. The interface says which it got, because "we think this is
    /// OptiFine" and "this is OptiFine" deserve different confidence.
    pub verified: bool,
}

impl AcceptedJar {
    /// The short hash people quote at each other.
    pub fn short_hash(&self) -> String {
        self.sha1.chars().take(12).collect()
    }

    /// A one-line description of what arrived.
    pub fn describe(&self) -> String {
        match (&self.version, &self.minecraft) {
            (Some(version), Some(minecraft)) => {
                format!(
                    "{} {} for Minecraft {}",
                    self.name,
                    tidy(version),
                    minecraft
                )
            }
            (Some(version), None) => format!("{} {}", self.name, tidy(version)),
            _ => self.name.clone(),
        }
    }
}

/// `HD_U_M5` reads as `HD U M5` once it is a label rather than a filename.
fn tidy(version: &str) -> String {
    version.replace('_', " ")
}

/// Accept a jar into the store.
///
/// Verifies, hashes, copies and identifies. Adds nothing to any profile — see
/// [`attach`] for that, which is a separate decision the user makes after
/// reading what this returned.
pub fn accept(engine: &ModEngine, source: &Path) -> Result<AcceptedJar, ModError> {
    let facts = jar::verify(source).map_err(|problem| match problem {
        jar::JarProblem::TooLarge { size } => ModError::TooLarge {
            path: source.display().to_string(),
            size,
        },
        // Every other structural failure is the same answer to the user — this
        // is not a usable jar — and the sentence that says which one is carried
        // in the error's own text.
        other => ModError::NotAJar {
            path: format!("{} ({other})", source.display()),
        },
    })?;

    let file_name = source
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| ModError::UnsafeName {
            name: source.display().to_string(),
        })?
        .to_owned();

    let stored = engine.store().adopt(source)?;

    // Ask the file what it is before asking its name. OptiFine carries its own
    // identity in `Config.class`, so when this succeeds it settles the id, the
    // version and the Minecraft version as facts rather than inferences -- and
    // it is the identification the OptiFabric bridge requirement hangs off, so
    // getting it from the filename would mean a renamed jar silently skipping
    // the bridge check. A failure here is not an error: almost every jar is not
    // OptiFine, and those fall through to the catalogue as before.
    if let Ok(identity) = super::optifine::inspect(source) {
        return Ok(AcceptedJar {
            id: "optifine".to_owned(),
            name: identity.name.clone(),
            version: Some(format!("{}_{}", identity.edition, identity.release)),
            minecraft: Some(identity.minecraft.clone()),
            file_name,
            sha1: stored.sha1,
            size: facts.size,
            entries: facts.entries,
            source: ModSource::UserSupplied,
            recognised: true,
            verified: true,
        });
    }

    let identified = identify::from_file_name(&file_name);

    let id = match identified.catalogue_id {
        Some(known) => ModId::new(known)?,
        None => super::id_from_file_name(&file_name)?,
    };
    let recognised = identified.catalogue_id.is_some();
    let name = identified
        .name
        .clone()
        .or_else(|| catalog::find(&id).map(|definition| definition.name.clone()))
        .unwrap_or_else(|| file_name.clone());

    Ok(AcceptedJar {
        id: id.as_str().to_owned(),
        name,
        version: identified.version,
        minecraft: identified.minecraft,
        file_name,
        sha1: stored.sha1,
        size: facts.size,
        entries: facts.entries,
        source: ModSource::UserSupplied,
        recognised,
        verified: false,
    })
}

/// Whether adding this jar to this profile would leave a bridge missing.
fn needs_bridge(component_id: &str, profile: &Profile) -> bool {
    let Some(component) = crate::components::find(component_id) else {
        return false;
    };
    let Some(bridge) = component.bridge else {
        return false;
    };
    if bridge.loader != profile.loader_kind() {
        return false;
    }
    crate::components::find(bridge.component)
        .is_some_and(|needed| !crate::components::installed_in(needed, profile))
}

/// Record an accepted jar in a profile's configuration.
///
/// The second half of the flow, and separate because the first half is
/// "identify this file" and this is "and put it here". Returns the entry so the
/// caller can show what was added.
pub fn attach(
    config: &mut ProfileModConfiguration,
    accepted: &AcceptedJar,
) -> Result<ProfileMod, ModError> {
    let mut entry = ProfileMod::new(
        ModId::new(&accepted.id)?,
        &accepted.sha1,
        &accepted.file_name,
    )
    .from_source(ModSource::UserSupplied);
    entry.version = accepted.version.as_deref().map(ModVersion::new);
    config.upsert(entry.clone());
    Ok(entry)
}

/// Why a profile can or cannot take this jar.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileFit {
    pub profile_id: String,
    pub profile_name: String,
    /// Whether "Add to profile" should be offered for this one.
    pub compatible: bool,
    /// Already in this profile, at this hash.
    pub already_present: bool,
    /// The reason, when it is not compatible, or a note when it is.
    pub note: String,
    /// Whether this profile would still be missing the bridge this jar needs.
    ///
    /// True only when the jar has a bridge, this profile's loader is the one
    /// that requires it, and the bridge is not already installed here. It is
    /// deliberately not a reason to mark the profile incompatible: adding
    /// OptiFine to a profile and then adding OptiFabric is a perfectly good
    /// order to do things in, and refusing the first step would make the second
    /// unreachable.
    pub needs_bridge: bool,
}

/// Which profiles this jar suits.
///
/// Three questions, in the order that decides the answer:
///
/// 1. **Does the loader read a mods directory at all?** A vanilla profile has
///    nowhere to put a mod. This is the common case for a new install and the
///    reason the list is shown at all rather than assumed.
/// 2. **Does the Minecraft version match?** From the filename when it said, and
///    from the catalogue otherwise. An unknown version is not a refusal: an
///    unrecognised jar suits any profile as far as we can honestly tell.
/// 3. **Does the loader match what the catalogue says the mod needs?**
///    `OptiFine` for 1.8.9 is a Forge-or-standalone mod, so a Legacy Fabric
///    profile needs `OptiFabric` alongside it — which is a warning, not a
///    refusal, because it is a thing people do successfully every day.
pub fn compatible_profiles(accepted: &AcceptedJar, profiles: &[Profile]) -> Vec<ProfileFit> {
    let definition = ModId::new(&accepted.id)
        .ok()
        .and_then(|id| catalog::find(&id));

    profiles
        .iter()
        .map(|profile| {
            let already_present = profile.mods.iter().any(|entry| entry.sha1 == accepted.sha1);

            let (compatible, note) = fit(accepted, definition.as_ref(), profile, already_present);
            ProfileFit {
                profile_id: profile.id.as_str().to_owned(),
                profile_name: profile.display_name.clone(),
                compatible,
                already_present,
                note,
                needs_bridge: needs_bridge(&accepted.id, profile),
            }
        })
        .collect()
}

fn fit(
    accepted: &AcceptedJar,
    definition: Option<&crate::mods::definition::ModDefinition>,
    profile: &Profile,
    already_present: bool,
) -> (bool, String) {
    if already_present {
        return (false, "Already added to this profile".to_owned());
    }
    if !profile.loader_kind().supports_mods() {
        return (
            false,
            "This profile runs unmodified Minecraft, which has no mods directory".to_owned(),
        );
    }

    let wanted = accepted
        .minecraft
        .as_deref()
        .or_else(|| definition.map(|_| "").filter(|version| !version.is_empty()));
    if let Some(wanted) = wanted {
        if wanted != profile.version_id {
            return (
                false,
                format!(
                    "This jar is for Minecraft {wanted}; the profile is {}",
                    profile.version_id
                ),
            );
        }
    }

    if let Some(definition) = definition {
        if !definition.loaders.is_empty() && !definition.loaders.contains(&profile.loader_kind()) {
            // A warning rather than a refusal, and the sentence says what to do
            // about it. Refusing here would be the launcher being confidently
            // wrong about a setup that works.
            let needed = definition
                .loaders
                .iter()
                .map(|loader| loader.label())
                .collect::<Vec<_>>()
                .join(" or ");
            if profile.loader_kind() == Loader::LegacyFabric && accepted.id == "optifine" {
                return (
                    true,
                    "Needs OptiFabric alongside it on Legacy Fabric".to_owned(),
                );
            }
            return (true, format!("Built for {needed}"));
        }
    }

    (true, String::new())
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation
)]
mod tests {
    use super::*;
    use crate::paths::DataPaths;
    use crate::profiles::{LoaderConfig, ProfileId};
    use std::io::Write;

    fn engine() -> (tempfile::TempDir, ModEngine) {
        let directory = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(directory.path());
        (directory, ModEngine::new(paths))
    }

    /// A real OptiFine download, when the machine running the tests has one.
    /// Skipped rather than failed when absent -- OptiFine cannot be committed
    /// to this repository or fetched by CI.
    fn real_optifine() -> Option<std::path::PathBuf> {
        let jar = std::path::PathBuf::from(std::env::var_os("USERPROFILE")?)
            .join("Downloads")
            .join("OptiFine_1.8.9_HD_U_M5.jar");
        jar.exists().then_some(jar)
    }

    /// The defect the bytecode inspector removes. Identification used to come
    /// from the filename, so renaming the file changed what Fathom thought it
    /// was -- and since the OptiFabric bridge requirement hangs off the id, a
    /// renamed OptiFine jar would have skipped the bridge check entirely and
    /// gone on to crash the game.
    #[test]
    fn a_renamed_optifine_jar_is_still_optifine() {
        let Some(real) = real_optifine() else {
            eprintln!("no OptiFine jar available; skipping");
            return;
        };
        let (directory, engine) = engine();
        let disguised = directory.path().join("totally-ordinary-mod.jar");
        std::fs::copy(&real, &disguised).unwrap();

        let accepted = accept(&engine, &disguised).expect("the jar should be accepted");

        assert_eq!(
            accepted.id, "optifine",
            "identified by name rather than content"
        );
        assert_eq!(accepted.minecraft.as_deref(), Some("1.8.9"));
        assert_eq!(accepted.version.as_deref(), Some("HD_U_M5"));
        assert!(accepted.verified, "should be identified from the bytecode");
        assert!(accepted.recognised);
    }

    /// And the other direction, which matters just as much: a jar that merely
    /// has OptiFine's filename must not be treated as OptiFine.
    #[test]
    fn a_jar_named_like_optifine_but_is_not_is_not_verified() {
        let (directory, engine) = engine();
        let impostor = write_jar(directory.path(), "OptiFine_1.8.9_HD_U_M5.jar");

        let accepted = accept(&engine, &impostor).expect("still a usable jar");

        assert!(
            !accepted.verified,
            "a filename match must never count as verification"
        );
    }

    fn write_jar(directory: &Path, name: &str) -> std::path::PathBuf {
        let path = directory.join(name);
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&[0x50, 0x4B, 0x03, 0x04]);
        bytes.extend_from_slice(&[0u8; 60]);
        let offset = bytes.len() as u32;
        bytes.extend_from_slice(&[0u8; 40]);
        bytes.extend_from_slice(&[0x50, 0x4B, 0x05, 0x06]);
        bytes.extend_from_slice(&0u16.to_le_bytes());
        bytes.extend_from_slice(&0u16.to_le_bytes());
        bytes.extend_from_slice(&7u16.to_le_bytes());
        bytes.extend_from_slice(&7u16.to_le_bytes());
        bytes.extend_from_slice(&40u32.to_le_bytes());
        bytes.extend_from_slice(&offset.to_le_bytes());
        bytes.extend_from_slice(&0u16.to_le_bytes());
        let mut file = std::fs::File::create(&path).unwrap();
        file.write_all(&bytes).unwrap();
        path
    }

    fn profile(id: &str, name: &str, loader: Loader, version: &str) -> Profile {
        let mut profile = Profile::new(ProfileId::new(id).unwrap(), name);
        profile.version_id = version.to_owned();
        profile.loader = LoaderConfig {
            kind: loader,
            version: (loader != Loader::Vanilla).then(|| "1".to_owned()),
        };
        profile
    }

    #[test]
    fn an_optifine_jar_is_recognised_and_filed_under_the_catalogue_id() {
        let (home, engine) = engine();
        let source = write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar");

        let accepted = accept(&engine, &source).unwrap();
        assert_eq!(accepted.id, "optifine");
        assert_eq!(accepted.name, "OptiFine");
        assert_eq!(accepted.minecraft.as_deref(), Some("1.8.9"));
        assert!(accepted.recognised);
        assert_eq!(accepted.source, ModSource::UserSupplied);
        assert_eq!(accepted.entries, 7);
        assert_eq!(accepted.describe(), "OptiFine HD U M5 for Minecraft 1.8.9");
    }

    #[test]
    fn the_users_own_file_is_left_where_it_was() {
        let (home, engine) = engine();
        let source = write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar");
        accept(&engine, &source).unwrap();
        assert!(source.exists(), "the original must not be moved or deleted");
    }

    #[test]
    fn something_that_is_not_a_jar_is_refused_before_it_is_stored() {
        let (home, engine) = engine();
        let path = home.path().join("OptiFine_1.8.9_HD_U_M5.jar");
        std::fs::write(&path, b"<html>404</html>").unwrap();

        let problem = accept(&engine, &path).unwrap_err();
        assert!(matches!(problem, ModError::NotAJar { .. }));
        assert_eq!(
            engine.store().contents().len(),
            0,
            "nothing should have been copied into the store"
        );
    }

    #[test]
    fn an_unrecognised_jar_is_still_accepted() {
        let (home, engine) = engine();
        let source = write_jar(home.path(), "some-persons-mod-1.2.jar");
        let accepted = accept(&engine, &source).unwrap();
        assert!(!accepted.recognised);
        assert_eq!(accepted.source, ModSource::UserSupplied);
    }

    #[test]
    fn attaching_records_it_as_user_supplied() {
        let (home, engine) = engine();
        let source = write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar");
        let accepted = accept(&engine, &source).unwrap();

        let mut config = ProfileModConfiguration::default();
        let entry = attach(&mut config, &accepted).unwrap();
        assert_eq!(entry.source, ModSource::UserSupplied);
        assert_eq!(entry.sha1, accepted.sha1);
        assert_eq!(config.iter().count(), 1);
    }

    #[test]
    fn a_vanilla_profile_is_offered_with_the_reason_it_cannot_take_it() {
        let (home, engine) = engine();
        let accepted = accept(
            &engine,
            &write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar"),
        )
        .unwrap();

        let fits = compatible_profiles(
            &accepted,
            &[profile("plain", "Plain", Loader::Vanilla, "1.8.9")],
        );
        assert!(!fits[0].compatible);
        assert!(fits[0].note.contains("no mods directory"));
    }

    #[test]
    fn a_profile_on_another_version_is_refused_by_version() {
        let (home, engine) = engine();
        let accepted = accept(
            &engine,
            &write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar"),
        )
        .unwrap();

        let fits = compatible_profiles(
            &accepted,
            &[profile("modern", "Modern", Loader::LegacyFabric, "1.12.2")],
        );
        assert!(!fits[0].compatible);
        assert!(fits[0].note.contains("1.8.9"));
    }

    #[test]
    fn a_matching_fabric_profile_is_offered_with_the_optifabric_note() {
        let (home, engine) = engine();
        let accepted = accept(
            &engine,
            &write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar"),
        )
        .unwrap();

        let fits = compatible_profiles(
            &accepted,
            &[profile("bedwars", "Bedwars", Loader::LegacyFabric, "1.8.9")],
        );
        assert!(fits[0].compatible);
        assert!(fits[0].note.contains("OptiFabric"));
    }

    #[test]
    fn a_profile_that_already_has_it_says_so_and_is_not_offered_again() {
        let (home, engine) = engine();
        let accepted = accept(
            &engine,
            &write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar"),
        )
        .unwrap();

        let mut target = profile("bedwars", "Bedwars", Loader::LegacyFabric, "1.8.9");
        attach(&mut target.mods, &accepted).unwrap();

        let fits = compatible_profiles(&accepted, &[target]);
        assert!(fits[0].already_present);
        assert!(!fits[0].compatible);
    }

    #[test]
    fn the_same_jar_twice_is_one_file_in_the_store() {
        let (home, engine) = engine();
        let first = write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5.jar");
        let second = write_jar(home.path(), "OptiFine_1.8.9_HD_U_M5 (1).jar");

        let a = accept(&engine, &first).unwrap();
        let b = accept(&engine, &second).unwrap();
        assert_eq!(a.sha1, b.sha1);
        assert_eq!(engine.store().contents().len(), 1);
    }
}
