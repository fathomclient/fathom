/*
 * The approved backgrounds.
 *
 * Fathom's backgrounds are curated, not collected: every entry here is a
 * recording the glass system is tuned against. Right now there is exactly
 * one. The list stays a list so that a small curated set can be added
 * later without touching the player or the shell -- the picker reappears on its
 * own once there is more than one entry (`PICKER_AVAILABLE`).
 *
 * Three things have to agree about this list: the picker, the player, and
 * `crates/launcher-desktop/src/lib.rs`, whose allowlist decides which ids the
 * WebView may ask for at all. A test over there reads this file and fails if
 * the two stop matching.
 *
 * Every entry has a full-resolution PNG at
 * `desktop/public/backgrounds/<id>.png`. The still keeps the approved scene
 * sharp and avoids background decoder work in the launcher.
 */

export interface BackgroundOption {
  id: string;
  label: string;
  /** Plain-text credit shown in Settings; the full record is ATTRIBUTION.md. */
  credit: string;
}

export const BACKGROUNDS: BackgroundOption[] = [
  {
    id: 'bg_5rain',
    label: 'Rainforest Storm',
    credit: 'Community-made Minecraft world recording; see ATTRIBUTION.md',
  },
];

/**
 * What a first run shows, and what any retired or unknown id becomes. Rust owns
 * the stored preference and has the same default (`DEFAULT_BACKGROUND` in
 * `crates/launcher-desktop/src/lib.rs`); this one is only what the window paints
 * before that answer arrives.
 */
export const DEFAULT_BACKGROUND = 'bg_5rain';

/** A picker with one entry is a control that does nothing, so there is none. */
export const PICKER_AVAILABLE = BACKGROUNDS.length > 1;

/** Whether an id is one of the approved backgrounds. */
export function isApproved(id: string): boolean {
  return BACKGROUNDS.some((option) => option.id === id);
}

/** The approved id to show for a stored preference: itself, or the default. */
export function approvedOrDefault(id: string): string {
  return isApproved(id) ? id : DEFAULT_BACKGROUND;
}

/** The image behind a background, which is also its picker thumbnail. */
export function imageFor(id: string): string {
  return `/backgrounds/${id}.png`;
}
