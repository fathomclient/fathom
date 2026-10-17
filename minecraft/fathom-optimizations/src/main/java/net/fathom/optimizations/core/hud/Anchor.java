package net.fathom.optimizations.core.hud;

/**
 * Which corner or edge of the screen a widget is measured from.
 *
 * <p>A HUD widget stored as an absolute pixel position is wrong the moment the window is
 * resized or the GUI scale changes, and 1.8.9 players change GUI scale constantly. Storing
 * an anchor plus an offset means a widget pinned to the bottom-right stays in the
 * bottom-right at any resolution, which is what the person who put it there meant.
 *
 * <p>The offset is always measured <em>inward</em>: a widget at {@code TOP_RIGHT} with
 * offset (4, 4) sits four pixels from the top and four from the right. That is why
 * {@link #x} and {@link #y} take the widget's own size — the right-hand anchors have to
 * subtract it.
 */
public enum Anchor {
    TOP_LEFT("Top left", 0f, 0f),
    TOP_CENTER("Top", 0.5f, 0f),
    TOP_RIGHT("Top right", 1f, 0f),
    MIDDLE_LEFT("Left", 0f, 0.5f),
    CENTER("Centre", 0.5f, 0.5f),
    MIDDLE_RIGHT("Right", 1f, 0.5f),
    BOTTOM_LEFT("Bottom left", 0f, 1f),
    BOTTOM_CENTER("Bottom", 0.5f, 1f),
    BOTTOM_RIGHT("Bottom right", 1f, 1f);

    private final String label;
    private final float horizontal;
    private final float vertical;

    Anchor(String label, float horizontal, float vertical) {
        this.label = label;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public String label() {
        return label;
    }

    /** 0 at the left edge, 0.5 centred, 1 at the right edge. */
    public float horizontal() {
        return horizontal;
    }

    public float vertical() {
        return vertical;
    }

    /** The left edge of a widget of this width, in screen pixels. */
    public int x(int screenWidth, int widgetWidth, int offsetX) {
        int base = Math.round((screenWidth - widgetWidth) * horizontal);
        // Offsets run inward from the anchored edge, so a right-hand anchor
        // subtracts. Without this, dragging a bottom-right widget "in" from the
        // corner would move it off the screen.
        return horizontal > 0.5f ? base - offsetX : base + offsetX;
    }

    public int y(int screenHeight, int widgetHeight, int offsetY) {
        int base = Math.round((screenHeight - widgetHeight) * vertical);
        return vertical > 0.5f ? base - offsetY : base + offsetY;
    }

    /**
     * The anchor a point belongs to, used when a drag ends.
     *
     * <p>Thirds in both axes. Re-anchoring on release is what makes a dragged widget stay
     * where it was put after a resolution change: drag something to the bottom-right and
     * it becomes bottom-right-anchored, rather than staying top-left-anchored with a large
     * offset that stops meaning anything at another size.
     */
    public static Anchor nearest(int x, int y, int screenWidth, int screenHeight) {
        int column = third(x, screenWidth);
        int row = third(y, screenHeight);
        return values()[row * 3 + column];
    }

    private static int third(int value, int extent) {
        if (extent <= 0) {
            return 1;
        }
        float fraction = (float) value / (float) extent;
        if (fraction < 1f / 3f) {
            return 0;
        }
        return fraction < 2f / 3f ? 1 : 2;
    }
}
