package net.fathom.optimizations.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;

/**
 * The chat's stored messages and wrapped lines, read by the in-game self-test to
 * check that restyling keeps every message and every click and hover target.
 * Nothing in a normal run calls it.
 */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {

    @Accessor("messages")
    List<ChatHudLine> fathom$messages();

    @Accessor("visibleMessages")
    List<ChatHudLine> fathom$visibleMessages();
}
