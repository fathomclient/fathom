package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gl.Framebuffer;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the world framebuffer drawn to the window, once a frame. */
@Mixin(Framebuffer.class)
public abstract class ViewFramebufferMixin {

    @Inject(method = "draw(II)V", at = @At("HEAD"), require = 0)
    private void fathom$viewBlitStart(int width, int height, CallbackInfo info) {
        ViewProbe.enter(ViewSection.BLIT);
    }

    @Inject(method = "draw(II)V", at = @At("RETURN"), require = 0)
    private void fathom$viewBlitEnd(int width, int height, CallbackInfo info) {
        ViewProbe.exit(ViewSection.BLIT);
    }
}
