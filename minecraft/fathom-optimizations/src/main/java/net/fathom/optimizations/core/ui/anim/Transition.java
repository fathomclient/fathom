package net.fathom.optimizations.core.ui.anim;

/**
 * The enter/exit lifecycle shared by Fathom screens and modal panels.
 *
 * <p>Entry and exit are separate motions. Closing during entry therefore starts
 * from the exact value currently on screen instead of reversing a timer or
 * snapping to fully open first. The class owns no listener, thread or resource;
 * it is sampled only while its owner is drawn.
 */
public final class Transition {

    private final Motion entering;
    private final Motion exiting;
    private boolean closing;

    public Transition(float enterSeconds, float exitSeconds) {
        entering = new Motion(0f, enterSeconds, Ease.Curve.STANDARD);
        exiting = new Motion(0f, exitSeconds, Ease.Curve.EXIT);
    }

    /** Start or restart entry from the current monotonic time. */
    public void open(float now) {
        closing = false;
        entering.set(0f, now);
        exiting.set(0f, now);
        entering.to(1f, now);
    }

    /** Begin the independent exit. Safe to call repeatedly. */
    public void close(float now) {
        if (closing) {
            return;
        }
        closing = true;
        // Stop entry at the exact value currently visible. Otherwise a very
        // early close could keep getting brighter for a frame while entry's
        // ease-out outran the exit motion.
        entering.set(entering.value(now), now);
        exiting.to(1f, now);
    }

    /** Overall visible amount, exactly zero when exit completes. */
    public float visibility(float now) {
        return Ease.clamp01(entering.opacity(now) * (1f - exiting.opacity(now)));
    }

    /** Entry progress for the small position/scale offset accompanying opacity. */
    public float enter(float now) {
        return entering.opacity(now);
    }

    /** Exit progress for the small position/scale offset accompanying opacity. */
    public float exit(float now) {
        return exiting.opacity(now);
    }

    public boolean closing() {
        return closing;
    }

    public boolean entered(float now) {
        return entering.settled(now);
    }

    public boolean finished(float now) {
        return closing && exiting.settled(now);
    }
}
