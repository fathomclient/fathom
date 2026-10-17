package net.fathom.optimizations.core.ui.render;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fathom.optimizations.generated.SharedIconPaths;

/**
 * Fathom's icons as vector geometry on their 24-unit grid, parsed once.
 *
 * <p>The drawings are the generated {@link SharedIconPaths} -- the same SVG path
 * data the launcher's icon family and the GL renderer's rasterised atlas use --
 * so a vector backend draws the same icons rather than a lookalike set.
 *
 * <h2>Three kinds of ink</h2>
 *
 * <ul>
 *   <li><b>Strokes</b> -- almost everything. A command list (move, line, cubic,
 *       quad, close) stroked with round caps and joins. Strokes need no stencil.
 *   <li><b>Dots</b> -- Lucide draws a dot as a 0.01-unit line with a round cap.
 *       A tessellator drops a segment that short, so those become explicit
 *       circles here.
 *   <li><b>Fills</b> -- the rare solid icon, flattened to polygons with a
 *       convexity flag, because a concave fill needs either a stencil or
 *       triangulation and the backend has to know which.
 * </ul>
 *
 * <p>Parsed lazily per icon and cached for the life of the process: each icon
 * is a few hundred floats.
 */
public final class IconGeometry {

    /** The grid every path is written on. */
    public static final float GRID = 24f;
    /** Lucide's stroke, in grid units: the SOURCE geometry's pen, not the drawn one. */
    public static final float STROKE = 2f;

    /**
     * The drawn pen, in logical units, at EVERY icon size and in both renderers.
     *
     * <p>The launcher's {@code svg.lucide} rule: one 1.5 line whether the icon
     * is 12, 16 or 20. Scaling Lucide's 2/24 with the box gave a 14 icon a 1.17
     * line and a 20 icon a 1.67 one, and the GL and NanoVG backends disagreed
     * about which of the two rules applied -- so one glyph had three weights.
     */
    public static final float STROKE_DP = 1.5f;

    public static final int MOVE = 0;
    public static final int LINE = 1;
    public static final int CUBIC = 2;
    public static final int QUAD = 3;
    public static final int CLOSE = 4;

    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z]|[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+\\.?)(?:[eE][-+]?[0-9]+)?");
    private static final float DOT_LENGTH = 0.05f;
    private static final int CURVE_STEPS = 8;

    private static final EnumMap<Icon, IconGeometry> CACHE = new EnumMap<Icon, IconGeometry>(Icon.class);

    private final float[] strokes;
    private final float[] dots;
    private final float[][] fills;
    private final boolean[] convex;

    private IconGeometry(float[] strokes, float[] dots, float[][] fills, boolean[] convex) {
        this.strokes = strokes;
        this.dots = dots;
        this.fills = fills;
        this.convex = convex;
    }

    /** The geometry for an icon. Render thread; parsed on first use. */
    public static IconGeometry of(Icon icon) {
        IconGeometry found = CACHE.get(icon);
        if (found == null) {
            String source = SharedIconPaths.path(icon);
            found = parse(source == null ? "" : source, SharedIconPaths.filled(icon));
            CACHE.put(icon, found);
        }
        return found;
    }

    /**
     * Stroke commands: an op code followed by its points, repeated. MOVE and
     * LINE take (x, y); CUBIC takes (c1x, c1y, c2x, c2y, x, y); QUAD takes
     * (cx, cy, x, y); CLOSE takes nothing.
     */
    public float[] strokes() {
        return strokes;
    }

    /** Dot centres, (x, y) pairs. Radius is half the stroke. */
    public float[] dots() {
        return dots;
    }

    /** Filled polygons, each (x, y) pairs. */
    public int fillCount() {
        return fills.length;
    }

    public float[] fill(int index) {
        return fills[index];
    }

    public boolean fillConvex(int index) {
        return convex[index];
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    /** Visible for tests. */
    public static IconGeometry parse(String source, boolean filled) {
        Builder out = new Builder(filled);
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        int at = 0;
        char command = 0;
        char previous = 0;
        float x = 0f;
        float y = 0f;
        float startX = 0f;
        float startY = 0f;
        float cubicX = 0f;
        float cubicY = 0f;
        float quadX = 0f;
        float quadY = 0f;

        while (at < tokens.size()) {
            String token = tokens.get(at);
            if (isCommand(token)) {
                command = token.charAt(0);
                at++;
            } else if (command == 0) {
                throw new IllegalArgumentException("SVG path has data before a command: " + source);
            }
            boolean relative = Character.isLowerCase(command);
            switch (Character.toUpperCase(command)) {
                case 'M': {
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x;
                        ny += y;
                    }
                    out.move(nx, ny);
                    x = startX = nx;
                    y = startY = ny;
                    previous = 'M';
                    command = relative ? 'l' : 'L';
                    break;
                }
                case 'L': {
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x;
                        ny += y;
                    }
                    out.line(x, y, nx, ny);
                    x = nx;
                    y = ny;
                    previous = 'L';
                    break;
                }
                case 'H': {
                    float nx = value(tokens, at++);
                    if (relative) {
                        nx += x;
                    }
                    out.line(x, y, nx, y);
                    x = nx;
                    previous = 'H';
                    break;
                }
                case 'V': {
                    float ny = value(tokens, at++);
                    if (relative) {
                        ny += y;
                    }
                    out.line(x, y, x, ny);
                    y = ny;
                    previous = 'V';
                    break;
                }
                case 'C': {
                    float x1 = value(tokens, at++);
                    float y1 = value(tokens, at++);
                    float x2 = value(tokens, at++);
                    float y2 = value(tokens, at++);
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        x1 += x;
                        y1 += y;
                        x2 += x;
                        y2 += y;
                        nx += x;
                        ny += y;
                    }
                    out.cubic(x, y, x1, y1, x2, y2, nx, ny);
                    cubicX = x2;
                    cubicY = y2;
                    x = nx;
                    y = ny;
                    previous = 'C';
                    break;
                }
                case 'S': {
                    boolean smooth = previous == 'C' || previous == 'S';
                    float x1 = smooth ? x * 2f - cubicX : x;
                    float y1 = smooth ? y * 2f - cubicY : y;
                    float x2 = value(tokens, at++);
                    float y2 = value(tokens, at++);
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        x2 += x;
                        y2 += y;
                        nx += x;
                        ny += y;
                    }
                    out.cubic(x, y, x1, y1, x2, y2, nx, ny);
                    cubicX = x2;
                    cubicY = y2;
                    x = nx;
                    y = ny;
                    previous = 'S';
                    break;
                }
                case 'Q': {
                    float qx = value(tokens, at++);
                    float qy = value(tokens, at++);
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        qx += x;
                        qy += y;
                        nx += x;
                        ny += y;
                    }
                    out.quad(x, y, qx, qy, nx, ny);
                    quadX = qx;
                    quadY = qy;
                    x = nx;
                    y = ny;
                    previous = 'Q';
                    break;
                }
                case 'T': {
                    boolean smooth = previous == 'Q' || previous == 'T';
                    float qx = smooth ? x * 2f - quadX : x;
                    float qy = smooth ? y * 2f - quadY : y;
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x;
                        ny += y;
                    }
                    out.quad(x, y, qx, qy, nx, ny);
                    quadX = qx;
                    quadY = qy;
                    x = nx;
                    y = ny;
                    previous = 'T';
                    break;
                }
                case 'A': {
                    float rx = value(tokens, at++);
                    float ry = value(tokens, at++);
                    float rotation = value(tokens, at++);
                    boolean large = value(tokens, at++) != 0f;
                    boolean sweep = value(tokens, at++) != 0f;
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x;
                        ny += y;
                    }
                    arc(out, x, y, rx, ry, rotation, large, sweep, nx, ny);
                    x = nx;
                    y = ny;
                    previous = 'A';
                    break;
                }
                case 'Z':
                    out.close();
                    x = startX;
                    y = startY;
                    previous = 'Z';
                    command = 0;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported SVG command " + command);
            }
        }
        return out.build();
    }

    private static boolean isCommand(String token) {
        return token.length() == 1 && Character.isLetter(token.charAt(0));
    }

    private static float value(List<String> tokens, int at) {
        if (at >= tokens.size() || isCommand(tokens.get(at))) {
            throw new IllegalArgumentException("Missing SVG path value");
        }
        return Float.parseFloat(tokens.get(at));
    }

    /** The SVG endpoint arc as cubic curves. */
    private static void arc(Builder out, float x0, float y0, float rawRx, float rawRy,
            float rotation, boolean large, boolean sweep, float x1, float y1) {
        double rx = Math.abs(rawRx);
        double ry = Math.abs(rawRy);
        if (rx < 0.0001 || ry < 0.0001 || (x0 == x1 && y0 == y1)) {
            if (x0 != x1 || y0 != y1) {
                out.line(x0, y0, x1, y1);
            }
            return;
        }
        double phi = Math.toRadians(rotation % 360f);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x1) / 2d;
        double dy = (y0 - y1) / 2d;
        double xp = cos * dx + sin * dy;
        double yp = -sin * dx + cos * dy;
        double lambda = xp * xp / (rx * rx) + yp * yp / (ry * ry);
        if (lambda > 1d) {
            double grow = Math.sqrt(lambda);
            rx *= grow;
            ry *= grow;
        }
        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double denominator = rx2 * yp * yp + ry2 * xp * xp;
        double numerator = Math.max(0d, rx2 * ry2 - denominator);
        double coefficient = denominator == 0d ? 0d : Math.sqrt(numerator / denominator);
        if (large == sweep) {
            coefficient = -coefficient;
        }
        double cxp = coefficient * (rx * yp / ry);
        double cyp = coefficient * (-ry * xp / rx);
        double cx = cos * cxp - sin * cyp + (x0 + x1) / 2d;
        double cy = sin * cxp + cos * cyp + (y0 + y1) / 2d;
        double start = angle(1d, 0d, (xp - cxp) / rx, (yp - cyp) / ry);
        double delta = angle((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry);
        if (!sweep && delta > 0d) {
            delta -= Math.PI * 2d;
        }
        if (sweep && delta < 0d) {
            delta += Math.PI * 2d;
        }
        int segments = Math.max(1, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2d)));
        double step = delta / segments;
        double px = x0;
        double py = y0;
        for (int segment = 0; segment < segments; segment++) {
            double a = start + step * segment;
            double b = a + step;
            double alpha = 4d / 3d * Math.tan((b - a) / 4d);
            double ax = -rx * Math.sin(a);
            double ay = ry * Math.cos(a);
            double bx = -rx * Math.sin(b);
            double by = ry * Math.cos(b);
            double ex = cx + cos * rx * Math.cos(b) - sin * ry * Math.sin(b);
            double ey = cy + sin * rx * Math.cos(b) + cos * ry * Math.sin(b);
            out.cubic((float) px, (float) py,
                    (float) (px + alpha * (cos * ax - sin * ay)), (float) (py + alpha * (sin * ax + cos * ay)),
                    (float) (ex - alpha * (cos * bx - sin * by)), (float) (ey - alpha * (sin * bx + cos * by)),
                    (float) ex, (float) ey);
            px = ex;
            py = ey;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }

    // -----------------------------------------------------------------------

    private static final class Builder {
        private final boolean filled;
        private final FloatList strokes = new FloatList();
        private final FloatList dots = new FloatList();
        private final List<float[]> fills = new ArrayList<float[]>();
        private final FloatList polygon = new FloatList();

        /** A move not yet written, so a lone dot segment can replace it. */
        private boolean pendingMove;
        private float moveX;
        private float moveY;

        Builder(boolean filled) {
            this.filled = filled;
        }

        void move(float x, float y) {
            if (filled) {
                finishPolygon();
                polygon.add(x);
                polygon.add(y);
                return;
            }
            pendingMove = true;
            moveX = x;
            moveY = y;
        }

        void line(float x0, float y0, float x1, float y1) {
            if (filled) {
                polygon.add(x1);
                polygon.add(y1);
                return;
            }
            float dx = x1 - x0;
            float dy = y1 - y0;
            if (pendingMove && dx * dx + dy * dy < DOT_LENGTH * DOT_LENGTH) {
                dots.add((x0 + x1) / 2f);
                dots.add((y0 + y1) / 2f);
                pendingMove = false;
                return;
            }
            flushMove();
            strokes.add(LINE);
            strokes.add(x1);
            strokes.add(y1);
        }

        void cubic(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            if (filled) {
                for (int step = 1; step <= CURVE_STEPS; step++) {
                    float t = step / (float) CURVE_STEPS;
                    float u = 1f - t;
                    polygon.add(u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3);
                    polygon.add(u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3);
                }
                return;
            }
            flushMove();
            strokes.add(CUBIC);
            strokes.add(x1);
            strokes.add(y1);
            strokes.add(x2);
            strokes.add(y2);
            strokes.add(x3);
            strokes.add(y3);
        }

        void quad(float x0, float y0, float cx, float cy, float x1, float y1) {
            if (filled) {
                for (int step = 1; step <= CURVE_STEPS; step++) {
                    float t = step / (float) CURVE_STEPS;
                    float u = 1f - t;
                    polygon.add(u * u * x0 + 2f * u * t * cx + t * t * x1);
                    polygon.add(u * u * y0 + 2f * u * t * cy + t * t * y1);
                }
                return;
            }
            flushMove();
            strokes.add(QUAD);
            strokes.add(cx);
            strokes.add(cy);
            strokes.add(x1);
            strokes.add(y1);
        }

        void close() {
            if (filled) {
                finishPolygon();
                return;
            }
            if (pendingMove) {
                pendingMove = false;
                return;
            }
            strokes.add(CLOSE);
        }

        private void flushMove() {
            if (pendingMove) {
                strokes.add(MOVE);
                strokes.add(moveX);
                strokes.add(moveY);
                pendingMove = false;
            }
        }

        private void finishPolygon() {
            if (polygon.size() >= 6) {
                fills.add(polygon.toArray());
            }
            polygon.clear();
        }

        IconGeometry build() {
            finishPolygon();
            float[][] polygons = fills.toArray(new float[0][]);
            boolean[] convex = new boolean[polygons.length];
            for (int index = 0; index < polygons.length; index++) {
                convex[index] = convex(polygons[index]);
            }
            return new IconGeometry(strokes.toArray(), dots.toArray(), polygons, convex);
        }
    }

    /** Whether every turn of a closed polygon goes the same way. */
    static boolean convex(float[] points) {
        int count = points.length / 2;
        int sign = 0;
        for (int i = 0; i < count; i++) {
            float ax = points[i * 2];
            float ay = points[i * 2 + 1];
            float bx = points[((i + 1) % count) * 2];
            float by = points[((i + 1) % count) * 2 + 1];
            float cx = points[((i + 2) % count) * 2];
            float cy = points[((i + 2) % count) * 2 + 1];
            float cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
            if (Math.abs(cross) < 1e-5f) {
                continue;
            }
            int turn = cross > 0f ? 1 : -1;
            if (sign == 0) {
                sign = turn;
            } else if (turn != sign) {
                return false;
            }
        }
        return true;
    }

    private static final class FloatList {
        private float[] values = new float[64];
        private int size;

        void add(float value) {
            if (size == values.length) {
                float[] grown = new float[size * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        void clear() {
            size = 0;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(values, 0, out, 0, size);
            return out;
        }
    }
}
