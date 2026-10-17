package net.fathom.optimizations.core.opt.text;

/**
 * Where batched geometry goes.
 *
 * <p>The seam between {@link QuadBatcher} and OpenGL. In the game this wraps
 * {@code WorldRenderer}/{@code Tessellator}; in tests it is a recorder that counts what it
 * was given, which is what makes the batcher's central claim — same vertices, same order,
 * fewer draws — checkable without a GL context.
 */
public interface QuadSink {

    /**
     * Bind {@code texture} for subsequent vertices.
     *
     * <p>Called only when the texture actually changes. A batcher that re-binds the same
     * texture is doing the thing it exists to avoid.
     */
    void bindTexture(int texture);

    /**
     * Append one quad's four vertices.
     *
     * <p>Positions in screen space, texture coordinates normalised, colour packed ARGB.
     */
    void quad(float x, float y, float width, float height, float u, float v, float uWidth,
            float vHeight, int argb);

    /**
     * Issue a draw call for everything appended since the last one.
     *
     * <p>The number of times this is called is the metric the batching optimizations
     * exist to reduce, and the reason it is a distinct method rather than implied by the
     * next bind.
     */
    void draw();
}
