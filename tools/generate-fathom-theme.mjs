import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'assets', 'theme', 'fathom-theme.json');
const theme = JSON.parse(fs.readFileSync(source, 'utf8'));

// `--check` writes nothing and exits non-zero if any output differs from what
// the manifest produces: for `npm run check`, and anything else that should
// fail rather than repair. Without it an output is rewritten only when its
// content changed, so an unchanged manifest leaves every timestamp alone and
// Gradle's and Vite's up-to-date checks stay quiet.
const checkOnly = process.argv.includes('--check');
const stale = [];

function emit(file, content) {
  let existing = null;
  try {
    existing = fs.readFileSync(file, 'utf8');
  } catch {
    /* A missing output is a stale one. */
  }
  // Git may check these out with CRLF on Windows; the content is what matters.
  if (existing !== null && existing.replace(/\r\n/g, '\n') === content) return;
  if (checkOnly) stale.push(path.relative(root, file));
  else fs.writeFileSync(file, content);
}

const css = [];
css.push('/* Generated from assets/theme/fathom-theme.json. Do not edit. */');
css.push(':root {');
for (const [name, value] of Object.entries(theme.colors)) css.push(`  --${name}: ${value};`);
for (const [name, value] of Object.entries(theme.dimensions)) css.push(`  --${name}: ${value}px;`);
for (const [name, value] of Object.entries(theme.ratios)) css.push(`  --${name}: ${value};`);
for (const [name, value] of Object.entries(theme.typography)) css.push(`  --t-${name}: ${value}px;`);
for (const [name, value] of Object.entries(theme.weights)) css.push(`  --w-${name}: ${value};`);
for (const [name, value] of Object.entries(theme.motion)) css.push(`  --m-${name}: ${value}ms;`);
for (const [name, value] of Object.entries(theme.css)) css.push(`  --${name}: ${value};`);
// Temporary aliases retained for the one legacy route that predates the 4px token names.
for (const name of ['1', '2', '3', '4', '5']) css.push(`  --s${name}: var(--s-${name});`);
css.push('}');
css.push('');
css.push(...systemCss(theme.system));
emit(path.join(root, 'desktop', 'src', 'lib', 'theme.generated.css'), css.join('\n'));

const motionTs = [
  '// Generated from assets/theme/fathom-theme.json. Do not edit.',
  ...Object.entries(theme.motion).map(([name, value]) =>
    `export const ${name.replace(/-/g, '_').toUpperCase()} = ${Number(value)};`),
  '',
  '// The golden-hour system (`system.motion`).',
  ...Object.entries(theme.system.motion.durations).map(([name, value]) =>
    `export const MOTION_${javaName(name)} = ${Number(value)};`),
  ...Object.entries(theme.system.motion.curves).map(([name, points]) =>
    `export const CURVE_${javaName(name)} = [${points.join(', ')}] as const;`),
  `export const PRESS_SCALE = ${theme.system.motion['press-scale']};`,
  `export const MATERIAL_LEVELS = [${Object.keys(theme.system.material).map((l) => `'${l}'`).join(', ')}] as const;`,
  ''
];
emit(path.join(root, 'desktop', 'src', 'lib', 'motion.generated.ts'), motionTs.join('\n'));

// The brand's construction numbers and colours, for the geometry module that
// both the launcher and `assets/brand/build-brand.mjs` draw from. Colours are
// resolved to hex so the icon rasteriser can use them directly.
const brandTs = [
  '// Generated from assets/theme/fathom-theme.json. Do not edit.',
  'export const BRAND = {',
  ...Object.entries(theme.system.brand).map(([name, value]) => {
    const key = name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    const resolved = resolve(theme.system, value);
    return `  ${key}: ${typeof resolved === 'number' ? resolved : `'${resolved}'`},`;
  }),
  '} as const;',
  '',
];
emit(path.join(root, 'desktop', 'src', 'lib', 'brand', 'brand.generated.ts'), brandTs.join('\n'));

// ---------------------------------------------------------------------------
// The golden-hour system.
//
// Everything below is emitted from `theme.system`. The legacy sections above
// are still emitted unchanged, so screens that have not been rebuilt keep
// rendering exactly as they did while they migrate onto these tokens.
// ---------------------------------------------------------------------------

/** Resolve an `@name` reference into the palette. */
function resolve(system, value) {
  if (typeof value !== 'string' || !value.startsWith('@')) return value;
  const found = system.palette[value.slice(1)];
  if (!found) throw new Error(`Unknown palette reference ${value}`);
  return found;
}

function rgba(hex, alpha) {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  if (!match) throw new Error(`Expected #rrggbb, got ${hex}`);
  const [r, g, b] = match.slice(1).map((part) => parseInt(part, 16));
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function rim(system, level) {
  const light = resolve(system, system.color['rim-light']);
  const shade = resolve(system, system.color['rim-shade']);
  // Light catching the edge of a thick pane: brightest along the top, a faint
  // even edge all round, and a darker lip underneath only where the level asks
  // for one. Inset shadows follow the corner radius, so the highlight curves
  // into the corners and fades there instead of stopping square.
  return [
    `inset 0 1px 0 0 ${rgba(light, level.rim.top)}`,
    `inset 0 0 0 1px ${rgba(light, level.rim.edge)}`,
    `inset 0 -1px 0 0 ${rgba(shade, level.rim.shade)}`,
  ].join(', ');
}

/**
 * The same system for the game, where nothing reads the framebuffer.
 *
 * In-game surfaces are the launcher's reduced-blur mode by construction, so the
 * game gets each level's `tint-noblur` as its fill, plus the rim components as
 * separate colours: a HUD plate draws them as spans, not as a box-shadow list.
 * Not consumed yet; the in-game migration reads these instead of adding its own.
 */
function systemJava(system) {
  const out = [''];
  out.push('    /** The golden-hour system (`system` in the manifest). Not yet consumed in game. */');
  out.push('    public static final class Tokens {');
  out.push('        private Tokens() {}');
  out.push('');
  for (const [name, value] of Object.entries(system.palette)) {
    out.push(`        public static final int PALETTE_${javaName(name)} = ${colour(value)};`);
  }
  for (const [name, value] of Object.entries(system.color)) {
    out.push(`        public static final int COLOR_${javaName(name)} = ${colour(resolve(system, value))};`);
  }
  out.push('');
  const light = resolve(system, system.color['rim-light']);
  const shade = resolve(system, system.color['rim-shade']);
  for (const [name, level] of Object.entries(system.material)) {
    const prefix = `MATERIAL_${javaName(name)}`;
    out.push(`        public static final int ${prefix}_FILL = ${colour(level['tint-noblur'])};`);
    out.push(`        public static final int ${prefix}_FILL_SOLID = ${colour(level['tint-solid'])};`);
    out.push(`        public static final int ${prefix}_RIM_TOP = ${colour(rgba(light, level.rim.top))};`);
    out.push(`        public static final int ${prefix}_RIM_EDGE = ${colour(rgba(light, level.rim.edge))};`);
    out.push(`        public static final int ${prefix}_RIM_SHADE = ${colour(rgba(shade, level.rim.shade))};`);
  }
  out.push('');
  for (const [name, value] of Object.entries(system.radius)) {
    out.push(`        public static final float RADIUS_${javaName(name)} = ${Number(value).toFixed(1)}f;`);
  }
  for (const [name, value] of Object.entries(system.space)) {
    out.push(`        public static final float SPACE_${javaName(name)} = ${Number(value).toFixed(1)}f;`);
  }
  for (const [name, role] of Object.entries(system.type.roles)) {
    out.push(`        public static final float TYPE_${javaName(name)} = ${Number(role.size).toFixed(1)}f;`);
  }
  for (const [name, value] of Object.entries(system.motion.durations)) {
    out.push(`        public static final float MOTION_${javaName(name)} = ${(Number(value) / 1000).toFixed(3)}f;`);
  }
  out.push('');
  // The brand: the Surface construction (in units of a 100-unit cap height)
  // and its colours, for the in-game wordmark, ring and Sounding Line.
  for (const [name, value] of Object.entries(system.brand)) {
    const resolved = resolve(system, value);
    if (typeof resolved === 'number') {
      out.push(`        public static final float BRAND_${javaName(name)} = ${resolved.toFixed(3)}f;`);
    } else {
      out.push(`        public static final int BRAND_${javaName(name)} = ${colour(resolved)};`);
    }
  }
  out.push('    }');
  return out;
}

function modeBlock(selector, system, pick) {
  const out = [`${selector} {`];
  for (const name of Object.keys(system.material)) {
    out.push(`  --mat-${name}-fill: var(--mat-${name}-${pick});`);
    out.push(`  --mat-${name}-filter: none;`);
  }
  return out;
}

function systemCss(system) {
  const out = [];
  out.push(':root {');
  for (const [name, value] of Object.entries(system.palette)) out.push(`  --palette-${name}: ${value};`);
  for (const [name, value] of Object.entries(system.color)) {
    out.push(`  --color-${name}: ${resolve(system, value)};`);
  }
  for (const [name, value] of Object.entries(system.shadow)) out.push(`  --shadow-${name}: ${value};`);
  for (const [name, value] of Object.entries(system.radius)) out.push(`  --radius-${name}: ${value}px;`);
  for (const [name, value] of Object.entries(system.space)) out.push(`  --space-${name}: ${value}px;`);
  for (const [name, value] of Object.entries(system.layout)) out.push(`  --layout-${name}: ${value}px;`);
  // Flat control surfaces: the fills, rims and heights of things that sit on or
  // inside a material, where a second blur would be wasted work.
  for (const [name, value] of Object.entries(system.control)) {
    out.push(`  --control-${name}: ${typeof value === 'number' ? `${value}px` : value};`);
  }

  out.push(`  --type-family: ${system.type.family};`);
  for (const [name, role] of Object.entries(system.type.roles)) {
    out.push(`  --type-${name}-size: ${role.fluid ?? `${role.size}px`};`);
    out.push(`  --type-${name}-weight: ${role.weight};`);
    out.push(`  --type-${name}-leading: ${role.leading};`);
    out.push(`  --type-${name}-tracking: ${role.tracking};`);
  }

  for (const [name, value] of Object.entries(system.motion.durations)) {
    out.push(`  --motion-${name}: ${value}ms;`);
  }
  for (const [name, points] of Object.entries(system.motion.curves)) {
    out.push(`  --ease-${name}: cubic-bezier(${points.join(', ')});`);
  }
  out.push(`  --press-scale: ${system.motion['press-scale']};`);

  for (const [name, value] of Object.entries(system.backdrop)) out.push(`  --backdrop-${name}: ${value};`);
  for (const [name, value] of Object.entries(system.brand)) {
    out.push(`  --brand-${name}: ${resolve(system, value)};`);
  }

  // Materials. `fill` and `filter` are the resolved values every surface reads;
  // the accessibility modes below swap only those two, so no component ever
  // branches on a mode.
  for (const [name, level] of Object.entries(system.material)) {
    out.push(`  --mat-${name}-tint: ${level.tint};`);
    out.push(`  --mat-${name}-tint-noblur: ${level['tint-noblur']};`);
    out.push(`  --mat-${name}-tint-solid: ${level['tint-solid']};`);
    out.push(`  --mat-${name}-blur: ${level.blur}px;`);
    out.push(`  --mat-${name}-rim: ${rim(system, level)};`);
    out.push(`  --mat-${name}-shadow: var(--shadow-${level.shadow});`);
    out.push(`  --mat-${name}-fill: var(--mat-${name}-tint);`);
    out.push(
      `  --mat-${name}-filter: blur(var(--mat-${name}-blur)) saturate(${level.saturate}) brightness(${level.brightness});`);
  }
  out.push('}');
  out.push('');

  // One rule per level. A surface is `class="material-standard"` and nothing
  // else; its radius comes from `--surface-radius` so the component that owns
  // the geometry decides it, and a `.concentric` child can derive its own.
  for (const name of Object.keys(system.material)) {
    out.push(`.material-${name} {`);
    out.push(`  background-color: var(--mat-${name}-fill);`);
    out.push(`  -webkit-backdrop-filter: var(--mat-${name}-filter);`);
    out.push(`  backdrop-filter: var(--mat-${name}-filter);`);
    out.push(`  box-shadow: var(--mat-${name}-rim), inset 0 0 0 100vmax var(--mat-veil, transparent), var(--mat-${name}-shadow);`);
    out.push('  border-radius: var(--surface-radius, var(--radius-lg));');
    out.push('  color: var(--color-text);');
    out.push('}');
  }
  out.push('');

  for (const [name, role] of Object.entries(system.type.roles)) {
    out.push(`.type-${name} {`);
    out.push('  font-family: var(--type-family);');
    out.push(`  font-size: var(--type-${name}-size);`);
    out.push(`  font-weight: var(--type-${name}-weight);`);
    out.push(`  line-height: var(--type-${name}-leading);`);
    out.push(`  letter-spacing: var(--type-${name}-tracking);`);
    out.push(`  color: var(--color-${role.color});`);
    if (role.numeric) out.push('  font-variant-numeric: tabular-nums;');
    out.push('}');
  }
  out.push('');

  // Accessibility modes. Reduced transparency wins over reduced blur, so it is
  // emitted last. Windows' own "Transparency effects" switch arrives as the
  // media query; the attributes are the launcher's own settings.
  out.push(...modeBlock(':root[data-blur="off"]', system, 'tint-noblur'));
  out.push('  --glass-blur: 0px;');
  out.push('  --glass-blur-strong: 0px;');
  out.push('}');
  for (const selector of [':root[data-transparency="reduced"]']) {
    out.push(...modeBlock(selector, system, 'tint-solid'));
    out.push('  --glass-fill: var(--glass-fill-solid);');
    out.push('  --glass-fill-strong: var(--glass-fill-solid);');
    out.push('  --backdrop-scrim: var(--backdrop-scrim-reduced);');
    out.push('}');
  }
  out.push('@media (prefers-reduced-transparency: reduce) {');
  // `[data-blur="off"]` is listed so the system setting still wins over the
  // launcher's blur switch: without it that selector's higher specificity would
  // put the no-blur tints back on top of the solid ones.
  out.push(...modeBlock(':root, :root[data-blur="off"]', system, 'tint-solid').map((line) => `  ${line}`));
  out.push('    --glass-fill: var(--glass-fill-solid);');
  out.push('    --glass-fill-strong: var(--glass-fill-solid);');
  out.push('    --backdrop-scrim: var(--backdrop-scrim-reduced);');
  out.push('  }');
  out.push('}');
  out.push('');
  return out;
}

function javaName(name) {
  return name.replace(/-/g, '_').toUpperCase();
}

function colour(value) {
  if (/^#[0-9a-f]{6}$/i.test(value)) return `0xFF${value.slice(1).toUpperCase()}`;
  const match = value.match(/^rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)$/i);
  if (!match) throw new Error(`Java colour cannot parse ${value}`);
  const alpha = Math.round(Number(match[4]) * 255);
  const hex = [alpha, Number(match[1]), Number(match[2]), Number(match[3])]
    .map((part) => part.toString(16).padStart(2, '0').toUpperCase()).join('');
  return `0x${hex}`;
}

const java = [];
java.push('package net.fathom.optimizations.generated;');
java.push('');
java.push('/** Generated from assets/theme/fathom-theme.json. Do not edit. */');
java.push('public final class FathomTheme {');
java.push('    private FathomTheme() {}');
java.push('');
for (const [name, value] of Object.entries(theme.colors)) {
  java.push(`    public static final int COLOR_${javaName(name)} = ${colour(value)};`);
}
java.push('');
for (const [name, value] of Object.entries(theme.dimensions)) {
  java.push(`    public static final float DIM_${javaName(name)} = ${Number(value).toFixed(1)}f;`);
}
for (const [name, value] of Object.entries(theme.ratios)) {
  java.push(`    public static final float RATIO_${javaName(name)} = ${Number(value).toFixed(3)}f;`);
}
java.push('');
for (const [name, value] of Object.entries(theme.typography)) {
  java.push(`    public static final float TYPE_${javaName(name)} = ${Number(value).toFixed(1)}f;`);
}
java.push('');
for (const [name, value] of Object.entries(theme.motion)) {
  java.push(`    public static final float MOTION_${javaName(name)} = ${(Number(value) / 1000).toFixed(3)}f;`);
}
java.push(...systemJava(theme.system));
java.push('}');
java.push('');
emit(path.join(root, 'minecraft', 'fathom-optimizations', 'src', 'main', 'java',
  'net', 'fathom', 'optimizations', 'generated', 'FathomTheme.java'), java.join('\n'));

if (checkOnly && stale.length > 0) {
  console.error('Theme outputs are out of date with assets/theme/fathom-theme.json:');
  for (const file of stale) console.error(`  ${file}`);
  console.error('Run: node tools/generate-fathom-theme.mjs');
  process.exit(1);
}
console.log(checkOnly
  ? 'Theme outputs match assets/theme/fathom-theme.json.'
  : 'Theme outputs are current with assets/theme/fathom-theme.json.');
