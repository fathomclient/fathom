package net.fathom.optimizations.core.ui.style;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * The launcher's elevation shadows ({@code --shadow-sm} and its siblings),
 * as numbers: one CSS {@code box-shadow} each.
 */
public final class Elevation {

    public static final Elevation NONE = new Elevation(0f, 0f, 0f, 0f, 0);
    public static final Elevation SM = new Elevation(Tokens.SHADOW_SM_X, Tokens.SHADOW_SM_Y,
            Tokens.SHADOW_SM_BLUR, Tokens.SHADOW_SM_SPREAD, Tokens.SHADOW_SM_COLOUR);
    public static final Elevation MD = new Elevation(Tokens.SHADOW_MD_X, Tokens.SHADOW_MD_Y,
            Tokens.SHADOW_MD_BLUR, Tokens.SHADOW_MD_SPREAD, Tokens.SHADOW_MD_COLOUR);
    public static final Elevation LG = new Elevation(Tokens.SHADOW_LG_X, Tokens.SHADOW_LG_Y,
            Tokens.SHADOW_LG_BLUR, Tokens.SHADOW_LG_SPREAD, Tokens.SHADOW_LG_COLOUR);
    /** The switch and slider knob's shadow (its inset lip is {@link #KNOB_LIP}). */
    public static final Elevation KNOB = new Elevation(Tokens.SHADOW_KNOB_X, Tokens.SHADOW_KNOB_Y,
            Tokens.SHADOW_KNOB_BLUR, Tokens.SHADOW_KNOB_SPREAD, Tokens.SHADOW_KNOB_COLOUR);
    public static final int KNOB_LIP = Tokens.SHADOW_KNOB_INSET_COLOUR;

    public final float offsetX;
    public final float offsetY;
    public final float blur;
    public final float spread;
    public final int argb;

    public Elevation(float offsetX, float offsetY, float blur, float spread, int argb) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.blur = blur;
        this.spread = spread;
        this.argb = argb;
    }

    /** The token's level by name: {@code none}, {@code sm}, {@code md}, {@code lg}. */
    public static Elevation named(String name) {
        if ("sm".equals(name)) {
            return SM;
        }
        if ("md".equals(name)) {
            return MD;
        }
        if ("lg".equals(name)) {
            return LG;
        }
        return NONE;
    }

    public void paint(Renderer ui, float left, float top, float right, float bottom, float radius) {
        if ((argb >>> 24) == 0) {
            return;
        }
        ui.boxShadow(left, top, right, bottom, radius, blur, spread, offsetX, offsetY, argb);
    }

    /** The same shadow at a fraction of its strength. */
    public void paint(Renderer ui, float left, float top, float right, float bottom, float radius, float strength) {
        if ((argb >>> 24) == 0 || strength <= 0f) {
            return;
        }
        int alpha = Math.round((argb >>> 24) * Math.min(1f, strength));
        ui.boxShadow(left, top, right, bottom, radius, blur, spread, offsetX, offsetY,
                (alpha << 24) | (argb & 0xFFFFFF));
    }
}
