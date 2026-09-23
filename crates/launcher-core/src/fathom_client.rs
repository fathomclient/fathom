//! Is this launch actually Fathom?
//!
//! # The failure this module exists for
//!
//! Pressing Play on a Fathom profile started Minecraft with Legacy Fabric and
//! *no Fathom mod*: the game log said `Loading 4 mods` — the loader, Mixin
//! Extras, Java and Minecraft. Nothing refused. The launcher had looked for the
//! client jar, not found one (a development build has no `resources/` beside
//! it), written "not included in this build" into a report nobody reads, and
//! launched the profile anyway. From the outside that is vanilla Minecraft with
//! a Fathom window in front of it.
//!
//! So a Fathom profile is now checked three times, and each check refuses
//! rather than warns:
//!
//! 1. **Before launch**, [`FathomClient`] decides whether this build can supply
//!    the client module for this profile at all ([`Launcher::fathom_client`]).
//! 2. **After the mods directory is laid out**, the jar must be in `mods/` with
//!    the hash that was installed ([`verify_placed`]).
//! 3. **After the game starts**, the module writes [`MARKER_PATH`] only after
//!    its registries, HUD and public API are installed, naming the jar it was
//!    loaded from. The launcher compares that with what it placed
//!    ([`read_marker`]). No marker in time means the game is not a verified
//!    Fathom session, and the interface says so.
//!
//! A profile that is not a Fathom profile skips all three: Custom and Vanilla
//! profiles are allowed to be vanilla.
//!
//! [`Launcher::fathom_client`]: crate::Launcher::fathom_client

use std::path::{Path, PathBuf};
use std::time::SystemTime;

use crate::components::{BundledJar, JarLookup};
use crate::mods::ProfileMod;
use crate::profiles::{Loader, Profile};

/// Where the module reports that it loaded, relative to the game directory.
/// Must match `ClientMarker.RELATIVE_PATH` on the Java side.
pub const MARKER_PATH: &str = "fathom/client-loaded.json";

/// The mod id Fathom's client module takes in a profile.
pub const MODULE_ID: &str = crate::mods::catalog::FATHOM_OPTIMIZATIONS;

/// What a Fathom profile will launch with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FathomClient {
    /// Custom or Vanilla: no Fathom client is expected.
    NotExpected,
    /// The module this build ships, installed and switched on.
    Bundled { jar: BundledJar, entry: ProfileMod },
    /// A jar the user supplied under the module's id. Launched as chosen.
    UserSupplied { entry: ProfileMod },
    /// The module is switched off. A Fathom-owned profile cannot launch in
    /// this state because it would silently become a vanilla client.
    SwitchedOff { entry: ProfileMod },
    /// The launch must not go ahead. One sentence, suitable for display.
    Unavailable(String),
}

impl FathomClient {
    /// Decide, for a profile whose components have just been brought up to
    /// date, what it will launch with.
    pub fn decide(profile: &Profile, lookup: &JarLookup) -> Self {
        if !profile.owns_components() {
            return FathomClient::NotExpected;
        }
        if profile.loader_kind() != Loader::LegacyFabric {
            return FathomClient::Unavailable(format!(
                "{} is a Fathom profile, and Fathom runs on Legacy Fabric, but this profile is \
                 set to {}. Change its loader back to Legacy Fabric.",
                profile.display_name,
                profile.loader_kind().label()
            ));
        }
        let entry = profile
            .mods
            .iter()
            .find(|entry| entry.id.as_str() == MODULE_ID)
            .cloned();

        if let Some(entry) = &entry {
            if !entry.source.is_launcher_owned() {
                return if entry.enabled {
                    FathomClient::UserSupplied {
                        entry: entry.clone(),
                    }
                } else {
                    FathomClient::SwitchedOff {
                        entry: entry.clone(),
                    }
                };
            }
        }

        let jar = match lookup {
            JarLookup::Found(jar) => jar.clone(),
            other => {
                return FathomClient::Unavailable(
                    other
                        .problem()
                        .unwrap_or_else(|| "Fathom's client module is unavailable.".to_owned()),
                )
            }
        };
        match entry {
            None => FathomClient::Unavailable(format!(
                "Fathom's client module is not installed in {}, and it could not be installed \
                 from {}.",
                profile.display_name,
                jar.path.display()
            )),
            Some(entry) if !entry.enabled => FathomClient::SwitchedOff { entry },
            Some(entry) => FathomClient::Bundled { jar, entry },
        }
    }

    /// Why a profile cannot be played, judged before its components are
    /// brought up to date. Used by readiness, where installing is not allowed:
    /// it only reports what installing could not fix.
    pub fn preflight(profile: &Profile, lookup: &JarLookup) -> Option<String> {
        if !profile.owns_components() {
            return None;
        }
        match Self::decide(profile, lookup) {
            FathomClient::Unavailable(reason) => {
                // "Not installed yet" is fixed by the launch itself, which
                // installs components first. Everything else is not.
                let installable = lookup.jar().is_some()
                    && profile.loader_kind() == Loader::LegacyFabric;
                (!installable).then_some(reason)
            }
            FathomClient::SwitchedOff { .. } => Some(
                "Fathom's client module is switched off for this profile. Enable it before playing."
                    .to_owned(),
            ),
            _ => None,
        }
    }

    /// The installed entry that should reach `mods/`, if one should.
    pub fn expected_entry(&self) -> Option<&ProfileMod> {
        match self {
            FathomClient::Bundled { entry, .. } | FathomClient::UserSupplied { entry } => {
                Some(entry)
            }
            _ => None,
        }
    }

    /// Why the launch must not go ahead, if it must not.
    pub fn refusal(&self) -> Option<&str> {
        match self {
            FathomClient::Unavailable(reason) => Some(reason),
            FathomClient::SwitchedOff { .. } => Some(
                "Fathom's client module is switched off for this profile. Enable it before playing.",
            ),
            _ => None,
        }
    }

    /// One line for a log or a diagnostics page.
    pub fn describe(&self) -> String {
        match self {
            FathomClient::NotExpected => "not expected (not a Fathom profile)".to_owned(),
            FathomClient::Bundled { jar, entry } => format!(
                "{} from {} ({}), sha1 {}",
                entry.file_name,
                jar.origin.label(),
                jar.path.display(),
                short(&entry.sha1)
            ),
            FathomClient::UserSupplied { entry } => format!(
                "{} supplied by you, sha1 {}",
                entry.file_name,
                short(&entry.sha1)
            ),
            FathomClient::SwitchedOff { entry } => {
                format!("{} is switched off for this profile", entry.file_name)
            }
            FathomClient::Unavailable(reason) => format!("UNAVAILABLE: {reason}"),
        }
    }
}

fn short(sha1: &str) -> &str {
    sha1.get(..12).unwrap_or(sha1)
}

/// Confirm the module actually reached `mods/`, with the expected bytes.
///
/// Checked after materialize and before the process starts. A jar recorded in
/// the profile but absent from the directory the loader reads is the precise
/// state that launched vanilla.
pub fn verify_placed(mods_dir: &Path, entry: &ProfileMod) -> Result<(), String> {
    let path = mods_dir.join(&entry.file_name);
    if !path.is_file() {
        return Err(format!(
            "Fathom's client module ({}) was not placed in {}. The game would start without \
             Fathom, so it was not started.",
            entry.file_name,
            mods_dir.display()
        ));
    }
    match crate::install::download::sha1_file(&path) {
        Some(hash) if hash.eq_ignore_ascii_case(&entry.sha1) => Ok(()),
        Some(hash) => Err(format!(
            "The Fathom client in {} is not the one this profile installed (expected sha1 {}, \
             found {}). The game was not started.",
            path.display(),
            short(&entry.sha1),
            short(&hash)
        )),
        None => Err(format!(
            "The Fathom client at {} could not be read.",
            path.display()
        )),
    }
}

/// What the module wrote when it loaded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientMarker {
    pub version: String,
    /// SHA-1 of the jar the module was loaded from, or `development` when it
    /// was loaded from a classes directory.
    pub jar_sha1: String,
    /// The commit the loaded module was built from, with `-dirty` for a
    /// modified tree. Empty for a module built before this field existed.
    ///
    /// The version string cannot distinguish two builds of the same milestone,
    /// and three jars with identical names and versions but different contents
    /// is not hypothetical here -- it is what happened. This is the field that
    /// makes "which build is actually running" answerable from a log.
    pub build: String,
    pub features: u32,
    pub written: SystemTime,
}

/// The outcome of checking a running game for the Fathom client.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientLoad {
    /// Not written yet. The module writes it within seconds of the game
    /// starting; how long to wait is the caller's decision.
    Pending,
    /// Loaded, and it is the jar that was placed.
    Loaded {
        version: String,
        /// The commit the running module was built from. Empty for a module
        /// built before the field existed.
        build: String,
        features: u32,
    },
    /// Loaded, but from different bytes than the launcher placed.
    WrongBuild { expected: String, found: String },
}

/// Remove a marker left by a previous session, before a new one starts.
pub fn clear_marker(game_directory: &Path) {
    let _ = std::fs::remove_file(game_directory.join(MARKER_PATH));
}

/// Read the marker, if one was written since `since`.
pub fn read_marker(game_directory: &Path, since: SystemTime) -> Option<ClientMarker> {
    let path = game_directory.join(MARKER_PATH);
    let meta = std::fs::metadata(&path).ok()?;
    if meta.len() > 16 * 1024 {
        return None;
    }
    let written = meta.modified().ok()?;
    // A marker older than the launch is a previous session's.
    if written + std::time::Duration::from_secs(2) < since {
        return None;
    }
    let value: serde_json::Value = serde_json::from_slice(&std::fs::read(&path).ok()?).ok()?;
    Some(ClientMarker {
        version: value.get("version")?.as_str()?.chars().take(64).collect(),
        jar_sha1: value.get("jarSha1")?.as_str()?.chars().take(64).collect(),
        // Optional: a module built before this field existed still loads, and a
        // launch must not be reported as failed because a diagnostic is absent.
        build: value
            .get("build")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default()
            .chars()
            .take(64)
            .collect(),
        features: value
            .get("features")
            .and_then(serde_json::Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or(0),
        written,
    })
}

/// Compare a running game's marker with what was placed.
pub fn check_loaded(game_directory: &Path, since: SystemTime, expected_sha1: &str) -> ClientLoad {
    match read_marker(game_directory, since) {
        None => ClientLoad::Pending,
        Some(marker)
            if marker.jar_sha1.eq_ignore_ascii_case(expected_sha1)
                || marker.jar_sha1 == "development" =>
        {
            ClientLoad::Loaded {
                version: marker.version,
                build: marker.build,
                features: marker.features,
            }
        }
        Some(marker) => ClientLoad::WrongBuild {
            expected: short(expected_sha1).to_owned(),
            found: short(&marker.jar_sha1).to_owned(),
        },
    }
}

/// The user's own Minecraft directory for this platform, which Fathom never
/// launches into.
pub fn official_minecraft_dir() -> Option<PathBuf> {
    if cfg!(windows) {
        std::env::var_os("APPDATA").map(|appdata| PathBuf::from(appdata).join(".minecraft"))
    } else if cfg!(target_os = "macos") {
        std::env::var_os("HOME")
            .map(|home| PathBuf::from(home).join("Library/Application Support/minecraft"))
    } else {
        std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".minecraft"))
    }
}

/// Whether a game directory is the user's normal Minecraft installation, or
/// inside it.
pub fn is_official_minecraft_dir(game_directory: &Path) -> bool {
    let Some(official) = official_minecraft_dir() else {
        return false;
    };
    let normalise = |path: &Path| {
        std::fs::canonicalize(path)
            .unwrap_or_else(|_| path.to_path_buf())
            .to_string_lossy()
            .to_ascii_lowercase()
    };
    let official = normalise(&official);
    let candidate = normalise(game_directory);
    candidate == official
        || candidate.starts_with(&format!("{official}{}", std::path::MAIN_SEPARATOR))
}

/// Other Fathom data directories a packaged app's virtual file system has
/// created under this user's local application data.
///
/// Found while tracing this very bug: a Fathom started from inside an MSIX
/// packaged application (an editor, an assistant) reads and writes
/// `Packages\<app>\LocalCache\Local\Fathom` instead of the real directory, so it
/// has its own settings, profiles, jars and logs, and nothing it does shows up
/// in the launcher the user starts normally. Worth a line in diagnostics,
/// because nothing else would ever explain it.
pub fn virtualised_data_directories() -> Vec<PathBuf> {
    let Some(local) = std::env::var_os("LOCALAPPDATA") else {
        return Vec::new();
    };
    let Ok(packages) = std::fs::read_dir(PathBuf::from(local).join("Packages")) else {
        return Vec::new();
    };
    packages
        .flatten()
        .map(|entry| {
            entry
                .path()
                .join("LocalCache")
                .join("Local")
                .join(crate::PRODUCT_NAME)
        })
        .filter(|candidate| candidate.is_dir())
        .collect()
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::components::JarOrigin;
    use crate::mods::{ModId, ModSource};
    use crate::profiles::{ProfileId, ProfileKind};

    fn fathom_profile() -> Profile {
        let mut profile = Profile::new(ProfileId::new("fathom").unwrap(), "Fathom");
        profile.kind = ProfileKind::Fathom;
        profile.loader.kind = Loader::LegacyFabric;
        profile
    }

    fn jar() -> JarLookup {
        JarLookup::Found(BundledJar {
            path: PathBuf::from("/build/libs/fathom-optimizations-1.jar"),
            origin: JarOrigin::DevelopmentBuild,
            modified: None,
        })
    }

    fn entry(enabled: bool, source: ModSource) -> ProfileMod {
        let mut entry = ProfileMod::new(
            ModId::new(MODULE_ID).unwrap(),
            "a".repeat(40),
            "fathom-optimizations-1.jar",
        )
        .from_source(source);
        entry.enabled = enabled;
        entry
    }

    #[test]
    fn a_fathom_profile_without_its_client_is_refused_not_launched() {
        let profile = fathom_profile();
        let missing = JarLookup::Missing {
            searched: vec![],
            hint: "not built".to_owned(),
        };
        assert_eq!(
            FathomClient::decide(&profile, &missing).refusal(),
            Some("not built")
        );
        // A jar exists, but the profile has no entry for it.
        assert!(FathomClient::decide(&profile, &jar()).refusal().is_some());
    }

    #[test]
    fn a_stale_development_jar_is_refused() {
        let mut profile = fathom_profile();
        profile.mods.upsert(entry(true, ModSource::FirstParty));
        let stale = JarLookup::Stale {
            jar: BundledJar {
                path: PathBuf::from("/libs/x.jar"),
                origin: JarOrigin::DevelopmentBuild,
                modified: None,
            },
            newest_source: PathBuf::from("/src/main/java/A.java"),
        };
        let refusal = FathomClient::decide(&profile, &stale);
        assert!(refusal.refusal().unwrap().contains("older than its source"));
    }

    #[test]
    fn a_fathom_profile_on_another_loader_is_refused() {
        let mut profile = fathom_profile();
        profile.loader.kind = Loader::Vanilla;
        assert!(FathomClient::decide(&profile, &jar()).refusal().is_some());
    }

    #[test]
    fn installed_switched_off_and_user_supplied_are_told_apart() {
        let mut profile = fathom_profile();
        profile.mods.upsert(entry(true, ModSource::FirstParty));
        assert!(matches!(
            FathomClient::decide(&profile, &jar()),
            FathomClient::Bundled { .. }
        ));

        profile.mods.upsert(entry(false, ModSource::FirstParty));
        let switched_off = FathomClient::decide(&profile, &jar());
        assert!(matches!(&switched_off, FathomClient::SwitchedOff { .. }));
        assert!(switched_off.refusal().is_some());
        assert!(FathomClient::preflight(&profile, &jar()).is_some());

        profile.mods.upsert(entry(true, ModSource::UserSupplied));
        let missing = JarLookup::Missing {
            searched: vec![],
            hint: String::new(),
        };
        assert!(matches!(
            FathomClient::decide(&profile, &missing),
            FathomClient::UserSupplied { .. }
        ));
    }

    #[test]
    fn custom_and_vanilla_profiles_expect_nothing() {
        let mut profile = fathom_profile();
        profile.kind = ProfileKind::Custom;
        assert_eq!(
            FathomClient::decide(&profile, &jar()),
            FathomClient::NotExpected
        );
    }

    /// The field that makes two identically-named jars distinguishable.
    ///
    /// Both directions matter: a module that reports its commit must have it
    /// carried through to the launcher's log, and a module built before the
    /// field existed must still verify rather than being read as a failed load.
    #[test]
    fn the_marker_carries_the_build_id_and_tolerates_its_absence() {
        let tmp = tempfile::tempdir().unwrap();
        let since = SystemTime::now() - std::time::Duration::from_secs(1);
        let sha = "abcdef0123456789abcdef0123456789abcdef01";
        std::fs::create_dir_all(tmp.path().join("fathom")).unwrap();

        std::fs::write(
            tmp.path().join(MARKER_PATH),
            format!(
                r#"{{"schema":1,"version":"0.3.0-hud","build":"a967a9fc415a-dirty","jarSha1":"{sha}","features":14}}"#
            ),
        )
        .unwrap();
        assert_eq!(
            check_loaded(tmp.path(), since, sha),
            ClientLoad::Loaded {
                version: "0.3.0-hud".to_owned(),
                build: "a967a9fc415a-dirty".to_owned(),
                features: 14,
            }
        );

        std::fs::write(
            tmp.path().join(MARKER_PATH),
            format!(r#"{{"schema":1,"version":"0.3.0-hud","jarSha1":"{sha}","features":14}}"#),
        )
        .unwrap();
        assert_eq!(
            check_loaded(tmp.path(), since, sha),
            ClientLoad::Loaded {
                version: "0.3.0-hud".to_owned(),
                build: String::new(),
                features: 14,
            }
        );
    }

    #[test]
    fn a_jar_that_did_not_reach_mods_or_is_different_bytes_is_caught() {
        let tmp = tempfile::tempdir().unwrap();
        let mut placed = entry(true, ModSource::FirstParty);
        assert!(verify_placed(tmp.path(), &placed).is_err());

        std::fs::write(tmp.path().join(&placed.file_name), b"PK\x03\x04 bytes").unwrap();
        assert!(verify_placed(tmp.path(), &placed).is_err(), "hash differs");

        placed.sha1 = crate::install::download::sha1_hex(b"PK\x03\x04 bytes");
        assert!(verify_placed(tmp.path(), &placed).is_ok());
    }

    #[test]
    fn the_marker_confirms_the_build_that_was_placed() {
        let tmp = tempfile::tempdir().unwrap();
        let since = SystemTime::now() - std::time::Duration::from_secs(1);
        assert_eq!(check_loaded(tmp.path(), since, "abc"), ClientLoad::Pending);

        std::fs::create_dir_all(tmp.path().join("fathom")).unwrap();
        let write = |sha: &str| {
            std::fs::write(
                tmp.path().join(MARKER_PATH),
                format!(r#"{{"schema":1,"version":"0.3.0-hud","jarSha1":"{sha}","features":14}}"#),
            )
            .unwrap();
        };
        write("abcdef0123456789abcdef0123456789abcdef01");
        assert_eq!(
            check_loaded(
                tmp.path(),
                since,
                "abcdef0123456789abcdef0123456789abcdef01"
            ),
            ClientLoad::Loaded {
                version: "0.3.0-hud".to_owned(),
                // The fixture below predates the field, which is the case a
                // module built by an older build actually produces.
                build: String::new(),
                features: 14
            }
        );
        assert!(matches!(
            check_loaded(
                tmp.path(),
                since,
                "ffffffffffffffffffffffffffffffffffffffff"
            ),
            ClientLoad::WrongBuild { .. }
        ));

        clear_marker(tmp.path());
        assert_eq!(check_loaded(tmp.path(), since, "abc"), ClientLoad::Pending);
    }

    #[test]
    fn the_users_own_minecraft_folder_is_recognised() {
        if let Some(official) = official_minecraft_dir() {
            assert!(is_official_minecraft_dir(&official));
            assert!(is_official_minecraft_dir(&official.join("saves")));
            assert!(!is_official_minecraft_dir(
                &official.with_file_name(".minecraft-fathom")
            ));
        }
    }
}
