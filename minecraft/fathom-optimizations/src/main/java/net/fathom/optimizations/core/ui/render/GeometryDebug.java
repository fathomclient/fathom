package net.fathom.optimizations.core.ui.render;

import java.util.Locale;

/**
 * The in-game geometry debug overlay. DEVELOPER ONLY.
 *
 * <p>Off unless the JVM is started with {@code -Dfathom.debugGeometry=true}
 * (for example {@code gradle runClient -Dfathom.debugGeometry=true}); there is
 * no setting, key or menu that turns it on, so no player can. While a Fathom
 * screen draws, every rim, segment and icon records its box here; at the end of
 * the frame {@link #drawAndClear} outlines each one with:
 *
 * <ul>
 *   <li>its size in dp and in device pixels,
 *   <li>its radius, and a rim's width in device pixels,
 *   <li>an icon's box size,
 * </ul>
 *
 * <p>and a header with the UI scale. Anything outside the metric system --
 * a rim that is not one device pixel, a radius that is not 10, 12, 18 or a
 * pill, an icon that is not 12, 16 or 20 -- is drawn in the warning colour.
 * The launcher's twin is {@code desktop/src/lib/GeometryDebug.svelte}.
 */
public final class GeometryDebug {

    public static final boolean ENABLED = Boolean.getBoolean("fathom.debugGeometry");

    private static final int RIM = 0;
    private static final int SEGMENT = 1;
    private static final int ICON = 2;

    private static final int CAPACITY = 768;
    private static final float[] BOXES = new float[CAPACITY * 6];
    private static final int[] KINDS = new int[CAPACITY];
    private static int count;

    private static final int OK = 0xD000FFC8;
    private static final int OFF = 0xF0FF5A3C;
    private static final Type LABEL = new Type(9f, Type.Weight.MONO_REGULAR);

    private GeometryDebug() {}

    /** A rim was drawn: its box, radius and width (logical units). */
    public static void rim(Renderer ui, float left, float top, float right, float bottom,
            float radius, float thickness) {
        record(ui, RIM, left, top, right, bottom, radius, thickness);
    }

    /** A segment or row overlay was drawn. */
    public static void segment(Renderer ui, float left, float top, float right, float bottom, float radius) {
        record(ui, SEGMENT, left, top, right, bottom, radius, 0f);
    }

    /** An icon was drawn in a {@code size} box. */
    public static void icon(Renderer ui, float x, float y, float size) {
        record(ui, ICON, x, y, x + size, y + size, 0f, 0f);
    }

    private static void record(Renderer ui, int kind, float left, float top, float right, float bottom,
            float radius, float thickness) {
        if (!ENABLED || count >= CAPACITY) {
            return;
        }
        // Into frame coordinates: the overlay draws after every transform has
        // been popped. `localX` is affine, so two samples invert it.
        float ax = ui.localX(1f) - ui.localX(0f);
        float ay = ui.localY(1f) - ui.localY(0f);
        float bx = ui.localX(0f);
        float by = ui.localY(0f);
        if (ax == 0f || ay == 0f) {
            return;
        }
        int at = count * 6;
        BOXES[at] = (left - bx) / ax;
        BOXES[at + 1] = (top - by) / ay;
        BOXES[at + 2] = (right - bx) / ax;
        BOXES[at + 3] = (bottom - by) / ay;
        BOXES[at + 4] = radius / ax;
        BOXES[at + 5] = thickness / ax;
        KINDS[count] = kind;
        count++;
    }

    /** Draw everything recorded this frame, then forget it. Frame coordinates. */
    public static void drawAndClear(Renderer ui) {
        if (!ENABLED) {
            count = 0;
            return;
        }
        float scale = ui.scale();
        float px = ui.pixel();
        int drawn = count;
        count = 0;
        for (int index = 0; index < drawn; index++) {
            int at = index * 6;
            float l = BOXES[at];
            float t = BOXES[at + 1];
            float r = BOXES[at + 2];
            float b = BOXES[at + 3];
            float w = r - l;
            float h = b - t;
            float radius = RoundedGeometry.radius(BOXES[at + 4], w, h);
            boolean pill = radius > 0f && Math.abs(radius - Math.min(w, h) / 2f) < 0.51f;
            String label;
            boolean off;
            if (KINDS[index] == ICON) {
                off = !(near(w, 12f) || near(w, 16f) || near(w, 20f));
                label = String.format(Locale.ROOT, "icon %.0f", w);
            } else {
                float rimDevice = BOXES[at + 5] * scale;
                off = !(pill || near(radius, 0f) || near(radius, 10f) || near(radius, 12f) || near(radius, 18f))
                        || (KINDS[index] == RIM && Math.abs(rimDevice - 1f) > 0.05f);
                label = String.format(Locale.ROOT, "%.0fx%.0f dp %.0fx%.0f px r%s%s",
                        w, h, w * scale, h * scale, pill ? "pill" : String.format(Locale.ROOT, "%.0f", radius),
                        KINDS[index] == RIM ? String.format(Locale.ROOT, " rim %.2fpx", rimDevice) : "");
            }
            int colour = off ? OFF : OK;
            ui.outline(l, t, r, b, 0f, px, colour);
            float tw = ui.measure(label, LABEL);
            ui.fill(l, t, l + tw + 4f, t + ui.lineHeight(LABEL), 0xB0000000);
            ui.text(label, l + 2f, t + ui.ascent(LABEL), LABEL, colour);
        }
        String header = String.format(Locale.ROOT, "geometry  ui scale %.2f  1dp = %.2f device px  %d boxes",
                scale, scale, drawn);
        float hw = ui.measure(header, LABEL);
        ui.fill(4f, 4f, 12f + hw, 8f + ui.lineHeight(LABEL), 0xD0000000);
        ui.text(header, 8f, 6f + ui.ascent(LABEL), LABEL, OK);
    }

    private static boolean near(float value, float target) {
        return Math.abs(value - target) < 0.51f;
    }
}
