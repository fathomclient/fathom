package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.option.KeyBinding;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.CrosshairWidget;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Observes every press the game processes, for the click counter.
 *
 * <p>{@code KeyBinding.onKeyPressed} is called by the tick's input loop once per
 * press, for keys and for mouse buttons (as {@code button - 100}). This reads
 * the code and returns; the press still reaches its binding untouched. Nothing
 * here can swallow, repeat or delay an attack.
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingPressMixin {

    @Inject(method = "onKeyPressed(I)V", at = @At("HEAD"))
    private static void fathom$observePress(int code, CallbackInfo info) {
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowEventDispatch()) {
            return;
        }
        if (code != -100 && code != -99) {
            return;
        }
        FathomHud hud = Runtime.hud();
        if (hud == null) {
            return;
        }
        hud.clicks().pressed(code);
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (code == -100 && client != null && client.currentScreen == null && client.player != null) {
            // A click at the game, not at a menu: the crosshair flash is about
            // swinging, and a click on a button is not a swing.
            CrosshairWidget.attacked();
        }
    }
}
