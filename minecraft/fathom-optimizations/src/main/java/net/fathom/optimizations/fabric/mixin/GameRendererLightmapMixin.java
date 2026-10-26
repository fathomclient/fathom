package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.texture.NativeImageBackedTexture;

import net.fathom.optimizations.fabric.visual.WorldLighting;

/**
 * Fullbright: the gamma the lightmap is built with, for that build only.
 *
 * <h2>Why {@code require = 0}</h2>
 *
 * <p>This and Hurt Camera's constant are the Fathom injections allowed to find
 * nothing. OptiFine can build the lightmap in its own code (custom colours) and
 * a future renderer may too;
 * in that case Fullbright has no effect, which its row says, rather than the
 * game refusing to start over a brightness setting. Every other Mixin keeps
 * the module's {@code defaultRequire} of 1.
 *
 * <h2>Custom lightmaps</h2>
 *
 * <p>OptiFine builds the lightmap from a resource pack's own lightmap image
 * when there is one, most PvP packs included, and clamps the brightness it
 * reads there to vanilla's maximum: the redirect never runs and a larger gamma
 * would change nothing anyway. So when an update finished without the redirect
 * running, the finished pixels get the same gamma curve and are uploaded again.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererLightmapMixin {

    @Shadow
    private boolean lightmapDirty;

    @Shadow
    @Final
    private int[] lightmapTexturePixels;

    @Shadow
    @Final
    private NativeImageBackedTexture lightmapTexture;

    @Unique
    private boolean fathom$updating;

    @Inject(method = "updateLightmap(F)V", at = @At("HEAD"))
    private void fathom$lightmapStart(float tickDelta, CallbackInfo info) {
        fathom$updating = lightmapDirty;
        WorldLighting.beginLightmap();
    }

    @Inject(method = "updateLightmap(F)V", at = @At("RETURN"))
    private void fathom$lightmapEnd(float tickDelta, CallbackInfo info) {
        if (fathom$updating && !lightmapDirty && WorldLighting.brighten(lightmapTexturePixels)) {
            lightmapTexture.upload();
        }
        fathom$updating = false;
    }

    @Redirect(method = "updateLightmap(F)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;gamma:F",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 0)
    private float fathom$fullbright(GameOptions options) {
        return WorldLighting.gamma(options.gamma);
    }
}
