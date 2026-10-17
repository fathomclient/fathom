package net.fathom.optimizations.core.hud;

/**
 * The scoreboard sidebar's geometry, as vanilla lays it out.
 *
 * <p>Vanilla's sidebar is a column of rows, one per score, under a title row
 * and a one-pixel rule: each row is a line of text on a dark band that runs two
 * pixels past the text on the left and ends flush with the score on the right.
 * Fathom's Scoreboard module draws the same thing at a position and scale the
 * player chooses, so these numbers are kept here, in one place, where a test
 * can hold them to vanilla's.
 */
public final class Sidebar {

    /** Vanilla shows the fifteen highest scores and no more. */
    public static final int MAX_ROWS = 15;

    /** Band past the text on the left, and the space the right edge keeps. */
    public static final int INSET = 2;

    /** Vanilla's band alphas: the title row is a little darker than the rest. */
    public static final int ROW_ALPHA = 0x50;
    public static final int TITLE_ALPHA = 0x60;

    private Sidebar() {}

    /** Entries whose name starts with {@code #} are hidden by vanilla, by convention. */
    public static boolean hidden(String name) {
        return name != null && name.startsWith("#");
    }

    /** The whole sidebar's width for {@code content} pixels of text. */
    public static int width(int content, int padding) {
        return content + INSET * 2 + padding * 2;
    }

    /** The whole sidebar's height: the rows, and the title row and its rule when shown. */
    public static int height(int rows, int rowHeight, boolean title, int padding) {
        return rows * rowHeight + (title ? rowHeight + 1 : 0) + padding * 2;
    }

    /** A band alpha, scaled by the module's row background setting and the widget's opacity. */
    public static int bandAlpha(int vanillaAlpha, int percent, int alpha) {
        int clamped = Math.max(0, Math.min(100, percent));
        return vanillaAlpha * clamped / 100 * Math.max(0, Math.min(255, alpha)) / 255;
    }
}
