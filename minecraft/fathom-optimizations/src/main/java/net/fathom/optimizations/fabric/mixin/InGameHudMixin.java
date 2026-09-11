package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Draws Fathom's HUD.
 *
 * <h2>Why {@code RETURN} and not {@code HEAD}</h2>
 *
 * <p>Vanilla's HUD has to be underneath. Drawing first would put the hotbar and the health
 * bar on top of anything overlapping them, which is both wrong and the sort of thing that
 * only shows up in the one place a widget happens to overlap.
 *
 * <h2>Why the editor is checked here</h2>
 *
 * <p>{@code InGameHud.render} is not called while a screen is open, so a HUD editor that
 * relied on this hook would have nothing to arrange. The editor therefore calls
 * {@link FathomHud#render} itself, and this injector skips its own call when the editor is
 * the current screen — otherwise every widget would be drawn twice, and the second copy
 * would be at a different scale.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void fathom$drawHud(float tickDelta, CallbackInfo info) {
        FathomHud hud = Runtime.hud();
        if (hud == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !MinecraftClient.isHudEnabled()) {
            // F1 hides the HUD. A HUD Fathom kept drawing through F1 would be a
            // HUD nobody could take a screenshot without.
            return;
        }
        if (client.currentScreen instanceof net.fathom.optimizations.fabric.hud.HudEditorScreen) {
            return;
        }
        hud.render(client, false);
        net.fathom.optimizations.fabric.hud.HudCapture.frame(client);
    }
}
