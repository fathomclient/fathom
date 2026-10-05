package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.generated.FathomTheme;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * Fathom's visual system in the game: neutral Rainforest glass.
 *
 * <h2>One source, two implementations</h2>
 *
 * <p>Every value here comes from the manifest that produces the launcher's
 * CSS -- {@code FathomTheme.Tokens}, the `system` half of it. The in-game client
 * should feel like the launcher walked into Minecraft, and a slightly different
 * tone or corner reads, immediately, as a different program.
 *
 * <h2>Glass over one shared backdrop</h2>
 *
 * <p>When Gameplay glass is selected, the Hub blurs the framebuffer once and
 * every surface composes over that shared result. Individual panes never
 * recapture or reblur their contents. The materials here are the manifest's
 * {@code tint-game} fills: clear is the launcher's own faint neutral veil, light
 * and standard are transparent dark deepenings, and only dense is dense.
 * A pale veil behind light type costs it contrast, so a group that carries text
 * deepens a little instead. The background -- the photograph, or the blurred
 * world under a controlled tint -- provides the colour; the
 * glass borrows it. There is no application shell: navigation and content
 * float as separate groups.
 *
 * <p>The hierarchy is the launcher's: clear is the default substrate, light is
 * navigation, rows and low-priority groups, standard is what is selected or
 * important, and dense is only for modals, popovers and temporary focus.
 *
 * <h2>Density, not colour</h2>
 *
 * <p>There is no accent hue. Selection, hover and "on" are a denser fill, a
 * brighter rim and full-strength neutral type -- the launcher's rule. The status
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

    /** Clear glass: the default substrate; controls on the photograph. */
    public static final int MATERIAL_CLEAR = Tokens.MATERIAL_CLEAR_FILL;
    /** Light glass: navigation, rows and low-priority groups. */
    public static final int MATERIAL_LIGHT = Tokens.MATERIAL_LIGHT_FILL;
    /** Standard glass: what is selected, and important interactive groups. */
    public static final int MATERIAL_STANDARD = Tokens.MATERIAL_STANDARD_FILL;
    /** Dense glass: modals, popovers and temporary focus. Never the default. */
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

    /** Was pure black. Now standard glass: a bar over the world. */
    public static final int CHROME = MATERIAL_STANDARD;

    /** A panel: light glass. */
    public static final int PANEL = MATERIAL_LIGHT;

    /** Inside a panel: a flat control fill, never a second glass. */
    public static final int RAISED = Tokens.CONTROL_FILL;
    public static final int RAISED_HOVER = Tokens.CONTROL_FILL_HOVER;

    /** A popover: dense glass. */
    public static final int OVERLAY = MATERIAL_DENSE;

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
     * The light deepening over the world behind the hub.
     *
     * <p>Neutral and light: the world stays visible as the hub's background,
     * and is only quieted enough that text holds over it.
     */
    public static final int SCRIM = Tokens.GAME_SCRIM_WORLD;
    /** The extra, lighter deepening over the photograph behind the hub. */
    public static final int SCRIM_POSTER = Tokens.GAME_SCRIM_POSTER;
    /** Under a modal: the launcher's modal scrim. */
    public static final int MODAL_SCRIM = Tokens.GAME_MODAL_SCRIM;

    /** Under a floating panel. Neutral and soft; not a blur. */
    public static final int SHADOW = Colour.fade(Tokens.COLOR_SHADOW, 0.55f);

    // -----------------------------------------------------------------------
    // Depth, as the pages ask for it
    // -----------------------------------------------------------------------

    /** A pressed well, and the deepest a resting control gets: standard glass. */
    public static final int BACKGROUND = MATERIAL_STANDARD;
    /** Wells: fields, tracks, the search box. Sunken, never a panel of its own. */
    public static final int RECESSED = Tokens.CONTROL_FILL_SUNKEN;
    /** A card or secondary control inside the window: a flat fill. */
    public static final int SURFACE = Tokens.CONTROL_FILL;
    /** Selected or current: the denser fill. */
    public static final int ELEVATED = Tokens.CONTROL_FILL_SELECTED;
    /** Transient: popovers, drawers, anything over everything. Dense glass. */
    public static final int FLOATING = MATERIAL_DENSE;

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

    /** The neutral light ring a keyboard-focused control wears. */
    public static final int FOCUS = Tokens.COLOR_FOCUS;

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /** Neutral light text at progressively lower densities. */
    public static final int TEXT = Tokens.COLOR_TEXT;
    public static final int TEXT_MUTED = Tokens.COLOR_TEXT_SECONDARY;
    public static final int TEXT_FAINT = Tokens.COLOR_TEXT_TERTIARY;
    public static final int TEXT_DISABLED = Tokens.COLOR_TEXT_DISABLED;
    public static final int TEXT_LABEL = Tokens.COLOR_TEXT_SECONDARY;
    /** Type on a light surface, where there is one. */
    public static final int TEXT_INVERSE = Tokens.COLOR_TEXT_INVERSE;

    // -----------------------------------------------------------------------
    // Selection and status
    // -----------------------------------------------------------------------

    /** Neutral light: an "on" track, a slider's fill, a chosen swatch. */
    public static final int ACCENT = Tokens.PALETTE_CHAMPAGNE_300;
    public static final int ACCENT_STRONG = Tokens.PALETTE_IVORY_100;
    public static final int ACCENT_DEEP = Tokens.PALETTE_BRONZE_600;
    public static final int ACCENT_WASH = Tokens.CONTROL_FILL_SELECTED;

    /**
     * Energy: what the old red did, now as neutral luminance.
     *
     * <p>{@code ENERGY} is a wash (scaled down before use it is a selected
     * fill), {@code ENERGY_EDGE} a brightened rim, {@code ENERGY_MARK} a caret,
     * a check, a thumb -- full neutral light. None of them is a hue.
     */
    public static final int ENERGY = 0x99FFFFFF;
    public static final int ENERGY_EDGE = 0x4DFFFFFF;
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
    /** Icon marks, fields and anything that is not a pill. */
    public static final float R_CONTROL = Tokens.RADIUS_MD;
    /** Popovers and menus: the launcher's `.ui-popover`. */
    public static final float R_MEDIUM = Tokens.RADIUS_LG;
    /** Cards, groups and modals. */
    public static final float R_CARD = Tokens.RADIUS_XL;
    /** Major floating groups: the rail, a page's list. */
    public static final float R_PANEL = Tokens.RADIUS_2XL;

    /** A hovered card rises this far before press compression wins. */
    public static final float HOVER_ELEVATION = 1.25f;

    // Generated-scale aliases retained for code outside the component layer.
    public static final float R_XS = R_SMALL;
    public static final float R_SM = R_CONTROL;
    public static final float R_MD = R_MEDIUM;
    public static final float R_LG = R_CARD;
    public static final float R_XL = R_PANEL;

    /** Three control heights -- compact 32, standard 36, primary 44 (`metrics`). A control that is none of these will not line up. */
    public static final float H_SM = Tokens.CONTROL_HEIGHT_SM;
    public static final float H_MD = Tokens.CONTROL_HEIGHT_MD;
    public static final float H_LG = Tokens.CONTROL_HEIGHT_LG;

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

    /*
     * There is no HAIRLINE or BORDER_* thickness here any more, deliberately.
     * They were logical units -- "1" was 1.5 device pixels at 1080p, which the
     * GL backend rounded to 2 and NanoVG smeared over three rows, and
     * BORDER_SELECTED (1.8) made a selected control visibly thicker. A border
     * is {@code ui.rim(...)} (one device pixel, snapped) and a divider is
     * {@code ui.pixel()} tall. State changes a rim's colour, never its width.
     */

    /** The hub's own frame. */
    public static final float HEADER_HEIGHT = FathomTheme.DIM_HUB_HEADER_HEIGHT;
    public static final float TOPBAR_HEIGHT = FathomTheme.DIM_TOPBAR_HEIGHT;
    public static final float BOTTOMBAR_HEIGHT = FathomTheme.DIM_BOTTOMBAR_HEIGHT;
    public static final float RAIL_WIDTH = FathomTheme.DIM_RAIL_WIDTH;
    public static final float RAIL_TRAY_WIDTH = FathomTheme.DIM_RAIL_TRAY_WIDTH;
    public static final float RAIL_ROW = FathomTheme.DIM_RAIL_ROW;
    /** List rows: {@code Metrics.ROW} (48), dense {@code Metrics.ROW_DENSE} (40). */
    public static final float ROW_HEIGHT = FathomTheme.Metrics.ROW;
    public static final float ROW_DENSE = FathomTheme.Metrics.ROW_DENSE;

    /** Icon sizes by role -- navigation, inline in a control or row, in a tile. */
    public static final float ICON_NAV = FathomTheme.Metrics.ICON_NAV;
    public static final float ICON_INLINE = FathomTheme.Metrics.ICON_INLINE;
    public static final float ICON_TILE = FathomTheme.Metrics.ICON_TILE;
    /** A module or item mark. */
    public static final float TILE = FathomTheme.Metrics.TILE;

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

    /**
     * The launcher's control radius (12dp), clamped to the shape: buttons,
     * fields, dropdowns, rows and segmented tracks. Pills are for switches,
     * slider thumbs, badges and round icon buttons, as in the launcher; a
     * button drawn as a pill beside one drawn at 12dp was the in-game
     * interface's most visible inconsistency.
     */
    public static float control(Rect area) {
        return radius(R_CONTROL, area);
    }

    /** A selection inside a control's track: the segment radius (10dp), concentric with {@link #control}. */
    public static float segment(Rect area) {
        return radius(R_SMALL, area);
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
