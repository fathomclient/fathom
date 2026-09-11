//! Privacy.
//!
//! # What changed and why
//!
//! This page used to open with a table of nineteen hostnames. That is the right
//! information and the wrong order: it answers "which DNS names might this
//! resolve" before it answers "does this thing spy on me", which is the
//! question people actually arrive with.
//!
//! So the page now leads with four flat answers — telemetry, analytics, crash
//! uploads, credentials — then groups what the launcher talks to by *purpose*,
//! and keeps the hostnames behind a disclosure on each group. Nothing was
//! removed. A privacy page that hides detail is worse than useless, because the
//! people most likely to read it are the ones who want the detail.

use iced::widget::{button, column, container, row, text};
use iced::{Alignment, Element, Length};

use launcher_core::network::Destination;

use crate::app::{App, Message};
use crate::components::{action_button, status_badge, ActionRole};
use crate::icons::{icon, Icon};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    column![
        promises(),
        activity(app),
        session_log(app),
    ]
    .spacing(style::space::LG)
    .width(Length::Fill)
    .into()
}

/// The four answers, before anything technical.
fn promises<'a>() -> Element<'a, Message> {
    let rows = column![
        promise("Telemetry", "None", "Nothing is collected about how you use Fathom."),
        promise("Analytics", "None", "No usage counters, no session identifiers, no ping."),
        promise(
            "Crash uploads",
            "Manual only",
            "Crashes are written to a log file on this machine. Nothing is sent unless you \
             choose to send it.",
        ),
        promise(
            "Credentials",
            "OS secure storage",
            "Only a Microsoft refresh token is kept, in Windows Credential Manager, protected \
             by DPAPI. Your password is never seen and never stored.",
        ),
        promise(
            "Device identifier",
            "None",
            "Requests carry a user agent naming the product and version, and nothing else.",
        ),
    ]
    .spacing(style::space::XXS);

    section(
        "What Fathom does not do",
        None,
        column![rows].spacing(style::space::MD).into(),
    )
}

fn promise<'a>(label: &'a str, value: &'a str, detail: &'a str) -> Element<'a, Message> {
    container(
        row![
            icon(Icon::ShieldOff, crate::icons::size::MEDIUM, style::GOOD),
            column![
                row![
                    text(label)
                        .size(style::text::ROW_TITLE)
                        .font(style::FONT_MEDIUM)
                        .color(style::TEXT)
                        .width(Length::Fixed(150.0)),
                    text(value)
                        .size(style::text::ROW_TITLE)
                        .font(style::FONT_SEMIBOLD)
                        .color(style::GOOD),
                ]
                .spacing(style::space::SM)
                .align_y(Alignment::Center),
                text(detail)
                    .size(style::text::CAPTION)
                    .color(style::TEXT_MUTED),
            ]
            .spacing(2)
            .width(Length::Fill),
        ]
        .spacing(style::space::MD)
        .align_y(Alignment::Center),
    )
    .padding(style::ROW_PADDING)
    .width(Length::Fill)
    .style(style::settings_row)
    .into()
}

/// What the launcher talks to, grouped by why.
fn activity(app: &App) -> Element<'_, Message> {
    let allowlist = app.core().network_allowlist();

    let mut groups = column![].spacing(style::space::XXS);
    for group in Group::ALL {
        groups = groups.push(group_row(app, group, &allowlist));
    }

    section(
        "Network activity",
        Some(
            "Every host this build is capable of reaching, and the reason it would. The list \
             is an enumeration in the source, not strings assembled at runtime, so it cannot \
             reach anything that is not here.",
        ),
        groups.into(),
    )
}

/// The purposes a request can serve, in the order a person meets them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Group {
    Authentication,
    Metadata,
    GameFiles,
    Loader,
    Updates,
}

impl Group {
    const ALL: [Self; 5] = [
        Self::Authentication,
        Self::Metadata,
        Self::GameFiles,
        Self::Loader,
        Self::Updates,
    ];

    const fn label(self) -> &'static str {
        match self {
            Self::Authentication => "Microsoft sign-in",
            Self::Metadata => "Minecraft metadata",
            Self::GameFiles => "Minecraft files",
            Self::Loader => "Legacy Fabric",
            Self::Updates => "Fathom updates",
        }
    }

    const fn purpose(self) -> &'static str {
        match self {
            Self::Authentication => {
                "Signs you in and confirms you own Minecraft. Your password goes to Microsoft \
                 directly; Fathom never sees it."
            }
            Self::Metadata => "Mojang's list of versions and what each one needs.",
            Self::GameFiles => "The client jar, libraries and assets, verified by SHA-1.",
            Self::Loader => "The mod loader's own metadata and libraries.",
            Self::Updates => {
                "One request on start, if you leave update checks on. No identifier is sent."
            }
        }
    }

    fn contains(self, destination: Destination) -> bool {
        match self {
            Self::Authentication => matches!(
                destination,
                Destination::MicrosoftOAuth
                    | Destination::XboxUserAuth
                    | Destination::XboxXsts
                    | Destination::MinecraftServices
            ),
            Self::Metadata => matches!(
                destination,
                Destination::PistonMeta | Destination::LauncherMeta
            ),
            Self::GameFiles => matches!(
                destination,
                Destination::PistonData
                    | Destination::LauncherMojang
                    | Destination::LibrariesMinecraft
                    | Destination::MinecraftResources
            ),
            Self::Loader => matches!(
                destination,
                Destination::LegacyFabricMeta
                    | Destination::LegacyFabricMaven
                    | Destination::LegacyFabricRepo
                    | Destination::FabricMaven
            ),
            Self::Updates => matches!(destination, Destination::LauncherUpdates),
        }
    }
}

fn group_row<'a>(
    app: &'a App,
    group: Group,
    allowlist: &[(Destination, bool)],
) -> Element<'a, Message> {
    let members: Vec<&(Destination, bool)> = allowlist
        .iter()
        .filter(|(destination, _)| group.contains(*destination))
        .collect();
    let expanded = app.expanded_privacy_group() == Some(group.label());
    let allowed = members.iter().filter(|(_, permitted)| *permitted).count();

    let (tone, state) = if members.is_empty() {
        (style::TEXT_FAINT, "None in this build".to_owned())
    } else if allowed == 0 {
        (style::WARN, "Blocked".to_owned())
    } else if allowed == members.len() {
        (style::GOOD, "Allowed".to_owned())
    } else {
        (style::WARN, format!("{allowed} of {} allowed", members.len()))
    };

    let mut body = column![
        row![
            text(group.label())
                .size(style::text::ROW_TITLE)
                .font(style::FONT_MEDIUM)
                .color(style::TEXT),
            status_badge(state, tone),
        ]
        .spacing(style::space::SM)
        .align_y(Alignment::Center),
        text(group.purpose())
            .size(style::text::METADATA)
            .color(style::TEXT_MUTED),
    ]
    .spacing(3)
    .width(Length::Fill);

    if expanded {
        let mut hosts = column![].spacing(2);
        if members.is_empty() {
            hosts = hosts.push(
                text("No host in this build serves this purpose yet.")
                    .size(style::text::CAPTION)
                    .color(style::TEXT_FAINT),
            );
        }
        for (destination, permitted) in &members {
            hosts = hosts.push(
                row![
                    text(destination.host())
                        .size(style::text::CAPTION)
                        .color(style::TEXT_MUTED)
                        .width(Length::Fill),
                    text(if *permitted { "allowed" } else { "blocked" })
                        .size(style::text::CAPTION)
                        .color(if *permitted { style::GOOD } else { style::WARN }),
                ]
                .spacing(style::space::SM),
            );
        }
        body = body.push(
            container(hosts)
                .padding(iced::Padding {
                    top: f32::from(style::space::SM),
                    right: 0.0,
                    bottom: 0.0,
                    left: 0.0,
                })
                .width(Length::Fill),
        );
    }

    let disclose = button(icon(
        if expanded {
            Icon::ChevronDown
        } else {
            Icon::ChevronRight
        },
        crate::icons::size::MEDIUM,
        style::TEXT_MUTED,
    ))
    .padding(style::space::XS)
    .style(style::ghost_button)
    .on_press(Message::PrivacyGroupExpanded(group.label()));

    container(
        row![disclose, body]
            .spacing(style::space::SM)
            .align_y(Alignment::Center),
    )
    .padding(style::ROW_PADDING)
    .width(Length::Fill)
    .style(style::list_row(expanded))
    .into()
}

/// The journal. Real requests, this session only.
fn session_log(app: &App) -> Element<'_, Message> {
    let summary = app.core().network_summary();

    let stats = row![
        stat("Requests", summary.total.to_string(), style::TEXT),
        stat("Completed", summary.completed.to_string(), style::GOOD),
        stat("Refused by policy", summary.denied.to_string(), style::WARN),
        stat("Failed", summary.failed.to_string(), style::BAD),
    ]
    .spacing(style::space::XXL);

    let body: Element<'_, Message> = if app.journal().is_empty() {
        text(
            "Nothing yet this session. The journal is held in memory only: it is never \
             written to disk and never transmitted.",
        )
        .size(style::text::BODY)
        .color(style::TEXT_MUTED)
        .into()
    } else {
        let mut list = column![].spacing(2);
        for entry in app.journal().iter().take(60) {
            list = list.push(
                row![
                    text(entry.destination.host())
                        .size(style::text::CAPTION)
                        .color(style::TEXT_MUTED)
                        .width(Length::Fill),
                    text(format!("{:?}", entry.outcome))
                        .size(style::text::CAPTION)
                        .color(style::TEXT_FAINT),
                ]
                .spacing(style::space::SM),
            );
        }
        list.into()
    };

    let actions = row![
        action_button("Refresh", ActionRole::Ghost, Some(Message::RefreshJournal)),
        action_button("Clear", ActionRole::Ghost, Some(Message::ClearJournal)),
    ]
    .spacing(style::space::SM);

    section(
        "This session",
        Some("Held in memory only. Never written to disk, never transmitted."),
        column![stats, body, actions]
            .spacing(style::space::MD)
            .into(),
    )
}

fn stat<'a>(label: &'a str, value: String, tone: iced::Color) -> Element<'a, Message> {
    column![
        text(value)
            .size(style::text::PAGE_TITLE)
            .font(style::FONT_SEMIBOLD)
            .color(tone),
        text(label)
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT),
    ]
    .spacing(2)
    .into()
}

fn section<'a>(
    title: &'a str,
    note: Option<&'a str>,
    body: Element<'a, Message>,
) -> Element<'a, Message> {
    let mut header = column![text(title)
        .size(style::text::SECTION_TITLE)
        .font(style::FONT_SEMIBOLD)
        .color(style::TEXT)]
    .spacing(4);
    if let Some(note) = note {
        header = header.push(
            text(note)
                .size(style::text::CAPTION)
                .color(style::TEXT_MUTED),
        );
    }
    column![header, body]
        .spacing(style::space::SM)
        .width(Length::Fill)
        .into()
}
