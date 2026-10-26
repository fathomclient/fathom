package net.fathom.optimizations.fabric.visual;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Weather Changer, TNT Countdown and Particles, as their render hooks ask for
 * them. Each answers from the module's settings at the moment vanilla asks,
 * and stores nothing: switching one off is vanilla again on the next frame.
 */
public final class WorldEffects {

    /** TNT labels given since the self-test last cleared it. */
    public static int tntLabels;

    private WorldEffects() {}

    static FathomModule active(String id) {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find(id);
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** The rain strength the client's world should show, or -1 to leave the server's. */
    public static float rain() {
        FathomModule module = active("weather-changer");
        if (module == null) {
            return -1.0F;
        }
        if ("Clear".equalsIgnoreCase(module.choice("weather", "Clear"))) {
            return 0.0F;
        }
        return (float) (module.number("intensity", 100) / 100.0);
    }

    /** The thunder strength the client's world should show, or -1 to leave the server's. */
    public static float thunder() {
        FathomModule module = active("weather-changer");
        if (module == null) {
            return -1.0F;
        }
        if (!"Thunder".equalsIgnoreCase(module.choice("weather", "Clear"))) {
            return 0.0F;
        }
        return (float) (module.number("intensity", 100) / 100.0);
    }

    /** The label over primed TNT, or null for none. */
    public static String tntLabel(int fuse, float tickDelta) {
        FathomModule module = active("tnt-countdown");
        if (module == null || fuse <= 0) {
            return null;
        }
        float seconds = Math.max(0.0F, (fuse - tickDelta) / 20.0F);
        String colour = !module.toggle("colour", true) ? "§f"
                : seconds > 2.0F ? "§a" : seconds > 1.0F ? "§e" : "§c";
        tntLabels++;
        if ("Ticks".equalsIgnoreCase(module.choice("format", "Seconds"))) {
            return colour + fuse;
        }
        return colour + String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    /** How many times to emit a hit's crit ({@code magic} false) or sharpness particles; 1 is vanilla. */
    public static int hitParticles(boolean magic) {
        FathomModule module = active("particles");
        if (module == null) {
            return 1;
        }
        return Math.max(0, (int) Math.round(module.number(magic ? "sharpness" : "crit", 1)));
    }

    /** Whether every hit shows crit ({@code magic} false) or sharpness particles. */
    public static boolean alwaysHitParticles(boolean magic) {
        FathomModule module = active("particles");
        return module != null && module.toggle(magic ? "always.sharpness" : "always.crit", false);
    }

    private static int explosionParticle;

    /**
     * Whether to keep the next of an explosion's per-block particles: all of
     * them, one in four, or none, as Particles is set.
     */
    public static boolean keepExplosionParticle() {
        FathomModule module = active("particles");
        if (module == null) {
            return true;
        }
        String choice = module.choice("explosions", "All");
        if ("None".equals(choice)) {
            return false;
        }
        if ("Fewer".equals(choice)) {
            return (explosionParticle++ & 3) == 0;
        }
        return true;
    }

    /** Whether block-breaking particles are hidden. */
    public static boolean hideBlockParticles() {
        FathomModule module = active("particles");
        return module != null && module.toggle("hide.blocks", false);
    }
}
