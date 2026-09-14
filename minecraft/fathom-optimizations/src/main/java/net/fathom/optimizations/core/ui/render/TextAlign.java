package net.fathom.optimizations.core.ui.render;

/**
 * Where a string sits relative to the point it is drawn at.
 *
 * <p>One horizontal flag and one vertical flag, or'd together:
 * {@code TextAlign.CENTER | TextAlign.MIDDLE}. Plain ints rather than an enum
 * pair so an alignment costs nothing to pass every frame.
 *
 * <p>The values are NanoVG's own, which lets a NanoVG backend pass them straight
 * through. Nothing above the renderer should rely on that.
 */
public final class TextAlign {

    public static final int LEFT = 1;
    public static final int CENTER = 1 << 1;
    public static final int RIGHT = 1 << 2;

    /** The top of the tallest glyph in the font (its ascender). */
    public static final int TOP = 1 << 3;
    /** Halfway between ascender and descender: what centres text in a box. */
    public static final int MIDDLE = 1 << 4;
    /** The bottom of the deepest glyph (its descender). */
    public static final int BOTTOM = 1 << 5;
    /** The baseline: the default, and {@code Renderer.text}'s anchor. */
    public static final int BASELINE = 1 << 6;

    private static final int HORIZONTAL = LEFT | CENTER | RIGHT;
    private static final int VERTICAL = TOP | MIDDLE | BOTTOM | BASELINE;

    private TextAlign() {}

    /** An alignment with a missing half filled in: left, and baseline. */
    public static int normalise(int align) {
        int horizontal = align & HORIZONTAL;
        int vertical = align & VERTICAL;
        if (Integer.bitCount(horizontal) != 1) {
            horizontal = LEFT;
        }
        if (Integer.bitCount(vertical) != 1) {
            vertical = BASELINE;
        }
        return horizontal | vertical;
    }

    /** How far left of the anchor a string of this width starts. */
    public static float offsetX(int align, float width) {
        if ((align & CENTER) != 0) {
            return -width / 2f;
        }
        if ((align & RIGHT) != 0) {
            return -width;
        }
        return 0f;
    }
}
