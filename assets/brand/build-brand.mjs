/*
 * Fathom brand build.
 *
 *   node assets/brand/build-brand.mjs
 *
 * Writes every brand asset from the one construction in
 * `desktop/src/lib/brand/surface.ts`, with colours from the theme manifest:
 *
 *   assets/brand/wordmark*.svg          the Surface wordmark masters
 *   assets/brand/ring.svg               the offset ring, untiled (hero use)
 *   assets/brand/icon.svg               the app icon master (large sizes)
 *   assets/brand/icon-{16..64}.svg      the pixel masters for small sizes
 *   assets/brand/sounding-line*.svg     the compact progress / tiny mark
 *   assets/brand/png/fathom-icon-*.png  every raster size
 *   crates/launcher-desktop/icons/      icon.ico and icon.png for Tauri, the
 *                                       Windows executable and the installer
 *
 * Nothing is rasterised once and shrunk. Each size is rendered from vector
 * geometry that `ringGeometry` has already solved in whole pixels for that
 * size, so a 16px icon is drawn as a 16px icon: the counter still drops a full
 * pixel and the surface still falls on a pixel row.
 *
 * The rasteriser below needs no image library: an even-odd scanline filler
 * with sixteen sub-rows per pixel and exact horizontal coverage, composited in
 * premultiplied RGBA. The PNG and ICO writers are the same minimal encoders the
 * previous icon build used.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

import {
  DARK_INK,
  ringGeometry,
  ringPath,
  ringPolygons,
  soundingGeometry,
  soundingPaths,
  tilePath,
  tilePolygon,
  wordmarkSvg,
} from '../../desktop/src/lib/brand/surface.ts';
import { BRAND } from '../../desktop/src/lib/brand/brand.generated.ts';

const BRAND_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(BRAND_DIR, '..', '..');
const APP_ICONS = path.join(REPO, 'crates', 'launcher-desktop', 'icons');

const PNG_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512];
const ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256];
const SVG_MASTERS = [16, 24, 32, 48, 64];

function rgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// ---------------------------------------------------------------------------
// Rasteriser
// ---------------------------------------------------------------------------

const SUBROWS = 16;

/**
 * Coverage, 0–1 per pixel, of a set of polygons under the even-odd rule.
 * `dx`/`dy` move the geometry; `top`/`bottom` restrict it to a band of pixel
 * rows, which is how a shape is split at the surface without a seam.
 */
function coverage(size, polygons, { dx = 0, dy = 0, top = -Infinity, bottom = Infinity } = {}) {
  const cov = new Float32Array(size * size);
  const edges = [];
  for (const polygon of polygons) {
    for (let i = 0; i < polygon.length; i += 1) {
      const [x1, y1] = polygon[i];
      const [x2, y2] = polygon[(i + 1) % polygon.length];
      if (y1 !== y2) edges.push([x1 + dx, y1 + dy, x2 + dx, y2 + dy]);
    }
  }
  const crossings = [];
  for (let row = 0; row < size; row += 1) {
    for (let k = 0; k < SUBROWS; k += 1) {
      const y = row + (k + 0.5) / SUBROWS;
      if (y < top || y >= bottom) continue;
      crossings.length = 0;
      for (const [x1, y1, x2, y2] of edges) {
        if ((y1 <= y && y < y2) || (y2 <= y && y < y1)) {
          crossings.push(x1 + ((y - y1) * (x2 - x1)) / (y2 - y1));
        }
      }
      crossings.sort((a, b) => a - b);
      for (let i = 0; i + 1 < crossings.length; i += 2) {
        const xa = Math.max(0, crossings[i]);
        const xb = Math.min(size, crossings[i + 1]);
        if (xb <= xa) continue;
        const w = 1 / SUBROWS;
        const ia = Math.floor(xa);
        const ib = Math.floor(xb);
        const base = row * size;
        if (ia === ib) {
          cov[base + ia] += (xb - xa) * w;
        } else {
          cov[base + ia] += (ia + 1 - xa) * w;
          for (let x = ia + 1; x < ib; x += 1) cov[base + x] += w;
          if (ib < size) cov[base + ib] += (xb - ib) * w;
        }
      }
    }
  }
  return cov;
}

/** A premultiplied RGBA float canvas. */
function canvas(size) {
  return new Float32Array(size * size * 4);
}

/** Paint a colour through a coverage mask, source-over. */
function paint(target, cov, hex, alpha = 1) {
  const [r, g, b] = rgb(hex).map((c) => c / 255);
  for (let i = 0; i < cov.length; i += 1) {
    const a = Math.min(1, cov[i]) * alpha;
    if (a <= 0) continue;
    const o = i * 4;
    target[o] = r * a + target[o] * (1 - a);
    target[o + 1] = g * a + target[o + 1] * (1 - a);
    target[o + 2] = b * a + target[o + 2] * (1 - a);
    target[o + 3] = a + target[o + 3] * (1 - a);
  }
}

/** Composite a whole group at one opacity, the way SVG group opacity does. */
function composite(target, group, opacity) {
  for (let i = 0; i < group.length; i += 4) {
    const a = group[i + 3] * opacity;
    if (a <= 0) continue;
    for (let c = 0; c < 3; c += 1) target[i + c] = group[i + c] * opacity + target[i + c] * (1 - a);
    target[i + 3] = a + target[i + 3] * (1 - a);
  }
}

function toBytes(target) {
  const px = Buffer.alloc(target.length);
  for (let i = 0; i < target.length; i += 4) {
    const a = target[i + 3];
    if (a <= 0) continue;
    for (let c = 0; c < 3; c += 1) px[i + c] = Math.round(Math.min(1, target[i + c] / a) * 255);
    px[i + 3] = Math.round(Math.min(1, a) * 255);
  }
  return px;
}

/** The app icon at one size: the deep tile, and the ring at that size's tier. */
function renderIcon(size) {
  const g = ringGeometry(size);
  const out = canvas(size);
  paint(out, coverage(size, [tilePolygon(size)]), BRAND.tile);
  const ring = ringPolygons(g);
  if (g.tier === 'one') {
    paint(out, coverage(size, ring), BRAND.air, BRAND.airOpacity);
    return toBytes(out);
  }
  if (g.tier === 'full') {
    const air = canvas(size);
    paint(air, coverage(size, ring, { dx: g.edge, dy: g.edge, bottom: g.surfaceY }), BRAND.edge);
    paint(air, coverage(size, ring, { bottom: g.surfaceY }), BRAND.air);
    composite(out, air, BRAND.airOpacity);
  } else {
    paint(out, coverage(size, ring, { bottom: g.surfaceY }), BRAND.air, BRAND.airOpacity);
  }
  paint(out, coverage(size, ring, { top: g.surfaceY }), BRAND.beneath, BRAND.beneathOpacity);
  return toBytes(out);
}

// ---------------------------------------------------------------------------
// SVG masters
// ---------------------------------------------------------------------------

function ringLayers(g, id) {
  const d = ringPath(g);
  if (g.tier === 'one') {
    return `<path fill="${BRAND.air}" fill-opacity="${BRAND.airOpacity}" fill-rule="evenodd" d="${d}"/>`;
  }
  const clips =
    `<defs><clipPath id="${id}-air"><rect x="0" y="0" width="${g.size}" height="${g.surfaceY}"/></clipPath>` +
    `<clipPath id="${id}-beneath"><rect x="0" y="${g.surfaceY}" width="${g.size}" height="${g.size - g.surfaceY}"/></clipPath></defs>`;
  const edge = g.edge
    ? `<g clip-path="url(#${id}-air)"><path fill="${BRAND.edge}" fill-rule="evenodd" transform="translate(${g.edge} ${g.edge})" d="${d}"/></g>`
    : '';
  return (
    clips +
    `<g opacity="${BRAND.airOpacity}">${edge}<g clip-path="url(#${id}-air)"><path fill="${BRAND.air}" fill-rule="evenodd" d="${d}"/></g></g>` +
    `<g opacity="${BRAND.beneathOpacity}" clip-path="url(#${id}-beneath)"><path fill="${BRAND.beneath}" fill-rule="evenodd" d="${d}"/></g>`
  );
}

function iconSvg(size) {
  const g = ringGeometry(size);
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" role="img" aria-label="Fathom">` +
    `<path fill="${BRAND.tile}" d="${tilePath(size)}"/>` +
    ringLayers(g, 'ring') +
    '</svg>'
  );
}

/** The ring without its tile, for the launcher's hero use over the photograph. */
function ringSvg(size) {
  const g = ringGeometry(size, size);
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" role="img" aria-label="Fathom">` +
    ringLayers(g, 'ring') +
    '</svg>'
  );
}

function soundingSvg(height, tiled) {
  const g = soundingGeometry(height);
  const { air, beneath } = soundingPaths(g);
  const pad = tiled ? Math.round(height * 0.5) : 0;
  const w = tiled ? height + 2 * pad : g.width;
  const h = tiled ? height + 2 * pad : g.height;
  const x = tiled ? Math.round((w - g.width) / 2) : 0;
  const y = tiled ? pad : 0;
  const tile = tiled ? `<path fill="${BRAND.tile}" d="${tilePath(w)}"/>` : '';
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="Fathom">` +
    tile +
    `<g transform="translate(${x} ${y})">` +
    `<path fill="${BRAND.air}" fill-opacity="${BRAND.airOpacity}" d="${air}"/>` +
    `<path fill="${BRAND.beneath}" fill-opacity="${BRAND.beneathOpacity}" d="${beneath}"/>` +
    '</g></svg>'
  );
}

// ---------------------------------------------------------------------------
// PNG and ICO
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

// ---------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------

function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
  console.log(`  ${path.relative(REPO, file)}  (${data.length} bytes)`);
}

const comment = '<!-- Generated by assets/brand/build-brand.mjs. Do not hand-edit. -->\n';

// The wordmark: the full treatment at a 100px cap, the reduced tier at a 32px
// cap, plain for small use, and the deep-brown variant for bright crops.
write(path.join(BRAND_DIR, 'wordmark.svg'), comment + wordmarkSvg(100));
write(path.join(BRAND_DIR, 'wordmark-reduced.svg'), comment + wordmarkSvg(32));
write(path.join(BRAND_DIR, 'wordmark-plain.svg'), comment + wordmarkSvg(16));
write(path.join(BRAND_DIR, 'wordmark-dark.svg'), comment + wordmarkSvg(100, DARK_INK));

write(path.join(BRAND_DIR, 'ring.svg'), comment + ringSvg(256));
write(path.join(BRAND_DIR, 'icon.svg'), comment + iconSvg(512));
for (const size of SVG_MASTERS) write(path.join(BRAND_DIR, `icon-${size}.svg`), comment + iconSvg(size));

write(path.join(BRAND_DIR, 'sounding-line.svg'), comment + soundingSvg(48, false));
write(path.join(BRAND_DIR, 'sounding-line-tile.svg'), comment + soundingSvg(32, true));

const rasters = new Map();
for (const size of PNG_SIZES) {
  const px = renderIcon(size);
  rasters.set(size, px);
  write(path.join(BRAND_DIR, 'png', `fathom-icon-${size}.png`), png(size, px));
}
write(path.join(APP_ICONS, 'icon.ico'), ico(rasters));
write(path.join(APP_ICONS, 'icon.png'), png(512, rasters.get(512)));
