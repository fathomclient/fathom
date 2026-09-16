package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.option.KeyBinding;

import net.fathom.optimizations.fabric.input.MovementToggles;

/**
 * Toggle Sneak: the sneak key as keyboard input reads it.
 *
 * <p>{@code KeyboardInput.tick} reads six bindings through {@code isPressed};
 * only the sneak key is answered by the toggle, and the other five return
 * exactly what vanilla would. Sneaking's slowdown is applied by vanilla right
 * after this read, from the same field, so it follows the toggle for free.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputSneakMixin {

    @Redirect(method = "tick()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean fathom$sneakKey(KeyBinding binding) {
        boolean pressed = binding.isPressed();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || binding != client.options.sneakKey) {
            return pressed;
        }
        return MovementToggles.sneakKey(pressed);
    }
}
