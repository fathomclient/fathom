package net.fathom.optimizations.core.ui.anim;

import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * The interface's durations: the launcher's motion tokens
 * ({@code system.motion} in the theme manifest, {@code --motion-*} in its
 * CSS), and nothing else.
 *
 * <p>Six durations, each named for what moves, all between 90 and 200ms:
 * fast, clean and understated, and the same in the game as in the launcher.
 * The names below are the interface's intents mapped onto them; a new control
 * picks the closest intent and never invents a seventh number. Values are
 * seconds of monotonic wall time -- never ticks or frames -- so a transition
 * takes as long at 30 fps as at 300.
 *
 * <p>Curves: {@link Ease.Curve#STANDARD} (the launcher's
 * {@code --ease-standard}, cubic-bezier(0.2, 0.8, 0.2, 1)) for anything
 * arriving or changing state, {@link Ease.Curve#EXIT} for anything leaving.
 * No springs that overshoot, no reversed entrances standing in for exits.
 */
public final class Timing {

    /** The pointer arriving at or leaving a control (--motion-hover). */
    public static final float HOVER_IN = Tokens.MOTION_HOVER;
    public static final float HOVER_OUT = Tokens.MOTION_HOVER;
    /** A button going down and coming back up (--motion-press). */
    public static final float PRESS = Tokens.MOTION_PRESS;
    /** Toggles, sliders and other binary state (--motion-state). */
    public static final float TOGGLE = Tokens.MOTION_STATE;
    /** Tabs, segmented markers, the current section's mark (--motion-state). */
    public static final float NAVIGATION = Tokens.MOTION_STATE;
    /** Moving between a list and one detail page (--motion-page). */
    public static final float DETAIL = Tokens.MOTION_PAGE;
    /** Ordinary component and status transitions (--motion-state). */
    public static final float STANDARD = Tokens.MOTION_STATE;
    /** Popovers, menus and panels appearing (--motion-surface). */
    public static final float PANEL = Tokens.MOTION_SURFACE;
    /** A screen arriving (--motion-page). */
    public static final float SCREEN_ENTER = Tokens.MOTION_PAGE;
    /** And leaving (--motion-exit). */
    public static final float SCREEN_EXIT = Tokens.MOTION_EXIT;
    public static final float HUB_ENTER = Tokens.MOTION_PAGE;
    public static final float HUB_EXIT = Tokens.MOTION_EXIT;
    /** A tooltip fading in once it is due (--motion-hover). */
    public static final float TOOLTIP = Tokens.MOTION_HOVER;
    /** Between successive items of one entrance (--motion-stagger). */
    public static final float STAGGER = Tokens.MOTION_STAGGER;
    /** A card of a grid arriving (--motion-surface). */
    public static final float CARD_ENTER = Tokens.MOTION_SURFACE;
    public static final float CARD_STAGGER = Tokens.MOTION_STAGGER;

    private Timing() {}
}
