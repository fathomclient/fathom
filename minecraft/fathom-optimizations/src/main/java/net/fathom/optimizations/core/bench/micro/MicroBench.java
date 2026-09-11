package net.fathom.optimizations.core.bench.micro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, honest harness for comparing two implementations of the same pure function.
 *
 * <h2>What this measures, and what it does not</h2>
 *
 * <p>It measures <strong>one function in isolation, on this machine, right now</strong>.
 * That is an isolated baseline in the sense the process asks for, and it is genuinely
 * useful: it tells you whether an implementation is faster than the one it replaces, and
 * by how much relative to the noise.
 *
 * <p>It is <strong>not</strong> a measurement of the game. A function that is ten times
 * faster in a loop may be invisible in a frame, because a frame is dominated by other
 * things and because a microbenchmark runs with the whole cache to itself. Nothing here
 * may be quoted as an FPS figure, and {@link Result#scope()} says so in the data rather
 * than in a footnote somebody drops on the way to a changelog.
 *
 * <h2>Why not JMH</h2>
 *
 * <p>JMH is the right tool and this is not a replacement for it. This module has no
 * dependencies outside the JDK on purpose — it has to be checkable in an environment with
 * no network and no Gradle — so the choice is between this and nothing.
 *
 * <p>The consequences are real and are stated so that results are read with them in mind:
 * no dead-code-elimination guards beyond consuming a result into a field the JIT cannot
 * prove unused, no fork-per-arm isolation, no profiler integration. A difference under
 * about 10% from this harness should be treated as no difference regardless of what the
 * noise floor says.
 *
 * <h2>What it does do properly</h2>
 *
 * <ul>
 *   <li><strong>Warms up.</strong> Untimed iterations first, so the JIT has compiled the
 *       thing being measured rather than being measured itself.
 *   <li><strong>Interleaves.</strong> A, B, A, B — never all of A then all of B. Thermal
 *       state and background load drift over the length of a run, and batching turns that
 *       drift into an apparent result. This is the single most important thing the harness
 *       does and the one most often skipped.
 *   <li><strong>Reports the median.</strong> Never the best. A best-of-N is a measurement
 *       of how quiet the machine got once.
 *   <li><strong>Establishes a noise floor</strong> by running the baseline against itself,
 *       and refuses to call anything smaller than it a difference.
 * </ul>
 */
public final class MicroBench {

    /** What a result is allowed to be quoted as. */
    public enum Scope {
        /**
         * One function, in a loop, on this machine. Not a statement about the game.
         */
        ISOLATED_FUNCTION,
        /**
         * A whole-JVM behaviour such as a collection pause. Still synthetic: the workload
         * is ours, not Minecraft's.
         */
        SYNTHETIC_JVM_WORKLOAD
    }

    /** One thing to time. */
    public interface Arm {
        /**
         * Do the work once and return something derived from it.
         *
         * <p>The return value is consumed by the harness so that the JIT cannot delete the
         * body as unused. Returning a constant defeats that, so return something that
         * actually depends on the work.
         */
        long run();

        String label();
    }

    /** The outcome of one comparison. */
    public static final class Result {
        private final String name;
        private final Scope scope;
        private final String baselineLabel;
        private final String candidateLabel;
        private final long[] baselineNanos;
        private final long[] candidateNanos;
        private final double noiseFloorFraction;
        private final int iterationsPerRun;

        Result(String name, Scope scope, String baselineLabel, String candidateLabel,
                long[] baselineNanos, long[] candidateNanos, double noiseFloorFraction,
                int iterationsPerRun) {
            this.name = name;
            this.scope = scope;
            this.baselineLabel = baselineLabel;
            this.candidateLabel = candidateLabel;
            this.baselineNanos = baselineNanos;
            this.candidateNanos = candidateNanos;
            this.noiseFloorFraction = noiseFloorFraction;
            this.iterationsPerRun = iterationsPerRun;
        }

        public String name() {
            return name;
        }

        public Scope scope() {
            return scope;
        }

        public double baselineMedianNanos() {
            return median(baselineNanos);
        }

        public double candidateMedianNanos() {
            return median(candidateNanos);
        }

        /** Per-call cost of the baseline, which is usually the readable number. */
        public double baselineNanosPerCall() {
            return baselineMedianNanos() / iterationsPerRun;
        }

        public double candidateNanosPerCall() {
            return candidateMedianNanos() / iterationsPerRun;
        }

        /**
         * How much faster the candidate is, as a fraction of the baseline.
         *
         * <p>Positive means faster. 0.25 means "took three quarters as long".
         */
        public double improvementFraction() {
            double base = baselineMedianNanos();
            if (base <= 0.0) {
                return Double.NaN;
            }
            return (base - candidateMedianNanos()) / base;
        }

        /**
         * The spread between runs of the identical implementation, as a fraction.
         *
         * <p>Anything smaller than this is not a result. Measured rather than assumed,
         * which is the step {@code benchmark-methodology.md} §5.1 calls the one most
         * easily skipped.
         */
        public double noiseFloorFraction() {
            return noiseFloorFraction;
        }

        /** Whether the difference is larger than the noise. */
        public boolean isSignificant() {
            return Math.abs(improvementFraction()) > noiseFloorFraction;
        }

        /**
         * The one sentence this result is allowed to be reported as.
         *
         * <p>Generated rather than written by hand, so a result cannot be described more
         * strongly than it supports.
         */
        public String claim() {
            if (!isSignificant()) {
                return "No measurable difference: " + percent(improvementFraction())
                        + " is inside the " + percent(noiseFloorFraction) + " noise floor.";
            }
            String direction = improvementFraction() > 0 ? "faster" : "SLOWER";
            String qualifier = scope == Scope.ISOLATED_FUNCTION
                    ? " for this function in isolation on this machine. Not an FPS claim."
                    : " under a synthetic workload on this machine. Not a Minecraft measurement.";
            return candidateLabel + " is " + percent(Math.abs(improvementFraction())) + " "
                    + direction + " than " + baselineLabel + qualifier;
        }

        /** A table row for the benchmarks document. */
        public String markdownRow() {
            return "| " + name + " | " + format(baselineNanosPerCall()) + " ns | "
                    + format(candidateNanosPerCall()) + " ns | "
                    + percent(improvementFraction()) + " | "
                    + percent(noiseFloorFraction) + " | "
                    + (isSignificant() ? "yes" : "**no**") + " |";
        }

        @Override
        public String toString() {
            return name + ": " + claim();
        }
    }

    /** How many untimed iterations before anything is recorded. */
    public static final int DEFAULT_WARMUP_RUNS = 5;

    /** How many timed runs of each arm. Odd, so the median is a real run. */
    public static final int DEFAULT_RUNS = 11;

    private final int warmupRuns;
    private final int runs;
    private final Map<String, Result> results = new LinkedHashMap<String, Result>();

    /** Consumed into, so the JIT cannot prove the work unused. Never read meaningfully. */
    @SuppressWarnings("unused")
    private volatile long sink;

    public MicroBench() {
        this(DEFAULT_WARMUP_RUNS, DEFAULT_RUNS);
    }

    public MicroBench(int warmupRuns, int runs) {
        if (warmupRuns < 1 || runs < 3) {
            throw new IllegalArgumentException(
                    "a comparison needs warm-up and at least three runs per arm");
        }
        if (runs % 2 == 0) {
            // An even count makes the median an average of two runs, which is a number
            // that did not happen. Cheap to avoid.
            throw new IllegalArgumentException("use an odd number of runs so the median is real");
        }
        this.warmupRuns = warmupRuns;
        this.runs = runs;
    }

    /**
     * Compare two arms, interleaved, and establish the noise floor first.
     *
     * @param iterationsPerRun how many times each arm's {@code run()} is called per timed
     *     run; large enough that one run takes milliseconds rather than nanoseconds
     */
    public Result compare(String name, Scope scope, Arm baseline, Arm candidate,
            int iterationsPerRun) {
        if (iterationsPerRun < 1) {
            throw new IllegalArgumentException("iterationsPerRun");
        }
        warmUp(baseline, iterationsPerRun);
        warmUp(candidate, iterationsPerRun);

        // The noise floor comes first and uses the baseline against itself. Running it
        // after the comparison would measure a machine that the comparison had already
        // warmed, which flatters the floor and therefore flatters every result.
        double floor = noiseFloor(baseline, iterationsPerRun);

        long[] baseNanos = new long[runs];
        long[] candNanos = new long[runs];
        for (int i = 0; i < runs; i++) {
            // Interleaved, and alternating which goes first, so neither arm systematically
            // benefits from following the other.
            if (i % 2 == 0) {
                baseNanos[i] = time(baseline, iterationsPerRun);
                candNanos[i] = time(candidate, iterationsPerRun);
            } else {
                candNanos[i] = time(candidate, iterationsPerRun);
                baseNanos[i] = time(baseline, iterationsPerRun);
            }
        }

        Result result = new Result(name, scope, baseline.label(), candidate.label(), baseNanos,
                candNanos, floor, iterationsPerRun);
        results.put(name, result);
        return result;
    }

    private void warmUp(Arm arm, int iterationsPerRun) {
        for (int i = 0; i < warmupRuns; i++) {
            time(arm, iterationsPerRun);
        }
    }

    /**
     * The spread between runs of the same implementation, as a fraction of the median.
     *
     * <p>Max minus min over median. A conservative reading — it takes the worst pair of
     * runs rather than a standard deviation — because the failure this number exists to
     * prevent is calling noise a result.
     */
    private double noiseFloor(Arm arm, int iterationsPerRun) {
        long[] samples = new long[runs];
        for (int i = 0; i < runs; i++) {
            samples[i] = time(arm, iterationsPerRun);
        }
        double median = median(samples);
        if (median <= 0.0) {
            return 1.0;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return (sorted[sorted.length - 1] - sorted[0]) / median;
    }

    private long time(Arm arm, int iterations) {
        long accumulator = 0L;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            accumulator += arm.run();
        }
        long elapsed = System.nanoTime() - start;
        sink = accumulator;
        return elapsed;
    }

    public List<Result> results() {
        return Collections.unmodifiableList(new ArrayList<Result>(results.values()));
    }

    /** The header for {@link Result#markdownRow()}. */
    public static String markdownHeader() {
        return "| Comparison | Baseline | Candidate | Difference | Noise floor | Significant |\n"
                + "|---|---|---|---|---|---|";
    }

    static double median(long[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static String percent(double fraction) {
        if (Double.isNaN(fraction)) {
            return "n/a";
        }
        return String.format("%.1f%%", fraction * 100.0);
    }

    static String format(double nanos) {
        if (Double.isNaN(nanos)) {
            return "n/a";
        }
        if (nanos >= 1000.0) {
            return String.format("%.0f", nanos);
        }
        if (nanos >= 10.0) {
            return String.format("%.1f", nanos);
        }
        return String.format("%.3f", nanos);
    }
}
