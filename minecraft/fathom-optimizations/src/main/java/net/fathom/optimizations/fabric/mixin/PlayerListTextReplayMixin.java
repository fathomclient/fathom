package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.TextLists;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.PlayerListHud;

/**
 * {@code render.hud.tab-list-text-replay}: the tab list's strings drawn from display
 * lists. See {@link TextLists}. A redirect rather than a wrap so the hook allocates
 * nothing and boxes nothing per string; OptiFine does not patch this class.
 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListTextReplayMixin {

    @Redirect(method = "render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"),
            require = 1, allow = 8)
    private int fathom$replayText(TextRenderer font, String text, float x, float y, int colour) {
        if (Runtime.tabListTextReplay) {
            return TextLists.drawWithShadow(font, text, x, y, colour);
        }
        return font.drawWithShadow(text, x, y, colour);
    }
}
