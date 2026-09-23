package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.hud.InGameHud;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.Probe;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/** Aggregate timing for GuiIngame.renderGameOverlay. */
@Mixin(InGameHud.class)
public abstract class InGameHudTimingMixin {
    private long fathom$coreHudProbe;

    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void fathom$coreHudStart(float tickDelta, CallbackInfo info) {
        fathom$coreHudProbe = BenchmarkState.profileStart();
        BenchmarkState.count(RenderCounters.Counter.VANILLA_HUD);
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void fathom$coreHudEnd(float tickDelta, CallbackInfo info) {
        BenchmarkState.profileStop(Probe.Section.VANILLA_HUD, fathom$coreHudProbe);
    }
}
