package net.fathom.optimizations.core.ui.layout;

/**
 * A rectangle, in logical units.
 *
 * <h2>Why a value type and not four floats</h2>
 *
 * <p>Because four floats get passed in the wrong order, and because the first
 * version of this interface computed the same rectangle twice — once to draw a
 * control and once to decide whether a click had hit it. Every divergence
 * between those two copies was a control that highlighted in one place and
 * responded in another, and there were several.
 *
 * <p>Now a rectangle is computed once and handed to something that both draws
 * and hit-tests it. That is the whole reason the controls are immediate-mode.
 *
 * <p>Immutable, so a rectangle handed to a child cannot be edited underneath the
 * parent. The {@code inset}/{@code cut} family returns new ones.
 */
public final class Rect {

    public final float x;
    public final float y;
    public final float width;
    public final float height;

    public Rect(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0f, width);
        this.height = Math.max(0f, height);
    }

    public static Rect of(float left, float top, float right, float bottom) {
        return new Rect(left, top, right - left, bottom - top);
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public float centreX() {
        return x + width / 2f;
    }

    public float centreY() {
        return y + height / 2f;
    }

    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
    }

    /** Shrink on every side. */
    public Rect inset(float amount) {
        return inset(amount, amount);
    }

    public Rect inset(float horizontal, float vertical) {
        return new Rect(x + horizontal, y + vertical, width - horizontal * 2f, height - vertical * 2f);
    }

    public Rect inset(float left, float top, float right, float bottom) {
        return new Rect(x + left, y + top, width - left - right, height - top - bottom);
    }

    /** The top {@code amount} of this rectangle. */
    public Rect top(float amount) {
        return new Rect(x, y, width, Math.min(amount, height));
    }

    /** Everything below the top {@code amount}. */
    public Rect belowTop(float amount) {
        float taken = Math.min(amount, height);
        return new Rect(x, y + taken, width, height - taken);
    }

    /** The left {@code amount}. */
    public Rect left(float amount) {
        return new Rect(x, y, Math.min(amount, width), height);
    }

    /** Everything right of the left {@code amount}. */
    public Rect rightOf(float amount) {
        float taken = Math.min(amount, width);
        return new Rect(x + taken, y, width - taken, height);
    }

    /** A rectangle of this width at a given height, starting at the top. */
    public Rect row(float rowHeight) {
        return new Rect(x, y, width, rowHeight);
    }

    /** The same rectangle moved. */
    public Rect offset(float dx, float dy) {
        return new Rect(x + dx, y + dy, width, height);
    }

    /** A rectangle of this height, {@code w} wide, at the right edge. */
    public Rect rightAligned(float w) {
        return new Rect(x + width - w, y, w, height);
    }

    /** A box of the given size, centred vertically, at an offset from the left. */
    public Rect vcentre(float fromLeft, float boxWidth, float boxHeight) {
        return new Rect(x + fromLeft, y + (height - boxHeight) / 2f, boxWidth, boxHeight);
    }

    /** A box of the given size, centred vertically, at an offset from the right. */
    public Rect vcentreRight(float fromRight, float boxWidth, float boxHeight) {
        return new Rect(x + width - fromRight - boxWidth, y + (height - boxHeight) / 2f, boxWidth, boxHeight);
    }

    /** This rectangle clipped to another. Zero-sized when they do not overlap. */
    public Rect intersect(Rect other) {
        float left = Math.max(x, other.x);
        float top = Math.max(y, other.y);
        float right = Math.min(right(), other.right());
        float bottom = Math.min(bottom(), other.bottom());
        return right <= left || bottom <= top ? new Rect(left, top, 0f, 0f) : Rect.of(left, top, right, bottom);
    }

    public boolean empty() {
        return width <= 0f || height <= 0f;
    }

    @Override
    public String toString() {
        return "Rect[" + x + "," + y + " " + width + "x" + height + "]";
    }
}
