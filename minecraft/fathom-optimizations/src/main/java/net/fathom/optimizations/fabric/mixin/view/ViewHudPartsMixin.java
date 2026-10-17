package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.Window;
import net.minecraft.scoreboard.ScoreboardObjective;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the parts of the vanilla HUD a server fills -- the
 * scoreboard sidebar above all -- and the hotbar and bars every frame draws.
 * Chat and the tab list are drawn by their own classes ({@link ViewChatMixin},
 * {@link ViewTabListMixin}).
 */
@Mixin(value = InGameHud.class, priority = 900)
public abstract class ViewHudPartsMixin {

    @Inject(method = "renderHotbar(Lnet/minecraft/client/util/Window;F)V", at = @At("HEAD"), require = 0)
    private void fathom$viewHotbarStart(Window window, float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HUD_HOTBAR);
    }

    @Inject(method = "renderHotbar(Lnet/minecraft/client/util/Window;F)V", at = @At("RETURN"), require = 0)
    private void fathom$viewHotbarEnd(Window window, float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HUD_HOTBAR);
    }

    @Inject(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V", at = @At("HEAD"), require = 0)
    private void fathom$viewBarsStart(Window window, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HUD_STATUS);
    }

    @Inject(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V", at = @At("RETURN"), require = 0)
    private void fathom$viewBarsEnd(Window window, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HUD_STATUS);
    }

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewSidebarStart(ScoreboardObjective objective, Window window, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HUD_SCOREBOARD);
    }

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/util/Window;)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewSidebarEnd(ScoreboardObjective objective, Window window, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HUD_SCOREBOARD);
    }
}
