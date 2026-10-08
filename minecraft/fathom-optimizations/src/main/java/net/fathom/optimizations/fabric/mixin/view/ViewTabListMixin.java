package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the tab list, drawn while its key is held. */
@Mixin(PlayerListHud.class)
public abstract class ViewTabListMixin {

    @Inject(method = "render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewTabStart(int width, Scoreboard scoreboard, ScoreboardObjective objective,
            CallbackInfo info) {
        ViewProbe.enter(ViewSection.HUD_TABLIST);
    }

    @Inject(method = "render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewTabEnd(int width, Scoreboard scoreboard, ScoreboardObjective objective,
            CallbackInfo info) {
        ViewProbe.exit(ViewSection.HUD_TABLIST);
    }
}
