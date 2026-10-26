package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.fathom.optimizations.fabric.perf.ModelBatch;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.TexturedQuad;

/**
 * {@code render.entity.model-box-batching}: while a box is batched, a face's
 * {@code begin} joins the open batch and its {@code draw} is left to the box. A face
 * asking for a different vertex format (OptiFine picks one per call, by whether shaders
 * are on) draws what is held first. Outside a box nothing changes.
 */
@Mixin(TexturedQuad.class)
public abstract class TexturedQuadBatchMixin {

    @WrapOperation(method = "draw(Lnet/minecraft/client/render/BufferBuilder;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/BufferBuilder;begin(ILnet/minecraft/client/render/VertexFormat;)V"))
    private void fathom$joinBatch(BufferBuilder buffer, int mode, VertexFormat format, Operation<Void> original) {
        if (ModelBatch.active) {
            if (ModelBatch.started && ModelBatch.format == format && mode == 7) {
                return;
            }
            ModelBatch.flush();
            original.call(buffer, mode, format);
            ModelBatch.started = mode == 7;
            ModelBatch.format = format;
            return;
        }
        original.call(buffer, mode, format);
    }

    @WrapOperation(method = "draw(Lnet/minecraft/client/render/BufferBuilder;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void fathom$leaveToBox(Tessellator tessellator, Operation<Void> original) {
        if (ModelBatch.active && ModelBatch.started) {
            return;
        }
        original.call(tessellator);
    }
}
