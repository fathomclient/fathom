package net.fathom.optimizations.core.visual;

/**
 * Fullbright's and Time Changer's arithmetic, without Minecraft.
 *
 * <h2>Fullbright</h2>
 *
 * <p>Vanilla's brightness slider is a gamma between 0 and 1 that bends the
 * lightmap towards white. Fullbright supplies a larger gamma where the lightmap
 * reads it — the same curve, pushed further — and never writes it to
 * {@code options.txt}: switching the module off, or a crash, leaves the
 * player's own slider exactly where they left it.
 *
 * <h2>Time Changer</h2>
 *
 * <p>Minecraft counts a day in 24,000 ticks starting at six in the morning. The
 * module shows the sky at a fixed hour by answering the client world's sky angle
 * for that tick instead of the server's; the server's time, and everything that
 * depends on it, is unchanged.
 */
public final class Lighting {

    /** Time Changer's presets, in the order they are offered. */
    public static final String[] TIMES = {
        "Dawn", "Morning", "Noon", "Afternoon", "Dusk", "Night", "Midnight", "Custom"
    };

    private static final int[] PRESET_HOURS = {5, 8, 12, 15, 18, 21, 0};
    /** Dusk sits at half past: the sun on the horizon, which is the reason to pick it. */
    private static final int[] PRESET_MINUTES = {0, 0, 0, 0, 30, 0, 0};

    private Lighting() {}

    /** The gamma Fullbright uses for a brightness percentage. Never below vanilla's maximum. */
    public static float gamma(double percent) {
        return (float) Math.max(1.0, percent / 100.0);
    }

    /**
     * Applies vanilla's brightness curve with {@code gamma} to finished ARGB
     * lightmap pixels, for lightmaps built where the gamma could not be supplied.
     */
    public static void brighten(int[] pixels, float gamma) {
        for (int index = 0; index < pixels.length; index++) {
            int argb = pixels[index];
            int red = channel((argb >> 16) & 0xFF, gamma);
            int green = channel((argb >> 8) & 0xFF, gamma);
            int blue = channel(argb & 0xFF, gamma);
            pixels[index] = (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
        }
    }

    private static int channel(int value, float gamma) {
        // The same steps as vanilla's lightmap: a 3% floor, then the gamma bend.
        float lit = value / 255.0F * 0.96F + 0.03F;
        float inverse = 1.0F - lit;
        float bent = 1.0F - inverse * inverse * inverse * inverse;
        lit = lit * (1.0F - gamma) + bent * gamma;
        return Math.round(Math.max(0.0F, Math.min(1.0F, lit)) * 255.0F);
    }

    /** The world tick that shows {@code hour}:{@code minute} on the sky. */
    public static long ticks(int hour, int minute) {
        int clampedHour = Math.max(0, Math.min(23, hour));
        int clampedMinute = Math.max(0, Math.min(59, minute));
        int fromSix = ((clampedHour - 6) % 24 + 24) % 24;
        return fromSix * 1000L + Math.round(clampedMinute * 1000.0 / 60.0);
    }

    /** Ticks in a Minecraft day. */
    public static final long DAY = 24000L;

    /**
     * The world time the client should present while Time Changer is on: the
     * real day number, at the chosen tick of the day. Keeping the day means
     * everything counted in days (moon phase, a resource pack's day ranges, the
     * debug screen's day) goes on following the real world, and only the time
     * of day is the override. It does not drift as the real clock advances.
     */
    public static long visualTimeOfDay(long real, long tickOfDay) {
        return Math.floorDiv(real, DAY) * DAY + tickOfDay;
    }

    /**
     * The tick a Time Changer setting stands for. Runs on every sky-angle query,
     * several times a frame, so it compares without lowercasing copies.
     */
    public static long ticks(String preset, int customHour) {
        if (preset != null) {
            for (int index = 0; index < PRESET_HOURS.length; index++) {
                if (TIMES[index].equalsIgnoreCase(preset)) {
                    return ticks(PRESET_HOURS[index], PRESET_MINUTES[index]);
                }
            }
        }
        return ticks(customHour, 0);
    }
}
