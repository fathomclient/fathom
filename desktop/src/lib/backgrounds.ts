/*
 * The approved backgrounds.
 *
 * Fathom's backgrounds are curated, not collected: every entry here is a
 * photograph the golden-hour system is tuned against. Right now there is
 * exactly one. The list stays a list so that a small curated set can be added
 * later without touching the player or the shell -- the picker reappears on its
 * own once there is more than one entry (`PICKER_AVAILABLE`).
 *
 * Three things have to agree about this list: the picker, the player, and
 * `crates/launcher-desktop/src/lib.rs`, whose allowlist decides which ids the
 * WebView may ask for at all. A test over there reads this file and fails if
 * the two stop matching.
 *
 * Every entry is a still image at `desktop/public/backgrounds/<id>.jpg`, which
 * is also its picker thumbnail. Video backgrounds are retired: the gameplay
 * clips and their poster frames are still in that folder, unlisted, and nothing
 * on the active path loads them. The warm gradient that shows when an image
 * cannot load (`--backdrop-fallback`) is a failure-state substrate, not a
 * choice, so it is not in this list.
 */

export interface BackgroundOption {
  id: string;
  label: string;
  /** Plain-text credit shown in Settings; the full record is ATTRIBUTION.md. */
  credit: string;
}

export const BACKGROUNDS: BackgroundOption[] = [
  {
    id: 'golden-hour',
    label: 'Golden Hour',
    credit: '“Palm Tree Sunset Water” by showtime, via Wallpapers.com',
  },
];

/**
 * What a first run shows, and what any retired or unknown id becomes. Rust owns
 * the stored preference and has the same default (`DEFAULT_BACKGROUND` in
 * `crates/launcher-desktop/src/lib.rs`); this one is only what the window paints
 * before that answer arrives.
 */
export const DEFAULT_BACKGROUND = 'golden-hour';

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
  return `/backgrounds/${id}.jpg`;
}
