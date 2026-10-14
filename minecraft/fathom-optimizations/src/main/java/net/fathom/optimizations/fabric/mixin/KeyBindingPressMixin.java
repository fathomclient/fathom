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
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        // The attack and use bindings, whatever they are bound to: somebody who
        // attacks with a key is clicking with that key.
        boolean attack = code == client.options.attackKey.getCode();
        boolean use = code == client.options.useKey.getCode();
        if (!attack && !use) {
            return;
        }
        FathomHud hud = Runtime.hud();
        if (hud == null) {
            return;
        }
        hud.clicks().pressed(attack);
        if (attack && client.currentScreen == null && client.player != null && fathom$onEntity(client)) {
            // An attack on an entity, not a swing at the air or a click on a
            // button: that is what "flash when you hit one" promises.
            CrosshairWidget.attacked();
        }
    }

    /** Whether the crosshair is on an entity, as the attack that just happened will see it. */
    private static boolean fathom$onEntity(net.minecraft.client.MinecraftClient client) {
        return client.targetedEntity != null
                || (client.result != null
                        && client.result.type == net.minecraft.util.hit.BlockHitResult.Type.ENTITY);
    }
}
