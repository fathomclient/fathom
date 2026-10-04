package net.fathom.optimizations.fabric.diag;

import java.util.Locale;

import org.lwjgl.input.Mouse;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.TextLeaf;
import net.fathom.optimizations.core.ui.render.Layer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.gfx.LaidOut;
import net.fathom.optimizations.fabric.gfx.SdfRenderer;

/**
 * The interface's debug view. Development only, off unless
 * {@code -Dfathom.uiDebug=true}; with it off this class is never called.
 *
 * <p>Drawn in {@link Layer#DEBUG}, over the frame it describes:
 *
 * <ul>
 *   <li><b>Bounds</b> of every box of the screen's layout, tinted by depth,
 *       and its <b>padding</b> as a translucent band inside it.
 *   <li>For every line of text: its line box, its <b>baseline</b> (cyan), the
 *       face's <b>ascender</b> (magenta) and <b>descender</b> (amber).
 *   <li><b>Clips</b> as pushed (orange) and every control's <b>hit target</b>
 *       (red), from {@code GeometryDebug}, with each rim and segment's size and
 *       <b>radius</b> labelled -- red where it is not one of the tokens.
 *   <li>The <b>layers</b>: how many primitives each z-layer carried last
 *       frame, and the frame's draw calls, bytes and blur passes.
 *   <li>An inspector for the box under the pointer: id, rectangle, padding.
 * </ul>
 */
public final class UiDebug {

    public static final boolean ENABLED = Boolean.getBoolean("fathom.uiDebug");

    private static final Type LABEL = new Type(10f, Type.Weight.MONO_REGULAR);
    private static final int[] DEPTH = {0x9070C8FF, 0x9080FF9C, 0x90FFD870, 0x90FF8AD8, 0x90A0A0FF, 0x90FFFFFF};
    private static final int PADDING = 0x2280FF9C;
    private static final int BASELINE = 0xE000E5FF;
    private static final int ASCENDER = 0xC0FF40FF;
    private static final int DESCENDER = 0xC0FFB000;
    private static final int LINE_BOX = 0x50FFFFFF;
    private static final int PANEL = 0xE0080A0C;
    private static final int INK = 0xFFEDEDED;

    private static Box hovered;

    private UiDebug() {}

    /** Draw the overlay for the current screen. */
    public static void draw(final Renderer ui, SdfRenderer compositor) {
        if (!ENABLED) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        final float px = ui.pixel();
        final float mouseX = Mouse.getX() / ui.scale();
        final float mouseY = (client.height - Mouse.getY() - 1) / ui.scale();
        hovered = null;
        if (client.currentScreen instanceof LaidOut) {
            Box root = ((LaidOut) client.currentScreen).layoutRoot();
            if (root != null) {
                root.visit(new Box.Visitor() {
                    @Override
                    public void box(Box box, int depth) {
                        Rect r = box.rect();
                        if (r.width <= 0f || r.height <= 0f) {
                            return;
                        }
                        Rect c = box.content();
                        if (c.x > r.x || c.y > r.y || c.right() < r.right() || c.bottom() < r.bottom()) {
                            band(ui, r, c);
                        }
                        ui.outline(r.x, r.y, r.right(), r.bottom(), 0f, px, DEPTH[Math.min(depth, DEPTH.length - 1)]);
                        if (box.measurer() instanceof TextLeaf) {
                            metrics(ui, (TextLeaf) box.measurer(), r, px);
                        }
                        if (r.contains(mouseX, mouseY)) {
                            hovered = box;
                        }
                    }
                });
            }
        }
        legend(ui, compositor);
        if (hovered != null) {
            inspector(ui, hovered, mouseX, mouseY);
        }
    }

    /** The padding: the box less its content, as four bands. */
    private static void band(Renderer ui, Rect outer, Rect inner) {
        ui.fill(outer.x, outer.y, outer.right(), inner.y, PADDING);
        ui.fill(outer.x, inner.bottom(), outer.right(), outer.bottom(), PADDING);
        ui.fill(outer.x, inner.y, inner.x, inner.bottom(), PADDING);
        ui.fill(inner.right(), inner.y, outer.right(), inner.bottom(), PADDING);
    }

    /** A line of text's line box, baseline, ascender and descender. */
    private static void metrics(Renderer ui, TextLeaf leaf, Rect r, float px) {
        Type face = leaf.face(ui);
        float baseline = leaf.baseline(ui);
        float ascender = baseline - ui.ascent(face);
        float descender = baseline + ui.descent(face);
        ui.outline(r.x, r.y, r.right(), r.bottom(), 0f, px, LINE_BOX);
        ui.fill(r.x, ascender, r.right(), ascender + px, ASCENDER);
        ui.fill(r.x, baseline, r.right(), baseline + px, BASELINE);
        ui.fill(r.x, descender, r.right(), descender + px, DESCENDER);
    }

    private static void legend(Renderer ui, SdfRenderer compositor) {
        if (compositor == null) {
            return;
        }
        SdfRenderer.Stats stats = compositor.stats();
        float lineHeight = ui.lineHeight(LABEL);
        float width = 230f;
        float rows = 3 + Layer.COUNT;
        float x = ui.width() - width - 8f;
        float y = ui.height() - rows * lineHeight - 16f;
        ui.rounded(x, y, x + width, y + rows * lineHeight + 8f, 6f, PANEL);
        float line = y + 4f + ui.ascent(LABEL);
        ui.text(String.format(Locale.ROOT, "draws %d  quads %d", stats.lastDraws, stats.lastQuads), x + 8f, line,
                LABEL, INK);
        line += lineHeight;
        ui.text(String.format(Locale.ROOT, "upload %.1f KB  blur passes %d", stats.lastBytes / 1024f,
                stats.lastBlurPasses), x + 8f, line, LABEL, INK);
        line += lineHeight;
        ui.text("layer         quads", x + 8f, line, LABEL, 0xFF8A93A0);
        for (int index = 0; index < Layer.COUNT; index++) {
            line += lineHeight;
            int quads = stats.lastLayerQuads[index];
            ui.text(String.format(Locale.ROOT, "%2d %-10s %5d", index * 10, Layer.name(index * 10), quads),
                    x + 8f, line, LABEL, quads > 0 ? INK : 0xFF6A717C);
        }
    }

    private static void inspector(Renderer ui, Box box, float x, float y) {
        Rect r = box.rect();
        String first = String.format(Locale.ROOT, "%s  %.1f,%.1f  %.1fx%.1f", box.id, r.x, r.y, r.width, r.height);
        String second = String.format(Locale.ROOT, "padding %.0f %.0f %.0f %.0f  gap %.0f%s",
                box.paddingLeft(), box.paddingTop(), box.paddingRight(), box.paddingBottom(), box.gapSize(),
                box.isClip() ? "  clips" : "");
        float lineHeight = ui.lineHeight(LABEL);
        float width = Math.max(ui.measure(first, LABEL), ui.measure(second, LABEL)) + 16f;
        float left = Math.min(x + 14f, ui.width() - width - 4f);
        float top = Math.min(y + 14f, ui.height() - lineHeight * 2f - 12f);
        ui.rounded(left, top, left + width, top + lineHeight * 2f + 8f, 6f, PANEL);
        ui.text(first, left + 8f, top + 4f + ui.ascent(LABEL), LABEL, INK);
        ui.text(second, left + 8f, top + 4f + lineHeight + ui.ascent(LABEL), LABEL, 0xFFBDBDBD);
        ui.outline(r.x, r.y, r.right(), r.bottom(), 0f, ui.pixel() * 2f, 0xFFFFFFFF);
    }
}
