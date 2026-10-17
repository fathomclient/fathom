package net.fathom.optimizations.core.ui.render;

/**
 * The clip, opacity and transform stacks a renderer keeps, as arithmetic.
 *
 * <p>Lives in {@code core} so the rules -- how nested clips intersect, how a
 * scaled transform composes, what an unbalanced frame looks like -- are tested
 * without a GL context, and so every backend applies the same ones. A backend
 * asks this class what the state <em>is</em> and tells its graphics library.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li>Three independent stacks, so pushes of different kinds may interleave.
 *   <li>Clip rectangles are stored in frame coordinates, with the transform in
 *       force when they were pushed already applied. A later transform does not
 *       move an earlier clip.
 *   <li>{@link #intersectClip} narrows the clip in force; {@link #pushClip}
 *       replaces it (a popover escaping its parent's scroll area). Both are
 *       undone by one {@link #popClip}.
 *   <li>Past {@link #MAX_DEPTH}, pushes are counted rather than stored, so the
 *       matching pops still balance; the state simply stops changing. A frame
 *       that ends unbalanced is reported by {@link #unbalanced}, not repaired
 *       silently.
 * </ul>
 */
public final class CanvasState {

    public static final int MAX_DEPTH = 32;

    // -- opacity ---------------------------------------------------------------
    private float alpha = 1f;
    private final float[] alphaStack = new float[MAX_DEPTH];
    private int alphaDepth;
    private int alphaOverflow;

    // -- transform: scale about an origin, then translate -----------------------
    private float offsetX;
    private float offsetY;
    private float zoom = 1f;
    private final float[] transformStack = new float[MAX_DEPTH * 3];
    private int transformDepth;
    private int transformOverflow;

    // -- clip ------------------------------------------------------------------
    private final float[] clipStack = new float[MAX_DEPTH * 4];
    private int clipDepth;
    /** An entry pushed by {@link #pushUnclipped} has NaN bounds. */
    private int clipOverflow;

    /** Start a frame: everything empty. */
    public void reset() {
        alpha = 1f;
        alphaDepth = 0;
        alphaOverflow = 0;
        offsetX = 0f;
        offsetY = 0f;
        zoom = 1f;
        transformDepth = 0;
        transformOverflow = 0;
        clipDepth = 0;
        clipOverflow = 0;
    }

    /** How many pushes were never popped, across all three stacks. */
    public int unbalanced() {
        return alphaDepth + alphaOverflow + transformDepth + transformOverflow + clipDepth + clipOverflow;
    }

    // -----------------------------------------------------------------------
    // Opacity
    // -----------------------------------------------------------------------

    public void pushAlpha(float value) {
        if (alphaDepth >= MAX_DEPTH) {
            alphaOverflow++;
            return;
        }
        alphaStack[alphaDepth++] = alpha;
        alpha *= value != value ? 0f : (value < 0f ? 0f : (value > 1f ? 1f : value));
    }

    /** @return false if there was nothing to pop */
    public boolean popAlpha() {
        if (alphaOverflow > 0) {
            alphaOverflow--;
            return true;
        }
        if (alphaDepth == 0) {
            return false;
        }
        alpha = alphaStack[--alphaDepth];
        return true;
    }

    /** The product of every opacity pushed. */
    public float alpha() {
        return alpha;
    }

    // -----------------------------------------------------------------------
    // Transform
    // -----------------------------------------------------------------------

    /** Scale about (originX, originY), then translate -- in the current space. */
    public void pushTransform(float translateX, float translateY, float scale, float originX, float originY) {
        if (transformDepth >= MAX_DEPTH) {
            transformOverflow++;
            return;
        }
        int base = transformDepth * 3;
        transformStack[base] = offsetX;
        transformStack[base + 1] = offsetY;
        transformStack[base + 2] = zoom;
        transformDepth++;
        offsetX += zoom * (originX * (1f - scale) + translateX);
        offsetY += zoom * (originY * (1f - scale) + translateY);
        zoom *= scale;
    }

    public boolean popTransform() {
        if (transformOverflow > 0) {
            transformOverflow--;
            return true;
        }
        if (transformDepth == 0) {
            return false;
        }
        transformDepth--;
        int base = transformDepth * 3;
        offsetX = transformStack[base];
        offsetY = transformStack[base + 1];
        zoom = transformStack[base + 2];
        return true;
    }

    public float offsetX() {
        return offsetX;
    }

    public float offsetY() {
        return offsetY;
    }

    public float zoom() {
        return zoom;
    }

    /** A logical x in frame coordinates. */
    public float frameX(float x) {
        return offsetX + x * zoom;
    }

    /** A logical y in frame coordinates. */
    public float frameY(float y) {
        return offsetY + y * zoom;
    }

    // -----------------------------------------------------------------------
    // Clip
    // -----------------------------------------------------------------------

    /** Narrow the clip to this rectangle (logical, current transform). */
    public void intersectClip(float left, float top, float right, float bottom) {
        float l = frameX(left);
        float t = frameY(top);
        float r = frameX(right);
        float b = frameY(bottom);
        if (clipDepth > 0 && clipOverflow == 0) {
            int base = (clipDepth - 1) * 4;
            if (!Float.isNaN(clipStack[base])) {
                l = Math.max(l, clipStack[base]);
                t = Math.max(t, clipStack[base + 1]);
                r = Math.min(r, clipStack[base + 2]);
                b = Math.min(b, clipStack[base + 3]);
            }
        }
        store(l, t, r, b);
    }

    /** Clip to exactly this rectangle, ignoring any clip already in force. */
    public void pushClip(float left, float top, float right, float bottom) {
        store(frameX(left), frameY(top), frameX(right), frameY(bottom));
    }

    /** Drop every clip in force until the matching {@link #popClip}. */
    public void pushUnclipped() {
        store(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    }

    private void store(float l, float t, float r, float b) {
        if (clipDepth >= MAX_DEPTH || clipOverflow > 0) {
            clipOverflow++;
            return;
        }
        int base = clipDepth * 4;
        clipStack[base] = l;
        clipStack[base + 1] = t;
        clipStack[base + 2] = Float.isNaN(r) ? r : Math.max(l, r);
        clipStack[base + 3] = Float.isNaN(b) ? b : Math.max(t, b);
        clipDepth++;
    }

    public boolean popClip() {
        if (clipOverflow > 0) {
            clipOverflow--;
            return true;
        }
        if (clipDepth == 0) {
            return false;
        }
        clipDepth--;
        return true;
    }

    /** Forget every clip. For recovering a frame, not for components. */
    public void resetClip() {
        clipDepth = 0;
        clipOverflow = 0;
    }

    /** Whether a clip rectangle is in force. */
    public boolean clipped() {
        return clipDepth > 0 && !Float.isNaN(clipStack[(clipDepth - 1) * 4]);
    }

    public float clipLeft() {
        return clipStack[(clipDepth - 1) * 4];
    }

    public float clipTop() {
        return clipStack[(clipDepth - 1) * 4 + 1];
    }

    public float clipRight() {
        return clipStack[(clipDepth - 1) * 4 + 2];
    }

    public float clipBottom() {
        return clipStack[(clipDepth - 1) * 4 + 3];
    }

    /**
     * Whether a logical rectangle is certainly invisible: fully outside the clip,
     * or drawn at no opacity. The cheapest draw call is the one never made.
     */
    public boolean culled(float left, float top, float right, float bottom) {
        if (alpha <= 0f) {
            return true;
        }
        if (!clipped()) {
            return false;
        }
        return frameX(right) <= clipLeft() || frameX(left) >= clipRight()
                || frameY(bottom) <= clipTop() || frameY(top) >= clipBottom();
    }
}
