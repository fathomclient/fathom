//! The session descriptor: what the launcher tells the game about this launch.
//!
//! # Why this is separate from the bridge handshake
//!
//! The handshake in [`crate::bridge`] carries a per-session token, because its
//! job is to let the game drive the launcher. This file carries no secret at
//! all, and that is the point: everything here is a fact the game may know and
//! display — which profile it is, which version, which loader, which preset —
//! and none of it is a capability.
//!
//! Keeping them apart buys three things:
//!
//! 1. **The game can read this without the bridge running.** The bridge is a
//!    listener that can fail to bind; the descriptor is a file that is written
//!    before the process starts. A profile name should not disappear from the
//!    main menu because a port was busy.
//! 2. **A file with no secret in it can be logged, copied into a bug report and
//!    shown in Diagnostics.** The handshake cannot, and the module has to be
//!    careful with it forever.
//! 3. **The CLI gets it too.** `bridge::start` lives in the desktop app's
//!    session handling; this is written by `services::launch`, which is the one
//!    path both front ends share.
//!
//! # What is deliberately absent
//!
//! No access token, no refresh token, no Microsoft identity, no username, no
//! UUID, no session id. There is a test that reads the written bytes back and
//! fails if any of the account fields ever appear.
//!
//! One path outside the game directory is carried, on purpose: `root`, the
//! launcher's own data root. The running game needs it to open the same Fathom
//! Profile collection the launcher edits (see `docs/fathom-profiles.md`). It is
//! the user's own launcher data, not an account, a token or an identity, which
//! is the line this file exists to hold.
//!
//! # Versioning
//!
//! `schema` is the first key and the module checks it. Adding a key is not a
//! schema change — both sides ignore what they do not recognise — so the number
//! moves only when an old reader would misread a new file.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use crate::profiles::{Loader, OptimizationPreset, Profile};

/// Where it goes, relative to the game directory. Must match
/// `LaunchSession.RELATIVE_PATH` on the Java side.
pub const RELATIVE_PATH: &str = "fathom/session.json";

/// Format version. Must match `LaunchSession.SCHEMA`.
pub const SCHEMA: u32 = 1;

/// One launch, as the game is allowed to see it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionDescriptor {
    pub profile_id: String,
    pub profile_name: String,
    /// Minecraft version id, e.g. `1.8.9`.
    pub minecraft: String,
    /// The loader's display name, e.g. `Legacy Fabric`.
    pub loader: String,
    /// The loader's own version, when it has one.
    pub loader_version: Option<String>,
    /// The performance preset, as the wire name the module already knows.
    pub preset: String,
    /// The version of Fathom's own game module installed here, when there is one.
    pub component_version: Option<String>,
    /// This launcher's version.
    pub launcher_version: String,
    /// The launcher's data root, where the shared Fathom Profile collection is.
    pub root: Option<String>,
}

impl SessionDescriptor {
    /// Read a profile.
    ///
    /// `component_version` is passed in rather than looked up: the profile
    /// records which components it wants, and what is actually installed is a
    /// question for the component store, which this module has no business
    /// reaching into.
    pub fn from_profile(
        profile: &Profile,
        component_version: Option<String>,
        launcher_version: &str,
    ) -> Self {
        Self {
            profile_id: profile.id.as_str().to_owned(),
            profile_name: profile.display_name.clone(),
            minecraft: profile.version_id.clone(),
            loader: loader_name(profile.loader_kind()),
            loader_version: profile.loader.version.clone(),
            preset: preset_key(profile.optimizations.preset).to_owned(),
            component_version,
            launcher_version: launcher_version.to_owned(),
            root: None,
        }
    }

    /// Say where the launcher's data root is, so the game opens the same profile
    /// collection. A builder rather than another argument to
    /// [`Self::from_profile`], because only the real launch path knows it.
    #[must_use]
    pub fn with_root(mut self, root: &Path) -> Self {
        self.root = Some(root.to_string_lossy().into_owned());
        self
    }

    /// The document.
    ///
    /// Hand-written rather than serialised through `serde_json`, for the same
    /// reason the handshake is: the Java side parses it with a reader that
    /// handles exactly this shape, and a formatter that can only emit this
    /// shape is the cheapest way to keep the two in step.
    pub fn to_json(&self) -> String {
        let mut out = String::new();
        let _ = write!(
            out,
            "{{\n  \"schema\": {SCHEMA},\n  \"profile\": \"{}\",\n  \"profileName\": \"{}\",\n  \
             \"minecraft\": \"{}\",\n  \"loader\": \"{}\",\n  \"loaderVersion\": \"{}\",\n  \
             \"preset\": \"{}\",\n  \"component\": \"{}\",\n  \"launcher\": \"{}\",\n  \"root\": \"{}\"\n}}\n",
            escape(&self.profile_id),
            escape(&self.profile_name),
            escape(&self.minecraft),
            escape(&self.loader),
            escape(self.loader_version.as_deref().unwrap_or_default()),
            escape(&self.preset),
            escape(self.component_version.as_deref().unwrap_or_default()),
            escape(&self.launcher_version),
            escape(self.root.as_deref().unwrap_or_default()),
        );
        out
    }

    /// Write it into a game directory, creating `fathom/` if needed.
    ///
    /// Written whole every launch, so a previous session's profile name can
    /// never survive into this one and be shown on the main menu of a different
    /// profile.
    pub fn write_to(&self, game_directory: &Path) -> std::io::Result<PathBuf> {
        let path = game_directory.join(RELATIVE_PATH);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        crate::fs_safety::atomic_write(&path, self.to_json().as_bytes())
            .map_err(|error| std::io::Error::other(error.to_string()))?;
        Ok(path)
    }

    /// Remove it. Called when the game exits.
    ///
    /// A descriptor left behind would tell the next game started by hand in the
    /// same directory that it was launched by Fathom into a profile, which is a
    /// lie the main menu would then display.
    pub fn remove_from(game_directory: &Path) {
        let _ = std::fs::remove_file(game_directory.join(RELATIVE_PATH));
    }
}

/// The version of Fathom's own module installed in a profile, if any.
///
/// Read from the profile's mod list rather than from the component registry:
/// the registry says which component a Fathom profile *wants*, and this has to
/// answer what is actually in the mods directory about to be launched. Those
/// differ exactly when something failed to install, which is the case worth
/// reporting accurately.
///
/// Falls back to the filename because that is where the version lives —
/// `fathom-optimizations-0.3.0-hud.jar` — and a component installed from a
/// bundled jar has no recorded version of its own.
pub fn component_version(profile: &Profile) -> Option<String> {
    let entry = profile
        .mods
        .enabled()
        .find(|entry| entry.id.as_str() == crate::mods::catalog::FATHOM_OPTIMIZATIONS)?;
    if let Some(version) = entry.version.as_ref() {
        return Some(version.as_str().to_owned());
    }
    let stem = entry
        .file_name
        .strip_suffix(".jar")
        .unwrap_or(&entry.file_name);
    stem.strip_prefix("fathom-optimizations-")
        .filter(|rest| !rest.is_empty())
        .map(str::to_owned)
}

/// The loader name the game shows.
///
/// Not [`Loader::label`]: that says "Forge (compatibility)", which is a
/// launcher's way of describing a choice. On a main menu the line reads
/// "1.8.9 · Legacy Fabric", and a parenthetical belongs in neither half.
fn loader_name(loader: Loader) -> String {
    match loader {
        Loader::Vanilla => "Vanilla",
        Loader::LegacyFabric => "Legacy Fabric",
        Loader::Forge => "Forge",
    }
    .to_owned()
}

/// The same wire names the optimization bridge uses, so the two files agree.
const fn preset_key(preset: OptimizationPreset) -> &'static str {
    match preset {
        OptimizationPreset::Balanced => "balanced",
        OptimizationPreset::Competitive => "competitive",
        OptimizationPreset::MaximumFps => "maximum-fps",
        OptimizationPreset::Custom => "custom",
    }
}

fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            other if (other as u32) < 0x20 => out.push(' '),
            other => out.push(other),
        }
    }
    out
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles::{LoaderConfig, ProfileId};

    fn profile() -> Profile {
        let mut profile = Profile::new(ProfileId::new("bedwars").unwrap(), "Bedwars");
        profile.loader = LoaderConfig {
            kind: Loader::LegacyFabric,
            version: Some("0.19.3".to_owned()),
        };
        profile.optimizations.preset = OptimizationPreset::Competitive;
        profile
    }

    #[test]
    fn a_descriptor_carries_what_the_menu_shows() {
        let descriptor =
            SessionDescriptor::from_profile(&profile(), Some("0.3.0-hud".to_owned()), "1.2.3");
        let json = descriptor.to_json();

        assert!(json.contains("\"schema\": 1"));
        assert!(json.contains("\"profile\": \"bedwars\""));
        assert!(json.contains("\"profileName\": \"Bedwars\""));
        assert!(json.contains("\"minecraft\": \"1.8.9\""));
        assert!(json.contains("\"loader\": \"Legacy Fabric\""));
        assert!(json.contains("\"preset\": \"competitive\""));
        assert!(json.contains("\"component\": \"0.3.0-hud\""));
    }

    /// The rule this module exists to keep. Not a review item: a test.
    #[test]
    fn the_data_root_is_written_so_the_game_opens_the_same_profiles() {
        let directory = tempfile::tempdir().unwrap();
        let root = std::path::Path::new("C:\\Users\\jb\\AppData\\Local\\Fathom\\data");
        let descriptor = SessionDescriptor {
            profile_id: "fathom-1-8-9".to_owned(),
            profile_name: "Fathom 1.8.9".to_owned(),
            minecraft: "1.8.9".to_owned(),
            loader: "Legacy Fabric".to_owned(),
            loader_version: None,
            preset: "balanced".to_owned(),
            component_version: None,
            launcher_version: "0.1.0".to_owned(),
            root: None,
        }
        .with_root(root);
        let path = descriptor.write_to(directory.path()).unwrap();
        let written = std::fs::read_to_string(path).unwrap();
        assert!(written.contains("\"root\""), "{written}");
        // Backslashes are escaped, so the Java reader gets the path back intact.
        assert!(written.contains("C:\\\\Users"), "{written}");
    }

    #[test]
    fn a_descriptor_carries_nothing_about_the_account() {
        let descriptor =
            SessionDescriptor::from_profile(&profile(), Some("0.3.0-hud".to_owned()), "1.2.3");
        let json = descriptor.to_json().to_ascii_lowercase();

        for forbidden in [
            "token",
            "access",
            "refresh",
            "uuid",
            "username",
            "session",
            "microsoft",
            "xbox",
            "secret",
            "auth",
        ] {
            assert!(
                !json.contains(forbidden),
                "the session descriptor must not mention {forbidden}: {json}"
            );
        }
    }

    #[test]
    fn a_name_with_quotes_in_it_does_not_break_the_document() {
        let mut awkward = profile();
        awkward.display_name = "He said \"hi\"\nand left".to_owned();
        let json = SessionDescriptor::from_profile(&awkward, None, "1.2.3").to_json();

        assert!(json.contains("\\\"hi\\\""));
        assert!(!json.contains("hi\"\nand"));
        // One line per key, still: nine keys, including root, plus the braces.
        assert_eq!(json.lines().count(), 12);
    }

    #[test]
    fn writing_replaces_rather_than_accumulates() {
        let directory = tempfile::tempdir().unwrap();
        let first = SessionDescriptor::from_profile(&profile(), None, "1.0.0");
        first.write_to(directory.path()).unwrap();

        let mut second_profile = profile();
        second_profile.display_name = "Duels".to_owned();
        let second = SessionDescriptor::from_profile(&second_profile, None, "1.0.0");
        let path = second.write_to(directory.path()).unwrap();

        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("Duels"));
        assert!(!text.contains("Bedwars"));
    }

    #[test]
    fn the_component_version_comes_from_what_is_actually_installed() {
        use crate::mods::{ModId, ModSource, ProfileMod};

        let mut profile = profile();
        assert_eq!(component_version(&profile), None, "nothing installed, nothing claimed");

        profile.mods.upsert(
            ProfileMod::new(
                ModId::new("fathom-optimizations").unwrap(),
                "0000000000000000000000000000000000000000",
                "fathom-optimizations-0.3.0-hud.jar",
            )
            .from_source(ModSource::FirstParty),
        );
        assert_eq!(component_version(&profile), Some("0.3.0-hud".to_owned()));
    }

    #[test]
    fn removing_it_leaves_nothing_for_the_next_game_to_read() {
        let directory = tempfile::tempdir().unwrap();
        SessionDescriptor::from_profile(&profile(), None, "1.0.0")
            .write_to(directory.path())
            .unwrap();
        SessionDescriptor::remove_from(directory.path());
        assert!(!directory.path().join(RELATIVE_PATH).exists());
        // And again, on a directory that has none: a game that never had one
        // must not produce an error on exit.
        SessionDescriptor::remove_from(directory.path());
    }
}
