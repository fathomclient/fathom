package net.fathom.optimizations.forge.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.fathom.optimizations.fabric.visual.HudTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Titles' size on Forge, whose HUD draws titles in its own {@code renderTitle}
 * rather than vanilla's. Forge's own names in the target strings, not remapped
 * (see {@link ForgeHudMixin}); the call itself is remapped with the jar.
 */
@Pseudo
@Mixin(targets = "net.minecraftforge.client.GuiIngameForge", remap = false)
public abstract class ForgeTitlesMixin {

    @Redirect(method = "renderTitle(IIF)V", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;func_179152_a(FFF)V",
                    remap = false))
    private void fathom$titleScale(float x, float y, float z) {
        if (x == y && y == z) {
            float scaled = HudTweaks.titleScale(x);
            GlStateManager.scale(scaled, scaled, scaled);
        } else {
            GlStateManager.scale(x, y, z);
        }
    }
}
