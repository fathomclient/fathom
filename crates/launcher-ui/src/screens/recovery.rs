//! Recovery.
//!
//! # Why this is its own destination
//!
//! Everything here already existed — the startup scan runs on every launch,
//! `RecoveryService` can rebuild what it finds, and `fathom-cli recover` has
//! been the way to reach it. What was missing was anywhere in the launcher to
//! *see* it. A repair tool you have to know about is a repair tool for the
//! person who wrote it.
//!
//! # What it deliberately does not do
//!
//! It does not offer a button per subsystem. The recovery work that exists is
//! reached through the profile and the installation, and inventing controls
//! here that map onto nothing would be exactly the placeholder problem this
//! page is meant to fix. Where an action is genuinely wired up it is offered;
//! where it is not, the page says which tool does it instead.

use iced::widget::{column, container, row, text};
use iced::{Alignment, Element, Length};

use crate::app::{App, Message, Screen};
use crate::components::{action_button, panel, status_badge, ActionRole};
use crate::icons::{icon, Icon};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    let mut sections = column![].spacing(style::space::LG).width(Length::Fill);

    sections = sections.push(startup_state(app));
    sections = sections.push(game_files(app));
    sections = sections.push(profile_repair(app));
    sections = sections.push(where_things_live(app));

    sections.into()
}

/// What the scan that runs on every launch found.
fn startup_state(app: &App) -> Element<'_, Message> {
    let (tone, headline, detail) = match app.startup_findings() {
        [] => (
            style::GOOD,
            "Nothing needed attention at startup",
            "The scan runs every time Fathom opens. It looks for interrupted downloads, \
             temporary files left behind by a previous session, and profiles that will not \
             load."
                .to_owned(),
        ),
        findings => (
            style::WARN,
            "Startup recovery ran",
            findings.join(" "),
        ),
    };

    let body = row![
        icon(
            if tone == style::GOOD { Icon::Check } else { Icon::Warning },
            crate::icons::size::LARGE,
            tone
        ),
        column![
            text(headline)
                .size(style::text::SECTION_TITLE)
                .font(style::FONT_SEMIBOLD)
                .color(style::TEXT),
            text(detail)
                .size(style::text::BODY)
                .color(style::TEXT_MUTED),
        ]
        .spacing(3)
        .width(Length::Fill),
    ]
    .spacing(style::space::MD)
    .align_y(Alignment::Center);

    container(body)
        .padding(style::CARD_PADDING)
        .width(Length::Fill)
        .style(style::notice(tone))
        .into()
}

fn game_files(app: &App) -> Element<'_, Message> {
    let installation = app.installation();
    let idle = app.busy().is_none() && app.worker_ready();

    let (tone, state, summary) = if installation.resolved.is_none() {
        (
            style::TEXT_MUTED,
            "Not checked",
            "Resolve the version first, then Fathom can compare what is on disk against \
             what Mojang published."
                .to_owned(),
        )
    } else if let Some(report) = installation.report.as_ref() {
        let total = installation
            .resolved
            .as_ref()
            .map_or(report.verified, |plan| plan.all_downloads().len());
        let healthy = report.verified == total;
        (
            if healthy { style::GOOD } else { style::WARN },
            if healthy { "Healthy" } else { "Incomplete" },
            format!(
                "{} of {total} files present{}.",
                report.verified,
                if installation.hashed {
                    " and verified by SHA-1"
                } else {
                    ""
                }
            ),
        )
    } else {
        (
            style::TEXT_MUTED,
            "Resolved",
            "The file list is known. Nothing has been hashed yet.".to_owned(),
        )
    };

    let actions = row![
        action_button(
            "Verify every file",
            ActionRole::Secondary,
            idle.then_some(Message::VerifyGameFiles),
        ),
        action_button(
            "Repair",
            ActionRole::Secondary,
            idle.then_some(Message::RepairGameFiles),
        ),
    ]
    .spacing(style::space::SM);

    panel(
        "Game files",
        Some("Verifying hashes every file against Mojang's published SHA-1. Repair replaces only what does not match."),
        column![
            row![status_badge(state, tone), text(summary).size(style::text::BODY).color(style::TEXT_MUTED)]
                .spacing(style::space::SM)
                .align_y(Alignment::Center),
            actions,
        ]
        .spacing(style::space::MD),
    )
}

fn profile_repair(app: &App) -> Element<'_, Message> {
    let Some(profile) = app.selected_profile() else {
        return panel(
            "Profile",
            Some("No profile is selected."),
            text("Choose a profile to check or repair it.")
                .size(style::text::BODY)
                .color(style::TEXT_MUTED),
        );
    };

    let id = profile.id.clone();
    let actions = row![
        action_button(
            "Check this profile",
            ActionRole::Secondary,
            Some(Message::ValidateProfile(id.clone())),
        ),
        action_button(
            "Rebuild missing parts",
            ActionRole::Secondary,
            Some(Message::RepairProfile(id)),
        ),
    ]
    .spacing(style::space::SM);

    let mut body = column![
        text(
            "Checking looks for a profile's directories, its configuration, and whether its \
             mods still resolve. Rebuilding recreates what is missing without touching worlds."
        )
        .size(style::text::BODY)
        .color(style::TEXT_MUTED),
        actions,
    ]
    .spacing(style::space::MD);

    if let Some(report) = app.profile_report() {
        let summary = if report.is_healthy() {
            "Last check: nothing wrong.".to_owned()
        } else {
            let total = report.defects.len();
            let repairable = report.auto_repairable().count();
            format!(
                "Last check: {total} issue{} found, {repairable} of which Fathom can rebuild.",
                if total == 1 { "" } else { "s" }
            )
        };
        body = body.push(
            text(summary)
                .size(style::text::METADATA)
                .color(style::TEXT_MUTED),
        );
    }

    panel("Profile", Some(&profile.display_name), body)
}

/// Where the data is, and how to reach the tools that are not in the window.
fn where_things_live(app: &App) -> Element<'_, Message> {
    let root = app.core().paths().root().display().to_string();

    let body = column![
        text(
            "Deleting a profile moves it into recovery storage rather than removing it, so a \
             mistake is recoverable. Worlds are never touched by any action on this page."
        )
        .size(style::text::BODY)
        .color(style::TEXT_MUTED),
        crate::components::detail("Data directory", root),
        text(
            "fathom-cli recover runs the same checks without opening a window, which is what \
             to use if the launcher itself will not start."
        )
        .size(style::text::CAPTION)
        .color(style::TEXT_FAINT),
        action_button(
            "Open diagnostics",
            ActionRole::Ghost,
            Some(Message::Navigate(Screen::Privacy)),
        ),
    ]
    .spacing(style::space::MD);

    panel("Storage and tools", None, body)
}
