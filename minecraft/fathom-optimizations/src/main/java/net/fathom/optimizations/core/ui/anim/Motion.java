package net.fathom.optimizations.core.ui.anim;

import net.fathom.optimizations.core.ui.Colour;

/**
 * One interruptible value sampled from the shared monotonic UI clock.
 *
 * <p>A motion stores a start value, destination and start timestamp. It does
 * not count frames and it is never advanced by the tick loop. Retargeting
 * samples the current interpolated value first, so a rapid enter/leave reverses
 * immediately without jumping or waiting for the previous trip to finish.
 */
public final class Motion {

    private final float defaultDuration;
    private final Ease.Curve defaultCurve;
    private float duration;
    private float from;
    private float target;
    private float startedAt;
    private Ease.Curve curve;

    public Motion(float initial, float durationSeconds) {
        this(initial, durationSeconds, Ease.Curve.STANDARD);
    }

    public Motion(float initial, float durationSeconds, Ease.Curve curve) {
        this.from = finite(initial) ? initial : 0f;
        this.target = this.from;
        this.defaultDuration = duration(durationSeconds);
        this.defaultCurve = curve == null ? Ease.Curve.STANDARD : curve;
        this.duration = defaultDuration;
        this.curve = defaultCurve;
    }

    /** Retarget at this monotonic elapsed time. */
    public void to(float next, float now) {
        to(next, now, defaultDuration, defaultCurve);
    }

    /**
     * Retarget with a duration for this trip only.
     *
     * <p>For motion that should not be symmetric: a hover that lights quickly
     * and fades a little more slowly answers the pointer without flickering when
     * it leaves. The duration is taken only when the target actually changes.
     */
    public void to(float next, float now, float durationSeconds) {
        to(next, now, durationSeconds, defaultCurve);
    }

    /** Retarget with a duration and one of Fathom's intentional curves. */
    public void to(float next, float now, float durationSeconds, Ease.Curve nextCurve) {
        if (!finite(next) || next == target) {
            return;
        }
        from = value(now);
        target = next;
        startedAt = now;
        duration = duration(durationSeconds);
        curve = nextCurve == null ? Ease.Curve.STANDARD : nextCurve;
    }

    /** Jump immediately. */
    public void set(float next, float now) {
        if (!finite(next)) {
            return;
        }
        from = next;
        target = next;
        startedAt = now;
    }

    /** Current value at the supplied monotonic elapsed time. */
    public float value(float now) {
        if (from == target) {
            return target;
        }
        float progress = progress(now);
        if (progress >= 1f) {
            // Store the exact destination. Besides avoiding a permanent tail,
            // subsequent reads become the cheap branch above.
            from = target;
            return target;
        }
        return Ease.mix(from, target, curve.apply(progress));
    }

    /** This scalar interpreted as opacity/progress, clamped to 0..1. */
    public float opacity(float now) {
        return Ease.clamp01(value(now));
    }

    /** Map a 0..1 motion onto position, scale, radius or any scalar range. */
    public float between(float low, float high, float now) {
        return Ease.mix(low, high, opacity(now));
    }

    /** Interpolate a packed ARGB colour using this motion's 0..1 value. */
    public int colour(int fromColour, int toColour, float now) {
        return Colour.mix(fromColour, toColour, opacity(now));
    }

    /** Where it is heading. */
    public float target() {
        return target;
    }

    /** 0 to 1, uneased. */
    public float progress(float now) {
        return from == target ? 1f : Ease.clamp01((now - startedAt) / duration);
    }

    public boolean settled(float now) {
        return progress(now) >= 1f;
    }

    private static float duration(float seconds) {
        return finite(seconds) && seconds > 0f ? seconds : 0.0001f;
    }

    private static boolean finite(float value) {
        return value == value && value != Float.POSITIVE_INFINITY && value != Float.NEGATIVE_INFINITY;
    }
}
