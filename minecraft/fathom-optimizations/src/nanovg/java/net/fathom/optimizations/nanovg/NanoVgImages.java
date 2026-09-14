package net.fathom.optimizations.nanovg;

import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_GENERATE_MIPMAPS;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_NEAREST;
import static org.lwjgl.nanovg.NanoVG.nvgCreateImageRGBA;
import static org.lwjgl.nanovg.NanoVG.nvgDeleteImage;
import static org.lwjgl.nanovg.NanoVG.nvgUpdateImage;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.imageio.ImageIO;

import org.lwjgl.nanovg.NanoVGGL2;

import net.fathom.optimizations.core.ui.render.ImageRef;

/**
 * The GPU copies of {@link ImageRef}s for one NanoVG context.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>The first draw of a resource image queues it on one background decoder
 *       thread and draws nothing. Decoding a 1080p JPEG on the render thread is
 *       a visible hitch; skipping one frame of an image is not.
 *   <li>A later frame finds the decoded pixels and uploads them, once. The
 *       Java-side copy is dropped straight after.
 *   <li>Every draw marks the entry used. An image not drawn for
 *       {@link #IDLE_NANOS} is deleted from the GPU at the start of a frame, and
 *       reloads if it is ever drawn again -- so scrolling past a hundred avatars
 *       does not keep a hundred textures alive.
 * </ol>
 *
 * <p>Wrapped GL textures are never copied and never deleted (NanoVG is told
 * {@code NVG_IMAGE_NODELETE}). Pixel images re-upload only when their version
 * changes. All GL work happens on the render thread, inside a frame.
 */
final class NanoVgImages {

    static final long IDLE_NANOS = 60L * 1_000_000_000L;
    private static final long SWEEP_EVERY_NANOS = 2L * 1_000_000_000L;

    private static final class Entry {
        final ImageRef ref;
        int id = -1;
        int width;
        int height;
        int uploadedVersion = -1;
        long lastUsed;
        volatile ImageRef.State state = ImageRef.State.UNLOADED;
        volatile ByteBuffer decoded;
        volatile int decodedWidth;
        volatile int decodedHeight;

        Entry(ImageRef ref) {
            this.ref = ref;
        }
    }

    private final long vg;
    private final Map<ImageRef, Entry> entries = new IdentityHashMap<ImageRef, Entry>();
    private ExecutorService decoder;
    private long lastSweep;

    NanoVgImages(long vg) {
        this.vg = vg;
    }

    /**
     * The NanoVG image for a reference, uploading whatever is ready; -1 if it
     * cannot be drawn this frame. {@code out[0..1]} receives its pixel size.
     */
    int resolve(ImageRef ref, long now, int[] size) {
        Entry entry = entries.get(ref);
        if (entry == null) {
            entry = new Entry(ref);
            entries.put(ref, entry);
        }
        entry.lastUsed = now;
        switch (ref.source()) {
            case RESOURCE:
                resolveResource(entry);
                break;
            case TEXTURE:
                resolveTexture(entry);
                break;
            default:
                resolvePixels(entry);
                break;
        }
        if (entry.id < 0) {
            return -1;
        }
        size[0] = entry.width;
        size[1] = entry.height;
        return entry.id;
    }

    ImageRef.State state(ImageRef ref) {
        Entry entry = entries.get(ref);
        return entry == null ? ImageRef.State.UNLOADED : entry.state;
    }

    void release(ImageRef ref) {
        Entry entry = entries.remove(ref);
        if (entry != null) {
            delete(entry);
        }
    }

    /** Start of a frame: let go of images nobody has drawn for a while. */
    void sweep(long now) {
        if (now - lastSweep < SWEEP_EVERY_NANOS) {
            return;
        }
        lastSweep = now;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.lastUsed > IDLE_NANOS && entry.state != ImageRef.State.LOADING) {
                delete(entry);
                iterator.remove();
            }
        }
    }

    int count() {
        return entries.size();
    }

    void dispose() {
        for (Entry entry : entries.values()) {
            delete(entry);
        }
        entries.clear();
        if (decoder != null) {
            decoder.shutdownNow();
            decoder = null;
        }
    }

    // -----------------------------------------------------------------------

    private int flags(ImageRef ref) {
        int flags = 0;
        if (!ref.smooth()) {
            flags |= NVG_IMAGE_NEAREST;
        }
        if (ref.mipmaps()) {
            flags |= NVG_IMAGE_GENERATE_MIPMAPS;
        }
        return flags;
    }

    private void resolveResource(final Entry entry) {
        if (entry.id >= 0 || entry.state == ImageRef.State.FAILED) {
            return;
        }
        if (entry.state == ImageRef.State.UNLOADED) {
            entry.state = ImageRef.State.LOADING;
            decoder().execute(new Runnable() {
                @Override
                public void run() {
                    decode(entry);
                }
            });
            return;
        }
        ByteBuffer pixels = entry.decoded;
        if (pixels == null) {
            return;
        }
        entry.decoded = null;
        int id = nvgCreateImageRGBA(vg, entry.decodedWidth, entry.decodedHeight, flags(entry.ref), pixels);
        if (id <= 0) {
            System.err.println("[Fathom] NanoVG: could not upload " + entry.ref);
            entry.state = ImageRef.State.FAILED;
            return;
        }
        entry.id = id;
        entry.width = entry.decodedWidth;
        entry.height = entry.decodedHeight;
        entry.ref.decoded(entry.width, entry.height);
        entry.state = ImageRef.State.READY;
    }

    /** On the decoder thread: pixels only, no GL. */
    private static void decode(Entry entry) {
        String path = entry.ref.path();
        try {
            InputStream stream = NanoVgImages.class.getResourceAsStream(path);
            if (stream == null) {
                throw new IllegalStateException("not on the classpath");
            }
            BufferedImage image;
            try {
                image = ImageIO.read(stream);
            } finally {
                stream.close();
            }
            if (image == null) {
                throw new IllegalStateException("not a readable PNG or JPEG");
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4);
            for (int pixel : argb) {
                rgba.put((byte) (pixel >> 16)).put((byte) (pixel >> 8)).put((byte) pixel)
                        .put((byte) (pixel >>> 24));
            }
            rgba.flip();
            entry.decodedWidth = w;
            entry.decodedHeight = h;
            entry.decoded = rgba;
        } catch (Exception | LinkageError problem) {
            System.err.println("[Fathom] NanoVG: image " + path + " failed: " + problem);
            entry.state = ImageRef.State.FAILED;
        }
    }

    private void resolveTexture(Entry entry) {
        if (entry.id >= 0 || entry.state == ImageRef.State.FAILED) {
            return;
        }
        ImageRef ref = entry.ref;
        int id = NanoVGGL2.nvglCreateImageFromHandle(vg, ref.textureId(), ref.width(), ref.height(),
                flags(ref) | NanoVGGL2.NVG_IMAGE_NODELETE);
        if (id <= 0) {
            System.err.println("[Fathom] NanoVG: could not wrap " + ref);
            entry.state = ImageRef.State.FAILED;
            return;
        }
        entry.id = id;
        entry.width = ref.width();
        entry.height = ref.height();
        entry.state = ImageRef.State.READY;
    }

    private void resolvePixels(Entry entry) {
        ImageRef ref = entry.ref;
        int version = ref.version();
        if (version == entry.uploadedVersion) {
            return;
        }
        ByteBuffer pixels = ref.pixelData();
        if (pixels == null) {
            return;
        }
        int w = ref.width();
        int h = ref.height();
        if (entry.id >= 0 && (w != entry.width || h != entry.height)) {
            nvgDeleteImage(vg, entry.id);
            entry.id = -1;
        }
        if (entry.id < 0) {
            int id = nvgCreateImageRGBA(vg, w, h, flags(ref), pixels);
            if (id <= 0) {
                entry.state = ImageRef.State.FAILED;
                entry.uploadedVersion = version;
                return;
            }
            entry.id = id;
            entry.width = w;
            entry.height = h;
        } else {
            nvgUpdateImage(vg, entry.id, pixels);
        }
        entry.uploadedVersion = version;
        entry.state = ImageRef.State.READY;
    }

    private void delete(Entry entry) {
        if (entry.id >= 0) {
            nvgDeleteImage(vg, entry.id);
            entry.id = -1;
        }
        entry.decoded = null;
        entry.state = ImageRef.State.UNLOADED;
    }

    private ExecutorService decoder() {
        if (decoder == null) {
            decoder = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "Fathom image decoder");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });
        }
        return decoder;
    }
}
