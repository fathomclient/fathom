package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;

import net.fathom.optimizations.fabric.perf.VanillaHudReplay;

/**
 * Counts changes to the scoreboard, for {@code render.hud.vanilla-replay}'s sidebar.
 *
 * <p>Every change a score, an objective or a team can undergo calls one of the
 * scoreboard's own {@code update*} notifications (a score's {@code setScore}, a
 * team's prefix, suffix and display name setters, an objective's display name and
 * render type): those, plus the three mutations that do not notify -- team
 * membership, and the score {@code getPlayerScore} creates on first read -- bump
 * {@link VanillaHudReplay#scoreboardRevision}. Over-counting only costs a re-record.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardRevisionMixin {

    @Unique
    private static void fathom$changed() {
        VanillaHudReplay.scoreboardRevision++;
    }

    @Inject(method = "updateObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"))
    private void fathom$objective(ScoreboardObjective objective, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateExistingObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"))
    private void fathom$existingObjective(ScoreboardObjective objective, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateRemovedObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"))
    private void fathom$removedObjective(ScoreboardObjective objective, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateScore(Lnet/minecraft/scoreboard/ScoreboardPlayerScore;)V", at = @At("HEAD"))
    private void fathom$score(ScoreboardPlayerScore score, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updatePlayerScore(Ljava/lang/String;)V", at = @At("HEAD"))
    private void fathom$playerScores(String player, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updatePlayerScore(Ljava/lang/String;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"))
    private void fathom$playerScore(String player, ScoreboardObjective objective, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateScoreboardTeamAndPlayers(Lnet/minecraft/scoreboard/Team;)V", at = @At("HEAD"))
    private void fathom$teamAndPlayers(Team team, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateScoreboardTeam(Lnet/minecraft/scoreboard/Team;)V", at = @At("HEAD"))
    private void fathom$team(Team team, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "updateRemovedTeam(Lnet/minecraft/scoreboard/Team;)V", at = @At("HEAD"))
    private void fathom$removedTeam(Team team, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "setObjectiveSlot(ILnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"))
    private void fathom$slot(int slot, ScoreboardObjective objective, CallbackInfo info) {
        fathom$changed();
    }

    @Inject(method = "addPlayerToTeam(Ljava/lang/String;Ljava/lang/String;)Z", at = @At("HEAD"))
    private void fathom$joined(String player, String team, CallbackInfoReturnable<Boolean> info) {
        fathom$changed();
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/scoreboard/Team;)V", at = @At("HEAD"))
    private void fathom$left(String player, Team team, CallbackInfo info) {
        fathom$changed();
    }

    /** The score {@code getPlayerScore} creates when asked for one that does not exist. */
    @Inject(method = "getPlayerScore(Ljava/lang/String;Lnet/minecraft/scoreboard/ScoreboardObjective;)Lnet/minecraft/scoreboard/ScoreboardPlayerScore;",
            at = @At(value = "NEW", target = "net/minecraft/scoreboard/ScoreboardPlayerScore"))
    private void fathom$created(String player, ScoreboardObjective objective,
            CallbackInfoReturnable<ScoreboardPlayerScore> info) {
        fathom$changed();
    }
}
