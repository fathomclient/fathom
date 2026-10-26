package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.fabric.input.MouseButtons;

/**
 * A screen's mouse handling reads the button through {@link MouseButtons}: a
 * side button bound to a hotbar key swaps into the right slot with Shift held.
 */
@Mixin(Screen.class)
public abstract class ScreenMouseButtonMixin {

    @Redirect(method = "handleMouse()V", require = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventButton()I", remap = false))
    private int fathom$sideButton() {
        return MouseButtons.eventButton();
    }
}
