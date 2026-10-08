package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.item.ArmorItem;
import net.minecraft.util.Identifier;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ArmorTextures;

/**
 * {@code render.entity.armor-texture-cache}: the armour layer's texture lookup without
 * two {@code String.format} calls per armour piece per frame. See {@link ArmorTextures}.
 *
 * <p>The same shape as the font metrics cache: a hit returns the identifier the
 * original method returned the first time; a miss runs the original untouched and
 * remembers its answer.
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class ArmorTextureCacheMixin {

    @Inject(method = "getArmorTexture(Lnet/minecraft/item/ArmorItem;ZLjava/lang/String;)Lnet/minecraft/util/Identifier;",
            at = @At("HEAD"), cancellable = true)
    private void fathom$knownTexture(ArmorItem item, boolean secondLayer, String overlay,
            CallbackInfoReturnable<Identifier> callback) {
        if (Runtime.armorTextureCache) {
            Identifier known = ArmorTextures.lookup(item, secondLayer, overlay);
            if (known != null) {
                callback.setReturnValue(known);
            }
        }
    }

    @ModifyReturnValue(
            method = "getArmorTexture(Lnet/minecraft/item/ArmorItem;ZLjava/lang/String;)Lnet/minecraft/util/Identifier;",
            at = @At("RETURN"))
    private Identifier fathom$rememberTexture(Identifier texture, @Local(argsOnly = true) ArmorItem item,
            @Local(argsOnly = true) boolean secondLayer, @Local(argsOnly = true) String overlay) {
        if (Runtime.armorTextureCache) {
            ArmorTextures.remember(item, secondLayer, overlay, texture);
        }
        return texture;
    }
}
