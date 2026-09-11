/*
 * Creator credits for background footage.
 *
 * Some backgrounds were recorded in community-made worlds whose creators allow
 * use with credit, so wherever that footage can be chosen, the credit is shown.
 * ATTRIBUTION.md is the full record; keep the two in step.
 *
 * Links are page ids, not URLs. The WebView cannot open a URL, only the pages
 * `crates/launcher-desktop/src/pages.rs` lists.
 */

export interface WorldCredit {
  world: string;
  creator: string;
  worldPage: string;
  creatorPage: string;
  /** Background ids recorded in this world. */
  backgrounds: string[];
}

export const WORLD_CREDITS: WorldCredit[] = [
  {
    world: 'Tideworn - 8 sqkm Forgotten Flooded City',
    creator: 'milkshake_mp3',
    worldPage: 'credit-tideworn',
    creatorPage: 'credit-milkshake-mp3',
    backgrounds: ['bg_5rain', 'bg_5noraintickfrozen', 'bg_5noraintickunfrozen'],
  },
  {
    world: 'Tokyo Inspired City',
    creator: 'CoinCoffer',
    worldPage: 'credit-tokyo-inspired-city',
    creatorPage: 'credit-coincoffer',
    backgrounds: ['bg_6midnight', 'bg_6clear'],
  },
];

/** The credit owed for a background, if it was recorded in someone's world. */
export function creditFor(background: string): WorldCredit | undefined {
  return WORLD_CREDITS.find((credit) => credit.backgrounds.includes(background));
}
