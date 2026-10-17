package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.Zoom;

/**
 * The Zoom module, where vanilla reads what it changes.
 *
 * <h2>Why {@code getFov} and not the option</h2>
 *
 * <p>Writing {@code GameOptions.fov} would zoom, and would also write the
 * player's own field of view to disk the next time anything saved the options —
 * so a client that crashed while zoomed would come back permanently at 25
 * degrees. This changes the value vanilla computed for this frame and nothing
 * else. The target is {@code getFov(FZ)F}, confirmed against the 1.8.9 bytecode,
 * injected at {@code RETURN} so vanilla's own movement and status-effect
 * adjustments are already in the number being divided.
 *
 * <p>Only when {@code changingFov} is true: that is the world's projection.
 * The hand is projected with {@code false}, and dividing its field of view too
 * made the arm fill the screen while zoomed.
 *
 * <h2>Sensitivity and smooth camera, the same way</h2>
 *
 * <p>Both are read from {@code GameOptions} by {@code render} (mouse to camera)
 * and {@code tick} (the smoothing filter). Redirecting those reads gives the
 * zoomed values to exactly the code that turns the camera, and leaves the
 * options — which Minecraft saves — untouched.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

    @Inject(method = "getFov(FZ)F", at = @At("RETURN"), cancellable = true)
    private void fathom$zoom(float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> info) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) return;
            if (!changingFov || Runtime.hud() == null) return;
            float factor = Zoom.factor();
            if (factor > 1f) info.setReturnValue(Float.valueOf(info.getReturnValueF() / factor));
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }

    @Redirect(method = {"render(FJ)V", "tick()V"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;sensitivity:F",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private float fathom$zoomSensitivity(GameOptions options) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.OPTIFINE_OVERLAP_HOOK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                    || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
                return options.sensitivity;
            }
            return Zoom.sensitivity(options.sensitivity);
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }

    @Redirect(method = {"render(FJ)V", "tick()V"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;smoothCameraEnabled:Z",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean fathom$zoomSmoothCamera(GameOptions options) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.OPTIFINE_OVERLAP_HOOK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                    || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
                return options.smoothCameraEnabled;
            }
            return Zoom.smoothCamera(options.smoothCameraEnabled);
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }

    @Inject(method = "renderHand(FI)V", at = @At("HEAD"), cancellable = true)
    private void fathom$hideHandWhileZoomed(float tickDelta, int anaglyphFilter, CallbackInfo info) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) return;
            if (Zoom.hideHand()) info.cancel();
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }
}
