package net.fathom.optimizations.fabric.perf;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * Caches the slow-changing HUD widgets as a recorded display list.
 *
 * <h2>Tiers</h2>
 *
 * <ul>
 *   <li><strong>Realtime</strong> — keystrokes, CPS, the sprint/sneak status and
 *       the crosshair: input feedback, drawn every frame, never cached. Widgets
 *       added through the public API are realtime too, because nothing is known
 *       about how often they change.
 *   <li><strong>Slow-changing</strong> — FPS, coordinates, clock, ping, armour,
 *       potions, pack display: recorded {@link #REFRESH_NANOS 20 times a
 *       second} and replayed on every other frame.
 *   <li><strong>Static</strong> — plates, borders, icons and labels are part of
 *       the widget that draws them, so they ride in the recorded list and are not
 *       issued from Java on replayed frames either.
 * </ul>
 *
 * <p>A change of layout, widget set, visibility, window size or any setting
 * records again immediately; the 50 ms interval only bounds how stale a
 * changing number can be. The HUD editor never uses the cache.
 *
 * <h2>Why a display list, and not a framebuffer</h2>
 *
 * <p>Drawn into an offscreen texture and composited, translucent plates would
 * not come out the same: the widgets' blend functions do not write coverage
 * into alpha, so a plate over a plate composites lighter than it draws. A
 * display list replays the commands the widgets issued — the same draws, the
 * same blending, into the same framebuffer — so a replayed frame is the frame
 * the widgets would have drawn at the moment it was recorded, pixel for pixel,
 * at any GUI scale.
 *
 * <p>Widgets drawn in Fathom's own typeface go through NanoVG, whose shaders
 * and vertex arrays do not belong in a display list; they are always drawn
 * directly, and are not passed here.
 *
 * <h2>Keeping GlStateManager honest</h2>
 *
 * <p>Recording goes through {@code GlStateManager}, which skips calls it thinks
 * redundant — so a recorded list is only correct if replayed from the state it
 * was recorded in. The state {@code GlStateManager} caches (texture, colour,
 * blend, alpha test, lighting, colour material, depth, cull, shading) is read
 * back from GL once per recording, at the start and at the end. Every replay
 * first forces the start state, replays, and then restores that same caller
 * state — each force uses a raw GL call and the matching
 * {@code GlStateManager} call, so GL and its cache agree afterwards whatever
 * the list changed. The captured end state remains diagnostic evidence of what
 * the recorded widget commands changed; it is not imposed on the caller.
 */
public final class HudLayerCache {

    private HudLayerCache() {}

    /** How long a recorded layer may be replayed before it is recorded again. */
    public static final long REFRESH_NANOS = 50_000_000L;

    private static final String[] SLOW = {
        "fps", "coordinates", "clock", "ping", "armour", "potions", "pack-display"
    };

    /** Whether a widget may be cached. Unknown widgets are realtime. */
    public static boolean cacheable(String widgetId) {
        for (String slow : SLOW) {
            if (slow.equals(widgetId)) {
                return true;
            }
        }
        return false;
    }

    private static int list;
    private static long recordedAt;
    private static long recordedSignature;
    private static boolean recording;
    private static final GlState START = new GlState();
    private static net.fathom.optimizations.fabric.gfx.GlStateAudit.Scope audit;

    /**
     * Replay the layer, or start recording it.
     *
     * @param signature everything the layer's content depends on besides time
     * @return true when the caller must now draw the slow widgets and then call
     *     {@link #endRecording()}; false when the layer was replayed
     */
    public static boolean beginFrame(long signature, long now) {
        audit = net.fathom.optimizations.fabric.gfx.GlStateAudit.beginRequested(
                net.fathom.optimizations.fabric.gfx.GlStateAudit.HUD_CACHE, PerfLayer.hudCache);
        if (list != 0 && signature == recordedSignature && now - recordedAt < REFRESH_NANOS) {
            START.force();
            GL11.glCallList(list);
            // The list's end state is useful for synchronizing the wrapper,
            // but it is not ours to leave behind. Restore the state in which
            // the surrounding HUD called the cached layer.
            START.force();
            PerfLayer.COUNTERS.hudComposites++;
            net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                    audit, true, "replay");
            return false;
        }
        if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0) {
            // Not the state the HUD is always drawn in; draw directly this once.
            PerfLayer.COUNTERS.hudDirect++;
            net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                    audit, false, "fallback-active-texture");
            return true;
        }
        if (list == 0) {
            list = GL11.glGenLists(1);
            if (list == 0) {
                PerfLayer.COUNTERS.hudDirect++;
                net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                        audit, false, "fallback-no-list");
                return true;
            }
        }
        START.capture();
        START.force();
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        recording = true;
        recordedSignature = signature;
        recordedAt = now;
        return true;
    }

    /** Finish a recording started by {@link #beginFrame}. Safe to call when none is. */
    public static void endRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        GL11.glEndList();
        // The end state used to be captured here as well: another twenty-odd
        // glGet calls, each a point where the CPU can wait for the driver,
        // twenty times a second -- for a snapshot nothing ever read. The
        // invariant callers rely on is the line below, which does not need it.
        // Recording used COMPILE_AND_EXECUTE, so it must obey the same caller
        // state invariant as a replay.
        START.force();
        PerfLayer.COUNTERS.hudRedraws++;
        net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                audit, true, "record");
    }

    /** Throw the recorded layer away, e.g. when the feature is switched off. */
    public static void release() {
        if (recording) {
            endRecording();
        }
        if (list != 0) {
            GL11.glDeleteLists(list, 1);
            list = 0;
        }
        recordedSignature = 0L;
    }

    /** Force a re-record on the next frame. */
    public static void invalidate() {
        recordedAt = Long.MIN_VALUE / 2;
    }

    /** The GL state {@code GlStateManager} caches, read back and forced. */
    static final class GlState {
        private static final IntBuffer INTS = BufferUtils.createIntBuffer(16);
        private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(16);
        private static final ByteBuffer BYTES = BufferUtils.createByteBuffer(16);

        boolean texture;
        int boundTexture;
        final float[] colour = new float[4];
        boolean blend;
        int srcRgb;
        int dstRgb;
        int srcAlpha;
        int dstAlpha;
        boolean alphaTest;
        int alphaFunc;
        float alphaRef;
        boolean lighting;
        boolean light0;
        boolean light1;
        boolean colourMaterial;
        int colourMaterialFace;
        int colourMaterialMode;
        boolean rescaleNormal;
        boolean depthTest;
        boolean depthMask;
        boolean cull;
        int shadeModel;

        void capture() {
            texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            FLOATS.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOATS);
            for (int index = 0; index < 4; index++) {
                colour[index] = FLOATS.get(index);
            }
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            FLOATS.clear();
            GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, FLOATS);
            alphaRef = FLOATS.get(0);
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            light0 = GL11.glIsEnabled(GL11.GL_LIGHT0);
            light1 = GL11.glIsEnabled(GL11.GL_LIGHT1);
            colourMaterial = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
            colourMaterialFace = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE);
            colourMaterialMode = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
            rescaleNormal = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
            depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            BYTES.clear();
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK, BYTES);
            depthMask = BYTES.get(0) != 0;
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
            INTS.clear();
        }

        /**
         * Set GL and GlStateManager's cache to this state. Each value is set
         * raw first, so GL holds it whatever the cache believed, and then
         * through GlStateManager, so the cache holds it too.
         */
        void force() {
            if (texture) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GlStateManager.enableTexture();
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GlStateManager.disableTexture();
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
            GlStateManager.bindTexture(boundTexture);
            GL11.glColor4f(colour[0], colour[1], colour[2], colour[3]);
            GlStateManager.color(colour[0], colour[1], colour[2], colour[3]);
            toggle(GL11.GL_BLEND, blend);
            if (blend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GlStateManager.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            toggle(GL11.GL_ALPHA_TEST, alphaTest);
            if (alphaTest) {
                GlStateManager.enableAlphaTest();
            } else {
                GlStateManager.disableAlphaTest();
            }
            GL11.glAlphaFunc(alphaFunc, alphaRef);
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
            toggle(GL11.GL_LIGHTING, lighting);
            if (lighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            toggle(GL11.GL_LIGHT0, light0);
            if (light0) {
                GlStateManager.enableLight(0);
            } else {
                GlStateManager.disableLight(0);
            }
            toggle(GL11.GL_LIGHT1, light1);
            if (light1) {
                GlStateManager.enableLight(1);
            } else {
                GlStateManager.disableLight(1);
            }
            toggle(GL11.GL_COLOR_MATERIAL, colourMaterial);
            if (colourMaterial) {
                GlStateManager.enableColorMaterial();
            } else {
                GlStateManager.disableColorMaterial();
            }
            GL11.glColorMaterial(colourMaterialFace, colourMaterialMode);
            GlStateManager.colorMaterial(colourMaterialFace, colourMaterialMode);
            toggle(GL12.GL_RESCALE_NORMAL, rescaleNormal);
            if (rescaleNormal) {
                GlStateManager.enableRescaleNormal();
            } else {
                GlStateManager.disableRescaleNormal();
            }
            toggle(GL11.GL_DEPTH_TEST, depthTest);
            if (depthTest) {
                GlStateManager.enableDepthTest();
            } else {
                GlStateManager.disableDepthTest();
            }
            GL11.glDepthMask(depthMask);
            GlStateManager.depthMask(depthMask);
            toggle(GL11.GL_CULL_FACE, cull);
            if (cull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            GL11.glShadeModel(shadeModel);
            GlStateManager.shadeModel(shadeModel);
        }

        private static void toggle(int capability, boolean on) {
            if (on) {
                GL11.glEnable(capability);
            } else {
                GL11.glDisable(capability);
            }
        }
    }
}
