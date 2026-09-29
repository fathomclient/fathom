import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'assets', 'theme', 'fathom-theme.json');
const theme = JSON.parse(fs.readFileSync(source, 'utf8'));

/*
 * ONE CONTROL METRIC SYSTEM.
 *
 * `metrics` is the only place a radius, a control or row height, a switch, an
 * icon size or a spacing step is written as a number. Every other table names
 * one as "$<metric>", resolved here before anything is emitted, so the three
 * generations of tokens below (the legacy `dimensions`, `system` and
 * `graphite`) cannot drift apart again: they are aliases, not values.
 *
 * `METRIC_TABLES` is the enforcement. A literal number in one of those tables
 * is a screen inventing its own control size, and the build refuses it.
 */
const METRIC_TABLES = [
  ['dimensions', /^(r-|h-|s-\d$|rail-row$|page-gap$)/],
  ['system.radius', /./],
  ['system.space', /^[1-8]$/],
  ['system.control', /^height-/],
  ['system.layout', /^(section-gap|page-gap)$/],
  ['graphite.radius', /./],
  ['graphite.size', /^(control|primary$|switch-|icon-|mod-icon)/],
  ['graphite.space', /^[1-8]$/],
];

function resolveMetrics(node, where) {
  if (typeof node === 'string' && node.startsWith('$')) {
    const value = theme.metrics?.[node.slice(1)];
    if (typeof value !== 'number') throw new Error(`${where} names unknown metric ${node}`);
    return value;
  }
  if (node && typeof node === 'object' && !Array.isArray(node)) {
    for (const [key, value] of Object.entries(node)) {
      if (where === '' && key === 'metrics') continue;
      node[key] = resolveMetrics(value, where ? `${where}.${key}` : key);
    }
  }
  return node;
}

function tableAt(dotted, from) {
  return dotted.split('.').reduce((node, key) => node?.[key], from);
}

{
  const raw = JSON.parse(fs.readFileSync(source, 'utf8'));
  for (const [dotted, pattern] of METRIC_TABLES) {
    for (const [key, value] of Object.entries(tableAt(dotted, raw) ?? {})) {
      if (pattern.test(key) && typeof value === 'number') {
        throw new Error(
          `${dotted}.${key} is the literal ${value}. Control geometry comes from \`metrics\`: write "$<metric>".`);
      }
    }
  }
  resolveMetrics(theme, '');
}

/** The metrics that are counted in device pixels, not dp. */
const DEVICE_METRICS = new Set(['rim-device-px', 'divider-device-px', 'focus-device-px']);

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
// The canonical control metrics. Device-pixel metrics are multiples of
// `--hairline`, which `main.ts` sets to one device pixel in CSS pixels, so a rim
// is one physical pixel at 100%, 125%, 150% and 200% alike.
for (const [name, value] of Object.entries(theme.metrics)) {
  if (name.startsWith('_')) continue;
  css.push(DEVICE_METRICS.has(name)
    ? `  --metric-${name.replace(/-device-px$/, '')}: calc(var(--hairline) * ${value});`
    : `  --metric-${name}: ${value}px;`);
}
css.push('}');
css.push('');
css.push(...systemCss(theme.system));
css.push(...graphiteCss(theme.graphite));
emit(path.join(root, 'desktop', 'src', 'lib', 'theme.generated.css'), css.join('\n'));

/*
 * Graphite + Instrument (`graphite` in the manifest): the proof-of-direction
 * identity. Emitted as `--g-*` custom properties and as `GraphiteTokens` for the
 * game, alongside the retiring system rather than over it, so only the screens
 * that ask for it change.
 */
function graphiteCss(g) {
  if (!g) return [];
  const out = ['', '/* Graphite + Instrument. */', ':root {'];
  for (const [name, value] of Object.entries(g.color)) out.push(`  --g-${name}: ${value};`);
  for (const [name, m] of Object.entries(g.material)) {
    out.push(`  --g-mat-${name}: ${m.fill};`);
    out.push(`  --g-mat-${name}-rim: rgba(255, 255, 255, ${m.rim});`);
    if (typeof m.blur === 'number') out.push(`  --g-mat-${name}-blur: ${m.blur}px;`);
  }
  out.push(`  --g-elevation: 0 ${g.shadow.y}px ${g.shadow.blur}px ${g.color.shadow};`);
  for (const [name, value] of Object.entries(g.radius)) out.push(`  --g-r-${name}: ${value}px;`);
  for (const [name, value] of Object.entries(g.size)) out.push(`  --g-size-${name}: ${value}px;`);
  for (const [name, value] of Object.entries(g.space)) out.push(`  --g-s-${name}: ${value}px;`);
  for (const [name, value] of Object.entries(g.layout)) out.push(`  --g-${name}: ${value}px;`);
  for (const [name, r] of Object.entries(g.type)) {
    const family = r.mono ? "'Geist Mono', ui-monospace, monospace" : "'Geist', system-ui, sans-serif";
    out.push(`  --g-type-${name}-family: ${family};`);
    out.push(`  --g-type-${name}-size: ${r.size}px;`);
    out.push(`  --g-type-${name}-line: ${r.line}px;`);
    out.push(`  --g-type-${name}-weight: ${r.weight};`);
    out.push(`  --g-type-${name}-tracking: ${r.tracking}em;`);
  }
  out.push(`  --g-ease: cubic-bezier(${g.motion.ease.join(', ')});`);
  for (const name of ['fast', 'panel', 'close', 'tooltip-delay', 'scrollbar-fade']) {
    out.push(`  --g-motion-${name}: ${g.motion[name]}ms;`);
  }
  out.push(`  --g-travel: ${g.motion.travel}px;`);
  out.push(`  --g-vignette-start: ${g.backdrop['vignette-start'] * 100}%;`);
  out.push(`  --g-vignette-alpha: ${g.backdrop['vignette-alpha']};`);
  out.push(`  --g-grain-alpha: ${g.backdrop['grain-alpha']};`);
  for (const [name, value] of Object.entries(g.glow ?? {})) {
    if (typeof value !== 'number') continue;
    const unit = /-(blur|spread|size)$/.test(name) && name !== 'bloom-size' ? 'px' : name.endsWith('-seconds') ? 's' : '';
    out.push(`  --g-glow-${name.replace(/-seconds$/, '')}: ${value}${unit};`);
  }
  out.push('}');
  out.push('');
  return out;
}

function graphiteJava(g) {
  const out = [];
  out.push('package net.fathom.optimizations.generated;');
  out.push('');
  out.push('/** Graphite + Instrument tokens. Generated from assets/theme/fathom-theme.json. Do not edit. */');
  out.push('public final class GraphiteTokens {');
  out.push('    private GraphiteTokens() {}');
  out.push('');
  for (const [name, value] of Object.entries(g.color)) {
    out.push(`    public static final int COLOR_${javaName(name)} = ${colour(value)};`);
  }
  out.push('');
  for (const [name, m] of Object.entries(g.material)) {
    const key = `MATERIAL_${javaName(name)}`;
    out.push(`    public static final int ${key}_FILL = ${colour(m.fill)};`);
    out.push(`    public static final int ${key}_RIM = ${colour(`rgba(255, 255, 255, ${m.rim})`)};`);
    if (typeof m.blur === 'number') {
      out.push(`    public static final float ${key}_BLUR = ${m.blur.toFixed(3)}f;`);
    }
    out.push(`    public static final boolean ${key}_SHADOW = ${m.shadow};`);
  }
  out.push('');
  const floats = (prefix, table) => {
    for (const [name, value] of Object.entries(table)) {
      if (typeof value === 'number') {
        out.push(`    public static final float ${prefix}${javaName(name)} = ${value.toFixed(3)}f;`);
      }
    }
  };
  floats('STATE_', g.state);
  floats('SHADOW_', g.shadow);
  floats('RADIUS_', g.radius);
  floats('SIZE_', g.size);
  floats('SPACE_', g.space);
  floats('LAYOUT_', g.layout);
  floats('WORDMARK_', g.wordmark);
  floats('BACKDROP_', g.backdrop);
  floats('GLOW_', g.glow ?? {});
  out.push('');
  for (const [name, r] of Object.entries(g.type)) {
    const key = `TYPE_${javaName(name)}`;
    out.push(`    public static final float ${key}_SIZE = ${r.size.toFixed(1)}f;`);
    out.push(`    public static final float ${key}_LINE = ${r.line.toFixed(1)}f;`);
    out.push(`    public static final int ${key}_WEIGHT = ${r.weight};`);
    out.push(`    public static final float ${key}_TRACKING = ${r.tracking.toFixed(3)}f;`);
    out.push(`    public static final boolean ${key}_MONO = ${r.mono};`);
    out.push(`    public static final boolean ${key}_UPPER = ${r.upper};`);
  }
  out.push('');
  out.push(`    public static final float[] EASE = {${g.motion.ease.map((v) => `${Number(v).toFixed(2)}f`).join(', ')}};`);
  for (const name of ['fast', 'panel', 'close', 'tooltip-delay', 'scrollbar-fade']) {
    out.push(`    public static final float MOTION_${javaName(name)} = ${(g.motion[name] / 1000).toFixed(3)}f;`);
  }
  out.push(`    public static final float MOTION_TRAVEL = ${g.motion.travel.toFixed(1)}f;`);
  out.push(`    public static final String WORDMARK_TEXT = "${g.wordmark.text}";`);
  out.push('}');
  out.push('');
  return out;
}

if (theme.graphite) {
  emit(path.join(root, 'minecraft', 'fathom-optimizations', 'src', 'main', 'java',
    'net', 'fathom', 'optimizations', 'generated', 'GraphiteTokens.java'),
    graphiteJava(theme.graphite).join('\n'));
}

const motionTs = [
  '// Generated from assets/theme/fathom-theme.json. Do not edit.',
  ...Object.entries(theme.motion).map(([name, value]) =>
    `export const ${name.replace(/-/g, '_').toUpperCase()} = ${Number(value)};`),
  '',
  '// The shared Rainforest glass system (`system.motion`).',
  ...Object.entries(theme.system.motion.durations).map(([name, value]) =>
    `export const MOTION_${javaName(name)} = ${Number(value)};`),
  ...Object.entries(theme.system.motion.curves).map(([name, points]) =>
    `export const CURVE_${javaName(name)} = [${points.join(', ')}] as const;`),
  `export const PRESS_SCALE = ${theme.system.motion['press-scale']};`,
  `export const MATERIAL_LEVELS = [${Object.keys(theme.system.material).map((l) => `'${l}'`).join(', ')}] as const;`,
  ''
];
emit(path.join(root, 'desktop', 'src', 'lib', 'motion.generated.ts'), motionTs.join('\n'));

// The control metrics for script: icon sizes above all, which Lucide takes as a
// prop rather than a CSS length. A component writes `size={ICON.inline}`, never
// a number, so two icons in the same kind of control cannot differ.
{
  const camel = (name) => name.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
  const entries = Object.entries(theme.metrics).filter(([name]) => !name.startsWith('_'));
  const icons = entries.filter(([name]) => name.startsWith('icon-'));
  emit(path.join(root, 'desktop', 'src', 'lib', 'metrics.generated.ts'), [
    '/* Generated from assets/theme/fathom-theme.json (`metrics`). Do not edit. */',
    '',
    'export const METRIC = {',
    ...entries.map(([name, value]) => `  ${camel(name)}: ${value},`),
    '} as const;',
    '',
    '/** Icon sizes by role: `nav` rail, `inline` in a control or row, `tile` in a mark, `caption` in 12px text. */',
    'export const ICON = {',
    ...icons.map(([name, value]) => `  ${camel(name.slice(5))}: ${value},`),
    '} as const;',
    '',
  ].join('\n'));
}

// The brand's construction numbers and colours, for the geometry module that
// both the launcher and `assets/brand/build-brand.mjs` draw from
// (`desktop/src/lib/brand/aperture.ts`). Colours are resolved to hex so the
// icon rasteriser can use them directly.
const brandTs = [
  '// Generated from assets/theme/fathom-theme.json. Do not edit.',
  'export const BRAND = {',
  ...Object.entries(theme.system.brand).filter(([name]) => !name.startsWith('_')).map(([name, value]) => {
    const key = name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    const resolved = resolve(theme.system, value);
    return `  ${key}: ${typeof resolved === 'number' ? resolved : `'${resolved}'`},`;
  }),
  '} as const;',
  '',
];
emit(path.join(root, 'desktop', 'src', 'lib', 'brand', 'brand.generated.ts'), brandTs.join('\n'));

// ---------------------------------------------------------------------------
// The shared Rainforest glass system.
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
  //
  // Every line is ONE DEVICE PIXEL (`--hairline`), not one CSS pixel: at 125%
  // or 150% display scaling a `1px` inset is 1.25 or 1.5 device pixels, which
  // the compositor smears across two rows and which reads as a heavier border
  // on some controls than others. All three lines sit on the same outermost
  // row of pixels, so a level changes the rim's brightness, never its width.
  const lines = [
    `inset 0 var(--hairline) 0 0 ${rgba(light, level.rim.top)}`,
    `inset 0 0 0 var(--hairline) ${rgba(light, level.rim.edge)}`,
  ];
  if (level.rim.shade > 0) lines.push(`inset 0 calc(-1 * var(--hairline)) 0 0 ${rgba(shade, level.rim.shade)}`);
  return lines.join(', ');
}

/**
 * The same system for the game, where nothing reads the framebuffer.
 *
 * Nothing in the game reads the framebuffer, so there is no blur. The fill is
 * each level's `tint-game`: clear is the launcher's own faint neutral veil, for controls
 * on the photograph; light, standard and dense are neutral deepenings, far lighter
 * than `tint-noblur`. Unblurred, a pale veil behind light type lowers its
 * contrast, so a group that carries text deepens a little instead. `tint-noblur`
 * is the launcher's blur-off mode, whose panes sit on a scrimmed photograph and
 * may be dense; the game's float over the world and must stay transparent. The rim components are separate
 * colours: a HUD plate draws them as spans, not as a box-shadow list.
 */
function systemJava(system) {
  const out = [''];
  out.push('    /** The shared Rainforest glass system (`system` in the manifest), which the game `Skin` reads. */');
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
    out.push(`        public static final int ${prefix}_FILL = ${colour(level['tint-game'] ?? level['tint-noblur'])};`);
    out.push(`        public static final int ${prefix}_FILL_SOLID = ${colour(level['tint-solid'])};`);
    out.push(`        public static final int ${prefix}_RIM_TOP = ${colour(rgba(light, level.rim.top))};`);
    out.push(`        public static final int ${prefix}_RIM_EDGE = ${colour(rgba(light, level.rim.edge))};`);
    out.push(`        public static final int ${prefix}_RIM_SHADE = ${colour(rgba(shade, level.rim.shade))};`);
  }
  out.push('');
  // Flat control fills: what a control inside a pane is made of. The launcher's
  // rims are CSS shadow lists and have no single colour, so only plain colours
  // and heights cross over; the game draws its rims from the material tokens.
  for (const [name, value] of Object.entries(system.control)) {
    if (typeof value === 'number') {
      out.push(`        public static final float CONTROL_${javaName(name)} = ${value.toFixed(1)}f;`);
    } else if (!String(value).includes('inset')) {
      out.push(`        public static final int CONTROL_${javaName(name)} = ${colour(value)};`);
    }
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
  // The brand: the Aperture construction (in units of the six-unit grid, and
  // tile sizes in px) and its inks.
  for (const [name, value] of Object.entries(system.brand)) {
    if (name.startsWith('_')) continue;
    const resolved = resolve(system, value);
    if (typeof resolved === 'number') {
      out.push(`        public static final float BRAND_${javaName(name)} = ${resolved.toFixed(3)}f;`);
    } else {
      out.push(`        public static final int BRAND_${javaName(name)} = ${colour(resolved)};`);
    }
  }
  // The game's own scrims: over the world, over the photograph, under a modal.
  out.push('');
  for (const [name, value] of Object.entries(system.game ?? {})) {
    out.push(`        public static final int GAME_${javaName(name)} = ${colour(resolve(system, value))};`);
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
    if (name.startsWith('_')) continue;
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
java.push('');
java.push('    /**');
java.push('     * The one control metric system (`metrics` in the manifest): the same');
java.push('     * numbers the launcher reads as `--metric-*`. Values ending in');
java.push('     * {@code _DEVICE_PX} are device pixels, not logical units; draw them with');
java.push('     * {@code Renderer.rim}/{@code Renderer.pixel}, never as a dp thickness.');
java.push('     */');
java.push('    public static final class Metrics {');
java.push('        private Metrics() {}');
java.push('');
for (const [name, value] of Object.entries(theme.metrics)) {
  if (name.startsWith('_')) continue;
  java.push(`        public static final float ${javaName(name)} = ${Number(value).toFixed(1)}f;`);
}
java.push('    }');
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
