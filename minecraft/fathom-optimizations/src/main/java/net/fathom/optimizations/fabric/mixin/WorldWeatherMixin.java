package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;

import net.fathom.optimizations.fabric.visual.WorldEffects;

/**
 * Weather Changer: the client's world answers the rain and thunder strength
 * you chose. Only the client's world, so the server's weather, and anything
 * that depends on it, is unchanged; rain sounds and sky colour follow the
 * strength, as they do in vanilla.
 */
@Mixin(World.class)
public abstract class WorldWeatherMixin {

    @Inject(method = "getRainGradient(F)F", at = @At("HEAD"), cancellable = true)
    private void fathom$rain(float tickDelta, CallbackInfoReturnable<Float> info) {
        if ((Object) this instanceof ClientWorld) {
            float rain = WorldEffects.rain();
            if (rain >= 0.0F) {
                info.setReturnValue(Float.valueOf(rain));
            }
        }
    }

    @Inject(method = "getThunderGradient(F)F", at = @At("HEAD"), cancellable = true)
    private void fathom$thunder(float tickDelta, CallbackInfoReturnable<Float> info) {
        if ((Object) this instanceof ClientWorld) {
            float thunder = WorldEffects.thunder();
            if (thunder >= 0.0F) {
                info.setReturnValue(Float.valueOf(thunder));
            }
        }
    }
}
