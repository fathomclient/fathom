package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.fabric.visual.Hitboxes;

/**
 * Hitboxes: vanilla's F3+B path, switched on while the module is, drawing
 * Fathom's box in place of vanilla's. See {@link Hitboxes}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherHitboxMixin {

    @Shadow
    private boolean renderHitboxes;

    @Redirect(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;renderHitboxes:Z",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean fathom$hitboxesWanted(EntityRenderDispatcher dispatcher) {
        return renderHitboxes || Hitboxes.forced();
    }

    @Inject(method = "renderHitbox(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void fathom$hitbox(Entity entity, double x, double y, double z, float yaw, float tickDelta,
            CallbackInfo info) {
        if (Hitboxes.draw(entity, x, y, z, tickDelta)) {
            info.cancel();
        }
    }
}
