package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.BaseFrustum;
import net.minecraft.client.render.Frustum;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: building the frustum, which reads both matrices back from GL. */
@Mixin(Frustum.class)
public abstract class ViewFrustumMixin {

    @Inject(method = "getInstance()Lnet/minecraft/client/render/BaseFrustum;", at = @At("HEAD"), require = 0)
    private static void fathom$viewFrustumStart(CallbackInfoReturnable<BaseFrustum> info) {
        ViewProbe.enter(ViewSection.FRUSTUM);
    }

    @Inject(method = "getInstance()Lnet/minecraft/client/render/BaseFrustum;", at = @At("RETURN"), require = 0)
    private static void fathom$viewFrustumEnd(CallbackInfoReturnable<BaseFrustum> info) {
        ViewProbe.exit(ViewSection.FRUSTUM);
    }
}
