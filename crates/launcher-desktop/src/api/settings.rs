//! Settings, as the interface sees it.
//!
//! Client settings, not a preferences database. Every row is a short label, one
//! line saying what it does, and a control — and the rows are grouped so that
//! somebody looking for "stop it phoning home" and somebody looking for "give
//! Minecraft more memory" are not reading the same list.
//!
//! Notably absent: anything that would let the frontend construct a launch, and
//! anything derived from a credential. The account row says a name and whether
//! somebody is signed in, which is what an avatar needs.

use serde::Serialize;

use launcher_core::configuration::{GameConsole, Settings};
use launcher_core::profiles::JavaPolicy;
use launcher_core::Launcher;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsView {
    pub account: AccountRow,
    pub toggles: Vec<ToggleRow>,
    /// Settings with more than two states. See [`ChoiceRow`].
    pub choices: Vec<ChoiceRow>,
    pub java: JavaRow,
    pub about: Vec<AboutRow>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountRow {
    pub name: String,
    pub signed_in: bool,
    /// True for the fake local identity, which the interface must never let
    /// pass for a real one.
    pub development: bool,
    pub detail: String,
    /// Where the refresh token lives, in words. Not the token.
    pub credential_store: String,
    /// Whether signing in is possible in this build at all.
    pub can_sign_in: bool,
    /// Whether the fake development identity is compiled in AND enabled by the
    /// environment. False in every release build, which is why the interface
    /// can render the affordance unconditionally and still never show it to
    /// somebody holding a release.
    pub development_available: bool,
}

/// One switch.
///
/// The `id` is what comes back on a change, so the set of ids is the set of
/// things the frontend may alter — there is no generic "write this settings
/// key" command.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToggleRow {
    pub id: &'static str,
    pub label: &'static str,
    /// One line. If it needs two, the setting needs a better name.
    pub detail: &'static str,
    pub value: bool,
    /// Which Settings category this row appears under.
    ///
    /// Lives here rather than in the interface so that the categorisation is
    /// one table rather than two that have to agree. A switch added to
    /// `TOGGLES` appears in the right place without anybody editing Svelte.
    pub section: &'static str,
    /// Set when this switch is forced by another one, so the page can show it
    /// greyed rather than pretending it is live.
    pub overridden_by: Option<&'static str>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRow {
    pub found: bool,
    pub headline: String,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AboutRow {
    pub label: &'static str,
    pub value: String,
}

/// The ids the frontend may set, and what each one is.
///
/// A table rather than a match arm per setting, because this is the whole
/// permitted surface and it should be readable as a list.
pub const TOGGLES: [(&str, &str, &str, &str); 4] = [
    (
        "offline",
        "Offline mode",
        "Blocks every network request, including sign-in and downloads.",
        "network",
    ),
    (
        "updates",
        "Check for updates",
        "Checks once at start for a newer Fathom.",
        "general",
    ),
    (
        "mod-repositories",
        "Allow mod repositories",
        "Lets Fathom contact a mod index. Nothing downloads unless you ask.",
        "network",
    ),
    (
        "verbose-logging",
        "Verbose logging",
        "Records more detail. Takes effect next time Fathom starts.",
        "advanced",
    ),
];

/// One setting that is not a yes/no.
///
/// Kept apart from [`ToggleRow`] rather than folded into a single "setting"
/// type with an optional list of options. A switch and a three-way choice are
/// different controls, they are set by different commands, and a shared type
/// would mean every consumer of either one carrying a branch for the other.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChoiceRow {
    pub id: &'static str,
    pub label: &'static str,
    pub detail: &'static str,
    pub section: &'static str,
    /// The id of the selected option. One of `options`.
    pub value: String,
    pub options: Vec<ChoiceOption>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChoiceOption {
    pub id: &'static str,
    pub label: &'static str,
    /// One line, shown under the chosen option so the consequence of the
    /// selection is readable without selecting it.
    pub detail: &'static str,
}

/// The only multiple-choice setting there is, built from the core's own table.
///
/// The options are not written here: [`GameConsole::ALL`] is the single list,
/// so a fourth state added in the core appears in Settings without anybody
/// editing this file or the Svelte that renders it.
fn console_choice(settings: &Settings) -> ChoiceRow {
    ChoiceRow {
        id: "game-console",
        label: "Game console",
        detail: "A window showing Minecraft's own output while it runs.",
        section: "advanced",
        value: settings.game_console.id().to_owned(),
        options: GameConsole::ALL
            .iter()
            .map(|(_, id, label, detail)| ChoiceOption { id, label, detail })
            .collect(),
    }
}

/// The Java runtime policy, as a choice rather than a switch.
///
/// This replaced *Let Fathom install Java*, which was a switch that greyed
/// itself out whenever Java was already installed -- so the one person who most
/// wanted a runtime the launcher controls was the one person who could not ask
/// for it. See [`JavaPolicy`] for what each answer does.
///
/// `custom` is deliberately absent from the offered options here. It needs a
/// path, a path needs a file picker, and a settings row is the wrong place to
/// open one; a profile that wants a specific executable sets it on the profile.
fn java_choice(settings: &Settings) -> ChoiceRow {
    let policies = [
        JavaPolicy::Automatic,
        JavaPolicy::Managed,
        JavaPolicy::System,
    ];
    ChoiceRow {
        id: "java-runtime",
        label: "Java runtime",
        detail: "Which Java new profiles use.",
        section: "game",
        value: match settings.java {
            // A profile-level custom path has no equivalent here, so the row
            // shows the nearest launcher-wide answer rather than an option that
            // is not in the list.
            JavaPolicy::Custom { .. } => JavaPolicy::System.id().to_owned(),
            ref policy => policy.id().to_owned(),
        },
        options: policies
            .iter()
            .map(|policy| ChoiceOption {
                id: policy.id(),
                label: policy.label(),
                detail: policy.describe(),
            })
            .collect(),
    }
}

pub fn view(core: &Launcher) -> SettingsView {
    let settings = core.settings();
    let privacy = &settings.privacy;
    let identity = core.identity();
    let offline = privacy.offline_mode;

    // Offline mode makes the other three moot. Saying so is better than leaving
    // three live-looking switches that cannot do anything.
    let overridden = |id: &str| -> Option<&'static str> {
        if offline && id != "offline" {
            Some("Offline mode")
        } else {
            None
        }
    };

    let toggles = TOGGLES
        .iter()
        .map(|(id, label, detail, section)| ToggleRow {
            id,
            label,
            detail,
            section,
            value: match *id {
                "offline" => privacy.offline_mode,
                "updates" => privacy.check_for_updates,
                "mod-repositories" => privacy.mod_repositories,
                "verbose-logging" => settings.verbose_logging,
                _ => false,
            },
            overridden_by: overridden(id),
        })
        .collect();

    let account = AccountRow {
        name: identity.as_ref().map_or_else(
            || "Not signed in".to_owned(),
            |identity| identity.profile.name.clone(),
        ),
        signed_in: identity.is_some(),
        development: identity
            .as_ref()
            .is_some_and(|identity| !identity.kind.is_production()),
        detail: match identity.as_ref() {
            Some(identity) if !identity.kind.is_production() => {
                "A local development identity. It cannot join online servers.".to_owned()
            }
            Some(_) => "Signed in with a Microsoft account.".to_owned(),
            None => "A Microsoft account is needed to play online.".to_owned(),
        },
        credential_store: core.credential_store_description(),
        can_sign_in: core.sign_in_available(),
        development_available: core.development_auth_available(),
    };

    SettingsView {
        account,
        toggles,
        choices: vec![java_choice(settings), console_choice(settings)],
        java: java_row(core),
        about: vec![
            AboutRow {
                label: "Version",
                value: launcher_core::PRODUCT_VERSION.to_owned(),
            },
            AboutRow {
                label: "Credentials",
                value: if core.credential_store_is_persistent() {
                    "Stored in the OS credential manager".to_owned()
                } else {
                    "This session only".to_owned()
                },
            },
        ],
    }
}

fn java_row(core: &Launcher) -> JavaRow {
    let installations = core.java_installations();
    let usable = installations.iter().find(|java| java.major == 8);

    match usable {
        Some(java) => JavaRow {
            found: true,
            headline: format!("Java {} found", java.major),
            // The vendor is best-effort: the banner does not always name one,
            // and "Java 8 found" on its own is already the useful part.
            detail: java
                .vendor
                .clone()
                .unwrap_or_else(|| java.version_string.clone()),
        },
        None if installations.is_empty() => JavaRow {
            found: false,
            headline: "No Java found".to_owned(),
            detail: "Minecraft 1.8.9 needs Java 8.".to_owned(),
        },
        None => JavaRow {
            found: false,
            headline: "No Java 8".to_owned(),
            detail: format!(
                "Found {} other {}, none of them Java 8.",
                installations.len(),
                if installations.len() == 1 {
                    "installation"
                } else {
                    "installations"
                }
            ),
        },
    }
}

/// Apply one change, by id.
///
/// Returns the settings to save rather than saving them, so the caller owns the
/// one write. An unknown id is an error rather than a no-op: the set of ids is
/// fixed and generated from `TOGGLES`, so an unrecognised one means the
/// frontend and this table have drifted, and silently ignoring it would hide
/// that until somebody noticed a switch doing nothing.
pub fn apply(settings: &Settings, id: &str, value: bool) -> Result<Settings, String> {
    let mut next = settings.clone();
    match id {
        "offline" => next.privacy.offline_mode = value,
        "updates" => next.privacy.check_for_updates = value,
        "mod-repositories" => next.privacy.mod_repositories = value,
        "verbose-logging" => next.verbose_logging = value,
        other => return Err(format!("unknown setting: {other}")),
    }
    Ok(next)
}

/// Apply one multiple-choice change, by id.
///
/// Same contract as [`apply`]: an unknown id, or an unknown value for a known
/// id, is an error rather than a silent fallback. A control that quietly
/// reverts to a default when the two sides disagree looks exactly like a
/// control that works, right up until somebody checks.
pub fn apply_choice(settings: &Settings, id: &str, value: &str) -> Result<Settings, String> {
    let mut next = settings.clone();
    match id {
        "game-console" => {
            next.game_console = GameConsole::from_id(value)
                .ok_or_else(|| format!("unknown value for {id}: {value}"))?;
        }
        "java-runtime" => {
            let policy = JavaPolicy::from_id(value, None)
                .ok_or_else(|| format!("unknown value for {id}: {value}"))?;
            // Goes through `set_java_policy`, which is the one place the
            // network allowlist for the Java vendor is written. Assigning the
            // field directly would leave a user who chose "Fathom managed"
            // unable to download anything.
            next.set_java_policy(policy);
        }
        other => return Err(format!("unknown setting: {other}")),
    }
    Ok(next)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every switch must land in a category the Settings page actually has, or
    /// it renders nowhere and is unreachable.
    #[test]
    fn every_toggle_has_a_real_category() {
        // Account and Privacy are NOT categories. The account lives in the
        // top-bar drawer and Privacy is a main destination; giving either a
        // Settings category as well is the duplication this structure removes.
        const CATEGORIES: [&str; 6] = [
            "general",
            "game",
            "appearance",
            "network",
            "storage",
            "advanced",
        ];
        for (id, _, _, section) in TOGGLES {
            assert!(
                CATEGORIES.contains(&section),
                "{id} is filed under unknown category {section}"
            );
        }
    }

    /// Every id the page can render must be one `apply` accepts. If these two
    /// lists drift, a switch renders and then does nothing when clicked.
    #[test]
    fn every_rendered_toggle_can_be_applied() {
        let settings = Settings::default();
        for (id, ..) in TOGGLES {
            assert!(
                apply(&settings, id, true).is_ok(),
                "{id} is rendered but cannot be set"
            );
        }
    }

    #[test]
    fn an_unknown_setting_is_refused() {
        assert!(apply(&Settings::default(), "telemetry", true).is_err());
    }

    #[test]
    fn applying_a_setting_changes_only_that_setting() {
        let before = Settings::default();
        let after = apply(&before, "offline", true).expect("a real setting");
        assert!(after.privacy.offline_mode);
        assert_eq!(
            after.privacy.check_for_updates,
            before.privacy.check_for_updates
        );
        assert_eq!(after.privacy.managed_java, before.privacy.managed_java);
        assert_eq!(after.selected_profile, before.selected_profile);
    }

    /// The choice rows are filed the same way the switches are, and must land
    /// in a category that exists for the same reason.
    #[test]
    fn every_choice_has_a_real_category_and_a_valid_default() {
        const CATEGORIES: [&str; 6] = [
            "general",
            "game",
            "appearance",
            "network",
            "storage",
            "advanced",
        ];
        let settings = Settings::default();
        for row in [console_choice(&settings), java_choice(&settings)] {
            assert!(CATEGORIES.contains(&row.section), "{}", row.id);
            assert!(
                row.options.iter().any(|option| option.id == row.value),
                "the selected value {} is not one of {}'s options",
                row.value,
                row.id
            );
        }
    }

    #[test]
    fn every_offered_console_option_can_be_applied() {
        let settings = Settings::default();
        for option in console_choice(&settings).options {
            let after = apply_choice(&settings, "game-console", option.id)
                .unwrap_or_else(|error| panic!("{} is offered but refused: {error}", option.id));
            assert_eq!(after.game_console.id(), option.id);
        }
    }

    #[test]
    fn an_unknown_choice_or_value_is_refused() {
        let settings = Settings::default();
        assert!(apply_choice(&settings, "game-console", "sometimes").is_err());
        assert!(apply_choice(&settings, "telemetry-level", "high").is_err());
    }

    #[test]
    fn applying_a_choice_changes_only_that_choice() {
        let before = Settings::default();
        let after = apply_choice(&before, "game-console", "always").expect("a real option");
        assert_eq!(after.game_console, GameConsole::Always);
        assert_eq!(after.verbose_logging, before.verbose_logging);
        assert_eq!(after.privacy, before.privacy);
    }

    /// The switch this replaced could not be turned on when Java was already
    /// installed, so the one person who wanted a launcher-controlled runtime
    /// was the one person who could not ask for it.
    #[test]
    fn managed_java_is_no_longer_a_switch() {
        for (id, ..) in TOGGLES {
            assert_ne!(id, "managed-java", "the switch should be gone");
        }
        assert!(apply(&Settings::default(), "managed-java", true).is_err());
    }

    #[test]
    fn every_offered_java_policy_can_be_applied() {
        let settings = Settings::default();
        for option in java_choice(&settings).options {
            let after = apply_choice(&settings, "java-runtime", option.id)
                .unwrap_or_else(|error| panic!("{} is offered but refused: {error}", option.id));
            assert_eq!(after.java.id(), option.id);
        }
    }

    /// The rule the whole join exists for: choosing a policy that downloads a
    /// runtime has to also permit reaching the vendor, or the choice silently
    /// does nothing.
    #[test]
    fn choosing_a_downloading_policy_opens_the_allowlist() {
        let settings = Settings::default();
        let managed = apply_choice(&settings, "java-runtime", "managed").unwrap();
        assert!(managed.java.may_install());
        assert!(managed.to_network_policy().allow_managed_java);

        let system = apply_choice(&settings, "java-runtime", "system").unwrap();
        assert!(!system.java.may_install());
        assert!(!system.to_network_policy().allow_managed_java);
    }

    /// Offline mode still overrules everything, whatever the Java policy says.
    #[test]
    fn offline_mode_still_wins() {
        let mut settings = Settings::default();
        settings.set_java_policy(JavaPolicy::Managed);
        settings.privacy.offline_mode = true;
        assert!(!settings.to_network_policy().allow_managed_java);
    }

    #[test]
    fn an_unknown_java_policy_is_refused() {
        assert!(apply_choice(&Settings::default(), "java-runtime", "borrowed").is_err());
    }

    /// A profile-level custom path has no launcher-wide equivalent, so the row
    /// shows an answer that is actually in its own list.
    #[test]
    fn a_custom_path_falls_back_to_an_option_that_exists() {
        let settings = Settings {
            java: JavaPolicy::Custom {
                path: std::path::PathBuf::from("java.exe"),
            },
            ..Settings::default()
        };
        let row = java_choice(&settings);
        assert!(row.options.iter().any(|option| option.id == row.value));
    }

    /// A switch id and a choice id must never collide: the two commands are
    /// separate, and an id in both tables would mean the interface could set it
    /// either way and get different results.
    #[test]
    fn switch_ids_and_choice_ids_do_not_overlap() {
        let settings = Settings::default();
        for choice in [console_choice(&settings), java_choice(&settings)] {
            for (id, ..) in TOGGLES {
                assert_ne!(id, choice.id, "{id} is both a switch and a choice");
            }
        }
    }
}
