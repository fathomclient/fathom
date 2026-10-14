package net.fathom.optimizations.core.visual;

import java.util.Locale;

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

    /** The world tick that shows {@code hour}:{@code minute} on the sky. */
    public static long ticks(int hour, int minute) {
        int clampedHour = Math.max(0, Math.min(23, hour));
        int clampedMinute = Math.max(0, Math.min(59, minute));
        int fromSix = ((clampedHour - 6) % 24 + 24) % 24;
        return fromSix * 1000L + Math.round(clampedMinute * 1000.0 / 60.0);
    }

    /** The tick a Time Changer setting stands for. */
    public static long ticks(String preset, int customHour) {
        if (preset != null) {
            for (int index = 0; index < PRESET_HOURS.length; index++) {
                if (TIMES[index].toLowerCase(Locale.ROOT).equals(preset.toLowerCase(Locale.ROOT))) {
                    return ticks(PRESET_HOURS[index], PRESET_MINUTES[index]);
                }
            }
        }
        return ticks(customHour, 0);
    }
}
