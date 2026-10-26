package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.entity.ItemEntity;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ItemModelLists;

/**
 * {@code render.entity.dropped-item-models}: the window in which a dropped
 * item's model may be replayed, the same vertex reuse held items get (see
 * {@link ItemModelLists}). A generator's pile is the same few models drawn
 * dozens of times a frame, up to five copies each.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererModelListMixin {

    @Unique
    private boolean fathom$wasActive;

    @Inject(method = "render(Lnet/minecraft/entity/ItemEntity;DDDFF)V", at = @At("HEAD"))
    private void fathom$open(ItemEntity item, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        fathom$wasActive = ItemModelLists.active;
        ItemModelLists.active = Runtime.droppedItemModels;
    }

    @Inject(method = "render(Lnet/minecraft/entity/ItemEntity;DDDFF)V", at = @At("RETURN"))
    private void fathom$close(ItemEntity item, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        ItemModelLists.active = fathom$wasActive;
    }
}
