package net.fathom.optimizations.core.opt.perf;

/**
 * Tile entity render distances.
 *
 * <p>Vanilla draws every special-rendered tile entity out to 64 blocks, and
 * beacons out to 256. A limit here is applied to that same comparison — the
 * renderer's own {@code distance < maxRenderDistance} test — so it can only
 * shorten vanilla's range, and it changes nothing about the tile entity except
 * whether its renderer runs this frame. The block, its inventory, its ticking
 * and its block-model parts are untouched.
 *
 * <p>Beacons, end portals and moving pistons are {@link Kind#NEVER}: a beam is
 * visible hundreds of blocks from its base, a portal surface is gameplay, and a
 * moving piston is the block itself for as long as it moves.
 *
 * <p>OptiFine already has a sign-<em>text</em> distance and an item-frame item
 * distance. Neither is duplicated: this limits the whole renderer, which is a
 * different and coarser trade, so signs default to vanilla's range under
 * Conservative.
 */
public final class TileDistances {

    public enum Kind {
        CHEST("Chests"),
        ENDER_CHEST("Ender chests"),
        SIGN("Signs"),
        SKULL("Skulls"),
        BANNER("Banners"),
        ENCHANTING_TABLE("Enchanting tables"),
        SPAWNER("Mob spawners"),
        OTHER("Other tile entities"),
        NEVER("Never limited");

        public static final int COUNT = values().length;

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final double UNLIMITED = Double.POSITIVE_INFINITY;

    /** Vanilla's own range for every limitable kind. */
    public static final int VANILLA_BLOCKS = 64;

    /** No tile entity limit may come closer than this. */
    public static final int FLOOR_BLOCKS = 16;

    //                                          C   B   A
    private static final int[][] TABLE = {
        /* CHEST            */ {48, 32, 24},
        /* ENDER_CHEST      */ {48, 32, 24},
        /* SIGN             */ {64, 48, 32},
        /* SKULL            */ {48, 32, 24},
        /* BANNER           */ {64, 48, 32},
        /* ENCHANTING_TABLE */ {32, 24, 16},
        /* SPAWNER          */ {32, 24, 16},
        /* OTHER            */ {64, 48, 32},
    };

    private TileDistances() {}

    /** The preset's distance in blocks, or -1 for no limit. */
    public static int blocks(Kind kind, PerfPreset preset, int customBlocks) {
        if (kind == Kind.NEVER || preset == PerfPreset.OFF) {
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
            case CUSTOM:
                return clamp(customBlocks);
            default:
                return -1;
        }
    }

    public static int clamp(int blocks) {
        return Math.max(FLOOR_BLOCKS, Math.min(VANILLA_BLOCKS, blocks));
    }

    /** Fill {@code limitsSquared}, indexed by {@link Kind#ordinal()}. */
    public static void fill(double[] limitsSquared, PerfPreset preset, int customBlocks) {
        if (limitsSquared.length < Kind.COUNT) {
            throw new IllegalArgumentException("need one slot per kind");
        }
        for (Kind kind : Kind.values()) {
            int blocks = blocks(kind, preset, customBlocks);
            limitsSquared[kind.ordinal()] = blocks < 0 ? UNLIMITED : (double) blocks * blocks;
        }
    }
}
