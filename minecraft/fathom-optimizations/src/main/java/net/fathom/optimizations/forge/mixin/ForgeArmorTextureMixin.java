package net.fathom.optimizations.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ForgeArmorTextures;

/**
 * {@code render.entity.armor-texture-cache} under Forge, on the method Forge
 * actually draws armour through. See {@link ForgeArmorTextures} for why the
 * vanilla hook ({@code ArmorTextureCacheMixin}) never runs there.
 *
 * <p>{@code getArmorResource} is Forge's own method, so nothing here is
 * remapped: the class and descriptor are the names the game uses at run time
 * under Forge (see {@link ForgeHudMixin}).
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.entity.layers.LayerArmorBase", remap = false)
public abstract class ForgeArmorTextureMixin {

    @Inject(method = "getArmorResource(Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;ILjava/lang/String;)Lnet/minecraft/util/ResourceLocation;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void fathom$knownForgeTexture(Entity entity, ItemStack stack, int slot, String type,
            CallbackInfoReturnable<Identifier> callback) {
        if (Runtime.armorTextureCache) {
            Identifier known = ForgeArmorTextures.lookup(stack, slot, type);
            if (known != null) {
                net.fathom.optimizations.core.bench.view.ViewProbe.engaged(
                        net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_ARMOR_TEXTURE);
                callback.setReturnValue(known);
            }
        }
    }

    @ModifyReturnValue(
            method = "getArmorResource(Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;ILjava/lang/String;)Lnet/minecraft/util/ResourceLocation;",
            at = @At("RETURN"), remap = false, require = 1)
    private Identifier fathom$rememberForgeTexture(Identifier texture, @Local(argsOnly = true) ItemStack stack,
            @Local(argsOnly = true) int slot, @Local(argsOnly = true) String type) {
        if (Runtime.armorTextureCache) {
            ForgeArmorTextures.remember(stack, slot, type, texture);
        }
        return texture;
    }
}
