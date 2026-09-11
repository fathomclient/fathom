//! Fathom's visual system.
//!
//! All colour, spacing, radius, type, and interaction decisions live here.
//! Screens compose these tokens instead of inventing slightly different cards,
//! buttons, and labels as they go.
//!
//! # The one idea
//!
//! Gameplay footage runs behind the entire window. Everything in this file
//! exists to sit on top of moving imagery without either hiding it or being
//! hard to read. That leads to a rule the previous version did not follow:
//! **contrast comes from surfaces, not from borders.** A translucent dark plane
//! lets the footage through and still carries text. A one-pixel outline around
//! every group of content does not help legibility at all — it just draws a box
//! around the problem. So there are very few borders here, and the ones that
//! remain are hairlines at low alpha used to separate structural regions (the
//! rail, the bars), never to fence off content.

use iced::widget::{
    button, checkbox as checkbox_widget, container, scrollable as scrollable_widget, text_input,
};
use iced::{Background, Border, Color, Font, Gradient, Shadow, Theme, Vector};

// -- colour ---------------------------------------------------------------
//
// Black chrome, cool-dark surfaces, one red accent.
//
// The rule that drives the whole palette: **the chrome is black and the centre
// is not.** The rail, the top bar and the bottom bar are true black, so they
// read as a frame around the gameplay footage rather than as panels floating
// on top of it. Only the central content region lets the background through.
//
// The previous palette was navy-tinted throughout, which left the chrome
// looking like translucent blue-grey boxes -- the "gray chrome" and "random
// dark-blue boxes" this replaces.

/// The chrome. Top bar, navigation rail, bottom bar. Actually black.
pub const CHROME: Color = rgb(0x00, 0x00, 0x00);
/// The deepest content tone, and the base of the background scrim.
pub const VOID: Color = rgb(0x05, 0x06, 0x09);
/// The static background's upper tone.
pub const MIDNIGHT: Color = rgb(0x0B, 0x0D, 0x12);

pub const SURFACE: Color = rgb(0x0E, 0x10, 0x15);
pub const SURFACE_RAISED: Color = rgb(0x16, 0x18, 0x1E);
pub const SURFACE_HOVER: Color = rgb(0x1D, 0x20, 0x27);
pub const SURFACE_SELECTED: Color = rgb(0x24, 0x18, 0x1B);
pub const SURFACE_SUNKEN: Color = rgb(0x08, 0x09, 0x0D);

/// Hairlines. Deliberately near-invisible: they mark where one region of the
/// application ends, and nothing else.
pub const BORDER: Color = Color { r: 1.0, g: 1.0, b: 1.0, a: 0.07 };
pub const BORDER_STRONG: Color = Color { r: 1.0, g: 1.0, b: 1.0, a: 0.14 };

pub const TEXT: Color = rgb(0xF2, 0xF4, 0xF7);
pub const TEXT_MUTED: Color = rgb(0x9A, 0xA1, 0xAD);
pub const TEXT_FAINT: Color = rgb(0x67, 0x6D, 0x79);
pub const TEXT_DISABLED: Color = rgb(0x47, 0x4C, 0x55);

// The accent. A dark red -- deep enough to sit on black without glowing, clear
// enough to still read as red rather than as maroon or brown. Used sparingly:
// selection, the launch control, and destructive intent. It is the only hue in
// the interface, which is what makes it mean something.
pub const ACCENT: Color = rgb(0xC4, 0x30, 0x3C);
pub const ACCENT_STRONG: Color = rgb(0xDC, 0x4A, 0x55);
pub const ACCENT_DEEP: Color = rgb(0x8C, 0x1F, 0x27);

pub const GOOD: Color = rgb(0x4F, 0xC9, 0x8A);
pub const WARN: Color = rgb(0xE0, 0xAE, 0x57);
/// Failure. Deliberately distinct from the accent: if the accent is also the
/// error colour then a selected row and a broken one look the same.
pub const BAD: Color = rgb(0xE8, 0x72, 0x72);

// -- scale ----------------------------------------------------------------

pub mod space {
    pub const XXS: u16 = 3;
    pub const XS: u16 = 5;
    pub const SM: u16 = 8;
    pub const MD: u16 = 12;
    pub const LG: u16 = 16;
    pub const XL: u16 = 20;
    pub const XXL: u16 = 28;
}

pub mod radius {
    pub const SM: f32 = 5.0;
    pub const MD: f32 = 8.0;
    pub const LG: f32 = 11.0;
    pub const PILL: f32 = 999.0;
}

/// The type scale, named by role rather than by size.
///
/// A screen asks for `text::ROW_TITLE`, not for "14". That is the whole point:
/// when the row title needs to change, it changes in one place and every list
/// in the application follows, instead of a dozen literals drifting apart.
/// These are the roles the product actually has; adding a size means adding a
/// role and saying what it is for.
pub mod text {
    /// The largest type in the product: Home's version line.
    pub const DISPLAY: u16 = 32;
    /// The name of the screen you are on.
    pub const PAGE_TITLE: u16 = 21;
    /// A group heading inside a page.
    pub const SECTION_TITLE: u16 = 15;
    /// The first line of a list row: a profile, a mod, an optimization.
    pub const ROW_TITLE: u16 = 13;
    /// Running prose and descriptions.
    pub const BODY: u16 = 13;
    /// The second line of a row. Version numbers, counts, states.
    pub const METADATA: u16 = 12;
    /// Supporting notes and page subtitles.
    pub const CAPTION: u16 = 11;
    /// Text inside a control.
    pub const BUTTON: u16 = 12;
    /// Uppercase status chips and eyebrows.
    pub const BADGE: u16 = 10;
}

/// Compatibility aliases for the older size names, so this rename does not
/// have to land in every screen at once.
pub mod type_size {
    use super::text;
    pub const MICRO: u16 = text::BADGE;
    pub const CAPTION: u16 = text::CAPTION;
    pub const SUPPORTING: u16 = text::METADATA;
    pub const BODY: u16 = text::BODY;
    pub const CARD_TITLE: u16 = 14;
    pub const SECTION_TITLE: u16 = text::SECTION_TITLE;
    pub const PROFILE_TITLE: u16 = 18;
    pub const PAGE_TITLE: u16 = text::PAGE_TITLE;
    pub const HERO: u16 = text::DISPLAY;
    pub const PLAY: u16 = 20;
}

/// Heights of the three fixed regions. They are constants because the Home
/// screen has to centre its content in what is left over, and guessing would
/// put PLAY slightly off-centre.
pub const RAIL_WIDTH: f32 = 58.0;
pub const TOP_BAR_HEIGHT: f32 = 44.0;
pub const BOTTOM_BAR_HEIGHT: f32 = 34.0;

pub const CONTROL_PADDING: [u16; 2] = [8, 12];
pub const COMPACT_CONTROL_PADDING: [u16; 2] = [5, 9];
pub const CARD_PADDING: u16 = 14;
pub const ROW_PADDING: [u16; 2] = [9, 10];

// -- type ----------------------------------------------------------------
//
// Two faces, with a hard line between them.
//
// `FONT_APP` and its weights are the interface: everything a user reads to
// find out what is going on. Segoe UI Variable is Windows 11's refinement of
// Segoe UI -- better spacing at small sizes and a proper optical size for
// headings. On Windows 10, where the Variable families do not exist, the text
// shaper falls back on its own; the layout does not depend on the difference.
//
// `FONT_DISPLAY` is the brand: the wordmark and the launch control, and
// nothing else. No display face is bundled, so it is the interface bold;
// `display_safe` still guards every string that reaches it, so a display face
// with limited glyph coverage can be reintroduced without labels rendering as
// missing-glyph boxes.

#[cfg(target_os = "windows")]
pub const FONT_APP: Font = Font::with_name("Segoe UI Variable Text");
#[cfg(not(target_os = "windows"))]
pub const FONT_APP: Font = Font::DEFAULT;

/// Large headings. An optical size cut for display, not body copy.
#[cfg(target_os = "windows")]
pub const FONT_HEADING: Font = Font::with_name("Segoe UI Variable Display");
#[cfg(not(target_os = "windows"))]
pub const FONT_HEADING: Font = Font::DEFAULT;

// Weights are requested as a weight on the *base* family, never by naming the
// weighted family directly.
//
// Windows registers these faces under names clipped to 31 characters -- the
// Semibold display face is registered as "Segoe UI Variable Display Semib",
// not "...Semibold" -- so a full name looks correct in source, fails to match,
// and silently falls back to some other font. Asking the base family for a
// weight avoids the question and is what the shaper wants anyway.

#[cfg(target_os = "windows")]
pub const FONT_SEMIBOLD: Font = Font {
    family: iced::font::Family::Name("Segoe UI Variable Text"),
    weight: iced::font::Weight::Semibold,
    ..Font::DEFAULT
};
#[cfg(not(target_os = "windows"))]
pub const FONT_SEMIBOLD: Font = Font {
    weight: iced::font::Weight::Semibold,
    ..Font::DEFAULT
};

#[cfg(target_os = "windows")]
pub const FONT_BOLD: Font = Font {
    family: iced::font::Family::Name("Segoe UI Variable Display"),
    weight: iced::font::Weight::Bold,
    ..Font::DEFAULT
};
#[cfg(not(target_os = "windows"))]
pub const FONT_BOLD: Font = Font {
    weight: iced::font::Weight::Bold,
    ..Font::DEFAULT
};

#[cfg(target_os = "windows")]
pub const FONT_MEDIUM: Font = Font::with_name("Segoe UI Variable Text");
#[cfg(not(target_os = "windows"))]
pub const FONT_MEDIUM: Font = Font::DEFAULT;

// There is deliberately no icon font here any more. Icons come from
// `assets/icons` as SVG; a symbol-font constant existing at all is what let the
// old build reach for whatever glyph a system font happened to have.

/// The display face: the wordmark and the launch control.
///
/// The face Fathom used to bundle had no verifiable licence and was removed
/// before public release, so this is the interface bold.
pub const FONT_DISPLAY: Font = FONT_BOLD;

/// Whether a string may be set in `FONT_DISPLAY`.
///
/// Letters, digits and space only -- no full stop, no hyphen, no colon. Display
/// faces commonly stop there, and `MINECRAFT 1.8.9` through one would come out
/// as `MINECRAFT 1` and three missing-glyph boxes. Callers pick the interface
/// font when this is false, so a label can never render as tofu no matter what
/// it says.
pub fn display_safe(label: &str) -> bool {
    !label.is_empty()
        && label
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == ' ')
}

/// The display face when it can draw `label`, and the interface bold when it
/// cannot.
pub fn display_font(label: &str) -> Font {
    if display_safe(label) {
        FONT_DISPLAY
    } else {
        FONT_BOLD
    }
}

const fn rgb(r: u8, g: u8, b: u8) -> Color {
    Color {
        r: r as f32 / 255.0,
        g: g as f32 / 255.0,
        b: b as f32 / 255.0,
        a: 1.0,
    }
}

pub fn with_alpha(color: Color, a: f32) -> Color {
    Color { a, ..color }
}

fn vertical(stops: &[(f32, Color)]) -> Background {
    let mut linear = iced::gradient::Linear::new(iced::Radians(std::f32::consts::PI));
    for (offset, color) in stops {
        linear = linear.add_stop(*offset, *color);
    }
    Background::Gradient(Gradient::Linear(linear))
}

pub fn theme() -> Theme {
    Theme::custom(
        "Fathom".to_owned(),
        iced::theme::Palette {
            background: MIDNIGHT,
            text: TEXT,
            primary: ACCENT,
            success: GOOD,
            danger: BAD,
        },
    )
}

// -- the background layers ------------------------------------------------

/// The static midnight surface.
///
/// Painted underneath every other layer on every frame, including while
/// footage is playing. It is the reason no background change can ever show a
/// black window: there is always something already drawn. It is also the whole
/// look when animation is off, which is why it is a considered vertical
/// gradient and not a flat fill.
pub fn static_midnight(_: &Theme) -> container::Style {
    container::Style {
        background: Some(vertical(&[
            (0.0, rgb(0x0C, 0x11, 0x1C)),
            (0.55, MIDNIGHT),
            (1.0, VOID),
        ])),
        ..container::Style::default()
    }
}

/// Alias kept for the boot-failure window, which has no footage behind it.
pub fn app_background(theme: &Theme) -> container::Style {
    static_midnight(theme)
}

/// The scrim between the footage and the interface.
///
/// Much lighter than it used to be, because it no longer has to do the bars'
/// job. When the chrome was translucent, this had to be heavy at the top and
/// bottom so text over it stayed legible; now the chrome is opaque black and
/// the only thing the scrim serves is the central content region. What is left
/// is a gentle vertical fall-off that keeps body text comfortable without
/// flattening the footage into a dark smear.
pub fn scrim(_: &Theme) -> container::Style {
    container::Style {
        background: Some(vertical(&[
            (0.0, with_alpha(VOID, 0.46)),
            (0.45, with_alpha(VOID, 0.34)),
            (1.0, with_alpha(VOID, 0.50)),
        ])),
        ..container::Style::default()
    }
}

/// Extra local darkening directly behind dense screens.
///
/// Home leaves the footage alone; Profiles, Mods, Settings and Privacy are
/// walls of text and need a calmer floor under them.
pub fn content_wash(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(VOID, 0.40))),
        ..container::Style::default()
    }
}

// -- the three fixed regions ----------------------------------------------

/// The left navigation rail.
pub fn rail(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(CHROME)),
        border: Border {
            color: BORDER,
            width: 0.0,
            radius: 0.0.into(),
        },
        ..container::Style::default()
    }
}

pub fn top_bar(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(CHROME)),
        ..container::Style::default()
    }
}

pub fn bottom_bar(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(CHROME)),
        ..container::Style::default()
    }
}

/// A one-pixel structural separator. The only border-like thing used at scale.
pub fn hairline(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(BORDER)),
        ..container::Style::default()
    }
}

/// The floating panel raised over the bottom bar by the background switcher.
pub fn popover(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(rgb(0x0D, 0x12, 0x1D), 0.98))),
        border: Border {
            color: BORDER_STRONG,
            width: 1.0,
            radius: radius::LG.into(),
        },
        shadow: Shadow {
            color: Color {
                a: 0.55,
                ..Color::BLACK
            },
            offset: Vector::new(0.0, 10.0),
            blur_radius: 30.0,
        },
        ..container::Style::default()
    }
}

// -- content surfaces -----------------------------------------------------

/// The standard content panel.
///
/// Translucent rather than opaque, and with no outline. The footage stays
/// faintly perceptible through dense screens, which is what stops the
/// application from feeling like two unrelated halves.
pub fn panel(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(SURFACE, 0.82))),
        border: Border {
            color: BORDER,
            width: 1.0,
            radius: radius::LG.into(),
        },
        ..container::Style::default()
    }
}

/// Compatibility name for the boot-failure window.
pub fn card(theme: &Theme) -> container::Style {
    panel(theme)
}

/// A quiet inset surface for list rows, empty states, and grouped settings.
pub fn inset(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(SURFACE_SUNKEN, 0.62))),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::MD.into(),
        },
        ..container::Style::default()
    }
}

/// A flatter row surface for settings and other compact control groups.
pub fn settings_row(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(SURFACE_SUNKEN, 0.55))),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::MD.into(),
        },
        ..container::Style::default()
    }
}

pub fn list_row(selected: bool) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(Background::Color(if selected {
            with_alpha(SURFACE_SELECTED, 0.92)
        } else {
            with_alpha(SURFACE_SUNKEN, 0.58)
        })),
        border: Border {
            color: if selected {
                with_alpha(ACCENT, 0.45)
            } else {
                Color::TRANSPARENT
            },
            width: 1.0,
            radius: radius::MD.into(),
        },
        ..container::Style::default()
    }
}

pub fn identity_mark(tone: Color) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(Background::Color(with_alpha(tone, 0.16))),
        border: Border {
            color: with_alpha(tone, 0.32),
            width: 1.0,
            radius: radius::PILL.into(),
        },
        text_color: Some(tone),
        ..container::Style::default()
    }
}

/// The wordmark tile at the top of the rail.
pub fn brand_mark(_: &Theme) -> container::Style {
    container::Style {
        background: Some(vertical(&[(0.0, ACCENT_STRONG), (1.0, ACCENT_DEEP)])),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::MD.into(),
        },
        text_color: Some(Color::WHITE),
        ..container::Style::default()
    }
}

/// The accent bar marking the selected rail destination.
pub fn nav_indicator(selected: bool) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: selected.then_some(Background::Color(ACCENT)),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::PILL.into(),
        },
        ..container::Style::default()
    }
}

pub fn tooltip(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(rgb(0x11, 0x17, 0x24), 0.98))),
        border: Border {
            color: BORDER_STRONG,
            width: 1.0,
            radius: radius::SM.into(),
        },
        text_color: Some(TEXT),
        shadow: Shadow {
            color: Color {
                a: 0.45,
                ..Color::BLACK
            },
            offset: Vector::new(0.0, 4.0),
            blur_radius: 14.0,
        },
    }
}

/// Coloured notice strip. `tone` picks the semantic accent.
pub fn notice(tone: Color) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(Background::Color(with_alpha(tone, 0.10))),
        border: Border {
            color: with_alpha(tone, 0.28),
            width: 1.0,
            radius: radius::MD.into(),
        },
        text_color: Some(TEXT),
        ..container::Style::default()
    }
}

/// Small status label. Status colour is reserved for the badge, not the whole
/// parent surface, so warnings remain readable rather than visually loud.
pub fn pill(tone: Color) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(Background::Color(with_alpha(tone, 0.14))),
        border: Border {
            color: with_alpha(tone, 0.30),
            width: 1.0,
            radius: radius::PILL.into(),
        },
        text_color: Some(tone),
        ..container::Style::default()
    }
}

pub fn code_block(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(VOID, 0.70))),
        border: Border {
            color: BORDER,
            width: 1.0,
            radius: radius::SM.into(),
        },
        text_color: Some(TEXT_MUTED),
        ..container::Style::default()
    }
}

pub fn progress_track(_: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(with_alpha(VOID, 0.72))),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::PILL.into(),
        },
        ..container::Style::default()
    }
}

pub fn progress_fill(_: &Theme) -> container::Style {
    container::Style {
        background: Some(vertical(&[(0.0, ACCENT_STRONG), (1.0, ACCENT)])),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::PILL.into(),
        },
        ..container::Style::default()
    }
}

// -- buttons --------------------------------------------------------------

fn base_button(background: Color, text_color: Color, status: button::Status) -> button::Style {
    let (background, text_color, border_color) = match status {
        button::Status::Hovered => (lighten(background, 0.05), text_color, BORDER_STRONG),
        button::Status::Pressed => (darken(background, 0.04), text_color, BORDER_STRONG),
        button::Status::Disabled => (
            with_alpha(background, 0.35),
            TEXT_DISABLED,
            with_alpha(BORDER, 0.4),
        ),
        button::Status::Active => (background, text_color, BORDER),
    };
    button::Style {
        background: Some(Background::Color(background)),
        text_color,
        border: Border {
            color: border_color,
            width: 1.0,
            radius: radius::MD.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn primary_button(_: &Theme, status: button::Status) -> button::Style {
    let mut style = base_button(ACCENT, Color::WHITE, status);
    style.border.color = match status {
        button::Status::Disabled => with_alpha(ACCENT, 0.16),
        _ => with_alpha(ACCENT_STRONG, 0.8),
    };
    style
}

/// The launch control.
///
/// The single strongest thing on screen: a filled gradient, a real shadow, and
/// enough radius to read as one object from across the room. Disabled it goes
/// quiet rather than grey-and-sad, because a launcher that cannot play yet is
/// a normal state, not an error.
pub fn play_button(_: &Theme, status: button::Status) -> button::Style {
    let (top, bottom, glow) = match status {
        button::Status::Hovered => (lighten(ACCENT_STRONG, 0.05), ACCENT, 0.50),
        button::Status::Pressed => (ACCENT, ACCENT_DEEP, 0.20),
        button::Status::Disabled => (
            with_alpha(SURFACE_RAISED, 0.88),
            with_alpha(SURFACE, 0.88),
            0.0,
        ),
        button::Status::Active => (ACCENT_STRONG, ACCENT_DEEP, 0.36),
    };

    button::Style {
        background: Some(vertical(&[(0.0, top), (1.0, bottom)])),
        text_color: if matches!(status, button::Status::Disabled) {
            TEXT_MUTED
        } else {
            Color::WHITE
        },
        border: Border {
            color: if matches!(status, button::Status::Disabled) {
                BORDER_STRONG
            } else {
                with_alpha(Color::WHITE, 0.18)
            },
            width: 1.0,
            radius: radius::LG.into(),
        },
        shadow: Shadow {
            color: with_alpha(ACCENT_DEEP, glow),
            offset: Vector::new(0.0, 8.0),
            blur_radius: 26.0,
        },
    }
}

/// The launch control when the thing to do is not "play".
///
/// Sign in, install, repair. Same size and position — the hierarchy of the
/// screen must not move around depending on state — but unmistakably a
/// different action.
pub fn blocked_play_button(_: &Theme, status: button::Status) -> button::Style {
    let background = match status {
        button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.94),
        button::Status::Pressed => with_alpha(SURFACE, 0.94),
        _ => with_alpha(SURFACE_RAISED, 0.88),
    };
    button::Style {
        background: Some(Background::Color(background)),
        text_color: TEXT,
        border: Border {
            color: with_alpha(WARN, 0.45),
            width: 1.0,
            radius: radius::LG.into(),
        },
        shadow: Shadow {
            color: Color {
                a: 0.40,
                ..Color::BLACK
            },
            offset: Vector::new(0.0, 8.0),
            blur_radius: 22.0,
        },
    }
}

pub fn secondary_button(_: &Theme, status: button::Status) -> button::Style {
    base_button(with_alpha(SURFACE_RAISED, 0.85), TEXT, status)
}

pub fn danger_button(_: &Theme, status: button::Status) -> button::Style {
    let background = match status {
        button::Status::Hovered | button::Status::Pressed => with_alpha(BAD, 0.15),
        _ => with_alpha(SURFACE_RAISED, 0.85),
    };
    let mut style = base_button(background, BAD, status);
    style.border.color = match status {
        button::Status::Disabled => with_alpha(BAD, 0.12),
        _ => with_alpha(BAD, 0.34),
    };
    style
}

/// Flat text action for low-priority controls.
pub fn ghost_button(_: &Theme, status: button::Status) -> button::Style {
    let text_color = match status {
        button::Status::Hovered | button::Status::Pressed => TEXT,
        button::Status::Disabled => TEXT_DISABLED,
        button::Status::Active => TEXT_MUTED,
    };
    button::Style {
        background: match status {
            button::Status::Hovered => Some(Background::Color(with_alpha(SURFACE_HOVER, 0.7))),
            button::Status::Pressed => Some(Background::Color(with_alpha(SURFACE, 0.7))),
            _ => None,
        },
        text_color,
        border: Border {
            color: Color::TRANSPARENT,
            width: 1.0,
            radius: radius::MD.into(),
        },
        shadow: Shadow::default(),
    }
}

/// A control in one of the bars. Small, quiet, and the same height as the bar
/// it lives in.
pub fn bar_button(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| {
        let background = if active {
            Some(Background::Color(with_alpha(SURFACE_RAISED, 0.85)))
        } else {
            match status {
                button::Status::Hovered => {
                    Some(Background::Color(with_alpha(SURFACE_HOVER, 0.75)))
                }
                button::Status::Pressed => Some(Background::Color(with_alpha(SURFACE, 0.75))),
                _ => None,
            }
        };
        button::Style {
            background,
            text_color: match status {
                button::Status::Disabled => TEXT_DISABLED,
                _ if active => TEXT,
                button::Status::Hovered | button::Status::Pressed => TEXT,
                button::Status::Active => TEXT_MUTED,
            },
            border: Border {
                color: Color::TRANSPARENT,
                width: 0.0,
                radius: radius::SM.into(),
            },
            shadow: Shadow::default(),
        }
    }
}

/// Icon-first navigation rail action.
///
/// The icon stays monochrome in every state; selection is carried by the
/// separate accent marker and a slightly raised surface. Colouring the glyph
/// as well would be saying the same thing three times.
pub fn nav_rail_button(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| {
        let background = if selected {
            Some(Background::Color(with_alpha(SURFACE_SELECTED, 0.85)))
        } else {
            match status {
                button::Status::Hovered => {
                    Some(Background::Color(with_alpha(SURFACE_HOVER, 0.6)))
                }
                button::Status::Pressed => Some(Background::Color(with_alpha(SURFACE, 0.6))),
                button::Status::Active | button::Status::Disabled => None,
            }
        };
        button::Style {
            background,
            text_color: match status {
                button::Status::Disabled => TEXT_DISABLED,
                _ if selected => TEXT,
                button::Status::Hovered | button::Status::Pressed => TEXT,
                button::Status::Active => TEXT_MUTED,
            },
            border: Border {
                color: Color::TRANSPARENT,
                width: 0.0,
                radius: radius::MD.into(),
            },
            shadow: Shadow::default(),
        }
    }
}

/// One background in the switcher. The preview image carries the identity, so
/// the frame around it stays almost invisible until it is the chosen one.
pub fn background_option(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| button::Style {
        background: Some(Background::Color(match status {
            _ if selected => with_alpha(SURFACE_SELECTED, 0.9),
            button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.8),
            button::Status::Pressed => with_alpha(SURFACE, 0.8),
            _ => with_alpha(SURFACE_SUNKEN, 0.7),
        })),
        text_color: if matches!(status, button::Status::Disabled) {
            TEXT_DISABLED
        } else {
            TEXT
        },
        border: Border {
            color: if selected {
                ACCENT
            } else {
                Color::TRANSPARENT
            },
            width: if selected { 1.5 } else { 1.0 },
            radius: radius::MD.into(),
        },
        shadow: Shadow::default(),
    }
}

// -- controls -------------------------------------------------------------

pub fn input(_: &Theme, status: text_input::Status) -> text_input::Style {
    let (border_color, border_width, background) = match status {
        text_input::Status::Focused => (ACCENT, 1.5, with_alpha(VOID, 0.72)),
        text_input::Status::Hovered => (BORDER_STRONG, 1.0, with_alpha(VOID, 0.72)),
        text_input::Status::Disabled => (BORDER, 1.0, with_alpha(SURFACE, 0.6)),
        text_input::Status::Active => (BORDER, 1.0, with_alpha(VOID, 0.72)),
    };
    text_input::Style {
        background: Background::Color(background),
        border: Border {
            color: border_color,
            width: border_width,
            radius: radius::MD.into(),
        },
        icon: TEXT_MUTED,
        placeholder: TEXT_FAINT,
        value: if matches!(status, text_input::Status::Disabled) {
            TEXT_DISABLED
        } else {
            TEXT
        },
        selection: with_alpha(ACCENT, 0.35),
    }
}

pub fn checkbox(_: &Theme, status: checkbox_widget::Status) -> checkbox_widget::Style {
    let (is_checked, hovered, disabled) = match status {
        checkbox_widget::Status::Active { is_checked } => (is_checked, false, false),
        checkbox_widget::Status::Hovered { is_checked } => (is_checked, true, false),
        checkbox_widget::Status::Disabled { is_checked } => (is_checked, false, true),
    };
    let background = if is_checked {
        if disabled {
            with_alpha(ACCENT, 0.3)
        } else {
            ACCENT
        }
    } else if hovered {
        with_alpha(SURFACE_HOVER, 0.9)
    } else {
        with_alpha(VOID, 0.7)
    };
    checkbox_widget::Style {
        background: Background::Color(background),
        icon_color: if disabled {
            TEXT_DISABLED
        } else {
            Color::WHITE
        },
        border: Border {
            color: if is_checked {
                with_alpha(ACCENT_STRONG, if disabled { 0.28 } else { 1.0 })
            } else {
                BORDER_STRONG
            },
            width: 1.0,
            radius: radius::SM.into(),
        },
        text_color: Some(if disabled { TEXT_DISABLED } else { TEXT }),
    }
}

pub fn scrollable(_: &Theme, status: scrollable_widget::Status) -> scrollable_widget::Style {
    let hovered = !matches!(status, scrollable_widget::Status::Active);
    let rail = scrollable_widget::Rail {
        background: None,
        border: Border::default(),
        scroller: scrollable_widget::Scroller {
            color: if hovered { BORDER_STRONG } else { BORDER },
            border: Border {
                radius: radius::PILL.into(),
                ..Border::default()
            },
        },
    };
    scrollable_widget::Style {
        container: container::Style::default(),
        vertical_rail: rail,
        horizontal_rail: rail,
        gap: None,
    }
}

fn lighten(color: Color, amount: f32) -> Color {
    Color {
        r: (color.r + amount).min(1.0),
        g: (color.g + amount).min(1.0),
        b: (color.b + amount).min(1.0),
        a: color.a,
    }
}

fn darken(color: Color, amount: f32) -> Color {
    Color {
        r: (color.r - amount).max(0.0),
        g: (color.g - amount).max(0.0),
        b: (color.b - amount).max(0.0),
        a: color.a,
    }
}

/// A preset tile on the Performance page.
pub fn preset_button(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| button::Style {
        background: Some(Background::Color(if selected {
            with_alpha(SURFACE_SELECTED, 0.95)
        } else {
            match status {
                button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.85),
                button::Status::Pressed => with_alpha(SURFACE, 0.85),
                _ => with_alpha(SURFACE_SUNKEN, 0.7),
            }
        })),
        text_color: if selected { TEXT } else { TEXT_MUTED },
        border: Border {
            color: if selected { ACCENT } else { BORDER },
            width: if selected { 1.5 } else { 1.0 },
            radius: radius::MD.into(),
        },
        shadow: Shadow::default(),
    }
}

/// The track of a switch. Accent when on, sunken when off.
pub fn toggle_track(on: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| {
        let base = if on { ACCENT } else { with_alpha(SURFACE_RAISED, 0.9) };
        let background = match status {
            button::Status::Hovered if on => ACCENT_STRONG,
            button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.95),
            button::Status::Disabled => with_alpha(base, 0.4),
            _ => base,
        };
        button::Style {
            background: Some(Background::Color(background)),
            text_color: TEXT,
            border: Border {
                color: if on { with_alpha(ACCENT_STRONG, 0.9) } else { BORDER_STRONG },
                width: 1.0,
                radius: radius::PILL.into(),
            },
            shadow: Shadow::default(),
        }
    }
}

/// The knob of a switch.
pub fn toggle_knob(on: bool) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(Background::Color(if on {
            Color::WHITE
        } else {
            TEXT_MUTED
        })),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::PILL.into(),
        },
        ..container::Style::default()
    }
}


/// A text input with no chrome of its own.
///
/// For a search field that sits inside a surface which already provides the
/// box. Two nested boxes for one control is the "rectangle inside a rectangle"
/// problem in miniature.
pub fn bare_input(_: &Theme, status: text_input::Status) -> text_input::Style {
    text_input::Style {
        background: Background::Color(Color::TRANSPARENT),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 0.0.into(),
        },
        icon: TEXT_MUTED,
        placeholder: TEXT_FAINT,
        value: if matches!(status, text_input::Status::Disabled) {
            TEXT_DISABLED
        } else {
            TEXT
        },
        selection: with_alpha(ACCENT, 0.35),
    }
}

/// A small filter chip. Selection is carried by fill, not by an outline.
pub fn chip_button(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| button::Style {
        background: Some(Background::Color(if selected {
            with_alpha(ACCENT, 0.20)
        } else {
            match status {
                button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.85),
                button::Status::Pressed => with_alpha(SURFACE, 0.85),
                _ => with_alpha(SURFACE_SUNKEN, 0.6),
            }
        })),
        text_color: if selected { TEXT } else { TEXT_MUTED },
        border: Border {
            color: if selected { with_alpha(ACCENT, 0.55) } else { Color::TRANSPARENT },
            width: 1.0,
            radius: radius::PILL.into(),
        },
        shadow: Shadow::default(),
    }
}

/// A category tab in the Settings rail.
pub fn settings_tab(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_, status| button::Style {
        background: Some(Background::Color(if selected {
            with_alpha(SURFACE_SELECTED, 0.95)
        } else {
            match status {
                button::Status::Hovered => with_alpha(SURFACE_HOVER, 0.7),
                button::Status::Pressed => with_alpha(SURFACE, 0.7),
                _ => Color::TRANSPARENT,
            }
        })),
        text_color: if selected { TEXT } else { TEXT_MUTED },
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: radius::MD.into(),
        },
        shadow: Shadow::default(),
    }
}

/// The surface under a settings row, as a container style.
pub fn settings_row_surface(theme: &Theme) -> container::Style {
    settings_row(theme)
}


#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    /// Every label the launch control and wordmark can show, checked against
    /// the guard. The ones that are not safe must be *known* to be unsafe, so
    /// that a future edit reintroducing punctuation is caught here rather than
    /// by someone noticing boxes on the Home screen.
    #[test]
    fn the_display_guard_matches_the_labels_the_interface_uses() {
        for safe in [
            "FATHOM",
            "F",
            "PLAY",
            "RUNNING",
            "WORKING",
            "PREPARING",
            "DOWNLOADING",
            "REPAIRING",
            "SIGN IN TO PLAY",
            "SIGN IN UNAVAILABLE",
            "CHECK ACCOUNT",
            "REPAIR REQUIRED",
            "INSTALL GAME FILES",
            "VERIFY FILES",
            "JAVA REQUIRED",
            "LOADER UNAVAILABLE",
            "OFFLINE",
            "PREPARE",
            "NEEDS ATTENTION",
        ] {
            assert!(display_safe(safe), "{safe} should use the display face");
            assert_eq!(display_font(safe), FONT_DISPLAY, "{safe}");
        }

        // No full stop, hyphen, colon or ellipsis reaches the display face.
        for unsafe_label in [
            "MINECRAFT 1.8.9",
            "SIGN-IN UNAVAILABLE",
            "PREPARING\u{2026}",
            "Minecraft 1.8.9 \u{00B7} Vanilla",
            "",
        ] {
            assert!(
                !display_safe(unsafe_label),
                "{unsafe_label:?} would render with missing glyphs"
            );
            assert_eq!(display_font(unsafe_label), FONT_BOLD, "{unsafe_label:?}");
        }
    }
}
