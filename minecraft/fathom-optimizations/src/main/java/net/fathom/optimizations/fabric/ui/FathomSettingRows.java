package net.fathom.optimizations.fabric.ui;

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

    public static final float ROW = 64f;
    public static final float COMPACT_ROW = 54f;
    public static final float SECTION_HEADING = 43f;

    private FathomSettingRows() {}

    /** Draw the single surface that owns all rows in a semantic section. */
    public static void surface(Renderer ui, Rect area) {
        FathomComponents.panel(ui, area);
    }

    /** Draw a section name and its optional one-line explanation. */
    public static void heading(Renderer ui, Rect area, String title, String description) {
        ui.text(title, area.x, area.y + ui.ascent(Type.ROW_TITLE),
                Type.ROW_TITLE, Skin.TEXT);
        if (description != null && !description.isEmpty()) {
            ui.text(Text.fit(ui, description, Type.SMALL, area.width), area.x,
                    area.y + 20f + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        }
    }

    /** Height of a setting row; explained values get room for a second line. */
    public static float height(String description) {
        return description == null || description.isEmpty() ? COMPACT_ROW : ROW;
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
        int titleColour = enabled
                ? Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, response * 0.72f)
                : Skin.TEXT_DISABLED;
        int descriptionColour = enabled ? Skin.TEXT_FAINT : Skin.scaleAlpha(Skin.TEXT_DISABLED, 0.72f);

        float titleY = description == null || description.isEmpty()
                ? Controls.baseline(ui, area, Type.BODY_STRONG)
                : area.y + 17f + ui.ascent(Type.BODY_STRONG) / 2f;
        ui.text(Text.fit(ui, label, Type.BODY_STRONG, textRoom), textX,
                titleY, Type.BODY_STRONG, titleColour);
        if (description != null && !description.isEmpty()) {
            ui.text(Text.fit(ui, description, Type.SMALL, textRoom), textX,
                    area.y + 34f + ui.ascent(Type.SMALL), Type.SMALL, descriptionColour);
        }

        if (!last) {
            FathomComponents.divider(ui, area.x + Skin.CARD_PADDING,
                    area.right() - Skin.CARD_PADDING, area.bottom());
        }
        return new Rect(controlX, area.centreY() - controlHeight / 2f,
                controlWidth, controlHeight);
    }
}
