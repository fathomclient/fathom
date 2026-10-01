#!/usr/bin/env node
/*
 * Build every derived background file from `assets/backgrounds/manifest.json`.
 *
 *   node tools/prepare-background-library.mjs            build what is missing
 *   node tools/prepare-background-library.mjs --force    rebuild everything
 *
 * Needs `ffmpeg` on PATH. Runs when the library changes, not in a normal build;
 * its output is committed.
 *
 * WHAT IT MAKES, AND FROM WHAT
 *
 * - The FALLBACK STILL for a video background (`still`): frame 0 of the video
 *   itself, at the video's native size. The launcher shows it under the video
 *   until the first frame decodes and instead of the video if it cannot play,
 *   so it must be the same picture -- and not the 640x360 poster these files
 *   used to be, which was being stretched to full screen.
 * - A THUMBNAIL for the picker: 384x216 from the still. Never used full size.
 * - The MINECRAFT STILL for every background the game offers: the launcher's
 *   still, copied, so the menu shows the same picture as the launcher.
 * - Rainforest Storm's VIDEO: re-encoded from the game's own 1920x1080, 30 fps
 *   frame sequence (`textures/menu/bg_5rain/high`), which has no blur baked
 *   in. The launcher copy was 1600x900 with a Gaussian blur (sigma 3.5) and
 *   CRF 30 -- the soft, low-quality picture. CRF 23 keeps the rain.
 *
 * The other videos (bg_1-4, bg_5 twilight/drift, bg_6) cannot be improved
 * here: their unblurred sources live outside the repository. Rebuild them with
 * tools/prepare-background-video.ps1 once it no longer bakes in the blur.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'assets/backgrounds/manifest.json'), 'utf8'));
const force = process.argv.includes('--force');

const launcher = (rel) => path.join(root, 'desktop/public', rel);
const game = (rel) => path.join(root,
  'minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu', rel);
const RAIN_FRAMES = game('bg_5rain/high/frame_%03d.jpg');

function ffmpeg(args) {
  execFileSync('ffmpeg', ['-v', 'error', '-y', ...args], { stdio: 'inherit' });
}

function needed(file) {
  return force || !fs.existsSync(file);
}

let made = 0;
for (const bg of manifest.backgrounds) {
  // Rainforest Storm's launcher video, from the unblurred game frames.
  if (bg.id === 'bg_5rain' && bg.type === 'video' && needed(launcher(bg.asset))) {
    ffmpeg(['-framerate', '30', '-start_number', '0', '-i', RAIN_FRAMES,
      '-c:v', 'libvpx-vp9', '-pix_fmt', 'yuv420p', '-crf', '23', '-b:v', '0',
      '-row-mt', '1', '-deadline', 'good', '-cpu-used', '2', '-g', '30', '-an',
      launcher(bg.asset)]);
    made++;
  }

  // The fallback still: frame 0 of the video, native size.
  if (bg.type === 'video' && bg.still.endsWith('.jpg') && needed(launcher(bg.still))) {
    ffmpeg(['-i', launcher(bg.asset), '-frames:v', '1', '-q:v', '2', launcher(bg.still)]);
    made++;
  }

  // The picker thumbnail.
  const thumb = launcher(bg.thumbnail);
  if (needed(thumb)) {
    fs.mkdirSync(path.dirname(thumb), { recursive: true });
    ffmpeg(['-i', launcher(bg.still),
      '-vf', 'scale=384:216:force_original_aspect_ratio=increase:flags=lanczos,crop=384:216',
      '-q:v', '3', thumb]);
    made++;
  }

  // The game's still: the same picture the launcher shows.
  if (bg.minecraft) {
    const target = game(bg.minecraft.still);
    if (needed(target)) {
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(launcher(bg.still), target);
      made++;
    }
  }
}
console.log(made ? `Built ${made} background file(s).` : 'Background library is complete.');
