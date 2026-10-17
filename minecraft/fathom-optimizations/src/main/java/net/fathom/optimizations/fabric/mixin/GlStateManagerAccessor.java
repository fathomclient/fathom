package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.platform.GlStateManager;

/** Cheap read-only access to the state Minecraft already caches. */
@Mixin(GlStateManager.class)
public interface GlStateManagerAccessor {

    @Accessor("activeTexture")
    static int fathom$getActiveTexture() {
        throw new AssertionError();
    }

}
