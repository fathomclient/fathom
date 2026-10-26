package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.fabric.input.Freelook;

/**
 * Freelook: while it is on, the mouse turns the camera and not the player,
 * and the camera is set up with the free angles. See {@link Freelook}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFreelookMixin {

    @Redirect(method = "render(FJ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/ClientPlayerEntity;increaseTransforms(FF)V"))
    private void fathom$look(ClientPlayerEntity player, float dx, float dy) {
        if (!Freelook.look(dx, dy)) {
            player.increaseTransforms(dx, dy);
        }
    }

    @Inject(method = "transformCamera(F)V", at = @At("HEAD"))
    private void fathom$freeCamera(float tickDelta, CallbackInfo info) {
        Freelook.beginCamera(MinecraftClient.getInstance().getCameraEntity());
    }

    @Inject(method = "transformCamera(F)V", at = @At("RETURN"))
    private void fathom$ownAngles(float tickDelta, CallbackInfo info) {
        Freelook.endCamera();
    }
}
