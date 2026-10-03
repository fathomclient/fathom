package net.fathom.optimizations.fabric.bench.auto;

import net.fathom.optimizations.core.bench.auto.RenderCounters;

/**
 * The one place benchmark mode keeps its state.
 *
 * <h2>Why everything is here and not spread around</h2>
 *
 * <p>The alternative is a boolean on each class that a benchmark can switch, and
 * that is how a diagnostic feature becomes permanent: the flags outlive the
 * investigation, each one is read on a hot path, and nobody can say afterwards
 * which of them still does anything. One class means one place to look, one
 * place to reset, and one {@link #ACTIVE} constant guarding the lot.
 *
 * <h2>Cost when off</h2>
 *
 * <p>{@link #ACTIVE} is {@code static final}, set from a launch argument read
 * once at class load. Every call site is {@code if (BenchmarkState.ACTIVE)}, so
 * on a normal launch the JIT removes the branch and its body. A normal launch
 * behaves exactly as it did before this existed.
 */
public final class BenchmarkState {

    /**
     * Whether this process was started for a benchmark.
     *
     * <p>Accepts {@code --fathom-benchmark} in the game arguments, because that
     * is what somebody typing a launch flag will write, and
     * {@code -Dfathom.benchmark=true} as well, because a launcher that builds a
     * command line has an easier time adding a system property.
     */
    public static final boolean ACTIVE = detect();

    /** Dedicated short diagnosis requested after the optional-module benchmark. */
    public static final boolean CORE = "core".equalsIgnoreCase(
            System.getProperty("fathom.benchmark", ""));

    /** Tightly interleaved follow-up that compares only normal core and bypass. */
    public static final boolean CORE_PAIRED = "core-paired".equalsIgnoreCase(
            System.getProperty("fathom.benchmark", ""));

    /**
     * The performance-layer A/B run. Not a core-diagnostic arm: it compares
     * Fathom's optimizations off against on, so every core hook stays live in
     * both arms and only {@code PerfLayer} changes.
     */
    public static final boolean PERF_PAIRED = "perf-paired".equalsIgnoreCase(
            System.getProperty("fathom.benchmark", ""));

    /**
     * The view-direction profile: fixed camera scenes, exact-boundary section
     * timing, and interleaved arms (probes off, Fathom bypassed, GPU drain).
     * A core-diagnostic mode because its Fathom-off arm uses the same bypass.
     */
    public static final boolean VIEW = net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED;

    private static final boolean CORE_DIAGNOSTIC = CORE || CORE_PAIRED || VIEW;

    private static boolean detect() {
        try {
            String property = System.getProperty("fathom.benchmark", "");
            if (!property.isEmpty() && !"false".equalsIgnoreCase(property)
                    && !"off".equalsIgnoreCase(property)) {
                return true;
            }
            String command = System.getProperty("sun.java.command", "");
            if (command.contains("--fathom-benchmark")) {
                return true;
            }
            // The loader keeps the arguments it was handed, which is the only
            // reliable source once the game has parsed and discarded its own.
            String[] arguments =
                    net.fabricmc.loader.api.FabricLoader.getInstance().getLaunchArguments(true);
            if (arguments != null) {
                for (String argument : arguments) {
                    if ("--fathom-benchmark".equals(argument)) {
                        return true;
                    }
                }
            }
        } catch (Throwable unavailable) {
            // No launch arguments is a normal launch, not an error.
            return false;
        }
        return false;
    }

    /** Counters for the pass in progress. Allocated once, reset per pass. */
    private static final RenderCounters COUNTERS = new RenderCounters();

    /** Switched by the HUD-pass subsystem. */
    private static boolean hudPass = true;

    /** Switched by the Fathom-typeface subsystem. */
    private static boolean fathomFont = true;

    /** Set while a pass is measuring, so counters only cover measured frames. */
    private static volatile boolean measuring;

    /** Runtime gates used only by the six-pass core-isolation run. */
    private static boolean coreHooks = true;
    private static boolean eventDispatch = true;
    private static boolean renderHooks = true;
    private static boolean optifineOverlapHooks = true;
    private static boolean instrumentation = true;

    private BenchmarkState() {}

    public static RenderCounters counters() {
        return COUNTERS;
    }

    /** Count one, if a pass is measuring. The hot-path entry point. */
    public static void count(RenderCounters.Counter counter) {
        if (ACTIVE && measuring && instrumentation) {
            COUNTERS.hit(counter);
        }
    }

    public static boolean measuring() {
        return measuring;
    }

    public static void setMeasuring(boolean on) {
        measuring = on;
    }

    /**
     * Apply one core-isolation arm. The strings are report ids, deliberately
     * kept here so every hot-path gate reads primitive booleans only.
     */
    public static void applyCorePass(String id) {
        coreHooks = true;
        eventDispatch = true;
        renderHooks = true;
        optifineOverlapHooks = true;
        instrumentation = true;
        if ("core-bypass".equals(id)) {
            coreHooks = false;
            eventDispatch = false;
            renderHooks = false;
            optifineOverlapHooks = false;
        } else if ("event-bypass".equals(id)) {
            eventDispatch = false;
        } else if ("render-bypass".equals(id)) {
            renderHooks = false;
        } else if ("optifine-bypass".equals(id)) {
            optifineOverlapHooks = false;
        } else if ("instrument-control".equals(id)) {
            coreHooks = false;
            eventDispatch = false;
            renderHooks = false;
            optifineOverlapHooks = false;
            instrumentation = false;
        }
    }

    public static boolean allowCoreHooks() {
        return !CORE_DIAGNOSTIC || coreHooks;
    }

    public static boolean allowEventDispatch() {
        return !CORE_DIAGNOSTIC || (coreHooks && eventDispatch);
    }

    public static boolean allowRenderHooks() {
        return !CORE_DIAGNOSTIC || (coreHooks && renderHooks);
    }

    public static boolean allowOptifineOverlapHooks() {
        return !CORE_DIAGNOSTIC || (coreHooks && optifineOverlapHooks);
    }

    public static boolean instrumentationEnabled() {
        return !CORE_DIAGNOSTIC || instrumentation;
    }

    public static long profileStart() {
        return instrumentationEnabled()
                ? net.fathom.optimizations.core.instrument.Probe.start() : 0L;
    }

    public static void profileStop(
            net.fathom.optimizations.core.instrument.Probe.Section section, long at) {
        if (instrumentationEnabled()) {
            net.fathom.optimizations.core.instrument.Probe.stop(section, at);
        }
    }

    public static boolean hudPassEnabled() {
        return hudPass;
    }

    public static void setHudPassEnabled(boolean on) {
        hudPass = on;
    }

    public static boolean fathomFontEnabled() {
        return fathomFont;
    }

    public static void setFathomFontEnabled(boolean on) {
        fathomFont = on;
    }

    /**
     * Whether the HUD should draw at all this frame.
     *
     * <p>Read by the HUD mixin. Outside benchmark mode this is a constant true
     * and folds away.
     */
    public static boolean allowHudPass() {
        return !ACTIVE || hudPass;
    }

    /** Whether a module may use the Fathom typeface. */
    public static boolean allowFathomFont() {
        return !ACTIVE || fathomFont;
    }

    /** Put the switches back to their defaults. For the end of a run. */
    public static void reset() {
        hudPass = true;
        fathomFont = true;
        measuring = false;
        COUNTERS.reset();
        applyCorePass("normal-core");
    }
}
