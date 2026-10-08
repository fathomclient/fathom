package net.fathom.optimizations.fabric.mixin.view;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the client tick, the swap, and vanilla's GL error checks.
 *
 * <p>Applied only when {@code -Dfathom.benchmark=view} (see the Mixin plugin),
 * and every injector is {@code require = 0}: a target OptiFine rewrote is a
 * section the report shows as never called, not a crash.
 */
@Mixin(MinecraftClient.class)
public abstract class ViewClientMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewTickStart(CallbackInfo info) {
        ViewProbe.tally(net.fathom.optimizations.core.bench.view.ViewCounter.TICKS);
        ViewProbe.enter(ViewSection.TICK);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewTickEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK);
    }

    /**
     * The swap. In the drain arm a {@code glFinish} goes first and is timed on
     * its own: whatever the driver and GPU still had queued when the CPU
     * finished the frame.
     */
    @Inject(method = "updateDisplay()V", at = @At("HEAD"), require = 0)
    private void fathom$viewSwapStart(CallbackInfo info) {
        if (ViewProbe.draining()) {
            ViewProbe.enter(ViewSection.DRAIN);
            GL11.glFinish();
            ViewProbe.exit(ViewSection.DRAIN);
        }
        ViewProbe.enter(ViewSection.SWAP);
    }

    @Inject(method = "updateDisplay()V", at = @At("RETURN"), require = 0)
    private void fathom$viewSwapEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.SWAP);
    }

    @Inject(method = "runGameLoop()V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"), require = 0)
    private void fathom$viewYieldStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.YIELD);
    }

    @Inject(method = "runGameLoop()V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V", shift = At.Shift.AFTER),
            require = 0)
    private void fathom$viewYieldEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.YIELD);
    }

    @Inject(method = "setGlErrorMessage(Ljava/lang/String;)V", at = @At("HEAD"), require = 0)
    private void fathom$viewGlErrorStart(String location, CallbackInfo info) {
        ViewProbe.enter(ViewSection.GL_ERROR);
    }

    @Inject(method = "setGlErrorMessage(Ljava/lang/String;)V", at = @At("RETURN"), require = 0)
    private void fathom$viewGlErrorEnd(String location, CallbackInfo info) {
        ViewProbe.exit(ViewSection.GL_ERROR);
    }
}
