/**
 * The Surface: Fathom's brand geometry.
 *
 * One construction, drawn from one place. The launcher's wordmark, ring and
 * Sounding Line components render from these functions, and so does
 * `assets/brand/build-brand.mjs`, which writes the SVG masters, the PNG sizes
 * and the Windows icon. Nothing else draws the brand.
 *
 * THE RULE. Letters are built on a 100-unit cap height: 15-unit vertical stems,
 * 12-unit horizontal bars, 5-unit continuous outer corners, 2-unit inner
 * corners. A surface S runs 42 units above the baseline; nothing is drawn along
 * it. Everything below S is displaced to the right. The step where a stroke
 * crosses S stays sharp -- it is refraction, not a rounded corner.
 *
 * PIXELS. At a rendered size the displacement is about a sixth of the stem
 * width, snapped to whole screen pixels, and the surface line is snapped to a
 * pixel row. There is never a fractional displacement and never a blur.
 *
 * This file uses only erasable TypeScript, so Node can run it directly for the
 * asset build without a compiler.
 */
import { BRAND } from './brand.generated.ts';

export type Point = readonly [number, number];
export type Polygon = Point[];

// ---------------------------------------------------------------------------
// Construction, in units with y up from the baseline
// ---------------------------------------------------------------------------

const CAP = BRAND.cap;
const STEM = BRAND.stem;
const BAR = BRAND.bar;

interface Contour {
  points: Point[];
  /** A counter. All of its corners are inner corners. */
  hole: boolean;
  /** Already a curve (the O); no corners to round. */
  smooth: boolean;
}

interface Letter {
  char: string;
  width: number;
  contours: Contour[];
}

function straight(points: Point[], hole = false): Contour {
  return { points, hole, smooth: false };
}

function circle(cx: number, cy: number, r: number, segments = 128): Polygon {
  const out: Point[] = [];
  for (let i = 0; i < segments; i += 1) {
    const a = (i / segments) * Math.PI * 2;
    out.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]);
  }
  return out;
}

/* Diagonal legs: the A's and the M's run at a fixed slope, and their vertical
   thickness is chosen so the stroke measured across it is one stem. */
const A_SLOPE = 0.35; // x per unit of y
const A_LEG = 17; // horizontal thickness: 17 * cos(atan(0.35)) ≈ 16 across
const A_WIDTH = 84;
const A_BAR_TOP = 40;
const A_BAR_BOTTOM = A_BAR_TOP - BAR;
/** Where the two inner leg edges meet: the top of the A's counter. */
const A_APEX = (A_WIDTH - 2 * A_LEG) / (2 * A_SLOPE);

const M_WIDTH = 100;
const M_NOTCH = 42.8; // the top notch's floor
const M_DROP = 28.6; // vertical thickness of the diagonals: one stem across

/** The six letters, before spacing. */
const LETTERS: Letter[] = [
  {
    char: 'F',
    width: 58,
    contours: [
      straight([
        [0, 0], [STEM, 0], [STEM, 46], [50, 46], [50, 46 + BAR], [STEM, 46 + BAR],
        [STEM, CAP - BAR], [58, CAP - BAR], [58, CAP], [0, CAP],
      ]),
    ],
  },
  {
    char: 'A',
    width: A_WIDTH,
    contours: [
      straight([
        [0, 0],
        [A_LEG, 0],
        [A_LEG + A_SLOPE * A_BAR_BOTTOM, A_BAR_BOTTOM],
        [A_WIDTH - A_LEG - A_SLOPE * A_BAR_BOTTOM, A_BAR_BOTTOM],
        [A_WIDTH - A_LEG, 0],
        [A_WIDTH, 0],
        [A_WIDTH - A_SLOPE * CAP, CAP],
        [A_SLOPE * CAP, CAP],
      ]),
      straight(
        [
          [A_LEG + A_SLOPE * A_BAR_TOP, A_BAR_TOP],
          [A_WIDTH / 2, A_APEX],
          [A_WIDTH - A_LEG - A_SLOPE * A_BAR_TOP, A_BAR_TOP],
        ],
        true,
      ),
    ],
  },
  {
    char: 'T',
    width: 70,
    contours: [
      straight([
        [27.5, 0], [27.5 + STEM, 0], [27.5 + STEM, CAP - BAR], [70, CAP - BAR],
        [70, CAP], [0, CAP], [0, CAP - BAR], [27.5, CAP - BAR],
      ]),
    ],
  },
  {
    char: 'H',
    width: 76,
    contours: [
      straight([
        [0, 0], [STEM, 0], [STEM, 46], [76 - STEM, 46], [76 - STEM, 0], [76, 0],
        [76, CAP], [76 - STEM, CAP], [76 - STEM, 46 + BAR], [STEM, 46 + BAR],
        [STEM, CAP], [0, CAP],
      ]),
    ],
  },
  {
    // The O is the ring: a circle with its counter dropped, so the top wall is
    // heavier than the bottom -- 19 over, 15 at the sides, 11 under.
    char: 'O',
    width: CAP,
    contours: [
      { points: circle(CAP / 2, CAP / 2, CAP / 2), hole: false, smooth: true },
      {
        points: circle(CAP / 2, CAP / 2 - BRAND.ringDrop, CAP / 2 - BRAND.ringSide),
        hole: true,
        smooth: true,
      },
    ],
  },
  {
    char: 'M',
    width: M_WIDTH,
    contours: [
      straight([
        [0, 0], [STEM, 0], [STEM, CAP - M_DROP], [M_WIDTH / 2, M_NOTCH - M_DROP],
        [M_WIDTH - STEM, CAP - M_DROP], [M_WIDTH - STEM, 0], [M_WIDTH, 0],
        [M_WIDTH, CAP], [M_WIDTH - STEM, CAP], [M_WIDTH / 2, M_NOTCH], [STEM, CAP],
        [0, CAP],
      ]),
    ],
  },
];

/* Space after each letter, optically: the F's and A's open sides take less,
   the T's open foot takes more. */
const GAPS = [12, 10, 18, 22, 22];

// ---------------------------------------------------------------------------
// Corners
// ---------------------------------------------------------------------------

function signedArea(points: Polygon): number {
  let area = 0;
  for (let i = 0; i < points.length; i += 1) {
    const [x1, y1] = points[i];
    const [x2, y2] = points[(i + 1) % points.length];
    area += x1 * y2 - x2 * y1;
  }
  return area / 2;
}

/**
 * Fillet every corner of a polygon: `convex` radius where the outline turns
 * outward, `concave` where it turns in. Arcs are flattened finely enough that
 * they stay round at the largest size the brand is drawn.
 */
function roundCorners(points: Polygon, convex: number, concave: number): Polygon {
  const ccw = signedArea(points) > 0;
  const out: Point[] = [];
  const n = points.length;
  for (let i = 0; i < n; i += 1) {
    const p = points[i];
    const a = points[(i - 1 + n) % n];
    const b = points[(i + 1) % n];
    const ax = a[0] - p[0];
    const ay = a[1] - p[1];
    const bx = b[0] - p[0];
    const by = b[1] - p[1];
    const la = Math.hypot(ax, ay);
    const lb = Math.hypot(bx, by);
    const turn = (p[0] - a[0]) * (b[1] - p[1]) - (p[1] - a[1]) * (b[0] - p[0]);
    const isConvex = ccw ? turn > 0 : turn < 0;
    let r = isConvex ? convex : concave;
    const cos = Math.max(-1, Math.min(1, (ax * bx + ay * by) / (la * lb)));
    const theta = Math.acos(cos);
    if (r <= 0 || theta < 1e-3 || Math.PI - theta < 1e-3) {
      out.push(p);
      continue;
    }
    let t = r / Math.tan(theta / 2);
    const limit = Math.min(la, lb) / 2;
    if (t > limit) {
      t = limit;
      r = t * Math.tan(theta / 2);
    }
    const ux = ax / la;
    const uy = ay / la;
    const vx = bx / lb;
    const vy = by / lb;
    const t1: Point = [p[0] + ux * t, p[1] + uy * t];
    const t2: Point = [p[0] + vx * t, p[1] + vy * t];
    const bisx = ux + vx;
    const bisy = uy + vy;
    const bl = Math.hypot(bisx, bisy);
    const d = r / Math.sin(theta / 2);
    const cx = p[0] + (bisx / bl) * d;
    const cy = p[1] + (bisy / bl) * d;
    const a1 = Math.atan2(t1[1] - cy, t1[0] - cx);
    let delta = Math.atan2(t2[1] - cy, t2[0] - cx) - a1;
    while (delta > Math.PI) delta -= Math.PI * 2;
    while (delta <= -Math.PI) delta += Math.PI * 2;
    const steps = Math.max(3, Math.ceil(Math.abs(delta) / (Math.PI / 24)));
    for (let k = 0; k <= steps; k += 1) {
      const angle = a1 + (delta * k) / steps;
      out.push([cx + r * Math.cos(angle), cy + r * Math.sin(angle)]);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// The wordmark
// ---------------------------------------------------------------------------

export interface PlacedLetter {
  char: string;
  /** Left edge, in units from the start of the wordmark. */
  x: number;
  width: number;
  /** Rounded outlines in units, y up, already placed at `x`. */
  polygons: Polygon[];
}

function place(): PlacedLetter[] {
  let x = 0;
  return LETTERS.map((letter, index) => {
    const polygons = letter.contours.map((contour) => {
      const shaped = contour.smooth
        ? contour.points
        : contour.hole
          ? roundCorners(contour.points, BRAND.cornerInner, BRAND.cornerInner)
          : roundCorners(contour.points, BRAND.cornerOuter, BRAND.cornerInner);
      return shaped.map(([px, py]) => [px + x, py] as Point);
    });
    const placed = { char: letter.char, x, width: letter.width, polygons };
    x += letter.width + (GAPS[index] ?? 0);
    return placed;
  });
}

export const WORDMARK_LETTERS: PlacedLetter[] = place();
export const WORDMARK_UNITS = (() => {
  const last = WORDMARK_LETTERS[WORDMARK_LETTERS.length - 1];
  return last.x + last.width;
})();

/**
 * How much of the Surface a size can carry.
 *
 * - `full` (48px cap and up): the whole displacement, two densities, and the
 *   1px bronze edge detail above the surface.
 * - `reduced` (20–47px): a 1px displacement and the two densities, no edge.
 * - `plain` (under 20px): the letters alone. The icon carries the identity.
 */
export type WordmarkTier = 'plain' | 'reduced' | 'full';

export function wordmarkTier(capPx: number): WordmarkTier {
  if (capPx >= BRAND.tierFull) return 'full';
  if (capPx >= BRAND.tierReduced) return 'reduced';
  return 'plain';
}

export interface WordmarkLayout {
  tier: WordmarkTier;
  cap: number;
  /** Pixels per unit. */
  scale: number;
  width: number;
  height: number;
  /** The surface's pixel row, from the top. Everything at or below it shifts. */
  surfaceY: number;
  /** Whole-pixel displacement below the surface. */
  shift: number;
  /** Whole-pixel offset of the bronze edge detail; 0 when the tier has none. */
  edge: number;
  letters: { char: string; path: string; centre: number }[];
}

function pathFor(polygons: Polygon[], map: (p: Point) => Point): string {
  return polygons
    .map((polygon) => {
      const points = polygon.map(map);
      return (
        'M' +
        points.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join('L') +
        'Z'
      );
    })
    .join('');
}

/**
 * The wordmark laid out for a cap height in CSS pixels. Paths are in pixels,
 * y down, ready for an SVG whose viewBox is `0 0 width height`.
 */
export function layoutWordmark(capPx: number): WordmarkLayout {
  const cap = Math.max(1, Math.round(capPx));
  const scale = cap / CAP;
  const tier = wordmarkTier(cap);
  const stemPx = STEM * scale;
  const shift = tier === 'plain' ? 0 : tier === 'reduced' ? 1 : Math.max(1, Math.round(stemPx / 6));
  const edge = tier === 'full' ? 1 : 0;
  const surfaceY = Math.round((CAP - BRAND.surface) * scale);
  const letters = WORDMARK_LETTERS.map((letter) => {
    // Each letter starts on a whole pixel, so stems land on the same subpixel
    // phase at every size rather than drifting across the word.
    const origin = Math.round(letter.x * scale);
    const map = ([x, y]: Point): Point => [origin + (x - letter.x) * scale, (CAP - y) * scale];
    return {
      char: letter.char,
      path: pathFor(letter.polygons, map),
      centre: origin + (letter.width * scale) / 2,
    };
  });
  return {
    tier,
    cap,
    scale,
    width: Math.ceil(WORDMARK_UNITS * scale) + shift + edge,
    height: cap + edge,
    surfaceY,
    shift,
    edge,
    letters,
  };
}

export interface BrandInk {
  air: string;
  beneath: string;
  edge: string;
}

export const LIGHT_INK: BrandInk = { air: BRAND.air, beneath: BRAND.beneath, edge: BRAND.edge };
export const DARK_INK: BrandInk = { air: BRAND.darkAir, beneath: BRAND.darkBeneath, edge: BRAND.darkEdge };

/**
 * A standalone SVG of the wordmark, for the brand masters. The launcher's
 * component renders the same layout itself so it can animate it.
 */
export function wordmarkSvg(capPx: number, ink: BrandInk = LIGHT_INK): string {
  const l = layoutWordmark(capPx);
  const all = l.letters.map((letter) => letter.path).join('');
  const head = `<svg xmlns="http://www.w3.org/2000/svg" width="${l.width}" height="${l.height}" viewBox="0 0 ${l.width} ${l.height}" role="img" aria-label="Fathom">`;
  if (l.tier === 'plain') {
    return `${head}<path fill="${ink.air}" fill-opacity="${BRAND.airOpacity}" fill-rule="evenodd" d="${all}"/></svg>`;
  }
  const defs =
    `<defs><clipPath id="air"><rect x="-1" y="-1" width="${l.width + 2}" height="${l.surfaceY + 1}"/></clipPath>` +
    `<clipPath id="beneath"><rect x="-1" y="${l.surfaceY}" width="${l.width + 2}" height="${l.height - l.surfaceY + 1}"/></clipPath></defs>`;
  const edge = l.edge
    ? `<g clip-path="url(#air)"><path fill="${ink.edge}" fill-rule="evenodd" transform="translate(${l.edge} ${l.edge})" d="${all}"/></g>`
    : '';
  const air = `<g opacity="${BRAND.airOpacity}">${edge}<g clip-path="url(#air)"><path fill="${ink.air}" fill-rule="evenodd" d="${all}"/></g></g>`;
  const beneath = `<g transform="translate(${l.shift} 0)" opacity="${BRAND.beneathOpacity}"><g clip-path="url(#beneath)"><path fill="${ink.beneath}" fill-rule="evenodd" d="${all}"/></g></g>`;
  return `${head}${defs}${air}${beneath}</svg>`;
}

// ---------------------------------------------------------------------------
// The offset ring
// ---------------------------------------------------------------------------

/**
 * `one` (under 24px): a single tone, the dropped counter alone carries it.
 * `two` (24–47px): ivory above the surface, ivory-beneath below.
 * `full` (48px and up): the two tones and the bronze edge detail.
 */
export type RingTier = 'one' | 'two' | 'full';

export function ringTier(sizePx: number): RingTier {
  if (sizePx >= BRAND.tierFull) return 'full';
  if (sizePx >= 24) return 'two';
  return 'one';
}

export interface RingGeometry {
  tier: RingTier;
  size: number;
  cx: number;
  cy: number;
  /** Outer radius. */
  outer: number;
  /** Counter radius. */
  inner: number;
  /** The counter's centre, dropped below the ring's. */
  holeCy: number;
  /** Pixel row of the surface; the ring's lower tone starts here. */
  surfaceY: number;
  /** Bronze edge offset in pixels; 0 when the tier has none. */
  edge: number;
}

/**
 * The ring for a square of `size` pixels.
 *
 * Every dimension is solved in pixels for that size rather than scaled from a
 * master: the diameter is even so the ring centres on the grid, the side walls
 * are whole pixels, and the counter drops by at least one full pixel, so at
 * 16px the top wall is visibly heavier than the bottom and the icon cannot
 * collapse into a plain thin circle. `diameter` defaults to 72% of the size,
 * which leaves the tile a margin; the hero ring passes the full size.
 */
export function ringGeometry(size: number, diameter = 2 * Math.round(size * 0.36)): RingGeometry {
  const tier = ringTier(size);
  const outer = diameter / 2;
  const side = Math.max(2, Math.round((diameter * BRAND.ringSide) / CAP));
  const inner = outer - side;
  const exactDrop = (diameter * BRAND.ringDrop) / CAP;
  const drop = diameter < 48 ? Math.max(1, Math.round(exactDrop)) : Math.round(exactDrop * 2) / 2;
  const cx = size / 2;
  const cy = size / 2;
  return {
    tier,
    size,
    cx,
    cy,
    outer,
    inner,
    holeCy: cy + drop,
    surfaceY: Math.round(cy + outer - (diameter * BRAND.surface) / CAP),
    edge: tier === 'full' ? Math.max(1, Math.round(size / 64)) : 0,
  };
}

/** The ring as an even-odd SVG path: the outer circle and its counter. */
export function ringPath(g: RingGeometry): string {
  const arc = (cx: number, cy: number, r: number) =>
    `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0Z`;
  return arc(g.cx, g.cy, g.outer) + arc(g.cx, g.holeCy, g.inner);
}

/** The same ring as polygons, for the rasteriser. */
export function ringPolygons(g: RingGeometry): Polygon[] {
  return [circle(g.cx, g.cy, g.outer, 256), circle(g.cx, g.holeCy, g.inner, 256)];
}

/**
 * The icon tile: a continuous-curve square (a superellipse), so its corners
 * ease into the sides instead of meeting them at a visible join.
 */
export function tilePolygon(size: number, exponent = 5, segments = 256): Polygon {
  const half = size / 2;
  const out: Point[] = [];
  for (let i = 0; i < segments; i += 1) {
    const a = (i / segments) * Math.PI * 2;
    const c = Math.cos(a);
    const s = Math.sin(a);
    out.push([
      half + half * Math.sign(c) * Math.abs(c) ** (2 / exponent),
      half + half * Math.sign(s) * Math.abs(s) ** (2 / exponent),
    ]);
  }
  return out;
}

export function tilePath(size: number): string {
  const points = tilePolygon(size);
  return 'M' + points.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join('L') + 'Z';
}

// ---------------------------------------------------------------------------
// The Sounding Line
// ---------------------------------------------------------------------------

export interface SoundingGeometry {
  height: number;
  width: number;
  /** Stem width in whole pixels. */
  stem: number;
  /** Whole-pixel step to the right below the surface. */
  step: number;
  /** Pixel row of the surface, from the top. */
  surfaceY: number;
  /** Radius of the stem's two outer ends. */
  radius: number;
}

/**
 * One stem crossing the surface and stepping right beneath it: the brand's
 * refraction reduced to a single stroke. Proportioned like the wordmark's
 * stems, with the stem at least two pixels wide so it survives at 12px.
 */
export function soundingGeometry(heightPx: number): SoundingGeometry {
  const height = Math.max(8, Math.round(heightPx));
  const stem = Math.max(2, Math.round((height * STEM) / CAP));
  const step = Math.max(1, Math.round(stem / 6));
  return {
    height,
    width: stem + step,
    stem,
    step,
    surfaceY: Math.round((height * (CAP - BRAND.surface)) / CAP),
    radius: Math.min(stem / 2, (height * BRAND.cornerOuter) / CAP),
  };
}

/**
 * The upper segment (rounded top, square at the surface) and the lower one
 * (square at the surface, rounded bottom), with `depth` 0–1 of the lower
 * segment drawn -- the Sounding Line's measure of progress.
 */
export function soundingPaths(g: SoundingGeometry, depth = 1): { air: string; beneath: string } {
  const r = g.radius;
  const s = g.surfaceY;
  const air =
    `M0 ${s}V${r}Q0 0 ${r} 0H${g.stem - r}Q${g.stem} 0 ${g.stem} ${r}V${s}Z`;
  const full = g.height - s;
  const length = Math.max(0, Math.min(1, depth)) * full;
  if (length <= 0) return { air, beneath: '' };
  const x0 = g.step;
  const x1 = g.step + g.stem;
  const bottom = s + length;
  const rr = Math.min(r, length / 2);
  const beneath =
    `M${x0} ${s}H${x1}V${bottom - rr}Q${x1} ${bottom} ${x1 - rr} ${bottom}H${x0 + rr}Q${x0} ${bottom} ${x0} ${bottom - rr}Z`;
  return { air, beneath };
}
