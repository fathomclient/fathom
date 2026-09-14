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
    // Frame
    // -----------------------------------------------------------------------

    /** The interface's width, in logical units. */
    float width();

    /** The interface's height, in logical units. */
    float height();

    /** Device pixels per logical unit, for anything that has to align an edge. */
    float scale();
}
