package net.fathom.optimizations.core.ui.render;

/**
 * Where something is in the stack, independently of when it was drawn.
 *
 * <p>The interface used to be stacked by call order: a dropdown was above the
 * row below it only because the row happened to be drawn first, and a tooltip
 * was on top only if whoever drew it remembered to defer it. Every "the
 * popover is under the next card" bug was that.
 *
 * <p>Now a primitive carries the layer that was current when it was drawn
 * ({@link Renderer#pushLayer}), and the compositor submits the layers in this
 * order at the end of the frame. Within one layer, call order still decides --
 * which is what a parent drawing its children wants -- but nothing in a lower
 * layer can ever cover something in a higher one.
 *
 * <p>{@link #BACKDROP} is not something one draws into: it is the point at
 * which the compositor takes the frame as it stands -- the world or the menu
 * scene, and {@link #BACKGROUND} -- and blurs it for glass. Glass anywhere
 * above samples that, so a pane's frost is always of what is behind the
 * interface and never of the interface itself.
 */
public final class Layer {

    /** The scene the interface sits on: the menu video, a backdrop image. */
    public static final int BACKGROUND = 0;
    /** The frame is captured and blurred for glass here. Not drawn into. */
    public static final int BACKDROP = 10;
    /** Panels, cards, rails: the interface's planes. The default. */
    public static final int SURFACE = 20;
    /** Controls on those planes. */
    public static final int CONTROL = 30;
    /** Hover and selection washes over controls. */
    public static final int OVERLAY = 40;
    /** Floating controls: a dragged item, a sticky bar. */
    public static final int FLOATING = 50;
    /** Dropdowns, menus, the account panel. */
    public static final int POPOVER = 60;
    public static final int TOOLTIP = 70;
    /** A dialog and its scrim. */
    public static final int MODAL = 80;
    /** The development overlay. Above everything. */
    public static final int DEBUG = 90;

    /** Layers are multiples of ten; this many fit. */
    public static final int COUNT = 10;

    private static final String[] NAMES = {
        "background", "backdrop", "surface", "control", "overlay",
        "floating", "popover", "tooltip", "modal", "debug",
    };

    private Layer() {}

    /** The layer's slot, 0 .. COUNT-1, for a value that is a multiple of ten. */
    public static int index(int layer) {
        int index = layer / 10;
        return index < 0 ? 0 : (index >= COUNT ? COUNT - 1 : index);
    }

    public static String name(int layer) {
        return NAMES[index(layer)];
    }
}
