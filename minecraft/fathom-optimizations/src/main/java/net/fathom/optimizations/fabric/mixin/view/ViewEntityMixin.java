package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: each entity draw. The world path calls
 * {@code doRenderEntity} ({@code method_6913}) directly, which is why it is the
 * boundary rather than the wrapper.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ViewEntityMixin {

    @Inject(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("HEAD"), require = 0)
    private void fathom$viewEntityStart(Entity entity, double x, double y, double z, float yaw,
            float tickDelta, boolean hideDebugBox, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.enter(ViewSection.ENTITY);
    }

    @Inject(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("RETURN"), require = 0)
    private void fathom$viewEntityEnd(Entity entity, double x, double y, double z, float yaw,
            float tickDelta, boolean hideDebugBox, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.exit(ViewSection.ENTITY);
    }
}
