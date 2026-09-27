package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
import net.fathom.optimizations.generated.BrandGeometry;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * Fathom's brand in the game: the Surface wordmark and the Sounding Line.
 *
 * <p>The same identity the launcher shows, from the same geometry. The letter
 * outlines are generated from the launcher's {@code surface.ts} into
 * {@link BrandGeometry}; the renderer rasterises them once per size, snapped to
 * whole pixels the way the launcher's wordmark is, and draws the result as one
 * quad. Nothing here knows what a letter looks like.
 *
 * <h2>What is not here</h2>
 *
 * <p>The Aperture mark and the tracked "FATHOM" set in Geist are retired. So is
 * the offset ring as an in-game mark: at the sizes the game draws a mark it is
 * too coarse to read, and the Sounding Line -- one stem stepping right where it
 * crosses the surface -- is the small form of the brand instead.
 *
 * <h2>Sizes</h2>
 *
 * <p>A wordmark is asked for by its cap height in logical units. At the
 * launcher's thresholds (in device pixels) it carries the full refraction and
 * the bronze edge, a one-pixel refraction, or the letters alone. A caller
 * placing it by its top edge gets exactly the cap height of ink; there is no
 * ascender or descender to allow for.
 */
public final class BrandMark {

    /** Width over cap height: the whole word, in the brand's own units. */
    public static final float ASPECT = BrandGeometry.WORDMARK_UNITS / Tokens.BRAND_CAP;

    /** The surface's height above the baseline, as a fraction of the cap. */
    public static final float SURFACE = Tokens.BRAND_SURFACE / Tokens.BRAND_CAP;

    /** The plain word, for a renderer that has no brand sheet. */
    private static final Type FALLBACK = new Type(14f, Type.Weight.SEMIBOLD);

    private BrandMark() {}

    /** The wordmark's width at a cap height, both in logical units. */
    public static float wordmarkWidth(Renderer ui, float capHeight) {
        if (ui instanceof GlRenderer) {
            return ((GlRenderer) ui).wordmarkWidth(capHeight);
        }
        return capHeight * ASPECT;
    }

    /**
     * The Surface wordmark with its top-left at {@code x, y}.
     *
     * @return the width drawn, in logical units
     */
    public static float wordmark(Renderer ui, float x, float y, float capHeight) {
        if (ui instanceof GlRenderer) {
            return ((GlRenderer) ui).wordmark(x, y, capHeight);
        }
        // A renderer without the brand sheet (the development showcase) gets
        // the word, set plainly, rather than nothing.
        ui.text("FATHOM", x, y + capHeight, FALLBACK.at(capHeight / 0.71f), Tokens.BRAND_AIR);
        return capHeight * ASPECT;
    }

    /**
     * The Sounding Line, top-left at {@code x, y}, in one ink.
     *
     * @return the width drawn, in logical units
     */
    public static float soundingLine(Renderer ui, float x, float y, float height, int argb) {
        if (ui instanceof GlRenderer) {
            return ((GlRenderer) ui).soundingLine(x, y, height, argb);
        }
        float stem = Math.max(2f, height * Tokens.BRAND_STEM / Tokens.BRAND_CAP);
        ui.rounded(x, y, x + stem, y + height, stem / 2f, argb);
        return stem;
    }

}
