package net.fathom.optimizations.core.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.fathom.optimizations.core.instrument.Counters.Counter;

/**
 * A workload we want to measure.
 *
 * <p>Each scenario names what it stresses, how to set it up, and — the part that matters
 * most — <strong>how reproducible it actually is</strong>. A benchmark whose variance
 * exceeds the effect it is measuring is worse than no benchmark, because it produces a
 * number that looks like evidence.
 */
public final class Scenario {

    /** How much of a workload we can actually reproduce on demand. */
    public enum Reproducibility {
        /**
         * Fully automatable. The client can construct the workload itself, with no
         * server and no human, and two runs differ only by noise.
         */
        AUTOMATABLE,
        /**
         * Reproducible from a fixed local world plus a scripted camera path. Needs a
         * prepared world file, which we ship in the benchmark harness rather than
         * generating.
         */
        SCRIPTED_LOCAL,
        /**
         * Needs a live server we do not control. Reproducible only in the loose sense
         * that the same server at the same time of day is roughly similar.
         *
         * <p>Anything at this level produces figures that cannot be compared between
         * sessions. It is still worth running — it is the workload people actually care
         * about — but a difference smaller than the between-session variance means
         * nothing.
         */
        LIVE_SERVER,
        /**
         * We have not worked out how to reproduce this at all yet.
         *
         * <p>Recorded rather than omitted. A scenario missing from the list looks like an
         * oversight; one marked {@code NOT_YET_REPRODUCIBLE} is a known gap.
         */
        NOT_YET_REPRODUCIBLE
    }

    private final String id;
    private final String name;
    private final String description;
    private final Reproducibility reproducibility;
    private final String setup;
    private final List<Counter> primaryCounters;
    private final List<String> limitations;
    private final int recommendedSeconds;

    private Scenario(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.name = Objects.requireNonNull(builder.name, "name");
        this.description = Objects.requireNonNull(builder.description, "description");
        this.reproducibility = Objects.requireNonNull(builder.reproducibility, "reproducibility");
        this.setup = Objects.requireNonNull(builder.setup, "setup");
        this.primaryCounters =
                Collections.unmodifiableList(new ArrayList<Counter>(builder.primaryCounters));
        this.limitations =
                Collections.unmodifiableList(new ArrayList<String>(builder.limitations));
        this.recommendedSeconds = builder.recommendedSeconds;

        if (reproducibility != Reproducibility.AUTOMATABLE && limitations.isEmpty()) {
            // Anything not fully automatable has a reason it is not, and that reason is
            // what a reader needs in order to judge the numbers. Requiring it here means
            // it cannot be left out by accident.
            throw new IllegalArgumentException(
                    id + ": a scenario that is not AUTOMATABLE must state its limitations");
        }
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Reproducibility reproducibility() {
        return reproducibility;
    }

    /** How to put the client into this state. */
    public String setup() {
        return setup;
    }

    /** The counters this scenario exists to move. */
    public List<Counter> primaryCounters() {
        return primaryCounters;
    }

    /** Everything that makes this measurement less trustworthy than it looks. */
    public List<String> limitations() {
        return limitations;
    }

    public int recommendedSeconds() {
        return recommendedSeconds;
    }

    /**
     * Whether a difference measured here can be trusted between sessions.
     *
     * <p>False for live-server scenarios: the server's own load varies, so the same build
     * measured an hour apart will differ by more than most optimizations do.
     */
    public boolean comparableBetweenSessions() {
        return reproducibility == Reproducibility.AUTOMATABLE
                || reproducibility == Reproducibility.SCRIPTED_LOCAL;
    }

    @Override
    public String toString() {
        return id + " (" + reproducibility + ")";
    }

    static final class Builder {
        private final String id;
        private String name;
        private String description;
        private Reproducibility reproducibility;
        private String setup;
        private final List<Counter> primaryCounters = new ArrayList<Counter>();
        private final List<String> limitations = new ArrayList<String>();
        private int recommendedSeconds = 120;

        private Builder(String id) {
            this.id = id;
        }

        Builder name(String name) {
            this.name = name;
            return this;
        }

        Builder description(String description) {
            this.description = description;
            return this;
        }

        Builder reproducibility(Reproducibility reproducibility) {
            this.reproducibility = reproducibility;
            return this;
        }

        Builder setup(String setup) {
            this.setup = setup;
            return this;
        }

        Builder counters(Counter... counters) {
            this.primaryCounters.addAll(Arrays.asList(counters));
            return this;
        }

        Builder limitation(String limitation) {
            this.limitations.add(limitation);
            return this;
        }

        Builder seconds(int seconds) {
            this.recommendedSeconds = seconds;
            return this;
        }

        Scenario build() {
            return new Scenario(this);
        }
    }
}
