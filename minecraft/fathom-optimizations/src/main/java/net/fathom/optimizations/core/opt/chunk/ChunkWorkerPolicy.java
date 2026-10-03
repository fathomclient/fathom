package net.fathom.optimizations.core.opt.chunk;

/**
 * How many chunk-build workers to run -- {@code render.chunk.rebuild-scheduling}.
 *
 * <p>Vanilla 1.8.9 constructs its chunk builder with exactly two worker
 * threads and five build buffers, whatever the machine (read from
 * {@code ChunkBuilder.<init>}). OptiFine and later Minecraft versions size the
 * pool to the CPU instead. This does the same for profiles without OptiFine:
 * half the hardware threads, never fewer than vanilla's two and never more than
 * five, so building chunks cannot crowd out the client thread and the
 * integrated server on a small machine or flood a large one.
 *
 * <p>Each worker holds a build buffer while it works, so the buffer pool grows
 * with it: vanilla's three spare buffers over its two workers, kept.
 */
public final class ChunkWorkerPolicy {

    public static final int VANILLA_WORKERS = 2;
    public static final int VANILLA_BUFFERS = 5;
    public static final int MAX_WORKERS = 5;

    private ChunkWorkerPolicy() {}

    public static int workers(int hardwareThreads) {
        return Math.max(VANILLA_WORKERS, Math.min(MAX_WORKERS, hardwareThreads / 2));
    }

    public static int buffers(int workers) {
        return workers + (VANILLA_BUFFERS - VANILLA_WORKERS);
    }
}
