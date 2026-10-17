/**
 * The appearance modes, applied to the document.
 *
 * Attributes on `<html>` and nothing else: `data-transparency="reduced"`,
 * `data-blur="off"` and `data-case="standard"`. The token swaps the glass ones
 * trigger are generated into `theme.generated.css`, so no component knows these
 * modes exist -- a surface built from a material simply gets the right fill.
 * The case one lifts the lowercase rule in `theme.css`; without it every word
 * is drawn in lowercase, which is also what the first paint shows.
 *
 * Windows' own "Transparency effects" switch is not read here. It arrives as
 * `prefers-reduced-transparency` and the stylesheet honours it directly, so it
 * applies from the first paint and in both windows without a round trip.
 */
import {
  appearanceModes,
  setAppearanceModes,
  DEFAULT_APPEARANCE_MODES,
  type AppearanceModes,
} from './api';

export function applyAppearanceModes(modes: AppearanceModes): void {
  const root = document.documentElement;
  if (modes.reduceTransparency) root.dataset.transparency = 'reduced';
  else delete root.dataset.transparency;
  if (modes.reduceBlur) root.dataset.blur = 'off';
  else delete root.dataset.blur;
  if (modes.standardCapitalization) root.dataset.case = 'standard';
  else delete root.dataset.case;
}

/** Read the stored modes and apply them. Never throws: the default is full glass, in lowercase. */
export async function restoreAppearanceModes(): Promise<AppearanceModes> {
  let modes = DEFAULT_APPEARANCE_MODES;
  try {
    modes = await appearanceModes();
  } catch {
    /* An unreadable preference is the default, not an error screen. */
  }
  applyAppearanceModes(modes);
  return modes;
}

/** Apply first, then persist, so the switch answers immediately. */
export async function changeAppearanceModes(modes: AppearanceModes): Promise<void> {
  applyAppearanceModes(modes);
  await setAppearanceModes(modes);
}
