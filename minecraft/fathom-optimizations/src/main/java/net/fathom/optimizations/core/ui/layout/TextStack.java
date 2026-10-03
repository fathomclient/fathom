package net.fathom.optimizations.core.ui.layout;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Where text sits vertically: the one rule, and a cursor for lines stacked
 * top to bottom.
 *
 * <h2>The rule</h2>
 *
 * <p>A line of type is centred in its box by the face's own ascent and
 * descent: the baseline is {@code top + (height + ascent - descent) / 2}. It is
 * what CSS does with a line box, so a label lands where the launcher puts the
 * same label, and it is the same rule for a 36dp button, a 20dp line of a
 * card and a 12dp caption -- two labels in two equal boxes always line up,
 * whatever their sizes. {@link #baseline} is the only implementation; every
 * other "where does this text go" in the interface calls it.
 *
 * <h2>Stacking</h2>
 *
 * <p>Lines of a block -- an eyebrow over a title over a detail -- each get a
 * line box (the type's size times its line height, as the launcher's CSS
 * sets it) and gaps between them in dp. Measuring the block and drawing it
 * walk the same boxes, so the height a layout reserved is the height the
 * text takes. Replaces per-line fudges ({@code y + ascent + 2f},
 * {@code y + 22f + ascent}) that only lined up at the one size they were
 * tuned at.
 *
 * <p>One instance per screen, reused every frame: no allocation.
 */
public final class TextStack {

    /** The line height the launcher's body text uses, as a multiple of the size. */
    public static final float BODY_LINE = 1.4f;

    private Renderer ui;
    private float y;

    /** Start a block whose first line box begins at {@code top}. */
    public TextStack begin(Renderer renderer, float top) {
        this.ui = renderer;
        this.y = top;
        return this;
    }

    /**
     * The baseline of the next line, in a box {@code size * lineHeight} tall,
     * and move past it.
     */
    public float line(Type type, float lineHeight) {
        float box = lineBox(type, lineHeight);
        float baseline = baseline(ui, y, box, type);
        y += box;
        return baseline;
    }

    /**
     * The same, with the box sized for {@code box} and the line centred by the
     * metrics of {@code face}: for a face held at a legible minimum size
     * inside the box its role reserved.
     */
    public float line(Type box, float lineHeight, Type face) {
        float height = lineBox(box, lineHeight);
        float baseline = baseline(ui, y, height, face);
        y += height;
        return baseline;
    }

    /** Move down by a gap, in dp. */
    public TextStack gap(float dp) {
        y += dp;
        return this;
    }

    /** Where the next line box would begin. */
    public float y() {
        return y;
    }

    /** A line box as the launcher's CSS sets it: size times line height, whole dp. */
    public static float lineBox(Type type, float lineHeight) {
        return Math.round(type.size() * lineHeight);
    }

    /** The baseline that centres a line of {@code type} in a box {@code height} tall at {@code top}. */
    public static float baseline(Renderer ui, float top, float height, Type type) {
        return top + (height + ui.ascent(type) - ui.descent(type)) / 2f;
    }

    /** The same, for a box. */
    public static float baseline(Renderer ui, Rect box, Type type) {
        return baseline(ui, box.y, box.height, type);
    }

    /** The baseline that centres a line of {@code type} on the line {@code centreY}. */
    public static float centredOn(Renderer ui, float centreY, Type type) {
        return centreY + (ui.ascent(type) - ui.descent(type)) / 2f;
    }

    /**
     * The baseline of a line whose own line box -- the face's line height --
     * starts at {@code top}: text "with its top at y".
     */
    public static float firstLine(Renderer ui, float top, Type type) {
        return baseline(ui, top, ui.lineHeight(type), type);
    }
}
