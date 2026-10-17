package net.fathom.optimizations.fabric.hud;

import java.util.Arrays;

/**
 * The last few text widths a widget measured, by string object and font.
 *
 * <p>A widget's strings are the same objects frame after frame until their text
 * changes, and most widgets measured each of them several times a frame — for
 * the size, the box and the alignment. Every measurement goes through the font
 * metrics hook, which is cheap but not free; this asks it once per string.
 *
 * <p>Cleared when the font changes and when resources reload, since a resource
 * pack can change a glyph's width without changing the font object.
 */
final class MeasuredWidths {

    /** Moved on by a resource reload. */
    private static int generation;

    private final String[] text;
    private final float[] width;
    private HudFont font;
    private int cursor;
    private int seenGeneration;

    MeasuredWidths(int size) {
        text = new String[size];
        width = new float[size];
    }

    static void reloaded() {
        generation++;
    }

    float of(HudFont measuring, String value) {
        if (measuring != font || seenGeneration != generation) {
            Arrays.fill(text, null);
            font = measuring;
            seenGeneration = generation;
        }
        for (int index = 0; index < text.length; index++) {
            if (text[index] == value) {
                return width[index];
            }
        }
        float measured = measuring.width(value);
        text[cursor] = value;
        width[cursor] = measured;
        cursor = (cursor + 1) % text.length;
        return measured;
    }
}
