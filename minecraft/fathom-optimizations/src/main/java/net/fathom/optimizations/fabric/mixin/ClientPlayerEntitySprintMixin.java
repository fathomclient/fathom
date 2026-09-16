package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.fabric.input.MovementToggles;

/**
 * Toggle Sprint: the sprint key as the movement tick reads it.
 *
 * <p>Both {@code isPressed} calls in 1.8.9's {@code tickMovement} are the sprint
 * key — the one that starts a sprint from standing and the one that restarts it
 * while walking — confirmed against the bytecode. The binding is still checked
 * in the redirect, so a future call on another key reads the real state.
 *
 * <p>The toggle advances at the head of the same tick, before either read, so
 * the press that latches sprint is the press that starts it.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntitySprintMixin {

    @Inject(method = "tickMovement()V", at = @At("HEAD"))
    private void fathom$advanceToggles(CallbackInfo info) {
        MovementToggles.tick((ClientPlayerEntity) (Object) this);
    }

    @Redirect(method = "tickMovement()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean fathom$sprintKey(KeyBinding binding) {
        boolean pressed = binding.isPressed();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || binding != client.options.sprintKey) {
            return pressed;
        }
        return MovementToggles.sprintKey(pressed);
    }
}
