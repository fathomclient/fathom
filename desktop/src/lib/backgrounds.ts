/*
 * The background library.
 *
 * The background is the user's to choose. It supplies the atmosphere; the
 * control system (`metrics` in the theme) supplies the quality, and it is the
 * same over every one of these. Nothing here is tuned per background.
 *
 * The catalogue itself is `assets/backgrounds/manifest.json`, generated into
 * `backgrounds.generated.ts` (and into the game's `BackgroundCatalog`). Three
 * things have to agree about it: the picker, the player, and the Rust
 * allowlist in `crates/launcher-desktop/src/lib.rs`, whose test reads the same
 * manifest.
 *
 * Every entry has a full-resolution still. A video background plays its
 * `.webm` over that still: the still is what shows while the first frame
 * decodes, and what stays if the video cannot play at all -- the same picture,
 * never a stretched thumbnail.
 */
import { BACKGROUNDS, DEFAULT_BACKGROUND, type BackgroundOption } from './backgrounds.generated';

export { BACKGROUNDS, DEFAULT_BACKGROUND, type BackgroundOption };

/** A picker with one entry is a control that does nothing, so there is none. */
export const PICKER_AVAILABLE = BACKGROUNDS.length > 1;

/** Whether an id is one of the library's backgrounds. */
export function isApproved(id: string): boolean {
  return BACKGROUNDS.some((option) => option.id === id);
}

/** The background for a stored preference: itself, or the default. */
export function optionFor(id: string): BackgroundOption {
  return (
    BACKGROUNDS.find((option) => option.id === id) ??
    (BACKGROUNDS.find((option) => option.id === DEFAULT_BACKGROUND) as BackgroundOption)
  );
}

/** The library id to show for a stored preference: itself, or the default. */
export function approvedOrDefault(id: string): string {
  return optionFor(id).id;
}
