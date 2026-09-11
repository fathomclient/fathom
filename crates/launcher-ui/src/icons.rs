//! The icon set.
//!
//! # Why this module exists
//!
//! Pages ask for [`Icon::Home`], never for a file path and never for a
//! character. Everything about how an icon is stored, coloured and sized lives
//! here, so replacing the artwork — which is the plan; these are placeholders
//! until real icons are drawn — means editing this file and the `assets/icons`
//! directory, and touching no page code at all.
//!
//! # Why SVG rather than a glyph font
//!
//! The previous build drew navigation with Unicode characters (`⌂`, `▦`, `◇`)
//! in whatever font happened to have them. That has three problems: the shapes
//! come from different type designers and do not look like a set; the weights
//! do not match the interface; and a missing glyph renders as a hollow box,
//! which is exactly what happened to the switcher's tick mark. Vector files we
//! control have none of those failure modes, and swapping one for custom
//! artwork later does not mean re-fitting a font.
//!
//! # The rules the set follows
//!
//! One family, no exceptions: 24×24 grid, 1.75 stroke, round caps and joins,
//! no fills except two intentional dots, and `stroke="currentColor"` so a
//! single style decides the colour. Mixing in a second source — a system
//! symbol, an emoji, a differently-weighted set — is what makes an interface
//! look assembled rather than designed, so `Icon` is an enum and there is no
//! way to reference an icon that is not part of it.

// The icon set is deliberately complete rather than exactly what today's pages
// happen to call. An icon system that only contains the icons already in use is
// one that gets extended ad hoc by whoever needs the next one, which is how a
// set stops looking like a set. The unused entries are drawn to the same grid
// and stroke as the rest and are covered by this module's tests.
#![allow(dead_code)]

use iced::widget::svg::{self, Handle};
use iced::widget::Svg;
use iced::{Color, Length, Theme};

use crate::style;

macro_rules! icons {
    ($($variant:ident => $file:literal),* $(,)?) => {
        /// Every icon the interface can draw.
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
        pub enum Icon {
            $($variant),*
        }

        impl Icon {
            /// The raw SVG, compiled in.
            const fn source(self) -> &'static [u8] {
                match self {
                    $(Self::$variant => {
                        include_bytes!(concat!("../assets/icons/", $file, ".svg"))
                    })*
                }
            }

            /// Every variant, for the test that checks they all parse.
            pub const ALL: &'static [Self] = &[$(Self::$variant),*];

            /// The file this icon comes from, for diagnostics.
            pub const fn name(self) -> &'static str {
                match self {
                    $(Self::$variant => $file),*
                }
            }
        }
    };
}

icons! {
    // Navigation destinations. One per rail entry, no shared shapes.
    Home => "home",
    Profiles => "profiles",
    Mods => "mods",
    Performance => "performance",
    Privacy => "privacy",
    Recovery => "recovery",
    Settings => "settings",

    // Chrome and controls.
    Account => "account",
    Backgrounds => "backgrounds",
    Search => "search",
    Play => "play",
    Check => "check",
    ChevronDown => "chevron-down",
    ChevronRight => "chevron-right",
    Warning => "warning",
    Info => "info",
    Folder => "folder",
    Plus => "plus",
    Trash => "trash",
    Refresh => "refresh",
    Power => "power",
    ShieldOff => "shield-off",
}

impl Icon {
    fn handle(self) -> Handle {
        // `from_memory` over a `&'static [u8]` does not copy, and iced caches
        // the parsed tree by handle, so repeating an icon every frame in a
        // list costs a lookup rather than a re-parse.
        Handle::from_memory(self.source())
    }
}

/// An icon at a given size, in a given colour.
///
/// Size is both dimensions: the set is square on a 24-unit grid, and anything
/// that needs a non-square icon needs a different icon, not a stretched one.
pub fn icon<'a, Message: 'a>(icon: Icon, size: f32, color: Color) -> iced::Element<'a, Message> {
    Svg::new(icon.handle())
        .width(Length::Fixed(size))
        .height(Length::Fixed(size))
        .style(move |_: &Theme, _| svg::Style { color: Some(color) })
        .into()
}

/// An icon that takes its colour from whatever is drawing it.
///
/// Used inside buttons, where the button's own style already decides the text
/// colour for each interaction state and the icon should follow it rather than
/// being pinned to one value.
pub fn icon_inherit<'a, Message: 'a>(which: Icon, size: f32) -> iced::Element<'a, Message> {
    Svg::new(which.handle())
        .width(Length::Fixed(size))
        .height(Length::Fixed(size))
        .style(|_: &Theme, _| svg::Style { color: None })
        .into()
}

/// The standard sizes. Anything else is a one-off and should be justified.
pub mod size {
    /// Inline with caption text — badges, row affordances.
    pub const SMALL: f32 = 13.0;
    /// Inline with body text — bar controls, list rows.
    pub const MEDIUM: f32 = 16.0;
    /// The navigation rail.
    pub const RAIL: f32 = 19.0;
    /// Empty states and page-level marks.
    pub const LARGE: f32 = 28.0;
}

/// A muted icon, for anything not currently the subject.
pub fn muted<'a, Message: 'a>(which: Icon, size: f32) -> iced::Element<'a, Message> {
    icon(which, size, style::TEXT_MUTED)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    /// Every icon has to actually parse. A malformed file would otherwise draw
    /// nothing at all, silently, and an empty square in a navigation rail is
    /// not something a compiler or a reviewer notices.
    #[test]
    fn every_icon_is_valid_svg() {
        for which in Icon::ALL {
            let source = which.source();
            let text = std::str::from_utf8(source)
                .unwrap_or_else(|_| panic!("{} is not utf-8", which.name()));
            assert!(
                text.starts_with("<svg"),
                "{} does not start with an svg element",
                which.name()
            );
            assert!(
                text.trim_end().ends_with("</svg>"),
                "{} is truncated",
                which.name()
            );
            assert!(
                usvg::Tree::from_data(source, &usvg::Options::default()).is_ok(),
                "{} does not parse as SVG",
                which.name()
            );
        }
    }

    /// The set is one family. Every file has to declare the same grid and the
    /// same stroke, because "close enough" is what makes an icon set look
    /// borrowed from three places.
    #[test]
    fn the_set_is_visually_consistent() {
        for which in Icon::ALL {
            let text = std::str::from_utf8(which.source()).expect("utf-8");
            assert!(
                text.contains(r#"viewBox="0 0 24 24""#),
                "{} is not on the 24x24 grid",
                which.name()
            );
            assert!(
                text.contains(r#"stroke-width="1.75""#),
                "{} does not use the family stroke weight",
                which.name()
            );
            assert!(
                text.contains(r#"stroke="currentColor""#),
                "{} hard-codes a colour instead of inheriting one",
                which.name()
            );
            assert!(
                !text.contains("<image"),
                "{} embeds a raster image, which will not scale",
                which.name()
            );
        }
    }

    /// Two destinations sharing a glyph is a navigation bug, not a style one.
    #[test]
    fn no_two_icons_share_a_file() {
        let mut names: Vec<&str> = Icon::ALL.iter().map(|i| i.name()).collect();
        names.sort_unstable();
        let count = names.len();
        names.dedup();
        assert_eq!(count, names.len(), "two Icon variants point at the same file");
    }
}
