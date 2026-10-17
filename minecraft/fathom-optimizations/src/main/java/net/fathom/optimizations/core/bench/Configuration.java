package net.fathom.optimizations.core.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The stacks a benchmark compares.
 *
 * <h2>Why six and not two</h2>
 *
 * <p>"With Fathom" against "without Fathom" is one number and it answers the
 * wrong question. Fathom is a loader, a module framework, a HUD, a renderer and
 * a handful of optimizations, and each of those costs something. A single
 * difference cannot say which — so a regression in the module framework and a
 * win in the renderer cancel out and the client looks unchanged.
 *
 * <p>These six isolate one layer at a time:
 *
 * <pre>
 *   A  vanilla                     the floor
 *   B  + Legacy Fabric             what the loader costs
 *   C  + Fathom Core               what the framework costs
 *   D  + Fathom Performance, off   what the registry costs when it does nothing
 *   E  Balanced preset             what the safe optimizations are worth
 *   F  Competitive preset          what all of them are worth
 * </pre>
 *
 * <p>The gap that decides this milestone is <b>D minus B</b>: everything Fathom
 * adds, with every optional feature switched off. If that is materially above a
 * few percent in a CPU-bound scene, no more optimizations get written until it
 * is understood — see {@code docs/performance/regression-thresholds.md}.
 *
 * <h2>Why C and D are separate</h2>
 *
 * <p>Because they fail differently. C is the HUD loop, the module registry and
 * the frame hook — work that happens whether or not anything is enabled. D adds
 * the optimization registry and its switch board, which with everything off
 * should cost essentially nothing. If D is measurably above C with everything
 * off, the registry itself is the problem, and that is a different fix from a
 * HUD that is too eager.
 *
 * <h2>What is not here</h2>
 *
 * <p>Lunar Client. It cannot be automated, its configuration cannot be verified
 * from outside it, and a number taken from it by hand is a reference point
 * rather than a comparison.
 */
public final class Configuration {

    /** How the stack is assembled for a run. */
    public enum Stack {
        /** No loader, no mods. */
        VANILLA,
        /** Legacy Fabric with no mods in the folder. */
        LOADER_ONLY,
        /** Legacy Fabric and Fathom's jar. */
        FATHOM
    }

    private final String id;
    private final String name;
    private final Stack stack;
    private final String preset;
    private final boolean fathomModules;
    private final boolean fathomOptimizations;
    private final String purpose;
    private final String setup;

    private Configuration(
            String id,
            String name,
            Stack stack,
            String preset,
            boolean fathomModules,
            boolean fathomOptimizations,
            String purpose,
            String setup) {
        this.id = id;
        this.name = name;
        this.stack = stack;
        this.preset = preset;
        this.fathomModules = fathomModules;
        this.fathomOptimizations = fathomOptimizations;
        this.purpose = purpose;
        this.setup = setup;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Stack stack() {
        return stack;
    }

    /** The performance preset, or empty when Fathom is not in the stack. */
    public String preset() {
        return preset;
    }

    /** Whether any Fathom module — HUD widget, Hub, menu — is enabled. */
    public boolean fathomModules() {
        return fathomModules;
    }

    /** Whether any optional optimization is enabled. */
    public boolean fathomOptimizations() {
        return fathomOptimizations;
    }

    /** What this configuration is for, in one sentence. */
    public String purpose() {
        return purpose;
    }

    /** How to put the machine into this state, precisely enough to repeat. */
    public String setup() {
        return setup;
    }

    /** The six, in the order they are meant to be run. */
    public static List<Configuration> all() {
        List<Configuration> out = new ArrayList<Configuration>();

        out.add(new Configuration(
                "a-vanilla",
                "Vanilla 1.8.9",
                Stack.VANILLA,
                "",
                false,
                false,
                "The floor. Everything else is measured against this.",
                "A profile with loader Vanilla and an empty mods directory. No Fathom jar "
                        + "anywhere in the game directory."));

        out.add(new Configuration(
                "b-loader",
                "Legacy Fabric only",
                Stack.LOADER_ONLY,
                "",
                false,
                false,
                "What the loader costs before any mod is added. The baseline every Fathom "
                        + "configuration is compared against.",
                "The same profile with loader Legacy Fabric and an empty mods directory. "
                        + "Fabric API is NOT installed: it is a mod, and this configuration is "
                        + "the loader alone."));

        out.add(new Configuration(
                "c-core",
                "Fathom Core",
                Stack.FATHOM,
                "vanilla",
                true,
                false,
                "What the module framework, HUD loop and frame hook cost with the "
                        + "optimization registry doing nothing.",
                "Fathom's jar in mods. Performance preset Vanilla, so every optional "
                        + "optimization is off. Default modules only: FPS and Coordinates on, "
                        + "everything else off. Hub closed."));

        out.add(new Configuration(
                "d-performance-off",
                "Fathom, everything optional off",
                Stack.FATHOM,
                "vanilla",
                false,
                false,
                "The overhead number. Everything Fathom adds, with nothing switched on.",
                "As C, and then every module switched off as well — no HUD widget, no "
                        + "main menu replacement. Hub closed. This is the configuration the "
                        + "regression threshold is written against."));

        out.add(new Configuration(
                "e-balanced",
                "Fathom Balanced",
                Stack.FATHOM,
                "balanced",
                true,
                true,
                "The default a user gets. Safe optimizations on, default HUD.",
                "As C, with the Balanced preset applied on the launcher's Performance "
                        + "page."));

        out.add(new Configuration(
                "f-competitive",
                "Fathom Competitive",
                Stack.FATHOM,
                "competitive",
                true,
                true,
                "Everything Fathom will turn on for someone who asks for frames.",
                "As C, with the Competitive preset applied."));

        return out;
    }

    public static Configuration byId(String id) {
        for (Configuration configuration : all()) {
            if (configuration.id.equals(id)) {
                return configuration;
            }
        }
        return null;
    }

    /**
     * The pair whose difference is Fathom's own overhead.
     *
     * <p>Named rather than left to a reader to work out, because getting it
     * wrong — comparing D against A, say — silently folds the loader's cost into
     * Fathom's and makes the number unactionable.
     */
    public static List<String> overheadPair() {
        return Collections.unmodifiableList(java.util.Arrays.asList("b-loader", "d-performance-off"));
    }

    @Override
    public String toString() {
        return id;
    }
}
