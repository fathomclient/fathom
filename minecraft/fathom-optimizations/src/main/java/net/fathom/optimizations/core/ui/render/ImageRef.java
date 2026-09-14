package net.fathom.optimizations.core.ui.render;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An image the interface can draw: where its pixels come from, not the pixels.
 *
 * <h2>Why a reference and not a texture</h2>
 *
 * <p>Interface code names the image it wants -- a piece of mod artwork, an
 * avatar, a scene still -- and the renderer decides when to decode it, when to
 * upload it, and when to let it go. A reference is cheap and long-lived; the GPU
 * copy behind it is cached by the renderer, uploaded once, and evicted when the
 * image has not been drawn for a while.
 *
 * <h2>Three sources</h2>
 *
 * <ul>
 *   <li>{@link #resource} -- a PNG or JPEG on the mod's classpath. Decoded off
 *       the render thread, so the first frame that asks for it does not stall;
 *       it simply is not drawn until it is ready. Interned by path, so calling
 *       this every frame allocates nothing.
 *   <li>{@link #texture} -- a GL texture something else already owns (a skin, a
 *       video frame). Wrapped, never copied, never deleted by the renderer.
 *   <li>{@link #pixels} -- RGBA bytes the caller produces and may change. Call
 *       {@link #update} with new pixels and the renderer re-uploads them once,
 *       on the next frame that draws the image.
 * </ul>
 */
public final class ImageRef {

    public enum Source {
        RESOURCE,
        TEXTURE,
        PIXELS
    }

    /** Where a renderer's copy of an image is up to. */
    public enum State {
        /** Not requested yet, or evicted. */
        UNLOADED,
        /** Being decoded or waiting for upload. */
        LOADING,
        READY,
        /** Missing or undecodable. Not retried until {@link #update} or a release. */
        FAILED
    }

    private static final ConcurrentHashMap<String, ImageRef> RESOURCES =
            new ConcurrentHashMap<String, ImageRef>();

    private final Source source;
    private final String path;
    private final int textureId;
    private final boolean smooth;
    private final boolean mipmaps;

    private volatile int width;
    private volatile int height;
    private volatile ByteBuffer pixels;
    private volatile int version;

    private ImageRef(Source source, String path, int textureId, int width, int height,
            boolean smooth, boolean mipmaps) {
        this.source = source;
        this.path = path;
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.smooth = smooth;
        this.mipmaps = mipmaps;
    }

    /**
     * An image on the classpath, e.g. {@code "/assets/fathom/textures/x.png"}.
     *
     * <p>Smoothly filtered, with mipmaps so it stays clean drawn smaller than it
     * is -- the usual case for artwork and thumbnails.
     */
    public static ImageRef resource(String classpathPath) {
        if (classpathPath == null || classpathPath.isEmpty()) {
            throw new IllegalArgumentException("an image resource needs a path");
        }
        ImageRef existing = RESOURCES.get(classpathPath);
        if (existing != null) {
            return existing;
        }
        String absolute = classpathPath.startsWith("/") ? classpathPath : "/" + classpathPath;
        ImageRef created = new ImageRef(Source.RESOURCE, absolute, 0, 0, 0, true, true);
        ImageRef raced = RESOURCES.putIfAbsent(classpathPath, created);
        return raced == null ? created : raced;
    }

    /** A GL texture owned elsewhere. The renderer never deletes it. */
    public static ImageRef texture(int glTextureId, int width, int height, boolean smooth) {
        if (glTextureId <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("not a texture: " + glTextureId + " " + width + "x" + height);
        }
        return new ImageRef(Source.TEXTURE, null, glTextureId, width, height, smooth, false);
    }

    /** Caller-supplied RGBA pixels; nothing is drawn until the first {@link #update}. */
    public static ImageRef pixels(boolean smooth) {
        return new ImageRef(Source.PIXELS, null, 0, 0, 0, smooth, false);
    }

    /**
     * New pixels for a {@link Source#PIXELS} image: {@code width × height × 4}
     * bytes of straight-alpha RGBA, top row first, in a direct buffer.
     *
     * <p>The buffer is read on the render thread at the next frame that draws
     * this image, so it must not be changed or freed until then.
     */
    public void update(ByteBuffer rgba, int newWidth, int newHeight) {
        if (source != Source.PIXELS) {
            throw new IllegalStateException("only pixel images can be updated");
        }
        if (rgba == null || !rgba.isDirect() || newWidth <= 0 || newHeight <= 0
                || rgba.remaining() < newWidth * newHeight * 4) {
            throw new IllegalArgumentException("need " + newWidth + "x" + newHeight
                    + " RGBA bytes in a direct buffer");
        }
        width = newWidth;
        height = newHeight;
        pixels = rgba;
        version++;
    }

    public Source source() {
        return source;
    }

    /** The classpath path, for a resource. */
    public String path() {
        return path;
    }

    public int textureId() {
        return textureId;
    }

    public boolean smooth() {
        return smooth;
    }

    public boolean mipmaps() {
        return mipmaps;
    }

    /** Known size in pixels; 0 for a resource until a renderer has decoded it. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** For a renderer: a resource's size, once decoded. */
    public void decoded(int decodedWidth, int decodedHeight) {
        if (source == Source.RESOURCE) {
            width = decodedWidth;
            height = decodedHeight;
        }
    }

    /** For a renderer: the current pixels of a pixel image. */
    public ByteBuffer pixelData() {
        return pixels;
    }

    /** For a renderer: bumps on every {@link #update}. */
    public int version() {
        return version;
    }

    @Override
    public String toString() {
        switch (source) {
            case RESOURCE:
                return "ImageRef[" + path + "]";
            case TEXTURE:
                return "ImageRef[texture " + textureId + " " + width + "x" + height + "]";
            default:
                return "ImageRef[pixels " + width + "x" + height + " v" + version + "]";
        }
    }
}
