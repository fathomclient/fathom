//! Performance.
//!
//! # Why this is a page and not a settings tab
//!
//! The first-party optimization module is one of the things Fathom *is*. It has
//! its own registry of eighteen catalogued optimizations, its own presets, its
//! own benchmark methodology and its own rules about what may be switched on.
//! Putting that behind a tab in Settings would file it as a preference.
//!
//! # What this page refuses to do
//!
//! It does not claim a frame rate. Not once, anywhere.
//!
//! The Java registry is unambiguous about the state of the work: nothing is
//! enabled by default, the only evidence that exists is isolated
//! microbenchmarks, and the Fabric layer has never been compiled. So the two
//! JVM flags — which really are passed to Java on every launch, and are covered
//! by tests — are shown as active, and every game-side optimization is shown as
//! needing a module that is not built yet. Five of those have working,
//! unit-tested core logic, and the page says exactly that rather than rounding
//! it up to "on".
//!
//! A page that offered eighteen toggles which all did nothing would look far
//! more finished than this one. It would also be a lie, and the first time a
//! user compared their frame rate before and after, they would know.

use iced::widget::{button, column, container, row, text, Space};
use iced::{Alignment, Element, Length};

use launcher_core::optimizations::{
    self, Category, Group, Optimization, Readiness, Status, Surface,
};
use launcher_core::profiles::OptimizationPreset;

use crate::app::{App, Message, Screen};
use crate::components::status_badge;
use crate::icons::{icon, Icon};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    let mut page = column![].spacing(style::space::LG).width(Length::Fill);

    page = page.push(module_state());
    page = page.push(presets(app));

    for group in Group::ALL {
        let entries = optimizations::in_group(group);
        if entries.is_empty() {
            continue;
        }
        page = page.push(group_section(app, group, &entries));
    }

    page = page.push(memory_section(app));
    page.into()
}

/// The honest headline: what is actually running.
fn module_state<'a>() -> Element<'a, Message> {
    let active = optimizations::active_count();
    let total = optimizations::all().len();

    let headline = row![
        icon(Icon::Warning, crate::icons::size::LARGE, style::WARN),
        column![
            text("The optimization module is not installed")
                .size(style::text::SECTION_TITLE)
                .font(style::FONT_SEMIBOLD)
                .color(style::TEXT),
            text(format!(
                "{active} of {total} optimizations are doing something on this machine. \
                 The {} game-side entries below need fathom-performance, which is not \
                 built or shipped in this release.",
                total - active
            ))
            .size(style::text::BODY)
            .color(style::TEXT_MUTED),
        ]
        .spacing(2)
        .width(Length::Fill),
    ]
    .spacing(style::space::MD)
    .align_y(Alignment::Center);

    let note = text(
        "Nothing here has been benchmarked inside Minecraft. The measurements quoted on \
         individual rows are isolated microbenchmarks: they show a mechanism works, not that \
         the game got faster.",
    )
    .size(style::text::CAPTION)
    .color(style::TEXT_FAINT);

    container(column![headline, note].spacing(style::space::MD))
        .padding(style::CARD_PADDING)
        .width(Length::Fill)
        .style(style::notice(style::WARN))
        .into()
}

fn presets(app: &App) -> Element<'_, Message> {
    let current = app.optimization_preset();

    let mut choices = row![].spacing(style::space::SM);
    for preset in OptimizationPreset::ALL {
        let selected = preset == current;
        choices = choices.push(
            button(
                column![
                    text(preset.label())
                        .size(style::text::ROW_TITLE)
                        .font(style::FONT_SEMIBOLD),
                    text(preset.description())
                        .size(style::text::CAPTION)
                        .color(style::TEXT_MUTED),
                ]
                .spacing(3),
            )
            .padding(style::space::MD)
            .width(Length::Fill)
            .style(style::preset_button(selected))
            .on_press(Message::PresetSelected(preset)),
        );
    }

    section(
        "Preset",
        Some(
            "Presets mirror the module's own, which are about what has been measured rather \
             than about how fast something sounds. There is no \"Maximum FPS\" preset because \
             no in-game benchmark has been run.",
        ),
        choices.into(),
    )
}

fn group_section<'a>(
    app: &'a App,
    group: Group,
    entries: &[Optimization],
) -> Element<'a, Message> {
    let mut rows = column![].spacing(style::space::XXS);
    for entry in entries {
        rows = rows.push(optimization_row(app, *entry));
    }
    section(group.label(), None, rows.into())
}

fn optimization_row<'a>(app: &'a App, entry: Optimization) -> Element<'a, Message> {
    let enabled = app.optimization_enabled(entry.id);

    let (tone, state) = match entry.status {
        Status::Active => (style::GOOD, entry.readiness.label()),
        Status::Planned => match entry.readiness {
            Readiness::CoreWritten => (style::ACCENT_STRONG, "Core written"),
            _ => (style::TEXT_FAINT, "Planned"),
        },
    };

    let mut heading = row![
        text(entry.name)
            .size(style::text::ROW_TITLE)
            .font(style::FONT_MEDIUM)
            .color(if entry.is_actionable() {
                style::TEXT
            } else {
                style::TEXT_MUTED
            }),
        status_badge(state, tone),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center);

    if entry.category != Category::Safe {
        heading = heading.push(status_badge(
            entry.category.label(),
            if entry.category == Category::Experimental {
                style::WARN
            } else {
                style::TEXT_MUTED
            },
        ));
    }

    let mut detail = column![
        heading,
        text(entry.summary)
            .size(style::text::METADATA)
            .color(style::TEXT_MUTED),
    ]
    .spacing(4)
    .width(Length::Fill);

    if let Some(evidence) = entry.evidence {
        detail = detail.push(
            text(format!("Measured: {evidence}"))
                .size(style::text::CAPTION)
                .color(style::TEXT_FAINT),
        );
    }

    // The control is only offered where flipping it changes what the game
    // does. Everywhere else it is a state label -- a toggle that silently does
    // nothing is worse than no toggle.
    let control: Element<'_, Message> = if entry.is_actionable() {
        crate::components::toggle(enabled, Message::OptimizationToggled(entry.id.to_owned()))
    } else {
        text("Unavailable")
            .size(style::text::CAPTION)
            .color(style::TEXT_DISABLED)
            .into()
    };

    container(
        row![detail, container(control).width(Length::Shrink)]
            .spacing(style::space::MD)
            .align_y(Alignment::Center),
    )
    .padding(style::ROW_PADDING)
    .width(Length::Fill)
    .style(style::settings_row)
    .into()
}

/// Java and memory, kept separate from game optimizations on purpose: they are
/// a different mechanism with a different failure mode, and lumping them
/// together is what made the old build look like it had one settings list.
fn memory_section(app: &App) -> Element<'_, Message> {
    let Some(profile) = app.selected_profile() else {
        return section(
            "Memory and Java",
            Some("Select a profile to configure its runtime."),
            Space::with_height(Length::Fixed(0.0)).into(),
        );
    };

    let java = match app.readiness().java.as_ref() {
        Some(found) => format!("Java {} — {}", found.major, found.path.display()),
        None => "No compatible runtime found".to_owned(),
    };

    let rows = column![
        labelled(
            "Allocated memory",
            format!("{} MB heap", profile.memory.heap_mb),
            "-Xms and -Xmx are set to the same value, so the heap never resizes mid-game.",
        ),
        labelled("Java runtime", java, "Minecraft 1.8.9 requires Java 8."),
    ]
    .spacing(style::space::XXS);

    let edit = button(
        text("Edit in Profiles")
            .size(style::text::BUTTON)
            .font(style::FONT_MEDIUM),
    )
    .padding(style::CONTROL_PADDING)
    .style(style::secondary_button)
    .on_press(Message::Navigate(Screen::Profiles));

    section(
        "Memory and Java",
        Some("Applied by the launcher on every launch, module or no module."),
        column![rows, edit].spacing(style::space::MD).into(),
    )
}

fn labelled<'a>(
    label: &'a str,
    value: impl Into<String>,
    note: &'a str,
) -> Element<'a, Message> {
    container(
        row![
            column![
                text(label)
                    .size(style::text::ROW_TITLE)
                    .font(style::FONT_MEDIUM)
                    .color(style::TEXT),
                text(note)
                    .size(style::text::CAPTION)
                    .color(style::TEXT_FAINT),
            ]
            .spacing(3)
            .width(Length::Fill),
            text(value.into())
                .size(style::text::METADATA)
                .font(style::FONT_MEDIUM)
                .color(style::TEXT),
        ]
        .spacing(style::space::MD)
        .align_y(Alignment::Center),
    )
    .padding(style::ROW_PADDING)
    .width(Length::Fill)
    .style(style::settings_row)
    .into()
}

/// A page section: a heading and its content, with no box around it.
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

/// Surface is part of the model but not currently shown as its own column;
/// referencing it keeps the import honest if the grouping changes.
#[allow(dead_code)]
fn is_java(entry: &Optimization) -> bool {
    entry.surface == Surface::Java
}
