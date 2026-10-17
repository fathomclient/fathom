import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'assets', 'icons', 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const lucideRoot = path.join(root, 'desktop', 'node_modules', '@lucide', 'svelte', 'dist', 'icons');
const assetRoot = path.join(root, 'assets', 'icons');

function number(value, fallback = 0) {
  return value === undefined ? fallback : Number(value);
}

function nodePath([kind, attributes]) {
  if (kind === 'path') return attributes.d;
  if (kind === 'line') {
    return `M${attributes.x1} ${attributes.y1}L${attributes.x2} ${attributes.y2}`;
  }
  if (kind === 'polyline' || kind === 'polygon') {
    const points = String(attributes.points).trim().split(/[\s,]+/);
    if (points.length % 2 !== 0) throw new Error(`Odd point count in ${attributes.points}`);
    let out = '';
    for (let index = 0; index < points.length; index += 2) {
      out += `${index === 0 ? 'M' : 'L'}${points[index]} ${points[index + 1]}`;
    }
    return out + (kind === 'polygon' ? 'Z' : '');
  }
  if (kind === 'circle' || kind === 'ellipse') {
    const cx = number(attributes.cx);
    const cy = number(attributes.cy);
    const rx = kind === 'circle' ? number(attributes.r) : number(attributes.rx);
    const ry = kind === 'circle' ? number(attributes.r) : number(attributes.ry);
    return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${rx * 2} 0a${rx} ${ry} 0 1 0 ${-rx * 2} 0Z`;
  }
  if (kind === 'rect') {
    const x = number(attributes.x);
    const y = number(attributes.y);
    const width = number(attributes.width);
    const height = number(attributes.height);
    const rx = Math.min(number(attributes.rx), width / 2, height / 2);
    if (!rx) return `M${x} ${y}h${width}v${height}h${-width}Z`;
    return `M${x + rx} ${y}h${width - rx * 2}a${rx} ${rx} 0 0 1 ${rx} ${rx}` +
      `v${height - rx * 2}a${rx} ${rx} 0 0 1 ${-rx} ${rx}h${-(width - rx * 2)}` +
      `a${rx} ${rx} 0 0 1 ${-rx} ${-rx}v${-(height - rx * 2)}a${rx} ${rx} 0 0 1 ${rx} ${-rx}Z`;
  }
  throw new Error(`Unsupported Lucide node ${kind}`);
}

const resolved = new Map();
for (const [id, definition] of Object.entries(manifest.icons)) {
  const componentPath = path.join(lucideRoot, `${definition.lucide}.svelte`);
  const component = fs.readFileSync(componentPath, 'utf8');
  const match = component.match(/const iconNode = (\[[\s\S]*?\]);/);
  if (!match) throw new Error(`No iconNode in ${componentPath}`);
  const nodes = JSON.parse(match[1]);
  const body = nodes.map(([kind, attributes]) => {
    const attrs = Object.entries(attributes).map(([key, value]) => ` ${key}="${value}"`).join('');
    return `  <${kind}${attrs} />`;
  }).join('\n');
  const fill = definition.fill ? 'currentColor' : 'none';
  const svg = `<!-- Generated from ${manifest.package}/${definition.lucide}. -->\n` +
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="${fill}" ` +
    `stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">\n${body}\n</svg>\n`;
  fs.writeFileSync(path.join(assetRoot, `${id}.svg`), svg);
  // Each SVG child owns a fresh current point. A leading relative move must
  // therefore start at SVG's origin even when several children are combined.
  // Prefixing a zero move preserves all following implicit relative segments.
  const combined = nodes.map(nodePath)
    .map(value => /^\s*m/.test(value) ? `M0 0 ${value}` : value)
    .join(' ');
  const inline = nodes.map(([kind, attributes]) => {
    const attrs = Object.entries(attributes).map(([key, value]) => ` ${key}="${value}"`).join('');
    return `<${kind}${attrs}/>`;
  }).join('');
  resolved.set(id, { path: combined, fill: Boolean(definition.fill), inline });
}

const ts = [
  '// Generated from assets/icons/manifest.json. Do not edit.',
  '//',
  '// The launcher draws these inline (see `FathomIcon.svelte`) so that the',
  '// global icon stroke rule in `theme.css` gives them the same optical weight',
  '// as every Lucide component. The same artwork is in assets/icons/*.svg.',
  'export const FATHOM_ICONS = {',
];
for (const id of Object.keys(manifest.icons)) ts.push(`  '${id}': '${id}',`);
ts.push('} as const;', '', 'export type FathomIconName = keyof typeof FATHOM_ICONS;', '');
ts.push('export const FATHOM_ICON_BODY: Record<FathomIconName, string> = {');
for (const id of Object.keys(manifest.icons)) {
  ts.push(`  '${id}': ${JSON.stringify(resolved.get(id).inline)},`);
}
ts.push('};', '');
ts.push('export const FATHOM_ICON_FILLED: ReadonlySet<FathomIconName> = new Set([');
for (const id of Object.keys(manifest.icons)) if (resolved.get(id).fill) ts.push(`  '${id}',`);
ts.push(']);', '');
fs.writeFileSync(path.join(root, 'desktop', 'src', 'lib', 'icons.generated.ts'), ts.join('\n'));

const java = [
  'package net.fathom.optimizations.generated;',
  '',
  'import net.fathom.optimizations.core.ui.render.Icon;',
  '',
  '/** Generated from assets/icons/*.svg via assets/icons/manifest.json. Do not edit. */',
  'public final class SharedIconPaths {',
  '    private SharedIconPaths() {}',
  '',
  '    public static String path(Icon icon) {',
  '        switch (icon) {'
];
for (const [name, id] of Object.entries(manifest.java)) {
  const value = resolved.get(id);
  if (!value) throw new Error(`Unknown shared icon id ${id}`);
  const escaped = value.path.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  java.push(`            case ${name}: return "${escaped}";`);
}
java.push('            default: return "";', '        }', '    }', '', '    public static boolean filled(Icon icon) {',
  '        return icon == Icon.STAR_FILLED;', '    }', '}', '');
const javaRoot = path.join(root, 'minecraft', 'fathom-optimizations', 'src', 'main', 'java',
  'net', 'fathom', 'optimizations', 'generated');
fs.mkdirSync(javaRoot, { recursive: true });
fs.writeFileSync(path.join(javaRoot, 'SharedIconPaths.java'), java.join('\n'));

console.log(`Generated ${resolved.size} shared SVG icons and both runtime maps.`);
