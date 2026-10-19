package net.fathom.optimizations.fabric.mixin;

import net.fathom.optimizations.fabric.visual.OldAnimations;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.7 Animations: the client-only swing for attacking a block while an item is in use. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientOldAnimationsMixin {

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void fathom$oldAnimationsPunch(CallbackInfo info) {
        OldAnimations.tick((MinecraftClient) (Object) this);
    }
}
