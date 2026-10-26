package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fathom.optimizations.fabric.perf.ItemModelLists;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.resource.ResourceManager;

/** {@code render.entity.item-model-lists}: a resource reload replaces every model; drop their lists. */
@Mixin(BakedModelManager.class)
public abstract class BakedModelReloadMixin {

    @Inject(method = "reload(Lnet/minecraft/resource/ResourceManager;)V", at = @At("RETURN"))
    private void fathom$releaseItemLists(ResourceManager manager, CallbackInfo info) {
        ItemModelLists.release();
    }
}
