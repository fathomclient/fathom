package net.fathom.optimizations.core.hud;

/**
 * Keeps widgets off each other, whatever they happen to contain.
 *
 * <h2>Why a placement alone cannot do it</h2>
 *
 * <p>A placement is an anchor and an offset, chosen once. What it places is
 * not a fixed size: the coordinates readout is one line or three depending on
 * its settings, the potion list is as long as the effects you have, and the
 * same widget is a different number of pixels at every GUI scale. FPS at the
 * top-left corner and Coordinates "twelve pixels under it" stops being under
 * it the moment FPS grows a plate, which is how the shipped layout came to
 * have its first two widgets drawn over each other.
 *
 * <h2>What this does</h2>
 *
 * <p>Each frame, after every widget has been placed where its placement says,
 * any widget that lands on one placed before it is moved along its anchor's
 * own direction -- down for a widget hung from the top or the middle, up for
 * one standing on the bottom -- until it is clear, or the other way when its
 * own way runs off the screen. Widgets nearer their own
 * edge are placed first, so a stack grows away from its corner the way a list
 * does. The stored placements are never touched: shrink the potion list and
 * everything under it moves back up.
 *
 * <p>Two things are left alone. A widget marked fixed -- the one being
 * dragged in the editor, and the crosshair, which belongs where it is aimed --
 * is never moved, though others still move off it. And a widget that has
 * nowhere clear to go on screen stays exactly where it was put: an overlap the
 * player can see and fix beats a widget pushed off the edge.
 *
 * <p>Allocation-free: the caller owns every array, so this can run on the
 * render thread every frame. A dozen widgets is a few hundred comparisons.
 */
public final class HudFlow {

    /** Space left between two widgets that had to be moved apart. */
    public static final int GAP = 2;

    private HudFlow() {}

    /** Which way a widget with this anchor moves to get clear: 1 is down, -1 is up. */
    public static int direction(Anchor anchor) {
        return anchor.vertical() > 0.5f ? -1 : 1;
    }

    /**
     * Move widgets off one another.
     *
     * @param count how many widgets the arrays describe
     * @param x left edges, read
     * @param y top edges, read and written
     * @param w widths, read
     * @param h heights, read
     * @param direction 1 to move down, -1 to move up, 0 to take no part at all
     * @param fixed widgets that may not be moved but are still in the way
     * @param order scratch, at least {@code count} long
     * @param screenHeight the space a moved widget has to stay inside
     */
    public static void resolve(int count, int[] x, int[] y, int[] w, int[] h, int[] direction,
            boolean[] fixed, int[] order, int screenHeight) {
        // Placing order: fixed first, then nearest its own edge, then as listed.
        // An insertion sort -- stable, and a dozen items.
        int ordered = 0;
        for (int index = 0; index < count; index++) {
            if (direction[index] == 0) {
                continue;
            }
            int at = ordered++;
            while (at > 0 && before(index, order[at - 1], y, h, direction, fixed, screenHeight)) {
                order[at] = order[at - 1];
                at--;
            }
            order[at] = index;
        }

        for (int position = 0; position < ordered; position++) {
            int self = order[position];
            if (fixed[self]) {
                continue;
            }
            int original = y[self];
            if (firstOverlap(self, original, position, order, x, y, w, h) < 0) {
                continue;
            }
            // Its own way first; if that runs off the screen, the other way --
            // a widget hung from the middle of an edge can as well go up as
            // down. Failing both, it stays where it was put.
            int candidate = clearing(self, original, direction[self], position, order, x, y, w, h, screenHeight);
            if (candidate == Integer.MIN_VALUE) {
                candidate = clearing(self, original, -direction[self], position, order, x, y, w, h, screenHeight);
            }
            y[self] = candidate == Integer.MIN_VALUE ? original : candidate;
        }
    }

    /**
     * The nearest clear top edge moving one way from {@code start}, on screen,
     * or {@link Integer#MIN_VALUE} when there is none.
     */
    private static int clearing(int self, int start, int way, int placed, int[] order,
            int[] x, int[] y, int[] w, int[] h, int screenHeight) {
        int candidate = start;
        // Each step moves past one obstacle; a widget can need several.
        for (int step = 0; step <= placed; step++) {
            int blocker = firstOverlap(self, candidate, placed, order, x, y, w, h);
            if (blocker < 0) {
                break;
            }
            candidate = way > 0 ? y[blocker] + h[blocker] + GAP : y[blocker] - GAP - h[self];
            if (candidate < 0 || candidate + h[self] > screenHeight) {
                return Integer.MIN_VALUE;
            }
        }
        boolean clear = firstOverlap(self, candidate, placed, order, x, y, w, h) < 0;
        return clear ? candidate : Integer.MIN_VALUE;
    }

    private static boolean before(int a, int b, int[] y, int[] h, int[] direction, boolean[] fixed,
            int screenHeight) {
        if (fixed[a] != fixed[b]) {
            return fixed[a];
        }
        int distanceA = distance(a, y, h, direction, screenHeight);
        int distanceB = distance(b, y, h, direction, screenHeight);
        return distanceA < distanceB;
    }

    /** How far a widget sits from the edge it grows away from. */
    private static int distance(int index, int[] y, int[] h, int[] direction, int screenHeight) {
        return direction[index] > 0 ? y[index] : screenHeight - (y[index] + h[index]);
    }

    /** The first already-placed widget a candidate position would overlap, or -1. */
    private static int firstOverlap(int self, int top, int placed, int[] order,
            int[] x, int[] y, int[] w, int[] h) {
        for (int position = 0; position < placed; position++) {
            int other = order[position];
            if (x[self] < x[other] + w[other] && x[other] < x[self] + w[self]
                    && top < y[other] + h[other] && y[other] < top + h[self]) {
                return other;
            }
        }
        return -1;
    }
}
