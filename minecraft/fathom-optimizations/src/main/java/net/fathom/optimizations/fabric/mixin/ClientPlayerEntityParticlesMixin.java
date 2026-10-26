package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ParticleType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.fabric.visual.HitParticles;
import net.fathom.optimizations.fabric.visual.WorldEffects;

/**
 * Particles: the crit and sharpness particles your hits make, multiplied.
 * Purely visual: the hit itself is vanilla's.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityParticlesMixin {

    @Shadow
    protected MinecraftClient client;

    @Inject(method = "addCritParticles(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void fathom$crit(Entity target, CallbackInfo info) {
        fathom$emit(target, false, info);
    }

    @Inject(method = "addEnchantedHitParticles(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void fathom$sharpness(Entity target, CallbackInfo info) {
        fathom$emit(target, true, info);
    }

    @Unique
    private void fathom$emit(Entity target, boolean magic, CallbackInfo info) {
        HitParticles.emitted(magic);
        int times = WorldEffects.hitParticles(magic);
        if (times == 1) {
            return;
        }
        for (int index = 0; index < times; index++) {
            client.particleManager.addEmitter(target, magic ? ParticleType.CRIT_MAGIC : ParticleType.CRIT);
        }
        info.cancel();
    }
}
