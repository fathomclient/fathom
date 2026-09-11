/*
 * The background catalogue.
 *
 * One list, because three things have to agree about it: the picker that offers
 * them, the player that has to know whether an id is a video or a still, and
 * `crates/launcher-desktop/src/main.rs`, whose allowlist decides which ids the
 * WebView may ask for at all. Those last two drifted once -- the picker offered
 * five recordings Rust refused -- so a test over there reads this file and fails
 * if the two lists stop matching.
 *
 * A `video` needs `<id>.webm` and a `<id>.jpg` poster in
 * `desktop/public/backgrounds`; a `still` needs only the jpg. The picker uses
 * the jpg as its thumbnail either way, which is why both live beside each other.
 *
 * Ordered alphabetically by label, because that is the order the picker shows
 * them in and a list of fifteen thumbnails is something people scan for a name
 * rather than read top to bottom. Midnight -- the plain gradient, the one
 * without footage -- sorts into the middle with everything else rather than
 * being pinned to the front.
 */

export type BackgroundKind = 'gradient' | 'video' | 'still';

export interface BackgroundOption {
  id: string;
  label: string;
  kind: BackgroundKind;
}

export const BACKGROUNDS: BackgroundOption[] = [
  { id: 'bg_1', label: 'Cherry Grove', kind: 'video' },
  { id: 'bg_6pull_city-overlook', label: 'City Overlook', kind: 'still' },
  { id: 'bg_6clear', label: 'Metropolis', kind: 'video' },
  { id: 'static', label: 'Midnight', kind: 'gradient' },
  { id: 'bg_3', label: 'Midnight Coast', kind: 'video' },
  { id: 'bg_6midnight', label: 'Midnight Metropolis', kind: 'video' },
  { id: 'bg_6pull_moon-tower', label: 'Moon Tower', kind: 'still' },
  { id: 'bg_6pull_neon-grid', label: 'Neon Grid', kind: 'still' },
  { id: 'bg_5noraintickunfrozen', label: 'Rainforest Drift', kind: 'video' },
  { id: 'bg_5rain', label: 'Rainforest Storm', kind: 'video' },
  { id: 'bg_5noraintickfrozen', label: 'Rainforest Twilight', kind: 'video' },
  { id: 'bg_6pull_red-tower', label: 'Red Tower', kind: 'still' },
  { id: 'bg_4', label: 'Savanna', kind: 'video' },
  { id: 'bg_2', label: 'Snowfall', kind: 'video' },
  { id: 'bg_6pull_storm-panorama', label: 'Storm Panorama', kind: 'still' },
];

/** Unknown ids are treated as video, which is what every id was before stills. */
export function kindOf(id: string): BackgroundKind {
  return BACKGROUNDS.find((option) => option.id === id)?.kind ?? 'video';
}

/** The asset behind a background. A still is the same jpg the picker shows. */
export function sourceFor(id: string): string {
  return kindOf(id) === 'still' ? `/backgrounds/${id}.jpg` : `/backgrounds/${id}.webm`;
}
