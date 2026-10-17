package net.fathom.optimizations.fabric.gfx;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * A texture full of small pictures, packed as they are asked for.
 *
 * <h2>Why one texture</h2>
 *
 * <p>A draw call ends every time the bound texture changes. Glyphs, icons and
 * the shapes that make a rounded corner are all small, all needed in the same
 * frame, and there are hundreds of them — so they share a sheet and the whole
 * hub draws in a handful of calls rather than one per letter.
 *
 * <h2>Shelf packing</h2>
 *
 * <p>Rows, filled left to right, a new row when the current one runs out. Not
 * the tightest packing there is; it is the one whose failure mode is wasted
 * space rather than a bug, and everything going in is roughly the same height,
 * which is the case shelves are good at.
 *
 * <h2>Power of two</h2>
 *
 * <p>Deliberately, even though every driver Minecraft 1.8.9 runs on supports
 * non-power-of-two textures by now. It costs nothing here and it removes an
 * entire class of "works on my machine".
 */
final class Atlas {

    /**
     * Something that has to happen before this sheet is written to.
     *
     * <p>Uploading binds a texture, and the renderer batches geometry against
     * whatever it last bound. A glyph rasterised in the middle of a frame — the
     * first time an accented character in a world name is drawn, say — would
     * therefore change the binding underneath a batch that was still open, and
     * every quad waiting in it would be drawn with the wrong sheet. That is the
     * "corrupt quadrant" failure, and it is a class of bug rather than an
     * instance: it appears only on the frame a new glyph or icon first shows up.
     *
     * <p>So the sheet flushes the batch before it writes. It costs one extra
     * draw call on the frame a raster is created and nothing on any other.
     */
    interface BeforeUpload {
        void flush();
    }

    /** Transparent margin around every entry, so bilinear sampling cannot bleed. */
    static final int PADDING = 1;

    private final BeforeUpload beforeUpload;
    private final int size;
    private int texture = -1;
    private int shelfX;
    private int shelfY;
    private int shelfHeight;
    private boolean full;

    Atlas(int size, BeforeUpload beforeUpload) {
        this.size = size;
        this.beforeUpload = beforeUpload;
    }

    private void flushFirst() {
        if (beforeUpload != null) {
            beforeUpload.flush();
        }
    }

    int texture() {
        return texture;
    }

    int size() {
        return size;
    }

    /** Whether an allocation has failed since this atlas was created. */
    boolean full() {
        return full;
    }

    /**
     * Reserve a rectangle, growing the current shelf or starting a new one.
     *
     * @return {@code {x, y}} of the top-left corner, or null when the sheet is
     *     full — the caller draws nothing rather than drawing the wrong thing.
     */
    int[] allocate(int width, int height) {
        int w = width + PADDING * 2;
        int h = height + PADDING * 2;
        if (w > size || h > size) {
            full = true;
            return null;
        }
        if (shelfX + w > size) {
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + h > size) {
            full = true;
            return null;
        }
        int[] at = new int[] {shelfX + PADDING, shelfY + PADDING};
        shelfX += w;
        if (h > shelfHeight) {
            shelfHeight = h;
        }
        return at;
    }

    /** Keep the dynamic shelf allocator below fixed entries written by Shapes. */
    void reserveRows(int rows) {
        shelfX = 0;
        shelfY = Math.max(shelfY, Math.max(0, rows));
        shelfHeight = 0;
    }

    /** Create the GL texture, empty and transparent. */
    void create() {
        if (texture != -1) {
            return;
        }
        flushFirst();
        texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        ByteBuffer empty = BufferUtils.createByteBuffer(size * size * 4);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, empty);
        // Linear, because a rounded corner is sampled between texels and a
        // glyph drawn at a fractional position would otherwise crawl. There are
        // no mipmaps: nothing here is ever minified.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
    }

    /** Upload an image into a reserved rectangle. */
    void upload(BufferedImage image, int x, int y) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        int[] row = new int[w];
        for (int line = 0; line < h; line++) {
            image.getRGB(0, line, w, 1, row, 0, w);
            for (int column = 0; column < w; column++) {
                int argb = row[column];
                pixels.put((byte) ((argb >> 16) & 0xFF));
                pixels.put((byte) ((argb >> 8) & 0xFF));
                pixels.put((byte) (argb & 0xFF));
                pixels.put((byte) ((argb >>> 24) & 0xFF));
            }
        }
        pixels.flip();
        flushFirst();
        GlStateManager.bindTexture(texture);
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
    }

    /** Upload raw coverage, as white pixels with the given alpha. */
    void uploadCoverage(byte[] alpha, int x, int y, int w, int h) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        for (int index = 0; index < w * h; index++) {
            pixels.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put(alpha[index]);
        }
        pixels.flip();
        flushFirst();
        GlStateManager.bindTexture(texture);
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
    }

    /** Texture coordinate for a pixel column. */
    float u(float pixel) {
        return pixel / size;
    }

    /** Texture coordinate for a pixel row. */
    float v(float pixel) {
        return pixel / size;
    }

    void delete() {
        if (texture != -1) {
            flushFirst();
            GlStateManager.deleteTexture(texture);
            texture = -1;
        }
        shelfX = 0;
        shelfY = 0;
        shelfHeight = 0;
        full = false;
    }
}
