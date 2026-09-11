package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.Zoom;

/**
 * The Zoom module's field of view.
 *
 * <h2>Why {@code getFov} and not the option</h2>
 *
 * <p>Writing {@code GameOptions.fov} would zoom, and would also write the
 * player's own field of view to disk the next time anything saved the options —
 * so a client that crashed while zoomed would come back permanently at 25
 * degrees. This changes the value vanilla computed for this frame and nothing
 * else, which is why {@code getFov} is the hook even though it is private.
 *
 * <p>Private is not an obstacle to a Mixin, but it is a reason to be explicit:
 * the target is {@code getFov(FZ)F}, confirmed against the 1.8.9 bytecode rather
 * than assumed from a newer mapping, and it is injected at {@code RETURN} so
 * vanilla's own movement and status-effect adjustments are already in the number
 * being divided.
 *
 * <h2>Why a divisor</h2>
 *
 * <p>A zoom is "the same picture, less of it", which is a ratio, not a
 * subtraction. Dividing keeps the relationship between the setting and the
 * result the same at every field of view, so somebody playing at 110 and
 * somebody playing at 70 get the same 4× when they ask for 4×.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

    @Inject(method = "getFov(FZ)F", at = @At("RETURN"), cancellable = true)
    private void fathom$zoom(float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> info) {
        if (Runtime.hud() == null) {
            return;
        }
        float factor = Zoom.factor();
        if (factor <= 1f) {
            return;
        }
        info.setReturnValue(Float.valueOf(info.getReturnValueF() / factor));
    }
}
