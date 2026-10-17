package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.entity.LivingEntity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the armour layer, all four slots and their glint. */
@Mixin(ArmorFeatureRenderer.class)
public abstract class ViewArmorMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("HEAD"), require = 0)
    private void fathom$viewArmorStart(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.enter(ViewSection.ARMOR);
    }

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V", at = @At("RETURN"), require = 0)
    private void fathom$viewArmorEnd(LivingEntity entity, float a, float b, float c, float d, float e,
            float f, float g, CallbackInfo info) {
        ViewProbe.exit(ViewSection.ARMOR);
    }
}
