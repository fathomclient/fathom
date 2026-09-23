package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;

import net.fathom.optimizations.fabric.hud.Zoom;

/**
 * The mouse wheel, offered to Zoom before it changes the hotbar slot.
 *
 * <p>The tick's input loop turns a wheel event into
 * {@code PlayerInventory.scrollInHotbar}. While the zoom key is held and the
 * module scrolls, the wheel adjusts the zoom instead and the slot stays put;
 * otherwise the call goes through unchanged. Spectator menus and creative fly
 * speed use other branches of the same loop and are not touched.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientZoomScrollMixin {

    @Redirect(method = "tick()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;scrollInHotbar(I)V"))
    private void fathom$zoomScroll(PlayerInventory inventory, int wheel) {
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowEventDispatch()) {
            inventory.scrollInHotbar(wheel);
            return;
        }
        if (!Zoom.scroll(wheel)) {
            inventory.scrollInHotbar(wheel);
        }
    }
}
