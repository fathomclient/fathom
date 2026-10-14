import { defineConfig, type Plugin } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { BRAND } from './src/lib/brand/brand.generated';
import { MARK_ASPECT, markElements, markViewBox, wordGradient, wordmarkLayout } from './src/lib/brand/orbit';

/**
 * The mark and the wordmark in `index.html`'s static splash, from the same
 * generated paths the interface draws, so the first frame never carries a
 * second copy of the geometry.
 */
function splashLockup(): Plugin {
  // Laid out as `LoadingScreen.svelte` lays out an 18px ascender at 100%, so
  // the hand-over does not move a pixel there.
  const ascender = 18;
  const layout = wordmarkLayout(ascender);
  const markHeight = ascender * BRAND.stackedMark;
  const mark =
    `<svg class="orbit" viewBox="${markViewBox()}" width="${markHeight * MARK_ASPECT}" height="${markHeight}" ` +
    `aria-hidden="true">${markElements('splash', undefined, { halo: true })}</svg>`;
  const word =
    `<svg viewBox="0 0 ${layout.units} 100" width="${layout.width}" ` +
    `height="${layout.height}" role="img" aria-label="Fathom">` +
    `<defs>${wordGradient('splash-word', layout.units)}</defs>` +
    `<path d="${layout.d}" fill="url(#splash-word)"/></svg>`;
  return {
    name: 'fathom-splash-lockup',
    transformIndexHtml: (html) =>
      html.replace('<!--fathom:mark-->', mark).replace('<!--fathom:wordmark-->', word),
  };
}

export default defineConfig({
  plugins: [svelte(), splashLockup()],
  // Tauri serves the built assets from the app itself; a relative base keeps
  // the bundle working regardless of where it is mounted.
  base: './',
  build: {
    target: 'chrome110',
    // The WebView2 runtime is evergreen, so shipping legacy transforms would be
    // dead weight. Sourcemaps stay off in release: they would embed the build
    // machine's paths in a shipped artifact.
    sourcemap: false,
    // Videos are already compressed; inlining anything would only bloat the JS.
    assetsInlineLimit: 4096,
  },
  server: { port: 5173, strictPort: true },
  clearScreen: false,
});
