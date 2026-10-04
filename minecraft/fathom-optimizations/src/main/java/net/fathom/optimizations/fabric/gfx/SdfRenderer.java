package net.fathom.optimizations.fabric.gfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.UiScale;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Layer;
import net.fathom.optimizations.core.ui.render.LayerRecorder;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.text.FontAtlas;
import net.fathom.optimizations.generated.FathomTheme;
import net.fathom.optimizations.generated.Wordmark;

/**
 * Fathom's interface compositor: a small GPU renderer that does exactly what
 * the interface needs and nothing else. The reasoning, the measurements and
 * the comparison with what it replaced are in
 * {@code docs/ingame-ui-compositor.md}.
 *
 * <h2>Recording, then compositing</h2>
 *
 * <p>Nothing touches OpenGL while a screen draws. Every call becomes one quad
 * appended to the current {@link Layer}'s vertex array -- shape, text glyph,
 * icon, image, glass, shadow -- carrying everything its pixels need: its
 * rounded box, border, colours, clip rectangle and kind. At
 * {@link #endFrame} the layers are uploaded in one buffer and drawn in layer
 * order with one program; glass, the only thing that needs the frame behind
 * it, gets it from {@link SdfBackdrop} at the {@code BACKDROP} boundary. A
 * typical Hub frame is a handful of draw calls.
 *
 * <h2>What a pixel is</h2>
 *
 * <p>Coordinates are device pixels, origin top left, produced from logical
 * units by the one transform this class keeps. The game's projection and GUI
 * scale are not used at all. Shapes are evaluated as exact signed distances
 * in the fragment shader (see {@link SdfShaders}), so a radius is a true
 * quarter circle at any size, a border is one width all the way round, and
 * nothing is rasterised in advance -- or rebuilt when the scale changes.
 * Text is a multi-channel distance-field atlas built offline from Geist
 * ({@link SdfText}); positions are kept fractional along the line, as a
 * browser does, with the baseline on a pixel row.
 *
 * <h2>Cost when closed</h2>
 *
 * <p>Nothing. There is no thread, no tick and no per-frame hook: with no
 * Fathom screen open this class is not called.
 */
public final class SdfRenderer implements UiCanvas {

    private static final int MAX_DEPTH = 32;
    private static final int LOGGED_IMBALANCES = 5;

    /** Ints per vertex: pos2 uv2 local2 half2 radii4 params4 clip4 fill1 border1. */
    private static final int STRIDE = 22;
    private static final int QUAD = STRIDE * 4;
    private static final int STRIDE_BYTES = STRIDE * 4;

    /** A command whose quads sample no per-draw texture: shapes, text, icons. */
    private static final int ANY = LayerRecorder.ANY;

    private static final float NO_CLIP = 1.0e7f;

    /**
     * The launcher's standard glass, CSS {@code blur(18px) saturate(1.06)
     * brightness(0.98)}.
     */
    private static final float GLASS_BLUR = 18f;
    private static final float GLASS_SATURATION = 1.06f;
    private static final float GLASS_BRIGHTNESS = 0.98f;

    /**
     * Text weight and contrast, tuned against the launcher's WebView2 text
     * (DirectWrite, greyscale) on the same screens: light text on dark glass
     * reads a little heavier there than linear coverage gives.
     */
    private static float textBias = Float.parseFloat(System.getProperty("fathom.textBias", "0.08"));
    private static float textGamma = Float.parseFloat(System.getProperty("fathom.textGamma", "0.85"));
    /** Extra weight, screen pixels, for near-white text only. */
    private static float textBoost = Float.parseFloat(System.getProperty("fathom.textBoost", "0.08"));

    /** Development: retune the text weight while running (the UI tour's {@code text:} step). */
    public static void tuneText(float bias, float gamma, float boost) {
        textBias = bias;
        textGamma = gamma;
        textBoost = boost;
    }

    private final MinecraftClient client;
    private final SdfShaders shaders = new SdfShaders();
    private final SdfText text = new SdfText();
    private final Icons icons = new Icons(null);
    private final BrandArt brand = new BrandArt(null);
    private final GlStateSnapshot state = new GlStateSnapshot();
    private SdfBackdrop backdrop;

    private boolean created;
    private boolean failed;
    private boolean inFrame;
    private UiScale ui = new UiScale(854, 480, 1f);
    private boolean lowercase;
    private int keepCase;

    /** Recording: per layer, the quads' ints and the draw commands over them. */
    private final LayerRecorder recorder = new LayerRecorder(QUAD);
    private final int[] layerBase = new int[Layer.COUNT];

    private ByteBuffer upload;
    private IntBuffer uploadInts;
    private int vbo;

    // The primitive being emitted.
    private float pCentreX;
    private float pCentreY;
    private float pHalfX;
    private float pHalfY;
    private float pR0;
    private float pR1;
    private float pR2;
    private float pR3;
    private float pBorder;
    private float pKind;
    private float pExtra0;
    private float pExtra1;
    private int pBorderColour;

    // Transform, clip, opacity, layer.
    private float offsetX;
    private float offsetY;
    private float zoom = 1f;
    private final float[] transformStack = new float[MAX_DEPTH * 3];
    private int transformDepth;
    private int transformOverflow;
    private float clipLeft = -NO_CLIP;
    private float clipTop = -NO_CLIP;
    private float clipRight = NO_CLIP;
    private float clipBottom = NO_CLIP;
    private final float[] clipStack = new float[MAX_DEPTH * 4];
    private int clipDepth;
    private int clipOverflow;
    private float alpha = 1f;
    private final float[] alphaStack = new float[MAX_DEPTH];
    private int alphaDepth;
    private int alphaOverflow;
    private int layer = Layer.SURFACE;
    private final int[] layerStack = new int[MAX_DEPTH];
    private int layerDepth;
    private int layerOverflow;
    private int imbalanceReports;

    /** Glass drawn this frame, device l/t/r/b: a pane inside one is not frosted again. */
    private final float[] glassed = new float[4 * 32];
    private int glassedCount;

    private final Stats stats = new Stats();

    /** What the last frame cost. */
    public static final class Stats {
        public int quads;
        public int draws;
        public int bytes;
        public int blurPasses;
        public int lastQuads;
        public int lastDraws;
        public int lastBytes;
        public int lastBlurPasses;
        /** Quads per layer, last frame, for the debug overlay. */
        public final int[] lastLayerQuads = new int[Layer.COUNT];

        void reset() {
            lastQuads = quads;
            lastDraws = draws;
            lastBytes = bytes;
            lastBlurPasses = blurPasses;
            quads = 0;
            draws = 0;
            bytes = 0;
            blurPasses = 0;
        }
    }

    public SdfRenderer(MinecraftClient client) {
        this.client = client;
    }

    /** Whether the GPU has what the compositor needs: GLSL 1.20 and framebuffer objects. */
    public static boolean supported() {
        return GLContext.getCapabilities().OpenGL21 && SdfBackdrop.supported();
    }

    /** Whether creating the GPU resources failed; the caller falls back. */
    public boolean failed() {
        return failed;
    }

    public Stats stats() {
        return stats;
    }

    public UiScale scaleInfo() {
        return ui;
    }

    public String describe() {
        return "Fathom SDF compositor (GLSL 1.20, MSDF text)";
    }

    // -----------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------

    /**
     * Start recording a frame. Touches no GL state, except the first time,
     * when the programs, the atlas and the buffers are created.
     *
     * @return false when the compositor cannot run (creation failed); nothing
     *     was opened and the caller should use another renderer
     */
    public boolean beginFrame(float preference) {
        if (inFrame) {
            throw new IllegalStateException("a Fathom frame is already open");
        }
        if (failed) {
            return false;
        }
        if (!created) {
            try {
                shaders.create();
                text.create();
                icons.create();
                brand.create();
                backdrop = new SdfBackdrop();
                vbo = GL15.glGenBuffers();
                created = true;
            } catch (Exception problem) {
                failed = true;
                System.err.println("[Fathom] The interface compositor could not start ("
                        + problem.getMessage() + "); using the fallback renderer.");
                return false;
            }
        }
        int framebufferWidth = Math.max(1, client.width);
        int framebufferHeight = Math.max(1, client.height);
        float nextScale = UiScale.scaleFor(framebufferHeight, preference);
        if (UiScale.differs(nextScale, ui.scale())) {
            // Icons and the wordmark are exact-size rasters; text is not and
            // keeps its atlas.
            icons.reset();
            brand.reset();
        }
        if (framebufferWidth != ui.framebufferWidth() || framebufferHeight != ui.framebufferHeight()
                || UiScale.differs(nextScale, ui.scale())) {
            ui = new UiScale(framebufferWidth, framebufferHeight, preference);
        }
        offsetX = 0f;
        offsetY = 0f;
        zoom = 1f;
        transformDepth = 0;
        transformOverflow = 0;
        clipLeft = -NO_CLIP;
        clipTop = -NO_CLIP;
        clipRight = NO_CLIP;
        clipBottom = NO_CLIP;
        clipDepth = 0;
        clipOverflow = 0;
        alpha = 1f;
        alphaDepth = 0;
        alphaOverflow = 0;
        layer = Layer.SURFACE;
        layerDepth = 0;
        layerOverflow = 0;
        glassedCount = 0;
        for (int index = 0; index < Layer.COUNT; index++) {
            stats.lastLayerQuads[index] = recorder.primitives(index);
        }
        recorder.clear();
        stats.reset();
        backdrop.beginFrame();
        inFrame = true;
        return true;
    }

    /** Composite the recorded frame onto the bound framebuffer, then put GL back. */
    public void endFrame() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        reportImbalance();
        int total = recorder.totalInts();
        if (total == 0) {
            return;
        }
        state.captureInto();
        int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GlStateManager.activeTexture(GL13.GL_TEXTURE1);
        int unit1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.activeTexture(GL13.GL_TEXTURE2);
        int unit2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.activeTexture(GL13.GL_TEXTURE0);
        try {
            uploadLayers(total);
            int width = Math.max(1, client.width);
            int height = Math.max(1, client.height);
            setUp(width, height);
            boolean blurred = backdrop.slots() == 0;
            for (int index = 0; index < Layer.COUNT; index++) {
                if (!blurred && index >= Layer.index(Layer.BACKDROP)) {
                    backdrop.run(shaders, state.framebuffer(), width, height);
                    stats.blurPasses += backdrop.passes;
                    blurred = true;
                    setUp(width, height);
                }
                drawLayer(index);
            }
        } finally {
            for (int attribute = 0; attribute <= SdfShaders.A_BORDER; attribute++) {
                GL20.glDisableVertexAttribArray(attribute);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager.activeTexture(GL13.GL_TEXTURE2);
            GlStateManager.bindTexture(unit2);
            GlStateManager.activeTexture(GL13.GL_TEXTURE1);
            GlStateManager.bindTexture(unit1);
            GlStateManager.activeTexture(GL13.GL_TEXTURE0);
            state.restore();
        }
    }

    public boolean inFrame() {
        return inFrame;
    }

    private void uploadLayers(int totalInts) {
        int bytes = totalInts * 4;
        if (upload == null || upload.capacity() < bytes) {
            upload = BufferUtils.createByteBuffer(Math.max(bytes, 1 << 18) * 3 / 2).order(ByteOrder.nativeOrder());
            uploadInts = upload.asIntBuffer();
        }
        uploadInts.clear();
        for (int index = 0; index < Layer.COUNT; index++) {
            layerBase[index] = uploadInts.position() / STRIDE;
            uploadInts.put(recorder.data(index), 0, recorder.used(index));
        }
        upload.position(0).limit(bytes);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
        stats.bytes = bytes;
    }

    private void setUp(int width, int height) {
        GL11.glViewport(0, 0, width, height);
        GlStateManager.enableBlend();
        // Premultiplied: the shader outputs colour already multiplied by coverage.
        GlStateManager.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlphaTest();
        GlStateManager.disableDepthTest();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GL20.glUseProgram(shaders.program);
        GL20.glUniform2f(shaders.viewport, width, height);
        GL20.glUniform1f(shaders.textGamma, textGamma);
        GL20.glUniform1f(shaders.textBoost, textBoost);
        GL20.glUniform1f(shaders.trueSlack, text.trueSlack());
        GL20.glUniform1i(shaders.atlas, 0);
        GL20.glUniform1i(shaders.masks, 1);
        GL20.glUniform1i(shaders.image, 2);
        GlStateManager.activeTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(icons.atlas().texture());
        GlStateManager.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(text.texture());
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        attribute(SdfShaders.A_POS, 2, GL11.GL_FLOAT, false, 0);
        attribute(SdfShaders.A_UV, 2, GL11.GL_FLOAT, false, 8);
        attribute(SdfShaders.A_LOCAL, 2, GL11.GL_FLOAT, false, 16);
        attribute(SdfShaders.A_HALF, 2, GL11.GL_FLOAT, false, 24);
        attribute(SdfShaders.A_RADII, 4, GL11.GL_FLOAT, false, 32);
        attribute(SdfShaders.A_PARAMS, 4, GL11.GL_FLOAT, false, 48);
        attribute(SdfShaders.A_CLIP, 4, GL11.GL_FLOAT, false, 64);
        attribute(SdfShaders.A_FILL, 4, GL11.GL_UNSIGNED_BYTE, true, 80);
        attribute(SdfShaders.A_BORDER, 4, GL11.GL_UNSIGNED_BYTE, true, 84);
    }

    private static void attribute(int index, int size, int type, boolean normalised, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalised, STRIDE_BYTES, offset);
    }

    private void drawLayer(int index) {
        int boundImage = Integer.MIN_VALUE;
        for (int command = 0; command < recorder.commands(index); command++) {
            int texture = recorder.commandTexture(index, command);
            if (texture != ANY && texture != boundImage) {
                GlStateManager.activeTexture(GL13.GL_TEXTURE2);
                GlStateManager.bindTexture(texture <= -2 ? backdrop.texture(-2 - texture) : texture);
                GlStateManager.activeTexture(GL13.GL_TEXTURE0);
                boundImage = texture;
            }
            GL11.glDrawArrays(GL11.GL_QUADS, layerBase[index] + recorder.commandStart(index, command) * 4,
                    recorder.commandCount(index, command) * 4);
            stats.draws++;
        }
    }

    private void reportImbalance() {
        int open = transformDepth + transformOverflow + clipDepth + clipOverflow + alphaDepth + alphaOverflow
                + layerDepth + layerOverflow;
        if (open > 0 && imbalanceReports < LOGGED_IMBALANCES) {
            imbalanceReports++;
            System.err.println("[Fathom] UI frame ended with " + open
                    + " unmatched clip/alpha/transform/layer push(es); a component is not popping what it pushed");
        }
        transformDepth = 0;
        transformOverflow = 0;
        clipDepth = 0;
        clipOverflow = 0;
        alphaDepth = 0;
        alphaOverflow = 0;
        layerDepth = 0;
        layerOverflow = 0;
    }

    /** Drop every GPU resource. For a resource reload or a shutdown. */
    public void dispose() {
        inFrame = false;
        if (!created) {
            return;
        }
        shaders.delete();
        text.delete();
        icons.delete();
        brand.delete();
        if (backdrop != null) {
            backdrop.delete();
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
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

    /** Whole device pixels while nothing is scaling, so edges and rims are crisp. */
    private float snap(float device) {
        return zoom == 1f ? Math.round(device) : device;
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
    // Emitting
    // -----------------------------------------------------------------------

    private int tint(int argb) {
        return alpha >= 0.999f ? argb : Colour.scaleAlpha(argb, alpha);
    }

    /** ARGB to the bytes R, G, B, A in memory (a little-endian int). */
    private static int rgba(int argb) {
        return (argb & 0xFF000000) | ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >> 16) & 0xFF);
    }

    /** Set the shape every vertex of the next quad shares. Device pixels. */
    private void shape(float left, float top, float right, float bottom,
            float r0, float r1, float r2, float r3, float border, float kind) {
        pCentreX = (left + right) * 0.5f;
        pCentreY = (top + bottom) * 0.5f;
        pHalfX = (right - left) * 0.5f;
        pHalfY = (bottom - top) * 0.5f;
        pR0 = r0;
        pR1 = r1;
        pR2 = r2;
        pR3 = r3;
        pBorder = border;
        pKind = kind;
        pExtra0 = 0f;
        pExtra1 = 0f;
        pBorderColour = 0;
    }

    /** Whether a device rectangle is entirely outside the current clip. */
    private boolean clippedAway(float left, float top, float right, float bottom) {
        return right <= clipLeft || left >= clipRight || bottom <= clipTop || top >= clipBottom;
    }

    /**
     * Append one quad to the current layer, with the shape set by
     * {@link #shape}. {@code texture} is the per-draw image, or {@link #ANY}.
     */
    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
            int topArgb, int bottomArgb, int texture) {
        if (x1 <= x0 || y1 <= y0 || clippedAway(x0, y0, x1, y1)) {
            return;
        }
        int[] d = recorder.begin(layer, texture);
        int at = recorder.offset();
        int top = rgba(topArgb);
        int bottom = rgba(bottomArgb);
        int border = rgba(pBorderColour);
        at = vertex(d, at, x0, y0, u0, v0, top, border);
        at = vertex(d, at, x0, y1, u0, v1, bottom, border);
        at = vertex(d, at, x1, y1, u1, v1, bottom, border);
        vertex(d, at, x1, y0, u1, v0, top, border);
        stats.quads++;
    }

    private int vertex(int[] d, int at, float x, float y, float u, float v, int fill, int border) {
        d[at] = Float.floatToRawIntBits(x);
        d[at + 1] = Float.floatToRawIntBits(y);
        d[at + 2] = Float.floatToRawIntBits(u);
        d[at + 3] = Float.floatToRawIntBits(v);
        d[at + 4] = Float.floatToRawIntBits(x - pCentreX);
        d[at + 5] = Float.floatToRawIntBits(y - pCentreY);
        d[at + 6] = Float.floatToRawIntBits(pHalfX);
        d[at + 7] = Float.floatToRawIntBits(pHalfY);
        d[at + 8] = Float.floatToRawIntBits(pR0);
        d[at + 9] = Float.floatToRawIntBits(pR1);
        d[at + 10] = Float.floatToRawIntBits(pR2);
        d[at + 11] = Float.floatToRawIntBits(pR3);
        d[at + 12] = Float.floatToRawIntBits(pBorder);
        d[at + 13] = Float.floatToRawIntBits(pKind);
        d[at + 14] = Float.floatToRawIntBits(pExtra0);
        d[at + 15] = Float.floatToRawIntBits(pExtra1);
        d[at + 16] = Float.floatToRawIntBits(clipLeft);
        d[at + 17] = Float.floatToRawIntBits(clipTop);
        d[at + 18] = Float.floatToRawIntBits(clipRight);
        d[at + 19] = Float.floatToRawIntBits(clipBottom);
        d[at + 20] = fill;
        d[at + 21] = border;
        return at + STRIDE;
    }

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    @Override
    public void fill(float left, float top, float right, float bottom, int argb) {
        rounded(left, top, right, bottom, 0f, 0f, 0f, 0f, argb);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom, float radius, int argb) {
        rounded(left, top, right, bottom, radius, radius, radius, radius, argb);
    }

    @Override
    public void rounded(float left, float top, float right, float bottom,
            float topLeft, float topRight, float bottomRight, float bottomLeft, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        box(left, top, right, bottom, topLeft, topRight, bottomRight, bottomLeft, colour, colour, 0, 0f);
    }

    @Override
    public void gradient(float left, float top, float right, float bottom, float radius, int topArgb, int bottomArgb) {
        int upper = tint(topArgb);
        int lower = tint(bottomArgb);
        if ((Colour.invisible(upper) && Colour.invisible(lower)) || right <= left || bottom <= top) {
            return;
        }
        box(left, top, right, bottom, radius, radius, radius, radius, upper, lower, 0, 0f);
    }

    @Override
    public void outline(float left, float top, float right, float bottom, float radius, float thickness, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        box(left, top, right, bottom, radius, radius, radius, radius, 0, 0, colour, thickness);
    }

    @Override
    public void box(float left, float top, float right, float bottom, float radius,
            int fillArgb, int borderArgb, float borderWidth) {
        int fill = tint(fillArgb);
        int border = tint(borderArgb);
        if ((Colour.invisible(fill) && (Colour.invisible(border) || borderWidth <= 0f))
                || right <= left || bottom <= top) {
            return;
        }
        box(left, top, right, bottom, radius, radius, radius, radius, fill, fill, border, borderWidth);
    }

    /** The one shape: a rounded box, filled (top to bottom) and bordered. Logical units in, tinted colours. */
    private void box(float left, float top, float right, float bottom,
            float r0, float r1, float r2, float r3, int fillTop, int fillBottom, int border, float borderWidth) {
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        if (r <= l || b <= t) {
            return;
        }
        float width = 0f;
        if (borderWidth > 0f && !Colour.invisible(border)) {
            // A rim is whole device pixels: one pixel means one, never a smear across two.
            width = Math.max(1f, Math.round(deviceSize(borderWidth)));
        }
        float scale = ui.scale() * zoom;
        shape(l, t, r, b, r0 * scale, r1 * scale, r2 * scale, r3 * scale, width, SdfShaders.KIND_SHAPE);
        pBorderColour = width > 0f ? border : 0;
        // One pixel of margin for the edge's anti-aliasing.
        quad(l - 1f, t - 1f, r + 1f, b + 1f, 0f, 0f, 0f, 0f, fillTop, fillBottom, ANY);
    }

    @Override
    public void inset(float left, float top, float right, float bottom, float radius,
            float offsetX, float offsetY, float spread, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float width = spread > 0f ? Math.max(1f, Math.round(deviceSize(spread))) : 0f;
        float dx = whole(deviceSize(offsetX));
        float dy = whole(deviceSize(offsetY));
        if (width == 0f && dx == 0f && dy == 0f) {
            return;
        }
        float rad = deviceSize(radius);
        shape(l, t, r, b, rad, rad, rad, rad, width, SdfShaders.KIND_SHAPE);
        pBorderColour = colour;
        // uv is the inner shape's offset.
        quad(l - 1f, t - 1f, r + 1f, b + 1f, dx, dy, dx, dy, 0, 0, ANY);
    }

    /** A hairline length in whole device pixels: at least one when it is not zero. */
    private static float whole(float device) {
        if (device == 0f) {
            return 0f;
        }
        float rounded = Math.round(device);
        return rounded == 0f ? Math.signum(device) : rounded;
    }

    @Override
    public void shadow(float left, float top, float right, float bottom, float radius, float spread, int argb) {
        boxShadow(left, top, right, bottom, radius, spread, 0f, 0f, 0f, argb);
    }

    @Override
    public void boxShadow(float left, float top, float right, float bottom, float radius,
            float blur, float spread, float offsetX, float offsetY, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || right <= left || bottom <= top) {
            return;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        float sigma = Math.max(0.01f, deviceSize(blur) / 2f);
        float grow = deviceSize(spread);
        float dx = deviceSize(offsetX);
        float dy = deviceSize(offsetY);
        float reach = sigma * 3f + Math.max(0f, grow) + 1f;
        float rad = deviceSize(radius);
        shape(l, t, r, b, rad, rad, rad, rad, 0f, SdfShaders.KIND_SHADOW);
        pExtra0 = sigma;
        pExtra1 = grow;
        // uv carries the offset for a shadow.
        quad(Math.min(l, l + dx) - reach, Math.min(t, t + dy) - reach, Math.max(r, r + dx) + reach,
                Math.max(b, b + dy) + reach, dx, dy, dx, dy, colour, colour, ANY);
    }

    @Override
    public void bloom(float centreX, float centreY, float radius, int argb) {
        int colour = tint(argb);
        if (Colour.invisible(colour) || !(radius > 0f)) {
            return;
        }
        float cx = deviceX(centreX);
        float cy = deviceY(centreY);
        float rad = deviceSize(radius);
        shape(cx - rad, cy - rad, cx + rad, cy + rad, 0f, 0f, 0f, 0f, 0f, SdfShaders.KIND_BLOOM);
        quad(cx - rad, cy - rad, cx + rad, cy + rad, 0f, 0f, 0f, 0f, colour, colour, ANY);
    }

    // -----------------------------------------------------------------------
    // Glass and images
    // -----------------------------------------------------------------------

    @Override
    public void captureFrost() {
        // Everything drawn before this is the backdrop. Screens that draw
        // their own scene do so in Layer.BACKGROUND; this is the point at
        // which the rest of the frame stops being part of it.
        if (layer == Layer.BACKGROUND) {
            layer = Layer.SURFACE;
        }
    }

    @Override
    public boolean frost(float left, float top, float right, float bottom, float radius) {
        return glass(left, top, right, bottom, radius, GLASS_BLUR);
    }

    @Override
    public boolean glass(float left, float top, float right, float bottom, float radius, float blur) {
        return glass(left, top, right, bottom, radius, blur, GLASS_SATURATION, GLASS_BRIGHTNESS);
    }

    @Override
    public boolean glass(float left, float top, float right, float bottom, float radius, float blur,
            float saturate, float brightness) {
        if (blur <= 0f || layer <= Layer.BACKDROP || right <= left || bottom <= top) {
            return false;
        }
        float l = snap(deviceX(left));
        float t = snap(deviceY(top));
        float r = snap(deviceX(right));
        float b = snap(deviceY(bottom));
        for (int index = 0; index < glassedCount; index++) {
            int at = index * 4;
            if (l >= glassed[at] && t >= glassed[at + 1] && r <= glassed[at + 2] && b <= glassed[at + 3]) {
                // Inside a pane already frosted: the copy is the scene before
                // any interface, so frosting again would wipe the outer
                // pane's tint out from under this one.
                return false;
            }
        }
        if (glassedCount < glassed.length / 4) {
            int at = glassedCount * 4;
            glassed[at] = l;
            glassed[at + 1] = t;
            glassed[at + 2] = r;
            glassed[at + 3] = b;
            glassedCount++;
        }
        int slot = backdrop.slot(blur * ui.scale());
        float rad = deviceSize(radius);
        int opacity = tint(0xFFFFFFFF);
        shape(l, t, r, b, rad, rad, rad, rad, 0f, SdfShaders.KIND_GLASS);
        pExtra0 = saturate;
        pExtra1 = brightness;
        // A fading pane fades its glass by the fill's alpha.
        quad(l - 1f, t - 1f, r + 1f, b + 1f, 0f, 0f, 0f, 0f, opacity, opacity, -2 - slot);
        return true;
    }

    @Override
    public void image(int texture, float left, float top, float right, float bottom, float radius,
            float sourceAspect) {
        if (texture <= 0 || right <= left || bottom <= top) {
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
        float rad = deviceSize(radius);
        shape(l, t, r, b, rad, rad, rad, rad, 0f, SdfShaders.KIND_IMAGE);
        quad(l, t, r, b, u0, v0, u1, v1, colour, colour, texture);
    }

    /** The old renderer's signature: the backdrop colour painted its corners; here they are true. */
    public void image(int texture, float left, float top, float right, float bottom, float radius,
            float sourceAspect, int ignoredBackdrop) {
        image(texture, left, top, right, bottom, radius, sourceAspect);
    }

    @Override
    public void roundedImage(int texture, float left, float top, float right, float bottom, float radius) {
        if (texture <= 0 || right <= left || bottom <= top) {
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
        float rad = deviceSize(radius);
        shape(l, t, r, b, rad, rad, rad, rad, 0f, SdfShaders.KIND_IMAGE);
        quad(l, t, r, b, 0f, 0f, 1f, 1f, colour, colour, texture);
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    @Override
    public void setLowercase(boolean on) {
        lowercase = on;
    }

    @Override
    public void pushKeepCase() {
        keepCase++;
    }

    @Override
    public void popKeepCase() {
        keepCase = Math.max(0, keepCase - 1);
    }

    private boolean lowered(Type type) {
        return lowercase && keepCase == 0 && type.weight() != Type.Weight.MONO_REGULAR
                && type.weight() != Type.Weight.MONO_MEDIUM;
    }

    @Override
    public float text(String string, float x, float baselineY, Type type, int argb) {
        return run(string, x, baselineY, type, 0f, argb);
    }

    /**
     * Lay a string out along its baseline and emit its glyphs: advances and
     * GPOS kerning in exact fractional pixels, the baseline on a pixel row.
     *
     * @return the advance, logical units
     */
    private float run(String string, float x, float baselineY, Type type, float tracking, int argb) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        FontAtlas.Face face = text.face(type);
        int colour = tint(argb);
        float logicalScale = ui.scale() * zoom;
        float pixels = type.size() * logicalScale;
        // CSS letter-spacing: after every character, the last included.
        float gap = tracking * logicalScale + type.tracking() * pixels;
        boolean lower = lowered(type);
        float start = deviceX(x);
        float pen = start;
        float baseline = zoom == 1f ? Math.round(deviceY(baselineY)) : deviceY(baselineY);
        boolean visible = !Colour.invisible(colour)
                && !(baseline - pixels * face.ascender >= clipBottom || baseline + pixels * face.descender <= clipTop);
        if (visible) {
            shape(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, SdfShaders.KIND_TEXT);
            pExtra0 = text.screenRange(pixels);
            pExtra1 = textBias;
        }
        int previous = -1;
        int length = string.length();
        for (int index = 0; index < length; index++) {
            int code = string.charAt(index);
            if (lower) {
                code = Character.toLowerCase(code);
            }
            // Standard ligatures, as a browser applies them -- and, as CSS
            // says, not where letter-spacing is set.
            long ligature = gap == 0f ? face.ligatureAt(string, index, lower) : -1L;
            if (ligature >= 0L) {
                code = (int) (ligature >>> 8);
                index += (int) (ligature & 0xFF) - 1;
            }
            if (previous >= 0) {
                pen += face.kerning(previous, code) * pixels;
            }
            FontAtlas.Glyph glyph = face.glyphOrFallback(code);
            if (glyph == null) {
                previous = code;
                continue;
            }
            if (visible && !glyph.blank()) {
                float x0 = pen + glyph.planeLeft * pixels;
                float x1 = pen + glyph.planeRight * pixels;
                float y0 = baseline - glyph.planeTop * pixels;
                float y1 = baseline - glyph.planeBottom * pixels;
                quad(x0, y0, x1, y1, text.u(glyph.atlasLeft), text.v(glyph.atlasTop),
                        text.u(glyph.atlasRight), text.v(glyph.atlasBottom), colour, colour, ANY);
            }
            pen += glyph.advance * pixels + gap;
            previous = code;
        }
        return (pen - start) / logicalScale;
    }

    @Override
    public float textCentred(String string, float centreX, float baselineY, Type type, int argb) {
        return text(string, centreX - measure(string, type) / 2f, baselineY, type, argb);
    }

    @Override
    public float textRight(String string, float rightX, float baselineY, Type type, int argb) {
        return text(string, rightX - measure(string, type), baselineY, type, argb);
    }

    @Override
    public float textTrackedCentred(String string, float centreX, float baselineY, Type type, float tracking,
            int argb) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        float gap = Math.max(0f, tracking);
        float width = measure(string, type) + gap * (string.length() - 1);
        run(string, centreX - width / 2f, baselineY, type, gap, argb);
        return width;
    }

    @Override
    public float measure(String string, Type type) {
        if (string == null || string.isEmpty()) {
            return 0f;
        }
        return text.face(type).width(string, lowered(type), type.tracking()) * type.size();
    }

    @Override
    public float lineHeight(Type type) {
        FontAtlas.Face face = text.face(type);
        return (face.ascender + face.descender + face.lineGap) * type.size();
    }

    @Override
    public float ascent(Type type) {
        return text.face(type).ascender * type.size();
    }

    @Override
    public float descent(Type type) {
        return text.face(type).descender * type.size();
    }

    // -----------------------------------------------------------------------
    // Icons and the brand
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
        // Rasterised at the interface scale, not the zoom of an entrance: the
        // quad scales for the few frames that lasts, and nothing re-rasterises.
        int pixels = Math.max(6, Math.round(size * ui.scale()));
        Icons.Entry entry = icons.entry(icon, pixels, Icons.strokeUnits(size) * ui.scale());
        float left = snap(deviceX(x));
        float top = snap(deviceY(y));
        float extent = entry.pixels * zoom;
        shape(left, top, left + extent, top + extent, 0f, 0f, 0f, 0f, 0f, SdfShaders.KIND_MASK);
        quad(left, top, left + extent, top + extent, entry.u0, entry.v0, entry.u1, entry.v1, colour, colour, ANY);
    }

    @Override
    public float wordmarkWidth(float capHeight) {
        int cap = Math.round(capHeight * ui.scale());
        return BrandArt.wordmarkWidth(cap) / ui.scale();
    }

    @Override
    public float wordmark(float x, float y, float capHeight, int argb) {
        int cap = Math.round(capHeight * ui.scale());
        BrandArt.Entry entry = brand.wordmark(cap);
        int colour = tint(argb);
        if (entry.width > 0 && !Colour.invisible(colour)) {
            float pad = entry.pad * zoom;
            float left = snap(deviceX(x)) - pad;
            float top = snap(deviceY(y)) - pad;
            float right = left + entry.width * zoom;
            float bottom = top + entry.height * zoom;
            shape(left, top, right, bottom, 0f, 0f, 0f, 0f, 0f, SdfShaders.KIND_IMAGE_MASK);
            quad(left, top, right, bottom, entry.u0, entry.v0, entry.u1, entry.v1, colour, colour,
                    brand.atlas().texture());
        }
        return BrandArt.wordmarkWidth(cap) / ui.scale();
    }

    /**
     * The Aperture lockup. The same construction as the launcher's
     * ({@code desktop/src/lib/brand/aperture.ts}), solved in whole device
     * pixels; see {@code GlRenderer.lockup} for the derivation.
     */
    @Override
    public float lockup(float x, float y, float capHeight, int frameArgb, int planeArgb) {
        float scale = ui.scale() * zoom;
        int wanted = Math.round(capHeight * ui.scale());
        int unit = Math.max(2, Math.round(wanted * Wordmark.TEXT_X_HEIGHT / Wordmark.CAP
                / (FathomTheme.Tokens.BRAND_GRID - FathomTheme.Tokens.BRAND_PLANE)));
        int grid = Math.round(FathomTheme.Tokens.BRAND_GRID);
        int plane = Math.round(FathomTheme.Tokens.BRAND_PLANE);
        int cap = BrandArt.capForXHeight(unit * (grid - plane), wanted);
        BrandArt.Layout layout = BrandArt.layout(cap);

        float left = Math.round(deviceX(x));
        float top = Math.round(deviceY(y));
        float size = grid * unit;
        float wall = FathomTheme.Tokens.BRAND_WALL * unit;
        float reach = (grid - plane) * unit;
        float tile = unit * FathomTheme.Tokens.BRAND_UNIT_DIVISOR;
        float outer = tile >= FathomTheme.Tokens.BRAND_TIER_CURVE ? FathomTheme.Tokens.BRAND_CORNER_OUTER * unit : 0f;
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

        float planeAt = reach - plane * unit;
        rounded(logicalX(left + planeAt), logicalY(top + planeAt),
                logicalX(left + planeAt + plane * unit), logicalY(top + planeAt + plane * unit),
                planeRadius / scale, planeArgb);

        float gap = FathomTheme.Tokens.BRAND_LOCKUP_GAP * unit;
        float wordLeft = left + size + gap;
        float wordTop = top + size - layout.ascender;
        wordmark(logicalX(wordLeft), logicalY(wordTop), cap / ui.scale(), frameArgb);
        return (size + gap + layout.width) / scale;
    }

    @Override
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
        int base = clipDepth * 4;
        clipStack[base] = clipLeft;
        clipStack[base + 1] = clipTop;
        clipStack[base + 2] = clipRight;
        clipStack[base + 3] = clipBottom;
        clipDepth++;
        // Whole pixels, as a scissor box was: a list's edge is crisp.
        clipLeft = Math.max(clipLeft, Math.round(deviceX(left)));
        clipTop = Math.max(clipTop, Math.round(deviceY(top)));
        clipRight = Math.min(clipRight, Math.round(deviceX(right)));
        clipBottom = Math.min(clipBottom, Math.round(deviceY(bottom)));
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
        int base = clipDepth * 4;
        clipLeft = clipStack[base];
        clipTop = clipStack[base + 1];
        clipRight = clipStack[base + 2];
        clipBottom = clipStack[base + 3];
    }

    @Override
    public boolean clipContains(float x, float y) {
        float dx = x * ui.scale();
        float dy = y * ui.scale();
        return dx >= clipLeft && dx < clipRight && dy >= clipTop && dy < clipBottom;
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
        float deviceOriginX = deviceX(originX);
        float deviceOriginY = deviceY(originY);
        offsetX = deviceOriginX - (deviceOriginX - offsetX) * scale + deviceSize(translateX);
        offsetY = deviceOriginY - (deviceOriginY - offsetY) * scale + deviceSize(translateY);
        zoom *= scale;
        if (zoom == 1f) {
            // Whole pixels, so a sliding panel's fill and its text move together.
            offsetX = Math.round(offsetX);
            offsetY = Math.round(offsetY);
        }
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
    public void pushLayer(int next) {
        if (layerDepth >= MAX_DEPTH) {
            layerOverflow++;
            return;
        }
        layerStack[layerDepth++] = layer;
        layer = next;
    }

    @Override
    public void popLayer() {
        if (layerOverflow > 0) {
            layerOverflow--;
            return;
        }
        if (layerDepth == 0) {
            return;
        }
        layer = layerStack[--layerDepth];
    }

    @Override
    public int layer() {
        return layer;
    }
}
