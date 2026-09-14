package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.generated.FathomTheme;

/**
 * Fathom's visual system, in the game.
 *
 * <h2>One source, two implementations</h2>
 *
 * <p>These are generated from the same manifest that produces the launcher's
 * CSS: the same greys, the same red, the same 4px spacing scale, the
 * same radii, the same three durations and one curve, and — since the renderer
 * rewrite — the same typeface at the same sizes. The point of the in-game
 * client is that it should feel like the launcher walked into Minecraft, and a
 * slightly different red or a slightly different corner radius reads,
 * immediately, as a different program.
 *
 * <h2>Black, and no themes</h2>
 *
 * <p>The hub used to draw a blurred photograph behind its content, themed by a
 * picker. That is gone. A menu inside a game is already sitting on the most
 * decorated background it will ever have — the game — and a second decorative
 * layer competes with the type for no benefit. So: the world is darkened hard,
 * the panel is near-black, and the only colour in the interface is the accent,
 * which is what makes the accent mean something.
 *
 * <h2>Control states are tokens too</h2>
 *
 * <p>Idle, hover, pressed, disabled, focused. Five states, one set of values,
 * used by every control — see {@link Controls}. A control that invents its own
 * hover colour is how an interface starts feeling assembled rather than
 * designed.
 */
public final class Skin {

    private Skin() {}

    // -----------------------------------------------------------------------
    // Surfaces
    // -----------------------------------------------------------------------

    /** Pure black. The header and the rail. */
    public static final int CHROME = FathomTheme.COLOR_CHROME;

    /** The panel. Near-black, with just enough transparency to sit on a scene. */
    public static final int PANEL = FathomTheme.COLOR_PANEL;

    /** Inside the panel: a card, a drawer, a popover. */
    public static final int RAISED = FathomTheme.COLOR_RAISED;

    /** The same surface with the pointer on it. */
    public static final int RAISED_HOVER = FathomTheme.COLOR_RAISED_HOVER;

    /** A popover that must be readable over anything, including itself. */
    public static final int OVERLAY = FathomTheme.COLOR_OVERLAY;

    /**
     * Control material for a control sitting on a photograph.
     *
     * <p>{@link #FILL} and its relatives are white at low alpha. That is the
     * right material inside the hub, where they sit on Fathom's own near-black
     * glass and lift off it — and the wrong one on the main menu, where a 5%
     * white fill over a snowfield is a slightly brighter snowfield with a grey
     * label on it. A control on top of an image has to bring its own darkness.
     *
     * <p>These are the launcher's own tokens for the same situation, so the
     * title screen and the launcher's home page are made of the same material
     * rather than two hand-matched approximations of it.
     */
    public static final int GLASS = FathomTheme.COLOR_GLASS_FILL;
    public static final int GLASS_HOVER = FathomTheme.COLOR_GLASS_FILL_STRONG;
    public static final int GLASS_PRESSED = FathomTheme.COLOR_GLASS_FILL_SOLID;
    public static final int GLASS_EDGE = FathomTheme.COLOR_GLASS_EDGE;
    public static final int GLASS_EDGE_STRONG = FathomTheme.COLOR_GLASS_EDGE_STRONG;

    public static final int CHROME_LINE = FathomTheme.COLOR_CHROME_LINE;
    public static final int CHROME_LINE_STRONG = FathomTheme.COLOR_CHROME_LINE_STRONG;

    /** The thin bright edge. Low contrast: higher and it becomes a stroke. */
    public static final int EDGE = FathomTheme.COLOR_EDGE;
    public static final int EDGE_STRONG = FathomTheme.COLOR_EDGE_STRONG;
    /** The internal highlight along the top. The detail that makes it look lit. */
    public static final int SHEEN = FathomTheme.COLOR_SHEEN;

    /**
     * How hard the world behind the hub is darkened.
     *
     * <p>Hard. The requirement is that the game is visible as context and never
     * as content: at 0.82 a bright desert reads as a dark texture behind the
     * panel, which is the point, and a nether portal cannot shine through the
     * type.
     */
    public static final int SCRIM = FathomTheme.COLOR_SCRIM;

    /** Under a floating panel. Not a blur — a falloff. */
    public static final int SHADOW = FathomTheme.COLOR_SHADOW;

    // -----------------------------------------------------------------------
    // Control states
    // -----------------------------------------------------------------------

    public static final int FILL = FathomTheme.COLOR_FILL;
    public static final int FILL_HOVER = FathomTheme.COLOR_FILL_HOVER;
    public static final int FILL_PRESSED = FathomTheme.COLOR_FILL_PRESSED;
    public static final int FILL_ACTIVE = FathomTheme.COLOR_FILL_ACTIVE;
    public static final int FILL_SUNKEN = FathomTheme.COLOR_FILL_SUNKEN;
    public static final int FILL_DISABLED = FathomTheme.COLOR_FILL_DISABLED;

    /** The ring a keyboard-focused control wears. */
    public static final int FOCUS = FathomTheme.COLOR_FOCUS;

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /** Four text levels and no more. A fifth grey is how a palette stops meaning anything. */
    public static final int TEXT = FathomTheme.COLOR_TEXT;
    public static final int TEXT_MUTED = FathomTheme.COLOR_TEXT_MUTED;
    public static final int TEXT_FAINT = FathomTheme.COLOR_TEXT_FAINT;
    public static final int TEXT_DISABLED = FathomTheme.COLOR_TEXT_DISABLED;
    public static final int TEXT_LABEL = FathomTheme.COLOR_TEXT_LABEL;

    // -----------------------------------------------------------------------
    // Accent and status
    // -----------------------------------------------------------------------

    /** The only hue in the product, which is what makes it mean something. */
    public static final int ACCENT = FathomTheme.COLOR_ACCENT;
    public static final int ACCENT_STRONG = FathomTheme.COLOR_ACCENT_STRONG;
    public static final int ACCENT_DEEP = FathomTheme.COLOR_ACCENT_DEEP;
    public static final int ACCENT_WASH = FathomTheme.COLOR_ACCENT_WASH;

    public static final int GOOD = FathomTheme.COLOR_GOOD;
    public static final int WARN = FathomTheme.COLOR_WARN;
    /** A different red from the accent: selection and failure must not look alike. */
    public static final int BAD = FathomTheme.COLOR_BAD;
    public static final int BAD_WASH = FathomTheme.COLOR_BAD_WASH;

    // -----------------------------------------------------------------------
    // Spacing, on the launcher's 4px scale
    // -----------------------------------------------------------------------

    public static final float S1 = FathomTheme.DIM_S_1;
    public static final float S2 = FathomTheme.DIM_S_2;
    public static final float S3 = FathomTheme.DIM_S_3;
    public static final float S4 = FathomTheme.DIM_S_4;
    public static final float S5 = FathomTheme.DIM_S_5;
    public static final float S6 = FathomTheme.DIM_S_6;
    public static final float S8 = FathomTheme.DIM_S_8;
    public static final float S10 = FathomTheme.DIM_S_10;

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    /** Small inset shapes: swatches, tags and compact tooltips. */
    public static final float R_SMALL = FathomTheme.DIM_R_XS;
    /** Ordinary buttons, fields and navigation rows. */
    public static final float R_CONTROL = FathomTheme.DIM_R_SM;
    /** Search, segmented controls, popovers and compact floating panels. */
    public static final float R_MEDIUM = FathomTheme.DIM_R_MD;
    /** Cards, drawers and substantial overlays. */
    public static final float R_CARD = FathomTheme.DIM_R_LG;
    /** The outer application panel and large destination controls. */
    public static final float R_PANEL = FathomTheme.DIM_R_XL;

    // -----------------------------------------------------------------------
    // Fathom signature geometry
    // -----------------------------------------------------------------------

    /** Length of the interrupted edge used by Fathom's stepped depth bracket. */
    public static final float SIGNATURE_LENGTH = 24f;
    /** Each turn in the depth bracket moves by this amount. */
    public static final float SIGNATURE_STEP = 4f;
    /** Distance between the signature stroke and the surface edge. */
    public static final float SIGNATURE_INSET = 1.5f;
    /** A hovered card rises this far before press compression wins. */
    public static final float HOVER_ELEVATION = 1.25f;

    // Generated-scale aliases retained for code outside the component layer.
    public static final float R_XS = R_SMALL;
    public static final float R_SM = R_CONTROL;
    public static final float R_MD = R_MEDIUM;
    public static final float R_LG = R_CARD;
    public static final float R_XL = R_PANEL;

    /** Three control heights. A control that is none of these will not line up. */
    public static final float H_SM = FathomTheme.DIM_H_SM;
    public static final float H_MD = FathomTheme.DIM_H_MD;
    public static final float H_LG = FathomTheme.DIM_H_LG;

    /**
     * The widest a column of settings gets.
     *
     * <p>A row is a name on the left and a control on the right, and a hub two
     * and a half thousand units wide would put a metre of nothing between them.
     * Pages made of rows use this and centre nothing: the column stays against
     * the left margin, so the eye keeps one starting point down the page.
     */
    public static final float COLUMN = FathomTheme.DIM_COLUMN;
    public static final float COLUMN_WIDE = FathomTheme.DIM_COLUMN_WIDE;

    /** A hairline. One logical unit, which is one device pixel at 1×. */
    public static final float HAIRLINE = FathomTheme.DIM_HAIRLINE;

    /** The hub's own frame. */
    public static final float HEADER_HEIGHT = FathomTheme.DIM_HUB_HEADER_HEIGHT;
    public static final float TOPBAR_HEIGHT = FathomTheme.DIM_TOPBAR_HEIGHT;
    public static final float BOTTOMBAR_HEIGHT = FathomTheme.DIM_BOTTOMBAR_HEIGHT;
    public static final float RAIL_WIDTH = FathomTheme.DIM_RAIL_WIDTH;
    public static final float RAIL_TRAY_WIDTH = FathomTheme.DIM_RAIL_TRAY_WIDTH;
    public static final float RAIL_ROW = FathomTheme.DIM_RAIL_ROW;
    public static final float ROW_HEIGHT = 52f;

    /**
     * How much of the screen the hub takes.
     *
     * <p>Most of it. The first version was a panel in the middle of a dark
     * screen with a content pane too small for a sentence, because it was laid
     * out in a coordinate space 240 units tall. There is no reason for a client
     * menu to be small: it is the only thing the user is looking at while it is
     * open.
     */
    public static final float SCREEN_FRACTION_X = FathomTheme.RATIO_HUB_SCREEN_FRACTION_X;
    public static final float SCREEN_FRACTION_Y = FathomTheme.RATIO_HUB_SCREEN_FRACTION_Y;
    public static final float MAX_WIDTH = FathomTheme.DIM_HUB_MAX_WIDTH;
    public static final float MAX_HEIGHT = FathomTheme.DIM_HUB_MAX_HEIGHT;
    public static final float MIN_WIDTH = FathomTheme.DIM_HUB_MIN_WIDTH;
    public static final float MIN_HEIGHT = FathomTheme.DIM_HUB_MIN_HEIGHT;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public static int argb(float alpha, int rgb) {
        return Colour.argb(alpha, rgb);
    }

    public static int fade(int colour, float alpha) {
        return Colour.fade(colour, alpha);
    }

    public static int scaleAlpha(int colour, float factor) {
        return Colour.scaleAlpha(colour, factor);
    }

    public static int mix(int from, int to, float amount) {
        return Colour.mix(from, to, amount);
    }

    /** Clamp a semantic radius to geometry that can physically contain it. */
    public static float radius(float semanticRadius, Rect area) {
        return RoundedGeometry.radius(semanticRadius, area.width, area.height);
    }

    /** A true pill is always half the shorter dimension, never a magic radius. */
    public static float pill(Rect area) {
        return RoundedGeometry.pill(area.width, area.height);
    }

    /** Radius for a concentric child inset from a rounded parent. */
    public static float innerRadius(float outerRadius, Rect outer, float inset) {
        return RoundedGeometry.insetRadius(outerRadius, outer.width, outer.height, inset);
    }

    /** Align a static edge to the framebuffer at every GUI scale. */
    public static float snap(Renderer ui, float logical) {
        return RoundedGeometry.snap(logical, ui.scale());
    }

    /** Align all four outer edges of a static rectangle to device pixels. */
    public static Rect pixelAligned(Renderer ui, Rect area) {
        return Rect.of(snap(ui, area.x), snap(ui, area.y),
                snap(ui, area.right()), snap(ui, area.bottom()));
    }
}
