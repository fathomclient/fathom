package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.generated.FathomTheme;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Graphite + Instrument, in the game: the identity's materials, rims, shadow,
 * type roles and the few controls the proof-of-direction screens need.
 *
 * <p>Every value comes from {@link GraphiteTokens}, generated from the same
 * manifest as the launcher's {@code --g-*} properties. Only the title screen and
 * the Hub's chrome and Mods section use this class; everything else still reads
 * {@link Skin} until the identity is rolled out.
 *
 * <h2>Rules this enforces</h2>
 *
 * <ul>
 *   <li>A material is one fill, one uniform 1-device-pixel rim inside the edge,
 *   and -- only for the outermost group or an overlay -- one shadow.</li>
 *   <li>Nothing inside a material gets a material: items inside a surface are
 *   {@link #overlay}s and {@link #hairline}s.</li>
 *   <li>States change density and rim, never colour; disabled is a token swap.</li>
 *   <li>No glow or gradient rim; blur belongs to one screen backdrop, never an
 *   individual material.</li>
 * </ul>
 */
public final class Graphite {

    private Graphite() {}

    // -----------------------------------------------------------------------
    // Palette
    // -----------------------------------------------------------------------

    public static final int INK_0 = GraphiteTokens.COLOR_INK_0;
    public static final int INK_1 = GraphiteTokens.COLOR_INK_1;
    public static final int TEXT = GraphiteTokens.COLOR_TEXT_PRIMARY;
    public static final int TEXT_SECONDARY = GraphiteTokens.COLOR_TEXT_SECONDARY;
    public static final int TEXT_TERTIARY = GraphiteTokens.COLOR_TEXT_TERTIARY;
    public static final int TEXT_DISABLED = GraphiteTokens.COLOR_TEXT_DISABLED;
    public static final int RIM = GraphiteTokens.COLOR_RIM;
    public static final int RIM_HOVER = GraphiteTokens.COLOR_RIM_HOVER;
    public static final int RIM_SELECTED = GraphiteTokens.COLOR_RIM_SELECTED;
    public static final int HAIRLINE = GraphiteTokens.COLOR_HAIRLINE;
    public static final int STATE_HOVER = GraphiteTokens.COLOR_STATE_HOVER;
    public static final int STATE_SELECTED = GraphiteTokens.COLOR_STATE_SELECTED;
    public static final int SIGNAL = GraphiteTokens.COLOR_SIGNAL;
    /** The icon's arc blue: the current place's glyph, as in the launcher's rail. */
    public static final int BRAND_LIGHT = FathomTheme.Tokens.BRAND_ORBIT_LIGHT;
    public static final int SCROLLBAR = GraphiteTokens.COLOR_SCROLLBAR;

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    public static final float R_PANEL = GraphiteTokens.RADIUS_PANEL;
    public static final float R_CONTROL = GraphiteTokens.RADIUS_CONTROL;
    public static final float R_SEGMENT = GraphiteTokens.RADIUS_SEGMENT;
    public static final float R_ICON = GraphiteTokens.RADIUS_ICON;
    public static final float CONTROL = GraphiteTokens.SIZE_CONTROL;
    public static final float CONTROL_COMPACT = GraphiteTokens.SIZE_CONTROL_COMPACT;
    public static final float S1 = GraphiteTokens.SPACE_1;
    public static final float S2 = GraphiteTokens.SPACE_2;
    public static final float S3 = GraphiteTokens.SPACE_3;
    public static final float S4 = GraphiteTokens.SPACE_4;
    public static final float S6 = GraphiteTokens.SPACE_6;
    public static final float S8 = GraphiteTokens.SPACE_8;
    public static final float S12 = GraphiteTokens.SPACE_12;
    public static final float PAD = GraphiteTokens.LAYOUT_PANEL_PAD;
    public static final float GROUP_GAP = GraphiteTokens.LAYOUT_GROUP_GAP;
    public static final float ICON_INLINE = GraphiteTokens.SIZE_ICON_INLINE;
    public static final float ICON_NAV = GraphiteTokens.SIZE_ICON_NAV;
    public static final float SWITCH_W = GraphiteTokens.SIZE_SWITCH_WIDTH;
    public static final float SWITCH_H = GraphiteTokens.SIZE_SWITCH_HEIGHT;
    public static final float SWITCH_KNOB = GraphiteTokens.SIZE_SWITCH_KNOB;

    // -----------------------------------------------------------------------
    // Type: the specification's roles
    // -----------------------------------------------------------------------

    public static final Type TITLE = role(GraphiteTokens.TYPE_TITLE_SIZE, GraphiteTokens.TYPE_TITLE_WEIGHT, false,
            GraphiteTokens.TYPE_TITLE_TRACKING);
    public static final Type HEADING = role(GraphiteTokens.TYPE_HEADING_SIZE, GraphiteTokens.TYPE_HEADING_WEIGHT, false,
            GraphiteTokens.TYPE_HEADING_TRACKING);
    public static final Type UI = role(GraphiteTokens.TYPE_UI_SIZE, GraphiteTokens.TYPE_UI_WEIGHT, false,
            GraphiteTokens.TYPE_UI_TRACKING);
    public static final Type BODY = role(GraphiteTokens.TYPE_BODY_SIZE, GraphiteTokens.TYPE_BODY_WEIGHT, false,
            GraphiteTokens.TYPE_BODY_TRACKING);
    public static final Type SMALL = role(GraphiteTokens.TYPE_SMALL_SIZE, GraphiteTokens.TYPE_SMALL_WEIGHT, false,
            GraphiteTokens.TYPE_SMALL_TRACKING);
    public static final Type CAPTION = role(GraphiteTokens.TYPE_CAPTION_SIZE, GraphiteTokens.TYPE_CAPTION_WEIGHT, false,
            GraphiteTokens.TYPE_CAPTION_TRACKING);
    public static final Type DATA_L = role(GraphiteTokens.TYPE_DATA_L_SIZE, GraphiteTokens.TYPE_DATA_L_WEIGHT, true,
            GraphiteTokens.TYPE_DATA_L_TRACKING);
    public static final Type DATA_M = role(GraphiteTokens.TYPE_DATA_M_SIZE, GraphiteTokens.TYPE_DATA_M_WEIGHT, true,
            GraphiteTokens.TYPE_DATA_M_TRACKING);
    public static final Type DATA_S = role(GraphiteTokens.TYPE_DATA_S_SIZE, GraphiteTokens.TYPE_DATA_S_WEIGHT, true,
            GraphiteTokens.TYPE_DATA_S_TRACKING);

    private static Type role(float size, int weight, boolean mono, float tracking) {
        Type.Weight face;
        if (mono) {
            face = weight >= 500 ? Type.Weight.MONO_MEDIUM : Type.Weight.MONO_REGULAR;
        } else {
            face = weight >= 600 ? Type.Weight.SEMIBOLD : weight >= 500 ? Type.Weight.MEDIUM : Type.Weight.REGULAR;
        }
        // The launcher's letter-spacing for the role, in ems.
        return new Type(size, face, tracking);
    }

    /** The 9-device-pixel floor: below it a role is held at 9px, never smaller. */
    private static final float TEXT_FLOOR = GraphiteTokens.SIZE_TEXT_FLOOR_PX;
    private static final Type[] FLOOR_FROM = new Type[16];
    private static final Type[] FLOOR_TO = new Type[16];
    private static float floorScale = -1f;

    /** {@code type}, held at 9 device pixels when the UI scale would put it under. */
    public static Type floor(Renderer ui, Type type) {
        float scale = ui.scale();
        if (type.size() * scale >= TEXT_FLOOR) {
            return type;
        }
        if (scale != floorScale) {
            floorScale = scale;
            java.util.Arrays.fill(FLOOR_FROM, null);
            java.util.Arrays.fill(FLOOR_TO, null);
        }
        for (int slot = 0; slot < FLOOR_FROM.length; slot++) {
            if (FLOOR_FROM[slot] == type) {
                return FLOOR_TO[slot];
            }
            if (FLOOR_FROM[slot] == null) {
                FLOOR_FROM[slot] = type;
                FLOOR_TO[slot] = type.at(TEXT_FLOOR / scale);
                return FLOOR_TO[slot];
            }
        }
        return type.at(TEXT_FLOOR / scale);
    }

    // -----------------------------------------------------------------------
    // Materials
    // -----------------------------------------------------------------------

    /** The four densities of one neutral glass. */
    public enum Material {
        CLEAR(GraphiteTokens.MATERIAL_CLEAR_FILL, GraphiteTokens.MATERIAL_CLEAR_RIM,
                GraphiteTokens.MATERIAL_CLEAR_BLUR, GraphiteTokens.MATERIAL_CLEAR_SHADOW),
        LIGHT(GraphiteTokens.MATERIAL_LIGHT_FILL, GraphiteTokens.MATERIAL_LIGHT_RIM,
                GraphiteTokens.MATERIAL_LIGHT_BLUR, GraphiteTokens.MATERIAL_LIGHT_SHADOW),
        STANDARD(GraphiteTokens.MATERIAL_STANDARD_FILL, GraphiteTokens.MATERIAL_STANDARD_RIM,
                GraphiteTokens.MATERIAL_STANDARD_BLUR, GraphiteTokens.MATERIAL_STANDARD_SHADOW),
        DENSE(GraphiteTokens.MATERIAL_DENSE_FILL, GraphiteTokens.MATERIAL_DENSE_RIM,
                GraphiteTokens.MATERIAL_DENSE_BLUR, GraphiteTokens.MATERIAL_DENSE_SHADOW);

        public final int fill;
        public final int rim;
        /** {@code blur()} behind it, CSS pixels. */
        public final float blur;
        public final boolean shadowAllowed;

        Material(int fill, int rim, float blur, boolean shadowAllowed) {
            this.fill = fill;
            this.rim = rim;
            this.blur = blur;
            this.shadowAllowed = shadowAllowed;
        }

        /** The next density up: what selection turns a floating surface into. */
        public Material denser() {
            return this == DENSE ? DENSE : values()[ordinal() + 1];
        }
    }

    /**
     * A floating surface: one fill, one rim, and the shadow when asked for and
     * the material allows one.
     *
     * @param hover 0-1: fill alpha +6 points, rim to 12%
     * @param pressed 0-1: fill alpha +10 points
     * @param selected 0-1: the next density up, rim 16%
     */
    public static void surface(Renderer ui, Rect area, float radius, Material material,
            float hover, float pressed, float selected, boolean shadow) {
        if (shadow && material.shadowAllowed) {
            shadow(ui, area, radius);
        }
        // Glass, as the launcher's Graphite panes are:
        // backdrop-filter: blur(<material>) saturate(1.12).
        if (material.blur > 0f) {
            ui.glass(area.x, area.y, area.right(), area.bottom(), radius, material.blur, GLASS_SATURATE, 1f);
        }
        int fill = material.fill;
        if (selected > 0.001f) {
            fill = Colour.mix(fill, material.denser().fill, selected);
        }
        float lift = GraphiteTokens.STATE_HOVER_ALPHA * hover + GraphiteTokens.STATE_PRESSED_ALPHA * pressed;
        if (lift > 0f) {
            fill = Colour.fade(fill, Math.min(1f, Colour.alpha(fill) + lift));
        }
        ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, fill);
        // Panes catch the scene's light from above (Glow), as the launcher's
        // Standard and Dense materials do. Not controls: a button-sized
        // surface is too small to hold a pool of light.
        if ((material == Material.STANDARD || material == Material.DENSE)
                && area.height > CONTROL * 2.5f) {
            Glow.sheen(ui, area, radius);
        }
        int rim = Colour.mix(Colour.mix(material.rim, RIM_HOVER, hover), RIM_SELECTED, selected);
        rim(ui, area, radius, rim);
    }

    /** The shade the dim is made of: the deepest ink, a touch cooler than black. */
    private static final int DIM_INK = 0x05080C;

    /**
     * The darkening between the scene and a Fathom screen: an even veil over
     * everything when {@code veil} (the Fathom background; gameplay glass has
     * its own, the RShift dim setting), then a soft ellipse of shade under
     * {@code focus}, the screen's own composition, so the world stays faintly
     * visible at the edges and the controls sit on calm ground.
     *
     * <p>Two fills and no blur: it reads nothing back and adds no pass, so it
     * costs the same over the cached backdrop and over live glass. Draw it
     * after the scene (and after the glass), before anything of the screen.
     */
    public static void dim(Renderer ui, Rect focus, boolean veil, float strength) {
        if (strength <= 0.001f) {
            return;
        }
        if (veil) {
            ui.rounded(0f, 0f, ui.width(), ui.height(), 0f,
                    Colour.argb(GraphiteTokens.BACKDROP_DIM_VEIL * strength, DIM_INK));
        }
        ui.radial(0f, 0f, ui.width(), ui.height(), 0f, focus.centreX(), focus.centreY(),
                Math.max(focus.width * 0.85f, ui.width() * 0.45f), Math.max(focus.height, ui.height() * 0.6f),
                Colour.argb(GraphiteTokens.BACKDROP_DIM_POOL * strength, DIM_INK), 1f);
    }

    /**
     * Take the screen as it stands as the backdrop the glass of every pane
     * drawn after it this frame frosts. Call once the scene is drawn and
     * before anything that should not show through the glass.
     */
    public static void captureFrost(Renderer ui) {
        if (ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas) {
            ((net.fathom.optimizations.fabric.gfx.UiCanvas) ui).captureFrost();
        }
    }

    /** A resting surface with no state. */
    public static void surface(Renderer ui, Rect area, float radius, Material material, boolean shadow) {
        surface(ui, area, radius, material, 0f, 0f, 0f, shadow);
    }

    /** The rim: one device pixel, inside the shape's edge, one uniform colour. */
    public static void rim(Renderer ui, Rect area, float radius, int argb) {
        ui.rim(area.x, area.y, area.right(), area.bottom(), radius, argb);
    }

    /**
     * The one soft shadow: black at 35%, 24dp of blur, 8dp down, no spread.
     * The renderer's shadow is a ring outside the shape, never under it, so a
     * translucent panel does not darken the world through itself; it is set
     * 8dp lower and fades over the outer half of the 24dp blur.
     */
    public static void shadow(Renderer ui, Rect area, float radius) {
        // --g-elevation: 0 8px 24px rgba(0, 0, 0, 0.35), as a box-shadow.
        ui.boxShadow(area.x, area.y, area.right(), area.bottom(), radius, GraphiteTokens.SHADOW_BLUR, 0f, 0f,
                GraphiteTokens.SHADOW_Y, GraphiteTokens.COLOR_SHADOW);
    }

    /** The Graphite panes' {@code saturate()}, after the blur. */
    private static final float GLASS_SATURATE = 1.12f;

    /**
     * An item inside a surface: the hover overlay, the selected overlay and --
     * when selected -- the 16% rim. Never a material of its own.
     */
    public static void overlay(Renderer ui, Rect area, float radius, float hover, float selected) {
        int fill = Colour.mix(Colour.scaleAlpha(STATE_HOVER, hover), STATE_SELECTED, selected);
        if (Colour.alpha(fill) > 0) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, fill);
        }
        if (selected > 0.001f) {
            rim(ui, area, radius, Colour.scaleAlpha(RIM_SELECTED, selected));
        }
    }

    /**
     * A segment inside a segmented control: fill only, never a rim.
     *
     * <p>The segment sits {@code Metrics.SEGMENT_INSET} inside the track, whose
     * own rim is already there; a second rim two pixels inside it reads as a
     * double border. Selection is the denser fill and full-strength type.
     */
    public static void segment(Renderer ui, Rect area, float hover, float selected) {
        int fill = Colour.mix(Colour.scaleAlpha(STATE_HOVER, hover), STATE_SELECTED, selected);
        if (Colour.alpha(fill) > 0) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), R_SEGMENT, fill);
        }
        if (net.fathom.optimizations.core.ui.render.GeometryDebug.ENABLED) {
            net.fathom.optimizations.core.ui.render.GeometryDebug.segment(
                    ui, area.x, area.y, area.right(), area.bottom(), R_SEGMENT);
        }
    }

    /** A divider: 1 device pixel, snapped. */
    public static void hairline(Renderer ui, float left, float right, float y) {
        float px = ui.pixel();
        ui.fill(left, y, right, y + px, HAIRLINE);
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------

    /**
     * The switch: 32x18, neutral monochrome. Off is an 8% track and a grey knob
     * at the left; on is a 16% track and a near-white knob at the right. No
     * colour. {@code on} is the animated position, 0-1.
     */
    public static void switchControl(Renderer ui, Rect box, float on, boolean enabled) {
        float radius = box.height / 2f;
        int track = Colour.mix(GraphiteTokens.COLOR_SWITCH_OFF_TRACK, GraphiteTokens.COLOR_SWITCH_ON_TRACK, on);
        ui.rounded(box.x, box.y, box.right(), box.bottom(), radius, track);
        rim(ui, box, radius, Colour.mix(RIM, RIM_SELECTED, on));
        float inset = (box.height - SWITCH_KNOB) / 2f;
        float x = box.x + inset + (box.width - SWITCH_KNOB - inset * 2f) * on;
        int knob = enabled
                ? Colour.mix(GraphiteTokens.COLOR_SWITCH_OFF_KNOB, GraphiteTokens.COLOR_SWITCH_ON_KNOB, on)
                : TEXT_DISABLED;
        ui.rounded(x, box.y + inset, x + SWITCH_KNOB, box.y + inset + SWITCH_KNOB, SWITCH_KNOB / 2f, knob);
    }

    /**
     * A module's mark: its glyph in a small rounded tile -- the launcher's mod
     * monogram, in the game. {@code Metrics.TILE} square with the small radius,
     * the glyph {@code Metrics.ICON_TILE}, centred on the pixel grid. On is the denser tile with full-strength ink;
     * off recedes. Every list and detail view uses this one, so a module looks
     * the same wherever it is shown.
     */
    public static void modMark(Renderer ui, Rect box, net.fathom.optimizations.core.ui.render.Icon icon,
            float on, float hover) {
        float radius = FathomTheme.Metrics.RADIUS_SMALL;
        int fill = Colour.mix(Colour.mix(STATE_HOVER, STATE_SELECTED, on), RIM_HOVER, hover * 0.5f);
        ui.rounded(box.x, box.y, box.right(), box.bottom(), radius, fill);
        rim(ui, box, radius, Colour.mix(RIM, RIM_SELECTED, on));
        float glyph = FathomTheme.Metrics.ICON_TILE;
        int ink = Colour.mix(TEXT_TERTIARY, TEXT, Math.max(on, hover * 0.5f));
        ui.icon(icon, Math.round(box.centreX() - glyph / 2f), Math.round(box.centreY() - glyph / 2f),
                glyph, ink);
    }

    /**
     * Nothing to show: the launcher's {@code EmptyState}. A 16-unit glyph in a
     * faint 32-unit circle, a short heading, one line under it, centred in the
     * space the content would have taken. No illustration, no card.
     */
    public static void emptyState(Renderer ui, Rect area, net.fathom.optimizations.core.ui.render.Icon icon,
            String title, String detail) {
        Type heading = floor(ui, UI);
        Type line = floor(ui, SMALL);
        float circle = FathomTheme.Metrics.TILE;
        float gap = S2;
        float height = circle + gap + ui.lineHeight(heading)
                + (detail == null || detail.isEmpty() ? 0f : S1 + ui.lineHeight(line));
        float top = Math.round(area.centreY() - height / 2f);
        float cx = area.centreX();
        Rect badge = new Rect(Math.round(cx - circle / 2f), top, circle, circle);
        ui.rounded(badge.x, badge.y, badge.right(), badge.bottom(), circle / 2f, STATE_HOVER);
        rim(ui, badge, circle / 2f, RIM);
        float inline = FathomTheme.Metrics.ICON_INLINE;
        ui.icon(icon, badge.centreX() - inline / 2f, badge.centreY() - inline / 2f, inline, TEXT_SECONDARY);
        float y = badge.bottom() + gap;
        ui.textCentred(net.fathom.optimizations.core.ui.layout.Text.fit(ui, title, heading, area.width - S8),
                cx, y + ui.ascent(heading), heading, TEXT);
        if (detail != null && !detail.isEmpty()) {
            y += ui.lineHeight(heading) + S1;
            ui.textCentred(net.fathom.optimizations.core.ui.layout.Text.fit(ui, detail, line, area.width - S8),
                    cx, y + ui.ascent(line), line, TEXT_SECONDARY);
        }
    }

    /**
     * A value's baseline centred in a box, from the face's own metrics.
     *
     * <p>The same rule as {@link Controls#baseline}: centre the ascent-plus-
     * descent box. This used to be {@code ascent / 2 - ascent * 0.06}, a guess
     * that sat text about an eighth of an em lower than every Controls row, so
     * the title screen and the Hub chrome had a different baseline from the
     * pages inside them.
     */
    public static float baseline(Renderer ui, Rect box, Type type) {
        return Controls.baseline(ui, box, type);
    }

    // -----------------------------------------------------------------------
    // The wordmark
    // -----------------------------------------------------------------------

    /** The brand art is drawn in its own inks: the tint only fades it. */
    private static final int BRAND_TINT = 0xFFFFFFFF;

    /**
     * Draw the wordmark, "fathom", with its ascender's top at {@code y}; returns
     * its width. {@code capHeight} is the ascender, which in Geist is the cap
     * height. It is drawn in the brand's inks, ivory warming to the arc's blue
     * over its last letters. A renderer without the brand sheet (the
     * development showcase) gets the word set plainly rather than nothing.
     */
    public static float wordmark(Renderer ui, float x, float y, float capHeight) {
        if (ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas) {
            return ((net.fathom.optimizations.fabric.gfx.UiCanvas) ui).wordmark(x, y, capHeight, BRAND_TINT);
        }
        ui.text(GraphiteTokens.WORDMARK_TEXT, x, y + capHeight, UI.at(capHeight * 1.4f), TEXT);
        return ui.measure(GraphiteTokens.WORDMARK_TEXT, UI.at(capHeight * 1.4f));
    }

    /**
     * The lockup, as the launcher's header sets it: the mark, then the
     * wordmark. {@code y} is the word's ascender top, as for
     * {@link #wordmark}; the mark is {@code BRAND_LOCKUP_MARK} ascenders tall,
     * centred on the ascender, so it reaches past the word above and below,
     * and stands {@code BRAND_LOCKUP_GAP} ascenders before it. A renderer
     * without the brand sheet gets the wordmark alone.
     *
     * @return its width
     */
    public static float lockup(Renderer ui, float x, float y, float capHeight) {
        if (!(ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas)) {
            return wordmark(ui, x, y, capHeight);
        }
        net.fathom.optimizations.fabric.gfx.UiCanvas canvas = (net.fathom.optimizations.fabric.gfx.UiCanvas) ui;
        float markHeight = capHeight * FathomTheme.Tokens.BRAND_LOCKUP_MARK;
        float markWidth = canvas.mark(x, y + (capHeight - markHeight) / 2f, markHeight, BRAND_TINT);
        float gap = capHeight * FathomTheme.Tokens.BRAND_LOCKUP_GAP;
        return markWidth + gap + canvas.wordmark(x + markWidth + gap, y, capHeight, BRAND_TINT);
    }

    /** The lockup's width at an ascender height, without drawing it. */
    public static float lockupWidth(Renderer ui, float capHeight) {
        if (!(ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas)) {
            return wordmarkWidth(ui, capHeight);
        }
        net.fathom.optimizations.fabric.gfx.UiCanvas canvas = (net.fathom.optimizations.fabric.gfx.UiCanvas) ui;
        return canvas.markWidth(capHeight * FathomTheme.Tokens.BRAND_LOCKUP_MARK)
                + capHeight * FathomTheme.Tokens.BRAND_LOCKUP_GAP
                + canvas.wordmarkWidth(capHeight);
    }

    /** The wordmark's width at an ascender height, without drawing it. */
    public static float wordmarkWidth(Renderer ui, float capHeight) {
        if (ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas) {
            return ((net.fathom.optimizations.fabric.gfx.UiCanvas) ui).wordmarkWidth(capHeight);
        }
        return ui.measure(GraphiteTokens.WORDMARK_TEXT, UI.at(capHeight * 1.4f));
    }
}
