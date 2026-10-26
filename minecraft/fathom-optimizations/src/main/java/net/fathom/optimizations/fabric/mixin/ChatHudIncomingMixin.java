package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;

import net.fathom.optimizations.fabric.input.ChatMacros;

/**
 * Each line that reaches chat, read for Auto GG and Bedwars Stats. Nothing
 * about the line or its display changes.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudIncomingMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void fathom$readLine(Text message, CallbackInfo info) {
        if (message != null) {
            ChatMacros.received(MinecraftClient.getInstance(), message.asUnformattedString());
        }
    }
}
