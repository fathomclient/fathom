/**
 * The compositor's reference scene, built from the launcher's tokens and
 * control styles. Every box is absolutely placed at the logical position the
 * game's RendererProofScreen uses, so the two can be screenshotted at the same
 * size and compared pixel for pixel. See renderer.html.
 */
import '../src/lib/theme.generated.css';
import '../src/lib/theme.css';
import '../src/lib/controls.css';
import { FATHOM_ICON_BODY } from '../src/lib/icons.generated';

const params = new URLSearchParams(location.search);
const background = params.get('bg') ?? '/backgrounds/bg_5rain.jpg';

const scene = document.getElementById('scene') as HTMLDivElement;
document.body.style.margin = '0';
document.body.style.overflow = 'hidden';
document.body.style.background = '#071015';
scene.style.cssText =
  `position:fixed;inset:0;background:url(${background}) center/cover no-repeat;` +
  'font-family:var(--type-family);color:var(--color-text);';

function box(x: number, y: number, w: number, h: number, css = '', parent: HTMLElement = scene): HTMLDivElement {
  const el = document.createElement('div');
  el.style.cssText = `position:absolute;left:${x}px;top:${y}px;width:${w}px;height:${h}px;box-sizing:border-box;${css}`;
  parent.appendChild(el);
  return el;
}

/** A line of text with its line box's top at y. */
function text(
  x: number, y: number, value: string, size: number, weight: number, lineHeight: number,
  colour: string, tracking = '0', family = 'var(--type-family)', parent: HTMLElement = scene,
): HTMLDivElement {
  const el = document.createElement('div');
  el.textContent = value;
  el.style.cssText =
    `position:absolute;left:${x}px;top:${y}px;white-space:nowrap;font-family:${family};` +
    `font-size:${size}px;font-weight:${weight};line-height:${lineHeight}px;letter-spacing:${tracking};color:${colour};`;
  parent.appendChild(el);
  return el;
}

function icon(x: number, y: number, size: number, name: keyof typeof FATHOM_ICON_BODY, colour: string,
    parent: HTMLElement = scene): HTMLDivElement {
  const holder = box(x, y, size, size, `color:${colour};`, parent);
  holder.innerHTML =
    `<svg class="lucide fathom-icon" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" ` +
    `stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">` +
    `${FATHOM_ICON_BODY[name]}</svg>`;
  return holder;
}

const SECONDARY = 'var(--color-text-secondary)';
const PRIMARY = 'var(--color-text)';
const TERTIARY = 'var(--color-text-tertiary)';

// --- A: a standard pane --------------------------------------------------------
box(40, 40, 400, 300, '--surface-radius:18px;', scene).classList.add('material-standard');
text(64, 64, 'current installation', 12, 500, 16, SECONDARY, '0.01em');
text(64, 86, 'minecraft 1.8.9', 18, 600, 24, PRIMARY, '-0.012em');
text(64, 116, 'the quick brown fox jumps over the lazy dog', 14, 400, 21, SECONDARY, '-0.003em');
text(64, 140, '1.8.9 · legacy fabric · 11 mods', 12, 400, 16, TERTIARY, '0.005em');
const play = box(64, 176, 168, 44,
  'border-radius:12px;background:var(--control-prominent);display:flex;align-items:center;justify-content:center;');
text(0, 0, 'play', 14, 600, 44, 'var(--color-prominent-text)', '-0.005em', undefined, play).style.cssText +=
  'position:static;';
const settings = box(244, 176, 104, 44, '');
settings.className = 'ui-capsule large';
settings.style.cssText += 'position:absolute;left:244px;top:176px;width:104px;height:44px;padding:0;';
settings.textContent = 'settings';
const iconButton = box(360, 180, 36, 36, 'display:grid;place-items:center;border-radius:12px;');
icon(0, 0, 16, 'settings', SECONDARY, iconButton).style.cssText += 'position:static;';
box(64, 240, 352, 1, 'background:var(--control-hairline);');
text(64, 256, 'client', 12, 400, 16, SECONDARY);
text(64, 274, 'fathom', 13, 500, 18, PRIMARY);
box(170, 256, 1, 36, 'background:var(--control-hairline);');
text(184, 256, 'preset', 12, 400, 16, SECONDARY);
text(184, 274, 'balanced', 13, 500, 18, PRIMARY);
box(290, 256, 1, 36, 'background:var(--control-hairline);');
text(304, 256, 'modules', 12, 400, 16, SECONDARY);
text(304, 274, '11 active', 13, 500, 18, PRIMARY);

// --- B: a dense card with a clipped, scrolled list -----------------------------
box(40, 370, 400, 310, '--surface-radius:18px;').classList.add('material-dense');
const viewport = box(40, 378, 400, 294, 'overflow:hidden;');
const names = ['keystrokes', 'armour status', 'coordinates', 'crosshair', 'fps', 'ping', 'potions', 'toggle sprint'];
names.forEach((name, index) => {
  const top = index * 56 - 20; // scrolled 20
  if (index > 0) box(16, top, 368, 1, 'background:var(--control-hairline);', viewport);
  const mark = box(16, top + 12, 32, 32,
    'border-radius:10px;background:var(--control-fill-selected);box-shadow:var(--control-rim);' +
    'display:grid;place-items:center;font-size:14px;font-weight:600;line-height:32px;', viewport);
  mark.textContent = name[0];
  text(64, top + 8, name, 14, 600, 19, PRIMARY, '-0.012em', undefined, viewport);
  text(64, top + 29, `module ${index + 1} of 8`, 13, 400, 19, SECONDARY, '0', undefined, viewport);
});
box(433, 382, 4, 200, 'border-radius:2px;background:var(--control-scrollbar);');

// --- C: controls -------------------------------------------------------------------
// Segmented: three options, the middle selected. Widths come from the text.
const track = box(480, 40, 0, 36,
  'display:inline-flex;width:auto;padding:2px;border-radius:12px;background:var(--control-fill-faint);' +
  'box-shadow:var(--control-rim);align-items:center;');
['low', 'balanced', 'high'].forEach((label, index) => {
  const segment = document.createElement('div');
  segment.textContent = label;
  segment.style.cssText =
    'height:32px;padding:0 16px;border-radius:10px;font-size:13px;font-weight:500;line-height:32px;' +
    `color:${index === 1 ? PRIMARY : SECONDARY};${index === 1 ? 'background:var(--control-fill-selected);' : ''}`;
  track.appendChild(segment);
});

// Switches: on, then off.
function toggle(x: number, y: number, on: boolean) {
  const sw = box(x, y, 32, 18,
    `border-radius:999px;background:var(${on ? '--control-switch-track-on' : '--control-switch-track'});` +
    `box-shadow:var(${on ? '--control-rim-strong' : '--control-rim'});`);
  box(on ? 16 : 2, 2, 14, 14,
    `border-radius:999px;background:var(${on ? '--control-switch-knob-on' : '--control-switch-knob'});` +
    'box-shadow:var(--shadow-knob);', sw);
}
toggle(480, 92, true);
toggle(528, 92, false);

// Pills.
function pill(x: number, y: number, w: number, label: string) {
  const el = box(x, y, w, 24,
    'border-radius:999px;background:var(--control-fill);box-shadow:var(--control-rim);' +
    'display:flex;align-items:center;justify-content:center;');
  text(0, 0, label, 12, 500, 24, PRIMARY, '0.01em', undefined, el).style.cssText += 'position:static;';
}
pill(576, 89, 60, 'active');
pill(644, 89, 52, 'beta');

// Icon + label rows: selected, hovered, at rest.
const rows: [keyof typeof FATHOM_ICON_BODY, string, string, string][] = [
  ['home', 'home', 'var(--control-fill-selected)', PRIMARY],
  ['mods', 'mods', 'var(--control-fill)', PRIMARY],
  ['performance', 'performance', 'transparent', SECONDARY],
];
rows.forEach(([name, label, fill, ink], index) => {
  const y = 130 + index * 40;
  box(480, y, 220, 36, `border-radius:10px;background:${fill};`);
  icon(488, y + 8, 20, name, ink);
  text(520, y + 8, label, 14, 400, 20, ink);
});

// The type ramp.
text(480, 260, 'caption 12 · the quick brown fox', 12, 400, 16, SECONDARY, '0.005em');
text(480, 280, 'secondary 13 · the quick brown fox', 13, 400, 19, SECONDARY);
text(480, 303, 'body 14 · the quick brown fox', 14, 400, 21, PRIMARY, '-0.003em');
text(480, 328, 'button 14 · the quick brown fox', 14, 500, 21, PRIMARY, '-0.005em');
text(480, 352, 'heading 18 · the quick brown fox', 18, 600, 23, PRIMARY, '-0.012em');
text(480, 380, 'title 28 · fathom', 28, 600, 31, PRIMARY, '-0.022em');
text(480, 418, '144 fps · 6.94 ms · 0123456789', 13, 400, 18, SECONDARY, '0', "'Geist Mono', monospace");

// --- D: field, dropdown, a card under a popover, and the 8x inset ----------------
const field = box(840, 40, 400, 36,
  'border-radius:12px;background:var(--control-fill-sunken);box-shadow:var(--control-rim);');
text(12, 7, 'search modules', 14, 400, 22, TERTIARY, '0', undefined, field);

const dropdown = box(840, 92, 180, 36,
  'border-radius:12px;background:var(--control-fill);box-shadow:var(--control-rim);');
text(16, 8, 'balanced', 13, 500, 20, PRIMARY, '-0.005em', undefined, dropdown);
icon(152, 10, 16, 'chevron-down', SECONDARY, dropdown);

// The card is in the DOM after the popover; z-index keeps the popover above.
const popover = box(840, 134, 200, 166, '--surface-radius:18px;z-index:2;padding:8px;' +
  'display:flex;flex-direction:column;gap:2px;');
popover.classList.add('material-dense');
['low', 'balanced', 'high', 'ultra'].forEach((label, index) => {
  const item = document.createElement('div');
  item.textContent = label;
  item.style.cssText =
    'height:36px;flex:none;padding:0 12px;border-radius:10px;font-size:14px;line-height:36px;' +
    `color:${index === 1 ? PRIMARY : SECONDARY};${index === 1 ? 'background:var(--control-fill-hover);' : ''}`;
  popover.appendChild(item);
});
const card = box(840, 200, 400, 140, '--surface-radius:18px;z-index:1;');
card.classList.add('material-light');
text(224, 24, 'layered card', 14, 600, 19, PRIMARY, '-0.012em', undefined, card);
text(224, 49, 'drawn after the popover;', 13, 400, 19, SECONDARY, '0', undefined, card);
text(224, 68, 'the popover stays above.', 13, 400, 19, SECONDARY, '0', undefined, card);

box(840, 360, 400, 320, '--surface-radius:18px;').classList.add('material-light');
const lens = box(864, 384, 352, 272, 'overflow:hidden;');
const zoomed = box(0, 0, 60, 48, 'transform:scale(8);transform-origin:0 0;', lens);
const capsule = box(4, 4, 80, 36,
  'border-radius:12px;background:var(--control-fill);box-shadow:var(--control-rim);', zoomed);
text(14, 8, 'Ag', 13, 500, 20, PRIMARY, '0', undefined, capsule);
