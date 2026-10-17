package net.fathom.optimizations.tools.font;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-channel signed distance fields for glyph outlines: Chlumsky's MSDF,
 * reimplemented for the offline atlas tool.
 *
 * <h2>Why multi-channel</h2>
 *
 * <p>A single-channel distance field rounds every corner it is magnified
 * past, because the distance to a corner is a circle. Three channels, each
 * holding the distance to a different subset of the edges, keep a corner
 * sharp: the shader takes the median, and where two edges meet at a corner
 * the median follows whichever edge is nearer. The fourth channel is the
 * true distance, for effects and for robustness.
 *
 * <h2>What is done here, in order</h2>
 *
 * <ol>
 *   <li><b>Overlaps removed.</b> Geist's outlines come from a variable font
 *       and overlap freely (41 printable ASCII glyphs). A distance to an edge
 *       hidden inside another contour is a phantom edge; the outline is
 *       unioned with {@link Area} first, so every edge is a real boundary.
 *   <li><b>Edges coloured</b> so that the two edges at each corner never share
 *       all channels ({@link #colourEdges}, msdfgen's "simple" colouring).
 *   <li><b>Per-channel pseudo-distance</b>, with endpoints extended along
 *       their tangents so a channel's field stays straight past a corner.
 *   <li><b>Clash correction</b>: texels whose channels disagree so much with
 *       a neighbour that bilinear filtering would create a false edge between
 *       them are flattened to their median.
 *   <li><b>Sign correction</b>: every texel's inside/outside is checked
 *       against the outline itself (non-zero fill) and flipped if the field
 *       disagrees, so no texel can be on the wrong side of the shape.
 * </ol>
 */
public final class Msdf {

    static final int RED = 1;
    static final int GREEN = 2;
    static final int BLUE = 4;
    static final int YELLOW = RED | GREEN;
    static final int MAGENTA = RED | BLUE;
    static final int CYAN = GREEN | BLUE;
    static final int WHITE = RED | GREEN | BLUE;

    /** Corners sharper than this (radians from straight) get differently coloured edges. */
    private static final double ANGLE_THRESHOLD = 3.0;

    private Msdf() {}

    // -----------------------------------------------------------------------
    // Shape
    // -----------------------------------------------------------------------

    /** A segment of an outline: a line or a quadratic, with its channel mask. */
    static final class Edge {
        final double[] p;
        int colour = WHITE;

        Edge(double... points) {
            this.p = points;
        }

        boolean quadratic() {
            return p.length == 6;
        }

        double x(double t) {
            if (!quadratic()) {
                return p[0] + (p[2] - p[0]) * t;
            }
            double u = 1 - t;
            return u * u * p[0] + 2 * u * t * p[2] + t * t * p[4];
        }

        double y(double t) {
            if (!quadratic()) {
                return p[1] + (p[3] - p[1]) * t;
            }
            double u = 1 - t;
            return u * u * p[1] + 2 * u * t * p[3] + t * t * p[5];
        }

        /** The tangent at t (not normalised). */
        double[] direction(double t) {
            if (!quadratic()) {
                return new double[] {p[2] - p[0], p[3] - p[1]};
            }
            double dx = (p[2] - p[0]) * (1 - t) + (p[4] - p[2]) * t;
            double dy = (p[3] - p[1]) * (1 - t) + (p[5] - p[3]) * t;
            if (dx == 0 && dy == 0) {
                return new double[] {p[4] - p[0], p[5] - p[1]};
            }
            return new double[] {dx, dy};
        }

        Edge[] thirds() {
            if (!quadratic()) {
                double[] a = {p[0], p[1]};
                double[] b = {x(1 / 3.0), y(1 / 3.0)};
                double[] c = {x(2 / 3.0), y(2 / 3.0)};
                double[] d = {p[2], p[3]};
                return new Edge[] {new Edge(a[0], a[1], b[0], b[1]), new Edge(b[0], b[1], c[0], c[1]),
                    new Edge(c[0], c[1], d[0], d[1])};
            }
            double[] p0 = {p[0], p[1]};
            double[] p1 = {p[2], p[3]};
            double[] p2 = {p[4], p[5]};
            double[] m1 = {x(1 / 3.0), y(1 / 3.0)};
            double[] m2 = {x(2 / 3.0), y(2 / 3.0)};
            return new Edge[] {
                new Edge(p0[0], p0[1], mix(p0[0], p1[0], 1 / 3.0), mix(p0[1], p1[1], 1 / 3.0), m1[0], m1[1]),
                new Edge(m1[0], m1[1],
                        mix(mix(p0[0], p1[0], 5 / 9.0), mix(p1[0], p2[0], 4 / 9.0), .5),
                        mix(mix(p0[1], p1[1], 5 / 9.0), mix(p1[1], p2[1], 4 / 9.0), .5), m2[0], m2[1]),
                new Edge(m2[0], m2[1], mix(p1[0], p2[0], 2 / 3.0), mix(p1[1], p2[1], 2 / 3.0), p2[0], p2[1])};
        }

        /**
         * Signed distance from (x, y): {@code out[0]} the distance (positive
         * inside, for an outline wound like TrueType's), {@code out[1]} the
         * tie-breaking "dot", {@code out[2]} the nearest parameter.
         */
        void signedDistance(double px, double py, double[] out) {
            if (!quadratic()) {
                double aqx = px - p[0];
                double aqy = py - p[1];
                double abx = p[2] - p[0];
                double aby = p[3] - p[1];
                double param = (aqx * abx + aqy * aby) / (abx * abx + aby * aby);
                double eqx = (param > .5 ? p[2] : p[0]) - px;
                double eqy = (param > .5 ? p[3] : p[1]) - py;
                double endpoint = Math.hypot(eqx, eqy);
                if (param > 0 && param < 1) {
                    double length = Math.hypot(abx, aby);
                    // Orthonormal with polarity false: (y, -x).
                    double ortho = (aby / length) * aqx + (-abx / length) * aqy;
                    if (Math.abs(ortho) < endpoint) {
                        out[0] = ortho;
                        out[1] = 0;
                        out[2] = param;
                        return;
                    }
                }
                out[0] = nonZeroSign(aqx * aby - aqy * abx) * endpoint;
                double abLength = Math.hypot(abx, aby);
                double eqLength = endpoint == 0 ? 1 : endpoint;
                out[1] = Math.abs((abx / abLength) * (eqx / eqLength) + (aby / abLength) * (eqy / eqLength));
                out[2] = param;
                return;
            }
            double qax = p[0] - px;
            double qay = p[1] - py;
            double abx = p[2] - p[0];
            double aby = p[3] - p[1];
            double brx = p[4] - p[2] - abx;
            double bry = p[5] - p[3] - aby;
            double a = brx * brx + bry * bry;
            double b = 3 * (abx * brx + aby * bry);
            double c = 2 * (abx * abx + aby * aby) + (qax * brx + qay * bry);
            double d = qax * abx + qay * aby;
            double[] roots = new double[3];
            int solutions = solveCubic(roots, a, b, c, d);

            double[] dir0 = direction(0);
            double minDistance = nonZeroSign(dir0[0] * qay - dir0[1] * qax) * Math.hypot(qax, qay);
            double param = -(qax * dir0[0] + qay * dir0[1]) / (dir0[0] * dir0[0] + dir0[1] * dir0[1]);
            {
                double[] dir1 = direction(1);
                double ex = p[4] - px;
                double ey = p[5] - py;
                double distance = Math.hypot(ex, ey);
                if (distance < Math.abs(minDistance)) {
                    minDistance = nonZeroSign(dir1[0] * ey - dir1[1] * ex) * distance;
                    param = ((px - p[2]) * dir1[0] + (py - p[3]) * dir1[1])
                            / (dir1[0] * dir1[0] + dir1[1] * dir1[1]);
                }
            }
            for (int i = 0; i < solutions; i++) {
                double t = roots[i];
                if (t > 0 && t < 1) {
                    double qex = qax + 2 * t * abx + t * t * brx;
                    double qey = qay + 2 * t * aby + t * t * bry;
                    double distance = Math.hypot(qex, qey);
                    if (distance <= Math.abs(minDistance)) {
                        double tx = abx + t * brx;
                        double ty = aby + t * bry;
                        minDistance = nonZeroSign(tx * qey - ty * qex) * distance;
                        param = t;
                    }
                }
            }
            out[0] = minDistance;
            out[2] = param;
            if (param >= 0 && param <= 1) {
                out[1] = 0;
            } else if (param < .5) {
                double[] n = normalise(dir0);
                double ql = Math.hypot(qax, qay);
                out[1] = ql == 0 ? 0 : Math.abs(n[0] * qax / ql + n[1] * qay / ql);
            } else {
                double[] n = normalise(direction(1));
                double ex = p[4] - px;
                double ey = p[5] - py;
                double el = Math.hypot(ex, ey);
                out[1] = el == 0 ? 0 : Math.abs(n[0] * ex / el + n[1] * ey / el);
            }
        }

        /** Extend the edge's ends along their tangents (msdfgen's pseudo-distance). */
        void toPseudoDistance(double[] distance, double px, double py) {
            double param = distance[2];
            if (param < 0) {
                double[] dir = normalise(direction(0));
                double aqx = px - x(0);
                double aqy = py - y(0);
                double ts = aqx * dir[0] + aqy * dir[1];
                if (ts < 0) {
                    double pseudo = aqx * dir[1] - aqy * dir[0];
                    if (Math.abs(pseudo) <= Math.abs(distance[0])) {
                        distance[0] = pseudo;
                        distance[1] = 0;
                    }
                }
            } else if (param > 1) {
                double[] dir = normalise(direction(1));
                double bqx = px - x(1);
                double bqy = py - y(1);
                double ts = bqx * dir[0] + bqy * dir[1];
                if (ts > 0) {
                    double pseudo = bqx * dir[1] - bqy * dir[0];
                    if (Math.abs(pseudo) <= Math.abs(distance[0])) {
                        distance[0] = pseudo;
                        distance[1] = 0;
                    }
                }
            }
        }
    }

    /** An outline as closed contours of edges, overlaps already removed. */
    static final class Outline {
        final List<List<Edge>> contours = new ArrayList<List<Edge>>();
        final Area area;
        final Rectangle2D bounds;

        Outline(Shape glyph) {
            area = new Area(glyph);
            bounds = area.getBounds2D();
            PathIterator it = area.getPathIterator(null);
            double[] c = new double[6];
            List<Edge> contour = null;
            double sx = 0;
            double sy = 0;
            double lx = 0;
            double ly = 0;
            while (!it.isDone()) {
                int type = it.currentSegment(c);
                switch (type) {
                    case PathIterator.SEG_MOVETO:
                        if (contour != null) {
                            close(contour, lx, ly, sx, sy);
                        }
                        contour = new ArrayList<Edge>();
                        sx = lx = c[0];
                        sy = ly = c[1];
                        break;
                    case PathIterator.SEG_LINETO:
                        if (c[0] != lx || c[1] != ly) {
                            contour.add(new Edge(lx, ly, c[0], c[1]));
                        }
                        lx = c[0];
                        ly = c[1];
                        break;
                    case PathIterator.SEG_QUADTO:
                        if (c[2] != lx || c[3] != ly) {
                            contour.add(new Edge(lx, ly, c[0], c[1], c[2], c[3]));
                        }
                        lx = c[2];
                        ly = c[3];
                        break;
                    case PathIterator.SEG_CUBICTO:
                        // Area keeps a quadratic outline quadratic; a cubic
                        // here would be a font this tool was not built for.
                        throw new IllegalStateException("cubic segment in a TrueType outline");
                    case PathIterator.SEG_CLOSE:
                        if (contour != null) {
                            close(contour, lx, ly, sx, sy);
                            lx = sx;
                            ly = sy;
                        }
                        contour = null;
                        break;
                    default:
                        break;
                }
                it.next();
            }
            if (contour != null) {
                close(contour, lx, ly, sx, sy);
            }
            // Orientation: positive distance must be inside. Area's winding is
            // its own; measure the outline's total signed area and reverse
            // every contour if it came out the other way round.
            if (signedArea() < 0) {
                for (List<Edge> each : contours) {
                    reverse(each);
                }
            }
        }

        private void close(List<Edge> contour, double lx, double ly, double sx, double sy) {
            if (lx != sx || ly != sy) {
                contour.add(new Edge(lx, ly, sx, sy));
            }
            if (!contour.isEmpty()) {
                contours.add(contour);
            }
        }

        /** Shoelace over the control polygons; the sign is what matters. */
        double signedArea() {
            double total = 0;
            for (List<Edge> contour : contours) {
                for (Edge edge : contour) {
                    int n = edge.p.length / 2;
                    for (int i = 0; i < n - 1; i++) {
                        total += edge.p[i * 2] * edge.p[i * 2 + 3] - edge.p[i * 2 + 2] * edge.p[i * 2 + 1];
                    }
                }
            }
            // Font units, y up: TrueType's outer contours are clockwise, so a
            // correctly wound glyph has negative shoelace area here.
            return -total;
        }

        private static void reverse(List<Edge> contour) {
            java.util.Collections.reverse(contour);
            for (int index = 0; index < contour.size(); index++) {
                Edge edge = contour.get(index);
                double[] p = edge.p;
                double[] r = new double[p.length];
                int n = p.length / 2;
                for (int i = 0; i < n; i++) {
                    r[i * 2] = p[(n - 1 - i) * 2];
                    r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1];
                }
                Edge flipped = new Edge(r);
                flipped.colour = edge.colour;
                contour.set(index, flipped);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Edge colouring (msdfgen's edgeColoringSimple)
    // -----------------------------------------------------------------------

    static void colourEdges(Outline outline, long seedValue) {
        long[] seed = {seedValue};
        double crossThreshold = Math.sin(ANGLE_THRESHOLD);
        int[] colour = {0};
        for (List<Edge> contour : outline.contours) {
            List<Integer> corners = new ArrayList<Integer>();
            if (!contour.isEmpty()) {
                double[] previous = contour.get(contour.size() - 1).direction(1);
                for (int index = 0; index < contour.size(); index++) {
                    double[] next = contour.get(index).direction(0);
                    if (isCorner(normalise(previous), normalise(next), crossThreshold)) {
                        corners.add(Integer.valueOf(index));
                    }
                    previous = contour.get(index).direction(1);
                }
            }
            if (corners.isEmpty()) {
                // A smooth contour: one colour is enough.
                switchColour(colour, seed, 0);
                for (Edge edge : contour) {
                    edge.colour = WHITE;
                }
            } else if (corners.size() == 1) {
                // A teardrop: three colours round the one corner.
                int[] colours = new int[3];
                int[] c = {WHITE};
                switchColour(c, seed, 0);
                colours[0] = c[0];
                colours[1] = WHITE;
                c[0] = colours[0];
                switchColour(c, seed, 0);
                colours[2] = c[0];
                int corner = corners.get(0).intValue();
                if (contour.size() >= 3) {
                    int m = contour.size();
                    for (int i = 0; i < m; i++) {
                        contour.get((corner + i) % m).colour = colours[1 + trichotomy(i, m)];
                    }
                } else {
                    List<Edge> parts = new ArrayList<Edge>();
                    if (contour.size() == 1) {
                        Edge[] thirds = contour.get(0).thirds();
                        thirds[0].colour = colours[0];
                        thirds[1].colour = colours[1];
                        thirds[2].colour = colours[2];
                        for (Edge e : thirds) {
                            parts.add(e);
                        }
                    } else {
                        Edge[] a = contour.get(0).thirds();
                        Edge[] b = contour.get(1).thirds();
                        a[0].colour = a[1].colour = colours[0];
                        a[2].colour = b[0].colour = colours[1];
                        b[1].colour = b[2].colour = colours[2];
                        for (Edge e : a) {
                            parts.add(e);
                        }
                        for (Edge e : b) {
                            parts.add(e);
                        }
                    }
                    contour.clear();
                    contour.addAll(parts);
                }
            } else {
                int cornerCount = corners.size();
                int spline = 0;
                int start = corners.get(0).intValue();
                int m = contour.size();
                switchColour(colour, seed, 0);
                int initial = colour[0];
                for (int i = 0; i < m; i++) {
                    int index = (start + i) % m;
                    if (spline + 1 < cornerCount && corners.get(spline + 1).intValue() == index) {
                        spline++;
                        switchColour(colour, seed, spline == cornerCount - 1 ? initial : 0);
                    }
                    contour.get(index).colour = colour[0];
                }
            }
        }
    }

    private static int trichotomy(int position, int n) {
        return (int) (3 + 2.875 * position / (n - 1) - 1.4375 + .5) - 3;
    }

    private static boolean isCorner(double[] a, double[] b, double crossThreshold) {
        return a[0] * b[0] + a[1] * b[1] <= 0 || Math.abs(a[0] * b[1] - a[1] * b[0]) > crossThreshold;
    }

    private static void switchColour(int[] colour, long[] seed, int banned) {
        int combined = colour[0] & banned;
        if (combined == RED || combined == GREEN || combined == BLUE) {
            colour[0] = combined ^ WHITE;
            return;
        }
        if (colour[0] == 0 || colour[0] == WHITE) {
            int[] start = {CYAN, MAGENTA, YELLOW};
            colour[0] = start[(int) (seed[0] % 3)];
            seed[0] /= 3;
            return;
        }
        int shifted = colour[0] << (1 + (int) (seed[0] & 1));
        colour[0] = (shifted | shifted >> 3) & WHITE;
        seed[0] >>= 1;
    }

    // -----------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------

    /**
     * Render an outline's field into {@code width x height} RGBA floats (0..1,
     * 0.5 on the edge, above it inside).
     *
     * @param scale  pixels per outline unit
     * @param left   the outline x at the bitmap's left edge
     * @param top    the outline y at the bitmap's top edge (y up)
     * @param range  the distance, in pixels, that spans 0..1
     */
    static float[] generate(Outline outline, int width, int height, double scale, double left, double top,
            double range) {
        float[] out = new float[width * height * 4];
        double[] sd = new double[3];
        double[] best = new double[3];
        for (int y = 0; y < height; y++) {
            double py = top - (y + 0.5) / scale;
            for (int x = 0; x < width; x++) {
                double px = left + (x + 0.5) / scale;
                double[] chosenR = null;
                double[] chosenG = null;
                double[] chosenB = null;
                Edge edgeR = null;
                Edge edgeG = null;
                Edge edgeB = null;
                double[] trueBest = {Double.MAX_VALUE, 1, 0};
                for (List<Edge> contour : outline.contours) {
                    for (Edge edge : contour) {
                        edge.signedDistance(px, py, sd);
                        if (less(sd, trueBest)) {
                            trueBest = sd.clone();
                        }
                        if ((edge.colour & RED) != 0 && (chosenR == null || less(sd, chosenR))) {
                            chosenR = sd.clone();
                            edgeR = edge;
                        }
                        if ((edge.colour & GREEN) != 0 && (chosenG == null || less(sd, chosenG))) {
                            chosenG = sd.clone();
                            edgeG = edge;
                        }
                        if ((edge.colour & BLUE) != 0 && (chosenB == null || less(sd, chosenB))) {
                            chosenB = sd.clone();
                            edgeB = edge;
                        }
                    }
                }
                int at = (y * width + x) * 4;
                out[at] = channel(edgeR, chosenR, px, py, scale, range);
                out[at + 1] = channel(edgeG, chosenG, px, py, scale, range);
                out[at + 2] = channel(edgeB, chosenB, px, py, scale, range);
                out[at + 3] = (float) (trueBest[0] == Double.MAX_VALUE ? 0
                        : clamp01(trueBest[0] * scale / range + 0.5));
            }
        }
        correctClashes(out, width, height, 1.001 / range);
        correctSigns(out, width, height, outline, scale, left, top);
        return out;
    }

    private static float channel(Edge edge, double[] distance, double px, double py, double scale, double range) {
        if (edge == null) {
            return 0f;
        }
        double[] d = distance.clone();
        edge.toPseudoDistance(d, px, py);
        return (float) clamp01(d[0] * scale / range + 0.5);
    }

    private static boolean less(double[] a, double[] b) {
        double fa = Math.abs(a[0]);
        double fb = Math.abs(b[0]);
        return fa < fb || (fa == fb && a[1] < b[1]);
    }

    /** msdfgen's legacy error correction: flatten texels that would interpolate into a false edge. */
    private static void correctClashes(float[] out, int width, int height, double threshold) {
        boolean[] clash = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                if ((x > 0 && clashes(out, at, at - 1, threshold))
                        || (x < width - 1 && clashes(out, at, at + 1, threshold))
                        || (y > 0 && clashes(out, at, at - width, threshold))
                        || (y < height - 1 && clashes(out, at, at + width, threshold))) {
                    clash[at] = true;
                }
            }
        }
        for (int at = 0; at < clash.length; at++) {
            if (clash[at]) {
                float m = median(out[at * 4], out[at * 4 + 1], out[at * 4 + 2]);
                out[at * 4] = out[at * 4 + 1] = out[at * 4 + 2] = m;
            }
        }
    }

    private static boolean clashes(float[] out, int a, int b, double threshold) {
        double a0 = out[a * 4];
        double a1 = out[a * 4 + 1];
        double a2 = out[a * 4 + 2];
        double b0 = out[b * 4];
        double b1 = out[b * 4 + 1];
        double b2 = out[b * 4 + 2];
        double t;
        // Sort so the pairs run from the largest difference to the smallest.
        if (Math.abs(b1 - a1) < Math.abs(b2 - a2)) {
            t = a1; a1 = a2; a2 = t;
            t = b1; b1 = b2; b2 = t;
        }
        if (Math.abs(b0 - a0) < Math.abs(b1 - a1)) {
            t = a0; a0 = a1; a1 = t;
            t = b0; b0 = b1; b1 = t;
            if (Math.abs(b1 - a1) < Math.abs(b2 - a2)) {
                t = a1; a1 = a2; a2 = t;
                t = b1; b1 = b2; b2 = t;
            }
        }
        return Math.abs(b1 - a1) >= threshold
                && !(b0 == b1 && b0 == b2)
                && Math.abs(a2 - .5) >= Math.abs(b2 - .5);
    }

    /** Inside means inside the outline, whatever the field says. */
    private static void correctSigns(float[] out, int width, int height, Outline outline,
            double scale, double left, double top) {
        for (int y = 0; y < height; y++) {
            double py = top - (y + 0.5) / scale;
            for (int x = 0; x < width; x++) {
                double px = left + (x + 0.5) / scale;
                int at = (y * width + x) * 4;
                boolean inside = outline.area.contains(px, py);
                float m = median(out[at], out[at + 1], out[at + 2]);
                if (m != 0.5f && (m > 0.5f) != inside) {
                    out[at] = 1f - out[at];
                    out[at + 1] = 1f - out[at + 1];
                    out[at + 2] = 1f - out[at + 2];
                }
                if (out[at + 3] != 0.5f && (out[at + 3] > 0.5f) != inside) {
                    out[at + 3] = 1f - out[at + 3];
                }
            }
        }
    }

    static float median(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    // -----------------------------------------------------------------------
    // Arithmetic
    // -----------------------------------------------------------------------

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int nonZeroSign(double v) {
        return v > 0 ? 1 : -1;
    }

    private static double[] normalise(double[] v) {
        double length = Math.hypot(v[0], v[1]);
        return length == 0 ? new double[] {0, 1} : new double[] {v[0] / length, v[1] / length};
    }

    private static int solveQuadratic(double[] x, double a, double b, double c) {
        if (a == 0 || Math.abs(b) > 1e12 * Math.abs(a)) {
            if (b == 0) {
                return c == 0 ? -1 : 0;
            }
            x[0] = -c / b;
            return 1;
        }
        double discriminant = b * b - 4 * a * c;
        if (discriminant > 0) {
            discriminant = Math.sqrt(discriminant);
            x[0] = (-b + discriminant) / (2 * a);
            x[1] = (-b - discriminant) / (2 * a);
            return 2;
        } else if (discriminant == 0) {
            x[0] = -b / (2 * a);
            return 1;
        }
        return 0;
    }

    private static int solveCubicNormed(double[] x, double a, double b, double c) {
        double a2 = a * a;
        double q = 1 / 9.0 * (a2 - 3 * b);
        double r = 1 / 54.0 * (a * (2 * a2 - 9 * b) + 27 * c);
        double r2 = r * r;
        double q3 = q * q * q;
        a *= 1 / 3.0;
        if (r2 < q3) {
            double t = r / Math.sqrt(q3);
            t = t < -1 ? -1 : (t > 1 ? 1 : t);
            t = Math.acos(t);
            q = -2 * Math.sqrt(q);
            x[0] = q * Math.cos(1 / 3.0 * t) - a;
            x[1] = q * Math.cos(1 / 3.0 * (t + 2 * Math.PI)) - a;
            x[2] = q * Math.cos(1 / 3.0 * (t - 2 * Math.PI)) - a;
            return 3;
        }
        double u = (r < 0 ? 1 : -1) * Math.pow(Math.abs(r) + Math.sqrt(r2 - q3), 1 / 3.0);
        double v = u == 0 ? 0 : q / u;
        x[0] = (u + v) - a;
        if (u == v || Math.abs(u - v) < 1e-12 * Math.abs(u + v)) {
            x[1] = -.5 * (u + v) - a;
            return 2;
        }
        return 1;
    }

    private static int solveCubic(double[] x, double a, double b, double c, double d) {
        if (a != 0) {
            double bn = b / a;
            if (Math.abs(bn) < 1e6) {
                return solveCubicNormed(x, bn, c / a, d / a);
            }
        }
        return solveQuadratic(x, b, c, d);
    }
}
