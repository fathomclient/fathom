# Fathom's design system

Everything visual lives in `crates/launcher-ui/src/style.rs`, `icons.rs` and
`motion.rs`. A screen composes tokens from those; it does not invent a colour, a
size or a duration. This document is why the tokens are what they are.

## 1. The one idea

**The chrome is black and the centre is not.**

The navigation rail, the top bar and the bottom bar are true `#000`. The central
content region is the only place gameplay footage shows through. That single
rule produces the whole layout: the chrome reads as a frame around the game
rather than as panels floating on top of it.

The previous palette was navy-tinted throughout at 55–74% opacity, which made
the bars look like translucent blue-grey boxes — the "gray chrome" and "random
dark-blue boxes" this replaced. Once the bars became opaque, the background
scrim could drop from 0.72/0.88 at the edges to 0.34–0.50, because it no longer
had to keep bar text legible. The footage is substantially more visible now than
it was, and the interface is *more* readable, not less.

## 2. Colour

| Token | Value | Used for |
| --- | --- | --- |
| `CHROME` | `#000000` | Rail, top bar, bottom bar. Actually black. |
| `VOID` | `#05060 9` | Deepest content tone, scrim base |
| `MIDNIGHT` | `#0B0D12` | Static background's upper tone |
| `SURFACE` | `#0E1015` | Panels |
| `SURFACE_RAISED` | `#16181E` | Controls |
| `SURFACE_SELECTED` | `#24181B` | Selected rows — warm, because selection is the accent's job |
| `ACCENT` | `#C4303C` | The one hue in the product |
| `ACCENT_STRONG` | `#DC4A55` | Hover |
| `ACCENT_DEEP` | `#8C1F27` | Pressed, gradient base |
| `GOOD` / `WARN` / `BAD` | green / amber / `#E87272` | Status only |

The accent is a **dark red**: deep enough to sit on black without glowing, clear
enough to still read as red rather than as maroon or brown. It is used for
selection, the launch control, and destructive intent — and nothing else.
Restraint is what makes it mean something.

`BAD` is deliberately a *different* red from `ACCENT`. If the accent were also
the error colour, a selected row and a broken one would look the same.

Borders are hairlines at 7% and 14% white. They separate structural regions —
the rail from the content, one bar from another — and never fence off content.
Contrast comes from surfaces.

## 3. Typography

Two faces, with a hard line between them.

**Interface: Segoe UI Variable.** Windows 11's refinement of Segoe UI — better
spacing at small sizes, and a real optical size for headings (`Text` for body,
`Display` for large). On Windows 10 the Variable families do not exist and the
shaper falls back; no layout depends on the difference.

Weights are always requested as a *weight on the base family*, never by naming
the weighted family. Windows registers these clipped to 31 characters — the
semibold display face is `Segoe UI Variable Display Semib`, not `...Semibold` —
so a full name looks correct in source, silently fails to match, and falls back
to some other font. This was caught before it shipped and is worth remembering.

**Display: none bundled.** The wordmark and the launch control use
`style::FONT_DISPLAY`, which is currently the interface bold. The display face
Fathom used to bundle had no verifiable licence and was removed before public
release.

`style::display_safe` still checks every string before it reaches
`FONT_DISPLAY` — letters, digits and space only — so a display face with limited
glyph coverage can be reintroduced without `MINECRAFT 1.8.9` rendering as
`MINECRAFT 1` and three tofu boxes. Any face added here needs a licence that
permits redistribution, recorded in [`ATTRIBUTION.md`](../ATTRIBUTION.md).

### The scale

Named by role, never by size. A screen asks for `text::ROW_TITLE`, not for `13`.

| Token | px | Role |
| --- | --- | --- |
| `DISPLAY` | 32 | Home's version line |
| `PAGE_TITLE` | 21 | The screen you are on |
| `SECTION_TITLE` | 15 | A group heading inside a page |
| `ROW_TITLE` | 13 | First line of a list row |
| `BODY` | 13 | Running prose |
| `METADATA` | 12 | Second line of a row |
| `CAPTION` | 11 | Supporting notes |
| `BUTTON` | 12 | Text inside a control |
| `BADGE` | 10 | Uppercase chips and eyebrows |

Adding a size means adding a *role* and saying what it is for.

## 4. Icons

22 SVGs in `crates/launcher-ui/assets/icons`, drawn to one specification: a
24×24 grid, 1.75 stroke, round caps and joins, no fills except two intentional
dots, and `stroke="currentColor"` so a single style decides the colour.

They are authored in-house rather than vendored. That sidesteps the licence
question entirely and guarantees the set is coherent, which a set assembled from
three sources never is. They are placeholders for real artwork.

**Pages reference `Icon::Home`, never a file path.** `Icon` is an enum, so there
is no way to draw an icon that is not part of the set — which is the mechanism,
not just the convention, that keeps a stray emoji or system symbol out. Replacing
the artwork means editing `icons.rs` and the asset directory, and touching no
page code.

Three tests enforce it: every icon parses as SVG through the same parser the
renderer uses, every file declares the family grid and stroke, and no two
variants point at the same file.

### Why not a glyph font

The previous build drew navigation with Unicode characters (`⌂`, `▦`, `◇`) in
whatever font happened to have them. Three problems: the shapes came from
different type designers and did not look like a set, the weights did not match
the interface, and a missing glyph renders as a hollow box — which is exactly
what happened to the background switcher's tick mark. `style::FONT_ICON` no
longer exists; that constant's existence was what made reaching for an arbitrary
glyph easy.

## 5. Motion

`motion.rs`. Three durations and one curve:

| Token | ms | Used for |
| --- | --- | --- |
| `FAST` | 110 | Selection marks, toggles, hovers |
| `NORMAL` | 160 | Page changes, popovers, status |
| `SLOW` | 220 | Background crossfades, and nothing else without a reason |

The curve is a **cubic ease-out**, not ease-in-out. A transition the user
*caused* should begin at full speed: they already know it started, they pressed
the thing. Easing in as well adds a delay before anything visibly happens, which
reads as lag. Nothing bounces, overshoots or springs.

`Animated` tracks progress separately from its eased output, so **retargeting
mid-flight is continuous** — a value interrupted half-way carries on from where
it visually is rather than snapping back to the start of a new curve. That is
what keeps fast clicking from looking broken. Retargeting somewhere it is
already going is ignored, so a message that repeats every frame cannot freeze
the value at its first step.

### The rule that matters most

**The clock only runs while something is moving.** `is_moving()` gates the tick
subscription, so an idle launcher sitting on a menu requests no redraws at all.
An animation system that repaints at 60 Hz forever is a laptop battery problem,
not a polished interface.

Currently driven: the page-change transition (content rises 10 px and settles)
and the background crossfade (420 ms, its own timer predating this module).

## 6. What a screen may and may not do

May: compose `style::*` tokens, `icons::icon(...)`, `components::*`.

May not: write a hex colour, a raw font size, a raw duration, or a Unicode
symbol used as an icon. If a screen needs something the system does not have,
the system gains a token — with a name that says what it is for.

## 7. Page primitives

Home is a composition and owns its whole canvas. Every other screen is a
document, and they are all built from four components in `src/lib/ui/` rather
than from bespoke markup:

| Component | What it is |
| --- | --- |
| `Page` | Title, one line of state, an actions slot, a capped 720px column |
| `Section` | A labelled group, optionally collapsible, with a summary that survives collapsing |
| `Row` | Short title, **one** line of detail, a control, and a disclosure for anything longer |
| `Switch` / `Button` | The two controls. Three button variants, and no more |

The column is capped and centred rather than stretched. A settings row 1600px
wide has its label at one end of the monitor and its switch at the other.

### The copy rule, made structural

`Row` has nowhere to put a paragraph. `detail` is one line and it truncates with
an ellipsis; the only place a longer explanation can go is the `details` slot,
which sits behind a disclosure. This is deliberate: a component that made it
easy to write four sentences would get four sentences, on every row, forever.
The layout enforces the rule so that nobody has to remember it.

A collapsed `Section` keeps its summary — "3 of 5 on". A disclosure that hides
both the content and any indication of what is inside is just a thing to click.

### Rows, not cards

Rows inside a `Section` share one panel with hairlines between them, rather than
each being a separately bordered card. Cards would put a rectangle around every
individual setting, which is how a settings page becomes a wall of boxes.
