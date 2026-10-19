package net.fathom.optimizations.fabric.perf;

/**
 * A count that changes whenever the client's set of loaded chunks, or a loaded
 * chunk's biome data, changes: a cache of anything computed from biomes keys on
 * it ({@link ForgeSkyBlend}).
 *
 * <p>Bumped on the client thread by the chunk hooks in
 * {@code fathom.forge.mixins.json}: a chunk loaded, a chunk unloaded, a chunk
 * filled from the server (which carries its biomes), a biome array replaced.
 * {@link #trusted()} is false until the load hook and the fill hook have each
 * been seen to run, so a hook that failed to apply turns a cache off rather than
 * making it stale.
 */
public final class ChunkRevision {

    private static long value;
    private static boolean loadSeen;
    private static boolean fillSeen;

    private ChunkRevision() {}

    public static long value() {
        return value;
    }

    public static boolean trusted() {
        return loadSeen && fillSeen;
    }

    /** A chunk was loaded or unloaded. */
    public static void chunkLoaded() {
        loadSeen = true;
        value++;
    }

    /** A chunk's contents, biomes included, were replaced. */
    public static void chunkFilled() {
        fillSeen = true;
        value++;
    }
}
