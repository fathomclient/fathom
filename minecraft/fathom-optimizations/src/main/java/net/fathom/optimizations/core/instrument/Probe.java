package net.fathom.optimizations.core.instrument;

/**
 * Where Fathom's own time goes, measured cheaply enough to trust.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>"Fathom draw: 0.44 ms" is one number for everything Fathom does in a frame,
 * and it is not enough to act on. The question this milestone has to answer is
 * <em>which part</em> — the HUD loop, the module dispatch, the optimization
 * hooks, or the Hub — and a single total cannot be split after the fact.
 *
 * <h2>Why this and not a profiler</h2>
 *
 * <p>A sampling profiler attributes time to methods, which is the right tool for
 * finding a hot method and the wrong one for answering "what does Fathom cost a
 * frame". It also cannot run in a build somebody is playing, and half the
 * questions here are about frames a real session produces.
 *
 * <h2>What it costs</h2>
 *
 * <p>When off: one static boolean read per call site. The JIT removes the branch
 * body entirely, so the cost is not measurable.
 *
 * <p>When on: two {@code System.nanoTime()} calls and one array add per section
 * per frame. On the machine this was written on, {@code nanoTime} is about 25 ns,
 * so four sections cost roughly 250 ns of the frame they are measuring. At 5 ms
 * a frame that is <b>0.005%</b> — small enough that it does not move the number
 * it is reporting, which is the only property that matters for an instrument.
 *
 * <p>It is stated rather than assumed: {@code MicroBench} measures the probe
 * against an empty loop, and the measurement is in
 * {@code docs/performance/profiler.md}.
 *
 * <h2>Why a flat array and not a map</h2>
 *
 * <p>Because a map lookup is comparable to the thing being measured. Sections
 * are an enum, the arrays are indexed by ordinal, and there is no allocation on
 * any path — including the accumulate, which is the one that runs per frame.
 *
 * <h2>Deep instrumentation</h2>
 *
 * <p>Anything that would run per entity, per particle or per glyph is behind
 * {@link #deep()}, which is off unless {@code -Dfathom.profile=deep} is set.
 * Counting every entity considered is genuinely useful and genuinely changes the
 * frame it is counting; it belongs in a profiling run, not in a client somebody
 * is playing on.
 */
public final class Probe {

    /** The parts of a frame Fathom is responsible for. */
    public enum Section {
        /** Drawing the HUD widgets, including the loop that decides what to draw. */
        HUD,
        /** Drawing a Fathom screen — the Hub, the main menu, the editor. */
        SCREEN,
        /** Deciding which modules and widgets are active. */
        MODULES,
        /** Optimization hooks: the frame cap policy, the font metrics cache. */
        HOOKS,
        /** Everything else Fathom does in a frame, for the arithmetic to close. */
        OTHER
    }

    private static final int COUNT = Section.values().length;

    /**
     * Whether to accumulate at all.
     *
     * <p>Not volatile. It is written once when a run starts and read on every
     * call site; a reader that sees a stale value for a few frames records a few
     * frames wrong at the start of a run that discards its first five hundred
     * anyway. Making it volatile would put a memory barrier in the hot path to
     * fix a problem the warm-up already removes.
     */
    private static boolean enabled;

    private static boolean deep;

    private static final long[] NANOS = new long[COUNT];
    private static final long[] CALLS = new long[COUNT];

    /** Per-frame accumulation, moved into the totals once a frame ends. */
    private static final long[] FRAME_NANOS = new long[COUNT];

    private static long framesRecorded;
    private static long worstFrameNanos;

    private Probe() {}

    /** Whether sections are being timed. */
    public static boolean on() {
        return enabled;
    }

    /**
     * Whether per-entity, per-particle and per-glyph counting is on.
     *
     * <p>Set from {@code -Dfathom.profile=deep} at class load. A property rather
     * than a setting, because it is a property of the <em>build being run for a
     * measurement</em>, not a preference somebody expresses in a menu.
     */
    public static boolean deep() {
        return deep;
    }

    static {
        deep = "deep".equalsIgnoreCase(System.getProperty("fathom.profile", ""));
    }

    /** Start accumulating. Clears whatever was there. */
    public static void begin() {
        reset();
        enabled = true;
    }

    /** Stop accumulating. Totals stay readable until the next {@link #begin}. */
    public static void end() {
        enabled = false;
    }

    public static void reset() {
        for (int index = 0; index < COUNT; index++) {
            NANOS[index] = 0L;
            CALLS[index] = 0L;
            FRAME_NANOS[index] = 0L;
        }
        framesRecorded = 0L;
        worstFrameNanos = 0L;
    }

    /**
     * The clock, or zero when nothing is being measured.
     *
     * <p>Returning zero rather than reading the clock is what makes this free
     * when off: the call site is {@code long at = Probe.start();} and the whole
     * of it folds away.
     */
    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    /**
     * Record time against a section.
     *
     * @param at what {@link #start} returned. Zero means the probe was off when
     *     the section began, and nothing is recorded — which is also what
     *     happens if a run starts halfway through a frame.
     */
    public static void stop(Section section, long at) {
        if (at == 0L || !enabled) {
            return;
        }
        int index = section.ordinal();
        long taken = System.nanoTime() - at;
        NANOS[index] += taken;
        CALLS[index] += 1L;
        FRAME_NANOS[index] += taken;
    }

    /**
     * A frame ended.
     *
     * <p>Called from the frame hook. Rolls the per-frame accumulation into the
     * worst-frame figure, which is the one that answers "does Fathom ever cause
     * a stutter" — a mean cannot, and a mean is what every other number here is.
     */
    public static void frameEnded() {
        if (!enabled) {
            return;
        }
        long total = 0L;
        for (int index = 0; index < COUNT; index++) {
            total += FRAME_NANOS[index];
            FRAME_NANOS[index] = 0L;
        }
        framesRecorded++;
        if (total > worstFrameNanos) {
            worstFrameNanos = total;
        }
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    public static long nanos(Section section) {
        return NANOS[section.ordinal()];
    }

    public static long calls(Section section) {
        return CALLS[section.ordinal()];
    }

    public static long frames() {
        return framesRecorded;
    }

    /** Milliseconds per frame in a section, averaged over the run. */
    public static double meanMillis(Section section) {
        if (framesRecorded == 0L) {
            return 0d;
        }
        return NANOS[section.ordinal()] / 1_000_000d / framesRecorded;
    }

    /** Everything Fathom spent, in milliseconds per frame. */
    public static double totalMeanMillis() {
        double total = 0d;
        for (Section section : Section.values()) {
            total += meanMillis(section);
        }
        return total;
    }

    /** The most Fathom cost any single frame in the run, in milliseconds. */
    public static double worstFrameMillis() {
        return worstFrameNanos / 1_000_000d;
    }
}
