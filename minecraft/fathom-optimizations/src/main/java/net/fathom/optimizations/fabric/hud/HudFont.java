package net.fathom.optimizations.fabric.hud;

/**
 * The text a HUD widget draws, without saying which font draws it.
 *
 * <h2>Why the HUD needed an abstraction and the hub did not</h2>
 *
 * <p>Fathom's screens draw in Fathom's own coordinate space, so they could
 * simply use Fathom's renderer. The HUD cannot: it is drawn inside Minecraft's
 * own HUD pass, in Minecraft's scaled-GUI space, and everything around it —
 * the saved layout, the editor's hit boxes, the snapping, the plate behind each
 * widget — is measured in that space against {@code TextRenderer.fontHeight},
 * which is nine.
 *
 * <p>So the unit of this interface is <em>Minecraft's scaled pixel</em>, in
 * every implementation. A widget asks how wide a string is and gets an answer
 * in the same units its position is stored in, whichever font is underneath.
 * That is what let the typeface change without touching a single saved layout,
 * the snapping arithmetic, or the editor.
 *
 * <h2>Both implementations are real</h2>
 *
 * <p>Minecraft's font is the fresh default because HUD modules live over a
 * resource-pack-owned game surface. Fathom's typeface remains an independent,
 * profile-persisted choice on every module. It is not a global UI switch: the
 * Hub and other Fathom application surfaces continue to use Fathom typography.
 *
 * @see HudFonts
 */
public interface HudFont {

    /** How wide the string draws, in Minecraft's scaled pixels. */
    float width(String text);

    /** One line's height, in Minecraft's scaled pixels. */
    float lineHeight();

    /**
     * Draw one line.
     *
     * @param x    left edge, in Minecraft's scaled pixels
     * @param top  top edge, in Minecraft's scaled pixels — not the baseline, because
     *             the widgets stack lines downwards and never knew what a baseline was
     * @param argb the colour, with the widget's opacity already in the alpha
     * @param shadow whether to draw the offset dark copy underneath
     */
    void draw(String text, float x, float top, int argb, boolean shadow);

    /** True when this is Fathom's typeface rather than Minecraft's. */
    boolean fathom();

    /**
     * The height of a capital letter, in Minecraft's scaled pixels.
     *
     * <p>What to centre on. {@link #lineHeight} includes the space below the
     * baseline and above the capitals, and centring a label in a key by it
     * leaves the letters visibly high or low depending on the font.
     */
    float capHeight();

    /** Distance from the {@code top} passed to {@link #draw} down to the top of a capital. */
    float capTop();

    /** The {@code top} that centres a capital letter in a box starting at {@code boxTop}. */
    default float centredTop(float boxTop, float boxHeight) {
        return boxTop + (boxHeight - capHeight()) / 2f - capTop();
    }
}
