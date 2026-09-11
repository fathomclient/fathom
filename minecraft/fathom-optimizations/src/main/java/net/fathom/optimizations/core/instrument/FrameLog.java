package net.fathom.optimizations.core.instrument;

/**
 * A fixed-capacity ring of frame durations.
 *
 * <p>Bounded on purpose. A benchmark run at 300 FPS for ten minutes is 180,000 frames;
 * an unbounded list of those would allocate steadily throughout the run, which is
 * precisely the behaviour the allocation-rate metric is trying to observe. A pre-sized
 * {@code long[]} allocates once, at construction, and never again.
 *
 * <p>{@link #record} does no arithmetic beyond a store and an index bump, because it runs
 * once per frame on the render thread and instrumentation that shows up in its own
 * measurements is not instrumentation.
 *
 * <p>Not thread-safe. One frame log belongs to one thread.
 */
public final class FrameLog {

    /**
     * Ten minutes at 500 FPS. Large enough that a normal benchmark never wraps, small
     * enough to be a 2.4 MB array rather than a memory problem of its own.
     */
    public static final int DEFAULT_CAPACITY = 300_000;

    private final long[] nanos;
    private int count;
    private int next;
    private boolean wrapped;

    public FrameLog() {
        this(DEFAULT_CAPACITY);
    }

    public FrameLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.nanos = new long[capacity];
    }

    /** Record one frame's duration. */
    public void record(long frameNanos) {
        if (frameNanos <= 0L) {
            // A zero or negative duration means the clock went backwards or two samples
            // landed in the same tick. Counting it would drag every percentile down with
            // a frame that did not happen.
            return;
        }
        // Set before the store, not after the index wraps: a full ring has not yet lost
        // anything, and `wrapped` means "older samples were overwritten". Reporting it a
        // frame early would put a caveat on a report whose statistics cover the whole run.
        if (count == nanos.length) {
            wrapped = true;
        }
        nanos[next] = frameNanos;
        next++;
        if (next == nanos.length) {
            next = 0;
        }
        if (count < nanos.length) {
            count++;
        }
    }

    public int size() {
        return count;
    }

    public int capacity() {
        return nanos.length;
    }

    /**
     * Whether the ring has overwritten older samples.
     *
     * <p>Worth reporting: statistics over a wrapped log describe the last N frames, not
     * the whole run, and a reader should be told which.
     */
    public boolean wrapped() {
        return wrapped;
    }

    /** A copy of the samples, oldest first. */
    public long[] samples() {
        long[] out = new long[count];
        if (count < nanos.length) {
            System.arraycopy(nanos, 0, out, 0, count);
        } else {
            System.arraycopy(nanos, next, out, 0, nanos.length - next);
            System.arraycopy(nanos, 0, out, nanos.length - next, next);
        }
        return out;
    }

    public FrameStatistics statistics() {
        return FrameStatistics.of(samples());
    }

    public void reset() {
        count = 0;
        next = 0;
        wrapped = false;
    }
}
