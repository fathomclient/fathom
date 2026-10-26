package net.fathom.optimizations.fabric.perf;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Tessellator;

/**
 * {@code render.entity.model-box-batching}: each box of an entity model compiled as
 * one draw instead of six.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>A model part (a player's head, arm, jacket layer...) is compiled once into a
 * display list and called every time it is drawn. Inside that list, every face of
 * every box is its own draw: {@code TexturedQuad.draw} begins the tessellator, adds
 * four vertices and draws, so a box is six separate {@code glDrawArrays} of one quad
 * each, and a player's twelve parts are 72 draws the driver replays per player per
 * frame. OptiFine M5 compiles the same way.
 *
 * <h2>What this changes</h2>
 *
 * <p>While a box is drawn, the six faces go into one tessellator batch and are drawn
 * once: the same vertices, in the same order, with the same format and the same GL
 * state, as one {@code GL_QUADS} draw of 24 vertices. Quads in one draw are
 * rasterised exactly as the same quads in consecutive draws, in the same order, so
 * the pixels cannot differ. Nothing per frame changes: the saving is in what the
 * compiled list holds.
 *
 * <p>Lists compiled before the switch changes are recompiled the next time each part
 * is drawn ({@link #generation}).
 *
 * <h2>Verification</h2>
 *
 * <p>With {@code -Dfathom.verify.caches=true} every part also keeps a list compiled
 * the vanilla way; one call in 64 draws both into an offscreen framebuffer under the
 * live matrices and state and compares the two images byte for byte.
 */
public final class ModelBatch {

    private ModelBatch() {}

    public static final boolean VERIFY = Boolean.getBoolean("fathom.verify.caches");

    /** A box is being drawn with its faces batched. Client thread only. */
    public static boolean active;
    /** The batch has begun (the first face's {@code begin} went through). */
    public static boolean started;
    /** The format the batch began with; a face asking for another flushes first. */
    public static Object format;
    /** While a verification reference list is compiled, batching is held off. */
    public static boolean suspended;

    /** Bumped when the switch changes, so every part recompiles once. */
    public static int generation = 1;

    public static long boxesBatched;
    public static long verified;
    /** Comparisons in which the part drew at least one pixel, so equality meant something. */
    public static long verifiedDrawn;
    public static long mismatches;
    public static long recompiles;

    public static boolean enabled() {
        return net.fathom.optimizations.fabric.Runtime.modelBoxBatching && !suspended;
    }

    /** Draw what the batch holds, if anything, and end it. */
    public static void flush() {
        if (active && started) {
            started = false;
            Tessellator.getInstance().draw();
        }
    }

    // -- verification -----------------------------------------------------------

    private static Framebuffer target;
    private static ByteBuffer batched;
    private static ByteBuffer reference;
    private static long calls;

    /** Whether this call should be checked. */
    public static boolean dueForCheck() {
        return VERIFY && (++calls & 63) == 0;
    }

    /**
     * Draw {@code batchedList} and {@code referenceList} offscreen under the current
     * matrices and state, compare them, and return to the main framebuffer.
     */
    public static void check(int batchedList, int referenceList) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.width;
        int height = client.height;
        if (target == null || target.textureWidth != width || target.textureHeight != height) {
            if (target != null) {
                target.delete();
            }
            target = new Framebuffer(width, height, true);
            batched = BufferUtils.createByteBuffer(width * height * 4);
            reference = BufferUtils.createByteBuffer(width * height * 4);
        }
        int texture = GlStateCache.boundTexture();
        draw(batchedList, batched, width, height, texture);
        draw(referenceList, reference, width, height, texture);
        verified++;
        if (!batched.equals(reference)) {
            mismatches++;
        }
        for (int at = 0, end = reference.limit(); at < end; at += 4) {
            if (reference.getInt(at) != 0) {
                verifiedDrawn++;
                break;
            }
        }
        client.getFramebuffer().bind(true);
        if (texture >= 0) {
            GlStateManager.bindTexture(texture);
        }
    }

    private static void draw(int list, ByteBuffer into, int width, int height, int texture) {
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear();
        target.bind(true);
        if (texture >= 0) {
            GlStateManager.bindTexture(texture);
        }
        GL11.glCallList(list);
        into.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into);
        into.rewind();
    }
}
