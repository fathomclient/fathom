package net.fathom.optimizations.core.bench.auto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.fathom.optimizations.core.instrument.FrameStatistics;

/**
 * What one pass measured.
 *
 * <h2>Frame time, not frame rate</h2>
 *
 * <p>Every comparison in the report is made on {@link #meanFrameMillis}, because
 * frame rate does not subtract. Going from 660 fps to 400 is a loss of 260 fps
 * and also of 0.98 ms; the second number is the one that can be attributed to a
 * subsystem, added up, or compared against another machine. A report that ranks
 * subsystems by "fps cost" ranks them by how fast the machine already was.
 */
public final class BenchmarkResult {

    private final BenchmarkPass pass;
    private final FrameStatistics frames;
    private final Map<String, Double> sectionMillis;
    private final RenderCounters counters;
    private final long gcCountDelta;
    private final long gcMillisDelta;
    private final long heapUsedBeforeMb;
    private final long heapUsedAfterMb;
    private final String note;

    public BenchmarkResult(
            BenchmarkPass pass,
            FrameStatistics frames,
            Map<String, Double> sectionMillis,
            RenderCounters counters,
            long gcCountDelta,
            long gcMillisDelta,
            long heapUsedBeforeMb,
            long heapUsedAfterMb,
            String note) {
        this.pass = pass;
        this.frames = frames;
        this.sectionMillis = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(sectionMillis));
        this.counters = counters;
        this.gcCountDelta = gcCountDelta;
        this.gcMillisDelta = gcMillisDelta;
        this.heapUsedBeforeMb = heapUsedBeforeMb;
        this.heapUsedAfterMb = heapUsedAfterMb;
        this.note = note == null ? "" : note;
    }

    public BenchmarkPass pass() {
        return pass;
    }

    public FrameStatistics frames() {
        return frames;
    }

    public double meanFrameMillis() {
        return frames.meanFrameTimeMillis();
    }

    public double averageFps() {
        return frames.averageFps();
    }

    /** Fathom's own instrumented sections, in milliseconds per frame. */
    public Map<String, Double> sectionMillis() {
        return sectionMillis;
    }

    public RenderCounters counters() {
        return counters;
    }

    public long gcCountDelta() {
        return gcCountDelta;
    }

    public long gcMillisDelta() {
        return gcMillisDelta;
    }

    public long heapUsedBeforeMb() {
        return heapUsedBeforeMb;
    }

    public long heapUsedAfterMb() {
        return heapUsedAfterMb;
    }

    public String note() {
        return note;
    }

    /**
     * What this pass cost over a baseline, in milliseconds per frame.
     *
     * <p>Signed: a pass that measured faster than the baseline returns a
     * negative number, and the report prints it rather than clamping. A negative
     * cost for a subsystem that should cost something is information -- it means
     * the difference is inside the noise, and the report says so rather than
     * dressing it up as a tiny saving.
     */
    public double costOver(BenchmarkResult baseline) {
        if (baseline == null) {
            return 0d;
        }
        return meanFrameMillis() - baseline.meanFrameMillis();
    }

    /** That cost as a percentage of the baseline's frame time. */
    public double costPercentOver(BenchmarkResult baseline) {
        if (baseline == null || baseline.meanFrameMillis() <= 0d) {
            return 0d;
        }
        return costOver(baseline) / baseline.meanFrameMillis() * 100d;
    }
}
