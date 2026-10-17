package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import net.fathom.optimizations.fabric.menu.FathomMenuScreen;

/**
 * Joining a world gives back what only the menus draw.
 *
 * <p>Every background the title screen or the picker has shown keeps its
 * full-resolution texture (about 8 MB of video memory each at 1080p), the
 * picker keeps its thumbnails and the menu video its streaming frame. None of
 * that is drawn while playing, so once {@code connect} has a world it is
 * released, except the selected background, which the Hub can draw over the
 * world. Anything released comes back from the jar the next time a menu asks.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMenuReleaseMixin {

    @Inject(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("TAIL"))
    private void fathom$releaseMenus(ClientWorld world, String message, CallbackInfo ci) {
        if (world != null) {
            FathomMenuScreen.releaseForPlay();
        }
    }
}
