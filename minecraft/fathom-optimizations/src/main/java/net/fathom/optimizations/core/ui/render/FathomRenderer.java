package net.fathom.optimizations.core.ui.render;

/**
 * Fathom's full 2D drawing API: {@link Renderer}, plus the primitives a
 * vector backend makes cheap.
 *
 * <h2>The layering</h2>
 *
 * <pre>
 *   Fathom UI  ->  layout / animation  ->  FathomRenderer  ->  NanoVG backend  ->  OpenGL
 * </pre>
 *
 * <p>Interface code draws through this interface and nothing else: it never
 * imports NanoVG, never touches GL, never loads a font. Frames are opened for it
 * by {@code fabric/gfx/Canvas}, which owns the GL state, the scale and failure
 * handling. That is what lets the backend change, or fail, without a component
 * noticing.
 *
 * <h2>Conventions</h2>
 *
 * <ul>
 *   <li>Rectangles are {@code left, top, right, bottom} in logical units, as in
 *       {@link Renderer}.
 *   <li>Colours are packed ARGB, straight alpha ({@code net.fathom.optimizations.core.ui.Colour}).
 *       Everything is multiplied by the {@code pushAlpha} stack.
 *   <li>Animated values are just arguments: sample a {@code Motion}, pass the
 *       float. Colours interpolate with {@code Colour.mix}.
 *   <li>Nothing here allocates per call in the NanoVG backend. Paints are
 *       caller-owned and reused; see {@link Paint}.
 * </ul>
 *
 * <h2>Shapes and the stencil</h2>
 *
 * <p>Minecraft 1.8.9 has no stencil buffer. Every shape method here is drawn as a
 * single convex path, which needs none. {@link #fillPath} is the exception: a
 * path built with {@link #moveTo}/{@link #lineTo} must be <b>one convex
 * outline</b>, or it will fill its bounding area. Strokes of any path are fine.
 */
public interface FathomRenderer extends FrameRenderer {

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    /** A rounded rectangle with each corner's radius given: top-left, top-right, bottom-right, bottom-left. */
    void rounded(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, int argb);

    /** A rounded rectangle filled with a paint. */
    void rounded(float left, float top, float right, float bottom, float radius, Paint paint);

    /** Per-corner radii, filled with a paint. */
    void rounded(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, Paint paint);

    /** An inside outline with per-corner radii. */
    void outline(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, float thickness, int argb);

    void circle(float centreX, float centreY, float radius, int argb);

    void circle(float centreX, float centreY, float radius, Paint paint);

    /** A circle's outline, centred on the radius. */
    void ring(float centreX, float centreY, float radius, float thickness, int argb);

    /** A straight line with round caps. */
    void line(float x1, float y1, float x2, float y2, float thickness, int argb);

    // -----------------------------------------------------------------------
    // Paths
    // -----------------------------------------------------------------------

    /** Start a new path, discarding any unfinished one. */
    void beginPath();

    void moveTo(float x, float y);

    void lineTo(float x, float y);

    void quadTo(float controlX, float controlY, float x, float y);

    void bezierTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

    /** An arc around a centre, angles in radians, clockwise on screen. */
    void arc(float centreX, float centreY, float radius, float fromAngle, float toAngle);

    void closePath();

    /** Fill the path. It must be one convex outline; see the class comment. */
    void fillPath(int argb);

    void fillPath(Paint paint);

    /** Stroke the path with round joins and caps. Any shape. */
    void strokePath(float thickness, int argb);

    // -----------------------------------------------------------------------
    // Gradients
    // -----------------------------------------------------------------------

    /** Left to right, with rounded corners. ({@link #gradient} is top to bottom.) */
    void horizontalGradient(float left, float top, float right, float bottom, float radius,
            int leftArgb, int rightArgb);

    // -----------------------------------------------------------------------
    // Shadows
    // -----------------------------------------------------------------------

    /** A shadow under a rounded rectangle. Draw it before the shape. */
    void shadow(float left, float top, float right, float bottom, float radius, Shadow shadow);

    /**
     * A soft coloured light around a rounded rectangle. This is one box-gradient
     * fill intended to be drawn immediately before the component surface; the
     * surface covers its centre. Low-opacity repeated use is therefore cheap,
     * while semantic restraint remains the caller's responsibility.
     */
    void glow(float left, float top, float right, float bottom, float radius, float size, int argb);

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /**
     * Draw a string anchored at (x, y) by {@link TextAlign} flags.
     *
     * @return its advance width
     */
    float text(String text, float x, float y, Type type, int argb, int align);

    /**
     * Draw a string no wider than {@code maxWidth}, ending in an ellipsis if it
     * has to be cut. Alignment applies to what is actually drawn.
     *
     * @return the width drawn
     */
    float textFit(String text, float x, float y, float maxWidth, Type type, int argb, int align);

    /**
     * The box a string would occupy if drawn at (x, y) with this alignment:
     * {@code out[0..3] = left, top, right, bottom}.
     *
     * @return its advance width
     */
    float textBounds(String text, float x, float y, Type type, int align, float[] out);

    /** False when no font could be loaded; text then draws nothing and measures approximately. */
    boolean fontsAvailable();

    // -----------------------------------------------------------------------
    // Clipping ({@link #clip} intersects; these add the other two cases)
    // -----------------------------------------------------------------------

    /** Clip to exactly this rectangle, ignoring the clip in force. Pop with {@link #unclip}. */
    void pushScissor(float left, float top, float right, float bottom);

    /** Draw unclipped until the matching {@link #unclip}. */
    void pushNoScissor();

    /** Drop every clip. For recovering from a broken frame, not for components. */
    void resetScissor();

    // -----------------------------------------------------------------------
    // Images
    // -----------------------------------------------------------------------

    /**
     * Draw an image stretched over a rounded rectangle, at an extra opacity.
     *
     * <p>Draws nothing until the image is {@link ImageRef.State#READY}; the first
     * call starts loading it. Pair with {@link #imageState} to draw a placeholder.
     */
    void image(ImageRef image, float left, float top, float right, float bottom, float radius, float alpha);

    /**
     * The same, cropped to cover the box without distortion (CSS
     * {@code object-fit: cover}), which is what artwork and avatars want.
     */
    void imageCover(ImageRef image, float left, float top, float right, float bottom, float radius, float alpha);

    ImageRef.State imageState(ImageRef image);

    /** Free the renderer's copy now. It is loaded again if drawn again. */
    void release(ImageRef image);
}
