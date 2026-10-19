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
     * Stand in for vanilla's title screen. Implemented by the menu Mixin, with
     * {@code MenuTakeover} catching a title screen that arrived before Fathom loaded.
     *
     * <p>Switchable for a reason worth stating: this replaces the first screen
     * of the game, and anything that does that has to have an off switch that
     * gives the original back untouched. Nothing is patched into vanilla's
     * screen, so off is exactly vanilla.
     */
    public static final String MAIN_MENU = "main-menu";

    /**
     * Toggle Sprint and Toggle Sneak. Implemented by the player-input Mixins,
     * which report a latched key to vanilla as held and change nothing else.
     */
    public static final String MOVEMENT_TOGGLE = "movement-toggle";

    /**
     * Fathom's own performance layer: the entity and tile entity distance
     * limits, the nametag fast path and the cached HUD layer. Implemented in
     * {@code fabric.perf} and read by the render hooks there.
     */
    public static final String PERFORMANCE_LAYER = "performance-layer";

    /**
     * Draw the scoreboard sidebar as a HUD widget, and skip vanilla's.
     * Implemented by the sidebar Mixin, which hands the objective over.
     */
    public static final String SCOREBOARD = "scoreboard";

    /** A brighter lightmap, read where vanilla reads gamma. Implemented by the lightmap Mixin. */
    public static final String FULLBRIGHT = "fullbright";

    /** The client world's sky at a fixed hour. Implemented by the sky angle Mixin. */
    public static final String TIME_CHANGER = "time-changer";

    /**
     * 1.7's first-person animations: the swing carried through blocking (the
     * blockhit) and through eating, drinking and drawing a bow. Implemented by
     * the held-item Mixin. Visual and client-only.
     */
    public static final String OLD_ANIMATIONS = "old-animations";

    /** Timestamps, folded repeats, background and shadow. Implemented by the chat Mixin. */
    public static final String CHAT = "chat";

    /** Every behaviour the client implements. */
    public static final String[] ALL = {
        ZOOM, CROSSHAIR, MAIN_MENU, MOVEMENT_TOGGLE, PERFORMANCE_LAYER, SCOREBOARD, FULLBRIGHT, TIME_CHANGER, OLD_ANIMATIONS, CHAT
    };

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
