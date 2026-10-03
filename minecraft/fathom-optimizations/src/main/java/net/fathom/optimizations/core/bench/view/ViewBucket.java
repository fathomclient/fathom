package net.fathom.optimizations.core.bench.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything measured for one scene under one arm, pooled across its windows.
 *
 * <p>Frame times are kept exactly, so median and tail percentiles are exact.
 * Sections are kept as sums (for means) and as a log histogram of per-frame
 * self time (for medians and p95s): an exact per-frame record of forty-odd
 * sections over tens of thousands of frames per bucket would cost hundreds of
 * megabytes, and a 4% bin is far finer than the differences this is looking
 * for.
 */
public final class ViewBucket {

    /** Sixteen bins per doubling: about 4.4% wide. */
    private static final int SUB = 16;
    private static final int BINS = 40 * SUB;

    public final String scene;
    public final String arm;

    private long[] frames = new long[8192];
    private int frameCount;
    private int rejected;
    private int unfocused;

    final long[] totalSum = new long[ViewSection.COUNT];
    final long[] selfSum = new long[ViewSection.COUNT];
    final long[] callSum = new long[ViewSection.COUNT];
    final long[] counterSum = new long[ViewCounter.COUNT];
    private final int[][] selfHistogram = new int[ViewSection.COUNT][];

    /** Mean frame time of each window, to show drift and window-to-window spread. */
    final List<Double> windowMeans = new ArrayList<Double>();
    long gcCount;
    long gcMillis;
    long allocatedBytes;
    double measuredSeconds;

    public ViewBucket(String scene, String arm) {
        this.scene = scene;
        this.arm = arm;
    }

    /** One accepted frame. Runs on the client thread at the loop head; no allocation in the steady state. */
    public void add(long frameNanos, long[] total, long[] child, int[] calls, long[] counters) {
        if (frameCount == frames.length) {
            frames = Arrays.copyOf(frames, frames.length * 2);
        }
        frames[frameCount++] = frameNanos;
        for (int index = 0; index < ViewSection.COUNT; index++) {
            long self = total[index] - child[index];
            if (self < 0L) {
                self = 0L;
            }
            totalSum[index] += total[index];
            selfSum[index] += self;
            callSum[index] += calls[index];
            if (total[index] != 0L || calls[index] != 0) {
                int[] histogram = selfHistogram[index];
                if (histogram == null) {
                    histogram = new int[BINS];
                    selfHistogram[index] = histogram;
                }
                histogram[bin(self)]++;
            }
        }
        for (int index = 0; index < ViewCounter.COUNT; index++) {
            counterSum[index] += counters[index];
        }
    }

    public void reject() {
        rejected++;
    }

    /** A frame drawn while the window did not have focus. Counted, not rejected. */
    public void unfocused() {
        unfocused++;
    }

    public int unfocusedFrames() {
        return unfocused;
    }

    public void window(double meanFrameMillis, double seconds, long gcCollections, long gcPauseMillis,
            long allocated) {
        windowMeans.add(Double.valueOf(meanFrameMillis));
        measuredSeconds += seconds;
        gcCount += gcCollections;
        gcMillis += gcPauseMillis;
        allocatedBytes += allocated;
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    public int frames() {
        return frameCount;
    }

    public int rejected() {
        return rejected;
    }

    public long[] frameNanos() {
        return Arrays.copyOf(frames, frameCount);
    }

    public double meanFrameMillis() {
        if (frameCount == 0) {
            return 0d;
        }
        long sum = 0L;
        for (int index = 0; index < frameCount; index++) {
            sum += frames[index];
        }
        return sum / 1_000_000d / frameCount;
    }

    /** Standard deviation of frame time: the pacing figure a frame cap is judged on. */
    public double frameStddevMillis() {
        if (frameCount < 2) {
            return 0d;
        }
        double mean = meanFrameMillis() * 1_000_000d;
        double square = 0d;
        for (int index = 0; index < frameCount; index++) {
            double difference = frames[index] - mean;
            square += difference * difference;
        }
        return Math.sqrt(square / (frameCount - 1)) / 1_000_000d;
    }

    /** Exact, nearest-rank. */
    public double frameMillisAt(double percentile) {
        if (frameCount == 0) {
            return 0d;
        }
        long[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100d * sorted.length) - 1;
        rank = Math.max(0, Math.min(sorted.length - 1, rank));
        return sorted[rank] / 1_000_000d;
    }

    public double totalMillis(ViewSection section) {
        return frameCount == 0 ? 0d : totalSum[section.ordinal()] / 1_000_000d / frameCount;
    }

    public double selfMillis(ViewSection section) {
        return frameCount == 0 ? 0d : selfSum[section.ordinal()] / 1_000_000d / frameCount;
    }

    public double callsPerFrame(ViewSection section) {
        return frameCount == 0 ? 0d : callSum[section.ordinal()] / (double) frameCount;
    }

    public double counterPerFrame(ViewCounter counter) {
        return frameCount == 0 ? 0d : counterSum[counter.ordinal()] / (double) frameCount;
    }

    /** Timed enter/exit pairs per frame, across every section. What calibration multiplies. */
    public double probePairsPerFrame() {
        if (frameCount == 0) {
            return 0d;
        }
        long sum = 0L;
        for (int index = 0; index < ViewSection.COUNT; index++) {
            if (index != ViewSection.HARNESS.ordinal()) {
                sum += callSum[index];
            }
        }
        return sum / (double) frameCount;
    }

    /**
     * A percentile of per-frame self time, from the histogram: the bin's
     * midpoint. Counts only frames in which the section ran, so a section that
     * runs every third frame reports what it costs when it runs.
     */
    public double selfMillisAt(ViewSection section, double percentile) {
        int[] histogram = selfHistogram[section.ordinal()];
        if (histogram == null) {
            return 0d;
        }
        long count = 0L;
        for (int value : histogram) {
            count += value;
        }
        if (count == 0L) {
            return 0d;
        }
        long target = (long) Math.ceil(percentile / 100d * count);
        long seen = 0L;
        for (int bin = 0; bin < histogram.length; bin++) {
            seen += histogram[bin];
            if (seen >= target) {
                return binMidpoint(bin) / 1_000_000d;
            }
        }
        return binMidpoint(histogram.length - 1) / 1_000_000d;
    }

    /** Frames in which the section ran at all. */
    public long framesWith(ViewSection section) {
        int[] histogram = selfHistogram[section.ordinal()];
        if (histogram == null) {
            return 0L;
        }
        long count = 0L;
        for (int value : histogram) {
            count += value;
        }
        return count;
    }

    public List<Double> windowMeans() {
        return windowMeans;
    }

    /** How long a window lasted on average: fixed for a view, the rebuild time for a reload. */
    public double meanWindowSeconds() {
        return windowMeans.isEmpty() ? 0d : measuredSeconds / windowMeans.size();
    }

    public long gcCount() {
        return gcCount;
    }

    public long gcMillis() {
        return gcMillis;
    }

    public double allocationMegabytesPerSecond() {
        return measuredSeconds <= 0d ? 0d : allocatedBytes / measuredSeconds / (1024d * 1024d);
    }

    // -----------------------------------------------------------------------
    // Histogram
    // -----------------------------------------------------------------------

    /** Integer-only: the exponent and the next four bits of the mantissa. */
    static int bin(long nanos) {
        if (nanos < SUB) {
            return (int) Math.max(0L, nanos);
        }
        int exponent = 63 - Long.numberOfLeadingZeros(nanos);
        int mantissa = (int) ((nanos >>> (exponent - 4)) & (SUB - 1));
        int bin = (exponent - 3) * SUB + mantissa;
        return Math.min(BINS - 1, bin);
    }

    static double binMidpoint(int bin) {
        if (bin < SUB) {
            return bin;
        }
        int exponent = bin / SUB + 3;
        int mantissa = bin % SUB;
        double low = (SUB + mantissa) * Math.pow(2d, exponent - 4);
        double width = Math.pow(2d, exponent - 4);
        return low + width / 2d;
    }
}
