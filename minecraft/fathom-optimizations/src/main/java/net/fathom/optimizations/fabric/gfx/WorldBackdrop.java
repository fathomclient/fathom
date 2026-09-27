package net.fathom.optimizations.fabric.gfx;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.BackdropProcess;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * The live world, captured once when a Fathom menu opens over it, as Graphite
 * smoke.
 *
 * <h2>Once, and only while asked</h2>
 *
 * <p>A menu calls {@link #request} when it opens. On the next rendered frame,
 * at the head of {@code InGameHud.render} -- after the world is drawn and
 * before any HUD is -- the framebuffer is read back once, box-averaged to a
 * quarter of its size, run through {@link BackdropProcess} (the same blur,
 * desaturation and darkening the shipped captures were made with) and
 * uploaded as one texture. The menu draws that texture every frame until it
 * closes and calls {@link #release}. Nothing here runs while no menu has asked:
 * the hook is one boolean read.
 *
 * <p>The read-back stalls the pipeline once, on the frame the menu opens. The
 * processing is at a quarter of the resolution, so the blur is cheap; the
 * whole cost is printed with {@code -Dfathom.transitionInstrumentation=true}.
 */
public final class WorldBackdrop {

    private static final int FACTOR = Math.max(1, Math.round(GraphiteTokens.BACKDROP_HUB_DOWNSCALE));

    private static boolean pending;
    private static int texture;
    private static int width;
    private static int height;
    /** How long the last capture took, read-back to upload, in milliseconds. */
    private static float lastMillis;

    private static ByteBuffer readBack;
    private static int[] full;
    private static int[] quarter;
    private static IntBuffer upload;

    private WorldBackdrop() {}

    /** Capture the world on the next frame that draws one. */
    public static void request() {
        pending = true;
    }

    /** Whether a capture is waiting for its frame. */
    public static boolean pending() {
        return pending;
    }

    /** The captured backdrop's texture, or 0 when there is none. */
    public static int texture() {
        return texture;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static float lastMillis() {
        return lastMillis;
    }

    /**
     * Called at the head of {@code InGameHud.render}. The world is in the
     * framebuffer and nothing of the HUD is.
     */
    public static void captureIfRequested(MinecraftClient client) {
        if (!pending) {
            return;
        }
        pending = false;
        long started = System.nanoTime();
        int frameWidth = client.width;
        int frameHeight = client.height;
        if (frameWidth <= 0 || frameHeight <= 0) {
            return;
        }
        int count = frameWidth * frameHeight;
        if (readBack == null || readBack.capacity() < count * 4) {
            readBack = BufferUtils.createByteBuffer(count * 4);
        }
        readBack.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, frameWidth, frameHeight, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, readBack);
        if (full == null || full.length < count) {
            full = new int[count];
        }
        // Bottom-up rows from GL, top-down here. The buffer is in native (little-
        // endian) order, so BGRA bytes read as ints are already ARGB.
        IntBuffer pixels = readBack.asIntBuffer();
        for (int y = 0; y < frameHeight; y++) {
            pixels.position((frameHeight - 1 - y) * frameWidth);
            pixels.get(full, y * frameWidth, frameWidth);
        }

        if (Boolean.getBoolean("fathom.captureBackdropSource")) {
            saveSource(client, full, frameWidth, frameHeight);
        }

        int w = Math.max(1, frameWidth / FACTOR);
        int h = Math.max(1, frameHeight / FACTOR);
        quarter = BackdropProcess.downsample(full, frameWidth, frameHeight, FACTOR, quarter);
        BackdropProcess.process(quarter, w, h, BackdropProcess.sigmaFor(Math.max(frameWidth, frameHeight)) / FACTOR);
        uploadTexture(quarter, w, h);
        lastMillis = (System.nanoTime() - started) / 1_000_000f;
        if (Boolean.getBoolean("fathom.transitionInstrumentation")) {
            System.out.println("[Fathom] Backdrop capture: " + frameWidth + "x" + frameHeight
                    + " -> " + w + "x" + h + ", " + String.format(java.util.Locale.ROOT, "%.2f", lastMillis)
                    + " ms (read-back, blur, grade, upload).");
        }
    }

    private static void uploadTexture(int[] argb, int w, int h) {
        int count = w * h;
        if (upload == null || upload.capacity() < count) {
            upload = BufferUtils.createIntBuffer(count);
        }
        upload.clear();
        upload.put(argb, 0, count);
        upload.flip();
        if (texture == 0) {
            texture = GL11.glGenTextures();
        }
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, upload);
        GlStateManager.bindTexture(0);
        width = w;
        height = h;
    }

    /** Free the texture and the scratch memory: the menu has closed. */
    public static void release() {
        pending = false;
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
        width = 0;
        height = 0;
        readBack = null;
        full = null;
        quarter = null;
        upload = null;
    }

    /** Development only: keep the unprocessed frame as a curated-capture source. */
    private static void saveSource(MinecraftClient client, int[] argb, int w, int h) {
        try {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, w, h, argb, 0, w);
            File out = new File(new File(client.runDirectory, "screenshots"), "fathom-backdrop-source.png");
            out.getParentFile().mkdirs();
            ImageIO.write(image, "png", out);
            System.out.println("[Fathom] Backdrop source saved: " + out.getAbsolutePath());
        } catch (Exception failure) {
            System.out.println("[Fathom] Backdrop source not saved: " + failure);
        }
    }
}
