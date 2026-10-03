package net.fathom.optimizations.core.opt.frames;

/**
 * A frame limiter that hits its target -- {@code input.frame-limiter-accuracy}.
 *
 * <h2>What it replaces</h2>
 *
 * <p>Vanilla caps the frame rate with LWJGL 2's {@code Display.sync}. That
 * estimates how long {@code Thread.sleep(1)} and {@code Thread.yield()}
 * really take from running averages and decides from those estimates when to
 * stop waiting, reading a clock that is only as fine as LWJGL's timer. A cap
 * reached that way lands on the right average with uneven frames around it.
 *
 * <h2>What this does instead</h2>
 *
 * <ul>
 *   <li>Fixed deadlines, one period apart, on {@code System.nanoTime}. A frame
 *       that finishes early waits for its deadline; the next deadline is a
 *       period after this one, not after now, so small errors do not
 *       accumulate into a drifting rate.
 *   <li>Sleep only while more than {@link #SLEEP_MARGIN_NANOS} remains, then
 *       yield until the deadline. {@code Thread.sleep(1)} can overshoot by
 *       about a millisecond on Windows; the margin keeps that overshoot inside
 *       the wait instead of past the deadline.
 *   <li>A frame that ran a whole period late resynchronises to now rather than
 *       trying to catch up: a burst of unthrottled frames after a hitch is the
 *       unevenness a limiter exists to prevent.
 * </ul>
 *
 * <p>Pure: the clock and the waiting are passed in, so the behaviour is tested
 * without a game or a real clock.
 */
public final class FramePacer {

    /** Sleep while more than this is left; yield for the rest. */
    public static final long SLEEP_MARGIN_NANOS = 1_500_000L;

    /** Time and waiting, injected. */
    public interface Clock {
        long nanoTime();

        void sleepOneMillisecond() throws InterruptedException;

        void yieldThread();
    }

    /** The real thing. */
    public static final Clock SYSTEM = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepOneMillisecond() throws InterruptedException {
            Thread.sleep(1L);
        }

        @Override
        public void yieldThread() {
            Thread.yield();
        }
    };

    private long deadline;
    private int lastFps;

    /** Wait until the frame that just ended has taken its share of a second at {@code fps}. */
    public void sync(int fps, Clock clock) {
        if (fps <= 0) {
            return;
        }
        long period = 1_000_000_000L / fps;
        long now = clock.nanoTime();
        // `deadline` is the previous frame's; this frame's is a period later.
        if (fps != lastFps || deadline == 0L || now - deadline > period) {
            // A new schedule, or a frame a whole period late: release this one
            // now and pace the next from here. Waiting here would add a period
            // of latency to every hitch, on top of the hitch.
            deadline = now;
            lastFps = fps;
            return;
        }
        deadline += period;
        while (true) {
            long remaining = deadline - clock.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            if (remaining > SLEEP_MARGIN_NANOS) {
                try {
                    clock.sleepOneMillisecond();
                } catch (InterruptedException interrupted) {
                    // Keep the interrupt for whoever owns the thread, and stop
                    // waiting: a limiter must never be why a thread will not stop.
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                clock.yieldThread();
            }
        }
    }

    /** Forget the schedule; the next call starts a fresh one. */
    public void reset() {
        deadline = 0L;
        lastFps = 0;
    }
}
