package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.world.BuiltChunk;

import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;

/**
 * View benchmark only: a render section marked dirty ({@code setNeedsUpdate(true)},
 * Yarn's {@code method_10162}). Counted, because "something keeps invalidating
 * sections" is one of the ways a renderer can rebuild far more than it should.
 */
@Mixin(BuiltChunk.class)
public abstract class ViewBuiltChunkMixin {

    @Inject(method = "method_10162(Z)V", at = @At("HEAD"), require = 0)
    private void fathom$viewInvalidated(boolean dirty, CallbackInfo info) {
        if (dirty) {
            ViewProbe.count(ViewCounter.INVALIDATIONS, 1L);
        }
    }
}
