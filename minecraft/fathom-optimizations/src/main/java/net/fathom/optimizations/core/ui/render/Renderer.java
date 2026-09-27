package net.fathom.optimizations.core.ui.render;

/**
 * Everything Fathom's interface can draw.
 *
 * <h2>Why this is an interface, in core, with no Minecraft in it</h2>
 *
 * <p>Two reasons, and the second is the one that matters.
 *
 * <ol>
 *   <li>The renderer chosen for 1.8.9 is not the renderer a version shipping
 *       LWJGL 3 would choose. {@code docs/ingame-ui-renderer-decision.md} says
 *       why, and the whole force of that decision is that it can be revisited
 *       without touching a single line of interface code. This interface is
 *       the seam.
 *   <li>Layout is arithmetic and arithmetic can be tested. Everything above
 *       this line runs against a recording implementation with no window, no GL
 *       context and no game.
 * </ol>
 *
 * <h2>Units</h2>
 *
 * <p>Every coordinate here is a <b>logical unit</b>, fractional, with the origin
 * at the top left of the screen. The implementation multiplies by the scale from
 * {@code UiScale} and rasterises at device resolution. Callers never see a
 * device pixel, which is the point: an interface that knows about device pixels
 * is an interface that will be wrong on somebody's display.
 *
 * <h2>State</h2>
 *
 * <p>Clip, opacity and translation are stacks, and every {@code push} has to be
 * matched. A frame that leaves the stacks unbalanced is a bug the implementation
 * is expected to shout about rather than repair.
 */
public interface Renderer {

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    /** A filled rectangle. */
    void fill(float left, float top, float right, float bottom, int argb);

    /**
     * A filled rectangle with rounded corners.
     *
     * <p>Anti-aliased at any fractional radius. Implementations normalise the
     * radius to at most half the shorter side; callers should use
     * {@link RoundedGeometry#pill} when the semantic shape is a pill.
     */
    void rounded(float left, float top, float right, float bottom, float radius, int argb);

    /** A vertical gradient, with the same corners. */
    void gradient(float left, float top, float right, float bottom, float radius, int topArgb, int bottomArgb);

    /** An outline drawn inside the rectangle, so a bordered box is its stated size. */
    void outline(float left, float top, float right, float bottom, float radius, float thickness, int argb);

    /**
     * One device pixel, in logical units, under the current transform.
     *
     * <p>What a hairline is. A thickness written in logical units scales with
     * the interface: "1" is 1.5 device pixels at 1080p and 3 at 4K, which one
     * backend rounds up to 2 and another smears across three rows -- so the
     * same nominal rim looked heavier on some controls than others. Anything
     * meant to be one physical pixel is a multiple of this instead.
     */
    default float pixel() {
        float scale = scale();
        return scale > 0f ? 1f / scale : 1f;
    }

    /**
     * THE rim: one device pixel, inside the shape's edge, on the pixel grid.
     *
     * <p>Every bordered control, panel, row and swatch draws its border
     * through this, at every state. Selection and hover change the colour,
     * never the width -- there is deliberately no thickness parameter.
     */
    default void rim(float left, float top, float right, float bottom, float radius, int argb) {
        outline(left, top, right, bottom, radius, pixel(), argb);
        if (GeometryDebug.ENABLED) {
            GeometryDebug.rim(this, left, top, right, bottom, radius, pixel());
        }
    }

    /**
     * The keyboard focus ring: two device pixels, {@code gap} logical units
     * outside the shape, following its corner. The launcher's
     * {@code --metric-focus}.
     */
    default void focusRing(float left, float top, float right, float bottom, float radius, float gap, int argb) {
        outline(left - gap, top - gap, right + gap, bottom + gap, radius + gap, 2f * pixel(), argb);
    }

    /**
     * A soft shadow under a panel.
     *
     * <p>Not a blur of what is behind it — nothing here reads the framebuffer.
     * A falloff drawn outwards from the shape, which is what separates a panel
     * from the game behind it.
     */
    void shadow(float left, float top, float right, float bottom, float radius, float spread, int argb);

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /**
     * Draw a string with its left edge at {@code x} and its <b>baseline</b> at
     * {@code y}, and return the advance.
     *
     * <p>Baseline rather than top: it is the only anchor that keeps two
     * different sizes on the same line looking like one line.
     */
    float text(String text, float x, float baselineY, Type type, int argb);

    /** The same, centred horizontally on {@code centreX}. */
    float textCentred(String text, float centreX, float baselineY, Type type, int argb);

    /** The same, with its right edge at {@code rightX}. */
    float textRight(String text, float rightX, float baselineY, Type type, int argb);

    /** How wide that string would be. The measurement the drawing actually uses. */
    float measure(String text, Type type);

    /** Distance from a baseline to the one below it. */
    float lineHeight(Type type);

    /** How far the tallest glyph rises above the baseline. */
    float ascent(Type type);

    /** How far the deepest glyph falls below it. */
    float descent(Type type);

    // -----------------------------------------------------------------------
    // Icons
    // -----------------------------------------------------------------------

    /** An icon, in a square box of {@code size} logical units. */
    void icon(Icon icon, float x, float y, float size, int argb);

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /**
     * Restrict drawing to a rectangle, intersected with whatever is already
     * clipped. Every call must be matched by {@link #unclip}.
     */
    void clip(float left, float top, float right, float bottom);

    void unclip();

    /**
     * Multiply everything drawn until {@link #popAlpha} by this opacity.
     *
     * <p>How a whole panel fades in without every colour inside it having to
     * know that it is fading.
     */
    void pushAlpha(float alpha);

    void popAlpha();

    /** Shift everything drawn until {@link #popTransform}. */
    void pushTransform(float translateX, float translateY);

    /** Shift and scale, about the given origin. For a panel that grows as it opens. */
    void pushTransform(float translateX, float translateY, float scale, float originX, float originY);

    void popTransform();

    // -----------------------------------------------------------------------
    // Hit testing
    // -----------------------------------------------------------------------

    /*
     * A control's hitbox is the geometry it was drawn with. These map an
     * untransformed point -- the pointer, in logical units -- into the space
     * the current transform draws in, and say whether the current clip shows
     * it, so a control tested while a page slides or a panel settles is hit
     * exactly where it is seen, and a row scrolled under a list's edge cannot
     * be clicked through the edge. The defaults are for a renderer without a
     * transform stack.
     */

    /** An untransformed x, in the space the current transform draws in. */
    default float localX(float x) {
        return x;
    }

    /** An untransformed y, in the space the current transform draws in. */
    default float localY(float y) {
        return y;
    }

    /** Whether an untransformed point is inside the current clip. */
    default boolean clipContains(float x, float y) {
        return true;
    }

    // -----------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------

    /** The interface's width, in logical units. */
    float width();

    /** The interface's height, in logical units. */
    float height();

    /** Device pixels per logical unit, for anything that has to align an edge. */
    float scale();
}
