//! Settings.
//!
//! # Structure
//!
//! A category rail on the left, compact rows on the right, and a search that
//! filters across every category at once. That last part is why search is worth
//! having: the reason people cannot find a setting is that they are looking in
//! the wrong category, so a search that only looked inside the open one would
//! solve nothing.
//!
//! # What is not here
//!
//! Performance. It has its own destination in the navigation rail. It is a
//! catalogue of eighteen optimizations with presets, evidence and per-entry
//! status — filing that under Settings would describe the product's main
//! feature as a preference.

use iced::widget::{button, column, container, row, text, text_input, Space};
use iced::{Alignment, ContentFit, Element, Length};

use crate::app::{App, Message, Screen};
use crate::background::BackgroundChoice;
use crate::components::{action_button, detail, gap, planned_notice, settings_row, ActionRole};
use crate::icons::{icon, muted, Icon};
use crate::style;

/// The categories, in the order they appear.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    General,
    Appearance,
    Game,
    Account,
    Storage,
    Privacy,
    Advanced,
}

impl Category {
    pub const ALL: [Self; 7] = [
        Self::General,
        Self::Appearance,
        Self::Game,
        Self::Account,
        Self::Storage,
        Self::Privacy,
        Self::Advanced,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::General => "General",
            Self::Appearance => "Appearance",
            Self::Game => "Game",
            Self::Account => "Account",
            Self::Storage => "Storage",
            Self::Privacy => "Privacy",
            Self::Advanced => "Advanced",
        }
    }

    /// Words that should match this category in search, beyond its rows'
    /// visible text.
    const fn keywords(self) -> &'static str {
        match self {
            Self::General => "updates start launcher version",
            Self::Appearance => "background video theme wallpaper motion",
            Self::Game => "java memory heap runtime loader minecraft",
            Self::Account => "microsoft sign in entitlement ownership",
            Self::Storage => "data directory disk files cache",
            Self::Privacy => "network telemetry offline hosts",
            Self::Advanced => "diagnostics logs experimental",
        }
    }
}

pub fn view(app: &App) -> Element<'_, Message> {
    let searching = !app.settings_search().trim().is_empty();

    let body: Element<'_, Message> = if searching {
        results(app)
    } else {
        category_body(app, app.settings_category())
    };

    row![
        rail(app),
        column![search_box(app), body]
            .spacing(style::space::MD)
            .width(Length::Fill),
    ]
    .spacing(style::space::LG)
    .width(Length::Fill)
    .into()
}

fn rail(app: &App) -> Element<'_, Message> {
    let searching = !app.settings_search().trim().is_empty();
    let mut items = column![].spacing(2).width(Length::Fixed(150.0));

    for category in Category::ALL {
        let selected = !searching && app.settings_category() == category;
        items = items.push(
            button(
                text(category.label())
                    .size(style::text::ROW_TITLE)
                    .font(style::FONT_MEDIUM),
            )
            .padding([style::space::SM, style::space::MD])
            .width(Length::Fill)
            .style(style::settings_tab(selected))
            .on_press(Message::SettingsCategoryChanged(category)),
        );
    }

    // Performance is a destination, not a category. It is listed here as a
    // pointer so that someone looking for it in Settings -- which is where
    // every other launcher puts it -- is sent to the right place rather than
    // concluding it does not exist.
    items = items.push(gap(style::space::MD));
    items = items.push(
        button(
            row![
                muted(Icon::Performance, crate::icons::size::SMALL),
                text("Performance")
                    .size(style::text::ROW_TITLE)
                    .font(style::FONT_MEDIUM),
                muted(Icon::ChevronRight, crate::icons::size::SMALL),
            ]
            .spacing(style::space::XS)
            .align_y(Alignment::Center),
        )
        .padding([style::space::SM, style::space::MD])
        .width(Length::Fill)
        .style(style::ghost_button)
        .on_press(Message::Navigate(Screen::Performance)),
    );

    items.into()
}

fn search_box(app: &App) -> Element<'_, Message> {
    container(
        row![
            muted(Icon::Search, crate::icons::size::MEDIUM),
            text_input("Search settings", app.settings_search())
                .on_input(Message::SettingsSearchChanged)
                .size(style::text::BODY)
                .padding(0)
                .style(style::bare_input)
                .width(Length::Fill),
        ]
        .spacing(style::space::SM)
        .align_y(Alignment::Center),
    )
    .padding([style::space::SM, style::space::MD])
    .width(Length::Fill)
    .style(style::inset)
    .into()
}

/// Search across every category at once.
fn results(app: &App) -> Element<'_, Message> {
    let needle = app.settings_search().trim().to_lowercase();
    let mut found = column![].spacing(style::space::LG);
    let mut any = false;

    for category in Category::ALL {
        let haystack = format!("{} {}", category.label(), category.keywords()).to_lowercase();
        if !haystack.contains(&needle) {
            continue;
        }
        any = true;
        found = found.push(
            column![
                text(category.label())
                    .size(style::text::BADGE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT_FAINT),
                category_body(app, category),
            ]
            .spacing(style::space::SM),
        );
    }

    if !any {
        return container(
            column![
                muted(Icon::Search, crate::icons::size::LARGE),
                text("Nothing matches")
                    .size(style::text::SECTION_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text("No setting matches that search.")
                    .size(style::text::BODY)
                    .color(style::TEXT_MUTED),
            ]
            .spacing(style::space::SM)
            .align_x(Alignment::Center),
        )
        .padding(style::space::XXL)
        .width(Length::Fill)
        .center_x(Length::Fill)
        .style(style::inset)
        .into();
    }

    found.into()
}

fn category_body(app: &App, category: Category) -> Element<'_, Message> {
    match category {
        Category::General => general(app),
        Category::Appearance => appearance(app),
        Category::Game => game(app),
        Category::Account => account(app),
        Category::Storage => storage(app),
        Category::Privacy => privacy(app),
        Category::Advanced => advanced(app),
    }
}

fn general(app: &App) -> Element<'_, Message> {
    let privacy = &app.core().settings().privacy;
    column![
        settings_row(
            "Check for launcher updates on start",
            "One request to our release host. No identifier, no cookie, no version reported \
             back. This is the only request made without you asking.",
            privacy.check_for_updates,
            Message::UpdateCheckChanged,
        ),
        detail("Version", launcher_core::PRODUCT_VERSION),
        detail("User agent", launcher_core::user_agent()),
    ]
    .spacing(style::space::XS)
    .into()
}

fn appearance(app: &App) -> Element<'_, Message> {
    let mut choices = row![].spacing(style::space::SM).width(Length::Fill);
    for choice in BackgroundChoice::ALL {
        choices = choices.push(background_option(app, choice));
    }

    let explanation = if app.background().selected().is_animated() {
        "Ten seconds of gameplay at 1280\u{00D7}720 and 30 frames per second, looping \
         seamlessly. The defocus and the grade are encoded into the frames rather than \
         applied while drawing, so playing one costs a single JPEG decode per frame on a \
         thread of its own. Playback stops while Minecraft is running and while the window \
         is minimised."
    } else {
        "Animation is off. The launcher paints its own midnight surface \u{2014} which is \
         also what sits underneath the footage, so that changing background never shows a \
         black window."
    };

    column![
        choices,
        text(explanation)
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT),
        text("The same choices are one click away in the bottom bar.")
            .size(style::text::CAPTION)
            .color(style::TEXT_FAINT),
    ]
    .spacing(style::space::MD)
    .into()
}

fn background_option(app: &App, choice: BackgroundChoice) -> Element<'_, Message> {
    let selected = app.background().selected() == choice;
    let installed = app.background().is_installed(choice);

    let preview: Element<'_, Message> = match app.background().thumbnail(choice) {
        Some(handle) => iced::widget::image(handle.clone())
            .width(Length::Fill)
            .height(Length::Fixed(66.0))
            .content_fit(ContentFit::Cover)
            .into(),
        // Static has no footage. Showing the surface it actually produces is a
        // more honest preview than a grey rectangle labelled "none".
        None => container(Space::new(Length::Fill, Length::Fixed(66.0)))
            .width(Length::Fill)
            .height(Length::Fixed(66.0))
            .style(style::static_midnight)
            .into(),
    };

    let marker: Element<'_, Message> = if selected {
        icon(Icon::Check, crate::icons::size::SMALL, style::ACCENT_STRONG)
    } else if !installed {
        text("Missing")
            .size(style::text::BADGE)
            .color(style::WARN)
            .into()
    } else {
        Space::with_width(Length::Fixed(0.0)).into()
    };

    let mut control = button(
        column![
            preview,
            row![
                text(choice.label())
                    .size(style::text::METADATA)
                    .font(style::FONT_MEDIUM)
                    .width(Length::Fill),
                marker,
            ]
            .width(Length::Fill)
            .align_y(Alignment::Center),
        ]
        .spacing(style::space::XS),
    )
    .padding(style::space::XS)
    .width(Length::Fill)
    .style(style::background_option(selected));

    if installed {
        control = control.on_press(Message::BackgroundSelected(choice));
    }
    control.into()
}

fn game(app: &App) -> Element<'_, Message> {
    let mut body = column![].spacing(style::space::SM);

    if app.java().is_empty() {
        body = body.push(
            text("No Java runtime has been found yet.")
                .size(style::text::BODY)
                .color(style::TEXT_MUTED),
        );
    } else {
        for install in app.java() {
            let suitable = install.major == 8;
            body = body.push(
                container(
                    row![
                        icon(
                            if suitable { Icon::Check } else { Icon::Warning },
                            crate::icons::size::MEDIUM,
                            if suitable { style::GOOD } else { style::WARN }
                        ),
                        column![
                            text(format!("Java {}", install.major))
                                .size(style::text::ROW_TITLE)
                                .font(style::FONT_MEDIUM)
                                .color(style::TEXT),
                            text(install.path.display().to_string())
                                .size(style::text::CAPTION)
                                .color(style::TEXT_FAINT),
                        ]
                        .spacing(2)
                        .width(Length::Fill),
                    ]
                    .spacing(style::space::MD)
                    .align_y(Alignment::Center),
                )
                .padding(style::ROW_PADDING)
                .width(Length::Fill)
                .style(style::settings_row_surface),
            );
        }
    }

    body = body.push(action_button(
        "Scan for Java runtimes",
        ActionRole::Secondary,
        Some(Message::RefreshJava),
    ));
    body = body.push(
        text(
            "Minecraft 1.8.9 declares Java 8 and uses LWJGL 2, so a modern JVM will not run \
             it without work this launcher has not done.",
        )
        .size(style::text::CAPTION)
        .color(style::TEXT_FAINT),
    );
    body = body.push(planned_notice(
        "automatic Java download",
        "The launcher finds Java you already have. Shipping an OpenJDK build means inheriting \
         its redistribution terms and its security patching, which is a decision to make \
         deliberately.",
    ));
    body.into()
}

fn account(app: &App) -> Element<'_, Message> {
    let signed_in = app.core().identity().is_some();
    column![
        text(if signed_in {
            "You are signed in. Account details and sign-out are on the Account screen."
        } else {
            "You are not signed in. Signing in is required before Minecraft can launch."
        })
        .size(style::text::BODY)
        .color(style::TEXT_MUTED),
        action_button(
            "Open Account",
            ActionRole::Secondary,
            Some(Message::Navigate(Screen::Account)),
        ),
    ]
    .spacing(style::space::MD)
    .into()
}

fn storage(app: &App) -> Element<'_, Message> {
    column![
        detail(
            "Data directory",
            app.core().paths().root().display().to_string()
        ),
        text(
            "Profiles, mods, logs and downloaded game files all live under this directory. \
             Deleting a profile moves it into recovery storage rather than removing it."
        )
        .size(style::text::CAPTION)
        .color(style::TEXT_FAINT),
        action_button(
            "Open Recovery",
            ActionRole::Secondary,
            Some(Message::Navigate(Screen::Recovery)),
        ),
    ]
    .spacing(style::space::MD)
    .into()
}

fn privacy(app: &App) -> Element<'_, Message> {
    let settings = &app.core().settings().privacy;
    column![
        settings_row(
            "Offline mode",
            "Refuse every outbound request, including sign-in.",
            settings.offline_mode,
            Message::OfflineModeChanged,
        ),
        settings_row(
            "Let the launcher download a Java runtime",
            "Off by default. Not implemented yet either way.",
            settings.managed_java,
            Message::ManagedJavaChanged,
        ),
        settings_row(
            "Allow mod repository access",
            "Off by default. Even when on, each mod needs a separate, explicit action.",
            settings.mod_repositories,
            Message::ModRepositoriesChanged,
        ),
        action_button(
            "See everything Fathom can reach",
            ActionRole::Ghost,
            Some(Message::Navigate(Screen::Privacy)),
        ),
    ]
    .spacing(style::space::XS)
    .into()
}

fn advanced(app: &App) -> Element<'_, Message> {
    column![
        text(
            "Fathom keeps a log file for the current session. It contains no credentials: \
             tokens are scrubbed before anything is written."
        )
        .size(style::text::BODY)
        .color(style::TEXT_MUTED),
        detail(
            "Log directory",
            app.core().paths().launcher_logs_dir().display().to_string()
        ),
        action_button(
            "Open Recovery",
            ActionRole::Secondary,
            Some(Message::Navigate(Screen::Recovery)),
        ),
    ]
    .spacing(style::space::MD)
    .into()
}
