package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.fathom.optimizations.fabric.perf.ItemModelLists;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.item.ItemStack;

/**
 * {@code render.entity.item-model-lists}: see {@link ItemModelLists}. Only while an
 * entity's held item is drawn ({@link HeldItemFeatureListMixin}); everything around the
 * model's draw -- the transforms, the texture, the glint, OptiFine's custom item choice --
 * stays live.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemModelListMixin {

    @Unique private static BakedModel fathom$recordingModel;
    @Unique private static long fathom$recordingKey;
    @Unique private static boolean fathom$drawingLive;

    @Shadow
    private void renderBakedItemModel(BakedModel model, int colour, ItemStack stack) {}

    @Inject(method = "renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$replay(BakedModel model, int colour, ItemStack stack, CallbackInfo info) {
        if (!ItemModelLists.active || fathom$drawingLive) {
            return;
        }
        long key = ItemModelLists.key(model, colour, stack);
        if (key == ItemModelLists.LIVE) {
            ItemModelLists.live++;
            return;
        }
        ItemModelLists.Entry entry = ItemModelLists.find(model, key);
        if (entry == null) {
            // Drawn live this once; the bytes are kept as the tessellator hands them over.
            fathom$recordingModel = model;
            fathom$recordingKey = key;
            return;
        }
        if (ItemModelLists.dueForCheck()) {
            ItemModelLists.checkGlint = colour != -1;
            ItemModelLists.beginCheck(ItemModelLists.CONTROL ? null : entry);
            fathom$drawingLive = true;
            ItemModelLists.checking = entry;
            ItemModelLists.checkingModel = model;
            ItemModelLists.checkingStack = stack;
            ItemModelLists.checkingColour = colour;
            try {
                if (ItemModelLists.CONTROL) {
                    renderBakedItemModel(model, colour, stack);
                    ItemModelLists.controlFirstDrawn();
                }
                renderBakedItemModel(model, colour, stack);
            } finally {
                fathom$drawingLive = false;
                ItemModelLists.checking = null;
            }
            ItemModelLists.compareLive();
        }
        ItemModelLists.draw(entry);
        ItemModelLists.replays++;
        net.fathom.optimizations.core.bench.view.ViewProbe.engaged(
                net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_ITEM_MODEL_LIST);
        info.cancel();
    }

    @WrapOperation(method = "renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void fathom$keepBytes(Tessellator tessellator, Operation<Void> original) {
        if (fathom$drawingLive) {
            ItemModelLists.compareBytes(tessellator.getBuffer());
        }
        if (fathom$recordingModel != null && !fathom$drawingLive) {
            ItemModelLists.keep(fathom$recordingModel, fathom$recordingKey, tessellator.getBuffer());
            fathom$recordingModel = null;
        }
        original.call(tessellator);
    }

    @Inject(method = "renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"))
    private void fathom$endRecording(BakedModel model, int colour, ItemStack stack, CallbackInfo info) {
        if (!fathom$drawingLive) {
            fathom$recordingModel = null;
        }
    }
}
