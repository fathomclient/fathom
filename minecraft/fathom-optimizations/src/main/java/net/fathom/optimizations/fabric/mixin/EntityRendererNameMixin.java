package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import net.fathom.optimizations.fabric.perf.NametagNames;

/**
 * {@code render.entity.nametag-name-cache} for entities that are not living. See
 * {@link NametagNames}.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameMixin {

    @Redirect(method = "method_10208(Lnet/minecraft/entity/Entity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getName()Lnet/minecraft/text/Text;"))
    private Text fathom$cachedName(Entity entity) {
        return NametagNames.name(entity);
    }

    @Redirect(method = "method_10208(Lnet/minecraft/entity/Entity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/text/Text;asFormattedString()Ljava/lang/String;"))
    private String fathom$cachedFormatted(Text text) {
        return NametagNames.formatted(text);
    }
}
