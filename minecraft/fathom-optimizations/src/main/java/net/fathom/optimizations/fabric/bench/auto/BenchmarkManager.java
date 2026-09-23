package net.fathom.optimizations.fabric.bench.auto;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.core.bench.auto.BenchmarkPass;
import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;
import net.fathom.optimizations.core.bench.auto.BenchmarkResult;
import net.fathom.optimizations.core.bench.auto.BenchmarkSubsystem;
import net.fathom.optimizations.core.bench.auto.BenchmarkReportWriter;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.FrameLog;
import net.fathom.optimizations.core.instrument.FrameStatistics;
import net.fathom.optimizations.core.instrument.Probe;

/**
 * Runs the passes, one frame at a time.
 *
 * <h2>Why a state machine and not a thread</h2>
 *
 * <p>Everything it touches -- module switches, GL state, the frame clock -- is
 * the render thread's. A benchmark that drove itself from another thread would
 * have to synchronise with the render thread on every transition, and the
 * synchronisation would land inside the frames being measured. Instead this is
 * advanced once per frame from the existing frame hook, which is already on the
 * right thread and already has the frame time in hand.
 *
 * <h2>Warm-up</h2>
 *
 * <p>Every pass discards frames before it measures, for two reasons that need
 * different amounts of time. Switching a subsystem changes what the JIT has
 * compiled and what the driver has cached, which settles in well under a second;
 * a scene that is still streaming chunks does not settle at all, and no fixed
 * delay fixes that. So the warm-up is a fixed floor <em>plus</em> a stability
 * test: it will not start measuring until recent frames stop varying wildly, and
 * it gives up waiting after a ceiling so a genuinely unstable scene still
 * produces a report -- with a note saying it was unstable.
 */
public final class BenchmarkManager {

    /** Frames thrown away immediately after a switch, before anything is judged. */
    private static final int SETTLE_FRAMES = 60;

    /** Minimum warm-up once settled. */
    private static final long WARMUP_NANOS = 5L * 1_000_000_000L;

    /** Longest we will wait for a stable scene before measuring anyway. */
    private static final long WARMUP_CEILING_NANOS = 20L * 1_000_000_000L;

    /** How long each pass measures. */
    private static final long MEASURE_NANOS = 12L * 1_000_000_000L;

    /** Below this many frames a pass is reported but not ranked. */
    private static final int MINIMUM_FRAMES = 200;

    /** Coefficient of variation under which recent frames count as settled. */
    private static final double STABLE_COV = 0.35d;

    /** How many times a pass may be retried after losing focus before giving up. */
    private static final int MAX_RETRIES = 2;

    private enum Phase {
        WAITING_FOR_WORLD,
        SETTLING,
        WARMUP,
        MEASURING,
        DONE
    }

    private final BenchmarkRegistry registry;
    private final List<BenchmarkPass> passes;
    private final List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
    private final List<String> sceneNotes = new ArrayList<String>();
    private final Map<String, Boolean> original;
    private final long startedNanos = System.nanoTime();

    private Phase phase = Phase.WAITING_FOR_WORLD;
    private int passIndex = -1;
    private int settleFrames;
    private long phaseStartNanos;
    private FrameLog log = new FrameLog(200_000);
    private long gcCountAtStart;
    private long gcMillisAtStart;
    private long heapBeforeMb;
    private final double[] recent = new double[60];
    private int recentCursor;
    private boolean finished;

    /**
     * Whether the window kept focus for the pass being measured.
     *
     * <p>This is the fix for the first run's headline. Minecraft caps an
     * unfocused window at 30 fps, so a pass measured while the player had
     * alt-tabbed reported 8.35 ms against a 2.58 ms baseline and the report
     * announced that Fathom cost 69% of the frame. It cost nothing; the window
     * was in the background. A pass that loses focus is now void and re-run,
     * because a benchmark that silently measures the frame cap is worse than
     * one that refuses to finish.
     */
    private final net.fathom.optimizations.fabric.bench.Capture.Focus focus =
            new net.fathom.optimizations.fabric.bench.Capture.Focus();
    private int retries;

    public BenchmarkManager(BenchmarkRegistry registry, List<BenchmarkPass> passes) {
        this.registry = registry;
        this.passes = passes;
        this.original = registry.snapshot();
    }

    /** How many isolated passes run between one baseline measurement and the next. */
    private static final int BASELINE_EVERY = 4;

    /**
     * Build the plan.
     *
     * <h2>Why the baseline repeats</h2>
     *
     * <p>The first run of this harness measured every isolated pass as
     * <em>faster</em> than the minimal baseline -- all sixteen of them. Enabling
     * a subsystem cannot make a frame quicker, so that was not a result about
     * Fathom: it was a monotonic warm-up trend across six minutes, with the
     * baseline measured while the JIT was coldest and every later pass measured
     * after it had settled.
     *
     * <p>So Minimal is re-measured every few passes and each isolated pass is
     * compared against the nearest one in time, which cancels drift locally
     * instead of accumulating it. The spread between those repeats is also the
     * honest noise floor for the run -- a measured number rather than the
     * constant this code used to guess.
     */
    public static List<BenchmarkPass> plan(BenchmarkRegistry registry) {
        List<BenchmarkPass> out = new ArrayList<BenchmarkPass>();
        out.add(BenchmarkPass.normal());
        out.add(BenchmarkPass.minimal());

        // Groups first: a single HUD widget is usually below the noise floor,
        // and the group tells you whether to bother looking at its members.
        for (String group : registry.groups()) {
            List<BenchmarkSubsystem> members = registry.inGroup(group);
            if (members.size() <= 1) {
                continue;
            }
            LinkedHashSet<String> ids = new LinkedHashSet<String>();
            for (BenchmarkSubsystem each : members) {
                ids.add(each.id());
            }
            out.add(BenchmarkPass.isolate("group." + group, "All " + group, ids));
        }

        int sinceBaseline = 0;
        for (BenchmarkSubsystem each : registry.measurable()) {
            LinkedHashSet<String> one = new LinkedHashSet<String>();
            one.add(each.id());
            out.add(BenchmarkPass.isolate(each.id(), each.label(), one));
            if (++sinceBaseline >= BASELINE_EVERY) {
                sinceBaseline = 0;
                out.add(BenchmarkPass.minimal());
            }
        }
        // Both references again at the end. Normal first-and-last is what makes
        // the drift over the whole run a number rather than a suspicion.
        out.add(BenchmarkPass.minimal());
        out.add(BenchmarkPass.normal());
        return out;
    }

    public boolean finished() {
        return finished;
    }

    public int passCount() {
        return passes.size();
    }

    public int currentPass() {
        return Math.max(0, passIndex + 1);
    }

    public String currentLabel() {
        if (passIndex < 0 || passIndex >= passes.size()) {
            return "starting";
        }
        return passes.get(passIndex).label();
    }

    public String phaseLabel() {
        return phase.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    public List<BenchmarkResult> results() {
        return results;
    }

    /**
     * Advance one frame.
     *
     * @param frameNanos what the frame hook measured for the frame just drawn
     */
    public void frame(MinecraftClient client, long frameNanos) {
        if (finished || client == null) {
            return;
        }
        try {
            step(client, frameNanos);
        } catch (Throwable failure) {
            // A benchmark that throws must still put the client back.
            System.out.println("[FATHOM BENCH] aborted: " + failure);
            failure.printStackTrace();
            abort();
        }
    }

    private void step(MinecraftClient client, long frameNanos) {
        if (phase == Phase.WAITING_FOR_WORLD) {
            // Nothing is comparable on a loading screen, and the first pass
            // should not begin while terrain is still arriving.
            if (client.world == null || client.player == null) {
                return;
            }
            beginNextPass(client);
            return;
        }

        if (frameNanos <= 0L) {
            return;
        }
        long now = System.nanoTime();

        switch (phase) {
            case SETTLING:
                settleFrames++;
                focus.sample();
                record(frameNanos);
                if (settleFrames >= SETTLE_FRAMES) {
                    phase = Phase.WARMUP;
                    phaseStartNanos = now;
                }
                return;

            case WARMUP:
                focus.sample();
                record(frameNanos);
                if (!focus.held()) {
                    // Still warming up, so nothing is lost by simply restarting
                    // the wait once the window comes back.
                    restartWarmup(now);
                    return;
                }
                long warmed = now - phaseStartNanos;
                if (warmed < WARMUP_NANOS) {
                    return;
                }
                if (warmed < WARMUP_CEILING_NANOS && !stable()) {
                    return;
                }
                if (warmed >= WARMUP_CEILING_NANOS && !stable()) {
                    sceneNotes.add(passes.get(passIndex).label()
                            + ": scene never settled; its numbers are less comparable than the rest.");
                }
                startMeasuring(now);
                return;

            case MEASURING:
                focus.sample();
                if (!focus.held()) {
                    voidPass(client);
                    return;
                }
                log.record(frameNanos);
                record(frameNanos);
                if (now - phaseStartNanos >= MEASURE_NANOS) {
                    finishPass(client);
                }
                return;

            default:
                return;
        }
    }

    // -----------------------------------------------------------------------

    private void beginNextPass(MinecraftClient client) {
        passIndex++;
        if (passIndex >= passes.size()) {
            complete(client);
            return;
        }
        passes.get(passIndex).apply(registry, original);
        settleFrames = 0;
        recentCursor = 0;
        retries = 0;
        focus.reset();
        java.util.Arrays.fill(recent, 0d);
        log.reset();
        phase = Phase.SETTLING;
        phaseStartNanos = System.nanoTime();
        System.out.println("[FATHOM BENCH] pass " + (passIndex + 1) + "/" + passes.size()
                + ": " + passes.get(passIndex).label());
    }

    /** Focus came back during warm-up: start the wait again rather than counting it. */
    private void restartWarmup(long now) {
        focus.reset();
        recentCursor = 0;
        java.util.Arrays.fill(recent, 0d);
        phaseStartNanos = now;
    }

    /**
     * Throw the pass away and run it again.
     *
     * <p>After {@link #MAX_RETRIES} it is recorded anyway, flagged, and the
     * report excludes it from ranking. A player who has alt-tabbed for good
     * should still get a report for the passes that did measure something.
     */
    private void voidPass(MinecraftClient client) {
        BenchmarkState.setMeasuring(false);
        Probe.end();
        BenchmarkPass current = passes.get(passIndex);
        if (retries >= MAX_RETRIES) {
            sceneNotes.add(current.label()
                    + ": window lost focus on every attempt; excluded from ranking "
                    + "(an unfocused window is capped at 30 fps).");
            results.add(voidResult(current));
            beginNextPass(client);
            return;
        }
        retries++;
        System.out.println("[FATHOM BENCH] " + current.label()
                + ": lost window focus, retrying (" + retries + "/" + MAX_RETRIES + ")");
        focus.reset();
        settleFrames = 0;
        recentCursor = 0;
        java.util.Arrays.fill(recent, 0d);
        log.reset();
        phase = Phase.SETTLING;
        phaseStartNanos = System.nanoTime();
    }

    /** A pass that could not be measured, kept so the report can say why. */
    private BenchmarkResult voidResult(BenchmarkPass current) {
        return new BenchmarkResult(
                current,
                FrameStatistics.of(new long[0]),
                new LinkedHashMap<String, Double>(),
                new RenderCounters(),
                0L, 0L, usedHeapMb(), usedHeapMb(),
                "VOID: window lost focus, measured the 30 fps unfocused cap rather than the scene");
    }

    private void startMeasuring(long now) {
        phase = Phase.MEASURING;
        phaseStartNanos = now;
        log.reset();
        Probe.begin();
        BenchmarkState.counters().reset();
        BenchmarkState.setMeasuring(true);
        gcCountAtStart = gcCount();
        gcMillisAtStart = gcMillis();
        heapBeforeMb = usedHeapMb();
    }

    private void finishPass(MinecraftClient client) {
        BenchmarkState.setMeasuring(false);
        Probe.end();

        Map<String, Double> sections = new LinkedHashMap<String, Double>();
        for (Probe.Section section : Probe.Section.values()) {
            double millis = Probe.meanMillis(section);
            if (millis > 0d) {
                sections.put(section.name().toLowerCase(java.util.Locale.ROOT), Double.valueOf(millis));
            }
        }

        FrameStatistics statistics = log.statistics();
        String note = statistics.sampleCount() < MINIMUM_FRAMES
                ? "only " + statistics.sampleCount() + " frames sampled; treat as indicative"
                : "";

        results.add(new BenchmarkResult(
                passes.get(passIndex),
                statistics,
                sections,
                BenchmarkState.counters().copy(),
                gcCount() - gcCountAtStart,
                gcMillis() - gcMillisAtStart,
                heapBeforeMb,
                usedHeapMb(),
                note));

        beginNextPass(client);
    }

    private void complete(MinecraftClient client) {
        phase = Phase.DONE;
        finished = true;
        restore();

        Environment environment = BenchmarkEnvironment.capture(client);
        long seconds = (System.nanoTime() - startedNanos) / 1_000_000_000L;
        String stem = BenchmarkReportWriter.fileStem();
        String text = BenchmarkReportWriter.text(
                environment, results, registry, sceneNotes, seconds);
        String json = BenchmarkReportWriter.json(environment, results, seconds);

        File directory = new File(client.runDirectory, "logs");
        File textFile = new File(directory, stem + ".txt");
        File jsonFile = new File(directory, stem + ".json");
        write(directory, textFile, text);
        write(directory, jsonFile, json);

        System.out.println(text);
        System.out.println("[FATHOM BENCH] report written to " + textFile.getAbsolutePath());
        BenchmarkOverlay.show(results, textFile);
    }

    /** Put every switch back, whatever happened. */
    private void restore() {
        List<String> failures = registry.restore(original);
        BenchmarkState.reset();
        for (String failure : failures) {
            System.out.println("[FATHOM BENCH] could not restore " + failure);
        }
    }

    private void abort() {
        finished = true;
        phase = Phase.DONE;
        restore();
    }

    // -----------------------------------------------------------------------

    private void record(double frameNanos) {
        recent[recentCursor] = frameNanos;
        recentCursor = (recentCursor + 1) % recent.length;
    }

    /** Whether recent frames vary little enough to be worth measuring. */
    private boolean stable() {
        double sum = 0d;
        int count = 0;
        for (double each : recent) {
            if (each > 0d) {
                sum += each;
                count++;
            }
        }
        if (count < recent.length) {
            return false;
        }
        double mean = sum / count;
        if (mean <= 0d) {
            return false;
        }
        double variance = 0d;
        for (double each : recent) {
            double delta = each - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / count) / mean <= STABLE_COV;
    }

    private static long gcCount() {
        long total = 0L;
        try {
            for (java.lang.management.GarbageCollectorMXBean bean
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = bean.getCollectionCount();
                if (count > 0L) {
                    total += count;
                }
            }
        } catch (Throwable unavailable) {
            return 0L;
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        try {
            for (java.lang.management.GarbageCollectorMXBean bean
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                long millis = bean.getCollectionTime();
                if (millis > 0L) {
                    total += millis;
                }
            }
        } catch (Throwable unavailable) {
            return 0L;
        }
        return total;
    }

    private static long usedHeapMb() {
        java.lang.Runtime vm = java.lang.Runtime.getRuntime();
        return (vm.totalMemory() - vm.freeMemory()) >> 20;
    }

    private static void write(File directory, File file, String contents) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                System.out.println("[FATHOM BENCH] could not create " + directory);
                return;
            }
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), "UTF-8");
            try {
                writer.write(contents);
            } finally {
                writer.close();
            }
        } catch (Throwable failure) {
            System.out.println("[FATHOM BENCH] could not write " + file + ": " + failure);
        }
    }
}
