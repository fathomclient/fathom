package net.fathom.optimizations.core.ui.anim;

/**
 * The one clock the interface animates on.
 *
 * <h2>Why there is exactly one</h2>
 *
 * <p>Every animation in Fathom's interface reads {@link #seconds()} and
 * {@link #delta()} from the same instance, advanced once per rendered frame.
 * That is what makes a hover fade, a panel opening and a toggle sliding agree
 * about how much time has passed — and it is what makes them all stop when
 * nothing is being drawn.
 *
 * <h2>Monotonic, and never ticks</h2>
 *
 * <p>{@code System.nanoTime}, not {@code currentTimeMillis}: the wall clock can
 * jump backwards when the machine syncs its time, and an animation that sees
 * negative elapsed time snaps. And not the game's tick counter, because 20 Hz
 * is not enough resolution to animate anything and a lagging server should not
 * change how fast a menu opens.
 *
 * <h2>Elapsed time and bounded deltas</h2>
 *
 * <p>A chunk load, a garbage collection or a dragged window can put a second or
 * more between two frames. Scrollers receive a bounded delta so a wheel glide
 * cannot jump. Animations sample the unclamped monotonic elapsed time, however:
 * their result depends on time, never on how many frames happened to render.
 */
public final class Clock {

    /** The largest step any animation will ever be given, in seconds. */
    public static final float MAX_DELTA = 0.10f;

    private long lastNanos;
    private long startNanos;
    private float delta;
    private float elapsed;
    private long frames;

    /** A clock that starts now. */
    public Clock(long nowNanos) {
        this.startNanos = nowNanos;
        this.lastNanos = nowNanos;
    }

    /**
     * Advance to this frame.
     *
     * <p>Call once per frame, before anything reads the clock. Returns the
     * delta so a caller that wants it does not have to ask twice.
     */
    public float advance(long nowNanos) {
        long difference = nowNanos - lastNanos;
        lastNanos = nowNanos;
        if (difference <= 0L) {
            // Two frames inside one nanosecond, or a clock that did not move.
            // Zero is right: nothing should animate by a negative amount.
            delta = 0f;
            return 0f;
        }
        float seconds = difference / 1_000_000_000f;
        delta = seconds > MAX_DELTA ? MAX_DELTA : seconds;
        elapsed += seconds;
        frames++;
        return delta;
    }

    /**
     * Restart, discarding the gap since the last frame.
     *
     * <p>For opening a screen. Without this the first frame after a menu opens
     * carries however long the menu was closed for, and every animation on it
     * starts finished.
     *
     * <p>It closes the gap by moving the <em>last frame</em> forward, and
     * deliberately leaves {@link #seconds()} alone. Every live {@link Motion}
     * holds a start timestamp on that timeline; rewinding it under them would
     * put their start in the future, where progress reads as zero and the
     * interface stops mid-animation until real time caught up. Elapsed time
     * only ever moves forward.
     */
    public void restart(long nowNanos) {
        startNanos = nowNanos;
        lastNanos = nowNanos;
        delta = 0f;
        frames = 0L;
    }

    /** Seconds since the last frame, clamped. */
    public float delta() {
        return delta;
    }

    /** Monotonic elapsed seconds since the clock started, never frame-clamped. */
    public float seconds() {
        return elapsed;
    }

    /** Frames advanced since the clock started. For measurement, not animation. */
    public long frames() {
        return frames;
    }

    /** Real nanoseconds since the clock started, unclamped. For measurement. */
    public long elapsedNanos(long nowNanos) {
        return nowNanos - startNanos;
    }
}
