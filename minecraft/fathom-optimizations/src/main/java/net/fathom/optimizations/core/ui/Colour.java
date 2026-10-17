package net.fathom.optimizations.core.ui;

/**
 * ARGB arithmetic, in one place.
 *
 * <p>Colours are packed ints because that is what every drawing call in this
 * codebase takes and what the design tokens are written as. The operations here
 * are the four the interface actually performs — build one from an opacity,
 * change an opacity, scale an opacity, blend two — and having them in one place
 * is what stops a {@code >>> 24 & 0xFF} appearing in a layout file.
 */
public final class Colour {

    private Colour() {}

    /** An opaque colour from {@code 0xRRGGBB}. */
    public static int rgb(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /** Pack 0-255 channels. Out-of-range channels are clamped. */
    public static int rgba(int red, int green, int blue, int alpha) {
        return (channel(alpha) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    /** Pack an alpha fraction and an RGB triple. */
    public static int argb(float alpha, int rgb) {
        int a = Math.round(clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** The same colour at a different opacity. */
    public static int fade(int colour, float alpha) {
        return argb(alpha, colour & 0xFFFFFF);
    }

    /**
     * Scale a colour's existing opacity.
     *
     * <p>For fading a whole panel in without flattening the opacity each of its
     * parts was designed with.
     */
    public static int scaleAlpha(int colour, float factor) {
        int a = Math.round(alpha(colour) * 255f * clamp01(factor));
        return (a << 24) | (colour & 0xFFFFFF);
    }

    /** Linear blend, for hover and selection states. */
    public static int mix(int from, int to, float amount) {
        float t = clamp01(amount);
        int a = blend((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
        int r = blend((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = blend((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = blend(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static float alpha(int colour) {
        return ((colour >>> 24) & 0xFF) / 255f;
    }

    public static float red(int colour) {
        return ((colour >> 16) & 0xFF) / 255f;
    }

    public static float green(int colour) {
        return ((colour >> 8) & 0xFF) / 255f;
    }

    public static float blue(int colour) {
        return (colour & 0xFF) / 255f;
    }

    /** Whether drawing this would change anything. */
    public static boolean invisible(int colour) {
        return ((colour >>> 24) & 0xFF) == 0;
    }

    private static int channel(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static int blend(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static float clamp01(float value) {
        if (value != value) {
            return 0f;
        }
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
