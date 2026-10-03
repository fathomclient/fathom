package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.player.PlayerEntity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: {@code Camera.update}, vanilla's {@code ActiveRenderInfo},
 * which reads three things back from GL every frame. Timed on its own because a
 * readback can wait for the driver to drain what was queued before it.
 */
@Mixin(Camera.class)
public abstract class ViewCameraMixin {

    @Inject(method = "update(Lnet/minecraft/entity/player/PlayerEntity;Z)V", at = @At("HEAD"), require = 0)
    private static void fathom$viewCameraStart(PlayerEntity player, boolean thirdPerson, CallbackInfo info) {
        ViewProbe.enter(ViewSection.CAMERA);
    }

    @Inject(method = "update(Lnet/minecraft/entity/player/PlayerEntity;Z)V", at = @At("RETURN"), require = 0)
    private static void fathom$viewCameraEnd(PlayerEntity player, boolean thirdPerson, CallbackInfo info) {
        ViewProbe.exit(ViewSection.CAMERA);
    }
}
