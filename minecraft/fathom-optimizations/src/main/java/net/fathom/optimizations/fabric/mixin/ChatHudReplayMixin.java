package net.fathom.optimizations.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.PerfLayer;
import net.fathom.optimizations.fabric.perf.VanillaHudReplay;

/**
 * {@code render.hud.vanilla-replay} for chat. See {@link VanillaHudReplay}.
 *
 * <p>{@code ChatHud.render(int)} reads, and the signature therefore covers: the
 * tick it is given (line ages and fading), the visible lines (their count and the
 * first and last line objects -- a new message, a removal or a rebuild changes
 * one of them), how far chat is scrolled, the unread marker, whether chat is
 * focused, the chat opacity, scale, width, line count and visibility settings,
 * and the font. Nothing else: no time, no mouse, no world state.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudReplayMixin {

    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private List<ChatHudLine> visibleMessages;
    @Shadow private int scrolledLines;
    @Shadow private boolean hasUnreadNewMessages;

    @Shadow
    public abstract boolean isChatFocused();

    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract int getVisibleLineCount();

    @Shadow
    public abstract float getChatScale();

    @Unique private long fathom$checkedSignature;
    @Unique private boolean fathom$recordable;

    @Inject(method = "render(I)V", at = @At("HEAD"), cancellable = true)
    private void fathom$replayChat(int ticks, CallbackInfo callback) {
        if (!Runtime.vanillaHudReplay || PerfLayer.shadersActive || client.options == null) {
            return;
        }
        int size = visibleMessages.size();
        boolean focused = isChatFocused();
        // Unfocused chat shows lines younger than 200 ticks, newest first. When
        // even the newest is older, vanilla draws nothing, and replaying nothing
        // would still cost a state restore: let vanilla's empty pass run.
        if (!focused && (size == 0 || ticks - visibleMessages.get(0).getCreationTick() >= 200)) {
            return;
        }
        long signature = 0x43484154L;
        signature = VanillaHudReplay.mix(signature, ticks);
        signature = VanillaHudReplay.mix(signature, size);
        signature = VanillaHudReplay.mix(signature, size == 0 ? 0 : System.identityHashCode(visibleMessages.get(0)));
        signature = VanillaHudReplay.mix(signature,
                size == 0 ? 0 : System.identityHashCode(visibleMessages.get(size - 1)));
        signature = VanillaHudReplay.mix(signature, scrolledLines);
        signature = VanillaHudReplay.mix(signature, (hasUnreadNewMessages ? 1 : 0) | (focused ? 2 : 0));
        signature = VanillaHudReplay.mix(signature, Float.floatToIntBits(client.options.chatOpacity));
        signature = VanillaHudReplay.mix(signature, Float.floatToIntBits(getChatScale()));
        signature = VanillaHudReplay.mix(signature, getWidth());
        signature = VanillaHudReplay.mix(signature, getVisibleLineCount());
        signature = VanillaHudReplay.mix(signature, client.options.chatVisibilityType.ordinal());
        signature = VanillaHudReplay.mix(signature, System.identityHashCode(client.textRenderer));
        // The Chat module's background and shadow change what is drawn.
        signature = VanillaHudReplay.mix(signature, net.fathom.optimizations.fabric.visual.ChatStyle.signature());
        if (signature != fathom$checkedSignature) {
            fathom$checkedSignature = signature;
            fathom$recordable = !fathom$obfuscatedOnScreen();
        }
        if (!VanillaHudReplay.CHAT.begin(signature, fathom$recordable)) {
            net.fathom.optimizations.core.bench.view.ViewProbe.engaged(net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_HUD_REPLAY);
            callback.cancel();
            if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(
                        net.fathom.optimizations.core.bench.view.ViewSection.HUD_CHAT);
            }
        }
    }

    @Inject(method = "render(I)V", at = @At("RETURN"))
    private void fathom$finishChat(int ticks, CallbackInfo callback) {
        VanillaHudReplay.CHAT.end();
    }

    /** {@code §k} in a line that can be on screen: it is redrawn at random every frame. */
    @Unique
    private boolean fathom$obfuscatedOnScreen() {
        int last = Math.min(visibleMessages.size(), scrolledLines + getVisibleLineCount() + 1);
        for (int index = Math.max(0, scrolledLines); index < last; index++) {
            String text = visibleMessages.get(index).getText().asFormattedString();
            if (text.indexOf("§k") >= 0 || text.indexOf("§K") >= 0) {
                return true;
            }
        }
        return false;
    }
}
