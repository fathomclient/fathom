package net.fathom.optimizations.core.ui.anim;

/**
 * One interruptible value sampled from the shared monotonic UI clock.
 *
 * <p>A motion stores a start value, destination and start timestamp. It does
 * not count frames and it is never advanced by the tick loop. Retargeting
 * samples the current interpolated value first, so a rapid enter/leave reverses
 * immediately without jumping or waiting for the previous trip to finish.
 */
public final class Motion {

    private final float duration;
    private float from;
    private float target;
    private float startedAt;

    public Motion(float initial, float durationSeconds) {
        this.from = initial;
        this.target = initial;
        this.duration = durationSeconds <= 0f ? 0.0001f : durationSeconds;
    }

    /** Retarget at this monotonic elapsed time. */
    public void to(float next, float now) {
        if (next == target) {
            return;
        }
        from = value(now);
        target = next;
        startedAt = now;
    }

    /** Jump immediately. */
    public void set(float next, float now) {
        from = next;
        target = next;
        startedAt = now;
    }

    /** Current value at the supplied monotonic elapsed time. */
    public float value(float now) {
        if (from == target) {
            return target;
        }
        return Ease.mix(from, target, Ease.out(progress(now)));
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
}
