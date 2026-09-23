package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * The signed-in player's head, as a GL texture.
 *
 * <p>The launcher cuts it out of the account's skin after sign-in and keeps it
 * at {@code accounts/<uuid>-head.png} in Fathom's data directory. The same file
 * is shown here, so the game menu and the launcher show the same face.
 *
 * <p>Decoded on a worker, uploaded on the render thread the next time it is
 * asked for, and re-read when the file changes — a skin changed while the game
 * is open shows up without a restart. No file means no head: the caller draws
 * its usual icon.
 */
final class AccountHead {

    private static final long RECHECK_MILLIS = 3000L;
    private static final int MAX_BYTES = 256 * 1024;

    private static String currentKey = "";
    private static int texture;
    private static long loadedModified;
    private static long lastCheck;
    private static volatile ByteBuffer pending;
    private static volatile int pendingSize;
    private static volatile long pendingModified;
    private static volatile boolean loading;

    private AccountHead() {}

    /**
     * The texture for this account, or 0 while there is none.
     *
     * @param root Fathom's data directory, or null
     * @param uuid the account's UUID, dashed or not
     */
    static int texture(File root, String uuid) {
        String compact = uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (root == null || !compact.matches("[0-9a-f]{32}")) {
            return 0;
        }
        final File file = new File(new File(root, "accounts"), compact + "-head.png");
        String key = file.getPath();
        if (!key.equals(currentKey)) {
            currentKey = key;
            release();
        }

        ByteBuffer ready = pending;
        if (ready != null) {
            pending = null;
            upload(ready, pendingSize);
            loadedModified = pendingModified;
        }

        long now = System.currentTimeMillis();
        if (!loading && now - lastCheck > RECHECK_MILLIS) {
            lastCheck = now;
            final long modified = file.isFile() ? file.lastModified() : 0L;
            if (modified == 0L) {
                release();
            } else if (modified != loadedModified) {
                loading = true;
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            decode(file, modified);
                        } finally {
                            loading = false;
                        }
                    }
                }, "fathom-account-head");
                worker.setDaemon(true);
                worker.start();
            }
        }
        return texture;
    }

    private static void decode(File file, long modified) {
        if (file.length() > MAX_BYTES) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null || image.getWidth() != image.getHeight()
                    || image.getWidth() < 8 || image.getWidth() > 512) {
                return;
            }
            int size = image.getWidth();
            ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb)
                            .put((byte) (argb >>> 24));
                }
            }
            pixels.flip();
            pendingSize = size;
            pendingModified = modified;
            pending = pixels;
        } catch (Exception unreadable) {
            // No head is the fallback, and the icon is already there for it.
        }
    }

    private static void upload(ByteBuffer pixels, int size) {
        GlStateManager.enableTexture();
        if (texture <= 0) {
            texture = GL11.glGenTextures();
        }
        GlStateManager.bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        // Eight pixels of art: nearest, or the face is a blur.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static void release() {
        if (texture > 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
        loadedModified = 0L;
        pending = null;
    }
}
