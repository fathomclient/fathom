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
    final long[] packetSum = new long[PacketKind.COUNT * PacketKind.STRIDE];
    /** The most packets applied in any one frame. */
    long packetPeak;

    /**
     * Sections recorded frame by frame (total time, in microseconds as floats),
     * so the tail can be explained: what the slowest 1% of frames contained that
     * the median frames did not. A fixed handful, not every section: forty-odd
     * per frame over tens of thousands of frames is hundreds of megabytes.
     */
    public static final ViewSection[] TAIL = {
        ViewSection.TICK, ViewSection.TICK_ENTITIES, ViewSection.TICK_PLAYER, ViewSection.TICK_PARTICLES,
        ViewSection.TASKS, ViewSection.CHUNK_UPDATES, ViewSection.SETUP_TERRAIN, ViewSection.ENTITIES,
        ViewSection.ENTITY_PLAYER, ViewSection.MODEL, ViewSection.FEATURES, ViewSection.ARMOR,
        ViewSection.HELD_ITEM, ViewSection.NAMETAG_DECIDE, ViewSection.NAMETAG, ViewSection.TEXT,
        ViewSection.ENTITY, ViewSection.ENTITY_ITEM, ViewSection.BLOCK_ENTITY,
        ViewSection.PARTICLES, ViewSection.HUD, ViewSection.HUD_TABLIST, ViewSection.HUD_CHAT,
        ViewSection.FATHOM_HUD, ViewSection.SWAP,
    };
    private float[][] tail = new float[TAIL.length][8192];
    /** Per frame: packets applied (low 16 bits), ticks (next 8), flags (top 8). */
    private int[] frameMeta = new int[8192];
    private final int[][] selfHistogram = new int[ViewSection.COUNT][];

    /** Mean frame time of each window, to show drift and window-to-window spread. */
    final List<Double> windowMeans = new ArrayList<Double>();
    long gcCount;
    long gcMillis;
    long allocatedBytes;
    /** Bytes allocated by the client thread alone, where the JVM can say. -1 when it cannot. */
    long clientAllocatedBytes;
    boolean clientAllocationKnown = true;
    double measuredSeconds;

    public ViewBucket(String scene, String arm) {
        this.scene = scene;
        this.arm = arm;
    }

    /** One accepted frame. Runs on the client thread at the loop head; no allocation in the steady state. */
    public void add(long frameNanos, long[] total, long[] child, int[] calls, long[] counters) {
        add(frameNanos, total, child, calls, counters, null, 0);
    }

    /** One accepted frame, with its packet figures and harness flags. */
    public void add(long frameNanos, long[] total, long[] child, int[] calls, long[] counters, long[] packets,
            int flags) {
        if (frameCount == frames.length) {
            frames = Arrays.copyOf(frames, frames.length * 2);
            frameMeta = Arrays.copyOf(frameMeta, frames.length);
            for (int at = 0; at < tail.length; at++) {
                tail[at] = Arrays.copyOf(tail[at], frames.length);
            }
        }
        for (int at = 0; at < TAIL.length; at++) {
            tail[at][frameCount] = total[TAIL[at].ordinal()] / 1000f;
        }
        long applied = 0L;
        if (packets != null) {
            for (int index = 0; index < packets.length; index++) {
                packetSum[index] += packets[index];
            }
            for (int kind = 0; kind < PacketKind.COUNT; kind++) {
                if (kind != PacketKind.NOT_A_PACKET.ordinal()) {
                    applied += packets[kind * PacketKind.STRIDE + PacketKind.APPLIED];
                }
            }
            packetPeak = Math.max(packetPeak, applied);
        }
        long ticks = counters[ViewCounter.TICKS.ordinal()];
        frameMeta[frameCount] = (int) Math.min(0xFFFFL, applied) | (int) (Math.min(0xFFL, ticks) << 16)
                | ((flags & 0xFF) << 24);
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

    /** Per-frame mean of one packet figure for one kind. */
    public double packetPerFrame(PacketKind kind, int slot) {
        return frameCount == 0 ? 0d : packetSum[kind.ordinal() * PacketKind.STRIDE + slot] / (double) frameCount;
    }

    /** Packets of one kind per second of measured frames. */
    public double packetPerSecond(PacketKind kind, int slot) {
        long nanos = 0L;
        for (int index = 0; index < frameCount; index++) {
            nanos += frames[index];
        }
        return nanos <= 0L ? 0d : packetSum[kind.ordinal() * PacketKind.STRIDE + slot] / (nanos / 1e9d);
    }

    public long packetPeak() {
        return packetPeak;
    }

    /**
     * What a band of frames contained, by frame-time rank. Returns, for frames
     * whose time lies between the {@code fromPercentile} and {@code toPercentile}
     * ranks: [0] mean frame ms, [1] share with a client tick, [2] share with a
     * GC, [3] mean packets applied, then the mean of each {@link #TAIL} section
     * in ms.
     */
    public double[] band(double fromPercentile, double toPercentile) {
        double[] out = new double[4 + TAIL.length];
        if (frameCount == 0) {
            return out;
        }
        Integer[] order = new Integer[frameCount];
        for (int index = 0; index < frameCount; index++) {
            order[index] = Integer.valueOf(index);
        }
        final long[] times = frames;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(times[a.intValue()], times[b.intValue()]);
            }
        });
        int from = (int) Math.floor(fromPercentile / 100d * frameCount);
        int to = Math.max(from + 1, (int) Math.ceil(toPercentile / 100d * frameCount));
        to = Math.min(frameCount, to);
        int count = 0;
        for (int rank = from; rank < to; rank++) {
            int frame = order[rank].intValue();
            int meta = frameMeta[frame];
            out[0] += frames[frame] / 1_000_000d;
            out[1] += ((meta >>> 16) & 0xFF) > 0 ? 1d : 0d;
            out[2] += ((meta >>> 24) & ViewProbe.FLAG_GC) != 0 ? 1d : 0d;
            out[3] += meta & 0xFFFF;
            for (int at = 0; at < TAIL.length; at++) {
                out[4 + at] += tail[at][frame] / 1000d;
            }
            count++;
        }
        for (int index = 0; index < out.length; index++) {
            out[index] /= Math.max(1, count);
        }
        return out;
    }

    /** Mean frame ms of frames with a client tick and without one: [with, without, share with]. */
    public double[] tickSplit() {
        double with = 0d;
        double without = 0d;
        int withCount = 0;
        for (int index = 0; index < frameCount; index++) {
            if (((frameMeta[index] >>> 16) & 0xFF) > 0) {
                with += frames[index];
                withCount++;
            } else {
                without += frames[index];
            }
        }
        int withoutCount = frameCount - withCount;
        return new double[] {
            withCount == 0 ? 0d : with / withCount / 1_000_000d,
            withoutCount == 0 ? 0d : without / withoutCount / 1_000_000d,
            frameCount == 0 ? 0d : withCount / (double) frameCount,
        };
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

    /** Add the client thread's own allocation for one window, or -1 if unmeasurable. */
    public void clientAllocation(long bytes) {
        if (bytes < 0L) {
            clientAllocationKnown = false;
        } else {
            clientAllocatedBytes += bytes;
        }
    }

    /** Client-thread bytes per accepted frame, or -1 if the JVM could not say. */
    public double clientBytesPerFrame() {
        return !clientAllocationKnown || frameCount == 0 ? -1d : clientAllocatedBytes / (double) frameCount;
    }

    public double clientAllocationMegabytesPerSecond() {
        return !clientAllocationKnown || measuredSeconds <= 0d ? -1d
                : clientAllocatedBytes / measuredSeconds / (1024d * 1024d);
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
