package net.fathom.optimizations.fabric.mixin.view;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;

import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: the world renderer's phases, one boundary each.
 *
 * <p>Timed at the callee rather than at the call site in the world pass:
 * OptiFine rewrites the world pass but keeps these methods, so a method-level
 * boundary survives where a call-site one would not.
 */
@Mixin(WorldRenderer.class)
public abstract class ViewWorldRendererMixin {

    @Shadow private List<?> visibleChunks;

    private static final ViewSection[] LAYERS = {
        ViewSection.LAYER_SOLID, ViewSection.LAYER_CUTOUT_MIPPED,
        ViewSection.LAYER_CUTOUT, ViewSection.LAYER_TRANSLUCENT
    };

    private static ViewSection fathom$layer(RenderLayer layer) {
        int index = layer == null ? -1 : layer.ordinal();
        return index >= 0 && index < LAYERS.length ? LAYERS[index] : ViewSection.LAYER_SOLID;
    }

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), require = 0)
    private void fathom$viewSkyStart(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.enter(ViewSection.SKY);
    }

    @Inject(method = "renderSky(FI)V", at = @At("RETURN"), require = 0)
    private void fathom$viewSkyEnd(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.exit(ViewSection.SKY);
    }

    @Inject(method = "renderClouds(FI)V", at = @At("HEAD"), require = 0)
    private void fathom$viewCloudsStart(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.enter(ViewSection.CLOUDS);
    }

    @Inject(method = "renderClouds(FI)V", at = @At("RETURN"), require = 0)
    private void fathom$viewCloudsEnd(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.exit(ViewSection.CLOUDS);
    }

    @Inject(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewTerrainStart(Entity entity, double tickDelta, CameraView camera, int frame,
            boolean spectator, CallbackInfo info) {
        ViewProbe.enter(ViewSection.SETUP_TERRAIN);
    }

    @Inject(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewTerrainEnd(Entity entity, double tickDelta, CameraView camera, int frame,
            boolean spectator, CallbackInfo info) {
        ViewProbe.exit(ViewSection.SETUP_TERRAIN);
        List<?> visible = visibleChunks;
        ViewProbe.level(ViewCounter.VISIBLE_SECTIONS, visible == null ? 0 : visible.size());
    }

    @Inject(method = "updateChunks(J)V", at = @At("HEAD"), require = 0)
    private void fathom$viewUpdatesStart(long finishBy, CallbackInfo info) {
        ViewProbe.enter(ViewSection.CHUNK_UPDATES);
    }

    @Inject(method = "updateChunks(J)V", at = @At("RETURN"), require = 0)
    private void fathom$viewUpdatesEnd(long finishBy, CallbackInfo info) {
        ViewProbe.exit(ViewSection.CHUNK_UPDATES);
    }

    @Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), require = 0)
    private void fathom$viewLayerStart(RenderLayer layer, double tickDelta, int anaglyph, Entity entity,
            CallbackInfoReturnable<Integer> info) {
        ViewProbe.enter(fathom$layer(layer));
    }

    @Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("RETURN"), require = 0)
    private void fathom$viewLayerEnd(RenderLayer layer, double tickDelta, int anaglyph, Entity entity,
            CallbackInfoReturnable<Integer> info) {
        ViewProbe.exit(fathom$layer(layer));
        ViewCounter counter = ViewCounter.layer(layer == null ? -1 : layer.ordinal());
        if (counter != null) {
            ViewProbe.count(counter, info.getReturnValueI());
        }
    }

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewEntitiesStart(Entity entity, CameraView camera, float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.ENTITIES);
    }

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewEntitiesEnd(Entity entity, CameraView camera, float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.ENTITIES);
    }

    @Inject(method = "drawBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;IF)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewSelectionStart(PlayerEntity player, BlockHitResult hit, int mode, float tickDelta,
            CallbackInfo info) {
        ViewProbe.enter(ViewSection.SELECTION);
    }

    @Inject(method = "drawBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;IF)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewSelectionEnd(PlayerEntity player, BlockHitResult hit, int mode, float tickDelta,
            CallbackInfo info) {
        ViewProbe.exit(ViewSection.SELECTION);
    }

    @Inject(method = "drawBlockDamage(Lnet/minecraft/client/render/Tessellator;Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/entity/Entity;F)V",
            at = @At("HEAD"), require = 0)
    private void fathom$viewDamageStart(Tessellator tessellator, BufferBuilder buffer, Entity entity,
            float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.BLOCK_DAMAGE);
    }

    @Inject(method = "drawBlockDamage(Lnet/minecraft/client/render/Tessellator;Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/entity/Entity;F)V",
            at = @At("RETURN"), require = 0)
    private void fathom$viewDamageEnd(Tessellator tessellator, BufferBuilder buffer, Entity entity,
            float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.BLOCK_DAMAGE);
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), require = 0)
    private void fathom$viewBorderStart(Entity entity, float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.WORLD_BORDER);
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"), require = 0)
    private void fathom$viewBorderEnd(Entity entity, float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.WORLD_BORDER);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewTickStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_RENDERERS);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewTickEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_RENDERERS);
    }
}
