package net.fathom.optimizations.fabric.gfx;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.anim.Clock;

/**
 * The renderer, the clock and the frame measurements — one of each.
 *
 * <h2>Nothing happens until something draws</h2>
 *
 * <p>The renderer is created the first time a Fathom screen opens, and its
 * clock only advances when a frame is drawn. There is no thread, no tick hook
 * and no timer: with the hub closed, this class costs one null check per
 * screen open and nothing else at all. That is the "closed GUI adds negligible
 * overhead" requirement, met by construction rather than by measurement.
 *
 * <h2>Why a singleton</h2>
 *
 * <p>The atlases are the expensive thing — a megabyte or two of rasterised
 * glyphs and icons — and every Fathom screen wants the same ones. One renderer
 * means the hub, the HUD editor and the spike share a texture and a font cache,
 * and switching between them costs nothing.
 */
public final class Gfx {

    private static GlRenderer renderer;
    private static Clock clock;
    private static final Frames FRAMES = new Frames();
    /** Menu background isolation runs every frame; retain its snapshot too. */
    private static final GlStateSnapshot ISOLATED_STATE = new GlStateSnapshot();
    private static boolean isolated;

    private Gfx() {}

    /** The renderer, created on first use. */
    public static GlRenderer renderer(MinecraftClient client) {
        if (renderer == null) {
            renderer = new GlRenderer(client);
            clock();
        }
        return renderer;
    }

    /** The renderer, if one exists. Never creates one. */
    public static GlRenderer peek() {
        return renderer;
    }

    /** The animation clock, created on first use whichever renderer draws. */
    public static Clock clock() {
        if (clock == null) {
            clock = new Clock(System.nanoTime());
        }
        return clock;
    }

    /** Frame-time measurements for whatever is currently drawing. */
    public static Frames frames() {
        return FRAMES;
    }

    /**
     * Begin a Fathom frame: advance the clock, take the GL state.
     *
     * @return seconds since the last Fathom frame, clamped
     */
    /**
     * When the current Fathom screen's drawing began, for the profiler.
     *
     * <p>Held here rather than in each screen because there is exactly one
     * screen drawing at a time, and three screens each remembering their own
     * would be three chances to leave a section open.
     */
    private static long screenProbe;

    public static float begin(MinecraftClient client, float scalePreference) {
        screenProbe = net.fathom.optimizations.core.instrument.Probe.start();
        GlRenderer active = renderer(client);
        active.setLowercase(false);
        active.setHighQuality(net.fathom.optimizations.fabric.Runtime.highQualityUi());
        float delta = clock().advance(System.nanoTime());
        FRAMES.begin();
        active.beginFrame(scalePreference);
        return delta;
    }

    /** What {@link #beginScreen} opened, so {@link #endScreen} closes the same thing. */
    private static final boolean FORCE_GL_RENDERER = Boolean.getBoolean("fathom.forceGlRenderer");
    private static boolean vectorOpen;
    private static boolean glOpen;
    private static float screenDelta;
    /** The renderer {@link #beginScreen} returned, for the geometry debug overlay. */
    private static net.fathom.optimizations.core.ui.render.Renderer screenRenderer;

    /**
     * Begin a Fathom screen frame on the best renderer available.
     *
     * <p>The vector renderer ({@link Canvas}, NanoVG) when it can start, otherwise
     * the GL renderer, with the same clock, frame measurements and profiler
     * section either way. Callers draw through the returned {@code Renderer} and
     * never learn which one it is.
     */
    public static net.fathom.optimizations.core.ui.render.Renderer beginScreen(
            MinecraftClient client, float scalePreference) {
        if (vectorOpen || glOpen) {
            throw new IllegalStateException("a Fathom screen frame is already open");
        }
        if (!FORCE_GL_RENDERER) {
            net.fathom.optimizations.core.ui.render.FathomRenderer vector =
                    Canvas.begin(client, net.fathom.optimizations.core.ui.render.FrameScale.Mode.FATHOM);
            if (vector != null) {
                screenProbe = net.fathom.optimizations.core.instrument.Probe.start();
                screenDelta = clock().advance(System.nanoTime());
                FRAMES.begin();
                vectorOpen = true;
                screenRenderer = vector;
                return vector;
            }
        }
        screenDelta = begin(client, scalePreference);
        // Screens follow the launcher's capitalization; the HUD, which also
        // draws through begin(), is gameplay and keeps its own.
        renderer.setLowercase(!net.fathom.optimizations.fabric.menu.MenuScenes.standardCapitalization());
        glOpen = true;
        screenRenderer = renderer;
        return renderer;
    }

    /** Seconds since the previous screen frame, clamped, as of {@link #beginScreen}. */
    public static float screenDelta() {
        return screenDelta;
    }

    /** Whether the frame {@link #beginScreen} opened is the vector renderer's. */
    public static boolean vectorFrame() {
        return vectorOpen;
    }

    /** Close what {@link #beginScreen} opened. Safe to call when nothing is open. */
    public static void endScreen() {
        // Developer-only (-Dfathom.debugGeometry=true): the frame's boxes, on top.
        if (net.fathom.optimizations.core.ui.render.GeometryDebug.ENABLED
                && (glOpen || vectorOpen) && screenRenderer != null) {
            net.fathom.optimizations.core.ui.render.GeometryDebug.drawAndClear(screenRenderer);
        }
        screenRenderer = null;
        if (glOpen) {
            glOpen = false;
            end();
            return;
        }
        if (!vectorOpen) {
            return;
        }
        vectorOpen = false;
        try {
            Canvas.end();
            net.fathom.optimizations.fabric.ui.HoverLatency.frameRendered(clock().frames());
        } finally {
            net.fathom.optimizations.core.instrument.Probe.stop(
                    net.fathom.optimizations.core.instrument.Probe.Section.SCREEN, screenProbe);
            screenProbe = 0L;
            FRAMES.end();
        }
    }

    /** End it: flush, put the state back, record what it cost. */
    public static void end() {
        try {
            if (renderer != null) {
                renderer.endFrame();
            }
            // After the flush, so "first rendered" means submitted rather than
            // "the last control finished drawing".
            net.fathom.optimizations.fabric.ui.HoverLatency.frameRendered(
                    clock == null ? 0L : clock.frames());
        } finally {
            net.fathom.optimizations.core.instrument.Probe.stop(
                    net.fathom.optimizations.core.instrument.Probe.Section.SCREEN, screenProbe);
            screenProbe = 0L;
            FRAMES.end();
        }
    }

    /**
     * Open a Fathom frame for something drawn inside the game's own pass.
     *
     * <p>For the HUD, which is not a screen. It takes the GL state and sets up
     * the renderer exactly as {@link #begin} does, and deliberately does two
     * things less: it does not advance the animation clock, and it does not
     * record a frame time.
     *
     * <p>Both omissions matter. The clock is what every screen's animations are
     * sampled from, and advancing it sixty times a second while nobody is
     * looking at a menu would mean every animation on the next screen opened
     * had already finished. The frame ring is what the Performance page calls
     * "Fathom UI", which is the cost of a Fathom <em>screen</em>; the HUD's own
     * cost is measured separately, as {@code Probe.Section.HUD}, and folding one
     * into the other would make both numbers mean nothing.
     *
     * @return the renderer with a frame open, or null when one is already open —
     *     the HUD editor draws the widgets from inside its own frame
     */
    public static GlRenderer beginOverlay(MinecraftClient client, float scalePreference) {
        GlRenderer active = renderer(client);
        if (active.inFrame()) {
            return null;
        }
        active.setHighQuality(net.fathom.optimizations.fabric.Runtime.highQualityUi());
        active.beginFrame(scalePreference);
        return active;
    }

    /** Close what {@link #beginOverlay} opened. Null is the "already open" case. */
    public static void endOverlay(GlRenderer opened) {
        if (opened != null) {
            opened.endFrame();
        }
    }

    /** Run a non-Fathom draw pass without leaking any GL state into the UI. */
    public static void isolated(Runnable draw) {
        if (isolated) {
            throw new IllegalStateException("nested isolated GL passes are not supported");
        }
        GlStateSnapshot before = ISOLATED_STATE.captureInto();
        isolated = true;
        try {
            draw.run();
        } finally {
            try {
                before.restore();
            } finally {
                isolated = false;
            }
        }
    }

    /**
     * Start animating from now.
     *
     * <p>For a screen opening. Without it the first frame carries however long
     * the interface was closed and every animation on it starts finished.
     */
    public static void restartClock() {
        if (clock != null) {
            clock.restart(System.nanoTime());
        }
    }

    /** Throw away every texture — for a resource reload. */
    public static void dispose() {
        // NanoVG may be the only backend a session created. Dispose both
        // resource owners so fonts, images and the vector context cannot leak.
        Canvas.dispose();
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
        // The clock is not a GPU resource. Preserve its monotonic timeline so
        // a resource reload cannot put live Motions' start times in the future.
    }

    /**
     * A ring of recent frame costs.
     *
     * <h2>Why percentiles and not an average</h2>
     *
     * <p>An average frame time cannot tell the difference between a menu that is
     * smooth and one that stutters twice a second, and stutter is the thing
     * people actually feel. The 95th and 99th percentiles can, which is why the
     * brief asked for them.
     */
    public static final class Frames {

        /** About four seconds at 60 fps. */
        private static final int WINDOW = 240;

        private final long[] uiNanos = new long[WINDOW];
        private final long[] frameNanos = new long[WINDOW];
        private final long[] sorted = new long[WINDOW];
        private int count;
        private int next;
        private long beganAt;
        private long lastFrameAt;

        void begin() {
            long now = System.nanoTime();
            if (lastFrameAt != 0L) {
                frameNanos[next] = now - lastFrameAt;
            }
            lastFrameAt = now;
            beganAt = now;
        }

        void end() {
            uiNanos[next] = System.nanoTime() - beganAt;
            next = (next + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
        }

        public void reset() {
            count = 0;
            next = 0;
            lastFrameAt = 0L;
        }

        public int samples() {
            return count;
        }

        /** Mean milliseconds spent inside Fathom's renderer. */
        public float uiMeanMillis() {
            return mean(uiNanos);
        }

        public float uiPercentileMillis(float fraction) {
            return percentile(uiNanos, fraction);
        }

        /** Mean milliseconds between frames — the whole game, not just us. */
        public float frameMeanMillis() {
            return mean(frameNanos);
        }

        public float framePercentileMillis(float fraction) {
            return percentile(frameNanos, fraction);
        }

        /** Frames per second implied by the mean frame time. */
        public float fps() {
            float mean = frameMeanMillis();
            return mean <= 0f ? 0f : 1000f / mean;
        }

        private float mean(long[] values) {
            if (count == 0) {
                return 0f;
            }
            long total = 0L;
            for (int index = 0; index < count; index++) {
                total += values[index];
            }
            return total / (float) count / 1_000_000f;
        }

        private float percentile(long[] values, float fraction) {
            if (count == 0) {
                return 0f;
            }
            System.arraycopy(values, 0, sorted, 0, count);
            java.util.Arrays.sort(sorted, 0, count);
            int index = Math.min(count - 1, Math.max(0, Math.round(fraction * (count - 1))));
            return sorted[index] / 1_000_000f;
        }
    }
}
