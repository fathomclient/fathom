package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.visual.ItemPhysics;

/**
 * Item Physics: Fathom's transform for a dropped item in place of vanilla's
 * bob and spin. See {@link ItemPhysics}.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererPhysicsMixin {

    @Shadow
    protected abstract int method_10222(ItemStack stack);

    @Inject(method = "method_10221(Lnet/minecraft/entity/ItemEntity;DDDFLnet/minecraft/client/render/model/BakedModel;)I",
            at = @At("HEAD"), cancellable = true)
    private void fathom$physics(ItemEntity item, double x, double y, double z, float tickDelta, BakedModel model,
            CallbackInfoReturnable<Integer> info) {
        FathomModule module = ItemPhysics.active();
        if (module == null) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getItem() == null) {
            return;
        }
        info.setReturnValue(ItemPhysics.transform(module, item, x, y, z, tickDelta, model, method_10222(stack)));
    }
}
