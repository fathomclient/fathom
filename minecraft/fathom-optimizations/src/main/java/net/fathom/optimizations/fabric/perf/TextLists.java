package net.fathom.optimizations.fabric.perf;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.font.TextRenderer;

/**
 * {@code render.hud.tab-list-text-replay}: the tab list's text, each string replayed
 * from a display list instead of drawn glyph by glyph every frame it is held.
 *
 * <h2>Why only the text</h2>
 *
 * <p>Replaying the whole tab list was rejected (`multiplayer-2026-10.md`): what it
 * shows depends on skins loading in the background, hat-layer metadata, latency and
 * an animated health display, and no signature covers all of that without risking a
 * stale frame. The text is different. A string drawn at a position in a colour is
 * the same pixels every time, so each one is recorded once and replayed; everything
 * else -- the sort, the skins, the icons, the backgrounds, the scores -- stays live.
 * With 80 players the text was 0.46 of the list's 0.74 ms a frame
 * (`multiplayer-2026-10-13.md`).
 *
 * <h2>Keeping GlStateManager honest</h2>
 *
 * <p>As {@link NametagRenderer} does: before recording, the cached texture, colour and
 * alpha test are invalidated, so the font's bind, colour and alpha-enable are issued
 * and recorded rather than skipped as redundant; after recording the texture and
 * colour the list leaves behind are read back once; after each replay
 * {@code GlStateManager} is told them, so its cache matches GL.
 *
 * <h2>When it steps aside</h2>
 *
 * <p>Obfuscated text ({@code §k}) changes every frame by design; an OptiFine shader
 * pack rebinds textures through its own path; more than {@link #BUDGET} new strings
 * in one frame are drawn live and recorded on later frames. A font reload or the
 * unicode toggle releases every list.
 *
 * <p>With {@code -Dfathom.verify.caches=true}, one replay in 64 is drawn both ways
 * into an offscreen framebuffer and the two images compared byte for byte.
 */
public final class TextLists {

    private TextLists() {}

    /** Distinct strings kept: an 80-player list is 160; this is headroom. */
    private static final int CAPACITY = 1024;
    private static final int MASK = CAPACITY - 1;
    /** New strings recorded per frame, at most. */
    static final int BUDGET = 48;

    private static final String[] TEXT = new String[CAPACITY];
    private static final float[] X = new float[CAPACITY];
    private static final float[] Y = new float[CAPACITY];
    private static final int[] COLOUR = new int[CAPACITY];
    private static final int[] LIST = new int[CAPACITY];
    private static final int[] ADVANCE = new int[CAPACITY];
    private static final int[] END_TEXTURE = new int[CAPACITY];
    private static final float[][] END_COLOUR = new float[CAPACITY][4];
    private static int size;
    private static TextRenderer owner;
    private static int recordedThisFrame;

    private static final FloatBuffer READ = BufferUtils.createFloatBuffer(16);

    public static long replays;
    public static long records;
    public static long live;
    public static long verified;
    public static long mismatches;

    /** Once per frame. */
    public static void frame() {
        recordedThisFrame = 0;
    }

    /**
     * Draw {@code text} at ({@code x}, {@code y}) in {@code colour} with a shadow, from a
     * list when one is kept, otherwise live (recording it when allowed).
     */
    public static int drawWithShadow(TextRenderer font, String text, float x, float y, int colour) {
        if (text == null || font == null || PerfLayer.shadersActive || NametagRenderer.obfuscated(text)
                || !GlStateCache.available()) {
            TextLists.live++;
            return font.drawWithShadow(text, x, y, colour);
        }
        if (font != owner) {
            release();
            owner = font;
        }
        int slot = find(text, x, y, colour);
        if (slot >= 0 && LIST[slot] != 0) {
            if (NametagNames.VERIFY && replays % 64 == 63) {
                replays++;
                return verify(slot, font, text, x, y, colour);
            }
            replay(slot);
            replays++;
            return ADVANCE[slot];
        }
        if (recordedThisFrame >= BUDGET || size >= CAPACITY * 3 / 4) {
            if (size >= CAPACITY * 3 / 4) {
                // A list that has seen this many distinct strings is being fed
                // something that changes (a counter in a name); start again rather
                // than probing a full table.
                release();
            }
            TextLists.live++;
            return font.drawWithShadow(text, x, y, colour);
        }
        int list = GL11.glGenLists(1);
        if (list == 0) {
            TextLists.live++;
            return font.drawWithShadow(text, x, y, colour);
        }
        recordedThisFrame++;
        GlStateManager.bindTexture(0);
        GlStateManager.clearColor();
        GlStateManager.disableAlphaTest();
        int advance;
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        try {
            advance = font.drawWithShadow(text, x, y, colour);
        } finally {
            GL11.glEndList();
        }
        int at = insert(text, x, y, colour);
        LIST[at] = list;
        ADVANCE[at] = advance;
        END_TEXTURE[at] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        READ.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, READ);
        float[] end = END_COLOUR[at];
        end[0] = READ.get(0);
        end[1] = READ.get(1);
        end[2] = READ.get(2);
        end[3] = READ.get(3);
        records++;
        return advance;
    }

    private static void replay(int slot) {
        // The list's first commands bind the glyph page and set the colour; alpha test
        // is enabled inside it as the font does. Afterwards GlStateManager learns
        // what GL now holds.
        GL11.glCallList(LIST[slot]);
        GlStateManager.bindTexture(END_TEXTURE[slot]);
        float[] end = END_COLOUR[slot];
        GlStateManager.color(end[0], end[1], end[2], end[3]);
        GlStateManager.enableAlphaTest();
    }

    // -- verification -----------------------------------------------------------

    private static net.minecraft.client.gl.Framebuffer target;
    private static java.nio.ByteBuffer replayed;
    private static java.nio.ByteBuffer drawnLive;

    private static int verify(int slot, TextRenderer font, String text, float x, float y, int colour) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        int width = client.width;
        int height = client.height;
        if (target == null || target.textureWidth != width || target.textureHeight != height) {
            if (target != null) {
                target.delete();
            }
            target = new net.minecraft.client.gl.Framebuffer(width, height, true);
            replayed = BufferUtils.createByteBuffer(width * height * 4);
            drawnLive = BufferUtils.createByteBuffer(width * height * 4);
        }
        int texture = GlStateCache.boundTexture();
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear();
        target.bind(true);
        GlStateManager.bindTexture(0);
        GlStateManager.clearColor();
        GlStateManager.disableAlphaTest();
        replay(slot);
        read(replayed, width, height);
        target.clear();
        target.bind(true);
        GlStateManager.bindTexture(0);
        GlStateManager.clearColor();
        GlStateManager.disableAlphaTest();
        int advance = font.drawWithShadow(text, x, y, colour);
        read(drawnLive, width, height);
        verified++;
        if (!replayed.equals(drawnLive) || advance != ADVANCE[slot]) {
            mismatches++;
        }
        client.getFramebuffer().bind(true);
        if (texture >= 0) {
            GlStateManager.bindTexture(texture);
        }
        // The on-screen string, drawn live this once.
        return font.drawWithShadow(text, x, y, colour);
    }

    private static void read(java.nio.ByteBuffer into, int width, int height) {
        into.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into);
        into.rewind();
    }

    // -- the table -------------------------------------------------------------

    private static int hash(String text, float x, float y, int colour) {
        int h = text.hashCode();
        h = h * 31 + Float.floatToIntBits(x);
        h = h * 31 + Float.floatToIntBits(y);
        h = h * 31 + colour;
        h ^= h >>> 16;
        return h & MASK;
    }

    private static int find(String text, float x, float y, int colour) {
        int at = hash(text, x, y, colour);
        for (int probe = 0; probe < CAPACITY; probe++) {
            String held = TEXT[at];
            if (held == null) {
                return -1;
            }
            if (X[at] == x && Y[at] == y && COLOUR[at] == colour && held.equals(text)) {
                return at;
            }
            at = (at + 1) & MASK;
        }
        return -1;
    }

    private static int insert(String text, float x, float y, int colour) {
        int at = hash(text, x, y, colour);
        while (TEXT[at] != null) {
            at = (at + 1) & MASK;
        }
        TEXT[at] = text;
        X[at] = x;
        Y[at] = y;
        COLOUR[at] = colour;
        size++;
        return at;
    }

    /** Font reload, unicode toggle, feature switched off, table full. */
    public static void release() {
        if (size == 0) {
            return;
        }
        for (int at = 0; at < CAPACITY; at++) {
            if (LIST[at] != 0) {
                GL11.glDeleteLists(LIST[at], 1);
                LIST[at] = 0;
            }
            TEXT[at] = null;
        }
        size = 0;
    }

    public static int size() {
        return size;
    }
}
