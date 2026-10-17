package net.fathom.optimizations.core.registry;

import java.util.Objects;

/**
 * Something this optimization is known to interfere with.
 *
 * <p>The thing conflicted with is named as a free string rather than an
 * {@link OptimizationId}, because most conflicts are with software outside this mod —
 * OptiFine, PolyPatcher, a shader pack — and forcing those into our id space would imply
 * we control them.
 */
public final class Conflict {

    /** How the launcher and the mod should behave when the conflict is detected. */
    public enum Resolution {
        /**
         * Turn this optimization off and leave the other software alone.
         *
         * <p>The default posture, and the one {@code mod-policies.md} §8 argues for:
         * "we must detect OptiFine at load and disable our conflicting patches by default
         * rather than fighting it". The user chose to install the other thing.
         */
        DISABLE_OURS,
        /** Both can run, but the user should be told the result may be odd. */
        WARN_ONLY,
        /** They genuinely cannot coexist; the launcher refuses to start. */
        REFUSE_TO_LAUNCH
    }

    private final String with;
    private final Resolution resolution;
    private final String detail;

    public Conflict(String with, Resolution resolution, String detail) {
        this.with = Objects.requireNonNull(with, "with");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.trim().isEmpty()) {
            // "Conflicts with OptiFine" is not something a user can act on. What goes
            // wrong, and what happens as a result, is the whole content of a conflict.
            throw new IllegalArgumentException("a conflict must say what actually goes wrong");
        }
    }

    public static Conflict disableOurs(String with, String detail) {
        return new Conflict(with, Resolution.DISABLE_OURS, detail);
    }

    public static Conflict warn(String with, String detail) {
        return new Conflict(with, Resolution.WARN_ONLY, detail);
    }

    public String with() {
        return with;
    }

    public Resolution resolution() {
        return resolution;
    }

    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return with + " (" + resolution + "): " + detail;
    }
}
