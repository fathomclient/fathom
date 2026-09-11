/**
 * The motion vocabulary.
 *
 * Three durations and one curve. Svelte transitions take numbers rather than
 * CSS variables, so the shared theme generator emits the numeric exports that
 * this module re-exports beside the easing function.
 */

export { FAST, NORMAL, SLOW, STAGGER } from './motion.generated';

/**
 * Stagger between items in a list that animates in.
 *
 * Small on purpose. At 40ms a six-item list takes a quarter of a second to
 * finish arriving, which reads as the interface being slow rather than as
 * choreography.
 */
/**
 * Cubic ease-out, the same curve as `--ease`.
 *
 * Ease-out rather than ease-in-out because a transition the user *caused*
 * should begin at full speed: they already know it started, they pressed the
 * thing. Easing in as well adds a delay before anything visibly happens, which
 * reads as lag.
 */
export function EASE(t: number): number {
  return 1 - Math.pow(1 - t, 3);
}
