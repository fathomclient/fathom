package net.fathom.optimizations.fabric.hud;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/** One shared, batched HUD substrate for text and bespoke modules alike. */
final class HudSurface {

    private HudSurface() {}

    static void draw(FathomModule module, int x, int y, int width, int height, int alpha) {
        HudMaterial material = HudMaterial.named(HudStyle.material(module));
        int strength = HudStyle.backgroundAlpha(module);
        int fillAlpha = material.fillAlpha() * strength / 255 * alpha / 255;
        if (fillAlpha > 0) {
            Plate.draw(x, y, width, height,
                    (fillAlpha << 24) | (HudStyle.backgroundColour(module) & 0x00FFFFFF),
                    HudStyle.radius(module), HudStyle.dropShadow(module));
        }

        int rimAlpha = material.rimAlpha() * strength / 255 * alpha / 255;
        int rimColour = HudStyle.borderColour(module);
        int rimWidth = 1;
        if (HudStyle.border(module)) {
            rimAlpha = alpha;
            rimWidth = HudStyle.borderWidth(module);
        }
        if (rimAlpha > 0) {
            Plate.border(x, y, width, height, HudStyle.radius(module), rimWidth,
                    (rimAlpha << 24) | (rimColour & 0x00FFFFFF));
        }
    }
}
