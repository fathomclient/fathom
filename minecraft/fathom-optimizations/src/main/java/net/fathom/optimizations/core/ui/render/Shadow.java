package net.fathom.optimizations.core.ui.render;

import net.fathom.optimizations.core.ui.Colour;

/**
 * A drop shadow, as a value.
 *
 * <p>One soft falloff, not a blur: nothing reads the framebuffer, and a shadow
 * costs one gradient fill (a few more when it has to be cut out; see
 * {@link #cutout}). Deliberately a small set of presets, for the same reason
 * {@link Type} has five sizes -- a shadow per component is how depth stops
 * meaning anything.
 */
public final class Shadow {

    /** A panel resting on the world: barely there. */
    public static final Shadow PANEL = new Shadow(18f, 0f, 0f, 6f, 0x59000000, true);
    /** Something floating above a panel: a dropdown, a tooltip. */
    public static final Shadow DROPDOWN = new Shadow(24f, 2f, 0f, 10f, 0x80000000, true);
    /** A modal over a scrim: the deepest thing on screen. */
    public static final Shadow MODAL = new Shadow(48f, 6f, 0f, 20f, 0xA6000000, true);

    private final float blur;
    private final float spread;
    private final float offsetX;
    private final float offsetY;
    private final int argb;
    private final boolean cutout;

    /**
     * @param blur    width of the falloff, in logical units
     * @param spread  how far the shadow's solid part grows past the shape
     * @param offsetX horizontal displacement
     * @param offsetY vertical displacement: light comes from above
     * @param argb    colour at full strength
     * @param cutout  whether the part under the shape is left out, so a
     *                translucent shape does not show its own shadow through it
     */
    public Shadow(float blur, float spread, float offsetX, float offsetY, int argb, boolean cutout) {
        this.blur = Math.max(0f, blur);
        this.spread = spread;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.argb = argb;
        this.cutout = cutout;
    }

    /** The same shadow at a fraction of its strength, for one that fades in. */
    public Shadow withStrength(float factor) {
        return new Shadow(blur, spread, offsetX, offsetY, Colour.scaleAlpha(argb, factor), cutout);
    }

    /** The same shadow in another colour, for a glow. */
    public Shadow coloured(int newArgb) {
        return new Shadow(blur, spread, offsetX, offsetY, newArgb, cutout);
    }

    public float blur() {
        return blur;
    }

    public float spread() {
        return spread;
    }

    public float offsetX() {
        return offsetX;
    }

    public float offsetY() {
        return offsetY;
    }

    public int argb() {
        return argb;
    }

    public boolean cutout() {
        return cutout;
    }

    /** How far past the shape this shadow can reach, for culling and clipping. */
    public float reach() {
        return blur + Math.max(0f, spread) + Math.max(Math.abs(offsetX), Math.abs(offsetY));
    }
}
