package net.fathom.optimizations.core.ui.render;

/**
 * What fills a shape: a colour, a gradient or an image.
 *
 * <h2>Mutable, on purpose</h2>
 *
 * <p>This is set up and used every frame. An immutable paint would be an
 * allocation per gradient per frame, so a paint is a reusable description: a
 * component holds one in a field and reshapes it before each use.
 *
 * <pre>
 *   private final Paint sheen = new Paint();
 *   ...
 *   ui.rounded(l, t, r, b, radius, sheen.vertical(t, b, top, bottom));
 * </pre>
 *
 * <p>Coordinates are logical units in the same space as the shape, so a paint
 * moves with {@code pushTransform} like everything else. Colours are Fathom's
 * packed ARGB, straight alpha; {@code pushAlpha} multiplies them.
 *
 * <p>New kinds are added here and to the backends; nothing that already uses a
 * paint changes.
 */
public final class Paint {

    public enum Kind {
        /** One colour: {@link #inner}. */
        SOLID,
        /** From {@link #inner} at (x0, y0) to {@link #outer} at (x1, y1). */
        LINEAR,
        /** From {@link #inner} at radius a to {@link #outer} at radius b, around (x0, y0). */
        RADIAL,
        /** A blurred rounded rectangle: (x0, y0, x1, y1) with radius a and feather b. */
        BOX
    }

    private Kind kind = Kind.SOLID;
    private float x0;
    private float y0;
    private float x1;
    private float y1;
    private float a;
    private float b;
    private int inner;
    private int outer;

    public Paint solid(int argb) {
        kind = Kind.SOLID;
        inner = argb;
        outer = argb;
        return this;
    }

    /** A gradient along any line, in logical units. */
    public Paint linear(float fromX, float fromY, float toX, float toY, int fromArgb, int toArgb) {
        kind = Kind.LINEAR;
        x0 = fromX;
        y0 = fromY;
        x1 = toX;
        y1 = toY;
        inner = fromArgb;
        outer = toArgb;
        return this;
    }

    /** Top to bottom, between two heights. */
    public Paint vertical(float top, float bottom, int topArgb, int bottomArgb) {
        return linear(0f, top, 0f, bottom, topArgb, bottomArgb);
    }

    /** Left to right, between two x positions. */
    public Paint horizontal(float left, float right, int leftArgb, int rightArgb) {
        return linear(left, 0f, right, 0f, leftArgb, rightArgb);
    }

    /** A circular gradient: solid inside {@code innerRadius}, fading out by {@code outerRadius}. */
    public Paint radial(float centreX, float centreY, float innerRadius, float outerRadius,
            int innerArgb, int outerArgb) {
        kind = Kind.RADIAL;
        x0 = centreX;
        y0 = centreY;
        a = innerRadius;
        b = outerRadius;
        inner = innerArgb;
        outer = outerArgb;
        return this;
    }

    /** A soft rounded rectangle, the building block of shadows and glows. */
    public Paint box(float left, float top, float right, float bottom, float radius, float feather,
            int innerArgb, int outerArgb) {
        kind = Kind.BOX;
        x0 = left;
        y0 = top;
        x1 = right;
        y1 = bottom;
        a = radius;
        b = feather;
        inner = innerArgb;
        outer = outerArgb;
        return this;
    }

    public Kind kind() {
        return kind;
    }

    public float x0() {
        return x0;
    }

    public float y0() {
        return y0;
    }

    public float x1() {
        return x1;
    }

    public float y1() {
        return y1;
    }

    /** Inner radius (radial) or corner radius (box). */
    public float a() {
        return a;
    }

    /** Outer radius (radial) or feather (box). */
    public float b() {
        return b;
    }

    public int inner() {
        return inner;
    }

    public int outer() {
        return outer;
    }

    /** Whether drawing with this could change a pixel. */
    public boolean invisible() {
        return ((inner >>> 24) & 0xFF) == 0 && ((outer >>> 24) & 0xFF) == 0;
    }
}
