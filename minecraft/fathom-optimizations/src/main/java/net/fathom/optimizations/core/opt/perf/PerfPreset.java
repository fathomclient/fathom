package net.fathom.optimizations.core.opt.perf;

/**
 * How hard a distance-based performance feature is allowed to work.
 *
 * <p>One vocabulary for every distance table, so "Balanced" means the same
 * amount of risk for entities and for tile entities. {@link #CUSTOM} reads the
 * user's own sliders; every other value reads a fixed table whose numbers are
 * never tighter than the per-kind floor.
 */
public enum PerfPreset {
    OFF("Off"),
    CONSERVATIVE("Conservative"),
    BALANCED("Balanced"),
    AGGRESSIVE("Aggressive"),
    CUSTOM("Custom");

    private static final PerfPreset[] ALL = values();
    private static final String[] LABELS = new String[ALL.length];

    static {
        for (int index = 0; index < ALL.length; index++) {
            LABELS[index] = ALL[index].label;
        }
    }

    private final String label;

    PerfPreset(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The labels in declaration order, for a setting's option list. */
    public static String[] labels() {
        return LABELS.clone();
    }

    /** The preset with this label, or {@code fallback} for anything else. */
    public static PerfPreset parse(String label, PerfPreset fallback) {
        if (label != null) {
            for (PerfPreset preset : ALL) {
                if (preset.label.equalsIgnoreCase(label.trim())) {
                    return preset;
                }
            }
        }
        return fallback;
    }
}
