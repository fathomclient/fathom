package net.fathom.optimizations.fabric.diag;

/**
 * Where a long frame went, for the stalls that are hard to catch in a profiler:
 * a freeze on joining a world, a hitch on the first hit after a quiet spell.
 *
 * <p>Off unless the game is started with {@code -Dfathom.diag.hitches=true}.
 * {@link #ENABLED} is a {@code static final}, so with it off every call site is
 * a branch the JIT removes and {@link #begin} is a constant zero: nothing is
 * read, allocated or logged.
 *
 * <p>On, it times the Fathom-owned paths that can block the render thread
 * (creating the interface compositor, the HUD pass, a world or resource
 * change, an attack, glyph and icon rasterising) and writes a line only for a
 * section longer than {@code -Dfathom.diag.hitches.ms} (default 8), and one
 * line for a whole frame longer than {@code -Dfathom.diag.hitches.frame.ms}
 * (default 40) that says which timed section was the longest in it. A frame
 * that was long with no timed section in it points away from Fathom's code.
 * At most {@value #MAX_LINES} lines are written per run, so a bad stretch
 * cannot flood the log.
 *
 * <p>A line holds a section name and durations. No names, chat, addresses or
 * account data are ever passed in or written.
 */
public final class Hitches {

    /** The paths that are timed. */
    public enum Section {
        COMPOSITOR_CREATE("interface compositor created"),
        COMPOSITOR_SHADERS("compositor shaders compiled"),
        COMPOSITOR_ATLAS("compositor font atlas uploaded"),
        HUD_PASS("Fathom HUD pass"),
        EVENT_DISPATCH("per-frame Fathom listeners"),
        WORLD_CHANGE("world or player changed"),
        RESOURCE_RELOAD("resource reload hook"),
        ATTACK("attack on an entity"),
        GLYPH_PAGE("glyph page rasterised"),
        ICON_RASTER("icon rasterised"),
        NAMETAG_COMPILE("nametag list recorded");

        final String what;

        Section(String what) {
            this.what = what;
        }
    }

    public static final boolean ENABLED = Boolean.getBoolean("fathom.diag.hitches");

    private static final int MAX_LINES = 60;
    private static final long SECTION_NANOS = Long.getLong("fathom.diag.hitches.ms", 8L) * 1_000_000L;
    private static final long FRAME_NANOS = Long.getLong("fathom.diag.hitches.frame.ms", 40L) * 1_000_000L;

    // Client thread only, like everything it times.
    private static int lines;
    private static long frames;
    private static Section longest;
    private static long longestNanos;

    private Hitches() {}

    /** The start of a timed section: a clock reading, or zero when diagnostics are off. */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** The end of a section started with {@link #begin}. */
    public static void end(Section section, long started) {
        if (!ENABLED || started == 0L) {
            return;
        }
        long took = System.nanoTime() - started;
        if (took > longestNanos) {
            longestNanos = took;
            longest = section;
        }
        if (took >= SECTION_NANOS) {
            say(section.what + " took " + millis(took) + " ms (frame " + frames + ")");
        }
    }

    /** The end of a frame: one line if it was long. */
    public static void frame(long frameNanos) {
        if (!ENABLED) {
            return;
        }
        frames++;
        if (frameNanos >= FRAME_NANOS) {
            say("frame " + frames + " took " + millis(frameNanos) + " ms; longest Fathom section in it: "
                    + (longest == null ? "none" : longest.what + " " + millis(longestNanos) + " ms"));
        }
        longest = null;
        longestNanos = 0L;
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static void say(String line) {
        if (lines >= MAX_LINES) {
            return;
        }
        lines++;
        System.out.println("[Fathom hitch] " + line + (lines == MAX_LINES ? " (further hitches not logged)" : ""));
    }
}
