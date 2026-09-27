package net.fathom.optimizations.core.module;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One configurable value belonging to a feature.
 *
 * <h2>Why a type hierarchy and not a map of strings</h2>
 *
 * <p>Because the hub renders these without knowing what any of them are. A
 * screen that switched on a string tag would need a case for every tag anybody
 * ever adds, in a class nobody adding a feature is looking at. A screen that
 * asks the setting to describe itself needs one renderer per <em>kind</em> of
 * control, and a new feature is a new value, not a new case.
 *
 * <p>The kinds are deliberately few. Four cover everything the first batch of
 * features needs, and a fifth would mean a fifth control to design, place, style
 * and make keyboard-reachable. When something genuinely does not fit, the honest
 * answer is usually that it is two settings.
 *
 * <h2>Values are strings on the wire and typed in the API</h2>
 *
 * <p>Persistence is a properties file, so everything is a string on disk.
 * Everything <em>above</em> disk is typed, and the parse happens once, here,
 * where a bad value falls back to the default rather than reaching a renderer.
 */
public abstract class Setting {

    /**
     * How the hub should draw this.
     *
     * <h2>Why the schema is closed</h2>
     *
     * <p>A module declares its settings and the interface renders them; no
     * module draws its own. That is what makes every module settings page in the
     * client look the same and what stops a module inventing a control nobody
     * else has. The cost is that a new kind of value needs a new constant here
     * and a new case on the page — which is the right amount of friction for
     * adding a control to a design system.
     *
     * <p>The eight the client supports, and what each is for:
     *
     * <ul>
     *   <li>{@code TOGGLE} — a boolean.
     *   <li>{@code SLIDER} — a real number in a range. Also the float and the
     *       range: a range with a step of one whole unit is an integer slider.
     *   <li>{@code INTEGER} — a whole number where the exact value matters more
     *       than the sweep, drawn as a stepper rather than a track.
     *   <li>{@code CHOICE} — one of a short list. The enum.
     *   <li>{@code COLOUR} — an RGB value from the palette.
     *   <li>{@code TEXT} — a short string, for a format or a label.
     *   <li>{@code KEY} — a key binding that belongs to one setting rather than
     *       to the module as a whole.
     * </ul>
     */
    public enum Kind {
        TOGGLE,
        SLIDER,
        INTEGER,
        CHOICE,
        COLOUR,
        TEXT,
        KEY
    }

    private final String id;
    private final String label;
    private final String description;

    /**
     * Bumped whenever any setting anywhere changes value.
     *
     * <p>For the code that reads settings at frame rate: a HUD widget can keep
     * everything it derived from its settings (a formatter, a layout, a preset)
     * and compare one integer per frame instead of re-reading a dozen values.
     * Render thread only; the profile writer thread serialises but never sets.
     */
    private static int revision;

    /** The current settings revision. Changes whenever any value changes. */
    public static int revision() {
        return revision;
    }

    /** Record that a value changed. Called by every mutator below. */
    protected static void touched() {
        revision++;
    }

    Setting(String id, String label, String description) {
        this.id = require(id, "id");
        this.label = require(label, "label");
        this.description = description == null ? "" : description;
    }

    private static String require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("setting " + what + " must not be empty");
        }
        return value;
    }

    /** Stable within its feature. Becomes part of the key on disk. */
    public final String id() {
        return id;
    }

    /** One to four words. */
    public final String label() {
        return label;
    }

    /** One sentence, or empty. Never a paragraph. */
    public final String description() {
        return description;
    }

    public abstract Kind kind();

    /** The stored form. */
    public abstract String serialise();

    /**
     * Read a stored form back.
     *
     * <p>Never throws. A value the user or a broken file put there that this
     * setting cannot use falls back to the default, because losing one setting
     * is better than losing the file — and the complaint is recorded by
     * {@link ModuleStore} so the reason is visible.
     *
     * @return whether the value was understood
     */
    public abstract boolean parse(String raw);

    /** Put it back the way it shipped. */
    public abstract void reset();

    /** Whether this differs from its default, for a "reset" affordance. */
    public abstract boolean changed();

    /** What the row shows on the right, when it is not a control. */
    public abstract String display();

    @Override
    public String toString() {
        return id + "=" + serialise();
    }

    // -----------------------------------------------------------------------
    // Toggle
    // -----------------------------------------------------------------------

    /** On or off. */
    public static final class Toggle extends Setting {
        private final boolean fallback;
        private boolean value;

        public Toggle(String id, String label, String description, boolean fallback) {
            super(id, label, description);
            this.fallback = fallback;
            this.value = fallback;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean next) {
            if (value != next) {
                value = next;
                touched();
            }
        }

        @Override
        public Kind kind() {
            return Kind.TOGGLE;
        }

        @Override
        public String serialise() {
            return String.valueOf(value);
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            String text = raw.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(text) || "on".equals(text) || "yes".equals(text)) {
                set(true);
                return true;
            }
            if ("false".equals(text) || "off".equals(text) || "no".equals(text)) {
                set(false);
                return true;
            }
            return false;
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return value != fallback;
        }

        @Override
        public String display() {
            return value ? "On" : "Off";
        }
    }

    // -----------------------------------------------------------------------
    // Slider
    // -----------------------------------------------------------------------

    /**
     * A number in a range.
     *
     * <p>Stored as a double and displayed with a fixed number of decimals, so a
     * scale of 1.5 shows as "1.5" and an FPS cap of 60 shows as "60" rather
     * than "60.0". The alternative — separate int and float sliders — is two
     * controls that look identical and behave identically.
     */
    public static final class Slider extends Setting {
        private final double fallback;
        private final double minimum;
        private final double maximum;
        private final double step;
        private final int decimals;
        private final String unit;
        private double value;

        public Slider(
                String id,
                String label,
                String description,
                double fallback,
                double minimum,
                double maximum,
                double step,
                int decimals,
                String unit) {
            super(id, label, description);
            if (minimum >= maximum) {
                throw new IllegalArgumentException(id + ": minimum must be below maximum");
            }
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step <= 0 ? 1 : step;
            this.decimals = Math.max(0, decimals);
            this.unit = unit == null ? "" : unit;
            this.fallback = clamp(fallback);
            this.value = this.fallback;
        }

        public static Slider whole(
                String id, String label, String description, int fallback, int minimum, int maximum, String unit) {
            return new Slider(id, label, description, fallback, minimum, maximum, 1, 0, unit);
        }

        private double clamp(double raw) {
            if (Double.isNaN(raw)) {
                return minimum;
            }
            double stepped = minimum + Math.round((raw - minimum) / step) * step;
            return Math.max(minimum, Math.min(maximum, stepped));
        }

        public double get() {
            return value;
        }

        public int asInt() {
            return (int) Math.round(value);
        }

        public float asFloat() {
            return (float) value;
        }

        public void set(double next) {
            double clamped = clamp(next);
            if (Double.compare(clamped, value) != 0) {
                value = clamped;
                touched();
            }
        }

        /** Where the handle sits, 0 to 1. */
        public double fraction() {
            return (value - minimum) / (maximum - minimum);
        }

        /** Move the handle to a position, 0 to 1. */
        public void setFraction(double fraction) {
            set(minimum + fraction * (maximum - minimum));
        }

        public double minimum() {
            return minimum;
        }

        public double maximum() {
            return maximum;
        }

        @Override
        public Kind kind() {
            return Kind.SLIDER;
        }

        @Override
        public String serialise() {
            return decimals == 0 ? String.valueOf(asInt()) : String.valueOf(value);
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            try {
                double parsed = Double.parseDouble(raw.trim());
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return false;
                }
                set(parsed);
                return true;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return Math.abs(value - fallback) > 1e-9;
        }

        @Override
        public String display() {
            String number = decimals == 0
                    ? String.valueOf(asInt())
                    : String.format(Locale.ROOT, "%." + decimals + "f", value);
            return unit.isEmpty() ? number : number + " " + unit;
        }
    }

    // -----------------------------------------------------------------------
    // Choice
    // -----------------------------------------------------------------------

    /** One of a short list. */
    public static final class Choice extends Setting {
        private final List<String> options;
        private final String fallback;
        private final java.util.Map<String, String> aliases = new java.util.HashMap<String, String>(2);
        private String value;

        public Choice(String id, String label, String description, String fallback, String... options) {
            super(id, label, description);
            if (options == null || options.length < 2) {
                throw new IllegalArgumentException(id + ": a choice needs at least two options");
            }
            this.options = Collections.unmodifiableList(Arrays.asList(options.clone()));
            if (!this.options.contains(fallback)) {
                throw new IllegalArgumentException(id + ": the default is not one of the options");
            }
            this.fallback = fallback;
            this.value = fallback;
        }

        public List<String> options() {
            return options;
        }

        public String get() {
            return value;
        }

        public int index() {
            return options.indexOf(value);
        }

        public void set(String next) {
            if (options.contains(next) && !next.equals(value)) {
                value = next;
                touched();
            }
        }

        /**
         * Accept an option name an older build wrote.
         *
         * <p>For a renamed option: a profile that stored the old spelling reads
         * back as the new one rather than falling back to the default, which
         * would silently undo somebody's choice on the day the label improved.
         */
        public Choice alias(String legacy, String current) {
            if (!options.contains(current)) {
                throw new IllegalArgumentException(id() + ": alias target " + current + " is not an option");
            }
            aliases.put(legacy, current);
            return this;
        }

        /** Step to the next option, wrapping. What clicking the row does. */
        public void advance() {
            set(options.get((index() + 1) % options.size()));
        }

        @Override
        public Kind kind() {
            return Kind.CHOICE;
        }

        @Override
        public String serialise() {
            return value;
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            String text = raw.trim();
            String migrated = aliases.get(text);
            if (migrated != null) {
                text = migrated;
            }
            if (!options.contains(text)) {
                return false;
            }
            set(text);
            return true;
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return !value.equals(fallback);
        }

        @Override
        public String display() {
            return value;
        }
    }

    // -----------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------

    /**
     * An RGB colour, stored as six hex digits.
     *
     * <p>No alpha. Opacity belongs to a widget's placement, where it applies to
     * the whole widget including its plate — a per-colour alpha would let a
     * user make the text invisible while the plate stayed, which is a state
     * nobody wants and everybody can reach by accident.
     */
    public static final class Colour extends Setting {
        private final int fallback;
        private int value;

        public Colour(String id, String label, String description, int fallback) {
            super(id, label, description);
            this.fallback = fallback & 0xFFFFFF;
            this.value = this.fallback;
        }

        /** The RGB triple, without alpha. */
        public int rgb() {
            return value;
        }

        /** Packed ARGB at the given alpha, ready to draw with. */
        public int argb(int alpha) {
            return (Math.max(0, Math.min(255, alpha)) << 24) | value;
        }

        public void set(int next) {
            int masked = next & 0xFFFFFF;
            if (masked != value) {
                value = masked;
                touched();
            }
        }

        @Override
        public Kind kind() {
            return Kind.COLOUR;
        }

        @Override
        public String serialise() {
            return String.format(Locale.ROOT, "%06X", value);
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            String text = raw.trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            if (text.length() != 6) {
                return false;
            }
            try {
                set(Integer.parseInt(text, 16));
                return true;
            } catch (NumberFormatException notHex) {
                return false;
            }
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return value != fallback;
        }

        @Override
        public String display() {
            return "#" + serialise();
        }
    }

    // -----------------------------------------------------------------------
    // Integer
    // -----------------------------------------------------------------------

    /**
     * A whole number, stepped.
     *
     * <p>Separate from {@link Slider} because the two are different questions.
     * A slider asks "roughly where in this range"; this asks "exactly how many",
     * and is drawn with a minus and a plus rather than a track. Decimal
     * precision, sample windows and padding are all this.
     */
    public static final class Whole extends Setting {
        private final int fallback;
        private final int minimum;
        private final int maximum;
        private final int step;
        private final String unit;
        private int value;

        public Whole(
                String id, String label, String description,
                int fallback, int minimum, int maximum, int step, String unit) {
            super(id, label, description);
            if (minimum >= maximum) {
                throw new IllegalArgumentException(id + ": minimum must be below maximum");
            }
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step <= 0 ? 1 : step;
            this.unit = unit == null ? "" : unit;
            this.fallback = clampTo(fallback);
            this.value = this.fallback;
        }

        private int clampTo(int candidate) {
            return candidate < minimum ? minimum : (candidate > maximum ? maximum : candidate);
        }

        public int get() {
            return value;
        }

        public void set(int next) {
            int clamped = clampTo(next);
            if (clamped != value) {
                value = clamped;
                touched();
            }
        }

        /** Step up or down, staying inside the range. */
        public void nudge(int direction) {
            set(value + step * (direction < 0 ? -1 : 1));
        }

        public int minimum() {
            return minimum;
        }

        public int maximum() {
            return maximum;
        }

        public boolean atMinimum() {
            return value <= minimum;
        }

        public boolean atMaximum() {
            return value >= maximum;
        }

        @Override
        public Kind kind() {
            return Kind.INTEGER;
        }

        @Override
        public String serialise() {
            return String.valueOf(value);
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            String text = raw.trim();
            try {
                set(Integer.parseInt(text));
                return true;
            } catch (NumberFormatException notANumber) {
                // A value an older build stored as a decimal ("3.0") is still
                // a number, and rounding it keeps somebody's choice.
                try {
                    double parsed = Double.parseDouble(text);
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        return false;
                    }
                    set((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(parsed))));
                    return true;
                } catch (NumberFormatException stillNotANumber) {
                    return false;
                }
            }
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return value != fallback;
        }

        @Override
        public String display() {
            return unit.isEmpty() ? String.valueOf(value) : value + " " + unit;
        }
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /**
     * A short string.
     *
     * <p>Length-capped, because it is drawn in a HUD widget and a setting that
     * can be a paragraph is a setting that can push a widget off the screen.
     */
    public static final class Text extends Setting {
        public static final int MAX_LENGTH = 48;

        private final String fallback;
        private String value;

        public Text(String id, String label, String description, String fallback) {
            super(id, label, description);
            this.fallback = fallback == null ? "" : trim(fallback);
            this.value = this.fallback;
        }

        private static String trim(String candidate) {
            String text = candidate.replace('\n', ' ').replace('\r', ' ');
            return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        }

        public String get() {
            return value;
        }

        public void set(String next) {
            String trimmed = next == null ? "" : trim(next);
            if (!trimmed.equals(value)) {
                value = trimmed;
                touched();
            }
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        @Override
        public String serialise() {
            return value;
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            set(raw);
            return true;
        }

        @Override
        public void reset() {
            set(fallback);
        }

        @Override
        public boolean changed() {
            return !value.equals(fallback);
        }

        @Override
        public String display() {
            return value.isEmpty() ? "None" : value;
        }
    }

    // -----------------------------------------------------------------------
    // Key
    // -----------------------------------------------------------------------

    /**
     * A key, as a setting rather than as a module-wide binding.
     *
     * <p>Modules whose whole purpose is a key — Zoom — declare a
     * {@link Keybind} instead, because the key handler has to find it without
     * walking every setting of every module. This is for the second key on a
     * module that already has one, and for a key that only makes sense
     * alongside a value.
     */
    public static final class Key extends Setting {
        private final Keybind binding;

        public Key(String id, String label, String description, Keybind binding) {
            super(id, label, description);
            if (binding == null) {
                throw new IllegalArgumentException(id + ": a key setting needs a binding");
            }
            this.binding = binding;
        }

        public Keybind binding() {
            return binding;
        }

        @Override
        public Kind kind() {
            return Kind.KEY;
        }

        @Override
        public String serialise() {
            return String.valueOf(binding.key());
        }

        @Override
        public boolean parse(String raw) {
            if (raw == null) {
                return false;
            }
            try {
                binding.set(Integer.parseInt(raw.trim()));
                return true;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }

        @Override
        public void reset() {
            binding.reset();
        }

        @Override
        public boolean changed() {
            return binding.changed();
        }

        @Override
        public String display() {
            return binding.keyName();
        }
    }
}
