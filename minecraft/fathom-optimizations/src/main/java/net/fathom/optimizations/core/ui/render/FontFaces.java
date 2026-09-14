package net.fathom.optimizations.core.ui.render;

/**
 * Which font file is which {@link Type.Weight}: the one place that says so.
 *
 * <p>Components name a {@link Type}; they never name a file. A renderer loads
 * these once per graphics context and falls back when one is missing (see
 * {@link #fallbackOrder}).
 */
public final class FontFaces {

    /** Where the families live on the mod's classpath. */
    public static final String ROOT = "/assets/fathom/font/";

    private FontFaces() {}

    /** The classpath resource for a weight. */
    public static String resource(Type.Weight weight) {
        switch (weight) {
            case MEDIUM:
                return ROOT + "Geist-Medium.ttf";
            case SEMIBOLD:
                return ROOT + "Geist-SemiBold.ttf";
            case REGULAR:
            default:
                return ROOT + "Geist-Regular.ttf";
        }
    }

    /**
     * The weights to try, in order, when this one is missing: the nearest
     * heavier-or-lighter weight first, so a missing SemiBold reads as Medium
     * rather than Regular.
     */
    public static Type.Weight[] fallbackOrder(Type.Weight weight) {
        switch (weight) {
            case SEMIBOLD:
                return new Type.Weight[] {Type.Weight.MEDIUM, Type.Weight.REGULAR};
            case MEDIUM:
                return new Type.Weight[] {Type.Weight.SEMIBOLD, Type.Weight.REGULAR};
            case REGULAR:
            default:
                return new Type.Weight[] {Type.Weight.MEDIUM, Type.Weight.SEMIBOLD};
        }
    }
}
