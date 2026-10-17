package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.util.math.Direction;

import net.fathom.optimizations.fabric.opt.DirectionArrays;

/** The chunk occlusion flood fill iterates the six directions. See {@link DirectionArrays}. */
@Mixin(ChunkOcclusionDataBuilder.class)
public abstract class ChunkVisibilityDirectionsMixin {

    @Redirect(method = "getOpenFaces(I)Ljava/util/Set;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/Direction;values()[Lnet/minecraft/util/math/Direction;"))
    private Direction[] fathom$sharedDirections() {
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
            return Direction.values();
        }
        return DirectionArrays.values();
    }
}
