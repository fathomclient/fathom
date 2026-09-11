package net.fathom.optimizations.core.instrument;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Garbage collection behaviour over a run.
 *
 * <h2>What this can and cannot see</h2>
 *
 * <p>{@link GarbageCollectorMXBean} gives a collection <em>count</em> and a cumulative
 * collection <em>time</em> per collector. From two readings we get: how many collections
 * happened, and how long they took in total. From those, a mean pause.
 *
 * <p>What it does <strong>not</strong> give:
 *
 * <ul>
 *   <li><strong>Individual pause durations.</strong> So there is no p99 pause here. Only
 *       a mean, and a mean pause hides exactly the outlier that ruins a fight.
 *   <li><strong>Whether a pause was stop-the-world.</strong> Concurrent collector phases
 *       are counted in the same total as stop-the-world ones.
 *   <li><strong>When</strong> a collection happened, beyond the interval it fell in.
 * </ul>
 *
 * <p>The honest way to get per-pause data on Java 8 is {@code -Xlog:gc} / {@code -verbose:gc}
 * output, or a JFR recording, parsed afterwards. That is a launcher-side experiment
 * (see {@code docs/performance/benchmark-methodology.md}), not something this class can do
 * from inside the process, and pretending otherwise would produce a p99 pause figure that
 * was not measured.
 *
 * <p>The most useful thing this <em>can</em> do is correlate: a frame-time spike that
 * coincides with the collection count going up is very likely a GC pause. The
 * methodology document describes that correlation as the intended use.
 */
public final class GcLog {

    /** One collector's counters at a point in time. */
    public static final class Reading {
        private final String collectorName;
        private final long collectionCount;
        private final long collectionTimeMillis;

        Reading(String collectorName, long collectionCount, long collectionTimeMillis) {
            this.collectorName = collectorName;
            this.collectionCount = collectionCount;
            this.collectionTimeMillis = collectionTimeMillis;
        }

        public String collectorName() {
            return collectorName;
        }

        /** {@code -1} when the JVM does not report it. */
        public long collectionCount() {
            return collectionCount;
        }

        public long collectionTimeMillis() {
            return collectionTimeMillis;
        }
    }

    /** What happened between two readings. */
    public static final class Delta {
        private final String collectorName;
        private final long collections;
        private final long totalPauseMillis;

        Delta(String collectorName, long collections, long totalPauseMillis) {
            this.collectorName = collectorName;
            this.collections = collections;
            this.totalPauseMillis = totalPauseMillis;
        }

        public String collectorName() {
            return collectorName;
        }

        public long collections() {
            return collections;
        }

        public long totalPauseMillis() {
            return totalPauseMillis;
        }

        /**
         * Total time in collection divided by collections.
         *
         * <p>A mean, and only a mean. There is no percentile available from this API, and
         * the number that actually matters for stutter is the worst pause, which is not
         * here. See the class note.
         */
        public double meanPauseMillis() {
            return collections <= 0 ? 0.0 : totalPauseMillis / (double) collections;
        }

        /** Fraction of wall time spent collecting. */
        public double pauseFractionOf(long wallMillis) {
            return wallMillis <= 0 ? 0.0 : totalPauseMillis / (double) wallMillis;
        }

        @Override
        public String toString() {
            return collectorName + ": " + collections + " collections, " + totalPauseMillis
                    + " ms total, " + String.format("%.2f", meanPauseMillis()) + " ms mean";
        }
    }

    private GcLog() {}

    /** Read every collector's counters now. */
    public static List<Reading> read() {
        List<Reading> out = new ArrayList<Reading>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.add(new Reading(bean.getName(), bean.getCollectionCount(), bean.getCollectionTime()));
        }
        return Collections.unmodifiableList(out);
    }

    /** The collectors this JVM is actually using, for the baseline record. */
    public static List<String> collectorNames() {
        List<String> out = new ArrayList<String>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.add(bean.getName());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Subtract two readings.
     *
     * <p>Collectors present in {@code after} but not {@code before} are reported in full,
     * which is correct: a collector that appeared mid-run did all its work during the run.
     */
    public static Map<String, Delta> between(List<Reading> before, List<Reading> after) {
        Map<String, Reading> baseline = new LinkedHashMap<String, Reading>();
        for (Reading reading : before) {
            baseline.put(reading.collectorName(), reading);
        }

        Map<String, Delta> out = new LinkedHashMap<String, Delta>();
        for (Reading reading : after) {
            Reading start = baseline.get(reading.collectorName());
            long startCount = start == null ? 0L : Math.max(start.collectionCount(), 0L);
            long startTime = start == null ? 0L : Math.max(start.collectionTimeMillis(), 0L);

            long collections = Math.max(reading.collectionCount(), 0L) - startCount;
            long pause = Math.max(reading.collectionTimeMillis(), 0L) - startTime;

            out.put(
                    reading.collectorName(),
                    new Delta(
                            reading.collectorName(),
                            Math.max(collections, 0L),
                            Math.max(pause, 0L)));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Total collections across every collector, for a one-number summary. */
    public static long totalCollections(Map<String, Delta> deltas) {
        long total = 0L;
        for (Delta delta : deltas.values()) {
            total += delta.collections();
        }
        return total;
    }

    public static long totalPauseMillis(Map<String, Delta> deltas) {
        long total = 0L;
        for (Delta delta : deltas.values()) {
            total += delta.totalPauseMillis();
        }
        return total;
    }

    /**
     * What this API cannot tell us, in words, for inclusion in every report.
     *
     * <p>Carried in the data rather than left to a reader to remember, because a GC
     * summary with no caveat reads as complete.
     */
    public static String limitations() {
        return "GarbageCollectorMXBean reports only a collection count and a cumulative"
                + " collection time per collector. Individual pause durations are not"
                + " available, so there is no p99 pause figure here, and concurrent phases"
                + " are counted alongside stop-the-world ones. Per-pause data needs GC"
                + " logging or JFR, parsed after the run.";
    }
}
