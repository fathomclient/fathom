package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import net.fathom.optimizations.fabric.visual.HitParticles;

/** Particles' "always" options: a hit that made none gets them anyway. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientInteractionParticlesMixin {

    private long fathom$attackHitch;

    @Inject(method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"))
    private void fathom$attackStart(PlayerEntity player, Entity target, CallbackInfo info) {
        fathom$attackHitch = net.fathom.optimizations.fabric.diag.Hitches.begin();
        HitParticles.begin();
    }

    @Inject(method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("RETURN"))
    private void fathom$attackEnd(PlayerEntity player, Entity target, CallbackInfo info) {
        HitParticles.end(player, target);
        net.fathom.optimizations.fabric.diag.Hitches.end(net.fathom.optimizations.fabric.diag.Hitches.Section.ATTACK, fathom$attackHitch);
    }
}
