package net.fathom.optimizations.core.module;

/**
 * How finished a module is, and what the interface is allowed to offer for it.
 *
 * <h2>The rule this exists to enforce</h2>
 *
 * <p><b>Never show a normal toggle for something that does not do anything.</b>
 * The client shipped with a list of modules where several switches changed a
 * boolean that nothing read: the module looked on, nothing appeared, and the
 * only way to find out was to try it. That is worse than not offering the
 * module at all, because it teaches people that Fathom's switches are
 * decorative.
 *
 * <p>So a module declares what it is, and the interface obeys:
 *
 * <ul>
 *   <li>{@link #WORKING} — switching it on changes the game. A plain toggle.
 *   <li>{@link #EXPERIMENTAL} — it works, with a caveat worth reading first. A
 *       toggle, and a badge saying so.
 *   <li>{@link #UNAVAILABLE} — it does not work here, in this version, or yet.
 *       <b>No toggle at all</b>; the row says why instead.
 * </ul>
 *
 * <p>{@code ModuleTests} asserts that every {@code WORKING} module has at least
 * one real effect — a HUD widget the renderer knows, an optimization the switch
 * board knows, or a declared behaviour — so this cannot rot back into a label.
 */
public enum Implementation {

    /** Does what it says. */
    WORKING("Working", true),

    /** Does what it says, with a caveat. */
    EXPERIMENTAL("Experimental", true),

    /** Does not work here. The interface offers no switch. */
    UNAVAILABLE("Unavailable", false);

    private final String label;
    private final boolean switchable;

    Implementation(String label, boolean switchable) {
        this.label = label;
        this.switchable = switchable;
    }

    public String label() {
        return label;
    }

    /** Whether the interface may draw an on/off control for this. */
    public boolean switchable() {
        return switchable;
    }

    /** Whether the interface should say something extra about it. */
    public boolean noteworthy() {
        return this != WORKING;
    }
}
