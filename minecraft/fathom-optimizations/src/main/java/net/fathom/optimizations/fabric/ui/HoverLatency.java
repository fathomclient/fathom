package net.fathom.optimizations.fabric.ui;

/**
 * Development probe for the pointer → logical hover → first rendered frame path.
 *
 * <h2>The three timestamps, and where each is taken</h2>
 *
 * <ul>
 *   <li>{@code pointerEventNs} — when the device reported the cursor at a new
 *       position, taken by {@code Pointer.poll} at the top of the frame.
 *   <li>{@code hoverStateChangeNs} — when a control's logical hover flipped,
 *       taken inside the hit test that decided it.
 *   <li>{@code firstRenderedHoverNs} — when the frame carrying that state was
 *       submitted, taken after the renderer flushes.
 * </ul>
 *
 * <h2>Frames waited is the number that matters</h2>
 *
 * <p>Microseconds vary with the machine. What the specification asks for is
 * structural: no more than one rendered frame of avoidable delay. So the frame
 * counter is sampled at the state change and again at the flush, and their
 * difference is recorded. Zero is the correct answer — the frame that
 * discovered the hover is the frame that drew it — and anything above zero is a
 * hover that waited for something, which is the defect this exists to catch.
 *
 * <p>Enable per-sample logging with {@code -Dfathom.hoverInstrumentation=true}.
 * The measurements are kept either way, so automated QA can assert the same
 * path without adding console work to ordinary frames.
 */
public final class HoverLatency {

    private static long pointerEventNs;
    private static long hoverStateChangeNs;
    private static long firstRenderedHoverNs;
    private static long changedOnFrame;
    private static long framesWaited;
    private static long worstFramesWaited;
    private static long samples;
    private static String changedId;
    private static boolean waitingForRender;

    private HoverLatency() {}

    static void stateChanged(String id, long inputNs, long frame) {
        pointerEventNs = inputNs;
        hoverStateChangeNs = System.nanoTime();
        firstRenderedHoverNs = 0L;
        changedOnFrame = frame;
        changedId = id;
        waitingForRender = true;
    }

    /**
     * The frame carrying the pending hover change has been submitted.
     *
     * <p>Called from the renderer's frame end rather than from the control
     * layer, because "rendered" has to mean flushed. Measuring it where the
     * last control finished drawing would report a latency that excludes the
     * submission it is supposed to include.
     */
    public static void frameRendered(long frame) {
        if (!waitingForRender) {
            return;
        }
        firstRenderedHoverNs = System.nanoTime();
        waitingForRender = false;
        framesWaited = Math.max(0L, frame - changedOnFrame);
        worstFramesWaited = Math.max(worstFramesWaited, framesWaited);
        samples++;
        if (Boolean.getBoolean("fathom.hoverInstrumentation")) {
            float toState = pointerEventNs == 0L ? 0f
                    : (hoverStateChangeNs - pointerEventNs) / 1_000f;
            float toRender = pointerEventNs == 0L ? 0f
                    : (firstRenderedHoverNs - pointerEventNs) / 1_000f;
            System.out.println("[Fathom] hover " + changedId
                    + " input→state " + toState + " µs"
                    + ", input→render " + toRender + " µs"
                    + ", frames waited " + framesWaited);
        }
    }

    public static long pointerEventNs() {
        return pointerEventNs;
    }

    public static long hoverStateChangeNs() {
        return hoverStateChangeNs;
    }

    public static long firstRenderedHoverNs() {
        return firstRenderedHoverNs;
    }

    /** Rendered frames between the latest hover change and the frame that showed it. */
    public static long framesWaited() {
        return framesWaited;
    }

    /** The worst that has been seen this session. Zero is the requirement. */
    public static long worstFramesWaited() {
        return worstFramesWaited;
    }

    public static long samples() {
        return samples;
    }

    /** One line for the Diagnostics page and the QA log. */
    public static String summary() {
        if (samples == 0L) {
            return "no hover samples";
        }
        return samples + " hover changes, worst " + worstFramesWaited + " frames waited";
    }

    public static void reset() {
        worstFramesWaited = 0L;
        samples = 0L;
        framesWaited = 0L;
        waitingForRender = false;
    }
}
