/* Generated from assets/backgrounds/manifest.json by tools/generate-backgrounds.mjs. Do not edit. */

export interface BackgroundOption {
  id: string;
  label: string;
  /** `video` loops the asset over its still; `image` is the still. */
  type: 'video' | 'image';
  /** URLs, served from desktop/public. */
  asset: string;
  still: string;
  thumbnail: string;
  /** The asset's native size, in pixels. */
  width: number;
  height: number;
  /** What the game's menus show: its video, its still, or nothing. */
  minecraft: 'video' | 'still' | null;
  /** Plain-text credit shown in Settings; the full record is ATTRIBUTION.md. */
  credit: string;
  /** The glow colour while this background shows: its measured accent, drawn toward the icon's blue. */
  accent: string;
}

export const BACKGROUNDS: BackgroundOption[] = [
  {
    id: "bg_6aurora",
    label: "Aurora",
    type: "video",
    asset: "/backgrounds/bg_6aurora.webm",
    still: "/backgrounds/bg_6aurora.jpg",
    thumbnail: "/backgrounds/thumbs/bg_6aurora.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    accent: "#87b8ee",
  },
  {
    id: "bg_1",
    label: "Cherry Grove",
    type: "video",
    asset: "/backgrounds/bg_1.webm",
    still: "/backgrounds/bg_1.jpg",
    thumbnail: "/backgrounds/thumbs/bg_1.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in vanilla Minecraft",
    accent: "#a9acdc",
  },
  {
    id: "bg_6clear",
    label: "Metropolis",
    type: "video",
    asset: "/backgrounds/bg_6clear.webm",
    still: "/backgrounds/bg_6clear.jpg",
    thumbnail: "/backgrounds/thumbs/bg_6clear.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    accent: "#a3b3d2",
  },
  {
    id: "bg_3",
    label: "Midnight Coast",
    type: "video",
    asset: "/backgrounds/bg_3.webm",
    still: "/backgrounds/bg_3.jpg",
    thumbnail: "/backgrounds/thumbs/bg_3.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in vanilla Minecraft",
    accent: "#91c3e1",
  },
  {
    id: "bg_6midnight",
    label: "Midnight Metropolis",
    type: "video",
    asset: "/backgrounds/bg_6midnight.webm",
    still: "/backgrounds/bg_6midnight.jpg",
    thumbnail: "/backgrounds/thumbs/bg_6midnight.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    accent: "#a4b0d3",
  },
  {
    id: "bg_5noraintickunfrozen",
    label: "Rainforest Drift",
    type: "video",
    asset: "/backgrounds/bg_5noraintickunfrozen.webm",
    still: "/backgrounds/bg_5noraintickunfrozen.jpg",
    thumbnail: "/backgrounds/thumbs/bg_5noraintickunfrozen.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
    accent: "#89b6ed",
  },
  {
    id: "bg_5rain",
    label: "Rainforest Storm",
    type: "video",
    asset: "/backgrounds/bg_5rain.webm",
    still: "/backgrounds/bg_5rain.jpg",
    thumbnail: "/backgrounds/thumbs/bg_5rain.jpg",
    width: 1920,
    height: 1080,
    minecraft: "video",
    credit: "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
    accent: "#8ebde7",
  },
  {
    id: "bg_5noraintickfrozen",
    label: "Rainforest Twilight",
    type: "video",
    asset: "/backgrounds/bg_5noraintickfrozen.webm",
    still: "/backgrounds/bg_5noraintickfrozen.jpg",
    thumbnail: "/backgrounds/thumbs/bg_5noraintickfrozen.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
    accent: "#89b6ec",
  },
  {
    id: "bg_4",
    label: "Savanna",
    type: "video",
    asset: "/backgrounds/bg_4.webm",
    still: "/backgrounds/bg_4.jpg",
    thumbnail: "/backgrounds/thumbs/bg_4.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in vanilla Minecraft",
    accent: "#b2cac3",
  },
  {
    id: "bg_2",
    label: "Snowfall",
    type: "video",
    asset: "/backgrounds/bg_2.webm",
    still: "/backgrounds/bg_2.jpg",
    thumbnail: "/backgrounds/thumbs/bg_2.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in vanilla Minecraft",
    accent: "#92c3df",
  },
  {
    id: "bg_6tokyo",
    label: "Tokyo Tower",
    type: "video",
    asset: "/backgrounds/bg_6tokyo.webm",
    still: "/backgrounds/bg_6tokyo.jpg",
    thumbnail: "/backgrounds/thumbs/bg_6tokyo.jpg",
    width: 1920,
    height: 1080,
    minecraft: "still",
    credit: "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    accent: "#8dc3e9",
  },
];

export const DEFAULT_BACKGROUND = "bg_5rain";
