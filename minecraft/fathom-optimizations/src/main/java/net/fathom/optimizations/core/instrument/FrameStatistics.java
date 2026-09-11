package net.fathom.optimizations.core.instrument;

import java.util.Arrays;

/**
 * Frame-timing statistics over a fixed set of samples.
 *
 * <h2>Why the definitions are written down</h2>
 *
 * <p>"1% low FPS" and "99th percentile frame time" are different numbers that people use
 * interchangeably, and comparing one against the other silently produces nonsense. This
 * class computes both and states which is which:
 *
 * <ul>
 *   <li><strong>p99 frame time</strong> — the single frame at the 99th percentile. One
 *       sample.
 *   <li><strong>0.1% low FPS</strong> — the <em>mean</em> of the slowest 0.1% of frames,
 *       expressed as a frame rate. An average over many samples, and the metric the
 *       benchmarking community means by "lows".
 * </ul>
 *
 * <p>The second is the more useful one for stutter, because a single unlucky frame moves
 * a percentile but not a mean-of-worst. Both are reported so that a comparison against
 * someone else's numbers can use whichever they measured.
 *
 * <h2>Averages</h2>
 *
 * <p>"Average FPS" is computed as {@code sampleCount / totalTime}, not as the mean of
 * per-frame rates. Those differ, and the mean-of-rates is wrong: it weights a 200 FPS
 * frame the same as a 20 FPS frame despite the latter occupying ten times the wall clock.
 *
 * <p>Immutable, and computed once from a snapshot. Nothing here touches the game.
 */
public final class FrameStatistics {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final int sampleCount;
    private final long totalNanos;
    private final long[] sortedNanos;

    private FrameStatistics(int sampleCount, long totalNanos, long[] sortedNanos) {
        this.sampleCount = sampleCount;
        this.totalNanos = totalNanos;
        this.sortedNanos = sortedNanos;
    }

    /**
     * @param frameNanos frame durations in nanoseconds; copied, not retained
     */
    public static FrameStatistics of(long[] frameNanos) {
        if (frameNanos == null || frameNanos.length == 0) {
            return new FrameStatistics(0, 0L, new long[0]);
        }
        long[] sorted = Arrays.copyOf(frameNanos, frameNanos.length);
        Arrays.sort(sorted);

        long total = 0L;
        for (long nanos : sorted) {
            total += nanos;
        }
        return new FrameStatistics(sorted.length, total, sorted);
    }

    public boolean isEmpty() {
        return sampleCount == 0;
    }

    public int sampleCount() {
        return sampleCount;
    }

    public double totalSeconds() {
        return totalNanos / NANOS_PER_SECOND;
    }

    /**
     * Frames divided by elapsed time.
     *
     * <p>Not the mean of per-frame rates. See the class note.
     */
    public double averageFps() {
        if (sampleCount == 0 || totalNanos == 0) {
            return 0.0;
        }
        return sampleCount / totalSeconds();
    }

    public double meanFrameTimeMillis() {
        if (sampleCount == 0) {
            return 0.0;
        }
        return (totalNanos / (double) sampleCount) / 1_000_000.0;
    }

    public double medianFrameTimeMillis() {
        return percentileFrameTimeMillis(50.0);
    }

    /**
     * The frame time at a percentile, by nearest-rank.
     *
     * <p>Nearest-rank rather than interpolated: an interpolated percentile invents a
     * frame time that never occurred, which is the wrong thing to report about a
     * discrete series of real events.
     *
     * @param percentile 0 to 100
     */
    public double percentileFrameTimeMillis(double percentile) {
        if (sampleCount == 0) {
            return 0.0;
        }
        if (percentile <= 0.0) {
            return sortedNanos[0] / 1_000_000.0;
        }
        if (percentile >= 100.0) {
            return sortedNanos[sampleCount - 1] / 1_000_000.0;
        }
        int rank = (int) Math.ceil((percentile / 100.0) * sampleCount);
        int index = Math.min(Math.max(rank - 1, 0), sampleCount - 1);
        return sortedNanos[index] / 1_000_000.0;
    }

    public double p95FrameTimeMillis() {
        return percentileFrameTimeMillis(95.0);
    }

    public double p99FrameTimeMillis() {
        return percentileFrameTimeMillis(99.0);
    }

    /**
     * The mean of the slowest {@code fraction} of frames, as a frame rate.
     *
     * <p>This is what "1% low" means in the benchmarking community: take the worst 1% of
     * frames, average their durations, and express that as FPS. It is deliberately not
     * the 1st percentile of per-frame FPS values.
     *
     * <p>Returns 0 when there are too few samples for the slice to contain anything. A
     * "0.1% low" over 200 frames would be a single frame, which is a percentile wearing a
     * different name; {@link #hasEnoughSamplesFor} lets a caller check before trusting it.
     *
     * @param fraction e.g. 0.01 for 1%, 0.001 for 0.1%
     */
    public double lowFps(double fraction) {
        if (sampleCount == 0 || fraction <= 0.0 || fraction > 1.0) {
            return 0.0;
        }
        int slice = (int) Math.floor(sampleCount * fraction);
        if (slice < 1) {
            return 0.0;
        }
        long sum = 0L;
        for (int i = sampleCount - slice; i < sampleCount; i++) {
            sum += sortedNanos[i];
        }
        double meanNanos = sum / (double) slice;
        if (meanNanos <= 0.0) {
            return 0.0;
        }
        return NANOS_PER_SECOND / meanNanos;
    }

    public double onePercentLowFps() {
        return lowFps(0.01);
    }

    public double pointOnePercentLowFps() {
        return lowFps(0.001);
    }

    /**
     * Whether a "low" figure at this fraction covers enough frames to mean anything.
     *
     * <p>A slice of fewer than about 10 frames is a percentile with a misleading name.
     * Reporting it without saying so is how a benchmark ends up comparing noise.
     */
    public boolean hasEnoughSamplesFor(double fraction) {
        return Math.floor(sampleCount * fraction) >= 10;
    }

    public double minFrameTimeMillis() {
        return sampleCount == 0 ? 0.0 : sortedNanos[0] / 1_000_000.0;
    }

    public double maxFrameTimeMillis() {
        return sampleCount == 0 ? 0.0 : sortedNanos[sampleCount - 1] / 1_000_000.0;
    }

    /**
     * How much frame times vary, as a fraction of the mean.
     *
     * <p>A single number for "is this smooth". Two runs with the same average FPS and
     * very different coefficients of variation feel completely different to play, which is
     * the whole reason percentile metrics are in this class at all.
     */
    public double frameTimeCoefficientOfVariation() {
        if (sampleCount < 2) {
            return 0.0;
        }
        double mean = totalNanos / (double) sampleCount;
        if (mean <= 0.0) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (long nanos : sortedNanos) {
            double delta = nanos - mean;
            sumSquares += delta * delta;
        }
        return Math.sqrt(sumSquares / (sampleCount - 1)) / mean;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "no frames";
        }
        return String.format(
                "%d frames over %.2fs: avg %.1f fps, median %.2f ms, p95 %.2f ms, p99 %.2f ms,"
                        + " 1%% low %.1f fps, 0.1%% low %.1f fps",
                sampleCount,
                totalSeconds(),
                averageFps(),
                medianFrameTimeMillis(),
                p95FrameTimeMillis(),
                p99FrameTimeMillis(),
                onePercentLowFps(),
                pointOnePercentLowFps());
    }
}
