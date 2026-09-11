package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.gui.DrawableHelper;

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
        // Anything Fathom has queued goes out first. The plate draws through
        // Minecraft's own Tessellator, and so does Fathom's renderer; starting
        // one that is already started is an exception in the middle of a frame.
        // Keystrokes is the widget that makes this necessary — it alternates
        // plate, label, plate, label all the way down.
        net.fathom.optimizations.fabric.gfx.GlRenderer active =
                net.fathom.optimizations.fabric.gfx.Gfx.peek();
        if (active != null && active.inFrame()) {
            active.flush();
        }
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
            DrawableHelper.fill(x, y, x + width, y + height, argb);
            return;
        }
        // The straight middle, full width.
        DrawableHelper.fill(x, y + radius, x + width, y + height - radius, argb);

        // The two corner bands, one span per row. `inset` is how far in the fill
        // starts on that row: the horizontal distance from the circle's centre
        // to its edge at that height.
        for (int row = 0; row < radius; row++) {
            int dy = radius - row;
            int inset = radius - (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            DrawableHelper.fill(x + inset, y + row, x + width - inset, y + row + 1, argb);
            DrawableHelper.fill(
                    x + inset, y + height - row - 1, x + width - inset, y + height - row, argb);
        }
    }
}
