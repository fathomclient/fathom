package net.fathom.optimizations.core.opt.perf;

/**
 * Entity render distances, per {@link EntityKind} and {@link PerfPreset}.
 *
 * <h2>What vanilla already does, and why these numbers are where they are</h2>
 *
 * <p>1.8.9 limits every entity by {@code averageSideLength * 64 * weight}
 * blocks. For most entities that is already about 64 blocks, and a Fathom limit
 * above it changes nothing. The entities it lets through are the ones worth
 * limiting:
 *
 * <ul>
 *   <li>other players, weight 10: vanilla draws them out to the render distance;
 *   <li>arrows, weight 10: 320 blocks;
 *   <li>armor stands, four times the base: about 250 blocks. A lobby's holograms
 *       and NPC stands are drawn far beyond the 64 blocks their names are;
 *   <li>item frames: 1024 blocks;
 *   <li>large mobs and fireballs, by size.
 * </ul>
 *
 * <p>Dropped items are 16 blocks in vanilla, so the item row cannot make items
 * vanish any sooner than they already do; its floor is vanilla's own range.
 *
 * <h2>Floors</h2>
 *
 * <p>Every kind has a floor no preset or custom slider can go under, chosen so
 * a gameplay-relevant entity never disappears at a normal combat distance:
 * players 48 blocks, projectiles, TNT and falling blocks 64. The limit only
 * ever tightens vanilla's answer; it never draws anything vanilla would not.
 */
public final class EntityDistances {

    /** No limit. Stored squared, and infinity squared is still infinity. */
    public static final double UNLIMITED = Double.POSITIVE_INFINITY;

    /**
     * Vanilla's nametag range. An entity whose name is showing is never limited
     * inside it, whatever its kind's limit says: on a server whose holograms are
     * armor stands, the name is the entity.
     */
    public static final int NAMETAG_BLOCKS = 64;

    //                                             C    B    A   floor
    private static final int[][] TABLE = {
        /* PLAYER      */ {128, 96, 64, 48},
        /* HOSTILE     */ {96, 64, 48, 32},
        /* PASSIVE     */ {64, 48, 32, 24},
        /* ITEM        */ {32, 24, 16, 16},
        /* ARMOR_STAND */ {96, 64, 48, 32},
        /* PROJECTILE  */ {128, 96, 64, 64},
        /* MISC        */ {64, 48, 32, 24},
    };

    /** The largest a custom slider may go; beyond this a limit is pointless. */
    public static final int MAXIMUM_BLOCKS = 256;

    private EntityDistances() {}

    /** The preset's distance in blocks, or -1 when the kind is not limited. */
    public static int blocks(EntityKind kind, PerfPreset preset) {
        if (kind == EntityKind.NEVER || preset == PerfPreset.OFF) {
            return -1;
        }
        int[] row = TABLE[kind.ordinal()];
        switch (preset) {
            case CONSERVATIVE:
                return row[0];
            case BALANCED:
                return row[1];
            case AGGRESSIVE:
                return row[2];
            default:
                throw new IllegalArgumentException("custom distances come from the sliders");
        }
    }

    /** The closest any setting may bring this kind's limit, or -1 for never limited. */
    public static int floor(EntityKind kind) {
        return kind == EntityKind.NEVER ? -1 : TABLE[kind.ordinal()][3];
    }

    /** A custom slider value held inside the kind's floor and the maximum. */
    public static int clamp(EntityKind kind, int blocks) {
        int floor = floor(kind);
        if (floor < 0) {
            return -1;
        }
        return Math.max(floor, Math.min(MAXIMUM_BLOCKS, blocks));
    }

    /**
     * Fill {@code limitsSquared}, indexed by {@link EntityKind#ordinal()}, with
     * squared distances for the given preset.
     *
     * <p>The four sliders are only read for {@link PerfPreset#CUSTOM}: players;
     * hostile and passive mobs; dropped items; armor stands and everything
     * else. Projectiles follow the player slider, never under their own floor,
     * because an arrow is only dangerous because of who shot it.
     */
    public static void fill(double[] limitsSquared, PerfPreset preset,
            int playerBlocks, int mobBlocks, int itemBlocks, int miscBlocks) {
        if (limitsSquared.length < EntityKind.COUNT) {
            throw new IllegalArgumentException("need one slot per kind");
        }
        for (EntityKind kind : EntityKind.values()) {
            int blocks;
            if (preset == PerfPreset.CUSTOM) {
                switch (kind) {
                    case PLAYER:
                    case PROJECTILE:
                        blocks = clamp(kind, playerBlocks);
                        break;
                    case HOSTILE:
                    case PASSIVE:
                        blocks = clamp(kind, mobBlocks);
                        break;
                    case ITEM:
                        blocks = clamp(kind, itemBlocks);
                        break;
                    case ARMOR_STAND:
                    case MISC:
                        blocks = clamp(kind, miscBlocks);
                        break;
                    default:
                        blocks = -1;
                        break;
                }
            } else {
                blocks = blocks(kind, preset);
            }
            limitsSquared[kind.ordinal()] = blocks < 0 ? UNLIMITED : (double) blocks * blocks;
        }
    }
}
