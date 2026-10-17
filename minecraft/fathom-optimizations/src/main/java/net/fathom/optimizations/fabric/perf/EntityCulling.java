package net.fathom.optimizations.fabric.perf;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EndCrystalEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningBoltEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.FireworkRocketEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.thrown.ThrowableEntity;
import net.minecraft.entity.thrown.EyeOfEnderEntity;

import net.fathom.optimizations.core.opt.perf.EntityDistances;
import net.fathom.optimizations.core.opt.perf.EntityKind;
import net.fathom.optimizations.core.opt.perf.PerfCounters;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/**
 * The entity half of the distance limits.
 *
 * <h2>Where it runs, and what it does not repeat</h2>
 *
 * <p>After {@code EntityRenderDispatcher.shouldRender} — the method OptiFine's
 * patched {@code renderEntities} asks about every entity in every visible chunk
 * — and only when that answered yes. So vanilla's own range test, its frustum
 * test and OptiFine's changes to either have all run and agreed first; this
 * adds nothing to an entity they rejected and never draws one they did not.
 * There is no second frustum test here: the dispatcher's is the frustum test,
 * and the entities it skips (fishing lines, the dragon) are the ones that draw
 * outside their bounds on purpose.
 *
 * <h2>What is never rejected</h2>
 *
 * <ul>
 *   <li>the camera entity, what it is riding and what rides it;
 *   <li>{@link EntityKind#NEVER}: bosses, lightning, end crystals;
 *   <li>anything showing its name, inside vanilla's 64-block nametag range;
 *   <li>anything inside its kind's floor, which the presets cannot go under.
 * </ul>
 *
 * <p>The one rejection that is not about distance is an armor stand that is
 * invisible, holds nothing, wears nothing, has no name and is not burning. Its
 * renderer runs its whole setup and draws no pixel, so skipping it is
 * output-identical by construction.
 *
 * <h2>Cost</h2>
 *
 * <p>A {@link ClassValue} lookup, one squared-distance compare and a couple of
 * field reads. The benchmark times one call in eight and scales, so a measured
 * run pays for eight {@code nanoTime} calls per 64 entities, not 128.
 */
public final class EntityCulling {

    private EntityCulling() {}

    private static final double NAMETAG_SQUARED =
            (double) EntityDistances.NAMETAG_BLOCKS * EntityDistances.NAMETAG_BLOCKS;
    private static final double PLAYER_FLOOR_SQUARED =
            (double) EntityDistances.floor(EntityKind.PLAYER) * EntityDistances.floor(EntityKind.PLAYER);

    private static final ClassValue<EntityKind> KINDS = new ClassValue<EntityKind>() {
        @Override
        protected EntityKind computeValue(Class<?> type) {
            return classify(type);
        }
    };

    private static int sample;

    /** The kind an entity class is limited as. Public for the statistics readout and tests. */
    public static EntityKind kindOf(Entity entity) {
        return KINDS.get(entity.getClass());
    }

    static EntityKind classify(Class<?> type) {
        if (EnderDragonEntity.class.isAssignableFrom(type)
                || WitherEntity.class.isAssignableFrom(type)
                || LightningBoltEntity.class.isAssignableFrom(type)
                || EndCrystalEntity.class.isAssignableFrom(type)) {
            return EntityKind.NEVER;
        }
        if (PlayerEntity.class.isAssignableFrom(type)) {
            return EntityKind.PLAYER;
        }
        if (AbstractArrowEntity.class.isAssignableFrom(type)
                || ThrowableEntity.class.isAssignableFrom(type)
                || ExplosiveProjectileEntity.class.isAssignableFrom(type)
                || FishingBobberEntity.class.isAssignableFrom(type)
                || EyeOfEnderEntity.class.isAssignableFrom(type)
                || FireworkRocketEntity.class.isAssignableFrom(type)
                || TntEntity.class.isAssignableFrom(type)
                || FallingBlockEntity.class.isAssignableFrom(type)) {
            return EntityKind.PROJECTILE;
        }
        if (ArmorStandEntity.class.isAssignableFrom(type)) {
            return EntityKind.ARMOR_STAND;
        }
        if (ItemEntity.class.isAssignableFrom(type)) {
            return EntityKind.ITEM;
        }
        if (Monster.class.isAssignableFrom(type)) {
            return EntityKind.HOSTILE;
        }
        if (MobEntity.class.isAssignableFrom(type)) {
            return EntityKind.PASSIVE;
        }
        return EntityKind.MISC;
    }

    /**
     * Whether an entity the dispatcher has already agreed to draw may still be
     * drawn. {@code cameraX/Y/Z} are the dispatcher's own camera coordinates.
     */
    public static boolean allow(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!PerfLayer.entitiesLimited) {
            return true;
        }
        if (BenchmarkState.ACTIVE && (++sample & 7) == 0) {
            long started = System.nanoTime();
            boolean allowed = decide(entity, cameraX, cameraY, cameraZ);
            PerfLayer.COUNTERS.cullNanos += (System.nanoTime() - started) * 8L;
            return allowed;
        }
        return decide(entity, cameraX, cameraY, cameraZ);
    }

    private static boolean decide(Entity entity, double cameraX, double cameraY, double cameraZ) {
        PerfCounters counters = PerfLayer.COUNTERS;
        counters.entitiesConsidered++;
        EntityKind kind = KINDS.get(entity.getClass());
        if (kind == EntityKind.NEVER) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = client.getCameraEntity();
        if (entity == camera || entity.rider == camera || entity.vehicle == camera) {
            return true;
        }
        double dx = entity.x - cameraX;
        double dy = entity.y - cameraY;
        double dz = entity.z - cameraZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= PerfLayer.entityLimitsSquared[kind.ordinal()]) {
            // A spectator sees invisible entities, translucent; for them an
            // invisible stand is not "nothing".
            if (kind == EntityKind.ARMOR_STAND && client.player != null && !client.player.isSpectator()
                    && drawsNothing((ArmorStandEntity) entity)) {
                return reject(counters, kind, distanceSquared);
            }
            return true;
        }
        // Past the limit. A name that is showing keeps the entity inside
        // vanilla's nametag range, whatever the limit.
        if (distanceSquared <= NAMETAG_SQUARED && entity.hasCustomName() && entity.isCustomNameVisible()) {
            return true;
        }
        return reject(counters, kind, distanceSquared);
    }

    private static boolean reject(PerfCounters counters, EntityKind kind, double distanceSquared) {
        counters.entitiesCulled++;
        counters.culledByKind[kind.ordinal()]++;
        if (kind == EntityKind.PLAYER && distanceSquared < PLAYER_FLOOR_SQUARED) {
            counters.nearPlayersCulled++;
        }
        return false;
    }

    /**
     * An armor stand whose renderer draws no pixel: invisible (so no body and
     * no shadow), nothing in any slot (so no layers), no name (so no label, not
     * even when looked at) and not burning (so no fire).
     */
    private static boolean drawsNothing(ArmorStandEntity stand) {
        if (stand.getStackInHand() != null) {
            return false;
        }
        for (int slot = 0; slot < 4; slot++) {
            if (stand.getArmorSlot(slot) != null) {
                return false;
            }
        }
        return stand.isInvisible() && !stand.hasCustomName() && !stand.doesRenderOnFire();
    }
}
