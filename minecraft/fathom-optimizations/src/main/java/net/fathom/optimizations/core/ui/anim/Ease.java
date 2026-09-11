package net.fathom.optimizations.core.ui.anim;

import net.fathom.optimizations.generated.FathomTheme;

/**
 * One easing family, and the three durations that go with it.
 *
 * <h2>Why one family</h2>
 *
 * <p>The launcher has a single {@code --ease} token and three durations. An
 * interface where different controls decelerate differently reads as several
 * interfaces, and nobody can say why. The curve here is the launcher's:
 * cubic-bezier(0.2, 0.8, 0.2, 1).
 *
 * <p>Ease-<em>out</em> rather than ease-in-out because a transition the user
 * caused should begin at full speed. They already know it started; they pressed
 * the thing. Ease-in-out is for motion that begins on its own.
 */
public final class Ease {

    /** Selection marks, hovers, toggles. Seconds. */
    public static final float FAST = FathomTheme.MOTION_FAST;
    /** The default: section changes, popovers, status. */
    public static final float NORMAL = FathomTheme.MOTION_NORMAL;
    /** A screen opening or closing. Nothing else without a reason. */
    public static final float SLOW = FathomTheme.MOTION_SLOW;

    private Ease() {}

    /** The shared Fathom cubic Bézier, evaluated in the same x-to-y form as CSS. */
    public static float out(float t) {
        float x = clamp01(t);
        if (x == 0f || x == 1f) {
            return x;
        }
        float parameter = x;
        for (int iteration = 0; iteration < 5; iteration++) {
            float estimate = bezier(parameter, 0.2f, 0.2f) - x;
            float slope = derivative(parameter, 0.2f, 0.2f);
            if (Math.abs(slope) < 0.0001f) {
                break;
            }
            parameter = clamp01(parameter - estimate / slope);
        }
        float low = 0f;
        float high = 1f;
        for (int iteration = 0; iteration < 8; iteration++) {
            float estimate = bezier(parameter, 0.2f, 0.2f);
            if (Math.abs(estimate - x) < 0.0001f) {
                break;
            }
            if (estimate < x) {
                low = parameter;
            } else {
                high = parameter;
            }
            parameter = (low + high) * 0.5f;
        }
        return clamp01(bezier(parameter, 0.8f, 1f));
    }

    private static float bezier(float t, float first, float second) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * t * first
                + 3f * inverse * t * t * second
                + t * t * t;
    }

    private static float derivative(float t, float first, float second) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * first
                + 6f * inverse * t * (second - first)
                + 3f * t * t * (1f - second);
    }

    /**
     * The same curve reversed, for something leaving.
     *
     * <p>Used only where a thing has to accelerate away — a dismissed toast.
     * A control that eased in should ease out on the same curve, not this one.
     */
    public static float in(float t) {
        float x = clamp01(t);
        return x * x * x;
    }

    /** For motion the interface starts by itself. Rare, on purpose. */
    public static float inOut(float t) {
        float x = clamp01(t);
        if (x < 0.5f) {
            return 4f * x * x * x;
        }
        float f = -2f * x + 2f;
        return 1f - f * f * f / 2f;
    }

    /** Linear interpolation, for colours and positions. */
    public static float mix(float from, float to, float amount) {
        return from + (to - from) * clamp01(amount);
    }

    public static float clamp01(float value) {
        if (value != value) {
            return 0f;
        }
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
