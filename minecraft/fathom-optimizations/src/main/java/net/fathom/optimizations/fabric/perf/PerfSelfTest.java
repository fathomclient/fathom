package net.fathom.optimizations.fabric.perf;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/**
 * Proves the cached drawing is the same drawing, before a benchmark measures it.
 *
 * <h2>Why pixels</h2>
 *
 * <p>Both caches here replay recorded GL commands instead of issuing them
 * again. The claim that a replay is identical to a draw is exactly the kind of
 * claim that is easy to assert and hard to believe, so it is checked the only
 * way that settles it: draw into an offscreen buffer, read the pixels back,
 * record the same drawing into a display list, replay it into a cleared buffer,
 * read those pixels, and compare them byte for byte.
 *
 * <p>The strings cover what a nametag can contain — colour codes, bold,
 * underline, strikethrough, a team-style prefix, non-ASCII, and a reset
 * mid-string — because the formatting state is what a cache would get wrong.
 *
 * <p>Runs once, in benchmark mode only, and puts everything it touched back.
 */
public final class PerfSelfTest {

    private PerfSelfTest() {}

    private static final int WIDTH = 256;
    private static final int HEIGHT = 32;

    private static final String[] SAMPLES = {
        "Steve",
        "§aGreen name",
        "§l§cBold red",
        "§9[§bVIP§9] §fPlayer_01",
        "§nUnderlined§r plain",
        "§mStruck§r out",
        "Ünïcödé åæø",
        "§6Level §e42 §6- §a1,234 §7pts"
    };

    private static final List<String> RESULTS = new ArrayList<String>();

    /** What the checks found, for the benchmark report. */
    public static List<String> results() {
        return RESULTS;
    }

    public static void run() {
        RESULTS.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client == null ? null : client.textRenderer;
        if (font == null) {
            RESULTS.add("self-test skipped: no font");
            return;
        }
        try {
            nametagPixelsMatch(font);
            fontReloadDropsTheCache(font);
        } catch (Throwable failure) {
            RESULTS.add("self-test failed: " + failure);
        }
    }

    /** Every sample string, drawn directly and replayed, must be the same pixels. */
    private static void nametagPixelsMatch(TextRenderer font) {
        Offscreen target = Offscreen.create();
        if (target == null) {
            RESULTS.add("nametag pixel check skipped: no offscreen buffer");
            return;
        }
        ByteBuffer direct = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        ByteBuffer replayed = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        int checked = 0;
        int mismatched = 0;
        long worst = 0L;
        try {
            target.begin();
            for (String sample : SAMPLES) {
                target.clear();
                font.draw(sample, 2, 2, 0xFFFFFFFF);
                read(direct);

                int list = GL11.glGenLists(1);
                if (list == 0) {
                    break;
                }
                target.clear();
                GlStateManager.bindTexture(0);
                GlStateManager.clearColor();
                GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
                try {
                    font.draw(sample, 2, 2, 0xFFFFFFFF);
                } finally {
                    GL11.glEndList();
                }
                // Replay alone, into a cleared buffer: what a second frame sees.
                target.clear();
                GL11.glCallList(list);
                read(replayed);
                GL11.glDeleteLists(list, 1);

                checked++;
                long difference = compare(direct, replayed);
                if (difference != 0L) {
                    mismatched++;
                    worst = Math.max(worst, difference);
                }
            }
        } finally {
            target.end();
            target.delete();
        }
        RESULTS.add(String.format(Locale.ROOT,
                "nametag replay is pixel-identical: %s (%d strings checked, %d differed%s)",
                mismatched == 0 ? "YES" : "NO", checked, mismatched,
                mismatched == 0 ? "" : ", worst channel difference " + worst));
    }

    /**
     * A font change must drop every recorded list, or a replay outlives the
     * texture it was recorded against.
     *
     * <p>Exercised through the unicode switch, which is the same invalidation
     * hook a resource-pack reload comes through, and the only one that can be
     * triggered without reloading every resource mid-benchmark. Set to the value
     * it already holds, so nothing about the client changes.
     */
    private static void fontReloadDropsTheCache(TextRenderer font) {
        int before = NametagRenderer.size();
        font.setUnicode(font.isUnicode());
        int after = NametagRenderer.size();
        RESULTS.add("a font change clears the label cache: " + (after == 0 ? "YES" : "NO")
                + " (" + before + " -> " + after + ")");
    }

    private static void read(ByteBuffer into) {
        into.clear();
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into);
    }

    /** The largest per-channel difference, or 0 when the images are identical. */
    private static long compare(ByteBuffer left, ByteBuffer right) {
        long worst = 0L;
        for (int index = 0; index < WIDTH * HEIGHT * 4; index++) {
            int difference = Math.abs((left.get(index) & 0xFF) - (right.get(index) & 0xFF));
            if (difference > worst) {
                worst = difference;
            }
        }
        return worst;
    }

    /** A scratch framebuffer, with the GUI's projection and state. */
    private static final class Offscreen {
        private int framebuffer;
        private int texture;
        private int previousFramebuffer;
        private static final java.nio.IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
        private final HudLayerCache.GlState before = new HudLayerCache.GlState();

        static Offscreen create() {
            Offscreen out = new Offscreen();
            out.texture = GL11.glGenTextures();
            out.framebuffer = GL30.glGenFramebuffers();
            if (out.texture == 0 || out.framebuffer == 0) {
                return null;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, out.texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            out.previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, out.texture, 0);
            boolean complete = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    == GL30.GL_FRAMEBUFFER_COMPLETE;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.previousFramebuffer);
            if (!complete) {
                out.delete();
                return null;
            }
            return out;
        }

        void begin() {
            before.capture();
            VIEWPORT.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
            previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager.viewport(0, 0, WIDTH, HEIGHT);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0d, WIDTH, HEIGHT, 0.0d, -1.0d, 1.0d);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.disableLighting();
            GlStateManager.disableDepthTest();
            GlStateManager.enableTexture();
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableAlphaTest();
            GlStateManager.alphaFunc(516, 0.1f);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }

        void clear() {
            GlStateManager.clearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        }

        void end() {
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GlStateManager.viewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
            before.force();
        }

        void delete() {
            if (framebuffer != 0) {
                GL30.glDeleteFramebuffers(framebuffer);
                framebuffer = 0;
            }
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
                texture = 0;
            }
        }
    }
}
