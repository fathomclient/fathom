package net.fathom.optimizations.core.hud;

/**
 * One thing the HUD can draw.
 *
 * <p>Metadata only: no rendering, no Minecraft types, no state. The drawing lives in
 * {@code net.fathom.optimizations.fabric.hud}, and keeping the description here means the
 * launcher and the in-game mod list can both enumerate widgets without either of them
 * touching a renderer.
 *
 * <p>There is deliberately no "is this on" here. A widget is drawn when the
 * module that owns it is on, and that is the only switch — see
 * {@code ModuleRegistry.widgetAllowed}. This class answers what a widget is and
 * where it starts.
 */
public final class Widget {

    private final String id;
    private final String name;
    private final String summary;
    private final String category;
    private final Sensitivity sensitivity;
    private final Placement defaultPlacement;

    private Widget(
            String id,
            String name,
            String summary,
            String category,
            Sensitivity sensitivity,
            Placement defaultPlacement) {
        this.id = require(id, "id");
        this.name = require(name, "name");
        this.summary = require(summary, "summary");
        this.category = require(category, "category");
        this.sensitivity = sensitivity;
        this.defaultPlacement = defaultPlacement;
    }

    public static Widget of(
            String id,
            String name,
            String summary,
            String category,
            Sensitivity sensitivity,
            Placement defaultPlacement) {
        return new Widget(id, name, summary, category, sensitivity, defaultPlacement);
    }

    private static String require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("widget " + what + " must not be empty");
        }
        return value;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String summary() {
        return summary;
    }

    /** The grouping the in-game hub lists this under. */
    public String category() {
        return category;
    }

    public Sensitivity sensitivity() {
        return sensitivity;
    }

    /** Where it sits before anybody moves it. */
    public Placement defaultPlacement() {
        return defaultPlacement;
    }

    /** Whether a search box query matches this widget. */
    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        return name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || summary.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || category.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || id.contains(needle);
    }

    @Override
    public String toString() {
        return id;
    }
}
