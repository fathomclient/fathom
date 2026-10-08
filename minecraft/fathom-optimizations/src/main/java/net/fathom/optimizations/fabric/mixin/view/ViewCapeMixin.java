package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the cape layer, which runs for every player whether or not one is worn. */
@Mixin(CapeFeatureRenderer.class)
public abstract class ViewCapeMixin {

    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFFFFFF)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewCapeStart(AbstractClientPlayerEntity entity, float a, float b, float c, float d,
            float e, float f, float g, CallbackInfo info) {
        ViewProbe.enter(ViewSection.CAPE);
    }

    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewCapeEnd(AbstractClientPlayerEntity entity, float a, float b, float c, float d,
            float e, float f, float g, CallbackInfo info) {
        ViewProbe.exit(ViewSection.CAPE);
    }
}
