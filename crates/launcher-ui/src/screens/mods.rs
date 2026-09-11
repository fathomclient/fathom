//! Mods.
//!
//! # What this page is for
//!
//! Answering three questions in the order people ask them: what is installed,
//! is any of it broken, and how do I change it. The previous version answered
//! the first with a list of file names and the second not at all.
//!
//! # What is real here
//!
//! Every control on this page maps onto something `launcher_core::mods` can
//! actually do: the store is content-addressed, `resolve` checks the set
//! against the profile's Minecraft version and loader and returns real
//! problems, and `materialize` writes the effective set into the game
//! directory before launch. Nothing is mocked.
//!
//! # What is deliberately absent
//!
//! There is no browse-and-install list, because there is no source integration
//! to back one. A grid of mods that cannot be installed would be the most
//! convincing-looking part of the launcher and the least real. The one thing
//! that changes that is a signed index with a licence to redistribute from, and
//! until it exists this page adds jars the user already has.

use iced::widget::{button, column, container, row, text, text_input, Space};
use iced::{Alignment, Element, Length};

use launcher_core::mods::{InstalledMod, ModId};
use launcher_core::profiles::Profile;

use crate::app::{App, Message, Screen};
use crate::components::{action_button, gap, status_badge, toggle, ActionRole};
use crate::icons::{icon, muted, Icon};
use crate::style;

/// The shelves a mod can sit on. Derived, never stored: a filter is a question
/// about the data, not a property of it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Filter {
    All,
    Enabled,
    Disabled,
    UserSupplied,
    Issues,
}

impl Filter {
    pub const ALL: [Self; 5] = [
        Self::All,
        Self::Enabled,
        Self::Disabled,
        Self::UserSupplied,
        Self::Issues,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Enabled => "Enabled",
            Self::Disabled => "Disabled",
            Self::UserSupplied => "You added",
            Self::Issues => "Issues",
        }
    }
}

pub fn view(app: &App) -> Element<'_, Message> {
    let Some(profile) = app.selected_profile() else {
        return empty_page(
            "No profile selected",
            "Mods belong to a profile. Choose one first.",
            Some(("Open Profiles", Message::Navigate(Screen::Profiles))),
        );
    };

    let mut page = column![].spacing(style::space::MD).width(Length::Fill);
    page = page.push(loader_state(profile));
    page = page.push(controls(app));

    let rows = visible_mods(app, profile);
    if rows.is_empty() {
        page = page.push(no_results(app, profile));
    } else {
        let mut list = column![].spacing(style::space::XXS);
        for entry in rows {
            list = list.push(mod_row(app, profile, entry));
        }
        page = page.push(list);
    }

    page = page.push(footer(app, profile));
    page.into()
}

/// Whether the selected loader can load anything at all.
///
/// The single most useful thing this page can say. A profile on Vanilla with
/// five mods installed loads none of them, and no amount of per-row detail
/// communicates that as well as one line at the top.
fn loader_state(profile: &Profile) -> Element<'_, Message> {
    let loader = profile.loader_kind();
    if loader.is_implemented() && loader.supports_mods() {
        return Space::with_height(Length::Fixed(0.0)).into();
    }

    let (tone, headline, detail) = if loader.supports_mods() {
        (
            style::WARN,
            format!("{} is not implemented yet", loader.label()),
            "Mods listed here are stored and configured, but this profile cannot launch \
             until the loader lands."
                .to_owned(),
        )
    } else {
        (
            style::WARN,
            "Vanilla loads no mods".to_owned(),
            "This profile has no mod loader, so nothing below will be loaded when you \
             play. Change the loader in Profiles to use these."
                .to_owned(),
        )
    };

    container(
        row![
            icon(Icon::Warning, crate::icons::size::MEDIUM, tone),
            column![
                text(headline)
                    .size(style::text::ROW_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text(detail)
                    .size(style::text::METADATA)
                    .color(style::TEXT_MUTED),
            ]
            .spacing(2)
            .width(Length::Fill),
            action_button(
                "Change loader",
                ActionRole::Secondary,
                Some(Message::Navigate(Screen::Profiles)),
            ),
        ]
        .spacing(style::space::MD)
        .align_y(Alignment::Center),
    )
    .padding(style::CARD_PADDING)
    .width(Length::Fill)
    .style(style::notice(tone))
    .into()
}

/// Search and filters.
fn controls(app: &App) -> Element<'_, Message> {
    let search = row![
        muted(Icon::Search, crate::icons::size::MEDIUM),
        text_input("Search mods", app.mod_search())
            .on_input(Message::ModSearchChanged)
            .size(style::text::BODY)
            .padding(0)
            .style(style::bare_input)
            .width(Length::Fill),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center);

    let mut filters = row![].spacing(style::space::XXS).align_y(Alignment::Center);
    for filter in Filter::ALL {
        let selected = app.mod_filter() == filter;
        let count = count_for(app, filter);
        filters = filters.push(
            button(
                row![
                    text(filter.label()).size(style::text::BUTTON),
                    text(count.to_string())
                        .size(style::text::BADGE)
                        .color(if selected {
                            style::TEXT_MUTED
                        } else {
                            style::TEXT_FAINT
                        }),
                ]
                .spacing(style::space::XS)
                .align_y(Alignment::Center),
            )
            .padding([4, style::space::SM])
            .style(style::chip_button(selected))
            .on_press(Message::ModFilterChanged(filter)),
        );
    }

    row![
        container(search)
            .padding([style::space::SM, style::space::MD])
            .width(Length::Fill)
            .style(style::inset),
        filters,
    ]
    .spacing(style::space::MD)
    .align_y(Alignment::Center)
    .into()
}

fn count_for(app: &App, filter: Filter) -> usize {
    let Some(profile) = app.selected_profile() else {
        return 0;
    };
    app.mods()
        .iter()
        .filter(|entry| matches_filter(app, profile, entry, filter))
        .count()
}

fn matches_filter(app: &App, profile: &Profile, entry: &InstalledMod, filter: Filter) -> bool {
    let configured = configured_for(profile, entry);
    match filter {
        Filter::All => true,
        Filter::Enabled => configured.map_or(!entry.managed, |m| m.enabled),
        Filter::Disabled => configured.is_some_and(|m| !m.enabled),
        Filter::UserSupplied => !entry.managed,
        Filter::Issues => issue_for(app, profile, entry).is_some(),
    }
}

fn configured_for<'a>(
    profile: &'a Profile,
    entry: &InstalledMod,
) -> Option<&'a launcher_core::mods::ProfileMod> {
    profile
        .mods
        .iter()
        .find(|configured| configured.sha1 == entry.sha1)
}

/// The one-line problem with a mod, if there is one.
///
/// Only things that are actually determinable from what the launcher knows. A
/// dependency graph would need metadata these jars do not carry, and inventing
/// one would be worse than saying nothing.
fn issue_for(app: &App, profile: &Profile, entry: &InstalledMod) -> Option<String> {
    if !profile.loader_kind().supports_mods() {
        return Some("This profile's loader does not load mods.".to_owned());
    }
    if configured_for(profile, entry).is_none() && entry.managed {
        return Some("Placed by Fathom but no longer configured. Rebuild to remove.".to_owned());
    }
    if !app.mod_store_has(&entry.sha1) && entry.managed {
        return Some("Missing from the mod store. Rebuild to restore it.".to_owned());
    }
    None
}

fn visible_mods<'a>(app: &'a App, profile: &Profile) -> Vec<&'a InstalledMod> {
    let needle = app.mod_search().trim().to_lowercase();
    app.mods()
        .iter()
        .filter(|entry| matches_filter(app, profile, entry, app.mod_filter()))
        .filter(|entry| {
            needle.is_empty()
                || entry.display_name().to_lowercase().contains(&needle)
                || entry.file_name.to_lowercase().contains(&needle)
        })
        .collect()
}

fn mod_row<'a>(
    app: &'a App,
    profile: &'a Profile,
    entry: &'a InstalledMod,
) -> Element<'a, Message> {
    let configured = configured_for(profile, entry);
    let enabled = configured.map_or(!entry.managed, |m| m.enabled);
    let issue = issue_for(app, profile, entry);
    let expanded = app.expanded_mod() == Some(&entry.sha1);

    let source = if entry.managed {
        "Fathom"
    } else {
        "You added"
    };

    let mut heading = row![
        text(entry.display_name())
            .size(style::text::ROW_TITLE)
            .font(style::FONT_MEDIUM)
            .color(style::TEXT),
        status_badge(source, if entry.managed { style::ACCENT_STRONG } else { style::TEXT_MUTED }),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center);

    if let Some(version) = configured.and_then(|m| m.version.as_ref()) {
        heading = heading.push(
            text(version.as_str())
                .size(style::text::METADATA)
                .color(style::TEXT_FAINT),
        );
    }
    if issue.is_some() {
        heading = heading.push(status_badge("Issue", style::WARN));
    }

    let secondary = issue.clone().unwrap_or_else(|| {
        format!(
            "{} \u{00B7} {}",
            launcher_core::services::human_bytes(entry.size),
            entry.short_hash()
        )
    });

    let mut body = column![
        heading,
        text(secondary)
            .size(style::text::METADATA)
            .color(if issue.is_some() {
                style::WARN
            } else {
                style::TEXT_MUTED
            }),
    ]
    .spacing(3)
    .width(Length::Fill);

    if expanded {
        body = body.push(details(entry, configured));
    }

    let disclose = button(icon(
        if expanded { Icon::ChevronDown } else { Icon::ChevronRight },
        crate::icons::size::MEDIUM,
        style::TEXT_MUTED,
    ))
    .padding(style::space::XS)
    .style(style::ghost_button)
    .on_press(Message::ModExpanded(entry.sha1.clone()));

    let controls: Element<'_, Message> = match ModId::new(&entry.file_name).ok() {
        Some(id) => toggle(enabled, Message::ToggleMod(id, !enabled)),
        // A jar whose name is not a valid id can still be shown, but it cannot
        // be addressed, so there is nothing honest to offer.
        None => text("Unmanaged")
            .size(style::text::CAPTION)
            .color(style::TEXT_DISABLED)
            .into(),
    };

    container(
        row![disclose, body, controls]
            .spacing(style::space::SM)
            .align_y(Alignment::Center),
    )
    .padding(style::ROW_PADDING)
    .width(Length::Fill)
    .style(style::list_row(expanded))
    .into()
}

/// The deep detail, shown only when asked for.
///
/// The file path is here and not in the row, because a raw AppData path is the
/// single most developer-looking thing an interface can put in front of someone
/// who just wants to turn a mod off.
fn details<'a>(
    entry: &'a InstalledMod,
    configured: Option<&'a launcher_core::mods::ProfileMod>,
) -> Element<'a, Message> {
    let mut rows = column![
        detail_row("File", entry.file_name.clone()),
        detail_row("SHA-1", entry.sha1.clone()),
        detail_row(
            "Managed by",
            if entry.managed {
                "Fathom placed this file from its store."
            } else {
                "You put this file here. Fathom will not move or replace it."
            },
        ),
    ]
    .spacing(3);

    if let Some(reason) = configured.and_then(|m| m.disabled_reason.as_ref()) {
        rows = rows.push(detail_row("Disabled because", format!("{reason:?}")));
    }
    rows = rows.push(detail_row("Location", entry.path.display().to_string()));

    // Removing lives in the detail rather than on the row. It is destructive
    // and rare; the toggle is neither, and putting them side by side is how a
    // mis-click becomes a deleted file.
    if let Ok(id) = ModId::new(&entry.file_name) {
        rows = rows.push(gap(style::space::XS));
        rows = rows.push(action_button(
            "Remove from this profile",
            ActionRole::Danger,
            Some(Message::RemoveMod(id)),
        ));
    }

    container(rows)
        .padding(iced::Padding {
            top: f32::from(style::space::SM),
            right: 0.0,
            bottom: 0.0,
            left: 0.0,
        })
        .width(Length::Fill)
        .into()
}

fn detail_row<'a>(label: &'a str, value: impl Into<String>) -> Element<'a, Message> {
    row![
        text(label)
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT)
            .width(Length::Fixed(110.0)),
        text(value.into())
            .size(style::text::CAPTION)
            .color(style::TEXT_MUTED)
            .width(Length::Fill),
    ]
    .spacing(style::space::SM)
    .into()
}

fn no_results<'a>(app: &'a App, _profile: &Profile) -> Element<'a, Message> {
    if app.mods().is_empty() {
        return empty_page(
            "No mods in this profile",
            "Add a jar you already have. Fathom stores it by content hash, so the same file \
             shared between profiles is stored once.",
            Some(("Add mod jar", Message::PickModFile)),
        );
    }
    empty_page(
        "Nothing matches",
        "No mod matches that search and filter.",
        None,
    )
}

fn empty_page<'a>(
    title: &'a str,
    body: &'a str,
    action: Option<(&'a str, Message)>,
) -> Element<'a, Message> {
    let mut content = column![
        muted(Icon::Mods, crate::icons::size::LARGE),
        text(title)
            .size(style::text::SECTION_TITLE)
            .font(style::FONT_SEMIBOLD)
            .color(style::TEXT),
        text(body)
            .size(style::text::BODY)
            .color(style::TEXT_MUTED),
    ]
    .spacing(style::space::SM)
    .align_x(Alignment::Center)
    .max_width(430.0);

    if let Some((label, message)) = action {
        content = content.push(action_button(label, ActionRole::Primary, Some(message)));
    }

    container(content)
        .padding(style::space::XXL)
        .width(Length::Fill)
        .center_x(Length::Fill)
        .style(style::inset)
        .into()
}

/// Actions that apply to the whole set.
fn footer<'a>(app: &'a App, profile: &Profile) -> Element<'a, Message> {
    let enabled = profile.mods.enabled().count();
    let total = app.mods().len();

    let summary = text(format!(
        "{enabled} of {total} enabled \u{00B7} the effective set is written into the game \
         directory before launch"
    ))
    .size(style::text::CAPTION)
    .color(style::TEXT_FAINT);

    row![
        container(summary).width(Length::Fill),
        action_button("Add mod jar", ActionRole::Primary, Some(Message::PickModFile)),
        action_button(
            "Rebuild folder",
            ActionRole::Secondary,
            Some(Message::MaterializeMods),
        ),
        action_button(
            "Open folder",
            ActionRole::Ghost,
            Some(Message::OpenModsFolder),
        ),
    ]
    .spacing(style::space::SM)
    .align_y(Alignment::Center)
    .into()
}
