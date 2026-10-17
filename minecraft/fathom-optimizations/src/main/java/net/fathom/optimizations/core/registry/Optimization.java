package net.fathom.optimizations.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One thing we might do to make the game faster.
 *
 * <p>Immutable and descriptive: an {@code Optimization} says what something <em>is</em>,
 * never whether it is currently on. That is {@link SwitchBoard}'s job, and keeping them
 * apart is what lets the registry be a compile-time constant while the switches are
 * per-profile state.
 *
 * <h2>Evidence, not intention</h2>
 *
 * <p>{@link #expectedBenefit()} is a hypothesis until {@link #measured()} says otherwise.
 * That distinction is enforced: {@link #defaultEnabled()} returns false for anything
 * unmeasured, whatever its category, so the "benchmark before defaults" rule in the
 * milestone brief cannot be violated by forgetting.
 *
 * <h2>Provenance</h2>
 *
 * <p>{@link #basis()} records where the knowledge came from. {@code mod-policies.md} §3
 * establishes that Patcher and PolyPatcher are CC BY-NC-SA and their <em>code</em> is not
 * reusable; observing that a problem exists is not the same as copying a solution, and
 * this field is where that line is written down per optimization rather than argued about
 * later.
 */
public final class Optimization {

    /** Where our knowledge of the problem and its fix came from. */
    public enum Basis {
        /**
         * A Mojang bug report, and where one exists, Mojang's own later fix.
         *
         * <p>The preferred basis. A documented defect plus an official fix is the
         * cleanest possible provenance.
         */
        MOJANG_BUG,
        /** Our own reading of the decompiled vanilla code. */
        VANILLA_ANALYSIS,
        /**
         * A restricted-licence project's public feature list or changelog told us the
         * problem exists. <strong>No code was read or reused.</strong>
         *
         * <p>{@code mod-policies.md} §3: their descriptions are facts about the game;
         * their source is CC BY-NC-SA and not ours to translate.
         */
        THIRD_PARTY_PROBLEM_STATEMENT,
        /** General JVM or OpenGL literature, not specific to Minecraft. */
        PLATFORM_LITERATURE
    }

    /** Which part of the stack an optimization lives in. */
    public enum Surface {
        /** Inside the game, via Mixin. */
        GAME,
        /** A JVM flag the launcher passes. Not this mod's code at all. */
        JVM,
        /** A launcher-owned file or process. */
        LAUNCHER
    }

    /**
     * Where a player would look for it. One vocabulary for the Hub, the
     * launcher and the exported registry, so the three cannot file the same
     * optimization under different headings.
     */
    public enum Group {
        RENDERING("Rendering"),
        CHUNKS("Chunks"),
        ENTITIES("Entities"),
        MEMORY("Memory"),
        FRAMES("Frames and input"),
        HUD("HUD and interface"),
        RESOURCES("Resources");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How a player turns it on or off -- or whether they do at all.
     *
     * <p>The difference matters to the launcher: a switch on something the
     * launcher cannot actually switch is the "toggle that changes nothing" this
     * project keeps removing. {@link #AUTOMATIC} gets no switch because it is
     * always right to have it; {@link #IN_GAME} gets none in the launcher
     * because its setting lives in a Fathom module and is changed in the Hub.
     */
    public enum Control {
        /** A launcher switch, sent to the game in the config bridge. */
        SWITCH,
        /** Always on where it applies. No decision to offer. */
        AUTOMATIC,
        /** Part of an in-game module's settings, changed from the Hub. */
        IN_GAME
    }

    /**
     * How far the implementation has got. Derived, never declared, so an entry
     * cannot claim to run in the game without the flags that say it does.
     */
    public enum Status {
        /** Described, not written. Never switched on by anything. */
        PLANNED,
        /** Logic in {@code core/}, unit-tested, with no hook into the game. */
        CORE_WRITTEN,
        /**
         * Hooked into the game and shipped in the jar, but not yet seen doing
         * its job in a running client. "It compiles and the hook applies" and
         * "it did the right thing in Minecraft" are different claims.
         */
        IN_MODULE,
        /** Hooked into the game and observed applying in a running 1.8.9. */
        RUNS_IN_GAME
    }

    private final OptimizationId id;
    private final String name;
    private final String description;
    private final Category category;
    private final Surface surface;
    private final Basis basis;
    private final Compatibility compatibility;
    private final List<Conflict> conflicts;
    private final String expectedBenefit;
    private final Evidence evidence;
    private final boolean implementedInCore;
    private final boolean runsInGame;
    private final boolean inModule;
    private final boolean defaultOnWhenMeasured;
    private final List<String> references;
    private final Group group;
    private final Control control;

    private Optimization(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.name = require(builder.name, "name");
        this.description = require(builder.description, "description");
        this.category = Objects.requireNonNull(builder.category, "category");
        this.surface = Objects.requireNonNull(builder.surface, "surface");
        this.basis = Objects.requireNonNull(builder.basis, "basis");
        this.compatibility = Objects.requireNonNull(builder.compatibility, "compatibility");
        this.conflicts = Collections.unmodifiableList(new ArrayList<Conflict>(builder.conflicts));
        this.expectedBenefit = require(builder.expectedBenefit, "expectedBenefit");
        this.evidence = builder.evidence;
        this.implementedInCore = builder.implementedInCore;
        this.runsInGame = builder.runsInGame;
        this.inModule = builder.inModule;
        this.defaultOnWhenMeasured = builder.defaultOnWhenMeasured;
        this.references = Collections.unmodifiableList(new ArrayList<String>(builder.references));
        this.group = Objects.requireNonNull(builder.group, "group");
        this.control = builder.control;

        if (defaultOnWhenMeasured && !category.mayDefaultOn()) {
            throw new IllegalArgumentException(
                    id + ": only SAFE optimizations may ever default on; this one is " + category);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("an optimization needs a " + field);
        }
        return value;
    }

    public static Builder builder(String id) {
        return new Builder(OptimizationId.of(id));
    }

    public OptimizationId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Category category() {
        return category;
    }

    public Surface surface() {
        return surface;
    }

    public Basis basis() {
        return basis;
    }

    public Compatibility compatibility() {
        return compatibility;
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }

    /**
     * What we expect this to gain, in words.
     *
     * <p>A hypothesis, phrased as one. Not a number, because we do not have numbers yet
     * and inventing them is what this milestone exists to avoid.
     */
    public String expectedBenefit() {
        return expectedBenefit;
    }

    /**
     * Our own measurement of this, or {@code null} if there is none.
     *
     * <p>See {@link Evidence}: the level matters as much as the existence. An isolated
     * microbenchmark is real evidence that an implementation works and is not slower than
     * what it replaces, and is still not grounds for turning anything on.
     */
    public Evidence evidence() {
        return evidence;
    }

    /**
     * Whether the logic behind this exists in {@code core} and is tested.
     *
     * <p>Distinct from being on, and distinct from being wired into the game. Nothing in
     * this module is wired into the game yet — the Fabric and Mixin layer has never been
     * compiled — so this flag says "the decision logic is written and checked", which is
     * as far as anything has got.
     */
    public boolean implementedInCore() {
        return implementedInCore;
    }

    /**
     * Whether a Mixin in this build actually calls it.
     *
     * <p>Distinct from {@link #implementedInCore()}, and the distinction is the
     * whole point. "Implemented in core" means the decision logic exists and has
     * tests — a real thing, and not the same thing as being wired into the game.
     * Four optimizations were in that state and the in-game Performance page
     * offered a working switch for every one of them, because it gated on the
     * wrong fact. Moving the switch changed a boolean nothing read.
     *
     * <p>So this is set by hand, on exactly the entries that have a hook, and
     * {@code OptTests} checks the count against the Mixin list. It is
     * deliberately not derived from anything: a fact that derived itself from
     * the registry would agree with the registry rather than with the game.
     */
    public boolean runsInGame() {
        return runsInGame;
    }

    /** Whether we have any measurement at all, of any scope. */
    public boolean measured() {
        return evidence != null;
    }

    /** Whether we have a measurement strong enough to promote this. */
    public boolean measuredInGame() {
        return evidence != null && evidence.permitsDefaultOn();
    }

    /**
     * Whether this is on unless the user says otherwise.
     *
     * <p><strong>Always false until measured.</strong> The milestone brief says
     * "establish repeatable measurement before promoting optimizations", and this is that
     * rule expressed as code rather than as a note somebody has to remember. A SAFE
     * optimization that nobody has benchmarked ships off.
     */
    public boolean defaultEnabled() {
        return measuredInGame() && defaultOnWhenMeasured;
    }

    /** Documents, bug reports and sources behind this entry. */
    public List<String> references() {
        return references;
    }

    /** Whether this optimization's code lives in this mod at all. */
    public Group group() {
        return group;
    }

    public Control control() {
        return control;
    }

    public Status status() {
        if (runsInGame) {
            return Status.RUNS_IN_GAME;
        }
        if (inModule) {
            return Status.IN_MODULE;
        }
        return implementedInCore ? Status.CORE_WRITTEN : Status.PLANNED;
    }

    /** The conflict that stands this down when {@code mod} is installed, or null. */
    public Conflict standsDownFor(String mod) {
        for (Conflict conflict : conflicts) {
            if (conflict.resolution() == Conflict.Resolution.DISABLE_OURS
                    && conflict.with().equalsIgnoreCase(mod)) {
                return conflict;
            }
        }
        return null;
    }

    public boolean isGameSide() {
        return surface == Surface.GAME;
    }

    /**
     * One line explaining the current default, including <em>why</em> it is off.
     *
     * <p>"Off" with no reason is the sort of thing that makes a user assume the feature is
     * broken.
     */
    public String defaultExplanation() {
        if (defaultEnabled()) {
            return "On by default: classified " + category.label()
                    + " and measured under our own benchmark method.";
        }
        if (evidence == null) {
            return "Off by default: not yet measured. Nothing is promoted before it has been"
                    + " benchmarked, whatever its risk class.";
        }
        if (!evidence.permitsDefaultOn()) {
            return "Off by default: measured only in isolation (" + evidence.runId() + "). That"
                    + " establishes the implementation works and is not slower than what it"
                    + " replaces; it does not establish that the game got faster, and only an"
                    + " in-game run does that.";
        }
        return "Off by default: classified " + category.label()
                + ", so it is the user's choice rather than ours.";
    }

    @Override
    public String toString() {
        return id + " (" + category + ", "
                + (evidence == null ? "unmeasured" : evidence.level().toString().toLowerCase()) + ")";
    }

    public static final class Builder {
        private final OptimizationId id;
        private String name;
        private String description;
        private Category category;
        private Surface surface = Surface.GAME;
        private Basis basis;
        private Compatibility compatibility = Compatibility.baseline();
        private final List<Conflict> conflicts = new ArrayList<Conflict>();
        private String expectedBenefit;
        private Evidence evidence;
        private boolean implementedInCore;
        private boolean runsInGame;
        private boolean inModule;
        private boolean defaultOnWhenMeasured;
        private final List<String> references = new ArrayList<String>();
        private Group group;
        private Control control = Control.SWITCH;

        private Builder(OptimizationId id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder category(Category category) {
            this.category = category;
            return this;
        }

        public Builder surface(Surface surface) {
            this.surface = surface;
            return this;
        }

        public Builder basis(Basis basis) {
            this.basis = basis;
            return this;
        }

        public Builder compatibility(Compatibility compatibility) {
            this.compatibility = compatibility;
            return this;
        }

        public Builder conflict(Conflict conflict) {
            this.conflicts.add(conflict);
            return this;
        }

        public Builder expectedBenefit(String expectedBenefit) {
            this.expectedBenefit = expectedBenefit;
            return this;
        }

        /**
         * Record a measurement of our own.
         *
         * <p>Deliberately carries a run identifier rather than a number: the numbers live
         * in {@code docs/performance/benchmarks.md} with the run that produced them, not
         * inlined here where they would drift out of agreement with it.
         */
        /** The decision logic for this exists in {@code core} and has tests. */
        public Builder implementedInCore() {
            this.implementedInCore = true;
            return this;
        }

        /**
         * A Mixin in this build calls it.
         *
         * <p>Implies {@link #implementedInCore()}: something has to be there to
         * call. Set only where a hook exists, because it is what the interface
         * gates a switch on.
         */
        /** Hooked in and shipped; not yet observed working in a running game. */
        public Builder inModule() {
            this.implementedInCore = true;
            this.inModule = true;
            return this;
        }

        public Builder runsInGame() {
            this.implementedInCore = true;
            this.runsInGame = true;
            return this;
        }

        public Builder evidence(Evidence evidence) {
            this.evidence = evidence;
            return this;
        }

        /**
         * Ask for this to be on by default <em>once it has been measured</em>.
         *
         * <p>Named for what it does. A plain {@code defaultOn()} would read as an
         * assertion that it is currently on, which it is not until {@link #measured()}.
         */
        public Builder defaultOnWhenMeasured() {
            this.defaultOnWhenMeasured = true;
            return this;
        }

        public Builder group(Group group) {
            this.group = group;
            return this;
        }

        /** No switch: always on where it applies. */
        public Builder automatic() {
            this.control = Control.AUTOMATIC;
            return this;
        }

        /** Set in an in-game module's settings rather than by a launcher switch. */
        public Builder inGameSetting() {
            this.control = Control.IN_GAME;
            return this;
        }

        public Builder reference(String reference) {
            this.references.add(reference);
            return this;
        }

        public Optimization build() {
            return new Optimization(this);
        }
    }
}
