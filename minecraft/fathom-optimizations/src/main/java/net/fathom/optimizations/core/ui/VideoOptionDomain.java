package net.fathom.optimizations.core.ui;

/** Valid domains for vanilla values exposed by Fathom's Quick page. */
public final class VideoOptionDomain {

    /** Minecraft 1.8.9's mapped field stores degrees, not a modern 0..1 fraction. */
    public static final float MIN_FOV = 30f;
    public static final float NORMAL_FOV = 70f;
    public static final float MAX_FOV = 110f;

    private VideoOptionDomain() {}

    /** Preserve a valid FOV exactly; repair old non-finite/out-of-domain data. */
    public static float fov(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) || value < MIN_FOV || value > MAX_FOV
                ? NORMAL_FOV
                : value;
    }

    /** Convert the 1.8.9 degree field to a control fraction. */
    public static float fovFraction(float degrees) {
        return (fov(degrees) - MIN_FOV) / (MAX_FOV - MIN_FOV);
    }

    /** Convert a control fraction back to the 1.8.9 degree field. */
    public static float fovDegrees(double fraction) {
        double bounded = Math.max(0d, Math.min(1d, fraction));
        return MIN_FOV + (float) bounded * (MAX_FOV - MIN_FOV);
    }
}
