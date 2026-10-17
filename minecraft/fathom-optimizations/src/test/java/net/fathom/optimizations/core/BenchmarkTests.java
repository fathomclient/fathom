package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.core.bench.auto.BenchmarkPass;
import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;
import net.fathom.optimizations.core.bench.auto.BenchmarkReportWriter;
import net.fathom.optimizations.core.bench.auto.BenchmarkResult;
import net.fathom.optimizations.core.bench.auto.BenchmarkSubsystem;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.FrameStatistics;

/**
 * The benchmark's own arithmetic, checked where it can be got wrong.
 *
 * <p>Three things here are worth a test rather than a reading: that costs are
 * computed from frame time and not frame rate, that a subsystem the registry
 * cannot switch is never silently counted as free, and that the state a run
 * borrows is given back even when a pass throws.
 */
public final class BenchmarkTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        costsAreFrameTimeNotFrameRate();
        aCostBelowTheNoiseFloorIsNotRanked();
        minimalRetainingMostOfNormalIsReportedAsArchitectural();
        anAbsentSubsystemIsNeverMeasuredAndAlwaysReported();
        anUnswitchableSubsystemIsReportedRatherThanMeasured();
        stateIsRestoredEvenWhenASubsystemThrows();
        countersFlagWorkDoneTwicePerFrame();
        theReportNamesEveryPass();
        theNoiseFloorComesFromTheRunsOwnRepeatedBaselines();
        aSubsystemIsComparedAgainstTheNearestBaselineNotTheFirst();
        aVoidPassIsNeverTheReference();
        warmUpDriftDoesNotProduceASortedListOfNoise();

        System.out.println("BenchmarkTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------

    /**
     * The mistake this whole report format exists to avoid.
     *
     * <p>660 fps to 400 fps is 260 "fps of cost", which is meaningless: the same
     * subsystem on a slower machine would show a smaller fps drop for the same
     * work. Frame time subtracts; frame rate does not.
     */
    static void costsAreFrameTimeNotFrameRate() {
        BenchmarkResult minimal = result("minimal", BenchmarkPass.minimal(), 1.50d);
        BenchmarkResult normal = result("x", isolate("x"), 2.50d);
        check(Math.abs(normal.costOver(minimal) - 1.00d) < 1e-9,
                "cost is the frame-time difference");
        check(Math.abs(normal.costPercentOver(minimal) - 66.6667d) < 1e-3,
                "percent is relative to the baseline frame");
        // And the fps numbers for those frame times are nothing like 1.00 apart.
        check(minimal.averageFps() > normal.averageFps(),
                "the faster pass still reports the higher fps");
    }

    static void aCostBelowTheNoiseFloorIsNotRanked() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.00d));
        results.add(result("minimal", BenchmarkPass.minimal(), 1.00d));
        // A difference far under the floor, and a negative one.
        results.add(result("tiny", isolate("tiny"), 1.01d));
        results.add(result("faster", isolate("faster"), 0.98d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(text.contains("noise floor"), "a sub-floor difference is called noise");
        check(!text.contains("1. Tiny"), "a sub-floor difference is not ranked");
        check(text.contains("inside the noise"), "the subsystem table labels it");
    }

    /**
     * The finding that redirects the whole investigation.
     *
     * <p>When switching everything off barely helps, the per-subsystem table is
     * a distraction and the report has to say so in the summary rather than
     * leaving a reader to notice.
     */
    static void minimalRetainingMostOfNormalIsReportedAsArchitectural() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.00d));
        results.add(result("minimal", BenchmarkPass.minimal(), 1.90d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(text.contains("WARNING"), "an architectural cost is warned about");
        check(text.contains("95%"), "the retained share is stated");
        check(text.contains("Mixin injection overhead")
                        || text.contains("Mixin injection"),
                "the warning names where to look instead");
    }

    static void anAbsentSubsystemIsNeverMeasuredAndAlwaysReported() {
        BenchmarkRegistry registry = new BenchmarkRegistry();
        registry.add(new Fake("cosmetics", "Cosmetics", "absent", false, false, "not built"));
        registry.add(new Fake("hud", "HUD", "hud", true, true, ""));
        check(registry.measurable().size() == 1, "an absent subsystem is not measurable");
        check(registry.absent().size() == 1, "an absent subsystem is still listed");

        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.00d));
        results.add(result("minimal", BenchmarkPass.minimal(), 1.00d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, registry, null, 30L);
        check(text.contains("Not present in this build"), "absence has its own section");
        check(text.contains("not built"), "and states the reason");
    }

    static void anUnswitchableSubsystemIsReportedRatherThanMeasured() {
        BenchmarkRegistry registry = new BenchmarkRegistry();
        registry.add(new Fake("nanovg", "NanoVG", "render", true, false, "decided at class load"));
        check(registry.measurable().isEmpty(), "it cannot be measured");
        check(registry.unswitchable().size() == 1, "it is listed as unswitchable");

        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.00d));
        results.add(result("minimal", BenchmarkPass.minimal(), 1.00d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, registry, null, 30L);
        check(text.contains("not switchable at runtime"),
                "the report says it was not measured");
        check(text.contains("decided at class load"), "and why");
    }

    /**
     * Benchmark mode must not leave a setting changed, including when a pass
     * fails halfway through switching things.
     */
    static void stateIsRestoredEvenWhenASubsystemThrows() {
        BenchmarkRegistry registry = new BenchmarkRegistry();
        Fake good = new Fake("a", "A", "hud", true, true, "");
        Fake bad = new Fake("b", "B", "hud", true, true, "");
        bad.throwOnSet = true;
        Fake other = new Fake("c", "C", "hud", true, true, "");
        good.on = true;
        other.on = false;
        registry.add(good);
        registry.add(bad);
        registry.add(other);

        Map<String, Boolean> snapshot = registry.snapshot();
        good.on = false;
        other.on = true;
        List<String> problems = registry.restore(snapshot);

        check(good.on, "the first subsystem was restored");
        check(!other.on, "a subsystem after the throwing one was still restored");
        check(problems.size() == 1, "the failure is reported rather than swallowed");
        check(problems.get(0).startsWith("b:"), "and names which one");
    }

    static void countersFlagWorkDoneTwicePerFrame() {
        RenderCounters counters = new RenderCounters();
        for (int frame = 0; frame < 100; frame++) {
            counters.hit(RenderCounters.Counter.FRAMES);
            counters.hit(RenderCounters.Counter.HUD_PASS);
            // Two vector frames inside one Minecraft frame: the thing worth catching.
            counters.hit(RenderCounters.Counter.NANOVG_BEGIN);
            counters.hit(RenderCounters.Counter.NANOVG_BEGIN);
            // Eleven widgets is eleven widgets, not a fault.
            for (int widget = 0; widget < 11; widget++) {
                counters.hit(RenderCounters.Counter.HUD_WIDGET);
            }
        }
        List<String> flags = counters.suspicious();
        check(flags.size() == 1, "exactly one counter is suspicious, got " + flags);
        check(flags.get(0).contains("NanoVG beginFrame"), "and it is the doubled one");
        check(flags.get(0).contains("more than once"), "described as duplicated work");
        check(Math.abs(counters.perFrame(RenderCounters.Counter.HUD_WIDGET) - 11d) < 1e-9,
                "a high but expected count is reported, not flagged");
    }

    static void theReportNamesEveryPass() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.00d));
        results.add(result("minimal", BenchmarkPass.minimal(), 1.00d));
        results.add(result("hud", isolate("hud"), 1.40d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(text.contains("Normal Fathom"), "normal appears");
        check(text.contains("Minimal Fathom"), "minimal appears");
        check(text.contains("ALL PASSES"), "the per-pass table exists");

        String json = BenchmarkReportWriter.json(new Environment(), results, 30L);
        check(json.contains("\"meanFrameMillis\""), "json carries frame time");
        check(json.contains("\"onePercentLowFps\""), "json carries the lows");
        check(json.trim().endsWith("}"), "json is closed");
    }

    /**
     * The fix for the run that ranked sixteen subsystems as free.
     *
     * <p>Repeated baseline passes are identical in configuration, so whatever
     * separates them is drift. That spread is the smallest difference the run
     * can resolve, and it has to come from the run rather than a constant.
     */
    static void theNoiseFloorComesFromTheRunsOwnRepeatedBaselines() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.60d));
        results.add(result("minimal", BenchmarkPass.minimal(), 2.60d));
        results.add(result("x", isolate("x"), 2.50d));
        results.add(result("minimal", BenchmarkPass.minimal(), 2.20d));
        // The baselines disagreed by 0.40 ms, so nothing smaller is a result.
        check(Math.abs(BenchmarkReportWriter.noiseFloor(results) - 0.40d) < 1e-6,
                "the floor is the baseline spread");

        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(text.contains("Measured noise floor"), "the floor is stated");
        check(text.contains("nothing here to rank") || text.contains("Nothing measured above"),
                "and nothing is ranked beneath it");

        // A single baseline cannot measure its own spread, so it falls back.
        List<BenchmarkResult> one = new ArrayList<BenchmarkResult>();
        one.add(result("minimal", BenchmarkPass.minimal(), 2.60d));
        check(BenchmarkReportWriter.noiseFloor(one) == BenchmarkReportWriter.NOISE_FLOOR_MILLIS,
                "one baseline falls back to the constant");
    }

    /**
     * Charging a late pass against an early baseline is what made every
     * subsystem look negative: it pays for all the drift in between.
     */
    static void aSubsystemIsComparedAgainstTheNearestBaselineNotTheFirst() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("minimal", BenchmarkPass.minimal(), 3.00d));   // index 0, cold
        results.add(result("a", isolate("a"), 2.10d));                    // index 1
        results.add(result("minimal", BenchmarkPass.minimal(), 2.00d));   // index 2, warm
        results.add(result("b", isolate("b"), 2.05d));                    // index 3

        check(BenchmarkReportWriter.nearestBaseline(results, 3).meanFrameMillis() == 2.00d,
                "a late pass uses the late baseline");
        check(BenchmarkReportWriter.nearestBaseline(results, 1).meanFrameMillis() == 3.00d,
                "an early pass uses the early baseline");
        // Against the first baseline b would read -0.95 ms, which is impossible.
        double nearest = results.get(3).costOver(BenchmarkReportWriter.nearestBaseline(results, 3));
        // 1e-4 rather than 1e-9: the fixture turns millis into whole nanos, so
        // 2.05 ms becomes 2049999 ns. The precision is the fixture's, not the
        // arithmetic's.
        check(Math.abs(nearest - 0.05d) < 1e-4, "the nearest baseline gives a sane cost");
    }

    /** A reference that lost focus must not become the number everything else is built on. */
    static void aVoidPassIsNeverTheReference() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(voidResult(BenchmarkPass.normal()));
        results.add(result("normal", BenchmarkPass.normal(), 2.50d));
        results.add(result("minimal", BenchmarkPass.minimal(), 2.48d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(text.contains("2.500"), "the usable Normal pass is the reference");
        check(text.contains("void"), "the void pass is disclosed");
        check(!text.contains("69."), "the void pass does not become the headline");
    }

    /**
     * The exact shape of the first bad run: every isolated pass measured faster
     * than its baseline because the machine warmed up. The report must refuse to
     * rank rather than sorting the noise.
     */
    static void warmUpDriftDoesNotProduceASortedListOfNoise() {
        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(result("normal", BenchmarkPass.normal(), 2.60d));
        results.add(result("minimal", BenchmarkPass.minimal(), 2.58d));
        results.add(result("a", isolate("a"), 2.33d));
        results.add(result("b", isolate("b"), 2.15d));
        results.add(result("c", isolate("c"), 2.27d));
        results.add(result("minimal", BenchmarkPass.minimal(), 2.14d));
        String text = BenchmarkReportWriter.text(
                new Environment(), results, new BenchmarkRegistry(), null, 30L);
        check(!text.contains("  1. A"), "a negative-cost subsystem is not ranked first");
        check(text.contains("nothing here to rank") || text.contains("Nothing measured above"),
                "the ranking refuses");
        check(text.contains("Drift across the run") || text.contains("Measured noise floor"),
                "and the drift is disclosed");
    }

    // -----------------------------------------------------------------------

    private static BenchmarkResult voidResult(BenchmarkPass pass) {
        return new BenchmarkResult(pass, FrameStatistics.of(new long[0]),
                new LinkedHashMap<String, Double>(), new RenderCounters(),
                0L, 0L, 0L, 0L, "VOID: window lost focus");
    }

    private static BenchmarkPass isolate(String id) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        ids.add(id);
        return BenchmarkPass.isolate(id, capitalise(id), ids);
    }

    private static String capitalise(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** A result whose every frame took exactly {@code millis}. */
    private static BenchmarkResult result(String id, BenchmarkPass pass, double millis) {
        long[] frames = new long[600];
        java.util.Arrays.fill(frames, (long) (millis * 1_000_000d));
        return new BenchmarkResult(pass, FrameStatistics.of(frames),
                new LinkedHashMap<String, Double>(), new RenderCounters(),
                0L, 0L, 100L, 100L, "");
    }

    private static final class Fake implements BenchmarkSubsystem {
        private final String id;
        private final String label;
        private final String group;
        private final boolean present;
        private final boolean toggle;
        private final String reason;
        boolean on = true;
        boolean throwOnSet;

        Fake(String id, String label, String group, boolean present, boolean toggle, String reason) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.present = present;
            this.toggle = toggle;
            this.reason = reason;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public boolean present() {
            return present;
        }

        @Override
        public String absenceReason() {
            return reason;
        }

        @Override
        public boolean runtimeToggle() {
            return toggle;
        }

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public void setEnabled(boolean next) {
            if (throwOnSet) {
                throw new IllegalStateException("cannot switch");
            }
            on = next;
        }

        @Override
        public boolean essential() {
            return false;
        }
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
