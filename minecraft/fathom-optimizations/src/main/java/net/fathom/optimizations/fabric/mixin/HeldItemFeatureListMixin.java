package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ItemModelLists;
import net.minecraft.client.render.entity.feature.HeldItemRenderer;
import net.minecraft.entity.LivingEntity;

/** {@code render.entity.item-model-lists}: the window in which item models may be replayed. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemFeatureListMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("HEAD"))
    private void fathom$open(LivingEntity entity, float a, float b, float c, float d, float e, float f, float g,
            CallbackInfo info) {
        ItemModelLists.active = Runtime.itemModelLists;
    }

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("RETURN"))
    private void fathom$close(LivingEntity entity, float a, float b, float c, float d, float e, float f, float g,
            CallbackInfo info) {
        ItemModelLists.active = false;
    }
}
