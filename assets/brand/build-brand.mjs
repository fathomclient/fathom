/*
 * Fathom brand build.
 *
 *   node assets/brand/build-brand.mjs
 *
 * Writes every brand asset from the one construction in
 * `desktop/src/lib/brand/orbit.ts`, with colours from the theme manifest and
 * the wordmark's outlines from `wordmark.generated.ts`:
 *
 *   assets/brand/icon.svg                the app icon master (512)
 *   assets/brand/mark*.svg               the mark without its tile
 *   assets/brand/wordmark*.svg           the wordmark, one file per optical tier
 *   assets/brand/lockup*.svg             mark and wordmark, side by side and stacked
 *   assets/brand/png/fathom-icon-*.png   every raster size
 *   crates/launcher-desktop/icons/       icon.ico and icon.png for Tauri, the
 *                                        Windows executable and the installer,
 *                                        and the installer's header and sidebar
 *                                        bitmaps
 *   .../generated/Mark.java              the mark's paths for the game
 *   .../assets/fathom-optimizations/icon the game window's icon and the mod's
 *
 * Nothing is rasterised once and shrunk. Each icon size is drawn at that size:
 * the tile's edge is set on whole pixels so it never lands between two (a
 * soft grey fringe on a taskbar), its rim is at least one whole pixel, and
 * below 48 px the arc is widened just enough to stay a line rather than a
 * smudge.
 *
 * The rasteriser below needs no image library: a scanline filler with sixteen
 * sub-rows per pixel and exact horizontal coverage, even-odd or non-zero,
 * composited in premultiplied RGBA, with the gradients and the one Gaussian
 * blur the mark uses. The PNG, ICO and BMP writers are minimal encoders of
 * their own.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

import {
  ARC,
  ARC_BOX,
  DARK_INK,
  DARK_WORD,
  GRID,
  HALO,
  LIGHT_INK,
  LIGHT_WORD,
  MARK_BOX,
  ORBIT_STOPS,
  ORBIT_VECTOR,
  STAR,
  STAR_CORE,
  STAR_CORE_WIDTH,
  STAR_GLOW,
  TAPER,
  TILE,
  TILE_STOPS,
  WORDMARK,
  iconElements,
  lockupElements,
  lockupLayout,
  markElements,
  markViewBox,
  oneInk,
  wordElements,
} from '../../desktop/src/lib/brand/orbit.ts';
import { BRAND } from '../../desktop/src/lib/brand/brand.generated.ts';

const BRAND_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(BRAND_DIR, '..', '..');
const APP_ICONS = path.join(REPO, 'crates', 'launcher-desktop', 'icons');
const GAME = path.join(REPO, 'minecraft', 'fathom-optimizations', 'src', 'main');
const GAME_GENERATED = path.join(GAME, 'java', 'net', 'fathom', 'optimizations', 'generated');
const GAME_ICONS = path.join(GAME, 'resources', 'assets', 'fathom-optimizations', 'icon');

// 30 and 36 are the taskbar's frames at 125% and 150%.
const ICO_SIZES = [16, 20, 24, 30, 32, 36, 40, 48, 64, 128, 256];
const PNG_SIZES = [...ICO_SIZES, 512];

function rgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

/** A colour along gradient stops, in sRGB as SVG interpolates it. */
function along(offsets, colours, t) {
  const c = colours.map(rgb);
  if (t <= offsets[0]) return c[0];
  for (let i = 1; i < offsets.length; i += 1) {
    if (t <= offsets[i]) {
      const f = (t - offsets[i - 1]) / Math.max(1e-6, offsets[i] - offsets[i - 1]);
      return [0, 1, 2].map((k) => c[i - 1][k] + (c[i][k] - c[i - 1][k]) * f);
    }
  }
  return c[c.length - 1];
}

// ---------------------------------------------------------------------------
// Rasteriser
// ---------------------------------------------------------------------------

const SUBROWS = 16;

/**
 * Coverage, 0–1 per pixel, of a set of polygons on a `w` x `h` grid, under
 * the even-odd rule or the non-zero rule (glyph outlines, which may overlap).
 */
function coverage(w, h, polygons, rule = 'nonzero') {
  const cov = new Float32Array(w * h);
  const edges = [];
  for (const polygon of polygons) {
    for (let i = 0; i < polygon.length; i += 1) {
      const [x1, y1] = polygon[i];
      const [x2, y2] = polygon[(i + 1) % polygon.length];
      if (y1 !== y2) edges.push([x1, y1, x2, y2]);
    }
  }
  const crossings = [];
  for (let row = 0; row < h; row += 1) {
    for (let k = 0; k < SUBROWS; k += 1) {
      const y = row + (k + 0.5) / SUBROWS;
      crossings.length = 0;
      for (const [x1, y1, x2, y2] of edges) {
        if ((y1 <= y && y < y2) || (y2 <= y && y < y1)) {
          crossings.push([x1 + ((y - y1) * (x2 - x1)) / (y2 - y1), y2 > y1 ? 1 : -1]);
        }
      }
      crossings.sort((a, b) => a[0] - b[0]);
      const spans = [];
      if (rule === 'evenodd') {
        for (let i = 0; i + 1 < crossings.length; i += 2) spans.push([crossings[i][0], crossings[i + 1][0]]);
      } else {
        let winding = 0;
        for (let i = 0; i < crossings.length; i += 1) {
          const before = winding;
          winding += crossings[i][1];
          if (before === 0 && winding !== 0) spans.push([crossings[i][0], null]);
          else if (before !== 0 && winding === 0) spans[spans.length - 1][1] = crossings[i][0];
        }
      }
      for (const [a, b] of spans) {
        const xa = Math.max(0, a);
        const xb = Math.min(w, b);
        if (xb <= xa) continue;
        const wt = 1 / SUBROWS;
        const ia = Math.floor(xa);
        const ib = Math.floor(xb);
        const base = row * w;
        if (ia === ib) {
          cov[base + ia] += (xb - xa) * wt;
        } else {
          cov[base + ia] += (ia + 1 - xa) * wt;
          for (let x = ia + 1; x < ib; x += 1) cov[base + x] += wt;
          if (ib < w) cov[base + ib] += (xb - ib) * wt;
        }
      }
    }
  }
  for (let i = 0; i < cov.length; i += 1) cov[i] = Math.min(1, cov[i]);
  return cov;
}

/**
 * Coverage widened by `radius` px: the shape stamped round a ring of offsets
 * and the most of each pixel kept. Only for the arc at small icon sizes, where
 * its thinnest stretch would otherwise be a fraction of a pixel.
 */
function widened(w, h, polygons, radius) {
  if (radius <= 0) return coverage(w, h, polygons);
  const out = coverage(w, h, polygons);
  for (let i = 0; i < 12; i += 1) {
    const a = (i / 12) * Math.PI * 2;
    const dx = Math.cos(a) * radius;
    const dy = Math.sin(a) * radius;
    const moved = coverage(w, h, polygons.map((p) => p.map(([x, y]) => [x + dx, y + dy])));
    for (let k = 0; k < out.length; k += 1) out[k] = Math.max(out[k], moved[k]);
  }
  return out;
}

/** A separable Gaussian blur of a coverage mask. */
function blur(w, h, cov, sigma) {
  if (sigma < 0.25) return cov;
  const r = Math.ceil(sigma * 3);
  const kernel = [];
  let sum = 0;
  for (let i = -r; i <= r; i += 1) {
    const v = Math.exp(-(i * i) / (2 * sigma * sigma));
    kernel.push(v);
    sum += v;
  }
  for (let i = 0; i < kernel.length; i += 1) kernel[i] /= sum;
  const pass = (src, horizontal) => {
    const dst = new Float32Array(src.length);
    for (let y = 0; y < h; y += 1) {
      for (let x = 0; x < w; x += 1) {
        let acc = 0;
        for (let i = -r; i <= r; i += 1) {
          const sx = horizontal ? x + i : x;
          const sy = horizontal ? y : y + i;
          if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
          acc += src[sy * w + sx] * kernel[i + r];
        }
        dst[y * w + x] = acc;
      }
    }
    return dst;
  };
  return pass(pass(cov, true), false);
}

/** A premultiplied RGBA float canvas. */
function canvas(w, h) {
  return { w, h, px: new Float32Array(w * h * 4) };
}

/**
 * Paint through a coverage mask, source-over. `ink` is a hex colour or a
 * function of the pixel's centre returning [r, g, b] or [r, g, b, a], 0–1.
 */
function paint(target, cov, ink, alpha = 1) {
  const t = target.px;
  const fixed = typeof ink === 'string' ? rgb(ink) : null;
  for (let i = 0; i < cov.length; i += 1) {
    let a = cov[i] * alpha;
    if (a <= 0) continue;
    const c = fixed ?? ink((i % target.w) + 0.5, Math.floor(i / target.w) + 0.5);
    if (c.length > 3) a *= c[3];
    if (a <= 0) continue;
    const o = i * 4;
    t[o] = c[0] * a + t[o] * (1 - a);
    t[o + 1] = c[1] * a + t[o + 1] * (1 - a);
    t[o + 2] = c[2] * a + t[o + 2] * (1 - a);
    t[o + 3] = a + t[o + 3] * (1 - a);
  }
}

function fill(target, polygons, ink, alpha = 1, rule = 'nonzero') {
  paint(target, coverage(target.w, target.h, polygons, rule), ink, alpha);
}

function toBytes(target) {
  const t = target.px;
  const px = Buffer.alloc(t.length);
  for (let i = 0; i < t.length; i += 4) {
    const a = t[i + 3];
    if (a <= 0) continue;
    for (let c = 0; c < 3; c += 1) px[i + c] = Math.round(Math.min(1, t[i + c] / a) * 255);
    px[i + 3] = Math.round(Math.min(1, a) * 255);
  }
  return px;
}

/** A whole-canvas mask. */
const everywhere = (target) => new Float32Array(target.w * target.h).fill(1);

// ---------------------------------------------------------------------------
// Geometry to polygons
// ---------------------------------------------------------------------------

/** An absolute M/L/Q/C/Z path through `map`, flattened into polygons. */
function pathPolygons(d, map, scale = 1) {
  const out = [];
  let current = null;
  let px = 0;
  let py = 0;
  const steps = (len) => Math.max(4, Math.min(48, Math.ceil((len * scale) / 1.2)));
  for (const [, cmd, body] of d.matchAll(/([MLQCZ])([^MLQCZ]*)/g)) {
    const n = body.trim() ? body.trim().split(/[\s,]+/).map(Number) : [];
    if (cmd === 'M') {
      current = [map(n[0], n[1])];
      out.push(current);
      [px, py] = n;
    } else if (cmd === 'L') {
      current.push(map(n[0], n[1]));
      [px, py] = n;
    } else if (cmd === 'Q') {
      const s = steps(Math.hypot(n[0] - px, n[1] - py) + Math.hypot(n[2] - n[0], n[3] - n[1]));
      for (let i = 1; i <= s; i += 1) {
        const t = i / s;
        const u = 1 - t;
        current.push(map(u * u * px + 2 * u * t * n[0] + t * t * n[2], u * u * py + 2 * u * t * n[1] + t * t * n[3]));
      }
      [px, py] = [n[2], n[3]];
    } else if (cmd === 'C') {
      for (let j = 0; j + 5 < n.length; j += 6) {
        const c = n.slice(j, j + 6);
        const s = steps(Math.hypot(c[0] - px, c[1] - py) + Math.hypot(c[2] - c[0], c[3] - c[1]) + Math.hypot(c[4] - c[2], c[5] - c[3]));
        for (let i = 1; i <= s; i += 1) {
          const t = i / s;
          const u = 1 - t;
          current.push(map(
            u * u * u * px + 3 * u * u * t * c[0] + 3 * u * t * t * c[2] + t * t * t * c[4],
            u * u * u * py + 3 * u * u * t * c[1] + 3 * u * t * t * c[3] + t * t * t * c[5],
          ));
        }
        [px, py] = [c[4], c[5]];
      }
    }
  }
  return out.filter((p) => p.length > 2);
}

/** A rounded rectangle as a polygon. */
function roundRect(x, y, w, h, r) {
  const out = [];
  const seg = Math.max(6, Math.ceil(r * 1.5));
  const corner = (cx, cy, a0) => {
    for (let i = 0; i <= seg; i += 1) {
      const a = a0 + (i / seg) * (Math.PI / 2);
      out.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]);
    }
  };
  corner(x + w - r, y + r, -Math.PI / 2);
  corner(x + w - r, y + h - r, 0);
  corner(x + r, y + h - r, Math.PI / 2);
  corner(x + r, y + r, Math.PI);
  return out;
}

/** A straight line of `width` with square ends, as a polygon. */
function bar(map, [x1, y1, x2, y2], width) {
  const len = Math.hypot(x2 - x1, y2 - y1) || 1;
  const nx = (-(y2 - y1) / len) * (width / 2);
  const ny = ((x2 - x1) / len) * (width / 2);
  return [map(x1 + nx, y1 + ny), map(x2 + nx, y2 + ny), map(x2 - nx, y2 - ny), map(x1 - nx, y1 - ny)];
}

// ---------------------------------------------------------------------------
// The mark, painted
// ---------------------------------------------------------------------------

/**
 * Paint the mark into a canvas through a 512-grid -> pixel transform: `x, y`
 * where the grid's origin lands and `k` pixels per grid pixel. `widen` is in
 * pixels, for the arc only; `star: false` leaves the arc alone.
 */
function paintMark(target, { x, y, k }, ink = LIGHT_INK, { halo = false, star = true, widen = 0, alpha = 1 } = {}) {
  const map = (gx, gy) => [x + gx * k, y + gy * k];
  const { w, h } = target;
  if (halo) {
    const [cx, cy] = map(HALO.cx, HALO.cy);
    const rx = HALO.rx * k;
    const ry = HALO.ry * k;
    const c = rgb(BRAND.halo);
    paint(target, everywhere(target), (px, py) => {
      const r = Math.hypot((px - cx) / rx, (py - cy) / ry);
      return [...c, r >= 1 ? 0 : BRAND.haloAlpha * (1 - r)];
    }, alpha);
  }

  // The arc's gradient runs along its own box, as SVG's objectBoundingBox does.
  const [bx, by] = map(ARC_BOX.x, ARC_BOX.y);
  const bw = ARC_BOX.width * k;
  const bh = ARC_BOX.height * k;
  const v = ORBIT_VECTOR;
  const dx = v.x2 - v.x1;
  const dy = v.y2 - v.y1;
  const orbit = (px, py) => {
    const u = (px - bx) / bw - v.x1;
    const vv = (py - by) / bh - v.y1;
    return along(ORBIT_STOPS, ink.orbit, Math.max(0, Math.min(1, (u * dx + vv * dy) / (dx * dx + dy * dy))));
  };
  paint(target, widened(w, h, pathPolygons(ARC, map, k), widen), orbit, alpha);
  fill(target, pathPolygons(TAPER, map, k), ink.taper, BRAND.taperAlpha * alpha);
  if (!star) return;
  if (ink.glow) {
    const glow = blur(w, h, coverage(w, h, pathPolygons(STAR_GLOW, map, k)), BRAND.starGlowBlur * k);
    paint(target, glow, ink.glow, BRAND.starGlowAlpha * alpha);
  }
  fill(target, pathPolygons(STAR, map, k), ink.star, alpha);
  if (ink.core) {
    fill(target, STAR_CORE.map((line) => bar(map, line, STAR_CORE_WIDTH)), ink.core, BRAND.starCoreAlpha * alpha);
  }
}

/** Paint the tile: its gradient, and its rim as an inner band of whole pixels. */
function paintTile(target, left, side, radius, rim) {
  const { w, h } = target;
  const outer = roundRect(left, left, side, side, radius);
  const inner = roundRect(left + rim, left + rim, side - 2 * rim, side - 2 * rim, Math.max(0, radius - rim));
  const tile = (px, py) => along(TILE_STOPS, [BRAND.tileTop, BRAND.tileMid, BRAND.tileBottom],
    Math.max(0, Math.min(1, (px - left + (py - left)) / (2 * side))));
  paint(target, coverage(w, h, [outer]), tile);
  paint(target, coverage(w, h, [outer, inner], 'evenodd'), BRAND.tileRim);
}

/**
 * The app icon at one size. The master's tile is 488 wide at 12 in, with a
 * 3 px rim centred on its edge, so its outer edge is at 10.5. Here that edge
 * is rounded to a whole pixel and the rim drawn inside it, at least one pixel
 * wide; the mark is scaled with the tile, so nothing reaches past it.
 */
function renderIcon(size) {
  const out = canvas(size, size);
  const edge = TILE.x - TILE.rim / 2;
  const left = Math.round((edge * size) / GRID);
  const side = size - 2 * left;
  const k = side / (TILE.size + TILE.rim);
  const rim = Math.max(1, Math.round(TILE.rim * k));
  paintTile(out, left, side, (TILE.radius + TILE.rim / 2) * k, rim);
  // The arc's thinnest stretch is about 9 grid px; below 48 px that is under
  // a pixel and would break up, so it is widened towards one -- a little more
  // at the smallest sizes, where the arc is most of what can be seen.
  const least = size < 24 ? 1.35 : size < 48 ? 1.1 : 0;
  const widen = Math.max(0, (least - 9 * k) / 2);
  paintMark(out, { x: left - edge * k, y: left - edge * k, k }, LIGHT_INK, { halo: true, widen });
  return toBytes(out);
}

/** The wordmark's polygons and its gradient, laid out by a lockup layout. */
function paintWord(target, layout, ox, oy, ink = LIGHT_WORD) {
  const s = layout.word.scale;
  const wx = ox + layout.word.x;
  const wy = oy + layout.word.y;
  const map = (x, y) => [wx + x * s, wy + y * s];
  const width = layout.word.units * s;
  const word = (px) => along([0, BRAND.wordTintFrom, 1], [ink.ink, ink.ink, ink.tint],
    Math.max(0, Math.min(1, (px - wx) / width)));
  fill(target, pathPolygons(WORDMARK[layout.word.tier].d, map, s), word);
}

/** Paint a lockup with its top-left at `x, y`. */
function paintLockup(target, layout, x, y, ink = LIGHT_INK, word = LIGHT_WORD) {
  const k = layout.mark.height / MARK_BOX.height;
  paintMark(target, { x: x + layout.mark.x - MARK_BOX.x * k, y: y + layout.mark.y - MARK_BOX.y * k, k }, ink);
  paintWord(target, layout, x, y, word);
}

/**
 * The installer's welcome and finish pages: the sidebar. The tile's ground,
 * the lockup at the top, and the arc again, large and faint, sweeping up out
 * of the lower edge through the halo. A one-pixel rim where it meets
 * the page.
 */
function renderSidebar() {
  const w = 164;
  const h = 314;
  const out = canvas(w, h);
  paint(out, everywhere(out), (px, py) => along(TILE_STOPS, [BRAND.tileTop, BRAND.tileMid, BRAND.tileBottom],
    Math.max(0, Math.min(1, (px * 0.25 + py) / (w * 0.25 + h)))));
  paintMark(out, { x: -95, y: 40, k: 0.75 }, LIGHT_INK, { halo: true, star: false, alpha: 0.4 });
  paintLockup(out, lockupLayout(15), 20, 26);
  fill(out, [[[w - 1, 0], [w, 0], [w, h], [w - 1, h]]], BRAND.tileRim, 0.9);
  return { w, h, px: toBytes(out) };
}

/** The installer's page header: a light band, the app icon and the word, right-aligned. */
function renderHeader() {
  const w = 150;
  const h = 57;
  const out = canvas(w, h);
  fill(out, [[[0, 0], [w, 0], [w, h], [0, h]]], '#ffffff');
  const word = lockupLayout(13);
  const icon = 32;
  const gap = 8;
  const total = icon + gap + (word.width - word.word.x);
  const left = Math.round(w - 12 - total);
  const top = Math.round((h - icon) / 2);
  // The icon itself, tile and all: on white, the mark alone would need its
  // light-ground inks; the tile carries its own ground.
  const tile = renderIcon(icon);
  for (let y = 0; y < icon; y += 1) {
    for (let x = 0; x < icon; x += 1) {
      const s = (y * icon + x) * 4;
      const a = tile[s + 3] / 255;
      if (!a) continue;
      const o = ((top + y) * w + left + x) * 4;
      for (let c = 0; c < 3; c += 1) out.px[o + c] = (tile[s + c] / 255) * a + out.px[o + c] * (1 - a);
      out.px[o + 3] = a + out.px[o + 3] * (1 - a);
    }
  }
  const wordLeft = left + icon + gap - word.word.x;
  const wordTop = Math.round(h / 2 - (word.word.y + 50 * word.word.scale));
  paintWord(out, word, wordLeft, wordTop, DARK_WORD);
  return { w, h, px: toBytes(out) };
}

// ---------------------------------------------------------------------------
// SVG masters
// ---------------------------------------------------------------------------

const n2 = (v) => String(Math.round(v * 100) / 100);

const svg = (w, h, body, viewBox = `0 0 ${w} ${h}`) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="${viewBox}" role="img" aria-label="Fathom">${body}</svg>\n`;

/** The mark without its tile, 256 tall, with room round it for the glow. */
function markSvg(ink) {
  const pad = 12;
  const [, , vw, vh] = markViewBox(pad).split(' ').map(Number);
  const k = 256 / vh;
  return svg(Math.round(vw * k), 256, markElements('fathom', ink), markViewBox(pad));
}

/** One optical tier of the wordmark at a 100px ascender, with room for the overshoot. */
function wordmarkSvg(tier, ink = LIGHT_WORD) {
  const w = WORDMARK[tier];
  const h = Math.ceil(100 + w.descent);
  return svg(Math.ceil(w.width), h, wordElements('fathom', tier, ink));
}

function lockupSvg(ascender, stacked, ink = LIGHT_INK, word = LIGHT_WORD) {
  const l = lockupLayout(ascender, stacked);
  return svg(Math.ceil(l.width), Math.ceil(l.height + l.overshoot), lockupElements('fathom', l, ink, word));
}

// ---------------------------------------------------------------------------
// The game's copy of the geometry
// ---------------------------------------------------------------------------

function javaString(d) {
  const parts = d.match(/M[^M]*/g);
  return parts.map((p, i) => `            "${p}"${i < parts.length - 1 ? ' +' : ';'}`).join('\n');
}

function markJava() {
  const core = STAR_CORE.map(([x1, y1, x2, y2]) => `M${x1} ${y1}L${x2} ${y2}`).join('');
  const f = (v) => `${n2(v)}f`;
  return [
    'package net.fathom.optimizations.generated;',
    '',
    '/**',
    ' * The Orbit mark: its paths on the icon\'s 512 grid, y down. Generated by',
    ' * assets/brand/build-brand.mjs from desktop/src/lib/brand/orbit.ts; the',
    ' * launcher draws the same paths. Inks are FathomTheme.Tokens.BRAND_*. Do not edit.',
    ' */',
    'public final class Mark {',
    '    private Mark() {}',
    '',
    '    /** The arc, narrowing to its leading point; filled along its own box. */',
    '    public static final String ARC =',
    javaString(ARC),
    '    public static final String TAPER =',
    javaString(TAPER),
    '    /** Filled, then blurred by BRAND_STAR_GLOW_BLUR grid px. */',
    '    public static final String STAR_GLOW =',
    javaString(STAR_GLOW),
    '    public static final String STAR =',
    javaString(STAR),
    '    /** Two hairlines, stroked STAR_CORE_WIDTH wide with square ends. */',
    `    public static final String STAR_CORE = "${core}";`,
    `    public static final float STAR_CORE_WIDTH = ${f(STAR_CORE_WIDTH)};`,
    '',
    '    /** The arc\'s box, which its gradient is laid along. */',
    `    public static final float ARC_X = ${f(ARC_BOX.x)};`,
    `    public static final float ARC_Y = ${f(ARC_BOX.y)};`,
    `    public static final float ARC_WIDTH = ${f(ARC_BOX.width)};`,
    `    public static final float ARC_HEIGHT = ${f(ARC_BOX.height)};`,
    `    /** The gradient's vector in the arc's box, and its stops. */`,
    `    public static final float ORBIT_X1 = ${f(ORBIT_VECTOR.x1)};`,
    `    public static final float ORBIT_Y1 = ${f(ORBIT_VECTOR.y1)};`,
    `    public static final float ORBIT_X2 = ${f(ORBIT_VECTOR.x2)};`,
    `    public static final float ORBIT_Y2 = ${f(ORBIT_VECTOR.y2)};`,
    `    public static final float[] ORBIT_STOPS = {${ORBIT_STOPS.map(f).join(', ')}};`,
    '',
    '    /** The halo behind the mark: an ellipse lit from its centre. */',
    `    public static final float HALO_CX = ${f(HALO.cx)};`,
    `    public static final float HALO_CY = ${f(HALO.cy)};`,
    `    public static final float HALO_RX = ${f(HALO.rx)};`,
    `    public static final float HALO_RY = ${f(HALO.ry)};`,
    '',
    '    /** The mark\'s box without its tile; the glow spills past it. */',
    `    public static final float BOX_X = ${f(MARK_BOX.x)};`,
    `    public static final float BOX_Y = ${f(MARK_BOX.y)};`,
    `    public static final float BOX_WIDTH = ${f(MARK_BOX.width)};`,
    `    public static final float BOX_HEIGHT = ${f(MARK_BOX.height)};`,
    '}',
    '',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// PNG, ICO and BMP
// ---------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i += 1) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function png(size, px) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y += 1) {
    raw[y * (size * 4 + 1)] = 0;
    px.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* BMP entries up to 128px, PNG for 256: PNG-in-ICO is legal since Vista, but
   some shell paths and installer toolchains still only decode BMP entries, and
   those are the sizes they reach for. The AND mask mirrors transparency for
   older paths that ignore the alpha channel. */
function bmpEntry(size, px) {
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0);
  header.writeInt32LE(size, 4);
  header.writeInt32LE(size * 2, 8);
  header.writeUInt16LE(1, 12);
  header.writeUInt16LE(32, 14);
  const xor = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y += 1) {
    const src = (size - 1 - y) * size * 4;
    for (let x = 0; x < size; x += 1) {
      const s = src + x * 4;
      const d = (y * size + x) * 4;
      xor[d] = px[s + 2];
      xor[d + 1] = px[s + 1];
      xor[d + 2] = px[s];
      xor[d + 3] = px[s + 3];
    }
  }
  const maskRow = Math.ceil(size / 32) * 4;
  const mask = Buffer.alloc(maskRow * size);
  for (let y = 0; y < size; y += 1) {
    const srcY = size - 1 - y;
    for (let x = 0; x < size; x += 1) {
      if (px[(srcY * size + x) * 4 + 3] === 0) mask[y * maskRow + (x >> 3)] |= 0x80 >> (x & 7);
    }
  }
  header.writeUInt32LE(xor.length + mask.length, 20);
  return Buffer.concat([header, xor, mask]);
}

function ico(rasters) {
  const images = ICO_SIZES.map((size) => ({
    size,
    data: size > 128 ? png(size, rasters.get(size)) : bmpEntry(size, rasters.get(size)),
  }));
  const header = Buffer.alloc(6);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  const dir = Buffer.alloc(images.length * 16);
  let offset = 6 + images.length * 16;
  images.forEach((img, i) => {
    const o = i * 16;
    dir[o] = img.size >= 256 ? 0 : img.size;
    dir[o + 1] = img.size >= 256 ? 0 : img.size;
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(img.data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += img.data.length;
  });
  return Buffer.concat([header, dir, ...images.map((img) => img.data)]);
}

/** A 24-bit bottom-up BMP, the format NSIS's Modern UI reads for its bitmaps. */
function bmp24({ w, h, px }) {
  const row = Math.ceil((w * 3) / 4) * 4;
  const size = 54 + row * h;
  const b = Buffer.alloc(size);
  b.write('BM', 0, 'ascii');
  b.writeUInt32LE(size, 2);
  b.writeUInt32LE(54, 10);
  b.writeUInt32LE(40, 14);
  b.writeInt32LE(w, 18);
  b.writeInt32LE(h, 22);
  b.writeUInt16LE(1, 26);
  b.writeUInt16LE(24, 28);
  b.writeUInt32LE(row * h, 34);
  b.writeInt32LE(2835, 38);
  b.writeInt32LE(2835, 42);
  for (let y = 0; y < h; y += 1) {
    let o = 54 + y * row;
    const src = (h - 1 - y) * w * 4;
    for (let x = 0; x < w; x += 1) {
      const i = src + x * 4;
      b[o++] = px[i + 2];
      b[o++] = px[i + 1];
      b[o++] = px[i];
    }
  }
  return b;
}


// ---------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------

function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
  console.log(`  ${path.relative(REPO, file)}  (${data.length} bytes)`);
}

const comment = '<!-- Generated by assets/brand/build-brand.mjs. Do not hand-edit. -->\n';

// The pixel masters of the retired mark, if this checkout still has them.
for (const size of [16, 20, 24, 30, 32, 36, 40, 48, 64]) {
  fs.rmSync(path.join(BRAND_DIR, `icon-${size}.svg`), { force: true });
}

write(path.join(BRAND_DIR, 'icon.svg'), comment + svg(GRID, GRID, iconElements('fathom')));

write(path.join(BRAND_DIR, 'mark.svg'), comment + markSvg(LIGHT_INK));
write(path.join(BRAND_DIR, 'mark-reversed.svg'), comment + markSvg(DARK_INK));
write(path.join(BRAND_DIR, 'mark-one-colour.svg'), comment + markSvg(oneInk(BRAND.orbitLight)));

write(path.join(BRAND_DIR, 'wordmark.svg'), comment + wordmarkSvg('display'));
write(path.join(BRAND_DIR, 'wordmark-text.svg'), comment + wordmarkSvg('text'));
write(path.join(BRAND_DIR, 'wordmark-small.svg'), comment + wordmarkSvg('small'));
write(path.join(BRAND_DIR, 'wordmark-reversed.svg'), comment + wordmarkSvg('display', DARK_WORD));

write(path.join(BRAND_DIR, 'lockup.svg'), comment + lockupSvg(64, false));
write(path.join(BRAND_DIR, 'lockup-reversed.svg'), comment + lockupSvg(64, false, DARK_INK, DARK_WORD));
write(path.join(BRAND_DIR, 'lockup-stacked.svg'), comment + lockupSvg(40, true));
write(path.join(BRAND_DIR, 'lockup-stacked-reversed.svg'), comment + lockupSvg(40, true, DARK_INK, DARK_WORD));

const rasters = new Map();
for (const size of PNG_SIZES) {
  const px = renderIcon(size);
  rasters.set(size, px);
  write(path.join(BRAND_DIR, 'png', `fathom-icon-${size}.png`), png(size, px));
}
write(path.join(APP_ICONS, 'icon.ico'), ico(rasters));
write(path.join(APP_ICONS, 'icon.png'), png(512, rasters.get(512)));
write(path.join(APP_ICONS, 'nsis-sidebar.bmp'), bmp24(renderSidebar()));
write(path.join(APP_ICONS, 'nsis-header.bmp'), bmp24(renderHeader()));
write(path.join(GAME_GENERATED, 'Mark.java'), markJava());
// The game window's icon (LWJGL takes 16 and 32) and the mod's own, 128.
for (const size of [16, 32, 128]) {
  write(path.join(GAME_ICONS, `fathom-${size}.png`), png(size, rasters.get(size)));
}
