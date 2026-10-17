/**
 * The motion vocabulary.
 *
 * Svelte transitions take numbers rather than CSS variables, so the shared theme
 * generator emits the numeric exports that this module re-exports beside the
 * easing functions.
 *
 * Quick, smooth and quiet: nothing bounces, overshoots or springs, and nothing
 * a pointer triggers takes longer than about 200 ms.
 */

export { FAST, NORMAL, SLOW, STAGGER } from './motion.generated';
export {
  MOTION_PRESS,
  MOTION_HOVER,
  MOTION_STATE,
  MOTION_PAGE,
  MOTION_SURFACE,
  MOTION_EXIT,
  MOTION_BACKDROP,
  MOTION_STAGGER,
  PRESS_SCALE,
} from './motion.generated';
import { CURVE_EXIT, CURVE_STANDARD } from './motion.generated';

/**
 * A CSS `cubic-bezier()` as a function of time, evaluated the way the browser
 * does: solve the curve's x for `t`, then read its y.
 *
 * This exists so JavaScript-driven transitions use exactly the curve that
 * `--ease-standard` gives CSS ones and that `Ease.java` gives the game. The
 * previous `EASE` was a cubic ease-out polynomial, which is close to that curve
 * but not the same one, so a Svelte fade and a CSS hover in the same component
 * moved differently.
 */
function bezier([x1, y1, x2, y2]: readonly [number, number, number, number]) {
  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;
  const sampleX = (s: number) => ((ax * s + bx) * s + cx) * s;
  const sampleY = (s: number) => ((ay * s + by) * s + cy) * s;
  const slopeX = (s: number) => (3 * ax * s + 2 * bx) * s + cx;

  return (t: number): number => {
    if (t <= 0) return 0;
    if (t >= 1) return 1;
    // Newton's method converges in a few steps for every curve this system
    // uses; bisection is the fallback for a flat slope.
    let s = t;
    for (let i = 0; i < 6; i += 1) {
      const error = sampleX(s) - t;
      if (Math.abs(error) < 1e-5) return sampleY(s);
      const slope = slopeX(s);
      if (Math.abs(slope) < 1e-6) break;
      s -= error / slope;
    }
    let low = 0;
    let high = 1;
    s = t;
    for (let i = 0; i < 20; i += 1) {
      const x = sampleX(s);
      if (Math.abs(x - t) < 1e-5) break;
      if (x < t) low = s;
      else high = s;
      s = (low + high) / 2;
    }
    return sampleY(s);
  };
}

/**
 * The one entrance curve, `--ease-standard`.
 *
 * An ease-out rather than an ease-in-out: a transition the user *caused* should
 * begin at full speed, because they already know it started.
 */
export const EASE = bezier(CURVE_STANDARD);

/** For things leaving, which should get out of the way promptly. `--ease-exit`. */
export const EASE_EXIT = bezier(CURVE_EXIT);
