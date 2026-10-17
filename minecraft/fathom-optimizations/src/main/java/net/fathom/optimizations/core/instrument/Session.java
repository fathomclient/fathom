package net.fathom.optimizations.core.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One measurement run.
 *
 * <p>A session is started, fed frames, and stopped. It holds the frame log, the counters
 * and the memory and GC readings that bracket the run, and produces a {@link Report}.
 *
 * <h2>Warm-up</h2>
 *
 * <p>Frames recorded before {@link #warmupComplete()} are discarded. A JIT-cold Minecraft
 * client is measurably slower than a warm one, and including the first few seconds of any
 * run makes every comparison depend on how long the tester waited before pressing the key.
 * The default warm-up is deliberately generous: {@code performance.md} §5.4 notes that
 * several Minecraft methods exceed HotSpot's {@code HugeMethodLimit} and never compile at
 * all, so "warm" is a longer state to reach here than in most programs.
 *
 * <p>Not thread-safe. The render thread owns it.
 */
public final class Session {

    /**
     * Frames discarded before measurement starts.
     *
     * <p>At 60 FPS this is about eight seconds. Chosen to be clearly past the initial
     * resource load and the first wave of JIT compilation rather than tuned to a
     * measurement, which would be circular.
     */
    public static final int DEFAULT_WARMUP_FRAMES = 500;

    private final String label;
    private final int warmupFrames;
    private final FrameLog frames;
    private final Counters counters = new Counters();
    private final MemorySampler memory = new MemorySampler();
    private final List<MemorySampler.Sample> memorySamples =
            new ArrayList<MemorySampler.Sample>();

    private List<GcLog.Reading> gcAtStart;
    private long startedNanos;
    private long stoppedNanos;
    private long startedWallMillis;
    private int framesSeen;
    private long lastMemorySampleMillis;
    private boolean running;
    private boolean stopped;

    /**
     * What was being run, for the report.
     *
     * <p>Set before {@link #start}. A run with no configuration recorded is a
     * number nobody can place afterwards, which is the same as no number —
     * `Report.caveats` says so rather than letting it be quoted.
     */
    private String configuration = "";
    private String scenario = "";
    private net.fathom.optimizations.core.bench.Environment environment =
            new net.fathom.optimizations.core.bench.Environment();

    public Session(String label) {
        this(label, DEFAULT_WARMUP_FRAMES, FrameLog.DEFAULT_CAPACITY);
    }

    public Session(String label, int warmupFrames, int frameCapacity) {
        this.label = label == null ? "unnamed" : label;
        this.warmupFrames = Math.max(warmupFrames, 0);
        this.frames = new FrameLog(frameCapacity);
    }

    /** Which of the six comparison configurations this run is. */
    public Session configuration(String id) {
        this.configuration = id == null ? "" : id;
        return this;
    }

    /** Which scenario is being played. */
    public Session scenario(String id) {
        this.scenario = id == null ? "" : id;
        return this;
    }

    /** The settings this run happened under. */
    public Session environment(net.fathom.optimizations.core.bench.Environment recorded) {
        if (recorded != null) {
            this.environment = recorded;
        }
        return this;
    }

    public String label() {
        return label;
    }

    public Counters counters() {
        return counters;
    }

    /** Begin. Frames recorded from here are counted towards warm-up. */
    public void start() {
        // The probe runs for exactly the length of a session. Outside one it is
        // off, and off costs a boolean read at each call site.
        Probe.begin();
        if (running) {
            throw new IllegalStateException("session " + label + " is already running");
        }
        if (stopped) {
            throw new IllegalStateException("session " + label + " has already been stopped");
        }
        running = true;
        framesSeen = 0;
        frames.reset();
        counters.reset();
        memorySamples.clear();

        gcAtStart = GcLog.read();
        startedWallMillis = System.currentTimeMillis();
        startedNanos = System.nanoTime();
        lastMemorySampleMillis = 0L;
        sampleMemoryIfDue(startedWallMillis);
    }

    public boolean running() {
        return running;
    }

    /** How many frames still to go before measurement begins. */
    public int warmupRemaining() {
        return Math.max(warmupFrames - framesSeen, 0);
    }

    public boolean warmupComplete() {
        return framesSeen >= warmupFrames;
    }

    /**
     * Record one frame.
     *
     * <p>Called once per frame on the render thread. Everything expensive is behind a
     * time check; the common path is an increment, a comparison and an array store.
     */
    public void frame(long frameNanos) {
        if (!running) {
            return;
        }
        framesSeen++;
        if (framesSeen <= warmupFrames) {
            // Warm-up frames are counted but not measured, and the counters are cleared
            // when warm-up ends so that render statistics describe the measured window
            // rather than the whole run.
            if (framesSeen == warmupFrames) {
                counters.reset();
                startedNanos = System.nanoTime();
                startedWallMillis = System.currentTimeMillis();
                gcAtStart = GcLog.read();
            }
            return;
        }
        frames.record(frameNanos);
        sampleMemoryIfDue(System.currentTimeMillis());
    }

    private void sampleMemoryIfDue(long nowMillis) {
        if (nowMillis - lastMemorySampleMillis < MemorySampler.MINIMUM_INTERVAL_MILLIS) {
            return;
        }
        lastMemorySampleMillis = nowMillis;
        memorySamples.add(memory.sample());
    }

    /**
     * Where Fathom's own time went, in milliseconds per frame.
     *
     * <p>Read out of the probe at the moment the run ends, so the report carries
     * numbers rather than a reference to a static that the next run will clear.
     */
    private java.util.Map<String, Double> sections() {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<String, Double>();
        for (Probe.Section section : Probe.Section.values()) {
            out.put(section.name().toLowerCase(java.util.Locale.ROOT), Double.valueOf(
                    Probe.meanMillis(section)));
        }
        return out;
    }

    /** Finish and produce the report. */
    public Report stop() {
        if (!running) {
            throw new IllegalStateException("session " + label + " is not running");
        }
        stoppedNanos = System.nanoTime();
        sampleMemoryIfDue(Long.MAX_VALUE / 2);
        List<GcLog.Reading> gcAtEnd = GcLog.read();
        running = false;
        stopped = true;
        Probe.end();

        return new Report(
                label,
                configuration,
                scenario,
                environment,
                sections(),
                Probe.worstFrameMillis(),
                startedWallMillis,
                (stoppedNanos - startedNanos) / 1_000_000L,
                frames.statistics(),
                frames.wrapped(),
                counters.read(),
                new ArrayList<MemorySampler.Sample>(memorySamples),
                memory.allocationIsMeasured(),
                memory.allocationMethod(),
                GcLog.between(gcAtStart, gcAtEnd));
    }
}
