package net.fathom.optimizations.core.hud.value;

/**
 * Clicks per second, over a sliding one-second window.
 *
 * <h2>Why a ring buffer of timestamps and not a counter reset every second</h2>
 *
 * <p>A counter that resets on the second boundary is what most CPS displays do, and it is
 * wrong in a way people notice: click eight times in the last 100 ms of one second and the
 * display shows 8, then 0. What a player wants to know is how fast they are clicking
 * <em>now</em>, which is the number of clicks in the last second measured from this
 * instant, not from the last boundary.
 *
 * <p>The buffer holds {@link #CAPACITY} timestamps and drops the oldest when full. A human
 * cannot exceed that rate, and a macro that does gets an honest reading of the cap rather
 * than an allocation.
 *
 * <p>No Minecraft types, no clock of its own: the caller passes the time. That is what
 * makes the sliding-window behaviour testable without sleeping.
 */
public final class ClickCounter {

    /** One second, in nanoseconds. */
    public static final long WINDOW_NANOS = 1_000_000_000L;

    /** The longest window the CPS module offers, in seconds. */
    public static final int MAX_WINDOW_SECONDS = 5;

    /**
     * The most clicks that can be counted in the window.
     *
     * <p>Chosen well above anything a hand produces, so the cap is never the thing being
     * displayed for a real player, and low enough that the buffer is trivial.
     */
    public static final int CAPACITY = 320;

    private final long[] times = new long[CAPACITY];
    private int head;
    private int count;

    /** Record a click at the given nanosecond timestamp. */
    public void click(long nowNanos) {
        times[head] = nowNanos;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
    }

    /** How many clicks landed in the last second. */
    public int perSecond(long nowNanos) {
        return perSecond(nowNanos, 1);
    }

    /**
     * Clicks per second, averaged over the last {@code seconds} seconds.
     *
     * <p>A longer window is not a longer count: it is the same rate measured
     * over more evidence, so it is the count divided by the window. A five
     * second window that reported five times the number would be reporting
     * clicks, not clicks per second, and the label would be a lie.
     */
    public int perSecond(long nowNanos, int seconds) {
        int window = seconds < 1 ? 1 : (seconds > MAX_WINDOW_SECONDS ? MAX_WINDOW_SECONDS : seconds);
        return Math.round(within(nowNanos, window * WINDOW_NANOS) / (float) window);
    }

    /** How many clicks landed in the last {@code span} nanoseconds. */
    public int within(long nowNanos, long span) {
        long cutoff = nowNanos - span;
        int found = 0;
        for (int step = 0; step < count; step++) {
            // Walk backwards from the newest. Once one is too old every older one
            // is too, so this stops early in the common case of a few clicks.
            int index = Math.floorMod(head - 1 - step, CAPACITY);
            if (times[index] <= cutoff) {
                break;
            }
            found++;
        }
        return found;
    }

    /** Forget everything, for a world change or a settings reset. */
    public void reset() {
        head = 0;
        count = 0;
    }
}
