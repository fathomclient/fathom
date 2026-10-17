package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the parts of a living entity's draw -- body, layers and
 * the label decision -- so one player's cost can be taken apart.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ViewLivingMixin {

    @Inject(method = "renderModel(Lnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), require = 0)
    private void fathom$viewModelStart(LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo info) {
        ViewProbe.enter(ViewSection.MODEL);
    }

    @Inject(method = "renderModel(Lnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At("RETURN"), require = 0)
    private void fathom$viewModelEnd(LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo info) {
        ViewProbe.exit(ViewSection.MODEL);
    }

    @Inject(method = "renderFeatures(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("HEAD"), require = 0)
    private void fathom$viewFeaturesStart(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.enter(ViewSection.FEATURES);
    }

    @Inject(method = "renderFeatures(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("RETURN"), require = 0)
    private void fathom$viewFeaturesEnd(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.exit(ViewSection.FEATURES);
    }

    /** {@code renderName} for living entities: visibility rules, the display name, the label. */
    @Inject(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V", at = @At("HEAD"), require = 0)
    private void fathom$viewNameStart(LivingEntity entity, double x, double y, double z, CallbackInfo info) {
        ViewProbe.enter(ViewSection.NAMETAG_DECIDE);
    }

    @Inject(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V", at = @At("RETURN"), require = 0)
    private void fathom$viewNameEnd(LivingEntity entity, double x, double y, double z, CallbackInfo info) {
        ViewProbe.exit(ViewSection.NAMETAG_DECIDE);
    }
}
