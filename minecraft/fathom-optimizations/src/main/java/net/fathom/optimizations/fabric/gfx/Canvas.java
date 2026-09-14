package net.fathom.optimizations.fabric.gfx;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

import com.mojang.blaze3d.platform.GLX;

import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.FrameScale;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.nanovg.NanoVg;

/**
 * Opens and closes frames of the vector renderer. The only place that does.
 *
 * <pre>
 *   FathomRenderer ui = Canvas.begin(client, FrameScale.Mode.FATHOM);
 *   if (ui != null) {
 *       try {
 *           draw(ui);
 *       } finally {
 *           Canvas.end();
 *       }
 *   }
 * </pre>
 *
 * <h2>What a frame takes care of, so components never do</h2>
 *
 * <ul>
 *   <li><b>Size.</b> The real framebuffer Minecraft is drawing the GUI into, read
 *       every frame, so resizes and fullscreen changes need nothing.
 *   <li><b>Scale.</b> A pixel ratio from {@link FrameScale}: Fathom's continuous
 *       scale, or Minecraft's GUI scale factor for anything that has to line up
 *       with vanilla coordinates. Geometry and glyphs are rasterised at the
 *       framebuffer's resolution either way.
 *   <li><b>GL state.</b> {@link NanoVgGlGuard} saves everything NanoVG touches
 *       before the frame and puts it back after, including the caches in
 *       {@code GlStateManager}. No GL repair belongs anywhere else.
 *   <li><b>Failure.</b> If the backend cannot start -- no classpath, natives that
 *       will not load, a driver NanoVG refuses -- {@code begin} logs why once and
 *       returns null for the rest of the session. The caller draws nothing, or
 *       falls back to the GL renderer. A crash inside native code cannot be
 *       caught, so the first frame is bracketed by a marker file: if the game
 *       dies during it, the next start skips NanoVG and says so
 *       ({@code -Dfathom.nanovg.retry=true} to try again).
 *   <li><b>Exclusivity.</b> One frame at a time, never inside a
 *       {@link GlRenderer} frame: both batch, and interleaving them would draw
 *       out of order.
 * </ul>
 *
 * <p>Render thread only.
 */
public final class Canvas {

    private static final String RETRY_PROPERTY = "fathom.nanovg.retry";

    private static final FrameScale SCALE = new FrameScale();

    private static FathomRenderer open;
    private static NanoVgGlGuard guard;
    private static boolean proven;

    private Canvas() {}

    /**
     * Open a frame, or return null when the vector renderer is unavailable.
     *
     * @throws IllegalStateException if a frame of either renderer is already open:
     *                               that is a bug in the caller, not a runtime condition
     */
    public static FathomRenderer begin(MinecraftClient client, FrameScale.Mode mode) {
        if (open != null) {
            throw new IllegalStateException("a Canvas frame is already open");
        }
        GlRenderer gl = Gfx.peek();
        if (gl != null && gl.inFrame()) {
            throw new IllegalStateException("a GlRenderer frame is open; end it before opening a Canvas frame");
        }
        FathomRenderer renderer = NanoVg.renderer(client);
        if (renderer == null) {
            return null;
        }
        if (!proven && !armFirstFrameMarker(client)) {
            return null;
        }

        int fbWidth = framebufferWidth(client);
        int fbHeight = framebufferHeight(client);
        SCALE.set(mode, fbWidth, fbHeight,
                MinecraftGuiSize.get(client).scaleFactor(), Runtime.uiScale());

        NanoVgGlGuard taken = NanoVgGlGuard.open(fbWidth, fbHeight);
        try {
            renderer.beginFrame(fbWidth, fbHeight, SCALE.ratio());
        } catch (RuntimeException | LinkageError problem) {
            taken.close();
            // A Java-side failure is not the crash the marker is for.
            disarmFirstFrameMarker();
            NanoVg.fail("could not begin a frame", problem);
            return null;
        }
        open = renderer;
        guard = taken;
        return renderer;
    }

    /** Submit the frame and hand the GL state back. Safe to call when nothing is open. */
    public static void end() {
        finish(false);
    }

    /** Discard the frame's drawing and hand the GL state back. */
    public static void cancel() {
        finish(true);
    }

    private static void finish(boolean discard) {
        FathomRenderer renderer = open;
        NanoVgGlGuard taken = guard;
        if (renderer == null) {
            return;
        }
        open = null;
        guard = null;
        try {
            if (discard) {
                renderer.cancelFrame();
            } else {
                renderer.endFrame();
            }
            if (!proven) {
                proven = true;
                disarmFirstFrameMarker();
            }
        } catch (RuntimeException | LinkageError problem) {
            disarmFirstFrameMarker();
            NanoVg.fail("could not finish a frame", problem);
        } finally {
            taken.close();
        }
    }

    /** Whether a frame is open right now. */
    public static boolean inFrame() {
        return open != null;
    }

    /** Cancel any live frame, then release the vector context and its images/fonts. */
    public static void dispose() {
        if (open != null) {
            finish(true);
        }
        NanoVg.dispose();
    }

    /** The scale of the frame open now, or of the last one. */
    public static FrameScale scale() {
        return SCALE;
    }

    // -----------------------------------------------------------------------
    // Sizes
    // -----------------------------------------------------------------------

    /** The GUI pass draws into Minecraft's framebuffer when it has one, else the display. */
    private static int framebufferWidth(MinecraftClient client) {
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer != null && GLX.supportsFbo() && framebuffer.viewportWidth > 0) {
            return framebuffer.viewportWidth;
        }
        return Math.max(1, client.width);
    }

    private static int framebufferHeight(MinecraftClient client) {
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer != null && GLX.supportsFbo() && framebuffer.viewportHeight > 0) {
            return framebuffer.viewportHeight;
        }
        return Math.max(1, client.height);
    }

    // -----------------------------------------------------------------------
    // The first frame: the one native crash worth remembering
    // -----------------------------------------------------------------------

    private static File marker(MinecraftClient client) {
        return new File(client.runDirectory, ".fathom/nanovg-first-frame.pending");
    }

    private static File armed;

    private static boolean armFirstFrameMarker(MinecraftClient client) {
        if (armed != null) {
            return true;
        }
        File file = marker(client);
        if (file.isFile() && !Boolean.getBoolean(RETRY_PROPERTY)) {
            NanoVg.fail("the last start ended during NanoVG's first frame (a native crash?); delete "
                    + file.getAbsolutePath() + " or pass -D" + RETRY_PROPERTY + "=true to try again", null);
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("could not create " + parent);
            }
            if (!file.isFile() && !file.createNewFile()) {
                throw new IOException("could not create " + file);
            }
        } catch (IOException problem) {
            // Not being able to write a marker is not a reason to refuse to draw.
            System.err.println("[Fathom] NanoVG first-frame marker unavailable: " + problem.getMessage());
        }
        armed = file;
        return true;
    }

    private static void disarmFirstFrameMarker() {
        if (armed != null && armed.isFile() && !armed.delete()) {
            System.err.println("[Fathom] could not delete " + armed);
        }
    }
}
