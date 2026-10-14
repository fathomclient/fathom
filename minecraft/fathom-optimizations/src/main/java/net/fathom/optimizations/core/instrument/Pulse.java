package net.fathom.optimizations.core.instrument;

import java.util.Arrays;

/**
 * The instrument's reading of play: how fast, how even, and when that changed.
 *
 * <p>A {@link Session} is a benchmark: started on purpose, warmed up, stopped, reported.
 * This is the opposite kind of measurement -- always present, never started, and cheap
 * enough that nobody has to decide to pay for it. It feeds the Hub's Performance panel
 * and, when the launcher started the game, the launcher's session view.
 *
 * <h2>What a frame costs</h2>
 *
 * <p>{@link #frame} is called once per frame on the render thread with a timestamp the
 * caller already took. The common path is a subtraction, three stores, a histogram
 * increment and a comparison. Once per counted second it does a little more: closes the
 * second, reads two {@link Memory} numbers, and copies a handful of values for the
 * launcher's writer. Nothing here allocates after construction, and nothing sorts on the
 * per-frame path: the recent percentiles are computed by {@link #read}, at most once per
 * closed second, and only when something is drawing them.
 *
 * <h2>Which frames count</h2>
 *
 * <p>Frames in a world with the window focused. The caller decides; this class only
 * honours the flag. A title screen held at 30 fps, or a window behind another one held
 * at the unfocused cap, is a frame rate the player chose not to look at, and folding it
 * in would make every session's average a statement about menus. An interval longer than
 * {@link #GAP_NANOS} -- a world load, a resource reload -- is a pause in play rather than
 * a frame, and is left out the same way.
 *
 * <h2>Seconds of play, not seconds of wall clock</h2>
 *
 * <p>The timeline advances by counted frame time. A minute in the pause menu with the
 * window unfocused is not a minute of the graph, so the graph is continuous play rather
 * than a strip with flat holes in it, and a test can drive it without a clock.
 *
 * <p>Not thread-safe, except {@link #publish}, which the launcher's writer thread uses
 * through {@link Snapshot}.
 */
public final class Pulse {

    /** An interval this long is a pause in play, not a frame. */
    public static final long GAP_NANOS = 1_000_000_000L;

    /** Frames kept for the recent percentiles: about four seconds at 500 fps. */
    public static final int RECENT = 2048;

    /** The recent percentiles never look further back than this. */
    public static final long RECENT_WINDOW_NANOS = 5_000_000_000L;

    /** Seconds on the in-game timeline: two minutes of play. */
    public static final int SECONDS = 120;

    /** Seconds in one launcher-timeline bucket. */
    public static final int BUCKET_SECONDS = 10;

    /** Buckets on the launcher's timeline: fifteen minutes of play. */
    public static final int BUCKETS = 90;

    /** Marks remembered, oldest dropped first. */
    public static final int MARKS = 16;

    /** A frame this slow is a hitch: three frames at 60 fps, and plainly felt. */
    public static final long HITCH_NANOS = 50_000_000L;

    /** Histogram resolution, 0.1 ms, up to {@link #HISTOGRAM_SLOTS} tenths. */
    private static final long SLOT_NANOS = 100_000L;
    private static final int HISTOGRAM_SLOTS = 1000;

    private static final long SECOND = 1_000_000_000L;

    /** What changed at a point on the timeline. */
    public enum Mark {
        /** Joined a world or server, or changed dimension. */
        WORLD("World"),
        /** A performance setting was changed in the Hub. */
        SETTINGS("Settings");

        private final String label;

        Mark(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where memory figures come from. Separate so a test can say what they are. */
    public interface Memory {
        long used();

        long max();

        Memory RUNTIME = new Memory() {
            @Override
            public long used() {
                java.lang.Runtime runtime = java.lang.Runtime.getRuntime();
                return runtime.totalMemory() - runtime.freeMemory();
            }

            @Override
            public long max() {
                return java.lang.Runtime.getRuntime().maxMemory();
            }
        };
    }

    private final Memory memory;

    // -- the frame before this one -------------------------------------------
    private long lastNanos;

    // -- recent frames ---------------------------------------------------------
    private final long[] recent = new long[RECENT];
    private final long[] scratch = new long[RECENT];
    private int recentCount;
    private int recentNext;

    // -- the second in progress --------------------------------------------------
    private long secondNanos;
    private int secondFrames;
    private long secondWorst;

    // -- the per-second timeline -----------------------------------------------
    private final float[] secondFps = new float[SECONDS];
    private final float[] secondWorstMillis = new float[SECONDS];
    private int secondsCount;
    private int secondsNext;
    /** Seconds of play closed since this pulse was made. */
    private long secondsClosed;

    // -- the ten-second timeline ------------------------------------------------
    private final float[] bucketFps = new float[BUCKETS];
    private final float[] bucketWorstMillis = new float[BUCKETS];
    private int bucketsCount;
    private int bucketsNext;
    private long bucketNanos;
    private int bucketFrames;
    private long bucketWorst;
    private int bucketSeconds;

    // -- the whole session -------------------------------------------------------
    private long sessionFrames;
    private long sessionNanos;
    private long sessionWorst;
    private long hitches;
    private final int[] histogram = new int[HISTOGRAM_SLOTS + 1];

    // -- marks -------------------------------------------------------------------
    private final long[] markSecond = new long[MARKS];
    private final Mark[] markKind = new Mark[MARKS];
    private int marksCount;
    private int marksNext;

    // -- memory, read once a second -------------------------------------------
    private long heapUsed;
    private long heapMax;

    // -- what read() last computed ----------------------------------------------
    private long readAtSecond = -1L;
    private float recentP95;
    private float recentP99;
    private int recentSamples;

    // -- the copy handed to another thread ------------------------------------
    private final Snapshot published = new Snapshot();
    private final Object publishLock = new Object();

    public Pulse() {
        this(Memory.RUNTIME);
    }

    public Pulse(Memory memory) {
        this.memory = memory == null ? Memory.RUNTIME : memory;
    }

    /**
     * One frame began.
     *
     * @param nowNanos the frame's start, from {@link System#nanoTime()}
     * @param counted  whether the interval that just ended was play (see the class note)
     */
    public void frame(long nowNanos, boolean counted) {
        long last = lastNanos;
        lastNanos = nowNanos;
        if (last == 0L) {
            return;
        }
        long interval = nowNanos - last;
        if (!counted || interval <= 0L || interval > GAP_NANOS) {
            return;
        }

        recent[recentNext] = interval;
        recentNext = recentNext + 1 == RECENT ? 0 : recentNext + 1;
        if (recentCount < RECENT) {
            recentCount++;
        }

        sessionFrames++;
        sessionNanos += interval;
        if (interval > sessionWorst) {
            sessionWorst = interval;
        }
        if (interval >= HITCH_NANOS) {
            hitches++;
        }
        // By upper edge: a frame of exactly 10.0 ms is in the slot that reports 10.0.
        long slot = (interval - 1L) / SLOT_NANOS;
        histogram[slot >= HISTOGRAM_SLOTS ? HISTOGRAM_SLOTS : (int) slot]++;

        secondFrames++;
        secondNanos += interval;
        if (interval > secondWorst) {
            secondWorst = interval;
        }
        if (secondNanos >= SECOND) {
            closeSecond();
        }
    }

    private void closeSecond() {
        float fps = (float) (secondFrames * (double) SECOND / secondNanos);
        float worst = secondWorst / 1_000_000f;
        secondFps[secondsNext] = fps;
        secondWorstMillis[secondsNext] = worst;
        secondsNext = secondsNext + 1 == SECONDS ? 0 : secondsNext + 1;
        if (secondsCount < SECONDS) {
            secondsCount++;
        }
        secondsClosed++;

        bucketFrames += secondFrames;
        bucketNanos += secondNanos;
        if (secondWorst > bucketWorst) {
            bucketWorst = secondWorst;
        }
        bucketSeconds++;
        if (bucketSeconds >= BUCKET_SECONDS) {
            bucketFps[bucketsNext] = (float) (bucketFrames * (double) SECOND / bucketNanos);
            bucketWorstMillis[bucketsNext] = bucketWorst / 1_000_000f;
            bucketsNext = bucketsNext + 1 == BUCKETS ? 0 : bucketsNext + 1;
            if (bucketsCount < BUCKETS) {
                bucketsCount++;
            }
            bucketFrames = 0;
            bucketNanos = 0L;
            bucketWorst = 0L;
            bucketSeconds = 0;
        }

        secondFrames = 0;
        secondNanos = 0L;
        secondWorst = 0L;

        heapUsed = memory.used();
        heapMax = memory.max();
        publish();
    }

    /**
     * Note that something changed at this point in play.
     *
     * <p>The timeline draws it as a tick, so a drop or a rise can be read against what
     * caused it. A second mark of the same kind in the same second replaces nothing.
     */
    public void mark(Mark kind) {
        if (kind == null) {
            return;
        }
        int newest = marksNext == 0 ? MARKS - 1 : marksNext - 1;
        if (marksCount > 0 && markSecond[newest] == secondsClosed && markKind[newest] == kind) {
            return;
        }
        markSecond[marksNext] = secondsClosed;
        markKind[marksNext] = kind;
        marksNext = marksNext + 1 == MARKS ? 0 : marksNext + 1;
        if (marksCount < MARKS) {
            marksCount++;
        }
    }

    // -----------------------------------------------------------------------
    // Reading, on the render thread
    // -----------------------------------------------------------------------

    /**
     * Bring the recent percentiles up to date.
     *
     * <p>Sorts at most once per closed second, however often it is called, so a panel
     * that redraws every frame pays for one sort a second. Call it before reading
     * {@link #recentP95Millis()} or {@link #recentP99Millis()}.
     */
    public void read() {
        if (readAtSecond == secondsClosed) {
            return;
        }
        readAtSecond = secondsClosed;
        int count = 0;
        long span = 0L;
        int index = recentNext;
        while (count < recentCount && span < RECENT_WINDOW_NANOS) {
            index = index == 0 ? RECENT - 1 : index - 1;
            long value = recent[index];
            scratch[count++] = value;
            span += value;
        }
        recentSamples = count;
        if (count == 0) {
            recentP95 = 0f;
            recentP99 = 0f;
            return;
        }
        Arrays.sort(scratch, 0, count);
        recentP95 = nearestRank(scratch, count, 0.95) / 1_000_000f;
        recentP99 = nearestRank(scratch, count, 0.99) / 1_000_000f;
    }

    private static long nearestRank(long[] sorted, int count, double fraction) {
        int rank = (int) Math.ceil(fraction * count);
        return sorted[Math.min(Math.max(rank - 1, 0), count - 1)];
    }

    /** Frames per second over the last closed second of play, or 0 before one. */
    public float currentFps() {
        return secondsCount == 0 ? 0f : secondFps[newestSecond()];
    }

    /** 95th-percentile frame time over the recent window, after {@link #read}. */
    public float recentP95Millis() {
        return recentP95;
    }

    /** 99th-percentile frame time over the recent window, after {@link #read}. */
    public float recentP99Millis() {
        return recentP99;
    }

    /** How many frames the recent percentiles were computed over. */
    public int recentSamples() {
        return recentSamples;
    }

    public long heapUsedBytes() {
        return heapUsed;
    }

    public long heapMaxBytes() {
        return heapMax;
    }

    /** Seconds of play recorded so far. */
    public long secondsOfPlay() {
        return secondsClosed;
    }

    /** Seconds on the per-second timeline, at most {@link #SECONDS}. */
    public int timelineLength() {
        return secondsCount;
    }

    /** Frames per second for timeline second {@code index}, 0 the oldest. */
    public float timelineFps(int index) {
        return secondFps[secondSlot(index)];
    }

    /** The slowest frame in timeline second {@code index}, in milliseconds. */
    public float timelineWorstMillis(int index) {
        return secondWorstMillis[secondSlot(index)];
    }

    /** How many marks are remembered. */
    public int markCount() {
        return marksCount;
    }

    /** Mark {@code index}, 0 the oldest. */
    public Mark markKind(int index) {
        return markKind[markSlot(index)];
    }

    /**
     * Where mark {@code index} sits on the per-second timeline: the timeline index of
     * the first second after it, or -1 when it has scrolled off the left edge. A mark
     * in the second still in progress is {@link #timelineLength()}, one past the end.
     */
    public int markTimelineIndex(int index) {
        long second = markSecond[markSlot(index)];
        long firstShown = secondsClosed - secondsCount;
        if (second < firstShown) {
            return -1;
        }
        return (int) (second - firstShown);
    }

    /** Frames counted over the whole session. */
    public long sessionFrames() {
        return sessionFrames;
    }

    /** Average frames per second over the whole session: frames over their time. */
    public float sessionAverageFps() {
        return sessionNanos == 0L ? 0f : (float) (sessionFrames * (double) SECOND / sessionNanos);
    }

    /** A session percentile, to the histogram's 0.1 ms. */
    public float sessionPercentileMillis(double fraction) {
        if (sessionFrames == 0L) {
            return 0f;
        }
        long rank = (long) Math.ceil(fraction * sessionFrames);
        long seen = 0L;
        for (int slot = 0; slot <= HISTOGRAM_SLOTS; slot++) {
            seen += histogram[slot];
            if (seen >= rank) {
                if (slot == HISTOGRAM_SLOTS) {
                    return sessionWorst / 1_000_000f;
                }
                // The slot's upper edge: never reports a frame faster than one that happened.
                return (slot + 1) * (SLOT_NANOS / 1_000_000f);
            }
        }
        return sessionWorst / 1_000_000f;
    }

    public float sessionWorstMillis() {
        return sessionWorst / 1_000_000f;
    }

    /** Frames of {@link #HITCH_NANOS} or longer this session. */
    public long hitches() {
        return hitches;
    }

    private int newestSecond() {
        return secondsNext == 0 ? SECONDS - 1 : secondsNext - 1;
    }

    private int secondSlot(int index) {
        int oldest = secondsCount < SECONDS ? 0 : secondsNext;
        return (oldest + index) % SECONDS;
    }

    private int markSlot(int index) {
        int oldest = marksCount < MARKS ? 0 : marksNext;
        return (oldest + index) % MARKS;
    }

    // -----------------------------------------------------------------------
    // Handing a copy to another thread
    // -----------------------------------------------------------------------

    /**
     * Copy the session's numbers into the shared snapshot.
     *
     * <p>Called on the render thread when a second closes. A short copy into arrays
     * allocated once, under a lock nobody else holds for longer than the same copy.
     */
    private void publish() {
        synchronized (publishLock) {
            Snapshot out = published;
            out.secondsOfPlay = secondsClosed;
            out.frames = sessionFrames;
            out.currentFps = currentFps();
            out.averageFps = sessionAverageFps();
            out.p95Millis = sessionPercentileMillis(0.95);
            out.p99Millis = sessionPercentileMillis(0.99);
            out.worstMillis = sessionWorstMillis();
            out.hitches = hitches;
            out.heapUsed = heapUsed;
            out.heapMax = heapMax;
            int oldest = bucketsCount < BUCKETS ? 0 : bucketsNext;
            for (int index = 0; index < bucketsCount; index++) {
                int slot = (oldest + index) % BUCKETS;
                out.bucketFps[index] = bucketFps[slot];
                out.bucketWorstMillis[index] = bucketWorstMillis[slot];
            }
            out.buckets = bucketsCount;
            long completedBucketSeconds = secondsClosed - bucketSeconds;
            long firstBucketSecond = completedBucketSeconds - (long) bucketsCount * BUCKET_SECONDS;
            int marks = 0;
            for (int index = 0; index < marksCount; index++) {
                long second = markSecond[markSlot(index)];
                if (second < firstBucketSecond) {
                    continue;
                }
                out.markBucket[marks] = (int) ((second - firstBucketSecond) / BUCKET_SECONDS);
                out.markKind[marks] = markKind(index);
                marks++;
            }
            out.marks = marks;
            out.version++;
        }
    }

    /**
     * Copy the last published numbers into {@code into}.
     *
     * @return false when nothing new has been published since {@code into} was filled
     */
    public boolean copyPublished(Snapshot into) {
        synchronized (publishLock) {
            if (into.version == published.version) {
                return false;
            }
            published.copyInto(into);
            return true;
        }
    }

    /** The session's numbers at the last closed second. Owned by whoever made it. */
    public static final class Snapshot {
        long version;
        public long secondsOfPlay;
        public long frames;
        public float currentFps;
        public float averageFps;
        public float p95Millis;
        public float p99Millis;
        public float worstMillis;
        public long hitches;
        public long heapUsed;
        public long heapMax;
        public final float[] bucketFps = new float[BUCKETS];
        public final float[] bucketWorstMillis = new float[BUCKETS];
        public int buckets;
        public final int[] markBucket = new int[MARKS];
        public final Mark[] markKind = new Mark[MARKS];
        public int marks;

        void copyInto(Snapshot into) {
            into.version = version;
            into.secondsOfPlay = secondsOfPlay;
            into.frames = frames;
            into.currentFps = currentFps;
            into.averageFps = averageFps;
            into.p95Millis = p95Millis;
            into.p99Millis = p99Millis;
            into.worstMillis = worstMillis;
            into.hitches = hitches;
            into.heapUsed = heapUsed;
            into.heapMax = heapMax;
            System.arraycopy(bucketFps, 0, into.bucketFps, 0, buckets);
            System.arraycopy(bucketWorstMillis, 0, into.bucketWorstMillis, 0, buckets);
            into.buckets = buckets;
            System.arraycopy(markBucket, 0, into.markBucket, 0, marks);
            System.arraycopy(markKind, 0, into.markKind, 0, marks);
            into.marks = marks;
        }
    }
}
