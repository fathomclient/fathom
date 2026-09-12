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
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;

/** Static first-frame fallback and shared presentation for the animated menu background. */
final class MenuPoster {

    private static final String POSTER_RESOURCE =
            "/assets/fathom-optimizations/textures/menu/main-menu-bg.png";
    private static final float SOURCE_ASPECT = 16f / 9f;

    /**
     * The navy tint over the poster, sized to the poster's own grade.
     *
     * <p>The rainy-jungle poster is lifted and softened offline. A further 24%
     * here seats white text over the pale horizon without crushing the bridge,
     * cliff and leaf silhouettes into the flat wash this menu exists to avoid.
     *
     * <p>It is a constant rather than a setting because it is not a preference:
     * it is the second half of a grade whose first half is baked into the PNG
     * by {@code tools/prepare-main-menu-poster.ps1}. Change the poster and this
     * number is part of what changes with it.
     */
    private static final int CONTRAST_WASH = 0x22050810;

    /** The bottom scrim's colour and its opacity at the very bottom edge. */
    private static final int SCRIM_R = 0x05;
    private static final int SCRIM_G = 0x07;
    private static final int SCRIM_B = 0x0C;
    private static final int SCRIM_ALPHA = 128;
    private static int texture = -1;

    private MenuPoster() {}

    /** Draws the poster with CSS-style cover cropping and linear sampling. */
    static void draw(MinecraftClient client) {
        if (!bind()) {
            drawTexture(client, 0);
            return;
        }
        drawTexture(client, texture);
    }

    /** The plain field, for the scene that is not a picture at all. */
    static void drawFlat(MinecraftClient client) {
        Window window = new Window(client);
        DrawableHelper.fill(0, 0, (int) window.getScaledWidth(), (int) window.getScaledHeight(),
                0xFF05060A);
    }

    /** Draw one prepared frame and the common contrast treatment over it. */
    static void drawTexture(MinecraftClient client, int textureId) {
        Window window = new Window(client);
        int width = Math.max(1, (int) window.getScaledWidth());
        int height = Math.max(1, (int) window.getScaledHeight());
        DrawableHelper.fill(0, 0, width, height, 0xFF050506);
        if (textureId <= 0) {
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
        DrawableHelper.fill(0, 0, width, height, CONTRAST_WASH);
        bottomScrim(width, height);
    }

    /**
     * A gradient up from the bottom edge, under the dock and the footer.
     *
     * <p>The flat tint is uniform because it is grading the poster; this is not
     * grading anything, it is protecting two rows of interface. Whatever the
     * poster is, its bottom edge is where the ground usually is and the ground
     * is usually the brightest thing in the frame. In this poster the foreground
     * leaves run directly behind the dock, close enough to the dock's own face
     * to make the row read as a smudge without a local contrast layer.
     *
     * <p>Half opacity at the bottom edge, nothing at 55% height, linear
     * between. That is enough to seat the dock and footer while leaving the
     * foreground path visible.
     */
    private static void bottomScrim(int width, int height) {
        float top = height * 0.55f;
        GlStateManager.disableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlphaTest();
        GL11.glShadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(0, height, 0).color(SCRIM_R, SCRIM_G, SCRIM_B, SCRIM_ALPHA).next();
        buffer.vertex(width, height, 0).color(SCRIM_R, SCRIM_G, SCRIM_B, SCRIM_ALPHA).next();
        buffer.vertex(width, top, 0).color(SCRIM_R, SCRIM_G, SCRIM_B, 0).next();
        buffer.vertex(0, top, 0).color(SCRIM_R, SCRIM_G, SCRIM_B, 0).next();
        tessellator.draw();

        GL11.glShadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlphaTest();
        GlStateManager.enableTexture();
    }

    /**
     * Load directly from the mod classpath and upload once.
     *
     * <p>Legacy Fabric's 1.8.9 resource manager does not expose arbitrary mod
     * asset namespaces as texture packs. Classpath lookup is stable in both
     * Loom and the remapped jar, and avoids a failed lookup every frame.
     */
    private static boolean bind() {
        if (texture > 0) {
            GlStateManager.bindTexture(texture);
            return true;
        }
        if (texture == 0) {
            return false;
        }
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
            System.out.println("[Fathom] Main-menu poster uploaded: "
                    + width + "x" + height + ".");
            return true;
        } catch (IOException | RuntimeException missing) {
            texture = 0;
            System.err.println("[Fathom] Main-menu poster failed to upload: "
                    + missing.getMessage());
            return false;
        }
    }
}
