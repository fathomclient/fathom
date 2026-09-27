package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;

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
import net.fathom.optimizations.fabric.gfx.WorldBackdrop;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * The Graphite backdrop, drawn: a processed capture cover-cropped to the
 * window, the vignette over it, and the grain over that.
 *
 * <p>The title screen draws the shipped capture -- the launcher's identical
 * file, processed offline, so the two match exactly and nothing is blurred at
 * runtime. A menu over the world draws {@link WorldBackdrop}'s one capture.
 * Either missing, it is flat ink-1 under the same vignette and grain.
 *
 * <p>Per frame: one textured quad, one vignette ring and one grain quad, with
 * the game's own tessellator, inside a screen's isolated background pass. The
 * two textures are uploaded once and kept.
 */
public final class GraphiteBackdrop {

    private static final String ROOT = "/assets/fathom-optimizations/textures/menu/graphite/";
    private static final float VIGNETTE_START = GraphiteTokens.BACKDROP_VIGNETTE_START;
    private static final float VIGNETTE_ALPHA = GraphiteTokens.BACKDROP_VIGNETTE_ALPHA;
    private static final float GRAIN_ALPHA = GraphiteTokens.BACKDROP_GRAIN_ALPHA;
    private static final float GRAIN_TILE = GraphiteTokens.BACKDROP_GRAIN_TILE;
    private static final int SEGMENTS = 48;

    private static int shipped = -1;
    private static int shippedWidth;
    private static int shippedHeight;
    private static int grain = -1;

    private GraphiteBackdrop() {}

    /** The title screen's backdrop: the shipped capture. */
    public static void drawShipped(MinecraftClient client, float alpha) {
        ensureShipped();
        draw(client, shipped, shippedWidth, shippedHeight, alpha);
    }

    /** A menu over the world: its one capture, or the shipped file when there is no world. */
    public static void drawCaptured(MinecraftClient client, float alpha) {
        if (client.world == null) {
            drawShipped(client, alpha);
            return;
        }
        draw(client, WorldBackdrop.texture(), WorldBackdrop.width(), WorldBackdrop.height(), alpha);
    }

    /** Free the shipped textures, for a resource reload. */
    public static void dispose() {
        if (shipped > 0) {
            GL11.glDeleteTextures(shipped);
        }
        if (grain > 0) {
            GL11.glDeleteTextures(grain);
        }
        shipped = -1;
        grain = -1;
    }

    private static void draw(MinecraftClient client, int texture, int textureWidth, int textureHeight, float alpha) {
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        int width = viewport.width();
        int height = viewport.height();
        GlStateManager.disableAlphaTest();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull();

        if (texture > 0 && textureWidth > 0 && textureHeight > 0) {
            image(texture, width, height, textureWidth / (float) textureHeight, alpha);
        } else {
            flat(width, height, GraphiteTokens.COLOR_INK_1, alpha);
        }
        vignette(width, height, alpha);
        ensureGrain();
        if (grain > 0) {
            grain(client, width, height, alpha);
        }

        GlStateManager.enableTexture();
        GlStateManager.enableAlphaTest();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Cover-scaled from the centre: heavily blurred, where it is cropped never matters. */
    private static void image(int texture, int width, int height, float sourceAspect, float alpha) {
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(texture);
        GlStateManager.color(1f, 1f, 1f, alpha);
        float viewAspect = width / (float) height;
        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;
        if (viewAspect > sourceAspect) {
            float visible = sourceAspect / viewAspect;
            v0 = (1f - visible) / 2f;
            v1 = v0 + visible;
        } else if (viewAspect < sourceAspect) {
            float visible = viewAspect / sourceAspect;
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
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void flat(int width, int height, int rgb, float alpha) {
        GlStateManager.disableTexture();
        int a = Math.round(alpha * 255f);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(0, height, 0).color(r, g, b, a).next();
        buffer.vertex(width, height, 0).color(r, g, b, a).next();
        buffer.vertex(width, 0, 0).color(r, g, b, a).next();
        buffer.vertex(0, 0, 0).color(r, g, b, a).next();
        tessellator.draw();
    }

    /**
     * Transparent at 60% of the half-diagonal, ink-0 at 35% in the corners: a
     * ring between the two circles, smooth-shaded. The corners are exactly on
     * the outer circle, so nothing beyond it needs drawing.
     */
    private static void vignette(int width, int height, float alpha) {
        GlStateManager.disableTexture();
        GL11.glShadeModel(GL11.GL_SMOOTH);
        float cx = width / 2f;
        float cy = height / 2f;
        float outer = (float) Math.sqrt(cx * cx + cy * cy);
        float inner = outer * VIGNETTE_START;
        int ink = GraphiteTokens.COLOR_INK_0;
        int r = (ink >> 16) & 0xFF;
        int g = (ink >> 8) & 0xFF;
        int b = ink & 0xFF;
        int edge = Math.round(VIGNETTE_ALPHA * alpha * 255f);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        for (int segment = 0; segment < SEGMENTS; segment++) {
            double a0 = segment * Math.PI * 2.0 / SEGMENTS;
            double a1 = (segment + 1) * Math.PI * 2.0 / SEGMENTS;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            buffer.vertex(cx + c0 * inner, cy + s0 * inner, 0).color(r, g, b, 0).next();
            buffer.vertex(cx + c0 * outer, cy + s0 * outer, 0).color(r, g, b, edge).next();
            buffer.vertex(cx + c1 * outer, cy + s1 * outer, 0).color(r, g, b, edge).next();
            buffer.vertex(cx + c1 * inner, cy + s1 * inner, 0).color(r, g, b, 0).next();
        }
        tessellator.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
    }

    /** The grain tile at 1:1 device pixels, never scaled, 3% alpha, static. */
    private static void grain(MinecraftClient client, int width, int height, float alpha) {
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(grain);
        GlStateManager.color(1f, 1f, 1f, GRAIN_ALPHA * alpha);
        float u = client.width / GRAIN_TILE;
        float v = client.height / GRAIN_TILE;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(0, height, 0).texture(0f, v).next();
        buffer.vertex(width, height, 0).texture(u, v).next();
        buffer.vertex(width, 0, 0).texture(u, 0f).next();
        buffer.vertex(0, 0, 0).texture(0f, 0f).next();
        tessellator.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void ensureShipped() {
        if (shipped >= 0) {
            return;
        }
        BufferedImage image = read("backdrop-default.png");
        if (image == null) {
            shipped = 0;
            return;
        }
        shippedWidth = image.getWidth();
        shippedHeight = image.getHeight();
        shipped = upload(image, GL11.GL_LINEAR, GL12.GL_CLAMP_TO_EDGE);
    }

    private static void ensureGrain() {
        if (grain >= 0) {
            return;
        }
        BufferedImage image = read("grain.png");
        grain = image == null ? 0 : upload(image, GL11.GL_NEAREST, GL11.GL_REPEAT);
    }

    private static BufferedImage read(String name) {
        try (InputStream stream = GraphiteBackdrop.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                System.err.println("[Fathom] Graphite asset is missing: " + name);
                return null;
            }
            return ImageIO.read(stream);
        } catch (Exception failure) {
            System.err.println("[Fathom] Graphite asset could not be read: " + name + ": " + failure);
            return null;
        }
    }

    private static int upload(BufferedImage image, int filter, int wrap) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        for (int index = 0; index < argb.length; index++) {
            argb[index] |= 0xFF000000;
        }
        IntBuffer pixels = BufferUtils.createIntBuffer(argb.length);
        pixels.put(argb).flip();
        int texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        GlStateManager.bindTexture(0);
        return texture;
    }
}
