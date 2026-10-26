package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.fabric.input.MouseButtons;

/**
 * The tick's mouse loop reads the button through {@link MouseButtons}, so Mouse 4
 * pressed with Shift held reaches its binding as Mouse 4 rather than Mouse 5.
 * See {@link MouseButtons} for the LWJGL bug this corrects.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMouseButtonMixin {

    @Redirect(method = "tick()V", require = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventButton()I", remap = false))
    private int fathom$sideButton() {
        return MouseButtons.eventButton();
    }
}
