package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;

import net.fathom.optimizations.fabric.perf.TileCulling;

/**
 * Tile entity distance limits, on vanilla's own range comparison.
 *
 * <p>{@code renderEntity} reads {@code distance < getSquaredRenderDistance()}
 * and draws only when that holds. OptiFine's patched version keeps the same
 * comparison. The first handler records the distance vanilla computed; the
 * second hands back the tighter of vanilla's range and the limit. Nothing is
 * drawn that vanilla would not draw, and nothing is computed twice.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherCullMixin {

    @Unique
    private net.fathom.optimizations.fabric.gfx.GlStateAudit.Scope fathom$tileAudit;

    @ModifyExpressionValue(
            method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/BlockEntity;getSquaredDistance(DDD)D"))
    private double fathom$recordDistance(double distance) {
        TileCulling.lastDistanceSquared = distance;
        return distance;
    }

    @ModifyExpressionValue(
            method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/BlockEntity;getSquaredRenderDistance()D"))
    private double fathom$distanceLimit(double vanillaSquared,
            @Local(argsOnly = true) BlockEntity entity) {
        fathom$tileAudit = net.fathom.optimizations.fabric.gfx.GlStateAudit.beginRequested(
                net.fathom.optimizations.fabric.gfx.GlStateAudit.TILE_LIMIT,
                net.fathom.optimizations.fabric.perf.PerfLayer.tilesLimited);
        double result = TileCulling.limitSquared(entity, vanillaSquared);
        net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                fathom$tileAudit, false, "policy-only");
        return result;
    }

    @Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("RETURN"))
    private void fathom$finishTileAudit(BlockEntity entity, float tickDelta, int destroyStage,
            CallbackInfo callback) {
        if (fathom$tileAudit != null) {
            net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                    net.fathom.optimizations.fabric.gfx.GlStateAudit.TILE_LIMIT);
            fathom$tileAudit = null;
        }
    }
}
