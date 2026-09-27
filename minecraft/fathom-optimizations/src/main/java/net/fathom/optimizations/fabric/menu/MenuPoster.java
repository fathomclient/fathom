package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;

import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;

/**
 * The approved bg_5rain poster and the presentation shared with video frames.
 *
 * <p>The cool neutral scrim matches {@code system.backdrop.scrim}; it preserves
 * the rainy bridge while seating white controls. A neutral field appears only
 * if this exact poster genuinely fails to load.
 *
 * <h2>Cost</h2>
 *
 * <p>One textured quad and one five-band gradient per frame, drawn with the
 * game's own tessellator inside the menu's isolated background pass. Nothing
 * is read back and nothing is allocated.
 */
final class MenuPoster {

    private static final String POSTER_RESOURCE =
            "/assets/fathom-optimizations/textures/menu/main-menu-bg.png";
    private static final float SOURCE_ASPECT = 16f / 9f;

    /**
     * The launcher's cool scrim, top to bottom, at five shared stops.
     */
    private static final float[] SCRIM_AT = {0f, 0.22f, 0.50f, 0.78f, 1f};
    private static final float[] SCRIM_ALPHA = {0.14f, 0.03f, 0.02f, 0.07f, 0.18f};
    private static final int SCRIM_R = 4;
    private static final int SCRIM_G = 9;
    private static final int SCRIM_B = 12;

    /** Neutral failure field, used only if the approved poster cannot load. */
    private static final float[] FIELD_AT = {0f, 0.28f, 0.52f, 0.76f, 1f};
    private static final int[] FIELD_RGB = {0x172630, 0x122029, 0x0D181E, 0x0A1419, 0x071015};
    private static int texture = -1;

    private MenuPoster() {}

    /** Release the classpath poster texture on a resource reload. */
    static void dispose() {
        if (texture > 0) {
            GlStateManager.bindTexture(0);
            GL11.glDeleteTextures(texture);
        }
        texture = -1;
    }

    /** Hold the approved poster until the first video frame is available. */
    static void draw(MinecraftClient client) {
        if (bind()) drawTexture(client, texture); else drawFlat(client);
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

    /** Load the exact approved poster once; no generated or alternate image. */
    private static boolean bind() {
        if (texture > 0) {
            GlStateManager.bindTexture(texture);
            return true;
        }
        if (texture == 0) return false;
        try {
            BufferedImage image;
            try (InputStream stream = MenuPoster.class.getResourceAsStream(POSTER_RESOURCE)) {
                if (stream == null) {
                    texture = 0;
                    System.err.println("[Fathom] Main-menu poster is missing: " + POSTER_RESOURCE);
                    return false;
                }
                image = ImageIO.read(stream);
            }
            if (image == null) {
                texture = 0;
                System.err.println("[Fathom] Main-menu poster could not be decoded: "
                        + POSTER_RESOURCE);
                return false;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int argb = row[x];
                    pixels.put((byte) ((argb >> 16) & 0xFF));
                    pixels.put((byte) ((argb >> 8) & 0xFF));
                    pixels.put((byte) (argb & 0xFF));
                    pixels.put((byte) ((argb >>> 24) & 0xFF));
                }
            }
            pixels.flip();

            texture = GL11.glGenTextures();
            GlStateManager.bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                    width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            System.out.println("[Fathom] Main-menu poster uploaded: " + width + "x" + height + ".");
            return true;
        } catch (IOException | RuntimeException failure) {
            texture = 0;
            System.err.println("[Fathom] Main-menu poster failed to upload: " + failure.getMessage());
            return false;
        }
    }
}
