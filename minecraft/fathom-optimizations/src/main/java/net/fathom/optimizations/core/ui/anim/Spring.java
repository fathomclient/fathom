package net.fathom.optimizations.core.ui.anim;

/**
 * A small allocation-free damped spring, for scrolling.
 *
 * <p>The analytical damped-oscillator solution makes the result depend on
 * elapsed time rather than frame count and remains stable when FPS is low.
 * {@link #smooth} is critically damped and cannot cross its target: the
 * interface has no bouncing anything.
 */
public final class Spring {

    private static final float SETTLE_DISTANCE = 0.001f;
    private static final float SETTLE_VELOCITY = 0.001f;

    private final float angularFrequency;
    private final float damping;
    private final boolean allowOvershoot;
    private float value;
    private float velocity;

    /** A critically damped spring suitable for scrolling and bounded values. */
    public static Spring smooth(float initial) {
        return new Spring(initial, Timing.PANEL, 1f, false);
    }

    public Spring(float initial, float responseSeconds, float dampingRatio, boolean overshoot) {
        value = finite(initial) ? initial : 0f;
        float response = finite(responseSeconds) && responseSeconds > 0f ? responseSeconds : 0.001f;
        angularFrequency = (float) (Math.PI * 2.0) / response;
        damping = finite(dampingRatio) ? Math.max(0.01f, Math.min(1f, dampingRatio)) : 1f;
        allowOvershoot = overshoot;
    }

    /** Advance towards {@code target} by a bounded real-time delta. */
    public float update(float target, float deltaSeconds) {
        if (!finite(target)) {
            target = 0f;
        }
        float delta = deltaSeconds < 0f || !finite(deltaSeconds)
                ? 0f
                : Math.min(Clock.MAX_DELTA, deltaSeconds);
        if (delta == 0f) {
            return value;
        }

        float before = value - target;
        if (Math.abs(before) <= SETTLE_DISTANCE && Math.abs(velocity) <= SETTLE_VELOCITY) {
            value = target;
            velocity = 0f;
            return value;
        }

        float omega = angularFrequency;
        if (damping >= 0.999f) {
            // Exact critically damped solution.
            float envelope = (float) Math.exp(-omega * delta);
            float impulse = (velocity + omega * before) * delta;
            value = target + (before + impulse) * envelope;
            velocity = (velocity - omega * impulse) * envelope;
        } else {
            // Exact under-damped solution, for a spring built with a damping ratio below 1.
            float damped = omega * damping;
            float frequency = omega * (float) Math.sqrt(1f - damping * damping);
            float envelope = (float) Math.exp(-damped * delta);
            float angle = frequency * delta;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            float second = (velocity + damped * before) / frequency;
            float displacement = envelope * (before * cosine + second * sine);
            float nextVelocity = envelope * (-damped * (before * cosine + second * sine)
                    + (-before * frequency * sine + second * frequency * cosine));
            value = target + displacement;
            velocity = nextVelocity;
        }

        if (!allowOvershoot && crossed(before, value - target)) {
            value = target;
            velocity = 0f;
        } else if (Math.abs(value - target) <= SETTLE_DISTANCE
                && Math.abs(velocity) <= SETTLE_VELOCITY) {
            value = target;
            velocity = 0f;
        }
        return value;
    }

    /** Jump without retaining momentum. */
    public void set(float next) {
        value = finite(next) ? next : 0f;
        velocity = 0f;
    }

    public float value() {
        return value;
    }

    public boolean settled(float target) {
        return Math.abs(value - target) <= SETTLE_DISTANCE
                && Math.abs(velocity) <= SETTLE_VELOCITY;
    }

    private static boolean crossed(float before, float after) {
        return (before < 0f && after >= 0f) || (before > 0f && after <= 0f);
    }

    private static boolean finite(float value) {
        return value == value && value != Float.POSITIVE_INFINITY && value != Float.NEGATIVE_INFINITY;
    }
}
