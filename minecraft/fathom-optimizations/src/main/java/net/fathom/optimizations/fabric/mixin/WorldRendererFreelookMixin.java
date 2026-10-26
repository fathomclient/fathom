package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.fabric.input.Freelook;

/**
 * Freelook: the visible-chunk search follows the camera that is drawn.
 *
 * <p>{@code setupTerrain} only rebuilds its visible list, and queues the chunks
 * that list newly reaches, when the camera entity's position or its
 * {@code yaw}/{@code pitch} differ from the last frame's. Freelook gives the
 * camera setup the free angles and puts the player's back straight afterwards,
 * so by the time this runs the entity has not turned and the test never fires:
 * the frustum had moved but the list was still the one cut for the old view,
 * and chunks that rotated into view were not built until the player moved.
 *
 * <p>The free angles are lent for the length of this one call, through the same
 * swap the camera setup uses, and put back on every return. Nothing is rebuilt
 * per frame: the search runs exactly when the camera has really turned. The
 * one forced rebuild is at freelook starting and stopping, where the
 * third-person camera moves while the angles do not.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererFreelookMixin {

    @Inject(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
            at = @At("HEAD"))
    private void fathom$freeTerrainStart(Entity entity, double tickDelta, CameraView camera, int frame,
            boolean spectator, CallbackInfo info) {
        if (Freelook.consumeTerrainDirty()) {
            ((WorldRenderer) (Object) this).scheduleTerrainUpdate();
        }
        Freelook.beginCamera(entity);
    }

    @Inject(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
            at = @At("RETURN"))
    private void fathom$freeTerrainEnd(Entity entity, double tickDelta, CameraView camera, int frame,
            boolean spectator, CallbackInfo info) {
        Freelook.endCamera();
    }
}
