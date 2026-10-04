package net.fathom.optimizations.core.ui.style;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * The launcher's glass materials, exactly: what {@code .material-standard}
 * and its siblings in {@code theme.generated.css} are made of, in the order
 * CSS paints them.
 *
 * <pre>
 *   background-color: tint;
 *   backdrop-filter: blur(b) saturate(s) brightness(v);
 *   box-shadow: inset 0 1px 0 0 rimTop,      -- the lit top edge
 *               inset 0 0 0 1px rimEdge,     -- the even rim
 *               inset 0 -1px 0 0 rimShade,   -- the lip underneath
 *               elevation;                   -- the drop shadow
 * </pre>
 *
 * <p>CSS paints an element's outer shadow first, then the backdrop filter's
 * result, its background, and its inset shadows from the last listed to the
 * first. {@link #paint} does the same, one primitive each. The values come
 * from {@code assets/theme/fathom-theme.json} through the generated tokens, so
 * the launcher's CSS and this are one source.
 *
 * <p>The rims are one device pixel, as the launcher's {@code --hairline} is
 * one CSS pixel on the displays it targets.
 */
public final class Material {

    public static final Material CLEAR = new Material("clear", Tokens.MATERIAL_CLEAR_TINT,
            Tokens.MATERIAL_CLEAR_BLUR, Tokens.MATERIAL_CLEAR_SATURATE, Tokens.MATERIAL_CLEAR_BRIGHTNESS,
            Tokens.MATERIAL_CLEAR_RIM_TOP, Tokens.MATERIAL_CLEAR_RIM_EDGE, Tokens.MATERIAL_CLEAR_RIM_SHADE,
            Elevation.named(Tokens.MATERIAL_CLEAR_SHADOW));
    public static final Material LIGHT = new Material("light", Tokens.MATERIAL_LIGHT_TINT,
            Tokens.MATERIAL_LIGHT_BLUR, Tokens.MATERIAL_LIGHT_SATURATE, Tokens.MATERIAL_LIGHT_BRIGHTNESS,
            Tokens.MATERIAL_LIGHT_RIM_TOP, Tokens.MATERIAL_LIGHT_RIM_EDGE, Tokens.MATERIAL_LIGHT_RIM_SHADE,
            Elevation.named(Tokens.MATERIAL_LIGHT_SHADOW));
    public static final Material STANDARD = new Material("standard", Tokens.MATERIAL_STANDARD_TINT,
            Tokens.MATERIAL_STANDARD_BLUR, Tokens.MATERIAL_STANDARD_SATURATE, Tokens.MATERIAL_STANDARD_BRIGHTNESS,
            Tokens.MATERIAL_STANDARD_RIM_TOP, Tokens.MATERIAL_STANDARD_RIM_EDGE, Tokens.MATERIAL_STANDARD_RIM_SHADE,
            Elevation.named(Tokens.MATERIAL_STANDARD_SHADOW));
    public static final Material DENSE = new Material("dense", Tokens.MATERIAL_DENSE_TINT,
            Tokens.MATERIAL_DENSE_BLUR, Tokens.MATERIAL_DENSE_SATURATE, Tokens.MATERIAL_DENSE_BRIGHTNESS,
            Tokens.MATERIAL_DENSE_RIM_TOP, Tokens.MATERIAL_DENSE_RIM_EDGE, Tokens.MATERIAL_DENSE_RIM_SHADE,
            Elevation.named(Tokens.MATERIAL_DENSE_SHADOW));

    public final String name;
    /** {@code background-color} over the blurred backdrop. */
    public final int tint;
    /** {@code blur()}, CSS pixels: a Gaussian's standard deviation. */
    public final float blur;
    public final float saturate;
    public final float brightness;
    public final int rimTop;
    public final int rimEdge;
    public final int rimShade;
    public final Elevation shadow;

    private Material(String name, int tint, float blur, float saturate, float brightness,
            int rimTop, int rimEdge, int rimShade, Elevation shadow) {
        this.name = name;
        this.tint = tint;
        this.blur = blur;
        this.saturate = saturate;
        this.brightness = brightness;
        this.rimTop = rimTop;
        this.rimEdge = rimEdge;
        this.rimShade = rimShade;
        this.shadow = shadow;
    }

    /** The whole material, at rest. */
    public void paint(Renderer ui, float left, float top, float right, float bottom, float radius) {
        paint(ui, left, top, right, bottom, radius, tint, true);
    }

    /**
     * The whole material with another background colour (a hover or a
     * selection deepens it), with or without its elevation shadow.
     */
    public void paint(Renderer ui, float left, float top, float right, float bottom, float radius,
            int background, boolean elevated) {
        if (elevated) {
            shadow.paint(ui, left, top, right, bottom, radius);
        }
        ui.glass(left, top, right, bottom, radius, blur, saturate, brightness);
        ui.rounded(left, top, right, bottom, radius, background);
        rims(ui, left, top, right, bottom, radius);
    }

    /** The three inset rims alone. */
    public void rims(Renderer ui, float left, float top, float right, float bottom, float radius) {
        float px = ui.pixel();
        ui.inset(left, top, right, bottom, radius, 0f, -px, 0f, rimShade);
        ui.inset(left, top, right, bottom, radius, 0f, 0f, px, rimEdge);
        ui.inset(left, top, right, bottom, radius, 0f, px, 0f, rimTop);
    }

    /** The next density up: what selection turns a floating surface into. */
    public Material denser() {
        if (this == CLEAR) {
            return LIGHT;
        }
        if (this == LIGHT) {
            return STANDARD;
        }
        return DENSE;
    }
}
