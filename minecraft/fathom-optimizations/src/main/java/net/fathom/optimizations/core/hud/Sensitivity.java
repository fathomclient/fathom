package net.fathom.optimizations.core.hud;

/**
 * How a feature stands with respect to server rules.
 *
 * <p>{@code FATHOM_MODS_AND_LICENSING.md} asks for exactly this classification and for one
 * rule to follow from it: <em>do not automatically enable features known to violate major
 * server rules.</em> Encoding it as a type rather than as a comment is what lets
 * {@link Widget#onByDefault()} enforce the rule instead of restating it.
 *
 * <p>Nothing here is a promise that a given server permits a given widget. Server rules
 * are written by servers and change without telling us. What this expresses is Fathom's
 * own judgement about which side of the line a feature sits on, and the conservative
 * default that follows from it.
 */
public enum Sensitivity {
    /**
     * Shows the player information their own client already has, and gives no advantage
     * that watching the screen would not. FPS, a clock, your own coordinates.
     */
    SAFE("Safe", "Shows what your own client already knows."),

    /**
     * Legitimate on most servers and disallowed on some, or advantageous enough that a
     * server might reasonably object. Never on by default.
     */
    SERVER_SENSITIVE(
            "Server-sensitive",
            "Allowed on most servers and not all. Check the rules where you play."),

    /** Not finished, or not understood well enough to recommend. Never on by default. */
    EXPERIMENTAL("Experimental", "Not finished. May misbehave.");

    private final String label;
    private final String explanation;

    Sensitivity(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }

    /** Whether Fathom may switch a feature of this class on without being asked. */
    public boolean mayDefaultOn() {
        return this == SAFE;
    }
}
