package net.fathom.optimizations.core.opt.text;

/**
 * Accumulates quads and draws them a texture at a time instead of one at a time.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>1.8.9's {@code FontRenderer.renderChar} begins a tessellator, emits one glyph's four
 * vertices, and calls {@code Tessellator.draw()}. Per character. The HUD and GUI paths
 * behave the same way through {@code drawTexturedModalRect}. A frame carrying a
 * scoreboard, a tab list, a chat backlog and a screenful of nametags is several thousand
 * characters, and therefore several thousand draw calls, for text alone. Each one is a
 * driver round trip that draws two triangles.
 *
 * <p>The glyphs on a single line overwhelmingly share one texture — the ASCII page is one
 * atlas — so nearly all of those draws could have been one.
 *
 * <h2>What this changes</h2>
 *
 * <p>Nothing about the vertices. The same quads reach the sink in the same order with the
 * same coordinates and the same colours; only the {@code draw()} boundaries move. That is
 * why this is classified as producing identical output rather than as a visual trade-off,
 * and it is asserted directly in the tests: a recording sink is fed the same submissions
 * through both paths and the vertex streams are compared element by element.
 *
 * <h2>Where the boundaries have to stay</h2>
 *
 * <p>Immediate-mode 2D drawing is order-dependent — a later quad blends over an earlier
 * one — so a batcher may never reorder to group textures together. It may only <em>defer
 * </em> the draw until the texture is about to change. Sorting by texture would be a
 * larger win and would put chat behind the vignette.
 *
 * <p>For the same reason the caller must {@link #flush()} before any state change the
 * batcher cannot see: a blend-mode switch, a scissor, a matrix push, a depth-test toggle,
 * or handing control to code outside this mod. {@link #pendingQuads()} exists so a Mixin
 * can assert it flushed rather than hope.
 */
public final class QuadBatcher {

    /** No texture bound. Not a valid GL name, so it cannot collide with a real one. */
    public static final int NO_TEXTURE = -1;

    private final QuadSink sink;

    private int boundTexture = NO_TEXTURE;
    private int pending;

    private long quadsSubmitted;
    private long drawsIssued;
    private long textureBinds;

    public QuadBatcher(QuadSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink");
        }
        this.sink = sink;
    }

    /**
     * Submit one quad.
     *
     * <p>Flushes first if {@code texture} differs from what is bound, which is the entire
     * mechanism.
     */
    public void submit(int texture, float x, float y, float width, float height, float u, float v,
            float uWidth, float vHeight, int argb) {
        if (texture == NO_TEXTURE) {
            throw new IllegalArgumentException("a quad needs a texture");
        }
        if (texture != boundTexture) {
            flush();
            sink.bindTexture(texture);
            boundTexture = texture;
            textureBinds++;
        }
        sink.quad(x, y, width, height, u, v, uWidth, vHeight, argb);
        pending++;
        quadsSubmitted++;
    }

    /**
     * Draw everything accumulated so far.
     *
     * <p>Idempotent, and cheap when there is nothing pending — a caller that flushes
     * defensively before every state change pays nothing for the ones that were already
     * flushed. That is deliberate: making the safe call free is how it stays in the code.
     */
    public void flush() {
        if (pending == 0) {
            return;
        }
        sink.draw();
        drawsIssued++;
        pending = 0;
    }

    /**
     * Finish and forget the bound texture.
     *
     * <p>Called at the end of a frame or a screen. Clearing {@link #boundTexture} matters:
     * anything else may have bound a texture between frames, and assuming ours survived
     * would draw text with whatever the last caller left behind.
     */
    public void end() {
        flush();
        boundTexture = NO_TEXTURE;
    }

    /** Quads submitted but not yet drawn. Zero everywhere it matters. */
    public int pendingQuads() {
        return pending;
    }

    public long quadsSubmitted() {
        return quadsSubmitted;
    }

    /** Draw calls issued. Vanilla's equivalent is one per quad. */
    public long drawsIssued() {
        return drawsIssued;
    }

    public long textureBinds() {
        return textureBinds;
    }

    /**
     * Quads per draw call so far, or {@code NaN} if nothing has been drawn.
     *
     * <p>Vanilla is 1.0 by construction. This is the number the batching entries in the
     * registry are claiming to move, and the one a benchmark should report.
     */
    public double quadsPerDraw() {
        return drawsIssued == 0L ? Double.NaN : (double) quadsSubmitted / (double) drawsIssued;
    }

    public void resetCounters() {
        quadsSubmitted = 0L;
        drawsIssued = 0L;
        textureBinds = 0L;
    }
}
