# In-game visual effects

Fathom's depth and state effects are defined by
`fabric/ui/VisualEffects.java`. Components should use these helpers instead of
inventing blur sizes or opacity values locally.

## Direction

Rounded, dark, clean, red-accented. Surfaces are near-black and flat; red appears only
where something is active; motion, not lighting, carries interaction energy.

Rules:

- **Hover never produces light.** Hover is a 1–2 unit lift or a small scale, a
  one-step brighter surface, a stronger border and brighter text or icon.
- **Glow is for active state only**: enabled modules, selected navigation,
  focused input, the open settings module, the primary action. It is a tight
  4–6 unit falloff around an icon or an active edge, never a bloom around a
  whole component.
- **No white decorative lighting.** White is for text, the logo and necessary
  control detail. There is no sheen, top light or cursor light.
- **No ambient wash.** The page and frame are charcoal; red light does not tint
  the background.

## Sidebar and categories

The sidebar has no rail or ticks: a rounded red marker slides to the selected
section, whose rounded row has a darker raised face, a base-red border and a red
icon with a tight light. Hover changes only the row surface and icon brightness.
The category selector is one quiet rounded track; inactive categories are text,
and a rounded pill with a red border slides to the active one.

## Palette and geometry

`Skin` surface levels:

- `BACKGROUND` `#050607` — frame and deepest page tone.
- `RECESSED` `#08090B` — tracks, fields, wells, toolbar.
- `SURFACE` `#0A0B0D` — dormant card face.
- `ELEVATED` `#101216` — selected rows and controls.
- `FLOATING` `#0E1013` — drawers, popovers and modals (with shadow).

Borders are muted charcoal (white at 6–17% alpha over those surfaces).

Red stays dark and derives from `BASE_RED` `#841C24`: `ACTIVE_RED` `#A0232D`
for active borders, `HOT_RED` `#BE2F3A` for the brightest state detail and
icons. No pink, magenta or orange-red.

Radii: main window 16, cards and drawers 12, icon containers, search and top
controls 10, buttons and rows 8, small inset shapes 6. Toggles, badges, status
tags, icon-only buttons and the active category are fully rounded. Roundness
is part of Fathom's character; identity comes from hierarchy, colour, depth,
motion and state, never from decorative geometry.

## Helpers

- **Energy glow / state glow** — one box-gradient falloff, 6 units for
  components and 4 for markers and icons. No hover parameter.
- **Energy wash** — a small dark-red radial light contained behind an active
  icon.
- **Activation pulse** — a hairline ring expanding 3 units and fading.
- **Layered surface** — a flat face darkening slightly toward the bottom; hover
  lifts it one small step. Never lightens toward white.
- **Page depth** — a recessed-to-background vertical gradient, nothing else.
- **Panel / overlay / modal shadow** — the existing neutral presets.

## Module cards

Enabled cards keep a dark face. The state is carried by the red border, a
red-tinted rounded icon well with a tight glow, the red icon and the
`ENABLED` tag. Disabled cards use a neutral border and muted icon. A selected
card adds a slightly brighter face and a hotter, heavier border.

Hover lifts the card 1.5 units, scales it by 0.5%, steps the face and border
brighter, brightens the title and icon, and scales the icon by 3%. Enabling and
disabling play a rounded hairline pulse and the border and toggle animate.

## Settings drawer

Darker and more utilitarian than the content: floating surface, charcoal border
touched with base red, and a recessed well holding the setting rows.
Only the selected module's header icon has a glow. Rows brighten their surface
and text on hover; a selected row gets a rounded red marker.

## Main menu

The logo has no halo. Singleplayer and Multiplayer are near-black rounded
rectangles (radius 10) with a charcoal border. Hover lifts 1.5 units, reddens the
border, brightens the label and slides in a chevron. No bloom. Dock icons are
circles in a rounded tray.

## Rendering cost

A glow is one NanoVG box-gradient fill drawn under an opaque surface; on the
compatibility renderer it is one draw from the cached falloff atlas. Energy and page paints are
shared static instances consumed immediately on the UI thread, so no `Paint` is
allocated per frame. No helper creates textures, rebuilds resources, reads the
framebuffer or rasterizes on the CPU per frame.

## Interaction-state QA

Visual-only flags for states that need a pointer or a moment in time:

```text
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaPointer=470,300             # hover
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaFocusSearch                 # search focus
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaAllModules -PqaReplay=0.12  # pulse, page switch
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaOpenSettings                # settings drawer
```

`qaPointer` is in UI units. `qaReplay` restarts the page switch and card
activation and captures that many seconds later. `qaAllModules` renders every
card as enabled without changing configuration. The capture log reports mean UI
render time.
