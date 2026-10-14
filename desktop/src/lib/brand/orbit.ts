/*
 * Orbit: Fathom's mark, and the rules that set it beside the wordmark.
 *
 * An arc sweeps up from the lower left, accelerating, and narrows to a fine
 * leading point with a four-pointed glint at its tip; a short orbital taper
 * falls away under the tip; a small star sits at the centre of the sweep. On
 * the app icon it stands on a deep rounded tile with a faint blue halo behind
 * it. Everything is drawn on a 512 grid, the icon's own size.
 *
 * Inks are `system.brand` in the theme manifest. The wordmark's outlines and
 * optical tiers come from tools/generate-wordmark.py; its ink is `word-ink`,
 * taking the arc's light (`word-tint`) over its last letters. The launcher,
 * `assets/brand/build-brand.mjs` and the game's generated geometry
 * (`generated/Mark.java`) all draw from here.
 */
import { BRAND } from './brand.generated.ts';
import { WORDMARK, WORDMARK_TIERS, type WordmarkTier } from './wordmark.generated.ts';

// ---------------------------------------------------------------------------
// The geometry, on the icon's 512 grid
// ---------------------------------------------------------------------------

export const GRID = 512;

/** The tile: a rounded square with a thin rim, centred on its edge. */
export const TILE = { x: 12, y: 12, size: 488, radius: 115, rim: 3 } as const;

/** The halo behind the mark, an ellipse lit from its centre. */
export const HALO = { cx: 292, cy: 263, rx: 185, ry: 142 } as const;

/** The long accelerating arc, narrowed to a fine leading point. */
export const ARC =
  'M104 384C76 356 116 307 176 260C247 205 327 166 398 145C414 140 426 132 428 116' +
  'C432 140 443 148 462 151C441 154 431 166 417 186C420 171 423 160 414 160' +
  'C355 159 257 207 184 259C123 304 96 349 104 384Z';

/** The orbital taper under the leading tip. */
export const TAPER = 'M423 162C414 190 398 218 381 236C393 209 397 186 393 177Z';

/** The star's glow, blurred, under the star. */
export const STAR_GLOW =
  'M282 242C285 272 287 280 300 285C287 289 285 300 282 327C278 300 275 289 263 285' +
  'C276 280 279 270 282 242Z';

/** The central star: small and crisp, so it holds at icon sizes. */
export const STAR =
  'M282 245C286 273 290 281 317 285C290 289 286 298 282 324C279 297 275 289 247 285' +
  'C275 281 279 273 282 245Z';

/** The star's two hairlines, as [x1, y1, x2, y2], and their width. */
export const STAR_CORE: readonly (readonly [number, number, number, number])[] = [
  [282, 253, 282, 316],
  [257, 285, 308, 285],
];
export const STAR_CORE_WIDTH = 2;

/** The arc's gradient, along its own box: up from the lower left to the tip. */
export const ORBIT_STOPS = [0, 0.26, 0.7, 1] as const;
export const ORBIT_VECTOR = { x1: 0, y1: 1, x2: 0.9, y2: 0 } as const;

/** The tile's gradient stops, top left to bottom right. */
export const TILE_STOPS = [0, 0.48, 1] as const;

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

type Segment = ['M', number, number] | ['C', number, number, number, number, number, number] | ['Z'];

/** An absolute M/C/Z path as segments: the only commands the mark uses. */
export function segments(d: string): Segment[] {
  const out: Segment[] = [];
  for (const [, cmd, body] of d.matchAll(/([MCZ])([^MCZ]*)/g)) {
    const n = body.trim() ? body.trim().split(/[\s,]+/).map(Number) : [];
    if (cmd === 'M') out.push(['M', n[0], n[1]]);
    else if (cmd === 'C') {
      for (let i = 0; i + 5 < n.length; i += 6) out.push(['C', n[i], n[i + 1], n[i + 2], n[i + 3], n[i + 4], n[i + 5]]);
    } else out.push(['Z']);
  }
  return out;
}

/** The exact box of a path's ink: cubic extrema, not control points. */
export function pathBox(d: string): Box {
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  const take = (x: number, y: number) => {
    x0 = Math.min(x0, x);
    y0 = Math.min(y0, y);
    x1 = Math.max(x1, x);
    y1 = Math.max(y1, y);
  };
  let px = 0;
  let py = 0;
  for (const s of segments(d)) {
    if (s[0] === 'M') {
      [px, py] = [s[1], s[2]];
      take(px, py);
    } else if (s[0] === 'C') {
      const [, ax, ay, bx, by, cx, cy] = s;
      for (let i = 0; i <= 64; i += 1) {
        const t = i / 64;
        const u = 1 - t;
        take(
          u * u * u * px + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * cx,
          u * u * u * py + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * cy,
        );
      }
      [px, py] = [cx, cy];
    }
  }
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

function union(a: Box, b: Box): Box {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return {
    x,
    y,
    width: Math.max(a.x + a.width, b.x + b.width) - x,
    height: Math.max(a.y + a.height, b.y + b.height) - y,
  };
}

/** The arc's box, which its gradient is laid along. */
export const ARC_BOX = pathBox(ARC);

/**
 * The mark's box without its tile: the arc, the taper and the star, in whole
 * grid pixels. The star's glow and the halo spill past it, as light does.
 */
export const MARK_BOX: Box = (() => {
  const b = [TAPER, STAR].map(pathBox).reduce(union, ARC_BOX);
  const x = Math.floor(b.x);
  const y = Math.floor(b.y);
  return { x, y, width: Math.ceil(b.x + b.width) - x, height: Math.ceil(b.y + b.height) - y };
})();

/** Width over height of the mark without its tile. */
export const MARK_ASPECT = MARK_BOX.width / MARK_BOX.height;

// ---------------------------------------------------------------------------
// Inks
// ---------------------------------------------------------------------------

export interface MarkInk {
  /** The arc's four stops, lower left to tip. */
  orbit: readonly [string, string, string, string];
  taper: string;
  star: string;
  /** Null where the ground is light: a glow there only greys it. */
  glow: string | null;
  core: string | null;
}

/** For dark grounds: the icon's own inks. */
export const LIGHT_INK: MarkInk = {
  orbit: [BRAND.orbitStart, BRAND.orbitLight, BRAND.orbitMid, BRAND.orbitTip],
  taper: BRAND.taper,
  star: BRAND.star,
  glow: BRAND.starGlow,
  core: BRAND.starCore,
};

/** For light grounds: the same blues, a density deeper, and no glow. */
export const DARK_INK: MarkInk = {
  orbit: [BRAND.darkOrbitStart, BRAND.darkOrbitLight, BRAND.darkOrbitMid, BRAND.darkOrbitTip],
  taper: BRAND.darkOrbitLight,
  star: BRAND.darkStar,
  glow: null,
  core: null,
};

/** One ink, for a stamp or a mask. */
export function oneInk(colour: string): MarkInk {
  return { orbit: [colour, colour, colour, colour], taper: colour, star: colour, glow: null, core: null };
}

export interface WordInk {
  ink: string;
  tint: string;
}

export const LIGHT_WORD: WordInk = { ink: BRAND.wordInk, tint: BRAND.wordTint };
export const DARK_WORD: WordInk = { ink: BRAND.darkWordInk, tint: BRAND.darkWordTint };

// ---------------------------------------------------------------------------
// SVG
// ---------------------------------------------------------------------------

const n2 = (v: number) => String(Math.round(v * 100) / 100);

const stops = (offsets: readonly number[], colours: readonly string[], opacity?: readonly number[]) =>
  offsets
    .map((o, i) => `<stop offset="${o}" stop-color="${colours[i]}"${opacity ? ` stop-opacity="${opacity[i]}"` : ''}/>`)
    .join('');

/**
 * The mark's SVG elements on the 512 grid (no `<svg>`), with their own
 * `<defs>`. `id` prefixes every definition, so two marks on one page never
 * share a gradient.
 */
export function markElements(id: string, ink: MarkInk = LIGHT_INK, { halo = false } = {}): string {
  const v = ORBIT_VECTOR;
  let defs =
    `<linearGradient id="${id}-orbit" x1="${v.x1}" y1="${v.y1}" x2="${v.x2}" y2="${v.y2}">` +
    `${stops(ORBIT_STOPS, ink.orbit)}</linearGradient>`;
  if (halo) defs += `<radialGradient id="${id}-halo">${stops([0, 1], [BRAND.halo, BRAND.halo], [BRAND.haloAlpha, 0])}</radialGradient>`;
  if (ink.glow) {
    defs += `<filter id="${id}-glow" x="-100%" y="-100%" width="300%" height="300%">` +
      `<feGaussianBlur stdDeviation="${BRAND.starGlowBlur}"/></filter>`;
  }
  let body = `<defs>${defs}</defs>`;
  if (halo) body += `<ellipse cx="${HALO.cx}" cy="${HALO.cy}" rx="${HALO.rx}" ry="${HALO.ry}" fill="url(#${id}-halo)"/>`;
  body += `<path d="${ARC}" fill="url(#${id}-orbit)"/>`;
  body += `<path d="${TAPER}" fill="${ink.taper}" opacity="${BRAND.taperAlpha}"/>`;
  if (ink.glow) body += `<path d="${STAR_GLOW}" fill="${ink.glow}" opacity="${BRAND.starGlowAlpha}" filter="url(#${id}-glow)"/>`;
  body += `<path d="${STAR}" fill="${ink.star}"/>`;
  if (ink.core) {
    const d = STAR_CORE.map(([x1, y1, x2, y2]) => `M${x1} ${y1}L${x2} ${y2}`).join('');
    body += `<path d="${d}" stroke="${ink.core}" stroke-width="${STAR_CORE_WIDTH}" opacity="${BRAND.starCoreAlpha}"/>`;
  }
  return body;
}

/** The tile's SVG elements on the 512 grid: the ground the icon's mark stands on. */
export function tileElements(id: string): string {
  const t = TILE;
  return `<defs><linearGradient id="${id}-tile" x1="0" y1="0" x2="1" y2="1">` +
    `${stops(TILE_STOPS, [BRAND.tileTop, BRAND.tileMid, BRAND.tileBottom])}</linearGradient></defs>` +
    `<rect x="${t.x}" y="${t.y}" width="${t.size}" height="${t.size}" rx="${t.radius}" fill="url(#${id}-tile)" ` +
    `stroke="${BRAND.tileRim}" stroke-width="${t.rim}"/>`;
}

/** The whole app icon on the 512 grid: tile, halo and mark. */
export function iconElements(id: string): string {
  return tileElements(id) + markElements(id, LIGHT_INK, { halo: true });
}

/** A viewBox that frames the mark alone, with `pad` grid pixels round it. */
export function markViewBox(pad = 0): string {
  const b = MARK_BOX;
  return `${b.x - pad} ${b.y - pad} ${b.width + 2 * pad} ${b.height + 2 * pad}`;
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
  /** Ink width in wordmark units, where the ascender is 100. */
  units: number;
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
  return { tier, scale, width: w.width * scale, height: 100 * scale, xHeight, units: w.width, d: w.d };
}

/**
 * The wordmark's ink, as a gradient across its width in wordmark units: the
 * ivory holds to `word-tint-from` of the way along, then warms to the arc's
 * light by the last letter.
 */
export function wordGradient(id: string, units: number, ink: WordInk = LIGHT_WORD): string {
  return `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="${n2(units)}" y2="0">` +
    `${stops([0, BRAND.wordTintFrom, 1], [ink.ink, ink.ink, ink.tint])}</linearGradient>`;
}

/** The wordmark's SVG elements in its own units (ascender 100), with its gradient. */
export function wordElements(id: string, tier: WordmarkTier, ink: WordInk = LIGHT_WORD): string {
  const w = WORDMARK[tier];
  return `<defs>${wordGradient(`${id}-word`, w.width, ink)}</defs><path fill="url(#${id}-word)" d="${w.d}"/>`;
}

// ---------------------------------------------------------------------------
// The lockup
// ---------------------------------------------------------------------------

export interface LockupLayout {
  width: number;
  height: number;
  /** The mark's box, in the lockup's pixels. */
  mark: Box;
  /** The word's top-left (its ascender's top) and scale from wordmark units. */
  word: { x: number; y: number; scale: number; tier: WordmarkTier; units: number };
  /** Below the baseline, for the round letters' overshoot. */
  overshoot: number;
}

/**
 * The lockup at a wordmark ascender of `ascender` px. Side by side, the mark
 * is `lockup-mark` ascenders tall and `lockup-gap` ascenders before the word,
 * centred on the word's ascender. Stacked, it is `stacked-mark` ascenders tall
 * and centred above the word, `stacked-gap` clear.
 */
export function lockupLayout(ascender: number, stacked = false): LockupLayout {
  const word = wordmarkLayout(ascender);
  const overshoot = WORDMARK[word.tier].descent * word.scale;
  if (!stacked) {
    const mh = BRAND.lockupMark * ascender;
    const mw = mh * MARK_ASPECT;
    const gap = BRAND.lockupGap * ascender;
    const top = Math.max(0, (mh - word.height) / 2);
    return {
      width: mw + gap + word.width,
      height: Math.max(mh, word.height),
      mark: { x: 0, y: 0, width: mw, height: mh },
      word: { x: mw + gap, y: top, scale: word.scale, tier: word.tier, units: word.units },
      overshoot,
    };
  }
  const mh = BRAND.stackedMark * ascender;
  const mw = mh * MARK_ASPECT;
  const gap = BRAND.stackedGap * ascender;
  const width = Math.max(mw, word.width);
  return {
    width,
    height: mh + gap + word.height,
    mark: { x: (width - mw) / 2, y: 0, width: mw, height: mh },
    word: { x: (width - word.width) / 2, y: mh + gap, scale: word.scale, tier: word.tier, units: word.units },
    overshoot,
  };
}

/** A lockup's SVG elements (no `<svg>`), in its own pixels. */
export function lockupElements(id: string, l: LockupLayout, ink: MarkInk = LIGHT_INK, word: WordInk = LIGHT_WORD): string {
  const k = l.mark.height / MARK_BOX.height;
  const mark = `<g transform="translate(${n2(l.mark.x - MARK_BOX.x * k)} ${n2(l.mark.y - MARK_BOX.y * k)}) scale(${Math.round(k * 10000) / 10000})">` +
    `${markElements(id, ink)}</g>`;
  const w = WORDMARK[l.word.tier];
  return mark +
    `<g transform="translate(${n2(l.word.x)} ${n2(l.word.y)}) scale(${l.word.scale})">` +
    `<defs>${wordGradient(`${id}-word`, w.width, word)}</defs><path fill="url(#${id}-word)" d="${w.d}"/></g>`;
}
