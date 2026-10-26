package net.fathom.optimizations.fabric.visual;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ParticleType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Particles' "always crit" and "always sharpness": during one attack, notes
 * which particles vanilla made, and afterwards adds the ones it did not.
 */
public final class HitParticles {

    private static boolean attacking;
    private static boolean crit;
    private static boolean magic;

    private HitParticles() {}

    public static void begin() {
        attacking = true;
        crit = false;
        magic = false;
    }

    public static void emitted(boolean enchanted) {
        if (enchanted) {
            magic = true;
        } else {
            crit = true;
        }
    }

    public static void end(PlayerEntity player, Entity target) {
        if (!attacking) {
            return;
        }
        attacking = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (target == null || client.particleManager == null || player != client.player) {
            return;
        }
        if (!crit && WorldEffects.alwaysHitParticles(false)) {
            emit(client, target, ParticleType.CRIT, WorldEffects.hitParticles(false));
        }
        if (!magic && WorldEffects.alwaysHitParticles(true)) {
            emit(client, target, ParticleType.CRIT_MAGIC, WorldEffects.hitParticles(true));
        }
    }

    private static void emit(MinecraftClient client, Entity target, ParticleType type, int times) {
        for (int index = 0; index < Math.max(1, times); index++) {
            client.particleManager.addEmitter(target, type);
        }
    }
}
