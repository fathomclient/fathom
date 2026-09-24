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

export default mount(windowLabel === 'console' ? Console : App, { target });
