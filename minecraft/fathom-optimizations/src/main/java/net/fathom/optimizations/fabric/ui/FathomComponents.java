package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Shared structural primitives for Fathom's in-game application surfaces.
 *
 * <p>Controls own interaction; this class owns the quiet material hierarchy
 * around them. There is no application window: the background -- the world
 * under a light scrim, or the photograph -- is the page, and navigation and
 * content float over it as separate groups of transparent glass, the way the
 * launcher's rail, selectors and Play control float over its photograph. Rows
 * inside a group are not cards. Only what floats over everything is dense.
 * None of these methods allocates textures or retains per-frame state.
 */
public final class FathomComponents {

    private FathomComponents() {}

    /**
     * A floating group of light glass: the rail, a page's list, a group of
     * settings. The launcher's `material-light` pane, without its blur.
     */
    public static void group(Renderer ui, Rect area, float radius) {
        VisualEffects.glass(ui, area, Skin.radius(radius, area), Skin.MATERIAL_LIGHT,
                Skin.RIM_TOP_LIGHT, Skin.RIM_EDGE_LIGHT);
    }

    /**
     * A card: light glass, standard when it is the selected one, with the
     * control fill's ivory lift under the pointer. Selection is density and a
     * brighter rim, no marker.
     */
    public static void card(Renderer ui, Rect area, float hover, float selected) {
        float radius = Skin.radius(Skin.R_CARD, area);
        int face = Skin.mix(Skin.MATERIAL_LIGHT, Skin.MATERIAL_STANDARD, selected);
        int edge = Skin.mix(Skin.RIM_EDGE_LIGHT, Skin.EDGE_ELEVATED, Math.max(hover * 0.5f, selected));
        VisualEffects.glass(ui, area, radius, face, Skin.RIM_TOP_LIGHT, edge);
        if (hover > 0.01f) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), radius,
                    Skin.scaleAlpha(Skin.FILL_HOVER, hover));
        }
    }

    /** A grouped content surface: light glass at the card radius. */
    public static void panel(Renderer ui, Rect area) {
        group(ui, area, Skin.R_CARD);
    }

    /** Dense glass for drawers and transient panels over everything. */
    public static void floatingPanel(Renderer ui, Rect area) {
        VisualEffects.overlayShadow(ui, area, Skin.R_CARD);
        VisualEffects.glass(ui, area, Skin.radius(Skin.R_CARD, area), Skin.FLOATING,
                Skin.RIM_TOP_DENSE, Skin.RIM_EDGE_DENSE);
    }

    /** Consistent page heading and optional supporting sentence. */
    public static Rect sectionHeader(Renderer ui, Rect area, String title, String subtitle) {
        float height = subtitle == null || subtitle.isEmpty() ? 30f : 52f;
        Rect heading = area.top(height);
        ui.text(title, heading.x, heading.y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        if (subtitle != null && !subtitle.isEmpty()) {
            ui.text(Text.fit(ui, subtitle, Type.SMALL, heading.width), heading.x,
                    heading.y + 30f + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_MUTED);
        }
        return heading;
    }

    /** A divider for actual structural separation, never for decorating text. */
    public static void divider(Renderer ui, float left, float right, float y) {
        ui.fill(left, y, right, y + ui.pixel(), Skin.EDGE_RECESSED);
    }
}
