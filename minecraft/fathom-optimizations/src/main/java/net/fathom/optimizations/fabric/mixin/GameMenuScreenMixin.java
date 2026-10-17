package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;

/** Adds an honest, working Fathom entry to the in-world pause menu. */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

    private static final int FATHOM_BUTTON = 0x4641;

    @Inject(method = "init()V", at = @At("RETURN"))
    private void fathom$addHubButton(CallbackInfo info) {
        if (Runtime.hud() == null) {
            return;
        }
        // Vanilla leaves one empty 24-unit row between Achievements/Stats
        // (+48) and Options/LAN (+96): +72, with the same -16 offset vanilla
        // applies to every button. +56 overlapped Achievements by 12 units.
        buttons.add(new ButtonWidget(
                FATHOM_BUTTON, width / 2 - 100, height / 4 + 72 - 16,
                200, 20, "Fathom Options"));
    }

    @Inject(
            method = "buttonClicked(Lnet/minecraft/client/gui/widget/ButtonWidget;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fathom$openHub(ButtonWidget button, CallbackInfo info) {
        if (button != null && button.id == FATHOM_BUTTON && Runtime.hud() != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            Runtime.features().seal();
            FathomNavigation.openHub(client, Runtime.hud());
            info.cancel();
        }
    }
}
