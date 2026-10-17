package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.fathom.optimizations.fabric.Runtime;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.util.math.BlockPos;

/**
 * {@code render.entity.shadow-allocation}: an entity shadow's per-block
 * {@code pos.down()} without a new {@code BlockPos} each time.
 *
 * <p>{@code EntityRenderer.renderShadow} walks the blocks under an entity and, for
 * each, asks for the block one below with {@code pos.down()} -- a new object used for
 * one block-state lookup and dropped. Under a pile of dropped items that is several
 * allocations per item per frame. The result is consumed at once and never kept, so
 * one mutable position, set to the same coordinates, gives the lookup exactly the
 * same input. OptiFine's patched renderer keeps the call (checked in its bytecode).
 * Shadows are drawn only on the render thread, which is what makes one shared
 * position safe here; the same trick would not be safe in code the integrated
 * server also runs.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowBelowMixin {

    @Unique
    private static final BlockPos.Mutable fathom$below = new BlockPos.Mutable();

    @Redirect(method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/BlockPos;down()Lnet/minecraft/util/math/BlockPos;"),
            require = 1, allow = 1)
    private BlockPos fathom$below(BlockPos pos) {
        if (Runtime.shadowAllocation) {
            return fathom$below.setPosition(pos.getX(), pos.getY() - 1, pos.getZ());
        }
        return pos.down();
    }
}
