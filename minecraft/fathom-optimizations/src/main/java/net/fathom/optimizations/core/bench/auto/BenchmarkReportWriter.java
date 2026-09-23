package net.fathom.optimizations.core.bench.auto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fathom.optimizations.core.bench.Environment;

/**
 * Turns the passes into something a person can act on.
 *
 * <h2>What it will and will not claim</h2>
 *
 * <p>A measured difference smaller than the run-to-run spread is not a result,
 * and this writer says so in those words rather than printing it as a cost.
 * Benchmarks of this kind routinely show a subsystem "saving" time; reporting
 * that as a saving is how a diagnosis goes wrong. {@link #NOISE_FLOOR_MILLIS} is
 * the threshold below which a difference is labelled rather than ranked.
 *
 * <h2>The warning that matters most</h2>
 *
 * <p>If Minimal is nearly as slow as Normal, no amount of per-subsystem ranking
 * will help: the cost is in something the benchmark cannot switch off, which
 * means mixins, hooks, the game loop, or the integration with OptiFine. The
 * report leads with that when it happens, because otherwise a reader spends the
 * afternoon optimising the HUD.
 */
public final class BenchmarkReportWriter {

    /**
     * The floor used when the run measured no baseline spread of its own.
     *
     * <p>Only a fallback now. The real floor is {@link #noiseFloor}, computed
     * from how much the repeated baseline passes disagreed with each other
     * during this run -- which is the only honest answer to "how small a
     * difference can this measurement resolve". A constant cannot know that a
     * particular sitting was noisy.
     */
    public static final double NOISE_FLOOR_MILLIS = 0.05d;

    /**
     * How far apart the repeated baseline measurements were, in ms per frame.
     *
     * <p>Minimal is measured several times across a run. Those passes are
     * identical in configuration, so everything separating them is drift: JIT
     * state, driver caches, the scene moving, the machine warming. Whatever that
     * spread is, no per-subsystem difference smaller than it means anything.
     *
     * <p>The first run of this harness needed exactly this. It measured all
     * sixteen subsystems as faster than the baseline, which is impossible, and
     * a floor derived from the run's own repeats would have refused to rank any
     * of them instead of printing a sorted list of noise.
     */
    public static double noiseFloor(List<BenchmarkResult> results) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        int seen = 0;
        for (BenchmarkResult each : results) {
            if (each.pass().kind() != BenchmarkPass.Kind.MINIMAL || each.frames().isEmpty()) {
                continue;
            }
            double millis = each.meanFrameMillis();
            low = Math.min(low, millis);
            high = Math.max(high, millis);
            seen++;
        }
        if (seen < 2) {
            return NOISE_FLOOR_MILLIS;
        }
        return Math.max(NOISE_FLOOR_MILLIS, high - low);
    }

    /**
     * The baseline nearest this pass in time.
     *
     * <p>Not the first one. Comparing a pass measured five minutes in against a
     * baseline measured at the start charges it for everything that drifted in
     * between, which is how a subsystem that costs nothing comes out negative.
     */
    public static BenchmarkResult nearestBaseline(List<BenchmarkResult> results, int index) {
        BenchmarkResult best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int at = 0; at < results.size(); at++) {
            BenchmarkResult each = results.get(at);
            if (each.pass().kind() != BenchmarkPass.Kind.MINIMAL || each.frames().isEmpty()) {
                continue;
            }
            int distance = Math.abs(at - index);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = each;
            }
        }
        return best;
    }

    /** Minimal retaining more than this share of Normal's frame time is flagged. */
    private static final double MINIMAL_RETENTION_WARNING = 0.80d;

    private BenchmarkReportWriter() {}

    public static String text(
            Environment environment,
            List<BenchmarkResult> results,
            BenchmarkRegistry registry,
            List<String> sceneNotes,
            long totalSeconds) {

        StringBuilder out = new StringBuilder(8192);
        BenchmarkResult normal = find(results, "normal");
        BenchmarkResult minimal = find(results, "minimal");
        double floor = noiseFloor(results);
        double drift = driftMillis(results, BenchmarkPass.Kind.NORMAL);
        int voided = 0;
        for (BenchmarkResult each : results) {
            if (each.frames().isEmpty()) {
                voided++;
            }
        }

        line(out, "FATHOM BENCHMARK REPORT");
        line(out, "=======================");
        line(out, "");
        line(out, "Generated  " + timestamp());
        line(out, "Duration   " + totalSeconds + " s across " + results.size() + " passes");
        line(out, "");

        // -- the headline ----------------------------------------------------
        if (normal != null && minimal != null) {
            heading(out, "SUMMARY");
            line(out, String.format(Locale.ROOT, "  Normal Fathom   %7.1f fps   %6.3f ms/frame",
                    normal.averageFps(), normal.meanFrameMillis()));
            line(out, String.format(Locale.ROOT, "  Minimal Fathom  %7.1f fps   %6.3f ms/frame",
                    minimal.averageFps(), minimal.meanFrameMillis()));
            double total = normal.meanFrameMillis() - minimal.meanFrameMillis();
            line(out, String.format(Locale.ROOT,
                    "  Everything Fathom's optional systems cost: %+.3f ms/frame (%.1f%%)",
                    total, normal.meanFrameMillis() > 0d ? total / normal.meanFrameMillis() * 100d : 0d));
            line(out, "");
            line(out, String.format(Locale.ROOT,
                    "  Measured noise floor: %.3f ms/frame (spread between repeated baselines).",
                    floor));
            if (drift > 0d) {
                line(out, String.format(Locale.ROOT,
                        "  Drift across the run: %.3f ms/frame between the first and last Normal.",
                        drift));
            }
            if (Math.abs(total) < floor) {
                line(out, "  NOTE: that total is smaller than this run's own noise floor. Fathom's");
                line(out, "  optional systems did not measurably change the frame. Nothing below");
                line(out, "  can be ranked.");
            }
            if (voided > 0) {
                line(out, "  " + voided + " pass(es) were void (window lost focus) and are excluded.");
            }
            line(out, "");

            double retention = normal.meanFrameMillis() > 0d
                    ? minimal.meanFrameMillis() / normal.meanFrameMillis() : 0d;
            if (retention >= MINIMAL_RETENTION_WARNING) {
                line(out, String.format(Locale.ROOT,
                        "  WARNING: Minimal Fathom retains %.0f%% of Normal Fathom frame time.",
                        retention * 100d));
                line(out, "  Optional UI and render systems are NOT the primary cost. Switching");
                line(out, "  every one of them off changed little, so the time is being spent in");
                line(out, "  something this benchmark cannot switch off. Investigate, in order:");
                line(out, "    - Mixin injection overhead on hot methods (per-call CallbackInfo");
                line(out, "      allocation on anything called thousands of times per frame)");
                line(out, "    - Render hooks that run whether or not a feature is on");
                line(out, "    - OptiFine/OptiFabric integration: patched methods Fathom also");
                line(out, "      touches, or vanilla paths restored in place of OptiFine's");
                line(out, "    - The frame-limit hook and the game loop itself");
                line(out, "    - Client tick and event dispatch");
                line(out, "    - GL state management: synchronous driver queries stall the CPU");
                line(out, "  The per-subsystem table below will not explain the gap.");
                line(out, "");
            }
        } else {
            line(out, "  Normal or Minimal pass missing; per-subsystem costs cannot be computed.");
            line(out, "");
        }

        // -- ranking ---------------------------------------------------------
        heading(out, "TOP PERFORMANCE COSTS");
        List<BenchmarkResult> isolates = new ArrayList<BenchmarkResult>();
        for (BenchmarkResult each : results) {
            if (each.pass().kind() == BenchmarkPass.Kind.ISOLATE) {
                isolates.add(each);
            }
        }
        final java.util.Map<BenchmarkResult, Double> costs =
                new java.util.IdentityHashMap<BenchmarkResult, Double>();
        for (int at = 0; at < results.size(); at++) {
            BenchmarkResult each = results.get(at);
            if (each.pass().kind() != BenchmarkPass.Kind.ISOLATE || each.frames().isEmpty()) {
                continue;
            }
            // Against the baseline nearest in time, not the first one.
            costs.put(each, Double.valueOf(each.costOver(nearestBaseline(results, at))));
        }
        Collections.sort(isolates, new Comparator<BenchmarkResult>() {
            @Override
            public int compare(BenchmarkResult a, BenchmarkResult b) {
                return Double.compare(value(costs, b), value(costs, a));
            }
        });
        if (isolates.isEmpty() || minimal == null) {
            line(out, "  No isolated passes ran.");
        } else {
            int rank = 0;
            for (BenchmarkResult each : isolates) {
                double cost = value(costs, each);
                if (cost < floor) {
                    continue;
                }
                rank++;
                line(out, String.format(Locale.ROOT, "  %d. %s", rank, each.pass().label()));
                line(out, String.format(Locale.ROOT,
                        "       %+.3f ms/frame   (floor for this run: %.3f ms)", cost, floor));
                for (String flag : each.counters().suspicious()) {
                    line(out, "       ! " + flag);
                }
            }
            if (rank == 0) {
                line(out, String.format(Locale.ROOT,
                        "  Nothing measured above this run's %.3f ms noise floor.", floor));
                line(out, "  No optional subsystem changed the frame by more than the baseline");
                line(out, "  passes disagreed with each other. There is nothing here to rank.");
            }
        }
        line(out, "");

        // -- every pass ------------------------------------------------------
        heading(out, "ALL PASSES");
        line(out, String.format(Locale.ROOT,
                "  %-28s %8s %8s %8s %8s %8s %8s %7s",
                "pass", "fps", "mean", "median", "p95", "p99", "1%low", "frames"));
        for (BenchmarkResult each : results) {
            line(out, String.format(Locale.ROOT,
                    "  %-28s %8.1f %8.3f %8.3f %8.3f %8.3f %8.1f %7d",
                    trim(each.pass().label(), 28),
                    each.averageFps(),
                    each.meanFrameMillis(),
                    each.frames().medianFrameTimeMillis(),
                    each.frames().p95FrameTimeMillis(),
                    each.frames().p99FrameTimeMillis(),
                    each.frames().onePercentLowFps(),
                    each.frames().sampleCount()));
        }
        line(out, "  (mean/median/p95/p99 in ms per frame)");
        line(out, "");

        // -- subsystem costs -------------------------------------------------
        if (minimal != null && !isolates.isEmpty()) {
            heading(out, "SUBSYSTEM COST, AGAINST THE NEAREST BASELINE");
            for (BenchmarkResult each : isolates) {
                double cost = value(costs, each);
                String verdict = cost < floor
                        ? "inside the noise"
                        : String.format(Locale.ROOT, "%.0f%% over floor", cost / floor * 100d);
                line(out, String.format(Locale.ROOT, "  %-34s %+8.3f ms   %s",
                        trim(each.pass().label(), 34), cost, verdict));
            }
            line(out, String.format(Locale.ROOT,
                    "  (noise floor for this run: %.3f ms/frame)", floor));
            line(out, "");
        }

        // -- profiler --------------------------------------------------------
        if (normal != null && !normal.sectionMillis().isEmpty()) {
            heading(out, "PROFILER BREAKDOWN (Normal Fathom)");
            for (Map.Entry<String, Double> entry : normal.sectionMillis().entrySet()) {
                line(out, String.format(Locale.ROOT, "  %-28s %8.4f ms/frame",
                        entry.getKey(), entry.getValue()));
            }
            double accounted = 0d;
            for (Double value : normal.sectionMillis().values()) {
                accounted += value.doubleValue();
            }
            line(out, String.format(Locale.ROOT, "  %-28s %8.4f ms/frame",
                    "unaccounted (vanilla+OptiFine+driver)",
                    Math.max(0d, normal.meanFrameMillis() - accounted)));
            line(out, "");
        }

        // -- duplicate work --------------------------------------------------
        heading(out, "CALL COUNTS AND DUPLICATE WORK");
        if (normal != null) {
            RenderCounters counters = normal.counters();
            for (RenderCounters.Counter counter : RenderCounters.Counter.values()) {
                if (counter == RenderCounters.Counter.FRAMES) {
                    continue;
                }
                double rate = counters.perFrame(counter);
                if (rate <= 0d) {
                    continue;
                }
                line(out, String.format(Locale.ROOT, "  %-28s %8.2f per frame",
                        counter.label(), rate));
            }
            List<String> flags = counters.suspicious();
            line(out, "");
            if (flags.isEmpty()) {
                line(out, "  No counter ran more often than one pass per frame.");
            } else {
                for (String flag : flags) {
                    line(out, "  ! " + flag);
                }
            }
        }
        line(out, "");

        // -- memory ----------------------------------------------------------
        heading(out, "MEMORY AND GC");
        for (BenchmarkResult each : results) {
            line(out, String.format(Locale.ROOT,
                    "  %-28s %3d collections, %5d ms paused, heap %4d -> %4d MB",
                    trim(each.pass().label(), 28), each.gcCountDelta(), each.gcMillisDelta(),
                    each.heapUsedBeforeMb(), each.heapUsedAfterMb()));
        }
        line(out, "");

        // -- what could not be measured --------------------------------------
        heading(out, "COVERAGE");
        line(out, "  Measured subsystems:   " + registry.measurable().size());
        List<BenchmarkSubsystem> unswitchable = registry.unswitchable();
        if (!unswitchable.isEmpty()) {
            line(out, "  Present but not switchable at runtime (NOT measured):");
            for (BenchmarkSubsystem each : unswitchable) {
                line(out, "    - " + each.label() + " -- " + each.absenceReason());
            }
        }
        List<BenchmarkSubsystem> absent = registry.absent();
        if (!absent.isEmpty()) {
            line(out, "  Not present in this build (nothing to measure):");
            for (BenchmarkSubsystem each : absent) {
                line(out, "    - " + each.label() + " -- " + each.absenceReason());
            }
        }
        line(out, "");

        // -- scene -----------------------------------------------------------
        if (sceneNotes != null && !sceneNotes.isEmpty()) {
            heading(out, "SCENE STABILITY");
            for (String note : sceneNotes) {
                line(out, "  " + note);
            }
            line(out, "");
        }

        // -- environment -----------------------------------------------------
        heading(out, "ENVIRONMENT");
        for (Map.Entry<String, String> entry : environment.all().entrySet()) {
            line(out, String.format(Locale.ROOT, "  %-20s %s", entry.getKey(), entry.getValue()));
        }
        line(out, "");

        heading(out, "NEXT INVESTIGATION");
        line(out, "  " + nextStep(normal, minimal, isolates));
        line(out, "");
        return out.toString();
    }

    /** One sentence naming what the data actually supports looking at next. */
    private static String nextStep(
            BenchmarkResult normal, BenchmarkResult minimal, List<BenchmarkResult> isolates) {
        if (normal == null || minimal == null) {
            return "Re-run: the Normal or Minimal pass did not complete.";
        }
        double retention = normal.meanFrameMillis() > 0d
                ? minimal.meanFrameMillis() / normal.meanFrameMillis() : 0d;
        if (retention >= MINIMAL_RETENTION_WARNING) {
            return "Minimal is within " + Math.round((1d - retention) * 100d)
                    + "% of Normal. Stop looking at optional subsystems and profile the "
                    + "always-on path: mixin injection sites on hot methods, render hooks "
                    + "that run unconditionally, and the OptiFine integration.";
        }
        for (BenchmarkResult each : isolates) {
            if (each.costOver(minimal) >= NOISE_FLOOR_MILLIS) {
                return "Start with " + each.pass().label() + " ("
                        + String.format(Locale.ROOT, "%+.3f ms/frame", each.costOver(minimal))
                        + "), the largest measured cost above the noise floor.";
            }
        }
        return "No single subsystem cleared the noise floor. The gap between Normal and "
                + "Minimal is real but spread thinly; measure again with longer passes "
                + "before attributing it.";
    }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    public static String json(
            Environment environment, List<BenchmarkResult> results, long totalSeconds) {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n");
        out.append("  \"generated\": \"").append(escape(timestamp())).append("\",\n");
        out.append("  \"durationSeconds\": ").append(totalSeconds).append(",\n");
        out.append("  \"environment\": ").append(environment.toJson("  ")).append(",\n");
        out.append("  \"passes\": [\n");
        for (int index = 0; index < results.size(); index++) {
            BenchmarkResult each = results.get(index);
            out.append("    {\n");
            out.append("      \"id\": \"").append(escape(each.pass().id())).append("\",\n");
            out.append("      \"label\": \"").append(escape(each.pass().label())).append("\",\n");
            out.append("      \"kind\": \"").append(each.pass().kind()).append("\",\n");
            out.append("      \"averageFps\": ").append(round(each.averageFps())).append(",\n");
            out.append("      \"meanFrameMillis\": ").append(round(each.meanFrameMillis())).append(",\n");
            out.append("      \"medianFrameMillis\": ")
                    .append(round(each.frames().medianFrameTimeMillis())).append(",\n");
            out.append("      \"p95FrameMillis\": ")
                    .append(round(each.frames().p95FrameTimeMillis())).append(",\n");
            out.append("      \"p99FrameMillis\": ")
                    .append(round(each.frames().p99FrameTimeMillis())).append(",\n");
            out.append("      \"minFrameMillis\": ")
                    .append(round(each.frames().minFrameTimeMillis())).append(",\n");
            out.append("      \"maxFrameMillis\": ")
                    .append(round(each.frames().maxFrameTimeMillis())).append(",\n");
            out.append("      \"onePercentLowFps\": ")
                    .append(round(each.frames().onePercentLowFps())).append(",\n");
            out.append("      \"frames\": ").append(each.frames().sampleCount()).append(",\n");
            out.append("      \"gcCollections\": ").append(each.gcCountDelta()).append(",\n");
            out.append("      \"gcPauseMillis\": ").append(each.gcMillisDelta()).append(",\n");
            out.append("      \"heapBeforeMb\": ").append(each.heapUsedBeforeMb()).append(",\n");
            out.append("      \"heapAfterMb\": ").append(each.heapUsedAfterMb()).append(",\n");
            out.append("      \"sections\": {");
            boolean first = true;
            for (Map.Entry<String, Double> entry : each.sectionMillis().entrySet()) {
                if (!first) {
                    out.append(",");
                }
                first = false;
                out.append("\n        \"").append(escape(entry.getKey())).append("\": ")
                        .append(round(entry.getValue().doubleValue()));
            }
            out.append(first ? "}" : "\n      }").append(",\n");
            out.append("      \"counters\": {");
            first = true;
            for (RenderCounters.Counter counter : RenderCounters.Counter.values()) {
                double rate = each.counters().perFrame(counter);
                if (rate <= 0d) {
                    continue;
                }
                if (!first) {
                    out.append(",");
                }
                first = false;
                out.append("\n        \"").append(counter.name()).append("PerFrame\": ")
                        .append(round(rate));
            }
            out.append(first ? "}" : "\n      }").append("\n");
            out.append("    }").append(index + 1 < results.size() ? "," : "").append("\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    // -----------------------------------------------------------------------

    private static double value(java.util.Map<BenchmarkResult, Double> costs, BenchmarkResult key) {
        Double found = costs.get(key);
        return found == null ? Double.NEGATIVE_INFINITY : found.doubleValue();
    }

    /** How far the first and last pass of a repeated kind disagreed. */
    private static double driftMillis(List<BenchmarkResult> results, BenchmarkPass.Kind kind) {
        BenchmarkResult first = null;
        BenchmarkResult last = null;
        for (BenchmarkResult each : results) {
            if (each.pass().kind() != kind || each.frames().isEmpty()) {
                continue;
            }
            if (first == null) {
                first = each;
            }
            last = each;
        }
        if (first == null || last == null || first == last) {
            return 0d;
        }
        return Math.abs(last.meanFrameMillis() - first.meanFrameMillis());
    }

    /**
     * The first usable pass with this id.
     *
     * <p>Skips void passes, so a reference that lost focus does not become the
     * number the whole report is built on -- which is exactly what happened the
     * first time this ran.
     */
    private static BenchmarkResult find(List<BenchmarkResult> results, String id) {
        for (BenchmarkResult each : results) {
            if (each.pass().id().equals(id) && !each.frames().isEmpty()) {
                return each;
            }
        }
        return null;
    }

    private static void heading(StringBuilder out, String text) {
        line(out, text);
        StringBuilder rule = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            rule.append('-');
        }
        line(out, rule.toString());
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    private static String trim(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        return text.substring(0, width - 1) + "…";
    }

    private static String round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String timestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(new java.util.Date());
    }

    /** The stem both files share, so the pair is obviously one run. */
    public static String fileStem() {
        return "fathom-benchmark-"
                + new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT)
                        .format(new java.util.Date());
    }
}
