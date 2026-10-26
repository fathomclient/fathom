package net.fathom.optimizations.fabric.visual;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.AbstractTeam;

import net.fathom.optimizations.core.module.FathomModule;

/**
 * Teamview: a marker over each teammate, seen through walls, so you know where
 * your side is.
 *
 * <p>Teammates only, as the server defines them: players on your own
 * scoreboard team, which is what colours nametags in team modes. Nobody else
 * is ever marked, and without a team nothing is drawn at all. Only players the
 * server already sends you are known, so it shows nothing the game does not
 * already have.
 */
public final class Teamview {

    /** Markers drawn since the self-test last cleared it. */
    public static int drawn;

    private Teamview() {}

    /** Whether {@code other} is on your team. */
    public static boolean teammate(PlayerEntity you, Entity other) {
        if (you == null || !(other instanceof PlayerEntity) || other == you) {
            return false;
        }
        AbstractTeam yours = you.getScoreboardTeam();
        AbstractTeam theirs = ((PlayerEntity) other).getScoreboardTeam();
        return yours != null && theirs != null && yours.isEqual(theirs);
    }

    /** Draws the markers. Called with the world's camera transform. */
    public static void render(MinecraftClient client, float tickDelta) {
        FathomModule module = WorldEffects.active("teamview");
        if (module == null || client.world == null || client.player == null) {
            return;
        }
        EntityRenderDispatcher dispatcher = client.getEntityRenderManager();
        int rgb = module.colour("colour", 0x4FC98A);
        boolean names = module.toggle("names", true);
        double maxDistance = module.number("distance", 128);
        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableDepthTest();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull();
        try {
            for (Object each : client.world.playerEntities) {
                if (!teammate(client.player, (Entity) each)) {
                    continue;
                }
                PlayerEntity mate = (PlayerEntity) each;
                double x = mate.prevTickX + (mate.x - mate.prevTickX) * tickDelta - dispatcher.cameraX;
                double y = mate.prevTickY + (mate.y - mate.prevTickY) * tickDelta - dispatcher.cameraY + mate.height + 0.9;
                double z = mate.prevTickZ + (mate.z - mate.prevTickZ) * tickDelta - dispatcher.cameraZ;
                double distance = Math.sqrt(x * x + y * y + z * z);
                if (distance > maxDistance) {
                    continue;
                }
                marker(client, dispatcher, x, y, z, distance, rgb, names ? mate.getTranslationKey() : null);
                drawn++;
            }
        } finally {
            // Restored even if a marker throws: the hand and the HUD draw next.
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepthTest();
            // Fog is left as this hook found it: vanilla ends the world pass
            // with it disabled, immediately before the hook runs, and nothing
            // between here and the HUD turns it back off.
            GlStateManager.disableFog();
            GlStateManager.enableTexture();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    /** A downward triangle, and the name under it, at a constant size on screen. */
    private static void marker(MinecraftClient client, EntityRenderDispatcher dispatcher, double x, double y,
            double z, double distance, int rgb, String name) {
        float scale = 0.0266F * (float) Math.max(1.0, distance / 8.0);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(-dispatcher.yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(dispatcher.pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableTexture();
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_TRIANGLES, VertexFormats.POSITION_COLOR);
        buffer.vertex(-5, -6, 0.0).color(red, green, blue, 220).next();
        buffer.vertex(5, -6, 0.0).color(red, green, blue, 220).next();
        buffer.vertex(0, 2, 0.0).color(red, green, blue, 220).next();
        tessellator.draw();
        GlStateManager.enableTexture();
        if (name != null) {
            int half = client.textRenderer.getStringWidth(name) / 2;
            client.textRenderer.drawWithShadow(name, -half, -16, 0xFF000000 | rgb);
        }
        GlStateManager.popMatrix();
    }
}
