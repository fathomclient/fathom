package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: each block entity the world draws (chests, signs, skulls, banners). */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ViewBlockEntityMixin {

    @Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("HEAD"), require = 0)
    private void fathom$viewBlockEntityStart(BlockEntity entity, float tickDelta, int destroyStage,
            CallbackInfo info) {
        ViewProbe.enter(ViewSection.BLOCK_ENTITY);
    }

    @Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("RETURN"), require = 0)
    private void fathom$viewBlockEntityEnd(BlockEntity entity, float tickDelta, int destroyStage,
            CallbackInfo info) {
        ViewProbe.exit(ViewSection.BLOCK_ENTITY);
    }
}
