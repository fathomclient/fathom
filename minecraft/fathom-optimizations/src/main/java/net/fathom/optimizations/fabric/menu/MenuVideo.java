package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

/** Buffered, independently clocked presentation of the prepared menu footage. */
final class MenuVideo {
    private static final String[] QUALITY_CHOICES = {"High", "Auto", "Low", "Off"};

    private static final String ROOT =
            "/assets/fathom-optimizations/textures/menu/bg_5rain/";
    private static final int QUEUE_DEPTH = 5;
    private static final int AUTO_WINDOW = 150;
    private static final int AUTO_MISS_LIMIT = 20;
    private static final int WARMUP_PRESENTATIONS = 30;
    private static final Metadata METADATA = Metadata.load();

    private static volatile Session session;
    private static String configured = "";
    private static boolean adaptive;
    private static int texture = -1;
    private static int textureWidth;
    private static int textureHeight;
    private static int displayedIndex = -1;
    private static long uploadedFrames;
    private static long uploadNanos;
    private static long missedFrames;
    private static long playbackStartedNs;
    private static int autoAttempts;
    private static int autoMisses;
    private static long nextFrameAt;
    private static String failure;

    private MenuVideo() {}

    static void draw(MinecraftClient client, String quality) {
        String wanted = normalise(quality);
        if (!wanted.equals(configured)) {
            configured = wanted;
            adaptive = "Auto".equals(wanted);
            switchTo("Low".equals(wanted) ? Profile.LOW : Profile.HIGH);
        }
        if ("Off".equals(wanted)) {
            stop();
            MenuPoster.draw(client);
            return;
        }

        Session active = session;
        if (active == null) {
            switchTo("Low".equals(wanted) ? Profile.LOW : Profile.HIGH);
            active = session;
        }

        boolean focused = Display.isActive();
        active.paused = !focused;
        long now = System.nanoTime();
        if (!focused) {
            nextFrameAt = 0L;
            drawCurrent(client);
            return;
        }

        if (nextFrameAt == 0L) nextFrameAt = now;
        if (now >= nextFrameAt) {
            long cadenceSkips = (now - nextFrameAt) / METADATA.frameNanos;
            Frame frame = active.ready.poll();
            boolean missed = frame == null;
            if (!missed) {
                upload(frame);
                displayedIndex = frame.index;
                uploadedFrames++;
                active.free.offer(frame.pixels);
                if (playbackStartedNs == 0L) {
                    playbackStartedNs = now;
                    System.out.println("[Fathom] Menu video: " + active.profile.width + "x"
                            + active.profile.height + " @ " + METADATA.fps
                            + " fps (" + configured + ", " + QUEUE_DEPTH + "-frame buffer).");
                }
                if (uploadedFrames == WARMUP_PRESENTATIONS) {
                    playbackStartedNs = now;
                    missedFrames = 0L;
                }
            } else if (texture > 0) {
                missedFrames++;
            }
            // Preserve the source cadence. Scheduling from `now` accumulates
            // one render-frame of drift per video frame (30 becomes ~26 at a
            // 240 Hz UI); advancing the deadline keeps the two clocks separate.
            nextFrameAt += (cadenceSkips + 1L) * METADATA.frameNanos;
            if (cadenceSkips > 0L) missedFrames += cadenceSkips;

            if (adaptive && active.profile == Profile.HIGH) {
                autoAttempts++;
                if (missed) autoMisses++;
                if (autoAttempts >= AUTO_WINDOW) {
                    if (autoMisses > AUTO_MISS_LIMIT) {
                        System.out.println("[Fathom] Menu video Auto: sustained decode misses ("
                                + autoMisses + "/" + autoAttempts + "), using 720p.");
                        switchTo(Profile.LOW);
                    }
                    autoAttempts = 0;
                    autoMisses = 0;
                }
            }
        }
        drawCurrent(client);
    }

    private static void drawCurrent(MinecraftClient client) {
        if (texture > 0) MenuPoster.drawTexture(client, texture); else MenuPoster.draw(client);
    }

    static synchronized void stop() {
        Session active = session;
        session = null;
        if (active != null) {
            active.running = false;
            active.thread.interrupt();
        }
    }

    /** Stop decoding and release the streaming texture on a resource reload. */
    static synchronized void dispose() {
        stop();
        if (texture > 0) {
            GlStateManager.bindTexture(0);
            GL11.glDeleteTextures(texture);
        }
        texture = -1;
        textureWidth = 0;
        textureHeight = 0;
        displayedIndex = -1;
        configured = "";
        nextFrameAt = 0L;
    }

    static long uploadedFrames() {
        return uploadedFrames;
    }

    static String diagnostics() {
        Session active = session;
        long decoded = active == null ? 0L : active.decodedFrames;
        long decodeNs = active == null ? 0L : active.decodeNanos;
        double decodeMs = decoded == 0L ? 0d : decodeNs / 1_000_000d / decoded;
        double uploadMs = uploadedFrames == 0L ? 0d : uploadNanos / 1_000_000d / uploadedFrames;
        long elapsed = playbackStartedNs == 0L ? 0L : System.nanoTime() - playbackStartedNs;
        long measuredFrames = Math.max(0L, uploadedFrames - WARMUP_PRESENTATIONS);
        double presentedFps = elapsed <= 0L ? 0d : measuredFrames * 1_000_000_000d / elapsed;
        return String.format(Locale.ROOT,
                "%s, %.1f presented fps, %d misses, %.2f ms decode, %.2f ms upload%s",
                active == null ? configured : active.profile.label,
                presentedFps, missedFrames, decodeMs, uploadMs,
                failure == null ? "" : ", fallback: " + failure);
    }

    private static synchronized void switchTo(Profile profile) {
        Session old = session;
        if (old != null && old.profile == profile && old.running) return;
        if (old != null) {
            old.running = false;
            old.thread.interrupt();
        }
        failure = null;
        nextFrameAt = 0L;
        autoAttempts = 0;
        autoMisses = 0;
        Session fresh = new Session(profile);
        session = fresh;
        fresh.thread.start();
    }

    private static void upload(Frame frame) {
        long began = System.nanoTime();
        GlStateManager.enableTexture();
        if (texture <= 0) texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        if (textureWidth != frame.profile.width || textureHeight != frame.profile.height) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                    frame.profile.width, frame.profile.height, 0, GL12.GL_BGR,
                    GL11.GL_UNSIGNED_BYTE, frame.pixels);
            textureWidth = frame.profile.width;
            textureHeight = frame.profile.height;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                    frame.profile.width, frame.profile.height, GL12.GL_BGR,
                    GL11.GL_UNSIGNED_BYTE, frame.pixels);
        }
        uploadNanos += System.nanoTime() - began;
    }

    private static String normalise(String raw) {
        if (raw != null) {
            for (String choice : QUALITY_CHOICES) {
                if (choice.equalsIgnoreCase(raw.trim())) return choice;
            }
        }
        return "High";
    }

    private enum Profile {
        HIGH("High", "high", 1920, 1080), LOW("Low", "low", 1280, 720);

        final String label;
        final String directory;
        final int width;
        final int height;

        Profile(String label, String directory, int width, int height) {
            this.label = label;
            this.directory = directory;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Session implements Runnable {
        final Profile profile;
        final ArrayBlockingQueue<Frame> ready = new ArrayBlockingQueue<Frame>(QUEUE_DEPTH);
        final ArrayBlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<ByteBuffer>(QUEUE_DEPTH);
        final Thread thread;
        volatile boolean running = true;
        volatile boolean paused;
        volatile long decodedFrames;
        volatile long decodeNanos;

        Session(Profile profile) {
            this.profile = profile;
            int bytes = profile.width * profile.height * 3;
            for (int i = 0; i < QUEUE_DEPTH; i++) free.offer(ByteBuffer.allocateDirect(bytes));
            thread = new Thread(this, "Fathom menu video decoder");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        }

        @Override
        public void run() {
            int index = displayedIndex < 0 ? 0 : (displayedIndex + 1) % METADATA.frames;
            ImageIO.setUseCache(false);
            try {
                while (running && session == this) {
                    if (paused || ready.remainingCapacity() == 0) {
                        Thread.sleep(paused ? 20L : 1L);
                        continue;
                    }
                    ByteBuffer pixels = free.poll(100L, TimeUnit.MILLISECONDS);
                    if (pixels == null) continue;
                    long began = System.nanoTime();
                    BufferedImage image = decode(index);
                    if (!copy(image, pixels, profile)) {
                        free.offer(pixels);
                        fail(this, "missing or invalid " + profile.label + " frame " + index);
                        return;
                    }
                    decodedFrames++;
                    decodeNanos += System.nanoTime() - began;
                    if (!ready.offer(new Frame(profile, index, pixels))) free.offer(pixels);
                    index = (index + 1) % METADATA.frames;
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException broken) {
                fail(this, broken.getMessage() == null
                        ? broken.getClass().getSimpleName() : broken.getMessage());
            } finally {
                if (reader != null) {
                    reader.dispose();
                }
            }
        }

        /*
         * One JPEG reader and one destination image for the whole session.
         *
         * `ImageIO.read` builds a new 1920x1080 BGR image for every frame: 6 MB
         * a frame, 30 times a second, on this thread. On G1 each is a humongous
         * allocation, and the soak benchmark counted 449 collections in 90 seconds
         * on the main menu. Decoding into the same raster removes that entirely;
         * the copy into the upload buffer below is unchanged.
         */
        private javax.imageio.ImageReader reader;
        private BufferedImage destination;
        private boolean reuseUnsupported;

        private BufferedImage decode(int index) throws IOException {
            if (reuseUnsupported) {
                return read(profile, index);
            }
            String path = ROOT + profile.directory + "/"
                    + String.format(Locale.ROOT, "frame_%03d.jpg", index);
            try (InputStream stream = MenuVideo.class.getResourceAsStream(path)) {
                if (stream == null) {
                    return null;
                }
                javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(stream);
                try {
                    if (reader == null) {
                        java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
                        if (!readers.hasNext()) {
                            return null;
                        }
                        reader = readers.next();
                    }
                    reader.setInput(input, true, true);
                    javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                    if (destination != null) {
                        param.setDestination(destination);
                    }
                    BufferedImage decoded = reader.read(0, param);
                    if (destination == null && decoded.getType() == BufferedImage.TYPE_3BYTE_BGR) {
                        destination = decoded;
                    }
                    return decoded;
                } catch (javax.imageio.IIOException incompatible) {
                    // A reader that refuses the destination: fall back to a fresh
                    // image per frame, which is what this did before.
                    reuseUnsupported = true;
                    return read(profile, index);
                } finally {
                    input.close();
                }
            }
        }
    }

    private static BufferedImage read(Profile profile, int index) throws IOException {
        String path = ROOT + profile.directory + "/"
                + String.format(Locale.ROOT, "frame_%03d.jpg", index);
        try (InputStream stream = MenuVideo.class.getResourceAsStream(path)) {
            return stream == null ? null : ImageIO.read(stream);
        }
    }

    private static boolean copy(BufferedImage image, ByteBuffer pixels, Profile profile) {
        if (image == null || image.getWidth() != profile.width || image.getHeight() != profile.height
                || image.getType() != BufferedImage.TYPE_3BYTE_BGR
                || !(image.getRaster().getDataBuffer() instanceof DataBufferByte)
                || !(image.getRaster().getSampleModel() instanceof ComponentSampleModel)) return false;
        DataBufferByte data = (DataBufferByte) image.getRaster().getDataBuffer();
        ComponentSampleModel sample = (ComponentSampleModel) image.getRaster().getSampleModel();
        byte[] source = data.getData();
        int offset = data.getOffset();
        int stride = sample.getScanlineStride();
        pixels.clear();
        if (stride == profile.width * 3) {
            pixels.put(source, offset, profile.width * profile.height * 3);
        } else {
            for (int y = 0; y < profile.height; y++) {
                pixels.put(source, offset + y * stride, profile.width * 3);
            }
        }
        pixels.flip();
        return true;
    }

    private static synchronized void fail(Session failed, String reason) {
        if (session != failed) return;
        failure = reason;
        failed.running = false;
        System.err.println("[Fathom] Animated main-menu background unavailable: " + reason);
    }

    private static final class Frame {
        final Profile profile;
        final int index;
        final ByteBuffer pixels;
        Frame(Profile profile, int index, ByteBuffer pixels) {
            this.profile = profile;
            this.index = index;
            this.pixels = pixels;
        }
    }

    private static final class Metadata {
        final int fps;
        final int frames;
        final long frameNanos;
        Metadata(int fps, int frames) {
            this.fps = Math.max(1, Math.min(60, fps));
            this.frames = Math.max(1, frames);
            this.frameNanos = 1_000_000_000L / this.fps;
        }
        static Metadata load() {
            Properties values = new Properties();
            try (InputStream stream = MenuVideo.class.getResourceAsStream(ROOT + "video.properties")) {
                if (stream != null) values.load(stream);
                return new Metadata(Integer.parseInt(values.getProperty("fps", "30")),
                        Integer.parseInt(values.getProperty("frames", "300")));
            } catch (IOException | NumberFormatException ignored) {
                return new Metadata(30, 300);
            }
        }
    }
}
