package net.fathom.optimizations.core.module;

/** Single gate shared by the custom draw path and vanilla-crosshair hook. */
public final class CrosshairPolicy {

    private CrosshairPolicy() {}

    /** A custom crosshair exists only after the user explicitly enables its module. */
    public static boolean drawsCustom(FathomModule module) {
        return module != null
                && module.enabled()
                && module.switchable()
                && !"Vanilla".equals(module.choice("shape", "Vanilla"));
    }

    /** Vanilla/resource-pack rendering is cancelled only for an active custom replacement. */
    public static boolean hidesVanilla(FathomModule module) {
        return drawsCustom(module) && module.toggle("hide-vanilla", true);
    }
}
