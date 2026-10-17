package net.fathom.optimizations.core.bench.view;

/**
 * What a packet the client thread applied was about, for the multiplayer profile.
 *
 * <p>Grouped by what the packet makes the client do rather than one row per
 * packet class: forty packet types with a handful each would hide the question,
 * which is whether a burst of one kind of server update is what lands in the
 * frame. The fabric side maps each packet class to one of these once
 * ({@code ClassValue}), so the hot path is an array index.
 */
public enum PacketKind {
    MOVE("entity movement (move, look, teleport, head yaw, velocity)"),
    SPAWN("entity spawn / destroy"),
    METADATA("entity metadata, attributes, effects, attach"),
    EQUIPMENT("entity equipment"),
    ANIMATION("entity animation / status (swing, hurt, crit)"),
    CHUNK("chunk data (single and bulk)"),
    BLOCKS("block changes (single, multi, block entity, action, break, explosion, world event)"),
    SCOREBOARD("scoreboard objectives, scores, display slots"),
    TEAMS("teams (prefixes, suffixes, members)"),
    PLAYER_LIST("tab list entries, header, footer"),
    PARTICLES("particles"),
    SOUND("sounds"),
    CHAT("chat, titles, action bar"),
    INVENTORY("inventory, slots, windows"),
    SELF("own player (position, health, xp, abilities)"),
    WORLD("world state (time, weather, border, spawn)"),
    OTHER("everything else"),
    /** A client-thread task that was not a packet (screens, resource reloads, the integrated server). */
    NOT_A_PACKET("scheduled task, not a packet");

    public static final PacketKind[] ALL = values();
    public static final int COUNT = ALL.length;

    /** Slots per kind in the per-frame packet array handed to the sink. */
    public static final int STRIDE = 4;
    /** Nanoseconds the client thread spent applying packets of this kind. */
    public static final int NANOS = 0;
    /** Packets of this kind applied on the client thread. */
    public static final int APPLIED = 1;
    /** Packets of this kind queued for the client thread by the network thread. */
    public static final int QUEUED = 2;
    /** Chunk sections invalidated while a packet of this kind was being applied. */
    public static final int INVALIDATIONS = 3;

    public final String label;

    PacketKind(String label) {
        this.label = label;
    }
}
