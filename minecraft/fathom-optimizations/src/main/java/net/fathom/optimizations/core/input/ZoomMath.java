package net.fathom.optimizations.core.input;

/**
 * The arithmetic of Zoom, where it can be tested.
 *
 * <p>Everything here is a pure function of numbers. The client-side class reads
 * keys and settings and hands them in; nothing here touches Minecraft.
 */
public final class ZoomMath {

    /** The narrowest a field of view is divided by, whatever the settings say. */
    public static final double HARD_MIN = 1.0;

    /** The most it is divided by. Past this the image is a handful of pixels. */
    public static final double HARD_MAX = 50.0;

    private ZoomMath() {}

    /**
     * The divisor for the field of view, part way through the animation.
     *
     * <p>Interpolated in log space. Zoom is a ratio, so equal steps of time
     * should be equal ratios: a linear walk from 1 to 8 does the first doubling
     * in the first seventh of the animation and the last in the final half,
     * which reads as a lurch followed by a crawl.
     */
    public static double divisor(double target, double progress) {
        double clampedTarget = clamp(target, HARD_MIN, HARD_MAX);
        double p = clamp(progress, 0.0, 1.0);
        return Math.exp(Math.log(clampedTarget) * p);
    }

    /**
     * One scroll of the wheel while zoomed.
     *
     * @param notches positive to zoom in
     * @param step the ratio per notch, above one
     */
    public static double scroll(double current, int notches, double step, double minimum, double maximum) {
        double low = clamp(Math.min(minimum, maximum), HARD_MIN, HARD_MAX);
        double high = clamp(Math.max(minimum, maximum), HARD_MIN, HARD_MAX);
        double ratio = Math.max(1.0001, step);
        double next = current * Math.pow(ratio, notches);
        return clamp(next, low, high);
    }

    /**
     * The mouse sensitivity that turns the camera {@code divisor} times slower.
     *
     * <h2>Why not just divide the sensitivity</h2>
     *
     * <p>Minecraft does not turn in proportion to its sensitivity option. It
     * computes {@code f = s * 0.6 + 0.2} and turns by {@code f³ × 8} per pixel of
     * mouse travel, so halving the option does not halve the turning — at the
     * default it slows it by about three quarters, and at low settings it can
     * stop the camera entirely. Dividing the option is why so many zooms feel
     * either sluggish or twitchy depending on the player's own setting.
     *
     * <p>This solves the curve instead: the result turns exactly
     * {@code divisor^strength} times slower than {@code sensitivity} does, at
     * every sensitivity. It can be negative, which vanilla's formula handles —
     * the cube of the resulting {@code f} is what matters, and it stays positive.
     *
     * @param strength 0 leaves the sensitivity alone, 1 matches the zoom exactly
     */
    public static float compensatedSensitivity(float sensitivity, double divisor, double strength) {
        double scale = Math.pow(clamp(divisor, HARD_MIN, HARD_MAX), clamp(strength, 0.0, 1.0));
        if (scale <= 1.0) {
            return sensitivity;
        }
        double f = sensitivity * 0.6 + 0.2;
        double compensated = f / Math.cbrt(scale);
        return (float) ((compensated - 0.2) / 0.6);
    }

    /** How fast vanilla turns the camera for a sensitivity option: the {@code f³ × 8} above. */
    public static double turnRate(float sensitivity) {
        double f = sensitivity * 0.6 + 0.2;
        return f * f * f * 8.0;
    }

    public static double clamp(double value, double low, double high) {
        if (Double.isNaN(value)) {
            return low;
        }
        return value < low ? low : (value > high ? high : value);
    }
}
