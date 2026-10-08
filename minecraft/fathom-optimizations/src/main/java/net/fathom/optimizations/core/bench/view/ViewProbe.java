package net.fathom.optimizations.core.bench.view;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Exact-boundary frame timing for the view-direction benchmark.
 *
 * <h2>Cost when not asked for</h2>
 *
 * <p>None. The Mixins that call {@link #enter} and {@link #exit} on vanilla
 * methods are only applied when the process was started with
 * {@code -Dfathom.benchmark=view} (the Mixin plugin decides), and the few calls
 * written into Fathom's own code check {@link #REQUESTED}, a constant the JIT
 * folds away.
 *
 * <h2>Cost when asked for</h2>
 *
 * <p>One {@code System.nanoTime()} on entry and one on exit, an array store,
 * and a thread check. Nothing allocates, nothing logs, nothing reads GL state.
 * Per-frame totals are handed to a {@link Sink} at the next loop head, which
 * copies them; formatting happens once the run is over. The pair's cost is
 * measured by {@link #calibrate} so the report can subtract it, and the
 * benchmark's probes-off arm measures the frame with every section switched
 * off, so the overhead is a number rather than a claim.
 *
 * <h2>Threads</h2>
 *
 * <p>Timing is client-thread only: the integrated server calls some of the same
 * methods ({@code World.tickEntities}, {@code Util.executeTask}) on its own
 * thread, and timing those would mix another thread's work into this one's
 * frame. Counters accept any thread, because chunk rebuilds are queued and
 * finished by worker threads.
 */
public final class ViewProbe {

    /** Whether this process was started for the view benchmark. */
    public static final boolean REQUESTED = requested();

    private static boolean requested() {
        try {
            return "view".equalsIgnoreCase(System.getProperty("fathom.benchmark", "").trim());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Receives each finished frame. Implementations must copy what they keep. */
    public interface Sink {
        /**
         * @param periodNanos loop head to the next loop head: the real frame interval
         * @param total nanoseconds per section, including nested sections
         * @param child nanoseconds of nested sections, so self = total - child
         * @param calls completed entries per section
         * @param counters per-frame counts, indexed by {@link ViewCounter}
         * @param packets per {@link PacketKind}, {@link PacketKind#STRIDE} slots each
         * @param tag what the frame was measuring, as set by {@link #tagNextFrame}
         * @param valid false when the frame should not be counted (focus, screen, transition)
         * @param flags {@link #FLAG_GC} and friends, set by the harness for the frame now ending
         */
        void frame(long periodNanos, long[] total, long[] child, int[] calls, long[] counters,
                long[] packets, int tag, boolean valid, int flags);
    }

    /** A garbage collection finished during this frame. */
    public static final int FLAG_GC = 1;

    private static final int SECTIONS = ViewSection.COUNT;
    private static final int LOOP = ViewSection.LOOP.ordinal();
    private static final int HARNESS = ViewSection.HARNESS.ordinal();
    private static final int TASKS = ViewSection.TASKS.ordinal();

    private static final long[] TOTAL = new long[SECTIONS];
    private static final long[] CHILD = new long[SECTIONS];
    private static final long[] START = new long[SECTIONS];
    private static final int[] CALLS = new int[SECTIONS];
    private static final int[] DEPTH = new int[SECTIONS];
    private static final int[] STACK = new int[64];
    private static int top;

    private static final AtomicLongArray COUNTERS = new AtomicLongArray(ViewCounter.COUNT);
    private static final long[] COUNTER_SNAPSHOT = new long[ViewCounter.COUNT];
    /** Client-thread tallies: plain adds, merged into the counters at the loop head. */
    private static final long[] TALLY = new long[ViewCounter.COUNT];

    /** Per-kind packet figures for the frame in progress, client thread only. */
    private static final long[] PACKETS = new long[PacketKind.COUNT * PacketKind.STRIDE];
    /** Packets queued by the network thread, per kind. */
    private static final AtomicLongArray QUEUED = new AtomicLongArray(PacketKind.COUNT);
    /** The kind of the packet the open task is applying, or -1 if it has not said. */
    private static int currentPacket = -1;
    private static int flags;

    /** Live particles, refreshed by the particle tick hook. Read by the harness. */
    public static volatile int particlesLive;

    private static Thread owner;
    private static Sink sink;
    private static long lastHead;

    /** Whether sections are timed. False in the probes-off arm: only the frame interval is. */
    private static boolean timing;
    private static boolean pendingTiming;
    /** Whether the swap is preceded by {@code glFinish}. The drain arm only. */
    private static boolean drain;
    private static boolean pendingDrain;
    private static int tag = -1;
    private static int pendingTag = -1;
    private static boolean frameValid;

    private ViewProbe() {}

    public static void install(Sink receiver) {
        sink = receiver;
    }

    // -----------------------------------------------------------------------
    // Hot path
    // -----------------------------------------------------------------------

    /** A section begins. Nested re-entry of the same section is timed once, outermost. */
    public static void enter(ViewSection section) {
        if (!timing || Thread.currentThread() != owner) {
            return;
        }
        int index = section.ordinal();
        if (DEPTH[index]++ != 0) {
            return;
        }
        START[index] = System.nanoTime();
        if (top < STACK.length) {
            STACK[top] = index;
        }
        top++;
    }

    /** A section ends. Its time is charged to whichever section was open when it began. */
    public static void exit(ViewSection section) {
        if (!timing || Thread.currentThread() != owner) {
            return;
        }
        int index = section.ordinal();
        if (DEPTH[index] == 0 || --DEPTH[index] != 0) {
            return;
        }
        long elapsed = System.nanoTime() - START[index];
        TOTAL[index] += elapsed;
        CALLS[index]++;
        if (index == TASKS) {
            int kind = currentPacket < 0 ? PacketKind.NOT_A_PACKET.ordinal() : currentPacket;
            PACKETS[kind * PacketKind.STRIDE + PacketKind.NANOS] += elapsed;
            PACKETS[kind * PacketKind.STRIDE + PacketKind.APPLIED]++;
            currentPacket = -1;
        }
        // Normally the top of the stack. A callback cancelled by another Mixin
        // can leave a section above it open; it is dropped here rather than
        // allowed to swallow its parent's time, and the loop head resets it.
        int at = Math.min(top, STACK.length) - 1;
        while (at >= 0 && STACK[at] != index) {
            at--;
        }
        if (at < 0) {
            return;
        }
        top = at;
        if (at > 0) {
            CHILD[STACK[at - 1]] += elapsed;
        }
    }

    /**
     * Add to a counter. Any thread, and in every arm: the settle phase reads
     * chunk activity from these to decide a window may start, and that has to
     * work in the probes-off arm too. Only chunk traffic and layer draws count
     * here, a handful of calls per frame.
     */
    public static void count(ViewCounter counter, long amount) {
        COUNTERS.addAndGet(counter.ordinal(), amount);
    }

    /**
     * Add to a counter from the client thread, in the timed arms only: a plain
     * array add, for things counted hundreds of times a frame (string widths).
     */
    public static void tally(ViewCounter counter) {
        if (timing && Thread.currentThread() == owner) {
            TALLY[counter.ordinal()]++;
        }
    }

    /**
     * A packet handler reached its main-thread half. On the client thread this
     * names the packet the open task is applying; on the network thread it is
     * the packet being queued.
     */
    public static void packet(int kind) {
        if (Thread.currentThread() == owner) {
            if (timing && DEPTH[TASKS] > 0) {
                currentPacket = kind;
            }
        } else {
            QUEUED.incrementAndGet(kind);
        }
    }

    /** A chunk section was marked dirty. Attributed to the packet being applied, if any. */
    public static void invalidated() {
        COUNTERS.incrementAndGet(ViewCounter.INVALIDATIONS.ordinal());
        if (timing && Thread.currentThread() == owner && currentPacket >= 0) {
            PACKETS[currentPacket * PacketKind.STRIDE + PacketKind.INVALIDATIONS]++;
            TALLY[ViewCounter.INVALIDATIONS_BY_PACKET.ordinal()]++;
        }
    }

    /** Flag the frame now ending. Set by the harness in the post-frame window. */
    public static void flagFrame(int flag) {
        flags |= flag;
    }

    /** Record a per-frame level rather than a count (the visible-section list size). */
    public static void level(ViewCounter counter, long value) {
        if (timing && Thread.currentThread() == owner) {
            COUNTERS.set(counter.ordinal(), value);
        }
    }

    /** Whether the drain arm wants {@code glFinish} before this swap. */
    public static boolean draining() {
        return drain && Thread.currentThread() == owner;
    }

    // -----------------------------------------------------------------------
    // Frame boundaries
    // -----------------------------------------------------------------------

    /**
     * The head of {@code runGameLoop}: the previous frame is complete.
     *
     * <p>The interval is taken first, then the previous frame is handed over,
     * then the arrays are cleared. The hand-over happens inside the new frame's
     * interval, so it is timed and charged to the harness, which the report
     * subtracts.
     */
    public static void loopHead() {
        long now = System.nanoTime();
        if (owner == null) {
            owner = Thread.currentThread();
        }
        if (Thread.currentThread() != owner) {
            return;
        }
        if (lastHead != 0L && sink != null) {
            for (int index = 0; index < COUNTER_SNAPSHOT.length; index++) {
                COUNTER_SNAPSHOT[index] = COUNTERS.getAndSet(index, 0L) + TALLY[index];
            }
            for (int kind = 0; kind < PacketKind.COUNT; kind++) {
                PACKETS[kind * PacketKind.STRIDE + PacketKind.QUEUED] = QUEUED.getAndSet(kind, 0L);
            }
            sink.frame(now - lastHead, TOTAL, CHILD, CALLS, COUNTER_SNAPSHOT, PACKETS, tag, frameValid, flags);
        }
        Arrays.fill(TALLY, 0L);
        Arrays.fill(PACKETS, 0L);
        currentPacket = -1;
        flags = 0;
        Arrays.fill(TOTAL, 0L);
        Arrays.fill(CHILD, 0L);
        Arrays.fill(CALLS, 0);
        Arrays.fill(DEPTH, 0);
        top = 0;
        lastHead = now;
        timing = pendingTiming;
        drain = pendingDrain;
        tag = pendingTag;
        frameValid = false;
        // The hand-over above belongs to the harness, not to the frame.
        TOTAL[HARNESS] += System.nanoTime() - now;
        CALLS[HARNESS]++;
        enter(ViewSection.LOOP);
    }

    /** The return of {@code runGameLoop}. */
    public static void loopReturn() {
        exit(ViewSection.LOOP);
    }

    /**
     * Time the harness's own work in the post-frame window. Always on, even in
     * the probes-off arm, because every arm subtracts it.
     */
    public static long harnessStart() {
        return System.nanoTime();
    }

    public static void harnessStop(long started) {
        if (Thread.currentThread() != owner) {
            return;
        }
        long elapsed = System.nanoTime() - started;
        TOTAL[HARNESS] += elapsed;
        CALLS[HARNESS]++;
        if (timing && top > 0) {
            CHILD[STACK[Math.min(top, STACK.length) - 1]] += elapsed;
        }
    }

    /** What the next frame measures. Takes effect at the next loop head. */
    public static void tagNextFrame(int nextTag, boolean nextTiming, boolean nextDrain) {
        pendingTag = nextTag;
        pendingTiming = nextTiming;
        pendingDrain = nextDrain;
    }

    /** Whether the frame now ending counts. Set by the harness during the post-frame window. */
    public static void markFrame(boolean valid) {
        frameValid = valid;
    }

    public static int currentTag() {
        return tag;
    }

    public static boolean timing() {
        return timing;
    }

    // -----------------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------------

    /**
     * Nanoseconds one timed enter/exit pair costs on this machine.
     *
     * <p>Run between frames on the client thread, so the arrays it disturbs are
     * cleared by the next loop head anyway. Best of five, because the question
     * is what the instrumentation costs, not what a context switch costs.
     */
    public static double calibrate(int iterations) {
        boolean wasTiming = timing;
        int savedTop = top;
        long savedTotal = TOTAL[HARNESS];
        int savedCalls = CALLS[HARNESS];
        long savedChild = CHILD[HARNESS];
        int savedDepth = DEPTH[HARNESS];
        timing = true;
        double best = Double.MAX_VALUE;
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                long started = System.nanoTime();
                for (int step = 0; step < iterations; step++) {
                    enter(ViewSection.HARNESS);
                    exit(ViewSection.HARNESS);
                }
                double each = (System.nanoTime() - started) / (double) iterations;
                best = Math.min(best, each);
            }
        } finally {
            timing = wasTiming;
            top = savedTop;
            TOTAL[HARNESS] = savedTotal;
            CALLS[HARNESS] = savedCalls;
            CHILD[HARNESS] = savedChild;
            DEPTH[HARNESS] = savedDepth;
        }
        return best;
    }
}
