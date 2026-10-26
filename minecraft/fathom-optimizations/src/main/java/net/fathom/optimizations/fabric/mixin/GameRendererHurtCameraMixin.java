package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.visual.HurtCamera;

/**
 * Hurt Camera: the 14 degrees vanilla rolls the view by when the player is hit.
 *
 * <p>{@code bobViewWhenHurt} has that one constant; the death roll beside it
 * uses others and is left alone. Like Fullbright's redirect this may find
 * nothing ({@code require = 0}): a renderer that rewrote the method would leave
 * the module with no effect rather than stop the game from starting over a
 * camera shake.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHurtCameraMixin {

    @ModifyConstant(method = "bobViewWhenHurt(F)V", constant = @Constant(floatValue = 14.0F), require = 0)
    private float fathom$hurtCamera(float original) {
        return HurtCamera.tilt(original);
    }
}
