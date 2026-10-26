package net.fathom.optimizations.core.ui.layout;

/**
 * The arithmetic of a scrolling list and its rail, without drawing.
 *
 * <p>Kept apart from the renderer so the parts that are easy to get subtly
 * wrong (the thumb's size and place, dragging it, paging by the track, and
 * how much of a wheel turn a nested list takes before the rest goes to its
 * parent) can be checked with plain numbers.
 */
public final class ScrollMath {

    /** Logical units per wheel notch. About two rows. */
    public static final float NOTCH = 110f;

    /** Smallest thumb, so it can always be grabbed. */
    public static final float MIN_THUMB = 32f;

    /** How much of a viewport a click on the track moves by: a little less than all of it. */
    public static final float PAGE = 0.9f;

    private ScrollMath() {}

    public static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }

    /** The furthest a list can scroll. Never negative: content that fits does not scroll. */
    public static float max(float content, float viewport) {
        return Math.max(0f, content - viewport);
    }

    /** The thumb's length on a track of {@code track} units. */
    public static float thumbLength(float track, float viewport, float content) {
        if (content <= 0f) {
            return track;
        }
        return Math.min(track, Math.max(MIN_THUMB, track * viewport / content));
    }

    /** The thumb's top edge for an offset. */
    public static float thumbTop(float trackTop, float track, float thumb, float offset, float max) {
        float progress = max <= 0f ? 0f : clamp(offset / max, 0f, 1f);
        return trackTop + (track - thumb) * progress;
    }

    /**
     * The offset a thumb dragged to {@code pointerY} stands for, holding the
     * point of the thumb that was first grabbed under the pointer.
     */
    public static float offsetForDrag(float pointerY, float grab, float trackTop, float track, float thumb,
            float max) {
        float travel = Math.max(1f, track - thumb);
        return clamp((pointerY - grab - trackTop) / travel, 0f, 1f) * max;
    }

    /** Where a click on the track, above or below the thumb, takes the list. */
    public static float pageTarget(float target, float viewport, boolean below, float max) {
        float step = viewport * PAGE;
        return clamp(below ? target + step : target - step, 0f, max);
    }

    /**
     * How many of {@code notches} this list takes. Positive notches scroll
     * towards the top. A list takes as many as it has room for, rounding the
     * last partial notch up (it clamps there) and none when it is already at
     * that end.
     */
    public static int consumed(int notches, float target, float max) {
        if (notches == 0) {
            return 0;
        }
        float room = notches > 0 ? target : max - target;
        if (room <= 0.5f) {
            return 0;
        }
        int fits = (int) Math.ceil(room / NOTCH);
        return Math.min(Math.abs(notches), fits);
    }

    /** What is left of a wheel turn after a list took its share, for its parent. */
    public static int leftover(int notches, float target, float max) {
        int taken = consumed(notches, target, max);
        return notches > 0 ? notches - taken : notches + taken;
    }
}
