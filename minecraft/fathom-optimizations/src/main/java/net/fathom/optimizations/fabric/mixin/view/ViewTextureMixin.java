package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.texture.TextureManager;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the texture tick, which uploads animated textures
 * (water, lava, fire, portals). OptiFine's animation and smart-animation
 * options act here.
 */
@Mixin(TextureManager.class)
public abstract class ViewTextureMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewTexturesStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_TEXTURES);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewTexturesEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_TEXTURES);
    }
}
