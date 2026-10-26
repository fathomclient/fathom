package net.fathom.optimizations.fabric.gfx;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.text.FontAtlas;

/**
 * The glyph atlas on the GPU: {@code assets/fathom/font/atlas}, read and
 * uploaded once, the first time a Fathom screen opens.
 *
 * <p>There is no per-size anything. A distance field is drawn at any size and
 * any interface scale from the same texels, so a changed scale, a window
 * resize or a panel zooming in rebuilds nothing -- the old renderer threw
 * every rasterised page away on each of those.
 */
final class SdfText {

    private static final String ROOT = "/assets/fathom/font/atlas/";

    private FontAtlas atlas;
    private final FontAtlas.Face[] faces = new FontAtlas.Face[Type.Weight.values().length];
    private int texture;
    private float inverseWidth;
    private float inverseHeight;

    /** The atlas as read from the jar: metrics, and the field's texels ready to upload. */
    private static final class Prepared {
        final FontAtlas atlas;
        final ByteBuffer pixels;
        final int width;
        final int height;

        Prepared(FontAtlas atlas, ByteBuffer pixels, int width, int height) {
            this.atlas = atlas;
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    /** The read in flight or finished and not yet uploaded. Guarded by the class. */
    private static java.util.concurrent.Future<Prepared> pending;

    /**
     * Start reading and decoding the atlas on the asset thread, if no read is
     * already waiting to be used. Touches no OpenGL, so it may be called from
     * anywhere. The read is consumed by the next {@link #create}, which then
     * only uploads; without it, {@code create} reads inline as it always did.
     */
    static synchronized void prepareAsync() {
        if (pending == null) {
            pending = AssetPrep.submit(new java.util.concurrent.Callable<Prepared>() {
                @Override
                public Prepared call() throws IOException {
                    return read();
                }
            });
        }
    }

    private static synchronized Prepared take() throws IOException {
        java.util.concurrent.Future<Prepared> ahead = pending;
        pending = null;
        if (ahead != null) {
            try {
                return ahead.get();
            } catch (Exception failed) {
                // Fall through and read it here: the same files, and the same
                // IOException if they really are unreadable.
            }
        }
        return read();
    }

    /** The slow half: the jar's two files, parsed and decoded. Pure; safe on any thread. */
    private static Prepared read() throws IOException {
        InputStream metrics = SdfText.class.getResourceAsStream(ROOT + "ui.atlas");
        if (metrics == null) {
            throw new IOException("ui.atlas is missing from the mod");
        }
        FontAtlas parsed;
        try {
            parsed = FontAtlas.read(metrics);
        } finally {
            metrics.close();
        }
        InputStream png = SdfText.class.getResourceAsStream(ROOT + "ui.png");
        if (png == null) {
            throw new IOException("ui.png is missing from the mod");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(png);
        } finally {
            png.close();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width != parsed.width || height != parsed.height) {
            throw new IOException("ui.png is " + width + "x" + height + ", ui.atlas expects "
                    + parsed.width + "x" + parsed.height);
        }
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        for (int value : argb) {
            pixels.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
        }
        pixels.flip();
        return new Prepared(parsed, pixels, width, height);
    }

    /** Take the atlas (read ahead if it was) and upload it. Throws if either file is missing or unreadable. */
    void create() throws IOException {
        if (texture != 0) {
            return;
        }
        Prepared ready = take();
        atlas = ready.atlas;
        faces[Type.Weight.REGULAR.ordinal()] = require("regular");
        faces[Type.Weight.MEDIUM.ordinal()] = require("medium");
        faces[Type.Weight.SEMIBOLD.ordinal()] = require("semibold");
        faces[Type.Weight.MONO_REGULAR.ordinal()] = require("mono-regular");
        faces[Type.Weight.MONO_MEDIUM.ordinal()] = require("mono-medium");

        int width = ready.width;
        int height = ready.height;
        texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, ready.pixels);
        // Linear: the field is meant to be interpolated. No mipmaps: averaging
        // distances across a corner would break the channels' agreement.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        inverseWidth = 1f / width;
        inverseHeight = 1f / height;
    }

    private FontAtlas.Face require(String name) throws IOException {
        FontAtlas.Face face = atlas.face(name);
        if (face == null) {
            throw new IOException("ui.atlas has no face " + name);
        }
        return face;
    }

    boolean ready() {
        return texture != 0;
    }

    int texture() {
        return texture;
    }

    /** Whether the faces are loaded, so a string can be measured outside a frame. */
    boolean loaded() {
        return faces[0] != null;
    }

    FontAtlas.Face face(Type type) {
        return faces[type.weight().ordinal()];
    }

    /** The field's 0..1 span in screen pixels for text {@code pixelSize} pixels per em. */
    float screenRange(float pixelSize) {
        return atlas.distanceRange * pixelSize / atlas.pixelsPerEm;
    }

    /** One atlas texel of distance, in the field's 0..1 units: how far the median may stray from the truth. */
    float trueSlack() {
        return atlas == null ? 1f : 1f / atlas.distanceRange;
    }

    float u(float atlasPixel) {
        return atlasPixel * inverseWidth;
    }

    float v(float atlasPixel) {
        return atlasPixel * inverseHeight;
    }

    void delete() {
        if (texture != 0) {
            GlStateManager.deleteTexture(texture);
            texture = 0;
        }
    }
}
