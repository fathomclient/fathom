package net.fathom.optimizations.fabric.hud;

/**
 * The plate behind a HUD widget.
 *
 * <h2>Why this exists rather than one {@code fill} call</h2>
 *
 * <p>Because the corner radius and the drop shadow are declared settings, and a
 * declared setting that nothing draws is the defect this milestone is about.
 * {@code DrawableHelper.fill} draws a rectangle and nothing else, so a radius
 * has to be built out of rectangles here — one span per row through the corner
 * bands, the middle drawn as a single quad.
 *
 * <h2>Why rows and not a texture</h2>
 *
 * <p>A radius runs to eight pixels, so the corner bands are at most eight spans
 * each and the whole plate is at most seventeen fills. Uploading a nine-slice
 * texture would be faster in the abstract and slower in practice: it would mean
 * a texture bind per widget, which is the one thing the batched renderer spends
 * its time avoiding.
 */
final class Plate {

    private Plate() {}

    /**
     * Draw a plate.
     *
     * @param argb the fill colour, alpha included. Nothing is drawn when it is
     *     fully transparent, so switching the background off costs nothing.
     * @param radius corner radius in widget pixels, clamped to half the smaller side
     * @param shadow whether to lay a soft edge underneath
     */
    static void draw(int x, int y, int width, int height, int argb, int radius, boolean shadow) {
        if (width <= 0 || height <= 0 || (argb >>> 24) == 0) {
            return;
        }
        // A plate with a radius is up to seventeen spans; one draw, not seventeen.
        // Opening the batch flushes anything Fathom's renderer queued first: the
        // plate draws through Minecraft's own Tessellator, and so does Fathom's
        // renderer, and starting one that is already started is an exception in
        // the middle of a frame. Inside a caller's batch that flush already ran.
        boolean batched = QuadBatch.isOpen();
        if (!batched) {
            QuadBatch.begin();
        }
        try {
            drawSpans(x, y, width, height, argb, radius, shadow);
        } finally {
            if (!batched) {
                QuadBatch.end();
            }
        }
    }

    /**
     * An outline following the plate's own rounded corners.
     *
     * <p>Drawn row by row as the difference between the plate and the plate
     * inset by the border width, so the ring is exactly as round as the fill
     * beside it, never overlaps itself (which would show as dark corners at
     * anything under full opacity) and needs no texture.
     */
    static void border(int x, int y, int width, int height, int radius, int thickness, int argb) {
        if (width <= 0 || height <= 0 || thickness <= 0 || (argb >>> 24) == 0) {
            return;
        }
        boolean batched = QuadBatch.isOpen();
        if (!batched) {
            QuadBatch.begin();
        }
        try {
            int t = Math.min(thickness, Math.min(width, height) / 2);
            int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
            if (r == 0) {
                // The common case, and the one drawn per key by Keystrokes:
                // four spans, not one per row. A square border rasterised row
                // by row is thirty rectangles to draw four.
                QuadBatch.rect(x, y, x + width, y + t, argb);
                QuadBatch.rect(x, y + height - t, x + width, y + height, argb);
                QuadBatch.rect(x, y + t, x + t, y + height - t, argb);
                QuadBatch.rect(x + width - t, y + t, x + width, y + height - t, argb);
                return;
            }
            // Rounded: the corner bands need a span per row, but the straight
            // middle is two tall rectangles however tall the widget is.
            int innerRadius = Math.max(0, r - t);
            int innerHeight = height - t * 2;
            // Rows that are inside a corner, or inside the top and bottom
            // edges, are drawn one at a time; everything between them is two
            // tall rectangles however tall the widget is.
            int bandRows = Math.min(height / 2, Math.max(r, t));
            for (int row = 0; row < bandRows; row++) {
                band(x, y + row, width, row, height, r, t, innerRadius, innerHeight, argb);
                band(x, y + height - 1 - row, width, height - 1 - row, height, r, t,
                        innerRadius, innerHeight, argb);
            }
            if (height - bandRows * 2 > 0) {
                QuadBatch.rect(x, y + bandRows, x + t, y + height - bandRows, argb);
                QuadBatch.rect(x + width - t, y + bandRows, x + width, y + height - bandRows, argb);
            }
        } finally {
            if (!batched) {
                QuadBatch.end();
            }
        }
    }

    /** One row through a rounded corner: the ring between the outer and inner curves. */
    private static void band(int x, int screenY, int width, int row, int height, int radius,
            int thickness, int innerRadius, int innerHeight, int argb) {
        int outer = inset(radius, row, height);
        int innerRow = row - thickness;
        if (innerRow < 0 || innerRow >= innerHeight || width - thickness * 2 <= 0) {
            QuadBatch.rect(x + outer, screenY, x + width - outer, screenY + 1, argb);
            return;
        }
        int inner = thickness + inset(innerRadius, innerRow, innerHeight);
        if (inner > outer) {
            QuadBatch.rect(x + outer, screenY, x + inner, screenY + 1, argb);
            QuadBatch.rect(x + width - inner, screenY, x + width - outer, screenY + 1, argb);
        }
    }

    /** How far in a rounded rectangle of this radius starts on a given row. */
    private static int inset(int radius, int row, int height) {
        if (radius <= 0) {
            return 0;
        }
        int fromEdge = row < radius ? row : (row >= height - radius ? height - 1 - row : -1);
        if (fromEdge < 0) {
            return 0;
        }
        int dy = radius - fromEdge;
        return radius - (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
    }

    private static void drawSpans(int x, int y, int width, int height, int argb, int radius, boolean shadow) {
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));

        if (shadow) {
            // Three rings, each fainter and one pixel further out. Enough to lift
            // the plate off a bright background, cheap enough to not think about.
            int alpha = argb >>> 24;
            for (int ring = 3; ring >= 1; ring--) {
                int ringAlpha = Math.max(1, alpha / (ring * 6));
                rounded(x - ring, y - ring, width + ring * 2, height + ring * 2, r + ring,
                        (ringAlpha << 24));
            }
        }
        rounded(x, y, width, height, r, argb);
    }

    private static void rounded(int x, int y, int width, int height, int radius, int argb) {
        if (radius <= 0) {
            QuadBatch.rect(x, y, x + width, y + height, argb);
            return;
        }
        // The straight middle, full width.
        QuadBatch.rect(x, y + radius, x + width, y + height - radius, argb);

        // The two corner bands, one span per row. `inset` is how far in the fill
        // starts on that row: the horizontal distance from the circle's centre
        // to its edge at that height.
        for (int row = 0; row < radius; row++) {
            int dy = radius - row;
            int inset = radius - (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            QuadBatch.rect(x + inset, y + row, x + width - inset, y + row + 1, argb);
            QuadBatch.rect(
                    x + inset, y + height - row - 1, x + width - inset, y + height - row, argb);
        }
    }
}
