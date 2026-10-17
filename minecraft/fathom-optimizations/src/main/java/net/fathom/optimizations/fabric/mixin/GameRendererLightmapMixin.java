package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.fabric.visual.WorldLighting;

/**
 * Fullbright: the gamma the lightmap is built with, for that build only.
 *
 * <h2>Why {@code require = 0}</h2>
 *
 * <p>This is the one Fathom redirect allowed to find nothing. OptiFine can build
 * the lightmap in its own code (custom colours) and a future renderer may too;
 * in that case Fullbright has no effect, which its row says, rather than the
 * game refusing to start over a brightness setting. Every other Mixin keeps
 * the module's {@code defaultRequire} of 1.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererLightmapMixin {

    @Redirect(method = "updateLightmap(F)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;gamma:F",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 0)
    private float fathom$fullbright(GameOptions options) {
        return WorldLighting.gamma(options.gamma);
    }
}
