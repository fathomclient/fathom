/*
 * Aperture: Fathom's mark, and the rules that set it beside the wordmark.
 *
 * A frame six units square with one-unit walls, open at its bottom-right
 * corner. The missing corner, two units square, is set inside as the plane,
 * moved in by its own width -- where it lands dead centre, one unit from every
 * wall it faces, its outer corner pointing straight at the opening.
 *
 * Every size is solved in whole pixels. In an app icon of S px the unit is
 * floor(S / 7) and the frame, 6 units, is centred with a margin of
 * (S - 6u) / 2 -- a whole number at every size the icon ships in, so no edge
 * falls between two pixels. The icon is the mark alone, with no tile. The
 * tiers are read in the units of the tile the mark was first drawn on, 8 per
 * unit: below 24 px the corners stay square (at 2 px walls a curve is only
 * grey pixels); from 24 px the outer corners take a 1.5-unit radius and the
 * inner ones 0.5, concentric; from 48 px the plane's corners soften to a
 * quarter unit.
 *
 * The wordmark stands on the same baseline as the mark, and the plane's top is
 * its x-height. That fixes the mark at one and a half x-heights and the gap at
 * half the mark (3 units). Clear space is the missing corner: 2 units.
 *
 * Numbers and inks are `system.brand` in the theme manifest; the wordmark's
 * outlines and optical tiers come from tools/generate-wordmark.py. The launcher,
 * `assets/brand/build-brand.mjs` and the game's generated geometry all draw
 * from here.
 */
import { BRAND } from './brand.generated.ts';
import { WORDMARK, WORDMARK_TIERS, type WordmarkTier } from './wordmark.generated.ts';

export type Point = readonly [number, number];
export type Polygon = Point[];
export type MarkTier = 'pixel' | 'curve' | 'full';

/** A closed outline whose corners are rounded by circular arcs of `radii[i]`. */
export interface Outline {
  points: Point[];
  radii: number[];
}

// ---------------------------------------------------------------------------
// The mark
// ---------------------------------------------------------------------------

const GRID = BRAND.grid;
const WALL = BRAND.wall;
const PLANE = BRAND.plane;
/** Where the open walls stop, measured from the frame's outer edge. */
const REACH = GRID - PLANE;
/** The plane's offset: its own width in from the missing corner. */
const PLANE_AT = REACH - PLANE;

/** The tier for a mark drawn at `unit` px per unit, tiled or not. */
export function markTier(unit: number): MarkTier {
  const tile = unit * BRAND.unitDivisor;
  if (tile >= BRAND.tierFull) return 'full';
  if (tile >= BRAND.tierCurve) return 'curve';
  return 'pixel';
}

export interface MarkGeometry {
  tier: MarkTier;
  /** Pixels per unit. */
  unit: number;
  /** The frame's top-left corner. */
  x: number;
  y: number;
  /** The frame's side: six units. */
  size: number;
  outer: number;
  inner: number;
  plane: number;
}

/** The mark with its frame's top-left at `x, y`, at `unit` px per unit. */
export function markGeometry(unit: number, x = 0, y = 0): MarkGeometry {
  const tier = markTier(unit);
  return {
    tier,
    unit,
    x,
    y,
    size: GRID * unit,
    outer: tier === 'pixel' ? 0 : BRAND.cornerOuter * unit,
    inner: tier === 'pixel' ? 0 : BRAND.cornerInner * unit,
    plane: tier === 'full' ? BRAND.cornerPlane * unit : 0,
  };
}

/**
 * The mark alone in an icon of `size` px, with no tile behind it: frame and
 * plane on transparency, so a dark taskbar shows no grey edge round the mark
 * and no grey square round the plane.
 *
 * Without a tile to fill the square, the mark is set larger than it was on
 * one: a unit of floor(size / icon-divisor), which leaves roughly half a unit
 * of clear space on every side, centred on whole pixels (6u is always even).
 */
export function iconMark(size: number): MarkGeometry {
  const unit = Math.max(2, Math.floor(size / BRAND.iconDivisor));
  const margin = (size - GRID * unit) / 2;
  return markGeometry(unit, margin, margin);
}

/**
 * The frame. `closing` 0 is the mark at rest, open at the corner; toward 1 the
 * open walls grow until the frame is whole -- the loader's measure. A whole
 * frame is two outlines (outside and inside), to be filled even-odd.
 */
export function frameOutlines(g: MarkGeometry, closing = 0): Outline[] {
  const { x, y, unit: u, size: w, outer: ro, inner: ri } = g;
  const b = WALL * u;
  const t = Math.max(0, Math.min(1, closing));
  if (t >= 1) {
    return [rect(x, y, w, w, ro), rect(x + b, y + b, w - 2 * b, w - 2 * b, ri)];
  }
  const reach = (REACH + t * PLANE) * u;
  return [{
    points: [
      [x, y], [x + w, y], [x + w, y + reach], [x + w - b, y + reach],
      [x + w - b, y + b], [x + b, y + b], [x + b, y + w - b],
      [x + reach, y + w - b], [x + reach, y + w], [x, y + w],
    ],
    // The three closed outer corners and the three inner ones round; the open
    // ends are cut square.
    radii: [ro, ro, 0, 0, ri, ri, ri, 0, 0, ro],
  }];
}

/**
 * The fill rule for `frameOutlines(g, closing)`. A whole frame is two outlines
 * and needs even-odd; an open one is a single outline whose growing walls
 * overlap near the corner as it closes, which needs non-zero.
 */
export function frameFillRule(closing = 0): 'evenodd' | 'nonzero' {
  return closing >= 1 ? 'evenodd' : 'nonzero';
}

/** The plane: the missing corner, set inside. */
export function planeOutline(g: MarkGeometry): Outline {
  const u = g.unit;
  return rect(g.x + PLANE_AT * u, g.y + PLANE_AT * u, PLANE * u, PLANE * u, g.plane);
}

function rect(x: number, y: number, w: number, h: number, r: number): Outline {
  return { points: [[x, y], [x + w, y], [x + w, y + h], [x, y + h]], radii: [r, r, r, r] };
}

// ---------------------------------------------------------------------------
// Outlines to SVG paths and to polygons
// ---------------------------------------------------------------------------

interface Corner {
  from: Point;
  to: Point;
  centre: Point;
  radius: number;
  sweep: 0 | 1;
}

function corners(o: Outline): (Corner | Point)[] {
  const n = o.points.length;
  return o.points.map((p, i) => {
    const r = o.radii[i] ?? 0;
    if (!r) return p;
    const prev = o.points[(i - 1 + n) % n];
    const next = o.points[(i + 1) % n];
    const toward = (q: Point): Point => {
      const len = Math.hypot(q[0] - p[0], q[1] - p[1]) || 1;
      return [p[0] + ((q[0] - p[0]) / len) * r, p[1] + ((q[1] - p[1]) / len) * r];
    };
    const from = toward(prev);
    const to = toward(next);
    // Right-angled corners only: the centre completes the square.
    const centre: Point = [from[0] + to[0] - p[0], from[1] + to[1] - p[1]];
    const cross = (from[0] - centre[0]) * (to[1] - centre[1]) - (from[1] - centre[1]) * (to[0] - centre[0]);
    return { from, to, centre, radius: r, sweep: cross > 0 ? 1 : 0 };
  });
}

const n2 = (v: number) => String(Math.round(v * 100) / 100);

/** The outline as an SVG path, with true arcs. */
export function outlinePath(o: Outline): string {
  let d = '';
  corners(o).forEach((c, i) => {
    if (Array.isArray(c)) {
      d += `${i ? 'L' : 'M'}${n2(c[0])} ${n2(c[1])}`;
    } else {
      const k = c as Corner;
      d += `${i ? 'L' : 'M'}${n2(k.from[0])} ${n2(k.from[1])}`;
      d += `A${n2(k.radius)} ${n2(k.radius)} 0 0 ${k.sweep} ${n2(k.to[0])} ${n2(k.to[1])}`;
    }
  });
  return d + 'Z';
}

/** The outline as a polygon, arcs flattened into `segments` steps each. */
export function outlinePolygon(o: Outline, segments = 12): Polygon {
  const out: Point[] = [];
  for (const c of corners(o)) {
    if (Array.isArray(c)) {
      out.push(c as Point);
      continue;
    }
    const k = c as Corner;
    const a0 = Math.atan2(k.from[1] - k.centre[1], k.from[0] - k.centre[0]);
    let a1 = Math.atan2(k.to[1] - k.centre[1], k.to[0] - k.centre[0]);
    let delta = a1 - a0;
    if (delta > Math.PI) delta -= 2 * Math.PI;
    if (delta < -Math.PI) delta += 2 * Math.PI;
    a1 = a0 + delta;
    for (let s = 0; s <= segments; s += 1) {
      const a = a0 + (delta * s) / segments;
      out.push([k.centre[0] + Math.cos(a) * k.radius, k.centre[1] + Math.sin(a) * k.radius]);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// SVG for the mark
// ---------------------------------------------------------------------------

export interface MarkInk {
  frame: string;
  plane: string;
}

export const LIGHT_INK: MarkInk = { frame: BRAND.frameInk, plane: BRAND.planeInk };
export const DARK_INK: MarkInk = { frame: BRAND.darkFrameInk, plane: BRAND.darkPlaneInk };

/** The mark's own SVG elements (no `<svg>`), at rest. */
export function markElements(g: MarkGeometry, ink: MarkInk = LIGHT_INK): string {
  const frame = frameOutlines(g).map(outlinePath).join('');
  return `<path fill="${ink.frame}" fill-rule="evenodd" d="${frame}"/>` +
    `<path fill="${ink.plane}" d="${outlinePath(planeOutline(g))}"/>`;
}

// ---------------------------------------------------------------------------
// The wordmark
// ---------------------------------------------------------------------------

export { WORDMARK, WORDMARK_TIERS };
export type { WordmarkTier };

/** The optical tier for an ascender height in device pixels. */
export function wordmarkTier(ascenderPx: number): WordmarkTier {
  if (ascenderPx <= WORDMARK_TIERS.smallMax) return 'small';
  if (ascenderPx <= WORDMARK_TIERS.textMax) return 'text';
  return 'display';
}

export interface WordmarkLayout {
  tier: WordmarkTier;
  /** Device pixels per wordmark unit. */
  scale: number;
  /** Ink width and ascender height, in device pixels. */
  width: number;
  height: number;
  /** The x-height actually drawn, in device pixels. */
  xHeight: number;
  d: string;
}

/**
 * The wordmark at an ascender height in device pixels. Up to the text tier's
 * limit the x-height is rounded to whole pixels, so the baseline and the
 * x-height both fall on pixel rows.
 */
export function wordmarkLayout(ascenderPx: number): WordmarkLayout {
  const tier = wordmarkTier(ascenderPx);
  const w = WORDMARK[tier];
  const exact = (ascenderPx * w.xHeight) / 100;
  const xHeight = ascenderPx <= WORDMARK_TIERS.textMax ? Math.max(1, Math.round(exact)) : exact;
  const scale = xHeight / w.xHeight;
  return { tier, scale, width: w.width * scale, height: 100 * scale, xHeight, d: w.d };
}

// ---------------------------------------------------------------------------
// The lockup
// ---------------------------------------------------------------------------

export interface LockupLayout {
  width: number;
  height: number;
  /** Below the baseline, for the round letters' overshoot. */
  overshoot: number;
  mark: MarkGeometry;
  word: { x: number; y: number; scale: number; d: string };
}

/** The lockup unit for a wordmark ascender of `ascenderPx`. */
export function lockupUnit(ascenderPx: number): number {
  return Math.max(2, Math.round((ascenderPx * WORDMARK.text.xHeight) / 100 / (GRID - PLANE)));
}

/**
 * The lockup at `unit` px per unit. Side by side: the mark stands on the
 * baseline, its plane's top on the x-height, three units from the word.
 * Stacked: the mark at twice the unit, centred above, three units clear.
 */
export function lockupLayout(unit: number, stacked = false): LockupLayout {
  const xHeight = (GRID - PLANE) * unit;
  const ascender = (xHeight / WORDMARK.text.xHeight) * 100;
  const tier = wordmarkTier(ascender);
  const w = WORDMARK[tier];
  const scale = xHeight / w.xHeight;
  const wordWidth = w.width * scale;
  const overshoot = w.descent * scale;
  const gap = BRAND.lockupGap * unit;
  if (!stacked) {
    const mark = markGeometry(unit, 0, 0);
    return {
      width: mark.size + gap + wordWidth,
      height: mark.size,
      overshoot,
      mark,
      word: { x: mark.size + gap, y: mark.size - 100 * scale, scale, d: w.d },
    };
  }
  const markUnit = unit * BRAND.stackedScale;
  const markSize = GRID * markUnit;
  const width = Math.max(markSize, wordWidth);
  const mark = markGeometry(markUnit, Math.round((width - markSize) / 2), 0);
  return {
    width,
    height: markSize + gap + 100 * scale,
    overshoot,
    mark,
    word: { x: (width - wordWidth) / 2, y: markSize + gap, scale, d: w.d },
  };
}

/** A lockup's SVG elements (no `<svg>`). */
export function lockupElements(l: LockupLayout, ink: MarkInk = LIGHT_INK): string {
  return markElements(l.mark, ink) +
    `<path fill="${ink.frame}" transform="translate(${n2(l.word.x)} ${n2(l.word.y)}) scale(${l.word.scale})" d="${l.word.d}"/>`;
}
