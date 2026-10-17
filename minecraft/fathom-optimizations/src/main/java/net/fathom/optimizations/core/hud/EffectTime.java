package net.fathom.optimizations.core.hud;

/**
 * How long a potion effect has left, as the potion HUD prints it.
 *
 * <p>Minutes and seconds, as vanilla's inventory shows. An effect the server
 * gave without an end — a lobby's speed, a map's night vision — is sent with
 * a duration of exactly 32767 and flagged, and vanilla prints it as
 * {@code **:**}; printing the number instead showed a timer of 27:18 that never
 * counted down.
 */
public final class EffectTime {

    /** The duration the protocol uses for "no end". */
    public static final int PERMANENT_TICKS = 32767;

    private EffectTime() {}

    /**
     * Whether an effect has no end. Vanilla's own rule: the flag the effect
     * packet sets when it carries exactly the maximum. A longer duration given
     * by a command is a real timer and counts down like any other.
     */
    public static boolean permanent(int ticks, boolean flagged) {
        return flagged || ticks == PERMANENT_TICKS;
    }

    public static String format(int ticks, boolean flagged) {
        if (permanent(ticks, flagged)) {
            return "**:**";
        }
        int seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    /** For ordering by time left: an effect with no end sorts after every other. */
    public static int sortKey(int ticks, boolean flagged) {
        return permanent(ticks, flagged) ? Integer.MAX_VALUE : ticks;
    }
}
