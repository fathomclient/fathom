/*
 * Fathom brand build.
 *
 *   node assets/brand/build-brand.mjs
 *
 * Writes every brand asset from the one construction in
 * `desktop/src/lib/brand/aperture.ts`, with colours and numbers from the theme
 * manifest and the wordmark's outlines from `wordmark.generated.ts`:
 *
 *   assets/brand/icon.svg                the app icon master (512)
 *   assets/brand/icon-{16..64}.svg       the pixel masters for small sizes
 *   assets/brand/mark*.svg               the mark without its tile
 *   assets/brand/wordmark*.svg           the wordmark, one file per optical tier
 *   assets/brand/lockup*.svg             mark and wordmark, side by side and stacked
 *   assets/brand/png/fathom-icon-*.png   every raster size
 *   crates/launcher-desktop/icons/       icon.ico and icon.png for Tauri, the
 *                                        Windows executable and the installer,
 *                                        and the installer's header and sidebar
 *                                        bitmaps
 *
 * Nothing is rasterised once and shrunk. Each icon size is rendered from
 * geometry that `iconMark` has already solved in whole pixels for that size,
 * so a 16px icon is drawn as a 16px icon.
 *
 * The rasteriser below needs no image library: a scanline filler with sixteen
 * sub-rows per pixel and exact horizontal coverage, even-odd or non-zero,
 * composited in premultiplied RGBA. The PNG, ICO and BMP writers are minimal
 * encoders of their own.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

import {
  DARK_INK,
  LIGHT_INK,
  frameOutlines,
  iconMark,
  lockupElements,
  lockupLayout,
  markElements,
  markGeometry,
  outlinePolygon,
  planeOutline,
  WORDMARK,
} from '../../desktop/src/lib/brand/aperture.ts';
import { BRAND } from '../../desktop/src/lib/brand/brand.generated.ts';

const BRAND_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(BRAND_DIR, '..', '..');
const APP_ICONS = path.join(REPO, 'crates', 'launcher-desktop', 'icons');
const THEME = JSON.parse(fs.readFileSync(path.join(REPO, 'assets', 'theme', 'fathom-theme.json'), 'utf8'));
const INK_1 = THEME.graphite.color['ink-1'];

// 30 and 36 are the taskbar's frames at 125% and 150%.
const ICO_SIZES = [16, 20, 24, 30, 32, 36, 40, 48, 64, 128, 256];
const PNG_SIZES = [...ICO_SIZES, 512];
const SVG_MASTERS = [16, 20, 24, 30, 32, 36, 40, 48, 64];

function rgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// ---------------------------------------------------------------------------
// Rasteriser
// ---------------------------------------------------------------------------

const SUBROWS = 16;

/**
 * Coverage, 0–1 per pixel, of a set of polygons on a `w` x `h` grid, under
 * the even-odd rule (the frame, whose whole form is two outlines) or the
 * non-zero rule (glyph outlines, which may overlap).
 */
function coverage(w, h, polygons, rule = 'evenodd') {
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
  return cov;
}

/** A premultiplied RGBA float canvas. */
function canvas(w, h) {
  return { w, h, px: new Float32Array(w * h * 4) };
}

/** Paint a colour through a coverage mask, source-over. */
function paint(target, cov, hex, alpha = 1) {
  const [r, g, b] = rgb(hex).map((c) => c / 255);
  const t = target.px;
  for (let i = 0; i < cov.length; i += 1) {
    const a = Math.min(1, cov[i]) * alpha;
    if (a <= 0) continue;
    const o = i * 4;
    t[o] = r * a + t[o] * (1 - a);
    t[o + 1] = g * a + t[o + 1] * (1 - a);
    t[o + 2] = b * a + t[o + 2] * (1 - a);
    t[o + 3] = a + t[o + 3] * (1 - a);
  }
}

function fill(target, polygons, hex, alpha = 1, rule = 'evenodd') {
  paint(target, coverage(target.w, target.h, polygons, rule), hex, alpha);
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

// ---------------------------------------------------------------------------
// Geometry to polygons
// ---------------------------------------------------------------------------

const arcSegments = (o) => Math.max(4, Math.ceil(Math.max(0, ...o.radii) * 1.5));
const polygons = (outlines) => outlines.map((o) => outlinePolygon(o, arcSegments(o)));

/** An absolute M/L/Q/C/Z path, scaled and moved, flattened into polygons. */
function pathPolygons(d, { x = 0, y = 0, scale = 1 } = {}) {
  const out = [];
  let current = null;
  let px = 0;
  let py = 0;
  const at = (ax, ay) => [x + ax * scale, y + ay * scale];
  const steps = (len) => Math.max(3, Math.min(24, Math.ceil((len * scale) / 1.5)));
  for (const [, cmd, body] of d.matchAll(/([MLQCZ])([^MLQCZ]*)/g)) {
    const n = body.trim() ? body.trim().split(/[\s,]+/).map(Number) : [];
    if (cmd === 'M') {
      current = [at(n[0], n[1])];
      out.push(current);
      [px, py] = n;
    } else if (cmd === 'L') {
      current.push(at(n[0], n[1]));
      [px, py] = n;
    } else if (cmd === 'Q') {
      const s = steps(Math.hypot(n[0] - px, n[1] - py) + Math.hypot(n[2] - n[0], n[3] - n[1]));
      for (let i = 1; i <= s; i += 1) {
        const t = i / s;
        const u = 1 - t;
        current.push(at(u * u * px + 2 * u * t * n[0] + t * t * n[2], u * u * py + 2 * u * t * n[1] + t * t * n[3]));
      }
      [px, py] = [n[2], n[3]];
    } else if (cmd === 'C') {
      const s = steps(Math.hypot(n[0] - px, n[1] - py) + Math.hypot(n[2] - n[0], n[3] - n[1]) + Math.hypot(n[4] - n[2], n[5] - n[3]));
      for (let i = 1; i <= s; i += 1) {
        const t = i / s;
        const u = 1 - t;
        current.push(at(
          u * u * u * px + 3 * u * u * t * n[0] + 3 * u * t * t * n[2] + t * t * t * n[4],
          u * u * u * py + 3 * u * u * t * n[1] + 3 * u * t * t * n[3] + t * t * t * n[5],
        ));
      }
      [px, py] = [n[4], n[5]];
    }
  }
  return out.filter((p) => p.length > 2);
}

/** Paint the mark (frame and plane) into a canvas. */
function paintMark(target, g, ink = LIGHT_INK, alpha = 1) {
  fill(target, polygons(frameOutlines(g)), ink.frame, alpha);
  fill(target, polygons([planeOutline(g)]), ink.plane, alpha);
}

/** Paint a lockup with its top-left at `x, y`. */
function paintLockup(target, layout, x, y, ink = LIGHT_INK) {
  const g = markGeometry(layout.mark.unit, x + layout.mark.x, y + layout.mark.y);
  paintMark(target, g, ink);
  fill(target, pathPolygons(layout.word.d, { x: x + layout.word.x, y: y + layout.word.y, scale: layout.word.scale }),
    ink.frame, 1, 'nonzero');
}

/**
 * The app icon at one size: the mark at that size's tier, frame and plane
 * only, on transparency. It used to sit on a deep tile, which on a dark
 * taskbar read as a grey outline round the frame and a grey square round the
 * plane.
 */
function renderIcon(size) {
  const out = canvas(size, size);
  paintMark(out, iconMark(size));
  return toBytes(out);
}

/**
 * The installer's welcome and finish pages: the sidebar. Ink ground, the
 * lockup at the top, and the frame again, large and at six per cent, running
 * off the lower edge. A one-pixel rim where it meets the page.
 */
function renderSidebar() {
  const w = 164;
  const h = 314;
  const out = canvas(w, h);
  fill(out, [[[0, 0], [w, 0], [w, h], [0, h]]], INK_1);
  const big = markGeometry(26, 44, 176);
  fill(out, polygons(frameOutlines(big)), BRAND.frameInk, 0.06);
  fill(out, polygons([planeOutline(big)]), BRAND.frameInk, 0.04);
  paintLockup(out, lockupLayout(3), 20, 26);
  fill(out, [[[w - 1, 0], [w, 0], [w, h], [w - 1, h]]], '#ffffff', 0.08);
  return { w, h, px: toBytes(out) };
}

/** The installer's page header: a light band, so the reversed lockup, right-aligned. */
function renderHeader() {
  const w = 150;
  const h = 57;
  const out = canvas(w, h);
  fill(out, [[[0, 0], [w, 0], [w, h], [0, h]]], '#ffffff');
  const layout = lockupLayout(3);
  paintLockup(out, layout, Math.round(w - 14 - layout.width), Math.floor((h - layout.height) / 2), DARK_INK);
  return { w, h, px: toBytes(out) };
}

// ---------------------------------------------------------------------------
// SVG masters
// ---------------------------------------------------------------------------

const svg = (w, h, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="Fathom">${body}</svg>\n`;

function iconSvg(size) {
  return svg(size, size, markElements(iconMark(size)));
}

/** The mark without its tile, full tier, one unit = 8. */
function markSvg(ink) {
  const g = markGeometry(8);
  return svg(g.size, g.size, markElements(g, ink));
}

/** One optical tier of the wordmark at a 100px ascender, with room for the overshoot. */
function wordmarkSvg(tier, ink = LIGHT_INK) {
  const w = WORDMARK[tier];
  const h = Math.ceil(100 + w.descent);
  return svg(Math.ceil(w.width), h, `<path fill="${ink.frame}" d="${w.d}"/>`);
}

function lockupSvg(unit, stacked, ink = LIGHT_INK) {
  const l = lockupLayout(unit, stacked);
  return svg(Math.ceil(l.width), Math.ceil(l.height + l.overshoot), lockupElements(l, ink));
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

write(path.join(BRAND_DIR, 'icon.svg'), comment + iconSvg(512));
for (const size of SVG_MASTERS) write(path.join(BRAND_DIR, `icon-${size}.svg`), comment + iconSvg(size));

write(path.join(BRAND_DIR, 'mark.svg'), comment + markSvg(LIGHT_INK));
write(path.join(BRAND_DIR, 'mark-reversed.svg'), comment + markSvg(DARK_INK));
write(path.join(BRAND_DIR, 'mark-one-colour.svg'), comment + markSvg({ frame: BRAND.frameInk, plane: BRAND.frameInk }));

write(path.join(BRAND_DIR, 'wordmark.svg'), comment + wordmarkSvg('display'));
write(path.join(BRAND_DIR, 'wordmark-text.svg'), comment + wordmarkSvg('text'));
write(path.join(BRAND_DIR, 'wordmark-small.svg'), comment + wordmarkSvg('small'));
write(path.join(BRAND_DIR, 'wordmark-reversed.svg'), comment + wordmarkSvg('display', DARK_INK));

write(path.join(BRAND_DIR, 'lockup.svg'), comment + lockupSvg(16, false));
write(path.join(BRAND_DIR, 'lockup-reversed.svg'), comment + lockupSvg(16, false, DARK_INK));
write(path.join(BRAND_DIR, 'lockup-stacked.svg'), comment + lockupSvg(8, true));
write(path.join(BRAND_DIR, 'lockup-stacked-reversed.svg'), comment + lockupSvg(8, true, DARK_INK));

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
