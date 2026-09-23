package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.GameRenderer;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/** Timings for EntityRenderer.updateCameraAndRender and renderWorld. */
@Mixin(GameRenderer.class)
public abstract class GameRendererTimingMixin {
    private long fathom$coreCameraProbe;
    private long fathom$coreWorldProbe;

    @Inject(method = "render(FJ)V", at = @At("HEAD"))
    private void fathom$coreCameraStart(float tickDelta, long limitTime, CallbackInfo info) {
        fathom$coreCameraProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.CAMERA_AND_RENDER);
    }

    @Inject(method = "render(FJ)V", at = @At("RETURN"))
    private void fathom$coreCameraEnd(float tickDelta, long limitTime, CallbackInfo info) {
        BenchmarkState.profileStop(Probe.Section.CAMERA_AND_RENDER, fathom$coreCameraProbe);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("HEAD"))
    private void fathom$coreWorldStart(float tickDelta, long limitTime, CallbackInfo info) {
        fathom$coreWorldProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.RENDER_WORLD);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"))
    private void fathom$coreWorldEnd(float tickDelta, long limitTime, CallbackInfo info) {
        BenchmarkState.profileStop(Probe.Section.WORLD, fathom$coreWorldProbe);
    }
}
