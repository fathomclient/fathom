package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


import net.minecraft.client.gui.hud.InGameHud;


/**
 * Draws Fathom's HUD.
 *
 * <h2>Where in vanilla's HUD it goes</h2>
 *
 * <p>After the hotbar, the bars, the debug screen and the scoreboard, and
 * before the chat and the player list. Vanilla's own HUD has to be underneath
 * the widgets — a hotbar drawn over a widget that overlaps it is the sort of
 * thing that only shows up in the one place a widget happens to sit — but the
 * chat and the player list are things a player asks for, and have to be on
 * top. The widgets used to be drawn at the very end, so holding Tab in a
 * minigame showed the clock, the ping and the potions over the names it was
 * held to read.
 *
 * <p>The chat is drawn inside a {@code translate(0, height - 48)}; the injection
 * point is the chat call itself, so the pass undoes that translation for its
 * own drawing and puts back the state vanilla had set up for the chat.
 *
 * <h2>Why the editor is checked here</h2>
 *
 * <p>The editor draws the widgets itself, over the world, so it can show
 * placeholders and hit boxes. This injector skips its own pass while the
 * editor is current — otherwise every widget would be drawn twice.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    /**
     * The world is drawn and no HUD is: the one moment a menu opening over the
     * world can capture it clean. One boolean read unless a menu asked.
     */
    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void fathom$captureBackdrop(float tickDelta, CallbackInfo info) {
        net.fathom.optimizations.fabric.hud.HudHooks.begin();
    }

    @Inject(method = "render(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;render(I)V"))
    private void fathom$drawHud(float tickDelta, CallbackInfo info) {
        net.fathom.optimizations.fabric.hud.HudHooks.widgets();
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void fathom$afterHud(float tickDelta, CallbackInfo info) {
        net.fathom.optimizations.fabric.hud.HudHooks.end();
    }

}
