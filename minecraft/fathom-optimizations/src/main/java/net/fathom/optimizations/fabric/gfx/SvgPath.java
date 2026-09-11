package net.fathom.optimizations.fabric.gfx;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal SVG path reader used by the generated Lucide icon atlas. */
final class SvgPath {

    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z]|[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+\\.?)(?:[eE][-+]?[0-9]+)?");

    private SvgPath() {}

    static void append(String source, Path2D.Float path) {
        List<String> tokens = tokens(source);
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
            char upper = Character.toUpperCase(command);
            switch (upper) {
                case 'M': {
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x;
                        ny += y;
                    }
                    path.moveTo(nx, ny);
                    x = nx;
                    y = ny;
                    startX = x;
                    startY = y;
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
                    path.lineTo(nx, ny);
                    x = nx;
                    y = ny;
                    previous = 'L';
                    break;
                }
                case 'H': {
                    float nx = value(tokens, at++);
                    if (relative) nx += x;
                    path.lineTo(nx, y);
                    x = nx;
                    previous = 'H';
                    break;
                }
                case 'V': {
                    float ny = value(tokens, at++);
                    if (relative) ny += y;
                    path.lineTo(x, ny);
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
                        x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y;
                    }
                    path.curveTo(x1, y1, x2, y2, nx, ny);
                    cubicX = x2;
                    cubicY = y2;
                    x = nx;
                    y = ny;
                    previous = 'C';
                    break;
                }
                case 'S': {
                    float x1 = previous == 'C' || previous == 'S' ? x * 2f - cubicX : x;
                    float y1 = previous == 'C' || previous == 'S' ? y * 2f - cubicY : y;
                    float x2 = value(tokens, at++);
                    float y2 = value(tokens, at++);
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        x2 += x; y2 += y; nx += x; ny += y;
                    }
                    path.curveTo(x1, y1, x2, y2, nx, ny);
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
                        qx += x; qy += y; nx += x; ny += y;
                    }
                    path.quadTo(qx, qy, nx, ny);
                    quadX = qx;
                    quadY = qy;
                    x = nx;
                    y = ny;
                    previous = 'Q';
                    break;
                }
                case 'T': {
                    float qx = previous == 'Q' || previous == 'T' ? x * 2f - quadX : x;
                    float qy = previous == 'Q' || previous == 'T' ? y * 2f - quadY : y;
                    float nx = value(tokens, at++);
                    float ny = value(tokens, at++);
                    if (relative) {
                        nx += x; ny += y;
                    }
                    path.quadTo(qx, qy, nx, ny);
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
                        nx += x; ny += y;
                    }
                    arc(path, x, y, rx, ry, rotation, large, sweep, nx, ny);
                    x = nx;
                    y = ny;
                    previous = 'A';
                    break;
                }
                case 'Z':
                    path.closePath();
                    x = startX;
                    y = startY;
                    previous = 'Z';
                    command = 0;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported SVG command " + command);
            }
        }
    }

    private static List<String> tokens(String source) {
        ArrayList<String> out = new ArrayList<String>();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) out.add(matcher.group());
        return out;
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

    /** Convert the SVG endpoint form of an elliptical arc to cubic curves. */
    private static void arc(Path2D.Float path, float x0, float y0, float rawRx, float rawRy,
            float rotation, boolean large, boolean sweep, float x1, float y1) {
        double rx = Math.abs(rawRx);
        double ry = Math.abs(rawRy);
        if (rx < 0.0001 || ry < 0.0001 || (x0 == x1 && y0 == y1)) {
            if (x0 != x1 || y0 != y1) path.lineTo(x1, y1);
            return;
        }
        double phi = Math.toRadians(rotation % 360f);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x1) / 2d;
        double dy = (y0 - y1) / 2d;
        double xp = cos * dx + sin * dy;
        double yp = -sin * dx + cos * dy;
        double scale = xp * xp / (rx * rx) + yp * yp / (ry * ry);
        if (scale > 1d) {
            double grow = Math.sqrt(scale);
            rx *= grow;
            ry *= grow;
        }
        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double denominator = rx2 * yp * yp + ry2 * xp * xp;
        double numerator = Math.max(0d, rx2 * ry2 - denominator);
        double coefficient = denominator == 0d ? 0d : Math.sqrt(numerator / denominator);
        if (large == sweep) coefficient = -coefficient;
        double cxp = coefficient * (rx * yp / ry);
        double cyp = coefficient * (-ry * xp / rx);
        double cx = cos * cxp - sin * cyp + (x0 + x1) / 2d;
        double cy = sin * cxp + cos * cyp + (y0 + y1) / 2d;

        double start = angle(1d, 0d, (xp - cxp) / rx, (yp - cyp) / ry);
        double delta = angle((xp - cxp) / rx, (yp - cyp) / ry,
                (-xp - cxp) / rx, (-yp - cyp) / ry);
        if (!sweep && delta > 0d) delta -= Math.PI * 2d;
        if (sweep && delta < 0d) delta += Math.PI * 2d;
        int segments = Math.max(1, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2d)));
        double step = delta / segments;
        for (int segment = 0; segment < segments; segment++) {
            double a = start + step * segment;
            double b = a + step;
            double alpha = 4d / 3d * Math.tan((b - a) / 4d);
            double[] p0 = point(cx, cy, rx, ry, cos, sin, a);
            double[] p1 = point(cx, cy, rx, ry, cos, sin, b);
            double[] d0 = tangent(rx, ry, cos, sin, a);
            double[] d1 = tangent(rx, ry, cos, sin, b);
            path.curveTo(
                    (float) (p0[0] + alpha * d0[0]), (float) (p0[1] + alpha * d0[1]),
                    (float) (p1[0] - alpha * d1[0]), (float) (p1[1] - alpha * d1[1]),
                    (float) p1[0], (float) p1[1]);
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }

    private static double[] point(double cx, double cy, double rx, double ry,
            double cos, double sin, double angle) {
        double x = rx * Math.cos(angle);
        double y = ry * Math.sin(angle);
        return new double[] {cx + cos * x - sin * y, cy + sin * x + cos * y};
    }

    private static double[] tangent(double rx, double ry, double cos, double sin, double angle) {
        double x = -rx * Math.sin(angle);
        double y = ry * Math.cos(angle);
        return new double[] {cos * x - sin * y, sin * x + cos * y};
    }
}
