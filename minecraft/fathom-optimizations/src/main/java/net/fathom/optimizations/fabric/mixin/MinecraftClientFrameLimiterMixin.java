package net.fathom.optimizations.fabric.mixin;

import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.opt.frames.FramePacer;
import net.fathom.optimizations.fabric.Runtime;

/**
 * The frame cap's wait -- {@code input.frame-limiter-accuracy}. See {@link FramePacer}.
 *
 * <p>Only the call in {@code runGameLoop} is replaced: it is the one that
 * paces gameplay, and it is reached only when a cap is set (vanilla skips it
 * at "Unlimited"). Switched off, it is vanilla's {@code Display.sync} exactly.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFrameLimiterMixin {

    @Unique
    private static final FramePacer FATHOM$PACER = new FramePacer();

    @Redirect(method = "runGameLoop()V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;sync(I)V", remap = false))
    private void fathom$pace(int fps) {
        if (Runtime.frameLimiter) {
            FATHOM$PACER.sync(fps, FramePacer.SYSTEM);
        } else {
            FATHOM$PACER.reset();
            Display.sync(fps);
        }
    }
}
