package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.core.opt.text.WidthMemo;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Font metrics caching — {@code render.font.metrics-cache}.
 *
 * <p>The first optimization in this project that actually changes what the game does at
 * runtime. Everything before it measured.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code TextRenderer.getStringWidth} walks the string character by character on every
 * call, consulting the width tables and interpreting format codes. The game calls it at
 * frame rate over a corpus that barely changes between frames: fifteen scoreboard lines,
 * eighty tab-list entries, a screen of chat, a nametag per visible player. This remembers
 * the answer and returns it the second time.
 *
 * <h2>Why it cannot return a wrong width</h2>
 *
 * <p>Because it never computes one. On a miss the original method runs untouched and its
 * return value is stored; on a hit that same {@code int} is handed back. There is no
 * reimplementation of vanilla's layout rules here to disagree with vanilla — see
 * {@link WidthMemo}, and contrast {@code TextMetricsCache}, which does reimplement them
 * and is correspondingly not the one wired up.
 *
 * <p>The one real obligation is invalidation: if the font changes and the table does not,
 * every remembered width is measured against a font that is no longer loaded. Both points
 * where 1.8.9 can change it are covered below, and both drop the table wholesale rather
 * than trying to be clever about which entries survive.
 *
 * <h2>Why HEAD-cancel and RETURN, rather than a redirect</h2>
 *
 * <p>A cancellable {@code HEAD} injection that calls {@code setReturnValue} returns
 * immediately, so the {@code RETURN} injector below does not run on a hit and the entry
 * is not rewritten with itself. On a miss the {@code HEAD} injector does nothing, the
 * original body runs, and {@code RETURN} records the result. Nothing in the original
 * method is replaced or reordered.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererWidthMixin {

    /**
     * Lazily created rather than field-initialised.
     *
     * <p>Mixin does merge field initialisers into the target's constructor, but the
     * target here is constructed during resource loading before much else exists, and a
     * null check on a hot path is cheaper than depending on that merge behaving.
     */
    @Unique private WidthMemo fathom$memo;

    @Unique
    private WidthMemo fathom$memo() {
        if (fathom$memo == null) {
            fathom$memo = new WidthMemo();
        }
        return fathom$memo;
    }

    @Inject(method = "getStringWidth(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void fathom$widthFromMemo(String text, CallbackInfoReturnable<Integer> callback) {
        if (!Runtime.enabled("render.font.metrics-cache")) {
            return;
        }
        Integer known = fathom$memo().lookup(text);
        if (known != null) {
            callback.setReturnValue(known);
        }
    }

    @Inject(method = "getStringWidth(Ljava/lang/String;)I", at = @At("RETURN"))
    private void fathom$rememberWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (!Runtime.enabled("render.font.metrics-cache")) {
            return;
        }
        fathom$memo().remember(text, callback.getReturnValueI());
    }

    /**
     * Resource reload. {@code TextRenderer.init} rebuilds the glyph width tables, which
     * is exactly the event that makes every remembered width stale.
     */
    @Inject(method = "init()V", at = @At("RETURN"))
    private void fathom$fontReloaded(CallbackInfo callback) {
        fathom$memo().invalidate();
    }

    /** The unicode font toggle changes widths without reloading resources. */
    @Inject(method = "setUnicode(Z)V", at = @At("RETURN"))
    private void fathom$unicodeChanged(boolean unicode, CallbackInfo callback) {
        fathom$memo().invalidate();
    }
}
