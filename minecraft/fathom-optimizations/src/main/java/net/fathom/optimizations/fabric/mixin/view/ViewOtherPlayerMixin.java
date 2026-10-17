package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.entity.player.OtherClientPlayerEntity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: one other player's client tick. It runs in the tick
 * frame, roughly one frame in (fps / 20), which is where p99 comes from.
 */
@Mixin(OtherClientPlayerEntity.class)
public abstract class ViewOtherPlayerMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewPlayerTickStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_PLAYER);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewPlayerTickEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_PLAYER);
    }
}
