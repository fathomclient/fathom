package net.fathom.optimizations.core.ui.anim;

/**
 * The intentionally small set of easing curves used by Fathom animation.
 *
 * <h2>Why one dominant family</h2>
 *
 * <p>The launcher has a single dominant {@code --ease} token. An
 * interface where different controls decelerate differently reads as several
 * interfaces, and nobody can say why. The curve here is the launcher's:
 * cubic-bezier(0.2, 0.8, 0.2, 1).
 *
 * <p>The standard is ease-<em>out</em> rather than ease-in-out because a transition the user
 * caused should begin at full speed. They already know it started; they pressed
 * the thing. Smooth is for motion that begins on its own; exit is a shorter,
 * quieter ease-out. Those are the only exceptions.
 */
public final class Ease {

    /** The only interpolation curves used by ordinary UI motion. */
    public enum Curve {
        /** Immediate response followed by a controlled deceleration. */
        STANDARD {
            @Override public float apply(float amount) { return Ease.out(amount); }
        },
        /** Symmetric motion for content that moves without a direct press. */
        SMOOTH {
            @Override public float apply(float amount) { return Ease.inOut(amount); }
        },
        /** A prompt, slightly quieter deceleration used only for dismissal. */
        EXIT {
            @Override public float apply(float amount) { return Ease.exit(amount); }
        };

        public abstract float apply(float amount);
    }

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

    /** A responsive ease-out for dismissal; shorter and quieter than entry. */
    public static float exit(float t) {
        float x = clamp01(t);
        return x * (2f - x);
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

    /** Sample a delayed, time-based transition without allocating a Motion. */
    public static float sample(float now, float startedAt, float delay, float duration, Curve curve) {
        if (duration <= 0f) {
            return now >= startedAt + delay ? 1f : 0f;
        }
        float progress = clamp01((now - startedAt - Math.max(0f, delay)) / duration);
        return (curve == null ? Curve.STANDARD : curve).apply(progress);
    }

    public static float clamp01(float value) {
        if (value != value) {
            return 0f;
        }
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
