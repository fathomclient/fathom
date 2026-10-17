package net.fathom.optimizations.fabric.mixin.view;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: particle drawing (both passes) and particle ticking. */
@Mixin(ParticleManager.class)
public abstract class ViewParticleMixin {

    @Shadow
    private List<Particle>[][] particles;

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), require = 0)
    private void fathom$viewParticlesStart(Entity camera, float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.PARTICLES);
    }

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"), require = 0)
    private void fathom$viewParticlesEnd(Entity camera, float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.PARTICLES);
    }

    /** The lit-particle pass (vanilla's {@code renderLitParticles}). */
    @Inject(method = "method_1299(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), require = 0)
    private void fathom$viewLitStart(Entity camera, float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.PARTICLES);
    }

    @Inject(method = "method_1299(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"), require = 0)
    private void fathom$viewLitEnd(Entity camera, float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.PARTICLES);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewTickStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_PARTICLES);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewTickEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_PARTICLES);
        // Counted once a tick, after the tick has added and removed its share.
        int live = 0;
        if (particles != null) {
            for (List<Particle>[] layer : particles) {
                for (List<Particle> list : layer) {
                    live += list.size();
                }
            }
        }
        ViewProbe.particlesLive = live;
    }
}
