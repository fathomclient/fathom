package net.fathom.optimizations.core.module;

/**
 * What a module needs before it can do anything.
 *
 * <h2>Why this is data and not a comment</h2>
 *
 * <p>Several modules only mean something in some situations: Ping is a number
 * the server sends, so in singleplayer it is zero and useless; Armour needs a
 * player; the frame caps need nothing at all. Without somewhere to say that, the
 * interface either lies — a switch that appears to work and shows nothing — or
 * each module hides itself with its own private rule, which is the same lie
 * written five times.
 *
 * <p>With it, the row can say <em>"On, but only shows in multiplayer"</em>, and
 * that sentence comes from the same place the widget's own visibility check
 * does.
 */
public final class Compatibility {

    /** Needs nothing. Works on the title screen. */
    public static final Compatibility ALWAYS = new Compatibility(false, false, "");

    /** Needs a world loaded and a player in it. */
    public static final Compatibility IN_GAME = new Compatibility(true, false, "Available in game");

    /** Needs a server, so it says nothing in singleplayer. */
    public static final Compatibility MULTIPLAYER =
            new Compatibility(true, true, "Available in multiplayer");

    private final boolean world;
    private final boolean server;
    private final String note;

    private Compatibility(boolean world, boolean server, String note) {
        this.world = world;
        this.server = server;
        this.note = note == null ? "" : note;
    }

    /** The same requirements with something extra to say. */
    public Compatibility saying(String extra) {
        String both = note.isEmpty() ? extra : note + " · " + extra;
        return new Compatibility(world, server, both);
    }

    public boolean needsWorld() {
        return world;
    }

    public boolean needsServer() {
        return server;
    }

    /** A sentence for the interface, or empty when there is nothing to say. */
    public String note() {
        return note;
    }

    /**
     * Whether the requirements are met.
     *
     * @param inWorld whether a world is loaded with a player in it
     * @param onServer whether that world is a multiplayer server
     */
    public boolean satisfied(boolean inWorld, boolean onServer) {
        if (world && !inWorld) {
            return false;
        }
        return !server || onServer;
    }
}
