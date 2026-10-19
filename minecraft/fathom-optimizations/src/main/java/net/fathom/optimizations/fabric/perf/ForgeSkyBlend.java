package net.fathom.optimizations.fabric.perf;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Forge's sky colour blend, answered from a cache that actually hits
 * ({@code render.forge.sky-blend-cache}).
 *
 * <h2>What Forge does</h2>
 *
 * <p>With Fancy graphics, Forge 1.8.9 tints the sky and fog by averaging the
 * sky colour of every biome column within a radius of the camera -- 26 blocks
 * at render distance 12, so 2,809 columns, each a new {@code BlockPos} and a
 * biome lookup -- and asks twice a frame (sky, then fog). It means to remember
 * the answer per block column, but its check compares the camera's Z with a
 * stored value it fills with the camera's Y ({@code ForgeHooksClient
 * .getSkyBlendColour}), so the answer is recomputed on almost every call.
 *
 * <h2>What this keeps</h2>
 *
 * <p>The same arithmetic, in the same order, over the same columns. Its inputs
 * are the world, the camera's block (Y included: a biome's temperature falls
 * with height), the blend radius, and which chunks are loaded with what biome
 * data; the cache is keyed on every one of them, the last through
 * {@link ChunkRevision}. A miss walks the columns with one reused position
 * instead of a new one each. {@code -Dfathom.verify.caches=true} lets Forge
 * compute every answer as well and counts any difference.
 */
public final class ForgeSkyBlend {

    /** Compare every answer with Forge's own (QA only). */
    public static final boolean VERIFY = Boolean.getBoolean("fathom.verify.caches");

    private static final BlockPos.Mutable CURSOR = new BlockPos.Mutable();

    private static World cachedWorld;
    private static int cachedX;
    private static int cachedY;
    private static int cachedZ;
    private static int cachedDistance = -1;
    private static long cachedRevision = -1L;
    private static int cachedColour;

    private static java.lang.reflect.Field blendRanges;
    private static boolean blendRangesLooked;

    private static long hits;
    private static long misses;
    private static long checks;
    private static long mismatches;

    private ForgeSkyBlend() {}

    /** The blended colour, as Forge would compute it for this world and camera block. */
    public static int colour(World world, BlockPos center) {
        int distance = distance();
        long revision = ChunkRevision.value();
        // Only once both chunk hooks are known to run: without them a change in
        // loaded chunks could not invalidate the cache, so compute every time.
        boolean cacheable = ChunkRevision.trusted();
        if (cacheable && world == cachedWorld && revision == cachedRevision && distance == cachedDistance
                && center.getX() == cachedX && center.getY() == cachedY && center.getZ() == cachedZ) {
            hits++;
            net.fathom.optimizations.core.bench.view.ViewProbe.engaged(
                    net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_SKY_BLEND);
            return cachedColour;
        }
        misses++;
        int colour = compute(world, center, distance);
        if (cacheable) {
            cachedWorld = world;
            cachedX = center.getX();
            cachedY = center.getY();
            cachedZ = center.getZ();
            cachedDistance = distance;
            cachedRevision = revision;
            cachedColour = colour;
        }
        return colour;
    }

    /** QA: Forge computed {@code forge} itself; count whether this cache agrees. */
    public static void verify(World world, BlockPos center, int forge) {
        int ours = colour(world, center);
        checks++;
        if (ours != forge) {
            mismatches++;
            if (mismatches <= 5) {
                System.out.println("[Fathom] sky blend MISMATCH at " + center + ": cache " + Integer.toHexString(ours)
                        + ", Forge " + Integer.toHexString(forge));
            }
        }
        if ((checks & 4095L) == 0L) {
            System.out.println("[Fathom] sky blend verify: " + checks + " checked, " + mismatches + " mismatched ("
                    + hits + " hits, " + misses + " misses)");
        }
    }

    /** Forward to a new world: nothing from the last one may answer for it. */
    public static void release() {
        if (VERIFY && checks > 0L) {
            System.out.println("[Fathom] sky blend verify (world change): " + checks + " checked, " + mismatches
                    + " mismatched (" + hits + " hits, " + misses + " misses)");
        }
        cachedWorld = null;
        cachedRevision = -1L;
    }

    /** Forge's loop, with one reused position. */
    private static int compute(World world, BlockPos center, int distance) {
        int r = 0;
        int g = 0;
        int b = 0;
        int divider = 0;
        int y = center.getY();
        for (int x = -distance; x <= distance; ++x) {
            for (int z = -distance; z <= distance; ++z) {
                BlockPos pos = CURSOR.setPosition(center.getX() + x, y, center.getZ() + z);
                Biome biome = world.getBiome(pos);
                int colour = biome.getSkyColor(biome.getTemperature(pos));
                r += (colour & 0xFF0000) >> 16;
                g += (colour & 0x00FF00) >> 8;
                b += colour & 0x0000FF;
                divider++;
            }
        }
        return (r / divider & 255) << 16 | (g / divider & 255) << 8 | (b / divider & 255);
    }

    /** Forge's radius: its table by render distance, with Fancy graphics; none otherwise. */
    private static int distance() {
        GameOptions options = MinecraftClient.getInstance().options;
        int[] ranges = ranges();
        if (options.fancyGraphics && ranges != null && options.viewDistance >= 0
                && options.viewDistance < ranges.length) {
            return ranges[options.viewDistance];
        }
        return 0;
    }

    /** Forge's {@code ForgeModContainer.blendRanges}, read each call: its config screen can replace it. */
    private static int[] ranges() {
        if (!blendRangesLooked) {
            blendRangesLooked = true;
            try {
                blendRanges = Class.forName("net.minecraftforge.common.ForgeModContainer").getField("blendRanges");
            } catch (Throwable absent) {
                blendRanges = null;
            }
        }
        try {
            return blendRanges == null ? null : (int[]) blendRanges.get(null);
        } catch (Throwable unreadable) {
            return null;
        }
    }
}
