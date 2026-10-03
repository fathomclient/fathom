package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import net.fathom.optimizations.fabric.perf.EntityOcclusion;

/**
 * What entity occlusion culling needs from the world renderer --
 * {@code render.entity.occlusion-culling}. The decision itself is in
 * {@code EntityRenderDispatcherCullMixin}.
 *
 * <ul>
 *   <li>the start and end of the entity pass, for the eye and the per-frame
 *       switches;
 *   <li>every block and render-region update, so a column whose built mesh is
 *       behind the world is not trusted to hide anything;
 *   <li>world changes and reloads, after which nothing is culled until the
 *       terrain has been built.
 * </ul>
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererOcclusionMixin {

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At("HEAD"))
    private void fathom$occlusionBegin(Entity camera, CameraView view, float tickDelta, CallbackInfo info) {
        EntityOcclusion.beginPass(camera, tickDelta);
    }

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At("RETURN"))
    private void fathom$occlusionEnd(Entity camera, CameraView view, float tickDelta, CallbackInfo info) {
        EntityOcclusion.endPass();
    }

    @Inject(method = "onBlockUpdate(Lnet/minecraft/util/math/BlockPos;)V", at = @At("HEAD"))
    private void fathom$occlusionBlock(BlockPos pos, CallbackInfo info) {
        // Vanilla re-renders the blocks around one that changed, too.
        EntityOcclusion.changed(pos.getX() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getZ() + 1);
    }

    @Inject(method = "onRenderRegionUpdate(IIIIII)V", at = @At("HEAD"))
    private void fathom$occlusionRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            CallbackInfo info) {
        EntityOcclusion.changed(minX, minZ, maxX, maxZ);
    }

    @Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;)V", at = @At("HEAD"))
    private void fathom$occlusionWorld(ClientWorld world, CallbackInfo info) {
        EntityOcclusion.unsettle();
    }

    @Inject(method = "reload()V", at = @At("HEAD"))
    private void fathom$occlusionReload(CallbackInfo info) {
        EntityOcclusion.unsettle();
    }
}
