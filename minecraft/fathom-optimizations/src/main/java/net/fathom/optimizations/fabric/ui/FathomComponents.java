package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Shared structural primitives for Fathom's in-game application surfaces.
 *
 * <p>Controls own interaction; this class owns the quiet material hierarchy
 * around them. One pane of standard glass is the window; the header and the
 * rail are regions of it, divided by hairlines, not further panes stacked on
 * top -- every translucent layer over another makes the one below it muddier.
 * Cards inside the window are flat control fills. Only what floats over
 * everything is dense glass. None of these methods allocates textures or
 * retains per-frame state.
 */
public final class FathomComponents {

    private FathomComponents() {}

    /** The single floating application window: standard glass above the scrimmed game. */
    public static void window(Renderer ui, Rect area) {
        VisualEffects.panelShadow(ui, area, Skin.R_PANEL);
        VisualEffects.glass(ui, area, Skin.R_PANEL, Skin.MATERIAL_STANDARD,
                Skin.RIM_TOP, Skin.RIM_EDGE);
    }

    /** The header region of the window: no fill of its own, a hairline under it. */
    public static void topBar(Renderer ui, Rect area) {
        ui.fill(area.x + Skin.S5, area.bottom() - Skin.HAIRLINE,
                area.right() - Skin.S5, area.bottom(), Skin.EDGE_RECESSED);
    }

    /** The navigation region of the window: no fill of its own, a hairline beside it. */
    public static void sidebar(Renderer ui, Rect area) {
        ui.fill(area.right() - Skin.HAIRLINE, area.y + Skin.S4,
                area.right(), area.bottom() - Skin.S4, Skin.EDGE_RECESSED);
    }

    /**
     * A page card: a flat fill inside the window, not a pane of its own.
     * Selection is a denser fill and a brighter rim -- no marker bar.
     */
    public static void card(Renderer ui, Rect area, float hover, float selected) {
        int face = Skin.mix(Skin.mix(Skin.SURFACE, Skin.SURFACE_HOVER, hover * 0.72f),
                Skin.ELEVATED, selected * 0.6f);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, face);
        int edge = Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, Math.max(hover * 0.5f, selected));
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, edge);
    }

    /** A grouped content surface inside the window: the flat fill, the faint rim. */
    public static void panel(Renderer ui, Rect area) {
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, Skin.SURFACE);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, Skin.EDGE_SURFACE);
    }

    /** Dense glass for drawers and transient panels over the window. */
    public static void floatingPanel(Renderer ui, Rect area) {
        VisualEffects.overlayShadow(ui, area, Skin.R_CARD);
        VisualEffects.glass(ui, area, Skin.R_CARD, Skin.FLOATING,
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
        ui.fill(left, y, right, y + Skin.HAIRLINE, Skin.EDGE_RECESSED);
    }
}
