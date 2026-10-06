# Appearance experience audit and implementation

## Settings model

`assets/backgrounds/manifest.json` is the only supported background catalogue.
It generates the launcher's TypeScript options and the game's Java catalogue;
the launcher's literal Rust allowlist is checked against it as a security
boundary. Eleven modern scenes are supported. Historical `golden-hour` and the
old `bg_6pull*`, gradient, and pre-underscore ids are not exposed.

| Setting | Surface | Scope and storage |
|---|---|---|
| Background | Launcher and game Appearance pickers, all Fathom backdrops | Launcher-wide `appearance.json`; either process writes the same validated key |
| Presentation | In-game Appearance | Profile-scoped `rshiftBackground`, with the legacy client preference retained for migration |
| Gameplay glass blur | In-game Appearance, only while Gameplay glass is selected | Profile-scoped `rshiftBlur`; Soft/Balanced/Strong |
| Gameplay glass dim | In-game Appearance, only while Gameplay glass is selected | Profile-scoped `rshiftDim`; Light/Balanced/Deep |
| Reduce transparency / reduce blur | Launcher Appearance | Launcher-only accessibility flags in `appearance.json` |
| Standard capitalization | Launcher Appearance; game reads it | Shared flag in `appearance.json` |
| Backdrop effects / menu video quality | In-game Performance | Client performance preferences; deliberately not duplicated on Appearance |
| Module and HUD visual settings | Individual module detail pages / HUD editor | Profile-scoped; deliberately not duplicated globally |

The iced-era `launcher-ui` implementation and its four-background frame
sequences remain quarantined source: `launcher-ui` now starts the canonical
Tauri/Svelte launcher and none of those modules are Cargo targets.

## In-game Appearance page

Appearance is a first-class Hub section. Presentation is a two-option segmented
control. Glass blur and dim occupy one dependent group whose height and opacity
interpolate on the shared monotonic clock; its controls become inert as the
group leaves, while all unrelated controls stay interactive.

The background picker is a responsive one-to-four-column grid capped at 744
logical pixels to preserve negative space. Every card is a 16:9 cover-cropped
real thumbnail with the shared radius, a one-pixel neutral rim, a small check,
and no accent glow. Pointer, wheel, arrow keys, Tab, Enter, and Space are read
at render-frame cadence.

## Assets, switching, and caching

The preparation script produces a dedicated 384×216 game thumbnail for every
manifest entry. `MenuScenes` holds thumbnails and full scenes in separate GL
texture caches and releases both only on a resource reload. Selecting a card
loads the full still first, then atomically replaces only `background` in the
shared appearance document. Other launcher keys survive. The launcher likewise
decodes the full still before changing its retained scene and uses the standard
surface-duration crossfade. A missing scene leaves the previous/fallback frame;
there is no deliberate black frame.

The game rechecks launcher-side edits at most once per second while a Fathom
menu is open. An in-game edit is guaranteed to be visible to the launcher on
its next startup; the already-running launcher intentionally has no new file
watcher or synchronization protocol.

No thumbnail or framebuffer is allocated per frame. Closed RShift never calls
the Appearance section and therefore adds no recurring work. The existing live
Gameplay Glass compositor and its cached full/half/quarter framebuffer chain
are unchanged.

## Fallbacks and migration

- Missing or malformed appearance documents resolve to Rainforest Storm and
  are repaired on the next in-game choice.
- Unknown, path-shaped, retired, and `golden-hour` ids migrate to Rainforest
  Storm in the launcher and never reach a resource path.
- Missing thumbnails draw the standard recessed fallback field; a missing full
  scene refuses the switch and keeps the current scene.
- A game launched from a portable root uses the root carried in its launch
  session; installed and standalone cases retain the platform/default fallback.
- Existing profile and legacy RShift values keep their current migration path.

Performance and visual results are recorded in
`docs/rshift-presentation-motion.md`; this pass adds the Appearance-page capture
and reruns the same live-glass measurement after the picker/cache changes.
