package net.fathom.optimizations.generated;

/**
 * The backgrounds the game's menus can show. Generated from
 * assets/backgrounds/manifest.json by tools/generate-backgrounds.mjs. Do not edit.
 *
 * <p>Parallel arrays, one entry per background. {@code STILL} is a classpath
 * resource under {@code /assets/fathom-optimizations/textures/menu/};
 * {@code THUMBNAIL} is the 384x216 picker image; {@code VIDEO} names a
 * frame sequence there, or is null for a still.
 */
public final class BackgroundCatalog {
    private BackgroundCatalog() {}

    public static final String DEFAULT_ID = "bg_5rain";

    public static final String[] IDS = {
        "bg_6aurora",
        "bg_1",
        "bg_6clear",
        "bg_3",
        "bg_6midnight",
        "bg_5noraintickunfrozen",
        "bg_5rain",
        "bg_5noraintickfrozen",
        "bg_4",
        "bg_2",
        "bg_6tokyo",
    };

    public static final String[] LABELS = {
        "Aurora",
        "Cherry Grove",
        "Metropolis",
        "Midnight Coast",
        "Midnight Metropolis",
        "Rainforest Drift",
        "Rainforest Storm",
        "Rainforest Twilight",
        "Savanna",
        "Snowfall",
        "Tokyo Tower",
    };

    public static final String[] STILL = {
        "scenes/bg_6aurora.jpg",
        "scenes/bg_1.jpg",
        "scenes/bg_6clear.jpg",
        "scenes/bg_3.jpg",
        "scenes/bg_6midnight.jpg",
        "scenes/bg_5noraintickunfrozen.jpg",
        "main-menu-bg.png",
        "scenes/bg_5noraintickfrozen.jpg",
        "scenes/bg_4.jpg",
        "scenes/bg_2.jpg",
        "scenes/bg_6tokyo.jpg",
    };

    public static final String[] THUMBNAIL = {
        "thumbs/bg_6aurora.jpg",
        "thumbs/bg_1.jpg",
        "thumbs/bg_6clear.jpg",
        "thumbs/bg_3.jpg",
        "thumbs/bg_6midnight.jpg",
        "thumbs/bg_5noraintickunfrozen.jpg",
        "thumbs/bg_5rain.jpg",
        "thumbs/bg_5noraintickfrozen.jpg",
        "thumbs/bg_4.jpg",
        "thumbs/bg_2.jpg",
        "thumbs/bg_6tokyo.jpg",
    };

    public static final String[] VIDEO = {
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
    };

    public static final String[] CREDITS = {
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in Tideworn by milkshake_mp3 (CurseForge); see ATTRIBUTION.md",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in vanilla Minecraft by the Fathom project owner",
        "Recorded in Tokyo Inspired City by CoinCoffer (CurseForge); see ATTRIBUTION.md",
    };

    /** The glow colour while each background shows, 0xRRGGBB. */
    public static final int[] ACCENT = {
        0x7B9FD5,
        0xCF81A8,
        0xC2938E,
        0x95BBB4,
        0xC48D90,
        0x7F9CD2,
        0x8DADC3,
        0x809BD0,
        0xE7CE6A,
        0x96BBAF,
        0x8ABBC7,
    };
}
