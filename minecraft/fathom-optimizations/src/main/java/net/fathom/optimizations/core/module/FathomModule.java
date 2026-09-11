package net.fathom.optimizations.core.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fathom.optimizations.core.hud.Sensitivity;

/**
 * One thing a user can switch on, configure, and see the effect of.
 *
 * <h2>The unit the whole client is built from</h2>
 *
 * <p>Keystrokes, CPS, Zoom, the unfocused frame cap — fourteen small things that
 * would otherwise be fourteen unrelated mods with fourteen config files and
 * fourteen ideas about what a settings screen looks like. They are one type
 * instead, and everything that follows comes free: the hub can list them,
 * search them, group them, persist them and render their settings without
 * knowing what any individual one does.
 *
 * <p>That is also the whole of the mod-config API. A Fathom-compatible mod
 * registers a {@code FathomModule}, and it appears in the hub next to the
 * first-party ones, with the same rows, the same search and the same
 * persistence. There is no second path for our own features to take.
 *
 * <h2>What a feature owns</h2>
 *
 * <ul>
 *   <li><b>Settings</b> — its own configuration, rendered by the hub.
 *   <li><b>Widgets</b> — HUD elements it draws, by id. Enabling a feature
 *       enables its widgets and disabling it hides them, so a user who switches
 *       Keystrokes off does not then have to find its widget as well.
 *   <li><b>Keybinds</b> — keys it wants. Zoom is the reason this exists.
 *   <li><b>Optimizations</b> — registry ids it drives, for features whose whole
 *       implementation is a switch the performance module already reads.
 * </ul>
 *
 * <p>All four are optional and most features use one or two. A feature with none
 * of them does nothing, which {@link Builder#build} refuses to produce.
 */
public final class FathomModule {

    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final Sensitivity sensitivity;
    private final String owner;
    private final Icon icon;
    private final boolean defaultEnabled;
    private final List<String> widgets;
    private final List<String> optimizations;
    private final Map<String, Setting> settings;
    private final List<Keybind> keybinds;
    private final Restart restart;
    private final Implementation implementation;
    private final Compatibility compatibility;
    /**
     * What switching this on actually does, in one word, for the interface and
     * for the test that refuses an inert toggle.
     */
    private final String behaviour;

    private boolean enabled;
    private boolean favourite;

    /** Whether changing this takes effect now or at the next launch. */
    public enum Restart {
        /** Applies on the next frame. */
        LIVE,
        /** Stored now, applied when the game next starts. */
        REQUIRED
    }

    /**
     * The small glyph family the hub draws.
     *
     * <p>Named shapes rather than image files: the module ships no textures, so
     * every icon is drawn from primitives at whatever size is asked for. The set
     * is deliberately short — a feature that needs a fifteenth icon is usually a
     * feature that belongs in an existing category.
     */
    public enum Icon {
        GAUGE,
        KEYBOARD,
        MOUSE,
        COMPASS,
        CLOCK,
        SIGNAL,
        SHIELD,
        FLASK,
        PACKAGE,
        CROSSHAIR,
        MAGNIFIER,
        RUN,
        SPARK,
        GRID,
        SLIDERS
    }

    /** Lazily built by {@link #matches}; see {@code haystack()}. */
    private String haystack;

    private FathomModule(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.category = builder.category;
        this.sensitivity = builder.sensitivity;
        this.owner = builder.owner;
        this.icon = builder.icon;
        this.defaultEnabled = builder.defaultEnabled && builder.sensitivity.mayDefaultOn();
        this.widgets = Collections.unmodifiableList(new ArrayList<String>(builder.widgets));
        this.optimizations = Collections.unmodifiableList(new ArrayList<String>(builder.optimizations));
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<String, Setting>(builder.settings));
        this.keybinds = Collections.unmodifiableList(new ArrayList<Keybind>(builder.keybinds));
        this.restart = builder.restart;
        this.implementation = builder.implementation;
        this.compatibility = builder.compatibility;
        this.behaviour = builder.behaviour;
        this.enabled = this.defaultEnabled;
    }

    /** How finished this module is, and whether the interface may switch it. */
    public Implementation implementation() {
        return implementation;
    }

    /** What it needs before it can do anything. */
    public Compatibility compatibility() {
        return compatibility;
    }

    /**
     * The name of the behaviour this module drives, or empty when it drives a
     * widget or an optimization instead.
     *
     * <p>Read by {@code ModuleTests}: a module that is {@code WORKING} and has
     * no widget, no optimization and no behaviour is a switch that changes a
     * boolean nothing reads, and the build fails rather than shipping it.
     */
    public String behaviour() {
        return behaviour;
    }

    /** Whether switching this on can change anything at all. */
    public boolean hasEffect() {
        return !widgets.isEmpty() || !optimizations.isEmpty() || !behaviour.isEmpty();
    }

    /**
     * Whether the interface should offer an on/off control.
     *
     * <p>False for {@link Implementation#UNAVAILABLE}, which is the whole point
     * of that state.
     */
    public boolean switchable() {
        return implementation.switchable();
    }

    /**
     * Whether this module is on <em>and</em> able to act right now.
     *
     * @param inWorld whether a world is loaded with a player in it
     * @param onServer whether that world is a multiplayer server
     */
    public boolean active(boolean inWorld, boolean onServer) {
        return enabled && implementation.switchable() && compatibility.satisfied(inWorld, onServer);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Category category() {
        return category;
    }

    public Sensitivity sensitivity() {
        return sensitivity;
    }

    /**
     * Who provides it. `Fathom` for first-party.
     *
     * <p>Shown in the hub so a user can tell what came with the client from what
     * came with a mod they installed. A client that presented third-party
     * features as its own would be taking credit and taking blame.
     */
    public String owner() {
        return owner;
    }

    public Icon icon() {
        return icon;
    }

    public Restart restart() {
        return restart;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean next) {
        enabled = next;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** Pinned to the top of the list by the user. */
    public boolean favourite() {
        return favourite;
    }

    public void setFavourite(boolean next) {
        favourite = next;
    }

    /** HUD widget ids this feature draws. */
    public List<String> widgets() {
        return widgets;
    }

    /** Optimization registry ids this feature switches. */
    public List<String> optimizations() {
        return optimizations;
    }

    public List<Keybind> keybinds() {
        return keybinds;
    }

    public List<Setting> settings() {
        return Collections.unmodifiableList(new ArrayList<Setting>(settings.values()));
    }

    public Setting setting(String settingId) {
        return settings.get(settingId);
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    /** Typed lookups, for the code that actually reads a value while drawing. */
    public boolean toggle(String settingId, boolean fallback) {
        Setting found = settings.get(settingId);
        return found instanceof Setting.Toggle ? ((Setting.Toggle) found).get() : fallback;
    }

    /**
     * A number, whichever kind of number setting it is.
     *
     * <p>Both {@link Setting.Slider} and {@link Setting.Whole} answer here on
     * purpose. The difference between them is how they are drawn -- a track
     * versus a minus and a plus -- and the code that reads a padding while
     * drawing should not have to care which control somebody chose for it. It
     * cared once, and the eight common HUD settings silently read their
     * fallbacks because they are whole numbers and this only knew about
     * sliders.
     */
    public double number(String settingId, double fallback) {
        Setting found = settings.get(settingId);
        if (found instanceof Setting.Slider) {
            return ((Setting.Slider) found).get();
        }
        if (found instanceof Setting.Whole) {
            return ((Setting.Whole) found).get();
        }
        return fallback;
    }

    /** A short string, or the fallback when nothing declared one. */
    public String text(String settingId, String fallback) {
        Setting found = settings.get(settingId);
        return found instanceof Setting.Text ? ((Setting.Text) found).get() : fallback;
    }

    /** A key declared as a setting rather than as a module-wide binding. */
    public Keybind key(String settingId) {
        Setting found = settings.get(settingId);
        return found instanceof Setting.Key ? ((Setting.Key) found).binding() : null;
    }

    public String choice(String settingId, String fallback) {
        Setting found = settings.get(settingId);
        return found instanceof Setting.Choice ? ((Setting.Choice) found).get() : fallback;
    }

    public int colour(String settingId, int fallback) {
        Setting found = settings.get(settingId);
        return found instanceof Setting.Colour ? ((Setting.Colour) found).rgb() : fallback;
    }

    /** Put every setting back the way it shipped, and the switch with them. */
    public void resetToDefaults() {
        for (Setting setting : settings.values()) {
            setting.reset();
        }
        for (Keybind keybind : keybinds) {
            keybind.reset();
        }
        enabled = defaultEnabled;
    }

    /** Whether anything about this feature differs from how it shipped. */
    public boolean changed() {
        if (enabled != defaultEnabled) {
            return true;
        }
        for (Setting setting : settings.values()) {
            if (setting.changed()) {
                return true;
            }
        }
        for (Keybind keybind : keybinds) {
            if (keybind.changed()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a search box query matches this. */
    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        return haystack().contains(query.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Everything about this feature that is worth searching, lower-cased once.
     *
     * <p>Built on the first search and kept, because the list is filtered on
     * every frame somebody is typing: doing it the obvious way lower-cased five
     * strings per feature per frame, which is a hundred allocations a frame to
     * answer a question whose inputs never change.
     *
     * <p>Settings are in it. Somebody looking for "opacity" should find the
     * features that have one, not an empty list.
     */
    private String haystack() {
        if (haystack == null) {
            StringBuilder text = new StringBuilder(128);
            text.append(name).append(' ').append(description).append(' ').append(id)
                    .append(' ').append(category.label());
            for (Setting setting : settings.values()) {
                text.append(' ').append(setting.label());
            }
            haystack = text.toString().toLowerCase(Locale.ROOT);
        }
        return haystack;
    }

    @Override
    public String toString() {
        return id;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /** How a feature is declared, first-party or otherwise. */
    public static final class Builder {
        private final String id;
        private String name;
        private String description = "";
        private Category category = Category.UTILITY;
        private Sensitivity sensitivity = Sensitivity.SAFE;
        private String owner = "Fathom";
        private Icon icon = Icon.SLIDERS;
        private boolean defaultEnabled;
        private Restart restart = Restart.LIVE;
        private final List<String> widgets = new ArrayList<String>();
        private final List<String> optimizations = new ArrayList<String>();
        private final Map<String, Setting> settings = new LinkedHashMap<String, Setting>();
        private final List<Keybind> keybinds = new ArrayList<Keybind>();
        private Implementation implementation = Implementation.WORKING;
        private Compatibility compatibility = Compatibility.ALWAYS;
        private String behaviour = "";

        Builder(String id) {
            if (id == null || !id.matches("[a-z0-9]+(?:[-.][a-z0-9]+)*")) {
                throw new IllegalArgumentException("feature id must be lowercase and hyphenated: " + id);
            }
            this.id = id;
            this.name = id;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder category(Category value) {
            this.category = value;
            return this;
        }

        public Builder sensitivity(Sensitivity value) {
            this.sensitivity = value;
            return this;
        }

        public Builder owner(String value) {
            this.owner = value;
            return this;
        }

        public Builder icon(Icon value) {
            this.icon = value;
            return this;
        }

        /**
         * Ask to be on out of the box.
         *
         * <p>Honoured only for {@link Sensitivity#SAFE}. The check is in the
         * constructor rather than here, so it cannot be avoided by calling the
         * builder methods in a different order.
         */
        public Builder onByDefault() {
            this.defaultEnabled = true;
            return this;
        }

        public Builder restartRequired() {
            this.restart = Restart.REQUIRED;
            return this;
        }

        /** Say how finished this is. The default is {@link Implementation#WORKING}. */
        public Builder implementation(Implementation value) {
            this.implementation = value == null ? Implementation.WORKING : value;
            return this;
        }

        /** Say what it needs. The default is {@link Compatibility#ALWAYS}. */
        public Builder needs(Compatibility value) {
            this.compatibility = value == null ? Compatibility.ALWAYS : value;
            return this;
        }

        /**
         * Name the behaviour this module drives.
         *
         * <p>For modules that are neither a widget nor an optimization — Zoom
         * changes the field of view, and nothing in the registry would know
         * that otherwise. The string is matched against the behaviours the
         * client implements, so a typo is a failing test rather than a module
         * that quietly does nothing.
         */
        public Builder behaviour(String value) {
            this.behaviour = value == null ? "" : value;
            return this;
        }

        public Builder widget(String widgetId) {
            this.widgets.add(widgetId);
            return this;
        }

        public Builder optimization(String optimizationId) {
            this.optimizations.add(optimizationId);
            return this;
        }

        public Builder setting(Setting setting) {
            if (settings.put(setting.id(), setting) != null) {
                throw new IllegalStateException(id + " declares " + setting.id() + " twice");
            }
            return this;
        }

        public Builder keybind(Keybind keybind) {
            this.keybinds.add(keybind);
            return this;
        }

        public FathomModule build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalStateException(id + " has no name");
            }
            if (description.length() < 10) {
                throw new IllegalStateException(id + " has no real description");
            }
            if (widgets.isEmpty() && optimizations.isEmpty() && keybinds.isEmpty() && settings.isEmpty()) {
                // A feature with nothing attached is a row in a list that does
                // nothing when pressed. Refusing it here is how the hub stays
                // free of decoration.
                throw new IllegalStateException(id + " does nothing: no widget, keybind, setting or optimization");
            }
            return new FathomModule(this);
        }
    }
}
