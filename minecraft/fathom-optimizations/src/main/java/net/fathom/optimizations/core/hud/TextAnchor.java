package net.fathom.optimizations.core.hud;

/**
 * Which edge of a widget's text stays put as the text changes length.
 *
 * <p>Separate from {@link Anchor}, which says which part of the <em>screen</em> a
 * widget is measured from. {@link #AUTO} is the behaviour every layout had before
 * this existed: the text follows the screen anchor, so a widget on the right
 * edge is right-aligned and one on the left is left-aligned. Saved layouts
 * therefore migrate as they were, and only a widget someone chooses otherwise
 * for changes.
 */
public enum TextAnchor {
    AUTO("Auto", -1f),
    LEFT("Left", 0f),
    CENTER("Centre", 0.5f),
    RIGHT("Right", 1f);

    private final String label;
    private final float fraction;

    TextAnchor(String label, float fraction) {
        this.label = label;
        this.fraction = fraction;
    }

    public String label() {
        return label;
    }

    /** 0 for left, 0.5 for centre, 1 for right; for {@link #AUTO}, the screen anchor's. */
    public float fraction(Anchor screen) {
        return this == AUTO ? screen.horizontal() : fraction;
    }

    /** By saved name, case and separators ignored; {@link #AUTO} for anything unknown or missing. */
    public static TextAnchor parse(String name) {
        if (name == null) {
            return AUTO;
        }
        String wanted = name.trim();
        for (TextAnchor each : values()) {
            if (each.name().equalsIgnoreCase(wanted)) {
                return each;
            }
        }
        return "centre".equalsIgnoreCase(wanted) || "middle".equalsIgnoreCase(wanted) ? CENTER : AUTO;
    }

    /** The name written to a file: lower case. */
    public String saved() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
