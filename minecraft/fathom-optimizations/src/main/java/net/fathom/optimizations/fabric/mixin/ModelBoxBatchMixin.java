package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fathom.optimizations.fabric.perf.ModelBatch;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.ModelBox;

/**
 * {@code render.entity.model-box-batching}: a box's six faces drawn as one batch. See
 * {@link ModelBatch}; the faces themselves are {@link TexturedQuadBatchMixin}.
 */
@Mixin(ModelBox.class)
public abstract class ModelBoxBatchMixin {

    @Inject(method = "draw(Lnet/minecraft/client/render/BufferBuilder;F)V", at = @At("HEAD"))
    private void fathom$beginBox(BufferBuilder buffer, float scale, CallbackInfo info) {
        if (!ModelBatch.active && ModelBatch.enabled()) {
            ModelBatch.active = true;
            ModelBatch.started = false;
            ModelBatch.format = null;
        }
    }

    @Inject(method = "draw(Lnet/minecraft/client/render/BufferBuilder;F)V", at = @At("RETURN"))
    private void fathom$endBox(BufferBuilder buffer, float scale, CallbackInfo info) {
        if (ModelBatch.active) {
            ModelBatch.flush();
            ModelBatch.active = false;
            ModelBatch.boxesBatched++;
        }
    }
}
