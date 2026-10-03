package net.fathom.optimizations.core.bench.view;

/**
 * Things the view profiler counts per frame rather than times.
 *
 * <p>A section that got slower either did the same work more slowly or did more
 * of it. These are the "more of it" half: how many chunk sections each terrain
 * layer drew, how many the visibility search kept, how many were rebuilt and
 * uploaded. A ground-facing frame that is slower because it draws four times as
 * many sections is a different finding from one that draws the same sections
 * more slowly.
 */
public enum ViewCounter {
    SECTIONS_SOLID("sections drawn: solid"),
    SECTIONS_CUTOUT_MIPPED("sections drawn: cutout-mipped"),
    SECTIONS_CUTOUT("sections drawn: cutout"),
    SECTIONS_TRANSLUCENT("sections drawn: translucent"),
    /** Size of the visible-section list after the visibility search. */
    VISIBLE_SECTIONS("visible sections (after culling)"),
    /** {@code BuiltChunk.setNeedsUpdate(true)}: a section marked dirty. */
    INVALIDATIONS("sections invalidated"),
    /** A rebuild handed to the worker threads. */
    REBUILDS_QUEUED("rebuilds queued"),
    /** A rebuild done on the client thread, inside the frame. */
    REBUILDS_SYNC("rebuilds on the client thread"),
    /** A finished layer buffer uploaded to the GPU. */
    UPLOADS("layer buffers uploaded");

    public static final ViewCounter[] ALL = values();
    public static final int COUNT = ALL.length;

    public final String label;

    ViewCounter(String label) {
        this.label = label;
    }

    /** The counter for terrain layer {@code index} in draw order, or null. */
    public static ViewCounter layer(int index) {
        switch (index) {
            case 0: return SECTIONS_SOLID;
            case 1: return SECTIONS_CUTOUT_MIPPED;
            case 2: return SECTIONS_CUTOUT;
            case 3: return SECTIONS_TRANSLUCENT;
            default: return null;
        }
    }
}
