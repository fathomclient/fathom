package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.gui.hud.InGameHud;

import net.fathom.optimizations.fabric.visual.HudTweaks;

/**
 * Titles and Boss Bar.
 *
 * <p>A title or subtitle the module hides is dropped as it arrives, so the
 * other half of a pair, and the timing the server set, are unaffected. The
 * size is the scale vanilla draws them at, multiplied. The boss bar is not
 * drawn at all while Boss Bar is on; Forge's HUD calls this same method.
 *
 * <p>The scale redirect is {@code require = 0}: Forge draws titles in its own
 * {@code renderTitle} (see the Forge mixin), and there the vanilla call is
 * never reached.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudTitlesMixin {

    @Inject(method = "setTitles(Ljava/lang/String;Ljava/lang/String;III)V", at = @At("HEAD"), cancellable = true)
    private void fathom$hideTitles(String title, String subtitle, int fadeIn, int stay, int fadeOut,
            CallbackInfo info) {
        if ((title != null && HudTweaks.hideTitle(false)) || (subtitle != null && HudTweaks.hideTitle(true))) {
            info.cancel();
        }
    }

    @Redirect(method = "render(F)V", require = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;scale(FFF)V"))
    private void fathom$titleScale(float x, float y, float z) {
        if (x == y && y == z) {
            float scaled = HudTweaks.titleScale(x);
            GlStateManager.scale(scaled, scaled, scaled);
        } else {
            GlStateManager.scale(x, y, z);
        }
    }

    @Inject(method = "renderBossBar()V", at = @At("HEAD"), cancellable = true)
    private void fathom$hideBossBar(CallbackInfo info) {
        if (HudTweaks.hideBossBar()) {
            info.cancel();
        }
    }
}
