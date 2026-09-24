package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Shared structural primitives for Fathom's in-game application surfaces.
 *
 * <p>Controls own interaction; this class owns the quiet material hierarchy
 * around them. Keeping window, chrome, sidebar, card and section geometry here
 * prevents every page from inventing another almost-identical black panel.
 * None of these methods allocates textures or retains per-frame state.
 */
public final class FathomComponents {

    private FathomComponents() {}

    /** The single floating application window above the dimmed game. */
    public static void window(Renderer ui, Rect area) {
        VisualEffects.panelShadow(ui, area, Skin.R_PANEL);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_PANEL, Skin.BACKGROUND);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_PANEL,
                Skin.BORDER_STANDARD, Skin.EDGE_SURFACE);
    }

    /** Top chrome, with only the two outer corners rounded. */
    public static void topBar(Renderer ui, Rect area) {
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(area.x, area.y, area.right(), area.bottom(),
                    Skin.R_PANEL, Skin.R_PANEL, 0f, 0f, Skin.BACKGROUND);
        } else {
            ui.rounded(area.x, area.y, area.right(), area.y + Skin.R_PANEL * 2f,
                    Skin.R_PANEL, Skin.BACKGROUND);
            ui.fill(area.x, area.y + Skin.R_PANEL, area.right(), area.bottom(), Skin.BACKGROUND);
        }
        ui.fill(area.x + Skin.S5, area.bottom() - Skin.HAIRLINE,
                area.right() - Skin.S5, area.bottom(), Skin.EDGE_RECESSED);
    }

    /** Fixed labelled navigation column, with the window's lower-left corner. */
    public static void sidebar(Renderer ui, Rect area) {
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(area.x, area.y, area.right(), area.bottom(),
                    0f, 0f, 0f, Skin.R_PANEL, Skin.RECESSED);
        } else {
            ui.fill(area.x, area.y, area.right(), area.bottom() - Skin.R_PANEL, Skin.RECESSED);
            ui.rounded(area.x, area.bottom() - Skin.R_PANEL * 2f, area.right(), area.bottom(),
                    Skin.R_PANEL, Skin.RECESSED);
        }
        ui.fill(area.right() - Skin.HAIRLINE, area.y + Skin.S4,
                area.right(), area.bottom() - Skin.S4, Skin.EDGE_RECESSED);
    }

    /** A page card. Selection is a restrained edge and three-unit marker. */
    public static void card(Renderer ui, Rect area, float hover, float selected) {
        int face = Skin.mix(Skin.SURFACE, Skin.SURFACE_HOVER, hover * 0.72f);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, face);
        int edge = Skin.mix(Skin.EDGE_SURFACE,
                Skin.scaleAlpha(Skin.ACTIVE_RED, 0.72f), selected);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, edge);
        if (selected > 0.01f) {
            float height = Math.min(30f, area.height - Skin.S5 * 2f) * selected;
            Rect marker = Rect.of(area.x, area.centreY() - height / 2f,
                    area.x + 3f, area.centreY() + height / 2f);
            ui.rounded(marker.x, marker.y, marker.right(), marker.bottom(), Skin.pill(marker),
                    Skin.scaleAlpha(Skin.HOT_RED, selected));
        }
    }

    /** Elevated content surface used by drawers, summaries and inspectors. */
    public static void panel(Renderer ui, Rect area) {
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, Skin.ELEVATED);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, Skin.EDGE_SURFACE);
    }

    /** Opaque-enough overlay surface for settings drawers and transient panels. */
    public static void floatingPanel(Renderer ui, Rect area) {
        VisualEffects.overlayShadow(ui, area, Skin.R_CARD);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, Skin.FLOATING);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, Skin.EDGE_ELEVATED);
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
