package net.fathom.optimizations.fabric.hud;

/**
 * Gameplay-safe equivalents of Fathom's glass materials.
 *
 * <p>These are alpha budgets, not effects: no framebuffer sampling, blur,
 * shadow pass or animated shader is involved. A material is one tinted plate
 * plus, where useful, one batched rim.
 */
enum HudMaterial {
    CLEAR("None", 0, 0),
    LIGHT("Light", 34, 34),
    STANDARD("Standard", 56, 48),
    DENSE("Dense", 82, 66);

    private final String label;
    private final int fillAlpha;
    private final int rimAlpha;

    HudMaterial(String label, int fillAlpha, int rimAlpha) {
        this.label = label;
        this.fillAlpha = fillAlpha;
        this.rimAlpha = rimAlpha;
    }

    int fillAlpha() {
        return fillAlpha;
    }

    int rimAlpha() {
        return rimAlpha;
    }

    static HudMaterial named(String value) {
        for (HudMaterial material : values()) {
            if (material.label.equals(value)) {
                return material;
            }
        }
        return STANDARD;
    }
}
