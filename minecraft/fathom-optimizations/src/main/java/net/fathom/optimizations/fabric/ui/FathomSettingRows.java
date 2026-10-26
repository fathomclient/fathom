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
     * One setting row height, described or not: 36 in the RShift window
     * (theme game.graphite.layout.setting-row). A row with a detail line used
     * to be taller than one without, so two switches in one group sat in rows
     * of different heights; identical rows are identical.
     */
    public static final float ROW = net.fathom.optimizations.generated.GraphiteTokens.LAYOUT_SETTING_ROW;
    /** Kept for callers; the same height. */
    public static final float COMPACT_ROW = ROW;
    /** A group's heading: one line, and the space down to its rows. */
    public static final float SECTION_HEADING = 28f;

    private FathomSettingRows() {}

    /**
     * The surface that owns a group's rows: a faint fill and a hairline, not a
     * pane of glass. The RShift window is the glass; a second frosted pane
     * inside it was glass on glass.
     */
    public static void surface(Renderer ui, Rect area) {
        float radius = Skin.radius(Skin.R_CARD, area);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), radius,
                FathomTheme.Tokens.CONTROL_FILL_FAINT);
        ui.rim(area.x, area.y, area.right(), area.bottom(), radius, Skin.EDGE_RECESSED);
    }

    /**
     * A group's name, and after it on the same line an optional hint in the
     * detail style (how keybinds are edited, say). One line, so every heading
     * is the same height.
     */
    public static void heading(Renderer ui, Rect area, String title, String description) {
        Rect line = new Rect(area.x, area.y, area.width, SECTION_HEADING - Skin.S2);
        float baseline = Controls.baseline(ui, line, TITLE);
        ui.text(title, area.x, baseline, TITLE, Skin.TEXT);
        if (description != null && !description.isEmpty()) {
            float x = area.x + ui.measure(title, TITLE) + Skin.S3;
            ui.text(Text.fit(ui, description, DETAIL, Math.max(0f, area.right() - x)), x, baseline,
                    DETAIL, Skin.TEXT_FAINT);
        }
    }

    /** A setting's name: the row title (14, its full text colour), as the launcher's. */
    public static final Type TITLE = Type.ROW_TITLE;
    /** The line under it: 12, secondary text -- the game's one-size-smaller detail. */
    public static final Type DETAIL = Type.SMALL;

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
        return description == null || description.isEmpty() ? ROW : DESCRIBED_ROW;
    }

    /**
     * A row with a detail line: 44, so the two lines keep clear of the
     * dividers either side. Every row of one kind is still the same height.
     */
    public static final float DESCRIBED_ROW = ROW + FathomTheme.Metrics.SPACE_2;

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
