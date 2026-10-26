package net.fathom.optimizations.fabric.visual;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

import net.fathom.optimizations.core.module.FathomModule;

/**
 * Motion Blur: each frame of the world is blended with the frames before it.
 *
 * <p>Runs after the world is drawn and before the HUD, so the hotbar, chat
 * and Fathom's widgets stay sharp. The previous result is kept in a
 * framebuffer of its own; this frame is drawn over with that history at the
 * chosen strength (a constant-alpha blend), and the mix becomes the history
 * for the next frame. Off, the extra framebuffer is released.
 */
public final class MotionBlur {

    /** GL 1.4's constant-alpha blend factors; LWJGL 2 names them only in ARB_imaging. */
    private static final int GL_CONSTANT_ALPHA = 0x8003;
    private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;

    private static Framebuffer history;
    private static boolean primed;

    /** Frames blended since the self-test last cleared it. */
    public static int blended;

    private MotionBlur() {}

    public static void afterWorld(MinecraftClient client) {
        FathomModule module = WorldEffects.active("motion-blur");
        if (module == null || client.world == null || !GLX.supportsFbo()) {
            release();
            return;
        }
        Framebuffer main = client.getFramebuffer();
        int width = client.width;
        int height = client.height;
        if (main == null || width <= 0 || height <= 0) {
            return;
        }
        if (history == null || history.textureWidth != main.textureWidth
                || history.textureHeight != main.textureHeight) {
            release();
            history = new Framebuffer(main.textureWidth, main.textureHeight, false);
            history.setTexFilter(GL11.GL_LINEAR);
        }
        float strength = (float) Math.max(0.0, Math.min(0.95, module.number("strength", 50) / 100.0));
        if (primed && strength > 0.0F) {
            // Draw the history over this frame: result = frame * (1 - s) + history * s.
            main.bind(true);
            GlStateManager.enableBlend();
            GL14.glBlendColor(0.0F, 0.0F, 0.0F, strength);
            GlStateManager.blendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
            history.drawInternal(width, height, false);
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableBlend();
            blended++;
        }
        // The mix is the history for the next frame.
        history.bind(true);
        main.drawInternal(width, height, true);
        primed = true;
        main.bind(true);
        GlStateManager.enableDepthTest();
        GlStateManager.enableAlphaTest();
    }

    private static void release() {
        if (history != null) {
            history.delete();
            history = null;
        }
        primed = false;
    }
}
