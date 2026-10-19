package net.fathom.optimizations.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fathom.optimizations.fabric.visual.OldAnimations;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 Animations: the swing progress handed back to the equip transform of an
 * item in use. Every call of the transform is wrapped, rather than one picked
 * by ordinal, so the hook does not depend on how the use-action switch is laid
 * out (OptiFine's copy of this method included); {@link OldAnimations#swing}
 * leaves any call that already had a swing alone.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemOldAnimationsMixin {

    @Inject(method = "renderArmHoldingItem(F)V", at = @At("HEAD"))
    private void fathom$oldAnimationsFrame(float tickDelta, CallbackInfo info) {
        OldAnimations.frame(tickDelta);
    }

    @WrapOperation(method = "renderArmHoldingItem(F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;applyEquipAndSwingOffset(FF)V"))
    private void fathom$oldAnimationsSwing(HeldItemRenderer renderer, float equip, float swing,
            Operation<Void> original) {
        original.call(renderer, equip, OldAnimations.swing(swing));
    }
}
