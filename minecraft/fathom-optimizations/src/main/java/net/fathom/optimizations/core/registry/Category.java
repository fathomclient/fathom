package net.fathom.optimizations.core.registry;

/**
 * How risky an optimization is, and therefore whether it may be on by default.
 *
 * <p>These are the classes from {@code docs/research/PERFORMANCE_MATRIX.md}, which is the
 * source of truth. They are deliberately about <em>risk</em>, not about size of win: a
 * large win that changes what the player sees is still OPTIONAL, and a small win that is
 * output-identical is still SAFE.
 *
 * <p>{@code REJECTED} from the matrix has no representative here. Something we have
 * decided not to implement does not need a switch, and giving it one would invite someone
 * to turn it on.
 */
public enum Category {

    /**
     * Output-identical, or so close that no player could tell. No meaningful downside.
     *
     * <p>Eligible for the default preset — but eligibility is not entitlement. An
     * optimization is only promoted to on-by-default once it has been measured under
     * {@code docs/performance/benchmark-methodology.md}. Until then it is SAFE and off.
     */
    SAFE,

    /**
     * Works correctly, but changes something a user might not want: visuals, memory,
     * latency, or compatibility. Off by default; the user chooses.
     */
    OPTIONAL,

    /**
     * Hardware-, driver-, or server-dependent, or simply not tested enough to have an
     * opinion about. Off by default and clearly labelled as such.
     */
    EXPERIMENTAL;

    /** Whether an optimization in this class may ever ship on by default. */
    public boolean mayDefaultOn() {
        return this == SAFE;
    }

    public String label() {
        switch (this) {
            case SAFE:
                return "Safe";
            case OPTIONAL:
                return "Optional";
            case EXPERIMENTAL:
                return "Experimental";
            default:
                throw new AssertionError(this);
        }
    }
}
