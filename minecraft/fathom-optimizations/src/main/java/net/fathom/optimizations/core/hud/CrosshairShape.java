package net.fathom.optimizations.core.hud;

import java.util.Arrays;

/**
 * A crosshair as integer rectangles.
 *
 * <h2>Why geometry lives here and not in the widget</h2>
 *
 * <p>Two things draw a crosshair: the HUD, through Minecraft's tessellator in
 * scaled GUI pixels, and the preview in the settings drawer, through Fathom's
 * renderer in logical units. If each worked out its own arms and gaps they
 * would disagree the first time somebody changed one of them, and the preview
 * would be a picture of a different crosshair. So both ask this class for the
 * same rectangles and only choose how to paint them.
 *
 * <h2>Why every coordinate is a whole pixel</h2>
 *
 * <p>A crosshair is the one HUD element people look at the exact centre of. A
 * half-pixel arm lands on two rows of the framebuffer at half brightness each,
 * and at GUI scale 3 that is a visibly soft, asymmetric line. Everything below
 * is built on a centre cell {@code thickness} pixels square, so each arm starts
 * and ends on a pixel edge and the four sides are mirror images.
 *
 * <h2>Why it is rasterised to a mask first</h2>
 *
 * <p>An outline drawn as "every rectangle, one pixel larger" overlaps itself
 * wherever two arms meet, and at anything under full opacity the overlaps show
 * as dark knots. So the shape is painted into a small boolean grid, the outline
 * is that grid grown by its width minus the shape itself, and both are read back
 * as row runs. The two never overlap, nothing overlaps itself, and a translucent
 * crosshair is one even tone.
 *
 * <p>Rebuilt only when an input changes; the caller keys that. The arrays are
 * reused, so a rebuild allocates nothing once they have grown to size.
 */
public final class CrosshairShape {

    public enum Style {
        CROSS,
        TEE,
        DOT,
        CIRCLE
    }

    /** Inputs are clamped to these, so a hand-edited profile cannot ask for a 10,000 pixel mask. */
    public static final int MAX_LENGTH = 40;
    public static final int MAX_THICKNESS = 8;
    public static final int MAX_GAP = 40;
    public static final int MAX_DOT = 12;
    public static final int MAX_OUTLINE = 4;

    private int[] primitives = new int[32];
    private int primitiveCount;

    private boolean[] shape = new boolean[0];
    private boolean[] grown = new boolean[0];

    /** Four ints per rectangle, in box coordinates: left, top, right, bottom. */
    private int[] fill = new int[64];
    private int fillCount;
    private int[] outline = new int[64];
    private int outlineCount;

    private int size;
    private int centre;

    /**
     * Lay the crosshair out.
     *
     * @param length how far each arm reaches, in pixels, not counting the gap
     * @param thickness line width, and the side of the centre cell
     * @param gap empty space between the centre cell and each arm
     * @param dot whether to fill a square at the centre as well
     * @param dotSize the side of that square
     * @param outlineWidth pixels of outline around the shape; 0 for none
     */
    public void build(Style style, int length, int thickness, int gap, boolean dot, int dotSize,
            int outlineWidth) {
        int t = clamp(thickness, 1, MAX_THICKNESS);
        int len = clamp(length, 0, MAX_LENGTH);
        int g = clamp(gap, 0, MAX_GAP);
        int d = clamp(dotSize, 1, MAX_DOT);
        int o = clamp(outlineWidth, 0, MAX_OUTLINE);
        primitiveCount = 0;

        // Primitives relative to the centre cell's top-left, which is (0, 0).
        switch (style == null ? Style.CROSS : style) {
            case DOT:
                square(d, t);
                break;
            case CIRCLE:
                circle(g + len, t);
                if (dot) {
                    square(d, t);
                }
                break;
            case TEE:
            case CROSS:
            default:
                if (len > 0) {
                    add(t + g, 0, t + g + len, t);            // right
                    add(-g - len, 0, -g, t);                  // left
                    add(0, t + g, t, t + g + len);            // down
                    if (style != Style.TEE) {
                        add(0, -g - len, t, -g);              // up
                    }
                    if (g == 0) {
                        // Arms that meet need their meeting point, or a gapless
                        // cross has a hole exactly where the aim is.
                        add(0, 0, t, t);
                    }
                }
                if (dot || len == 0) {
                    square(d, t);
                }
                break;
        }

        // The box: symmetric about the centre cell, so a centred widget puts
        // the centre cell on the screen's centre pixel.
        int reach = 0;
        for (int index = 0; index < primitiveCount; index += 4) {
            reach = Math.max(reach, -primitives[index]);
            reach = Math.max(reach, -primitives[index + 1]);
            reach = Math.max(reach, primitives[index + 2] - t);
            reach = Math.max(reach, primitives[index + 3] - t);
        }
        centre = reach + o;
        size = centre * 2 + t;

        int cells = size * size;
        if (shape.length < cells) {
            shape = new boolean[cells];
            grown = new boolean[cells];
        } else {
            Arrays.fill(shape, 0, cells, false);
            Arrays.fill(grown, 0, cells, false);
        }
        for (int index = 0; index < primitiveCount; index += 4) {
            paint(shape, primitives[index] + centre, primitives[index + 1] + centre,
                    primitives[index + 2] + centre, primitives[index + 3] + centre);
        }
        if (o > 0) {
            // Square dilation: a square-cornered outline, the way a pixel
            // crosshair's outline is expected to look.
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (shape[y * size + x]) {
                        paint(grown, x - o, y - o, x + o + 1, y + o + 1);
                    }
                }
            }
            for (int cell = 0; cell < cells; cell++) {
                grown[cell] = grown[cell] && !shape[cell];
            }
        }
        fillCount = runs(shape, true);
        outlineCount = o > 0 ? runs(grown, false) : 0;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    /** A square of side {@code size} centred on the centre cell. */
    private void square(int side, int t) {
        // When the parities differ there is no true centre, and the square
        // leans half a pixel up and to the left. The presets pair odd dots with
        // odd lines, so that case is somebody's choice rather than a default.
        int start = Math.floorDiv(t - side, 2);
        add(start, start, start + side, start + side);
    }

    /** A ring of the given outer radius and line width, row by row. */
    private void circle(int radius, int t) {
        int outer = Math.max(1, radius);
        int inner = Math.max(0, outer - t);
        double middle = t / 2.0;
        for (int row = -outer; row < t + outer; row++) {
            double dy = row + 0.5 - middle;
            double outerHalf = halfWidth(outer, dy);
            if (outerHalf <= 0) {
                continue;
            }
            int left = (int) Math.round(middle - outerHalf);
            int right = (int) Math.round(middle + outerHalf);
            double innerHalf = halfWidth(inner, dy);
            if (innerHalf <= 0) {
                add(left, row, right, row + 1);
                continue;
            }
            int innerLeft = (int) Math.round(middle - innerHalf);
            int innerRight = (int) Math.round(middle + innerHalf);
            add(left, row, innerLeft, row + 1);
            add(innerRight, row, right, row + 1);
        }
    }

    private static double halfWidth(int radius, double dy) {
        double squared = (double) radius * radius - dy * dy;
        return squared <= 0 ? 0 : Math.sqrt(squared);
    }

    private void add(int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        if (primitiveCount + 4 > primitives.length) {
            primitives = Arrays.copyOf(primitives, primitives.length * 2);
        }
        primitives[primitiveCount++] = left;
        primitives[primitiveCount++] = top;
        primitives[primitiveCount++] = right;
        primitives[primitiveCount++] = bottom;
    }

    private void paint(boolean[] mask, int left, int top, int right, int bottom) {
        int l = Math.max(0, left);
        int t = Math.max(0, top);
        int r = Math.min(size, right);
        int b = Math.min(size, bottom);
        for (int y = t; y < b; y++) {
            int row = y * size;
            for (int x = l; x < r; x++) {
                mask[row + x] = true;
            }
        }
    }

    /**
     * Read a mask back as rectangles: one per horizontal run, with runs that
     * repeat on the row below merged into one taller rectangle. An arm is one
     * rectangle, not {@code length} of them.
     */
    private int runs(boolean[] mask, boolean intoFill) {
        int[] out = intoFill ? fill : outline;
        int count = 0;
        // Rectangles still open from the row above start at `openFrom`.
        int openFrom = 0;
        for (int y = 0; y < size; y++) {
            int rowStart = count;
            int x = 0;
            while (x < size) {
                if (!mask[y * size + x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < size && mask[y * size + x]) {
                    x++;
                }
                if (count + 4 > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[count++] = start;
                out[count++] = y;
                out[count++] = x;
                out[count++] = y + 1;
            }
            // Same runs as the previous row: extend those and drop these.
            int previousLength = rowStart - openFrom;
            if (y > 0 && previousLength > 0 && previousLength == count - rowStart
                    && sameRuns(out, openFrom, rowStart, previousLength)) {
                for (int index = openFrom; index < rowStart; index += 4) {
                    out[index + 3] = y + 1;
                }
                count = rowStart;
            } else {
                openFrom = rowStart;
            }
        }
        if (intoFill) {
            fill = out;
        } else {
            outline = out;
        }
        return count;
    }

    private static boolean sameRuns(int[] rects, int previous, int current, int length) {
        for (int offset = 0; offset < length; offset += 4) {
            if (rects[previous + offset] != rects[current + offset]
                    || rects[previous + offset + 2] != rects[current + offset + 2]) {
                return false;
            }
        }
        return true;
    }

    /** The filled rectangles, four ints each, in box coordinates. Valid until the next {@link #build}. */
    public int[] fill() {
        return fill;
    }

    /** Number of ints used in {@link #fill()}: four per rectangle. */
    public int fillCount() {
        return fillCount;
    }

    /** The outline rectangles, drawn in the outline colour. Never overlaps the fill. */
    public int[] outline() {
        return outline;
    }

    public int outlineCount() {
        return outlineCount;
    }

    /** The side of the square box that holds the whole shape, outline included. */
    public int size() {
        return size;
    }

    /** Where the centre cell's top-left sits inside the box, on both axes. */
    public int centre() {
        return centre;
    }
}
