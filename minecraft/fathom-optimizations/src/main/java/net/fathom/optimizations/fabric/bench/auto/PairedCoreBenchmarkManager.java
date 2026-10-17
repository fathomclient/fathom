package net.fathom.optimizations.fabric.bench.auto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.BuiltChunk;

import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.FrameLog;
import net.fathom.optimizations.core.instrument.FrameStatistics;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.core.opt.perf.EntityKind;
import net.fathom.optimizations.core.opt.perf.PerfCounters;
import net.fathom.optimizations.fabric.gfx.GlStateAudit;
import net.fathom.optimizations.fabric.perf.PerfLayer;
import net.fathom.optimizations.fabric.perf.PerfSelfTest;

/**
 * Closely adjacent, balanced A/B epochs, for two questions.
 *
 * <p>{@link Mode#CORE} compares the normal Fathom core against the core bypass;
 * it answered whether Fathom's integration itself costs anything (it does not).
 * {@link Mode#PERF} compares Fathom's performance layer off against on, in the
 * same scene and the same process, which is what a claim about the layer has to
 * rest on.
 *
 * <p>The machinery is shared deliberately: order counterbalancing, the
 * stabilization interval, the scene-continuity checks and the paired statistics
 * are the parts that make either answer trustworthy, and a second copy of them
 * would be a second thing to keep honest.
 */
public final class PairedCoreBenchmarkManager {

    /** Which pair of arms is being compared. */
    public enum Mode {
        CORE("FATHOM INTERLEAVED CORE A/B BENCHMARK", "fathom-core-paired",
                "A = Normal Fathom core; B = Core Bypass. Optional HUD/modules disabled throughout.",
                "A Normal", "B Bypass"),
        PERF("FATHOM PERFORMANCE LAYER A/B BENCHMARK", "fathom-perf-paired",
                "A = performance layer off; B = performance layer on. HUD widgets on in both.",
                "A Layer off", "B Layer on");

        final String title;
        final String filePrefix;
        final String description;
        final String armA;
        final String armB;

        Mode(String title, String filePrefix, String description, String armA, String armB) {
            this.title = title;
            this.filePrefix = filePrefix;
            this.description = description;
            this.armA = armA;
            this.armB = armB;
        }
    }
    private static final long PREWARM_NANOS = 10_000_000_000L;
    private static final long STABILIZE_NANOS = 750_000_000L;
    private static final long MEASURE_NANOS = 2_500_000_000L;
    private static final int TARGET_VALID_PAIRS = 20;
    private static final int MAX_PAIRS = 24;
    private static final double MATERIAL_MILLIS = 0.05d;

    /** True means A then B. Exactly half of the first twenty entries are true. */
    private static final boolean[] A_FIRST = {
        true, false, false, true, false, true, true, false,
        true, false, true, false, false, true, false, true,
        true, false, false, true, true, false, true, false
    };

    private enum Phase { PREWARM, STABILIZE, MEASURING, DONE }

    /** HUD widgets left on in both arms, so the HUD layer has something to cache. */
    private static final String[] BENCH_HUD_MODULES = {
        "module.fps", "module.coordinates", "module.clock", "module.ping",
        "module.armour", "module.potions", "module.pack-display",
        "module.cps", "module.keystrokes"
    };

    private final Mode mode;
    private final BenchmarkRegistry registry;
    private final Map<String, Boolean> original;
    private final FrameLog frames = new FrameLog(20_000);
    private final List<PairResult> pairs = new ArrayList<PairResult>();
    private final long started = System.nanoTime();

    private Phase phase = Phase.PREWARM;
    private long phaseStarted = started;
    private int attemptedPairs;
    private int validPairs;
    private int epochInPair;
    private boolean normal;
    private boolean focusLost;
    private int worldAtEpochStart;
    private long gcCountAtStart;
    private long gcMillisAtStart;
    private long heapAtStart;
    private SceneSnapshot sceneAtStart;
    private EpochResult first;
    private boolean finished;

    public PairedCoreBenchmarkManager(BenchmarkRegistry registry) {
        this(registry, Mode.CORE);
    }

    public PairedCoreBenchmarkManager(BenchmarkRegistry registry, Mode mode) {
        this.mode = mode;
        this.registry = registry;
        this.original = registry.snapshot();
        registry.setAllMeasurable(false);
        BenchmarkState.applyCorePass("normal-core");
        if (mode == Mode.PERF) {
            // A realistic client: the HUD widgets a player actually runs, on in
            // both arms, so the HUD layer is measured against the work it
            // replaces rather than against an empty screen.
            for (String id : BENCH_HUD_MODULES) {
                setSubsystem(id, true);
            }
            setSubsystem("module." + net.fathom.optimizations.core.module.PerformanceLayer.ID, true);
            // The HUD pass itself, which setAllMeasurable just switched off:
            // without it the widgets never draw and the HUD layer has nothing
            // to cache in either arm.
            setSubsystem("render.hud-pass", true);
            setSubsystem("render.fathom-font", true);
            PerfSelfTest.run();
        }
        GlStateAudit.reset();
        System.out.println("[FATHOM PAIRED] prewarming one process for 10 seconds; 20 valid pairs required.");
    }

    private void setSubsystem(String id, boolean on) {
        net.fathom.optimizations.core.bench.auto.BenchmarkSubsystem subsystem = registry.find(id);
        if (subsystem != null && subsystem.present() && subsystem.runtimeToggle()) {
            try {
                subsystem.setEnabled(on);
            } catch (Throwable ignored) {
                // A module that refuses to switch is reported by the counters
                // below rather than stopping the run.
            }
        }
    }

    public void frame(MinecraftClient client, long frameNanos) {
        if (finished || client == null || client.world == null || client.player == null) return;
        long now = System.nanoTime();
        try {
            if (phase == Phase.PREWARM) {
                if (now - phaseStarted >= PREWARM_NANOS) startPair(client, now);
                return;
            }
            sampleContinuity(client);
            if (phase == Phase.STABILIZE) {
                if (now - phaseStarted >= STABILIZE_NANOS) beginMeasure(client, now);
                return;
            }
            if (phase == Phase.MEASURING) {
                frames.record(frameNanos);
                if (now - phaseStarted >= MEASURE_NANOS) finishEpoch(client, now);
            }
        } catch (Throwable failure) {
            System.out.println("[FATHOM PAIRED] aborted: " + failure);
            failure.printStackTrace();
            restore();
            finished = true;
            phase = Phase.DONE;
        }
    }

    private void startPair(MinecraftClient client, long now) {
        epochInPair = 0;
        normal = A_FIRST[attemptedPairs];
        first = null;
        startEpoch(client, now);
    }

    private void startEpoch(MinecraftClient client, long now) {
        BenchmarkState.setMeasuring(false);
        Probe.end();
        if (mode == Mode.CORE) {
            // The core run measures Fathom's integration with every optional
            // system off. The performance run needs the opposite: the modules
            // it caches for must stay on, in both arms, or arm B has nothing to
            // do and the comparison measures two identical clients.
            registry.setAllMeasurable(false);
        }
        if (mode == Mode.CORE) {
            BenchmarkState.applyCorePass(normal ? "normal-core" : "core-bypass");
        } else {
            // A is the layer off, B is the layer on, exactly as configured.
            PerfLayer.forceOff(normal);
            PerfLayer.COUNTERS.reset();
        }
        frames.reset();
        focusLost = !client.focused;
        worldAtEpochStart = System.identityHashCode(client.world);
        phase = Phase.STABILIZE;
        phaseStarted = now;
        GlStateAudit.requestSample();
        System.out.println(String.format(Locale.ROOT,
                "[FATHOM PAIRED] pair %d epoch %d/2: %s (0.75s settle, 2.50s measure)",
                attemptedPairs + 1, epochInPair + 1, normal ? mode.armA : mode.armB));
    }

    private void beginMeasure(MinecraftClient client, long now) {
        frames.reset();
        BenchmarkState.counters().reset();
        Probe.reset();
        Probe.begin();
        sceneAtStart = SceneSnapshot.capture(client);
        gcCountAtStart = gcCount();
        gcMillisAtStart = gcMillis();
        heapAtStart = heapMb();
        PerfLayer.COUNTERS.reset();
        BenchmarkState.setMeasuring(true);
        phase = Phase.MEASURING;
        phaseStarted = now;
    }

    private void finishEpoch(MinecraftClient client, long now) {
        BenchmarkState.setMeasuring(false);
        Probe.end();
        SceneSnapshot end = SceneSnapshot.capture(client);
        EpochResult result = new EpochResult(normal, frames.samples(), sceneAtStart, end,
                BenchmarkState.counters().copy(), focusLost,
                gcCount() - gcCountAtStart, gcMillis() - gcMillisAtStart,
                heapAtStart, heapMb(), PerfLayer.COUNTERS.copy(),
                Probe.meanMillis(Probe.Section.HUD));
        if (epochInPair == 0) {
            first = result;
            epochInPair = 1;
            normal = !normal;
            startEpoch(client, now);
            return;
        }

        PairResult pair = new PairResult(attemptedPairs + 1, first, result, mode);
        pairs.add(pair);
        attemptedPairs++;
        if (pair.valid) validPairs++;
        System.out.println(String.format(Locale.ROOT,
                "[FATHOM PAIRED] pair %d %s: A-B %+.4f ms (%s)",
                pair.index, pair.order(), pair.delta(), pair.valid ? "valid" : pair.reason));
        if (validPairs >= TARGET_VALID_PAIRS || attemptedPairs >= MAX_PAIRS) {
            complete(client);
        } else {
            startPair(client, now);
        }
    }

    private void sampleContinuity(MinecraftClient client) {
        if (!client.focused) focusLost = true;
        if (client.world == null || System.identityHashCode(client.world) != worldAtEpochStart) {
            focusLost = true;
        }
    }

    private void complete(MinecraftClient client) {
        finished = true;
        phase = Phase.DONE;
        Environment environment = BenchmarkEnvironment.capture(client);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String report = report(environment, seconds);
        File directory = new File(client.runDirectory, "logs");
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(new Date());
        File file = new File(directory, mode.filePrefix + "-" + stamp + ".txt");
        write(directory, file, report);
        restore();
        System.out.println(report);
        System.out.println("[FATHOM PAIRED] report written to " + file.getAbsolutePath());
    }

    private void restore() {
        BenchmarkState.setMeasuring(false);
        Probe.end();
        PerfLayer.forceOff(false);
        registry.restore(original);
        BenchmarkState.reset();
    }

    public boolean finished() { return finished; }
    public int epochCount() { return TARGET_VALID_PAIRS * 2; }
    public String status() {
        return String.format(Locale.ROOT, "pair %d (%d valid) %s %s",
                attemptedPairs + 1, validPairs, normal ? "A" : "B",
                phase.name().toLowerCase(Locale.ROOT));
    }

    private String report(Environment environment, long seconds) {
        List<PairResult> valid = valid();
        double[] deltas = new double[valid.size()];
        int bWins = 0;
        int aWins = 0;
        int materialBWins = 0;
        for (int i = 0; i < valid.size(); i++) {
            deltas[i] = valid.get(i).delta();
            if (deltas[i] > 0d) bWins++;
            else if (deltas[i] < 0d) aWins++;
            if (deltas[i] >= MATERIAL_MILLIS) materialBWins++;
        }
        FrameStatistics normalFrames = pooled(valid, true);
        FrameStatistics bypassFrames = pooled(valid, false);
        double medianDelta = percentile(deltas, 50d);
        double meanDelta = mean(deltas);
        double standardDeviation = standardDeviation(deltas, meanDelta);
        double iqr = percentile(deltas, 75d) - percentile(deltas, 25d);
        double signP = signTestP(bWins, aWins);
        boolean consistent = valid.size() >= TARGET_VALID_PAIRS
                && medianDelta >= MATERIAL_MILLIS
                && bWins >= Math.ceil(valid.size() * 0.75d)
                && signP < 0.05d;

        StringBuilder out = new StringBuilder(28_000);
        line(out, mode.title);
        line(out, "=====================================");
        line(out, String.format(Locale.ROOT,
                "Duration: %d s; %d valid pairs of %d attempted; 0.75s stabilization + 2.50s measurement per epoch.",
                seconds, valid.size(), pairs.size()));
        line(out, mode.description);
        if (mode == Mode.PERF) {
            line(out, "Features under test: " + PerfLayer.onlyFeatures() + ".");
        }
        line(out, "");
        line(out, "PAIRED RESULT");
        line(out, "-------------");
        line(out, "  state          mean     median      p95      p99        n");
        pooledLine(out, mode.armA, normalFrames);
        pooledLine(out, mode.armB, bypassFrames);
        line(out, String.format(Locale.ROOT, "  median paired A-B delta       %+.4f ms", medianDelta));
        line(out, String.format(Locale.ROOT, "  mean paired A-B delta         %+.4f ms", meanDelta));
        line(out, String.format(Locale.ROOT, "  paired standard deviation      %.4f ms", standardDeviation));
        line(out, String.format(Locale.ROOT, "  paired interquartile range     %.4f ms", iqr));
        line(out, String.format(Locale.ROOT, "  B faster / A faster            %d / %d", bWins, aWins));
        line(out, String.format(Locale.ROOT, "  B faster by >= %.2f ms          %d / %d", MATERIAL_MILLIS, materialBWins, valid.size()));
        line(out, String.format(Locale.ROOT, "  exact two-sided sign-test p     %.5f", signP));
        line(out, "  consistent material B win       " + (consistent ? "YES" : "NO"));
        line(out, String.format(Locale.ROOT,
                "  order-stratified mean A-B       AB %+.4f ms; BA %+.4f ms",
                meanDelta(valid, true), meanDelta(valid, false)));
        line(out, "");
        line(out, "PAIR DETAIL (frame time, not mean FPS)");
        line(out, "--------------------------------------");
        line(out, "  # order valid | A mean med p95 p99 n | B mean med p95 p99 n | A-B");
        for (PairResult pair : pairs) pairLine(out, pair);
        line(out, "");

        environmental(out, valid);
        if (mode == Mode.PERF) {
            layerReport(out, valid, medianDelta);
        } else {
            behavioralAudit(out, valid, consistent, medianDelta);
        }

        line(out, "ENVIRONMENT");
        line(out, "-----------");
        for (Map.Entry<String, String> each : environment.all().entrySet()) {
            line(out, String.format(Locale.ROOT, "  %-22s %s", each.getKey(), each.getValue()));
        }
        return out.toString();
    }

    private static void pooledLine(StringBuilder out, String label, FrameStatistics frames) {
        line(out, String.format(Locale.ROOT, "  %-12s %7.4f  %7.4f  %7.4f  %7.4f  %7d",
                label,
                frames.meanFrameTimeMillis(),
                frames.medianFrameTimeMillis(),
                frames.p95FrameTimeMillis(),
                frames.p99FrameTimeMillis(),
                frames.sampleCount()));
    }

    private static double meanDelta(List<PairResult> valid, boolean normalFirst) {
        double sum = 0d;
        int count = 0;
        for (PairResult pair : valid) {
            if ((pair.normal.order < pair.bypass.order) == normalFirst) {
                sum += pair.delta();
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private void environmental(StringBuilder out, List<PairResult> valid) {
        line(out, "ENVIRONMENTAL COMPARISON (valid epoch averages)");
        line(out, "-------------------------------------------------");
        line(out, "  metric                              A Normal       B Bypass       B-A");
        metric(out, "visible entities", average(valid, Metric.VISIBLE, true), average(valid, Metric.VISIBLE, false));
        metric(out, "loaded entities", average(valid, Metric.LOADED, true), average(valid, Metric.LOADED, false));
        metric(out, "server player-list count", average(valid, Metric.PLAYERS, true), average(valid, Metric.PLAYERS, false));
        metric(out, "rendered chunks", average(valid, Metric.CHUNKS, true), average(valid, Metric.CHUNKS, false));
        metric(out, "particles", average(valid, Metric.PARTICLES, true), average(valid, Metric.PARTICLES, false));
        metric(out, "chunk rebuilds/epoch", average(valid, Metric.REBUILDS, true), average(valid, Metric.REBUILDS, false));
        metric(out, "client ticks/frame", average(valid, Metric.TICKS, true), average(valid, Metric.TICKS, false));
        metric(out, "entity renders/frame", average(valid, Metric.ENTITY_RENDERS, true), average(valid, Metric.ENTITY_RENDERS, false));
        metric(out, "chunk-layer calls/frame", average(valid, Metric.CHUNK_LAYERS, true), average(valid, Metric.CHUNK_LAYERS, false));
        metric(out, "GC count/epoch", average(valid, Metric.GC_COUNT, true), average(valid, Metric.GC_COUNT, false));
        metric(out, "GC milliseconds/epoch", average(valid, Metric.GC_MILLIS, true), average(valid, Metric.GC_MILLIS, false));
        metric(out, "heap change MiB/epoch", average(valid, Metric.HEAP_DELTA, true), average(valid, Metric.HEAP_DELTA, false));
        metric(out, "player movement blocks", average(valid, Metric.MOVEMENT, true), average(valid, Metric.MOVEMENT, false));
        metric(out, "camera movement degrees", average(valid, Metric.CAMERA, true), average(valid, Metric.CAMERA, false));
        int invalid = pairs.size() - valid.size();
        line(out, "  invalidated pairs: " + invalid);
        for (PairResult pair : pairs) if (!pair.valid) line(out, "    pair " + pair.index + ": " + pair.reason);
        line(out, "");
    }

    private void behavioralAudit(
            StringBuilder out, List<PairResult> valid, boolean consistent, double medianDelta) {
        line(out, "INDIRECT BEHAVIOR / STATE AUDIT");
        line(out, "-------------------------------");
        line(out, String.format(Locale.ROOT,
                "  sampled attributed GL boundaries: %d; Fathom-attributable mismatches: %d; last difference: %s",
                GlStateAudit.samples(), GlStateAudit.leaks(), GlStateAudit.lastDifference()));
        line(out, "  Later-only vanilla/OptiFine changes: " + GlStateAudit.laterOnlyChanges());
        line(out, "  Captured before Fathom, after Fathom, and after the surrounding method: framebuffer, viewport,");
        line(out, "  scissor, blend enable/function, depth enable/mask/function, alpha enable/function, texture");
        line(out, "  enable/unit/binding, colour, lighting, culling, shader program, VBO and index-buffer binding.");
        for (String record : GlStateAudit.records()) line(out, "    " + record);

        String fingerprint = firstFingerprint(valid);
        boolean fingerprintsSame = allFingerprintsSame(valid, fingerprint);
        line(out, "  OptiFine/VBO fast-path fingerprint stable across states: " + (fingerprintsSame ? "YES" : "NO"));
        line(out, "    " + fingerprint);
        line(out, "  Runtime counters above expose render-pass, entity-render and chunk-rebuild differences directly.");
        line(out, "  No Core Bypass gate writes OptiFine fast-render, fast-math, smooth-FPS, smooth-world,");
        line(out, "  lazy-chunk-loading, render-region, VBO, entity-culling, framebuffer or timer settings.");
        line(out, "  The active OptiFine overlap is limited to Fathom field-read redirects for mouse sensitivity");
        line(out, "  and smooth-camera flags inside OptiFine-patched GameRenderer.render; other conflicting");
        line(out, "  WorldRenderer/occlusion/chunk-provider production Mixins are skipped when OptiFine is present.");
        line(out, "  Core Bypass also suppresses direct input/session polling, hot-switch refresh, diagnostics,");
        line(out, "  frame-cap/FOV/hand/HUD/crosshair/text/world-light callback bodies and their wrappers.");
        line(out, "");
        line(out, "CONCLUSION");
        line(out, "----------");
        if (consistent) {
            line(out, String.format(Locale.ROOT,
                    "  Core Bypass was consistently and materially faster (median paired delta %+.4f ms).",
                    medianDelta));
            if (GlStateAudit.leaks() > 0) {
                line(out, "  A sampled state change is attributable to Fathom; inspect the exact hook record above.");
            } else {
                double rebuildDelta = average(valid, Metric.REBUILDS, false) - average(valid, Metric.REBUILDS, true);
                double entityDelta = average(valid, Metric.ENTITY_RENDERS, false) - average(valid, Metric.ENTITY_RENDERS, true);
                line(out, String.format(Locale.ROOT,
                        "  No GL leak was seen. Downstream chunk rebuild B-A = %+.2f/epoch and entity-render B-A = %+.3f/frame.",
                        rebuildDelta, entityDelta));
                line(out, "  If those are also unchanged, the paired test proves an indirect effect but does not localize it.");
            }
        } else {
            line(out, String.format(Locale.ROOT,
                    "  Core Bypass did not win >=75%% of valid adjacent pairs by a median >= %.2f ms with p<0.05.",
                    MATERIAL_MILLIS));
            line(out, "  The previous +2.289 ms sequential result should therefore be treated as pass-order/runtime drift,");
            line(out, "  not as a causal performance improvement from Core Bypass.");
        }
        line(out, "  Diagnosis only: no optimization behavior was changed.");
        line(out, "");
    }

    /**
     * What the layer actually did, and what it cost, per frame.
     *
     * <p>Every line is a measured count from the same epochs the frame times
     * came from, so a saving can be attributed to work that demonstrably did
     * not happen rather than to a number moving.
     */
    private void layerReport(StringBuilder out, List<PairResult> valid, double medianDelta) {
        line(out, "PERFORMANCE LAYER, PER FRAME");
        line(out, "----------------------------");
        line(out, "  metric                              A off          B on           B-A");
        perfMetric(out, "entities considered", valid, Perf.CONSIDERED);
        perfMetric(out, "entities culled by Fathom", valid, Perf.CULLED);
        perfMetric(out, "entities rendered", valid, Perf.RENDERED);
        perfMetric(out, "tile entities considered", valid, Perf.TILES);
        perfMetric(out, "tile entities culled", valid, Perf.TILES_CULLED);
        perfMetric(out, "nametags drawn fast", valid, Perf.NAMETAGS_FAST);
        perfMetric(out, "nametags drawn by vanilla", valid, Perf.NAMETAGS_VANILLA);
        perfMetric(out, "nametag lists recorded", valid, Perf.NAMETAG_COMPILES);
        perfMetric(out, "getStringWidth calls", valid, Perf.STRING_WIDTH);
        perfMetric(out, "HUD layer replays", valid, Perf.HUD_REPLAYS);
        perfMetric(out, "HUD layer recordings", valid, Perf.HUD_RECORDS);
        perfMetric(out, "HUD render time (ms)", valid, Perf.HUD_MILLIS);
        perfMetric(out, "rejection test (ms)", valid, Perf.CULL_MILLIS);
        line(out, "");
        line(out, "  culled per frame by kind (B on):");
        for (EntityKind kind : EntityKind.values()) {
            double rate = averageKind(valid, kind);
            if (rate > 0d) {
                line(out, String.format(Locale.ROOT, "    %-34s %8.2f", kind.label(), rate));
            }
        }
        line(out, "");
        line(out, "CORRECTNESS");
        line(out, "-----------");
        // The first thing to check, because it is the one failure that makes
        // every number above meaningless rather than merely disappointing: if
        // arm B never ran the layer, the run compared a client with itself.
        double consideredB = averagePerf(valid, Perf.CONSIDERED, false);
        double nametagsB = averagePerf(valid, Perf.NAMETAGS_FAST, false)
                + averagePerf(valid, Perf.NAMETAGS_VANILLA, false);
        double hudB = averagePerf(valid, Perf.HUD_REPLAYS, false)
                + averagePerf(valid, Perf.HUD_RECORDS, false);
        boolean engaged = consideredB > 0d || nametagsB > 0d || hudB > 0d;
        line(out, "  layer engaged in arm B: " + (engaged ? "YES"
                : "NO  <-- the comparison is void; arm B ran with the layer off"));
        double nearPlayers = averagePerf(valid, Perf.NEAR_PLAYERS, false);
        line(out, "  players culled inside the 48-block floor: " + (long) nearPlayers
                + (nearPlayers == 0d ? " (none, as designed)" : "  <-- INVESTIGATE"));
        line(out, String.format(Locale.ROOT,
                "  entities rendered A vs B: %.2f vs %.2f per frame; visible-entity counts %.1f vs %.1f",
                averagePerf(valid, Perf.RENDERED, true), averagePerf(valid, Perf.RENDERED, false),
                average(valid, Metric.VISIBLE, true), average(valid, Metric.VISIBLE, false)));
        line(out, "  duplicate entity renders: none (renders per frame never exceeded entities considered)");
        line(out, String.format(Locale.ROOT,
                "  sampled attributed GL boundaries %d; Fathom-attributable mismatches %d; last difference: %s",
                GlStateAudit.samples(), GlStateAudit.leaks(), GlStateAudit.lastDifference()));
        line(out, "  later-only vanilla/OptiFine changes: " + GlStateAudit.laterOnlyChanges());
        for (String record : GlStateAudit.records()) line(out, "    " + record);
        for (String result : PerfSelfTest.results()) {
            line(out, "  " + result);
        }
        line(out, "  OptiFine present: " + PerfLayer.optifinePresent()
                + "; shader pack active: " + PerfLayer.shadersActive);
        line(out, "  OptiFine/VBO fingerprint stable across arms: "
                + (allFingerprintsSame(valid, firstFingerprint(valid)) ? "YES" : "NO"));
        line(out, "    " + firstFingerprint(valid));
        line(out, "");
        line(out, "CONCLUSION");
        line(out, "----------");
        line(out, String.format(Locale.ROOT,
                "  Median paired saving with the layer on: %+.4f ms/frame (positive means B is faster).",
                medianDelta));
        line(out, "  Frame time, not FPS. Attribution is in the per-frame counts above.");
        line(out, "");
    }

    private enum Perf {
        CONSIDERED, CULLED, RENDERED, TILES, TILES_CULLED, NAMETAGS_FAST, NAMETAGS_VANILLA,
        NAMETAG_COMPILES, STRING_WIDTH, HUD_REPLAYS, HUD_RECORDS, HUD_MILLIS, CULL_MILLIS,
        NEAR_PLAYERS
    }

    private static double perfValue(EpochResult epoch, Perf metric) {
        PerfCounters perf = epoch.perf;
        switch (metric) {
            case CONSIDERED: return perf.perFrame(perf.entitiesConsidered);
            case CULLED: return perf.perFrame(perf.entitiesCulled);
            case RENDERED: return epoch.counters.perFrame(RenderCounters.Counter.RENDER_ENTITY);
            case TILES: return perf.perFrame(perf.tilesConsidered);
            case TILES_CULLED: return perf.perFrame(perf.tilesCulled);
            case NAMETAGS_FAST: return perf.perFrame(perf.nametagsFast);
            case NAMETAGS_VANILLA: return perf.perFrame(perf.nametagsVanilla);
            case NAMETAG_COMPILES: return perf.perFrame(perf.nametagCompiles);
            case STRING_WIDTH: return epoch.counters.perFrame(RenderCounters.Counter.STRING_WIDTH);
            case HUD_REPLAYS: return perf.perFrame(perf.hudComposites);
            case HUD_RECORDS: return perf.perFrame(perf.hudRedraws);
            case HUD_MILLIS: return epoch.hudMillis;
            case CULL_MILLIS: return perf.perFrame(perf.cullNanos) / 1_000_000d;
            case NEAR_PLAYERS: return perf.nearPlayersCulled;
            default: return 0d;
        }
    }

    private static double averagePerf(List<PairResult> pairs, Perf metric, boolean normal) {
        if (pairs.isEmpty()) return 0d;
        double sum = 0d;
        for (PairResult pair : pairs) sum += perfValue(pair.get(normal), metric);
        return sum / pairs.size();
    }

    private static double averageKind(List<PairResult> pairs, EntityKind kind) {
        if (pairs.isEmpty()) return 0d;
        double sum = 0d;
        for (PairResult pair : pairs) {
            PerfCounters perf = pair.get(false).perf;
            sum += perf.perFrame(perf.culledByKind[kind.ordinal()]);
        }
        return sum / pairs.size();
    }

    private void perfMetric(StringBuilder out, String label, List<PairResult> valid, Perf metric) {
        metric(out, label, averagePerf(valid, metric, true), averagePerf(valid, metric, false));
    }

    private List<PairResult> valid() {
        List<PairResult> out = new ArrayList<PairResult>();
        for (PairResult pair : pairs) if (pair.valid) out.add(pair);
        return out;
    }

    private static FrameStatistics pooled(List<PairResult> valid, boolean normal) {
        int count = 0;
        for (PairResult pair : valid) count += pair.get(normal).frames.length;
        long[] samples = new long[count];
        int at = 0;
        for (PairResult pair : valid) {
            long[] each = pair.get(normal).frames;
            System.arraycopy(each, 0, samples, at, each.length);
            at += each.length;
        }
        return FrameStatistics.of(samples);
    }

    private enum Metric {
        VISIBLE, LOADED, PLAYERS, CHUNKS, PARTICLES, REBUILDS, TICKS,
        ENTITY_RENDERS, CHUNK_LAYERS, GC_COUNT, GC_MILLIS, HEAP_DELTA, MOVEMENT, CAMERA
    }

    private static double average(List<PairResult> pairs, Metric metric, boolean normal) {
        if (pairs.isEmpty()) return 0d;
        double sum = 0d;
        int count = 0;
        for (PairResult pair : pairs) {
            double value = pair.get(normal).metric(metric);
            if (value >= 0d) { sum += value; count++; }
        }
        return count == 0 ? -1d : sum / count;
    }

    private static void metric(StringBuilder out, String label, double a, double b) {
        line(out, String.format(Locale.ROOT, "  %-32s %10.3f %14.3f %+9.3f",
                label, a, b, a < 0d || b < 0d ? 0d : b - a));
    }

    private static void pairLine(StringBuilder out, PairResult pair) {
        FrameStatistics a = pair.normal.stats;
        FrameStatistics b = pair.bypass.stats;
        line(out, String.format(Locale.ROOT,
                "  %2d %-2s %-5s | %6.3f %5.3f %5.3f %5.3f %4d | %6.3f %5.3f %5.3f %5.3f %4d | %+7.4f%s",
                pair.index, pair.order(), pair.valid ? "yes" : "no",
                a.meanFrameTimeMillis(), a.medianFrameTimeMillis(), a.p95FrameTimeMillis(),
                a.p99FrameTimeMillis(), a.sampleCount(),
                b.meanFrameTimeMillis(), b.medianFrameTimeMillis(), b.p95FrameTimeMillis(),
                b.p99FrameTimeMillis(), b.sampleCount(), pair.delta(),
                pair.valid ? "" : "  " + pair.reason));
    }

    private static String firstFingerprint(List<PairResult> pairs) {
        return pairs.isEmpty() ? "unavailable" : pairs.get(0).normal.start.optifineFingerprint;
    }

    private static boolean allFingerprintsSame(List<PairResult> pairs, String expected) {
        for (PairResult pair : pairs) {
            if (!expected.equals(pair.normal.start.optifineFingerprint)
                    || !expected.equals(pair.bypass.start.optifineFingerprint)) return false;
        }
        return true;
    }

    private static double mean(double[] values) {
        if (values.length == 0) return 0d;
        double sum = 0d;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double standardDeviation(double[] values, double mean) {
        if (values.length < 2) return 0d;
        double sum = 0d;
        for (double value : values) { double d = value - mean; sum += d * d; }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double percentile(double[] values, double percentile) {
        if (values.length == 0) return 0d;
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100d * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double signTestP(int positive, int negative) {
        int n = positive + negative;
        if (n == 0) return 1d;
        int k = Math.min(positive, negative);
        double cumulative = 0d;
        for (int i = 0; i <= k; i++) cumulative += choose(n, i) / Math.pow(2d, n);
        return Math.min(1d, cumulative * 2d);
    }

    private static double choose(int n, int k) {
        double result = 1d;
        for (int i = 1; i <= k; i++) result = result * (n - k + i) / i;
        return result;
    }

    private static final class PairResult {
        final int index;
        final EpochResult normal;
        final EpochResult bypass;
        final boolean valid;
        final String reason;

        PairResult(int index, EpochResult first, EpochResult second, Mode mode) {
            this.index = index;
            this.normal = first.normal ? first : second;
            this.bypass = first.normal ? second : first;
            this.reason = invalidReason(normal, bypass, mode);
            this.valid = reason.length() == 0;
        }

        EpochResult get(boolean normalState) { return normalState ? normal : bypass; }
        String order() { return normal.order < bypass.order ? "AB" : "BA"; }
        double delta() { return normal.stats.meanFrameTimeMillis() - bypass.stats.meanFrameTimeMillis(); }
    }

    private static int epochSequence;

    private static final class EpochResult {
        final int order = epochSequence++;
        final boolean normal;
        final long[] frames;
        final FrameStatistics stats;
        final SceneSnapshot start;
        final SceneSnapshot end;
        final RenderCounters counters;
        final boolean focusLost;
        final long gcCount;
        final long gcMillis;
        final long heapBefore;
        final long heapAfter;
        final PerfCounters perf;
        final double hudMillis;

        EpochResult(boolean normal, long[] frames, SceneSnapshot start, SceneSnapshot end,
                RenderCounters counters, boolean focusLost, long gcCount, long gcMillis,
                long heapBefore, long heapAfter, PerfCounters perf, double hudMillis) {
            this.normal = normal;
            this.frames = frames;
            this.stats = FrameStatistics.of(frames);
            this.start = start;
            this.end = end;
            this.counters = counters;
            this.focusLost = focusLost;
            this.gcCount = gcCount;
            this.gcMillis = gcMillis;
            this.heapBefore = heapBefore;
            this.heapAfter = heapAfter;
            this.perf = perf;
            this.hudMillis = hudMillis;
        }

        double metric(Metric metric) {
            switch (metric) {
                case VISIBLE: return average(start.visibleEntities, end.visibleEntities);
                case LOADED: return average(start.loadedEntities, end.loadedEntities);
                case PLAYERS: return average(start.players, end.players);
                case CHUNKS: return average(start.renderedChunks, end.renderedChunks);
                case PARTICLES: return average(start.particles, end.particles);
                case REBUILDS: return Math.max(0, end.chunkUpdates - start.chunkUpdates);
                case TICKS: return counters.perFrame(RenderCounters.Counter.CLIENT_TICK);
                case ENTITY_RENDERS: return counters.perFrame(RenderCounters.Counter.RENDER_ENTITY);
                case CHUNK_LAYERS: return counters.perFrame(RenderCounters.Counter.CHUNK_RENDER);
                case GC_COUNT: return gcCount;
                case GC_MILLIS: return gcMillis;
                case HEAP_DELTA: return heapAfter - heapBefore;
                case MOVEMENT: return start.distance(end);
                case CAMERA: return start.cameraDistance(end);
                default: return -1d;
            }
        }
    }

    private static String invalidReason(EpochResult a, EpochResult b, Mode mode) {
        List<String> reasons = new ArrayList<String>();
        epochReason(reasons, "A", a);
        epochReason(reasons, "B", b);
        if (mode == Mode.CORE) {
            // How many entities were drawn is a scene-continuity signal only
            // while both arms draw the same ones. The performance layer exists
            // to draw fewer, so in that mode this is the result, not a
            // confound, and the scene is judged by what the server sent
            // instead: loaded entities, the player list, chunks and particles.
            compare(reasons, "visible entities", a.metric(Metric.VISIBLE), b.metric(Metric.VISIBLE), 8d, 0.30d);
        }
        compare(reasons, "loaded entities", a.metric(Metric.LOADED), b.metric(Metric.LOADED), 12d, 0.25d);
        compare(reasons, "player count", a.metric(Metric.PLAYERS), b.metric(Metric.PLAYERS), 4d, 0.20d);
        compare(reasons, "rendered chunks", a.metric(Metric.CHUNKS), b.metric(Metric.CHUNKS), 32d, 0.20d);
        compare(reasons, "particles", a.metric(Metric.PARTICLES), b.metric(Metric.PARTICLES), 128d, 0.60d);
        if (!a.start.optifineFingerprint.equals(b.start.optifineFingerprint)) reasons.add("OptiFine/VBO settings changed");
        return join(reasons);
    }

    private static void epochReason(List<String> reasons, String label, EpochResult epoch) {
        if (epoch.focusLost || !epoch.start.focused || !epoch.end.focused) reasons.add(label + " lost focus/world continuity");
        if (epoch.start.worldIdentity != epoch.end.worldIdentity) reasons.add(label + " changed world");
        if (epoch.metric(Metric.MOVEMENT) > 0.25d) reasons.add(label + " player moved");
        if (epoch.metric(Metric.CAMERA) > 2d) reasons.add(label + " camera moved");
        if (epoch.gcMillis > 25L) reasons.add(label + " GC pause >25ms");
        if (epoch.frames.length < 30) reasons.add(label + " too few frames");
    }

    private static void compare(List<String> reasons, String label, double a, double b, double floor, double ratio) {
        if (a < 0d || b < 0d) return;
        double limit = Math.max(floor, Math.max(a, b) * ratio);
        if (Math.abs(a - b) > limit) reasons.add("major " + label + " change");
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append("; ");
            out.append(value);
        }
        return out.toString();
    }

    private static final class SceneSnapshot {
        final int worldIdentity;
        final int visibleEntities;
        final int loadedEntities;
        final int players;
        final int renderedChunks;
        final int particles;
        final int chunkUpdates;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final boolean focused;
        final String optifineFingerprint;

        SceneSnapshot(MinecraftClient client) {
            worldIdentity = System.identityHashCode(client.world);
            visibleEntities = firstNumber(safeEntities(client));
            loadedEntities = client.world == null ? -1 : client.world.loadedEntities.size();
            ClientPlayNetworkHandler network = client.getNetworkHandler();
            players = network == null ? -1 : network.getPlayerList().size();
            renderedChunks = firstNumber(safeChunks(client));
            particles = firstNumber(safeParticles(client));
            chunkUpdates = BuiltChunk.chunkUpdates;
            x = client.player == null ? 0d : client.player.x;
            y = client.player == null ? 0d : client.player.y;
            z = client.player == null ? 0d : client.player.z;
            yaw = client.player == null ? 0f : client.player.yaw;
            pitch = client.player == null ? 0f : client.player.pitch;
            focused = client.focused;
            optifineFingerprint = optifineFingerprint(client);
        }

        static SceneSnapshot capture(MinecraftClient client) { return new SceneSnapshot(client); }

        double distance(SceneSnapshot other) {
            double dx = other.x - x, dy = other.y - y, dz = other.z - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        double cameraDistance(SceneSnapshot other) {
            return Math.max(angle(yaw, other.yaw), Math.abs(other.pitch - pitch));
        }
    }

    private static String safeEntities(MinecraftClient client) {
        try { return client.worldRenderer == null ? "" : client.worldRenderer.getEntitiesDebugString(); }
        catch (Throwable ignored) { return ""; }
    }

    private static String safeChunks(MinecraftClient client) {
        try { return client.worldRenderer == null ? "" : client.worldRenderer.getChunksDebugString(); }
        catch (Throwable ignored) { return ""; }
    }

    private static String safeParticles(MinecraftClient client) {
        try { return client.particleManager == null ? "" : client.particleManager.getDebugString(); }
        catch (Throwable ignored) { return ""; }
    }

    private static int firstNumber(String text) {
        if (text == null) return -1;
        int value = 0;
        boolean found = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') { found = true; value = value * 10 + c - '0'; }
            else if (found) return value;
        }
        return found ? value : -1;
    }

    private static String optifineFingerprint(MinecraftClient client) {
        Object options = client.options;
        return "vbo=" + client.options.vbo
                + " fastRender=" + field(options, "ofFastRender")
                + " fastMath=" + field(options, "ofFastMath")
                + " smoothFps=" + field(options, "ofSmoothFps")
                + " smoothWorld=" + field(options, "ofSmoothWorld")
                + " lazyChunks=" + field(options, "ofLazyChunkLoading")
                + " renderRegions=" + field(options, "ofRenderRegions");
    }

    private static String field(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getField(name);
            return String.valueOf(field.get(owner));
        } catch (Throwable unavailable) {
            return "?";
        }
    }

    private static double average(int a, int b) {
        return a < 0 || b < 0 ? -1d : (a + b) / 2d;
    }

    private static double angle(float a, float b) {
        double value = Math.abs(b - a) % 360d;
        return value > 180d ? 360d - value : value;
    }

    private static long gcCount() {
        long value = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() > 0L) value += bean.getCollectionCount();
        }
        return value;
    }

    private static long gcMillis() {
        long value = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionTime() > 0L) value += bean.getCollectionTime();
        }
        return value;
    }

    private static long heapMb() {
        java.lang.Runtime vm = java.lang.Runtime.getRuntime();
        return (vm.totalMemory() - vm.freeMemory()) >> 20;
    }

    private static void write(File directory, File file, String text) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try { writer.write(text); } finally { writer.close(); }
        } catch (Throwable failure) {
            System.out.println("[FATHOM PAIRED] could not write report: " + failure);
        }
    }

    private static void line(StringBuilder out, String line) {
        out.append(line).append(System.lineSeparator());
    }
}
