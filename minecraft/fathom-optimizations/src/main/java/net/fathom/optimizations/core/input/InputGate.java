package net.fathom.optimizations.core.input;

/**
 * The two rules a Fathom screen applies to input before believing it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Minecraft 1.8.9 has one LWJGL event queue and drains it into whichever
 * screen happens to be current at that instant. The hub becomes current in the
 * middle of a tick, from a key handler — so the events queued in the moments
 * before it opened are delivered <em>to the hub</em>, carrying coordinates that
 * meant something on the screen they were aimed at and nothing here. This was
 * observed, not theorised: a press the test driver never sent arrived in the
 * tick the hub opened, landed on the close button, and shut it again.
 *
 * <p>The mouse no longer needs this: Fathom's screens poll the device once per
 * frame and turn presses into edges, and an edge that happened before a screen
 * existed is not an edge that screen sees. The keyboard still arrives on the
 * tick, so this is what guards it — and the repeat window is kept because the
 * rule ("one press, one action") is the same one a screen that goes back to
 * queued input would need.
 *
 * <h2>Why it is here and not in the screen</h2>
 *
 * <p>It is arithmetic on a clock, and arithmetic on a clock can be asserted.
 * Everything in the {@code fabric} half needs a window, a GL context and a
 * world to exercise; this does not, so it lives in {@code core} where the test
 * runners can reach it and a regression fails a build instead of a play
 * session.
 */
public final class InputGate {

    private final long readyAt;
    private final long repeatMillis;
    private long lastPress;

    /**
     * @param now           the moment the screen opened
     * @param settleMillis  how long to ignore input for — the opening animation,
     *                      because nothing is readable before it finishes, so
     *                      nothing in the screen can honestly have been aimed at
     * @param repeatMillis  how close two presses have to be to be one press
     */
    public InputGate(long now, int settleMillis, int repeatMillis) {
        if (settleMillis < 0 || repeatMillis < 0) {
            throw new IllegalArgumentException("a window cannot be negative");
        }
        this.readyAt = now + settleMillis;
        this.repeatMillis = repeatMillis;
        // Far enough back that the first press is never mistaken for a repeat,
        // including when the clock reads zero in a test.
        this.lastPress = now - repeatMillis - 1L;
    }

    /** Whether the screen has been open long enough to own what arrives. */
    public boolean settled(long now) {
        return now >= readyAt;
    }

    /**
     * Whether to act on a press.
     *
     * <p>Records the press when it accepts it, so the next one within the
     * repeat window is refused. Refusing does not extend the window: a burst of
     * duplicates is one action, not silence until the burst stops.
     */
    public boolean press(long now) {
        if (!settled(now)) {
            return false;
        }
        if (now - lastPress < repeatMillis) {
            return false;
        }
        lastPress = now;
        return true;
    }

    /**
     * Whether to act on a keystroke.
     *
     * <p>No repeat window: holding a key is a legitimate way to type, and the
     * screens that care about repeats say so themselves. Only the settling rule
     * applies — the W that was walking you forward when you pressed the hub key
     * should not end up in the search box.
     */
    public boolean key(long now) {
        return settled(now);
    }
}
