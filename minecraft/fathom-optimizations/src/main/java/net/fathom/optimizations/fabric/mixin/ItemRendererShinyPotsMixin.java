package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.item.ItemStack;

import net.fathom.optimizations.fabric.visual.HudTweaks;

/**
 * Shiny Pots: the enchantment glint on a potion drawn again, for the bright
 * shine potions had in 1.7. Everywhere a potion is drawn as an item: the
 * hotbar, inventories, your hand, on the ground.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererShinyPotsMixin {

    @Shadow
    private void renderGlint(BakedModel model) {}

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/BakedModel;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/ItemRenderer;renderGlint(Lnet/minecraft/client/render/model/BakedModel;)V",
                    shift = At.Shift.AFTER))
    private void fathom$shinyPots(ItemStack stack, BakedModel model, CallbackInfo info) {
        int extra = HudTweaks.extraPotionGlint(stack);
        for (int pass = 0; pass < extra; pass++) {
            renderGlint(model);
        }
    }
}
