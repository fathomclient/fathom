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

    /**
     * How far the camera tilts when you take damage: none, vanilla's, or more.
     * Implemented by the hurt-camera Mixin. Visual and client-only.
     */
    public static final String HURT_CAMERA = "hurt-camera";

    /** The targeted block's box in your colours. Implemented by the block outline Mixin. */
    public static final String BLOCK_OUTLINE = "block-outline";

    /** F3+B boxes, always on and styled. Implemented by the hitbox Mixin. */
    public static final String HITBOXES = "hitboxes";

    /** Dropped items lie flat and tumble. Implemented by the item entity renderer Mixin. */
    public static final String ITEM_PHYSICS = "item-physics";

    /** The client world's rain and thunder. Implemented by the weather Mixin. */
    public static final String WEATHER_CHANGER = "weather-changer";

    /** The fuse over primed TNT. Implemented by the TNT renderer Mixin. */
    public static final String TNT_COUNTDOWN = "tnt-countdown";

    /** Hit particles multiplied, block particles hidden. Implemented by the particle Mixins. */
    public static final String PARTICLES = "particles";

    /** Frames blended with the ones before. Implemented by the game renderer Mixin. */
    public static final String MOTION_BLUR = "motion-blur";

    /** Your username drawn as another name. Implemented by the font Mixins. */
    public static final String NICK_HIDER = "nick-hider";

    /** Titles hidden or resized. Implemented by the in-game HUD Mixins. */
    public static final String TITLES = "titles";

    /** The boss bar hidden. Implemented by the in-game HUD Mixin. */
    public static final String BOSS_BAR = "boss-bar";

    /** The tint on hurt entities. Implemented by the living entity renderer Mixin. */
    public static final String HIT_COLOUR = "hit-colour";

    /** The view flipped while a key is held. Implemented by the frame hook. */
    public static final String SNAPLOOK = "snaplook";

    /** Labels over players and named mobs, styled. Implemented by the label Mixins. */
    public static final String NAMETAGS = "nametags";

    /** Marked spots drawn in the world. Implemented by the world overlay and command Mixins. */
    public static final String WAYPOINTS = "waypoints";

    /** A stronger glint on potions. Implemented by the item renderer Mixin. */
    public static final String SHINY_POTS = "shiny-pots";

    /** Markers over teammates. Implemented by the world overlay Mixin. */
    public static final String TEAMVIEW = "teamview";

    /** Says gg when a game ends. Implemented by the chat reader. */
    public static final String AUTO_GG = "auto-gg";

    /** The camera turns without you. Implemented by the game renderer Mixin. */
    public static final String FREELOOK = "freelook";

    /** Timestamps, folded repeats, background and shadow. Implemented by the chat Mixin. */
    public static final String CHAT = "chat";

    /** Every behaviour the client implements. */
    public static final String[] ALL = {
        ZOOM, CROSSHAIR, MAIN_MENU, MOVEMENT_TOGGLE, PERFORMANCE_LAYER, SCOREBOARD, FULLBRIGHT, TIME_CHANGER, OLD_ANIMATIONS, HURT_CAMERA, CHAT,
        BLOCK_OUTLINE, HITBOXES, ITEM_PHYSICS, WEATHER_CHANGER, TNT_COUNTDOWN, PARTICLES, MOTION_BLUR,
        NICK_HIDER, TITLES, BOSS_BAR, HIT_COLOUR, SNAPLOOK,
        NAMETAGS, WAYPOINTS, SHINY_POTS, TEAMVIEW,
        AUTO_GG, FREELOOK
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
