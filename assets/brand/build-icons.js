/*
 * Fathom icon build.
 *
 * Regenerates every raster and vector icon from one geometric definition:
 * concept 06, "Aperture" -- a square frame opened at its bottom-right corner
 * with a smaller plane suspended below and behind it.
 *
 *   node assets/brand/build-icons.js
 *
 * The mark is drawn on a 64-unit grid. That grid divides exactly at 32, 64,
 * 128, 256 and 512, so those sizes are the master scaled and land on whole
 * pixels on their own. 16, 20, 24, 40 and 48 do not divide cleanly, so each of
 * those is drawn by hand on its own pixel grid below -- same construction,
 * whole-pixel bars, no antialiasing anywhere. That is the difference between
 * an icon set and a scaled-down logo.
 *
 * Every shape is an axis-aligned rectangle on integer coordinates, so the
 * rasteriser here is exact: it fills rectangles, and there is nothing for it
 * to soften. No SVG renderer is involved and no image library is needed.
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const BRAND = __dirname;
const REPO = path.resolve(BRAND, '..', '..');
const APP_ICONS = path.join(REPO, 'crates', 'launcher-desktop', 'icons');

/* -- colour ------------------------------------------------------------- *
 * The application mark has a transparent ground. Windows supplies the tile in
 * task switchers and title bars; baking a second black tile into the artwork
 * produces the visible square outline this generator is meant to avoid. */
const FRAME = [0xf4, 0xf6, 0xf9];
const ACCENT = [0xc4, 0x30, 0x3c];

const TILE_HEX = '#080b11';
const FRAME_HEX = '#f4f6f9';
const ACCENT_HEX = '#c4303c';
const INK_HEX = '#101215';

/* -- geometry ----------------------------------------------------------- *
 * `base` is the frame, `accent` is the suspended plane. Rectangles are
 * [x, y, width, height]. The frame is four bars rather than a ring because
 * the bottom and right bars stop short of each other: that gap is the opening
 * the mark descends through, and it has to survive down to 16px. */

const MASTER = {
  grid: 64,
  base: [
    [8, 8, 48, 10],   // top
    [8, 18, 10, 38],  // left
    [18, 46, 22, 10], // bottom, stops 16 units short of the right edge
    [46, 18, 10, 22], // right, stops 16 units short of the bottom edge
  ],
  accent: [[26, 26, 14, 14]],
};

/* Hand-drawn pixel grids. Proportions hold to the master within a pixel:
 * bar thickness ~21% of the mark, opening ~33%, plane ~29%, and the plane sits
 * one pixel down and right of centre wherever there is room for the offset to
 * read. */
const HAND = {
  16: {
    grid: 16,
    base: [[1, 1, 14, 3], [1, 4, 3, 11], [4, 12, 7, 3], [12, 4, 3, 7]],
    accent: [[6, 6, 4, 4]],
  },
  20: {
    grid: 20,
    base: [[2, 2, 16, 4], [2, 6, 4, 12], [6, 14, 7, 4], [14, 6, 4, 7]],
    accent: [[8, 8, 4, 4]],
  },
  24: {
    grid: 24,
    base: [[3, 3, 18, 4], [3, 7, 4, 14], [7, 17, 8, 4], [17, 7, 4, 8]],
    accent: [[10, 10, 5, 5]],
  },
  40: {
    grid: 40,
    base: [[5, 5, 30, 6], [5, 11, 6, 24], [11, 29, 14, 6], [29, 11, 6, 14]],
    accent: [[16, 16, 9, 9]],
  },
  48: {
    grid: 48,
    base: [[6, 6, 36, 8], [6, 14, 8, 28], [14, 34, 16, 8], [34, 14, 8, 16]],
    accent: [[20, 20, 10, 10]],
  },
};

function geometry(size) {
  if (HAND[size]) return HAND[size];
  const k = size / MASTER.grid;
  const scale = (r) => r.map((v) => {
    const out = v * k;
    if (!Number.isInteger(out)) {
      throw new Error(size + 'px does not land on the 64 grid and has no hand-drawn version');
    }
    return out;
  });
  return { grid: size, base: MASTER.base.map(scale), accent: MASTER.accent.map(scale) };
}

/* -- raster ------------------------------------------------------------- */

function render(size) {
  const g = geometry(size);
  // Buffer.alloc gives the ground alpha 0. Only the two pieces of the mark are
  // made opaque below.
  const px = Buffer.alloc(size * size * 4);
  const fill = (rects, c) => {
    for (const [x, y, w, h] of rects) {
      for (let row = y; row < y + h; row++) {
        for (let col = x; col < x + w; col++) {
          const i = (row * size + col) * 4;
          px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
        }
      }
    }
  };
  fill(g.base, FRAME);
  fill(g.accent, ACCENT);
  return px;
}

/* -- PNG ---------------------------------------------------------------- */

const CRC_TABLE = (function () {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
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
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // colour type: RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none. Flat colour compresses fine.
    px.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* -- ICO ---------------------------------------------------------------- *
 * BMP entries up to 128px, PNG only for 256. PNG-in-ICO has been legal since
 * Vista, but GDI+ and some installer toolchains still refuse to decode it, and
 * everything below 256 is what shells, installers and file dialogs actually
 * reach for -- so those stay in the format nothing has ever argued with. 256
 * is PNG because that is the convention and because uncompressed it would be
 * 264KB on its own. The AND mask mirrors transparent pixels for older shell
 * paths that do not honour the 32-bit alpha channel. */

const PNG_ENTRY_ABOVE = 128;

function bmpEntry(size, px) {
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0);
  header.writeInt32LE(size, 4);
  header.writeInt32LE(size * 2, 8); // XOR + AND, per the ICO format
  header.writeUInt16LE(1, 12);
  header.writeUInt16LE(32, 14);
  const xor = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    const src = (size - 1 - y) * size * 4; // BMP rows run bottom-up
    for (let x = 0; x < size; x++) {
      const s = src + x * 4;
      const d = (y * size + x) * 4;
      xor[d] = px[s + 2]; xor[d + 1] = px[s + 1]; xor[d + 2] = px[s]; xor[d + 3] = px[s + 3];
    }
  }
  const maskRow = Math.ceil(size / 32) * 4;
  const mask = Buffer.alloc(maskRow * size);
  for (let y = 0; y < size; y++) {
    const srcY = size - 1 - y;
    for (let x = 0; x < size; x++) {
      if (px[(srcY * size + x) * 4 + 3] === 0) {
        mask[y * maskRow + (x >> 3)] |= 0x80 >> (x & 7);
      }
    }
  }
  header.writeUInt32LE(xor.length + mask.length, 20);
  return Buffer.concat([header, xor, mask]);
}

function ico(sizes) {
  const images = sizes.map((size) => {
    const px = render(size);
    return { size, data: size > PNG_ENTRY_ABOVE ? png(size, px) : bmpEntry(size, px) };
  });
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  const dir = Buffer.alloc(images.length * 16);
  let offset = 6 + images.length * 16;
  images.forEach((img, i) => {
    const o = i * 16;
    dir[o] = img.size >= 256 ? 0 : img.size;
    dir[o + 1] = img.size >= 256 ? 0 : img.size;
    dir[o + 2] = 0;
    dir[o + 3] = 0;
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(img.data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += img.data.length;
  });
  return Buffer.concat([header, dir, ...images.map((i) => i.data)]);
}

/* -- SVG ---------------------------------------------------------------- */

function rects(list, attr) {
  return list.map(([x, y, w, h]) =>
    '  <rect ' + attr + ' x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '"/>'
  ).join('\n');
}

function svg(opts) {
  const g = geometry(opts.size);
  const box = g.grid;
  const lines = [
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + box + ' ' + box + '"' +
      ' width="' + box + '" height="' + box + '" role="img" aria-label="Fathom">',
    '  <title>' + opts.title + '</title>',
  ];
  if (opts.tile) lines.push('  <rect width="' + box + '" height="' + box + '" fill="' + TILE_HEX + '"/>');
  lines.push(rects(g.base, 'fill="' + opts.base + '"'));
  lines.push(rects(g.accent, 'fill="' + opts.accent + '"'));
  lines.push('</svg>', '');
  return lines.join('\n');
}

/* -- write -------------------------------------------------------------- */

function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
  const rel = path.relative(REPO, file).split(path.sep).join('/');
  console.log('  ' + rel + '  (' + data.length + ' bytes)');
}

const ACCENT_VAR = 'var(--fathom-accent,' + ACCENT_HEX + ')';

console.log('vector masters');
write(path.join(BRAND, 'logo.svg'), svg({
  size: 64, title: 'Fathom', base: FRAME_HEX, accent: ACCENT_HEX,
}));
write(path.join(BRAND, 'logo-on-light.svg'), svg({
  size: 64, title: 'Fathom', base: INK_HEX, accent: ACCENT_HEX,
}));
write(path.join(BRAND, 'logo-mono.svg'), svg({
  size: 64, title: 'Fathom', base: 'currentColor', accent: 'currentColor',
}));
write(path.join(BRAND, 'app-icon.svg'), svg({
  size: 64, title: 'Fathom', base: FRAME_HEX, accent: ACCENT_HEX,
}));

console.log('small-size masters, drawn on their own pixel grid');
for (const size of [16, 20, 24]) {
  write(path.join(BRAND, 'small', 'mark-' + size + '.svg'), svg({
    size: size, title: 'Fathom ' + size + 'px', base: 'currentColor', accent: ACCENT_VAR,
  }));
}

console.log('raster set');
const PNG_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512];
for (const size of PNG_SIZES) {
  write(path.join(BRAND, 'png', 'fathom-icon-' + size + '.png'), png(size, render(size)));
}

console.log('application icons');
const ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256];
write(path.join(APP_ICONS, 'icon.ico'), ico(ICO_SIZES));
write(path.join(APP_ICONS, 'icon.png'), png(256, render(256)));

console.log('\nico contains ' + ICO_SIZES.join(', ') + ' px');
