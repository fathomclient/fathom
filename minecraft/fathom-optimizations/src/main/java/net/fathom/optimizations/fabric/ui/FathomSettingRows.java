package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.generated.FathomTheme;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Shared presentation for a group of related settings.
 *
 * <p>A section is one rounded surface. Rows inside it are deliberately not
 * cards: the label and explanation form one quiet column and the control forms
 * the other. This keeps a long configuration page readable without turning
 * every value into a separate floating rectangle.
 */
public final class FathomSettingRows {

    /**
     * One setting row height, described or not: {@code Metrics.ROW}. A row
     * with a detail line used to be 64 and one without 54, so two switches in
     * one group sat in rows of different heights.
     */
    public static final float ROW = FathomTheme.Metrics.ROW;
    /** Kept for callers; the same height -- identical rows are identical. */
    public static final float COMPACT_ROW = ROW;
    public static final float SECTION_HEADING = 43f;

    private FathomSettingRows() {}

    /** Draw the single surface that owns all rows in a semantic section. */
    public static void surface(Renderer ui, Rect area) {
        FathomComponents.panel(ui, area);
    }

    /** Draw a section name and its optional one-line explanation. */
    public static void heading(Renderer ui, Rect area, String title, String description) {
        ui.text(title, area.x, Controls.stackTitle(ui, area.y, Type.ROW_TITLE),
                Type.ROW_TITLE, Skin.TEXT);
        if (description != null && !description.isEmpty()) {
            ui.text(Text.fit(ui, description, Type.SMALL, area.width), area.x,
                    Controls.stackDetail(ui, area.y, Type.ROW_TITLE, Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        }
    }

    /** A setting's name: the launcher's row title (14, its full text colour). */
    public static final Type TITLE = Type.ROW_TITLE;
    /** The line under it: the launcher's row detail (13, secondary text). */
    public static final Type DETAIL = Type.BODY;

    /**
     * A setting's name and its optional detail line, in the one type scale
     * every settings row in the Hub uses -- the launcher's settings row, so a
     * setting reads the same in both. Centred on the row as a block.
     *
     * @param room the width the text may take before it is shortened
     */
    public static void label(Renderer ui, Rect row, float x, float room, String title, String detail,
            boolean enabled) {
        int titleColour = enabled ? Skin.TEXT : Skin.TEXT_DISABLED;
        int detailColour = enabled ? Graphite.TEXT_SECONDARY : Skin.scaleAlpha(Skin.TEXT_DISABLED, 0.72f);
        boolean described = detail != null && !detail.isEmpty();
        if (!described) {
            ui.text(Text.fit(ui, title, TITLE, room), x, Controls.baseline(ui, row, TITLE), TITLE, titleColour);
            return;
        }
        float top = Controls.stackTop(ui, row, TITLE, DETAIL);
        ui.text(Text.fit(ui, title, TITLE, room), x, Controls.stackTitle(ui, top, TITLE), TITLE, titleColour);
        ui.text(Text.fit(ui, detail, DETAIL, room), x, Controls.stackDetail(ui, top, TITLE, DETAIL),
                DETAIL, detailColour);
    }

    /** Height of a setting row; explained values get room for a second line. */
    public static float height(String description) {
        return ROW;
    }

    /**
     * Draw a setting row and return the rectangle reserved for its control.
     * The divider belongs to the section, not to either adjacent row.
     */
    public static Rect row(
            Renderer ui,
            Controls controls,
            String id,
            Rect area,
            String label,
            String description,
            float controlWidth,
            float controlHeight,
            boolean enabled,
            boolean hovered,
            boolean last) {
        hovered = enabled && hovered;
        float response = controls.towards(id + "#row", hovered, net.fathom.optimizations.core.ui.anim.Timing.HOVER_IN);
        if (response > 0.001f) {
            // Concentric with the group: its radius, less the inset.
            Rect wash = Rect.of(area.x + Skin.S1, area.y + Skin.S1,
                    area.right() - Skin.S1, area.bottom() - Skin.S1);
            ui.rounded(wash.x, wash.y, wash.right(), wash.bottom(),
                    Skin.radius(Skin.R_CARD - Skin.S1, wash),
                    Skin.scaleAlpha(Skin.FILL_HOVER, response));
        }

        float controlX = area.right() - Skin.CARD_PADDING - controlWidth;
        float textX = area.x + Skin.CARD_PADDING;
        float textRoom = Math.max(0f, controlX - Skin.S5 - textX);
        label(ui, area, textX, textRoom, label, description, enabled);

        if (!last) {
            FathomComponents.divider(ui, area.x + Skin.CARD_PADDING,
                    area.right() - Skin.CARD_PADDING, area.bottom());
        }
        return new Rect(controlX, area.centreY() - controlHeight / 2f,
                controlWidth, controlHeight);
    }
}
