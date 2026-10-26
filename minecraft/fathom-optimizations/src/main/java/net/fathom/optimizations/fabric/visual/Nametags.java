package net.fathom.optimizations.fabric.visual;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.module.FathomModule;

/**
 * Nametags: the labels over players and named mobs, sized, backed and shadowed
 * your way, and your own in third person.
 *
 * <p>While the module is on it draws the label itself, the way vanilla's
 * {@code renderLabelIfPresent} does, with the settings applied; the
 * performance layer's cached nametags stand aside for it. Off, both paths are
 * exactly as before. Which entities get a label is still vanilla's decision
 * (team rules, invisibility, sneaking), except your own.
 */
public final class Nametags {

    /** Vanilla's label size: 1/60 of a block per pixel, times 1.6. */
    private static final float VANILLA_SCALE = 0.016666668F * 1.6F;

    /** Labels drawn since the self-test last cleared it. */
    public static int drawn;

    private Nametags() {}

    static FathomModule active() {
        return WorldEffects.active("nametags");
    }

    /** Whether your own label is drawn: third person, with the setting on. */
    public static boolean showOwn(Entity entity) {
        FathomModule module = active();
        MinecraftClient client = MinecraftClient.getInstance();
        return module != null && module.toggle("own", true) && entity == client.player
                && client.options != null && client.options.perspective != 0;
    }

    /**
     * Draws a label as vanilla would, with the module's settings.
     *
     * @return true when it was drawn here (or was too far to draw at all); false
     *     to leave it to vanilla, which is what happens with the module off
     */
    public static boolean render(EntityRenderDispatcher dispatcher, TextRenderer font, Entity entity, String text,
            double x, double y, double z, int maxDistance) {
        FathomModule module = active();
        if (module == null || font == null || text == null) {
            return false;
        }
        if (entity.squaredDistanceTo(dispatcher.field_11098) > (double) (maxDistance * maxDistance)) {
            return true;
        }
        float scale = VANILLA_SCALE * (float) (module.number("scale", 100) / 100.0);
        boolean throughWalls = module.toggle("through-walls", true);
        boolean shadow = module.toggle("shadow", false);
        float background = module.toggle("background.show", true)
                ? (float) (module.number("background", 25) / 100.0) : 0.0F;
        int textRgb = 0xFFFFFF;
        String mode = module.choice("text.colour.mode", "Server colours");
        if (!"Server colours".equals(mode)) {
            // A colour of your own replaces the server's, so its codes go.
            text = net.minecraft.util.Formatting.strip(text);
            textRgb = "Rainbow".equals(mode)
                    ? java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000.0F, 0.8F, 1.0F) & 0xFFFFFF
                    : module.colour("text.colour", 0xFFFFFF);
        }
        int backgroundRgb = module.colour("background.colour", 0x000000);

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + entity.height + 0.5F, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-dispatcher.yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(dispatcher.pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        if (throughWalls) {
            GlStateManager.disableDepthTest();
        }
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);

        int half = font.getStringWidth(text) / 2;
        if (background > 0.0F) {
            float red = ((backgroundRgb >> 16) & 0xFF) / 255.0F;
            float green = ((backgroundRgb >> 8) & 0xFF) / 255.0F;
            float blue = (backgroundRgb & 0xFF) / 255.0F;
            GlStateManager.disableTexture();
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
            buffer.vertex(-half - 1, -1, 0.0).color(red, green, blue, background).next();
            buffer.vertex(-half - 1, 8, 0.0).color(red, green, blue, background).next();
            buffer.vertex(half + 1, 8, 0.0).color(red, green, blue, background).next();
            buffer.vertex(half + 1, -1, 0.0).color(red, green, blue, background).next();
            tessellator.draw();
            GlStateManager.enableTexture();
        }
        if (throughWalls) {
            // Vanilla's faint copy, seen through whatever is in front.
            font.draw(text, -half, 0, 0x20000000 | textRgb);
            GlStateManager.enableDepthTest();
        }
        GlStateManager.depthMask(true);
        if (shadow) {
            font.drawWithShadow(text, -half, 0, 0xFF000000 | textRgb);
        } else {
            font.draw(text, -half, 0, 0xFF000000 | textRgb);
        }
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
        drawn++;
        return true;
    }
}
