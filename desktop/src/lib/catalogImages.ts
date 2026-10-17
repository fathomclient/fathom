/**
 * Icons and screenshots for the mod browser.
 *
 * The WebView never fetches a remote image itself (the content security
 * policy would not let it, and should not): Rust fetches it from an
 * allowlisted host into the on-disk image cache and hands back a `data:` URL.
 * This keeps the answers for the session so scrolling back up does not ask
 * again, bounded so a long browse does not hold every screenshot in memory.
 */
import { catalogImage, type CatalogProvider } from './api';

const LIMIT = 240;
const loaded = new Map<string, Promise<string | null>>();

export function loadImage(provider: CatalogProvider, url: string): Promise<string | null> {
  const key = `${provider}|${url}`;
  const known = loaded.get(key);
  if (known) {
    // Refresh its place so the oldest-used is what goes first.
    loaded.delete(key);
    loaded.set(key, known);
    return known;
  }
  const pending = catalogImage(provider, url).catch(() => null);
  loaded.set(key, pending);
  // A failure is remembered for the session too: an image that would not load
  // a second ago will not load now, and asking again is a wasted request.
  if (loaded.size > LIMIT) {
    const oldest = loaded.keys().next().value;
    if (oldest !== undefined) loaded.delete(oldest);
  }
  return pending;
}
