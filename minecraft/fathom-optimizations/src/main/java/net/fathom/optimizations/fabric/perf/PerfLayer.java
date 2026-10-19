package net.fathom.optimizations.fabric.perf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.PerformanceLayer;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.opt.perf.PerfCounters;
import net.fathom.optimizations.fabric.Runtime;

/**
 * What the performance layer's hooks read, refreshed once per frame.
 *
 * <p>Every hook in {@code fabric.perf} runs per entity, per tile entity or per
 * nametag, so none of them looks anything up. They read the public static
 * fields here, which {@link #frame()} refreshes from the module's settings only
 * when a setting has changed — one integer comparison on an ordinary frame.
 *
 * <h2>OptiFine</h2>
 *
 * <p>Nothing here turns an OptiFine option off or overrides one. The distance
 * limits tighten the answer vanilla's (and OptiFine's) own range test gives;
 * the nametag path steps aside while an OptiFine shader pack is active, because
 * shader packs rebind textures through their own path.
 */
public final class PerfLayer {

    private PerfLayer() {}

    public static final PerfCounters COUNTERS = new PerfCounters();

    private static final PerformanceLayer.Snapshot CONFIGURED = new PerformanceLayer.Snapshot().off();
    private static final PerformanceLayer.Snapshot OFF = new PerformanceLayer.Snapshot().off();

    /** The snapshot in force: the user's, or all-off during a benchmark's A arm. */
    public static PerformanceLayer.Snapshot active = OFF;

    /** Hot copies of {@link #active}. */
    public static boolean entitiesLimited;
    public static boolean tilesLimited;
    public static boolean fastNametags;
    public static boolean hudCache;
    public static boolean stats;
    public static double[] entityLimitsSquared = OFF.entityLimitsSquared;
    public static double[] tileLimitsSquared = OFF.tileLimitsSquared;

    /** True while an OptiFine shader pack is loaded. Refreshed per frame. */
    public static boolean shadersActive;

    /**
     * Which features a benchmark is measuring, from {@code -Dfathom.perf.only}.
     * Empty means all of them, which is every ordinary launch. Attribution needs
     * one feature at a time; a user never sets this.
     */
    private static final String ONLY = System.getProperty("fathom.perf.only", "").trim().toLowerCase(
            java.util.Locale.ROOT);

    /** The features under test, for the benchmark report. */
    public static String onlyFeatures() {
        return ONLY.isEmpty() ? "all" : ONLY;
    }

    private static boolean measuring(String feature) {
        return ONLY.isEmpty() || ONLY.contains(feature);
    }

    private static int seenRevision = Integer.MIN_VALUE;
    private static boolean seenEnabled;
    private static boolean forcedOff;
    private static boolean dirty = true;

    /**
     * Force everything off regardless of the settings, or release that.
     * The benchmark's A arm; also the escape hatch if a hook ever misbehaves.
     */
    public static void forceOff(boolean off) {
        if (forcedOff != off) {
            forcedOff = off;
            dirty = true;
            refresh();
        }
    }

    public static boolean forcedOff() {
        return forcedOff;
    }

    /** Called once per frame from the frame hook. */
    public static void frame() {
        COUNTERS.frames++;
        shadersActive = queryShaders();
        NametagRenderer.frame();
        TextLists.frame();
        FathomModule module = module();
        boolean enabled = module != null && module.enabled();
        if (dirty || Setting.revision() != seenRevision || enabled != seenEnabled) {
            refresh();
        }
    }

    private static void refresh() {
        FathomModule module = module();
        seenRevision = Setting.revision();
        seenEnabled = module != null && module.enabled();
        dirty = false;
        CONFIGURED.read(module);
        PerformanceLayer.Snapshot next = forcedOff ? OFF : CONFIGURED;
        active = next;
        entitiesLimited = next.entitiesLimited() && measuring("entities");
        tilesLimited = next.tilesLimited() && measuring("tiles");
        fastNametags = next.fastNametags && measuring("nametags");
        hudCache = next.hudCache && measuring("hud");
        stats = next.stats;
        entityLimitsSquared = next.entityLimitsSquared;
        tileLimitsSquared = next.tileLimitsSquared;
        if (!fastNametags) {
            NametagRenderer.release();
        }
        if (!hudCache) {
            HudLayerCache.release();
            HudSteadyCache.release();
        }
    }

    public static FathomModule module() {
        return Runtime.features().find(PerformanceLayer.ID);
    }

    // -----------------------------------------------------------------------
    // OptiFine shader detection
    // -----------------------------------------------------------------------

    /** {@code Config.isShaders()}, which lives in OptiFine's default package. */
    private static final MethodHandle IS_SHADERS = findIsShaders();

    private static MethodHandle findIsShaders() {
        try {
            Class<?> config = Class.forName("Config");
            return MethodHandles.publicLookup().findStatic(config, "isShaders", MethodType.methodType(boolean.class));
        } catch (Throwable absent) {
            return null;
        }
    }

    /** Whether OptiFine is present at all, for the benchmark report. */
    public static boolean optifinePresent() {
        return IS_SHADERS != null;
    }

    private static boolean queryShaders() {
        MethodHandle handle = IS_SHADERS;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable failure) {
            return true;
        }
    }
}
