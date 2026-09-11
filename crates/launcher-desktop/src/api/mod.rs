//! The command bridge.
//!
//! # The rule this file exists to enforce
//!
//! **The frontend renders. It does not decide, fetch, or hold anything.**
//!
//! Every function here is a narrow, typed question or instruction. The
//! WebView cannot reach the filesystem, cannot make a network request, cannot
//! see a token, and cannot assemble a launch command. It asks for a view of
//! state and gets a struct back; it asks for something to happen and gets a
//! result. There is no general-purpose escape hatch — no `invoke("fs.read")`,
//! no shell plugin, no HTTP plugin.
//!
//! That is why the `tauri` dependency has no features and no plugins. Every
//! capability a plugin would add is either something the frontend must not
//! have, or something `launcher-core` already does behind one of these
//! commands.
//!
//! # What crosses the boundary
//!
//! Only these types, all of them plain data. Notably absent: paths the user has
//! no reason to see, anything derived from a credential, and any handle that
//! would let the caller do something not on this list.

pub mod auth;
pub mod launch;
pub mod logs;
pub mod mods;
pub mod performance;
pub mod privacy;
pub mod profiles;
pub mod recovery;
pub mod settings;
pub mod supplied;
pub mod setup;

use serde::Serialize;

use launcher_core::profiles::Profile;
use launcher_core::{Blocker, Launcher};

/// What Home needs to draw itself.
///
/// One call rather than five, because the launch composition is a single
/// hierarchy and rendering half of it while the rest arrives would produce a
/// visible reflow on every open.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeView {
    pub profile: Option<ProfileSummary>,
    /// The single most important thing on the screen: what the launch control
    /// says, and what pressing it does.
    pub action: LaunchAction,
    pub account: AccountSummary,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSummary {
    pub id: String,
    pub name: String,
    /// True when the name is only ever going to repeat the version, so the
    /// interface can drop it instead of saying the same thing twice.
    pub name_is_redundant: bool,
    pub version: String,
    pub loader: String,
    pub loader_available: bool,
    pub enabled_mods: usize,
    pub preset: String,
    /// What this profile is, said before what it carries. "Fathom" answers
    /// more of a reader's questions than "Legacy Fabric" does.
    pub kind: &'static str,
    pub kind_label: &'static str,
}

impl ProfileSummary {
    fn of(profile: &Profile) -> Self {
        let expected = format!("Minecraft {}", profile.version_id);
        Self {
            id: profile.id.as_str().to_owned(),
            name: profile.display_name.clone(),
            name_is_redundant: profile.display_name.trim().eq_ignore_ascii_case(&expected),
            version: profile.version_id.clone(),
            loader: profile.loader_kind().label().to_owned(),
            loader_available: profile.loader_kind().is_implemented(),
            enabled_mods: profile.mods.enabled().count(),
            preset: profile.optimizations.preset.label().to_owned(),
            kind: profile.kind.id(),
            kind_label: profile.kind.label(),
        }
    }
}

/// What the launch control shows and does.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchAction {
    /// The word on the button. Short: it is set in a display face at 30px.
    pub label: String,
    /// One concise line beneath. Never an implementation detail — see
    /// `developer_note`.
    pub detail: String,
    /// What to invoke. `None` means the control is inert.
    pub command: Option<String>,
    /// Whether this is the good case, which decides the button's treatment.
    pub ready: bool,
    /// Something only a developer can act on, kept out of the main composition
    /// and shown in a debug affordance instead.
    ///
    /// "This build has no Microsoft application id" is the motivating example:
    /// true, useful to exactly one person, and meaningless to everyone else
    /// standing in front of the launch screen.
    pub developer_note: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountSummary {
    pub name: Option<String>,
    pub signed_in: bool,
    /// True for the fake local identity, so the shell can say so permanently.
    pub development: bool,
}

/// The profile every screen is talking about.
///
/// The selected one, falling back to the first. Written once and shared,
/// because Home and the bottom bar used to answer this question differently:
/// Home read the selection and the status bar took whichever profile happened
/// to sort first, so the hero could say "Optimized -- Legacy Fabric, 1 mod"
/// over a status line reading "Vanilla, 0 mods" about a different profile
/// entirely.
pub fn current_profile(core: &Launcher) -> Option<Profile> {
    let selected = core.settings().selected_profile.clone();
    let profiles = core.profiles();
    selected
        .and_then(|id| profiles.iter().find(|profile| profile.id == id).cloned())
        .or_else(|| profiles.into_iter().next())
}

/// Build the Home view from the launcher's current state.
pub fn home_view(core: &Launcher) -> HomeView {
    let profile = current_profile(core);

    let identity = core.identity();
    let account = AccountSummary {
        name: identity.as_ref().map(|i| i.profile.name.clone()),
        signed_in: identity.is_some(),
        development: identity.as_ref().is_some_and(|i| !i.kind.is_production()),
    };

    let action = match profile.as_ref() {
        Some(profile) => {
            let version = core.cached_version(&profile.version_id);
            let snapshot = version.as_ref().and_then(|version| install_snapshot(core, profile, version));
            let readiness = core.readiness(profile, version.as_ref(), snapshot.as_ref());
            launch_action(&readiness.blockers, core.identity_warning())
        }
        None => LaunchAction {
            label: "NO PROFILE".to_owned(),
            detail: "Create a profile to start".to_owned(),
            command: Some("profiles".to_owned()),
            ready: false,
            developer_note: None,
        },
    };

    HomeView {
        profile: profile.as_ref().map(ProfileSummary::of),
        action,
        account,
    }
}

/// What is on disk for this profile, by existence.
///
/// Without this, `readiness` is passed `None` and answers `InstallNotChecked`
/// -- which is the correct answer to "have you looked", and turns the launch
/// control into "PREPARE GAME / check the game files first" on a machine where
/// every file is present. The launcher had simply never looked.
///
/// `quick_check` is existence-only and documented as cheap enough to run
/// whenever a screen is drawn; the hashing pass is what Verify is for. Returns
/// `None` when the version document is not cached yet, because then there is
/// genuinely nothing to compare against and `InstallNotChecked` is true.
fn install_snapshot(
    core: &Launcher,
    profile: &Profile,
    version: &launcher_core::minecraft_runtime::VersionDetail,
) -> Option<launcher_core::InstallSnapshot> {
    let resolved = core
        .resolve_install(version, launcher_core::network::policy::Feature::Play)
        .ok()?;
    let natives = core.dirs_for(profile).natives_dir();
    let report = core.install_service().quick_check(&resolved, &natives);
    Some(launcher_core::InstallSnapshot::from_report(&report, &resolved))
}

/// Turn the first blocker into a button.
///
/// One action, never a list. A launcher has exactly one important question --
/// can I play, and if not what do I press -- and answering it with five rows of
/// diagnostics is what made the previous Home look like a settings page.
///
/// Takes the identity warning rather than the whole `Launcher` so that the
/// mapping from blockers to a button is a pure function of its inputs, and can
/// be tested without constructing a launcher and a credential store.
fn launch_action(blockers: &[Blocker], identity_warning: Option<&str>) -> LaunchAction {
    if blockers.is_empty() {
        return LaunchAction {
            label: "PLAY".to_owned(),
            detail: "Ready to launch".to_owned(),
            command: Some("play".to_owned()),
            ready: true,
            developer_note: None,
        };
    }

    // `NoClientId` is a build-configuration fact, not something the person in
    // front of the screen can fix. It reads as "sign in required" like any
    // other signed-out state, and the real reason moves to the developer note.
    let developer_note = identity_warning.map(str::to_owned).or_else(|| {
        blockers
            .iter()
            .find_map(|blocker| match blocker {
                Blocker::NoClientId => Some(
                    "This build has no Microsoft application id, so sign-in is disabled. \
                     Set FATHOM_AZURE_CLIENT_ID to use your own registration."
                        .to_owned(),
                ),
                _ => None,
            })
    });

    // The *first* blocker becomes the button. Matched directly rather than
    // searched, because every variant maps to an action -- there is no such
    // thing as a blocker with nothing to press.
    let (label, detail, command) = match blockers.first() {
        Some(Blocker::SignedOut | Blocker::SessionExpired | Blocker::NoClientId) => {
            ("SIGN IN REQUIRED", "Sign in with Microsoft to play", "sign-in")
        }
        Some(Blocker::NotEntitled(_)) => {
            ("ACCOUNT ISSUE", "This account does not own Minecraft", "account")
        }
        Some(Blocker::NeedsInstall(snapshot))
            if snapshot.state == launcher_core::install::InstallationState::Corrupted =>
        {
            ("REPAIR REQUIRED", "Some game files are damaged", "repair")
        }
        Some(Blocker::NeedsInstall(_)) => {
            ("PREPARE GAME", "Download the game files", "install")
        }
        Some(Blocker::InstallNotChecked) => {
            ("PREPARE GAME", "Check the game files first", "verify")
        }
        Some(Blocker::NoJava { .. } | Blocker::WrongJava { .. }) => {
            ("JAVA REQUIRED", "Minecraft 1.8.9 needs Java 8", "java")
        }
        Some(Blocker::LoaderNotImplemented(_)) => {
            ("LOADER UNAVAILABLE", "This loader is not ready yet", "profiles")
        }
        Some(Blocker::OfflineWithWorkOutstanding) => {
            ("OFFLINE", "Offline mode blocks downloads", "settings")
        }
        None => ("NEEDS ATTENTION", "Open Recovery to see why", "recovery"),
    };

    LaunchAction {
        label: label.to_owned(),
        detail: detail.to_owned(),
        command: Some(command.to_owned()),
        ready: false,
        developer_note,
    }
}

/// The bottom bar's one line of state.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShellStatus {
    pub version: String,
    pub offline: bool,
    pub summary: String,
}

pub fn shell_status(core: &Launcher) -> ShellStatus {
    let profile = current_profile(core);
    let summary = profile.map_or_else(
        || "No profile".to_owned(),
        |profile| {
            let mods = profile.mods.enabled().count();
            format!(
                "Minecraft {} \u{00B7} {} \u{00B7} {} {}",
                profile.version_id,
                profile.loader_kind().label(),
                mods,
                if mods == 1 { "mod" } else { "mods" }
            )
        },
    );

    ShellStatus {
        version: launcher_core::PRODUCT_VERSION.to_owned(),
        offline: core.settings().privacy.offline_mode,
        summary,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The rule the whole boundary rests on: the frontend is handed a decision,
    /// not the inputs to one. If a blocker ever reached the WebView as raw
    /// state, the interface would start making launch judgements in TypeScript.
    #[test]
    fn a_blocked_launcher_yields_exactly_one_action() {
        let action = launch_action(
            &[
                Blocker::SignedOut,
                Blocker::InstallNotChecked,
                Blocker::NoJava { required_major: 8 },
            ],
            None,
        );
        assert_eq!(action.label, "SIGN IN REQUIRED");
        assert!(!action.ready);
        assert_eq!(action.command.as_deref(), Some("sign-in"));
    }

    /// Implementation detail must not reach the launch composition. It goes to
    /// `developer_note`, which the shell shows in a debug affordance.
    #[test]
    fn a_build_configuration_problem_is_not_shown_as_the_reason() {
        let action = launch_action(&[Blocker::NoClientId], None);
        assert_eq!(action.label, "SIGN IN REQUIRED");
        assert!(
            !action.detail.contains("application id"),
            "developer terminology leaked into the main composition: {}",
            action.detail
        );
        assert!(
            action
                .developer_note
                .as_deref()
                .is_some_and(|note| note.contains("application id")),
            "the real reason was dropped instead of moved"
        );
    }

    #[test]
    fn a_ready_launcher_says_play() {
        let action = launch_action(&[], None);
        assert_eq!(action.label, "PLAY");
        assert!(action.ready);
        assert!(action.developer_note.is_none());
    }
}
