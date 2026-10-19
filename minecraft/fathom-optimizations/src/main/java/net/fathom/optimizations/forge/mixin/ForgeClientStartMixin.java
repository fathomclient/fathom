package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.FathomOptimizationsClient;
import net.minecraft.client.MinecraftClient;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts Fathom under Forge, where there is no Fabric entrypoint to do it.
 *
 * <p>The moment matches Fabric's as closely as matters: during start-up, once
 * the game's options exist, before the first screen. If Forge's start-up ever
 * moved that assignment out of reach, the first client tick starts Fathom
 * instead, so a missed hook costs the first second of a session rather than
 * the whole client.
 */
@Mixin(MinecraftClient.class)
public abstract class ForgeClientStartMixin {

    @Unique
    private static boolean fathom$started;

    @Inject(method = "initializeGame()V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/MinecraftClient;options:Lnet/minecraft/client/option/GameOptions;",
                    opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER),
            require = 0)
    private void fathom$startWithOptions(CallbackInfo info) {
        fathom$start();
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void fathom$startLate(CallbackInfo info) {
        if (!fathom$started) {
            fathom$start();
        }
    }

    @Unique
    private static void fathom$start() {
        if (fathom$started) {
            return;
        }
        fathom$started = true;
        try {
            new FathomOptimizationsClient().onInitializeClient();
        } catch (Throwable failure) {
            // Fabric would have stopped the game here. Under Forge the player
            // keeps a working game without Fathom, and the log says why.
            System.err.println("[Fathom] client start failed under Forge: " + failure);
            failure.printStackTrace();
        }
    }
}
