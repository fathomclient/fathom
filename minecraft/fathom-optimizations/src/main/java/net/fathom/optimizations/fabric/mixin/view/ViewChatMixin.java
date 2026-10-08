package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.hud.ChatHud;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the chat overlay, which a busy server keeps on screen. */
@Mixin(value = ChatHud.class, priority = 900)
public abstract class ViewChatMixin {

    @Inject(method = "render(I)V", at = @At("HEAD"), require = 0)
    private void fathom$viewChatStart(int ticks, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HUD_CHAT);
    }

    @Inject(method = "render(I)V", at = @At("RETURN"), require = 0)
    private void fathom$viewChatEnd(int ticks, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HUD_CHAT);
    }
}
