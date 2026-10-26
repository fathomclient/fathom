package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.render.entity.LivingEntityRenderer;

import net.fathom.optimizations.fabric.visual.HudTweaks;

/**
 * Hit Colour: the tint a hurt entity is drawn with. Vanilla writes it as four
 * floats, red 1, green 0, blue 0, strength 0.3, into the texture environment
 * colour; these are the first four writes in the method, before the creeper's
 * white flash.
 *
 * <p>{@code require = 0}: OptiFine reworks this method for shaders, and a
 * different count there leaves the tint vanilla's rather than refusing to start.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererHitColourMixin {

    private static final String METHOD = "method_10252(Lnet/minecraft/entity/LivingEntity;FZ)Z";
    private static final String PUT = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;";

    @ModifyArg(method = METHOD, require = 0, at = @At(value = "INVOKE", target = PUT, ordinal = 0))
    private float fathom$red(float vanilla) {
        return HudTweaks.hitColour(0, vanilla);
    }

    @ModifyArg(method = METHOD, require = 0, at = @At(value = "INVOKE", target = PUT, ordinal = 1))
    private float fathom$green(float vanilla) {
        return HudTweaks.hitColour(1, vanilla);
    }

    @ModifyArg(method = METHOD, require = 0, at = @At(value = "INVOKE", target = PUT, ordinal = 2))
    private float fathom$blue(float vanilla) {
        return HudTweaks.hitColour(2, vanilla);
    }

    @ModifyArg(method = METHOD, require = 0, at = @At(value = "INVOKE", target = PUT, ordinal = 3))
    private float fathom$strength(float vanilla) {
        return HudTweaks.hitColour(3, vanilla);
    }
}
