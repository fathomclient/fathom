import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'assets', 'theme', 'fathom-theme.json');
const theme = JSON.parse(fs.readFileSync(source, 'utf8'));

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
fs.writeFileSync(path.join(root, 'desktop', 'src', 'lib', 'theme.generated.css'), css.join('\n'));

const motionTs = [
  '// Generated from assets/theme/fathom-theme.json. Do not edit.',
  ...Object.entries(theme.motion).map(([name, value]) =>
    `export const ${name.replace(/-/g, '_').toUpperCase()} = ${Number(value)};`),
  ''
];
fs.writeFileSync(path.join(root, 'desktop', 'src', 'lib', 'motion.generated.ts'), motionTs.join('\n'));

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
java.push('}');
java.push('');
fs.writeFileSync(path.join(root, 'minecraft', 'fathom-optimizations', 'src', 'main', 'java',
  'net', 'fathom', 'optimizations', 'generated', 'FathomTheme.java'), java.join('\n'));

console.log('Generated launcher CSS and Minecraft theme constants from assets/theme/fathom-theme.json.');
