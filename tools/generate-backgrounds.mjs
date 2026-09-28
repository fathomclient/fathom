#!/usr/bin/env node
/*
 * The background catalogue, from `assets/backgrounds/manifest.json` into:
 *
 *   desktop/src/lib/backgrounds.generated.ts        the launcher's picker and player
 *   .../generated/BackgroundCatalog.java            the game's menus
 *
 * `--check` writes nothing and fails if either output is stale, like the theme
 * generator. The Rust allowlist is not generated -- it is a security boundary
 * and stays a literal -- but its test reads the same manifest.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'assets/backgrounds/manifest.json'), 'utf8'));
const checkOnly = process.argv.includes('--check');
const stale = [];

function emit(file, content) {
  let existing = null;
  try {
    existing = fs.readFileSync(file, 'utf8');
  } catch {
    /* A missing output is a stale one. */
  }
  if (existing !== null && existing.replace(/\r\n/g, '\n') === content) return;
  if (checkOnly) stale.push(path.relative(root, file));
  else fs.writeFileSync(file, content);
}

const ID = /^[a-z0-9][a-z0-9_-]*$/;
const ids = new Set();
for (const bg of manifest.backgrounds) {
  // Ids become file names and URL segments; the Rust allowlist refuses
  // anything else, and so does this.
  if (!ID.test(bg.id)) throw new Error(`background id ${JSON.stringify(bg.id)} is not a plain name`);
  if (ids.has(bg.id)) throw new Error(`background id ${bg.id} is listed twice`);
  ids.add(bg.id);
  if (!['video', 'image'].includes(bg.type)) throw new Error(`${bg.id}: type must be video or image`);
  if (!/^#[0-9a-f]{6}$/.test(bg.accent ?? '')) {
    throw new Error(`${bg.id}: accent must be #rrggbb (run tools/derive-background-accents.mjs)`);
  }
  for (const key of ['asset', 'still', 'thumbnail']) {
    const file = path.join(root, 'desktop/public', bg[key]);
    if (!fs.existsSync(file)) throw new Error(`${bg.id}: ${key} ${bg[key]} does not exist (run tools/prepare-background-library.mjs)`);
  }
  if (bg.minecraft) {
    const file = path.join(root,
      'minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu',
      bg.minecraft.still);
    if (!fs.existsSync(file)) throw new Error(`${bg.id}: minecraft still ${bg.minecraft.still} does not exist`);
  }
}
if (!ids.has(manifest.default)) throw new Error(`the default ${manifest.default} is not in the library`);

const q = (value) => JSON.stringify(value);
const ts = [
  '/* Generated from assets/backgrounds/manifest.json by tools/generate-backgrounds.mjs. Do not edit. */',
  '',
  'export interface BackgroundOption {',
  '  id: string;',
  '  label: string;',
  "  /** `video` loops the asset over its still; `image` is the still. */",
  "  type: 'video' | 'image';",
  '  /** URLs, served from desktop/public. */',
  '  asset: string;',
  '  still: string;',
  '  thumbnail: string;',
  '  /** The asset\'s native size, in pixels. */',
  '  width: number;',
  '  height: number;',
  "  /** What the game's menus show: its video, its still, or nothing. */",
  "  minecraft: 'video' | 'still' | null;",
  '  /** Plain-text credit shown in Settings; the full record is ATTRIBUTION.md. */',
  '  credit: string;',
  "  /** The glow colour while this background shows (tools/derive-background-accents.mjs). */",
  '  accent: string;',
  '}',
  '',
  'export const BACKGROUNDS: BackgroundOption[] = [',
  ...manifest.backgrounds.filter((bg) => bg.launcher).map((bg) => [
    '  {',
    `    id: ${q(bg.id)},`,
    `    label: ${q(bg.label)},`,
    `    type: ${q(bg.type)},`,
    `    asset: ${q(`/${bg.asset}`)},`,
    `    still: ${q(`/${bg.still}`)},`,
    `    thumbnail: ${q(`/${bg.thumbnail}`)},`,
    `    width: ${bg.width},`,
    `    height: ${bg.height},`,
    `    minecraft: ${bg.minecraft ? q(bg.minecraft.video ? 'video' : 'still') : 'null'},`,
    `    credit: ${q(bg.credit)},`,
    `    accent: ${q(bg.accent)},`,
    '  },',
  ].join('\n')),
  '];',
  '',
  `export const DEFAULT_BACKGROUND = ${q(manifest.default)};`,
  '',
].join('\n');
emit(path.join(root, 'desktop/src/lib/backgrounds.generated.ts'), ts);

const inGame = manifest.backgrounds.filter((bg) => bg.minecraft);
const arr = (pick) => inGame.map((bg) => {
  const value = pick(bg);
  return `        ${value === null ? 'null' : q(value)},`;
});
const java = [
  'package net.fathom.optimizations.generated;',
  '',
  '/**',
  ' * The backgrounds the game\'s menus can show. Generated from',
  ' * assets/backgrounds/manifest.json by tools/generate-backgrounds.mjs. Do not edit.',
  ' *',
  ' * <p>Parallel arrays, one entry per background. {@code STILL} is a classpath',
  ' * resource under {@code /assets/fathom-optimizations/textures/menu/};',
  ' * {@code VIDEO} names a frame sequence there, or is null for a still.',
  ' */',
  'public final class BackgroundCatalog {',
  '    private BackgroundCatalog() {}',
  '',
  `    public static final String DEFAULT_ID = ${q(manifest.default)};`,
  '',
  '    public static final String[] IDS = {',
  ...arr((bg) => bg.id),
  '    };',
  '',
  '    public static final String[] LABELS = {',
  ...arr((bg) => bg.label),
  '    };',
  '',
  '    public static final String[] STILL = {',
  ...arr((bg) => bg.minecraft.still),
  '    };',
  '',
  '    public static final String[] VIDEO = {',
  ...arr((bg) => bg.minecraft.video),
  '    };',
  '',
  '    public static final String[] CREDITS = {',
  ...arr((bg) => bg.credit),
  '    };',
  '',
  '    /** The glow colour while each background shows, 0xRRGGBB. */',
  '    public static final int[] ACCENT = {',
  ...inGame.map((bg) => `        0x${bg.accent.slice(1).toUpperCase()},`),
  '    };',
  '}',
  '',
].join('\n');
emit(path.join(root, 'minecraft/fathom-optimizations/src/main/java/net/fathom/optimizations/generated/BackgroundCatalog.java'), java);

if (checkOnly && stale.length > 0) {
  console.error('Background outputs are out of date with assets/backgrounds/manifest.json:');
  for (const file of stale) console.error(`  ${file}`);
  console.error('Run: node tools/generate-backgrounds.mjs');
  process.exit(1);
}
console.log(checkOnly
  ? 'Background outputs match assets/backgrounds/manifest.json.'
  : 'Background outputs are current with assets/backgrounds/manifest.json.');
