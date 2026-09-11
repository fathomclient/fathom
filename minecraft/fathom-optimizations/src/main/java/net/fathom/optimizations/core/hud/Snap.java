package net.fathom.optimizations.core.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapping and alignment guides for the HUD editor.
 *
 * <p>Pure geometry over rectangles, which is why it lives in {@code core} and has tests.
 * The editor screen supplies the rectangles and draws the lines this returns; it makes no
 * decisions of its own about where anything should go.
 *
 * <h2>What "snapping" means here</h2>
 *
 * <p>While a widget is being dragged, its edges and centre are compared against the screen
 * edges, the screen centre, and every other visible widget. Any pair within
 * {@link #THRESHOLD} pixels is treated as intended alignment: the dragged widget moves
 * onto the line, and the line is drawn so the user can see why it stopped moving.
 *
 * <p>Both axes are resolved independently, and each axis takes the <em>closest</em>
 * candidate rather than the first. Taking the first produced a widget that snapped to a
 * far guide because it happened to be checked earlier, which reads as the editor fighting
 * the mouse.
 */
public final class Snap {

    /** How close two edges have to be, in scaled screen pixels, to be treated as aligned. */
    public static final int THRESHOLD = 4;

    private Snap() {}

    /** An axis-aligned box in scaled screen coordinates. */
    public static final class Box {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Box(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }

        public boolean contains(int px, int py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        @Override
        public String toString() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    /** A line the editor should draw, in screen coordinates. */
    public static final class Guide {
        public final boolean vertical;
        public final int position;

        Guide(boolean vertical, int position) {
            this.vertical = vertical;
            this.position = position;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Guide
                    && ((Guide) other).vertical == vertical
                    && ((Guide) other).position == position;
        }

        @Override
        public int hashCode() {
            return position * 2 + (vertical ? 1 : 0);
        }

        @Override
        public String toString() {
            return (vertical ? "x=" : "y=") + position;
        }
    }

    /** Where a dragged box ended up, and why. */
    public static final class Result {
        public final int x;
        public final int y;
        private final List<Guide> guides;

        Result(int x, int y, List<Guide> guides) {
            this.x = x;
            this.y = y;
            this.guides = Collections.unmodifiableList(guides);
        }

        public List<Guide> guides() {
            return guides;
        }

        public boolean snapped() {
            return !guides.isEmpty();
        }
    }

    /**
     * Snap a dragged box against the screen and its neighbours.
     *
     * @param dragged where the mouse currently puts it
     * @param others every other visible widget, which it may align to
     * @param screenWidth scaled screen width
     * @param screenHeight scaled screen height
     */
    public static Result snap(Box dragged, List<Box> others, int screenWidth, int screenHeight) {
        List<Guide> guides = new ArrayList<Guide>();

        Candidate horizontal = new Candidate();
        Candidate vertical = new Candidate();

        // Screen edges and centre. A widget aligned to the middle of the screen is
        // a thing people do deliberately and cannot do by hand at one-pixel accuracy.
        considerX(horizontal, dragged, 0);
        considerX(horizontal, dragged, screenWidth);
        considerX(horizontal, dragged, screenWidth / 2);
        considerY(vertical, dragged, 0);
        considerY(vertical, dragged, screenHeight);
        considerY(vertical, dragged, screenHeight / 2);

        for (Box other : others) {
            considerX(horizontal, dragged, other.x);
            considerX(horizontal, dragged, other.right());
            considerX(horizontal, dragged, other.centerX());
            considerY(vertical, dragged, other.y);
            considerY(vertical, dragged, other.bottom());
            considerY(vertical, dragged, other.centerY());
        }

        int x = dragged.x;
        int y = dragged.y;
        if (horizontal.found) {
            x = dragged.x + horizontal.delta;
            guides.add(new Guide(true, horizontal.line));
        }
        if (vertical.found) {
            y = dragged.y + vertical.delta;
            guides.add(new Guide(false, vertical.line));
        }
        return new Result(x, y, guides);
    }

    /**
     * One axis being resolved.
     *
     * <p>Keeps the best candidate rather than the first, so a widget between two guides
     * moves to the nearer one.
     */
    private static final class Candidate {
        boolean found;
        int distance = Integer.MAX_VALUE;
        int delta;
        int line;

        void offer(int gap, int shift, int guideLine) {
            int magnitude = Math.abs(gap);
            if (magnitude <= THRESHOLD && magnitude < distance) {
                found = true;
                distance = magnitude;
                delta = shift;
                line = guideLine;
            }
        }
    }

    private static void considerX(Candidate candidate, Box dragged, int line) {
        candidate.offer(line - dragged.x, line - dragged.x, line);
        candidate.offer(line - dragged.right(), line - dragged.right(), line);
        candidate.offer(line - dragged.centerX(), line - dragged.centerX(), line);
    }

    private static void considerY(Candidate candidate, Box dragged, int line) {
        candidate.offer(line - dragged.y, line - dragged.y, line);
        candidate.offer(line - dragged.bottom(), line - dragged.bottom(), line);
        candidate.offer(line - dragged.centerY(), line - dragged.centerY(), line);
    }

    /**
     * Keep a box on screen.
     *
     * <p>Applied after snapping, not before: a widget can legitimately be snapped to the
     * far edge, and clamping first would fight it. What this prevents is a widget dragged
     * entirely off the screen and then impossible to grab again.
     */
    public static Box clamp(Box box, int screenWidth, int screenHeight) {
        int x = Math.max(0, Math.min(box.x, screenWidth - box.width));
        int y = Math.max(0, Math.min(box.y, screenHeight - box.height));
        return new Box(x, y, box.width, box.height);
    }
}
