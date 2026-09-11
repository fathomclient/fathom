//! Launcher-wide settings.
//!
//! Small on purpose. Anything that varies per way-of-playing belongs on a
//! [`crate::profiles::Profile`], not here.
//!
//! The privacy-relevant defaults are chosen to match `security-model.md` §5:
//! the update check is the only thing that may happen without the user asking,
//! and it is disableable; managed Java and mod repositories are off until the
//! user turns them on.

use serde::{Deserialize, Serialize};

use crate::fs_safety::{self, FsError};
use crate::network::NetworkPolicy;
use crate::profiles::JavaPolicy;
use crate::paths::DataPaths;
use crate::profiles::ProfileId;

#[derive(Debug, thiserror::Error)]
pub enum SettingsError {
    #[error(transparent)]
    Fs(#[from] FsError),
    #[error("could not parse {path}: {source}")]
    Malformed {
        path: String,
        #[source]
        source: serde_json::Error,
    },
    #[error("i/o error at {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
}

/// Current schema version. Bumped when a migration is needed.
const SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum Appearance {
    #[default]
    System,
    Light,
    Dark,
}

/// When to open the game console.
///
/// The console is a second window showing the running game's own output as it
/// is written. It exists because the interesting half of a Minecraft failure is
/// a stack trace the game prints and then takes with it when it exits: by the
/// time the launcher window is back, whatever went wrong has already scrolled
/// past in a file nobody opened.
///
/// Three states rather than a switch, because "never" and "always" are both
/// wrong most of the time. Always means a second window every session for
/// people who will never read it; never means the one session that needed it is
/// the one session with nothing on screen.
///
/// [`Self::OnProblem`] is the default: nothing appears while the game behaves,
/// and the window is already open, already scrolled to the end, when it does
/// not. "Problem" is [`crate::minecraft_runtime::process::Outcome`]'s judgement -- a non-zero
/// exit or a crash -- not merely the word ERROR appearing in a log, which
/// happens in an entirely healthy session.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum GameConsole {
    /// Never opened. The Logs screen still has the same output.
    Never,
    /// Opened when the game exits badly, and not otherwise.
    #[default]
    OnProblem,
    /// Opened as the game starts.
    Always,
}

impl GameConsole {
    /// The stable string used by settings files and the command bridge.
    ///
    /// Written by hand rather than derived from serde so that the id, the
    /// label and the one-line description live in one table -- the interface
    /// renders all three, and a fourth state added here appears in Settings
    /// without anybody editing Svelte.
    pub const ALL: [(Self, &'static str, &'static str, &'static str); 3] = [
        (
            Self::Never,
            "never",
            "Never",
            "Game output goes to the Logs screen only.",
        ),
        (
            Self::OnProblem,
            "on-problem",
            "On failure",
            "Opens only if the game crashes or exits badly.",
        ),
        (
            Self::Always,
            "always",
            "Always",
            "Opens with the game and stays open.",
        ),
    ];

    pub fn id(self) -> &'static str {
        Self::ALL
            .iter()
            .find(|(value, ..)| *value == self)
            .map_or("on-problem", |(_, id, ..)| *id)
    }

    /// Parse an id from the interface. `None` for anything unrecognised: the
    /// set is fixed, and quietly falling back to a default would turn a drift
    /// between the two sides into a control that silently does nothing.
    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL
            .iter()
            .find(|(_, candidate, ..)| *candidate == id)
            .map(|(value, ..)| *value)
    }

    pub fn label(self) -> &'static str {
        Self::ALL
            .iter()
            .find(|(value, ..)| *value == self)
            .map_or("On failure", |(_, _, label, _)| *label)
    }

    /// Whether the console should be open while the game runs.
    pub fn opens_at_launch(self) -> bool {
        self == Self::Always
    }

    /// Whether a finished session with this verdict should raise the console.
    pub fn opens_on_exit(self, went_wrong: bool) -> bool {
        match self {
            Self::Never => false,
            Self::OnProblem => went_wrong,
            Self::Always => true,
        }
    }
}

/// Privacy switches, mirrored into [`NetworkPolicy`] at runtime.
///
/// Four independent booleans rather than an enum on purpose: they are genuinely
/// orthogonal, and each maps to one checkbox the user can reason about alone.
#[allow(clippy::struct_excessive_bools)]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PrivacySettings {
    /// Master offline switch.
    pub offline_mode: bool,
    /// Whether the launcher may check for its own updates on start.
    pub check_for_updates: bool,
    /// Whether the launcher may download a Java runtime.
    pub managed_java: bool,
    /// Whether mod-repository access is permitted at all.
    pub mod_repositories: bool,
}

impl Default for PrivacySettings {
    fn default() -> Self {
        Self {
            offline_mode: false,
            check_for_updates: true,
            managed_java: false,
            mod_repositories: false,
        }
    }
}

impl PrivacySettings {
    pub fn to_policy(&self) -> NetworkPolicy {
        NetworkPolicy {
            online: !self.offline_mode,
            allow_update_check: self.check_for_updates,
            allow_managed_java: self.managed_java,
            allow_mod_repositories: self.mod_repositories,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    #[serde(default)]
    pub appearance: Appearance,
    #[serde(default)]
    pub privacy: PrivacySettings,
    /// Last profile the user played. Validated on deserialise like any other
    /// [`ProfileId`].
    #[serde(default)]
    pub selected_profile: Option<ProfileId>,
    /// Account UUID whose refresh token is in the credential store. Public
    /// information; the token itself is never here.
    #[serde(default)]
    pub last_account_id: Option<String>,
    #[serde(default)]
    pub last_account_name: Option<String>,
    /// When to open the game console window. See [`GameConsole`].
    #[serde(default)]
    pub game_console: GameConsole,
    /// The Java runtime policy new profiles start with.
    ///
    /// A default, not an override: the profile's own policy is what decides
    /// which runtime a launch uses. This is here because the answer is almost
    /// always the same for everybody on one machine, and asking it once during
    /// the first setup is better than asking it in every setup afterwards.
    ///
    /// It also decides whether the launcher is allowed to reach the Java
    /// vendor at all -- see [`Settings::to_network_policy`].
    #[serde(default)]
    pub java: JavaPolicy,

    /// Write a verbose launcher log.
    ///
    /// Read once at startup by the desktop binary, because the tracing
    /// subscriber's filter is installed before anything else runs. The
    /// interface says so rather than pretending the change is immediate.
    #[serde(default)]
    pub verbose_logging: bool,
}

fn default_schema_version() -> u32 {
    SCHEMA_VERSION
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            appearance: Appearance::default(),
            privacy: PrivacySettings::default(),
            selected_profile: None,
            last_account_id: None,
            last_account_name: None,
            game_console: GameConsole::default(),
            java: JavaPolicy::default(),
            verbose_logging: false,
        }
    }
}

impl Settings {
    /// The network policy these settings imply.
    ///
    /// Almost entirely [`PrivacySettings`], with one join: whether the launcher
    /// may contact the Java vendor follows the **Java runtime policy**, not a
    /// switch of its own. Those were two settings that had to agree, and a user
    /// who chose "Fathom managed" and then could not work out why nothing
    /// downloaded was discovering that they did not.
    ///
    /// `privacy.managed_java` is kept in step whenever the policy is set, so
    /// the Privacy screen's allowlist still shows the truth.
    pub fn to_network_policy(&self) -> NetworkPolicy {
        let mut policy = self.privacy.to_policy();
        policy.allow_managed_java = self.privacy.offline_mode.eq(&false) && self.java.may_install();
        policy
    }

    /// Set the Java policy, keeping the network allowlist in step.
    ///
    /// The one place `privacy.managed_java` is written, so the two cannot
    /// drift.
    pub fn set_java_policy(&mut self, policy: JavaPolicy) {
        self.privacy.managed_java = policy.may_install();
        self.java = policy;
    }

    /// Read settings, falling back to defaults.
    ///
    /// A malformed file yields defaults plus a warning rather than a hard
    /// failure: a corrupt settings file must not make the launcher unusable.
    /// It is not overwritten until the user changes something, so the original
    /// remains available for inspection.
    pub fn load(paths: &DataPaths) -> Self {
        let file = paths.settings_file();
        let Ok(bytes) = std::fs::read(&file) else {
            return Settings::default();
        };
        // A byte-order mark is what a hand-edit in Notepad -- or a PowerShell
        // `Set-Content -Encoding utf8` -- leaves at the front of the file, and
        // serde_json treats one as a syntax error at line 1 column 1. Without
        // this, editing your own settings file silently resets every setting in
        // it. The appearance file has stripped one for exactly this reason
        // since it was written; this one had not.
        let bytes = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(&bytes);
        match serde_json::from_slice::<Settings>(bytes) {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!(
                    path = %file.display(),
                    error = %e,
                    "settings file could not be parsed; using defaults and leaving it in place"
                );
                Settings::default()
            }
        }
    }

    pub fn save(&self, paths: &DataPaths) -> Result<(), SettingsError> {
        let file = paths.settings_file();
        let json = serde_json::to_vec_pretty(self).map_err(|source| SettingsError::Malformed {
            path: file.display().to_string(),
            source,
        })?;
        fs_safety::atomic_write(&file, &json)?;
        Ok(())
    }
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

    fn paths() -> (tempfile::TempDir, DataPaths) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let p = DataPaths::rooted_at(tmp.path());
        p.ensure().unwrap();
        (tmp, p)
    }

    #[test]
    fn defaults_match_the_documented_privacy_posture() {
        let s = Settings::default();
        assert!(!s.privacy.offline_mode);
        // The one thing allowed without asking, and it can be turned off.
        assert!(s.privacy.check_for_updates);
        // Both opt-in.
        assert!(!s.privacy.managed_java);
        assert!(!s.privacy.mod_repositories);
        // No account, no profile, nothing that identifies the machine.
        assert!(s.last_account_id.is_none());
        assert!(s.selected_profile.is_none());
    }

    #[test]
    fn privacy_settings_map_onto_the_network_policy() {
        let privacy = PrivacySettings {
            offline_mode: true,
            check_for_updates: false,
            managed_java: true,
            mod_repositories: true,
        };
        let policy = privacy.to_policy();
        assert!(!policy.online);
        assert!(!policy.allow_update_check);
        assert!(policy.allow_managed_java);
        assert!(policy.allow_mod_repositories);
    }

    #[test]
    fn settings_round_trip() {
        let (_tmp, paths) = paths();
        let s = Settings {
            appearance: Appearance::Dark,
            privacy: PrivacySettings {
                offline_mode: true,
                ..PrivacySettings::default()
            },
            selected_profile: Some(ProfileId::new("main").unwrap()),
            ..Settings::default()
        };
        s.save(&paths).unwrap();

        let loaded = Settings::load(&paths);
        assert_eq!(loaded, s);
    }

    #[test]
    fn a_missing_settings_file_yields_defaults() {
        let (_tmp, paths) = paths();
        assert_eq!(Settings::load(&paths), Settings::default());
    }

    #[test]
    fn a_corrupt_settings_file_yields_defaults_and_is_left_alone() {
        let (_tmp, paths) = paths();
        let file = paths.settings_file();
        std::fs::write(&file, b"{ this is not json").unwrap();

        assert_eq!(Settings::load(&paths), Settings::default());
        // Still there, unmodified, for the user to inspect or fix.
        assert_eq!(std::fs::read(&file).unwrap(), b"{ this is not json");
    }

    #[test]
    fn a_tampered_profile_id_in_settings_is_rejected() {
        let (_tmp, paths) = paths();
        std::fs::write(
            paths.settings_file(),
            br#"{"selected_profile":"../../../etc"}"#,
        )
        .unwrap();
        // The whole document fails to parse, so we fall back to defaults
        // rather than accepting a traversing id.
        assert!(Settings::load(&paths).selected_profile.is_none());

        // And a legitimate id in the same position still loads.
        std::fs::write(paths.settings_file(), br#"{"selected_profile":"main"}"#).unwrap();
        assert_eq!(
            Settings::load(&paths)
                .selected_profile
                .map(|p| p.as_str().to_owned()),
            Some("main".to_owned())
        );
    }

    #[test]
    fn unknown_fields_are_tolerated() {
        let (_tmp, paths) = paths();
        std::fs::write(
            paths.settings_file(),
            br#"{"schema_version":1,"somethingFromTheFuture":true}"#,
        )
        .unwrap();
        let loaded = Settings::load(&paths);
        assert_eq!(loaded.schema_version, 1);
    }

    // -- the game console ---------------------------------------------------

    /// Every state the interface can render must be one `from_id` accepts. If
    /// these drift, a radio button renders and then does nothing when clicked.
    #[test]
    fn every_console_state_round_trips_through_its_id() {
        for (value, id, ..) in GameConsole::ALL {
            assert_eq!(value.id(), id);
            assert_eq!(GameConsole::from_id(id), Some(value));
        }
    }

    #[test]
    fn an_unknown_console_id_is_refused_rather_than_defaulted() {
        assert_eq!(GameConsole::from_id("sometimes"), None);
        assert_eq!(GameConsole::from_id(""), None);
        assert_eq!(GameConsole::from_id("Never"), None);
    }

    /// The whole point of the middle state: silent on a clean exit, present on
    /// a bad one.
    #[test]
    fn on_problem_is_the_only_state_that_depends_on_the_outcome() {
        assert!(!GameConsole::OnProblem.opens_at_launch());
        assert!(GameConsole::OnProblem.opens_on_exit(true));
        assert!(!GameConsole::OnProblem.opens_on_exit(false));

        assert!(GameConsole::Always.opens_at_launch());
        assert!(GameConsole::Always.opens_on_exit(false));

        assert!(!GameConsole::Never.opens_at_launch());
        assert!(!GameConsole::Never.opens_on_exit(true));
    }

    /// A settings file written before this setting existed must load, and must
    /// land on the documented default rather than on whatever serde would pick
    /// for a missing field of a non-`Option` type.
    #[test]
    fn a_settings_file_without_a_console_choice_loads_at_the_default() {
        let (_tmp, paths) = paths();
        std::fs::write(
            paths.settings_file(),
            br#"{"schema_version":1,"verbose_logging":true}"#,
        )
        .unwrap();
        let loaded = Settings::load(&paths);
        assert_eq!(loaded.game_console, GameConsole::OnProblem);
        assert!(loaded.verbose_logging, "the rest of the file still applies");
    }

    #[test]
    fn the_console_choice_survives_a_save_and_load() {
        let (_tmp, paths) = paths();
        let settings = Settings {
            game_console: GameConsole::Always,
            ..Settings::default()
        };
        settings.save(&paths).unwrap();
        assert_eq!(Settings::load(&paths).game_console, GameConsole::Always);
    }

    /// A hand-edited settings file often arrives with a byte-order mark, and
    /// losing every setting because of three invisible bytes is not an
    /// acceptable answer.
    #[test]
    fn a_byte_order_mark_does_not_discard_the_file() {
        let (_tmp, paths) = paths();
        let mut bytes = vec![0xEF, 0xBB, 0xBF];
        bytes.extend_from_slice(br#"{"schema_version":1,"game_console":"always"}"#);
        std::fs::write(paths.settings_file(), bytes).unwrap();

        let loaded = Settings::load(&paths);
        assert_eq!(
            loaded.game_console,
            GameConsole::Always,
            "the file was thrown away over a BOM"
        );
    }

    #[test]
    fn a_file_without_a_mark_still_loads() {
        let (_tmp, paths) = paths();
        std::fs::write(
            paths.settings_file(),
            br#"{"schema_version":1,"game_console":"never"}"#,
        )
        .unwrap();
        assert_eq!(Settings::load(&paths).game_console, GameConsole::Never);
    }
}
