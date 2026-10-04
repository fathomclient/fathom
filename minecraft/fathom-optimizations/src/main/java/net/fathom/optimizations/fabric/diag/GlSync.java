package net.fathom.optimizations.fabric.diag;

/**
 * How often Fathom asked the driver a question this window.
 *
 * <h2>Why this is worth counting on its own</h2>
 *
 * <p>Every {@code glGet}/{@code glIsEnabled} is a synchronous read of driver
 * state. On a deferred driver -- which is every modern one -- answering means
 * catching up with whatever commands are still queued, so a batch of them can
 * cost far more than the arithmetic around it and the cost lands on the CPU,
 * not in any GPU timing. It is the classic way a UI layer that is "obviously
 * cheap" takes a frame rate from four figures to three.
 *
 * <p>Fathom takes one such batch through {@code GlStateSnapshot} when either
 * renderer submits a frame. It is correctness machinery -- Fathom borrows the
 * game's GL state and must hand it back. What matters for diagnosis is how many
 * times a frame it runs, and that is a number, not an argument.
 */
public final class GlSync {

    /**
     * Whether to count.
     *
     * <p>{@code static final}, so when the profile is off the JIT removes the
     * body of {@link #captured} and its call sites entirely. A counter that only
     * a diagnostic reads must not appear in a frame nobody is measuring.
     */
    private static final boolean COUNTING = FramePerf.enabled();

    private static long captures;
    private static long queries;
    private static long widthQueries;

    private GlSync() {}

    /**
     * Record one state capture.
     *
     * @param queryCount how many driver queries it made
     */
    public static void captured(int queryCount) {
        if (!COUNTING) {
            return;
        }
        captures++;
        queries += queryCount;
    }

    /**
     * Calls into {@code TextRenderer.getStringWidth} this window.
     *
     * <p>Counted because that method carries two Mixin injection callbacks, and
     * under OptiFine the optimization they exist for is switched off -- so every
     * call pays for two {@code CallbackInfoReturnable} allocations and two
     * virtual calls to reach a pair of early returns. Whether that matters is a
     * question about the call rate, and nobody has measured the call rate. This
     * measures it, and only when the profile is on.
     */
    public static void stringWidthQueried() {
        if (!COUNTING) {
            return;
        }
        widthQueries++;
    }

    public static long widthQueries() {
        return widthQueries;
    }

    public static long captures() {
        return captures;
    }

    public static long queries() {
        return queries;
    }

    public static void reset() {
        captures = 0L;
        queries = 0L;
        widthQueries = 0L;
    }
}
