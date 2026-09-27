package net.fathom.optimizations.fabric.menu;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;

import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;

/**
 * The menu background's presentation: the picture's cover crop, the
 * launcher's warm scrim over it, and the warm field when there is no picture.
 *
 * <h2>The launcher's grade, not a new one</h2>
 *
 * <p>The scrim is {@code system.backdrop.scrim} from the theme manifest: a
 * warm umber deepening that is strongest at the top edge (where the account
 * control sits) and the bottom edge (where the dock and footer sit), and almost
 * nothing through the middle, where the photograph is the point. The field is
 * {@code system.backdrop.fallback}: the same photograph's colours, as bands.
 * No navy wash, no black, and the picture itself is not filtered.
 *
 * <h2>Cost</h2>
 *
 * <p>One textured quad and one five-band gradient per frame, drawn with the
 * game's own tessellator inside the menu's isolated background pass. Nothing
 * is read back and nothing is allocated.
 */
final class MenuPoster {

    private static final float SOURCE_ASPECT = 16f / 9f;

    /**
     * The launcher's scrim, top to bottom: position (0-1) and umber opacity.
     * {@code rgba(26, 16, 6, a)} at 0%, 22%, 50%, 78% and 100%.
     */
    private static final float[] SCRIM_AT = {0f, 0.22f, 0.50f, 0.78f, 1f};
    private static final float[] SCRIM_ALPHA = {0.30f, 0.08f, 0.03f, 0.12f, 0.40f};
    private static final int SCRIM_R = 26;
    private static final int SCRIM_G = 16;
    private static final int SCRIM_B = 6;

    /** The launcher's fallback field: the photograph's own colours, as bands. */
    private static final float[] FIELD_AT = {0f, 0.28f, 0.52f, 0.76f, 1f};
    private static final int[] FIELD_RGB = {0x6D6843, 0x857750, 0x9C7F4F, 0x74532F, 0x2A1C0E};

    private MenuPoster() {}

    /** Nothing to release: the poster no longer owns a texture. */
    static void dispose() {
    }

    /** A picture that cannot be shown gives the warm field. */
    static void draw(MinecraftClient client) {
        drawFlat(client);
    }

    /** The warm field, for when there is no picture behind the menu. */
    static void drawFlat(MinecraftClient client) {
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        bands(viewport.width(), viewport.height(), FIELD_AT, FIELD_RGB, null);
    }

    /** Draw one prepared picture and the launcher's scrim over it. */
    static void drawTexture(MinecraftClient client, int textureId) {
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        int width = viewport.width();
        int height = viewport.height();
        if (textureId <= 0) {
            drawFlat(client);
            return;
        }

        GlStateManager.enableTexture();
        GlStateManager.bindTexture(textureId);
        GlStateManager.disableAlphaTest();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        float viewAspect = width / (float) height;
        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;
        if (viewAspect > SOURCE_ASPECT) {
            float visible = SOURCE_ASPECT / viewAspect;
            v0 = (1f - visible) / 2f;
            v1 = v0 + visible;
        } else if (viewAspect < SOURCE_ASPECT) {
            float visible = viewAspect / SOURCE_ASPECT;
            u0 = (1f - visible) / 2f;
            u1 = u0 + visible;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(0, height, 0).texture(u0, v1).next();
        buffer.vertex(width, height, 0).texture(u1, v1).next();
        buffer.vertex(width, 0, 0).texture(u1, v0).next();
        buffer.vertex(0, 0, 0).texture(u0, v0).next();
        tessellator.draw();

        GlStateManager.enableAlphaTest();
        bands(width, height, SCRIM_AT, null, SCRIM_ALPHA);
    }

    /**
     * Vertical bands between stops, smooth-shaded: either opaque colours
     * ({@code rgb}) or the umber scrim at per-stop opacities ({@code alpha}).
     */
    private static void bands(int width, int height, float[] at, int[] rgb, float[] alpha) {
        GlStateManager.disableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlphaTest();
        GL11.glShadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        for (int stop = 0; stop + 1 < at.length; stop++) {
            float top = height * at[stop];
            float bottom = height * at[stop + 1];
            int r0 = rgb == null ? SCRIM_R : (rgb[stop] >> 16) & 0xFF;
            int g0 = rgb == null ? SCRIM_G : (rgb[stop] >> 8) & 0xFF;
            int b0 = rgb == null ? SCRIM_B : rgb[stop] & 0xFF;
            int a0 = alpha == null ? 255 : Math.round(alpha[stop] * 255f);
            int r1 = rgb == null ? SCRIM_R : (rgb[stop + 1] >> 16) & 0xFF;
            int g1 = rgb == null ? SCRIM_G : (rgb[stop + 1] >> 8) & 0xFF;
            int b1 = rgb == null ? SCRIM_B : rgb[stop + 1] & 0xFF;
            int a1 = alpha == null ? 255 : Math.round(alpha[stop + 1] * 255f);
            buffer.vertex(0, bottom, 0).color(r1, g1, b1, a1).next();
            buffer.vertex(width, bottom, 0).color(r1, g1, b1, a1).next();
            buffer.vertex(width, top, 0).color(r0, g0, b0, a0).next();
            buffer.vertex(0, top, 0).color(r0, g0, b0, a0).next();
        }
        tessellator.draw();

        GL11.glShadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlphaTest();
        GlStateManager.enableTexture();
    }
}
