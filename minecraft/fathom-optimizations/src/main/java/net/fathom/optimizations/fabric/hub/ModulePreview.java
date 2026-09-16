package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.hud.CrosshairShape;
import net.fathom.optimizations.core.module.CrosshairPresets;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * A live picture of what a module's settings currently produce.
 *
 * <h2>Why a module may have one and why almost none do</h2>
 *
 * <p>Most settings describe themselves: a toggle called "Seconds" adds seconds.
 * A crosshair does not — length, thickness, gap, dot and outline are five
 * numbers whose product you cannot picture, and the only way to choose them by
 * hand today is to close the menu, look, and open it again. So the crosshair
 * gets a preview and nothing else does, and the preview is a drawing, not a
 * picture: it asks {@link CrosshairShape} for the same rectangles the HUD draws,
 * so it cannot show something the game will not.
 *
 * <p>The drawer asks {@link #of} for a preview and draws whatever it gets. A
 * module without one simply has more room for its settings.
 */
interface ModulePreview {

    /** How tall the preview area is, in logical units. */
    float height();

    void draw(Renderer ui, FathomModule module, Rect area);

    /** The preview for a module, or null when it does not have one. */
    static ModulePreview of(FathomModule module) {
        return module != null && "crosshair".equals(module.id()) ? CROSSHAIR : null;
    }

    ModulePreview CROSSHAIR = new ModulePreview() {

        @Override
        public float height() {
            return 96f;
        }

        @Override
        public void draw(Renderer ui, FathomModule module, Rect area) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM, Skin.RECESSED);
            ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM,
                    Skin.BORDER_STANDARD, Skin.EDGE_RECESSED);

            String chosen = module.choice(CrosshairPresets.STYLE, "Cross");
            if ("Vanilla".equals(chosen)) {
                ui.text("Minecraft's own crosshair", area.x + Skin.S3,
                        area.centreY() + ui.ascent(Type.SMALL) / 2f, Type.SMALL, Skin.TEXT_MUTED);
                return;
            }

            CrosshairShape shape = SHAPE;
            shape.build(style(chosen),
                    (int) Math.round(module.number(CrosshairPresets.LENGTH, 4)),
                    (int) Math.round(module.number(CrosshairPresets.THICKNESS, 1)),
                    (int) Math.round(module.number(CrosshairPresets.GAP, 2)),
                    module.toggle(CrosshairPresets.DOT, false),
                    (int) Math.round(module.number(CrosshairPresets.DOT_SIZE, 1)),
                    module.toggle(CrosshairPresets.OUTLINE, true)
                            ? (int) Math.round(module.number(CrosshairPresets.OUTLINE_WIDTH, 1))
                            : 0);

            // Whole-number magnification, so the preview is the crosshair's own
            // pixels enlarged rather than a resampled blur of them.
            float room = Math.min(area.width - Skin.S5 * 2f, area.height - Skin.S5 * 2f);
            int scale = Math.max(1, Math.min(6, (int) (room / Math.max(1, shape.size()))));
            float size = shape.size() * scale;
            float left = area.centreX() - size / 2f;
            float top = area.centreY() - size / 2f;

            int alpha = (int) Math.round(module.number("opacity", 100)) * 255 / 100;
            int colour = Skin.argb(alpha / 255f, module.colour("colour", 0xFFFFFF));
            int outline = Skin.argb(alpha / 255f, module.colour("outline.colour", 0x000000));
            paint(ui, shape.outline(), shape.outlineCount(), left, top, scale, outline);
            paint(ui, shape.fill(), shape.fillCount(), left, top, scale, colour);

            ui.text(shape.size() + " x " + shape.size() + " px", area.x + Skin.S3,
                    area.bottom() - Skin.S3, Type.CAPTION, Skin.TEXT_DISABLED);
        }

        private void paint(Renderer ui, int[] rects, int count, float left, float top, int scale, int argb) {
            for (int index = 0; index < count; index += 4) {
                ui.fill(left + rects[index] * scale, top + rects[index + 1] * scale,
                        left + rects[index + 2] * scale, top + rects[index + 3] * scale, argb);
            }
        }

        private CrosshairShape.Style style(String chosen) {
            if ("Dot".equals(chosen)) {
                return CrosshairShape.Style.DOT;
            }
            if ("Circle".equals(chosen)) {
                return CrosshairShape.Style.CIRCLE;
            }
            if ("Tee".equals(chosen)) {
                return CrosshairShape.Style.TEE;
            }
            return CrosshairShape.Style.CROSS;
        }
    };

    /** One instance: the drawer draws one preview at a time, on one thread. */
    CrosshairShape SHAPE = new CrosshairShape();
}
