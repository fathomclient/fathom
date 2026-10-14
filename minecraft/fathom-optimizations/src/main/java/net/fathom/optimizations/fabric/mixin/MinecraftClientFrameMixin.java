package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.fabric.Runtime;

/**
 * Times each frame.
 *
 * <p>The target is {@code runGameLoop()V}, which is 1.8.9's per-frame method. It was
 * written as {@code render} against a guess at the mappings, and {@code render} does not
 * exist on {@code MinecraftClient} in this version — so the injection silently failed to
 * apply and no frame was ever timed. {@code injectors.defaultRequire} of 0 is what made
 * that silent, which is the cost of the setting and the reason a behaviour-changing Mixin
 * must not use it.
 *
 * <p>This Mixin changes nothing: it reads the clock
 * at the start and end of the render loop and hands the difference to
 * {@link Runtime#frame}. An instrumentation Mixin that altered behaviour would be
 * measuring itself.
 *
 * <h2>Why the timing is taken here</h2>
 *
 * <p>At the render call rather than around the buffer swap. The swap blocks on VSync, so
 * timing across it measures the display's refresh interval rather than the game's work.
 * Both are worth knowing and they are different questions; this one measures the work.
 * {@code docs/performance/benchmark-methodology.md} requires VSync off for exactly this
 * reason.
 *
 * <h2>Why `defaultRequire` is now 1</h2>
 *
 * <p>{@code fathom.mixins.json} sets {@code injectors.defaultRequire} to 1, so a Mixin
 * that fails to apply CRASHES the game instead of being skipped.
 *
 * <p>It was 0, and that is precisely how this Mixin sat broken: it targeted
 * {@code render}, which does not exist on {@code MinecraftClient} in 1.8.9, and the
 * failure was silent. No frame was ever timed and nothing said so. The setting had been
 * chosen so that losing a measurement would not lose a session; the price was losing the
 * measurement without anybody noticing for a whole milestone.
 *
 * <p>With a behaviour-changing optimization now shipping, the trade runs the other way. A
 * Mixin that half-applies can leave the renderer inconsistent, and a loud failure at
 * startup is much better than a quiet wrong answer on somebody's screen. A silent skip is
 * only tolerable when the thing being skipped does not matter, and once one Mixin in the
 * set matters, it is not tolerable for any of them.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFrameMixin {

    /**
     * Not an instance field of the Mixin target by accident: Mixin adds this to
     * {@code MinecraftClient} itself, and the client is a singleton on one thread, so a
     * plain long is correct and costs nothing.
     */
    private long fathom$frameStartNanos;
    private long fathom$gameLoopProbe;

    @Inject(method = "runGameLoop()V", at = @At("HEAD"))
    private void fathom$frameStart(CallbackInfo info) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            // The view benchmark's frame boundary: first, so the previous frame
            // ends before any of this frame's work, Fathom's included, begins.
            net.fathom.optimizations.core.bench.view.ViewProbe.loopHead();
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(
                    net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_FRAME);
        }
        fathom$frameStartNanos = System.nanoTime();
        fathom$gameLoopProbe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.GAME_LOOP);
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        long callback = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowEventDispatch()) {
            long dispatch = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.EVENT_DISPATCH);
            net.fathom.optimizations.fabric.FathomOptimizationsClient.ensureReloadHook();
            net.fathom.optimizations.fabric.gfx.FrameInput.poll(client);
            net.fathom.optimizations.fabric.input.ModuleKeys.poll(client);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.EVENT_LISTENER);
            net.fathom.optimizations.fabric.gfx.FrameKeyboard.pollCurrent(client);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.EVENT_LISTENER);
            net.fathom.optimizations.fabric.input.GameSession.frame(client);
            if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
                net.fathom.optimizations.fabric.diag.ModuleSelfTest.frame(client);
            }
            if (net.fathom.optimizations.fabric.diag.EditorSelfTest.ACTIVE) {
                net.fathom.optimizations.fabric.diag.EditorSelfTest.frame(client);
            }
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.EVENT_LISTENER);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.EVENT_DISPATCH, dispatch);
        }
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowCoreHooks()
                && net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
            // A vanilla title screen that arrived before Fathom finished loading.
            net.fathom.optimizations.fabric.menu.MenuTakeover.frame(client);
        }
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, callback);
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.exit(
                    net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_FRAME);
        }
    }

    @Inject(method = "runGameLoop()V", at = @At("RETURN"))
    private void fathom$frameEnd(CallbackInfo info) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.loopReturn();
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(
                    net.fathom.optimizations.core.bench.view.ViewSection.POST_FRAME);
        }
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        long callback = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                net.fathom.optimizations.core.instrument.Probe.Section.GAME_LOOP,
                fathom$gameLoopProbe);
        long start = fathom$frameStartNanos;
        long frameNanos = start == 0L ? 0L : System.nanoTime() - start;
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, callback);
        if (start != 0L) {
            Runtime.frame(frameNanos);
            // The instrument's reading of play. The frame's end is the clock just
            // read, so this costs no second timestamp. Left out of a benchmark arm
            // that bypasses Fathom's hooks, so that arm measures none of Fathom.
            if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowCoreHooks()) {
                net.fathom.optimizations.fabric.diag.SessionPulse.frame(
                        (MinecraftClient) (Object) this, start + frameNanos);
            }
        }
        if (net.fathom.optimizations.fabric.bench.PerfBench.ACTIVE) {
            net.fathom.optimizations.fabric.bench.PerfBench.frame((MinecraftClient) (Object) this, frameNanos);
        }
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.exit(
                    net.fathom.optimizations.core.bench.view.ViewSection.POST_FRAME);
        }
    }
}
