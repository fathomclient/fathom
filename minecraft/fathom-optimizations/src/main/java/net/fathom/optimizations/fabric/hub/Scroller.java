package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Spring;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.generated.FathomTheme.Tokens;
import net.fathom.optimizations.fabric.ui.VisualEffects;

/**
 * Scroll position for a list, and the rail that shows it.
 *
 * <p>Several sections need one and none of them needs a different one.
 *
 * <h2>The glide is a bounded spring</h2>
 *
 * <p>Advanced with the frame's delta from the one animation clock, so a scroll
 * decelerates at the same rate at 30 frames a second and at 300, and does not
 * move at all while nothing is being drawn.
 *
 * <h2>Clamped on read, not on write</h2>
 *
 * <p>The content height changes as somebody types in the search box. Clamping
 * when the wheel turns would use whichever height was current a moment ago, and
 * a filtered list would be left scrolled past its own end with nothing visible.
 * Clamping on read means the list is always showing something.
 */
public final class Scroller {

    /** Logical units per wheel notch. About two rows. */
    private static final float NOTCH = 110f;

    private final Spring position = Spring.smooth(0f);
    private final Motion visibility = new Motion(0.22f, Timing.STANDARD);
    private final Motion emphasis = new Motion(0f, Timing.HOVER_OUT);
    private float target;
    private float offset;
    private float contentHeight;
    private float viewportHeight;
    private float activeFor;
    private boolean dragging;
    private float dragGrab;

    /** Turn the wheel. Positive notches are away from the user. */
    public void wheel(int notches) {
        target -= notches * NOTCH;
        if (notches != 0) {
            activeFor = 0.65f;
        }
    }

    /** Put it back at the top. */
    public void reset() {
        offset = 0f;
        target = 0f;
        position.set(0f);
        activeFor = 0f;
        dragging = false;
    }

    /**
     * Where the list is, given how tall it is and how much fits.
     *
     * @param delta seconds since the last frame, from the one clock
     */
    public float offset(float delta, float content, float viewport) {
        this.contentHeight = content;
        this.viewportHeight = viewport;
        activeFor = Math.max(0f, activeFor - Math.max(0f, delta));

        float maximum = Math.max(0f, content - viewport);
        target = clamp(target, 0f, maximum);
        // A filtered list can become shorter between frames. Jumping an invalid
        // old offset to its new bound prevents a blank viewport; ordinary wheel
        // movement still follows the smooth spring.
        if (offset < 0f || offset > maximum) {
            offset = clamp(offset, 0f, maximum);
            position.set(offset);
        }
        offset = clamp(position.update(target, delta), 0f, maximum);
        return offset;
    }

    /**
     * The rail, at the right edge of the viewport it was last measured against.
     *
     * <p>Only when there is something to scroll: a track with a full-height
     * handle says "there is more" when there is not. Four units wide, inside the
     * content rather than beside it, because a scrollbar that takes layout space
     * makes every list narrower for the sake of the ones that overflow.
     */
    public void rail(Renderer ui, Rect viewport, Pointer pointer, float now) {
        if (contentHeight <= viewportHeight || viewport.height <= 0f) {
            dragging = false;
            visibility.set(0.22f, now);
            emphasis.set(0f, now);
            return;
        }
        float width = 4f;
        float x = viewport.right() - width;

        // Keep both end caps out of a rounded parent's corner quadrants. A
        // full-height rail reached the exact top and bottom of cards/panels and
        // was visible through their rectangular scissor at the curved corners.
        float endInset = Math.min(Skin.R_SMALL, viewport.height / 2f);
        float trackTop = viewport.y + endInset;
        float trackHeight = Math.max(0f, viewport.height - endInset * 2f);
        if (trackHeight <= 0f) {
            return;
        }

        float visible = viewportHeight / contentHeight;
        float handle = Math.min(trackHeight, Math.max(32f, trackHeight * visible));
        float progress = offset / Math.max(1f, contentHeight - viewportHeight);
        float handleY = trackTop + (trackHeight - handle) * progress;

        // The visual is four units wide, while the hit target is twelve. The
        // scrollbar becomes easier to grab without taking any content space.
        boolean hovered = pointer != null && pointer.over(x - 8f, handleY - 2f, 12f, handle + 4f);
        if (!dragging && hovered && pointer.pressed(0)) {
            dragging = true;
            dragGrab = pointer.y() - handleY;
            // The rail overlays content. Owning this press also swallows its
            // eventual release, so the row beneath cannot receive half a click.
            pointer.consume(0);
        }
        if (dragging) {
            if (pointer == null || !pointer.down(0)) {
                dragging = false;
            } else {
                float railTravel = Math.max(1f, trackHeight - handle);
                float next = clamp((pointer.y() - dragGrab - trackTop) / railTravel, 0f, 1f);
                target = next * Math.max(0f, contentHeight - viewportHeight);
                offset = target;
                position.set(target);
                activeFor = 0.65f;
                handleY = trackTop + railTravel * next;
            }
        }

        boolean active = dragging || hovered || activeFor > 0f;
        visibility.to(active ? 1f : 0.22f, now, active ? Timing.HOVER_IN : Timing.STANDARD);
        emphasis.to(dragging ? 1f : (hovered ? 0.62f : 0f), now,
                active ? Timing.HOVER_IN : Timing.HOVER_OUT);
        // The launcher's scrollbar: no track, a narrow neutral thumb inset
        // from the edge at 20%, 32% under the pointer or while dragged. It
        // never changes width and never turns white.
        float bright = emphasis.opacity(now);
        x = viewport.right() - width - 3f;
        ui.rounded(x, handleY, x + width, handleY + handle,
                net.fathom.optimizations.core.ui.render.RoundedGeometry.pill(width, handle),
                Skin.mix(Tokens.CONTROL_SCROLLBAR, Tokens.CONTROL_SCROLLBAR_HOVER, Math.min(1f, bright / 0.62f)));
    }

    /** Whether there is anything to scroll, for a caller that lays out around it. */
    public boolean overflowing() {
        return contentHeight > viewportHeight;
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }
}
