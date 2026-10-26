package net.fathom.optimizations.core.hud;

/**
 * Where a widget's box goes, from its anchor, its text anchor, its measured
 * size and its offset, and the offset a box position stands for.
 *
 * <p>One place for both directions so that drawing, hit testing, dragging and
 * the inspector cannot disagree: all of them are given the same measured width
 * and go through here.
 *
 * <h2>The reference edge</h2>
 *
 * <p>The screen anchor picks a point on the screen (left edge, middle, right
 * edge) and the offset moves it inward. The text anchor picks the part of the
 * <em>box</em> that sits on that point: its left edge, its middle or its right
 * edge. When the two agree (every layout before text anchors existed) this is
 * exactly {@link Anchor#x}. When they differ, say a right-aligned label on the
 * left of the screen, the box hangs from the chosen edge, so that edge does not
 * move when the label grows or shrinks.
 */
public final class HudGeometry {

    private HudGeometry() {}

    /** The screen point an anchor and an inward offset name, before the box is hung on it. */
    static int reference(Anchor anchor, int screenWidth, int offset) {
        float h = anchor.horizontal();
        int base = Math.round(screenWidth * h);
        return h > 0.5f ? base - offset : base + offset;
    }

    /** The box's left edge, unclamped. */
    public static int left(Anchor anchor, TextAnchor text, int screenWidth, int width, int offset) {
        float fraction = text.fraction(anchor);
        if (Float.compare(fraction, anchor.horizontal()) == 0) {
            return anchor.x(screenWidth, width, offset);
        }
        return reference(anchor, screenWidth, offset) - Math.round(width * fraction);
    }

    /** The offset that puts a box of {@code width} with its left edge at {@code left}. The inverse of {@link #left}. */
    public static int offset(Anchor anchor, TextAnchor text, int screenWidth, int width, int left) {
        float fraction = text.fraction(anchor);
        float h = anchor.horizontal();
        if (Float.compare(fraction, h) == 0) {
            if (h > 0.5f) {
                return screenWidth - (left + width);
            }
            if (h < 0.5f) {
                return left;
            }
            return left - Math.round((screenWidth - width) * 0.5f);
        }
        int edge = left + Math.round(width * fraction);
        int base = Math.round(screenWidth * h);
        return h > 0.5f ? base - edge : edge - base;
    }

    /** Keep a box that fits on the screen on it; one wider than the screen is left where it is. */
    public static int clampLeft(int left, int width, int screenWidth) {
        return width <= screenWidth ? Math.max(0, Math.min(left, screenWidth - width)) : left;
    }

    /**
     * The x of a line of text of {@code textWidth} inside a box, for a text
     * alignment of 0 (left), 0.5 or 1 (right).
     */
    public static float lineX(float boxX, float padding, float room, float textWidth, float alignment) {
        return boxX + padding + Math.round((room - textWidth) * alignment);
    }
}
