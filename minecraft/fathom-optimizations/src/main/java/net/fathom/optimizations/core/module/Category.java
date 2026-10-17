package net.fathom.optimizations.core.module;

/**
 * How the hub groups features.
 *
 * <p>The set the roadmap names, and no more. Every extra category is another
 * tab a user has to scan past to find the thing they wanted, and the failure
 * mode of a long category list is that everything ends up in "Utility" anyway.
 *
 * <p>Order is the order the hub shows them in: the ones people open the menu for
 * first, then the ones they set up once.
 */
public enum Category {
    HUD("HUD", "Things drawn on your screen while you play."),
    PVP("PvP", "Aimed at fighting other players."),
    VISUAL("Visual", "Changes how the game looks."),
    UTILITY("Utility", "Small conveniences."),
    PERFORMANCE("Performance", "Trades something for frame rate."),
    EXPERIMENTAL("Experimental", "Not finished. May misbehave.");

    private final String label;
    private final String summary;

    Category(String label, String summary) {
        this.label = label;
        this.summary = summary;
    }

    public String label() {
        return label;
    }

    public String summary() {
        return summary;
    }

    /** Parse a stored or API-supplied name, defaulting rather than throwing. */
    public static Category of(String raw) {
        if (raw != null) {
            for (Category candidate : values()) {
                if (candidate.name().equalsIgnoreCase(raw) || candidate.label.equalsIgnoreCase(raw)) {
                    return candidate;
                }
            }
        }
        return UTILITY;
    }
}
