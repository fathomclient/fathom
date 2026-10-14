/**
 * Words and numbers for a play session, shared by Home, the header's session
 * capsule and the Performance page, so the three never phrase the same fact
 * differently.
 */

/** Hitches are frames of this many milliseconds or more: the in-game rule. */
export const HITCH_MS = 50;

/**
 * Elapsed time, to the minute. A seconds counter is a clock, and nobody
 * pressed Play to watch one.
 */
export function elapsed(seconds: number | null | undefined): string {
  if (seconds == null || seconds < 60) return 'under a minute';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

export function fps(value: number): string {
  return `${Math.round(value)}`;
}

export function ms(value: number): string {
  return value >= 100 ? `${Math.round(value)}` : value.toFixed(value >= 10 ? 1 : 2);
}

export function gigabytes(megabytes: number): string {
  return (megabytes / 1024).toFixed(1);
}

/** The explanations behind each figure, worded once for every surface. */
export const EXPLAIN = {
  fps: 'Frames per second over the last second of play.',
  average: 'Frames divided by their time, over every second of play this session.',
  p95: 'One frame in twenty took longer than this.',
  p99: 'One frame in a hundred took longer than this: the stutter you feel.',
  memory: 'Java heap in use, of the most this game may use.',
  modules: 'Fathom modules switched on.',
  hitches: `Frames of ${HITCH_MS} ms or longer: three frames at 60 fps, and plainly felt.`,
  play: 'Measured in a world with the window focused. Menus and loading screens are left out.',
} as const;
