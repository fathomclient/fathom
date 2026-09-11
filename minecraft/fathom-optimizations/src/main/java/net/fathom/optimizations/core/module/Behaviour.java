package net.fathom.optimizations.core.module;

/**
 * The behaviours the client implements, by name.
 *
 * <h2>Why a module's effect is declared rather than assumed</h2>
 *
 * <p>Most modules act through a HUD widget or an optimization, and the registry
 * can see both. A few act on the game directly — Zoom changes the field of view;
 * the crosshair replaces vanilla's — and nothing in the model would know that.
 * Before this existed, those modules were switches over booleans nobody read,
 * which is exactly the defect this milestone is about.
 *
 * <p>So a module names its behaviour, the client implements one hook per name,
 * and {@code ModuleTests} checks the two lists against each other. A module
 * naming a behaviour nobody implements fails the build; a behaviour nobody
 * claims is dead code and also fails.
 */
public final class Behaviour {

    /** Hold a key to narrow the field of view. Implemented by the FOV Mixin. */
    public static final String ZOOM = "zoom";

    /** Draw our crosshair and hide vanilla's. Implemented by the HUD Mixin. */
    public static final String CROSSHAIR = "crosshair";

    /**
     * Stand in for vanilla's title screen. Implemented by the menu Mixin.
     *
     * <p>Switchable for a reason worth stating: this replaces the first screen
     * of the game, and anything that does that has to have an off switch that
     * gives the original back untouched. Nothing is patched into vanilla's
     * screen, so off is exactly vanilla.
     */
    public static final String MAIN_MENU = "main-menu";

    /** Every behaviour the client implements. */
    public static final String[] ALL = {ZOOM, CROSSHAIR, MAIN_MENU};

    private Behaviour() {}

    public static boolean implemented(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String candidate : ALL) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
