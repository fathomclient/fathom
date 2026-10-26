package net.fathom.optimizations.fabric.visual;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Hurt Camera, as the render hook asks for it.
 *
 * <p>Vanilla rolls the camera by up to 14 degrees when the player is hit. This
 * scales that one number at the moment vanilla reads it: nothing is stored, so
 * switching the module off is vanilla again on the next frame.
 */
public final class HurtCamera {

    private HurtCamera() {}

    /** The roll to use in place of vanilla's {@code original}. */
    public static float tilt(float original) {
        if (Runtime.features() == null) {
            return original;
        }
        FathomModule module = Runtime.features().find("hurt-camera");
        if (module == null || !module.enabled() || !module.switchable()) {
            return original;
        }
        return original * (float) (module.number("strength", 0) / 100.0);
    }
}
