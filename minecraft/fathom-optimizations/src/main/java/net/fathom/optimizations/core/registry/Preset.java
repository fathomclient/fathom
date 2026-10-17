package net.fathom.optimizations.core.registry;

/**
 * A named set of optimizations, for people who do not want to read forty descriptions.
 *
 * <p>Presets are a convenience over {@link SwitchBoard}'s per-id switches, never a
 * replacement for them. A preset that could express something the individual switches
 * cannot would be a second mechanism to keep in step with the first.
 */
public enum Preset {

    /** Nothing. Stock 1.8.9 behaviour, for comparing against. */
    VANILLA {
        @Override
        public boolean includes(Optimization optimization) {
            return false;
        }

        @Override
        public String description() {
            return "Nothing switched on. The game as Mojang shipped it, which is what every"
                    + " benchmark is measured against.";
        }
    },

    /**
     * Everything SAFE that we have actually measured.
     *
     * <p>Reads the same rule {@link Optimization#defaultEnabled()} enforces, rather than
     * asserting membership independently — otherwise the preset and the default could
     * disagree, and a user would get different behaviour depending on whether they had
     * ever opened the settings screen.
     */
    SAFE_DEFAULTS {
        @Override
        public boolean includes(Optimization optimization) {
            return optimization.defaultEnabled();
        }

        @Override
        public String description() {
            return "Everything classified Safe that we have benchmarked ourselves. Output is"
                    + " identical to vanilla; nothing here changes what you see.";
        }
    },

    /**
     * Everything SAFE, measured or not.
     *
     * <p>Honest about what it is: a way to try work that is finished but not yet
     * validated. Not offered as a default.
     */
    SAFE_INCLUDING_UNMEASURED {
        @Override
        public boolean includes(Optimization optimization) {
            return optimization.category() == Category.SAFE;
        }

        @Override
        public String description() {
            return "Everything classified Safe, including work we have not benchmarked yet."
                    + " Output-identical by design, but unproven by us.";
        }
    },

    /** Safe plus optional. Changes what you see; the user is choosing that. */
    AGGRESSIVE {
        @Override
        public boolean includes(Optimization optimization) {
            return optimization.category() == Category.SAFE
                    || optimization.category() == Category.OPTIONAL;
        }

        @Override
        public String description() {
            return "Safe and Optional together. Some of these change what you see — hidden"
                    + " entities, particle lighting — so read what they do before relying on"
                    + " them in a competitive setting.";
        }
    };

    /** Whether this preset wants the given optimization on. */
    public abstract boolean includes(Optimization optimization);

    /** What the preset is, in the words a user would read. */
    public abstract String description();

    public String label() {
        switch (this) {
            case VANILLA:
                return "Vanilla";
            case SAFE_DEFAULTS:
                return "Safe defaults";
            case SAFE_INCLUDING_UNMEASURED:
                return "Safe, including unmeasured";
            case AGGRESSIVE:
                return "Aggressive";
            default:
                throw new AssertionError(this);
        }
    }

    /**
     * Never includes anything EXPERIMENTAL.
     *
     * <p>Stated as a method so it is testable. Experimental means hardware-, driver- or
     * server-dependent, and a preset is by definition a choice made without knowing the
     * user's machine.
     */
    public boolean mayIncludeExperimental() {
        return false;
    }
}
