package net.fathom.optimizations.core.instrument;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;

/**
 * Heap usage and allocation rate.
 *
 * <h2>Allocation rate is not directly observable on stock Java 8</h2>
 *
 * <p>There is no portable API for "bytes allocated since the JVM started". HotSpot exposes
 * {@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes}, which is a Sun
 * extension, not part of the Java SE API, and not guaranteed to exist or to be enabled.
 *
 * <p>So this class does two things and is explicit about which is which:
 *
 * <ol>
 *   <li>Reaches for the HotSpot extension <em>reflectively</em>. If it is present and
 *       enabled, allocation figures are real measurements.
 *   <li>Falls back to inferring allocation from heap-used deltas across collections. That
 *       is an <em>estimate</em>, it undercounts, and {@link Sample#allocationIsEstimated()}
 *       says so.
 * </ol>
 *
 * <p>Reporting an estimate as a measurement is the kind of thing that makes a whole
 * benchmark untrustworthy, so the distinction is carried in the data rather than in a
 * footnote.
 *
 * <h2>The observer effect</h2>
 *
 * <p>Sampling the heap is itself cheap, but it is not free and it is not zero-allocation
 * ({@code MemoryUsage} is an object). Sample at most a few times per second, never per
 * frame. {@link #MINIMUM_INTERVAL_MILLIS} is the floor this class will honour.
 */
public final class MemorySampler {

    /** Sampling faster than this measures the sampler more than the game. */
    public static final long MINIMUM_INTERVAL_MILLIS = 100L;

    /** One reading. */
    public static final class Sample {
        private final long timestampMillis;
        private final long heapUsedBytes;
        private final long heapCommittedBytes;
        private final long heapMaxBytes;
        private final long nonHeapUsedBytes;
        private final long allocatedBytesSinceStart;
        private final boolean allocationIsEstimated;

        Sample(
                long timestampMillis,
                long heapUsedBytes,
                long heapCommittedBytes,
                long heapMaxBytes,
                long nonHeapUsedBytes,
                long allocatedBytesSinceStart,
                boolean allocationIsEstimated) {
            this.timestampMillis = timestampMillis;
            this.heapUsedBytes = heapUsedBytes;
            this.heapCommittedBytes = heapCommittedBytes;
            this.heapMaxBytes = heapMaxBytes;
            this.nonHeapUsedBytes = nonHeapUsedBytes;
            this.allocatedBytesSinceStart = allocatedBytesSinceStart;
            this.allocationIsEstimated = allocationIsEstimated;
        }

        public long timestampMillis() {
            return timestampMillis;
        }

        public long heapUsedBytes() {
            return heapUsedBytes;
        }

        public long heapCommittedBytes() {
            return heapCommittedBytes;
        }

        /** {@code -1} when the JVM declares no maximum. */
        public long heapMaxBytes() {
            return heapMaxBytes;
        }

        public long nonHeapUsedBytes() {
            return nonHeapUsedBytes;
        }

        /** Cumulative allocation. {@code -1} when nothing could be determined at all. */
        public long allocatedBytesSinceStart() {
            return allocatedBytesSinceStart;
        }

        /**
         * Whether the allocation figure is inferred rather than measured.
         *
         * <p>When true it is a floor, not a total: heap-delta inference cannot see memory
         * that was allocated and collected between two samples.
         */
        public boolean allocationIsEstimated() {
            return allocationIsEstimated;
        }
    }

    private final MemoryMXBean memory;
    private final Object hotspotThreadBean;
    private final Method getAllocatedBytes;

    private long lastHeapUsed = -1L;
    private long inferredAllocation;

    public MemorySampler() {
        this.memory = ManagementFactory.getMemoryMXBean();

        // Reflective on purpose: `com.sun.management` is not on the Java SE API, and a
        // hard reference would make this class fail to link on a JVM that lacks it. The
        // measurement degrading to an estimate is much better than the mod failing to
        // load.
        Object bean = null;
        Method method = null;
        try {
            Object threadBean = ManagementFactory.getThreadMXBean();
            Class<?> hotspot = Class.forName("com.sun.management.ThreadMXBean");
            if (hotspot.isInstance(threadBean)) {
                Method supported = hotspot.getMethod("isThreadAllocatedMemorySupported");
                Method enabled = hotspot.getMethod("isThreadAllocatedMemoryEnabled");
                if (Boolean.TRUE.equals(supported.invoke(threadBean))
                        && Boolean.TRUE.equals(enabled.invoke(threadBean))) {
                    bean = threadBean;
                    method = hotspot.getMethod("getThreadAllocatedBytes", long.class);
                }
            }
        } catch (Throwable unavailable) {
            // Any failure at all means we fall back. Deliberately catching Throwable:
            // a LinkageError here is exactly the case this guard exists for, and it is
            // not an Exception.
            bean = null;
            method = null;
        }
        this.hotspotThreadBean = bean;
        this.getAllocatedBytes = method;
    }

    /** Whether allocation is measured rather than estimated on this JVM. */
    public boolean allocationIsMeasured() {
        return getAllocatedBytes != null;
    }

    /** Why allocation is estimated, for the benchmark report. */
    public String allocationMethod() {
        return allocationIsMeasured()
                ? "com.sun.management.ThreadMXBean.getThreadAllocatedBytes (per-thread, measured)"
                : "inferred from heap-used growth between samples (an undercount; allocation"
                        + " that is collected between two samples is invisible)";
    }

    /** Take a reading. */
    public Sample sample() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long now = System.currentTimeMillis();

        long allocated;
        boolean estimated;
        if (allocationIsMeasured()) {
            allocated = measuredAllocationForCurrentThread();
            estimated = false;
        } else {
            allocated = inferAllocation(heap.getUsed());
            estimated = true;
        }

        return new Sample(
                now,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                allocated,
                estimated);
    }

    /**
     * Per-thread allocation for the calling thread.
     *
     * <p>Per-thread rather than process-wide, because that is what the HotSpot API offers.
     * Called from the render thread it measures the render thread, which is the one whose
     * allocation causes frame-time stutter — but it is not the whole process, and the
     * report says so.
     */
    private long measuredAllocationForCurrentThread() {
        try {
            Object result =
                    getAllocatedBytes.invoke(hotspotThreadBean, Thread.currentThread().getId());
            return result instanceof Long ? (Long) result : -1L;
        } catch (Throwable failed) {
            return -1L;
        }
    }

    /**
     * Add up heap growth between samples.
     *
     * <p>A drop means a collection happened; the allocation since the previous sample is
     * unknowable from this side, so it contributes nothing rather than a guess. That is
     * what makes this an undercount and why it is labelled an estimate.
     */
    private long inferAllocation(long heapUsed) {
        if (lastHeapUsed >= 0L && heapUsed > lastHeapUsed) {
            inferredAllocation += heapUsed - lastHeapUsed;
        }
        lastHeapUsed = heapUsed;
        return inferredAllocation;
    }

    /**
     * Allocation rate in bytes per second between two samples.
     *
     * <p>Returns {@code -1} when either sample has no allocation figure, rather than 0 —
     * "we could not tell" and "nothing was allocated" are different claims.
     */
    public static double allocationBytesPerSecond(Sample from, Sample to) {
        if (from.allocatedBytesSinceStart() < 0 || to.allocatedBytesSinceStart() < 0) {
            return -1.0;
        }
        long elapsedMillis = to.timestampMillis() - from.timestampMillis();
        if (elapsedMillis <= 0L) {
            return -1.0;
        }
        long delta = to.allocatedBytesSinceStart() - from.allocatedBytesSinceStart();
        if (delta < 0L) {
            return -1.0;
        }
        return delta * 1000.0 / elapsedMillis;
    }
}
