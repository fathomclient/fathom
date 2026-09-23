package net.fathom.optimizations.fabric;

import net.fathom.optimizations.core.guard.Guard;
import net.fathom.optimizations.core.instrument.Session;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;
import net.fathom.optimizations.fabric.bridge.LauncherBridge;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Static access for Mixins.
 *
 * <p><strong>Not compiled or run.</strong> See {@link FathomOptimizationsClient}.
 *
 * <p>Static because a Mixin has no constructor we control and no field we can inject into.
 * That is a real constraint of the technology rather than a preference, and the way to
 * make it tolerable is to keep the mutable surface tiny: three references, set once at
 * initialisation, never replaced.
 *
 * <p>Every accessor tolerates being called before {@link #install} — a Mixin can fire
 * during loading, before the client initializer has run. Returning "off" and doing nothing
 * is the correct behaviour in that window; throwing would crash the game during startup
 * for the sake of a measurement.
 */
public final class Runtime {

    private static volatile OptimizationRegistry registry;
    private static volatile SwitchBoard switches;
    private static volatile Guard guard;
    private static volatile Session session;
    private static volatile FathomHud hud;
    private static volatile ModuleRegistry features;
    private static volatile LauncherBridge bridge;

    private Runtime() {}

    static void install(OptimizationRegistry newRegistry, SwitchBoard newSwitches, Guard newGuard) {
        registry = newRegistry;
        switches = newSwitches;
        guard = newGuard;
        refreshHotSwitches();
    }

    // -----------------------------------------------------------------------
    // Switches read on hot paths
    // -----------------------------------------------------------------------

    public static final net.fathom.optimizations.core.registry.OptimizationId FONT_METRICS_CACHE =
            net.fathom.optimizations.core.registry.OptimizationId.of("render.font.metrics-cache");
    public static final net.fathom.optimizations.core.registry.OptimizationId CHUNK_LOOKUP_CACHE =
            net.fathom.optimizations.core.registry.OptimizationId.of("world.chunk.lookup-cache");
    public static final net.fathom.optimizations.core.registry.OptimizationId HOT_PATH_ALLOCATION =
            net.fathom.optimizations.core.registry.OptimizationId.of("cpu.allocation.hot-path-reduction");
    public static final net.fathom.optimizations.core.registry.OptimizationId WORLD_CHANGE_GC =
            net.fathom.optimizations.core.registry.OptimizationId.of("memory.world-change-gc");
    public static final net.fathom.optimizations.core.registry.OptimizationId UNFOCUSED_CAP =
            net.fathom.optimizations.core.registry.OptimizationId.of(FrameCapPolicy.UNFOCUSED_ID);

    /**
     * Switch positions for hooks that run per block, per chunk lookup or per
     * glyph measurement. Refreshed once per frame rather than looked up per
     * call: a map lookup there costs more than some of the work it guards. A
     * change made in the Hub takes effect on the next frame. Plain statics,
     * written and read on the client thread; a worker thread that reads a stale
     * value only chooses between two exactly equivalent code paths.
     */
    public static boolean fontMetricsCache;
    public static boolean chunkLookupCache;
    public static boolean hotPathAllocation;

    /** Re-read the hot switches from the board. Cheap: a handful of map lookups. */
    public static void refreshHotSwitches() {
        SwitchBoard current = switches;
        if (current == null) {
            return;
        }
        fontMetricsCache = current.isEnabled(FONT_METRICS_CACHE);
        chunkLookupCache = current.isEnabled(CHUNK_LOOKUP_CACHE);
        hotPathAllocation = current.isEnabled(HOT_PATH_ALLOCATION);
    }

    /**
     * Hand over the HUD, once the game directory is known.
     *
     * <p>Separate from {@link #install} because the two are ready at different moments and
     * fail for different reasons: the registry is built from constants and cannot fail,
     * while the HUD reads a file off disk. Keeping them apart means a broken layout file
     * cannot take the optimizations down with it.
     */
    static void installHud(FathomHud newHud) {
        hud = newHud;
    }

    /**
     * The HUD, or null before it is installed.
     *
     * <p>Every caller checks. A Mixin can fire during loading, before the client
     * initializer has run, and drawing nothing for one frame is the correct behaviour in
     * that window.
     */
    /**
     * How much larger or smaller than automatic the interface should be.
     *
     * <p>Read once per frame by every Fathom screen, so it answers from memory
     * rather than from the settings file — nothing in this client parses a
     * config while it is drawing.
     */
    public static float uiScale() {
        FathomHud current = hud();
        return current == null ? 1f : current.prefs().uiScale();
    }

    /** Whether antialiased framebuffer-sized Fathom geometry is enabled. */
    // Command-line overrides are fixed for the life of the process; read them
    // once rather than through the synchronized property table every frame.
    private static final String HIGH_QUALITY_OVERRIDE = System.getProperty("fathom.highQualityUi");
    private static final String MENU_BACKGROUND_OVERRIDE = System.getProperty("fathom.menuBackgroundQuality");
    private static final String HUD_FONT_OVERRIDE = System.getProperty("fathom.hudFont");

    public static boolean highQualityUi() {
        String override = HIGH_QUALITY_OVERRIDE;
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        FathomHud current = hud();
        return current == null || current.prefs().highQualityUi();
    }

    /** High, Auto, Low or Off. Independent from native-resolution UI quality. */
    public static String menuBackgroundQuality() {
        String override = MENU_BACKGROUND_OVERRIDE;
        if (override != null) {
            return override.trim();
        }
        FathomHud current = hud();
        return current == null ? "High" : current.prefs().menuBackgroundLabel();
    }

    /**
     * Whether the HUD widgets draw in Geist rather than Minecraft's font.
     *
     * <p>Overridable from the command line for the same reason the quality
     * switch is: the visual-QA gate has to be able to photograph both, and a
     * gate that had to change a saved preference file to do it would be a gate
     * that left the developer's preferences changed afterwards.
     */
    public static boolean hudFontIsFathom() {
        String override = HUD_FONT_OVERRIDE;
        if (override != null) {
            return !"minecraft".equalsIgnoreCase(override.trim());
        }
        FathomHud current = hud();
        return current == null || current.prefs().hudFontIsFathom();
    }

    /** The unfocused cap the user chose, in frames per second, or 0 for none. */
    public static int unfocusedFrameCap() {
        return chosenCap("unfocused-fps", FrameCapPolicy.UNFOCUSED_CHOICE, FrameCapPolicy.UNFOCUSED_CAP);
    }

    private static int chosenCap(String featureId, String settingId, int fallback) {
        ModuleRegistry registry = features();
        if (registry == null) {
            return fallback;
        }
        FathomModule feature = registry.find(featureId);
        if (feature == null) {
            return fallback;
        }
        // A direct lookup. This runs from `getMaxFramerate`, every frame, and
        // `settings()` builds a fresh list for callers that want a copy.
        Setting setting = feature.setting(settingId);
        return setting instanceof Setting.Choice
                ? FrameCapPolicy.capFor(((Setting.Choice) setting).get())
                : fallback;
    }

    public static FathomHud hud() {
        return hud;
    }


    /** Hand over the feature registry, once it is built and sealed. */
    static void installFeatures(ModuleRegistry newFeatures) {
        features = newFeatures;
    }

    /**
     * Every feature the client knows about.
     *
     * <p>Never null once the client has initialised, and an empty registry
     * before that — so a Mixin that fires during loading gets a list with
     * nothing in it rather than a null to check.
     */
    public static ModuleRegistry features() {
        ModuleRegistry current = features;
        return current == null ? ModuleRegistry.empty() : current;
    }

    static void installBridge(LauncherBridge newBridge) {
        bridge = newBridge;
    }

    /**
     * The connection to the launcher.
     *
     * <p>Never null: a bridge with no handshake file reports {@code ABSENT} and
     * refuses every call, which is the correct behaviour for a game somebody
     * started from a shortcut and means no caller needs a null check.
     */
    public static LauncherBridge bridge() {
        LauncherBridge current = bridge;
        if (current == null) {
            current = new LauncherBridge(new java.io.File("."));
            bridge = current;
        }
        return current;
    }

    public static boolean ready() {
        return switches != null;
    }

    /** Whether an optimization is on. False before installation, and for unknown ids. */
    public static boolean enabled(String id) {
        SwitchBoard current = switches;
        return current != null && current.isEnabled(id);
    }

    public static OptimizationRegistry registry() {
        return registry;
    }

    public static SwitchBoard switches() {
        return switches;
    }

    public static Guard guard() {
        return guard;
    }

    /** The measurement session, or null when nothing is being measured. */
    public static Session session() {
        return session;
    }

    /**
     * Start measuring.
     *
     * <p>Replaces any running session. Benchmarking is a deliberate act by whoever is
     * running it; silently refusing to start because an old one was left running would be
     * more confusing than replacing it.
     */
    public static Session beginSession(String label) {
        return start(new Session(label));
    }

    /** Start measuring with an explicit warm-up, for a scripted run that settles on its own. */
    public static Session beginSessionFor(String label, int warmupFrames) {
        return start(new Session(label, warmupFrames,
                net.fathom.optimizations.core.instrument.FrameLog.DEFAULT_CAPACITY));
    }

    private static Session start(Session started) {
        started.start();
        session = started;
        return started;
    }

    /** Stop measuring and return the report, or null when nothing was running. */
    public static net.fathom.optimizations.core.instrument.Report endSession() {
        Session current = session;
        session = null;
        return current == null ? null : current.stop();
    }

    /**
     * Record a frame, if a session is running.
     *
     * <p>Called once per frame from a Mixin. The null check is the whole cost when no
     * benchmark is running, which is the common case.
     */
    public static void frame(long frameNanos) {
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowEventDispatch()) {
            refreshHotSwitches();
        }
        // The performance layer's hooks read plain statics; this refreshes them
        // when a setting changed, and resets the per-frame nametag budget.
        net.fathom.optimizations.fabric.perf.PerfLayer.frame();
        // The probe first: it rolls the per-frame section totals into the
        // worst-frame figure, and a session that ended mid-frame should still
        // see the frame it was in.
        net.fathom.optimizations.core.instrument.Probe.frameEnded();
        // After frameEnded, so the window that is about to be reported has this
        // frame's sections rolled in.
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowCoreHooks()) {
            net.fathom.optimizations.fabric.diag.FramePerf.frame(frameNanos);
        }
        // The automated benchmark advances here because this is the render
        // thread and the frame time is already in hand. Folds away when the
        // process was not started for a benchmark.
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.ACTIVE) {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.FRAMES);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkRunner.frame(frameNanos);
        }
        Session current = session;
        if (current != null
                && net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowEventDispatch()) {
            current.frame(frameNanos);
            // Focus is checked here rather than only while a screen is drawn,
            // because a benchmark is played in the world with everything closed
            // -- and a run that lost focus reports the unfocused frame cap
            // rather than the machine.
            FOCUS.sample();
        }
    }

    /**
     * Whether the window kept focus for the run in progress.
     *
     * <p>Lives here because the frame hook is the only thing that runs every
     * frame regardless of what is on screen.
     */
    private static final net.fathom.optimizations.fabric.bench.Capture.Focus FOCUS =
            new net.fathom.optimizations.fabric.bench.Capture.Focus();

    /** The focus watcher for the run in progress. */
    public static net.fathom.optimizations.fabric.bench.Capture.Focus focus() {
        return FOCUS;
    }
}
