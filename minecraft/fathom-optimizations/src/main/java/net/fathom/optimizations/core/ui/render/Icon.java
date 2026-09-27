package net.fathom.optimizations.core.ui.render;

import net.fathom.optimizations.core.module.FathomModule;

/**
 * Fathom's icon family, as identities.
 *
 * <h2>Why an enum and not images</h2>
 *
 * <p>Each of these is a path on a 24-unit grid, described in code and rasterised
 * by the renderer at whatever size and resolution it is asked for. A sheet of
 * PNGs would be a fixed size magnified at every other one — the same mistake as
 * the bitmap font, in pictures.
 *
 * <p>The style is Lucide's, which is what the launcher uses: geometric, open,
 * even stroke weight, round caps, on a 24-unit grid. That is a style and this
 * draws in it. Nothing here is traced from anyone's artwork.
 *
 * <p>The identity lives in {@code core} so that layout code can name an icon
 * without depending on the renderer that draws it.
 */
public enum Icon {

    // -- chrome ---------------------------------------------------------------
    HOME,
    SEARCH,
    CLOSE,
    GRID,
    LAYERS,
    GAUGE,
    USER,
    EXTERNAL,
    STETHOSCOPE,
    SLIDERS,
    STAR,
    STAR_FILLED,
    CHEVRON_RIGHT,
    CHEVRON_DOWN,
    CHEVRON_LEFT,
    MOVE,
    RESET,
    CHECK,
    PIN,
    MINUS,
    PLUS,

    // -- status ---------------------------------------------------------------
    INFO,
    WARNING,
    SUCCESS,

    // -- features -------------------------------------------------------------
    KEYBOARD,
    MOUSE,
    COMPASS,
    CLOCK,
    SIGNAL,
    SHIELD,
    FLASK,
    PACKAGE,
    CROSSHAIR,
    MAGNIFIER,
    RUN,
    SPARK;

    /**
     * The icon a feature asked for.
     *
     * <p>A feature names its icon from a small enum of its own so that
     * {@code core/feature} does not depend on the interface. This is the one
     * place the two vocabularies meet.
     */
    public static Icon of(FathomModule.Icon icon) {
        if (icon == null) {
            return SLIDERS;
        }
        switch (icon) {
            case GAUGE:
                return GAUGE;
            case KEYBOARD:
                return KEYBOARD;
            case MOUSE:
                return MOUSE;
            case COMPASS:
                return COMPASS;
            case CLOCK:
                return CLOCK;
            case SIGNAL:
                return SIGNAL;
            case SHIELD:
                return SHIELD;
            case FLASK:
                return FLASK;
            case PACKAGE:
                return PACKAGE;
            case CROSSHAIR:
                return CROSSHAIR;
            case MAGNIFIER:
                return MAGNIFIER;
            case RUN:
                return RUN;
            case SPARK:
                return SPARK;
            case GRID:
                return GRID;
            case SLIDERS:
            default:
                return SLIDERS;
        }
    }
}
