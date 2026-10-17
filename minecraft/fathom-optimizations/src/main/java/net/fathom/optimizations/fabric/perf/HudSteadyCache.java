package net.fathom.optimizations.fabric.perf;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Replays the realtime HUD widgets while they are standing still.
 *
 * <h2>What the view benchmark found</h2>
 *
 * <p>Fathom's HUD cost 0.042 ms a frame in every scene the view-direction
 * profile measured, and 0.032 ms of that was the widgets {@link HudLayerCache}
 * leaves alone because they can change from one frame to the next: keystrokes
 * and the CPS readout. Most frames they do not change. A key that is up stays
 * up; a CPS count holds for a second. Those frames redrew several hundred
 * rounded-corner spans and a dozen strings to produce the pixels the previous
 * frame already produced.
 *
 * <h2>How it stays exact</h2>
 *
 * <p>Each widget reports a content signature (see
 * {@code HudWidget.contentSignature}); the HUD combines them with everything
 * else the drawing depends on. The drawing is recorded only once a signature
 * has been seen twice in a row, and replayed only while it is unchanged. So:
 *
 * <ul>
 *   <li>A frame whose content changed is always drawn live, the same frame.
 *       Pressing a key shows on the frame it is pressed, exactly as before.
 *   <li>Content that changes every frame -- a key fading -- is never recorded
 *       at all, so animating costs what it always did and nothing extra.
 *   <li>A replay starts from the same GL state the recording started from and
 *       leaves that state behind, the invariant {@link HudLayerCache} keeps.
 * </ul>
 *
 * <p>The recording references the font's glyph texture, so a resource or font
 * reload releases it (see {@code TextRendererWidthMixin}). Off with the
 * Performance layer's HUD caching setting, like the slow layer.
 */
public final class HudSteadyCache {

    private HudSteadyCache() {}

    private static int list;
    private static long recordedSignature;
    /** The signature of the previous frame, recorded or not. */
    private static long lastSignature;
    private static boolean recording;
    private static final HudLayerCache.GlState START = new HudLayerCache.GlState();
    private static net.fathom.optimizations.fabric.gfx.GlStateAudit.Scope audit;

    /** Benchmark switch: false draws every realtime widget live, as before this existed. */
    private static boolean enabled = true;

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            release();
        }
    }

    /**
     * Start this frame's steady widgets.
     *
     * @return true when the caller must draw them now (recording or not);
     *     false when a replay already drew them
     */
    public static boolean beginFrame(long signature) {
        audit = net.fathom.optimizations.fabric.gfx.GlStateAudit.beginRequested(
                net.fathom.optimizations.fabric.gfx.GlStateAudit.HUD_STEADY, PerfLayer.hudCache);
        long previous = lastSignature;
        lastSignature = signature;
        if (list != 0 && signature == recordedSignature) {
            START.force();
            GL11.glCallList(list);
            START.force();
            PerfLayer.COUNTERS.hudComposites++;
            net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(audit, true, "steady-replay");
            return false;
        }
        if (signature != previous) {
            // Changed this frame. Draw it live: if it changes again next frame
            // a recording would have been wasted work.
            net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(audit, false, "steady-changed");
            return true;
        }
        if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0) {
            net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                    audit, false, "steady-fallback-active-texture");
            return true;
        }
        if (list == 0) {
            list = GL11.glGenLists(1);
            if (list == 0) {
                net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                        audit, false, "steady-fallback-no-list");
                return true;
            }
        }
        START.capture();
        START.force();
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        recording = true;
        recordedSignature = signature;
        return true;
    }

    /** Finish a recording started by {@link #beginFrame}. Safe to call when none is. */
    public static void endRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        GL11.glEndList();
        START.force();
        PerfLayer.COUNTERS.hudRedraws++;
        net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(audit, true, "steady-record");
    }

    /** Forget the recording. The next frame draws live and the one after records afresh. */
    public static void release() {
        if (recording) {
            endRecording();
        }
        if (list != 0) {
            GL11.glDeleteLists(list, 1);
            list = 0;
        }
        recordedSignature = 0L;
        lastSignature = 0L;
    }
}
