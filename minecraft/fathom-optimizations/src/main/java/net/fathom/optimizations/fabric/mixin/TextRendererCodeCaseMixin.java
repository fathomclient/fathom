package net.fathom.optimizations.fabric.mixin;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.fabric.Runtime;

/**
 * Format codes without a lowercase copy — {@code render.font.format-code-case}.
 *
 * <p>Vanilla's string drawing loop reads the letter after each {@code §} as
 * {@code text.toLowerCase(Locale.ENGLISH).charAt(i + 1)}: a lowercased copy of
 * the whole line, for every colour code in it, and again for the shadow pass.
 * A scoreboard line with four codes is eight copies a frame. This skips the
 * copy and lowercases the one character instead.
 *
 * <p>Two hooks, both gated on the same per-frame switch: the copy is replaced
 * by the string itself, and the first {@code charAt} after it -- the code
 * letter -- is lowercased on its own. The code letter is only ever looked up
 * in {@code "0123456789abcdefklmnor"}, and for every character
 * {@code Character.toLowerCase} and the whole-string lowercase agree on
 * whether it lowercases into that set. They disagree only where the
 * whole-string lowercase changes the length (U+0130, which becomes two
 * characters): there vanilla reads the character one place off, and a code
 * after it is misread. That is the only output this changes.
 *
 * <p>OptiFine (HD U M5) and Forge (11.15.1.2318) both keep vanilla's call in
 * their versions of this method, checked against their patched classes, so
 * the hooks apply on all four combinations.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererCodeCaseMixin {

    @WrapOperation(method = "draw(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/lang/String;toLowerCase(Ljava/util/Locale;)Ljava/lang/String;"))
    private String fathom$skipLowercaseCopy(String text, Locale locale, Operation<String> original) {
        if (Runtime.fontCodeCase
                && net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) {
            return text;
        }
        return original.call(text, locale);
    }

    @ModifyExpressionValue(method = "draw(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;charAt(I)C", ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Ljava/lang/String;toLowerCase(Ljava/util/Locale;)Ljava/lang/String;")))
    private char fathom$lowercaseCodeLetter(char code) {
        if (Runtime.fontCodeCase
                && net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) {
            return Character.toLowerCase(code);
        }
        return code;
    }
}
