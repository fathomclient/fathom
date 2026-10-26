package net.fathom.optimizations.fabric.visual;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.entity.ItemEntity;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Item Physics: dropped items lie on the ground instead of floating and
 * spinning, and tumble while they fall.
 *
 * <p>Only the transform vanilla applies before drawing a dropped item is
 * replaced; the item, its count and where it is are the server's, so nothing
 * about pickup or collisions changes.
 */
public final class ItemPhysics {

    /** Items transformed since the self-test last cleared it. */
    public static int transformed;

    private ItemPhysics() {}

    public static FathomModule active() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("item-physics");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /**
     * Applies the transform for {@code item}, as vanilla's own method would,
     * and returns how many copies of the model to draw.
     */
    public static int transform(FathomModule module, ItemEntity item, double x, double y, double z,
            float tickDelta, BakedModel model, int copies) {
        boolean block = model.hasDepth();
        float age = item.getAge() + tickDelta;
        // A fixed turn per item, so a pile does not line up and nothing spins on the ground.
        float yaw = (item.hoverHeight * 57.295776F) % 360.0F;
        float spin = (float) module.number("speed", 100) / 100.0F;
        if (block) {
            float scale = model.getTransformation().getTransformation(ModelTransformation.Mode.GROUND).scale.y;
            GlStateManager.translate((float) x, (float) y + 0.25F * scale, (float) z);
            GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
            if (!item.onGround && spin > 0.0F) {
                GlStateManager.rotate(age * 12.0F * spin, 1.0F, 0.0F, 0.0F);
            }
        } else {
            float stack = 0.046875F * (copies - 1) * 0.5F;
            GlStateManager.translate((float) x, (float) y + 0.02F + stack, (float) z);
            GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
            if (!item.onGround && spin > 0.0F) {
                GlStateManager.rotate(age * 12.0F * spin, 0.0F, 1.0F, 0.0F);
            }
            GlStateManager.translate(0.0F, 0.0F, -stack);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        transformed++;
        return copies;
    }
}
