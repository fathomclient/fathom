package net.fathom.optimizations.fabric.perf;

import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;

import net.fathom.optimizations.core.opt.perf.TileDistances;

/**
 * The tile entity half of the distance limits.
 *
 * <p>Applied to the value {@code BlockEntityRenderDispatcher} compares the
 * distance against — {@code getSquaredRenderDistance()} — so vanilla (and
 * OptiFine, which keeps that comparison) still does the comparison, once, and
 * the limit can only make the answer "not this frame" sooner. The tile entity
 * itself, its data and its ticking are not touched; only its renderer is
 * skipped, and only while it is far away.
 */
public final class TileCulling {

    private TileCulling() {}

    private static final ClassValue<TileDistances.Kind> KINDS = new ClassValue<TileDistances.Kind>() {
        @Override
        protected TileDistances.Kind computeValue(Class<?> type) {
            return classify(type);
        }
    };

    static TileDistances.Kind classify(Class<?> type) {
        if (BeaconBlockEntity.class.isAssignableFrom(type)
                || EndPortalBlockEntity.class.isAssignableFrom(type)
                || PistonBlockEntity.class.isAssignableFrom(type)) {
            return TileDistances.Kind.NEVER;
        }
        if (EnderChestBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.ENDER_CHEST;
        if (ChestBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.CHEST;
        if (SignBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.SIGN;
        if (SkullBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.SKULL;
        if (BannerBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.BANNER;
        if (EnchantingTableBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.ENCHANTING_TABLE;
        if (MobSpawnerBlockEntity.class.isAssignableFrom(type)) return TileDistances.Kind.SPAWNER;
        return TileDistances.Kind.OTHER;
    }

    /**
     * The distance vanilla computed for the tile entity being tested, captured
     * from the expression just before the one {@link #limitSquared} modifies,
     * so the count below does not repeat the arithmetic. Render thread only.
     */
    public static double lastDistanceSquared;

    public static TileDistances.Kind kindOf(BlockEntity entity) {
        return KINDS.get(entity.getClass());
    }

    /**
     * The render distance, squared, to compare against: vanilla's, or the
     * limit if that is closer. Counts what it decides, which it can do here
     * because the caller's comparison is exactly "is it inside this".
     */
    public static double limitSquared(BlockEntity entity, double vanillaSquared) {
        PerfLayer.COUNTERS.tilesConsidered++;
        if (!PerfLayer.tilesLimited) {
            return vanillaSquared;
        }
        double limit = PerfLayer.tileLimitsSquared[KINDS.get(entity.getClass()).ordinal()];
        if (limit >= vanillaSquared) {
            return vanillaSquared;
        }
        double distance = lastDistanceSquared;
        if (distance >= limit && distance < vanillaSquared) {
            PerfLayer.COUNTERS.tilesCulled++;
        }
        return limit;
    }
}
