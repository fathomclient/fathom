package net.fathom.optimizations.generated;

/**
 * The backgrounds the game's menus can show. Generated from
 * assets/backgrounds/manifest.json by tools/generate-backgrounds.mjs. Do not edit.
 *
 * <p>Parallel arrays, one entry per background. {@code STILL} is a classpath
 * resource under {@code /assets/fathom-optimizations/textures/menu/};
 * {@code VIDEO} names a frame sequence there, or is null for a still.
 */
public final class BackgroundCatalog {
    private BackgroundCatalog() {}

    public static final String DEFAULT_ID = "bg_5rain";

    public static final String[] IDS = {
        "bg_1",
        "bg_6pull_city-overlook",
        "golden-hour",
        "graphite",
        "bg_6clear",
        "bg_3",
        "bg_6midnight",
        "bg_6pull_moon-tower",
        "bg_6pull_neon-grid",
        "bg_5noraintickunfrozen",
        "bg_5rain",
        "bg_5noraintickfrozen",
        "bg_6pull_red-tower",
        "bg_4",
        "bg_2",
        "bg_6pull_storm-panorama",
    };

    public static final String[] LABELS = {
        "Cherry Grove",
        "City Overlook",
        "Golden Hour",
        "Graphite",
        "Metropolis",
        "Midnight Coast",
        "Midnight Metropolis",
        "Moon Tower",
        "Neon Grid",
        "Rainforest Drift",
        "Rainforest Storm",
        "Rainforest Twilight",
        "Red Tower",
        "Savanna",
        "Snowfall",
        "Storm Panorama",
    };

    public static final String[] STILL = {
        "scenes/bg_1.jpg",
        "scenes/bg_6pull_city-overlook.jpg",
        "scenes/golden-hour.jpg",
        "graphite/backdrop-default.png",
        "scenes/bg_6clear.jpg",
        "scenes/bg_3.jpg",
        "scenes/bg_6midnight.jpg",
        "scenes/bg_6pull_moon-tower.jpg",
        "scenes/bg_6pull_neon-grid.jpg",
        "scenes/bg_5noraintickunfrozen.jpg",
        "main-menu-bg.png",
        "scenes/bg_5noraintickfrozen.jpg",
        "scenes/bg_6pull_red-tower.jpg",
        "scenes/bg_4.jpg",
        "scenes/bg_2.jpg",
        "scenes/bg_6pull_storm-panorama.jpg",
    };

    public static final String[] VIDEO = {
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "bg_5rain",
        null,
        null,
        null,
        null,
        null,
    };

    public static final String[] CREDITS = {
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "\"Palm Tree Sunset Water\", credited to showtime at Wallpapers.com; see ATTRIBUTION.md",
        "Fathom's Graphite backdrop; see ATTRIBUTION.md",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    };

    /** The glow colour while each background shows, 0xRRGGBB. */
    public static final int[] ACCENT = {
        0xCF81A8,
        0x8CA4C5,
        0xE7A06A,
        0x88A1C8,
        0xC2938E,
        0x97BAB3,
        0xC58C90,
        0x87A3C9,
        0x86A6CA,
        0x7F9AD2,
        0x8DAFC4,
        0x809AD0,
        0xD57C82,
        0xE7CE6A,
        0x98B9AF,
        0x8CA3C5,
    };
}
