package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.world.ClientWorld;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the client world's own tick, and random block display ticks. */
@Mixin(ClientWorld.class)
public abstract class ViewClientWorldMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewWorldStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_WORLD);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewWorldEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_WORLD);
    }

    @Inject(method = "spawnRandomParticles(III)V", at = @At("HEAD"), require = 0)
    private void fathom$viewDisplayStart(int x, int y, int z, CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_DISPLAY);
    }

    @Inject(method = "spawnRandomParticles(III)V", at = @At("RETURN"), require = 0)
    private void fathom$viewDisplayEnd(int x, int y, int z, CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_DISPLAY);
    }
}
