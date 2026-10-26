package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Hotbar keys bound to a mouse button, in an inventory.
 *
 * <p>Hovering an item and pressing a hotbar key swaps it into that slot, but
 * 1.8.9 only checks the hotbar keys from {@code keyPressed}: a hotbar key bound
 * to a side mouse button does nothing there. This sends a mouse button bound to
 * a hotbar key through the same {@code handleHotbarKeyPressed} the keyboard
 * uses (codes are {@code button - 100}, as vanilla stores them), so the swap is
 * vanilla's own click. The matching release is swallowed too, so it cannot act
 * as half of a click that never happened.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenHotbarMouseMixin {

    @Shadow
    protected abstract boolean handleHotbarKeyPressed(int code);

    @Unique
    private int fathom$swallowRelease = -1;

    @Inject(method = "mouseClicked(III)V", at = @At("HEAD"), cancellable = true)
    private void fathom$hotbarMouse(int x, int y, int button, CallbackInfo info) {
        if (button <= 1) {
            // Left and right click are never hotbar keys worth a swap: they are clicks.
            return;
        }
        if (handleHotbarKeyPressed(button - 100)) {
            fathom$swallowRelease = button;
            info.cancel();
        }
    }

    @Inject(method = "mouseReleased(III)V", at = @At("HEAD"), cancellable = true)
    private void fathom$hotbarMouseRelease(int x, int y, int button, CallbackInfo info) {
        if (button == fathom$swallowRelease) {
            fathom$swallowRelease = -1;
            info.cancel();
        }
    }
}
