package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/** Draws the Aperture mark and the FATHOM wordmark from {@code /assets/brand}. */
public final class BrandMark {

    /** Geist's cap height, in em. Lockups align on the caps, not the ink box. */
    public static final float CAP_HEIGHT = 0.71f;

    /** Treatment A's tracking, in em — {@code assets/brand/wordmark.svg}. */
    public static final float WORDMARK_TRACKING = 0.30f;

    /** How much of a 64-unit mark is ink: the grid keeps 8 units clear each side. */
    public static final float INK_FRACTION = 48f / 64f;

    private static final String[] LETTERS = {"F", "A", "T", "H", "O", "M"};

    /**
     * Geist 600 pair kerning for FA, AT, TH, HO and OM, in thousandths of an em.
     *
     * <p>{@code GlyphPage} deliberately does not kern, and for interface text that
     * is right. For the wordmark it is not: FA and AT are a third of the tracking
     * gap, and without them the F and A visibly drift apart. These are the values
     * {@code build-wordmark.py} reads from GPOS, so the live lettering sets the
     * same way the outlined SVG does.
     */
    private static final int[] KERNING = {-83, -96, -22, -16, 9};

    private BrandMark() {}

    /**
     * Draw the two-colour Fathom mark without a raster intermediate.
     *
     * <p>The coordinates deliberately match {@code assets/brand/logo.svg}. The
     * renderer converts these logical rectangles directly to framebuffer
     * pixels, so the same source geometry stays sharp at every GUI scale.
     */
    public static void draw(Renderer ui, float x, float y, float size, int frame, int plane) {
        float unit = size / 64f;
        rect(ui, x, y, unit, 8f, 8f, 48f, 10f, frame);
        rect(ui, x, y, unit, 8f, 18f, 10f, 38f, frame);
        rect(ui, x, y, unit, 18f, 46f, 22f, 10f, frame);
        rect(ui, x, y, unit, 46f, 18f, 10f, 22f, frame);
        rect(ui, x, y, unit, 26f, 26f, 14f, 14f, plane);
    }

    /**
     * The mark on its hand-drawn 24-unit grid, for chrome-sized uses.
     *
     * <p>{@code assets/brand/small/mark-24.svg}, which is also what the launcher's
     * top bar draws. The 64-unit master scaled to 24 puts every bar on a
     * fractional pixel; this grid does not.
     */
    public static void drawSmall(Renderer ui, float x, float y, float size, int frame, int plane) {
        float unit = size / 24f;
        rect(ui, x, y, unit, 3f, 3f, 18f, 4f, frame);
        rect(ui, x, y, unit, 3f, 7f, 4f, 14f, frame);
        rect(ui, x, y, unit, 7f, 17f, 8f, 4f, frame);
        rect(ui, x, y, unit, 17f, 7f, 4f, 8f, frame);
        rect(ui, x, y, unit, 10f, 10f, 5f, 5f, plane);
    }

    /** The wordmark's advance width, without trailing tracking. */
    public static float wordmarkWidth(Renderer ui, Type type, float trackingEm) {
        float width = 0f;
        for (int index = 0; index < LETTERS.length; index++) {
            width += ui.measure(LETTERS[index], type);
            if (index < KERNING.length) {
                width += gap(type, trackingEm, index);
            }
        }
        return width;
    }

    /**
     * FATHOM as live Geist, tracked and kerned like the brand wordmark.
     *
     * @param trackingEm 0.30 for the brand lockup, 0.16 to match the launcher's top bar
     * @return the width drawn
     */
    public static float wordmark(
            Renderer ui, float x, float baselineY, Type type, float trackingEm, int colour) {
        float pen = x;
        for (int index = 0; index < LETTERS.length; index++) {
            pen += ui.text(LETTERS[index], pen, baselineY, type, colour);
            if (index < KERNING.length) {
                pen += gap(type, trackingEm, index);
            }
        }
        return pen - x;
    }

    private static float gap(Type type, float trackingEm, int pair) {
        return type.size() * (trackingEm + KERNING[pair] / 1000f);
    }

    private static void rect(
            Renderer ui, float x, float y, float unit,
            float left, float top, float width, float height, int colour) {
        ui.fill(
                x + left * unit,
                y + top * unit,
                x + (left + width) * unit,
                y + (top + height) * unit,
                colour);
    }
}
