package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.fabric.visual.Waypoints;

/** {@code /fwp}: Waypoints' command, answered here and never sent to the server. */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityCommandMixin {

    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void fathom$clientCommand(String message, CallbackInfo info) {
        if (Waypoints.command(MinecraftClient.getInstance(), message)) {
            info.cancel();
        }
    }
}
