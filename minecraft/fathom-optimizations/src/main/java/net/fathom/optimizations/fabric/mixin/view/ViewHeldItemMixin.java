package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.feature.HeldItemRenderer;
import net.minecraft.entity.LivingEntity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the item another entity holds. */
@Mixin(HeldItemRenderer.class)
public abstract class ViewHeldItemMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("HEAD"), require = 0)
    private void fathom$viewHeldStart(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HELD_ITEM);
    }

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("RETURN"), require = 0)
    private void fathom$viewHeldEnd(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HELD_ITEM);
    }
}
