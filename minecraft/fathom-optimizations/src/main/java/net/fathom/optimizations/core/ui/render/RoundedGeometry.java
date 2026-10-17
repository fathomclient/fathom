package net.fathom.optimizations.core.ui.render;

/**
 * Geometry rules shared by every renderer that draws Fathom's rounded UI.
 *
 * <p>A radius is always a logical-unit value. It is normalised before it
 * reaches a backend so NanoVG, the GL fallback, images, fills and outlines all
 * agree on the same physical curve. This class deliberately contains no theme
 * values: whether a shape is a control, card or panel is a component decision;
 * whether that requested radius can exist in the supplied rectangle is not.
 */
public final class RoundedGeometry {

    private RoundedGeometry() {}

    /** A finite, non-negative radius no larger than half the shorter side. */
    public static float radius(float requested, float width, float height) {
        if (!(requested > 0f) || !(width > 0f) || !(height > 0f)) {
            return 0f;
        }
        return Math.min(requested, Math.min(width, height) / 2f);
    }

    /** The radius of a capsule (or a circle when the box is square). */
    public static float pill(float width, float height) {
        if (!(width > 0f) || !(height > 0f)) {
            return 0f;
        }
        return Math.min(width, height) / 2f;
    }

    /**
     * The concentric radius of a shape inset equally on every side.
     *
     * <p>Subtracting the inset is what keeps the inner and outer arcs centred.
     * Reusing the outer radius makes an inner fill too round; substituting the
     * next-smallest token makes it too sharp whenever the token gap differs
     * from the actual inset.
     */
    public static float insetRadius(float outerRadius, float width, float height, float inset) {
        float amount = Math.max(0f, inset);
        float outer = radius(outerRadius, width, height);
        return radius(Math.max(0f, outer - amount), width - amount * 2f, height - amount * 2f);
    }

    /**
     * Put a logical coordinate on the nearest device-pixel boundary.
     *
     * <p>Use this for static edges and centre lines, not freely moving
     * animation: snapping animation would trade soft edges for visible jitter.
     */
    public static float snap(float logical, float deviceScale) {
        float scale = deviceScale > 0f && deviceScale == deviceScale ? deviceScale : 1f;
        return Math.round(logical * scale) / scale;
    }
}
