package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.World;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: entity and block-entity ticking. The integrated server
 * ticks its own world through the same method on its own thread; the probe
 * times the client thread only.
 */
@Mixin(World.class)
public abstract class ViewWorldTickMixin {

    @Inject(method = "tickEntities()V", at = @At("HEAD"), require = 0)
    private void fathom$viewEntitiesStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_ENTITIES);
    }

    @Inject(method = "tickEntities()V", at = @At("RETURN"), require = 0)
    private void fathom$viewEntitiesEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_ENTITIES);
    }
}
