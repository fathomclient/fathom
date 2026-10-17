package net.fathom.optimizations.fabric.visual;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.visual.Lighting;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Fullbright and Time Changer, as the render hooks ask for them.
 *
 * <p>Both change a value at the moment vanilla reads it and nowhere else:
 * the gamma inside the lightmap update, the sky angle of the client's world.
 * Nothing is written to the game's options or to the world, so switching
 * either off restores vanilla on the next frame with nothing to undo.
 */
public final class WorldLighting {

    /** Whether the lightmap hook has ever run: false under a renderer that replaced it. */
    private static boolean lightmapSeen;

    private WorldLighting() {}

    private static FathomModule active(String id) {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find(id);
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** The gamma the lightmap should use this update. */
    public static float gamma(float original) {
        lightmapSeen = true;
        FathomModule module = active("fullbright");
        if (module == null) {
            return original;
        }
        return Math.max(original, Lighting.gamma(module.number("strength", 1000)));
    }

    /** Whether vanilla's lightmap code has been seen running Fullbright's hook. */
    public static boolean lightmapSeen() {
        return lightmapSeen;
    }

    /**
     * The world tick the client's sky should show, or -1 to leave the server's.
     */
    public static long skyTicks() {
        FathomModule module = active("time-changer");
        if (module == null) {
            return -1L;
        }
        return Lighting.ticks(module.choice("time", "Noon"), (int) Math.round(module.number("hour", 12)));
    }
}
