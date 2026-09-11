package net.fathom.optimizations.core.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * Turns an optimization off when it misbehaves, rather than letting it break the game.
 *
 * <h2>The rule</h2>
 *
 * <p>An optimization that throws is an optimization that is off. Not one that throws every
 * frame, not one that crashes the client, and not one that is silently swallowed and keeps
 * running wrong — off, once, loudly, with the reason recorded.
 *
 * <p>This matters more here than in most code because of what these optimizations are.
 * They replace working vanilla behaviour with faster behaviour that is supposed to be
 * equivalent. When the equivalence is wrong, the correct response is to stop being clever:
 * vanilla is slower but it is right, and a player would rather have 90 FPS and a correct
 * world than 140 FPS and missing chunks.
 *
 * <h2>Why a trip count rather than a single failure</h2>
 *
 * <p>A single exception during a resource reload, or on the frame a world unloads, is not
 * necessarily a broken optimization. {@link #TRIP_THRESHOLD} consecutive-ish failures is.
 * The counter never resets on success, though: an optimization that fails one frame in
 * fifty is still broken, just intermittently, and would otherwise run forever.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a crash handler and not a substitute for correctness. It catches the case where
 * we were wrong about an equivalence; it does not make an incorrect optimization
 * acceptable. Anything that trips a guard in the field is a bug to fix, and the report
 * exists to make that visible rather than to absorb it.
 */
public final class Guard {

    /** Failures before an optimization is switched off. */
    public static final int TRIP_THRESHOLD = 3;

    /** What happened to one optimization. */
    public static final class Record {
        private final OptimizationId id;
        private final int failures;
        private final boolean tripped;
        private final String firstFailure;
        private final String lastFailure;

        Record(
                OptimizationId id,
                int failures,
                boolean tripped,
                String firstFailure,
                String lastFailure) {
            this.id = id;
            this.failures = failures;
            this.tripped = tripped;
            this.firstFailure = firstFailure;
            this.lastFailure = lastFailure;
        }

        public OptimizationId id() {
            return id;
        }

        public int failures() {
            return failures;
        }

        /** Whether it was switched off. */
        public boolean tripped() {
            return tripped;
        }

        /**
         * The first failure's description.
         *
         * <p>The first rather than the last, because the first is the one that explains
         * what went wrong; later ones are usually consequences.
         */
        public String firstFailure() {
            return firstFailure;
        }

        public String lastFailure() {
            return lastFailure;
        }

        @Override
        public String toString() {
            return id + ": " + failures + " failure(s)" + (tripped ? ", switched off" : "")
                    + " — " + firstFailure;
        }
    }

    /** Something an optimization does that might go wrong. */
    public interface Action {
        void run() throws Throwable;
    }

    /** Something an optimization computes that might go wrong. */
    public interface Computation<T> {
        T compute() throws Throwable;
    }

    /** Told when a guard trips, so the failure is not silent. */
    public interface Listener {
        void tripped(Record record);
    }

    private static final class Counter {
        int failures;
        String first;
        String last;
    }

    private final SwitchBoard switches;
    private final Listener listener;
    private final Map<OptimizationId, Counter> counters =
            new LinkedHashMap<OptimizationId, Counter>();

    public Guard(SwitchBoard switches, Listener listener) {
        this.switches = switches;
        this.listener = listener;
    }

    /**
     * Run an optimization's work, falling back to vanilla if it fails.
     *
     * @param id the optimization doing the work
     * @param optimized what it wants to do
     * @param vanilla what the game would have done. Runs when the optimization is off, and
     *     when it throws.
     */
    public void run(OptimizationId id, Action optimized, Action vanilla) {
        if (!switches.isEnabled(id)) {
            runVanilla(vanilla);
            return;
        }
        try {
            optimized.run();
        } catch (Throwable failure) {
            // Throwable, not Exception: a StackOverflowError or a LinkageError from a
            // Mixin that did not apply cleanly is exactly the case this exists for, and
            // neither is an Exception.
            recordFailure(id, failure);
            runVanilla(vanilla);
        }
    }

    /**
     * The same, for something that produces a value.
     *
     * <p>The fallback is called if the optimization throws <em>or</em> returns null, since
     * a null where the caller expects a value is a failure the caller would otherwise
     * discover as a NullPointerException somewhere less useful.
     */
    public <T> T compute(OptimizationId id, Computation<T> optimized, Computation<T> vanilla) {
        if (switches.isEnabled(id)) {
            try {
                T result = optimized.compute();
                if (result != null) {
                    return result;
                }
                recordFailure(id, new IllegalStateException("returned null"));
            } catch (Throwable failure) {
                recordFailure(id, failure);
            }
        }
        try {
            return vanilla.compute();
        } catch (Throwable vanillaFailure) {
            // Vanilla failing is not our failure to absorb. Rethrowing preserves the
            // behaviour an unmodded client would have had, which is the entire point of
            // having a fallback.
            throw asUnchecked(vanillaFailure);
        }
    }

    private void runVanilla(Action vanilla) {
        try {
            vanilla.run();
        } catch (Throwable vanillaFailure) {
            throw asUnchecked(vanillaFailure);
        }
    }

    private static RuntimeException asUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new RuntimeException(failure);
    }

    private void recordFailure(OptimizationId id, Throwable failure) {
        Counter counter = counters.get(id);
        if (counter == null) {
            counter = new Counter();
            counters.put(id, counter);
        }
        counter.failures++;
        String description = describe(failure);
        if (counter.first == null) {
            counter.first = description;
        }
        counter.last = description;

        if (counter.failures == TRIP_THRESHOLD) {
            switches.disableBySafeguard(
                    id,
                    "Switched off after " + TRIP_THRESHOLD + " failures. First was: "
                            + counter.first);
            if (listener != null) {
                listener.tripped(snapshotOf(id, counter));
            }
        }
    }

    private static String describe(Throwable failure) {
        String type = failure.getClass().getName();
        String message = failure.getMessage();
        String where = "";
        StackTraceElement[] trace = failure.getStackTrace();
        if (trace.length > 0) {
            where = " at " + trace[0];
        }
        return message == null ? type + where : type + ": " + message + where;
    }

    private Record snapshotOf(OptimizationId id, Counter counter) {
        return new Record(
                id,
                counter.failures,
                counter.failures >= TRIP_THRESHOLD,
                counter.first,
                counter.last);
    }

    /** Whether anything has failed at all. */
    public boolean clean() {
        return counters.isEmpty();
    }

    /** Everything that has failed, tripped or not. */
    public List<Record> records() {
        List<Record> out = new ArrayList<Record>();
        for (Map.Entry<OptimizationId, Counter> entry : counters.entrySet()) {
            out.add(snapshotOf(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Only the ones that were switched off. */
    public List<Record> tripped() {
        List<Record> out = new ArrayList<Record>();
        for (Record record : records()) {
            if (record.tripped()) {
                out.add(record);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public int failureCount(OptimizationId id) {
        Counter counter = counters.get(id);
        return counter == null ? 0 : counter.failures;
    }
}
