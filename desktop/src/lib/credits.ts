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

/**
 * The required notice, word for word, in Settings > Appearance > Credits and at
 * the top of ATTRIBUTION.md. Not to be paraphrased, shortened or recased; the
 * game's own short form is in FathomMenuScreen.
 */
export const LEGAL_NOTICE =
  "Fathom is an independent Minecraft mod. Neither Fathom nor The Fathom Project is affiliated with, endorsed by, or otherwise connected to Mojang AB or the Microsoft Corporation. The terms 'Minecraft,' 'Mojang,' 'Microsoft,' and any other product marks, trademarks, logos, word marks, or service marks, along with Minecraft and underlying materials therein are the property of their respective owners. Use of the foregoing does not imply any affiliation with or endorsement by Mojang or Microsoft.";

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
