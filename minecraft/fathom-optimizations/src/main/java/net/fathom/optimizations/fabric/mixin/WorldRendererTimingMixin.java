package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/** Aggregate timings for RenderGlobal entity and chunk-layer boundaries. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererTimingMixin {
    private long fathom$coreEntitiesProbe;
    private long fathom$coreChunksProbe;

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V", at = @At("HEAD"))
    private void fathom$coreEntitiesStart(Entity entity, CameraView camera, float tickDelta,
            CallbackInfo info) {
        fathom$coreEntitiesProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.RENDER_ENTITIES);
    }

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V", at = @At("RETURN"))
    private void fathom$coreEntitiesEnd(Entity entity, CameraView camera, float tickDelta,
            CallbackInfo info) {
        BenchmarkState.profileStop(Probe.Section.RENDER_ENTITIES, fathom$coreEntitiesProbe);
    }

    @Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"))
    private void fathom$coreChunksStart(RenderLayer layer, double tickDelta, int anaglyph,
            Entity entity, CallbackInfoReturnable<Integer> info) {
        fathom$coreChunksProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.CHUNK_RENDER);
    }

    @Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("RETURN"))
    private void fathom$coreChunksEnd(RenderLayer layer, double tickDelta, int anaglyph,
            Entity entity, CallbackInfoReturnable<Integer> info) {
        BenchmarkState.profileStop(Probe.Section.CHUNK_RENDER, fathom$coreChunksProbe);
    }
}
