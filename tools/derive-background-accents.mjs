#!/usr/bin/env node
/**
 * Give every background in assets/backgrounds/manifest.json its accent: the
 * colour the interface's glows take while that background is showing.
 *
 * The accent is measured from the background's own still, not chosen by hand,
 * so a new background gets one by running this and nothing else:
 *
 *   node tools/derive-background-accents.mjs      then
 *   node tools/generate-backgrounds.mjs
 *
 * How: the still is decoded small (48x27, by ffmpeg) and each pixel votes for
 * its hue, weighted by how coloured and how bright it is, so a lit tower or a
 * sunset outvotes the large dim areas around it and a grey picture barely
 * votes at all. The winning hue is then set at one fixed lightness, so every accent
 * glows at the same strength on the same dark interface, and its saturation
 * follows how colourful the picture really was: a grey background gets a
 * near-grey accent rather than an invented colour.
 *
 * Where the loudest colour is not the one the picture is about -- a cherry
 * grove is about its blossom, not its grass -- the manifest entry names the
 * hue with `accentHue` (degrees). Lightness and saturation are still measured.
 *
 * Needs `ffmpeg` on PATH, like tools/prepare-background-library.mjs.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'assets/backgrounds/manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
// The one lightness every accent is set at: `graphite.glow.accent-lightness`.
const theme = JSON.parse(fs.readFileSync(path.join(root, 'assets/theme/fathom-theme.json'), 'utf8'));
const LIGHTNESS = theme.graphite.glow['accent-lightness'];

const W = 48;
const H = 27;

function pixels(file) {
  return execFileSync('ffmpeg', [
    '-v', 'error', '-i', file, '-frames:v', '1',
    '-vf', `scale=${W}:${H}:flags=area`, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-',
  ], { maxBuffer: W * H * 3 + 1024 });
}

function hsv(r, g, b) {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d > 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return { h, s: max === 0 ? 0 : d / max, v: max };
}

function hslToHex(h, s, l) {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x]
    : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  const hex = (n) => Math.round((n + m) * 255).toString(16).padStart(2, '0');
  return `#${hex(r)}${hex(g)}${hex(b)}`;
}

export function accentOf(file, pinnedHue) {
  const data = pixels(file);
  // A hue histogram, not an average: averaging the pink of a cherry grove
  // with the green of its grass gives a yellow that is in neither.
  const BINS = 24;
  const votes = new Float64Array(BINS);
  let chroma = 0;
  const count = W * H;
  const samples = [];
  for (let i = 0; i < count; i++) {
    const { h, s, v } = hsv(data[i * 3] / 255, data[i * 3 + 1] / 255, data[i * 3 + 2] / 255);
    chroma += s * v;
    if (v < 0.06 || s < 0.08) continue;
    // Vivid and bright counts most: the lit tower, the sunset -- what the eye
    // goes to -- over the large, merely tinted sky or grass around it.
    const w = s ** 4 * v * v;
    votes[Math.floor(h / (360 / BINS)) % BINS] += w;
    samples.push([h, w]);
  }
  // Smooth over neighbouring bins so a hue on a bin edge is not split in two.
  let best = 0;
  let bestScore = -1;
  for (let b = 0; b < BINS; b++) {
    const score = votes[(b + BINS - 1) % BINS] * 0.5 + votes[b] + votes[(b + 1) % BINS] * 0.5;
    if (score > bestScore) {
      bestScore = score;
      best = b;
    }
  }
  // The weighted mean of the pixels within 30 degrees of the winning bin.
  const centre = (best + 0.5) * (360 / BINS);
  let x = 0;
  let y = 0;
  for (const [h, w] of samples) {
    const apart = Math.abs(((h - centre + 540) % 360) - 180);
    if (apart > 30) continue;
    x += Math.cos((h * Math.PI) / 180) * w;
    y += Math.sin((h * Math.PI) / 180) * w;
  }
  let hue = x === 0 && y === 0 ? 210 : (Math.atan2(y, x) * 180) / Math.PI;
  if (hue < 0) hue += 360;
  if (typeof pinnedHue === 'number') hue = pinnedHue;
  // How colourful the picture is overall, 0 for grey. Mapped so a typical
  // Minecraft scene lands around 0.5-0.7 and a grey one near 0.15.
  const colourful = Math.min(1, (chroma / count) * 4.5);
  const saturation = 0.12 + 0.6 * colourful;
  return hslToHex(Math.round(hue) % 360, Math.round(saturation * 100) / 100, LIGHTNESS);
}

for (const bg of manifest.backgrounds) {
  bg.accent = accentOf(path.join(root, 'desktop/public', bg.still), bg.accentHue);
  console.log(`${bg.id.padEnd(24)} ${bg.accent}`);
}
fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
