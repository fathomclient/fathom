package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.fabric.perf.EntityCulling;

/**
 * Entity distance limits, on the answer to "should this entity be drawn".
 *
 * <p>{@code @ModifyReturnValue}, not a cancellable {@code @Inject}: the
 * original method — vanilla's range test and frustum test, as OptiFine left
 * them — runs unchanged, and the handler takes a primitive and returns one, so
 * there is no callback object per entity. The limit is only consulted when the
 * original said yes.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherCullMixin {

    @ModifyReturnValue(
            method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;DDD)Z",
            at = @At("RETURN"))
    private boolean fathom$distanceLimit(boolean original,
            @Local(argsOnly = true) Entity entity,
            @Local(argsOnly = true, ordinal = 0) double cameraX,
            @Local(argsOnly = true, ordinal = 1) double cameraY,
            @Local(argsOnly = true, ordinal = 2) double cameraZ) {
        net.fathom.optimizations.fabric.gfx.GlStateAudit.Scope audit =
                net.fathom.optimizations.fabric.gfx.GlStateAudit.beginRequested(
                        net.fathom.optimizations.fabric.gfx.GlStateAudit.ENTITY_LIMIT,
                        net.fathom.optimizations.fabric.perf.PerfLayer.entitiesLimited);
        boolean result = original && EntityCulling.allow(entity, cameraX, cameraY, cameraZ);
        net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(audit, false, "policy-only");
        net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                net.fathom.optimizations.fabric.gfx.GlStateAudit.ENTITY_LIMIT);
        return result;
    }
}
