package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.bench.view.ViewBucket;
import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewReport;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * The view profiler's accounting, checked where a mistake would move time
 * from one section to another without anyone noticing.
 */
public final class ViewTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        nestedTimeIsChargedToTheSectionThatWasOpen();
        aSectionLeftOpenDoesNotSwallowItsParent();
        theHarnessIsSubtractedFromTheFrame();
        nothingIsTimedWhenTimingIsOff();
        histogramPercentilesLandInTheRightBin();
        deltasAddUpToTheFrameDelta();
        theReportRendersEveryScene();

        System.out.println("ViewTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** What the sink saw for the last completed frame. */
    private static final class Capture implements ViewProbe.Sink {
        long period;
        long[] total;
        long[] child;
        int[] calls;
        long[] counters;
        int tag;
        boolean valid;
        int frames;

        @Override
        public void frame(long periodNanos, long[] total, long[] child, int[] calls, long[] counters,
                long[] packets, int tag, boolean valid, int flags) {
            this.period = periodNanos;
            this.total = total.clone();
            this.child = child.clone();
            this.calls = calls.clone();
            this.counters = counters.clone();
            this.tag = tag;
            this.valid = valid;
            frames++;
        }
    }

    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            // Busy-wait: sleeping would make the test about the scheduler.
        }
    }

    private static Capture frameWith(boolean timing, Runnable body) {
        Capture capture = new Capture();
        ViewProbe.install(capture);
        ViewProbe.tagNextFrame(3, timing, false);
        ViewProbe.loopHead();
        body.run();
        ViewProbe.loopReturn();
        ViewProbe.markFrame(true);
        ViewProbe.loopHead();
        return capture;
    }

    static void nestedTimeIsChargedToTheSectionThatWasOpen() {
        Capture frame = frameWith(true, new Runnable() {
            @Override
            public void run() {
                ViewProbe.enter(ViewSection.WORLD);
                spin(200_000L);
                ViewProbe.enter(ViewSection.SKY);
                spin(300_000L);
                ViewProbe.exit(ViewSection.SKY);
                ViewProbe.exit(ViewSection.WORLD);
            }
        });
        int world = ViewSection.WORLD.ordinal();
        int sky = ViewSection.SKY.ordinal();
        int loop = ViewSection.LOOP.ordinal();
        check(frame.total[sky] >= 300_000L, "the sky's own time is recorded");
        check(frame.child[world] == frame.total[sky], "the sky is the world's child");
        check(frame.total[world] - frame.child[world] >= 200_000L, "the world keeps its self time");
        check(frame.child[loop] == frame.total[world], "the world is the loop's child");
        check(frame.calls[sky] == 1 && frame.calls[world] == 1, "one call each");
        check(frame.tag == 3 && frame.valid, "the tag and validity travel with the frame");
    }

    static void aSectionLeftOpenDoesNotSwallowItsParent() {
        Capture frame = frameWith(true, new Runnable() {
            @Override
            public void run() {
                ViewProbe.enter(ViewSection.ENTITY);
                // A label whose RETURN never ran: another Mixin cancelled it.
                ViewProbe.enter(ViewSection.NAMETAG);
                spin(100_000L);
                ViewProbe.exit(ViewSection.ENTITY);
                ViewProbe.enter(ViewSection.PARTICLES);
                ViewProbe.exit(ViewSection.PARTICLES);
            }
        });
        int entity = ViewSection.ENTITY.ordinal();
        int loop = ViewSection.LOOP.ordinal();
        check(frame.total[entity] >= 100_000L, "the entity still closes");
        check(frame.total[ViewSection.NAMETAG.ordinal()] == 0L, "the unclosed label is dropped, not guessed");
        check(frame.calls[ViewSection.PARTICLES.ordinal()] == 1, "sections after it still time");
        check(frame.child[loop] >= frame.total[entity], "the entity is charged to the loop, not the label");
    }

    static void theHarnessIsSubtractedFromTheFrame() {
        Capture frame = frameWith(true, new Runnable() {
            @Override
            public void run() {
                spin(100_000L);
            }
        });
        // The harness time recorded for the frame includes the hand-over at its head.
        int harness = ViewSection.HARNESS.ordinal();
        check(frame.calls[harness] >= 1, "the hand-over is charged to the harness");
        check(frame.period >= 100_000L, "the period covers the frame");
        check(frame.period - frame.total[harness] > 0L, "the frame is what is left after the harness");
    }

    static void nothingIsTimedWhenTimingIsOff() {
        Capture frame = frameWith(false, new Runnable() {
            @Override
            public void run() {
                ViewProbe.enter(ViewSection.WORLD);
                spin(50_000L);
                ViewProbe.exit(ViewSection.WORLD);
                ViewProbe.count(ViewCounter.UPLOADS, 2L);
            }
        });
        check(frame.total[ViewSection.WORLD.ordinal()] == 0L, "probes-off times nothing");
        check(frame.period >= 50_000L, "but the frame interval is still taken");
        check(frame.counters[ViewCounter.UPLOADS.ordinal()] == 2L,
                "chunk counters still count, so settling works in every arm");
    }

    static void histogramPercentilesLandInTheRightBin() {
        ViewBucket bucket = new ViewBucket("s", "normal");
        long[] total = new long[ViewSection.COUNT];
        long[] child = new long[ViewSection.COUNT];
        int[] calls = new int[ViewSection.COUNT];
        long[] counters = new long[ViewCounter.COUNT];
        int sky = ViewSection.SKY.ordinal();
        for (int frame = 0; frame < 1000; frame++) {
            total[sky] = frame < 950 ? 100_000L : 1_000_000L;
            calls[sky] = 1;
            bucket.add(2_000_000L, total, child, calls, counters);
        }
        double median = bucket.selfMillisAt(ViewSection.SKY, 50d);
        double tail = bucket.selfMillisAt(ViewSection.SKY, 99d);
        check(Math.abs(median - 0.1d) / 0.1d < 0.05d, "median within 5%: " + median);
        check(Math.abs(tail - 1.0d) / 1.0d < 0.05d, "p99 within 5%: " + tail);
        check(Math.abs(bucket.selfMillis(ViewSection.SKY) - 0.145d) < 1e-9, "mean is exact");
        check(Math.abs(bucket.frameMillisAt(50d) - 2.0d) < 1e-9, "frame percentiles are exact");
    }

    private static ViewBucket synthetic(String scene, long sky, long solid, long swap, long frame) {
        ViewBucket bucket = new ViewBucket(scene, ViewReport.NORMAL);
        long[] total = new long[ViewSection.COUNT];
        long[] child = new long[ViewSection.COUNT];
        int[] calls = new int[ViewSection.COUNT];
        long[] counters = new long[ViewCounter.COUNT];
        total[ViewSection.SKY.ordinal()] = sky;
        total[ViewSection.LAYER_SOLID.ordinal()] = solid;
        total[ViewSection.WORLD.ordinal()] = sky + solid + 10_000L;
        child[ViewSection.WORLD.ordinal()] = sky + solid;
        total[ViewSection.SWAP.ordinal()] = swap;
        total[ViewSection.LOOP.ordinal()] = sky + solid + 10_000L + swap + 5_000L;
        child[ViewSection.LOOP.ordinal()] = sky + solid + 10_000L + swap;
        for (ViewSection section : Arrays.asList(ViewSection.SKY, ViewSection.LAYER_SOLID, ViewSection.WORLD,
                ViewSection.SWAP, ViewSection.LOOP)) {
            calls[section.ordinal()] = 1;
        }
        counters[ViewCounter.SECTIONS_SOLID.ordinal()] = solid / 1000L;
        for (int index = 0; index < 100; index++) {
            bucket.add(frame, total, child, calls, counters);
        }
        bucket.window(frame / 1_000_000d, 1d, 0L, 0L, 0L);
        return bucket;
    }

    static void deltasAddUpToTheFrameDelta() {
        ViewBucket sky = synthetic("sky", 50_000L, 20_000L, 100_000L, 300_000L);
        ViewBucket ground = synthetic("down45", 50_000L, 900_000L, 400_000L, 1_500_000L);
        double sum = 0d;
        for (ViewReport.Delta delta : ViewReport.deltas(sky, ground)) {
            sum += delta.change();
        }
        double frameDelta = ground.meanFrameMillis() - sky.meanFrameMillis();
        double untimed = (ground.meanFrameMillis() - attributed(ground))
                - (sky.meanFrameMillis() - attributed(sky));
        check(Math.abs(sum + untimed - frameDelta) < 1e-9, "self deltas plus untimed equal the frame delta");
        ViewReport.Delta largest = ViewReport.deltas(sky, ground).get(0);
        check(largest.section == ViewSection.LAYER_SOLID, "the solid layer leads: " + largest.section);
    }

    private static double attributed(ViewBucket bucket) {
        double sum = 0d;
        for (ViewSection section : ViewSection.ALL) {
            if (section != ViewSection.HARNESS) {
                sum += bucket.selfMillis(section);
            }
        }
        return sum;
    }

    static void theReportRendersEveryScene() {
        Map<String, ViewBucket> buckets = new LinkedHashMap<String, ViewBucket>();
        buckets.put(ViewReport.key("sky", ViewReport.NORMAL), synthetic("sky", 50_000L, 20_000L, 100_000L, 300_000L));
        buckets.put(ViewReport.key("down45", ViewReport.NORMAL),
                synthetic("down45", 50_000L, 900_000L, 400_000L, 1_500_000L));
        Map<String, String> overlays = new LinkedHashMap<String, String>();
        ViewReport report = new ViewReport(Arrays.asList("sky", "down45"),
                Arrays.asList(ViewReport.NORMAL), buckets, Arrays.asList("environment line"), 40d, "sky",
                "down45", overlays);
        String text = report.render();
        check(text.contains("BREAKDOWN: sky") && text.contains("BREAKDOWN: down45"), "both scenes broken down");
        check(text.contains("TOP 10 FRAME-TIME CONSUMERS: down45"), "the focus scene is ranked");
        check(text.contains("down45 - sky: frame +1.200 ms"), "the delta line reads the frame delta");
        check(report.csv().split("\n").length > 2 * ViewSection.COUNT, "the CSV has a row per section");
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
