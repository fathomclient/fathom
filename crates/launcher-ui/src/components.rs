//! Reusable presentation components for the launcher UI.
//!
//! These helpers own structure and interaction styling only. They do not read
//! launcher state or perform work; screens remain responsible for deciding
//! which messages a control sends.

use iced::widget::{button, checkbox, column, container, row, text, tooltip, Space, Stack};
use iced::{Alignment, Color, Element, Length, Padding, Theme};

use crate::app::{Message, Screen};
use crate::style;

/// Text with an extruded edge, drawn by stacking offset copies behind it.
///
/// Flat text is a flat outline on its own. Minecraft's own
/// wordmark reads as carved rather than printed, and the difference is depth —
/// so each copy below is shifted a pixel further down and right, darkening as
/// it goes, and the face sits on top undisplaced. It is the oldest trick there
/// is for this and it costs nothing: the renderer is already drawing text.
///
/// `depth` is how many pixels the extrusion runs for. Two or three is a chisel;
/// past about five it stops reading as an edge and starts reading as a blur.
///
/// The font is chosen by `style::display_font`, so a label the display face
/// cannot spell is drawn in the interface font — still extruded, still the
/// right size, just without the missing-glyph boxes.
pub fn extruded<'a>(
    label: impl Into<String>,
    size: u16,
    face: Color,
    edge: Color,
    depth: u16,
) -> Element<'a, Message> {
    let label: String = label.into();
    let font = style::display_font(&label);

    let mut layers = Stack::new();
    for step in (1..=depth).rev() {
        // Later layers are nearer the face, so they are lighter: the shadow
        // falls away from the letter rather than ending in a hard line.
        let fade = f32::from(step) / f32::from(depth.max(1));
        layers = layers.push(
            container(
                text(label.clone())
                    .size(size)
                    .font(font)
                    .color(style::with_alpha(edge, 1.0 - 0.45 * fade)),
            )
            .padding(Padding {
                top: f32::from(step),
                right: 0.0,
                bottom: 0.0,
                left: f32::from(step),
            }),
        );
    }

    layers
        .push(text(label).size(size).font(font).color(face))
        .into()
}

#[derive(Debug, Clone, Copy)]
pub enum ActionRole {
    Primary,
    Secondary,
    Ghost,
    Danger,
}

/// A consistently sized text action. Passing `None` produces a clearly
/// disabled control without changing its dimensions.
pub fn action_button<'a>(
    label: impl Into<String>,
    role: ActionRole,
    message: Option<Message>,
) -> Element<'a, Message> {
    let style_fn: fn(&Theme, button::Status) -> button::Style = match role {
        ActionRole::Primary => style::primary_button,
        ActionRole::Secondary => style::secondary_button,
        ActionRole::Ghost => style::ghost_button,
        ActionRole::Danger => style::danger_button,
    };
    let mut control = button(
        text(label.into())
            .size(style::type_size::BODY)
            .font(style::FONT_MEDIUM),
    )
    .padding(style::CONTROL_PADDING)
    .style(style_fn);
    if let Some(message) = message {
        control = control.on_press(message);
    }
    control.into()
}

/// One compact navigation-rail destination. The label moves into a tooltip so
/// the persistent shell stays icon-first without sacrificing discoverability.
pub fn nav_item<'a>(screen: Screen, selected: bool) -> Element<'a, Message> {
    // The accent marker sits outside the button's own surface so that the
    // selected state reads from the very edge of the window, the way a real
    // navigation rail does, rather than as a highlighted rectangle floating in
    // the middle of one.
    let glyph = row![
        container(Space::new(Length::Fixed(2.0), Length::Fixed(20.0)))
            .style(style::nav_indicator(selected)),
        container(crate::icons::icon_inherit(screen.icon(), crate::icons::size::RAIL))
            .center(Length::Fixed(34.0)),
    ]
    .spacing(style::space::XS)
    .align_y(Alignment::Center);

    let control = button(glyph)
        .width(Length::Fixed(46.0))
        .height(Length::Fixed(38.0))
        .padding(0)
        .style(style::nav_rail_button(selected))
        .on_press(Message::Navigate(screen));

    tooltip(
        control,
        text(screen.nav_label())
            .size(style::type_size::SUPPORTING)
            .font(style::FONT_MEDIUM),
        tooltip::Position::Right,
    )
    .gap(style::space::SM)
    .padding(style::space::SM)
    .style(style::tooltip)
    .into()
}

/// Standard title treatment for a content panel.
pub fn section_header<'a>(title: &'a str, subtitle: Option<&'a str>) -> Element<'a, Message> {
    let mut header = column![text(title)
        .size(style::type_size::CARD_TITLE)
        .font(style::FONT_SEMIBOLD)
        .color(style::TEXT),]
    .spacing(style::space::XXS);
    if let Some(subtitle) = subtitle {
        header = header.push(
            text(subtitle)
                .size(style::type_size::SUPPORTING)
                .color(style::TEXT_MUTED),
        );
    }
    header.into()
}

/// The standard bounded surface. Compact padding and a low-contrast border
/// keep it from reading as a stack of oversized website cards.
pub fn panel<'a>(
    title: &'a str,
    subtitle: Option<&'a str>,
    body: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    container(
        column![section_header(title, subtitle), body.into()]
            .spacing(style::space::MD)
            .width(Length::Fill),
    )
    .padding(style::CARD_PADDING)
    .width(Length::Fill)
    .style(style::panel)
    .into()
}

/// A label/value pair laid out on one compact desktop row.
pub fn detail<'a>(label: &'a str, value: impl Into<String>) -> Element<'a, Message> {
    row![
        text(label)
            .size(style::type_size::BODY)
            .color(style::TEXT_MUTED)
            .width(Length::Fixed(144.0)),
        text(value.into())
            .size(style::type_size::BODY)
            .color(style::TEXT)
            .width(Length::Fill),
    ]
    .spacing(style::space::MD)
    .align_y(Alignment::Start)
    .into()
}

/// A compact semantic status label.
pub fn status_badge<'a>(label: impl Into<String>, tone: iced::Color) -> Element<'a, Message> {
    container(
        text(label.into())
            .size(style::type_size::CAPTION)
            .font(style::FONT_MEDIUM),
    )
    .padding([style::space::XXS, style::space::SM])
    .style(style::pill(tone))
    .into()
}

pub fn code<'a>(body: impl Into<String>) -> Element<'a, Message> {
    container(
        text(body.into())
            .size(style::type_size::SUPPORTING)
            .font(iced::Font::MONOSPACE),
    )
    .padding(style::space::MD)
    .width(Length::Fill)
    .style(style::code_block)
    .into()
}

pub fn gap<'a>(height: u16) -> Element<'a, Message> {
    Space::with_height(Length::Fixed(f32::from(height))).into()
}

pub fn planned_notice<'a>(what: &'a str, why: &'a str) -> Element<'a, Message> {
    container(
        column![
            row![
                text("PLANNED")
                    .size(style::type_size::MICRO)
                    .font(style::FONT_SEMIBOLD)
                    .color(style::WARN),
                text(what)
                    .size(style::type_size::SUPPORTING)
                    .font(style::FONT_MEDIUM)
                    .color(style::TEXT),
            ]
            .spacing(style::space::SM),
            text(why)
                .size(style::type_size::SUPPORTING)
                .color(style::TEXT_MUTED),
        ]
        .spacing(style::space::XS),
    )
    .padding(style::space::MD)
    .width(Length::Fill)
    .style(style::notice(style::WARN))
    .into()
}

/// A two-line settings control with one predictable hit target and alignment.
pub fn settings_row<'a>(
    title: &'a str,
    description: &'a str,
    value: bool,
    on_toggle: impl Fn(bool) -> Message + 'a,
) -> Element<'a, Message> {
    let content = column![
        checkbox(title, value)
            .on_toggle(on_toggle)
            .size(16)
            .text_size(style::type_size::BODY)
            .style(style::checkbox),
        container(
            text(description)
                .size(style::type_size::SUPPORTING)
                .color(style::TEXT_MUTED),
        )
        .padding(iced::Padding {
            top: 0.0,
            right: 0.0,
            bottom: 0.0,
            left: 26.0,
        }),
    ]
    .spacing(style::space::XXS);

    container(content)
        .padding(style::ROW_PADDING)
        .width(Length::Fill)
        .style(style::settings_row)
        .into()
}

pub fn progress_bar<'a>(fraction: f32) -> Element<'a, Message> {
    let filled = (fraction.clamp(0.0, 1.0) * 1000.0) as u16;
    let empty = 1000_u16.saturating_sub(filled);

    container(
        row![
            container(Space::new(Length::Shrink, Length::Fixed(5.0)))
                .height(Length::Fixed(5.0))
                .width(Length::FillPortion(filled.max(1)))
                .style(style::progress_fill),
            container(Space::new(Length::Shrink, Length::Fixed(5.0)))
                .height(Length::Fixed(5.0))
                .width(Length::FillPortion(empty.max(1))),
        ]
        .height(Length::Fixed(5.0)),
    )
    .width(Length::Fill)
    .style(style::progress_track)
    .into()
}

/// A switch.
///
/// Drawn rather than using a checkbox, because a checkbox reads as "tick this
/// form field" and these are settings that take effect the moment they change.
/// The knob is a fixed-width spacer on one side or the other; iced has no
/// absolute positioning inside a widget, and a spacer that changes side is
/// both the simplest way to express it and the one that animates for free if
/// the track is ever driven by `motion::Animated`.
pub fn toggle<'a>(on: bool, message: Message) -> Element<'a, Message> {
    const TRACK: f32 = 34.0;
    const KNOB: f32 = 14.0;

    let knob = container(Space::new(
        Length::Fixed(KNOB),
        Length::Fixed(KNOB),
    ))
    .style(style::toggle_knob(on));

    let mut inner = row![].width(Length::Fixed(TRACK)).align_y(Alignment::Center);
    if on {
        inner = inner.push(Space::with_width(Length::Fill));
        inner = inner.push(knob);
    } else {
        inner = inner.push(knob);
        inner = inner.push(Space::with_width(Length::Fill));
    }

    button(container(inner).padding(Padding {
        top: 3.0,
        right: 3.0,
        bottom: 3.0,
        left: 3.0,
    }))
    .padding(0)
    .style(style::toggle_track(on))
    .on_press(message)
    .into()
}
