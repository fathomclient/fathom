package net.fathom.optimizations.core.registry;

import java.util.Objects;

/**
 * A measurement we actually took, and how far it is allowed to be stretched.
 *
 * <p>Replaces what used to be a boolean {@code measured} flag. The flag was not wrong so
 * much as too coarse: it could not tell the difference between "we timed this function in
 * a loop" and "we ran the game and the frame times moved", and those two support very
 * different claims. Collapsing them is how a 60% microbenchmark win becomes a "60% more
 * FPS" line in a changelog.
 *
 * <p>So the level is part of the evidence, and {@link Level#permitsDefaultOn()} is what the
 * registry consults. An isolated measurement is real evidence — it is how we know an
 * implementation is not slower than what it replaces — but it is not evidence that the
 * game got faster, and only the second kind promotes anything.
 */
public final class Evidence {

    /** How far a measurement reaches. */
    public enum Level {
        /**
         * One function in a loop, or a synthetic JVM workload.
         *
         * <p>Establishes that an implementation does what it claims and is not slower than
         * the thing it replaces. Says nothing about frame time, because a function that is
         * ten times faster in a loop may be invisible in a frame.
         *
         * <p>Does not permit default-on.
         */
        ISOLATED,
        /**
         * A run of the game, in a named scenario, under
         * {@code docs/performance/benchmark-methodology.md}.
         *
         * <p>The only kind that promotes anything.
         */
        IN_GAME;

        public boolean permitsDefaultOn() {
            return this == IN_GAME;
        }
    }

    private final Level level;
    private final String runId;
    private final String summary;

    private Evidence(Level level, String runId, String summary) {
        this.level = Objects.requireNonNull(level, "level");
        this.runId = require(runId, "runId");
        this.summary = require(summary, "summary");
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("evidence needs a " + field);
        }
        return value;
    }

    /**
     * A microbenchmark or synthetic measurement.
     *
     * @param runId the row in {@code docs/performance/benchmarks.md} this came from
     * @param summary what it showed, in one sentence, scoped honestly
     */
    public static Evidence isolated(String runId, String summary) {
        return new Evidence(Level.ISOLATED, runId, summary);
    }

    /** A run of the game in a named scenario. */
    public static Evidence inGame(String runId, String summary) {
        return new Evidence(Level.IN_GAME, runId, summary);
    }

    public Level level() {
        return level;
    }

    /**
     * Which recorded run this is.
     *
     * <p>A pointer rather than a number. Numbers inlined here would drift from the runs
     * that produced them, and a figure with no run behind it is the thing this whole
     * framework exists to prevent.
     */
    public String runId() {
        return runId;
    }

    public String summary() {
        return summary;
    }

    public boolean permitsDefaultOn() {
        return level.permitsDefaultOn();
    }

    @Override
    public String toString() {
        return level + " (" + runId + "): " + summary;
    }
}
