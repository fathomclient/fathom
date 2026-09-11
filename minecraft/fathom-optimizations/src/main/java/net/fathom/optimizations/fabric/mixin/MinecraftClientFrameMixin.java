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

    @Inject(method = "runGameLoop()V", at = @At("HEAD"))
    private void fathom$frameStart(CallbackInfo info) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        net.fathom.optimizations.fabric.gfx.FrameInput.poll(client);
        net.fathom.optimizations.fabric.gfx.FrameKeyboard.pollCurrent(client);
        fathom$frameStartNanos = System.nanoTime();
    }

    @Inject(method = "runGameLoop()V", at = @At("RETURN"))
    private void fathom$frameEnd(CallbackInfo info) {
        long start = fathom$frameStartNanos;
        if (start != 0L) {
            Runtime.frame(System.nanoTime() - start);
        }
    }
}
