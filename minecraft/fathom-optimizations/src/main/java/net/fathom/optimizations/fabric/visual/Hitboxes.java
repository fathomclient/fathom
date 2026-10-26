package net.fathom.optimizations.fabric.visual;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Hitboxes: vanilla's F3+B boxes, always on, with a colour, thickness and a
 * choice of which entities get one.
 *
 * <p>Drawn where vanilla draws its own, right after each entity, and only for
 * entities the game is drawing: an entity behind a wall is not drawn, so
 * neither is its box, and the box itself is depth-tested like vanilla's.
 */
public final class Hitboxes {

    /** Boxes drawn since the self-test last cleared it. */
    public static int drawn;

    private Hitboxes() {}

    private static FathomModule active() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("hitboxes");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** Whether boxes are wanted this frame, in addition to vanilla's own F3+B switch. */
    public static boolean forced() {
        return active() != null;
    }

    /** Draws the box for {@code entity}; true when vanilla's must not be drawn as well. */
    public static boolean draw(Entity entity, double x, double y, double z, float tickDelta) {
        FathomModule module = active();
        if (module == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!shown(module, client, entity)) {
            return true;
        }
        int rgb = BlockOutline.colour(module, "colour", 0xFFFFFF, "chroma");
        if (module.toggle("target", true) && entity == client.targetedEntity) {
            rgb = module.colour("target.colour", 0x55FF55);
        }
        if (module.toggle("hurt", true) && entity instanceof LivingEntity && ((LivingEntity) entity).hurtTime > 0) {
            rgb = module.colour("hurt.colour", 0xFF5555);
        }
        int alpha = BlockOutline.alpha(module, "opacity", 100);

        GlStateManager.depthMask(false);
        GlStateManager.disableTexture();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth((float) module.number("thickness", 2));

        Box bounds = entity.getBoundingBox();
        Box box = new Box(bounds.minX - entity.x + x, bounds.minY - entity.y + y, bounds.minZ - entity.z + z,
                bounds.maxX - entity.x + x, bounds.maxY - entity.y + y, bounds.maxZ - entity.z + z);
        WorldRenderer.drawBox(box, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);

        if (entity instanceof LivingEntity) {
            float eye = entity.getEyeHeight();
            if (module.toggle("eyes", true)) {
                double half = entity.width / 2.0F;
                WorldRenderer.drawBox(new Box(x - half, y + eye - 0.01, z - half, x + half, y + eye + 0.01, z + half),
                        255, 0, 0, alpha);
            }
            if (module.toggle("look", true)) {
                Vec3d look = entity.getRotationVector(tickDelta);
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder buffer = tessellator.getBuffer();
                buffer.begin(GL11.GL_LINE_STRIP, VertexFormats.POSITION_COLOR);
                buffer.vertex(x, y + eye, z).color(0, 0, 255, alpha).next();
                buffer.vertex(x + look.x * 2.0, y + eye + look.y * 2.0, z + look.z * 2.0).color(0, 0, 255, alpha).next();
                tessellator.draw();
            }
        }

        GL11.glLineWidth(1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture();
        GlStateManager.enableLighting();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        drawn++;
        return true;
    }

    private static boolean shown(FathomModule module, MinecraftClient client, Entity entity) {
        if (entity == client.player && !module.toggle("self", true)) {
            return false;
        }
        if (entity instanceof PlayerEntity) {
            return module.toggle("players", true);
        }
        if (entity instanceof ItemEntity) {
            return module.toggle("items", false);
        }
        if (entity instanceof LivingEntity) {
            return module.toggle("mobs", true);
        }
        return module.toggle("other", false);
    }
}
