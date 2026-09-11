package net.fathom.optimizations.core.ui;

/**
 * How a logical unit becomes a pixel.
 *
 * <h2>The bug this type exists to prevent</h2>
 *
 * <p>Minecraft hands a screen a coordinate space of {@code scaledWidth ×
 * scaledHeight}, which at GUI scale 3 on a 1280×720 window is 427×240. Anything
 * drawn in that space is drawn at 240 lines and then magnified three times by
 * the projection. That is not a small quality problem: it is the reason the
 * first version of the hub had a pixelated bitmap font, aliased corners, and a
 * content pane that could not be made larger — there was no room in the
 * coordinate system for it to be larger.
 *
 * <p>So Fathom's interface is authored in <b>logical units</b> — call them dp —
 * and this class is the only thing that knows what a dp is worth. It answers
 * from the <em>framebuffer</em>, never from {@code scaleFactor}.
 *
 * <h2>Why the scale is fractional</h2>
 *
 * <p>Minecraft's own scale factor is an integer: 1, 2, 3 or 4 and nothing in
 * between, which is why its interface jumps in size as a window grows. A
 * renderer that rasterises glyphs at the device size and positions quads at
 * fractional coordinates has no reason to accept that, so this returns a real
 * number. 1.6 is a perfectly good scale.
 */
public final class UiScale {

    /** The height, in device pixels, at which one dp is one pixel. */
    public static final float REFERENCE_HEIGHT = 720f;

    /** Below this, the interface would not fit; above it, it would be silly. */
    public static final float MIN_SCALE = 0.75f;
    public static final float MAX_SCALE = 3.0f;

    /** What the user is allowed to ask for, as a multiplier on the automatic scale. */
    public static final float MIN_PREFERENCE = 0.75f;
    public static final float MAX_PREFERENCE = 1.5f;

    private final int framebufferWidth;
    private final int framebufferHeight;
    private final float scale;

    public UiScale(int framebufferWidth, int framebufferHeight, float preference) {
        this.framebufferWidth = Math.max(1, framebufferWidth);
        this.framebufferHeight = Math.max(1, framebufferHeight);
        this.scale = scaleFor(this.framebufferHeight, preference);
    }

    /**
     * The automatic scale for a framebuffer height, times what the user asked for.
     *
     * <p>Linear in the height rather than stepped, and clamped at both ends: a
     * 4K screen gets a 3× interface rather than a 5.3× one, and a small window
     * gets 0.75 rather than something unreadable.
     */
    public static float scaleFor(int framebufferHeight, float preference) {
        float wanted = clamp(preference, MIN_PREFERENCE, MAX_PREFERENCE);
        float automatic = Math.max(1, framebufferHeight) / REFERENCE_HEIGHT;
        return clamp(automatic * wanted, MIN_SCALE, MAX_SCALE);
    }

    /** Device pixels per logical unit. */
    public float scale() {
        return scale;
    }

    /** The interface's width, in logical units. */
    public float width() {
        return framebufferWidth / scale;
    }

    /** The interface's height, in logical units. */
    public float height() {
        return framebufferHeight / scale;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    /** A logical coordinate in device pixels. Fractional, deliberately. */
    public float toDevice(float logical) {
        return logical * scale;
    }

    /** A device coordinate — a mouse position, say — in logical units. */
    public float toLogical(float device) {
        return device / scale;
    }

    /**
     * The nearest whole device pixel, for something that has to land on one.
     *
     * <p>Used for scissor rectangles and hairlines, and nothing else. Rounding
     * positions in general is what makes an interface jitter as it animates.
     */
    public int snap(float logical) {
        return Math.round(logical * scale);
    }

    /**
     * Whether two scales differ enough to be worth rebuilding atlases for.
     *
     * <p>Glyphs are rasterised at the device size, so a changed scale means new
     * rasters. A window being dragged across a screen edge can produce a stream
     * of framebuffer sizes, and rebuilding an atlas for a difference nobody can
     * see would be a stutter for nothing.
     */
    public static boolean differs(float a, float b) {
        return Math.abs(a - b) > 0.01f;
    }

    private static float clamp(float value, float low, float high) {
        if (value != value) {
            // NaN. A scale of NaN would silently make the entire interface
            // vanish, which is a worse way to find out than a wrong size.
            return low;
        }
        return value < low ? low : (value > high ? high : value);
    }

    @Override
    public String toString() {
        return "UiScale[" + framebufferWidth + "x" + framebufferHeight + " @" + scale + "]";
    }
}
