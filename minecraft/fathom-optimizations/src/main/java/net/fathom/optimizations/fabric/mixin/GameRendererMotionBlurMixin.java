package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.visual.MotionBlur;

/** Motion Blur, between the world and the HUD. See {@link MotionBlur}. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMotionBlurMixin {

    @Inject(method = "render(FJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJ)V",
                    shift = At.Shift.AFTER))
    private void fathom$motionBlur(float tickDelta, long nanoTime, CallbackInfo info) {
        MotionBlur.afterWorld(MinecraftClient.getInstance());
    }
}
