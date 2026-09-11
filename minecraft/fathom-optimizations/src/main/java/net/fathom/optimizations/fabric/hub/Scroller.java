package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * Scroll position for a list, and the rail that shows it.
 *
 * <p>Several sections need one and none of them needs a different one.
 *
 * <h2>The glide is delta-driven</h2>
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

    private float offset;
    private float velocity;
    private float contentHeight;
    private float viewportHeight;

    /** Turn the wheel. Positive notches are away from the user. */
    public void wheel(int notches) {
        velocity -= notches * NOTCH;
    }

    /** Put it back at the top. */
    public void reset() {
        offset = 0f;
        velocity = 0f;
    }

    /**
     * Where the list is, given how tall it is and how much fits.
     *
     * @param delta seconds since the last frame, from the one clock
     */
    public float offset(float delta, float content, float viewport) {
        this.contentHeight = content;
        this.viewportHeight = viewport;

        // Exponential decay towards zero: fast at first, then settling, with no
        // fixed number of frames anywhere in it.
        offset += velocity * delta * 6f;
        velocity *= (float) Math.pow(0.0016d, delta);
        if (Math.abs(velocity) < 1f) {
            velocity = 0f;
        }

        float maximum = Math.max(0f, content - viewport);
        if (offset < 0f) {
            offset = 0f;
            velocity = 0f;
        }
        if (offset > maximum) {
            offset = maximum;
            velocity = 0f;
        }
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
    public void rail(Renderer ui, Rect viewport) {
        if (contentHeight <= viewportHeight || viewport.height <= 0f) {
            return;
        }
        float width = 4f;
        float x = viewport.right() - width;
        ui.rounded(x, viewport.y, x + width, viewport.bottom(), width / 2f, Skin.argb(0.18f, 0x000000));

        float visible = viewportHeight / contentHeight;
        float handle = Math.max(32f, viewport.height * visible);
        float progress = offset / Math.max(1f, contentHeight - viewportHeight);
        float handleY = viewport.y + (viewport.height - handle) * progress;
        ui.rounded(x, handleY, x + width, handleY + handle, width / 2f, Skin.argb(0.28f, 0xFFFFFF));
    }

    /** Whether there is anything to scroll, for a caller that lays out around it. */
    public boolean overflowing() {
        return contentHeight > viewportHeight;
    }
}
