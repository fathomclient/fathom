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
import net.fathom.optimizations.generated.FathomTheme;
import net.fathom.optimizations.generated.Wordmark;

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

    /**
     * High quality: exact corner masks and quarter-pixel text positioning.
     * Off ("performance mode"): stretched corner masks and whole-pixel text.
     * Layout, sizes and measurement are identical either way.
     */
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
            // Minecraft's GUI projection spans the window divided by the scale
            // factor *exactly* -- 1280 / 3 = 426.67 units -- so one framebuffer
            // pixel is 1 / factor units. This was the whole-number GUI width
            // over the window width instead (426 / 1280), which put every
            // vertex a little left of its pixel, more the further right it was:
            // two thirds of a pixel at the middle of a 1280-wide window. Glyph
            // rasters were resampled between texels, uneven across a line, and
            // each right-hand corner mask read its atlas neighbour, a dark
            // one-pixel seam down every button. Only widths that the factor
            // does not divide were affected, which is why the seams were all
            // vertical.
            vertexScaleX = 1f / minecraftUi.scaleFactor();
            vertexScaleY = 1f / minecraftUi.scaleFactor();

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
        // Whole pixels even when the shape is a pill with an odd side: half of
        // 17 is 8.5, and an 8.5-pixel corner quad starts between two pixels, so
        // every one of its pixels is a blend of two texels and the curve goes
        // soft and square. Eight pixels and a one-pixel straight between the
        // two ends is indistinguishable from a circle; the blend was not.
        float whole = (float) Math.floor(limit);
        if (whole < 1f) {
            return limit;
        }
        return Math.min(Math.max(1f, Math.round(device)), whole);
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
        // The mask's full extent, edge to edge. The corner quad is whole pixels
        // wide and starts on a whole pixel (`cornerRadius`), so each pixel's
        // centre lands exactly on the centre of the texel rasterised for it and
        // the circle reaches the screen as it was computed.
        //
        // This used to map the quad onto the texel *centres* instead
        // (`+0.5 .. size-0.5`). That squeezes the mask by (size-1)/size, so the
        // outermost pixel read coverage from half a pixel inside the curve: an
        // 8-pixel corner came out a solid squircle rather than round, and every
        // radius in the interface looked smaller and squarer than its number.
        float far = sheet.u(maskX + maskSize);
        float near = sheet.u(maskX);
        float farV = sheet.v(maskY + maskSize);
        float nearV = sheet.v(maskY);
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
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        // Whole device pixels, so each corner quad maps 1:1 onto its mask.
        float s = Math.min(64f, Math.round(deviceSize(spread)));
        if (s < 1f) {
            return;
        }
        // The same corner radius the fill uses, so the shadow is the shape's
        // own outline pushed outwards rather than a box's.
        float rad = cornerRadius(radius, r - l, b - t);
        if (rad < 0.6f) {
            rad = 0f;
        }

        batch.bind(sheet.texture());
        float rampNear = sheet.u(Shapes.RAMP_X + 0.5f);
        float rampFar = sheet.u(Shapes.RAMP_X + Shapes.RAMP_SIZE - 0.5f);
        float rampV = sheet.v(Shapes.RAMP_Y + 0.5f);

        // The straight sides run between the corners' circle centres; on a
        // pill the left and right ones have no length and are skipped.
        vertical(l + rad, t - s, r - rad, t, rampFar, rampNear, rampV, colour);
        vertical(l + rad, b, r - rad, b + s, rampNear, rampFar, rampV, colour);
        horizontal(l - s, t + rad, l, b - rad, rampFar, rampNear, rampV, colour);
        horizontal(r, t + rad, r + s, b - rad, rampNear, rampFar, rampV, colour);

        Shapes.Mask corner = rad > 96f ? null : shapes.exactShadow(Math.round(rad), Math.round(s));
        if (corner == null) {
            return;
        }
        float size = rad + s;
        maskCorner(l - s, t - s, size, true, true, colour, corner.x, corner.y, corner.size);
        maskCorner(r - rad, t - s, size, false, true, colour, corner.x, corner.y, corner.size);
        maskCorner(l - s, b - rad, size, true, false, colour, corner.x, corner.y, corner.size);
        maskCorner(r - rad, b - rad, size, false, false, colour, corner.x, corner.y, corner.size);
    }

    /**
     * Four quadrants of the glow mask meeting at the centre, so the falloff is
     * round: the mask is a quarter disc of light, full at its corner and gone
     * at its radius, and each quadrant turns it to face outwards.
     */
    @Override
    public void bloom(float centreX, float centreY, float radius, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || !(radius > 0f)) {
            return;
        }
        float cx = deviceX(centreX);
        float cy = deviceY(centreY);
        float r = deviceSize(radius);
        batch.bind(sheet.texture());
        float near = sheet.u(Shapes.GLOW_X + 0.5f);
        float far = sheet.u(Shapes.GLOW_X + Shapes.GLOW_SIZE - 0.5f);
        float nearV = sheet.v(Shapes.GLOW_Y + 0.5f);
        float farV = sheet.v(Shapes.GLOW_Y + Shapes.GLOW_SIZE - 0.5f);
        quad(cx - r, cy - r, cx, cy, far, farV, near, nearV, colour);
        quad(cx, cy - r, cx + r, cy, near, farV, far, nearV, colour);
        quad(cx - r, cy, cx, cy + r, far, nearV, near, farV, colour);
        quad(cx, cy, cx + r, cy + r, near, nearV, far, farV, colour);
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
        // The pen walks in fractional device pixels, kerned pair by pair. Every
        // glyph's quad sits on whole pixels so its raster reaches the screen
        // one-to-one; in high quality the pen's fraction is carried inside the
        // raster (the nearest quarter-pixel phase), so each letter is where its
        // advance put it rather than rounded to the nearest pixel on its own.
        // Low quality ("performance mode") keeps one raster and snaps.
        boolean phased = highQuality;
        float pen = deviceX(x);
        float baseline = Math.round(deviceY(baselineY));
        char previous = 0;
        for (int index = 0; index < string.length(); index++) {
            char c = string.charAt(index);
            if (previous != 0) {
                pen += page.kern(previous, c);
            }
            previous = c;
            GlyphPage.Glyph glyph = page.glyph(c);
            if (!glyph.blank) {
                float whole;
                int phase;
                if (phased) {
                    whole = (float) Math.floor(pen);
                    phase = Math.round((pen - whole) * GlyphPage.PHASES);
                    if (phase == GlyphPage.PHASES) {
                        whole += 1f;
                        phase = 0;
                    }
                } else {
                    whole = Math.round(pen);
                    phase = 0;
                }
                GlyphPage.Raster raster = page.raster(glyph, phase);
                float gx = whole + raster.offsetX;
                float gy = baseline + raster.offsetY;
                quad(gx, gy, gx + raster.width, gy + raster.height,
                        raster.u0, raster.v0, raster.u1, raster.v1, colour);
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

    /**
     * The Aperture lockup: the mark, and the wordmark three units after it.
     *
     * <p>The launcher's rules (desktop/src/lib/brand/aperture.ts): the mark
     * stands on the word's baseline and its plane's top is the word's
     * x-height, which makes the mark six units of a quarter x-height each.
     * Everything is solved in device pixels -- the unit is whole, and the
     * word's size is chosen so its x-height is exactly four units -- so no
     * wall falls between two pixels.
     *
     * <p>The frame is the rounded ring {@link #outline} draws (outer radius
     * one and a half units, inner half a unit: concentric, as the mark's
     * construction has them), drawn twice through a clip that leaves out the
     * open corner, so its ends are cut square. Below a 24-pixel tile the
     * corners are square, as the icon's are.
     *
     * @param y the top of the mark, which is the top of the lockup
     * @return the lockup's width, in logical units
     */
    public float lockup(float x, float y, float capHeight, int frameArgb, int planeArgb) {
        float scale = ui.scale() * zoom;
        int wanted = Math.round(capHeight * ui.scale());
        int unit = Math.max(2, Math.round(wanted * Wordmark.TEXT_X_HEIGHT / Wordmark.CAP
                / (FathomTheme.Tokens.BRAND_GRID - FathomTheme.Tokens.BRAND_PLANE)));
        int grid = Math.round(FathomTheme.Tokens.BRAND_GRID);
        int plane = Math.round(FathomTheme.Tokens.BRAND_PLANE);
        int cap = BrandArt.capForXHeight(unit * (grid - plane), wanted);
        BrandArt.Layout layout = BrandArt.layout(cap);

        // Whole device pixels from here on; converted back to logical units
        // only to hand to the shape calls, which snap them to the same pixels.
        float left = Math.round(deviceX(x));
        float top = Math.round(deviceY(y));
        float size = grid * unit;
        float wall = FathomTheme.Tokens.BRAND_WALL * unit;
        float reach = (grid - plane) * unit;
        float tile = unit * FathomTheme.Tokens.BRAND_UNIT_DIVISOR;
        float outer = tile >= FathomTheme.Tokens.BRAND_TIER_CURVE
                ? FathomTheme.Tokens.BRAND_CORNER_OUTER * unit : 0f;
        float planeRadius = tile >= FathomTheme.Tokens.BRAND_TIER_FULL
                ? FathomTheme.Tokens.BRAND_CORNER_PLANE * unit : 0f;

        float l = logicalX(left);
        float t = logicalY(top);
        float r = logicalX(left + size);
        float b = logicalY(top + size);
        float open = logicalX(left + reach);
        float openY = logicalY(top + reach);
        float ring = wall / scale;
        clip(l, t, open, b);
        outline(l, t, r, b, outer / scale, ring, frameArgb);
        unclip();
        clip(open, t, r, openY);
        outline(l, t, r, b, outer / scale, ring, frameArgb);
        unclip();

        float planeAt = (reach - plane * unit);
        rounded(logicalX(left + planeAt), logicalY(top + planeAt),
                logicalX(left + planeAt + plane * unit), logicalY(top + planeAt + plane * unit),
                planeRadius / scale, planeArgb);

        // The word: on the mark's baseline, three units after it.
        float gap = FathomTheme.Tokens.BRAND_LOCKUP_GAP * unit;
        float wordLeft = left + size + gap;
        float wordTop = top + size - layout.ascender;
        wordmark(logicalX(wordLeft), logicalY(wordTop), cap / ui.scale(), frameArgb);
        return (size + gap + layout.width) / scale;
    }

    /** The lockup's height -- the mark's -- in logical units, for centring it. */
    public float lockupHeight(float capHeight) {
        int wanted = Math.round(capHeight * ui.scale());
        int unit = Math.max(2, Math.round(wanted * Wordmark.TEXT_X_HEIGHT / Wordmark.CAP
                / (FathomTheme.Tokens.BRAND_GRID - FathomTheme.Tokens.BRAND_PLANE)));
        return FathomTheme.Tokens.BRAND_GRID * unit / (ui.scale() * zoom);
    }

    private float logicalX(float device) {
        return (device - offsetX) / (ui.scale() * zoom);
    }

    private float logicalY(float device) {
        return (device - offsetY) / (ui.scale() * zoom);
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
        if (zoom == 1f) {
            // A pure translation moves whole device pixels. A fractional one
            // made every primitive round on its own: a panel sliding in by
            // 3.4 pixels put its fill on one pixel and its glyphs, snapped
            // separately, on the next, so the text shimmered against its
            // surface for the length of every transition.
            offsetX = Math.round(offsetX);
            offsetY = Math.round(offsetY);
        }
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
