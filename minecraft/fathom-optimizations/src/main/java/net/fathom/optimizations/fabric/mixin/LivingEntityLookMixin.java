package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Your crosshair's target, from where you are actually looking (MC-67665).
 *
 * <p>1.8.9's {@code LivingEntity.getRotationVector} aims with {@code headYaw},
 * which the client player only updates on the tick, while the mouse turns
 * {@code yaw} every frame. So the ray that decides what an attack hits lagged
 * up to a tick behind the crosshair you see: flick onto somebody and click, and
 * the game could still be aiming where you were. That is the hit registration
 * people feel as "my click did nothing".
 *
 * <p>For the local player only, this answers with {@code Entity}'s own version,
 * which interpolates {@code yaw} -- the direction the camera is drawn with. The
 * same fix MouseDelayFix and Patcher ship. Other entities, and every other
 * player in the world, keep vanilla's head-yaw look.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityLookMixin extends Entity {

    private LivingEntityLookMixin(World world) {
        super(world);
    }

    @Inject(method = "getRotationVector(F)Lnet/minecraft/util/math/Vec3d;", at = @At("HEAD"), cancellable = true)
    private void fathom$lookWhereTheCameraLooks(float tickDelta, CallbackInfoReturnable<Vec3d> info) {
        if ((Object) this instanceof ClientPlayerEntity) {
            if (tickDelta == 1.0F) {
                info.setReturnValue(getRotationVector(pitch, yaw));
            } else {
                float lookPitch = prevPitch + (pitch - prevPitch) * tickDelta;
                float lookYaw = prevYaw + (yaw - prevYaw) * tickDelta;
                info.setReturnValue(getRotationVector(lookPitch, lookYaw));
            }
        }
    }
}
