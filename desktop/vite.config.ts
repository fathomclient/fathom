import { defineConfig, type Plugin } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { wordmarkLayout } from './src/lib/brand/aperture';

/**
 * The wordmark in `index.html`'s static splash, from the same generated path
 * the interface draws, so the first frame never carries a second copy of the
 * letter geometry.
 */
function splashWordmark(): Plugin {
  // Laid out as `Wordmark.svelte` lays out an 18px ascender at 100%, so the
  // hand-over to `LoadingScreen.svelte` does not move a pixel there.
  const layout = wordmarkLayout(18);
  const svg =
    `<svg viewBox="0 0 ${layout.width / layout.scale} 100" width="${layout.width}" ` +
    `height="${layout.height}" role="img" aria-label="Fathom">` +
    `<path d="${layout.d}" fill="currentColor"/></svg>`;
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
