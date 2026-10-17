package net.fathom.optimizations.core.ui.layout;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.ui.render.Renderer;

/**
 * A node of a screen's retained layout: a small, deterministic subset of
 * flexbox -- enough to make spacing come from one place, and no more.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li><b>Rows, columns and stacks.</b> A row or a column places its
 *       children one after another along its axis, {@link #gap} apart, inside
 *       its {@link #padding}; a stack places every child over the whole
 *       content box (a card's background under its content, a badge on a
 *       corner).
 *   <li><b>Fixed, intrinsic and flexible sizes.</b> A child is its fixed
 *       size, or its content's size (a leaf measures itself -- text through
 *       the renderer's real metrics), plus a share of whatever space is left
 *       in proportion to its {@link #grow}. {@link #min} and {@link #max}
 *       bound the result.
 *   <li><b>Alignment.</b> Along the axis: start, centre, end, or the space
 *       between. Across it: start, centre, end or stretch, per container and
 *       per child.
 *   <li><b>Clipping and scrolling.</b> A {@link #clip clipped} box keeps its
 *       children's drawing inside it; a {@link #scroll scrolled} one also
 *       moves them by its offset and knows how far it can scroll.
 *   <li><b>Floating.</b> A floating box takes no room in its parent: it is
 *       placed against an anchor's rectangle -- below it, above it, aligned to
 *       one of its edges -- and kept inside the screen. Popovers and the
 *       account panel are floating.
 * </ul>
 *
 * <h2>Retained</h2>
 *
 * <p>A screen builds its tree once and keeps it. {@link #layout} recomputes
 * only when the space it is given, the interface scale or a node's content
 * changed ({@link #invalidate}); otherwise it returns at once, and drawing
 * reads the {@link #rect()}s it already has. Hit testing reads the same
 * rectangles, so what is drawn and what is clicked cannot disagree.
 *
 * <h2>No arbitrary offsets</h2>
 *
 * <p>Every distance in a tree is a padding, a gap or a size, and the values
 * are spacing tokens. Text is placed in its line box by the face's metrics
 * ({@link TextStack}); a box never nudges its content by a pixel or two.
 */
public final class Box {

    public enum Axis { ROW, COLUMN, STACK }

    public enum Align { START, CENTRE, END, STRETCH, BETWEEN }

    /** Where a floating box sits against its anchor. */
    public enum Place { BELOW_START, BELOW_END, ABOVE_START, ABOVE_END, RIGHT_START, LEFT_START }

    /** A leaf's content size: text, an icon, a control with a natural width. */
    public interface Measure {
        /** The width the content wants, logical units. */
        float width(Renderer ui);

        /** Its height at a width, logical units. */
        float height(Renderer ui, float width);
    }

    private static final float AUTO = Float.NaN;

    public final String id;
    private Axis axis = Axis.COLUMN;
    private float width = AUTO;
    private float height = AUTO;
    private float minWidth;
    private float minHeight;
    private float maxWidth = Float.MAX_VALUE;
    private float maxHeight = Float.MAX_VALUE;
    private float grow;
    private float padLeft;
    private float padTop;
    private float padRight;
    private float padBottom;
    private float gap;
    private Align justify = Align.START;
    private Align align = Align.STRETCH;
    private Align self;
    private boolean clip;
    private boolean scroll;
    private float scrollOffset;
    private float contentExtent;
    private boolean hidden;
    private Measure measure;

    private Box floatingAnchor;
    private Place place = Place.BELOW_START;
    private float floatGap;

    private Box parent;
    private final List<Box> children = new ArrayList<Box>();
    private final List<Box> floating = new ArrayList<Box>();

    // Replaced, not mutated, at each real layout -- which happens when
    // something changed, not every frame.
    private Rect rect = new Rect(0f, 0f, 0f, 0f);
    private Rect content = new Rect(0f, 0f, 0f, 0f);

    // Measured this layout: the box's own preferred size.
    private float wantWidth;
    private float wantHeight;

    // The last layout's inputs, so an unchanged one is skipped.
    private float lastX = Float.NaN;
    private float lastY;
    private float lastWidth;
    private float lastHeight;
    private float lastScale;
    private boolean dirty = true;
    private int layouts;

    private Box(String id) {
        this.id = id;
    }

    public static Box column(String id) {
        return new Box(id).axis(Axis.COLUMN);
    }

    public static Box row(String id) {
        return new Box(id).axis(Axis.ROW);
    }

    public static Box stack(String id) {
        return new Box(id).axis(Axis.STACK);
    }

    /** A leaf sized by its content. */
    public static Box leaf(String id, Measure measure) {
        Box box = new Box(id);
        box.measure = measure;
        return box;
    }

    /** A leaf of a fixed size. */
    public static Box fixed(String id, float width, float height) {
        return new Box(id).size(width, height);
    }

    /** Empty space that takes what is left: pushes its siblings apart. */
    public static Box spacer(String id) {
        return new Box(id).grow(1f);
    }

    // -----------------------------------------------------------------------
    // Style
    // -----------------------------------------------------------------------

    public Box axis(Axis value) {
        axis = value;
        return invalidate();
    }

    public Box size(float w, float h) {
        if (Float.compare(w, width) == 0 && Float.compare(h, height) == 0) {
            return this;
        }
        width = w;
        height = h;
        return invalidate();
    }

    public Box width(float w) {
        if (Float.compare(w, width) == 0) {
            return this;
        }
        width = w;
        return invalidate();
    }

    public Box height(float h) {
        if (Float.compare(h, height) == 0) {
            return this;
        }
        height = h;
        return invalidate();
    }

    public Box min(float w, float h) {
        minWidth = w;
        minHeight = h;
        return invalidate();
    }

    public Box max(float w, float h) {
        maxWidth = w;
        maxHeight = h;
        return invalidate();
    }

    public Box grow(float share) {
        grow = share;
        return invalidate();
    }

    public Box padding(float all) {
        return padding(all, all, all, all);
    }

    public Box padding(float horizontal, float vertical) {
        return padding(horizontal, vertical, horizontal, vertical);
    }

    public Box padding(float left, float top, float right, float bottom) {
        padLeft = left;
        padTop = top;
        padRight = right;
        padBottom = bottom;
        return invalidate();
    }

    public Box gap(float value) {
        gap = value;
        return invalidate();
    }

    /** Distribution along the axis: START, CENTRE, END or BETWEEN. */
    public Box justify(Align value) {
        justify = value;
        return invalidate();
    }

    /** Alignment across the axis, for every child: START, CENTRE, END or STRETCH. */
    public Box align(Align value) {
        align = value;
        return invalidate();
    }

    /** This child's own cross-axis alignment, over its parent's. */
    public Box alignSelf(Align value) {
        self = value;
        return invalidate();
    }

    public Box clip(boolean value) {
        clip = value;
        return this;
    }

    /** A scroll viewport: children move by the offset; {@link #scrollRange} is how far. */
    public Box scroll(boolean value) {
        scroll = value;
        clip = clip || value;
        return invalidate();
    }

    public Box measure(Measure value) {
        measure = value;
        return invalidate();
    }

    /** Leave this box (and its room) out of the layout. */
    public Box hidden(boolean value) {
        if (hidden != value) {
            hidden = value;
            invalidate();
        }
        return this;
    }

    // -----------------------------------------------------------------------
    // Tree
    // -----------------------------------------------------------------------

    public Box add(Box child) {
        child.parent = this;
        children.add(child);
        invalidate();
        return child;
    }

    /**
     * Add a box that floats against {@code anchor}: it takes no room here, is
     * laid out at its own preferred size and placed {@code gap} away from the
     * anchor's edge, inside the root's bounds.
     */
    public Box addFloating(Box child, Box anchor, Place where, float gapFromAnchor) {
        child.parent = this;
        child.floatingAnchor = anchor;
        child.place = where;
        child.floatGap = gapFromAnchor;
        floating.add(child);
        invalidate();
        return child;
    }

    public List<Box> children() {
        return children;
    }

    /** Mark this box and its ancestors as needing layout: content changed. */
    public Box invalidate() {
        Box at = this;
        while (at != null && !at.dirty) {
            at.dirty = true;
            at = at.parent;
        }
        dirty = true;
        return this;
    }

    // -----------------------------------------------------------------------
    // Results
    // -----------------------------------------------------------------------

    /** Where this box is, logical units, after {@link #layout}. */
    public Rect rect() {
        return rect;
    }

    /** Its rectangle less its padding. */
    public Rect content() {
        return content;
    }

    public boolean isClip() {
        return clip;
    }

    /** What measures this leaf -- a {@link TextLeaf} for text -- or null. */
    public Measure measurer() {
        return measure;
    }

    /** Padding, left/top/right/bottom, for the debug overlay. */
    public float paddingLeft() {
        return padLeft;
    }

    public float paddingTop() {
        return padTop;
    }

    public float paddingRight() {
        return padRight;
    }

    public float paddingBottom() {
        return padBottom;
    }

    public float gapSize() {
        return gap;
    }

    public boolean isHidden() {
        return hidden;
    }

    public float scrollOffset() {
        return scrollOffset;
    }

    /** How far a scroll viewport can scroll: its content's extent past its own height. */
    public float scrollRange() {
        return Math.max(0f, contentExtent - content.height);
    }

    /** Scroll a viewport; the offset is kept inside its range at the next layout. */
    public Box scrollTo(float offset) {
        if (offset != scrollOffset) {
            scrollOffset = offset;
            invalidate();
        }
        return this;
    }

    /** How many times this tree has actually been laid out (not skipped). */
    public int layouts() {
        return layouts;
    }

    /** A descendant by id, or null. For tests and the debug overlay. */
    public Box find(String wanted) {
        if (id.equals(wanted)) {
            return this;
        }
        for (Box child : children) {
            Box found = child.find(wanted);
            if (found != null) {
                return found;
            }
        }
        for (Box child : floating) {
            Box found = child.find(wanted);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Every box, depth first, floating ones after their parent's flow. */
    public void visit(Visitor visitor) {
        visit(visitor, 0);
    }

    public interface Visitor {
        void box(Box box, int depth);
    }

    private void visit(Visitor visitor, int depth) {
        if (hidden) {
            return;
        }
        visitor.box(this, depth);
        for (Box child : children) {
            child.visit(visitor, depth + 1);
        }
        for (Box child : floating) {
            child.visit(visitor, depth + 1);
        }
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    /**
     * Lay the tree out in a rectangle. Returns at once when neither the
     * rectangle, the scale nor any content changed since the last layout.
     *
     * @return true when it actually laid out
     */
    public boolean layout(Renderer ui, float x, float y, float w, float h) {
        float scale = ui.scale();
        if (!dirty && x == lastX && y == lastY && w == lastWidth && h == lastHeight && scale == lastScale) {
            return false;
        }
        lastX = x;
        lastY = y;
        lastWidth = w;
        lastHeight = h;
        lastScale = scale;
        layouts++;
        measureTree(ui);
        place(ui, x, y, w, h);
        placeFloating(ui, this);
        clean();
        return true;
    }

    private void clean() {
        dirty = false;
        for (Box child : children) {
            child.clean();
        }
        for (Box child : floating) {
            child.clean();
        }
    }

    /** Bottom up: each box's preferred size from its children or its content. */
    private void measureTree(Renderer ui) {
        for (Box child : children) {
            child.measureTree(ui);
        }
        for (Box child : floating) {
            child.measureTree(ui);
        }
        float w;
        float h;
        if (measure != null) {
            w = width == width ? width : measure.width(ui);
            h = height == height ? height : measure.height(ui, w);
        } else {
            float main = 0f;
            float cross = 0f;
            int shown = 0;
            for (Box child : children) {
                if (child.hidden) {
                    continue;
                }
                shown++;
                if (axis == Axis.ROW) {
                    main += child.wantWidth;
                    cross = Math.max(cross, child.wantHeight);
                } else if (axis == Axis.COLUMN) {
                    main += child.wantHeight;
                    cross = Math.max(cross, child.wantWidth);
                } else {
                    main = Math.max(main, child.wantWidth);
                    cross = Math.max(cross, child.wantHeight);
                }
            }
            if (axis != Axis.STACK && shown > 1) {
                main += gap * (shown - 1);
            }
            if (axis == Axis.COLUMN) {
                w = cross;
                h = main;
            } else {
                w = main;
                h = cross;
            }
            w += padLeft + padRight;
            h += padTop + padBottom;
            if (width == width) {
                w = width;
            }
            if (height == height) {
                h = height;
            }
        }
        wantWidth = clamp(w, minWidth, maxWidth);
        wantHeight = clamp(h, minHeight, maxHeight);
    }

    /** Top down: this box at a rectangle, then its children inside it. */
    private void place(Renderer ui, float x, float y, float w, float h) {
        rect = new Rect(x, y, w, h);
        content = new Rect(x + padLeft, y + padTop, Math.max(0f, w - padLeft - padRight),
                Math.max(0f, h - padTop - padBottom));
        if (measure != null || children.isEmpty()) {
            contentExtent = 0f;
            return;
        }
        if (axis == Axis.STACK) {
            for (Box child : children) {
                if (child.hidden) {
                    continue;
                }
                float cw = crossSize(child, content.width, child.wantWidth, true);
                float ch = crossSize(child, content.height, child.wantHeight, false);
                float cx = content.x + crossOffset(child, content.width, cw);
                float cy = content.y + crossOffset(child, content.height, ch);
                child.place(ui, cx, cy, cw, ch);
            }
            contentExtent = content.height;
            return;
        }
        boolean row = axis == Axis.ROW;
        float available = row ? content.width : content.height;
        float used = 0f;
        float growTotal = 0f;
        int shown = 0;
        for (Box child : children) {
            if (child.hidden) {
                continue;
            }
            shown++;
            used += row ? child.wantWidth : child.wantHeight;
            growTotal += child.grow;
        }
        if (shown == 0) {
            contentExtent = 0f;
            return;
        }
        used += gap * (shown - 1);
        float free = available - used;
        float spacing = gap;
        float cursor = row ? content.x : content.y;
        if (growTotal <= 0f && free > 0f) {
            if (justify == Align.CENTRE) {
                cursor += free / 2f;
            } else if (justify == Align.END) {
                cursor += free;
            } else if (justify == Align.BETWEEN && shown > 1) {
                spacing += free / (shown - 1);
            }
        }
        if (scroll) {
            float extent = used;
            float range = Math.max(0f, extent - available);
            scrollOffset = Math.max(0f, Math.min(range, scrollOffset));
            cursor -= scrollOffset;
            contentExtent = extent;
        } else {
            contentExtent = used;
        }
        for (Box child : children) {
            if (child.hidden) {
                continue;
            }
            float main = row ? child.wantWidth : child.wantHeight;
            if (growTotal > 0f && free != 0f && child.grow > 0f && !scroll) {
                main += free * (child.grow / growTotal);
                main = row ? clamp(main, child.minWidth, child.maxWidth)
                        : clamp(main, child.minHeight, child.maxHeight);
                main = Math.max(0f, main);
            }
            if (row) {
                float ch = crossSize(child, content.height, child.wantHeight, false);
                float cy = content.y + crossOffset(child, content.height, ch);
                // A leaf's height can depend on its width (wrapped text).
                child.place(ui, cursor, cy, main, ch);
            } else {
                float cw = crossSize(child, content.width, child.wantWidth, true);
                float cx = content.x + crossOffset(child, content.width, cw);
                float ch = main;
                if (child.measure != null && child.height != child.height) {
                    ch = Math.max(main, child.measure.height(ui, cw));
                }
                child.place(ui, cx, cursor, cw, ch);
                main = ch;
            }
            cursor += main + spacing;
        }
    }

    private float crossSize(Box child, float available, float wanted, boolean horizontal) {
        Align a = child.self != null ? child.self : align;
        boolean fixed = horizontal ? child.width == child.width : child.height == child.height;
        if (a == Align.STRETCH && !fixed) {
            return horizontal ? clamp(available, child.minWidth, child.maxWidth)
                    : clamp(available, child.minHeight, child.maxHeight);
        }
        return Math.min(wanted, Math.max(available, 0f) > 0f ? Math.max(wanted, 0f) : wanted);
    }

    private float crossOffset(Box child, float available, float size) {
        Align a = child.self != null ? child.self : align;
        if (a == Align.CENTRE) {
            return (available - size) / 2f;
        }
        if (a == Align.END) {
            return available - size;
        }
        return 0f;
    }

    /** Floating boxes, after the whole flow is placed so their anchors are final. */
    private void placeFloating(Renderer ui, Box root) {
        for (Box child : children) {
            child.placeFloating(ui, root);
        }
        for (Box child : floating) {
            Rect anchor = child.floatingAnchor != null ? child.floatingAnchor.rect : rect;
            float w = child.wantWidth;
            float h = child.wantHeight;
            float x;
            float y;
            switch (child.place) {
                case BELOW_END:
                    x = anchor.right() - w;
                    y = anchor.bottom() + child.floatGap;
                    break;
                case ABOVE_START:
                    x = anchor.x;
                    y = anchor.y - child.floatGap - h;
                    break;
                case ABOVE_END:
                    x = anchor.right() - w;
                    y = anchor.y - child.floatGap - h;
                    break;
                case RIGHT_START:
                    x = anchor.right() + child.floatGap;
                    y = anchor.y;
                    break;
                case LEFT_START:
                    x = anchor.x - child.floatGap - w;
                    y = anchor.y;
                    break;
                case BELOW_START:
                default:
                    x = anchor.x;
                    y = anchor.bottom() + child.floatGap;
                    break;
            }
            // Inside the root, flipping vertically before sliding.
            Rect bounds = root.rect;
            if (y + h > bounds.bottom() && (child.place == Place.BELOW_START || child.place == Place.BELOW_END)) {
                float above = anchor.y - child.floatGap - h;
                if (above >= bounds.y) {
                    y = above;
                }
            }
            x = Math.max(bounds.x, Math.min(bounds.right() - w, x));
            y = Math.max(bounds.y, Math.min(bounds.bottom() - h, y));
            child.place(ui, x, y, w, h);
            child.placeFloating(ui, root);
        }
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }

    @Override
    public String toString() {
        return id + " " + rect;
    }
}
