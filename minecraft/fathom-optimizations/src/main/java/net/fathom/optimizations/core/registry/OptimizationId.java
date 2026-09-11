package net.fathom.optimizations.core.registry;

import java.util.regex.Pattern;

/**
 * A stable, unique identifier for one optimization.
 *
 * <p>Dotted and namespaced — {@code render.font.batching} — so the id sorts into
 * meaningful groups and reads as a path through the subsystem it touches. It is a value
 * type rather than a bare {@code String} because it ends up as a config key, a log field
 * and a map key, and a typo in any of those should be a compile-time or construction-time
 * failure rather than a silently-missing setting.
 *
 * <p><strong>Ids are permanent.</strong> Once one has shipped it must not be renamed:
 * a user's saved configuration refers to it. Renaming would silently reset their choice.
 */
public final class OptimizationId implements Comparable<OptimizationId> {

    private static final Pattern VALID = Pattern.compile("[a-z0-9]+([.][a-z0-9]+(-[a-z0-9]+)*)+");
    private static final int MAX_LENGTH = 96;

    private final String value;

    private OptimizationId(String value) {
        this.value = value;
    }

    /**
     * @throws IllegalArgumentException if the id is not lowercase dotted segments, or is
     *     a single segment with no namespace, or is too long
     */
    public static OptimizationId of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("an optimization id must not be empty");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "an optimization id is at most " + MAX_LENGTH + " characters: " + raw);
        }
        if (!VALID.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "an optimization id must be lowercase dotted segments with at least one dot,"
                            + " e.g. render.font.batching; got: " + raw);
        }
        return new OptimizationId(raw);
    }

    public String value() {
        return value;
    }

    /** The leading segment: {@code render}, {@code cpu}, {@code memory}, {@code input}. */
    public String namespace() {
        return value.substring(0, value.indexOf('.'));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OptimizationId && ((OptimizationId) other).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public int compareTo(OptimizationId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
