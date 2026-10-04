package net.fathom.optimizations.fabric.gfx;

import net.fathom.optimizations.core.ui.render.Renderer;

/**
 * What Fathom's screens draw with beyond {@link Renderer}: the brand, the
 * game's textures, the backdrop for glass, and the interface's case.
 *
 * <p>Both renderers implement it -- the GPU compositor ({@link SdfRenderer})
 * and the coverage-texture renderer it replaces ({@link GlRenderer}), which
 * the HUD still draws through -- so a screen asks for this and never for a
 * concrete class.
 */
public interface UiCanvas extends Renderer {

    /** The wordmark's ink width in logical units at an ascender height. */
    float wordmarkWidth(float capHeight);

    /** The wordmark, its ascender's top-left ink at x, y. Returns the ink width. */
    float wordmark(float x, float y, float capHeight, int argb);

    /** The Aperture lockup, mark and word. Returns its width. */
    float lockup(float x, float y, float capHeight, int frameArgb, int planeArgb);

    /** The lockup's height in logical units. */
    float lockupHeight(float capHeight);

    /** A whole texture in a rounded rectangle: the account's head, as a circle. */
    void roundedImage(int textureId, float left, float top, float right, float bottom, float radius);

    /**
     * Everything drawn so far is the backdrop: glass from here on frosts it.
     * The compositor takes this as the end of {@code Layer.BACKGROUND}.
     */
    void captureFrost();

    /** Glass at the interface's standard strength; see {@link Renderer#glass}. */
    boolean frost(float left, float top, float right, float bottom, float radius);

    /** Centred display lettering with explicit tracking, logical units. */
    float textTrackedCentred(String text, float centreX, float baselineY,
            net.fathom.optimizations.core.ui.render.Type type, float tracking, int argb);

    /** Lowercase interface text, the launcher's default. */
    void setLowercase(boolean on);
}
