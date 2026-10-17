package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.entity.Entity;

import net.fathom.optimizations.fabric.perf.NametagNames;

/** Somewhere for {@link NametagNames} to keep an entity's label between frames: one field. */
@Mixin(Entity.class)
public abstract class EntityNameEntryMixin implements NametagNames.Holder {

    @Unique
    private NametagNames.Entry fathom$nameEntry;

    @Override
    public NametagNames.Entry fathom$nameEntry() {
        return fathom$nameEntry;
    }

    @Override
    public void fathom$setNameEntry(NametagNames.Entry entry) {
        fathom$nameEntry = entry;
    }
}
