package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: vanilla string drawing. Every public draw method ends
 * up here, with and without a shadow, so one boundary sees all of it.
 */
@Mixin(TextRenderer.class)
public abstract class ViewTextMixin {

    @Inject(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), require = 0)
    private void fathom$viewTextStart(String text, float x, float y, int colour, boolean shadow,
            CallbackInfoReturnable<Integer> info) {
        ViewProbe.enter(ViewSection.TEXT);
    }

    @Inject(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("RETURN"), require = 0)
    private void fathom$viewTextEnd(String text, float x, float y, int colour, boolean shadow,
            CallbackInfoReturnable<Integer> info) {
        ViewProbe.exit(ViewSection.TEXT);
    }
}
