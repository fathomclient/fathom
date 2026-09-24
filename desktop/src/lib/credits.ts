/*
 * Creator credits for the gameplay footage recorded in community worlds.
 *
 * The launcher no longer offers that footage as a background, but it is still
 * in the repository and the in-game main menu still plays it, so the credit
 * stays in Settings → Appearance. ATTRIBUTION.md is the full record; keep the
 * two in step. The launcher's own photograph is credited from
 * `backgrounds.ts`.
 *
 * Links are page ids, not URLs. The WebView cannot open a URL, only the pages
 * `crates/launcher-desktop/src/pages.rs` lists.
 */

export interface WorldCredit {
  world: string;
  creator: string;
  worldPage: string;
  creatorPage: string;
}

export const WORLD_CREDITS: WorldCredit[] = [
  {
    world: 'Tideworn - 8 sqkm Forgotten Flooded City',
    creator: 'milkshake_mp3',
    worldPage: 'credit-tideworn',
    creatorPage: 'credit-milkshake-mp3',
  },
  {
    world: 'Tokyo Inspired City',
    creator: 'CoinCoffer',
    worldPage: 'credit-tokyo-inspired-city',
    creatorPage: 'credit-coincoffer',
  },
];
