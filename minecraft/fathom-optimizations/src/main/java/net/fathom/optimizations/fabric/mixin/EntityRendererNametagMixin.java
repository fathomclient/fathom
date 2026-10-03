package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.fabric.perf.NametagRenderer;
import net.fathom.optimizations.fabric.perf.PerfLayer;

/**
 * The fast nametag path, at the one method every floating label goes through.
 *
 * <p>Players, named mobs, holograms and the score line under a player's name
 * all arrive at {@code renderLabelIfPresent} with the finished string. The
 * decisions about <em>whether</em> a name shows — team visibility, the sneaking
 * variant, which is drawn by {@code LivingEntityRenderer} itself — are made
 * before it, by vanilla, and are not touched.
 *
 * <p>The handler is two field reads and a call, and cancels only when the fast
 * path drew the label, so the callback object is the only thing Mixin adds to a
 * label vanilla draws.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNametagMixin {

    @Shadow @Final protected EntityRenderDispatcher dispatcher;

    @Shadow
    public abstract TextRenderer getFontRenderer();

    @Unique
    private net.fathom.optimizations.fabric.gfx.GlStateAudit.Scope fathom$nametagAudit;

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
            at = @At("HEAD"), cancellable = true)
    private void fathom$fastNametag(Entity entity, String text, double x, double y, double z,
            int maxDistance, CallbackInfo callback) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(net.fathom.optimizations.core.bench.view.ViewSection.NAMETAG);
        }
        fathom$nametagAudit = net.fathom.optimizations.fabric.gfx.GlStateAudit.beginRequested(
                net.fathom.optimizations.fabric.gfx.GlStateAudit.NAMETAG, PerfLayer.fastNametags);
        NametagRenderer.Result result = PerfLayer.fastNametags
                ? NametagRenderer.render(dispatcher, getFontRenderer(), entity, text, x, y, z, maxDistance)
                : NametagRenderer.Result.FALLBACK;
        net.fathom.optimizations.fabric.gfx.GlStateAudit.afterFathom(
                fathom$nametagAudit, result.mutated, result.path);
        if (result.handled) {
            callback.cancel();
            if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.NAMETAG);
            }
            net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                    net.fathom.optimizations.fabric.gfx.GlStateAudit.NAMETAG);
            fathom$nametagAudit = null;
        }
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
            at = @At("RETURN"))
    private void fathom$finishNametagAudit(Entity entity, String text, double x, double y, double z,
            int maxDistance, CallbackInfo callback) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.NAMETAG);
        }
        if (fathom$nametagAudit != null) {
            net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                    net.fathom.optimizations.fabric.gfx.GlStateAudit.NAMETAG);
            fathom$nametagAudit = null;
        }
    }
}
