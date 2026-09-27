package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.generated.FathomTheme;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * Fathom's visual system, in the game: the golden-hour glass.
 *
 * <h2>One source, two implementations</h2>
 *
 * <p>Every value here comes from the manifest that produces the launcher's
 * CSS -- {@code FathomTheme.Tokens}, the `system` half of it. The in-game client
 * should feel like the launcher walked into Minecraft, and a slightly different
 * ivory or corner reads, immediately, as a different program.
 *
 * <h2>Glass without blur</h2>
 *
 * <p>The launcher's panes blur what is behind them. The game's do not: a blur
 * is a framebuffer read and a pass per surface, and the game has better things
 * to spend a frame on. So the materials here are the manifest's
 * {@code tint-noblur} fills -- the same warm umber at the density the launcher
 * itself falls back to with blur turned off -- plus the rims and the type. The
 * hierarchy is the launcher's: clear for type on a controlled background,
 * light for low-priority groups, standard for persistent panels, dense for
 * what floats over everything.
 *
 * <h2>Density, not colour</h2>
 *
 * <p>There is no accent hue. Selection, hover and "on" are a denser fill, a
 * brighter rim and full-strength ivory type -- the launcher's rule. The status
 * tones (sage, amber, terracotta) are the only hues, and only where a state
 * needs one.
 *
 * <h2>Names kept from the previous system</h2>
 *
 * <p>Most constants below keep their names so the pages that read them did not
 * all have to change at once; what each one is now is written beside it.
 */
public final class Skin {

    private Skin() {}

    // -----------------------------------------------------------------------
    // Materials
    // -----------------------------------------------------------------------

    /** Clear glass: a control on the photograph that should be barely there. */
    public static final int MATERIAL_CLEAR = Tokens.MATERIAL_CLEAR_FILL;
    /** Light glass: low-priority groups. */
    public static final int MATERIAL_LIGHT = Tokens.MATERIAL_LIGHT_FILL;
    /** Standard glass: persistent panels -- the hub window, the menu's panes. */
    public static final int MATERIAL_STANDARD = Tokens.MATERIAL_STANDARD_FILL;
    /** Dense glass: what floats over everything -- popovers, sheets, menus. */
    public static final int MATERIAL_DENSE = Tokens.MATERIAL_DENSE_FILL;
    /** Dense glass that must be readable over anything, including itself. */
    public static final int MATERIAL_DENSE_SOLID = Tokens.MATERIAL_DENSE_FILL_SOLID;

    /** The lit top edge of a pane, and its faint all-round rim, per material. */
    public static final int RIM_TOP = Tokens.MATERIAL_STANDARD_RIM_TOP;
    public static final int RIM_EDGE = Tokens.MATERIAL_STANDARD_RIM_EDGE;
    public static final int RIM_TOP_DENSE = Tokens.MATERIAL_DENSE_RIM_TOP;
    public static final int RIM_EDGE_DENSE = Tokens.MATERIAL_DENSE_RIM_EDGE;
    public static final int RIM_TOP_LIGHT = Tokens.MATERIAL_LIGHT_RIM_TOP;
    public static final int RIM_EDGE_LIGHT = Tokens.MATERIAL_LIGHT_RIM_EDGE;
    public static final int RIM_TOP_CLEAR = Tokens.MATERIAL_CLEAR_RIM_TOP;
    public static final int RIM_EDGE_CLEAR = Tokens.MATERIAL_CLEAR_RIM_EDGE;

    /** The dark, dense fill of a primary action. Never coloured. */
    public static final int PROMINENT = Tokens.CONTROL_PROMINENT;
    public static final int PROMINENT_HOVER = Tokens.CONTROL_PROMINENT_HOVER;

    // -----------------------------------------------------------------------
    // Surfaces (previous names)
    // -----------------------------------------------------------------------

    /** Was pure black. Now dense glass. */
    public static final int CHROME = MATERIAL_DENSE;

    /** The panel: standard glass. */
    public static final int PANEL = MATERIAL_STANDARD;

    /** Inside a panel: a flat control fill, never a second glass. */
    public static final int RAISED = Tokens.CONTROL_FILL;
    public static final int RAISED_HOVER = Tokens.CONTROL_FILL_HOVER;

    /** A popover that must be readable over anything. */
    public static final int OVERLAY = MATERIAL_DENSE_SOLID;

    /** A control sitting on the photograph brings its own glass. */
    public static final int GLASS = MATERIAL_LIGHT;
    public static final int GLASS_HOVER = MATERIAL_STANDARD;
    public static final int GLASS_PRESSED = MATERIAL_DENSE;
    public static final int GLASS_EDGE = Tokens.MATERIAL_LIGHT_RIM_EDGE;
    public static final int GLASS_EDGE_STRONG = Tokens.MATERIAL_LIGHT_RIM_TOP;

    public static final int CHROME_LINE = Tokens.CONTROL_HAIRLINE;
    public static final int CHROME_LINE_STRONG = Tokens.CONTROL_HAIRLINE_STRONG;

    /** The thin bright edge. Low contrast: higher and it becomes a stroke. */
    public static final int EDGE = RIM_EDGE;
    public static final int EDGE_STRONG = Tokens.CONTROL_HAIRLINE_STRONG;
    /** The lit top line. The detail that makes a pane read as glass. */
    public static final int SHEEN = RIM_TOP;

    /**
     * The warm deepening over the world behind the hub.
     *
     * <p>Umber, not black, and lighter than the old one: the world is context,
     * and the window above it is already standard glass, so between the two
     * body text sits on a darkened, warmed version of whatever the player was
     * looking at rather than on a black hole.
     */
    public static final int SCRIM = 0x8C1A1006;

    /** Under a floating panel. Warm and soft; not a blur. */
    public static final int SHADOW = Colour.fade(Tokens.COLOR_SHADOW, 0.55f);

    // -----------------------------------------------------------------------
    // Depth, as the pages ask for it
    // -----------------------------------------------------------------------

    /** The window: standard glass. */
    public static final int BACKGROUND = MATERIAL_STANDARD;
    /** Wells: fields, tracks, the search box. Sunken, never a panel of its own. */
    public static final int RECESSED = Tokens.CONTROL_FILL_SUNKEN;
    /** A card or secondary control inside the window: a flat fill. */
    public static final int SURFACE = Tokens.CONTROL_FILL;
    /** Selected or current: the denser fill. */
    public static final int ELEVATED = Tokens.CONTROL_FILL_SELECTED;
    /** Transient: popovers, drawers, anything over everything. */
    public static final int FLOATING = MATERIAL_DENSE_SOLID;

    /** One step up from a surface: what hover does, and nothing more. */
    public static final int SURFACE_HOVER = Tokens.CONTROL_FILL_HOVER;
    public static final int EDGE_RECESSED = Tokens.CONTROL_HAIRLINE;
    public static final int EDGE_SURFACE = RIM_EDGE;
    public static final int EDGE_ELEVATED = Tokens.CONTROL_HAIRLINE_STRONG;
    public static final int INNER_HIGHLIGHT = Tokens.CONTROL_FILL_FAINT;

    // -----------------------------------------------------------------------
    // Control states
    // -----------------------------------------------------------------------

    public static final int FILL = Tokens.CONTROL_FILL_FAINT;
    public static final int FILL_HOVER = Tokens.CONTROL_FILL;
    public static final int FILL_PRESSED = Tokens.CONTROL_FILL_HOVER;
    public static final int FILL_ACTIVE = Tokens.CONTROL_FILL_SELECTED;
    public static final int FILL_SUNKEN = Tokens.CONTROL_FILL_SUNKEN;
    public static final int FILL_DISABLED = Tokens.CONTROL_FILL_FAINT;

    /** The ivory ring a keyboard-focused control wears. */
    public static final int FOCUS = Tokens.COLOR_FOCUS;

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /** Ivory, champagne, and champagne at two lower densities. */
    public static final int TEXT = Tokens.COLOR_TEXT;
    public static final int TEXT_MUTED = Tokens.COLOR_TEXT_SECONDARY;
    public static final int TEXT_FAINT = Tokens.COLOR_TEXT_TERTIARY;
    public static final int TEXT_DISABLED = Tokens.COLOR_TEXT_DISABLED;
    public static final int TEXT_LABEL = Tokens.COLOR_TEXT_SECONDARY;
    /** Type on an ivory surface, where there is one. */
    public static final int TEXT_INVERSE = Tokens.COLOR_TEXT_INVERSE;

    // -----------------------------------------------------------------------
    // Selection and status
    // -----------------------------------------------------------------------

    /** Champagne: an "on" track, a slider's fill, a chosen swatch. */
    public static final int ACCENT = Tokens.PALETTE_CHAMPAGNE_300;
    public static final int ACCENT_STRONG = Tokens.PALETTE_IVORY_100;
    public static final int ACCENT_DEEP = Tokens.PALETTE_BRONZE_600;
    public static final int ACCENT_WASH = Tokens.CONTROL_FILL_SELECTED;

    /**
     * Energy: what the old red did, now as ivory luminance.
     *
     * <p>{@code ENERGY} is a wash (scaled down before use it is a selected
     * fill), {@code ENERGY_EDGE} a brightened rim, {@code ENERGY_MARK} a caret,
     * a check, a thumb -- full ivory. None of them is a hue.
     */
    public static final int ENERGY = 0x99FFF4DE;
    public static final int ENERGY_EDGE = 0x4DFFF4DE;
    public static final int ENERGY_MARK = Tokens.COLOR_TEXT;

    public static final int GOOD = Tokens.COLOR_SUCCESS_TEXT;
    public static final int WARN = Tokens.COLOR_WARNING_TEXT;
    /** Danger is terracotta, and only for what destroys or fails. */
    public static final int BAD = Tokens.COLOR_DANGER_TEXT;
    public static final int BAD_WASH = Tokens.COLOR_DANGER_TINT;

    // -----------------------------------------------------------------------
    // Spacing, on the launcher's 4px scale
    // -----------------------------------------------------------------------

    public static final float S1 = Tokens.SPACE_1;
    public static final float S2 = Tokens.SPACE_2;
    public static final float S3 = Tokens.SPACE_3;
    public static final float S4 = Tokens.SPACE_4;
    public static final float S5 = Tokens.SPACE_5;
    public static final float S6 = Tokens.SPACE_6;
    public static final float S8 = Tokens.SPACE_8;
    public static final float S10 = Tokens.SPACE_10;

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    /** Small inset shapes: swatches, tags and compact tooltips. */
    public static final float R_SMALL = Tokens.RADIUS_SM;
    /** Buttons that are not pills, fields and list rows. */
    public static final float R_CONTROL = Tokens.RADIUS_MD;
    /** Search, segmented controls, icon containers and popovers. */
    public static final float R_MEDIUM = Tokens.RADIUS_MD;
    /** Cards, drawers and substantial overlays. */
    public static final float R_CARD = Tokens.RADIUS_LG;
    /** The outer application panel. */
    public static final float R_PANEL = Tokens.RADIUS_XL;

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
    public static final float BORDER_STANDARD = 1f;
    public static final float BORDER_ENERGIZED = 1.4f;
    public static final float BORDER_SELECTED = 1.8f;

    /** The hub's own frame. */
    public static final float HEADER_HEIGHT = FathomTheme.DIM_HUB_HEADER_HEIGHT;
    public static final float TOPBAR_HEIGHT = FathomTheme.DIM_TOPBAR_HEIGHT;
    public static final float BOTTOMBAR_HEIGHT = FathomTheme.DIM_BOTTOMBAR_HEIGHT;
    public static final float RAIL_WIDTH = FathomTheme.DIM_RAIL_WIDTH;
    public static final float RAIL_TRAY_WIDTH = FathomTheme.DIM_RAIL_TRAY_WIDTH;
    public static final float RAIL_ROW = FathomTheme.DIM_RAIL_ROW;
    public static final float ROW_HEIGHT = 52f;

    /** In-game composition tokens; pages use these rather than local guesses. */
    public static final float SIDEBAR_WIDTH = 172f;
    public static final float PAGE_INSET_X = 28f;
    public static final float PAGE_INSET_Y = 24f;
    public static final float CARD_PADDING = 16f;
    public static final float CARD_GAP = 16f;
    public static final float SECTION_GAP = 20f;
    public static final float ROW_GAP = 12f;

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
