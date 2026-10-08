package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.Window;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.PerfLayer;
import net.fathom.optimizations.fabric.perf.VanillaHudReplay;

/**
 * {@code render.hud.vanilla-replay} for the scoreboard sidebar. See {@link VanillaHudReplay}.
 *
 * <p>{@code renderScoreboardObjective} reads, and the signature therefore covers: the
 * objective, its scoreboard's scores and the teams of their holders (all through
 * {@link VanillaHudReplay#scoreboardRevision}, which {@link ScoreboardRevisionMixin}
 * bumps on every change), the scaled window size and the font. Nothing time-based.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudSidebarReplayMixin {

    @Unique private long fathom$checkedSignature;
    @Unique private boolean fathom$recordable;

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$replaySidebar(ScoreboardObjective objective, Window window, CallbackInfo callback) {
        if (!Runtime.vanillaHudReplay || PerfLayer.shadersActive || objective == null) {
            return;
        }
        Scoreboard scoreboard = objective.getScoreboard();
        long signature = 0x53494445L;
        signature = VanillaHudReplay.mix(signature, System.identityHashCode(objective));
        signature = VanillaHudReplay.mix(signature, System.identityHashCode(scoreboard));
        signature = VanillaHudReplay.mix(signature, VanillaHudReplay.scoreboardRevision);
        signature = VanillaHudReplay.mix(signature, window.getWidth());
        signature = VanillaHudReplay.mix(signature, window.getHeight());
        signature = VanillaHudReplay.mix(signature, System.identityHashCode(MinecraftClient.getInstance().textRenderer));
        if (signature != fathom$checkedSignature) {
            fathom$checkedSignature = signature;
            fathom$recordable = !fathom$obfuscated(objective, scoreboard);
        }
        if (!VanillaHudReplay.SIDEBAR.begin(signature, fathom$recordable)) {
            callback.cancel();
            if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(
                        net.fathom.optimizations.core.bench.view.ViewSection.HUD_SCOREBOARD);
            }
        }
    }

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/util/Window;)V",
            at = @At("RETURN"))
    private void fathom$finishSidebar(ScoreboardObjective objective, Window window, CallbackInfo callback) {
        VanillaHudReplay.SIDEBAR.end();
    }

    /** {@code §k} anywhere the sidebar draws: it is redrawn at random every frame. */
    @Unique
    private static boolean fathom$obfuscated(ScoreboardObjective objective, Scoreboard scoreboard) {
        if (obfuscated(objective.getDisplayName())) {
            return true;
        }
        for (ScoreboardPlayerScore score : scoreboard.getAllPlayerScores(objective)) {
            String name = score.getPlayerName();
            if (name != null && obfuscated(Team.decorateName(scoreboard.getPlayerTeam(name), name))) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean obfuscated(String text) {
        return text != null && (text.indexOf("§k") >= 0 || text.indexOf("§K") >= 0);
    }
}
