package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;

import net.fathom.optimizations.fabric.visual.WorldLighting;

/**
 * Time Changer: the sky angle of the client's own world, at the chosen hour.
 *
 * <p>The sky angle is what the sun, the moon, the stars, the sky colour, the
 * fog and the client's sky darkening are all drawn from. Only the client world
 * is answered: in singleplayer the integrated server's world is a separate
 * object, and its time — which decides mob spawning and whether a bed works —
 * is never touched.
 */
@Mixin(World.class)
public abstract class WorldSkyAngleMixin {

    @Inject(method = "getSkyAngle(F)F", at = @At("HEAD"), cancellable = true)
    private void fathom$timeChanger(float tickDelta, CallbackInfoReturnable<Float> info) {
        if (!((Object) this instanceof ClientWorld)) {
            return;
        }
        long ticks = WorldLighting.skyTicks();
        if (ticks >= 0L) {
            info.setReturnValue(Float.valueOf(((World) (Object) this).dimension.getSkyAngle(ticks, 0f)));
        }
    }
}
