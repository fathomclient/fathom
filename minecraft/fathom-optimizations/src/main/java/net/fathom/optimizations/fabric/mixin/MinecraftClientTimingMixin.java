package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/** Aggregate-only timing for Minecraft.runTick (Yarn: MinecraftClient.tick). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTimingMixin {
    private long fathom$coreTickProbe;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void fathom$coreTickStart(CallbackInfo info) {
        fathom$coreTickProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.CLIENT_TICK);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void fathom$coreTickEnd(CallbackInfo info) {
        BenchmarkState.profileStop(Probe.Section.TICK, fathom$coreTickProbe);
    }
}
