package net.fathom.optimizations.core.ui.render;

import net.fathom.optimizations.generated.FathomTheme;

/**
 * A piece of type: a size in logical units and a weight.
 *
 * <h2>The scale, from the launcher</h2>
 *
 * <p>Five sizes and three weights, which is the whole typographic system. A
 * sixth size is how a hierarchy stops meaning anything: if everything can be
 * emphasised, nothing is.
 *
 * <p>The sizes are the launcher's, in the same units — 11 and 12 for metadata,
 * 13 for body, 14 for a row's title, 19 for a section heading, 22 for a number
 * that is the point of its panel. The two halves are supposed to look like one
 * product, and a 15px body next to a 14px body is the kind of difference nobody
 * can name and everybody can feel.
 */
public final class Type {

    /** The three weights the interface uses. Geist has nine; three is a system. */
    public enum Weight {
        REGULAR,
        MEDIUM,
        SEMIBOLD
    }

    /** Metadata: badges, units, counts, the quiet line under a control. */
    public static final Type CAPTION = new Type(FathomTheme.TYPE_META, Weight.REGULAR);
    /** Secondary text: descriptions, hints. */
    public static final Type SMALL = new Type(FathomTheme.TYPE_CAPTION, Weight.REGULAR);
    /** Body. The default, and most of the interface. */
    public static final Type BODY = new Type(FathomTheme.TYPE_BODY, Weight.REGULAR);
    /** Body, emphasised: a control's label, a selected tab. */
    public static final Type BODY_STRONG = new Type(FathomTheme.TYPE_BODY, Weight.MEDIUM);
    /** The name of a row in a list — the thing the eye lands on first. */
    public static final Type ROW_TITLE = new Type(FathomTheme.TYPE_BODY_LG, Weight.MEDIUM);
    /** Small upper-case group labels. */
    public static final Type LABEL = new Type(FathomTheme.TYPE_META, Weight.MEDIUM);
    /** The header's product name. */
    public static final Type TITLE = new Type(FathomTheme.TYPE_ROW, Weight.SEMIBOLD);
    /** The heading at the top of a page. */
    public static final Type SECTION = new Type(FathomTheme.TYPE_SECTION, Weight.SEMIBOLD);
    /** A number that is the point of the panel it is on. */
    public static final Type DISPLAY = new Type(FathomTheme.TYPE_PAGE, Weight.SEMIBOLD);

    private final float size;
    private final Weight weight;

    public Type(float size, Weight weight) {
        if (size <= 0f) {
            throw new IllegalArgumentException("type has to have a size");
        }
        this.size = size;
        this.weight = weight == null ? Weight.REGULAR : weight;
    }

    /** The same type at another size. */
    public Type at(float newSize) {
        return new Type(newSize, weight);
    }

    /** The same size at another weight. */
    public Type weighing(Weight newWeight) {
        return new Type(size, newWeight);
    }

    /** In logical units. */
    public float size() {
        return size;
    }

    public Weight weight() {
        return weight;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Type)) {
            return false;
        }
        Type that = (Type) other;
        return that.size == size && that.weight == weight;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(size) * 31 + weight.hashCode();
    }

    @Override
    public String toString() {
        return "Type[" + size + " " + weight + "]";
    }
}
