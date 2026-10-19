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
    TICKS("client ticks"),

    // Engagement: each optimization's fast path actually taken, client thread.
    // A Mixin that applied proves nothing about whether its target is ever
    // called (Forge draws armour through a method of its own, so the vanilla
    // hook applied and never ran). These count the work done, so a loader on
    // which an optimization is applied but idle shows a zero, not a guess.
    ENGAGED_FONT_METRICS("engaged: font widths from cache"),
    ENGAGED_CHUNK_LOOKUP("engaged: chunk lookups from cache"),
    ENGAGED_DIRECTIONS("engaged: shared direction arrays"),
    ENGAGED_NAMETAG_NAMES("engaged: nametag names from cache"),
    ENGAGED_ARMOR_TEXTURE("engaged: armour textures from cache"),
    ENGAGED_SIGN_TEXT("engaged: sign texts from cache"),
    ENGAGED_ITEM_FRAME("engaged: item frame entities reused"),
    ENGAGED_SHADOW("engaged: shadow positions reused"),
    ENGAGED_HUD_REPLAY("engaged: chat/sidebar replayed"),
    ENGAGED_TAB_LIST("engaged: tab list strings replayed"),
    ENGAGED_SKY_BLEND("engaged: Forge sky colour from cache");

    public static final ViewCounter[] ALL = values();
    public static final int COUNT = ALL.length;

    public final String label;

    ViewCounter(String label) {
        this.label = label;
    }

    /** Whether this counts an optimization's fast path rather than work drawn. */
    public boolean engagement() {
        return name().startsWith("ENGAGED_");
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
