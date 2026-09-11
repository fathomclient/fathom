//! Profiles.
//!
//! # What changed
//!
//! The previous version was a form: profile id, Minecraft version, loader
//! radio buttons, a checkbox, five operation buttons and a delete confirmation,
//! all visible at once for the selected profile. It exposed the data model
//! rather than the thing the model is for.
//!
//! This is a browser. Each profile is one row you can read at a glance —
//! artwork, name, version, loader, mods, preset, health, when you last played
//! it — and Play is the primary action on every one of them. Editing is a
//! disclosure, because changing a heap size is rarer than picking which setup
//! to launch, and the common case should not pay for the rare one.
//!
//! Profile ids are not shown. They are a filename, they are ugly, and nobody
//! needs one to choose between "Main" and "PvP".

use iced::widget::{button, checkbox, column, container, image, row, text, text_input, Space};
use iced::{Alignment, ContentFit, Element, Length};

use launcher_core::profiles::{Defect, Loader, Profile, ProfileHealth};

use crate::app::{App, Message};
use crate::components::{action_button, detail, status_badge, ActionRole};
use crate::icons::{icon, muted, Icon};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    let mut page = column![].spacing(style::space::MD).width(Length::Fill);

    let mut profiles: Vec<&Profile> = app.profiles().iter().collect();
    profiles.sort_by_key(|profile| std::cmp::Reverse(profile.updated_at));

    let mut list = column![].spacing(style::space::XS);
    for profile in profiles {
        list = list.push(profile_card(app, profile));
    }
    page = page.push(list);
    page = page.push(create_row(app));
    page.into()
}

/// One profile, as a row you can act on without opening anything.
fn profile_card<'a>(app: &'a App, profile: &'a Profile) -> Element<'a, Message> {
    let selected = app.selected_id() == Some(&profile.id);
    let editing = selected && app.editing_profile();

    let art: Element<'_, Message> = match app.profile_art_for(&profile.id) {
        Some(handle) => container(
            image(handle.clone())
                .width(Length::Fixed(84.0))
                .height(Length::Fixed(52.0))
                .content_fit(ContentFit::Cover),
        )
        .clip(true)
        .style(style::inset)
        .into(),
        None => container(muted(Icon::Profiles, crate::icons::size::MEDIUM))
            .width(Length::Fixed(84.0))
            .height(Length::Fixed(52.0))
            .center_x(Length::Fixed(84.0))
            .center_y(Length::Fixed(52.0))
            .style(style::inset)
            .into(),
    };

    let enabled_mods = profile.mods.enabled().count();
    let facts = format!(
        "Minecraft {} \u{00B7} {} \u{00B7} {} {} \u{00B7} {}",
        profile.version_id,
        profile.loader_kind().label(),
        enabled_mods,
        if enabled_mods == 1 { "mod" } else { "mods" },
        profile.optimizations.preset.label(),
    );

    let (health_tone, health_label) = health_of(app, profile, selected);

    let mut heading = row![
        text(&profile.display_name)
            .size(style::text::SECTION_TITLE)
            .font(style::FONT_SEMIBOLD)
            .color(style::TEXT),
        status_badge(health_label, health_tone),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center);

    if selected {
        heading = heading.push(status_badge("Selected", style::ACCENT_STRONG));
    }

    let mut body = column![
        heading,
        text(facts)
            .size(style::text::METADATA)
            .color(style::TEXT_MUTED),
        text(last_played(profile))
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT),
    ]
    .spacing(3)
    .width(Length::Fill);

    // Play is the point of a profile, so it is the primary action on every row
    // rather than something you get to after selecting one.
    let actions = row![
        action_button(
            if selected { "Play" } else { "Select" },
            ActionRole::Primary,
            Some(if selected {
                Message::Play
            } else {
                Message::SelectProfile(profile.id.clone())
            }),
        ),
        action_button(
            if editing { "Done" } else { "Edit" },
            ActionRole::Secondary,
            Some(Message::EditProfile(profile.id.clone())),
        ),
    ]
    .spacing(style::space::XS)
    .align_y(Alignment::Center);

    if editing {
        body = body.push(editor(app, profile));
    }

    container(
        row![art, body, actions]
            .spacing(style::space::MD)
            .align_y(Alignment::Center),
    )
    .padding(style::space::MD)
    .width(Length::Fill)
    .style(style::list_row(selected))
    .into()
}

/// The health badge.
///
/// Only the selected profile has a validation report, because validating every
/// profile on every frame would mean hitting the disk for each one. An
/// unchecked profile says so rather than claiming to be healthy.
fn health_of(app: &App, profile: &Profile, selected: bool) -> (iced::Color, &'static str) {
    if !profile.loader_kind().is_implemented() {
        return (style::WARN, "Loader unavailable");
    }
    if !selected {
        return (style::TEXT_FAINT, "Not checked");
    }
    match app.profile_report() {
        Some(report) if report.is_healthy() => (style::GOOD, "Healthy"),
        Some(report) => match report.health {
            ProfileHealth::Unusable => (style::BAD, "Cannot launch"),
            _ => (style::WARN, "Needs repair"),
        },
        None => (style::TEXT_FAINT, "Not checked"),
    }
}

fn last_played(profile: &Profile) -> String {
    if profile.updated_at == 0 {
        return "Never launched".to_owned();
    }
    let now = launcher_core::profiles::model::now();
    let elapsed = now.saturating_sub(profile.updated_at);
    let when = match elapsed {
        0..=90 => "moments ago".to_owned(),
        91..=5400 => format!("{} minutes ago", elapsed / 60),
        5401..=172_800 => format!("{} hours ago", elapsed / 3600),
        _ => format!("{} days ago", elapsed / 86_400),
    };
    format!("Last changed {when}")
}

/// Everything that used to be permanently on screen, behind Edit.
fn editor<'a>(app: &'a App, profile: &'a Profile) -> Element<'a, Message> {
    let loaders = row![
        loader_option(Loader::Vanilla, profile.loader_kind()),
        loader_option(Loader::LegacyFabric, profile.loader_kind()),
        loader_option(Loader::Forge, profile.loader_kind()),
    ]
    .spacing(style::space::XS);

    let runtime = row![
        labelled_input("Memory (MB)", app.heap_input(), Message::HeapChanged),
        labelled_input(
            "Extra JVM arguments",
            app.extra_args_input(),
            Message::ExtraArgsChanged,
        ),
    ]
    .spacing(style::space::MD);

    let mut body = column![
        divider(),
        section_label("Loader"),
        loaders,
        section_label("Runtime"),
        runtime,
        checkbox(
            "Keep the launcher open after the game starts",
            profile.keep_launcher_open,
        )
        .on_toggle(Message::KeepOpenChanged)
        .size(15)
        .text_size(style::text::BODY)
        .style(style::checkbox),
        row![
            action_button("Save", ActionRole::Primary, Some(Message::SaveProfile)),
            action_button(
                "Check",
                ActionRole::Secondary,
                Some(Message::ValidateProfile(profile.id.clone())),
            ),
            action_button(
                "Repair",
                ActionRole::Secondary,
                Some(Message::RepairProfile(profile.id.clone())),
            ),
            action_button(
                "Duplicate",
                ActionRole::Secondary,
                Some(Message::CloneProfile(profile.id.clone())),
            ),
            action_button(
                "Export",
                ActionRole::Ghost,
                Some(Message::ExportProfile(profile.id.clone())),
            ),
        ]
        .spacing(style::space::XS),
    ]
    .spacing(style::space::SM);

    if !profile.loader_kind().is_implemented() {
        body = body.push(
            text(format!(
                "{} is catalogued but not implemented; this profile cannot launch with it yet.",
                profile.loader_kind().label()
            ))
            .size(style::text::CAPTION)
            .color(style::WARN),
        );
    }

    // Deleting is the one action that can lose a world, so it is two presses
    // and the second one is a differently-worded button, not the same one
    // again. A double-click cannot walk through both.
    if app.delete_armed_for(&profile.id) {
        body = body.push(
            container(
                column![
                    text(format!("Move {} to recovery?", profile.display_name))
                        .size(style::text::ROW_TITLE)
                        .font(style::FONT_MEDIUM)
                        .color(style::TEXT),
                    text(
                        "The profile and its worlds stay on disk and can be restored. Shared \
                         game files are untouched."
                    )
                    .size(style::text::CAPTION)
                    .color(style::TEXT_MUTED),
                    row![
                        action_button(
                            "Move to recovery",
                            ActionRole::Danger,
                            Some(Message::ConfirmDeleteProfile(profile.id.clone())),
                        ),
                        action_button(
                            "Cancel",
                            ActionRole::Ghost,
                            Some(Message::CancelDeleteProfile),
                        ),
                    ]
                    .spacing(style::space::SM),
                ]
                .spacing(style::space::SM),
            )
            .padding(style::space::MD)
            .width(Length::Fill)
            .style(style::notice(style::BAD)),
        );
    } else {
        body = body.push(action_button(
            "Delete\u{2026}",
            ActionRole::Ghost,
            Some(Message::RequestDeleteProfile(profile.id.clone())),
        ));
    }

    if let Some(report) = app.profile_report() {
        if !report.is_healthy() {
            let mut defects = column![].spacing(2);
            for defect in report.defects.iter().take(6) {
                defects = defects.push(defect_row(defect));
            }
            body = body.push(section_label("Findings"));
            body = body.push(defects);
        }
    }

    body = body.push(detail("Game directory", game_directory_of(app, profile)));
    body.into()
}

fn game_directory_of(app: &App, profile: &Profile) -> String {
    app.core()
        .profile_dirs(&profile.id)
        .minecraft_dir()
        .display()
        .to_string()
}

fn defect_row(defect: &Defect) -> Element<'_, Message> {
    row![
        icon(
            if defect.is_blocking() {
                Icon::Warning
            } else {
                Icon::Info
            },
            crate::icons::size::SMALL,
            if defect.is_blocking() {
                style::BAD
            } else {
                style::WARN
            }
        ),
        text(defect.message())
            .size(style::text::CAPTION)
            .color(style::TEXT_MUTED)
            .width(Length::Fill),
    ]
    .spacing(style::space::XS)
    .align_y(Alignment::Center)
    .into()
}

fn loader_option<'a>(loader: Loader, current: Loader) -> Element<'a, Message> {
    let selected = loader == current;
    let mut label = row![text(loader.label()).size(style::text::BUTTON)]
        .spacing(style::space::XS)
        .align_y(Alignment::Center);
    if !loader.is_implemented() {
        label = label.push(
            text("soon")
                .size(style::text::BADGE)
                .color(style::WARN),
        );
    }
    button(label)
        .padding([5, style::space::MD])
        .style(style::chip_button(selected))
        .on_press(Message::LoaderChanged(loader))
        .into()
}

fn labelled_input<'a>(
    label: &'a str,
    value: &'a str,
    on_input: impl Fn(String) -> Message + 'a,
) -> Element<'a, Message> {
    column![
        text(label)
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT),
        text_input("", value)
            .on_input(on_input)
            .padding(style::space::SM)
            .size(style::text::BODY)
            .style(style::input),
    ]
    .spacing(3)
    .width(Length::Fill)
    .into()
}

fn section_label<'a>(label: &'a str) -> Element<'a, Message> {
    text(label)
        .size(style::text::BADGE)
        .font(style::FONT_SEMIBOLD)
        .color(style::TEXT_FAINT)
        .into()
}

fn divider<'a>() -> Element<'a, Message> {
    container(Space::new(Length::Fill, Length::Fixed(1.0)))
        .width(Length::Fill)
        .height(Length::Fixed(1.0))
        .style(style::hairline)
        .into()
}

fn create_row(app: &App) -> Element<'_, Message> {
    row![
        text_input("New profile name", app.new_profile_name())
            .on_input(Message::NewProfileNameChanged)
            .on_submit(Message::CreateProfile)
            .padding(style::space::SM)
            .size(style::text::BODY)
            .style(style::input)
            .width(Length::Fill),
        action_button("Create", ActionRole::Primary, Some(Message::CreateProfile)),
        action_button(
            "Import\u{2026}",
            ActionRole::Secondary,
            Some(Message::ImportProfile),
        ),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center)
    .into()
}
