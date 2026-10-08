package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the shadow and fire pass every entity gets, and the
 * label decision for entities that are not living (the living one is in
 * {@link ViewLivingMixin}).
 */
@Mixin(EntityRenderer.class)
public abstract class ViewEntityRendererMixin {

    @Inject(method = "postRender(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), require = 0)
    private void fathom$viewPostStart(Entity entity, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        ViewProbe.enter(ViewSection.SHADOW_FIRE);
    }

    @Inject(method = "postRender(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("RETURN"), require = 0)
    private void fathom$viewPostEnd(Entity entity, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        ViewProbe.exit(ViewSection.SHADOW_FIRE);
    }

    @Inject(method = "method_10208(Lnet/minecraft/entity/Entity;DDD)V", at = @At("HEAD"), require = 0)
    private void fathom$viewNameStart(Entity entity, double x, double y, double z, CallbackInfo info) {
        ViewProbe.enter(ViewSection.NAMETAG_DECIDE);
    }

    @Inject(method = "method_10208(Lnet/minecraft/entity/Entity;DDD)V", at = @At("RETURN"), require = 0)
    private void fathom$viewNameEnd(Entity entity, double x, double y, double z, CallbackInfo info) {
        ViewProbe.exit(ViewSection.NAMETAG_DECIDE);
    }
}
