//! Account.
//!
//! Microsoft sign-in via the device-code flow: the user reads a code here and
//! types it on Microsoft's own page. This launcher never sees a password, and
//! the verification URL is shown as text with a copy button rather than being
//! opened automatically.

use iced::widget::{button, column, container, row, text, Space};
use iced::{Alignment, Element, Length};

use crate::app::{App, Message, SignIn};
use crate::components::{code, detail, gap, panel, planned_notice, status_badge};
use crate::style;

pub fn view(app: &App) -> Element<'_, Message> {
    let mut sections = column![].spacing(style::space::LG).width(Length::Fill);

    sections = sections.push(auth_state_row(app));

    // The account screen reflects the state machine rather than second-guessing
    // it, so there is no way for the two to disagree.
    sections = sections.push(match app.core().identity() {
        Some(identity) if identity.kind.is_production() => {
            signed_in(identity, app.core().auth().entitlement())
        }
        Some(identity) => signed_in_development(identity),
        None => signed_out(app),
    });

    // -- what is stored ----------------------------------------------------

    let persistent = app.core().credential_store_is_persistent();
    let mut storage = column![
        detail(
            "Credential store",
            app.core().credential_store_description()
        ),
        detail(
            "Stored on this machine",
            "Microsoft refresh token only \u{2014} nothing else",
        ),
        detail(
            "Never stored",
            "Your password, and every game session token"
        ),
    ]
    .spacing(8);

    if !persistent {
        storage = storage.push(gap(4));
        storage = storage.push(
            container(
                text(
                    "No OS credential store is available here, so the session will not survive \
                     a restart. The launcher deliberately does not fall back to writing the \
                     token to a plain file.",
                )
                .size(12),
            )
            .padding(12)
            .width(Length::Fill)
            .style(style::notice(style::WARN)),
        );
    }

    sections = sections.push(panel(
        "What is kept, and where",
        Some("The only persisted secret is a token you can revoke from your Microsoft account."),
        storage,
    ));

    sections = sections.push(development_option(app));

    sections.into()
}

fn signed_in<'a>(
    identity: &'a launcher_core::auth::provider::MinecraftIdentity,
    entitlement: Option<&'a launcher_core::auth::EntitlementProof>,
) -> Element<'a, Message> {
    let body = column![
        row![
            container(
                text(
                    identity
                        .profile
                        .name
                        .chars()
                        .next()
                        .unwrap_or('M')
                        .to_string()
                )
                .size(style::type_size::SECTION_TITLE)
                .font(style::FONT_SEMIBOLD),
            )
            .center(Length::Fixed(42.0))
            .style(style::brand_mark),
            column![
                text(&identity.profile.name)
                    .size(style::type_size::SECTION_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text(&identity.profile.id)
                    .size(style::type_size::CAPTION)
                    .color(style::TEXT_FAINT),
            ]
            .spacing(3)
            .width(Length::Fill),
            status_badge("Entitlement verified", style::GOOD),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
        gap(2),
        text(entitlement.map_or_else(
            || "Entitlement not yet verified.".to_owned(),
            |e| e.ownership.explanation(),
        ))
        .size(13)
        .color(style::TEXT_MUTED),
        gap(6),
        text(
            "The entitlement came from a response signed by Mojang, and the signature was \
             checked against a key compiled into this binary. The unsigned part of the response \
             is not trusted."
        )
        .size(12)
        .color(style::TEXT_FAINT),
        gap(6),
        button(text("Sign out").size(13))
            .padding([9, 16])
            .style(style::danger_button)
            .on_press(Message::SignOut),
    ]
    .spacing(8);

    panel("Signed in", None, body)
}

fn signed_out(app: &App) -> Element<'_, Message> {
    if !app.core().sign_in_available() {
        return panel(
            "Sign-in unavailable in this build",
            Some("This is a configuration gap, not a bug."),
            column![
                planned_notice(
                    "Microsoft sign-in",
                    "Microsoft requires every launcher to register its own Azure application and \
                     be approved for the Minecraft API before api.minecraftservices.com will \
                     answer it. Unapproved applications get HTTP 403. No client id is compiled \
                     into this build, and borrowing another launcher's would be both dishonest \
                     and fragile.",
                ),
                gap(4),
                text("To run this build against your own registration:")
                    .size(13)
                    .color(style::TEXT),
                code("FATHOM_AZURE_CLIENT_ID=<your-application-id>"),
                text(
                    "The application must use the `consumers` tenant and request the \
                     XboxLive.signin and offline_access scopes."
                )
                .size(12)
                .color(style::TEXT_FAINT),
            ]
            .spacing(10),
        );
    }

    match app.sign_in() {
        SignIn::Starting => panel(
            "Contacting Microsoft",
            Some("Asking for a sign-in code."),
            column![
                container(
                    column![
                        text("Preparing secure sign-in\u{2026}")
                            .size(style::type_size::BODY)
                            .font(style::FONT_MEDIUM)
                            .color(style::TEXT),
                        text("The rest of the launcher remains available.")
                            .size(style::type_size::SUPPORTING)
                            .color(style::TEXT_MUTED),
                    ]
                    .spacing(style::space::XS),
                )
                .padding(style::space::MD)
                .width(Length::Fill)
                .style(style::inset),
                button(text("Cancel").size(13))
                    .padding(style::CONTROL_PADDING)
                    .style(style::ghost_button)
                    .on_press(Message::CancelSignIn),
            ]
            .spacing(style::space::SM),
        ),
        SignIn::Idle => panel(
            "Sign in to play",
            Some("Required: this launcher will not start the game without a verified entitlement."),
            column![
                text(
                    "You will be given a short code to enter on Microsoft's own sign-in page. \
                     Your password is never typed into this window and never passes through this \
                     program."
                )
                .size(13)
                .color(style::TEXT_MUTED),
                gap(6),
                sign_in_button(app.worker_ready()),
            ]
            .spacing(8),
        ),
        SignIn::AwaitingUser(prompt) => panel(
            "Finish signing in",
            Some("Two steps, in your browser."),
            column![
                text("1. Open this page:").size(13).color(style::TEXT),
                code(prompt.verification_uri.clone()),
                row![
                    button(text("Copy link").size(12))
                        .padding([6, 12])
                        .style(style::secondary_button)
                        .on_press(Message::CopyToClipboard(prompt.verification_uri.clone())),
                    text("Opened manually on purpose \u{2014} we do not launch your browser for you.")
                        .size(11)
                        .color(style::TEXT_FAINT),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
                gap(8),
                text("2. Enter this code:").size(13).color(style::TEXT),
                container(text(prompt.user_code.clone()).size(30).font(iced::Font::MONOSPACE))
                    .padding(16)
                    .width(Length::Fill)
                    .center_x(Length::Fill)
                    .style(style::code_block),
                row![
                    button(text("Copy code").size(12))
                        .padding([6, 12])
                        .style(style::secondary_button)
                        .on_press(Message::CopyToClipboard(prompt.user_code.clone())),
                ]
                .spacing(10),
                gap(10),
                row![
                    container(text("Waiting for Microsoft\u{2026}").size(13))
                        .padding([10, 4]),
                    button(text("Cancel").size(13))
                        .padding([10, 16])
                        .style(style::ghost_button)
                        .on_press(Message::CancelSignIn),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
                text(
                    "This window checks on its own, on the interval Microsoft asks for. It stops \
                     as soon as you are done, and the launcher stays usable meanwhile."
                )
                .size(11)
                .color(style::TEXT_FAINT),
            ]
            .spacing(8),
        ),
    }
}

/// Inert until the background worker has connected, which takes a frame or two
/// at startup. A button that silently does nothing is worse than one that is
/// visibly not ready yet.
fn sign_in_button<'a>(ready: bool) -> Element<'a, Message> {
    let mut b = button(
        text("Sign in with Microsoft")
            .size(style::type_size::BODY)
            .font(style::FONT_MEDIUM),
    )
    .padding(style::CONTROL_PADDING)
    .style(style::primary_button);
    if ready {
        b = b.on_press(Message::BeginSignIn);
    }
    b.into()
}

/// A fake identity is presented as a fake identity — never dressed up as a
/// signed-in account.
fn signed_in_development(
    identity: &launcher_core::auth::provider::MinecraftIdentity,
) -> Element<'_, Message> {
    let body = column![
        row![
            container(
                text(
                    identity
                        .profile
                        .name
                        .chars()
                        .next()
                        .unwrap_or('D')
                        .to_string()
                )
                .size(style::type_size::SECTION_TITLE)
                .font(style::FONT_SEMIBOLD),
            )
            .center(Length::Fixed(42.0))
            .style(style::brand_mark),
            column![
                text(&identity.profile.name)
                    .size(style::type_size::SECTION_TITLE)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::TEXT),
                text(identity.kind.label()).size(11).color(style::BAD),
            ]
            .spacing(3)
            .width(Length::Fill),
            status_badge("NOT A REAL ACCOUNT", style::BAD),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
        gap(2),
        text(identity.kind.warning().unwrap_or_default())
            .size(13)
            .color(style::TEXT_MUTED),
        gap(4),
        detail("UUID", &identity.profile.id),
        detail("Access token", identity.access_token.redacted_hint()),
        detail("Reported user type", identity.user_type()),
        gap(4),
        text(
            "Singleplayer works, so the install pipeline, profiles, mods and performance work \
             can all be exercised. Joining an online-mode server does not work and cannot be \
             made to work: there is no valid token to authenticate with."
        )
        .size(12)
        .color(style::TEXT_FAINT),
        gap(6),
        button(text("Sign out").size(13))
            .padding([9, 16])
            .style(style::danger_button)
            .on_press(Message::SignOut),
    ]
    .spacing(8);

    panel("Development identity", None, body)
}

/// The development sign-in offer.
///
/// Present only when `launcher-core`'s `dev-auth` feature was compiled AND the
/// opt-in environment variable is set, so it cannot appear in a shipped build.
fn development_option<'a>(app: &'a App) -> Element<'a, Message> {
    let already_using_it = app
        .core()
        .identity()
        .is_some_and(|i| !i.kind.is_production());

    if !app.core().development_auth_available() || already_using_it {
        return Space::with_height(Length::Fixed(0.0)).into();
    }

    panel(
        "Development identity",
        Some("This build was compiled with development authentication."),
        column![
            container(
                text(
                    "Skip Microsoft sign-in and use a fake local identity. It is not a Minecraft \
                     account, it owns nothing, and it cannot join online-mode servers \u{2014} \
                     there is no valid token behind it."
                )
                .size(12),
            )
            .padding(12)
            .width(Length::Fill)
            .style(style::notice(style::BAD)),
            gap(4),
            button(text("Use development identity").size(13))
                .padding([9, 16])
                .style(style::secondary_button)
                .on_press(Message::BeginDevelopmentSignIn),
            text(
                "Compiled in only with --features dev-auth, which cannot be combined with a \
                 release build."
            )
            .size(11)
            .color(style::TEXT_FAINT),
        ]
        .spacing(8),
    )
}

/// Which of the six authentication states we are in, stated plainly.
///
/// The screen reads this from the state machine rather than inferring it, so
/// the two cannot drift apart.
fn auth_state_row(app: &App) -> Element<'_, Message> {
    use launcher_core::auth::AuthStatus;

    let status = app.core().auth_status();
    let tone = match status {
        AuthStatus::EntitlementVerified => style::GOOD,
        AuthStatus::AuthError => style::BAD,
        AuthStatus::TokenRefreshRequired => style::WARN,
        _ => style::TEXT_MUTED,
    };

    let detail_text = match app.core().auth().state() {
        launcher_core::auth::AuthState::Authenticating { stage } => stage.label().to_owned(),
        launcher_core::auth::AuthState::TokenRefreshRequired { reason, .. } => {
            format!("Because {}.", reason.label())
        }
        launcher_core::auth::AuthState::Failed { message, .. } => message.clone(),
        launcher_core::auth::AuthState::EntitlementVerified { entitlement, .. } => {
            if entitlement.authoritative {
                "Verified against Mojang's signature.".to_owned()
            } else {
                "NOT verified against a signature - development build only.".to_owned()
            }
        }
        _ => String::new(),
    };

    let mut body = column![row![
        text("Authentication state")
            .size(12)
            .color(style::TEXT_MUTED)
            .width(Length::Fill),
        container(text(status.label()).size(11))
            .padding([3, 9])
            .style(style::pill(tone)),
    ]
    .spacing(10)
    .align_y(Alignment::Center)]
    .spacing(4);

    if !detail_text.is_empty() {
        body = body.push(text(detail_text).size(11).color(style::TEXT_FAINT));
    }

    container(body)
        .padding(12)
        .width(Length::Fill)
        .style(style::inset)
        .into()
}
