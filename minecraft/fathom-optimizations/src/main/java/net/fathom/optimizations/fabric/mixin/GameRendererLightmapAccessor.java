package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.render.GameRenderer;

/**
 * The lightmap as last built, read by the in-game self-test to check Fullbright
 * did what it says. Read-only, and nothing in a normal run calls it.
 */
@Mixin(GameRenderer.class)
public interface GameRendererLightmapAccessor {

    @Accessor("lightmapTexturePixels")
    int[] fathom$lightmapPixels();
}
