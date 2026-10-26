package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import net.fathom.optimizations.fabric.input.Freelook;

/**
 * Freelook: the angles every floating label, marker and waypoint faces are
 * the camera's, not the player's.
 *
 * <p>{@code updateCamera} caches the view yaw and pitch that nametags, Teamview
 * and Waypoints all billboard with ({@code dispatcher.yaw}/{@code pitch}). It
 * reads them from the camera entity, which under freelook has been turned back
 * to the player's own angles since the camera was set up. Labels then faced
 * where the player looks while the screen showed somewhere else, which is
 * edge-on, and so invisible, as soon as the two differ enough: worst when the
 * free camera is pitched steeply. The free angles are lent for this one call.
 *
 * <p>Front-facing third person had the same symptom on its own: see the pitch
 * correction in the second hook.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherFreelookMixin {

    @Inject(method = "updateCamera(Lnet/minecraft/world/World;Lnet/minecraft/client/font/TextRenderer;"
            + "Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;"
            + "Lnet/minecraft/client/option/GameOptions;F)V", at = @At("HEAD"))
    private void fathom$freeLabels(World world, TextRenderer font, Entity camera, Entity target,
            GameOptions options, float tickDelta, CallbackInfo info) {
        Freelook.beginCamera(camera);
    }

    @Inject(method = "updateCamera(Lnet/minecraft/world/World;Lnet/minecraft/client/font/TextRenderer;"
            + "Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;"
            + "Lnet/minecraft/client/option/GameOptions;F)V", at = @At("RETURN"))
    private void fathom$ownAngles(World world, TextRenderer font, Entity camera, Entity target,
            GameOptions options, float tickDelta, CallbackInfo info) {
        Freelook.endCamera();
        // Front-facing third person: the yaw is turned round for the camera
        // that looks back at the player, but the pitch is not, so every label
        // tilts the wrong way and goes edge-on as the pitch grows. The camera
        // looking back sees the player's pitch the other way up (later
        // versions negate it here too).
        if (options != null && options.perspective == 2) {
            EntityRenderDispatcher self = (EntityRenderDispatcher) (Object) this;
            self.pitch = -self.pitch;
        }
    }
}
