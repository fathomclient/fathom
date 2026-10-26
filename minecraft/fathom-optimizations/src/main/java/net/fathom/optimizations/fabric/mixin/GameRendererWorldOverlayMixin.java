package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.visual.Teamview;
import net.fathom.optimizations.fabric.visual.Waypoints;

/**
 * Waypoints and Teamview, drawn into the world after everything else in it
 * and before the hand, with the world's camera transform still in place.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererWorldOverlayMixin {

    @Inject(method = "renderWorld(IFJ)V",
            at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V",
                    args = "ldc=hand"))
    private void fathom$worldOverlays(int pass, float tickDelta, long limit, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        Waypoints.render(client);
        Teamview.render(client, tickDelta);
    }
}
