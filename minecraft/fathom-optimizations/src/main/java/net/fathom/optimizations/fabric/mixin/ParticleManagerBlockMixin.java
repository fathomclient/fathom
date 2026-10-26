package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.BlockState;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import net.fathom.optimizations.fabric.visual.WorldEffects;

/** Particles: block-breaking particles hidden, when asked. */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerBlockMixin {

    @Inject(method = "addBlockBreakParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$hideBreak(BlockPos pos, BlockState state, CallbackInfo info) {
        if (WorldEffects.hideBlockParticles()) {
            info.cancel();
        }
    }

    @Inject(method = "addBlockBreakingParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$hideBreaking(BlockPos pos, Direction side, CallbackInfo info) {
        if (WorldEffects.hideBlockParticles()) {
            info.cancel();
        }
    }
}
