package net.fathom.optimizations.core.instrument;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Cheap counters for render-workload statistics.
 *
 * <p>A fixed enum of counters rather than a string-keyed map: the set is known at compile
 * time, an {@code EnumMap} lookup is an array index, and a typo in a counter name becomes
 * a compile error rather than a silently separate counter.
 *
 * <p>Every increment happens inside the frame it measures, so these are plain
 * {@code long}s and plain increments. Not thread-safe; chunk-worker counters are recorded
 * by handing the render thread a total rather than by sharing a counter.
 */
public final class Counters {

    /** What we count. */
    public enum Counter {
        /** Chunk mesh rebuilds started. */
        CHUNK_REBUILDS,
        /** Nanoseconds spent in chunk rebuilds. */
        CHUNK_REBUILD_NANOS,
        /** Entities considered for rendering, before culling. */
        ENTITIES_CONSIDERED,
        /** Entities actually drawn. */
        ENTITIES_RENDERED,
        /** Tile entities drawn. */
        TILE_ENTITIES_RENDERED,
        /** Particles drawn. */
        PARTICLES_RENDERED,
        /** Text runs drawn — a chat line, a nametag, a scoreboard row. */
        TEXT_RUNS,
        /** Glyphs drawn. The ratio to TEXT_RUNS is what font batching changes. */
        GLYPHS,
        /** GUI quads drawn. */
        GUI_QUADS,
        /**
         * Approximate draw calls.
         *
         * <p>Approximate on purpose: counting real GL draw calls means intercepting
         * every call site, and 1.8.9 draws through several paths. This counts the ones
         * our instrumentation covers, and the methodology document says which those are
         * so nobody reads it as a total.
         */
        DRAW_CALLS_APPROX,
        /** Buffer uploads to the GPU. */
        BUFFER_UPLOADS
    }

    private final EnumMap<Counter, long[]> values = new EnumMap<Counter, long[]>(Counter.class);

    public Counters() {
        for (Counter counter : Counter.values()) {
            values.put(counter, new long[1]);
        }
    }

    public void increment(Counter counter) {
        values.get(counter)[0]++;
    }

    public void add(Counter counter, long amount) {
        values.get(counter)[0] += amount;
    }

    public long get(Counter counter) {
        return values.get(counter)[0];
    }

    /** Take the totals and start again. Called once per frame or once per run. */
    public Map<Counter, Long> drain() {
        EnumMap<Counter, Long> snapshot = new EnumMap<Counter, Long>(Counter.class);
        for (Counter counter : Counter.values()) {
            long[] cell = values.get(counter);
            snapshot.put(counter, cell[0]);
            cell[0] = 0L;
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<Counter, Long> read() {
        EnumMap<Counter, Long> snapshot = new EnumMap<Counter, Long>(Counter.class);
        for (Counter counter : Counter.values()) {
            snapshot.put(counter, values.get(counter)[0]);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public void reset() {
        for (Counter counter : Counter.values()) {
            values.get(counter)[0] = 0L;
        }
    }
}
