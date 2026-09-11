package net.fathom.optimizations.core.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A question about JVM configuration that we intend to answer by measuring.
 *
 * <h2>Why these are experiments and not settings</h2>
 *
 * <p>The community holds several beliefs about Minecraft JVM tuning with more confidence
 * than the evidence supports, and so, until this milestone, did we:
 *
 * <ul>
 *   <li>"{@code -Xms} must equal {@code -Xmx}"
 *   <li>"{@code -XX:+DisableExplicitGC} is always better"
 *   <li>"this GC flag set is optimal"
 * </ul>
 *
 * <p>Each is plausible, each has a mechanism behind it, and none of them has been measured
 * <em>by us on 1.8.9 on Java 8</em>. {@code performance.md} §5.1 is explicit that most
 * modern JVM tuning advice does not transfer to Java 8, which is exactly the situation in
 * which inherited folk wisdom is least reliable.
 *
 * <p>So each belief becomes an {@link Experiment}: a hypothesis, the arms to compare, the
 * scenarios that would show a difference, and what result would falsify it. An experiment
 * that cannot be falsified is not an experiment, and {@link #falsifiedBy()} is required.
 *
 * <p>This class describes experiments; it does not run them. Running them means launching
 * the game repeatedly with different flags, which is the launcher's job.
 */
public final class Experiment {

    /** One configuration to compare. */
    public static final class Arm {
        private final String label;
        private final List<String> jvmArguments;
        private final String rationale;

        public Arm(String label, List<String> jvmArguments, String rationale) {
            this.label = Objects.requireNonNull(label, "label");
            this.jvmArguments =
                    Collections.unmodifiableList(new ArrayList<String>(jvmArguments));
            this.rationale = Objects.requireNonNull(rationale, "rationale");
        }

        public static Arm of(String label, String rationale, String... jvmArguments) {
            return new Arm(label, Arrays.asList(jvmArguments), rationale);
        }

        public String label() {
            return label;
        }

        public List<String> jvmArguments() {
            return jvmArguments;
        }

        /** Why this arm is worth including. */
        public String rationale() {
            return rationale;
        }

        @Override
        public String toString() {
            return label + " " + jvmArguments;
        }
    }

    private final String id;
    private final String question;
    private final String hypothesis;
    private final List<Arm> arms;
    private final List<String> scenarioIds;
    private final String primaryMetric;
    private final String falsifiedBy;
    private final String currentBehaviour;
    private final List<String> notes;

    private Experiment(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.question = Objects.requireNonNull(builder.question, "question");
        this.hypothesis = Objects.requireNonNull(builder.hypothesis, "hypothesis");
        this.arms = Collections.unmodifiableList(new ArrayList<Arm>(builder.arms));
        this.scenarioIds =
                Collections.unmodifiableList(new ArrayList<String>(builder.scenarioIds));
        this.primaryMetric = Objects.requireNonNull(builder.primaryMetric, "primaryMetric");
        this.falsifiedBy = Objects.requireNonNull(builder.falsifiedBy, "falsifiedBy");
        this.currentBehaviour =
                Objects.requireNonNull(builder.currentBehaviour, "currentBehaviour");
        this.notes = Collections.unmodifiableList(new ArrayList<String>(builder.notes));

        if (arms.size() < 2) {
            throw new IllegalArgumentException(
                    id + ": an experiment needs at least two arms to compare");
        }
        if (scenarioIds.isEmpty()) {
            throw new IllegalArgumentException(
                    id + ": an experiment must name the scenarios that would show a difference");
        }
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** What we do not know. */
    public String question() {
        return question;
    }

    /** What we currently believe, stated as a belief. */
    public String hypothesis() {
        return hypothesis;
    }

    public List<Arm> arms() {
        return arms;
    }

    /** Scenario ids from {@code Scenarios}. */
    public List<String> scenarioIds() {
        return scenarioIds;
    }

    /** The metric that decides it. */
    public String primaryMetric() {
        return primaryMetric;
    }

    /**
     * What result would show the hypothesis is wrong.
     *
     * <p>Required. Writing this down before running anything is what stops a result being
     * reinterpreted after the fact to agree with what we already believed.
     */
    public String falsifiedBy() {
        return falsifiedBy;
    }

    /** What the launcher does today, and on what basis. */
    public String currentBehaviour() {
        return currentBehaviour;
    }

    public List<String> notes() {
        return notes;
    }

    @Override
    public String toString() {
        return id + ": " + question;
    }

    static final class Builder {
        private final String id;
        private String question;
        private String hypothesis;
        private final List<Arm> arms = new ArrayList<Arm>();
        private final List<String> scenarioIds = new ArrayList<String>();
        private String primaryMetric;
        private String falsifiedBy;
        private String currentBehaviour;
        private final List<String> notes = new ArrayList<String>();

        private Builder(String id) {
            this.id = id;
        }

        Builder question(String question) {
            this.question = question;
            return this;
        }

        Builder hypothesis(String hypothesis) {
            this.hypothesis = hypothesis;
            return this;
        }

        Builder arm(Arm arm) {
            this.arms.add(arm);
            return this;
        }

        Builder scenarios(String... ids) {
            this.scenarioIds.addAll(Arrays.asList(ids));
            return this;
        }

        Builder primaryMetric(String metric) {
            this.primaryMetric = metric;
            return this;
        }

        Builder falsifiedBy(String falsifiedBy) {
            this.falsifiedBy = falsifiedBy;
            return this;
        }

        Builder currentBehaviour(String currentBehaviour) {
            this.currentBehaviour = currentBehaviour;
            return this;
        }

        Builder note(String note) {
            this.notes.add(note);
            return this;
        }

        Experiment build() {
            return new Experiment(this);
        }
    }
}
