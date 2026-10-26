package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.particle.ParticleType;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

import net.fathom.optimizations.fabric.visual.WorldEffects;

/**
 * Particles' explosion setting. An explosion draws one large burst, then a
 * puff and a wisp of smoke for every block it breaks: a TNT blast through a
 * Bedwars base is hundreds of particles in one tick, each simulated and drawn
 * for a second or more. The burst is always kept; the per-block pair (the
 * third and fourth particle calls here) is thinned or dropped. Blocks, damage
 * and knockback are untouched.
 */
@Mixin(Explosion.class)
public abstract class ExplosionParticlesMixin {

    private static final String ADD_PARTICLE =
            "Lnet/minecraft/world/World;addParticle(Lnet/minecraft/client/particle/ParticleType;DDDDDD[I)V";

    @Redirect(method = "affectWorld(Z)V", require = 0, at = @At(value = "INVOKE", target = ADD_PARTICLE, ordinal = 2))
    private void fathom$blockPuff(World world, ParticleType type, double x, double y, double z, double vx, double vy,
            double vz, int[] data) {
        if (WorldEffects.keepExplosionParticle()) {
            world.addParticle(type, x, y, z, vx, vy, vz, data);
        }
    }

    @Redirect(method = "affectWorld(Z)V", require = 0, at = @At(value = "INVOKE", target = ADD_PARTICLE, ordinal = 3))
    private void fathom$blockSmoke(World world, ParticleType type, double x, double y, double z, double vx, double vy,
            double vz, int[] data) {
        if (WorldEffects.keepExplosionParticle()) {
            world.addParticle(type, x, y, z, vx, vy, vz, data);
        }
    }
}
