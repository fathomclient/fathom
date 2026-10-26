package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;

import net.fathom.optimizations.fabric.visual.BlockOutline;

/** Block Outline: Fathom's box in place of vanilla's. See {@link BlockOutline}. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererBlockOutlineMixin {

    @Inject(method = "drawBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;IF)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$blockOutline(PlayerEntity player, BlockHitResult hit, int mode, float tickDelta,
            CallbackInfo info) {
        if (BlockOutline.draw(player, hit, mode, tickDelta)) {
            info.cancel();
        }
    }
}
