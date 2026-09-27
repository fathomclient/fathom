/**
 * The entry point, for both windows.
 *
 * Fathom has two: the launcher itself, and the game console that appears while
 * Minecraft runs. They are one bundle under two labels rather than two builds —
 * one set of styles, one set of tokens, one place to fix a bug — so the label
 * is what decides which root component gets mounted.
 *
 * Branching here rather than inside `App` keeps the console window from
 * mounting the shell at all: no background video decoding behind a log, no
 * navigation rail, no polling for a launch state the console does not draw.
 */
import { mount } from 'svelte';

import App from './App.svelte';
import Console from './routes/Console.svelte';
import { windowLabel } from './lib/api';
import { restoreAppearanceModes } from './lib/appearance';

const target = document.getElementById('app');
if (!target) throw new Error('#app is missing from index.html');

// Both windows honour the glass accessibility modes. The main window stays
// hidden until its shell data arrives, which takes longer than this read, so
// it is never seen with the wrong material.
void restoreAppearanceModes();

// One device pixel, in CSS pixels. A `1px` hairline at 125% or 150% display
// scaling is 1.25 or 1.5 device pixels, which the compositor smears across two
// rows. `--hairline` is re-derived whenever the window moves to a display with
// a different scale.
function trackDevicePixel() {
  const ratio = window.devicePixelRatio || 1;
  document.documentElement.style.setProperty('--hairline', `${1 / ratio}px`);
  matchMedia(`(resolution: ${ratio}dppx)`).addEventListener('change', trackDevicePixel, {
    once: true,
  });
}
trackDevicePixel();

export default mount(windowLabel === 'console' ? Console : App, { target });

// The geometry debug overlay (Ctrl+Shift+G): development builds only. Behind
// `import.meta.env.DEV` the import is dead code in a release build, so the
// bundle users run does not contain it at all.
if (import.meta.env.DEV) {
  void import('./lib/GeometryDebug.svelte').then(({ default: GeometryDebug }) => {
    const layer = document.createElement('div');
    document.body.appendChild(layer);
    mount(GeometryDebug, { target: layer });
  });
}
