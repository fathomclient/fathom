package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.fabric.opt.WorldChangeGc;

/**
 * Routes vanilla's two world-change {@code System.gc()} calls through
 * {@link WorldChangeGc}. The shutdown and out-of-memory recovery collections in
 * {@code stop}, {@code run} and {@code cleanUpAfterCrash} are not touched.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientWorldGcMixin {

    @Redirect(method = "startIntegratedServer(Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/world/level/LevelInfo;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V"))
    private void fathom$singleplayerGc() {
        WorldChangeGc.requested("singleplayer start");
    }

    @Redirect(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V"))
    private void fathom$worldChangeGc() {
        WorldChangeGc.requested("world change");
    }
}
