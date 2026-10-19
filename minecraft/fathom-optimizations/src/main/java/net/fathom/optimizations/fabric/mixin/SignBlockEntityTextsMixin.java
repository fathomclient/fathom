package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.fathom.optimizations.fabric.perf.SignTexts;
import net.minecraft.block.entity.SignBlockEntity;

/** Gives each sign somewhere to keep its wrapped lines. See {@link SignTexts}. */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityTextsMixin implements SignTexts.Holder {

    @Unique
    private SignTexts.Entry fathom$texts;

    @Override
    public SignTexts.Entry fathom$signTexts() {
        SignTexts.Entry entry = fathom$texts;
        if (entry == null) {
            entry = new SignTexts.Entry();
            fathom$texts = entry;
        }
        return entry;
    }
}
