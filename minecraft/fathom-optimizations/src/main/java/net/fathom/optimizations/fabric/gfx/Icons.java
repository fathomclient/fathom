package net.fathom.optimizations.fabric.gfx;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.generated.SharedIconPaths;

/**
 * Fathom's icon family, drawn as vectors and rasterised anti-aliased.
 *
 * <h2>What changed, and what did not</h2>
 *
 * <p>The <em>drawings</em> are the ones the first hub used: the same 24-unit
 * grid, the same paths, the same Lucide-like geometry that matches the
 * launcher's icon family. What changed is how they reach the screen. They used
 * to be walked with Bresenham and stamped as one-pixel quads — a staircase, in
 * a coordinate space that was then magnified three times. Now they are
 * {@code Path2D} outlines stroked with round caps and joins by Java2D, at the
 * exact pixel size they will be drawn at, and cached.
 *
 * <p>That is the same argument as the font, in pictures: the description is
 * resolution-independent, so rasterise it at the resolution you have.
 *
 * <h2>Stroke weight</h2>
 *
 * <p>The launcher's weight, not Lucide's ratio: one 1.5-unit pen
 * ({@code IconGeometry.STROKE_DP}) at every size, multiplied by the display
 * scale, so a 12-unit caption glyph and a 20-unit navigation glyph read as the
 * same pen here, in the NanoVG renderer and in the launcher's
 * {@code svg.lucide} rule. A plain 2/24 ratio drew small icons hairline-thin
 * and large ones heavy.
 */
final class Icons {

    /** The grid every path is described on. */
    private static final float GRID = 24f;

    /** Below this many device pixels a stroke stops reading as a line. */
    private static final float MIN_STROKE_PIXELS = 1.3f;

    /**
     * The stroke for an icon drawn {@code logical} interface units wide: the
     * same pen at every size ({@link IconGeometry#STROKE_DP}), as NanoVG and
     * the launcher draw it.
     */
    static float strokeUnits(float logical) {
        return net.fathom.optimizations.core.ui.render.IconGeometry.STROKE_DP;
    }

    private static final int SHEET = 512;

    /** Icons in use at once, looked up without allocating. See {@code FontBook}. */
    private static final int CACHE = 16;

    private final Atlas atlas;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private final int[] recentKeys = new int[CACHE];
    private final Entry[] recentEntries = new Entry[CACHE];
    private int recentNext;

    /** Where one icon at one size lives on the sheet. */
    static final class Entry {
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final int pixels;

        Entry(float u0, float v0, float u1, float v1, int pixels) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.pixels = pixels;
        }
    }

    Icons(Atlas.BeforeUpload beforeUpload) {
        this.atlas = new Atlas(SHEET, beforeUpload);
    }

    Atlas atlas() {
        return atlas;
    }

    int count() {
        return entries.size();
    }

    void create() {
        atlas.create();
    }

    void reset() {
        entries.clear();
        forgetRecent();
        atlas.delete();
        atlas.create();
    }

    void delete() {
        entries.clear();
        forgetRecent();
        atlas.delete();
    }

    private void forgetRecent() {
        for (int slot = 0; slot < CACHE; slot++) {
            recentKeys[slot] = 0;
            recentEntries[slot] = null;
        }
        recentNext = 0;
    }

    /**
     * The sheet entry for an icon at a device size, rasterising if needed.
     *
     * @param strokePixels the stroke in device pixels (see {@link #strokeUnits})
     */
    Entry entry(Icon icon, int pixels, float strokePixels) {
        int size = Math.max(6, Math.min(128, pixels));
        int tenths = Math.max(1, Math.min(99, Math.round(strokePixels * 10f)));
        int key = (tenths * 256 + size) * 64 + icon.ordinal() + 1;
        for (int slot = 0; slot < CACHE; slot++) {
            if (recentKeys[slot] == key) {
                return recentEntries[slot];
            }
        }
        String name = icon.name() + '/' + size + '/' + tenths;
        Entry found = entries.get(name);
        if (found == null) {
            found = rasterise(icon, size, tenths / 10f);
            entries.put(name, found);
        }
        recentKeys[recentNext] = key;
        recentEntries[recentNext] = found;
        recentNext = (recentNext + 1) % CACHE;
        return found;
    }

    private Entry rasterise(Icon icon, int size, float strokePixels) {
        int[] at = atlas.allocate(size, size);
        if (at == null) {
            return new Entry(0f, 0f, 0f, 0f, size);
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setColor(Color.WHITE);

        float scale = size / GRID;
        graphics.scale(scale, scale);

        float stroke = Math.max(strokePixels, MIN_STROKE_PIXELS) / scale;
        graphics.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Pen pen = new Pen(stroke);
        paths(icon, pen);
        graphics.draw(pen.strokes);
        graphics.fill(pen.fills);
        graphics.dispose();

        atlas.upload(image, at[0], at[1]);
        return new Entry(
                atlas.u(at[0]),
                atlas.v(at[1]),
                atlas.u(at[0] + size),
                atlas.v(at[1] + size),
                size);
    }

    // -----------------------------------------------------------------------
    // The pen
    // -----------------------------------------------------------------------

    /**
     * Describes a glyph on the 24-unit grid.
     *
     * <p>Two paths, because an icon is a stroked outline plus, occasionally,
     * something solid — the dot at the centre of a gauge, the bars of a signal
     * meter. Filling and stroking one path would put a stroke around the solid
     * parts.
     */
    private static final class Pen {
        final Path2D.Float strokes = new Path2D.Float();
        final Path2D.Float fills = new Path2D.Float();
        private final float dotRadius;

        Pen(float stroke) {
            this.dotRadius = stroke * 0.62f;
        }

        void line(float x0, float y0, float x1, float y1) {
            strokes.moveTo(x0, y0);
            strokes.lineTo(x1, y1);
        }

        /** A solid round point, the way a stroke's round cap would end. */
        void dot(float x, float y) {
            fills.append(new Ellipse2D.Float(x - dotRadius, y - dotRadius, dotRadius * 2, dotRadius * 2), false);
        }

        void box(float x0, float y0, float x1, float y1) {
            box(x0, y0, x1, y1, 0f);
        }

        void box(float x0, float y0, float x1, float y1, float radius) {
            strokes.append(
                    new RoundRectangle2D.Float(x0, y0, x1 - x0, y1 - y0, radius * 2f, radius * 2f), false);
        }

        void fillRect(float x0, float y0, float x1, float y1) {
            fills.append(new java.awt.geom.Rectangle2D.Float(x0, y0, x1 - x0, y1 - y0), false);
        }

        void circle(float cx, float cy, float radius) {
            strokes.append(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2), false);
        }

        /**
         * An arc, in the same degrees the drawings were written in.
         *
         * <p>Those degrees run clockwise on screen — {@code sin} against a
         * y-axis that points down. Java2D's run counter-clockwise. Negating
         * both ends converts one to the other, which is why every path below
         * could be carried across unchanged.
         */
        void arc(float cx, float cy, float radius, int fromDegrees, int toDegrees) {
            strokes.append(
                    new Arc2D.Float(
                            cx - radius,
                            cy - radius,
                            radius * 2,
                            radius * 2,
                            -fromDegrees,
                            -(toDegrees - fromDegrees),
                            Arc2D.OPEN),
                    false);
        }
    }

    // -----------------------------------------------------------------------
    // The drawings
    // -----------------------------------------------------------------------

    private static void paths(Icon icon, Pen pen) {
        String shared = SharedIconPaths.path(icon);
        if (shared != null && !shared.isEmpty()) {
            SvgPath.append(shared, SharedIconPaths.filled(icon) ? pen.fills : pen.strokes);
            return;
        }
        switch (icon) {
            case SEARCH:
                magnifier(pen);
                break;
            case CLOSE:
                pen.line(6, 6, 18, 18);
                pen.line(18, 6, 6, 18);
                break;
            case GRID:
                pen.box(3, 3, 11, 11, 1);
                pen.box(13, 3, 21, 11, 1);
                pen.box(3, 13, 11, 21, 1);
                pen.box(13, 13, 21, 21, 1);
                break;
            case LAYERS:
                pen.line(12, 3, 21, 8);
                pen.line(3, 8, 12, 3);
                pen.line(3, 8, 12, 13);
                pen.line(21, 8, 12, 13);
                pen.line(3, 14, 12, 19);
                pen.line(21, 14, 12, 19);
                break;
            case GAUGE:
                pen.arc(12, 14, 8, 180, 360);
                pen.line(12, 14, 17, 9);
                pen.dot(12, 14);
                break;
            case USER:
                pen.circle(12, 8, 4);
                pen.arc(12, 22, 8, 180, 360);
                break;
            case EXTERNAL:
                pen.line(11, 5, 5, 5);
                pen.line(5, 5, 5, 19);
                pen.line(5, 19, 19, 19);
                pen.line(19, 19, 19, 13);
                pen.line(11, 13, 20, 4);
                pen.line(14, 4, 20, 4);
                pen.line(20, 4, 20, 10);
                break;
            case STETHOSCOPE:
                pen.line(2, 12, 8, 12);
                pen.line(8, 12, 10, 6);
                pen.line(10, 6, 14, 18);
                pen.line(14, 18, 16, 12);
                pen.line(16, 12, 22, 12);
                break;
            case SLIDERS:
                pen.line(3, 7, 21, 7);
                pen.line(3, 17, 21, 17);
                pen.dot(9, 7);
                pen.dot(16, 17);
                break;
            case STAR:
                star(pen, false);
                break;
            case STAR_FILLED:
                star(pen, true);
                break;
            case CHEVRON_RIGHT:
                pen.line(9, 5, 16, 12);
                pen.line(16, 12, 9, 19);
                break;
            case CHEVRON_DOWN:
                pen.line(5, 9, 12, 16);
                pen.line(12, 16, 19, 9);
                break;
            case CHEVRON_LEFT:
                pen.line(15, 5, 8, 12);
                pen.line(8, 12, 15, 19);
                break;
            case MOVE:
                pen.line(12, 3, 12, 21);
                pen.line(3, 12, 21, 12);
                pen.line(12, 3, 9, 6);
                pen.line(12, 3, 15, 6);
                pen.line(12, 21, 9, 18);
                pen.line(12, 21, 15, 18);
                pen.line(3, 12, 6, 9);
                pen.line(3, 12, 6, 15);
                pen.line(21, 12, 18, 9);
                pen.line(21, 12, 18, 15);
                break;
            case RESET:
                pen.arc(12, 12, 8, 40, 320);
                pen.line(12, 4, 12, 8);
                pen.line(12, 4, 16, 6);
                break;
            case CHECK:
                pen.line(5, 12, 10, 17);
                pen.line(10, 17, 19, 7);
                break;
            case KEYBOARD:
                pen.box(2, 6, 22, 18);
                pen.dot(6, 10);
                pen.dot(10, 10);
                pen.dot(14, 10);
                pen.dot(18, 10);
                pen.line(7, 14, 17, 14);
                break;
            case MOUSE:
                pen.box(7, 2, 17, 22, 5);
                pen.line(12, 6, 12, 10);
                break;
            case COMPASS:
                pen.circle(12, 12, 9);
                pen.line(15, 9, 9, 15);
                pen.line(9, 15, 15, 9);
                pen.dot(12, 12);
                break;
            case CLOCK:
                pen.circle(12, 12, 9);
                pen.line(12, 7, 12, 12);
                pen.line(12, 12, 16, 14);
                break;
            case SIGNAL:
                pen.fillRect(4, 16, 7, 20);
                pen.fillRect(9, 12, 12, 20);
                pen.fillRect(14, 8, 17, 20);
                pen.fillRect(19, 4, 22, 20);
                break;
            case SHIELD:
                pen.line(12, 3, 20, 6);
                pen.line(4, 6, 12, 3);
                pen.line(20, 6, 20, 12);
                pen.line(4, 6, 4, 12);
                pen.line(4, 12, 12, 21);
                pen.line(20, 12, 12, 21);
                break;
            case FLASK:
                pen.line(9, 3, 9, 10);
                pen.line(15, 3, 15, 10);
                pen.line(9, 10, 5, 19);
                pen.line(15, 10, 19, 19);
                pen.line(5, 19, 19, 19);
                pen.line(8, 3, 16, 3);
                break;
            case PACKAGE:
                pen.line(12, 3, 21, 8);
                pen.line(3, 8, 12, 3);
                pen.line(21, 8, 21, 17);
                pen.line(3, 8, 3, 17);
                pen.line(3, 17, 12, 22);
                pen.line(21, 17, 12, 22);
                pen.line(12, 12, 12, 22);
                pen.line(3, 8, 12, 12);
                pen.line(21, 8, 12, 12);
                break;
            case CROSSHAIR:
                pen.circle(12, 12, 8);
                pen.line(12, 1, 12, 6);
                pen.line(12, 18, 12, 23);
                pen.line(1, 12, 6, 12);
                pen.line(18, 12, 23, 12);
                break;
            case MAGNIFIER:
                magnifier(pen);
                break;
            case RUN:
                pen.dot(14, 4);
                pen.line(14, 8, 9, 13);
                pen.line(9, 13, 13, 16);
                pen.line(13, 16, 12, 21);
                pen.line(9, 13, 4, 14);
                pen.line(13, 16, 18, 19);
                break;
            case SPARK:
            default:
                pen.line(13, 2, 5, 13);
                pen.line(5, 13, 12, 13);
                pen.line(12, 13, 11, 22);
                pen.line(11, 22, 19, 11);
                pen.line(19, 11, 12, 11);
                pen.line(12, 11, 13, 2);
                break;
        }
    }

    private static void magnifier(Pen pen) {
        pen.circle(10, 10, 7);
        pen.line(15, 15, 21, 21);
    }

    private static void star(Pen pen, boolean filled) {
        float[] xs = new float[10];
        float[] ys = new float[10];
        for (int point = 0; point < 10; point++) {
            double angle = Math.toRadians(-90 + point * 36);
            float radius = (point % 2 == 0) ? 9.5f : 4.2f;
            xs[point] = 12f + (float) Math.cos(angle) * radius;
            ys[point] = 12f + (float) Math.sin(angle) * radius;
        }
        Path2D.Float star = new Path2D.Float();
        star.moveTo(xs[0], ys[0]);
        for (int point = 1; point < 10; point++) {
            star.lineTo(xs[point], ys[point]);
        }
        star.closePath();
        if (filled) {
            pen.fills.append(star, false);
        } else {
            pen.strokes.append(star, false);
        }
    }

}
