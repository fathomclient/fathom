package net.fathom.optimizations.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which optimizations are on, and why.
 *
 * <p>Every optimization is individually switchable, always. Presets are a convenience
 * layered on top — {@link #applyPreset} is a bulk assignment of the same per-id switches
 * a user could set one at a time. That ordering matters: a design where presets are
 * primary and individual control is bolted on later cannot express "the Safe preset, but
 * without this one thing", which is exactly what a person debugging a graphics problem
 * needs.
 *
 * <h2>Who turned it off</h2>
 *
 * <p>{@link Reason} distinguishes a user's decision from the launcher's. It is the same
 * distinction the mod engine makes for {@code DisabledReason}, and for the same purpose:
 * something we turned off because OptiFine was present may be offered back when OptiFine
 * is gone, whereas something the user turned off stays off until they say otherwise.
 *
 * <p>Not thread-safe. It is read on the render thread and written from the settings
 * screen, both on the client thread. Anything that needs it off-thread should take a
 * {@link #snapshot()}.
 */
public final class SwitchBoard {

    /** Why an optimization is in the state it is in. */
    public enum Reason {
        /** Nobody has said anything; this is the registry's default. */
        DEFAULT,
        /** The user chose this explicitly. */
        USER,
        /** A preset set it. */
        PRESET,
        /** Turned off because something it conflicts with is present. */
        CONFLICT,
        /** Turned off because it cannot apply to this Minecraft version, loader or JVM. */
        INCOMPATIBLE,
        /** Turned off by a correctness safeguard after it misbehaved. */
        SAFEGUARD
    }

    /** One switch's position and the reason for it. */
    public static final class State {
        private final boolean enabled;
        private final Reason reason;
        private final String detail;

        State(boolean enabled, Reason reason, String detail) {
            this.enabled = enabled;
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }

        public boolean enabled() {
            return enabled;
        }

        public Reason reason() {
            return reason;
        }

        /** Free text explaining the reason, where there is more to say. */
        public String detail() {
            return detail;
        }

        /** Whether the user set this, as opposed to us. */
        public boolean chosenByUser() {
            return reason == Reason.USER;
        }

        /**
         * Whether we turned this off on the user's behalf and could offer it back.
         *
         * <p>The question that decides whether removing OptiFine should re-enable the
         * optimizations it displaced. A user's own "off" must survive that.
         */
        public boolean offAutomatically() {
            return !enabled
                    && (reason == Reason.CONFLICT
                            || reason == Reason.INCOMPATIBLE
                            || reason == Reason.SAFEGUARD);
        }

        @Override
        public String toString() {
            return (enabled ? "on" : "off") + " (" + reason + ")";
        }
    }

    private final OptimizationRegistry registry;
    private final Map<OptimizationId, State> states = new LinkedHashMap<OptimizationId, State>();

    private SwitchBoard(OptimizationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Optimization entry : registry.all()) {
            states.put(entry.id(), new State(entry.defaultEnabled(), Reason.DEFAULT, ""));
        }
    }

    /** A board at the registry's defaults. */
    public static SwitchBoard defaults(OptimizationRegistry registry) {
        return new SwitchBoard(registry);
    }

    public OptimizationRegistry registry() {
        return registry;
    }

    public boolean isEnabled(OptimizationId id) {
        State state = states.get(id);
        if (state == null) {
            // An id nobody registered is off. Returning false rather than throwing means a
            // Mixin guarded on a stale id fails closed — it does nothing — instead of
            // crashing the game on a rename.
            return false;
        }
        return state.enabled;
    }

    public boolean isEnabled(String id) {
        return isEnabled(OptimizationId.of(id));
    }

    public State state(OptimizationId id) {
        State state = states.get(id);
        if (state == null) {
            throw new IllegalArgumentException("no such optimization: " + id);
        }
        return state;
    }

    /**
     * The user turned something on or off.
     *
     * @return whether anything changed, so a caller can avoid a pointless save
     */
    public boolean setByUser(OptimizationId id, boolean enabled) {
        return set(id, enabled, Reason.USER, "");
    }

    /**
     * Turn something off because of a conflict.
     *
     * <p>Never overrides a user's explicit choice to have it <em>on</em>: if someone has
     * deliberately enabled an optimization knowing OptiFine is installed, silently undoing
     * that is worse than the conflict. The conflict is still reported; it just is not
     * acted on unilaterally.
     *
     * @return whether the switch actually moved
     */
    public boolean disableForConflict(OptimizationId id, String detail) {
        State current = states.get(id);
        if (current != null && current.enabled && current.reason == Reason.USER) {
            return false;
        }
        return set(id, false, Reason.CONFLICT, detail);
    }

    public boolean disableAsIncompatible(OptimizationId id, String detail) {
        // Incompatibility is not negotiable: there is nothing for the optimization to
        // attach to, so a user's preference cannot make it work.
        return set(id, false, Reason.INCOMPATIBLE, detail);
    }

    public boolean disableBySafeguard(OptimizationId id, String detail) {
        // Same: a safeguard fires because something went wrong, and a preference cannot
        // make it right.
        return set(id, false, Reason.SAFEGUARD, detail);
    }

    private boolean set(OptimizationId id, boolean enabled, Reason reason, String detail) {
        if (!registry.contains(id)) {
            throw new IllegalArgumentException("no such optimization: " + id);
        }
        State current = states.get(id);
        if (current != null && current.enabled == enabled && current.reason == reason) {
            return false;
        }
        states.put(id, new State(enabled, reason, detail));
        return true;
    }

    /**
     * Apply a preset.
     *
     * <p>Leaves alone anything the user chose explicitly, and anything switched off by a
     * conflict, an incompatibility or a safeguard. A preset is a starting point, not an
     * override: "Safe defaults" should not silently re-enable a feature that crashed
     * last session.
     *
     * @return the ids the preset actually moved
     */
    public List<OptimizationId> applyPreset(Preset preset) {
        List<OptimizationId> moved = new ArrayList<OptimizationId>();
        for (Optimization entry : registry.all()) {
            State current = states.get(entry.id());
            if (current != null
                    && (current.reason == Reason.USER
                            || current.reason == Reason.CONFLICT
                            || current.reason == Reason.INCOMPATIBLE
                            || current.reason == Reason.SAFEGUARD)) {
                continue;
            }
            boolean wanted = preset.includes(entry);
            if (set(entry.id(), wanted, Reason.PRESET, preset.name())) {
                moved.add(entry.id());
            }
        }
        return moved;
    }

    /** Everything currently on. */
    public List<Optimization> enabled() {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : registry.all()) {
            if (isEnabled(entry.id())) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Everything we turned off that the user might want back. */
    public List<Optimization> offAutomatically() {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : registry.all()) {
            State state = states.get(entry.id());
            if (state != null && state.offAutomatically()) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Offer back anything that was turned off for a conflict that no longer exists.
     *
     * @param stillConflicting names of software still present
     * @return the ids that were re-enabled
     */
    public List<OptimizationId> releaseResolvedConflicts(List<String> stillConflicting) {
        List<OptimizationId> released = new ArrayList<OptimizationId>();
        for (Optimization entry : registry.all()) {
            State state = states.get(entry.id());
            if (state == null || state.reason != Reason.CONFLICT) {
                continue;
            }
            boolean stillBlocked = false;
            for (Conflict conflict : entry.conflicts()) {
                if (stillConflicting.contains(conflict.with())) {
                    stillBlocked = true;
                    break;
                }
            }
            if (!stillBlocked) {
                states.put(entry.id(), new State(entry.defaultEnabled(), Reason.DEFAULT, ""));
                released.add(entry.id());
            }
        }
        return released;
    }

    /** An immutable copy, safe to read from another thread. */
    public Map<OptimizationId, State> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<OptimizationId, State>(states));
    }

    /** Only what the user chose, which is all that is worth persisting. */
    public Map<OptimizationId, Boolean> userChoices() {
        Map<OptimizationId, Boolean> out = new LinkedHashMap<OptimizationId, Boolean>();
        for (Map.Entry<OptimizationId, State> entry : states.entrySet()) {
            if (entry.getValue().chosenByUser()) {
                out.put(entry.getKey(), entry.getValue().enabled);
            }
        }
        return out;
    }

    /**
     * Restore choices previously saved by {@link #userChoices()}.
     *
     * <p>Unknown ids are skipped rather than rejected: a configuration written by a newer
     * build, or one naming an optimization since removed, should still restore everything
     * it can. Losing one setting is better than losing all of them.
     *
     * @return ids that were skipped because this build does not know them
     */
    public List<String> restoreUserChoices(Map<String, Boolean> saved) {
        List<String> unknown = new ArrayList<String>();
        for (Map.Entry<String, Boolean> entry : saved.entrySet()) {
            OptimizationId id;
            try {
                id = OptimizationId.of(entry.getKey());
            } catch (IllegalArgumentException malformed) {
                unknown.add(entry.getKey());
                continue;
            }
            if (!registry.contains(id)) {
                unknown.add(entry.getKey());
                continue;
            }
            setByUser(id, Boolean.TRUE.equals(entry.getValue()));
        }
        return unknown;
    }
}
