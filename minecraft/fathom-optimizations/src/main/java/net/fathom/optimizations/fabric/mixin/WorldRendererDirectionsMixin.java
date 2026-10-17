package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Direction;

import net.fathom.optimizations.fabric.opt.DirectionArrays;

/** The per-frame visible-chunk search iterates the six directions. See {@link DirectionArrays}. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererDirectionsMixin {

    @Redirect(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
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
