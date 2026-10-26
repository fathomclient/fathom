package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.hud.InGameHud;

/**
 * The title being shown, read by the in-game self-test to check Titles drops
 * a hidden one. Read-only, and nothing in a normal run calls it.
 */
@Mixin(InGameHud.class)
public interface InGameHudTitleAccessor {

    /**
     * The large title line. Yarn's 1.8.9 names have the two fields swapped:
     * {@code setTitles}' first argument, drawn at four times size, is stored
     * in the field it calls {@code subtitle}.
     */
    @Accessor("subtitle")
    String fathom$title();
}
