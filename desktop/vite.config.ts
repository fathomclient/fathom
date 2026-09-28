import { defineConfig, type Plugin } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { GRAPHITE_WORDMARK } from './src/lib/brand/graphiteWordmark';

/**
 * The wordmark in `index.html`'s static splash, from the same generated path
 * the interface draws, so the first frame never carries a second copy of the
 * letter geometry.
 */
function splashWordmark(): Plugin {
  const { d, width, cap } = GRAPHITE_WORDMARK;
  const svg =
    `<svg viewBox="0 0 ${width} ${cap}" role="img" aria-label="Fathom">` +
    `<path d="${d}" fill="currentColor" fill-rule="evenodd"/></svg>`;
  return {
    name: 'fathom-splash-wordmark',
    transformIndexHtml: (html) => html.replace('<!--fathom:wordmark-->', svg),
  };
}

export default defineConfig({
  plugins: [svelte(), splashWordmark()],
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
