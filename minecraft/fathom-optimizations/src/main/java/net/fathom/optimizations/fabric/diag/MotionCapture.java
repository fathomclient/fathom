package net.fathom.optimizations.fabric.diag;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import net.minecraft.client.MinecraftClient;

/**
 * Development-only high-frame-rate capture for motion QA.
 *
 * <p>The framebuffer read stays on the render thread, then a single background
 * writer encodes half-size PNGs. Nothing is initialized unless a UI-tour step
 * named {@code motion:*} starts a sequence. Production therefore pays no hook,
 * allocation, thread, or readback cost.
 */
public final class MotionCapture {

    private static final int QUEUE_CAPACITY = 96;
    private static final ArrayBlockingQueue<Frame> QUEUE = new ArrayBlockingQueue<Frame>(QUEUE_CAPACITY);
    private static final Frame STOP = new Frame(null, null);

    private static ByteBuffer readback;
    private static Thread writer;
    private static File directory;
    private static long startedAt;
    private static long nextAt;
    private static long interval;
    private static int frame;
    private static int dropped;
    private static boolean active;
    private static boolean prepared;

    private MotionCapture() {}

    public static void start(MinecraftClient client, String label, int fps) {
        ensureWriter();
        String safe = label == null ? "motion" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        directory = new File(new File(client.runDirectory, "screenshots"), "motion-" + safe);
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
        File[] previous = directory.listFiles();
        if (previous != null) {
            for (File old : previous) {
                if (old.isFile() && (old.getName().matches("\\d{4}-\\d{4}ms\\.png")
                        || "contact-sheet.png".equals(old.getName()))) {
                    old.delete();
                }
            }
        }
        startedAt = System.nanoTime();
        nextAt = startedAt;
        interval = 1_000_000_000L / Math.max(1, Math.min(240, fps));
        frame = 0;
        dropped = 0;
        active = true;
    }

    /** Pay the development recorder's own allocation/JIT cost before timing motion. */
    public static void prepare(MinecraftClient client) {
        if (prepared || client == null) {
            return;
        }
        ensureWriter();
        snapshot(client);
        prepared = true;
    }

    /** Capture the current completed framebuffer when the next sample is due. */
    public static void frame(MinecraftClient client) {
        if (!active || client == null) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextAt) {
            return;
        }
        // Do not burst after a slow frame: one rendered state can produce at
        // most one recorded frame, and its filename carries the real timestamp.
        nextAt = now + interval;
        BufferedImage image = snapshot(client);
        long millis = (now - startedAt) / 1_000_000L;
        File output = new File(directory, String.format(Locale.ROOT, "%04d-%04dms.png", frame++, millis));
        if (!QUEUE.offer(new Frame(output, image))) {
            dropped++;
        }
    }

    public static void finish() {
        if (!active) {
            return;
        }
        active = false;
        System.out.println("[Fathom] Motion capture: " + directory.getAbsolutePath() + ", " + frame
                + " sampled, " + dropped + " dropped.");
    }

    /** Drain every PNG before the scripted client exits. */
    public static void shutdown() {
        finish();
        if (writer == null) {
            return;
        }
        try {
            QUEUE.put(STOP);
            writer.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        writer = null;
        readback = null;
        prepared = false;
    }

    private static BufferedImage snapshot(MinecraftClient client) {
        int width = Math.max(1, client.width);
        int height = Math.max(1, client.height);
        int bytes = width * height * 4;
        if (readback == null || readback.capacity() < bytes) {
            readback = BufferUtils.createByteBuffer(bytes).order(ByteOrder.nativeOrder());
        }
        readback.clear();
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, readback);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);

        int outWidth = Math.max(1, width / 2);
        int outHeight = Math.max(1, height / 2);
        BufferedImage image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        IntBuffer source = readback.asIntBuffer();
        for (int y = 0; y < outHeight; y++) {
            int sourceY = height - 1 - Math.min(height - 1, y * 2);
            int sourceRow = sourceY * width;
            int targetRow = y * outWidth;
            for (int x = 0; x < outWidth; x++) {
                target[targetRow + x] = source.get(sourceRow + Math.min(width - 1, x * 2));
            }
        }
        return image;
    }

    private static void ensureWriter() {
        if (writer != null) {
            return;
        }
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (;;) {
                    try {
                        Frame next = QUEUE.take();
                        if (next == STOP) {
                            return;
                        }
                        ImageIO.write(next.image, "png", next.file);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception failure) {
                        System.out.println("[Fathom] Motion frame not saved: " + failure);
                    }
                }
            }
        }, "fathom-motion-capture");
        writer.setDaemon(true);
        writer.start();
    }

    private static final class Frame {
        final File file;
        final BufferedImage image;

        Frame(File file, BufferedImage image) {
            this.file = file;
            this.image = image;
        }
    }
}
