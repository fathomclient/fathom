package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;

import net.fathom.optimizations.fabric.visual.Nametags;

/**
 * Nametags: your own label in third person. Vanilla never labels the camera's
 * entity; every other entity's answer is left as it was.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererOwnNameMixin {

    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void fathom$ownName(LivingEntity entity, CallbackInfoReturnable<Boolean> info) {
        if (!info.getReturnValueZ() && Nametags.showOwn(entity) && !entity.isInvisible()
                && net.minecraft.client.MinecraftClient.isHudEnabled()) {
            info.setReturnValue(Boolean.TRUE);
        }
    }
}
