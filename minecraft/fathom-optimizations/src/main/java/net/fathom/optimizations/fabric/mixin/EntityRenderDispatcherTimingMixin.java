package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/**
 * Aggregate timing for RenderManager.doRenderEntity; no per-entity logging.
 *
 * <p>Targets doRenderEntity itself ({@code method_6913}), not renderEntityWithPosYaw
 * ({@code render(Entity,DDDFF)Z}): the world path, renderEntityStatic, calls
 * doRenderEntity directly, so hooking the wrapper counted zero in-world entities.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherTimingMixin {
    private long fathom$coreEntityProbe;

    @Inject(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("HEAD"))
    private void fathom$coreEntityStart(Entity entity, double x, double y, double z,
            float yaw, float tickDelta, boolean hideDebugBox, CallbackInfoReturnable<Boolean> info) {
        fathom$coreEntityProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.RENDER_ENTITY);
    }

    @Inject(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("RETURN"))
    private void fathom$coreEntityEnd(Entity entity, double x, double y, double z,
            float yaw, float tickDelta, boolean hideDebugBox, CallbackInfoReturnable<Boolean> info) {
        BenchmarkState.profileStop(Probe.Section.RENDER_ENTITY, fathom$coreEntityProbe);
    }
}
