package net.fathom.optimizations.core.ui.anim;

import net.fathom.optimizations.generated.FathomTheme;

/**
 * The small, named set of durations used by every Fathom-owned interface.
 *
 * <p>Names describe intent rather than a particular component. A new control
 * should choose the closest existing intent instead of adding another almost
 * identical duration. Values are seconds because the animation clock is
 * monotonic wall time, never Minecraft ticks or rendered frames.
 */
public final class Timing {

    /** Pointer entering a control: deliberately quicker than leaving it. */
    public static final float HOVER_IN = 0.090f;
    /** Pointer leaving a control: enough tail to avoid flicker, never floaty. */
    public static final float HOVER_OUT = 0.130f;
    /** The tactile down/up response of a button. */
    public static final float PRESS = FathomTheme.MOTION_PRESS;
    /** Toggle knobs, slider progress and other binary state changes. */
    public static final float TOGGLE = FathomTheme.MOTION_TOGGLE;
    /** Tabs, segmented markers and navigation selection. */
    public static final float NAVIGATION = 0.190f;
    /** Ordinary component and status transitions. */
    public static final float STANDARD = FathomTheme.MOTION_NORMAL;
    /** Drawers and modal panels. */
    public static final float PANEL = FathomTheme.MOTION_DRAWER;
    /** A full Fathom screen arriving. */
    public static final float SCREEN_ENTER = FathomTheme.MOTION_HUB;
    /** A full Fathom screen leaving; intentionally faster than entry. */
    public static final float SCREEN_EXIT = FathomTheme.MOTION_HUB_CLOSE;
    /** Tooltip opacity after its dwell delay has elapsed. */
    public static final float TOOLTIP = 0.120f;
    /** Delay between adjacent rows in a deliberately staggered entrance. */
    public static final float STAGGER = FathomTheme.MOTION_STAGGER;

    private Timing() {}
}
