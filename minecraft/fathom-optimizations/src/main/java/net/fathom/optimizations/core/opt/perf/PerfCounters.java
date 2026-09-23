package net.fathom.optimizations.core.opt.perf;

/**
 * What the performance layer did, counted.
 *
 * <p>Plain long fields, written from the render thread by the hooks and read
 * by the statistics overlay and the benchmark. A field increment is the whole
 * cost, so the counters stay on in every launch: a statistics readout that has
 * to be switched on before it knows anything is not one anybody consults.
 */
public final class PerfCounters {

    public long frames;

    public long entitiesConsidered;
    public long entitiesCulled;
    public final long[] culledByKind = new long[EntityKind.COUNT];
    /**
     * Players within the player floor that the layer refused to draw. Zero by
     * construction; counted so the benchmark can prove it rather than assert it.
     */
    public long nearPlayersCulled;
    /** Sampled rejection-test time, scaled to an estimate of the total. */
    public long cullNanos;

    public long tilesConsidered;
    public long tilesCulled;

    public long nametagsFast;
    public long nametagsVanilla;
    public long nametagCompiles;
    public long nametagEvictions;

    public long hudRedraws;
    public long hudComposites;
    public long hudDirect;

    public void reset() {
        frames = 0L;
        entitiesConsidered = 0L;
        entitiesCulled = 0L;
        java.util.Arrays.fill(culledByKind, 0L);
        nearPlayersCulled = 0L;
        cullNanos = 0L;
        tilesConsidered = 0L;
        tilesCulled = 0L;
        nametagsFast = 0L;
        nametagsVanilla = 0L;
        nametagCompiles = 0L;
        nametagEvictions = 0L;
        hudRedraws = 0L;
        hudComposites = 0L;
        hudDirect = 0L;
    }

    public void copyInto(PerfCounters out) {
        out.frames = frames;
        out.entitiesConsidered = entitiesConsidered;
        out.entitiesCulled = entitiesCulled;
        System.arraycopy(culledByKind, 0, out.culledByKind, 0, culledByKind.length);
        out.nearPlayersCulled = nearPlayersCulled;
        out.cullNanos = cullNanos;
        out.tilesConsidered = tilesConsidered;
        out.tilesCulled = tilesCulled;
        out.nametagsFast = nametagsFast;
        out.nametagsVanilla = nametagsVanilla;
        out.nametagCompiles = nametagCompiles;
        out.nametagEvictions = nametagEvictions;
        out.hudRedraws = hudRedraws;
        out.hudComposites = hudComposites;
        out.hudDirect = hudDirect;
    }

    public PerfCounters copy() {
        PerfCounters out = new PerfCounters();
        copyInto(out);
        return out;
    }

    /** {@code value} per counted frame, or 0 before any frame. */
    public double perFrame(long value) {
        return frames <= 0L ? 0d : value / (double) frames;
    }
}
