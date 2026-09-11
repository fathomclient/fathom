package net.fathom.optimizations.core.hud;

/**
 * Where one widget sits and how big it is.
 *
 * <h2>Why there is no "enabled" here</h2>
 *
 * <p>There used to be, and it was the client's worst defect. A widget was drawn
 * only when its module was on <em>and</em> its placement said enabled, and the
 * shipped layout said disabled for nine of the eleven widgets. So switching a
 * module on in Mods merely <em>permitted</em> its widget; the layout still hid
 * it, and the toggle looked broken because it was.
 *
 * <p>One switch now, and it is the module's. This class answers where, and only
 * where.
 *
 * <p>Immutable, and every mutator returns a new instance. The HUD editor changes a
 * placement on every mouse-move, and a value type makes "what did it look like before I
 * dragged it" a variable rather than a snapshot protocol.
 */
public final class Placement {

    /** Below this a widget is unreadable; above it, it stops being a HUD. */
    public static final float MIN_SCALE = 0.5f;

    public static final float MAX_SCALE = 3.0f;

    private final Anchor anchor;
    private final int offsetX;
    private final int offsetY;
    private final float scale;
    private final float opacity;

    public Placement(Anchor anchor, int offsetX, int offsetY, float scale, float opacity) {
        this.anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        this.opacity = clamp(opacity, 0.1f, 1f);
    }

    public static Placement at(Anchor anchor, int offsetX, int offsetY) {
        return new Placement(anchor, offsetX, offsetY, 1f, 1f);
    }

    private static float clamp(float value, float low, float high) {
        if (Float.isNaN(value)) {
            return high == 1f ? 1f : low;
        }
        return Math.max(low, Math.min(high, value));
    }

    public Anchor anchor() {
        return anchor;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public float scale() {
        return scale;
    }

    public float opacity() {
        return opacity;
    }

    public Placement withAnchor(Anchor next) {
        return new Placement(next, offsetX, offsetY, scale, opacity);
    }

    public Placement withOffset(int x, int y) {
        return new Placement(anchor, x, y, scale, opacity);
    }

    public Placement withScale(float next) {
        return new Placement(anchor, offsetX, offsetY, next, opacity);
    }

    public Placement withOpacity(float next) {
        return new Placement(anchor, offsetX, offsetY, scale, next);
    }

    /** The alpha byte, for packing into an ARGB colour. */
    public int alpha() {
        return Math.max(0, Math.min(255, Math.round(opacity * 255f)));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Placement)) {
            return false;
        }
        Placement that = (Placement) other;
        return anchor == that.anchor
                && offsetX == that.offsetX
                && offsetY == that.offsetY
                && Float.compare(scale, that.scale) == 0
                && Float.compare(opacity, that.opacity) == 0;
    }

    @Override
    public int hashCode() {
        int result = anchor.hashCode();
        result = 31 * result + offsetX;
        result = 31 * result + offsetY;
        result = 31 * result + Float.floatToIntBits(scale);
        return 31 * result + Float.floatToIntBits(opacity);
    }

    @Override
    public String toString() {
        return anchor + "+" + offsetX + "," + offsetY + " x" + scale;
    }
}
