package net.fathom.optimizations.fabric.hud;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;

/**
 * Solid rectangles collected into one draw.
 *
 * <p>{@code DrawableHelper.fill} is one tessellator draw and a round of blend
 * and texture state changes per rectangle. Keystrokes draws a plate and four
 * border strips per key, about thirty-five fills a frame. Collected here they
 * are one draw with the same blend function, the same vertices and per-vertex
 * colour instead of a uniform colour — the same pixels.
 *
 * <p>Ordering is the caller's responsibility: everything in a batch is drawn
 * together, so only rectangles that nothing else draws between may share one.
 * Render thread only.
 */
final class QuadBatch {

    private static boolean open;
    private static int lastArgb;

    private QuadBatch() {}

    static boolean isOpen() {
        return open;
    }

    static void begin() {
        if (open) {
            return;
        }
        // Anything Fathom's renderer queued shares the same Tessellator.
        net.fathom.optimizations.fabric.gfx.Gfx.flushHudOverlay();
        Tessellator.getInstance().getBuffer().begin(7, VertexFormats.POSITION_COLOR);
        open = true;
    }

    /** One rectangle, with {@code DrawableHelper.fill}'s coordinate normalisation. */
    static void rect(int left, int top, int right, int bottom, int argb) {
        if (!open) {
            net.minecraft.client.gui.DrawableHelper.fill(left, top, right, bottom, argb);
            return;
        }
        if (left < right) {
            int swap = left;
            left = right;
            right = swap;
        }
        if (top < bottom) {
            int swap = top;
            top = bottom;
            bottom = swap;
        }
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.vertex(left, bottom, 0.0).color(r, g, b, a).next();
        buffer.vertex(right, bottom, 0.0).color(r, g, b, a).next();
        buffer.vertex(right, top, 0.0).color(r, g, b, a).next();
        buffer.vertex(left, top, 0.0).color(r, g, b, a).next();
        lastArgb = argb;
    }

    static void end() {
        if (!open) {
            return;
        }
        open = false;
        GlStateManager.enableBlend();
        GlStateManager.disableTexture();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        Tessellator.getInstance().draw();
        // Leave the state as the last `fill` would have: its colour current,
        // texturing back on, blending off.
        GlStateManager.color((lastArgb >> 16 & 0xFF) / 255f, (lastArgb >> 8 & 0xFF) / 255f,
                (lastArgb & 0xFF) / 255f, (lastArgb >>> 24) / 255f);
        GlStateManager.enableTexture();
        GlStateManager.disableBlend();
    }
}
