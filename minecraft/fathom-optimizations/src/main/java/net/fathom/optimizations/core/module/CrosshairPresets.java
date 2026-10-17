package net.fathom.optimizations.core.module;

/**
 * Named starting points for the crosshair.
 *
 * <h2>What a preset is, and is not</h2>
 *
 * <p>A preset writes the shape settings — style, length, thickness, gap, dot and
 * outline — and nothing else. Colour and opacity are left alone: somebody who
 * has picked their green should be able to try "Small" without losing it.
 *
 * <p>A preset is not stored as a mode. Choosing one writes its values into the
 * ordinary settings, and editing any of those afterwards turns the picker to
 * "Custom". So there is one crosshair definition, the settings, and the preset
 * row only ever describes it; it cannot override what the sliders say.
 */
public final class CrosshairPresets {

    public static final String CUSTOM = "Custom";

    public static final String PRESET = "preset";
    public static final String STYLE = "shape";
    public static final String LENGTH = "length";
    public static final String THICKNESS = "thickness";
    public static final String GAP = "gap";
    public static final String DOT = "dot";
    public static final String DOT_SIZE = "dot.size";
    public static final String OUTLINE = "outline";
    public static final String OUTLINE_WIDTH = "outline.width";

    /** Names in picker order, Custom first. */
    public static final String[] NAMES = {
        CUSTOM, "Default", "Classic", "Dot", "Small", "Competitive", "Plus", "T-shape", "Circle"
    };

    private static final Object[][] VALUES = {
        // name,        style,    length, thickness, gap, dot,   dot size, outline, outline width
        {"Default",     "Cross",  4,      1,         2,   false, 1,        true,    1},
        {"Classic",     "Cross",  4,      1,         0,   false, 1,        false,   1},
        {"Dot",         "Dot",    4,      1,         2,   true,  3,        true,    1},
        {"Small",       "Cross",  2,      1,         1,   false, 1,        true,    1},
        {"Competitive", "Cross",  3,      1,         2,   true,  1,        true,    1},
        {"Plus",        "Cross",  5,      2,         0,   false, 2,        true,    1},
        {"T-shape",     "Tee",    4,      1,         2,   false, 1,        true,    1},
        {"Circle",      "Circle", 2,      1,         3,   true,  1,        true,    1},
    };

    private CrosshairPresets() {}

    /** Write a preset's shape into the module. Unknown names and Custom change nothing. */
    public static void apply(FathomModule module, String name) {
        Object[] row = row(name);
        if (module == null || row == null) {
            return;
        }
        choice(module, STYLE).set((String) row[1]);
        whole(module, LENGTH).set(((Integer) row[2]).intValue());
        whole(module, THICKNESS).set(((Integer) row[3]).intValue());
        whole(module, GAP).set(((Integer) row[4]).intValue());
        toggle(module, DOT).set(((Boolean) row[5]).booleanValue());
        whole(module, DOT_SIZE).set(((Integer) row[6]).intValue());
        toggle(module, OUTLINE).set(((Boolean) row[7]).booleanValue());
        whole(module, OUTLINE_WIDTH).set(((Integer) row[8]).intValue());
    }

    /** The preset the current shape settings exactly match, or {@link #CUSTOM}. */
    public static String matching(FathomModule module) {
        for (Object[] row : VALUES) {
            // A dot has no arms, so its length, gap and dot switch are not part
            // of what it looks like and cannot make it custom.
            boolean arms = !"Dot".equals(row[1]);
            if (module.choice(STYLE, "").equals(row[1])
                    && (!arms || same(module, LENGTH, row[2]))
                    && same(module, THICKNESS, row[3])
                    && (!arms || same(module, GAP, row[4]))
                    && (!arms || module.toggle(DOT, false) == ((Boolean) row[5]).booleanValue())
                    // A dot size nobody can see does not make a preset custom.
                    && (!dotVisible(module) || same(module, DOT_SIZE, row[6]))
                    && module.toggle(OUTLINE, false) == ((Boolean) row[7]).booleanValue()
                    && (!module.toggle(OUTLINE, false) || same(module, OUTLINE_WIDTH, row[8]))) {
                return (String) row[0];
            }
        }
        return CUSTOM;
    }

    /**
     * The module's change listener: a preset choice writes values, any other
     * shape edit re-labels the picker.
     */
    public static final FathomModule.ChangeListener LISTENER = new FathomModule.ChangeListener() {
        @Override
        public void changed(FathomModule module, Setting setting) {
            Setting.Choice preset = choice(module, PRESET);
            if (preset == null) {
                return;
            }
            if (setting == preset) {
                apply(module, preset.get());
            }
            // Re-derived either way, so choosing a preset whose values somebody
            // has already matched by hand still reads correctly, and Custom
            // chosen directly settles on whatever the values actually are.
            preset.set(matching(module));
        }
    };

    private static boolean dotVisible(FathomModule module) {
        return "Dot".equals(module.choice(STYLE, "")) || module.toggle(DOT, false);
    }

    private static Object[] row(String name) {
        for (Object[] row : VALUES) {
            if (row[0].equals(name)) {
                return row;
            }
        }
        return null;
    }

    private static boolean same(FathomModule module, String id, Object expected) {
        return Math.round(module.number(id, Double.NaN)) == ((Integer) expected).intValue();
    }

    private static Setting.Choice choice(FathomModule module, String id) {
        Setting found = module.setting(id);
        return found instanceof Setting.Choice ? (Setting.Choice) found : null;
    }

    private static Setting.Whole whole(FathomModule module, String id) {
        return (Setting.Whole) module.setting(id);
    }

    private static Setting.Toggle toggle(FathomModule module, String id) {
        return (Setting.Toggle) module.setting(id);
    }
}
