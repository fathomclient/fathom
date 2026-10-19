package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.hud.HudHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fathom's HUD under Forge.
 *
 * <p>Forge draws the in-game HUD with {@code GuiIngameForge}, whose
 * {@code renderGameOverlay} replaces vanilla's without calling it, so the
 * vanilla hooks in {@code InGameHudMixin} never run there. These are the same
 * four moments in Forge's method: start and end of the HUD, just before the
 * chat (inside the same translation vanilla uses), and just after the
 * crosshair decision.
 *
 * <p>Forge's classes are not in Fabric's mappings, so every name here is the
 * one the game uses at run time under Forge (SRG for vanilla members, Forge's
 * own names for its methods) and nothing is remapped.
 */
@Pseudo
@Mixin(targets = "net.minecraftforge.client.GuiIngameForge", remap = false)
public abstract class ForgeHudMixin {

    @Inject(method = "func_175180_a(F)V", at = @At("HEAD"), remap = false)
    private void fathom$hudBegin(float partialTicks, CallbackInfo info) {
        HudHooks.begin();
    }

    @Inject(method = "renderChat(II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;func_146230_a(I)V",
                    remap = false),
            remap = false)
    private void fathom$hudWidgets(int width, int height, CallbackInfo info) {
        HudHooks.widgets();
    }

    @Inject(method = "renderCrosshairs(II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/GuiIngameForge;func_175183_b()Z",
                    shift = At.Shift.AFTER, remap = false),
            remap = false)
    private void fathom$hudCrosshair(int width, int height, CallbackInfo info) {
        HudHooks.crosshair();
    }

    @Inject(method = "func_175180_a(F)V", at = @At("RETURN"), remap = false)
    private void fathom$hudEnd(float partialTicks, CallbackInfo info) {
        HudHooks.end();
    }
}
