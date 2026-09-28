package net.fathom.optimizations.fabric.gfx;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GLX;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.UiScale;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Fathom's 2D renderer, on the GL context the game already has.
 *
 * <p>The reasoning behind every choice here is in
 * {@code docs/ingame-ui-renderer-decision.md}. The short version:
 *
 * <ul>
 *   <li><b>Device pixels, not Minecraft's scaled space.</b> Everything is laid
 *       out in logical units and converted to real framebuffer pixels. The game's
 *       projection is left alone and undone with one multiply per vertex, so
 *       nothing is ever rasterised small and magnified.
 *   <li><b>One sheet, one batch.</b> Fills, corners, glyphs and icons come from
 *       texture atlases; quads accumulate into a {@code BufferBuilder} and are
 *       uploaded once per texture change. The whole hub is a few draw calls.
 *   <li><b>Anti-aliasing from coverage textures.</b> No shader is required, so
 *       there is no driver on which this degrades to something else.
 * </ul>
 *
 * <h2>The state rules</h2>
 *
 * <p>Every state change goes through {@link GlStateManager}, which caches what
 * it believes the driver's state to be — a raw {@code GL11.glDisable} desyncs
 * that cache and the symptom is translucent geometry silently vanishing from the
 * second frame onwards. The exception is the scissor box, which
 * {@code GlStateManager} does not model at all.
 *
 * <p>{@link #beginFrame} takes the state it needs and {@link #endFrame} puts it
 * back, so a screen that throws halfway through cannot leave the game with
 * blending off.
 */
public final class GlRenderer implements Renderer {

    private static final int MAX_DEPTH = 32;
    private static final int LOGGED_IMBALANCES = 5;

    private final MinecraftClient client;
    private final Batch batch = new Batch(this);
    /**
     * Every sheet flushes the batch before it writes to itself.
     *
     * <p>See {@link Atlas.BeforeUpload}: without it, the first frame that draws
     * a character or an icon nobody has drawn before would bind a texture
     * underneath geometry already queued against a different one.
     */
    private final Atlas.BeforeUpload flusher = new Atlas.BeforeUpload() {
        @Override
        public void flush() {
            batch.flush();
        }
    };

    private final Atlas sheet = new Atlas(Shapes.SHEET, flusher);
    private final Shapes shapes = new Shapes(sheet);
    private final FontBook fonts = new FontBook(flusher);
    private final Icons icons = new Icons(flusher);
    private final BrandArt brand = new BrandArt(flusher);

    private UiScale ui = new UiScale(854, 480, 1f);
    private boolean created;
    private boolean inFrame;
    private boolean highQuality = true;
    /** One render-thread snapshot, reused every frame to avoid hot-path garbage. */
    private final GlStateSnapshot frameState = new GlStateSnapshot();
    private GlStateSnapshot inheritedState;

    /** Device pixels to Minecraft's scaled units — the projection, undone. */
    private float vertexScaleX = 1f;
    private float vertexScaleY = 1f;

    /** Current transform, in device pixels. */
    private float offsetX;
    private float offsetY;
    private float zoom = 1f;
    private final float[] transformStack = new float[MAX_DEPTH * 3];
    private int transformDepth;
    private int transformOverflow;

    /** Current clip, in device pixels, or null-ish when nothing is clipped. */
    private final float[] clipStack = new float[MAX_DEPTH * 4];
    private int clipDepth;
    private int clipOverflow;

    private float alpha = 1f;
    private final float[] alphaStack = new float[MAX_DEPTH];
    private int alphaDepth;
    private int alphaOverflow;

    private final Stats stats = new Stats();
    private int imbalanceReports;

    /** What the last frame cost, for the diagnostics section and the spike. */
    public static final class Stats {
        /** This frame, so far. */
        public int quads;
        public int draws;
        /**
         * The last completed frame.
         *
         * <p>What anything on screen should report. A screen reading
         * {@code quads} is reading a frame it is in the middle of drawing, and
         * the draw-call count in particular would always be one short, because
         * the final flush happens after the interface that displays it.
         */
        public int lastQuads;
        public int lastDraws;
        public int glyphPages;
        public int iconEntries;
        public boolean fallbackFont;

        void reset() {
            lastQuads = quads;
            lastDraws = draws;
            quads = 0;
            draws = 0;
        }
    }

    public GlRenderer(MinecraftClient client) {
        this.client = client;
    }

    public Stats stats() {
        return stats;
    }

    /** Selects only the shape/icon quality path; layout and text stay native-resolution. */
    public void setHighQuality(boolean enabled) {
        highQuality = enabled;
    }

    public boolean highQuality() {
        return highQuality;
    }

    public UiScale scaleInfo() {
        return ui;
    }

    // -----------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------

    /**
     * Take the GL state, work out the scale, and start batching.
     *
     * @param preference the user's UI scale multiplier
     */
    public void beginFrame(float preference) {
        if (inFrame) {
            throw new IllegalStateException("a Fathom frame is already open");
        }
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.GL_FRAME_BEGIN);
        GlStateSnapshot snapshot = frameState.captureInto();
        try {
            if (!created) {
                sheet.create();
                shapes.build();
                fonts.create();
                icons.create();
                brand.create();
                created = true;
                stats.fallbackFont = fonts.usingFallback();
            }

            int framebufferWidth = Math.max(1, client.width);
            int framebufferHeight = Math.max(1, client.height);
            float nextScale = UiScale.scaleFor(framebufferHeight, preference);
            boolean dimensionsChanged = framebufferWidth != ui.framebufferWidth()
                    || framebufferHeight != ui.framebufferHeight();
            if (UiScale.differs(nextScale, ui.scale())) {
                // The rasters are only right for one scale. Rebuilding them is a
                // few milliseconds and happens when a window is resized, which is
                // already a frame nobody is measuring.
                fonts.reset();
                icons.reset();
                brand.reset();
            }
            if (dimensionsChanged || UiScale.differs(nextScale, ui.scale())) {
                ui = new UiScale(framebufferWidth, framebufferHeight, preference);
            }

            MinecraftGuiSize minecraftUi = MinecraftGuiSize.get(client);
            vertexScaleX = minecraftUi.width() / (float) framebufferWidth;
            vertexScaleY = minecraftUi.height() / (float) framebufferHeight;

            offsetX = 0f;
            offsetY = 0f;
            zoom = 1f;
            transformDepth = 0;
            transformOverflow = 0;
            clipDepth = 0;
            clipOverflow = 0;
            alpha = 1f;
            alphaDepth = 0;
            alphaOverflow = 0;
            stats.reset();
            stats.glyphPages = fonts.pageCount();
            stats.iconEntries = icons.count();

            GlStateManager.activeTexture(GLX.textureUnit);
            if (GLX.areShadersSupported()) {
                GLX.gl20UseProgram(0);
            }
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // Alpha testing would throw away exactly the partly-covered pixels that
            // anti-aliasing is made of. Every soft edge in the interface depends on
            // this line.
            GlStateManager.disableAlphaTest();
            GlStateManager.disableDepthTest();
            GlStateManager.depthMask(false);
            GlStateManager.disableCull();
            GlStateManager.enableTexture();
            GlStateManager.color(1f, 1f, 1f, 1f);
            inheritedState = snapshot;
            inFrame = true;
        } catch (RuntimeException failure) {
            snapshot.restore();
            throw failure;
        } catch (Error failure) {
            snapshot.restore();
            throw failure;
        }
    }

    /** Flush, and put the state back the way the game had it. */
    public void endFrame() {
        if (!inFrame) {
            return;
        }
        try {
            batch.flush();
            reportImbalance();
            clipDepth = 0;
            clipOverflow = 0;
            transformDepth = 0;
            transformOverflow = 0;
            alphaDepth = 0;
            alphaOverflow = 0;
        } finally {
            GlStateSnapshot snapshot = inheritedState;
            inheritedState = null;
            inFrame = false;
            if (snapshot != null) {
                snapshot.restore();
            }
        }
    }

    public boolean inFrame() {
        return inFrame;
    }

    private void reportImbalance() {
        int open = transformDepth + transformOverflow + clipDepth + clipOverflow
                + alphaDepth + alphaOverflow;
        if (open > 0 && imbalanceReports < LOGGED_IMBALANCES) {
            imbalanceReports++;
            System.err.println("[Fathom] GL frame ended with " + open
                    + " unmatched clip/alpha/transform push(es); a component is not popping what it pushed");
        }
    }

    /**
     * Submit whatever is queued, now.
     *
     * <p>Ordinarily the batch decides for itself when to flush — on a texture
     * change, and at the end of a frame — and callers never need this. The HUD
     * does: it draws Fathom text inside Minecraft's own HUD pass, where the
     * modelview matrix is changed between widgets and vanilla drawing shares the
     * one global Tessellator. Both of those mean "whatever is queued has to go
     * out before the next thing happens", and only the caller knows when that
     * is.
     */
    public void flush() {
        batch.flush();
    }

    /** Drop every texture. For a resource reload or a shutdown. */
    public void dispose() {
        if (inFrame) {
            endFrame();
        }
        batch.flush();
        fonts.delete();
        icons.delete();
        brand.delete();
        sheet.delete();
        created = false;
    }

    // -----------------------------------------------------------------------
    // Coordinates
    // -----------------------------------------------------------------------

    private float deviceX(float logical) {
        return offsetX + logical * ui.scale() * zoom;
    }

    private float deviceY(float logical) {
        return offsetY + logical * ui.scale() * zoom;
    }

    private float deviceSize(float logical) {
        return logical * ui.scale() * zoom;
    }

    /** A logical position from a device one — for the mouse. */
    public float toLogicalX(float device) {
        return device / ui.scale();
    }

    public float toLogicalY(float device) {
        return device / ui.scale();
    }

    @Override
    public float width() {
        return ui.width();
    }

    @Override
    public float height() {
        return ui.height();
    }

    @Override
    public float scale() {
        return ui.scale();
    }

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    @Override
    public void fill(float left, float top, float right, float bottom, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        solid(snap(deviceX(left)), snap(deviceY(top)), snap(deviceX(right)), snap(deviceY(bottom)), colour);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom, float radius, int argb) {
        gradient(left, top, right, bottom, radius, argb, argb);
    }

    @Override
    public void gradient(
            float left, float top, float right, float bottom, float radius, int topArgb, int bottomArgb) {
        int upper = tint(topArgb);
        int lower = tint(bottomArgb);
        if ((Colour.invisible(upper) && Colour.invisible(lower)) || right <= left || bottom <= top) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float rad = cornerRadius(radius, r - l, b - t);

        batch.bind(sheet.texture());
        if (rad < 0.6f) {
            quadShaded(l, t, r, b, whiteU(), whiteV(), whiteU(), whiteV(), upper, lower);
            return;
        }

        // Middle band, full width; then the two bands between the corners.
        quadShaded(l, t + rad, r, b - rad, whiteU(), whiteV(), whiteU(), whiteV(),
                mixAt(upper, lower, t, b, t + rad), mixAt(upper, lower, t, b, b - rad));
        quadShaded(l + rad, t, r - rad, t + rad, whiteU(), whiteV(), whiteU(), whiteV(),
                upper, mixAt(upper, lower, t, b, t + rad));
        quadShaded(l + rad, b - rad, r - rad, b, whiteU(), whiteV(), whiteU(), whiteV(),
                mixAt(upper, lower, t, b, b - rad), lower);

        corner(l, t, rad, true, true, upper);
        corner(r - rad, t, rad, false, true, upper);
        corner(l, b - rad, rad, true, false, lower);
        corner(r - rad, b - rad, rad, false, false, lower);
    }

    /**
     * A corner radius in whole device pixels.
     *
     * <p>Corners are stamped from a mask generated at {@code round(radius)} and
     * then drawn into a quad {@code radius} across. While those two disagreed --
     * a 14-unit radius at a 1.25 scale is 17.5 device pixels, stamped from an
     * 18-pixel mask -- every corner was a resampled copy of the shape it should
     * have been, which is what made an outline look thicker at the corners than
     * along the sides. Rounding here means the mask is drawn at exactly its own
     * size and the ring keeps one thickness the whole way round.
     */
    private float cornerRadius(float radius, float width, float height) {
        float limit = Math.min(width / 2f, height / 2f);
        float device = RoundedGeometry.radius(deviceSize(radius), width, height);
        if (device < 0.6f) {
            return device;
        }
        return Math.min(Math.max(1f, Math.round(device)), limit);
    }

    /** One corner of a rounded rectangle: the quarter disc, oriented. */
    private void corner(float x, float y, float size, boolean leftSide, boolean topSide, int argb) {
        Shapes.Mask mask = highQuality ? shapes.exactDisc(Math.max(1, Math.round(size))) : null;
        int maskX = mask == null ? Shapes.DISC_X : mask.x;
        int maskY = mask == null ? Shapes.DISC_Y : mask.y;
        int maskSize = mask == null ? Shapes.DISC_SIZE : mask.size;
        maskCorner(x, y, size, leftSide, topSide, argb, maskX, maskY, maskSize);
    }

    private void maskCorner(float x, float y, float size, boolean leftSide, boolean topSide,
            int argb, int maskX, int maskY, int maskSize) {
        // Texel centres. Sampling the mask's full extent instead was tried and
        // measured worse -- 159 against 170 down the middle of a corner arc --
        // because it lands every pixel on a texel boundary and blends the pair.
        float far = sheet.u(maskX + maskSize - 0.5f);
        float near = sheet.u(maskX + 0.5f);
        float farV = sheet.v(maskY + maskSize - 0.5f);
        float nearV = sheet.v(maskY + 0.5f);
        float u0 = leftSide ? far : near;
        float u1 = leftSide ? near : far;
        float v0 = topSide ? farV : nearV;
        float v1 = topSide ? nearV : farV;
        quad(x, y, x + size, y + size, u0, v0, u1, v1, argb);
    }

    @Override
    public void outline(
            float left, float top, float right, float bottom, float radius, float thickness, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float w = Math.max(1f, Math.round(deviceSize(thickness)));
        float rad = cornerRadius(radius, r - l, b - t);

        batch.bind(sheet.texture());
        // Border geometry is never a quality toggle. The old fast path drew
        // four square quads here, so a rounded control kept its curved fill but
        // acquired a blocky rectangular outline whenever NanoVG was unavailable
        // or the saved UI-quality preference was Low. Exact ring masks are
        // cached by device radius and width; after the first occurrence this is
        // the same four corner quads as the square path, with no per-frame
        // rasterisation or allocation.
        // The four straight sides, drawn inside the rectangle so a bordered box
        // is the size it says it is.
        solid(l + rad, t, r - rad, t + w, colour);
        solid(l + rad, b - w, r - rad, b, colour);
        solid(l, t + rad, l + w, b - rad, colour);
        solid(r - w, t + rad, r, b - rad, colour);

        if (rad >= 0.6f) {
            Shapes.Mask ring = shapes.exactRing(
                    Math.max(1, Math.round(rad)), Math.max(1, Math.round(w)));
            if (ring != null) {
                maskCorner(l, t, rad, true, true, colour, ring.x, ring.y, ring.size);
                maskCorner(r - rad, t, rad, false, true, colour, ring.x, ring.y, ring.size);
                maskCorner(l, b - rad, rad, true, false, colour, ring.x, ring.y, ring.size);
                maskCorner(r - rad, b - rad, rad, false, false, colour, ring.x, ring.y, ring.size);
            } else {
                arc(l + rad, t + rad, rad - w / 2f, 180f, 270f, w, colour);
                arc(r - rad, t + rad, rad - w / 2f, 270f, 360f, w, colour);
                arc(r - rad, b - rad, rad - w / 2f, 0f, 90f, w, colour);
                arc(l + rad, b - rad, rad - w / 2f, 90f, 180f, w, colour);
            }
        }
    }

    /**
     * A picture, cover-cropped into a rounded rectangle.
     *
     * <p>For the scene thumbnails, and anything else that has to show a real
     * image inside this interface. The crop is CSS {@code object-fit: cover}:
     * the image fills the box on its tighter axis and loses the overflow on the
     * other, so a 16:9 still in a 16:9 tile loses nothing and in a squarer tile
     * loses its sides rather than stretching.
     *
     * <p>The corners are painted out afterwards in {@code backdrop} rather than
     * masked in. One quad samples one texture, and the picture and the corner
     * mask live in different ones; painting the leftover square in the colour
     * behind the tile reaches the same place from the other side.
     *
     * @param sourceAspect width over height of the image itself
     * @param backdrop     what sits behind this tile, for the corners
     */
    public void image(int textureId, float left, float top, float right, float bottom,
            float radius, float sourceAspect, int backdrop) {
        if (textureId <= 0 || right <= left || bottom <= top) {
            return;
        }
        int colour = tint(0xFFFFFFFF);
        if (Colour.invisible(colour)) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float rad = cornerRadius(radius, r - l, b - t);

        float target = (r - l) / (b - t);
        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;
        if (sourceAspect > target) {
            float keep = target / sourceAspect;
            u0 = (1f - keep) / 2f;
            u1 = 1f - u0;
        } else if (sourceAspect > 0f) {
            float keep = sourceAspect / target;
            v0 = (1f - keep) / 2f;
            v1 = 1f - v0;
        }

        batch.bind(textureId);
        quad(l, t, r, b, u0, v0, u1, v1, colour);

        if (rad < 0.6f) {
            return;
        }
        int cover = tint(backdrop);
        if (Colour.invisible(cover)) {
            return;
        }
        Shapes.Mask notch = shapes.exactNotch(Math.max(1, Math.round(rad)));
        if (notch == null) {
            return;
        }
        batch.bind(sheet.texture());
        maskCorner(l, t, rad, true, true, cover, notch.x, notch.y, notch.size);
        maskCorner(r - rad, t, rad, false, true, cover, notch.x, notch.y, notch.size);
        maskCorner(l, b - rad, rad, true, false, cover, notch.x, notch.y, notch.size);
        maskCorner(r - rad, b - rad, rad, false, false, cover, notch.x, notch.y, notch.size);
    }

    /**
     * A stroked arc, stamped as overlapping discs.
     *
     * <p>The cheapest anti-aliased curve available without a shader: a round nib
     * dragged along the path. Two stamps per pixel of arc length, so the result
     * is a continuous stroke with round ends rather than a dotted line.
     */
    private void arc(float cx, float cy, float radius, float fromDegrees, float toDegrees, float width, int argb) {
        if (radius <= 0.2f) {
            return;
        }
        float sweep = (float) Math.toRadians(toDegrees - fromDegrees);
        float length = Math.abs(sweep) * radius;
        int steps = Math.max(3, Math.round(length * 2f));
        float u0 = sheet.u(Shapes.DOT_X + 0.5f);
        float v0 = sheet.v(Shapes.DOT_Y + 0.5f);
        float u1 = sheet.u(Shapes.DOT_X + Shapes.DOT_SIZE - 0.5f);
        float v1 = sheet.v(Shapes.DOT_Y + Shapes.DOT_SIZE - 0.5f);
        float half = width / 2f;
        for (int step = 0; step <= steps; step++) {
            double angle = Math.toRadians(fromDegrees) + sweep * step / steps;
            float x = cx + (float) Math.cos(angle) * radius;
            float y = cy + (float) Math.sin(angle) * radius;
            quad(x - half, y - half, x + half, y + half, u0, v0, u1, v1, argb);
        }
    }

    @Override
    public void shadow(float left, float top, float right, float bottom, float radius, float spread, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour)) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float s = deviceSize(spread);
        if (s < 1f) {
            return;
        }
        float rad = Math.min(deviceSize(radius), Math.min((r - l) / 2f, (b - t) / 2f));
        float inset = Math.max(0f, rad * 0.5f);

        batch.bind(sheet.texture());
        float rampNear = sheet.u(Shapes.RAMP_X + 0.5f);
        float rampFar = sheet.u(Shapes.RAMP_X + Shapes.RAMP_SIZE - 0.5f);
        float rampV = sheet.v(Shapes.RAMP_Y + 0.5f);

        // Sides: opaque against the panel, transparent at the far edge. The
        // ramp is one row of the sheet, so the fade runs along u and v is fixed.
        vertical(l + inset, t - s, r - inset, t, rampFar, rampNear, rampV, colour);
        vertical(l + inset, b, r - inset, b + s, rampNear, rampFar, rampV, colour);
        horizontal(l - s, t + inset, l, b - inset, rampFar, rampNear, rampV, colour);
        horizontal(r, t + inset, r + s, b - inset, rampNear, rampFar, rampV, colour);

        // Corners: the soft quarter disc, one per direction.
        float gNear = sheet.u(Shapes.GLOW_X + 0.5f);
        float gFar = sheet.u(Shapes.GLOW_X + Shapes.GLOW_SIZE - 0.5f);
        float gNearV = sheet.v(Shapes.GLOW_Y + 0.5f);
        float gFarV = sheet.v(Shapes.GLOW_Y + Shapes.GLOW_SIZE - 0.5f);
        quad(l - s, t - s, l + inset, t + inset, gFar, gFarV, gNear, gNearV, colour);
        quad(r - inset, t - s, r + s, t + inset, gNear, gFarV, gFar, gNearV, colour);
        quad(l - s, b - inset, l + inset, b + s, gFar, gNearV, gNear, gFarV, colour);
        quad(r - inset, b - inset, r + s, b + s, gNear, gNearV, gFar, gFarV, colour);
    }

    /** A strip whose ramp runs top to bottom. */
    private void vertical(float x0, float y0, float x1, float y1, float uTop, float uBottom, float v, int argb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        batch.quad(
                x0 * vertexScaleX, y0 * vertexScaleY, x1 * vertexScaleX, y1 * vertexScaleY,
                uTop, v, uBottom, v, uBottom, v, uTop, v,
                argb, argb, argb, argb);
    }

    /** A strip whose ramp runs left to right. */
    private void horizontal(float x0, float y0, float x1, float y1, float uLeft, float uRight, float v, int argb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        batch.quad(
                x0 * vertexScaleX, y0 * vertexScaleY, x1 * vertexScaleX, y1 * vertexScaleY,
                uLeft, v, uLeft, v, uRight, v, uRight, v,
                argb, argb, argb, argb);
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    @Override
    public float text(String string, float x, float baselineY, Type type, int argb) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        int colour = tint(argb);
        GlyphPage page = fonts.page(type, ui.scale());
        float advance = page.width(string);
        if (Colour.invisible(colour)) {
            return advance / ui.scale();
        }

        batch.bind(fonts.atlas().texture());
        // The pen walks in fractional device pixels; each glyph's quad is
        // snapped to whole ones. Fractional accumulation keeps the spacing
        // right; snapped quads keep the rasters crisp.
        float pen = deviceX(x);
        float baseline = Math.round(deviceY(baselineY));
        for (int index = 0; index < string.length(); index++) {
            GlyphPage.Glyph glyph = page.glyph(string.charAt(index));
            if (!glyph.blank) {
                float gx = Math.round(pen + glyph.offsetX);
                float gy = baseline + glyph.offsetY;
                quad(gx, gy, gx + glyph.width, gy + glyph.height,
                        glyph.u0, glyph.v0, glyph.u1, glyph.v1, colour);
            }
            pen += glyph.advance;
        }
        return advance / ui.scale();
    }

    @Override
    public float textCentred(String string, float centreX, float baselineY, Type type, int argb) {
        float width = measure(string, type);
        return text(string, centreX - width / 2f, baselineY, type, argb);
    }

    /** Draw centred display lettering with explicit tracking in logical units. */
    public float textTrackedCentred(
            String string, float centreX, float baselineY, Type type, float tracking, int argb) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        float gap = Math.max(0f, tracking);
        float width = gap * Math.max(0, string.length() - 1);
        for (int index = 0; index < string.length(); index++) {
            width += measure(String.valueOf(string.charAt(index)), type);
        }
        float pen = centreX - width / 2f;
        for (int index = 0; index < string.length(); index++) {
            String glyph = String.valueOf(string.charAt(index));
            pen += text(glyph, pen, baselineY, type, argb) + gap;
        }
        return width;
    }

    @Override
    public float textRight(String string, float rightX, float baselineY, Type type, int argb) {
        float width = measure(string, type);
        return text(string, rightX - width, baselineY, type, argb);
    }

    @Override
    public float measure(String string, Type type) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        return fonts.page(type, ui.scale()).width(string) / ui.scale();
    }

    @Override
    public float lineHeight(Type type) {
        return fonts.page(type, ui.scale()).lineHeight() / ui.scale();
    }

    @Override
    public float ascent(Type type) {
        return fonts.page(type, ui.scale()).ascent() / ui.scale();
    }

    @Override
    public float descent(Type type) {
        return fonts.page(type, ui.scale()).descent() / ui.scale();
    }

    // -----------------------------------------------------------------------
    // Icons
    // -----------------------------------------------------------------------

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || icon == null) {
            return;
        }
        if (net.fathom.optimizations.core.ui.render.GeometryDebug.ENABLED) {
            net.fathom.optimizations.core.ui.render.GeometryDebug.icon(this, x, y, size);
        }
        int pixels = Math.max(6, Math.round(deviceSize(size)));
        Icons.Entry entry = icons.entry(icon, pixels, deviceSize(Icons.strokeUnits(size)));
        batch.bind(icons.atlas().texture());
        float left = Math.round(deviceX(x));
        float top = Math.round(deviceY(y));
        quad(left, top, left + entry.pixels, top + entry.pixels,
                entry.u0, entry.v0, entry.u1, entry.v1, colour);
    }

    // -----------------------------------------------------------------------
    // The brand
    // -----------------------------------------------------------------------

    /*
     * Rasterised at the UI scale alone, not the transform's zoom: a screen that
     * scales 0.992 -> 1 as it arrives would otherwise ask for a new raster on
     * every frame of its entrance. During that entrance the quad is scaled
     * instead, which nobody can see; at rest it is pixel for pixel.
     */

    /** The wordmark's ink width in logical units at an ascender height in logical units. */
    public float wordmarkWidth(float capHeight) {
        int cap = Math.round(capHeight * ui.scale());
        return BrandArt.wordmarkWidth(cap) / ui.scale();
    }

    /**
     * The wordmark, "fathom", with its ascender's top-left ink at {@code x, y},
     * in one ink. The ascender (Geist's cap height) is held at the wordmark's
     * minimum in device pixels or more.
     *
     * @return the ink width drawn, in logical units
     */
    public float wordmark(float x, float y, float capHeight, int argb) {
        int cap = Math.round(capHeight * ui.scale());
        BrandArt.Entry entry = brand.wordmark(cap);
        int colour = tint(argb);
        if (entry.width > 0 && !Colour.invisible(colour)) {
            batch.bind(brand.atlas().texture());
            float pad = entry.pad * zoom;
            float left = Math.round(deviceX(x)) - pad;
            float top = Math.round(deviceY(y)) - pad;
            quad(left, top, left + entry.width * zoom, top + entry.height * zoom,
                    entry.u0, entry.v0, entry.u1, entry.v1, colour);
        }
        return BrandArt.wordmarkWidth(cap) / ui.scale();
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    @Override
    public void clip(float left, float top, float right, float bottom) {
        if (clipDepth >= MAX_DEPTH || clipOverflow > 0) {
            clipOverflow++;
            return;
        }
        float l = deviceX(left);
        float t = deviceY(top);
        float r = deviceX(right);
        float b = deviceY(bottom);
        if (clipDepth > 0) {
            int base = (clipDepth - 1) * 4;
            l = Math.max(l, clipStack[base]);
            t = Math.max(t, clipStack[base + 1]);
            r = Math.min(r, clipStack[base + 2]);
            b = Math.min(b, clipStack[base + 3]);
        }
        int base = clipDepth * 4;
        clipStack[base] = l;
        clipStack[base + 1] = t;
        clipStack[base + 2] = r;
        clipStack[base + 3] = b;
        clipDepth++;
        applyClip();
    }

    @Override
    public void unclip() {
        if (clipOverflow > 0) {
            clipOverflow--;
            return;
        }
        if (clipDepth == 0) {
            return;
        }
        clipDepth--;
        applyClip();
    }

    private void applyClip() {
        batch.flush();
        if (clipDepth == 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        int base = (clipDepth - 1) * 4;
        int left = Math.round(clipStack[base]);
        int top = Math.round(clipStack[base + 1]);
        int right = Math.round(clipStack[base + 2]);
        int bottom = Math.round(clipStack[base + 3]);
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        // The scissor box is in window pixels with the origin at the bottom
        // left, which is the one place in this class where the y axis flips.
        GL11.glScissor(left, client.height - top - height, width, height);
    }

    @Override
    public void pushAlpha(float value) {
        if (alphaDepth >= MAX_DEPTH) {
            alphaOverflow++;
            return;
        }
        alphaStack[alphaDepth++] = alpha;
        alpha *= value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    @Override
    public void popAlpha() {
        if (alphaOverflow > 0) {
            alphaOverflow--;
            return;
        }
        if (alphaDepth == 0) {
            alpha = 1f;
            return;
        }
        alpha = alphaStack[--alphaDepth];
    }

    @Override
    public void pushTransform(float translateX, float translateY) {
        pushTransform(translateX, translateY, 1f, 0f, 0f);
    }

    @Override
    public void pushTransform(float translateX, float translateY, float scale, float originX, float originY) {
        if (transformDepth >= MAX_DEPTH) {
            transformOverflow++;
            return;
        }
        int base = transformDepth * 3;
        transformStack[base] = offsetX;
        transformStack[base + 1] = offsetY;
        transformStack[base + 2] = zoom;
        transformDepth++;

        // Scale about the origin, then translate, all in device pixels.
        float deviceOriginX = deviceX(originX);
        float deviceOriginY = deviceY(originY);
        offsetX = deviceOriginX - (deviceOriginX - offsetX) * scale + deviceSize(translateX);
        offsetY = deviceOriginY - (deviceOriginY - offsetY) * scale + deviceSize(translateY);
        zoom *= scale;
    }

    @Override
    public float localX(float x) {
        float s = ui.scale();
        return (x * s - offsetX) / (s * zoom);
    }

    @Override
    public float localY(float y) {
        float s = ui.scale();
        return (y * s - offsetY) / (s * zoom);
    }

    @Override
    public boolean clipContains(float x, float y) {
        if (clipDepth == 0) {
            return true;
        }
        int base = (clipDepth - 1) * 4;
        float dx = x * ui.scale();
        float dy = y * ui.scale();
        return dx >= clipStack[base] && dx < clipStack[base + 2]
                && dy >= clipStack[base + 1] && dy < clipStack[base + 3];
    }

    @Override
    public void popTransform() {
        if (transformOverflow > 0) {
            transformOverflow--;
            return;
        }
        if (transformDepth == 0) {
            return;
        }
        transformDepth--;
        int base = transformDepth * 3;
        offsetX = transformStack[base];
        offsetY = transformStack[base + 1];
        zoom = transformStack[base + 2];
    }

    // -----------------------------------------------------------------------
    // Emitting
    // -----------------------------------------------------------------------

    private int tint(int argb) {
        return alpha >= 0.999f ? argb : Colour.scaleAlpha(argb, alpha);
    }

    private static float snap(float device) {
        return Math.round(device);
    }

    private float whiteU() {
        return sheet.u(Shapes.WHITE_X + Shapes.WHITE_SIZE / 2f);
    }

    private float whiteV() {
        return sheet.v(Shapes.WHITE_Y + Shapes.WHITE_SIZE / 2f);
    }

    private void solid(float left, float top, float right, float bottom, int argb) {
        if (right <= left || bottom <= top) {
            return;
        }
        batch.bind(sheet.texture());
        quad(left, top, right, bottom, whiteU(), whiteV(), whiteU(), whiteV(), argb);
    }

    private static int mixAt(int top, int bottom, float t, float b, float y) {
        if (top == bottom || b <= t) {
            return top;
        }
        return Colour.mix(top, bottom, (y - t) / (b - t));
    }

    private void quad(
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1, int argb) {
        quadShaded(x0, y0, x1, y1, u0, v0, u1, v1, argb, argb);
    }

    private void quadShaded(
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1, int topArgb, int bottomArgb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        batch.quad(
                x0 * vertexScaleX, y0 * vertexScaleY, x1 * vertexScaleX, y1 * vertexScaleY,
                u0, v0, u0, v1, u1, v1, u1, v0,
                topArgb, bottomArgb, bottomArgb, topArgb);
    }

    // -----------------------------------------------------------------------
    // The batch
    // -----------------------------------------------------------------------

    /**
     * Quads waiting to be uploaded.
     *
     * <p>A draw call ends when the texture changes or something needs the
     * geometry on screen — a scissor change, or the end of the frame. Everything
     * else accumulates, so the interface is a handful of calls rather than one
     * per rectangle.
     */
    private static final class Batch {
        private final GlRenderer owner;
        private int texture = -1;
        private boolean building;

        Batch(GlRenderer owner) {
            this.owner = owner;
        }

        void bind(int wanted) {
            if (wanted != texture) {
                flush();
                texture = wanted;
            }
        }

        void quad(
                float x0, float y0, float x1, float y1,
                float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                int argb0, int argb1, int argb2, int argb3) {
            start();
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            vertex(buffer, x0, y0, u0, v0, argb0);
            vertex(buffer, x0, y1, u1, v1, argb1);
            vertex(buffer, x1, y1, u2, v2, argb2);
            vertex(buffer, x1, y0, u3, v3, argb3);
            owner.stats.quads++;
        }

        private static void vertex(BufferBuilder buffer, float x, float y, float u, float v, int argb) {
            buffer.vertex(x, y, 0d)
                    .texture(u, v)
                    .color(
                            (argb >> 16) & 0xFF,
                            (argb >> 8) & 0xFF,
                            argb & 0xFF,
                            (argb >>> 24) & 0xFF)
                    .next();
        }

        private void start() {
            if (building) {
                return;
            }
            if (texture >= 0) {
                GlStateManager.bindTexture(texture);
            }
            Tessellator.getInstance().getBuffer().begin(GL11.GL_QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
            building = true;
        }

        void flush() {
            if (!building) {
                return;
            }
            Tessellator.getInstance().draw();
            building = false;
            owner.stats.draws++;
        }
    }
}
