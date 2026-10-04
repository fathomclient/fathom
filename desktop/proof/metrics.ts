import '../src/lib/theme.generated.css';
import '../src/lib/theme.css';

const SIZE = 100;
const STRINGS = [
  'fathom', 'minecraft 1.8.9', 'the quick brown fox jumps over the lazy dog', 'singleplayer',
  'Te Ta Yo AV WA LT', 'keystrokes', 'performance', 'balanced', '144 fps', 'AVATAR', 'Wavy',
  'settings', 'current installation', '1.8.9 · legacy fabric · 11 mods',
];
const FACES = [
  {name: 'regular', family: "'Geist'", weight: 400},
  {name: 'medium', family: "'Geist'", weight: 500},
  {name: 'semibold', family: "'Geist'", weight: 600},
  {name: 'mono-regular', family: "'Geist Mono'", weight: 400},
  {name: 'mono-medium', family: "'Geist Mono'", weight: 500},
];

async function main() {
  const probe = document.createElement('span');
  probe.style.cssText =
    `position:absolute;left:0;top:0;white-space:pre;font-size:${SIZE}px;letter-spacing:0;` +
    'font-kerning:normal;text-transform:none;font-feature-settings:normal;';
  document.body.appendChild(probe);
  // Load every weight before measuring anything.
  for (const face of FACES) {
    await document.fonts.load(`${face.weight} ${SIZE}px ${face.family}`);
  }
  await document.fonts.ready;
  const result: Record<string, Record<string, number>> = {};
  for (const face of FACES) {
    probe.style.fontFamily = face.family;
    probe.style.fontWeight = String(face.weight);
    const widths: Record<string, number> = {};
    for (const text of STRINGS) {
      probe.textContent = text;
      widths[text] = probe.getBoundingClientRect().width / SIZE;
    }
    result[face.name] = widths;
  }
  (document.getElementById('out') as HTMLElement).textContent = JSON.stringify({size: SIZE, ems: result});
}

void main();
