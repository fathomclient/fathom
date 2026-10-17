package net.fathom.optimizations.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import net.fathom.optimizations.core.chat.ChatLines;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.visual.ChatStyle;

/**
 * The Chat module: timestamps, folded repeats, background and shadow.
 *
 * <h2>Arrivals</h2>
 *
 * <p>A new message — not a re-wrap of a stored one, which vanilla does when the
 * chat is resized — is restyled before vanilla stores it: a timestamp put in
 * front, and a message identical to the one before it folded into that one with
 * a count, rather than filling the chat with copies. The restyled message is
 * handed back to vanilla's own method, so wrapping, scrolling and the hundred
 * line limit are vanilla's.
 *
 * <h2>Drawing</h2>
 *
 * <p>The background band behind each line and the text shadow are changed at
 * the two calls that draw them, and nowhere else. With the module off both
 * pass vanilla's values through.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudCustomMixin {

    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow @Final private List<ChatHudLine> visibleMessages;

    @Shadow
    private void addMessage(Text message, int id, int ticks, boolean refresh) {
        throw new AssertionError("shadowed");
    }

    @Unique private final ChatLines.Repeats fathom$repeats = new ChatLines.Repeats();
    @Unique private boolean fathom$adding;
    /** The last message this hook added, as vanilla stored it, and its first wrapped line. */
    @Unique private ChatHudLine fathom$lastStored;
    @Unique private ChatHudLine fathom$lastFirstLine;
    @Unique private int fathom$lastLines;

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;IIZ)V", at = @At("HEAD"), cancellable = true)
    private void fathom$restyle(Text message, int id, int ticks, boolean refresh, CallbackInfo info) {
        if (refresh || fathom$adding || message == null) {
            return;
        }
        FathomModule chat = ChatStyle.active();
        boolean stack = chat != null && chat.toggle("stack", false) && id == 0;
        String stamp = ChatStyle.stamp(chat);
        if (!stack) {
            fathom$repeats.reset();
        }
        if (stamp == null && !stack) {
            return;
        }

        Text shown = message;
        if (stack) {
            // The whole component, not its words: two lines that read the same
            // but run different commands when clicked, or say different things
            // when hovered, are two messages. Folding by plain text replaced
            // the first one's click target with the second's.
            String whole = Text.Serializer.serialize(message);
            int count = fathom$repeats.offer(whole);
            if (count > 1 && fathom$foldPrevious()) {
                shown = message.copy().append(new LiteralText("§7" + ChatLines.repeats(count)));
            } else if (count > 1) {
                // The previous copy is not the newest any more; start a new run.
                fathom$repeats.reset();
                fathom$repeats.offer(whole);
            }
        }
        if (stamp != null) {
            shown = new LiteralText("§7" + stamp + " §r").append(shown);
        }

        ChatHudLine previousTop = visibleMessages.isEmpty() ? null : visibleMessages.get(0);
        fathom$adding = true;
        try {
            addMessage(shown, id, ticks, false);
        } finally {
            fathom$adding = false;
        }
        fathom$lastStored = messages.isEmpty() ? null : messages.get(0);
        fathom$lastFirstLine = visibleMessages.isEmpty() ? null : visibleMessages.get(0);
        fathom$lastLines = previousTop == null ? visibleMessages.size() : visibleMessages.indexOf(previousTop);
        info.cancel();
    }

    /** Take the previous copy out of the chat, if it is still the newest thing in it. */
    @Unique
    private boolean fathom$foldPrevious() {
        if (fathom$lastStored == null || fathom$lastLines <= 0 || messages.isEmpty()
                || messages.get(0) != fathom$lastStored || visibleMessages.size() < fathom$lastLines
                || visibleMessages.get(0) != fathom$lastFirstLine) {
            return false;
        }
        for (int line = 0; line < fathom$lastLines; line++) {
            visibleMessages.remove(0);
        }
        messages.remove(0);
        return true;
    }

    @Inject(method = "clear()V", at = @At("HEAD"))
    private void fathom$forgetRepeats(CallbackInfo info) {
        fathom$repeats.reset();
        fathom$lastStored = null;
        fathom$lastFirstLine = null;
        fathom$lastLines = 0;
    }

    @Redirect(method = "render(I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;fill(IIIII)V", ordinal = 0))
    private void fathom$background(int left, int top, int right, int bottom, int colour) {
        DrawableHelper.fill(left, top, right, bottom, ChatStyle.background(colour));
    }

    @Redirect(method = "render(I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
    private int fathom$shadow(TextRenderer renderer, String text, float x, float y, int colour) {
        return ChatStyle.shadow()
                ? renderer.drawWithShadow(text, x, y, colour)
                : renderer.draw(text, x, y, colour, false);
    }
}
