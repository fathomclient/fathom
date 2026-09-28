package net.fathom.optimizations.nanovg;

import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_BASELINE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT;
import static org.lwjgl.nanovg.NanoVG.NVG_HOLE;
import static org.lwjgl.nanovg.NanoVG.NVG_MITER;
import static org.lwjgl.nanovg.NanoVG.NVG_ROUND;
import static org.lwjgl.nanovg.NanoVG.NVG_BUTT;
import static org.lwjgl.nanovg.NanoVG.nvgArc;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgBeginPath;
import static org.lwjgl.nanovg.NanoVG.nvgBezierTo;
import static org.lwjgl.nanovg.NanoVG.nvgBoxGradient;
import static org.lwjgl.nanovg.NanoVG.nvgCancelFrame;
import static org.lwjgl.nanovg.NanoVG.nvgCircle;
import static org.lwjgl.nanovg.NanoVG.nvgClosePath;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;
import static org.lwjgl.nanovg.NanoVG.nvgFill;
import static org.lwjgl.nanovg.NanoVG.nvgFillColor;
import static org.lwjgl.nanovg.NanoVG.nvgFillPaint;
import static org.lwjgl.nanovg.NanoVG.nvgFontFaceId;
import static org.lwjgl.nanovg.NanoVG.nvgFontSize;
import static org.lwjgl.nanovg.NanoVG.nvgGlobalAlpha;
import static org.lwjgl.nanovg.NanoVG.nvgImagePattern;
import static org.lwjgl.nanovg.NanoVG.nvgLineCap;
import static org.lwjgl.nanovg.NanoVG.nvgLineJoin;
import static org.lwjgl.nanovg.NanoVG.nvgLineTo;
import static org.lwjgl.nanovg.NanoVG.nvgLinearGradient;
import static org.lwjgl.nanovg.NanoVG.nvgMoveTo;
import static org.lwjgl.nanovg.NanoVG.nvgPathWinding;
import static org.lwjgl.nanovg.NanoVG.nvgQuadTo;
import static org.lwjgl.nanovg.NanoVG.nvgRGBAf;
import static org.lwjgl.nanovg.NanoVG.nvgRadialGradient;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgResetScissor;
import static org.lwjgl.nanovg.NanoVG.nvgResetTransform;
import static org.lwjgl.nanovg.NanoVG.nvgRoundedRect;
import static org.lwjgl.nanovg.NanoVG.nvgRoundedRectVarying;
import static org.lwjgl.nanovg.NanoVG.nvgScale;
import static org.lwjgl.nanovg.NanoVG.nvgScissor;
import static org.lwjgl.nanovg.NanoVG.nvgShapeAntiAlias;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign;
import static org.lwjgl.nanovg.NanoVG.nvgTextBounds;
import static org.lwjgl.nanovg.NanoVG.nvgTextGlyphPositions;
import static org.lwjgl.nanovg.NanoVG.nvgTextMetrics;
import static org.lwjgl.nanovg.NanoVG.nvgTranslate;

import java.nio.ByteBuffer;
import java.util.HashMap;

import org.lwjgl.Version;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGGlyphPosition;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVGGL2;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.UiScale;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.CanvasState;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.IconGeometry;
import net.fathom.optimizations.core.ui.render.ImageRef;
import net.fathom.optimizations.core.ui.render.Paint;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.core.ui.render.Shadow;
import net.fathom.optimizations.core.ui.render.TextAlign;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Fathom's {@link FathomRenderer}, drawn by NanoVG.
 *
 * <h2>Where this runs</h2>
 *
 * <p>Inside an isolated classloader, next to LWJGL 3, on the render thread,
 * against the GL context LWJGL 2 created and made current. It imports nothing
 * from Minecraft and nothing from LWJGL 2; the only Fathom types it sees are in
 * {@code core}, which the isolated loader delegates to the game's loader so both
 * sides agree on what a {@link FathomRenderer}, a {@link Paint} or an
 * {@link ImageRef} is.
 *
 * <p>It never opens a frame on its own and never saves GL state: that is
 * {@code fabric/gfx/Canvas}'s job, in one place, so no drawing code here has to
 * think about Minecraft.
 *
 * <h2>The stencil</h2>
 *
 * <p>Minecraft 1.8.9 has <b>no stencil buffer</b>. NanoVG fills anything but a
 * single convex path through the stencil, so every shape here is one convex path,
 * the context is created without {@code NVG_STENCIL_STROKES}, and the one place
 * that wants a hole -- a shadow cut out under a translucent panel -- builds its
 * ring from convex pieces instead (see {@link #shadowRing}). If a stencil ever is
 * present, the frame notices and uses NanoVG's hole path.
 *
 * <h2>Per frame</h2>
 *
 * <p>No allocation on the drawing paths: colours and paints are reused native
 * structs, text is encoded on LWJGL's thread-local stack, widths are cached per
 * type, and the clip/alpha/transform stacks are primitive arrays in
 * {@link CanvasState}. Font, image and paint setup happen once, not per frame.
 */
public final class NanoVgRenderer implements FathomRenderer {

    private static final int LOGGED_IMBALANCES = 5;
    private static final int MEASURE_CACHE_LIMIT = 2048;

    private long vg;
    private boolean inFrame;
    private String gl = "";
    private int stencilBits;
    private long frameNanos;
    private int imbalanceReports;

    private NanoVgFonts fonts;
    private NanoVgImages images;
    private final CanvasState state = new CanvasState();

    private final NVGColor colour = NVGColor.create();
    private final NVGColor second = NVGColor.create();
    private final NVGPaint nvgPaint = NVGPaint.create();
    private final float[] bounds = new float[4];
    private final float[] ascender = new float[1];
    private final float[] descender = new float[1];
    private final float[] lineHeight = new float[1];
    private final int[] imageSize = new int[2];

    /** Current NanoVG text state, so unchanged faces cost no native call. */
    private int faceId = -2;
    private float faceSize = -1f;
    private int faceAlign = -1;

    private final HashMap<Type, HashMap<String, Float>> widths = new HashMap<Type, HashMap<String, Float>>();
    private float widthsScale = -1f;

    private ByteBuffer utf8 = ByteBuffer.allocateDirect(256);
    private NVGGlyphPosition.Buffer glyphs = NVGGlyphPosition.create(128);

    /** Shadow ring geometry, reused. */
    private final float[] innerRing = new float[4 * 11 * 2];
    private final float[] outerRing = new float[4 * 11 * 2];

    private float scale = 1f;
    private float width;
    private float height;

    public NanoVgRenderer() {
        // Nothing touches GL here. The loader creates this off the frame, and a
        // GL call before a context is current is a crash, not an exception.
    }

    // -----------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------

    @Override
    public String describe() {
        return "NanoVG (LWJGL " + Version.getVersion() + ", GL2 backend)"
                + (gl.isEmpty() ? "" : " on " + gl + ", stencil " + stencilBits + " bits")
                + (images == null ? "" : ", " + images.count() + " images cached");
    }

    private void initialise() {
        // LWJGL 3 learns the GL functions of the context LWJGL 2 already made
        // current. NanoVG's GL backend reads its function pointers from exactly
        // these capabilities, by name, which is why lwjgl-opengl is required.
        GL.createCapabilities();
        gl = GL11.glGetString(GL11.GL_VERSION) + " / " + GL11.glGetString(GL11.GL_RENDERER);

        long context = NanoVGGL2.nvgCreate(NanoVGGL2.NVG_ANTIALIAS);
        if (context == 0L) {
            throw new IllegalStateException("nvgCreate returned no context on " + gl
                    + " (the GL2 backend needs shaders and non-power-of-two textures)");
        }
        vg = context;
        fonts = new NanoVgFonts(vg);
        images = new NanoVgImages(vg);
    }

    @Override
    public void beginFrame(int framebufferWidth, int framebufferHeight, float ratio) {
        if (inFrame) {
            throw new IllegalStateException("a NanoVG frame is already open");
        }
        if (vg == 0L) {
            initialise();
        }
        scale = ratio > 0f && ratio == ratio ? ratio : 1f;
        width = Math.max(1, framebufferWidth) / scale;
        height = Math.max(1, framebufferHeight) / scale;
        frameNanos = System.nanoTime();
        stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);

        if (UiScale.differs(widthsScale, scale)) {
            // Glyph advances are computed at the rasterised size, so a new scale
            // can move them by a fraction of a unit.
            widths.clear();
            widthsScale = scale;
        }

        state.reset();
        faceId = -2;
        faceSize = -1f;
        faceAlign = -1;

        images.sweep(frameNanos);
        nvgBeginFrame(vg, width, height, scale);
        inFrame = true;
    }

    @Override
    public void endFrame() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        reportImbalance();
        nvgEndFrame(vg);
    }

    @Override
    public void cancelFrame() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        nvgCancelFrame(vg);
    }

    private void reportImbalance() {
        int open = state.unbalanced();
        if (open > 0 && imbalanceReports < LOGGED_IMBALANCES) {
            imbalanceReports++;
            System.err.println("[Fathom] NanoVG frame ended with " + open
                    + " unmatched clip/alpha/transform push(es); a component is not popping what it pushed");
        }
    }

    @Override
    public boolean inFrame() {
        return inFrame;
    }

    @Override
    public void dispose() {
        try {
            if (vg != 0L) {
                long context = vg;
                try {
                    if (inFrame) {
                        inFrame = false;
                        nvgCancelFrame(context);
                    }
                } finally {
                    try {
                        if (images != null) {
                            images.dispose();
                        }
                    } finally {
                        NanoVGGL2.nvgDelete(context);
                    }
                }
            }
        } finally {
            inFrame = false;
            vg = 0L;
            images = null;
            fonts = null;
            widths.clear();
            widthsScale = -1f;
        }
    }

    @Override
    public float width() {
        return width;
    }

    @Override
    public float height() {
        return height;
    }

    @Override
    public float scale() {
        return scale;
    }

    /** One device pixel under the current transform, zoom included. */
    @Override
    public float pixel() {
        float zoom = state.zoom();
        return 1f / (scale * (zoom > 0f ? zoom : 1f));
    }

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    private boolean skip(float left, float top, float right, float bottom) {
        return !Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(right) || !Float.isFinite(bottom)
                || right <= left || bottom <= top || state.culled(left, top, right, bottom);
    }

    @Override
    public void fill(float left, float top, float right, float bottom, int argb) {
        if (Colour.invisible(argb) || skip(left, top, right, bottom)) {
            return;
        }
        nvgBeginPath(vg);
        nvgRect(vg, left, top, right - left, bottom - top);
        nvgFillColor(vg, rgba(argb, colour));
        nvgFill(vg);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom, float radius, int argb) {
        if (Colour.invisible(argb) || skip(left, top, right, bottom)) {
            return;
        }
        roundedPath(left, top, right, bottom, radius);
        nvgFillColor(vg, rgba(argb, colour));
        nvgFill(vg);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom, float radius, Paint paint) {
        if (paint == null || paint.invisible() || skip(left, top, right, bottom)) {
            return;
        }
        roundedPath(left, top, right, bottom, radius);
        fillWith(paint);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, int argb) {
        if (Colour.invisible(argb) || skip(left, top, right, bottom)) {
            return;
        }
        variedPath(left, top, right, bottom, topLeft, topRight, bottomRight, bottomLeft, 0f);
        nvgFillColor(vg, rgba(argb, colour));
        nvgFill(vg);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, Paint paint) {
        if (paint == null || paint.invisible() || skip(left, top, right, bottom)) {
            return;
        }
        variedPath(left, top, right, bottom, topLeft, topRight, bottomRight, bottomLeft, 0f);
        fillWith(paint);
    }

    @Override
    public void gradient(float left, float top, float right, float bottom, float radius,
            int topArgb, int bottomArgb) {
        if ((Colour.invisible(topArgb) && Colour.invisible(bottomArgb)) || skip(left, top, right, bottom)) {
            return;
        }
        nvgLinearGradient(vg, left, top, left, bottom, rgba(topArgb, colour), rgba(bottomArgb, second), nvgPaint);
        roundedPath(left, top, right, bottom, radius);
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    @Override
    public void horizontalGradient(float left, float top, float right, float bottom, float radius,
            int leftArgb, int rightArgb) {
        if ((Colour.invisible(leftArgb) && Colour.invisible(rightArgb)) || skip(left, top, right, bottom)) {
            return;
        }
        nvgLinearGradient(vg, left, top, right, top, rgba(leftArgb, colour), rgba(rightArgb, second), nvgPaint);
        roundedPath(left, top, right, bottom, radius);
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    @Override
    public void outline(float left, float top, float right, float bottom, float radius,
            float thickness, int argb) {
        outline(left, top, right, bottom, radius, radius, radius, radius, thickness, argb);
    }

    @Override
    public void outline(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, float thickness, int argb) {
        if (Colour.invisible(argb) || !(thickness > 0f) || skip(left, top, right, bottom)) {
            return;
        }
        // A stroke that is a whole number of device pixels wide -- every rim and
        // focus ring -- is put on the pixel grid first. NanoVG strokes the
        // centre line of the path, and a one-pixel line whose centre falls
        // between two pixel rows is drawn as two half-strength rows: softer,
        // and visibly heavier than the same rim on a control that happens to
        // sit on the grid. Snapping the edges and insetting by half the width
        // puts the centre line exactly on a pixel centre. Not while a zoom
        // animates, where a snapped edge would step instead of glide.
        if (crisp(thickness)) {
            left = snapX(left);
            top = snapY(top);
            right = snapX(right);
            bottom = snapY(bottom);
        }
        // Centred on a line half the thickness inside the box, so a bordered box
        // is exactly the size it says it is -- Renderer's rule for outlines.
        float half = thickness / 2f;
        if (right - left <= thickness || bottom - top <= thickness) {
            return;
        }
        variedPath(left, top, right, bottom, topLeft, topRight, bottomRight, bottomLeft, half);
        stroke(thickness, argb, NVG_MITER, NVG_BUTT);
    }

    @Override
    public void circle(float centreX, float centreY, float radius, int argb) {
        if (Colour.invisible(argb) || !(radius > 0f)
                || skip(centreX - radius, centreY - radius, centreX + radius, centreY + radius)) {
            return;
        }
        nvgBeginPath(vg);
        nvgCircle(vg, centreX, centreY, radius);
        nvgFillColor(vg, rgba(argb, colour));
        nvgFill(vg);
    }

    @Override
    public void circle(float centreX, float centreY, float radius, Paint paint) {
        if (paint == null || paint.invisible() || !(radius > 0f)
                || skip(centreX - radius, centreY - radius, centreX + radius, centreY + radius)) {
            return;
        }
        nvgBeginPath(vg);
        nvgCircle(vg, centreX, centreY, radius);
        fillWith(paint);
    }

    @Override
    public void ring(float centreX, float centreY, float radius, float thickness, int argb) {
        float reach = radius + thickness / 2f;
        if (Colour.invisible(argb) || !(radius > 0f) || !(thickness > 0f)
                || skip(centreX - reach, centreY - reach, centreX + reach, centreY + reach)) {
            return;
        }
        nvgBeginPath(vg);
        nvgCircle(vg, centreX, centreY, radius);
        stroke(thickness, argb, NVG_MITER, NVG_BUTT);
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
        float half = thickness / 2f;
        if (Colour.invisible(argb) || !(thickness > 0f)
                || skip(Math.min(x1, x2) - half, Math.min(y1, y2) - half,
                        Math.max(x1, x2) + half, Math.max(y1, y2) + half)) {
            return;
        }
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1, y1);
        nvgLineTo(vg, x2, y2);
        stroke(thickness, argb, NVG_ROUND, NVG_ROUND);
    }

    private void roundedPath(float left, float top, float right, float bottom, float radius) {
        float w = right - left;
        float h = bottom - top;
        nvgBeginPath(vg);
        nvgRoundedRect(vg, left, top, w, h, corner(radius, w, h));
    }

    /** A rounded rectangle with four radii, inset by {@code inset} on every side. */
    private void variedPath(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, float inset) {
        float w = right - left;
        float h = bottom - top;
        float iw = w - inset * 2f;
        float ih = h - inset * 2f;
        nvgBeginPath(vg);
        nvgRoundedRectVarying(vg, left + inset, top + inset, iw, ih,
                Math.max(0f, corner(topLeft, w, h) - inset),
                Math.max(0f, corner(topRight, w, h) - inset),
                Math.max(0f, corner(bottomRight, w, h) - inset),
                Math.max(0f, corner(bottomLeft, w, h) - inset));
    }

    private void stroke(float thickness, int argb, int join, int cap) {
        nvgStrokeWidth(vg, thickness);
        nvgLineJoin(vg, join);
        nvgLineCap(vg, cap);
        nvgStrokeColor(vg, rgba(argb, colour));
        nvgStroke(vg);
    }

    private static float corner(float radius, float w, float h) {
        return RoundedGeometry.radius(radius, w, h);
    }

    // -----------------------------------------------------------------------
    // Paths
    // -----------------------------------------------------------------------

    @Override
    public void beginPath() {
        nvgBeginPath(vg);
    }

    @Override
    public void moveTo(float x, float y) {
        nvgMoveTo(vg, x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        nvgLineTo(vg, x, y);
    }

    @Override
    public void quadTo(float controlX, float controlY, float x, float y) {
        nvgQuadTo(vg, controlX, controlY, x, y);
    }

    @Override
    public void bezierTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        nvgBezierTo(vg, c1x, c1y, c2x, c2y, x, y);
    }

    @Override
    public void arc(float centreX, float centreY, float radius, float fromAngle, float toAngle) {
        // NVG_CW: angles increase clockwise on a y-down screen.
        nvgArc(vg, centreX, centreY, radius, fromAngle, toAngle, 2);
    }

    @Override
    public void closePath() {
        nvgClosePath(vg);
    }

    @Override
    public void fillPath(int argb) {
        if (Colour.invisible(argb) || state.alpha() <= 0f) {
            return;
        }
        nvgFillColor(vg, rgba(argb, colour));
        nvgFill(vg);
    }

    @Override
    public void fillPath(Paint paint) {
        if (paint == null || paint.invisible() || state.alpha() <= 0f) {
            return;
        }
        fillWith(paint);
    }

    @Override
    public void strokePath(float thickness, int argb) {
        if (Colour.invisible(argb) || !(thickness > 0f) || state.alpha() <= 0f) {
            return;
        }
        stroke(thickness, argb, NVG_ROUND, NVG_ROUND);
    }

    /** Fill the current path with a Fathom paint: a colour, or a native paint built in place. */
    private void fillWith(Paint paint) {
        switch (paint.kind()) {
            case LINEAR:
                nvgLinearGradient(vg, paint.x0(), paint.y0(), paint.x1(), paint.y1(),
                        rgba(paint.inner(), colour), rgba(paint.outer(), second), nvgPaint);
                break;
            case RADIAL:
                nvgRadialGradient(vg, paint.x0(), paint.y0(), paint.a(), Math.max(paint.a() + 0.01f, paint.b()),
                        rgba(paint.inner(), colour), rgba(paint.outer(), second), nvgPaint);
                break;
            case BOX:
                nvgBoxGradient(vg, paint.x0(), paint.y0(), paint.x1() - paint.x0(), paint.y1() - paint.y0(),
                        paint.a(), Math.max(0.01f, paint.b()),
                        rgba(paint.inner(), colour), rgba(paint.outer(), second), nvgPaint);
                break;
            case SOLID:
            default:
                nvgFillColor(vg, rgba(paint.inner(), colour));
                nvgFill(vg);
                return;
        }
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    // -----------------------------------------------------------------------
    // Shadows
    // -----------------------------------------------------------------------

    @Override
    public void shadow(float left, float top, float right, float bottom, float radius, float spread, int argb) {
        // Renderer's original contract: a falloff outwards from the shape, no
        // offset, drawn under an opaque panel.
        shadowImpl(left, top, right, bottom, radius, spread, 0f, 0f, 0f, argb, false);
    }

    @Override
    public void shadow(float left, float top, float right, float bottom, float radius, Shadow shadow) {
        if (shadow == null) {
            return;
        }
        shadowImpl(left, top, right, bottom, radius, shadow.blur(), shadow.spread(),
                shadow.offsetX(), shadow.offsetY(), shadow.argb(), shadow.cutout());
    }

    @Override
    public void glow(float left, float top, float right, float bottom, float radius, float size, int argb) {
        // Repeated glows (for example a full page of enabled module cards) are
        // one box-gradient fill each. Their opaque/near-opaque surface is drawn
        // next and covers the centre; cutting a hole here would require many
        // small ring fills on Minecraft's no-stencil framebuffer.
        shadowImpl(left, top, right, bottom, radius, size, 0f, 0f, 0f, argb, false);
    }

    @Override
    public void bloom(float centreX, float centreY, float radius, int argb) {
        if (Colour.invisible(argb) || !Float.isFinite(centreX) || !Float.isFinite(centreY)
                || !(radius > 0f) || !Float.isFinite(radius)) {
            return;
        }
        if (state.culled(centreX - radius, centreY - radius, centreX + radius, centreY + radius)) {
            return;
        }
        // One radial gradient, faded to transparent in the same colour so the
        // edge never darkens on its way out.
        nvgRadialGradient(vg, centreX, centreY, 0f, radius,
                rgba(argb, colour), rgba(argb & 0x00FFFFFF, second), nvgPaint);
        nvgBeginPath(vg);
        nvgCircle(vg, centreX, centreY, radius);
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    /**
     * One box gradient: the cheapest soft shadow there is -- a single fill whose
     * falloff the fragment shader computes, not a blur.
     */
    private void shadowImpl(float left, float top, float right, float bottom, float radius,
            float blur, float spread, float offsetX, float offsetY, int argb, boolean cutout) {
        float w = right - left;
        float h = bottom - top;
        if (Colour.invisible(argb) || !Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(right) || !Float.isFinite(bottom) || !(w > 0f) || !(h > 0f)) {
            return;
        }
        float safeBlur = Float.isFinite(blur) ? blur : 0f;
        float safeSpread = Float.isFinite(spread) ? spread : 0f;
        float safeOffsetX = Float.isFinite(offsetX) ? offsetX : 0f;
        float safeOffsetY = Float.isFinite(offsetY) ? offsetY : 0f;
        float feather = Math.max(1f, safeBlur);
        // The box gradient reaches zero half a feather outside its box.
        float reach = feather / 2f + Math.max(0f, safeSpread)
                + Math.max(Math.abs(safeOffsetX), Math.abs(safeOffsetY)) + 1f;
        if (state.culled(left - reach, top - reach, right + reach, bottom + reach)) {
            return;
        }
        float rad = corner(radius, w, h);
        nvgBoxGradient(vg, left + safeOffsetX - safeSpread, top + safeOffsetY - safeSpread,
                w + safeSpread * 2f, h + safeSpread * 2f,
                Math.max(0f, rad + safeSpread), feather,
                rgba(argb, colour), rgba(argb & 0x00FFFFFF, second), nvgPaint);

        if (!cutout) {
            nvgBeginPath(vg);
            nvgRect(vg, left - reach, top - reach, w + reach * 2f, h + reach * 2f);
            nvgFillPaint(vg, nvgPaint);
            nvgFill(vg);
        } else if (stencilBits > 0) {
            nvgBeginPath(vg);
            nvgRect(vg, left - reach, top - reach, w + reach * 2f, h + reach * 2f);
            nvgRoundedRect(vg, left, top, w, h, rad);
            nvgPathWinding(vg, NVG_HOLE);
            nvgFillPaint(vg, nvgPaint);
            nvgFill(vg);
        } else {
            shadowRing(left, top, right, bottom, rad, reach);
        }
    }

    /**
     * The area between a rounded rectangle and a larger concentric one, without
     * a stencil.
     *
     * <p>Both outlines are sampled at the same angles, so each pair of
     * neighbouring samples bounds a quadrilateral between two parallel curves --
     * which is always convex, and a convex fill needs no stencil. The quads share
     * exact vertices, so with anti-aliasing off for them they meet without seams;
     * the outer edge needs no smoothing because the gradient is already zero
     * there, and the inner edge sits half a pixel under the panel's own
     * anti-aliased edge.
     */
    private void shadowRing(float left, float top, float right, float bottom, float rad, float reach) {
        float pixel = 1f / (scale * state.zoom());
        float inset = pixel * 0.5f;
        float l = left + inset;
        float t = top + inset;
        float r = right - inset;
        float b = bottom - inset;
        float innerRadius = Math.max(0f, rad - inset);
        float grow = reach + inset;
        float outerRadius = innerRadius + grow;

        int steps = Math.max(2, Math.min(10, (int) Math.ceil(outerRadius / pixel / 8f)));
        int count = 0;
        for (int corner = 0; corner < 4; corner++) {
            float cx = corner == 0 || corner == 3 ? l + innerRadius : r - innerRadius;
            float cy = corner < 2 ? t + innerRadius : b - innerRadius;
            double start = Math.PI + corner * Math.PI / 2.0;
            for (int step = 0; step <= steps; step++) {
                double angle = start + (Math.PI / 2.0) * step / steps;
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                innerRing[count * 2] = cx + cos * innerRadius;
                innerRing[count * 2 + 1] = cy + sin * innerRadius;
                outerRing[count * 2] = cx + cos * outerRadius;
                outerRing[count * 2 + 1] = cy + sin * outerRadius;
                count++;
            }
        }

        nvgShapeAntiAlias(vg, false);
        nvgFillPaint(vg, nvgPaint);
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            nvgBeginPath(vg);
            nvgMoveTo(vg, innerRing[i * 2], innerRing[i * 2 + 1]);
            nvgLineTo(vg, outerRing[i * 2], outerRing[i * 2 + 1]);
            nvgLineTo(vg, outerRing[j * 2], outerRing[j * 2 + 1]);
            nvgLineTo(vg, innerRing[j * 2], innerRing[j * 2 + 1]);
            nvgClosePath(vg);
            nvgFill(vg);
        }
        nvgShapeAntiAlias(vg, true);
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    @Override
    public boolean fontsAvailable() {
        return fonts != null && fonts.available();
    }

    /** Select a type and alignment, skipping native calls that would change nothing. */
    private void face(Type type, int align) {
        int id = fonts.id(type.weight());
        if (id != faceId) {
            nvgFontFaceId(vg, id);
            faceId = id;
        }
        if (type.size() != faceSize) {
            nvgFontSize(vg, type.size());
            faceSize = type.size();
        }
        if (align != faceAlign) {
            nvgTextAlign(vg, align);
            faceAlign = align;
        }
    }

    @Override
    public float text(String text, float x, float baselineY, Type type, int argb) {
        return text(text, x, baselineY, type, argb, NVG_ALIGN_LEFT | NVG_ALIGN_BASELINE);
    }

    @Override
    public float textCentred(String text, float centreX, float baselineY, Type type, int argb) {
        return text(text, centreX, baselineY, type, argb, TextAlign.CENTER | TextAlign.BASELINE);
    }

    @Override
    public float textRight(String text, float rightX, float baselineY, Type type, int argb) {
        return text(text, rightX, baselineY, type, argb, TextAlign.RIGHT | TextAlign.BASELINE);
    }

    @Override
    public float text(String text, float x, float y, Type type, int argb, int align) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float advance = measure(text, type);
        if (Colour.invisible(argb) || state.alpha() <= 0f || !fontsAvailable()) {
            return advance;
        }
        face(type, TextAlign.normalise(align));
        nvgFillColor(vg, rgba(argb, colour));
        nvgText(vg, x, snapY(y), text);
        return advance;
    }

    @Override
    public float textFit(String text, float x, float y, float maxWidth, Type type, int argb, int align) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) {
            return 0f;
        }
        float full = measure(text, type);
        if (full <= maxWidth) {
            return text(text, x, y, type, argb, align);
        }
        float ellipsis = measure(Text.ELLIPSIS, type);
        if (ellipsis > maxWidth || !fontsAvailable()) {
            return 0f;
        }
        int normal = TextAlign.normalise(align);
        int vertical = normal & ~(TextAlign.LEFT | TextAlign.CENTER | TextAlign.RIGHT);
        face(type, TextAlign.LEFT | vertical);

        int bytes = encode(text);
        if (glyphs.capacity() < text.length()) {
            glyphs = NVGGlyphPosition.create(Math.max(text.length(), glyphs.capacity() * 2));
        }
        glyphs.clear();
        int count = nvgTextGlyphPositions(vg, 0f, 0f, utf8, glyphs);
        long base = MemoryUtil.memAddress(utf8);

        // The last glyph that ends before there is only room for the ellipsis.
        float room = maxWidth - ellipsis;
        int last = -1;
        for (int i = 0; i < count; i++) {
            if (glyphs.get(i).maxx() > room) {
                break;
            }
            last = i;
        }
        // Do not end on a space before the ellipsis; " …" reads as a pause.
        while (last >= 0 && utf8.get((int) (glyphs.get(last).str() - base)) == ' ') {
            last--;
        }
        int cut = last + 1 < count ? (int) (glyphs.get(last + 1).str() - base) : bytes;
        float advance = last < 0 ? 0f : (last + 1 < count ? glyphs.get(last + 1).x() : full);
        float drawn = advance + ellipsis;

        if (!Colour.invisible(argb) && state.alpha() > 0f) {
            float start = x + TextAlign.offsetX(normal, drawn);
            y = snapY(y);
            nvgFillColor(vg, rgba(argb, colour));
            if (cut > 0) {
                utf8.limit(cut);
                nvgText(vg, start, y, utf8);
            }
            nvgText(vg, start + advance, y, Text.ELLIPSIS);
        }
        return drawn;
    }

    /**
     * A text anchor moved onto the device-pixel grid, vertically.
     *
     * <p>Glyphs rasterised at a fractional vertical offset are resampled every
     * frame something moves, which reads as text shimmering or its baseline
     * wandering by half a pixel between neighbouring rows. Horizontal position
     * is left fractional so slides stay smooth.
     */
    private float snapY(float y) {
        float zoom = state.zoom();
        float frame = state.frameY(y);
        float snapped = Math.round(frame * scale) / scale;
        return y + (snapped - frame) / (zoom == 0f ? 1f : zoom);
    }

    /** The same, horizontally: for static edges only (see {@link #crisp}). */
    private float snapX(float x) {
        float zoom = state.zoom();
        float frame = state.frameX(x);
        float snapped = Math.round(frame * scale) / scale;
        return x + (snapped - frame) / (zoom == 0f ? 1f : zoom);
    }

    /**
     * Whether a stroke is a whole number of device pixels (1 or 2) at rest, and
     * so should be drawn on the pixel grid. False mid-zoom.
     */
    private boolean crisp(float thickness) {
        float zoom = state.zoom();
        if (Math.abs(zoom - 1f) > 0.001f) {
            return false;
        }
        float device = thickness * scale;
        float whole = Math.round(device);
        return whole >= 1f && whole <= 2f && Math.abs(device - whole) < 0.01f;
    }

    /** UTF-8 into the reusable buffer, position 0, limit at the end. */
    private int encode(String text) {
        int length = MemoryUtil.memLengthUTF8(text, false);
        if (utf8.capacity() < length) {
            utf8 = ByteBuffer.allocateDirect(Math.max(length, utf8.capacity() * 2));
        }
        utf8.clear();
        int written = MemoryUtil.memUTF8(text, false, utf8, 0);
        utf8.position(0).limit(written);
        return written;
    }

    @Override
    public float textBounds(String text, float x, float y, Type type, int align, float[] out) {
        if (text == null || text.isEmpty() || !fontsAvailable()) {
            if (out != null && out.length >= 4) {
                out[0] = x;
                out[1] = y;
                out[2] = x;
                out[3] = y;
            }
            return 0f;
        }
        face(type, TextAlign.normalise(align));
        return nvgTextBounds(vg, x, y, text, out);
    }

    @Override
    public float measure(String text, Type type) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        HashMap<String, Float> perType = widths.get(type);
        if (perType == null) {
            perType = new HashMap<String, Float>();
            widths.put(type, perType);
        }
        Float known = perType.get(text);
        if (known != null) {
            return known;
        }
        float width;
        if (!fontsAvailable()) {
            // Near enough for layout to stay sane with no font at all.
            width = text.length() * type.size() * 0.5f;
        } else {
            face(type, NVG_ALIGN_LEFT | NVG_ALIGN_BASELINE);
            width = nvgTextBounds(vg, 0f, 0f, text, bounds);
        }
        if (perType.size() >= MEASURE_CACHE_LIMIT) {
            perType.clear();
        }
        perType.put(text, width);
        return width;
    }

    @Override
    public float lineHeight(Type type) {
        if (!metrics(type)) {
            return type.size() * 1.25f;
        }
        // Already absolute: fontstash scales the font's line height by the size.
        return lineHeight[0];
    }

    @Override
    public float ascent(Type type) {
        return metrics(type) ? ascender[0] : type.size() * 0.8f;
    }

    @Override
    public float descent(Type type) {
        return metrics(type) ? -descender[0] : type.size() * 0.2f;
    }

    private boolean metrics(Type type) {
        if (!fontsAvailable()) {
            return false;
        }
        face(type, faceAlign < 0 ? NVG_ALIGN_LEFT | NVG_ALIGN_BASELINE : faceAlign);
        nvgTextMetrics(vg, ascender, descender, lineHeight);
        return true;
    }

    // -----------------------------------------------------------------------
    // Icons
    // -----------------------------------------------------------------------

    /**
     * An icon from {@link IconGeometry}: the same Lucide-style path data the GL
     * renderer rasterises, stroked here as vectors at whatever size it is drawn.
     *
     * <p>Strokes and dots need no stencil. The rare filled icon is a polygon: a
     * convex one is filled directly, a concave one (the filled star) is fanned
     * from its centroid with anti-aliasing off -- valid for the star-shaped
     * outlines icons use -- and its edge is smoothed with a hairline stroke.
     */
    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        if (icon == null || Colour.invisible(argb) || size <= 0f || skip(x, y, x + size, y + size)) {
            return;
        }
        if (net.fathom.optimizations.core.ui.render.GeometryDebug.ENABLED) {
            net.fathom.optimizations.core.ui.render.GeometryDebug.icon(this, x, y, size);
        }
        IconGeometry geometry = IconGeometry.of(icon);
        float k = size / IconGeometry.GRID;
        float pixel = 1f / (scale * state.zoom());
        float thickness = Math.max(IconGeometry.STROKE_DP, 1.3f * pixel);

        float[] ops = geometry.strokes();
        if (ops.length > 0) {
            nvgBeginPath(vg);
            int at = 0;
            while (at < ops.length) {
                int op = (int) ops[at++];
                switch (op) {
                    case IconGeometry.MOVE:
                        nvgMoveTo(vg, x + ops[at] * k, y + ops[at + 1] * k);
                        at += 2;
                        break;
                    case IconGeometry.LINE:
                        nvgLineTo(vg, x + ops[at] * k, y + ops[at + 1] * k);
                        at += 2;
                        break;
                    case IconGeometry.CUBIC:
                        nvgBezierTo(vg, x + ops[at] * k, y + ops[at + 1] * k, x + ops[at + 2] * k,
                                y + ops[at + 3] * k, x + ops[at + 4] * k, y + ops[at + 5] * k);
                        at += 6;
                        break;
                    case IconGeometry.QUAD:
                        nvgQuadTo(vg, x + ops[at] * k, y + ops[at + 1] * k, x + ops[at + 2] * k,
                                y + ops[at + 3] * k);
                        at += 4;
                        break;
                    default:
                        nvgClosePath(vg);
                        break;
                }
            }
            stroke(thickness, argb, NVG_ROUND, NVG_ROUND);
        }

        float[] dots = geometry.dots();
        if (dots.length > 0) {
            rgba(argb, colour);
            for (int i = 0; i + 1 < dots.length; i += 2) {
                nvgBeginPath(vg);
                nvgCircle(vg, x + dots[i] * k, y + dots[i + 1] * k, thickness * 0.55f);
                nvgFillColor(vg, colour);
                nvgFill(vg);
            }
        }

        for (int index = 0; index < geometry.fillCount(); index++) {
            fillPolygon(geometry.fill(index), geometry.fillConvex(index), x, y, k, pixel, argb);
        }
    }

    private void fillPolygon(float[] points, boolean convex, float x, float y, float k, float pixel, int argb) {
        int count = points.length / 2;
        nvgFillColor(vg, rgba(argb, colour));
        if (convex) {
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + points[0] * k, y + points[1] * k);
            for (int i = 1; i < count; i++) {
                nvgLineTo(vg, x + points[i * 2] * k, y + points[i * 2 + 1] * k);
            }
            nvgClosePath(vg);
            nvgFill(vg);
            return;
        }
        float cx = 0f;
        float cy = 0f;
        for (int i = 0; i < count; i++) {
            cx += points[i * 2];
            cy += points[i * 2 + 1];
        }
        cx = x + cx / count * k;
        cy = y + cy / count * k;
        nvgShapeAntiAlias(vg, false);
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            nvgBeginPath(vg);
            nvgMoveTo(vg, cx, cy);
            nvgLineTo(vg, x + points[i * 2] * k, y + points[i * 2 + 1] * k);
            nvgLineTo(vg, x + points[j * 2] * k, y + points[j * 2 + 1] * k);
            nvgClosePath(vg);
            nvgFill(vg);
        }
        nvgShapeAntiAlias(vg, true);
        nvgBeginPath(vg);
        nvgMoveTo(vg, x + points[0] * k, y + points[1] * k);
        for (int i = 1; i < count; i++) {
            nvgLineTo(vg, x + points[i * 2] * k, y + points[i * 2 + 1] * k);
        }
        nvgClosePath(vg);
        stroke(pixel, argb, NVG_ROUND, NVG_ROUND);
    }

    // -----------------------------------------------------------------------
    // Images
    // -----------------------------------------------------------------------

    @Override
    public void image(ImageRef image, float left, float top, float right, float bottom, float radius, float alpha) {
        if (image == null || alpha <= 0f || skip(left, top, right, bottom)) {
            return;
        }
        int id = images.resolve(image, frameNanos, imageSize);
        if (id < 0) {
            return;
        }
        nvgImagePattern(vg, left, top, right - left, bottom - top, 0f, id, Math.min(1f, alpha), nvgPaint);
        roundedPath(left, top, right, bottom, radius);
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    @Override
    public void imageCover(ImageRef image, float left, float top, float right, float bottom, float radius,
            float alpha) {
        if (image == null || alpha <= 0f || skip(left, top, right, bottom)) {
            return;
        }
        int id = images.resolve(image, frameNanos, imageSize);
        if (id < 0 || imageSize[0] <= 0 || imageSize[1] <= 0) {
            return;
        }
        float w = right - left;
        float h = bottom - top;
        float fit = Math.max(w / imageSize[0], h / imageSize[1]);
        float pw = imageSize[0] * fit;
        float ph = imageSize[1] * fit;
        nvgImagePattern(vg, left + (w - pw) / 2f, top + (h - ph) / 2f, pw, ph, 0f, id, Math.min(1f, alpha), nvgPaint);
        roundedPath(left, top, right, bottom, radius);
        nvgFillPaint(vg, nvgPaint);
        nvgFill(vg);
    }

    @Override
    public ImageRef.State imageState(ImageRef image) {
        return images == null || image == null ? ImageRef.State.UNLOADED : images.state(image);
    }

    @Override
    public void release(ImageRef image) {
        if (images != null && image != null) {
            images.release(image);
        }
    }

    // -----------------------------------------------------------------------
    // State: the arithmetic lives in CanvasState; this tells NanoVG about it
    // -----------------------------------------------------------------------

    @Override
    public void clip(float left, float top, float right, float bottom) {
        state.intersectClip(left, top, right, bottom);
        applyClip();
    }

    @Override
    public void pushScissor(float left, float top, float right, float bottom) {
        state.pushClip(left, top, right, bottom);
        applyClip();
    }

    @Override
    public void pushNoScissor() {
        state.pushUnclipped();
        applyClip();
    }

    @Override
    public void unclip() {
        if (state.popClip()) {
            applyClip();
        }
    }

    @Override
    public void resetScissor() {
        state.resetClip();
        applyClip();
    }

    /**
     * NanoVG transforms a scissor by the transform current when it is set, so
     * the rectangle -- already in frame coordinates -- is applied under the
     * identity and the transform put back afterwards.
     */
    private void applyClip() {
        nvgResetTransform(vg);
        if (state.clipped()) {
            nvgScissor(vg, state.clipLeft(), state.clipTop(),
                    state.clipRight() - state.clipLeft(), state.clipBottom() - state.clipTop());
        } else {
            nvgResetScissor(vg);
        }
        applyTransform();
    }

    @Override
    public void pushAlpha(float value) {
        state.pushAlpha(value);
        nvgGlobalAlpha(vg, state.alpha());
    }

    @Override
    public void popAlpha() {
        if (state.popAlpha()) {
            nvgGlobalAlpha(vg, state.alpha());
        }
    }

    @Override
    public void pushTransform(float translateX, float translateY) {
        pushTransform(translateX, translateY, 1f, 0f, 0f);
    }

    @Override
    public void pushTransform(float translateX, float translateY, float scale, float originX, float originY) {
        state.pushTransform(translateX, translateY, scale, originX, originY);
        applyTransform();
    }

    @Override
    public void popTransform() {
        if (state.popTransform()) {
            applyTransform();
        }
    }

    private void applyTransform() {
        nvgResetTransform(vg);
        nvgTranslate(vg, state.offsetX(), state.offsetY());
        nvgScale(vg, state.zoom(), state.zoom());
    }

    // -----------------------------------------------------------------------

    /** Fathom's ARGB into NanoVG's straight-alpha float colour, in a reused struct. */
    private static NVGColor rgba(int argb, NVGColor into) {
        return nvgRGBAf(Colour.red(argb), Colour.green(argb), Colour.blue(argb), Colour.alpha(argb), into);
    }
}
