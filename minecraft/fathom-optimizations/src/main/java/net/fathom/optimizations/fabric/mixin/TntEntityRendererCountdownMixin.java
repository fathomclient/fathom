package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.TntEntityRenderer;
import net.minecraft.entity.TntEntity;

import net.fathom.optimizations.fabric.visual.WorldEffects;

/** TNT Countdown: the fuse left, as a nametag over primed TNT. */
@Mixin(TntEntityRenderer.class)
public abstract class TntEntityRendererCountdownMixin extends EntityRenderer<TntEntity> {

    protected TntEntityRendererCountdownMixin(EntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Inject(method = "render(Lnet/minecraft/entity/TntEntity;DDDFF)V", at = @At("RETURN"))
    private void fathom$countdown(TntEntity tnt, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        String label = WorldEffects.tntLabel(tnt.fuseTimer, tickDelta);
        if (label != null) {
            renderLabelIfPresent(tnt, label, x, y, z, 64);
        }
    }
}
