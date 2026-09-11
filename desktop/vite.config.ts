import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
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
