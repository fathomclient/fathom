//! Home.
//!
//! # The shape of this screen
//!
//! One vertical column, centred in whatever the shell leaves over:
//!
//! ```text
//!                     profile name
//!                   MINECRAFT 1.8.9
//!            Vanilla · Balanced · 3 mods
//!
//!                       [ PLAY ]
//!
//!                     Ready to launch
//!
//!               ── health · java · files ──
//! ```
//!
//! That is the entire screen. It replaces a stack of five bordered panels, of
//! which three were headed lists of things that were wrong. The information
//! did not disappear: the blockers now speak through the launch control
//! itself, which says `SIGN IN TO PLAY` or `REPAIR REQUIRED` instead of `PLAY`
//! and carries the fix as its own action. A launcher has exactly one important
//! question — can I play, and if not, what do I press — and the answer should
//! not be assembled by reading four cards.
//!
//! The footage behind all of this is not decoration to be covered up. Nothing
//! here draws a panel; the text sits directly on the background, and the
//! shell's scrim is what makes it readable.

use iced::widget::{button, column, container, row, text, Space};
use iced::{Alignment, Color, Element, Length};

use launcher_core::install::InstallationState;
use launcher_core::{Blocker, Readiness};

use crate::app::{App, Message, Screen};
use crate::components::{extruded, progress_bar};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    let Some(profile) = app.selected_profile() else {
        return centred(
            column![
                text("No profile selected")
                    .size(style::type_size::PROFILE_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text("Create one in Profiles, then come back here to launch.")
                    .size(style::type_size::BODY)
                    .color(style::TEXT_MUTED),
                Space::with_height(Length::Fixed(8.0)),
                button(
                    text("OPEN PROFILES")
                        .size(style::type_size::BODY)
                        .font(style::FONT_SEMIBOLD)
                )
                .padding([style::space::MD, style::space::XXL])
                .style(style::blocked_play_button)
                .on_press(Message::Navigate(Screen::Profiles)),
            ]
            .spacing(style::space::SM)
            .align_x(Alignment::Center)
            .into(),
        );
    };

    let readiness = app.readiness();
    // Destructured rather than borrowed: the strings are built here and move
    // straight into the widgets that display them.
    let PrimaryAction {
        label,
        supporting,
        message,
        secondary,
        ready,
    } = primary_action(app, &readiness);
    let enabled_mods = profile.mods.enabled().count();

    // -- above the button: what is about to launch ------------------------
    //
    // The eyebrow carries the profile's name. When that name is only ever
    // going to be "Minecraft <version>" — which is what the first profile is
    // called — it says nothing the heading below is not about to say much
    // louder, so it becomes a label instead.
    let eyebrow: String = if crate::app::profile_name_adds_nothing(profile) {
        "SELECTED PROFILE".to_owned()
    } else {
        profile.display_name.clone()
    };

    let identity = column![
        // Muted rather than faint: this line sits directly on the footage with
        // no surface behind it, and over a bright scene the fainter tone
        // disappears entirely.
        text(eyebrow)
            .size(style::type_size::CAPTION)
            .font(style::FONT_SEMIBOLD)
            .color(style::TEXT_MUTED),
        // Not the display face: this string contains full stops, which that
        // face has no glyph for. It gets the heading optical size instead.
        text(format!("MINECRAFT {}", profile.version_id))
            .size(style::type_size::HERO)
            .font(style::FONT_HEADING)
            .color(style::TEXT),
        row![
            fact(profile.loader_kind().label()),
            dot(),
            fact(profile.optimizations.preset.label()),
            dot(),
            fact(format!(
                "{enabled_mods} {}",
                if enabled_mods == 1 { "mod" } else { "mods" }
            )),
        ]
        .spacing(style::space::SM)
        .align_y(Alignment::Center),
    ]
    .spacing(style::space::XS)
    .align_x(Alignment::Center);

    // -- the button itself -------------------------------------------------
    //
    // The one place in the launcher that gets the full display treatment: the
    // blocky face, extruded. `extruded` picks the interface font by itself if
    // the label is something the display face cannot spell, so a state like
    // "REPAIR REQUIRED" is safe and so is anything added later.
    let mut launch = button(
        container(extruded(
            label,
            style::type_size::PLAY,
            Color::WHITE,
            style::VOID,
            2,
        ))
        .center_x(Length::Fill),
    )
    .width(Length::Fixed(if ready { 268.0 } else { 300.0 }))
    .padding([style::space::LG, style::space::XXL])
    .style(if ready {
        style::play_button
    } else {
        style::blocked_play_button
    });
    if let Some(message) = message {
        launch = launch.on_press(message);
    }

    // -- below the button: why, and what else you could do -----------------
    let mut beneath = column![text(supporting)
        .size(style::type_size::BODY)
        .color(if ready { style::TEXT_MUTED } else { style::WARN })]
    .spacing(style::space::SM)
    .align_x(Alignment::Center);

    if let Some(install) = app.install() {
        if let Some(fraction) = install.fraction() {
            beneath = beneath.push(
                container(progress_bar(fraction)).width(Length::Fixed(268.0)),
            );
        }
    }

    if let Some(secondary) = secondary {
        beneath = beneath.push(
            button(
                text(secondary.0)
                    .size(style::type_size::SUPPORTING)
                    .font(style::FONT_MEDIUM),
            )
            .padding(style::COMPACT_CONTROL_PADDING)
            .style(style::ghost_button)
            .on_press(secondary.1),
        );
    }

    if app.game().is_some() {
        beneath = beneath.push(
            button(
                text("STOP MINECRAFT")
                    .size(style::type_size::SUPPORTING)
                    .font(style::FONT_MEDIUM),
            )
            .padding(style::CONTROL_PADDING)
            .style(style::danger_button)
            .on_press(Message::StopGame),
        );
    }

    centred(
        column![
            identity,
            Space::with_height(Length::Fixed(26.0)),
            launch,
            Space::with_height(Length::Fixed(14.0)),
            beneath,
            Space::with_height(Length::Fixed(30.0)),
            vitals(app),
        ]
        .align_x(Alignment::Center)
        .into(),
    )
}

/// Puts the column in the middle of the space the shell handed us.
fn centred(content: Element<'_, Message>) -> Element<'_, Message> {
    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .center_x(Length::Fill)
        .center_y(Length::Fill)
        .padding(style::space::LG)
        .into()
}

/// The quiet strip under the launch control.
///
/// Four facts on one line with no box around them. This is what used to be two
/// full-width panels titled "Installation health" and "Profile configuration";
/// almost all of what they contained was either already visible above or only
/// interesting on the screen that owns it.
fn vitals(app: &App) -> Element<'_, Message> {
    let installation = app.installation();
    let state = installation
        .state()
        .unwrap_or(InstallationState::NotInstalled);

    let (files_tone, files) = if app.install().is_some() {
        (style::ACCENT, "Working".to_owned())
    } else if installation.resolved.is_none() {
        (style::TEXT_MUTED, "Not prepared".to_owned())
    } else if let Some(report) = installation.report.as_ref() {
        let total = installation
            .resolved
            .as_ref()
            .map_or(report.verified, |plan| plan.all_downloads().len());
        (
            match state {
                InstallationState::Installed => style::GOOD,
                InstallationState::Corrupted => style::BAD,
                _ => style::WARN,
            },
            format!(
                "{}/{total}{}",
                report.verified,
                if installation.hashed { " verified" } else { "" }
            ),
        )
    } else {
        (style::TEXT_MUTED, "Resolved".to_owned())
    };

    let readiness = app.readiness();
    let (java_tone, java) = match readiness.java.as_ref() {
        Some(java) => (style::GOOD, format!("Java {}", java.major)),
        None => (style::WARN, "Not found".to_owned()),
    };

    let profile = app.selected_profile();
    let heap = profile.map_or_else(
        || "\u{2014}".to_owned(),
        |profile| format!("{} MB", profile.memory.heap_mb),
    );

    let mut line = row![
        vital("FILES", files, files_tone),
        vital("JAVA", java, java_tone),
        vital("HEAP", heap, style::TEXT),
    ]
    .spacing(style::space::XXL)
    .align_y(Alignment::Center);

    if let Some(game) = app.game() {
        line = line.push(vital(
            "SESSION",
            format!("{:.0}s", game.elapsed().as_secs_f64()),
            style::GOOD,
        ));
    }

    line.into()
}

fn vital<'a>(label: &'a str, value: impl Into<String>, tone: iced::Color) -> Element<'a, Message> {
    column![
        text(label)
            .size(style::type_size::MICRO)
            .font(style::FONT_SEMIBOLD)
            .color(style::TEXT_FAINT),
        text(value.into())
            .size(style::type_size::SUPPORTING)
            .font(style::FONT_MEDIUM)
            .color(tone),
    ]
    .spacing(2)
    .align_x(Alignment::Center)
    .into()
}

fn fact<'a>(value: impl Into<String>) -> Element<'a, Message> {
    text(value.into())
        .size(style::type_size::SUPPORTING)
        .color(style::TEXT_MUTED)
        .into()
}

fn dot<'a>() -> Element<'a, Message> {
    text("\u{00B7}")
        .size(style::type_size::SUPPORTING)
        .color(style::TEXT_FAINT)
        .into()
}

/// What the one big button says, does, and explains.
struct PrimaryAction {
    label: String,
    supporting: String,
    message: Option<Message>,
    /// A quieter alternative directly beneath, when there is a sensible one.
    secondary: Option<(&'static str, Message)>,
    /// Whether this is the good case. Drives which button treatment is used.
    ready: bool,
}

fn primary_action(app: &App, readiness: &Readiness) -> PrimaryAction {
    if app.game().is_some() {
        return PrimaryAction {
            label: "RUNNING".to_owned(),
            supporting: "Minecraft is running".to_owned(),
            message: None,
            secondary: None,
            ready: false,
        };
    }

    if let Some(install) = app.install() {
        let label = if install.repairing {
            "REPAIRING"
        } else if install.fraction().is_some() {
            "DOWNLOADING"
        } else {
            "PREPARING"
        };
        return PrimaryAction {
            label: label.to_owned(),
            supporting: install.label(),
            message: None,
            secondary: Some(("Cancel", Message::CancelInstall)),
            ready: false,
        };
    }

    if let Some(job) = app.busy() {
        return PrimaryAction {
            label: "WORKING".to_owned(),
            supporting: job.to_owned(),
            message: None,
            secondary: None,
            ready: false,
        };
    }

    if readiness.can_play() {
        return PrimaryAction {
            label: "PLAY".to_owned(),
            supporting: "Ready to launch".to_owned(),
            message: Some(Message::Play),
            secondary: Some(("Verify game files", Message::VerifyGameFiles)),
            ready: true,
        };
    }

    // Not ready. The first blocker becomes the button, because a list of
    // things that are wrong is not an action and the user has to start
    // somewhere. Resolving one re-evaluates and the button becomes the next.
    for blocker in visible_blockers(readiness) {
        if let Some(action) = blocker_action(blocker) {
            return action;
        }
    }

    if app.version().is_none() {
        return PrimaryAction {
            label: "PREPARE".to_owned(),
            supporting: "Resolve the selected Minecraft version".to_owned(),
            message: Some(Message::PrepareVersion),
            secondary: None,
            ready: false,
        };
    }

    PrimaryAction {
        label: "NEEDS ATTENTION".to_owned(),
        supporting: "Open Diagnostics to see what happened".to_owned(),
        message: Some(Message::Navigate(Screen::Privacy)),
        secondary: None,
        ready: false,
    }
}

fn blocker_action(blocker: &Blocker) -> Option<PrimaryAction> {
    let (label, supporting, message, secondary) = match blocker {
        Blocker::SignedOut | Blocker::SessionExpired => (
            "SIGN IN TO PLAY",
            "A Microsoft account is required to launch Minecraft",
            Message::Navigate(Screen::Account),
            None,
        ),
        Blocker::NoClientId => (
            "SIGN IN UNAVAILABLE",
            "This build has no Microsoft application id",
            Message::Navigate(Screen::Account),
            None,
        ),
        Blocker::NotEntitled(_) => (
            "CHECK ACCOUNT",
            "This account does not own Minecraft: Java Edition",
            Message::Navigate(Screen::Account),
            None,
        ),
        Blocker::NeedsInstall(snapshot) if snapshot.state == InstallationState::Corrupted => (
            "REPAIR REQUIRED",
            "Some game files are damaged and will be replaced",
            Message::RepairGameFiles,
            Some(("Verify first", Message::VerifyGameFiles)),
        ),
        Blocker::NeedsInstall(_) => (
            "INSTALL GAME FILES",
            "Downloaded from Mojang's own hosts and verified by SHA-1",
            Message::InstallGameFiles,
            None,
        ),
        Blocker::InstallNotChecked => (
            "VERIFY FILES",
            "The game files have not been checked yet",
            Message::VerifyGameFiles,
            None,
        ),
        Blocker::NoJava { .. } | Blocker::WrongJava { .. } => (
            "JAVA REQUIRED",
            "Minecraft 1.8.9 needs a Java 8 runtime",
            Message::Navigate(Screen::Settings),
            Some(("Scan for Java", Message::RefreshJava)),
        ),
        Blocker::LoaderNotImplemented(_) => (
            "LOADER UNAVAILABLE",
            "This profile selects a loader that is not implemented yet",
            Message::Navigate(Screen::Profiles),
            None,
        ),
        Blocker::OfflineWithWorkOutstanding => (
            "OFFLINE",
            "Downloads are blocked while offline mode is on",
            Message::Navigate(Screen::Settings),
            None,
        ),
    };

    Some(PrimaryAction {
        label: label.to_owned(),
        supporting: supporting.to_owned(),
        message: Some(message),
        secondary,
        ready: false,
    })
}

/// `NoClientId` already explains why sign-in is impossible, so the `SignedOut`
/// that always accompanies it would just be the same sentence again.
fn visible_blockers(readiness: &Readiness) -> Vec<&Blocker> {
    let has_no_client_id = readiness
        .blockers
        .iter()
        .any(|blocker| matches!(blocker, Blocker::NoClientId));
    readiness
        .blockers
        .iter()
        .filter(|blocker| !(has_no_client_id && matches!(blocker, Blocker::SignedOut)))
        .collect()
}
