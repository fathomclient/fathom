package net.fathom.optimizations.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.SignTexts;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import net.minecraft.text.Text;

/**
 * {@code render.blockentity.sign-text-cache}: a sign's lines wrapped and formatted once,
 * not every frame. See {@link SignTexts}.
 *
 * <p>Wraps the two calls inside the line loop -- {@code Texts.wrapLines} and the first
 * wrapped line's {@code asFormattedString} -- rather than replacing the loop, so the
 * editing markers, OptiFine's custom sign colours and its text distance check all stay
 * exactly where vanilla and OptiFine put them. Both calls are present once each in
 * OptiFine M5's patched renderer (checked in its patched bytecode).
 */
@Mixin(SignBlockEntityRenderer.class)
public abstract class SignTextCacheMixin {

    @WrapOperation(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/util/Texts;wrapLines(Lnet/minecraft/text/Text;ILnet/minecraft/client/font/TextRenderer;ZZ)Ljava/util/List;"))
    private List<Text> fathom$wrapOnce(Text text, int width, TextRenderer font, boolean a, boolean b,
            Operation<List<Text>> original, @Local(argsOnly = true) SignBlockEntity sign) {
        if (!Runtime.signTextCache) {
            return original.call(text, width, font, a, b);
        }
        SignTexts.Entry entry = ((SignTexts.Holder) sign).fathom$signTexts();
        if (!SignTexts.VERIFY_ACTIVE) {
            List<Text> known = SignTexts.wrapped(entry, sign.text, text, width, font, a, b);
            if (known != null) {
                net.fathom.optimizations.core.bench.view.ViewProbe.engaged(net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_SIGN_TEXT);
                return known;
            }
        }
        List<Text> result = original.call(text, width, font, a, b);
        SignTexts.rememberWrap(entry, sign.text, text, width, font, a, b, result);
        return result;
    }

    @WrapOperation(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/text/Text;asFormattedString()Ljava/lang/String;"))
    private String fathom$formatOnce(Text text, Operation<String> original,
            @Local(argsOnly = true) SignBlockEntity sign) {
        if (!Runtime.signTextCache) {
            return original.call(text);
        }
        SignTexts.Entry entry = ((SignTexts.Holder) sign).fathom$signTexts();
        if (!SignTexts.VERIFY_ACTIVE) {
            String known = SignTexts.formatted(entry, text);
            if (known != null) {
                return known;
            }
        }
        String result = original.call(text);
        SignTexts.rememberFormatted(entry, text, result);
        return result;
    }
}
