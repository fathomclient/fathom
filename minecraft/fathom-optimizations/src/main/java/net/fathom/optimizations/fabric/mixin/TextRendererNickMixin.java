package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.fabric.visual.NickHider;

/**
 * Nick Hider: every string the font draws, with your name replaced. Every
 * public draw call, shadowed or not, reaches this method; widths are handled
 * in {@link TextRendererWidthMixin}.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererNickMixin {

    @ModifyVariable(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true)
    private String fathom$hideNick(String text) {
        return NickHider.apply(text);
    }
}
