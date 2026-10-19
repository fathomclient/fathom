package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.FrameItems;
import net.minecraft.client.render.entity.ItemFrameEntityRenderer;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * {@code render.entity.item-frame-entity-reuse}: see {@link FrameItems}. Wraps the one
 * {@code new ItemEntity(...)} in the frame's item path; everything the renderer then does
 * with it is vanilla's (and OptiFine's).
 */
@Mixin(ItemFrameEntityRenderer.class)
public abstract class ItemFrameItemReuseMixin {

    @WrapOperation(method = "method_4334(Lnet/minecraft/entity/decoration/ItemFrameEntity;)V",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/world/World;DDDLnet/minecraft/item/ItemStack;)Lnet/minecraft/entity/ItemEntity;"))
    private ItemEntity fathom$reuseItemEntity(World world, double x, double y, double z, ItemStack stack,
            Operation<ItemEntity> original) {
        if (Runtime.itemFrameEntityReuse) {
            ItemEntity kept = FrameItems.reuse(world, stack);
            if (kept != null) {
                return kept;
            }
            ItemEntity made = original.call(world, x, y, z, stack);
            FrameItems.keep(made);
            return made;
        }
        return original.call(world, x, y, z, stack);
    }
}
