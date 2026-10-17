package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;

import net.fathom.optimizations.fabric.perf.NametagNames;

/**
 * {@code render.entity.nametag-name-cache} for living entities: the two calls in
 * {@code renderName} that build the label string. See {@link NametagNames}.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererNameMixin {

    @Redirect(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getName()Lnet/minecraft/text/Text;"))
    private Text fathom$cachedName(LivingEntity entity) {
        return NametagNames.name(entity);
    }

    @Redirect(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/text/Text;asFormattedString()Ljava/lang/String;"))
    private String fathom$cachedFormatted(Text text) {
        return NametagNames.formatted(text);
    }
}
