package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.Runtime;

/**
 * The narrow exceptions around Minecraft's frame-limit decision.
 *
 * <h2>What vanilla does, exactly</h2>
 *
 * <p>{@code getMaxFramerate} returns 30 when there is no world <em>and</em> a screen is
 * open, and the user's configured cap otherwise. So the title screen is capped and nothing
 * else is: an inventory over a loaded world renders a frozen scene at 300 fps, and a
 * window you have alt-tabbed away from renders at full speed into a surface nobody is
 * looking at.
 *
 * <h2>Why this cannot make anything worse</h2>
 *
 * <p>Fathom never adds a general {@code currentScreen != null} cap. The only lowering is
 * the explicit unfocused-window preference. The only raising is equally narrow: when
 * vanilla returned its hard-coded title-screen 30 for one of Fathom's interactive
 * no-world screens, use the player's configured limit instead. The panorama animation
 * has its own 30 Hz clock and does not throttle the UI.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFrameCapMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "getMaxFramerate()I", at = @At("RETURN"), cancellable = true)
    private void fathom$capFramerate(CallbackInfoReturnable<Integer> callback) {
        if (!Runtime.ready()) {
            return;
        }
        long probe = net.fathom.optimizations.core.instrument.Probe.start();
        MinecraftClient client = (MinecraftClient) (Object) this;
        int vanilla = callback.getReturnValueI();
        int capped =
                FrameCapPolicy.effective(
                        vanilla,
                        client.options == null ? vanilla : client.options.maxFramerate,
                        Display.isActive(),
                        Runtime.switches().isEnabled(Runtime.UNFOCUSED_CAP)
                                && !net.fathom.optimizations.fabric.bench.PerfBench.suppressUnfocusedCap(),
                        Runtime.unfocusedFrameCap(),
                        client.world != null,
                        client.world == null && currentScreen instanceof FathomScreen);
        if (capped != vanilla) {
            callback.setReturnValue(Integer.valueOf(capped));
        }
        net.fathom.optimizations.core.instrument.Probe.stop(
                net.fathom.optimizations.core.instrument.Probe.Section.HOOKS, probe);
    }
}
