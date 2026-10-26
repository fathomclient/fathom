package net.fathom.optimizations.fabric.mixin;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.fathom.optimizations.fabric.perf.ModelBatch;
import net.minecraft.client.render.model.ModelPart;

/**
 * {@code render.entity.model-box-batching}: recompiling a part's list after the switch
 * changes, and (verification only) keeping a vanilla-compiled list beside it to compare.
 *
 * <p>A part compiled under the other setting is marked uncompiled at the head of whichever
 * draw method reaches it first; its own code then compiles it again. Vanilla allocates a
 * new list name on every compile, so the old one is deleted first; OptiFine reuses the
 * name it has, so there it is left alone.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartBatchMixin {

    @Shadow private boolean compiledList;
    @Shadow private int glList;

    @Unique private int fathom$generation;
    @Unique private boolean fathom$batched;
    @Unique private int fathom$referenceList;

    @Shadow
    private void compileList(float scale) {}

    @Inject(method = {"render(F)V", "rotateAndRender(F)V", "preRender(F)V"}, at = @At("HEAD"))
    private void fathom$recompileOnSwitch(float scale, CallbackInfo info) {
        if (fathom$generation != ModelBatch.generation) {
            if (compiledList && fathom$batched != ModelBatch.enabled()) {
                compiledList = false;
                ModelBatch.recompiles++;
            }
            fathom$generation = ModelBatch.generation;
        }
    }

    @Inject(method = "compileList(F)V", at = @At("HEAD"))
    private void fathom$beforeCompile(float scale, CallbackInfo info) {
        if (glList != 0 && fathom$generation != 0 && !FathomMixinPlugin.optiFinePresent()
                && !ModelBatch.suspended) {
            GL11.glDeleteLists(glList, 1);
            glList = 0;
        }
    }

    @Inject(method = "compileList(F)V", at = @At("RETURN"))
    private void fathom$afterCompile(float scale, CallbackInfo info) {
        if (ModelBatch.suspended) {
            return;
        }
        fathom$batched = ModelBatch.enabled();
        fathom$generation = ModelBatch.generation;
        if (ModelBatch.VERIFY && fathom$batched) {
            // A second list, compiled the vanilla way from the same boxes, to compare against.
            // OptiFine compiles into the name the part holds (allocating one if it has
            // none); vanilla always allocates a new one. Either way the name the nested
            // compile leaves in glList is the reference.
            int kept = glList;
            boolean wasCompiled = compiledList;
            if (fathom$referenceList != 0 && !FathomMixinPlugin.optiFinePresent()) {
                GL11.glDeleteLists(fathom$referenceList, 1);
                fathom$referenceList = 0;
            }
            glList = fathom$referenceList;
            ModelBatch.suspended = true;
            try {
                compileList(scale);
                fathom$referenceList = glList;
            } finally {
                ModelBatch.suspended = false;
                glList = kept;
                compiledList = wasCompiled;
            }
        }
    }

    @WrapOperation(method = {"render(F)V", "rotateAndRender(F)V"},
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;callList(I)V"))
    private void fathom$checkList(int list, Operation<Void> original) {
        if (fathom$batched && fathom$referenceList != 0 && ModelBatch.dueForCheck()) {
            ModelBatch.check(list, fathom$referenceList);
        }
        original.call(list);
    }
}
