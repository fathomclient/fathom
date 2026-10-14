package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.hud.InGameHud;

import net.fathom.optimizations.core.module.CrosshairPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Hides vanilla's crosshair when Fathom is drawing its own.
 *
 * <h2>Why this is a separate Mixin from the one that draws the HUD</h2>
 *
 * <p>Different question, different method. {@code InGameHudMixin} adds drawing
 * after vanilla's; this answers a question vanilla asks itself before drawing
 * one particular thing. Merging them would mean the HUD injector knowing about
 * the crosshair module, which is the sort of special case that makes a renderer
 * stop being general.
 *
 * <h2>Why the setting can turn this off</h2>
 *
 * <p>Because drawing both is a legitimate thing to want for the ten seconds
 * somebody spends deciding whether they prefer theirs, and because a client that
 * hides vanilla's crosshair and then fails to draw its own leaves you aiming at
 * nothing. The setting is the way back.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudCrosshairMixin {

    @Inject(method = "showCrosshair()Z", at = @At("RETURN"), cancellable = true)
    private void fathom$hideVanillaCrosshair(CallbackInfoReturnable<Boolean> info) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) return;
            if (!Boolean.TRUE.equals(info.getReturnValue()) || Runtime.hud() == null) return;
            FathomModule crosshair = Runtime.features().find("crosshair");
            if (CrosshairPolicy.hidesVanilla(crosshair)) info.setReturnValue(Boolean.FALSE);
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }

    /**
     * Draw Fathom's crosshair where vanilla draws its own: straight after the
     * hotbar, under the boss bar, the scoreboard, the chat and the player
     * list. It used to be drawn with the rest of Fathom's HUD at the end of the
     * pass, on top of the chat.
     */
    @Inject(method = "render(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;showCrosshair()Z",
                    shift = At.Shift.AFTER))
    private void fathom$drawCrosshair(float tickDelta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                    || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowHudPass()) return;
            net.fathom.optimizations.fabric.hud.FathomHud hud = Runtime.hud();
            if (hud == null) return;
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || !net.minecraft.client.MinecraftClient.isHudEnabled()) return;
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.hud.HudEditorScreen
                    || client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen) return;
            java.util.Map<String, String> glBefore = null;
            java.util.List<String> desyncBefore = null;
            if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecking()) {
                glBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.snapshot();
                desyncBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.desync();
            }
            boolean drew = hud.renderCrosshair(client);
            if (drew) {
                // Put back what the rest of vanilla's HUD expects: its icon
                // sheet bound, blending on, an untinted colour.
                com.mojang.blaze3d.platform.GlStateManager.enableTexture();
                com.mojang.blaze3d.platform.GlStateManager.color(1f, 1f, 1f, 1f);
                client.getTextureManager().bindTexture(net.minecraft.client.gui.DrawableHelper.GUI_ICONS_TEXTURE);
                com.mojang.blaze3d.platform.GlStateManager.enableBlend();
            }
            if (glBefore != null && drew) {
                net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecked("crosshair", glBefore, desyncBefore);
            }
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }
}
