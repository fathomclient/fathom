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
    UPLOADS("layer buffers uploaded"),
    /** Sections invalidated while a packet was being applied: the server changed a block or sent a chunk. */
    INVALIDATIONS_BY_PACKET("sections invalidated by packets"),
    /** Packets applied on the client thread this frame. */
    PACKET_TASKS("packets applied on the client thread"),
    /** Packets the network thread queued for the client thread this frame. */
    PACKETS_QUEUED("packets queued by the network thread"),
    /** Other players in the world (level). */
    PLAYERS_LOADED("other players loaded"),
    /** Entities in the world (level). */
    ENTITIES_LOADED("entities loaded"),
    /** Live particles (level). */
    PARTICLES_LIVE("particles live"),
    /** {@code TextRenderer.getStringWidth} calls, every caller. */
    STRING_WIDTHS("string width queries"),
    /** Client ticks run this frame: 0 on most frames, 1 on about one in (fps / 20). */
    TICKS("client ticks");

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
