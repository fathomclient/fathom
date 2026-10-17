package net.fathom.optimizations.fabric.perf;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.opt.text.StringTable;

/**
 * The nametag fast path.
 *
 * <h2>Where the time goes in a vanilla nametag</h2>
 *
 * <p>{@code EntityRenderer.renderLabelIfPresent} measures the string three
 * times and draws it twice — once see-through, once depth-tested. Each draw
 * walks the string in Java, looks every character up in a 256-entry table, and
 * sends each glyph to the driver in immediate mode: {@code glBegin}, four
 * texture coordinates, four vertices, {@code glEnd}. OptiFine keeps that path.
 * A lobby's forty labels of twenty characters are some sixteen thousand driver
 * calls a frame, and the render thread's profile is dominated by the driver
 * catching up at {@code SwapBuffers}.
 *
 * <h2>What this does instead</h2>
 *
 * <p>The first time a string is drawn, each of its two passes is drawn by the
 * game's own {@code TextRenderer.draw}, recorded into a display list as it goes
 * ({@code GL_COMPILE_AND_EXECUTE}). After that the pass is one
 * {@code glCallList}. The width is measured once and kept with the lists.
 * Everything around the text — the matrix, the depth and blend state, the
 * translucent background — is vanilla's own sequence, issued through
 * {@link GlStateManager} in vanilla's order, so team colours, formatting codes,
 * resource-pack fonts and OptiFine's custom text colours come out exactly as
 * the game drew them the first time.
 *
 * <h2>Keeping GlStateManager honest</h2>
 *
 * <p>{@code GlStateManager} caches what it believes is bound, which colour is
 * current and whether alpha test is enabled, and skips calls that would not
 * change them. A replayed list changes these behind its back. So:
 *
 * <ul>
 *   <li>before recording, the cached texture is set to 0 and the cached colour
 *       cleared, so the list's first bind and first colour are always issued
 *       and recorded rather than skipped;
 *   <li>after recording, the texture and colour the list left behind are read
 *       back once from GL and kept;
 *   <li>after every replay, texture, colour and alpha test are told to
 *       {@code GlStateManager}, which brings its cache back in line with what
 *       GL now holds;
 *   <li>the caller's texture binding is read from {@code GlStateManager}'s
 *       cache before drawing and restored afterward, without querying GL.
 * </ul>
 *
 * <p>The font's draw path changes nothing else: its only other state calls are
 * a matched disable/enable of texturing around underline and strikethrough.
 *
 * <h2>When it steps aside</h2>
 *
 * <ul>
 *   <li>Obfuscated text ({@code §k}) changes every frame by design.
 *   <li>An OptiFine shader pack rebinds textures through its own path.
 *   <li>More than {@link #COMPILE_BUDGET} new strings in one frame: the rest are
 *       drawn by vanilla this frame and recorded on a later one.
 * </ul>
 *
 * <p>Lists are deleted when a label leaves the table, when the font reloads
 * (resource packs, unicode mode) and when the feature is switched off.
 */
public final class NametagRenderer {

    private NametagRenderer() {}

    /** vanilla's see-through pass colour: white at alpha 0x20. */
    private static final int SEE_THROUGH = 553648127;
    private static final int SOLID = -1;

    /** Distinct strings kept. A lobby shows a few dozen; this is headroom. */
    private static final int CAPACITY = 384;

    /** New strings recorded per frame, at most. */
    static final int COMPILE_BUDGET = 12;
    /**
     * Diagnostic only, never a setting: skip the label's background quad, to measure
     * what one display list per label could at most save
     * ({@code -Dfathom.measure.labelNoBackground=true}). Changes what is drawn.
     */
    private static final boolean MEASURE_WITHOUT_BACKGROUND = Boolean.getBoolean("fathom.measure.labelNoBackground");

    /** Outcome of one hook invocation, including whether it touched GL. */
    public enum Result {
        FALLBACK(false, false, "fallback"),
        HANDLED_NO_GL(true, false, "distance-reject"),
        HANDLED_REPLAY(true, true, "replay"),
        HANDLED_RECORD(true, true, "record");

        public final boolean handled;
        public final boolean mutated;
        public final String path;

        Result(boolean handled, boolean mutated, String path) {
            this.handled = handled;
            this.mutated = mutated;
            this.path = path;
        }
    }

    private static final FloatBuffer COLOUR = BufferUtils.createFloatBuffer(16);

    private static final class Label {
        final int width;
        final int offsetY;
        final int[] lists = new int[2];
        final int[] endTexture = new int[2];
        final float[][] endColour = new float[2][4];

        Label(int width, int offsetY) {
            this.width = width;
            this.offsetY = offsetY;
        }
    }

    private static final StringTable<Label> LABELS = new StringTable<Label>(CAPACITY,
            new StringTable.Evictor<Label>() {
                @Override
                public void evicted(String key, Label label) {
                    delete(label);
                    PerfLayer.COUNTERS.nametagEvictions++;
                }
            });

    private static TextRenderer owner;
    private static int compiledThisFrame;

    /** Once per frame. */
    public static void frame() {
        compiledThisFrame = 0;
    }

    /** Drop every list. Font reload, unicode switch, feature off. */
    public static void release() {
        if (LABELS.size() > 0) {
            LABELS.clear();
        }
    }

    /**
     * Draw a label the way {@code renderLabelIfPresent} would.
     *
     * @return true if it was handled here (including "too far, nothing drawn");
     *     false to let vanilla draw it
     */
    public static Result render(EntityRenderDispatcher dispatcher, TextRenderer font, Entity entity,
            String text, double x, double y, double z, int maxDistance) {
        if (!PerfLayer.fastNametags || PerfLayer.shadersActive || !GlStateCache.available()
                || text == null || font == null
                || obfuscated(text)) {
            PerfLayer.COUNTERS.nametagsVanilla++;
            return Result.FALLBACK;
        }
        double distance = entity.squaredDistanceTo(dispatcher.field_11098);
        if (distance > (double) (maxDistance * maxDistance)) {
            return Result.HANDLED_NO_GL;
        }
        if (font != owner) {
            release();
            owner = font;
        }
        // Font drawing binds the glyph atlas. Vanilla happens to leave that
        // binding behind, but an optimized replacement must not make its
        // caller depend on a cached-list implementation detail. Read the
        // wrapper's existing cache (no glGet) and restore it once at the end.
        int callerTexture = GlStateCache.boundTexture();
        Label label = LABELS.get(text);
        if (label == null) {
            label = new Label(font.getStringWidth(text), "deadmau5".equals(text) ? -10 : 0);
            LABELS.put(text, label);
        }

        float scale = 0.016666668f * 1.6f;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x + 0.0f, (float) y + entity.height + 0.5f, (float) z);
        GL11.glNormal3f(0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(-dispatcher.yaw, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(dispatcher.pitch, 1.0f, 0.0f, 0.0f);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepthTest();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);

        int half = label.width / 2;
        int top = label.offsetY;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        GlStateManager.disableTexture();
        if (!MEASURE_WITHOUT_BACKGROUND) {
        buffer.begin(7, VertexFormats.POSITION_COLOR);
        buffer.vertex(-half - 1, -1 + top, 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).next();
        buffer.vertex(-half - 1, 8 + top, 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).next();
        buffer.vertex(half + 1, 8 + top, 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).next();
        buffer.vertex(half + 1, -1 + top, 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).next();
        tessellator.draw();
        }
        GlStateManager.enableTexture();

        int left = -label.width / 2;
        boolean recorded = pass(font, label, text, 0, left, top, SEE_THROUGH);
        GlStateManager.enableDepthTest();
        GlStateManager.depthMask(true);
        recorded |= pass(font, label, text, 1, left, top, SOLID);

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        if (callerTexture >= 0) {
            GlStateManager.bindTexture(callerTexture);
        }
        GlStateManager.popMatrix();
        PerfLayer.COUNTERS.nametagsFast++;
        return recorded ? Result.HANDLED_RECORD : Result.HANDLED_REPLAY;
    }

    private static boolean pass(TextRenderer font, Label label, String text, int pass, int left, int top,
            int colour) {
        int list = label.lists[pass];
        if (list != 0) {
            GL11.glCallList(list);
            GlStateManager.bindTexture(label.endTexture[pass]);
            float[] end = label.endColour[pass];
            GlStateManager.color(end[0], end[1], end[2], end[3]);
            // TextRenderer.draw starts by enabling alpha test. A display-list
            // replay can perform that raw GL command behind GlStateManager's
            // cache, so make the expected post-draw state explicit as we do
            // for texture and colour. TextRenderer does not mutate blend.
            GlStateManager.enableAlphaTest();
            return false;
        }
        if (compiledThisFrame >= COMPILE_BUDGET) {
            font.draw(text, left, top, colour);
            return false;
        }
        list = GL11.glGenLists(1);
        if (list == 0) {
            font.draw(text, left, top, colour);
            return false;
        }
        compiledThisFrame++;
        PerfLayer.COUNTERS.nametagCompiles++;
        // Make the list's first bind and first colour real calls, so they are
        // recorded instead of skipped as redundant.
        GlStateManager.bindTexture(0);
        GlStateManager.clearColor();
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        try {
            font.draw(text, left, top, colour);
        } finally {
            GL11.glEndList();
        }
        label.lists[pass] = list;
        label.endTexture[pass] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        COLOUR.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOUR);
        float[] end = label.endColour[pass];
        end[0] = COLOUR.get(0);
        end[1] = COLOUR.get(1);
        end[2] = COLOUR.get(2);
        end[3] = COLOUR.get(3);
        GlStateManager.enableAlphaTest();
        return true;
    }

    private static void delete(Label label) {
        for (int pass = 0; pass < 2; pass++) {
            if (label.lists[pass] != 0) {
                GL11.glDeleteLists(label.lists[pass], 1);
                label.lists[pass] = 0;
            }
        }
    }

    /** {@code §k}: glyphs chosen at random every frame. */
    static boolean obfuscated(String text) {
        int at = text.indexOf('§');
        while (at >= 0 && at + 1 < text.length()) {
            char code = text.charAt(at + 1);
            if (code == 'k' || code == 'K') {
                return true;
            }
            at = text.indexOf('§', at + 1);
        }
        return false;
    }

    /** Labels held, for the statistics readout. */
    public static int size() {
        return LABELS.size();
    }
}
