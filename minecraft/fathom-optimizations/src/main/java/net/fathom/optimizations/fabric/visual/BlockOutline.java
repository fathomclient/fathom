package net.fathom.optimizations.fabric.visual;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Block Outline: the box around the block you are looking at, in your colour,
 * thickness and opacity, with an optional tinted fill.
 *
 * <p>Drawn in place of vanilla's, at the same point in the frame and with the
 * same box (the block's selection box grown by 0.002), so it is hidden by
 * whatever hides vanilla's: never drawn through walls.
 */
public final class BlockOutline {

    /** Outlines drawn since the self-test last cleared it. */
    public static int drawn;

    private BlockOutline() {}

    private static FathomModule active() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("block-outline");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** Draws the outline; true when vanilla's must not be drawn as well. */
    public static boolean draw(PlayerEntity player, BlockHitResult hit, int mode, float tickDelta) {
        FathomModule module = active();
        if (module == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (mode != 0 || hit == null || hit.type != BlockHitResult.Type.BLOCK || world == null || player == null) {
            return true;
        }
        BlockPos pos = hit.getBlockPos();
        Block block = world.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.AIR || !world.getWorldBorder().contains(pos)) {
            return true;
        }
        block.setBoundingBox(world, pos);
        double x = player.prevTickX + (player.x - player.prevTickX) * tickDelta;
        double y = player.prevTickY + (player.y - player.prevTickY) * tickDelta;
        double z = player.prevTickZ + (player.z - player.prevTickZ) * tickDelta;
        Box box = block.getSelectionBox(world, pos).expand(0.002, 0.002, 0.002).offset(-x, -y, -z);

        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture();
        GlStateManager.depthMask(false);
        if (module.toggle("fill", false)) {
            int fill = colour(module, "fill.colour", 0xFFFFFF, "fill.chroma");
            GlStateManager.disableCull();
            fill(box, fill, alpha(module, "fill.opacity", 15));
            GlStateManager.enableCull();
        }
        float width = (float) module.number("thickness", 2);
        if (width > 0.0F) {
            int line = colour(module, "colour", 0x000000, "chroma");
            GL11.glLineWidth(width);
            WorldRenderer.drawBox(box, (line >> 16) & 0xFF, (line >> 8) & 0xFF, line & 0xFF,
                    alpha(module, "opacity", 40));
            GL11.glLineWidth(1.0F);
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture();
        GlStateManager.disableBlend();
        drawn++;
        return true;
    }

    /** A colour setting, or the rainbow when its chroma toggle is on. */
    static int colour(FathomModule module, String id, int fallback, String chroma) {
        if (module.toggle(chroma, false)) {
            float hue = (System.currentTimeMillis() % 4000L) / 4000.0F;
            return java.awt.Color.HSBtoRGB(hue, 0.8F, 1.0F) & 0xFFFFFF;
        }
        return module.colour(id, fallback);
    }

    static int alpha(FathomModule module, String id, int fallbackPercent) {
        return (int) Math.round(Math.max(0.0, Math.min(100.0, module.number(id, fallbackPercent))) * 2.55);
    }

    private static void fill(Box box, int rgb, int alpha) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        double[][] faces = {
            {box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ},
            {box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ},
            {box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.minY, box.minZ},
            {box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ},
            {box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ},
            {box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.minY, box.maxZ},
        };
        for (double[] face : faces) {
            for (int corner = 0; corner < 12; corner += 3) {
                buffer.vertex(face[corner], face[corner + 1], face[corner + 2]).color(red, green, blue, alpha).next();
            }
        }
        tessellator.draw();
    }
}
