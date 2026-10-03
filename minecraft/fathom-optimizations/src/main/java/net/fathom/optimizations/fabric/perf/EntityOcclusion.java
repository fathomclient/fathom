package net.fathom.optimizations.fabric.perf;

import net.minecraft.block.BaseLeavesBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EndCrystalEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LightningBoltEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.GiantEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;
import net.fathom.optimizations.core.opt.entity.EntityFacts;
import net.fathom.optimizations.core.opt.entity.EntityVisibilityPolicy;
import net.fathom.optimizations.core.opt.entity.OcclusionTest;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Entity occlusion culling, in the game -- {@code render.entity.occlusion-culling}.
 *
 * <p>Asked about an entity only after vanilla's range and frustum test and
 * Fathom's distance limit have all agreed to draw it. The refusal rules in
 * {@link EntityVisibilityPolicy} run first, because they are cheap and most
 * entities that matter -- players, anything named -- are exempt; only what is
 * left is given the sight-line test of {@link OcclusionTest}, against the
 * blocks the client has loaded.
 *
 * <h2>When it does nothing at all</h2>
 *
 * <ul>
 *   <li>an OptiFine shader pack is loaded (its shadow pass needs geometry the
 *       camera cannot see);
 *   <li>third person, sleeping, or spectator mode -- the camera is not at the
 *       eye this computes, or may be inside blocks;
 *   <li>the eye is inside an opaque block;
 *   <li>for {@link #SETTLE_NANOS} after a world is joined or the renderers
 *       reload, while terrain is still being built.
 * </ul>
 *
 * <h2>Blocks the screen does not show yet</h2>
 *
 * <p>The test reads the world, the screen shows the built chunk meshes, and the
 * two disagree until a changed chunk is rebuilt. A wall placed this frame is in
 * the world but not on screen. So a chunk column that has had any block or
 * render-region update in the last {@link #UNSTABLE_NANOS} counts as having no
 * occluding blocks: the answer there is "draw it".
 */
public final class EntityOcclusion {

    private EntityOcclusion() {}

    /** After a world join or renderer reload, nothing is culled for this long. */
    static final long SETTLE_NANOS = 10_000_000_000L;

    /** A chunk column that changed is not trusted to occlude for this long. */
    static final long UNSTABLE_NANOS = 2_000_000_000L;

    private static final OcclusionTest TEST = new OcclusionTest();
    private static final EntityVisibilityPolicy POLICY = new EntityVisibilityPolicy();
    private static final Facts FACTS = new Facts();
    private static final WorldOpacity OPACITY = new WorldOpacity();
    private static final UnstableColumns UNSTABLE = new UnstableColumns();

    /** Whether this frame's entity pass may cull. Set at its start. */
    private static boolean active;
    private static Entity camera;
    private static float tickDelta;
    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static long settledAt;

    // This frame's counts, handed to the view profiler at the end of the pass.
    private static long tested;
    private static long hidden;

    /** Start of {@code renderEntities}: decide whether this pass may cull, and from where. */
    public static void beginPass(Entity cameraEntity, float delta) {
        active = false;
        tested = 0L;
        hidden = 0L;
        TEST.resetCounters();
        TEST.resetBlockers();
        if (!Runtime.entityOcclusion || PerfLayer.shadersActive || cameraEntity == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.options.perspective != 0) {
            return;
        }
        if (client.player != null && client.player.isSpectator()) {
            return;
        }
        if (cameraEntity instanceof LivingEntity && ((LivingEntity) cameraEntity).isSleeping()) {
            return;
        }
        long now = System.nanoTime();
        if (now - settledAt < SETTLE_NANOS) {
            return;
        }
        camera = cameraEntity;
        tickDelta = delta;
        eyeX = cameraEntity.prevTickX + (cameraEntity.x - cameraEntity.prevTickX) * delta;
        eyeY = cameraEntity.prevTickY + (cameraEntity.y - cameraEntity.prevTickY) * delta
                + cameraEntity.getEyeHeight();
        eyeZ = cameraEntity.prevTickZ + (cameraEntity.z - cameraEntity.prevTickZ) * delta;
        OPACITY.begin(world, now);
        active = true;
    }

    /** End of {@code renderEntities}: report this pass to the view profiler, if one is running. */
    public static void endPass() {
        if (ViewProbe.REQUESTED && tested != 0L) {
            ViewProbe.count(ViewCounter.OCCLUSION_TESTED, tested);
            ViewProbe.count(ViewCounter.OCCLUSION_HIDDEN, hidden);
            ViewProbe.count(ViewCounter.OCCLUSION_LINES, TEST.lines());
        }
        active = false;
        camera = null;
        OPACITY.end();
    }

    /** Whether an entity the dispatcher has agreed to draw may still be drawn. */
    public static boolean allow(Entity entity) {
        if (!active) {
            return true;
        }
        FACTS.of(entity);
        if (POLICY.exemption(FACTS) != null) {
            return true;
        }
        if (ViewProbe.REQUESTED) {
            ViewProbe.enter(ViewSection.FATHOM_OCCLUSION);
        }
        boolean occluded = test(entity);
        if (ViewProbe.REQUESTED) {
            ViewProbe.exit(ViewSection.FATHOM_OCCLUSION);
        }
        tested++;
        if (!occluded) {
            return true;
        }
        FACTS.occluded = true;
        // The policy has the last word, over every rule, not just the ones
        // asked above.
        if (POLICY.decide(FACTS).mustRender()) {
            return true;
        }
        hidden++;
        return false;
    }

    private static boolean test(Entity entity) {
        // Where the renderer will draw it this frame, not where it is.
        double offsetX = (entity.prevTickX - entity.x) * (1.0 - tickDelta);
        double offsetY = (entity.prevTickY - entity.y) * (1.0 - tickDelta);
        double offsetZ = (entity.prevTickZ - entity.z) * (1.0 - tickDelta);
        Box box = entity.getBoundingBox();
        double side = OcclusionTest.padSide(entity.width);
        double up = OcclusionTest.padUp(entity.height);
        return TEST.hidden(eyeX, eyeY, eyeZ,
                box.minX + offsetX - side, box.minY + offsetY - OcclusionTest.PAD_DOWN, box.minZ + offsetZ - side,
                box.maxX + offsetX + side, box.maxY + offsetY + up, box.maxZ + offsetZ + side,
                OPACITY);
    }

    /** A world join or renderer reload: terrain is about to be rebuilt from nothing. */
    public static void unsettle() {
        settledAt = System.nanoTime();
        UNSTABLE.clear();
    }

    /** Blocks in this range changed or are being re-rendered; see the class comment. */
    public static void changed(int minX, int minZ, int maxX, int maxZ) {
        UNSTABLE.mark(minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4, System.nanoTime() + UNSTABLE_NANOS);
    }

    // -----------------------------------------------------------------------

    /** What the policy may know, read from the entity once per decision. Reused. */
    private static final class Facts implements EntityFacts {
        private static final ClassValue<String> TYPE_IDS = new ClassValue<String>() {
            @Override
            protected String computeValue(Class<?> type) {
                // Only the types on the policy's never-cull list need a name.
                if (EnderDragonEntity.class.isAssignableFrom(type)) {
                    return "minecraft:ender_dragon";
                }
                if (WitherEntity.class.isAssignableFrom(type)) {
                    return "minecraft:wither";
                }
                if (GuardianEntity.class.isAssignableFrom(type)) {
                    return "minecraft:guardian";
                }
                if (FishingBobberEntity.class.isAssignableFrom(type)) {
                    return "minecraft:fishing_bobber";
                }
                return "";
            }
        };

        /**
         * Kinds whose drawing reaches past their bounds by more than the
         * padding: bolts and crystal beams, a ghast's and a squid's
         * tentacles, a giant, and a wither skull (vanilla draws its label
         * even when it is culled).
         */
        private static final ClassValue<Boolean> BEYOND_BOUNDS = new ClassValue<Boolean>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                return Boolean.valueOf(LightningBoltEntity.class.isAssignableFrom(type)
                        || EndCrystalEntity.class.isAssignableFrom(type)
                        || GhastEntity.class.isAssignableFrom(type)
                        || SquidEntity.class.isAssignableFrom(type)
                        || GiantEntity.class.isAssignableFrom(type)
                        || WitherSkullEntity.class.isAssignableFrom(type));
            }
        };

        private Entity entity;
        boolean occluded;

        void of(Entity entity) {
            this.entity = entity;
            this.occluded = false;
        }

        @Override
        public String typeId() {
            return TYPE_IDS.get(entity.getClass());
        }

        @Override
        public boolean isViewer() {
            return entity == camera;
        }

        @Override
        public boolean isAttachedToViewer() {
            return entity.rider == camera || entity.vehicle == camera;
        }

        @Override
        public boolean isPlayer() {
            return entity instanceof PlayerEntity;
        }

        @Override
        public boolean hasVisibleNameTag() {
            // Any name: some show only when looked at, and which ones is the
            // renderer's business. Erring towards drawing.
            return entity.hasCustomName();
        }

        @Override
        public boolean hasVisualEffects() {
            return entity.doesRenderOnFire();
        }

        @Override
        public boolean rendersBeyondItsBounds() {
            // A lead is drawn by the animal on it, out to the fence or the player.
            return BEYOND_BOUNDS.get(entity.getClass()).booleanValue()
                    || (entity instanceof MobEntity && ((MobEntity) entity).isLeashed());
        }

        @Override
        public double sizeBlocks() {
            return Math.max(entity.width, entity.height);
        }

        @Override
        public double distanceBlocks() {
            double dx = entity.x - eyeX;
            double dy = entity.y - eyeY;
            double dz = entity.z - eyeZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public boolean occlusionResultIsFresh() {
            // Computed this frame, from this frame's eye, or not at all.
            return true;
        }

        @Override
        public boolean occludedByOpaqueGeometry() {
            return occluded;
        }
    }

    /**
     * The client's loaded blocks, as the sight-line test sees them: a full,
     * opaque, ordinarily modelled cube occludes; anything else -- glass, slabs,
     * leaves at any graphics setting, barriers, unloaded chunks, columns that
     * changed recently -- does not.
     */
    static final class WorldOpacity implements OcclusionTest.Opacity {
        /** Per block id: 0 not yet asked, 1 occludes, 2 does not. */
        private static final byte[] OCCLUDER = new byte[4096];

        // A per-frame memo of answers in a cube around the eye. Sight lines
        // to neighbouring sample points cross the same blocks over and over;
        // asking the world each time costs a chunk lookup per block. Each byte
        // is (frame stamp << 1 | answer); a stamp from an earlier frame means
        // "not asked yet", so nothing is cleared between frames.
        private static final int SIDE = 128;
        private static final int HALF = SIDE / 2;
        private final byte[] memo = new byte[SIDE * SIDE * SIDE];
        private int stamp;
        private int originX;
        private int originY;
        private int originZ;

        private ClientWorld world;
        private long now;
        // Two chunks remembered: a sight line is walked from both ends at
        // once, and the ends are usually in different chunks.
        private Chunk chunkA;
        private int chunkAX;
        private int chunkAZ;
        private boolean trustedA;
        private Chunk chunkB;
        private int chunkBX;
        private int chunkBZ;
        private boolean trustedB;

        void begin(ClientWorld world, long now) {
            this.world = world;
            this.now = now;
            this.chunkA = null;
            this.chunkB = null;
            if (++stamp > 127) {
                stamp = 1;
                java.util.Arrays.fill(memo, (byte) 0);
            }
            originX = OcclusionTest.floorOf(eyeX) - HALF;
            originY = OcclusionTest.floorOf(eyeY) - HALF;
            originZ = OcclusionTest.floorOf(eyeZ) - HALF;
        }

        void end() {
            world = null;
            chunkA = null;
            chunkB = null;
        }

        @Override
        public boolean occludes(int x, int y, int z) {
            int mx = x - originX;
            int my = y - originY;
            int mz = z - originZ;
            if ((mx | my | mz) >= 0 && mx < SIDE && my < SIDE && mz < SIDE) {
                int index = (mx * SIDE + my) * SIDE + mz;
                int cell = memo[index];
                if ((cell >>> 1) == stamp) {
                    return (cell & 1) != 0;
                }
                boolean answer = lookup(x, y, z);
                memo[index] = (byte) (stamp << 1 | (answer ? 1 : 0));
                return answer;
            }
            return lookup(x, y, z);
        }

        private boolean lookup(int x, int y, int z) {
            if (y < 0 || y > 255) {
                return false;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            Chunk chunk;
            if (chunkA != null && cx == chunkAX && cz == chunkAZ) {
                if (!trustedA) {
                    return false;
                }
                chunk = chunkA;
            } else if (chunkB != null && cx == chunkBX && cz == chunkBZ) {
                if (!trustedB) {
                    return false;
                }
                chunk = chunkB;
            } else {
                chunk = world.getChunk(cx, cz);
                boolean trusted = !chunk.isEmpty() && !UNSTABLE.contains(cx, cz, now);
                // The older entry makes way.
                chunkB = chunkA;
                chunkBX = chunkAX;
                chunkBZ = chunkAZ;
                trustedB = trustedA;
                chunkA = chunk;
                chunkAX = cx;
                chunkAZ = cz;
                trustedA = trusted;
                if (!trusted) {
                    return false;
                }
            }
            ChunkSection section = chunk.getBlockStorage()[y >> 4];
            if (section == null) {
                return false;
            }
            int id = section.getBlockStates()[(y & 15) << 8 | (z & 15) << 4 | (x & 15)] >> 4;
            byte known = OCCLUDER[id];
            if (known == 0) {
                known = occluder(Block.getById(id)) ? (byte) 1 : (byte) 2;
                OCCLUDER[id] = known;
            }
            return known == 1;
        }

        private static boolean occluder(Block block) {
            // hasTransparency() is 1.8.9's isOpaqueCube in these mappings, and
            // renderAsNormalBlock() its isFullCube. Render type 3 is a block
            // model. Leaves are out whatever the graphics setting says.
            return block != null
                    && block.hasTransparency()
                    && block.renderAsNormalBlock()
                    && block.getBlockType() == 3
                    && block.getMaterial().isOpaque()
                    && !(block instanceof BaseLeavesBlock);
        }
    }

    /**
     * Chunk columns that changed recently, with when each may be trusted
     * again. Open addressing on primitive arrays: the test asks once per chunk
     * it walks into, and boxing a key there would be garbage per question.
     */
    static final class UnstableColumns {
        private static final int CAPACITY = 4096;
        private static final long EMPTY = Long.MIN_VALUE;

        private final long[] keys = new long[CAPACITY];
        private final long[] until = new long[CAPACITY];
        private int size;
        /** Latest expiry of anything stored; past it, the table is empty in effect. */
        private long latest;

        UnstableColumns() {
            java.util.Arrays.fill(keys, EMPTY);
        }

        void clear() {
            java.util.Arrays.fill(keys, EMPTY);
            size = 0;
            latest = 0L;
        }

        void mark(int minX, int minZ, int maxX, int maxZ, long expiry) {
            if (latest - System.nanoTime() < 0L) {
                clear();
            }
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    put(key(x, z), expiry);
                }
            }
            latest = expiry;
        }

        boolean contains(int x, int z, long now) {
            if (size == 0 || latest - now < 0L) {
                return false;
            }
            long key = key(x, z);
            for (int slot = slot(key), probes = 0; probes < CAPACITY; slot = (slot + 1) & (CAPACITY - 1), probes++) {
                long stored = keys[slot];
                if (stored == EMPTY) {
                    return false;
                }
                if (stored == key) {
                    return until[slot] - now > 0L;
                }
            }
            return false;
        }

        private void put(long key, long expiry) {
            if (size >= CAPACITY * 3 / 4) {
                // Too much changing to keep track of column by column: treat
                // everything as changed for the whole window instead.
                clear();
                settledAt = System.nanoTime() - SETTLE_NANOS + UNSTABLE_NANOS;
                return;
            }
            for (int slot = slot(key); ; slot = (slot + 1) & (CAPACITY - 1)) {
                long stored = keys[slot];
                if (stored == EMPTY) {
                    keys[slot] = key;
                    until[slot] = expiry;
                    size++;
                    return;
                }
                if (stored == key) {
                    until[slot] = expiry;
                    return;
                }
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        private static int slot(long key) {
            long mixed = key * 0x9E3779B97F4A7C15L;
            return (int) (mixed >>> 52) & (CAPACITY - 1);
        }
    }
}
