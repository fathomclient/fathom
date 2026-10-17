package net.fathom.optimizations.core.ui.style;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * A control inside a pane, as the launcher's {@code controls.css} draws it: a
 * flat {@code --control-*} fill and {@code --control-rim} -- an even
 * hairline ring and a lit top edge, both inset shadows. No blur: a second
 * blur under the first is work nobody can see.
 */
public final class ControlSurface {

    public static final int FAINT = Tokens.CONTROL_FILL_FAINT;
    public static final int FILL = Tokens.CONTROL_FILL;
    public static final int HOVER = Tokens.CONTROL_FILL_HOVER;
    public static final int PRESSED = Tokens.CONTROL_FILL_PRESSED;
    public static final int SELECTED = Tokens.CONTROL_FILL_SELECTED;
    public static final int SUNKEN = Tokens.CONTROL_FILL_SUNKEN;
    public static final int SUNKEN_HOVER = Tokens.CONTROL_FILL_SUNKEN_HOVER;
    public static final int PROMINENT = Tokens.CONTROL_PROMINENT;
    public static final int PROMINENT_HOVER = Tokens.CONTROL_PROMINENT_HOVER;
    public static final int HAIRLINE = Tokens.CONTROL_HAIRLINE;
    public static final int SCROLLBAR = Tokens.CONTROL_SCROLLBAR;
    public static final int SCROLLBAR_HOVER = Tokens.CONTROL_SCROLLBAR_HOVER;
    public static final int SWITCH_TRACK = Tokens.CONTROL_SWITCH_TRACK;
    public static final int SWITCH_TRACK_ON = Tokens.CONTROL_SWITCH_TRACK_ON;
    public static final int SWITCH_KNOB = Tokens.CONTROL_SWITCH_KNOB;
    public static final int SWITCH_KNOB_ON = Tokens.CONTROL_SWITCH_KNOB_ON;

    /** {@code --press-scale}: what a pressed control shrinks to (theme: motion.press-scale). */
    public static final float PRESS_SCALE = 0.985f;

    private ControlSurface() {}

    /** {@code background-color: fill; box-shadow: var(--control-rim)} -- or the strong rim. */
    public static void paint(Renderer ui, float left, float top, float right, float bottom, float radius,
            int fill, boolean strongRim) {
        ui.rounded(left, top, right, bottom, radius, fill);
        rim(ui, left, top, right, bottom, radius, strongRim);
    }

    /** The rim alone: the even ring under the lit top edge, as CSS lists them. */
    public static void rim(Renderer ui, float left, float top, float right, float bottom, float radius,
            boolean strong) {
        float px = ui.pixel();
        ui.inset(left, top, right, bottom, radius, 0f, 0f, px,
                strong ? Tokens.CONTROL_RIM_STRONG_EDGE : Tokens.CONTROL_RIM_EDGE);
        ui.inset(left, top, right, bottom, radius, 0f, px, 0f,
                strong ? Tokens.CONTROL_RIM_STRONG_TOP : Tokens.CONTROL_RIM_TOP);
    }

    /** A one-pixel separator, {@code --control-hairline}. */
    public static void hairline(Renderer ui, float left, float top, float right) {
        ui.fill(left, top, right, top + ui.pixel(), HAIRLINE);
    }

    /** A vertical one. */
    public static void hairlineDown(Renderer ui, float left, float top, float bottom) {
        ui.fill(left, top, left + ui.pixel(), bottom, HAIRLINE);
    }
}
